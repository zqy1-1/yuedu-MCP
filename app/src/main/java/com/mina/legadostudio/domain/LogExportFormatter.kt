package com.mina.legadostudio.domain

import com.mina.legadostudio.data.db.HttpLogEntity
import com.mina.legadostudio.data.db.OperationLogEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 日志导出为纯文本，排查问题时可直接贴给 AI 或开发者。 */
object LogExportFormatter {
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun operation(logs: List<OperationLogEntity>, dateKey: String): String = buildString {
        appendLine("# 操作日志 $dateKey 共 ${logs.size} 条（导出 ${time.format(Date())}）")
        logs.sortedBy { it.createdAt }.forEach { log ->
            appendLine("[${time.format(Date(log.createdAt))}] [${log.level}] [${log.category}] ${log.message}")
            if (log.detail.isNotBlank()) appendLine(log.detail.prependIndent("  "))
        }
    }

    fun http(logs: List<HttpLogEntity>, dateKey: String): String = buildString {
        appendLine("# HTTP 事务 $dateKey 共 ${logs.size} 条（导出 ${time.format(Date())}）")
        logs.sortedBy { it.createdAt }.forEach { log ->
            appendLine("==== #${log.id} [${time.format(Date(log.createdAt))}] ${log.method} ${log.url}")
            appendLine("→ HTTP ${log.statusCode} ${log.durationMs}ms final=${log.finalUrl}")
            if (log.error.isNotBlank()) appendLine("error: ${log.error}")
            if (log.redirectChain.isNotBlank()) appendLine("redirects: ${log.redirectChain}")
            if (log.requestHeaders.isNotBlank()) appendLine("-- 请求头 --\n${log.requestHeaders}")
            if (log.requestBody.isNotBlank()) appendLine("-- 请求体 --\n${log.requestBody}")
            if (log.responseHeaders.isNotBlank()) appendLine("-- 响应头 --\n${log.responseHeaders}")
            if (log.responseBody.isNotBlank()) appendLine("-- 响应体 --\n${log.responseBody}")
        }
    }

    /**
     * 按书源锚点分组的 HTTP 导出：每个锚点一个小节，组头给出归属结论与依据；
     * 未归属记录单独归入 [HttpLogAttributor.UNATTRIBUTED_KEY] 小节，不与已归属混排。
     * [anchors] 由调用方（UI/MCP 筛选后导出）显式给出时优先使用，
     * 保证旧日志（sourceAnchor=null 但 URL/Referer 指向所选锚点）也能归并进桶；
     * 不传则退化为仅从日志自带 sourceAnchor + Referer 证据推导候选
     * （Referer 指向的注册域并入候选，镜像域日志才能归并进桶；URL 请求目标不入候选，
     * 避免镜像请求被自身 URL 抢占归属）。
     */
    fun httpGrouped(logs: List<HttpLogEntity>, dateKey: String, anchors: List<String>? = null): String {
        val candidates = anchors ?: LogMultiFormatExporter.deriveAnchorCandidates(logs)
        val grouped = HttpLogAttributor.group(logs, candidates, ContextAnchorRegistry.snapshot())
        return buildString {
            appendLine("# HTTP 事务 $dateKey 共 ${logs.size} 条（导出 ${time.format(Date())}）")
            grouped.toSortedMap().forEach { (key, bucket) ->
                val (decision, items) = bucket
                val title = when (key) {
                    HttpLogAttributor.UNATTRIBUTED_KEY -> "未归属"
                    HttpLogAttributor.INFRASTRUCTURE_ANCHOR -> "基础设施"
                    else -> key
                }
                appendLine("\n## $title（${items.size} 条 · ${decision.kind} · 置信度 ${decision.confidence}）")
                appendLine("# 依据：${decision.evidence}")
                items.sortedBy { it.createdAt }.forEach { log ->
                    appendLine("==== #${log.id} [${time.format(Date(log.createdAt))}] ${log.method} ${log.url}")
                    appendLine("→ HTTP ${log.statusCode} ${log.durationMs}ms final=${log.finalUrl}")
                    if (log.error.isNotBlank()) appendLine("error: ${log.error}")
                    if (log.redirectChain.isNotBlank()) appendLine("redirects: ${log.redirectChain}")
                    if (log.requestHeaders.isNotBlank()) appendLine("-- 请求头 --\n${log.requestHeaders}")
                    if (log.requestBody.isNotBlank()) appendLine("-- 请求体 --\n${log.requestBody}")
                    if (log.responseHeaders.isNotBlank()) appendLine("-- 响应头 --\n${log.responseHeaders}")
                    if (log.responseBody.isNotBlank()) appendLine("-- 响应体 --\n${log.responseBody}")
                }
            }
        }
    }
}
