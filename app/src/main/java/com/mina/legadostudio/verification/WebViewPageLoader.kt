package com.mina.legadostudio.verification

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.JsonParser
import com.mina.legadostudio.network.HttpFetcher
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebViewPageLoader(private val context: Context, private val userAgentProvider: () -> String = { HttpFetcher.DEFAULT_UA }) {
    data class Result(val finalUrl: String, val html: String, val elapsedMs: Long)

    /**
     * [resourceRecorder] 在每次子资源/主文档请求发起时回调（method, url, requestHeaders），
     * 用于把 WebView 内部请求记入 HTTP 日志；归属来源由调用方在回调闭包内自带。
     * 注意：shouldInterceptRequest 只能观测请求，拿不到响应码/响应体——记录的 statusCode=0。
     * 返回 null 不拦截，不改变页面真实加载行为。回调在拦截线程执行，实现必须非阻塞（用 recordAsync）。
     *
     * [html] 非空时经 [android.webkit.WebView.loadDataWithBaseURL] 以 url 为 baseURL 直接渲染
     * （java.webView(html,url,js) 三参交接路径）；为 null/空白时退回 [android.webkit.WebView.loadUrl]。
     * url 必须是公网 http/https（WebViewLoadPlan 校验，拒绝其他 scheme 与回环/私网），html 受大小上限约束。
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun load(
        url: String,
        webJs: String? = null,
        delayMs: Long = 800,
        timeoutMs: Long = 60_000,
        html: String? = null,
        resourceRecorder: ((method: String, requestUrl: String, headers: Map<String, String>) -> Unit)? = null,
    ): Result = withTimeout(timeoutMs) {
        val plan = WebViewLoadPlan.plan(url, html)
        suspendCancellableCoroutine { continuation ->
            val main = Handler(Looper.getMainLooper())
            val started = System.currentTimeMillis()
            var view: WebView? = null
            // 会话一旦结束（完成/失败/取消/超时）即闭合：finish 与轮询都以它为准，防止竞态二次回调
            val closed = AtomicBoolean(false)
            fun teardown() {
                main.removeCallbacksAndMessages(null)
                view?.stopLoading()
                view?.destroy()
                view = null
            }
            fun finish(result: kotlin.Result<Result>) {
                if (!closed.compareAndSet(false, true)) return
                main.post { teardown() }
                if (continuation.isActive) result.onSuccess(continuation::resume).onFailure(continuation::resumeWithException)
            }
            continuation.invokeOnCancellation {
                closed.set(true)
                main.post { teardown() }
            }
            main.post {
                if (closed.get()) return@post
                runCatching {
                    WebView(context).also { webView ->
                        view = webView
                        webView.settings.javaScriptEnabled = true
                        webView.settings.domStorageEnabled = true
                        webView.settings.userAgentString = userAgentProvider()
                        webView.settings.allowFileAccess = false
                        webView.settings.allowContentAccess = false
                        webView.settings.safeBrowsingEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                        webView.webChromeClient = WebChromeClient()
                        webView.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(v: WebView, finalUrl: String) {
                                if (closed.get()) return
                                main.postDelayed({ poll(v, finalUrl, webJs, started, main, closed, ::finish) }, delayMs.coerceIn(0, 10_000))
                            }

                            // 仅观测不拦截：把 WebView 主文档/子资源请求暴露给 HTTP 日志归属。
                            // 回环/私网由调用方 HttpLogRecorder 自带的 isLoopbackOrPrivate 过滤。
                            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                                resourceRecorder?.let { rec ->
                                    runCatching {
                                        rec(request.method ?: "GET", request.url?.toString().orEmpty(),
                                            request.requestHeaders ?: emptyMap())
                                    }
                                }
                                return null
                            }
                        }
                        val embedded = plan.embeddedHtml
                        if (embedded != null) {
                            // 交接文档路径：html 作页面内容、url 作 baseURL（相对资源/同源判定都锚在该 origin）
                            webView.loadDataWithBaseURL(plan.url, embedded, "text/html", "utf-8", null)
                        } else {
                            webView.loadUrl(plan.url)
                        }
                    }
                }.onFailure { finish(kotlin.Result.failure(it)) }
            }
        }
    }

    private fun poll(view: WebView, url: String, webJs: String?, started: Long, main: Handler, closed: AtomicBoolean, finish: (kotlin.Result<Result>) -> Unit) {
        if (closed.get()) return
        val script = WebViewLoadPlan.buildPollScript(webJs)
        view.evaluateJavascript(script) { raw ->
            if (closed.get()) return@evaluateJavascript
            val value = decode(raw)
            when {
                // webJs 返回 null = 未就绪：固定间隔轮询重试，由外层 withTimeout 兜底，不用同步 XHR 泵
                value == null -> main.postDelayed({ poll(view, view.url ?: url, webJs, started, main, closed, finish) }, WebViewLoadPlan.POLL_INTERVAL_MS)
                value.startsWith("__STUDIO_ERROR__") -> finish(kotlin.Result.failure(IllegalStateException(value.removePrefix("__STUDIO_ERROR__"))))
                else -> {
                    // finalUrl 可能与入口 url 不同（HTTP 重定向 / JS location.replace）：重定向落点
                    // 仍是危险 scheme / 私网地址时必须拒绝，而不是以 200 名义把私网目标写进日志/返回给规则。
                    val finalUrl = runCatching {
                        WebViewLoadPlan.requirePublicHttpUrl(view.url ?: url, "webView finalUrl")
                    }.getOrElse { err ->
                        finish(kotlin.Result.failure(IllegalStateException("webView 重定向落点不合法：${err.message}")))
                        return@evaluateJavascript
                    }
                    CookieManager.getInstance().flush()
                    finish(kotlin.Result.success(Result(finalUrl, value, System.currentTimeMillis() - started)))
                }
            }
        }
    }

    private fun decode(value: String?): String? {
        if (value == null || value == "null") return null
        return runCatching { JsonParser.parseString(value).takeUnless { it.isJsonNull }?.asString }.getOrElse { value.trim('"') }
    }
}
