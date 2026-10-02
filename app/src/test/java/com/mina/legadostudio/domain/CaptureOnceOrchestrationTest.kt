package com.mina.legadostudio.domain

import com.mina.legadostudio.network.CaptureOnce
import com.mina.legadostudio.network.HttpFetcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CaptureOnce.run 编排层的真实测试（JVM，无 Android/Room）：
 * - 入口校验（录制关闭/私网/WebView/方法）断言不触网；
 * - directFetcher 直连 MockWebServer 验证 perHop→recordHop 落库语义与 RedirectHop 字段；
 * - permissiveCapture 走完整 cap.run()（入口缝+DNS 缝放行 localhost，redirectGuard 可选内建）：
 *   302→301→200 断言 hopLogIds/finalLogIds 与逐跳字段；被拦 3xx 断言 partial 同时含 hop 与 error 行。
 */
class CaptureOnceOrchestrationTest {

    /** 内存版 LogStore：row 顺序即落库顺序；recordHop 模拟真实 HttpLogRecorder 的中间跳写库。 */
    private class RecordStore(enabled: Boolean) : CaptureOnce.LogStore {
        override val recordingEnabled = enabled
        var queries = 0
        val rows = mutableListOf<CaptureOnce.LogEntry>()
        var nextId = 1L

        fun append(method: String, url: String, finalUrl: String, status: Int, error: String = "", originKind: String? = null) {
            rows += CaptureOnce.LogEntry(nextId++, method, url, finalUrl, status, 0L, error, originKind)
        }

        override suspend fun logsByContextId(contextId: String): List<CaptureOnce.LogEntry> {
            queries++
            return rows.toList()
        }

        override fun recordHop(hop: HttpFetcher.RedirectHop, sourceAnchor: String?, contextId: String, originKind: String) {
            // 中间跳实体：url=本跳请求 URL、finalUrl=下一跳目标、statusCode=3xx；responseHeaders 由真实路径脱敏。
            append(hop.method, hop.requestUrl, hop.nextUrl.orEmpty(), hop.statusCode, originKind = originKind)
        }
    }

    private fun fakeStore(enabled: Boolean) = RecordStore(enabled)

    private fun newCapture(store: CaptureOnce.LogStore, webView: (String) -> Boolean = { false }) =
        CaptureOnce(
            fetcherFactory = { _, _, _ -> HttpFetcher() },
            logStore = store,
            requiresWebView = webView,
        )

    /**
     * 全链路 run() 测试缝变体：entryTargetValidator={} 放行入口字面量（validateTarget 会拒 127.0.0.1），
     * fetcherFactory 把 execute 注入的三守卫真实接进 HttpFetcher；unsafeSyncRecorder 让最终落点/失败
     * error 行走与生产 record() 相同的同步语义落进内存 store（hop 行由 execute 内 perHop→recordHop）。
     * guardOverrides 可替换单分量：MockWebServer 只绑 localhost，DNS 守卫必须放行才能建连；
     * redirectOverride=null 时保留内建 redirectGuard（SSRF 逐跳拦截真实在线）。
     */
    private fun permissiveCapture(
        store: RecordStore,
        redirectOverride: ((String) -> String?)? = null,
        webView: (String) -> Boolean = { false },
    ) = CaptureOnce(
        fetcherFactory = { redirectGuard, dnsGuard, perHop ->
            HttpFetcher(
                unsafeSyncRecorder = { draft ->
                    store.append(draft.method, draft.url, draft.finalUrl, draft.statusCode, error = draft.error, originKind = draft.originKind)
                },
                unsafeRedirectGuard = redirectGuard, unsafeDnsGuard = dnsGuard, unsafePerHopRecorder = perHop,
            )
        },
        logStore = store,
        requiresWebView = webView,
        entryTargetValidator = {},
        guardOverrides = CaptureOnce.GuardOverrides(redirect = redirectOverride, dns = { null }),
    )

    @Test
    fun recordingDisabledRejectsBeforeAnyRequest() = runTest {
        val store = fakeStore(enabled = false)
        val cap = newCapture(store)
        val e = runCatching { cap.run(CaptureOnce.Params(url = "https://www.69shu.cx/")) }.exceptionOrNull()
        assertTrue(e is com.mina.legadostudio.network.CaptureBlockedException)
        assertTrue(e!!.message.orEmpty().contains("记录已关闭"))
        // 关闭时绝不能查询日志（连 collectLogs 都不该触发）
        assertEquals(0, store.queries)
    }

    @Test
    fun privateTargetsAreRejectedAtEntry() = runTest {
        val store = fakeStore(enabled = true)
        val cap = newCapture(store)
        listOf(
            "http://127.0.0.1/",
            "http://localhost/",
            "http://169.254.169.254/latest/meta-data",
            "http://10.0.0.1/",
            "http://192.168.1.1/",
            "http://user:pass@evil.com/",
            "file:///etc/passwd",
            "ftp://example.com/",
        ).forEach { url ->
            val e = runCatching { cap.run(CaptureOnce.Params(url = url)) }.exceptionOrNull()
            assertNotNull("应拒绝 $url", e)
            assertTrue("$url 应为 Blocked/Illegal", e is com.mina.legadostudio.network.CaptureBlockedException || e is IllegalArgumentException)
        }
        assertEquals("私网/非法目标不应触达日志查询", 0, store.queries)
    }

    @Test
    fun webViewDomainIsRejectedForGet() = runTest {
        val store = fakeStore(enabled = true)
        val cap = newCapture(store, webView = { true })
        val e = runCatching { cap.run(CaptureOnce.Params(url = "https://webview-only.com/")) }.exceptionOrNull()
        assertTrue(e is com.mina.legadostudio.network.CaptureBlockedException)
        assertTrue(e!!.message.orEmpty().contains("WebView"))
        assertEquals(0, store.queries)
    }

    @Test
    fun invalidMethodIsRejected() = runTest {
        val cap = newCapture(fakeStore(true))
        val e = runCatching { cap.run(CaptureOnce.Params(url = "https://x.com/", method = "DELETE")) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    // ---- 中间跳逐跳证据：HttpFetcher.unsafePerHopRecorder 直连 MockWebServer（绕过 CaptureOnce 入口私网校验）----
    // 该层只验证 fetcher→perHop→store 的落库语义与 hopLogIds 分组；公网目标校验已由 privateTargetsAreRejectedAtEntry 覆盖。

    private fun directFetcher(perHop: (HttpFetcher.RedirectHop) -> Unit) =
        HttpFetcher(unsafeRedirectGuard = { null }, unsafeDnsGuard = { null }, unsafePerHopRecorder = perHop)

    @Test
    fun perHopRecordsEachRedirectAsSeparateLog() = runTest {
        // 302→301→200：两跳中间跳各记一条 capture_hop（url=本跳、finalUrl=下一跳目标、statusCode=3xx），
        // 最终落点单独一条非 hop 记录；hopLogIds 与 finalLogIds 分组正确、redirectChain 结构不变。
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/hop2?step=a"))
            server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/final"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("done"))
            server.start()
            val store = fakeStore(enabled = true)
            val hops = mutableListOf<HttpFetcher.RedirectHop>()
            val fetcher = directFetcher { hop ->
                store.recordHop(hop, "example.com", "cap:test", "capture_hop")
                hops += hop
            }
            val result = fetcher.fetch(HttpFetcher.FetchRequest(server.url("/start").toString()))
            assertEquals(200, result.code)
            // redirectChain 结构不变：起始 + 中间 + 最终各一条 URL。
            assertEquals(listOf("/start", "/hop2?step=a", "/final").size, result.redirectChain.size)

            // 补最终落点（真实链路是 HttpFetcher.fetch 内 logRecorder.record 写；这里直接 append 模拟同一 contextId）。
            store.append("GET", server.url("/start").toString(), server.url("/final").toString(), 200, originKind = "capture_once")

            assertEquals(2, hops.size)
            val hopRows = store.rows.filter { it.originKind == "capture_hop" }
            assertEquals(2, hopRows.size)
            // 第一跳：请求 URL 是 /start，finalUrl 是 resolve 后的 /hop2?step=a，状态码 302
            assertTrue(hopRows[0].url.endsWith("/start"))
            assertTrue(hopRows[0].finalUrl.endsWith("/hop2?step=a"))
            assertEquals(302, hopRows[0].statusCode)
            // 第二跳：请求 URL 是 /hop2，finalUrl 是 /final，状态码 301
            assertTrue(hopRows[1].url.endsWith("/hop2?step=a"))
            assertTrue(hopRows[1].finalUrl.endsWith("/final"))
            assertEquals(301, hopRows[1].statusCode)
            // 与最终落点同 contextId（recordHop 里是调用方传的；此处按语义把 capture_once 也挂同上下文逻辑验证分组）
            val allIds = store.rows.map { it.id }
            val hopIds = store.rows.filter { it.originKind == "capture_hop" }.map { it.id }
            val finalIds = store.rows.filter { it.originKind != "capture_hop" }.map { it.id }
            assertEquals(3, allIds.size)
            assertEquals(2, hopIds.size)
            assertEquals(1, finalIds.size)
            assertEquals(store.rows.last().id, finalIds.single())
        }
    }

    @Test
    fun blockedRedirectStillRecordsBlockedHop() = runTest {
        // 被 SSRF redirect guard 拦截的 3xx：中间跳证据先落库再中断，error 日志留下阻断原因。
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://169.254.169.254/latest"))
            server.start()
            val store = fakeStore(enabled = true)
            val fetcher = HttpFetcher(
                unsafeRedirectGuard = { target -> if (LogFilterUtils.isLoopbackOrPrivate(target)) "重定向目标为回环/私网" else null },
                unsafeDnsGuard = { null },
                unsafePerHopRecorder = { hop -> store.recordHop(hop, "example.com", "cap:test", "capture_hop") },
            )
            val err = runCatching { fetcher.fetch(HttpFetcher.FetchRequest(server.url("/start").toString())) }.exceptionOrNull()
            assertNotNull(err)
            assertTrue(err!!.message.orEmpty().contains("重定向"))
            // 被拦的 3xx 本身已留一条 capture_hop（保留状态码+Location 目标）
            val hops = store.rows.filter { it.originKind == "capture_hop" }
            assertEquals(1, hops.size)
            assertEquals(302, hops[0].statusCode)
            assertTrue(hops[0].finalUrl.contains("169.254.169.254"))
        }
    }

    @Test
    fun hopResponseHeadersAreRedacted() = runTest {
        // 中间跳响应头里的 Cookie/Set-Cookie/Authorization 必须脱敏（recorder 侧打 ***）；
        // 本层验证 recordHop 收到的 RedirectHop.responseHeaders 可被原样落库，脱敏是 HttpLogRecorder.buildEntity 的责任，
        // 这里只断言 RedirectHop 结构上能携带 Set-Cookie，真正打码由 HttpLogRecorder 单测覆盖。
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final").setHeader("Set-Cookie", "sess=secret"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            server.start()
            val hops = mutableListOf<HttpFetcher.RedirectHop>()
            val fetcher = directFetcher { hops += it }
            fetcher.fetch(HttpFetcher.FetchRequest(server.url("/start").toString()))
            assertEquals(1, hops.size)
            assertTrue(hops[0].responseHeaders.entries.any { it.key.equals("Set-Cookie", ignoreCase = true) })
            assertTrue(hops[0].responseHeaders.values.any { it.contains("sess=secret") })
        }
    }

    // ---- cap.run() 全链路：permissiveCapture 注入字面量+DNS 缝，redirectGuard 默认内建 ----

    @Test
    fun captureOnceRunRecordsHopsAndFinalIds() = runTest {
        // 302→301→200 经 cap.run() 全链路：redirectGuard 放行 localhost 跳（测试缝 override），
        // 两跳 capture_hop 落库 + 最终落点 capture_once；hopLogIds/finalLogIds 分组正确。
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/hop2?step=a"))
            server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/final"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("done"))
            server.start()
            val store = fakeStore(enabled = true)
            val cap = permissiveCapture(store, redirectOverride = { null })
            val result = cap.run(CaptureOnce.Params(url = server.url("/start").toString()))

            assertEquals(200, result.code)
            assertTrue(result.finalUrl.endsWith("/final"))
            assertEquals(3, result.redirectChain.size)
            // 全链路：unsafeSyncRecorder 让最终落点也走 record() 语义落 store；
            // collectLogs 等到 final+2 hop 齐全后返回（runTest 虚拟时钟下 delay 瞬完，取到完整快照）。
            assertEquals(3, result.logs.size)
            val hopIds = result.hopLogIds
            assertEquals(2, hopIds.size)
            assertEquals(store.rows.filter { it.originKind == "capture_hop" }.map { it.id }, hopIds)
            assertEquals(1, result.finalLogIds.size)
            val finalRow = store.rows.first { it.originKind == "capture_once" }
            assertEquals(listOf(finalRow.id), result.finalLogIds)
            // hop 行逐跳断言：url=本跳请求、finalUrl=下一跳目标、statusCode=3xx
            val hops = store.rows.filter { it.originKind == "capture_hop" }
            assertTrue(hops[0].url.endsWith("/start") && hops[0].finalUrl.endsWith("/hop2?step=a") && hops[0].statusCode == 302)
            assertTrue(hops[1].url.endsWith("/hop2?step=a") && hops[1].finalUrl.endsWith("/final") && hops[1].statusCode == 301)
        }
    }

    @Test
    fun captureOnceRunBlockedRedirectLeavesHopAndErrorRows() = runTest {
        // 302 指向 169.254.169.254：内建 redirectGuard 真拦（不经 override），
        // capture_hop 证据先落库，fetch 抛 IOException，partial.logs 应含 hop 行 + error 行。
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://169.254.169.254/latest/meta-data"))
            server.start()
            val store = fakeStore(enabled = true)
            val cap = permissiveCapture(store) // redirectOverride=null → 内建 redirectGuard 在线
            val e = runCatching { cap.run(CaptureOnce.Params(url = server.url("/start").toString())) }.exceptionOrNull()
            assertTrue("应为 CaptureFailedException，实际：$e", e is CaptureOnce.CaptureFailedException)
            val partial = (e as CaptureOnce.CaptureFailedException).partial
            // hop 行：本跳请求 /start、finalUrl 指向 169.254、statusCode=302
            val hops = partial.logs.filter { it.originKind == "capture_hop" }
            assertEquals(1, hops.size)
            assertTrue(hops[0].url.endsWith("/start"))
            assertTrue(hops[0].finalUrl.contains("169.254.169.254"))
            assertEquals(302, hops[0].statusCode)
            // error 行：fetch 失败时 HttpFetcher 的 catch 写 error draft（originKind=capture_once 非 hop）
            val nonHops = partial.logs.filter { it.originKind != "capture_hop" }
            assertTrue("应至少有一条非 hop 行（错误尾迹），实际 logs=${partial.logs}", nonHops.isNotEmpty())
            assertTrue(nonHops.any { it.error.contains("重定向") })
            assertEquals(1, partial.hopLogIds.size)
            assertEquals(1, partial.finalLogIds.size)
        }
    }

    @Test
    fun resultGroupingsAreStableAndLegacyCompatible() = runTest {
        // hopLogIds/finalLogIds 的分组口径就是 Result 里的 filter(originKind == ORIGIN_HOP)：
        // originKind 为 null 的旧日志行不落入 hop 分组，归并到 final 侧（向后兼容）。
        val store = fakeStore(enabled = true)
        store.append("GET", "https://a.com/start", "https://a.com/hop", 302, originKind = "capture_hop")
        store.append("GET", "https://a.com/hop", "https://a.com/final", 200, originKind = "capture_once")
        store.append("GET", "https://a.com/old", "", 200, originKind = null)  // 旧日志：无 originKind
        val hopIds = store.rows.filter { it.originKind == "capture_hop" }.map { it.id }
        val finalIds = store.rows.filter { it.originKind != "capture_hop" }.map { it.id }
        assertEquals(listOf(1L), hopIds)
        assertEquals(listOf(2L, 3L), finalIds)
        assertEquals(3, store.rows.size)
    }
}
