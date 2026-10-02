package com.mina.legadostudio.verification

import com.mina.legadostudio.domain.LogFilterUtils

/**
 * `java.webView(html, url, js)` / WebViewPageLoader 的纯逻辑判定与注入脚本构造。
 * 与 android.webkit 无关，JVM 单测可直接覆盖；WebViewPageLoader 只负责把它们落到 WebView 上。
 */
object WebViewLoadPlan {

    /** 内嵌 HTML 上限：与抓取通道 maxBodyBytes=4_000_000 同量级，挡住超大 payload 卡死渲染。 */
    const val MAX_EMBEDDED_HTML_CHARS = 4_000_000

    /** webJs 轮询固定间隔（毫秒）。返回 null = 未就绪，到 load() 总超时为止，不用同步 XHR 泵。 */
    const val POLL_INTERVAL_MS = 300L

    /**
     * 校验后的加载计划。
     * [embeddedHtml] 非空 → `loadDataWithBaseURL(baseUrl, html)`；为 null → `loadUrl(url)`。
     */
    data class Plan(
        val url: String,
        val embeddedHtml: String?,
    )

    /**
     * 校验 url + 可选内嵌 html，返回加载计划；不合法直接抛 IllegalArgumentException。
     * 规则：
     * - url 必须是合法 http/https（data/javascript/file/content 等 scheme 一律拒绝）；
     * - url 不允许是回环/私网地址（与 HttpLogRecorder/LogFilterUtils 同一口径）；
     * - html 仅 null 或全空白时退回 loadUrl；非空 html 走 loadDataWithBaseURL 且受大小上限约束。
     */
    fun plan(url: String?, html: String?): Plan {
        return Plan(requirePublicHttpUrl(url, "webView url"), html?.takeIf { it.isNotBlank() }?.also { embedded ->
            require(embedded.length <= MAX_EMBEDDED_HTML_CHARS) {
                "webView html 超过 ${MAX_EMBEDDED_HTML_CHARS} 字符上限（当前 ${embedded.length}）"
            }
        })
    }

    /**
     * 单一校验口径：非空 + http/https scheme + 非回环/私网。
     * 入口 url 与 WebView 加载结束后的 finalUrl 共用——页面重定向到
     * file/javascript/私网地址时不能带着「已通过校验」的假设透传。
     */
    fun requirePublicHttpUrl(url: String?, label: String = "webView url"): String {
        val target = url?.trim().orEmpty()
        require(target.isNotEmpty()) { "$label 不能为空" }
        val scheme = target.substringBefore(':').lowercase()
        require(target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true)) {
            "$label 仅支持 http/https，拒绝 scheme=${scheme.ifBlank { "无" }}: $target"
        }
        require(!LogFilterUtils.isLoopbackOrPrivate(target)) { "$label 不允许回环/私网地址：$target" }
        return target
    }

    /**
     * 构造注入 WebView 的取值脚本：
     * - webJs 为空 → 直接取 document.documentElement.outerHTML；
     * - 先 `eval(<webJs>)`：表达式与「var 声明+末尾表达式」都按脚本完成值取回；
     *   eval 抛 SyntaxError（顶层 `return` 的唯一合法场景）才退回 `(function(){<webJs>})()` 形态重评，
     *   不用 \\breturn\\b 关键字嗅探——字符串/注释/正则字面量里出现单词 return 曾被误判成
     *   「带 return 的函数体」而静默 undefined 轮询到超时；合法脚本不会被求值两次；
     * - 异常转成 `__STUDIO_ERROR__<msg>` 哨兵，JS 返回 null/undefined → 返回 null 交给上层重试轮询。
     */
    fun buildPollScript(webJs: String?): String {
        if (webJs.isNullOrBlank()) return "document.documentElement.outerHTML"
        val src = quoteJsString(webJs)
        return "(function(){try{var r;try{r=eval($src);}" +
            "catch(e0){if(!(e0 instanceof SyntaxError))throw e0;r=eval(\"(function(){\"+$src+\"})()\");}" +
            "return r==null?null:String(r);}catch(e){return '__STUDIO_ERROR__'+e;}})()"
    }

    /**
     * 把任意字符串转成合法 JS 字符串字面量（双引号包裹）。
     * 不用 org.json.JSONObject.quote：JVM 单测里 Android stub 不可用，且输出语义与 JSONObject.quote 一致。
     */
    fun quoteJsString(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c.code in 0x7F..0x9F) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
