package com.mina.legadostudio.domain

import com.mina.legadostudio.data.db.CaptureSessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抓包会话来源判定（CaptureSessionKinds.classify）JVM 单测：
 * 全靠会话内行的真实 originKind 与结算行 mode= 标记；缺证据一律 UNKNOWN，不猜。
 */
class CaptureSessionKindsTest {

    private fun summary(
        contextId: String = "cap:t",
        hasOnce: Int = 0,
        webviewKinds: String? = null,
        summaryBody: String? = null,
        latestLogId: Long = 10L,
    ) = CaptureSessionSummary(
        contextId = contextId, firstLogId = 1L, latestLogId = latestLogId,
        totalCount = 3, firstAt = 1000L, lastAt = 2000L, hopCount = 0,
        lastStatus = 200, hasOnce = hasOnce, webviewKinds = webviewKinds, summaryBody = summaryBody,
    )

    @Test
    fun `classify once-capture from capture_once kind`() {
        val s = summary(hasOnce = 1, webviewKinds = "capture_once")
        assertEquals(CaptureSessionKinds.Kind.ONCE, CaptureSessionKinds.classify(s))
        assertEquals("capture_once", CaptureSessionKinds.mcpLabel(CaptureSessionKinds.classify(s)))
        assertEquals("逐次抓包", CaptureSessionKinds.uiLabel(CaptureSessionKinds.classify(s)))
    }

    @Test
    fun `classify once-capture includes hop kinds`() {
        val s = summary(hasOnce = 1, webviewKinds = "capture_once,capture_hop")
        assertEquals(CaptureSessionKinds.Kind.ONCE, CaptureSessionKinds.classify(s))
    }

    @Test
    fun `classify interactive browser from summary mode`() {
        val s = summary(
            webviewKinds = "webview_capture,webview_capture_resource",
            summaryBody = "WebView 抓包会话：entry=https://a final=https://a captured=2 observed=0 blocked=0 budgetExhausted=false mode=interactive",
        )
        assertEquals(CaptureSessionKinds.Kind.BROWSER_INTERACTIVE, CaptureSessionKinds.classify(s))
        assertEquals("webview_browser_visible", CaptureSessionKinds.mcpLabel(CaptureSessionKinds.classify(s)))
    }

    @Test
    fun `classify headless webview from summary mode`() {
        val s = summary(
            webviewKinds = "webview_capture,webview_capture_resource",
            summaryBody = "… blocked=0 budgetExhausted=false mode=headless",
        )
        assertEquals(CaptureSessionKinds.Kind.WEBVIEW_HEADLESS, CaptureSessionKinds.classify(s))
        assertEquals("webview_capture_headless", CaptureSessionKinds.mcpLabel(CaptureSessionKinds.classify(s)))
    }

    @Test
    fun `classify generic browser when summary lacks mode`() {
        // 旧版结算行没有 mode= 标记：能确定是浏览器抓包，但不冒充可见/无头
        val s = summary(
            webviewKinds = "webview_capture,webview_capture_resource",
            summaryBody = "WebView 抓包会话（交互式）：entry=https://a captured=2",
        )
        assertEquals(CaptureSessionKinds.Kind.BROWSER_GENERIC, CaptureSessionKinds.classify(s))
        assertEquals("webview_capture", CaptureSessionKinds.mcpLabel(CaptureSessionKinds.classify(s)))
    }

    @Test
    fun `classify generic browser when only resource rows`() {
        val s = summary(webviewKinds = "webview_capture_resource,webview_capture_blocked")
        assertEquals(CaptureSessionKinds.Kind.BROWSER_GENERIC, CaptureSessionKinds.classify(s))
    }

    @Test
    fun `classify unknown when no evidence`() {
        assertEquals(CaptureSessionKinds.Kind.UNKNOWN, CaptureSessionKinds.classify(summary()))
        assertEquals(CaptureSessionKinds.Kind.UNKNOWN, CaptureSessionKinds.classify(summary(webviewKinds = "mcp_fetch,debug_source")))
        assertEquals("unknown", CaptureSessionKinds.mcpLabel(CaptureSessionKinds.Kind.UNKNOWN))
        assertEquals("抓包", CaptureSessionKinds.uiLabel(CaptureSessionKinds.Kind.UNKNOWN))
    }

    @Test
    fun `isFinished kind view waits settlement only for interactive browser`() {
        assertTrue(CaptureSessionKinds.isFinished(CaptureSessionKinds.Kind.ONCE, hasSettlement = false))
        assertTrue(CaptureSessionKinds.isFinished(CaptureSessionKinds.Kind.WEBVIEW_HEADLESS, hasSettlement = false))
        assertFalse(CaptureSessionKinds.isFinished(CaptureSessionKinds.Kind.BROWSER_INTERACTIVE, hasSettlement = false))
        assertTrue(CaptureSessionKinds.isFinished(CaptureSessionKinds.Kind.BROWSER_INTERACTIVE, hasSettlement = true))
        assertFalse(CaptureSessionKinds.isFinished(CaptureSessionKinds.Kind.BROWSER_GENERIC, hasSettlement = false))
        assertTrue(CaptureSessionKinds.isFinished(CaptureSessionKinds.Kind.BROWSER_GENERIC, hasSettlement = true))
        assertFalse(CaptureSessionKinds.isFinished(CaptureSessionKinds.Kind.UNKNOWN, hasSettlement = false))
    }

    @Test
    fun `isFinished state view ends once and headless without waiting settlement`() {
        fun st(once: Int = 0, wv: Int = 0, settle: Int = 0) =
            com.mina.legadostudio.data.db.CaptureSessionEndState(hasOnce = once, hasWebView = wv, hasSettlement = settle)
        assertFalse(CaptureSessionKinds.isFinished(null))                       // 会话刚建、未写行：活跃
        assertTrue(CaptureSessionKinds.isFinished(st(once = 1)))                // 逐次抓包写完即结束
        assertTrue(CaptureSessionKinds.isFinished(st(wv = 1, settle = 1)))      // 浏览器会话已结算
        assertFalse(CaptureSessionKinds.isFinished(st(wv = 1, settle = 0)))     // 可见浏览器：等「结束并保存」
        assertTrue(CaptureSessionKinds.isFinished(st()))                        // 全零（无证据旧会话）：不会再生
    }
}
