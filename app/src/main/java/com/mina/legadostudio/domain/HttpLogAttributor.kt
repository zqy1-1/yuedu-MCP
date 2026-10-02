package com.mina.legadostudio.domain

import com.google.gson.JsonParser
import com.mina.legadostudio.data.db.HttpLogEntity
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * HTTP 事务「书源锚点」归属器：无 Android/Room 依赖，可 JVM 单测。
 *
 * 判定优先级（命中即停）：
 * 1. 显式元数据为权威来源——行级 sourceAnchor 是调用方逐事务写入的判定，即使有分歧也胜出：
 *    调试某书源时即使请求去了第三方站点，事务仍归该书源锚点；
 *    显式锚点不要求出现在 [anchors] 候选集合中（候选缺失时视同已补入，URL 不抢占来源）。
 *    与 contextId 映射不一致时在依据里标注分歧但**仍归显式**——同上下文换书源后，新行只认行内元数据，
 *    旧映射不得把有明确来源的行打进未归属桶。
 *    contextId 映射为歧义（同一上下文登记过多个书源锚点，见 [ContextAnchorRegistry.AMBIGUOUS_ANCHOR]）
 *    且行内无显式锚点时判 [Kind.CONFLICT] 未归属——不做「最近一次锚点」式推测。
 * 2. URL 证据：请求 url → finalUrl → 重定向链 host 命中候选注册域（按此顺序优先，
 *    URL 是实际请求目标，权重高于 Referer，不与 Referer 冲突判）。
 * 3. Referer 证据：请求头 Referer host 命中候选注册域（镜像域场景的唯一证据）。
 * 4. 无任何证据 → [Kind.UNATTRIBUTED]；回环/私网 → [Kind.INFRASTRUCTURE]。
 *
 * 硬边界：
 * - 响应体 HTML 里出现的第三方域名（广告/统计等外链）不产生事务、不参与归属；
 * - 不同注册域的镜像不做全局硬编码；仅当本次请求头 Referer 明确落在候选锚点注册域时才归并；
 *   绝不把「镜像域」无条件归给时间上最近的书源；
 * - 绝不从时间邻近反推归属。
 *
 * contextId → 锚点 的稳定映射由调用方显式传入（见 [contextAnchors] 参数与
 * [ContextAnchorRegistry]）：归属函数本身不读取也不持有全局状态。
 */
object HttpLogAttributor {

    /** 一条事务归属一条锚点时的结论。 */
    data class Decision(
        /** 规范化后的锚点 key；未归属时为 null，基础设施时为 [INFRASTRUCTURE_ANCHOR]。 */
        val anchor: String?,
        val kind: Kind,
        /** 置信度 0..100：显式锚点+contextId 一致=100，显式锚点/contextId=95/100，URL=85，重定向=80，Referer=70。 */
        val confidence: Int,
        /** 人类可读的证据描述（落 UI/导出用），不含敏感参数。 */
        val evidence: String,
    )

    enum class Kind {
        /** 显式 sourceAnchor 或 contextId 映射命中（新日志由调用链逐事务传入）。 */
        EXPLICIT,
        /** 请求 URL/finalUrl/重定向链与候选锚点同注册域。 */
        SAME_HOST,
        /** 仅 Referer 指向候选锚点注册域（典型：书源站点 → 阅读页镜像域）。 */
        REFERER,
        /** 回环/私网/探活等基础设施流量，与书源无关。 */
        INFRASTRUCTURE,
        /** 上下文映射有分歧（歧义上下文且无行级显式锚点），不做推测归属。 */
        CONFLICT,
        /** 无任何可用证据。 */
        UNATTRIBUTED,
    }

    /** 基础设施桶的展示锚点（不是真实书源）。 */
    const val INFRASTRUCTURE_ANCHOR = "__infrastructure__"

    /** 未归属桶的展示 key（Decision.anchor=null 不能作 map key）。 */
    const val UNATTRIBUTED_KEY = "__unattributed__"

    /** 归属判定顺序：请求 URL → 最终 URL → 重定向链。 */
    private enum class UrlStage { REQUEST, FINAL, CHAIN }

    /**
     * 对一条日志记录判定归属。
     * [anchors] 候选锚点（注册域或站点 URL，会被 normalizeAnchor 规整）；
     * [contextAnchors] contextId→锚点 的稳定映射快照（MCP 层持有），默认空表。
     * 值为 [ContextAnchorRegistry.AMBIGUOUS_ANCHOR] 哨兵的条目表示该 contextId
     * 登记过多个不同锚点：不参与显式-vs-上下文冲突判，无显式锚点时直接 CONFLICT 未归属。
     */
    fun attribute(
        log: HttpLogEntity,
        anchors: List<String>,
        contextAnchors: Map<String, String> = emptyMap(),
    ): Decision {
        val candidates = anchors.mapNotNull { normalizeAnchor(it) }.distinct()

        // 1. 行级显式锚点是唯一权威：与 contextId 映射有分歧时在依据里标注，仍归显式——
        //    同上下文换书源后新行只认行内元数据，旧映射不得把明确来源行打进未归属
        val explicit = log.sourceAnchor?.takeIf { it.isNotBlank() }?.let { normalizeAnchor(it) ?: it.trim() }
        val ctxRaw = log.contextId?.takeIf { it.isNotBlank() }?.let { contextAnchors[it] }
        // 歧义上下文（同 contextId 登记过多个书源锚点）：不参与显式判定，无显式锚点时判 CONFLICT 未归属
        val ctxAmbiguous = ctxRaw == ContextAnchorRegistry.AMBIGUOUS_ANCHOR
        val ctxHit = if (ctxAmbiguous) null else ctxRaw?.let { normalizeAnchor(it) ?: it.trim() }

        if (explicit != null) {
            val (conf, suffix) = when {
                ctxHit == explicit -> 100 to "（contextId 一致）"
                ctxHit != null -> 95 to "（contextId 登记为 $ctxHit，分歧以显式为准）"
                ctxAmbiguous -> 95 to "（contextId 登记过多个锚点，以显式为准）"
                else -> 95 to ""
            }
            return Decision(explicit, Kind.EXPLICIT, conf, "显式 sourceAnchor=${log.sourceAnchor}$suffix")
        }
        if (ctxHit != null) {
            return Decision(ctxHit, Kind.EXPLICIT, 100, "显式 contextId=${log.contextId} → $ctxHit")
        }
        if (ctxAmbiguous) {
            return Decision(null, Kind.CONFLICT, 0,
                "contextId=${log.contextId} 登记过多个书源锚点，不做推测归属")
        }

        if (candidates.isEmpty()) return noAnchorDecision(log)

        // 2. URL 证据：url → finalUrl → 重定向链，逐级取第一个命中候选的注册域。
        //    请求实际目标的权重高于 Referer：URL 命中 A、Referer 指向 B 时归 A，不判冲突。
        var stage = UrlStage.REQUEST
        var urlHit = domainOf(log.url)?.takeIf { it in candidates }
        if (urlHit == null) {
            stage = UrlStage.FINAL
            urlHit = domainOf(log.finalUrl)?.takeIf { it in candidates }
        }
        if (urlHit == null) {
            stage = UrlStage.CHAIN
            urlHit = parseUrlList(log.redirectChain).asSequence()
                .mapNotNull(::domainOf).firstOrNull { it in candidates }
        }
        if (urlHit != null) {
            val via = when (stage) {
                UrlStage.REQUEST -> "请求 URL"
                UrlStage.FINAL -> "最终 URL"
                UrlStage.CHAIN -> "重定向链"
            }
            return Decision(urlHit, Kind.SAME_HOST, if (stage == UrlStage.CHAIN) 80 else 85, "$via host 命中 $urlHit")
        }

        // 3. Referer 证据（镜像域唯一线索）
        val refererDomain = refererDomain(log)
        refererDomain?.takeIf { it in candidates }?.let {
            return Decision(it, Kind.REFERER, 70, "请求头 Referer host=$refererDomain")
        }

        // 4. 基础设施 / 未归属
        if (isInfrastructure(log)) {
            return Decision(INFRASTRUCTURE_ANCHOR, Kind.INFRASTRUCTURE, 90, "回环/私网/探活端点")
        }
        return Decision(null, Kind.UNATTRIBUTED, 0, "无 URL/Referer/上下文证据")
    }

    /** 没有锚点候选时的兜底：有显式归属按显式走，其余只区分基础设施与未归属。 */
    private fun noAnchorDecision(log: HttpLogEntity): Decision {
        val explicit = log.sourceAnchor?.takeIf { it.isNotBlank() }?.let { normalizeAnchor(it) ?: it.trim() }
        if (explicit != null) return Decision(explicit, Kind.EXPLICIT, 95, "显式 sourceAnchor=${log.sourceAnchor}")
        if (isInfrastructure(log)) return Decision(INFRASTRUCTURE_ANCHOR, Kind.INFRASTRUCTURE, 90, "回环/私网/探活端点")
        return Decision(null, Kind.UNATTRIBUTED, 0, "无锚点候选且无证据")
    }

    /**
     * 把用户/链路传入的锚点规范化为注册域 key：
     * 接受 `example.com`、`https://m.example.com/`、完整 bookSourceUrl；
     * 返回 null 表示不能作为锚点（空串/无法提取 host）。
     * 占位桶 key（[UNATTRIBUTED_KEY]/[INFRASTRUCTURE_ANCHOR]）不会在此被识别——
     * 调用方（MCP/UI 筛选）必须先对占位桶特判再调本函数，避免占位串被当作域名变形。
     */
    fun normalizeAnchor(value: String): String? {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isBlank()) return null
        val host = if ("://" in trimmed) {
            trimmed.toHttpUrlOrNull()?.host ?: LogFilterUtils.extractRawHost(trimmed)
        } else {
            LogFilterUtils.extractRawHost(trimmed)
        }
        if (host.isBlank()) return null
        return registeredDomain(host)
    }

    /** 取 URL 的注册域（eTLD+1 口径，含 com.cn 等复合后缀）。 */
    fun registeredDomain(host: String): String =
        com.mina.legadostudio.verification.DomainKey.fromHost(host)

    /** url → finalUrl → redirectChain 沿途所有注册域（有序去重）。 */
    fun urlChainDomains(log: HttpLogEntity): List<String> {
        val domains = linkedSetOf<String>()
        listOf(log.url, log.finalUrl).forEach { u ->
            domainOf(u)?.let { domains += it }
        }
        parseUrlList(log.redirectChain).forEach { u ->
            domainOf(u)?.let { domains += it }
        }
        return domains.toList()
    }

    /** 从请求头 JSON 提取 Referer 的注册域（未设置/解析失败 → null）。 */
    fun refererDomain(log: HttpLogEntity): String? {
        val referer = headerValue(log.requestHeaders, "referer") ?: return null
        return domainOf(referer)
    }

    fun domainOf(url: String): String? {
        val host = url.toHttpUrlOrNull()?.host ?: return null
        return registeredDomain(host).takeIf { it.isNotBlank() }
    }

    private fun parseUrlList(json: String): List<String> {
        if (json.isBlank() || json == "[]") return emptyList()
        return runCatching {
            JsonParser.parseString(json).asJsonArray.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
        }.getOrElse { emptyList() }
    }

    private fun headerValue(headersJson: String, name: String): String? {
        if (headersJson.isBlank()) return null
        return runCatching {
            JsonParser.parseString(headersJson).asJsonObject.entrySet()
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull()
    }

    private fun isInfrastructure(log: HttpLogEntity): Boolean =
        LogFilterUtils.isLoopbackOrPrivate(log.url) ||
            (log.finalUrl.isNotBlank() && LogFilterUtils.isLoopbackOrPrivate(log.finalUrl))

    /** 把一组日志按归属分组：key=锚点（未归属用 [UNATTRIBUTED_KEY]，基础设施用 [INFRASTRUCTURE_ANCHOR]）。 */
    fun group(
        logs: List<HttpLogEntity>,
        anchors: List<String>,
        contextAnchors: Map<String, String> = emptyMap(),
    ): Map<String, Pair<Decision, List<HttpLogEntity>>> {
        val out = linkedMapOf<String, Pair<Decision, MutableList<HttpLogEntity>>>()
        logs.forEach { log ->
            val d = attribute(log, anchors, contextAnchors)
            val key = d.anchor ?: UNATTRIBUTED_KEY
            val bucket = out[key]
            if (bucket == null) out[key] = d to mutableListOf(log)
            else bucket.second.add(log)
        }
        return out.mapValues { it.value.first to it.value.second.toList() }
    }
}

/**
 * contextId → 书源锚点 的登记注册表（进程内）。
 * MCP 层只在拿到书源自带元数据（debug_source/check_source 的 sourceAnchorOf，
 * 或调用方显式传的 sourceAnchor）时写入；泛用工具不带锚点时不得回写。
 *
 * 同一 contextId 登记**不同**规范化锚点即记歧义：该条从映射移除、[get] 返回 null、
 * [snapshot] 透出 [AMBIGUOUS_ANCHOR]，归属器据此判 CONFLICT——混上下文做多书源时
 * 裸流量宁可未归属，也不让旧锚点抢占。行级显式锚点仍是权威，不受歧义影响。
 */
object ContextAnchorRegistry {
    /** 歧义哨兵：snapshot() 中对已登记过多个锚点的 contextId 返回此值。 */
    const val AMBIGUOUS_ANCHOR = "__ambiguous__"

    private const val MAX_ENTRIES = 512
    private val lock = Any()
    private val map = LinkedHashMap<String, String>()
    private val ambiguous = LinkedHashSet<String>()

    /** 登记一条映射：同规范化锚点幂等；锚点不同则该 contextId 转歧义；超容量丢最旧条目。 */
    fun put(contextId: String, anchor: String) {
        synchronized(lock) {
            if (contextId in ambiguous) return
            val existing = map[contextId]
            if (existing == null) {
                map[contextId] = anchor
                while (map.size > MAX_ENTRIES) map.remove(map.keys.first())
            } else if (HttpLogAttributor.normalizeAnchor(existing) != HttpLogAttributor.normalizeAnchor(anchor)) {
                map.remove(contextId)
                ambiguous += contextId
                while (ambiguous.size > MAX_ENTRIES) ambiguous.remove(ambiguous.first())
            }
        }
    }

    /** 歧义 contextId 返回 null（哨兵只在 [snapshot] 透出）。 */
    fun get(contextId: String): String? = synchronized(lock) {
        if (contextId in ambiguous) null else map[contextId]
    }

    /** 一致性快照：复制当前映射，歧义 contextId 以 [AMBIGUOUS_ANCHOR] 透出。 */
    fun snapshot(): Map<String, String> = synchronized(lock) {
        LinkedHashMap(map).apply { ambiguous.forEach { put(it, AMBIGUOUS_ANCHOR) } }
    }

    /** 仅供测试隔离。 */
    internal fun reset() = synchronized(lock) { map.clear(); ambiguous.clear() }
}
