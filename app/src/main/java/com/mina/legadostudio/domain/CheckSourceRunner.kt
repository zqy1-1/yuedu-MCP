package com.mina.legadostudio.domain

import com.google.gson.JsonParser
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.runtime.LegadoRuntime

/**
 * `check_source` 工具的纯逻辑编排：组装各阶段入口、跑 validation + debug、
 * 产出逐阶段结果与 timingsMs（含缓存、JS 求值与联网耗时）。
 * 不依赖 Android Context，可被 JVM 单测直接实例化。
 */
object CheckSourceRunner {

    data class Request(
        val source: String,
        val searchKey: String? = null,
        val detailUrl: String? = null,
        val tocUrl: String? = null,
        val contentUrl: String? = null,
        /** true = 全程实时（不用任务快照缓存）。 */
        val refresh: Boolean = false,
    )

    suspend fun run(
        request: Request,
        runtime: LegadoRuntime,
        cacheFetch: (suspend (HttpFetcher.FetchRequest) -> HttpFetcher.FetchResult)?,
        origin: com.mina.legadostudio.network.HttpOrigin? = null,
    ): Map<String, Any> {
        // 与 save_source/debug_source 同一套 JSON 错误提示：语法错直接回行列定位片段
        runCatching { JsonParser.parseString(request.source).asJsonObject }.getOrNull()
            ?: error("source 不是合法 JSON 对象")

        val checks = linkedMapOf<String, String>()
        request.searchKey?.takeIf { it.isNotBlank() }?.let { checks["搜索"] = it }
        request.detailUrl?.takeIf { it.isNotBlank() }?.let { checks["详情"] = it }
        request.tocUrl?.takeIf { it.isNotBlank() }?.let { checks["目录"] = "++$it" }
        request.contentUrl?.takeIf { it.isNotBlank() }?.let { checks["正文"] = "--$it" }

        val warnings = mutableListOf<String>()
        // 真机严格模式：&&/||/%% 分支内的模式前缀真机不识别，提前告警
        warnings += BookSourceRuleSanitizer.strictModeWarnings(request.source)
        // UA 语义与真机一致（书源 header 显式 User-Agent 覆盖默认），但默认值不同：真机用 App 内 UA
        val declaresUa = runCatching {
            val header = JsonParser.parseString(request.source).asJsonObject.get("header") ?: return@runCatching false
            val obj = when {
                header.isJsonObject -> header.asJsonObject
                header.isJsonPrimitive -> JsonParser.parseString(header.asString).asJsonObject
                else -> null
            }
            obj?.keySet()?.any { it.equals("User-Agent", true) } == true
        }.getOrDefault(false)
        if (!declaresUa) {
            warnings += "书源未在 header 中显式声明 User-Agent：沙箱请求使用工具默认 UA（${HttpFetcher.DEFAULT_UA.take(40)}…），真机 Legado 使用其 App 默认 UA——两者不同，遇 UA 校验/WAF 站点可能表现不一致；要完全一致请在书源 header 显式写上目标 UA"
        }
        val hasSearchUrl = runCatching {
            JsonParser.parseString(request.source).asJsonObject.get("searchUrl")
                ?.takeIf { it.isJsonPrimitive }?.asString?.isNotBlank()
        }.getOrNull() == true
        if ("搜索" !in checks && hasSearchUrl) {
            checks["搜索"] = "我"
            warnings += "未传 searchKey：已用关键词「我」自动探测搜索链路；若「搜索」报告显示「列表 0 条」而站点实际有结果，说明 ruleSearch 选择器与搜索结果页结构不匹配（常见错误：直接套用发现/列表页选择器），请 fetch_page 搜索页实测后修正规则并显式传 searchKey 复验"
        }

        val timingsMs = linkedMapOf<String, Long>()
        val totalStart = System.nanoTime()
        val results = linkedMapOf<String, Any>()
        results["validation"] = timed("validation", timingsMs) { runtime.validate(request.source) }
        checks.forEach { (name, entry) ->
            // 失败也记：timingsMs 含出错阶段耗时；stage 结果带 error 字段不被内层吞掉
            results[name] = runCatching {
                timed(name, timingsMs) { runtime.debug(request.source, entry, cacheFetch, origin) }
            }.fold({ it }, { mapOf("error" to it.message.orEmpty()) })
        }
        if (warnings.isNotEmpty()) results["warnings"] = warnings
        results["cache"] = if (cacheFetch == null) "实时请求" else "本任务快照（最终验收请传 refresh=true）"
        timingsMs["total"] = (System.nanoTime() - totalStart) / 1_000_000
        results["timingsMs"] = timingsMs
        return results
    }

    private suspend fun <T> timed(stage: String, timingsMs: MutableMap<String, Long>, block: suspend () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            timingsMs[stage] = (System.nanoTime() - start) / 1_000_000
        }
    }
}
