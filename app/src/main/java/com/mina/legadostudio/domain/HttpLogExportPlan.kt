package com.mina.legadostudio.domain

import com.mina.legadostudio.data.db.HttpLogEntity
import com.mina.legadostudio.data.db.HttpLogSummary

/**
 * HTTP 日志导出的范围规划与执行（纯 Kotlin + suspend，无 Android/Room 依赖，可 JVM 单测）。
 *
 * 范围：当日 / 全部日期（锚点跨天归并）/ 仅已勾选。
 * 取数分两趟：轻量投影 keyset 页（id<beforeId，SQL 排除 cap:%，无 OFFSET 漂移）先扫锚点候选、
 * 再重扫做归属判定驻留命中 id 与分桶；命中超 [MAX_ROWS] 抛 [ScanLimitExceeded]。
 * 正文按 [LogDeletePlan.chunk] 分批回取交写出器，永远只有一批在飞。
 * cap:% 抓包事务在 SQL 投影页排除；勾选导出由调用方做「选中 ∩ 可见」解析。
 */
object HttpLogExportPlan {

    /** 取页大小：与 UI 日窗口一致，keyset 游标页。 */
    const val PAGE_SIZE = 200

    /** 单次导出命中行数上限：超过即抛 [ScanLimitExceeded]（不静默截断，防巨大导出卡死/OOM）。 */
    const val MAX_ROWS = 100_000

    /** 命中行数超过 [MAX_ROWS] 时抛出；message 供 UI 直接 toast。 */
    class ScanLimitExceeded(val hit: Int) : Exception("导出命中 $hit 条，超过上限 $MAX_ROWS，请加筛选条件")

    /** 导出范围。 */
    enum class Scope { DAY, ALL, SELECTED }

    /** 范围选项的持久 key（弹窗 radio 回传给 shareExport）。 */
    const val SCOPE_DAY = "day"
    const val SCOPE_ALL = "all"
    const val SCOPE_SELECTED = "selected"

    /**
     * 一次导出的完整请求。dateKey 形如 yyyy-MM-dd；anchorFilter 是归属桶 key
     * （真实锚点域名，或 [HttpLogAttributor.UNATTRIBUTED_KEY] / [HttpLogAttributor.INFRASTRUCTURE_ANCHOR] 占位桶）；
     * selectedIds 只在 [Scope.SELECTED] 下使用，须已是「勾选 ∩ 当前可见」解析后的 Long id。
     */
    data class Request(
        val scope: Scope,
        val dateKey: String,
        val anchorFilter: String?,
        val selectedIds: List<Long> = emptyList(),
    )

    /** 数据库只暴露这三个通道，便于测试用 List-backed fake 实现。 */
    interface Source {
        /** 当日轻量投影 keyset 页：createdAt∈[dayStart,dayEnd]，排除 cap:%，id<beforeId DESC。 */
        suspend fun summaryDayPage(dayStart: Long, dayEnd: Long, beforeId: Long, limit: Int): List<HttpLogSummary>

        /** 全库轻量投影 keyset 页：排除 cap:%，id<beforeId DESC。 */
        suspend fun summaryAllPage(beforeId: Long, limit: Int): List<HttpLogSummary>

        /** 按 id 批量取回全量实体（含正文）。调用方负责按批切分。 */
        suspend fun entitiesByIds(ids: List<Long>): List<HttpLogEntity>
    }

    /** 一条记录归属桶 key（未归属/基础设施占位桶归一到非空 key）。 */
    fun bucketKey(d: HttpLogAttributor.Decision): String =
        d.anchor ?: HttpLogAttributor.UNATTRIBUTED_KEY

    /**
     * 锚点桶命中判定：与 LogsScreen 列表筛选、MCP get_http_logs 同一口径——
     * 占位桶 __unattributed__ 匹配 anchor==null，__infrastructure__ 匹配 INFRASTRUCTURE kind，
     * 其余按桶 key 等值。null 过滤 = 全桶。
     */
    fun inAnchorBucket(d: HttpLogAttributor.Decision, anchorFilter: String?): Boolean = when (anchorFilter) {
        null -> true
        HttpLogAttributor.UNATTRIBUTED_KEY -> d.anchor == null
        HttpLogAttributor.INFRASTRUCTURE_ANCHOR -> d.kind == HttpLogAttributor.Kind.INFRASTRUCTURE
        else -> bucketKey(d) == anchorFilter
    }

    /**
     * 归属判定的锚点候选：显式 sourceAnchor ∪ Referer 指向的注册域。
     * 镜像域请求（URL 在镜像、Referer 指回源站）的唯一证据是 Referer；
     * 请求 URL 本身的注册域故意不入候选，避免镜像请求被自身 URL 命中误归镜像桶
     * （与 [LogMultiFormatExporter.deriveAnchorCandidates] 同口径；额外把当前锚点筛选并入，
     * 保证筛选域即使没有任何日志自带证据也参与判定）。
     */
    fun anchorCandidates(logs: Iterable<HttpLogSummary>, extra: List<String> = emptyList()): List<String> =
        (logs.mapNotNull { it.sourceAnchor } + logs.mapNotNull { HttpLogAttributor.refererDomain(it.toAttributionEntity()) } + extra)
            .distinct()

    /**
     * 第一趟扫描：keyset 逐页取投影、归属判定、过滤，返回导出范围。
     *
     * @param dayWindow [Scope.DAY] 的当日 [start,end]（毫秒，闭区间）；其他 scope 传 null。
     * @param contextAnchors contextId→锚点 的进程内快照（[ContextAnchorRegistry.snapshot]）。
     */
    suspend fun scan(
        source: Source,
        request: Request,
        dayWindow: Pair<Long, Long>?,
        contextAnchors: Map<String, String> = ContextAnchorRegistry.snapshot(),
    ): ScanResult {
        // 勾选导出：id 已由调用方做「选中 ∩ 可见」交集解析，直接命中，不再跑归属过滤
        // （勾的就是用户眼前那几行，归属桶已在 UI 明示过）。cap:% 进不来（可见 id 集合已排除）。
        // 仍取回选中行投影收集锚点候选：JSON/CSV/HTML 每行归属列需要同一判定口径。
        if (request.scope == Scope.SELECTED) {
            val ids = request.selectedIds.distinct().sorted()
            val candidateLogs = mutableListOf<HttpLogSummary>()
            LogDeletePlan.chunk(ids).forEach { batch ->
                candidateLogs += source.entitiesByIds(batch).map {
                    HttpLogSummary(
                        id = it.id, method = it.method, url = it.url, finalUrl = it.finalUrl,
                        statusCode = it.statusCode, durationMs = it.durationMs, error = it.error,
                        createdAt = it.createdAt, sourceAnchor = it.sourceAnchor,
                        contextId = it.contextId, originKind = it.originKind,
                        requestHeaders = it.requestHeaders, redirectChain = it.redirectChain,
                    )
                }
            }
            return ScanResult(
                ids = ids, total = ids.size,
                anchors = anchorCandidates(candidateLogs),
                bucketCounts = emptyMap(),
                idsByBucket = emptyMap(),
            )
        }

        // 第一趟只收集锚点候选（不驻留投影行）：首页日志可能引用后续页才出现的锚点域，
        // 顺序判定会把它们漏成未归属，故先扫出全部显式锚点 + Referer 域。
        val realFilter = request.anchorFilter?.takeUnless {
            it == HttpLogAttributor.UNATTRIBUTED_KEY || it == HttpLogAttributor.INFRASTRUCTURE_ANCHOR
        }
        val candidateSet = linkedSetOf<String>()
        realFilter?.let { candidateSet += it }
        var beforeId = Long.MAX_VALUE
        while (true) {
            val page = fetchSummaryPage(source, request, dayWindow, beforeId)
            if (page.isEmpty()) break
            page.forEach { s ->
                s.sourceAnchor?.let { candidateSet += it }
                HttpLogAttributor.refererDomain(s.toAttributionEntity())?.let { candidateSet += it }
            }
            beforeId = page.last().id
            if (page.size < PAGE_SIZE) break
        }
        val anchors = candidateSet.toList()

        // 第二趟重扫投影做归属判定：只驻留命中 id（全局升序）与每桶 id 列表。
        val ids = ArrayList<Long>()
        val bucketCounts = sortedMapOf<String, Int>()
        val idsByBucket = sortedMapOf<String, MutableList<Long>>()
        var hitCount = 0
        beforeId = Long.MAX_VALUE
        while (true) {
            val page = fetchSummaryPage(source, request, dayWindow, beforeId)
            if (page.isEmpty()) break
            page.forEach { s ->
                val d = HttpLogAttributor.attribute(s.toAttributionEntity(), anchors, contextAnchors)
                if (inAnchorBucket(d, request.anchorFilter)) {
                    val bucket = bucketKey(d)
                    bucketCounts[bucket] = (bucketCounts[bucket] ?: 0) + 1
                    idsByBucket.getOrPut(bucket) { ArrayList() }.add(s.id)
                    // 命中即抛：先判上限再累积，命中集合不超限驻留
                    if (++hitCount > MAX_ROWS) throw ScanLimitExceeded(hitCount)
                }
            }
            beforeId = page.last().id
            if (page.size < PAGE_SIZE) break
        }
        // 扫描页按 id DESC 翻，命中收集是降序；导出统一升序输出。
        idsByBucket.values.forEach { it.reverse() }
        idsByBucket.values.forEach { ids.addAll(it) }
        ids.sort()
        return ScanResult(
            ids = ids, total = ids.size, anchors = anchors,
            bucketCounts = bucketCounts, idsByBucket = idsByBucket,
        )
    }

    private suspend fun fetchSummaryPage(
        source: Source,
        request: Request,
        dayWindow: Pair<Long, Long>?,
        beforeId: Long,
    ): List<HttpLogSummary> = when (request.scope) {
        Scope.DAY -> {
            val w = requireNotNull(dayWindow) { "Scope.DAY 需要 dayWindow" }
            source.summaryDayPage(w.first, w.second, beforeId, PAGE_SIZE)
        }
        Scope.ALL -> source.summaryAllPage(beforeId, PAGE_SIZE)
        Scope.SELECTED -> emptyList()
    }

    /**
     * 第二趟：把 id 集合按 [LogDeletePlan.chunk] 分批取回实体、逐批交给 [onBatch]。
     * onBatch 收到的每批按 id 升序；返回实际取回的总条数（并发删除导致的空洞按实发数算）。
     */
    suspend fun forEachEntityBatch(
        source: Source,
        ids: List<Long>,
        onBatch: (List<HttpLogEntity>) -> Unit,
    ): Int {
        var delivered = 0
        LogDeletePlan.chunk(ids).forEach { batch ->
            val entities = source.entitiesByIds(batch)
            if (entities.isNotEmpty()) {
                onBatch(entities.sortedBy { it.id })
                delivered += entities.size
            }
        }
        return delivered
    }

    /**
     * 导出范围的人类可读标签（dialog 文案、导出完成提示、TXT 头部共用同一口径）。
     * [bucketLabel] 是锚点筛选的展示名（未归属/基础设施/域名）。
     */
    fun describe(request: Request, bucketLabel: (String) -> String = ::defaultBucketLabel): String = when (request.scope) {
        Scope.DAY -> if (request.anchorFilter != null) {
            "${request.dateKey} · 锚点「${bucketLabel(request.anchorFilter)}」"
        } else "${request.dateKey} 当日全部"
        Scope.ALL -> if (request.anchorFilter != null) {
            "全部日期 · 锚点「${bucketLabel(request.anchorFilter)}」"
        } else "全部日期"
        Scope.SELECTED -> "已勾选 ${request.selectedIds.size} 条"
    }

    fun defaultBucketLabel(anchor: String): String = when (anchor) {
        HttpLogAttributor.UNATTRIBUTED_KEY -> "未归属"
        HttpLogAttributor.INFRASTRUCTURE_ANCHOR -> "基础设施"
        else -> anchor
    }

    /**
     * 弹窗范围选项：当日（dayTotal 条）/ 全部日期 / 仅已勾选（selectedCount>0 才出现）。
     * key 用 [SCOPE_DAY]/[SCOPE_ALL]/[SCOPE_SELECTED] 常量，弹窗回传后映射回 [Scope]。
     */
    data class ScopeOption(val key: String, val label: String, val hint: String)

    fun scopeOptions(
        dateKey: String,
        dayTotal: Int,
        selectedCount: Int,
        anchorFilter: String?,
        bucketLabel: (String) -> String = ::defaultBucketLabel,
    ): List<ScopeOption> {
        val anchorNote = anchorFilter?.let { "，锚点「${bucketLabel(it)}」" }.orEmpty()
        return buildList {
            add(ScopeOption(SCOPE_DAY, "当日 $dateKey（$dayTotal 条）", "该日全库普通 HTTP 事务$anchorNote；不含抓包 cap: 会话"))
            add(ScopeOption(SCOPE_ALL, "全部日期$anchorNote", "跨天扫描全库：锚点证据在任意日期的老日志上也生效；量大时较慢"))
            if (selectedCount > 0) {
                add(ScopeOption(SCOPE_SELECTED, "仅导出已勾选（$selectedCount 条）", "只导出当前筛选下已勾选的记录，不重新归属"))
            }
        }
    }

    fun scopeFromKey(key: String): Scope = when (key) {
        SCOPE_ALL -> Scope.ALL
        SCOPE_SELECTED -> Scope.SELECTED
        else -> Scope.DAY
    }

    /**
     * 弹窗默认范围：有勾选集时默认 [SCOPE_SELECTED]——按钮已明示「导出（已勾选 N）」，
     * 默认当日会让一键分享把全库几千条发出去；无勾选（选项无 selected）默认当日。
     */
    fun defaultScopeKey(options: List<ScopeOption>?): String =
        if (options?.any { it.key == SCOPE_SELECTED } == true) SCOPE_SELECTED else SCOPE_DAY

    /**
     * 第一趟结果：导出 id 集合（升序）+ 桶分布 + 锚点候选（供写出器做分桶/列归属）。
     * [idsByBucket] 桶 key 升序、桶内 id 升序；TXT 分节按桶交批时直接取，不重扫投影。
     */
    data class ScanResult(
        val ids: List<Long>,
        val total: Int,
        val anchors: List<String>,
        val bucketCounts: Map<String, Int>,
        val idsByBucket: Map<String, List<Long>> = emptyMap(),
    )
}
