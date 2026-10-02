package com.mina.legadostudio.domain

import com.google.gson.Gson
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.runtime.RhinoEvaluator
import com.mina.legadostudio.verification.WebViewLoadPlan
import com.mina.legadostudio.verification.WebViewPageLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rhino 侧 java.webView(html,url,js) 桥的纯逻辑测试：
 * - url 空/危险 scheme/私网 在进入 WebView 前被拒（与 WebViewLoadPlan 同一校验口径）；
 * - WebViewPageLoader 缺省时报「Loader 不可用」而不是静默 loadUrl；
 * - 签名链路 (url, webJs, delay, timeout, html, recorder) 稳定，供桥与 EmbeddedLegadoRuntime 调用。
 *
 * 真正创建 WebView 的部分依赖 Android Runtime，本文件只跑到「校验通过、即将触屏 WebView」之前。
 */
class RhinoEvaluatorWebViewBridgeTest {

    private fun eval(js: String) = RhinoEvaluator(HttpFetcher(), Gson()).evaluate(js)

    @Test fun webViewRequiresUrl() {
        val e = runCatching { eval("java.webView(null, '', 'document.title')") }.exceptionOrNull()
        assertTrue(e != null)
        assertTrue(e!!.message.orEmpty().contains("webView url 不能为空"))
    }

    @Test fun webViewRejectsDangerousSchemeBeforeLoaderCheck() {
        // url 校验先于 loader 存在性校验：scheme 错误必须命中 url 报错而非「Loader 不可用」
        val e = runCatching { eval("java.webView(null, 'file:///etc/passwd', null)") }.exceptionOrNull()
        assertTrue(e != null)
        assertTrue(e!!.message.orEmpty().contains("http/https"))
    }

    @Test fun webViewRejectsLoopbackBeforeLoaderCheck() {
        val e = runCatching { eval("java.webView('<b>x</b>', 'http://127.0.0.1:1/x', null)") }.exceptionOrNull()
        assertTrue(e != null)
        assertTrue(e!!.message.orEmpty().contains("回环/私网"))
    }

    @Test fun webViewWithoutLoaderFailsLoudly() {
        // 没有 WebViewLoader 的 JVM 沙箱：valid URL 命中 loader 缺失，绝不静默当作普通抓取
        val e = runCatching { eval("java.webView(null, 'https://example.com/', null)") }.exceptionOrNull()
        assertTrue(e != null)
        assertTrue(e!!.message.orEmpty().contains("WebView Loader 不可用"))
    }

    @Test fun webViewHtmlPlanMatchesLoaderContract() {
        // 桥签名到加载计划的映射：html 非空 → 内嵌渲染（loadDataWithBaseURL 路径），html 空 → loadUrl
        val embedded = WebViewLoadPlan.plan("https://www.aaawz.cc/b/1.html", "<div>doc</div>")
        assertEquals("<div>doc</div>", embedded.embeddedHtml)
        val remote = WebViewLoadPlan.plan("https://www.aaawz.cc/b/1.html", "")
        assertEquals(null, remote.embeddedHtml)
    }
}
