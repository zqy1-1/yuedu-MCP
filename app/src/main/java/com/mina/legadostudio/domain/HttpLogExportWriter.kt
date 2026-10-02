package com.mina.legadostudio.domain

import com.mina.legadostudio.data.db.HttpLogEntity
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * HTTP 日志流式写出器：与 [LogMultiFormatExporter] 同一输出格式，
 * 但行不整载内存——调用方按批喂入实体（见 [HttpLogExportPlan.forEachEntityBatch]），
 * 逐行 append 到 [Writer]，全程只有一批正文驻留。
 * TXT 按锚点分节（节头桶 key 升序），JSON/CSV/HTML 平铺带归属列；批内按 createdAt 升序。
 */
class HttpLogExportWriter(
    private val out: Writer,
    private val title: String,
    private val format: LogExportFormat,
    private val redact: Boolean,
    private val groupByAnchor: Boolean,
    private val anchors: List<String>,
    private val contextAnchors: Map<String, String>,
    private val bucketLabel: (String) -> String = HttpLogExportPlan::defaultBucketLabel,
) {
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val keys = COLUMNS.map { it.first }
    private val labels = COLUMNS.map { it.second }
    private var started = false
    private var jsonRowCount = 0
    private var totalRows = 0
    private var currentBucket: String? = null
    /** TXT 分节：待写节头的桶（桶 key, 条数），该桶首行写出时补上真实归属依据。 */
    private var pendingBucketHeader: Pair<String, Int>? = null

    /** 打开文件：写头部（JSON `[`、CSV BOM+表头、HTML 骨架头、TXT 首行标题）。 */
    fun begin(expectedCount: Int) {
        check(!started) { "begin() 只能调用一次" }
        started = true
        when (format) {
            LogExportFormat.JSON -> out.write("[\n")
            LogExportFormat.CSV -> {
                out.write(BOM)
                out.write(labels.joinToString(",") { LogMultiFormatExporter.escapeCsv(it) })
                out.write("\r\n")
            }
            LogExportFormat.HTML -> {
                out.write("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"utf-8\">\n")
                out.write("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                out.write("<title>")
                out.write(LogMultiFormatExporter.escapeHtml(title))
                out.write("</title>\n<style>")
                out.write(HTML_STYLE)
                out.write("</style>\n</head>\n<body>\n<h1>")
                out.write(LogMultiFormatExporter.escapeHtml(title))
                out.write("</h1>\n<p class=\"meta\">")
                out.write(LogMultiFormatExporter.escapeHtml("共 $expectedCount 条 · 导出于 ${time.format(Date())}"))
                out.write("</p>\n<div class=\"wrap\"><table>\n<thead><tr>")
                labels.forEach { out.write("<th>"); out.write(LogMultiFormatExporter.escapeHtml(it)); out.write("</th>") }
                out.write("</tr></thead>\n<tbody>\n")
            }
            LogExportFormat.TEXT -> out.write("# $title 共 $expectedCount 条（导出 ${time.format(Date())}）\n")
        }
    }

    /**
     * TXT 分节模式专用：开始一个桶节（桶 key 升序由调用方保证）。
     * 节头「标题（N 条 · kind · 置信度）+ 依据行」在该桶第一行写出时补全
     * （kind/置信度/依据取首行真实归属判定，不用占位合成），与内存版
     * [LogExportFormatter.httpGrouped] 同结构。
     */
    fun beginBucket(bucket: String, count: Int) {
        check(started) { "先调用 begin()" }
        check(format == LogExportFormat.TEXT && groupByAnchor) { "beginBucket 只在 TXT 分节模式下可用" }
        pendingBucketHeader = bucket to count
        currentBucket = bucket
    }

    /** 喂入一批实体（同一桶内的一批，或平铺模式的一批）；内部按 createdAt 升序逐行写出。 */
    fun writeBatch(logs: List<HttpLogEntity>) {
        check(started) { "先调用 begin()" }
        logs.sortedBy { it.createdAt }.forEach { raw ->
            // 归属判定用原始记录：脱敏会改写 url/Referer 等判定证据，必须在 redact 之前做
            val d = HttpLogAttributor.attribute(raw, anchors, contextAnchors)
            val log = if (redact) LogMultiFormatExporter.redactHttp(raw) else raw
            if (format == LogExportFormat.TEXT) {
                val pending = pendingBucketHeader
                if (pending != null && pending.first == currentBucket) {
                    out.write("\n## ${bucketLabel(pending.first)}（${pending.second} 条 · ${d.kind} · 置信度 ${d.confidence}）\n")
                    out.write("# 依据：${d.evidence}\n")
                    pendingBucketHeader = null
                }
                writeTextRow(log)
            } else {
                writeTabularRow(rowOf(log, d))
            }
            totalRows++
        }
    }

    /** 收尾：JSON `]`、HTML 骨架尾、TXT 无尾巴。 */
    fun end() {
        when (format) {
            LogExportFormat.JSON -> out.write(if (jsonRowCount == 0) "]" else "\n]")
            LogExportFormat.HTML -> {
                if (totalRows == 0) {
                    out.write("<tr><td colspan=\"")
                    out.write(labels.size.toString())
                    out.write("\">")
                    out.write(LogMultiFormatExporter.escapeHtml("无记录"))
                    out.write("</td></tr>\n")
                }
                out.write("</tbody>\n</table></div>\n</body>\n</html>\n")
            }
            else -> Unit
        }
    }

    private fun rowOf(log: HttpLogEntity, d: HttpLogAttributor.Decision): List<Any> = listOf(
        log.id, time.format(Date(log.createdAt)), log.method, log.url, log.finalUrl,
        log.statusCode, log.durationMs, log.error, log.redirectChain,
        d.anchor ?: HttpLogAttributor.UNATTRIBUTED_KEY, "${d.kind}：${d.evidence}", d.confidence,
        log.requestHeaders, log.requestBody, log.responseHeaders, log.responseBody,
    )

    private fun writeTabularRow(row: List<Any>) {
        when (format) {
            LogExportFormat.JSON -> {
                if (jsonRowCount > 0) out.write(",\n")
                out.write("  {")
                keys.indices.forEach { i ->
                    if (i > 0) out.write(", ")
                    out.write("\"")
                    out.write(LogMultiFormatExporter.escapeJson(keys[i]))
                    out.write("\": ")
                    val value = row[i]
                    if (value is Number) out.write(value.toString())
                    else {
                        out.write("\"")
                        out.write(LogMultiFormatExporter.escapeJson(value.toString()))
                        out.write("\"")
                    }
                }
                out.write("}")
                jsonRowCount++
            }
            LogExportFormat.CSV -> {
                out.write(row.joinToString(",") { LogMultiFormatExporter.escapeCsv(it.toString()) })
                out.write("\r\n")
            }
            LogExportFormat.HTML -> {
                out.write("<tr>")
                row.forEach { out.write("<td>"); out.write(LogMultiFormatExporter.escapeHtml(it.toString())); out.write("</td>") }
                out.write("</tr>\n")
            }
            LogExportFormat.TEXT -> Unit
        }
    }

    /** TXT 行输出：节头由 [beginBucket] 显式写，行体与内存版 [LogExportFormatter] 同结构。 */
    private fun writeTextRow(log: HttpLogEntity) {
        out.write("==== #${log.id} [${time.format(Date(log.createdAt))}] ${log.method} ${log.url}\n")
        out.write("→ HTTP ${log.statusCode} ${log.durationMs}ms final=${log.finalUrl}\n")
        if (log.error.isNotBlank()) out.write("error: ${log.error}\n")
        if (log.redirectChain.isNotBlank()) out.write("redirects: ${log.redirectChain}\n")
        if (log.requestHeaders.isNotBlank()) out.write("-- 请求头 --\n${log.requestHeaders}\n")
        if (log.requestBody.isNotBlank()) out.write("-- 请求体 --\n${log.requestBody}\n")
        if (log.responseHeaders.isNotBlank()) out.write("-- 响应头 --\n${log.responseHeaders}\n")
        if (log.responseBody.isNotBlank()) out.write("-- 响应体 --\n${log.responseBody}\n")
    }

    companion object {
        /** 与 [LogMultiFormatExporter] 同一套 HTTP 导出列。 */
        val COLUMNS = listOf(
            "id" to "ID", "time" to "时间", "method" to "方法", "url" to "URL", "finalUrl" to "最终 URL",
            "statusCode" to "状态码", "durationMs" to "耗时(ms)", "error" to "错误", "redirectChain" to "重定向链",
            "anchor" to "锚点", "attribution" to "归属依据", "confidence" to "置信度",
            "requestHeaders" to "请求头", "requestBody" to "请求体", "responseHeaders" to "响应头", "responseBody" to "响应体",
        )

        private const val BOM = "\uFEFF"
        private const val HTML_STYLE =
            "body{font-family:-apple-system,\"PingFang SC\",\"Microsoft YaHei\",sans-serif;margin:16px;color:#1c1c1e;}" +
                "h1{font-size:18px;margin:0 0 4px;}p.meta{color:#6e6e73;font-size:12px;margin:0 0 12px;}" +
                ".wrap{overflow-x:auto;}" +
                "table{border-collapse:collapse;font-size:12px;min-width:100%;}" +
                "th,td{border:1px solid #d1d1d6;padding:6px 8px;text-align:left;vertical-align:top;}" +
                "th{background:#f2f2f7;position:sticky;top:0;}tr:nth-child(even) td{background:#fafafa;}" +
                "td{white-space:pre-wrap;word-break:break-all;font-family:ui-monospace,Menlo,Consolas,monospace;max-width:480px;}"
    }
}
