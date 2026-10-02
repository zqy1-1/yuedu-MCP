package com.mina.legadostudio.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HttpLogRecorder.shouldDropForPrivateUrl 的落库闸回归（直接调生产伴生函数）：
 * P0 —— 私网/回环 URL 被 isLoopbackOrPrivate 吞掉，但 WebView 抓包引擎对「真正阻断」的
 * 私网导航/资源已递增 blockedRows 并发 recordAsync 写 BLOCKED 证据行；被吞会造成
 * 「blocked 计数有、库里没行」的缺失。仅对 webview_capture_blocked + cap: 会话放行。
 */
class HttpLogRecorderPrivateUrlTest {

    private fun draft(
        url: String,
        originKind: String? = null,
        contextId: String? = null,
        error: String = "",
    ) = HttpLogRecorder.Draft(
        method = "GET", url = url, statusCode = 0,
        originKind = originKind, contextId = contextId, error = error,
    )

    // ---- 私网/回环：默认丢弃 ----

    @Test fun `private urls are dropped for ordinary requests`() {
        assertTrue(HttpLogRecorder.shouldDropForPrivateUrl(draft("http://127.0.0.1/x")))
        assertTrue(HttpLogRecorder.shouldDropForPrivateUrl(draft("http://localhost/")))
        assertTrue(HttpLogRecorder.shouldDropForPrivateUrl(draft("http://10.0.0.5/internal")))
        assertTrue(HttpLogRecorder.shouldDropForPrivateUrl(draft("http://192.168.1.1/a")))
        assertTrue(HttpLogRecorder.shouldDropForPrivateUrl(draft("http://169.254.1.1/")))
    }

    // ---- 私网 + webview_capture_blocked + cap: 会话：放行（阻断证据行） ----

    @Test fun `webview blocked evidence inside cap session survives private filter`() {
        assertFalse(
            HttpLogRecorder.shouldDropForPrivateUrl(
                draft(
                    url = "http://192.168.1.50/internal.js",
                    originKind = "webview_capture_blocked",
                    contextId = "cap:session-1",
                    error = "BLOCKED：导航目标非 http(s)/内嵌凭据/回环私网保留地址，已拦截跳转",
                )
            )
        )
        assertFalse(
            HttpLogRecorder.shouldDropForPrivateUrl(
                draft(url = "http://127.0.0.1/res", originKind = "webview_capture_blocked", contextId = "cap:s")
            )
        )
    }

    // ---- 例外必须足够窄：缺一个条件就回到丢弃 ----

    @Test fun `exception requires BOTH webview_capture_blocked origin and cap contextId`() {
        val privateUrl = "http://10.1.2.3/x"
        // 只满足 originKind，无 cap: 会话归属 → 仍丢弃（非抓包上下文伪造不生效）
        assertTrue(HttpLogRecorder.shouldDropForPrivateUrl(
            draft(privateUrl, originKind = "webview_capture_blocked", contextId = null)))
        assertTrue(HttpLogRecorder.shouldDropForPrivateUrl(
            draft(privateUrl, originKind = "webview_capture_blocked", contextId = "task-uuid")))
        // 只满足 cap:，originKind 不是阻断证据 → 仍丢弃（不把普通私网资源行放行）
        assertTrue(HttpLogRecorder.shouldDropForPrivateUrl(
            draft(privateUrl, originKind = "webview_capture", contextId = "cap:s")))
        assertTrue(HttpLogRecorder.shouldDropForPrivateUrl(
            draft(privateUrl, originKind = "webview_capture_resource", contextId = "cap:s")))
        assertTrue(HttpLogRecorder.shouldDropForPrivateUrl(
            draft(privateUrl, originKind = "capture_once", contextId = "cap:s")))
        assertTrue(HttpLogRecorder.shouldDropForPrivateUrl(
            draft(privateUrl, originKind = null, contextId = "cap:s")))
    }

    // ---- 公网 URL 永不丢弃（闸只在私网命中） ----

    @Test fun `public urls are never dropped`() {
        assertFalse(HttpLogRecorder.shouldDropForPrivateUrl(draft("https://example.com/x")))
        assertFalse(HttpLogRecorder.shouldDropForPrivateUrl(
            draft("https://example.com/x", originKind = "webview_capture_blocked", contextId = "cap:s")))
        assertFalse(HttpLogRecorder.shouldDropForPrivateUrl(
            draft("https://a.com/r", originKind = "webview_capture_resource", contextId = "cap:s")))
    }
}
