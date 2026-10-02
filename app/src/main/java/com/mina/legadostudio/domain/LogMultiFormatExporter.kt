package com.mina.legadostudio.domain

import com.mina.legadostudio.data.db.HttpLogEntity
import com.mina.legadostudio.data.db.OperationLogEntity
import com.mina.legadostudio.diagnostic.LogRedactor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogExportFormat(val mime: String, val extension: String) {
    TEXT("text/plain", "txt"),
    JSON("application/json", "json"),
    CSV("text/csv", "csv"),
    HTML("text/html", "html"),
}

/** 日志多格式导出：TEXT 复用 [LogExportFormatter]，JSON/CSV/HTML 纯手写转义，不依赖 Android。 */
object LogMultiFormatExporter {
    private const val BOM = "\uFEFF"
    private val queryToken = Regex("(?i)([?&](?:api[-_]?key|access[-_]?token|secret)=)[^&#\\s]+")

    private val operationColumns = listOf(
        "id" to "ID", "time" to "时间", "level" to "级别", "category" to "分类", "message" to "消息", "detail" to "详情",
    )
    private val httpColumns = listOf(
        "id" to "ID", "time" to "时间", "method" to "方法", "url" to "URL", "finalUrl" to "最终 URL",
        "statusCode" to "状态码", "durationMs" to "耗时(ms)", "error" to "错误", "redirectChain" to "重定向链",
        "anchor" to "锚点", "attribution" to "归属依据", "confidence" to "置信度",
        "requestHeaders" to "请求头", "requestBody" to "请求体", "responseHeaders" to "响应头", "responseBody" to "响应体",
    )

    fun operation(logs: List<OperationLogEntity>, dateKey: String, format: LogExportFormat, redact: Boolean = false): String {
        val source = if (redact) logs.map(::redactOperation) else logs
        if (format == LogExportFormat.TEXT) return LogExportFormatter.operation(source, dateKey)
        val time = timeFormat()
        val rows = source.sortedBy { it.createdAt }.map { log ->
            listOf<Any>(log.id, time.format(Date(log.createdAt)), log.level, log.category, log.message, log.detail)
        }
        return render(format, "操作日志 $dateKey", operationColumns, rows, time)
    }

    /**
     * HTTP 事务导出。`groupByAnchor=true` 时先按锚点分组排序（每组内按时间升序），
     * 每行追加 锚点/归属依据/置信度 三列；TEXT 走 [LogExportFormatter.httpGrouped] 分节输出。
     * [anchors] 为归属判定的候选锚点：调用方（UI 锚点筛选 / MCP anchor 参数）已确定筛选域时
     * 必须传入，保证 sourceAnchor=null 的旧日志能凭 URL/Referer 证据归并到所选锚点；
     * 不传则退化为仅从日志自带 sourceAnchor 推导候选（无筛选场景等价行为）。
     */
    fun http(
        logs: List<HttpLogEntity>,
        dateKey: String,
        format: LogExportFormat,
        redact: Boolean = false,
        groupByAnchor: Boolean = false,
        anchors: List<String>? = null,
    ): String {
        val source = if (redact) logs.map(::redactHttp) else logs
        if (format == LogExportFormat.TEXT) {
            return if (groupByAnchor) LogExportFormatter.httpGrouped(source, dateKey, anchors) else LogExportFormatter.http(source, dateKey)
        }
        val resolvedAnchors = anchors ?: deriveAnchorCandidates(source)
        val ctxAnchors = ContextAnchorRegistry.snapshot()
        val ordered = if (groupByAnchor) {
            HttpLogAttributor.group(source, resolvedAnchors, ctxAnchors)
                .toSortedMap()
                .flatMap { (key, bucket) -> bucket.second.sortedBy { it.createdAt }.map { key to it } }
                .map { it.second }
        } else source.sortedBy { it.createdAt }
        val time = timeFormat()
        val rows = ordered.map { log ->
            val decision = HttpLogAttributor.attribute(log, resolvedAnchors, ctxAnchors)
            listOf<Any>(
                log.id, time.format(Date(log.createdAt)), log.method, log.url, log.finalUrl,
                log.statusCode, log.durationMs, log.error, log.redirectChain,
                decision.anchor ?: HttpLogAttributor.UNATTRIBUTED_KEY, "${decision.kind}：${decision.evidence}", decision.confidence,
                log.requestHeaders, log.requestBody, log.responseHeaders, log.responseBody,
            )
        }
        return render(format, "HTTP 事务 $dateKey", httpColumns, rows, time)
    }

    /**
     * 分组导出未传显式 [anchors] 时的候选推导：sourceAnchor 之外，把日志里 Referer
     * 指向的注册域也并入候选——镜像域请求（URL 落在镜像、Referer 指回源站）
     * 的唯一证据是 Referer，若不并入候选则无法归并。请求 URL 本身的注册域不进候选，
     * 避免镜像请求被自身 URL 命中而误归镜像桶（URL 证据优先级高于 Referer）。
     */
    internal fun deriveAnchorCandidates(logs: List<HttpLogEntity>): List<String> = (
        logs.mapNotNull { it.sourceAnchor } + logs.mapNotNull { HttpLogAttributor.refererDomain(it) }
        ).distinct()

    fun fileName(prefix: String, dateKey: String, format: LogExportFormat, now: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        return "${prefix}_${dateKey}_$stamp.${format.extension}"
    }

    fun redactOperation(log: OperationLogEntity): OperationLogEntity = log.copy(
        message = LogRedactor.redact(log.message),
        detail = LogRedactor.redact(log.detail),
    )

    fun redactHttp(log: HttpLogEntity): HttpLogEntity = log.copy(
        url = redactUrl(log.url),
        finalUrl = redactUrl(log.finalUrl),
        redirectChain = redactUrl(log.redirectChain),
        requestHeaders = LogRedactor.redact(log.requestHeaders),
        responseHeaders = LogRedactor.redact(log.responseHeaders),
        requestBody = LogRedactor.redact(log.requestBody),
        responseBody = LogRedactor.redact(log.responseBody),
        error = LogRedactor.redact(log.error),
    )

    private fun redactUrl(value: String): String =
        LogRedactor.redact(value.replace(queryToken) { match -> "${match.groupValues[1]}***" })

    private fun timeFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    private fun render(
        format: LogExportFormat,
        title: String,
        columns: List<Pair<String, String>>,
        rows: List<List<Any>>,
        time: SimpleDateFormat,
    ): String = when (format) {
        LogExportFormat.JSON -> toJson(columns.map { it.first }, rows)
        LogExportFormat.CSV -> toCsv(columns.map { it.first }, rows)
        LogExportFormat.HTML -> toHtml(title, columns.map { it.second }, rows, time.format(Date()))
        LogExportFormat.TEXT -> error("TEXT 由 LogExportFormatter 输出")
    }

    private fun toJson(keys: List<String>, rows: List<List<Any>>): String {
        if (rows.isEmpty()) return "[]"
        return rows.joinToString(separator = ",\n", prefix = "[\n", postfix = "\n]") { row ->
            keys.indices.joinToString(separator = ", ", prefix = "  {", postfix = "}") { i ->
                val value = row[i]
                val encoded = if (value is Number) value.toString() else "\"${escapeJson(value.toString())}\""
                "\"${escapeJson(keys[i])}\": $encoded"
            }
        }
    }

    private fun toCsv(keys: List<String>, rows: List<List<Any>>): String = buildString {
        append(BOM)
        append(keys.joinToString(",") { escapeCsv(it) }).append("\r\n")
        rows.forEach { row ->
            append(row.joinToString(",") { escapeCsv(it.toString()) }).append("\r\n")
        }
    }

    private fun toHtml(title: String, labels: List<String>, rows: List<List<Any>>, exportedAt: String): String = buildString {
        append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"utf-8\">\n")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        append("<title>").append(escapeHtml(title)).append("</title>\n")
        append("<style>")
        append("body{font-family:-apple-system,\"PingFang SC\",\"Microsoft YaHei\",sans-serif;margin:16px;color:#1c1c1e;}")
        append("h1{font-size:18px;margin:0 0 4px;}p.meta{color:#6e6e73;font-size:12px;margin:0 0 12px;}")
        append(".wrap{overflow-x:auto;}")
        append("table{border-collapse:collapse;font-size:12px;min-width:100%;}")
        append("th,td{border:1px solid #d1d1d6;padding:6px 8px;text-align:left;vertical-align:top;}")
        append("th{background:#f2f2f7;position:sticky;top:0;}tr:nth-child(even) td{background:#fafafa;}")
        append("td{white-space:pre-wrap;word-break:break-all;font-family:ui-monospace,Menlo,Consolas,monospace;max-width:480px;}")
        append("</style>\n</head>\n<body>\n")
        append("<h1>").append(escapeHtml(title)).append("</h1>\n")
        append("<p class=\"meta\">").append(escapeHtml("共 ${rows.size} 条 · 导出于 $exportedAt")).append("</p>\n")
        append("<div class=\"wrap\"><table>\n<thead><tr>")
        labels.forEach { append("<th>").append(escapeHtml(it)).append("</th>") }
        append("</tr></thead>\n<tbody>\n")
        if (rows.isEmpty()) {
            append("<tr><td colspan=\"").append(labels.size).append("\">").append(escapeHtml("无记录")).append("</td></tr>\n")
        } else {
            rows.forEach { row ->
                append("<tr>")
                row.forEach { append("<td>").append(escapeHtml(it.toString())).append("</td>") }
                append("</tr>\n")
            }
        }
        append("</tbody>\n</table></div>\n</body>\n</html>\n")
    }

    fun escapeJson(value: String): String = buildString(value.length + 8) {
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < '\u0020') append("\\u").append(String.format(Locale.US, "%04x", c.code)) else append(c)
            }
        }
    }

    fun escapeCsv(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    fun escapeHtml(value: String): String = buildString(value.length + 16) {
        value.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }
}
