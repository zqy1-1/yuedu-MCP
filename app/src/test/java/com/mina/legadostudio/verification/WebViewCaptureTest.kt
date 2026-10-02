package com.mina.legadostudio.verification

import com.mina.legadostudio.verification.WebViewCapture.ResourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebViewCapture 纯判定层 JVM 测试：decide（纯签名）/isCapturableUrl/budgetFor/consume/
 * kindFromContentType 不依赖 android.webkit（WebResourceRequest 薄壳在 Android 侧委托）。
 */
class WebViewCaptureTest {

    private fun session(
        maxResources: Int = WebViewCapture.MAX_RESOURCES,
        sessionBudget: Long = WebViewCapture.MAX_SESSION_BYTES,
        resourceBudget: Long = WebViewCapture.MAX_RESOURCE_BODY_BYTES,
    ) = WebViewCapture.Session(
        contextId = "cap:test",
        entryUrl = "https://www.example.com/",
        sourceAnchor = "example.com",
        maxResources = maxResources,
        sessionBudgetBytes = sessionBudget,
        resourceBudgetBytes = resourceBudget,
    )

    // ---------- open / 入口校验 ----------

    @Test
    fun `open rejects non-public url`() {
        val ex = runCatching { WebViewCapture.open("file:///etc/passwd") }.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex is IllegalArgumentException)
    }

    @Test
    fun `open rejects loopback url`() {
        val ex = runCatching { WebViewCapture.open("http://127.0.0.1/x") }.exceptionOrNull()
        assertNotNull(ex)
    }

    @Test
    fun `open stamps cap contextId and anchor`() {
        val s = WebViewCapture.open("https://sub.example.com/path?q=1")
        assertTrue(s.contextId.startsWith("cap:"))
        assertEquals("https://sub.example.com/path?q=1", s.entryUrl)
        assertEquals("example.com", s.sourceAnchor)
    }

    // ---------- isCapturableUrl ----------

    @Test
    fun `isCapturableUrl filters scheme and private hosts`() {
        assertFalse(WebViewCapture.isCapturableUrl(""))
        assertFalse(WebViewCapture.isCapturableUrl("javascript:alert(1)"))
        assertFalse(WebViewCapture.isCapturableUrl("data:text/html,hi"))
        assertFalse(WebViewCapture.isCapturableUrl("file:///sdcard/x"))
        assertFalse(WebViewCapture.isCapturableUrl("http://127.0.0.1/"))
        assertFalse(WebViewCapture.isCapturableUrl("http://localhost:8080/"))
        assertFalse(WebViewCapture.isCapturableUrl("http://10.0.0.1/a"))
        assertFalse(WebViewCapture.isCapturableUrl("http://192.168.1.2/"))
        assertFalse(WebViewCapture.isCapturableUrl("https://user:pass@example.com/"))
        assertTrue(WebViewCapture.isCapturableUrl("https://www.example.com/a.js"))
        assertTrue(WebViewCapture.isCapturableUrl("http://cdn.69shu.cx/font.woff2"))
    }

    // ---------- decide ----------

    @Test
    fun `decide rejects uncapturable url without counting`() {
        val s = session()
        val d = s.decide("http://127.0.0.1/x", "GET", emptyMap(), forMainFrame = false)
        assertFalse(d.allow)
        assertEquals(0, s.countedResources)
    }

    @Test
    fun `decide marks POST as observeOnly with note`() {
        val s = session()
        val d = s.decide("https://api.example.com/search", "POST", emptyMap(), forMainFrame = false)
        assertTrue(d.allow)
        assertTrue(d.observeOnly)
        assertEquals(WebViewCapture.Session.OBSERVED_ONLY_BODY, d.requestNote)
    }

    @Test
    fun `decide lets GET through for interception supply`() {
        val s = session()
        val d = s.decide("https://www.example.com/a.js", "GET", emptyMap(), forMainFrame = false)
        assertTrue(d.allow)
        assertFalse(d.observeOnly)
        assertNull(d.requestNote)
        assertEquals(ResourceKind.SCRIPT, d.kind)
        assertEquals(1, s.countedResources)
    }

    @Test
    fun `decide stops counting past maxResources`() {
        val s = session(maxResources = 2)
        assertTrue(s.decide("https://www.example.com/1", "GET", emptyMap(), false).allow)
        assertTrue(s.decide("https://www.example.com/2", "GET", emptyMap(), false).allow)
        assertFalse(s.decide("https://www.example.com/3", "GET", emptyMap(), false).allow)
        assertEquals(2, s.countedResources)
    }

    @Test
    fun `decide flags sessionExhausted after budget drain`() {
        // 极小会话预算：请求行计数就耗尽 → 后续 GET 也只剩观察行
        val s = session(sessionBudget = 200)
        val d1 = s.decide("https://www.example.com/" + "a".repeat(100), "GET", emptyMap(), false)
        assertTrue(d1.allow)
        assertTrue(d1.sessionExhausted)
        assertTrue(d1.observeOnly)
        assertEquals(WebViewCapture.Session.SESSION_EXHAUSTED_NOTE, d1.requestNote)
    }

    // ---------- budgetFor / consume ----------

    @Test
    fun `budgetFor full when within limits`() {
        val s = session()
        val (budget, cap) = s.budgetFor(1000)
        assertEquals(WebViewCapture.Budget.FULL, budget)
        assertEquals(WebViewCapture.MAX_RESOURCE_BODY_BYTES, cap)
    }

    @Test
    fun `budgetFor truncated when over resource cap`() {
        val s = session(resourceBudget = 500)
        val (budget, cap) = s.budgetFor(10_000)
        assertEquals(WebViewCapture.Budget.TRUNCATED, budget)
        assertEquals(500L, cap)
    }

    @Test
    fun `budgetFor drop when session exhausted`() {
        val s = session(sessionBudget = 300)
        s.decide("https://www.example.com/" + "x".repeat(200), "GET", emptyMap(), false)
        val (budget, _) = s.budgetFor(100)
        assertEquals(WebViewCapture.Budget.DROP, budget)
    }

    @Test
    fun `consume drains session budget`() {
        val s = session(sessionBudget = 1_000)
        s.consume(600)
        assertFalse(s.sessionExhausted)
        s.consume(500)
        assertTrue(s.sessionExhausted)
    }

    // ---------- 去重（按事务序号，不按 URL——同一 URL 重复请求不互吞） ----------

    @Test
    fun `markRecorded dedupes same txnSeq only`() {
        val s = session()
        val d1 = s.decide("https://www.example.com/a", "GET", emptyMap(), false)
        val d2 = s.decide("https://www.example.com/a", "GET", emptyMap(), false)
        assertTrue(d1.txnSeq > 0 && d2.txnSeq > 0 && d1.txnSeq != d2.txnSeq)
        assertTrue(s.markRecorded(d1.txnSeq))
        assertFalse(s.markRecorded(d1.txnSeq))
        // 同一 URL 的第二次请求是独立事务，不受第一次去重影响
        assertTrue(s.markRecorded(d2.txnSeq))
        assertTrue(s.isRecorded(d1.txnSeq))
        assertEquals(2, s.countedResources)
    }

    @Test
    fun `lastTxnSeq resolves latest allowed request for url`() {
        val s = session()
        val d1 = s.decide("https://www.example.com/a", "GET", emptyMap(), false)
        val d2 = s.decide("https://www.example.com/a", "GET", emptyMap(), false)
        assertEquals(d2.txnSeq, s.lastTxnSeq("https://www.example.com/a", "GET"))
        assertEquals(0, s.lastTxnSeq("https://www.example.com/other", "GET"))
        assertEquals(0, s.lastTxnSeq("https://www.example.com/a", "POST"))
    }

    // ---------- DNS 守卫 ----------

    @Test
    fun `decide blocks when dnsGuard reports private resolution`() {
        val s = WebViewCapture.Session(
            contextId = "cap:t", entryUrl = "https://www.example.com/", sourceAnchor = "example.com",
            dnsGuard = { host -> if (host == "evil.example.com") "解析到私网/回环/保留地址 10.0.0.1" else null },
        )
        val blocked = s.decide("https://evil.example.com/x", "GET", emptyMap(), false)
        assertFalse(blocked.allow)
        assertTrue(blocked.requestNote.orEmpty().contains("DNS"))
        val ok = s.decide("https://www.example.com/x", "GET", emptyMap(), false)
        assertTrue(ok.allow)
    }

    // ---------- bodyFile 标记 ----------

    @Test
    fun `bodyFileMark renders parseable marker`() {
        val m = WebViewCapture.bodyFileMark("captures/abc/7.bin")
        assertEquals("[bodyFile=captures/abc/7.bin]", m)
        assertTrue(m.startsWith(WebViewCapture.BODY_FILE_MARK_PREFIX))
    }

    // ---------- captures/ 目录可控清理 ----------

    @Test
    fun `purgeExpiredCaptures removes old ctx dirs, keeps fresh and keepCtx`() {
        val root = java.io.File.createTempFile("cap", "root").let { it.delete(); it.parentFile }
        val cache = java.io.File(root, "cache_" + System.nanoTime()).apply { mkdirs() }
        try {
            val old = java.io.File(cache, "captures/oldctx").apply { mkdirs() }
            java.io.File(old, "1.bin").writeBytes(byteArrayOf(1))
            // 把整个目录与文件的时间都拨到保留期之前
            val past = System.currentTimeMillis() - WebViewCapture.CAPTURE_FILE_RETENTION_MS - 1000
            java.io.File(old, "1.bin").setLastModified(past); old.setLastModified(past)
            val fresh = java.io.File(cache, "captures/freshctx").apply { mkdirs() }
            java.io.File(fresh, "2.bin").writeBytes(byteArrayOf(2))
            val keep = java.io.File(cache, "captures/keepme").apply { mkdirs() }
            java.io.File(keep, "3.bin").writeBytes(byteArrayOf(3))
            java.io.File(keep, "3.bin").setLastModified(past); keep.setLastModified(past)

            val removed = WebViewCapture.purgeExpiredCaptures(
                cache, WebViewCapture.CAPTURE_FILE_RETENTION_MS, keepCtx = "cap:keepme",
            )
            assertTrue(removed.contains("oldctx"))
            assertFalse(old.exists())
            assertTrue(fresh.exists())   // 未过期不删
            assertTrue(keep.exists())    // keepCtx 不删（导出进行中的会话）
        } finally {
            cache.deleteRecursively()
        }
    }

    // ---------- isSuppliableStatus ----------

    @Test
    fun `isSuppliableStatus excludes redirects and no-content`() {
        assertFalse(WebViewCapture.isSuppliableStatus(301))
        assertFalse(WebViewCapture.isSuppliableStatus(302))
        assertFalse(WebViewCapture.isSuppliableStatus(304))
        assertFalse(WebViewCapture.isSuppliableStatus(204))
        assertTrue(WebViewCapture.isSuppliableStatus(200))
        assertTrue(WebViewCapture.isSuppliableStatus(404))
        assertTrue(WebViewCapture.isSuppliableStatus(500))
    }

    // ---------- captureDirName / captureResourcePath / bodyFileRefFrom / sessionMode / isStaleCursor ----------

    @Test
    fun `captureDirName only accepts cap prefix with safe name`() {
        assertEquals("abc-123", WebViewCapture.captureDirName("cap:abc-123"))
        assertNull(WebViewCapture.captureDirName("abc-123"))         // 无 cap: 前缀
        assertNull(WebViewCapture.captureDirName("cap:"))            // 空前缀名
        assertNull(WebViewCapture.captureDirName("cap:a/b"))         // 目录分隔符
        assertNull(WebViewCapture.captureDirName("cap:a\\b"))
        assertNull(WebViewCapture.captureDirName("cap:.."))          // 上一层
    }

    @Test
    fun `captureResourcePath enforces session dir and rejects traversal`() {
        val cache = java.io.File.createTempFile("capres", "root").let { it.delete(); it.parentFile }
        val base = java.io.File(cache, "cache_" + System.nanoTime()).apply { mkdirs() }
        try {
            val dir = java.io.File(base, "captures/ctx1").apply { mkdirs() }
            java.io.File(dir, "7.bin").writeBytes(byteArrayOf(1, 2, 3))
            java.io.File(base, "captures/other").apply { mkdirs() }
            java.io.File(base, "captures/other/9.bin").writeBytes(byteArrayOf(9))

            // 合法：本会话目录内
            val ok = WebViewCapture.captureResourcePath(base, "cap:ctx1", "captures/ctx1/7.bin")
            assertNotNull(ok)
            assertTrue(ok!!.isFile)
            // 别的会话目录不能读
            assertNull(WebViewCapture.captureResourcePath(base, "cap:ctx1", "captures/other/9.bin"))
            // .. 逃逸、绝对路径、嵌套子目录、非法字符一律拒
            assertNull(WebViewCapture.captureResourcePath(base, "cap:ctx1", "captures/../ctx1/7.bin"))
            assertNull(WebViewCapture.captureResourcePath(base, "cap:ctx1", "captures/ctx1/../../x"))
            assertNull(WebViewCapture.captureResourcePath(base, "cap:ctx1", "/etc/passwd"))
            assertNull(WebViewCapture.captureResourcePath(base, "cap:ctx1", "captures/ctx1/sub/7.bin"))
            assertNull(WebViewCapture.captureResourcePath(base, "cap:ctx1", "captures/ctx1/7.bin?x=1"))
            // contextId 不是 cap: 前缀时整个目录名都解析不出
            assertNull(WebViewCapture.captureResourcePath(base, "plain", "captures/ctx1/7.bin"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `bodyFileRefFrom extracts marker and rejects invalid forms`() {
        assertEquals("captures/ctx1/7.bin", WebViewCapture.bodyFileRefFrom("[binary 3B font/woff2] [bodyFile=captures/ctx1/7.bin]"))
        assertNull(WebViewCapture.bodyFileRefFrom("no marker here"))
        assertNull(WebViewCapture.bodyFileRefFrom("[bodyFile=../escape]"))
        assertNull(WebViewCapture.bodyFileRefFrom("[bodyFile=other/ctx/7.bin]"))
        // 与 bodyFileMark 写端互逆
        val mark = WebViewCapture.bodyFileMark("captures/abc/7.bin")
        assertEquals("captures/abc/7.bin", WebViewCapture.bodyFileRefFrom(mark))
    }

    @Test
    fun `sessionModeFromSummary parses mode marker`() {
        assertEquals("interactive", WebViewCapture.sessionModeFromSummary("WebView 抓包会话：entry=https://a final=https://a captured=1 observed=0 blocked=0 budgetExhausted=false mode=interactive"))
        assertEquals("headless", WebViewCapture.sessionModeFromSummary("… blocked=0 budgetExhausted=false mode=headless"))
        assertNull(WebViewCapture.sessionModeFromSummary("（旧版无 mode 标记的结算行）"))
        assertNull(WebViewCapture.sessionModeFromSummary(null))
        assertNull(WebViewCapture.sessionModeFromSummary(""))
    }

    @Test
    fun `isStaleCursor flags cursor beyond latest row`() {
        assertTrue(WebViewCapture.isStaleCursor(afterLogId = 50, latestLogId = 40))
        assertFalse(WebViewCapture.isStaleCursor(afterLogId = 40, latestLogId = 40))
        assertFalse(WebViewCapture.isStaleCursor(afterLogId = 10, latestLogId = 40))
        assertFalse(WebViewCapture.isStaleCursor(afterLogId = 0, latestLogId = null))
    }

    @Test
    fun `sessionMode defaults headless and constructor accepts interactive`() {
        val headless = WebViewCapture.Session("cap:h", "https://www.example.com/", "example.com")
        assertEquals(WebViewCapture.Session.MODE_HEADLESS, headless.sessionMode)
        val visible = WebViewCapture.Session("cap:v", "https://www.example.com/", "example.com",
            sessionMode = WebViewCapture.Session.MODE_INTERACTIVE)
        assertEquals(WebViewCapture.Session.MODE_INTERACTIVE, visible.sessionMode)
    }

    // ---------- kindOf / kindFromContentType ----------

    @Test
    fun `kindOf classifies by extension and main frame`() {
        val s = session()
        assertEquals(ResourceKind.DOCUMENT, s.kindOf("https://www.example.com/", emptyMap(), forMainFrame = true))
        assertEquals(ResourceKind.SCRIPT, s.kindOf("https://a.example.com/x.js?v=2", emptyMap(), false))
        assertEquals(ResourceKind.STYLESHEET, s.kindOf("https://a.example.com/x.css", emptyMap(), false))
        assertEquals(ResourceKind.FONT, s.kindOf("https://a.example.com/x.woff2", emptyMap(), false))
        assertEquals(ResourceKind.IMAGE, s.kindOf("https://a.example.com/x.webp", emptyMap(), false))
        assertEquals(ResourceKind.MEDIA, s.kindOf("https://a.example.com/x.m3u8", emptyMap(), false))
        assertEquals(ResourceKind.XHR, s.kindOf("https://a.example.com/api", mapOf("Sec-Fetch-Dest" to "empty"), false))
        assertEquals(ResourceKind.OTHER, s.kindOf("https://a.example.com/x.bin", mapOf("Accept" to "text/html"), false))
    }

    @Test
    fun `kindFromContentType fills in missing extension`() {
        assertEquals(ResourceKind.FONT, WebViewCapture.kindFromContentType("font/woff2"))
        assertEquals(ResourceKind.SCRIPT, WebViewCapture.kindFromContentType("application/javascript; charset=utf-8"))
        assertEquals(ResourceKind.STYLESHEET, WebViewCapture.kindFromContentType("text/css"))
        assertEquals(ResourceKind.XHR, WebViewCapture.kindFromContentType("application/json"))
        assertEquals(ResourceKind.IMAGE, WebViewCapture.kindFromContentType("image/png"))
        assertEquals(ResourceKind.MEDIA, WebViewCapture.kindFromContentType("audio/mpeg"))
        assertEquals(ResourceKind.DOCUMENT, WebViewCapture.kindFromContentType("text/html; charset=utf-8"))
        assertEquals(ResourceKind.OTHER, WebViewCapture.kindFromContentType(null))
        assertEquals(ResourceKind.OTHER, WebViewCapture.kindFromContentType("application/octet-stream"))
    }
}
