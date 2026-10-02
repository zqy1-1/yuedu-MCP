package com.mina.legadostudio.domain

import com.mina.legadostudio.data.db.HttpLogEntity
import com.mina.legadostudio.domain.HttpLogAttributor.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 归属器纯函数测试：覆盖显式锚点（含不在候选集合）、contextId 映射、同域 URL、
 * 重定向链、Referer 镜像、显式元数据互斥冲突、基础设施、未归属。
 * 归属语义（用户审读定稿）：
 * - 行级显式 sourceAnchor 是权威，不要求在候选集合中；与 contextId 映射分歧时
 *   标注分歧仍归显式（同上下文换书源后，旧映射不得把明确来源行打进未归属）；
 * - URL 证据（url→finalUrl→重定向链）优先于 Referer：URL 命中 A、Referer 指向 B 归 A 不冲突；
 * - 占位桶 __unattributed__/__infrastructure__ 不参与 normalizeAnchor 候选。
 * 498 条真实导出日志的离线复核（Perl 镜像脚本）已验证：URL 命中 459 / Referer 5 /
 * 未归属 34（其中 31 条为无 Referer 的 aqxsw 镜像域请求，符合"无证据不硬归"规则）。
 */
class HttpLogAttributorTest {

    private val anchors = listOf("ali75.com", "aqxsw66.com")

    @Before
    fun resetContextMap() {
        ContextAnchorRegistry.reset()
    }

    private fun log(
        url: String,
        finalUrl: String = "",
        redirectChain: String = "[]",
        requestHeaders: String = "{}",
        sourceAnchor: String? = null,
        contextId: String? = null,
        originKind: String? = null,
    ) = HttpLogEntity(
        method = "GET", url = url, finalUrl = finalUrl,
        statusCode = 200, durationMs = 10, requestHeaders = requestHeaders,
        responseHeaders = "{}", requestBody = "", responseBody = "",
        error = "", redirectChain = redirectChain,
        sourceAnchor = sourceAnchor, contextId = contextId, originKind = originKind,
    )

    // ---------- 显式元数据（权威来源） ----------

    @Test
    fun explicitSourceAnchorWins() {
        val d = HttpLogAttributor.attribute(log("https://dm.aqxsw66.com/", sourceAnchor = "aqxsw66.com"), anchors)
        assertEquals(Kind.EXPLICIT, d.kind)
        assertEquals("aqxsw66.com", d.anchor)
        assertEquals(95, d.confidence)
    }

    @Test
    fun explicitAnchorNotInCandidatesStillWins() {
        // 新口径：显式锚点不要求在候选集合中——调试书源跳到第三方站仍归该书源
        val d = HttpLogAttributor.attribute(log("https://dm.aqxsw66.com/", sourceAnchor = "other.com"), anchors)
        assertEquals(Kind.EXPLICIT, d.kind)
        assertEquals("other.com", d.anchor)
        assertEquals(95, d.confidence)
    }

    @Test
    fun explicitAnchorBeatsUrlEvidence() {
        // 显式锚点与 URL 命中不同时不判冲突，归显式
        val d = HttpLogAttributor.attribute(log("https://m.ali75.com/", sourceAnchor = "aqxsw66.com"), anchors)
        assertEquals(Kind.EXPLICIT, d.kind)
        assertEquals("aqxsw66.com", d.anchor)
    }

    @Test
    fun contextIdMappingWins() {
        ContextAnchorRegistry.put("ctx-1", "aqxsw66.com")
        val d = HttpLogAttributor.attribute(
            log("https://dm.aqxsw66.com/", contextId = "ctx-1"), anchors, ContextAnchorRegistry.snapshot())
        assertEquals(Kind.EXPLICIT, d.kind)
        assertEquals("aqxsw66.com", d.anchor)
        assertEquals(100, d.confidence)
    }

    @Test
    fun contextIdMappingPassedAsParameter() {
        // 归属器不读全局状态：映射必须由调用方显式传入才生效
        val ctxMap = mapOf("ctx-1" to "ali75.com")
        val without = HttpLogAttributor.attribute(log("https://dm.aqxsw66.com/", contextId = "ctx-1"), anchors)
        assertEquals(Kind.SAME_HOST, without.kind)
        assertEquals("aqxsw66.com", without.anchor)
        val with = HttpLogAttributor.attribute(log("https://dm.aqxsw66.com/", contextId = "ctx-1"), anchors, ctxMap)
        assertEquals(Kind.EXPLICIT, with.kind)
        assertEquals("ali75.com", with.anchor)
    }

    @Test
    fun consistentExplicitAndContextBoostsConfidence() {
        val ctxMap = mapOf("ctx-1" to "aqxsw66.com")
        val d = HttpLogAttributor.attribute(
            log("https://dm.aqxsw66.com/", sourceAnchor = "aqxsw66.com", contextId = "ctx-1"), anchors, ctxMap)
        assertEquals(Kind.EXPLICIT, d.kind)
        assertEquals("aqxsw66.com", d.anchor)
        assertEquals(100, d.confidence)
    }

    @Test
    fun explicitAnchorWinsOverDivergentContext() {
        // sourceAnchor 与 contextId 映射指向不同锚点：行级显式是权威（同上下文换书源后，
        // 新行只认行内元数据），分歧标注在依据里而非丢未归属
        val ctxMap = mapOf("ctx-1" to "ali75.com")
        val d = HttpLogAttributor.attribute(
            log("https://dm.aqxsw66.com/", sourceAnchor = "aqxsw66.com", contextId = "ctx-1"), anchors, ctxMap)
        assertEquals(Kind.EXPLICIT, d.kind)
        assertEquals("aqxsw66.com", d.anchor)
        assertEquals(95, d.confidence)
        assertTrue(d.evidence.contains("分歧"))
    }

    // ---------- URL 证据 ----------

    @Test
    fun sameHostByRequestUrl() {
        val d = HttpLogAttributor.attribute(log("https://m.ali75.com/alis/1/"), anchors)
        assertEquals(Kind.SAME_HOST, d.kind)
        assertEquals("ali75.com", d.anchor)
        assertEquals(85, d.confidence)
    }

    @Test
    fun sameHostByFinalUrl() {
        val d = HttpLogAttributor.attribute(log("https://m.ali75.com/", finalUrl = "https://www.ali75.com/"), anchors)
        assertEquals(Kind.SAME_HOST, d.kind)
        assertEquals("ali75.com", d.anchor)
    }

    @Test
    fun redirectChainHit() {
        val chain = """["https://m.ali75.com/","https://www.ali75.com/"]"""
        // url/finalUrl 都不命中锚点，但重定向链经过
        val d = HttpLogAttributor.attribute(log("https://cdn.example.com/x", finalUrl = "https://cdn.example.com/y", redirectChain = chain), anchors)
        assertEquals(Kind.SAME_HOST, d.kind)
        assertEquals("ali75.com", d.anchor)
        assertEquals(80, d.confidence)
    }

    // ---------- Referer 证据（镜像域唯一线索） ----------

    @Test
    fun refererAttributesMirrorDomain() {
        val headers = """{"referer":"https://dm.aqxsw66.com/file.php?hash=abc"}"""
        val d = HttpLogAttributor.attribute(log("https://dm.downshu321.shop/read.php?file=x", requestHeaders = headers), anchors)
        assertEquals(Kind.REFERER, d.kind)
        assertEquals("aqxsw66.com", d.anchor)
        assertEquals(70, d.confidence)
    }

    @Test
    fun refererToUnknownAnchorNotUsed() {
        val headers = """{"referer":"https://unrelated.com/page"}"""
        val d = HttpLogAttributor.attribute(log("https://dm.downshu321.shop/read.php", requestHeaders = headers), anchors)
        assertEquals(Kind.UNATTRIBUTED, d.kind)
        assertNull(d.anchor)
    }

    @Test
    fun urlBeatsRefererNoConflict() {
        // URL 命中 ali75、Referer 指向 aqxsw66：URL 是实际请求目标，归 ali75 不判冲突
        val headers = """{"referer":"https://dm.aqxsw66.com/"}"""
        val d = HttpLogAttributor.attribute(log("https://m.ali75.com/", requestHeaders = headers), anchors)
        assertEquals(Kind.SAME_HOST, d.kind)
        assertEquals("ali75.com", d.anchor)
    }

    // ---------- 基础设施 / 未归属 ----------

    @Test
    fun loopbackIsInfrastructure() {
        val d = HttpLogAttributor.attribute(log("http://127.0.0.1:8080/test"), anchors)
        assertEquals(Kind.INFRASTRUCTURE, d.kind)
        assertEquals(HttpLogAttributor.INFRASTRUCTURE_ANCHOR, d.anchor)
    }

    @Test
    fun privateIpIsInfrastructure() {
        val d = HttpLogAttributor.attribute(log("http://192.168.1.10/api"), anchors)
        assertEquals(Kind.INFRASTRUCTURE, d.kind)
    }

    @Test
    fun noEvidenceIsUnattributed() {
        val d = HttpLogAttributor.attribute(log("https://dm.downshu321.shop/read.php"), anchors)
        assertEquals(Kind.UNATTRIBUTED, d.kind)
        assertNull(d.anchor)
    }

    @Test
    fun mirrorWithoutRefererStaysUnattributed() {
        // 镜像域不带 Referer 时不得硬编码归入（不做「时间邻近/已知镜像名」式推测）
        listOf("https://dm.mirror-a.example/dm.php", "https://dm.mirror-b.example/down.php", "https://w.mirror-c.example/x").forEach { url ->
            val d = HttpLogAttributor.attribute(log(url), anchors)
            assertEquals("$url 应未归属", Kind.UNATTRIBUTED, d.kind)
        }
    }

    @Test
    fun emptyCandidatesStillRespectsExplicit() {
        // 无候选锚点时显式元数据仍生效；其余只区分基础设施/未归属
        val d = HttpLogAttributor.attribute(log("https://m.ali75.com/", sourceAnchor = "ali75.com"), emptyList())
        assertEquals(Kind.EXPLICIT, d.kind)
        assertEquals("ali75.com", d.anchor)
        val noAnchor = HttpLogAttributor.attribute(log("https://m.ali75.com/"), emptyList())
        assertEquals(Kind.UNATTRIBUTED, noAnchor.kind)
    }

    // ---------- 规范化 ----------

    @Test
    fun normalizeAnchorAcceptsBareDomainAndUrl() {
        assertEquals("aqxsw66.com", HttpLogAttributor.normalizeAnchor("aqxsw66.com"))
        assertEquals("aqxsw66.com", HttpLogAttributor.normalizeAnchor("https://dm.aqxsw66.com/"))
        assertEquals("aqxsw66.com", HttpLogAttributor.normalizeAnchor("https://dm.aqxsw66.com/search.php?key=x"))
        assertEquals("ali75.com", HttpLogAttributor.normalizeAnchor("m.ali75.com"))
        assertNull(HttpLogAttributor.normalizeAnchor(""))
        assertNull(HttpLogAttributor.normalizeAnchor("   "))
    }

    @Test
    fun placeholderBucketKeysAreNotDomains() {
        // 回归：__unattributed__/__infrastructure__ 经 normalizeAnchor 会被 fromHost
        // 当单段域名变形返回非空——调用方（MCP anchor 参数）必须先特判再调 normalizeAnchor，
        // 否则占位桶筛选会被静默改成等值匹配而失效。
        // 此处锁定 normalizeAnchor 自身语义（不归一为 null），配套修复在 StudioMcpServer。
        val u = HttpLogAttributor.normalizeAnchor(HttpLogAttributor.UNATTRIBUTED_KEY)
        val i = HttpLogAttributor.normalizeAnchor(HttpLogAttributor.INFRASTRUCTURE_ANCHOR)
        // 占位桶经归一化后仍是它自身（fromHost 对单段原样返回），正因如此必须先特判
        assertTrue(u == null || u == HttpLogAttributor.UNATTRIBUTED_KEY || u.contains("unattributed"))
        assertTrue(i == null || i == HttpLogAttributor.INFRASTRUCTURE_ANCHOR || i.contains("infrastructure"))
    }

    // ---------- 分组 ----------

    @Test
    fun groupBucketsCorrectly() {
        val logs = listOf(
            log("https://m.ali75.com/"),
            log("https://dm.aqxsw66.com/"),
            log("https://dm.downshu321.shop/read.php", requestHeaders = """{"referer":"https://dm.aqxsw66.com/file.php?h=1"}"""),
            log("https://unrelated.com/"),
            log("http://127.0.0.1/x"),
        )
        val grouped = HttpLogAttributor.group(logs, anchors)
        assertEquals(1, grouped["ali75.com"]?.second?.size)
        assertEquals(2, grouped["aqxsw66.com"]?.second?.size) // 主站 + Referer 镜像
        assertEquals(1, grouped[HttpLogAttributor.UNATTRIBUTED_KEY]?.second?.size)
        assertEquals(1, grouped[HttpLogAttributor.INFRASTRUCTURE_ANCHOR]?.second?.size)
        // 组头记录首条日志的归属证据；镜像记录仍在同一桶
        assertEquals(Kind.SAME_HOST, grouped["aqxsw66.com"]?.first?.kind)
        assertEquals(Kind.REFERER, HttpLogAttributor.attribute(logs[2], anchors).kind)
    }

    // ---------- 并发 ----------

    @Test
    fun attributeIsThreadSafeWithSharedContextSnapshot() {
        // ContextAnchorRegistry 写入并发安全；归属判定吃一致性快照，不受并发写影响
        ContextAnchorRegistry.put("ctx-A", "ali75.com")
        ContextAnchorRegistry.put("ctx-B", "aqxsw66.com")
        val snapshot = ContextAnchorRegistry.snapshot()
        val pool = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(64)
        val errors = AtomicInteger(0)
        repeat(64) { i ->
            pool.submit {
                try {
                    val ctx = if (i % 2 == 0) "ctx-A" else "ctx-B"
                    val expected = if (i % 2 == 0) "ali75.com" else "aqxsw66.com"
                    val d = HttpLogAttributor.attribute(log("https://unrelated-$i.com/", contextId = ctx), anchors, snapshot)
                    if (d.anchor != expected || d.kind != Kind.EXPLICIT) errors.incrementAndGet()
                } finally { latch.countDown() }
            }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(0, errors.get())
    }

    @Test
    fun htmlBodyDomainDoesNotCreateTransaction() {
        // 第三方广告域只出现在响应体，不参与归属：日志 url 是无关域 → 未归属
        val d = HttpLogAttributor.attribute(log("https://dm.aqxsw66.com/", responseBodyMentioning = "third-party-ad.example"), anchors)
        // URL 命中主站即可；响应体里的第三方域不改变归属
        assertEquals("aqxsw66.com", d.anchor)
    }

    // 辅助：构造时带 responseBody（不参与归属的证据不应影响结果）
    private fun log(url: String, responseBodyMentioning: String) = HttpLogEntity(
        method = "GET", url = url, finalUrl = url,
        statusCode = 200, durationMs = 10, requestHeaders = "{}",
        responseHeaders = "{}", requestBody = "", responseBody = "<a href='https://$responseBodyMentioning/'>ad</a>",
        error = "", redirectChain = "[]",
    )

    // ---------- ContextAnchorRegistry 上限与原子性 ----------

    @Test
    fun registryPutIsAtomicAndBounded() {
        val pool = Executors.newFixedThreadPool(16)
        val latch = CountDownLatch(600)
        repeat(600) { i ->
            pool.submit {
                try { ContextAnchorRegistry.put("ctx-$i", "a$i.com") } finally { latch.countDown() }
            }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        // 容量上限 512：写入 600 条后只保留最近 512，且不抛异常不丢同步块
        val snap = ContextAnchorRegistry.snapshot()
        assertTrue(snap.size <= 512)
        assertTrue(snap.containsKey("ctx-599"))
    }

    // ---------- ContextAnchorRegistry 歧义登记 ----------

    @Test
    fun registrySameAnchorPutIsIdempotent() {
        // 同规范化锚点重复登记（含 URL 写法差异）幂等：不转歧义、不覆盖
        ContextAnchorRegistry.put("ctx-x", "ali75.com")
        ContextAnchorRegistry.put("ctx-x", "https://m.ali75.com/")
        assertEquals("ali75.com", ContextAnchorRegistry.get("ctx-x"))
        assertEquals("ali75.com", ContextAnchorRegistry.snapshot()["ctx-x"])
    }

    @Test
    fun registryDifferentAnchorMarksAmbiguous() {
        // 同 contextId 登记两个不同书源锚点 → 歧义：get 返回 null，snapshot 透出哨兵
        ContextAnchorRegistry.put("ctx-x", "ali75.com")
        ContextAnchorRegistry.put("ctx-x", "aqxsw66.com")
        assertNull(ContextAnchorRegistry.get("ctx-x"))
        assertEquals(ContextAnchorRegistry.AMBIGUOUS_ANCHOR, ContextAnchorRegistry.snapshot()["ctx-x"])
    }

    @Test
    fun ambiguousContextStaysAmbiguous() {
        // 歧义后再登记（哪怕回到首个锚点）不改变歧义态——不做「最近一次 wins」
        ContextAnchorRegistry.put("ctx-x", "ali75.com")
        ContextAnchorRegistry.put("ctx-x", "aqxsw66.com")
        ContextAnchorRegistry.put("ctx-x", "ali75.com")
        assertNull(ContextAnchorRegistry.get("ctx-x"))
        assertEquals(ContextAnchorRegistry.AMBIGUOUS_ANCHOR, ContextAnchorRegistry.snapshot()["ctx-x"])
    }

    @Test
    fun ambiguousContextWithoutExplicitIsConflict() {
        // 歧义上下文 + 无显式锚点 → CONFLICT 未归属：即使 URL/Referer 有候选证据也不推测
        ContextAnchorRegistry.put("ctx-amb", "ali75.com")
        ContextAnchorRegistry.put("ctx-amb", "aqxsw66.com")
        val snap = ContextAnchorRegistry.snapshot()
        val d = HttpLogAttributor.attribute(
            log("https://m.ali75.com/", contextId = "ctx-amb"), anchors, snap)
        assertEquals(Kind.CONFLICT, d.kind)
        assertNull(d.anchor)
    }

    @Test
    fun explicitAnchorStillWinsOverAmbiguousContext() {
        // 歧义上下文 + 行内显式锚点 → 显式仍生效（歧义不是「具体矛盾」）
        ContextAnchorRegistry.put("ctx-amb", "ali75.com")
        ContextAnchorRegistry.put("ctx-amb", "aqxsw66.com")
        val snap = ContextAnchorRegistry.snapshot()
        val d = HttpLogAttributor.attribute(
            log("https://unrelated.example/", sourceAnchor = "ali75.com", contextId = "ctx-amb"), anchors, snap)
        assertEquals(Kind.EXPLICIT, d.kind)
        assertEquals("ali75.com", d.anchor)
    }

    @Test
    fun multiSourceContextSequence() {
        // 同 contextId 连续制作两个书源：A 期事务归 A、B 期事务归 B、期间的裸流量宁可未归属。
        // inheritedAnchor 不再回写旧锚点后，行级 sourceAnchor 是唯一权威。
        val ctx = "ctx-multi"

        // 阶段一：debug_source(A) 登记 + 带显式锚点的事务
        ContextAnchorRegistry.put(ctx, "ali75.com")
        val snapA = ContextAnchorRegistry.snapshot()
        val aRow = HttpLogAttributor.attribute(
            log("https://m.ali75.com/search", sourceAnchor = "ali75.com", contextId = ctx), anchors, snapA)
        assertEquals("ali75.com", aRow.anchor)

        // 阶段二：同一 context 泛用工具裸请求其它站（无 sourceAnchor）——
        // 新口径下 inheritedAnchor 不会回填，行级锚点为 null；
        // 归属判定走 URL/Referer 证据：无证据时未归属，绝不硬归 A
        val bare = HttpLogAttributor.attribute(
            log("https://other-site.example/page", contextId = ctx), anchors, snapA)
        // contextId 映射为 ali75.com：裸行凭 ctx 映射归 ali75（映射是稳定显式元数据，非时间邻近推测）
        assertEquals("ali75.com", bare.anchor)

        // 阶段三：debug_source(B) 用同 contextId → 映射转歧义，旧锚点不再回写
        ContextAnchorRegistry.put(ctx, "aqxsw66.com")
        val snapAB = ContextAnchorRegistry.snapshot()
        assertEquals(ContextAnchorRegistry.AMBIGUOUS_ANCHOR, snapAB[ctx])
        // 歧义后的裸行：即使 URL 命中候选也不推测，判 CONFLICT 未归属
        val bareAfter = HttpLogAttributor.attribute(
            log("https://m.ali75.com/book/1", contextId = ctx), anchors, snapAB)
        assertEquals(Kind.CONFLICT, bareAfter.kind)
        assertNull(bareAfter.anchor)
        // 歧义后带显式锚点的新行（B 书源事务）：行级权威不受歧义影响
        val bRow = HttpLogAttributor.attribute(
            log("https://dm.aqxsw66.com/x", sourceAnchor = "aqxsw66.com", contextId = ctx), anchors, snapAB)
        assertEquals("aqxsw66.com", bRow.anchor)
        assertEquals(Kind.EXPLICIT, bRow.kind)
    }

    @Test
    fun summaryProjectionAttributesSameAsEntity() {
        // UI/MCP 用 toAttributionEntity 的投影判定，必须与全量实体同口径（Referer 证据保留）
        val headers = """{"referer":"https://dm.aqxsw66.com/f"}"""
        val full = log("https://dm.downshu321.shop/r", requestHeaders = headers)
        val summary = com.mina.legadostudio.data.db.HttpLogSummary(
            id = 1, method = "GET", url = full.url, finalUrl = full.finalUrl, statusCode = 200,
            durationMs = 10, error = "", createdAt = 0, sourceAnchor = null, contextId = null,
            originKind = null, requestHeaders = headers, redirectChain = "[]",
        )
        assertEquals(Kind.REFERER, HttpLogAttributor.attribute(summary.toAttributionEntity(), anchors).kind)
        assertEquals(HttpLogAttributor.attribute(full, anchors).anchor,
            HttpLogAttributor.attribute(summary.toAttributionEntity(), anchors).anchor)
    }

    @Test
    fun unattributedAndInfrastructureAreDisjointBuckets() {
        // 每条日志只进一个桶：占位桶与真实锚点互补，无遗漏无重复
        val logs = listOf(
            log("https://m.ali75.com/"),
            log("https://unrelated.example/"),
            log("http://127.0.0.1/x"),
        )
        val grouped = HttpLogAttributor.group(logs, anchors)
        val totalBucketed = grouped.values.sumOf { it.second.size }
        assertEquals(logs.size, totalBucketed)
        // 未归属桶的 decision.anchor 必须为 null（key 是占位符而非真实锚点）
        assertNull(grouped[HttpLogAttributor.UNATTRIBUTED_KEY]?.first?.anchor)
    }
}
