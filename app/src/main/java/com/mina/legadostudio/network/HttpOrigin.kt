package com.mina.legadostudio.network

/**
 * 一次 HTTP 事务的**显式来源**：由调用链路逐参数传递，不走线程/进程全局“当前书源”。
 *
 * - [sourceAnchor] 书源锚点域名（注册域 eTLD+1，如 `example.com`）或书源完整 URL；
 *   归一化用 [HttpLogAttributor.normalizeAnchor]。
 * - [contextId] 触发本次请求的 MCP 任务上下文 ID（无上下文调用为 null）。
 * - [originKind] 实际发起链路：`mcp_fetch` / `debug_source` / `check_source` / `eval_js` /
 *   `inspect_rule` / `rule_js` / `webview` / `explore` 等，仅作标注，不参与归属判定。
 */
data class HttpOrigin(
    val sourceAnchor: String? = null,
    val contextId: String? = null,
    val originKind: String? = null,
)
