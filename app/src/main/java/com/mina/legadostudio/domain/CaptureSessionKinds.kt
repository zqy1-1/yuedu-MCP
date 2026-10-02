package com.mina.legadostudio.domain

import com.mina.legadostudio.data.db.CaptureSessionSummary

/**
 * 抓包会话「来源类型」判定：所有 cap:contextId 会话同表同前缀，
 * 只能靠会话内行的真实 originKind 与结算行 mode= 标记识别——
 * 识别不出一律 UNKNOWN（“未知来源”），不靠域名/hopCount 猜。
 *
 * - ONCE：出现 capture_once / capture_hop 行（逐次抓包链路专用 originKind）；
 * - BROWSER_INTERACTIVE：有 webview_capture 结算行且 mode=interactive（用户在 App 可见浏览器点页）；
 * - WEBVIEW_HEADLESS：有 webview_capture 结算行且 mode=headless（MCP webview_capture 无头一次性）；
 * - BROWSER_GENERIC：只有 webview_capture_* 资源/错误/阻断行、结算行缺 mode 标记（旧版本写入）
 *   或行集合里没有结算行——能确定是浏览器抓包，但无法区分可见/无头；
 * - UNKNOWN：以上证据都没有（更老的逐次抓包历史没有任何 originKind，落到这里）。
 */
object CaptureSessionKinds {

    enum class Kind { ONCE, BROWSER_INTERACTIVE, WEBVIEW_HEADLESS, BROWSER_GENERIC, UNKNOWN }

    /** 判定 [s] 的来源类型；规则见类注释，输入字段全部来自 DAO 聚合行。 */
    fun classify(s: CaptureSessionSummary): Kind {
        if (s.hasOnce > 0) return Kind.ONCE
        val kinds = s.webviewKinds.orEmpty().split(',').map { it.trim() }.toSet()
        val isWebView = kinds.any { it.startsWith("webview_capture") }
        if (!isWebView) return Kind.UNKNOWN
        return when (com.mina.legadostudio.verification.WebViewCapture.sessionModeFromSummary(s.summaryBody)) {
            com.mina.legadostudio.verification.WebViewCapture.Session.MODE_INTERACTIVE -> Kind.BROWSER_INTERACTIVE
            com.mina.legadostudio.verification.WebViewCapture.Session.MODE_HEADLESS -> Kind.WEBVIEW_HEADLESS
            else -> Kind.BROWSER_GENERIC
        }
    }

    /**
     * 会话是否「已结束」：
     * - ONCE / WEBVIEW_HEADLESS：一次性写入，写完即结束（不会再有新行）→ true；
     * - BROWSER_INTERACTIVE：可见浏览器页持续追加，直到用户「结束并保存」写出结算行 → 看 hasSettlement；
     * - BROWSER_GENERIC / UNKNOWN：缺 mode/结算证据的旧会话——保守按「一旦出现过结算行即结束、否则视为不可判定的活跃」处理。
     * [hasSettlement] = 会话内已出现 originKind='webview_capture' 结算行。
     */
    fun isFinished(kind: Kind, hasSettlement: Boolean): Boolean = when (kind) {
        Kind.ONCE, Kind.WEBVIEW_HEADLESS -> true
        Kind.BROWSER_INTERACTIVE -> hasSettlement
        Kind.BROWSER_GENERIC, Kind.UNKNOWN -> hasSettlement
    }

    /** 便捷重载：直接吃 DAO 聚合行（hasOnce/hasWebView/hasSettlement），与 classify 同源。 */
    fun isFinished(state: com.mina.legadostudio.data.db.CaptureSessionEndState?): Boolean = when {
        state == null -> false                                   // 会话刚建、一行未写：活跃中
        state.hasOnce > 0 -> true                                // 逐次抓包一次写完即结束
        state.hasWebView == 0 -> true                            // 无任何 webview 行（旧逐次/未知历史）：不会再生
        else -> state.hasSettlement > 0                          // 浏览器会话：结算行落库即结束
    }

    /** 展示/输出用的稳定标签（UI 中文、MCP 英文各自映射，不在本层做文案）。 */
    fun mcpLabel(kind: Kind): String = when (kind) {
        Kind.ONCE -> "capture_once"
        Kind.BROWSER_INTERACTIVE -> "webview_browser_visible"
        Kind.WEBVIEW_HEADLESS -> "webview_capture_headless"
        Kind.BROWSER_GENERIC -> "webview_capture"
        Kind.UNKNOWN -> "unknown"
    }

    /** UI 中文标签（历史页/会话详情用；与 captureSessionKindLabel 的旧口径对齐）。 */
    fun uiLabel(kind: Kind): String = when (kind) {
        Kind.ONCE -> "逐次抓包"
        Kind.BROWSER_INTERACTIVE -> "浏览器抓包·可见"
        Kind.WEBVIEW_HEADLESS -> "浏览器抓包·无头"
        Kind.BROWSER_GENERIC -> "浏览器抓包"
        Kind.UNKNOWN -> "抓包"
    }
}
