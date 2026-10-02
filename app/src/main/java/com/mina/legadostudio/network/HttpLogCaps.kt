package com.mina.legadostudio.network

/**
 * http_logs 正文落库上限的纯判定（JVM 可测，不碰 Android）。
 *
 * - 默认沿用旧口径：普通 HTTP / 逐次抓包（CaptureOnce）/ 其他来源的正文与请求体仍按
 *   [DEFAULT_BODY_CHARS] 截断——**不变**。
 * - WebView 抓包供给行通过 [HttpLogRecorder.Draft.bodyMaxChars] 显式申请更大的独立上限，
 *   钳制在 [MAX_WEBVIEW_BODY_CHARS]：上限仍存在是因为单行最终要过 SQLite CursorWindow
 *   （约 2MB）与导出 JSON，不是无限放开。
 * - 截断处写截断标记（[markFor]，含截断前原长）如实标注，回看/导出可见该正文不完整且可知原长；
 *   旧库里的「…[正文已截断]」无原长标记由 [isTruncatedMarked]/[truncatedFrom] 兼容识别。
 *
 * 调用约定：先 [takeMarked] 再脱敏——被截掉的尾巴永不落库，脱敏扫描被自然限制在
 * ≤cap+mark 的字符量上（避免对 4MB 级正文做全量正则拖慢拦截线程）。
 */
object HttpLogCaps {

    /** 普通行正文/请求体落库上限（历史值，CaptureOnce 与普通 HTTP 保持不变）。 */
    const val DEFAULT_BODY_CHARS = 8_192

    /**
     * WebView 抓包供给行的独立上限：整页 HTML/章节正文要可回看复用，远高于默认；
     * 仍受单行 CursorWindow 约束（512K chars 最坏 UTF-8 ≈1.5MB < 2MB）。
     */
    const val MAX_WEBVIEW_BODY_CHARS = 512 * 1024

    /**
     * 旧版截断标记（无原长）。仅作历史行兼容识别/测试构造用——
     * 新写入一律走 [markFor] 生成带原长的新格式。
     */
    const val TRUNCATED_MARK = "\n…[正文已截断]"

    /**
     * 新旧截断标记共有的前缀：`contains(TRUNCATED_MARK_PREFIX)` 同时命中
     * 旧「…[正文已截断]」与新「…[正文已截断，原长 N 字符]」，行尾精确判定用 [isTruncatedMarked]。
     */
    const val TRUNCATED_MARK_PREFIX = "\n…[正文已截断"

    /** 行尾截断标记（新旧格式）：捕获组 1 = 原长，旧格式不参与（groupValues[1] 为空串）。 */
    private val TRUNCATED_TAIL = Regex("\n…\\[正文已截断(?:，原长 (\\d+) 字符)?]$")

    /** 调用方申请的上限（0=默认）；>0 时钳制到 [MAX_WEBVIEW_BODY_CHARS]，防误用放开。 */
    fun capFor(requested: Int): Int =
        if (requested > 0) requested.coerceAtMost(MAX_WEBVIEW_BODY_CHARS) else DEFAULT_BODY_CHARS

    /** 生成带原长的截断标记：[originalLength] = 截断前完整长度。 */
    fun markFor(originalLength: Int): String = "\n…[正文已截断，原长 $originalLength 字符]"

    /** 正文尾是否带截断标记：旧格式（无原长）与新格式都命中，要求标记在行尾。 */
    fun isTruncatedMarked(value: String): Boolean = TRUNCATED_TAIL.find(value) != null

    /** 截断标记里记录的原长：新格式返回 N；旧格式（无原长）与未截断行返回 null。 */
    fun truncatedFrom(value: String): Int? =
        TRUNCATED_TAIL.find(value)?.groupValues?.get(1)
            ?.takeIf { it.isNotEmpty() }?.toIntOrNull()

    /** 超上限时截断并追加带原长的截断标记（[markFor]）；未超原样返回（不复制）。 */
    fun takeMarked(value: String, cap: Int): String =
        if (value.length <= cap) value else value.take(cap) + markFor(value.length)
}
