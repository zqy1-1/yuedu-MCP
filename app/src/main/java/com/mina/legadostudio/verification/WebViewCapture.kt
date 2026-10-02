package com.mina.legadostudio.verification

import android.webkit.WebResourceRequest
import com.mina.legadostudio.domain.LogFilterUtils
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.io.File
import java.util.UUID

/**
 * 「WebView 浏览器抓包」的会话策略：纯判定层，不依赖 android.webkit 之外的环境，JVM 可单测。
 * 真实加载/供给/落库由 [WebViewCaptureEngine] 完成；本层只负责入口校验、逐请求放行判定、
 * 资源类型归类、容量预算、DNS 私网守卫与证据行文本。
 *
 * 与逐次抓包（CaptureOnce）的关键差异：
 * - 观察的是 WebView 真实加载：GET/HEAD 走「拦截供给」——引擎用独立 OkHttpClient 对同一 URL
 *   发一次自己的请求、如实记录这次供给请求的结果（这是 OkHttp 的独立请求，不是 WebView 内部
 *   的网络栈字节流），然后把这批已记录字节喂回 WebView 渲染（若 WebView 采用供给响应，页面
 *   拿到的就是记下的那批字节；平台也可能忽略供给改走自己的网络栈，本条日志仍是供给侧证据）；
 * - 会话内请求打同一 `cap:<uuid>` contextId 与入口注册域 sourceAnchor，跨域子资源不丢归属；
 * - 私网/回环/非 http(s)/DNS 解析到私网的请求返回 allow=false：引擎返回空占位响应真正阻断
 *   （不是 return null 放行）并记 BLOCKED 证据行；
 * - POST/PUT 拿不到请求体、会话容量耗尽、超大/未知超长响应体、3xx/204/304：observeOnly=true，
 *   只记证据行后 return null 让 WebView 自己加载。注意 3xx 如实记 OBSERVED_ONLY：供给侧
 *   （OkHttp）拿到 3xx 但 WebView 会对**同一 URL 重新发起**、由它自己的栈处理跳转——
 *   不宣称"逐跳抓全链"，后续请求是否出现取决于 WebView 自身行为。
 */
object WebViewCapture {

    /** 单资源「入账」容量上限：记入 http_logs 的正文/二进制字节以它为界。 */
    const val MAX_RESOURCE_BODY_BYTES = 4L * 1024 * 1024
    /** 单次会话容量上限：防聊天页/无限滚动会话把存储刷满。 */
    const val MAX_SESSION_BYTES = 32L * 1024 * 1024
    /** 单次会话资源行硬上限：>2000 条请求的下拉加载会话不会无限增长。 */
    const val MAX_RESOURCES = 2_000
    /** 单资源落盘文件上限：字体/二进制原始字节写到 captures/<ctx>/ 目录时以它为界。 */
    const val MAX_RESOURCE_FILE_BYTES = 8L * 1024 * 1024

    /** 按 URL/请求特征归类的资源类型（小写展示标签）。 */
    enum class ResourceKind { DOCUMENT, SCRIPT, STYLESHEET, FONT, XHR, IMAGE, MEDIA, OTHER }

    /** 单个资源行的预算结论：FULL=正文全留，TRUNCATED=只留到上限，DROP=只留头。 */
    enum class Budget { FULL, TRUNCATED, DROP }

    /** 一条请求证据的判定结果。 */
    data class Decision(
        /**
         * false = 私网/回环/非 http(s)/DNS 私网/超资源数上限：引擎必须返回无害空响应阻断，
         * 不能 return null（null 是放行让 WebView 自己加载，私网请求必须真正断掉）。
         */
        val allow: Boolean,
        val kind: ResourceKind = ResourceKind.OTHER,
        /** 非空时写进请求证据行（如 POST/PUT 的「仅观察请求」声明、容量耗尽说明、阻断原因）。 */
        val requestNote: String? = null,
        /**
         * true = 只记证据行、不供给响应：带体请求（POST/PUT 拿不到 body）、会话容量耗尽、
         * 超大/未知超长响应、以及 3xx/204/304 等不该用 WebResourceResponse 供给的状态。
         * 引擎此时返回 null，让 WebView 自己加载/跳转。
         */
        val observeOnly: Boolean = false,
        /** 会话容量是否已耗尽：后续行只剩请求行证据。 */
        val sessionExhausted: Boolean = false,
        /** 本次 shouldInterceptRequest 回调的事务序号（0=未计入/被拦）。去重与日志序都用它。 */
        val txnSeq: Int = 0,
    )

    /**
     * 新会话入口判定：
     * - url 必须是公网 http/https（与 [WebViewLoadPlan.requirePublicHttpUrl] 同一口径）；
     * - entryAnchor 为入口注册域（eTLD+1）：会话内所有请求（含跨域/镜像域子资源）共享它；
     * - 独立 contextId（`cap:` 前缀）：不污染普通 HTTP 列表，也不与其它任务混淆。
     */
    fun open(url: String, anchorProvider: (String) -> String? = { LogFilterUtils.extractPrimaryDomain(it) }): Session {
        val entry = WebViewLoadPlan.requirePublicHttpUrl(url, "webview_capture url")
        return Session(
            contextId = "cap:" + UUID.randomUUID().toString(),
            entryUrl = entry,
            sourceAnchor = anchorProvider(entry),
        )
    }

    /**
     * 一次抓包会话：逐请求判定与预算扣减都是同步的
     * （shouldInterceptRequest 在拦截线程被调用，不能挂起也不能 block）。
     *
     * @param dnsGuard DNS 私网守卫：解析目标 host，返回非空原因即拒绝（防 DNS 重绑定绕过
     *   字面校验）。在拦截线程里同步调用，实现方应带小结果缓存避免每次都打 DNS；
     *   null = 关闭（JVM 单测不传，生产由引擎注入缓存实现）。
     * @param sessionMode 会话形态标记：[MODE_INTERACTIVE]=应用内可见浏览器页、
     *   [MODE_HEADLESS]=MCP webview_capture 一次性无头。结算时写进会话汇总行，
     *   供 list_captures/历史页区分来源——旧数据没有该行则如实显示「未知来源」。
     */
    class Session(
        val contextId: String,
        val entryUrl: String,
        /** 入口注册域；解析失败时为 null（不影响加载，只是该会话没有归属锚点）。 */
        val sourceAnchor: String?,
        val sessionMode: String = MODE_HEADLESS,
        val maxResources: Int = MAX_RESOURCES,
        val sessionBudgetBytes: Long = MAX_SESSION_BYTES,
        val resourceBudgetBytes: Long = MAX_RESOURCE_BODY_BYTES,
        val dnsGuard: ((String) -> String?)? = null,
    ) {
        private val lock = Any()
        private var counted = 0
        private var txnCounter = 0
        private var consumedBytes = 0L
        private var exhausted = false
        /** 已产生完整证据行（响应行/错误行/观察行）的事务序号：onReceived* 回调按它去重防双写。 */
        private val recorded = HashSet<Int>()
        /** 最近一次放行事务的 (method+url → txnSeq)：onReceived* 回调按 URL 找回该事务做去重。 */
        private val lastTxnByKey = HashMap<String, Int>()
        // ---- 写入闸（finishSession 收尾竞态防护，全部字段只经 lock 访问）----
        /** true 后新回调的写请求被拒（闸外产生的写不再入队）；闸内已开始写的块允许写完。 */
        private var writesClosed = false
        /** 闸内已开始、尚未结束的写块数：关闸后归零时由最后一个 endWrite 触发 pendingSettle。 */
        private var inFlightWrites = 0
        /** closeWrites 登记的结算动作：等全部在途写块的 recordAsync 都已 FIFO 入队后才执行。 */
        private var pendingSettle: (() -> Unit)? = null
        /** 供给成功/仅观察/阻断 行计数（引擎侧原子累加可读——拦截线程与主线程都会碰）。 */
        val capturedRows = java.util.concurrent.atomic.AtomicInteger(0)
        val observedRows = java.util.concurrent.atomic.AtomicInteger(0)
        val blockedRows = java.util.concurrent.atomic.AtomicInteger(0)

        /** 已产生证据（请求行/响应行）的资源数。 */
        val countedResources: Int get() = synchronized(lock) { counted }
        /** 会话容量是否已耗尽：true 时后续行只剩请求行。 */
        val sessionExhausted: Boolean get() = synchronized(lock) { exhausted }

        /** 标记一条事务已落库完整行；重复标记返回 false（调用方据此跳过重复记录）。按 txnSeq 去重，不按 URL。 */
        fun markRecorded(txnSeq: Int): Boolean = synchronized(lock) { recorded.add(txnSeq) }
        fun isRecorded(txnSeq: Int): Boolean = synchronized(lock) { txnSeq in recorded }

        /** 找最近一次放行事务的序号（onReceivedHttpError/onReceivedError 需要把错误行对回同一事务去重）。 */
        fun lastTxnSeq(url: String, method: String): Int = synchronized(lock) {
            lastTxnByKey["${method.uppercase()} $url"] ?: 0
        }

        /**
         * 写入闸开始：进入一段「会产生 recordAsync 证据行」的代码块。返回 false 表示会话已关闸
         * （finishSession 已发起结算）：调用方跳过写库路径，但阻断/吞掉私网导航等功能行为仍保留。
         * 成功的 beginWrite 必须配对 [endWrite]（用 [withWriteGate] 或 try/finally）。
         */
        fun beginWrite(): Boolean = synchronized(lock) {
            if (writesClosed) false else { inFlightWrites++; true }
        }

        /**
         * 关闸：此后 [beginWrite] 一律 false。若当前无在途写块立即执行 [settle]（由它入队结算行）；
         * 否则等最后一个 [endWrite] 触发。这样在途供给的 recordAsync 恒先于结算行入队
         * （写库线程单线程 FIFO），结算行落库时刻即「会话不再有在途写入」时刻。
         * settle 在锁外执行；closeWrites 幂等——重复调用后 pendingSettle 已被取空，不会二次结算。
         */
        fun closeWrites(settle: () -> Unit) {
            val runNow = synchronized(lock) {
                writesClosed = true
                if (pendingSettle == null) pendingSettle = settle
                inFlightWrites == 0
            }
            if (runNow) drainSettle()
        }

        /** 写块结束：关闸后若为最后一块在途写，触发结算入队。 */
        fun endWrite() {
            val lastOut = synchronized(lock) {
                inFlightWrites--
                writesClosed && inFlightWrites == 0 && pendingSettle != null
            }
            if (lastOut) drainSettle()
        }

        /** 取走并执行结算动作（锁外 invoke——settle 内含 recordAsync，不占会话锁）。 */
        private fun drainSettle() {
            val settle = synchronized(lock) { pendingSettle.also { pendingSettle = null } }
            settle?.invoke()
        }

        /** 闸内执行 [block]：闸已关返回 null（调用方据此跳过写库路径、走 WebView 自加载/仅阻断）。 */
        fun <T> withWriteGate(block: () -> T): T? =
            if (beginWrite()) try { block() } finally { endWrite() } else null

        /**
         * shouldInterceptRequest 的同步判定（WebResourceRequest 薄壳委托纯签名版）。
         */
        fun decide(request: WebResourceRequest): Decision = decide(
            url = request.url?.toString().orEmpty(),
            method = request.method,
            headers = request.requestHeaders ?: emptyMap(),
            forMainFrame = request.isForMainFrame,
        )

        /**
         * 纯签名判定（JVM 可测）：
         * - URL 必须 http(s) 且非回环/私网/保留段（字面校验）；
         * - dnsGuard 非空时再查域名解析结果：公网域名解析到私网/回环/保留 IP（DNS 重绑定）也拒绝；
         * - 方法保持浏览器原样——拿不到 body 的 POST/PUT 只记「请求观察」；
         * - 容量耗尽后续行仍记请求行（证据保留），但不再供给/收正文。
         */
        fun decide(url: String, method: String?, headers: Map<String, String>, forMainFrame: Boolean): Decision {
            if (!isCapturableUrl(url)) {
                return Decision(allow = false, requestNote = "BLOCKED：非 http(s)/内嵌凭据/回环私网保留地址，已阻断加载")
            }
            val host = url.toHttpUrlOrNull()?.host.orEmpty()
            val dnsReason = if (host.isNotEmpty()) dnsGuard?.invoke(host) else null
            if (dnsReason != null) {
                return Decision(allow = false, requestNote = "BLOCKED：DNS 解析到私网/回环/保留地址（$dnsReason），已阻断加载")
            }
            val m = method?.uppercase() ?: "GET"
            val bodyObservable = m in BODYLESS_METHODS
            val (seq, exhaustedNow) = synchronized(lock) {
                if (counted >= maxResources) return Decision(allow = false, requestNote = "BLOCKED：会话已达 $maxResources 条资源上限")
                counted += 1
                txnCounter += 1
                // 会话容量逐行扣减：请求行本体也算一份（粗粒度预算，防 >2000 条会话刷爆）。
                consumedBytes += url.length + 128
                if (consumedBytes >= sessionBudgetBytes) exhausted = true
                // 供给/错误回调需要把后续 onReceived* 对回同一事务：记录最近一次放行的 seq。
                lastTxnByKey["$m $url"] = txnCounter
                txnCounter to exhausted
            }
            val note = when {
                !bodyObservable -> OBSERVED_ONLY_BODY
                exhaustedNow -> SESSION_EXHAUSTED_NOTE
                else -> null
            }
            return Decision(
                allow = true,
                kind = kindOf(url, headers, forMainFrame, m),
                requestNote = note,
                observeOnly = !bodyObservable || exhaustedNow,
                sessionExhausted = exhaustedNow,
                txnSeq = seq,
            )
        }

        /**
         * 响应行预算结算：返回 (预算, 本次可入账字节上限)。
         * FULL=按 contentLength 全留（仍受上限截断）、TRUNCATED=只留到上限、DROP=只留头。
         */
        fun budgetFor(contentLength: Long): Pair<Budget, Long> = synchronized(lock) {
            if (exhausted) return Pair(Budget.DROP, 0L)
            val remaining = (sessionBudgetBytes - consumedBytes).coerceAtLeast(0)
            val take = minOf(resourceBudgetBytes, remaining)
            when {
                take <= 0 -> Pair(Budget.DROP, 0L)
                contentLength in 1..take -> Pair(Budget.FULL, take)
                contentLength > take -> Pair(Budget.TRUNCATED, take)
                // 未知长度（-1/0）：给到资源上限，读满即止
                else -> Pair(Budget.FULL, take)
            }
        }

        /** 响应行实际入账后回记容量（读了多少字节就扣多少）。 */
        fun consume(bytes: Long) = synchronized(lock) {
            consumedBytes += bytes.coerceAtLeast(0)
            if (consumedBytes >= sessionBudgetBytes) exhausted = true
        }

        /** 资源类型归类：主文档优先，其次字体/脚本/样式/图片/音视频扩展名，fetch-like 头特征归 XHR。 */
        fun kindOf(url: String, headers: Map<String, String>, forMainFrame: Boolean, method: String? = null): ResourceKind {
            if (forMainFrame) return ResourceKind.DOCUMENT
            val path = url.substringBefore('?').substringBefore('#')
            val ext = path.substringAfterLast('.', "").lowercase()
            return when {
                ext in FONT_EXTENSIONS -> ResourceKind.FONT
                ext in setOf("js", "mjs", "vbs") -> ResourceKind.SCRIPT
                ext == "css" -> ResourceKind.STYLESHEET
                ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "ico", "avif", "svg") -> ResourceKind.IMAGE
                ext in setOf("mp3", "m4a", "aac", "flac", "ogg", "wav", "mp4", "ts", "mov", "mkv", "avi", "webm", "flv", "m4s", "m3u8") -> ResourceKind.MEDIA
                methodIsFetchLike(headers) -> ResourceKind.XHR
                else -> ResourceKind.OTHER
            }
        }

        private fun methodIsFetchLike(headers: Map<String, String>): Boolean {
            val accept = headers.entries.firstOrNull { it.key.equals("Accept", ignoreCase = true) }?.value.orEmpty().lowercase()
            val dest = headers.entries.firstOrNull { it.key.equals("Sec-Fetch-Dest", ignoreCase = true) }?.value.orEmpty().lowercase()
            // fetch/XHR/beacon 常带 Sec-Fetch-Dest: empty 或 Accept: application/json / *\/*
            return dest == "empty" || accept.contains("application/json") || accept.contains("*/*")
        }

        companion object {
            /** WebView 侧不会携带请求体的方法：可安全拦截供给（OkHttp 重发语义等价）。 */
            val BODYLESS_METHODS = setOf("GET", "HEAD", "OPTIONS")
            /** 会话形态：结算行的 `mode=` 标记，list_captures 据此区分可见浏览器与无头抓包。 */
            const val MODE_INTERACTIVE = "interactive"
            const val MODE_HEADLESS = "headless"
            private val FONT_EXTENSIONS = setOf("woff", "woff2", "ttf", "otf")
            /** POST/PUT 等带体请求：WebView 拦截拿不到 body，证据行必须如实写观察限制。 */
            const val OBSERVED_ONLY_BODY =
                "OBSERVED_ONLY：WebView 拦截只观察请求头（无请求体），POST/PUT 正文不可得"
            /** 会话容量耗尽后仍保留请求行证据的说明。 */
            const val SESSION_EXHAUSTED_NOTE =
                "SESSION_BUDGET_EXHAUSTED：会话容量耗尽，仅记录请求行"
        }
    }

    /** URL 是否允许进入抓包会话（http(s)、无内嵌凭据、非公网回环/私网/保留段——仅字面判定，DNS 走 Session.dnsGuard）。 */
    fun isCapturableUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
        if (LogFilterUtils.isLoopbackOrPrivate(url)) return false
        return true
    }

    /**
     * captures/<ctx>/ 落盘二进制（字体/图片/媒体原始字节）的保留时长：7 天。
     * 到期由 [purgeExpiredCaptures] 清掉，防 cacheDir 被长期占满；导出正在进行的那次会话
     * 目录由调用方跳过（导出前先读字节再清理其它过期目录）。
     */
    const val CAPTURE_FILE_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

    /** 日志正文里的落盘文件引用标记（解析稳定）：[bodyFile=captures/<ctx>/<seq>.bin]。 */
    const val BODY_FILE_MARK_PREFIX = "[bodyFile="

    /** 二进制资源行的 bodyFile 标记文本：导出器按它定位 cacheDir 下的原始字节。 */
    fun bodyFileMark(relativePath: String): String = "$BODY_FILE_MARK_PREFIX$relativePath]"

    /**
     * 可控清理 cacheDir/captures/：删掉最老修改时间早于 [olderThanMs] 的整个 <ctx> 目录
     * （含其下所有 .bin）。保留 [keepCtx]（如正在导出的会话）不被误删；返回实际删除的目录名。
     * 供导出前清旧目录 + 会话结算后兜底用，避免字体等原始字节无限堆积。
     */
    fun purgeExpiredCaptures(cacheDir: java.io.File, olderThanMs: Long, keepCtx: String? = null): List<String> {
        val root = java.io.File(cacheDir, "captures")
        if (!root.isDirectory) return emptyList()
        val cutoff = System.currentTimeMillis() - olderThanMs
        val removed = mutableListOf<String>()
        root.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val ctx = dir.name
            if (keepCtx != null && ctx == keepCtx.removePrefix("cap:")) return@forEach
            // 目录最老文件即其存活起点：整个会话目录按它判过期（不按单文件，避免半删）。
            val oldest = dir.listFiles()?.minOfOrNull { it.lastModified() } ?: dir.lastModified()
            if (oldest < cutoff) {
                runCatching { dir.deleteRecursively() }.onSuccess { removed += ctx }
            }
        }
        return removed
    }

    /**
     * 该 HTTP 状态码能否安全用 WebResourceResponse 供给：
     * 3xx（301/302/303/307/308）不能——构造给 WebView 无意义甚至 IllegalArgumentException。
     * 且应如实记为 OBSERVED_ONLY：供给侧（OkHttp）拿到了 3xx，但 WebView 会对**同一个 URL**
     * 重新发起请求、由它自己的网络栈处理跳转——不是引擎"逐跳抓到下一跳"。204/304 同理不供给。
     */
    fun isSuppliableStatus(code: Int): Boolean =
        (code in 200..299 || code in 400..599) && code != 204 && code != 304

    /**
     * 响应行的 Content-Type 辅助归类：URL 扩展名缺失时按声明类型补判
     * （font/ 前缀、javascript、css、json、image/、video/、audio/），仍命中不了再回落 OTHER。
     */
    fun kindFromContentType(contentType: String?): ResourceKind {
        val ct = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (ct.isEmpty()) return ResourceKind.OTHER
        return when {
            ct.startsWith("font/") || ct.contains("font") && ct.startsWith("application/") -> ResourceKind.FONT
            ct.contains("javascript") || ct.contains("ecmascript") -> ResourceKind.SCRIPT
            ct == "text/css" -> ResourceKind.STYLESHEET
            ct.contains("json") || ct.endsWith("+json") -> ResourceKind.XHR
            ct.startsWith("image/") -> ResourceKind.IMAGE
            ct.startsWith("video/") || ct.startsWith("audio/") -> ResourceKind.MEDIA
            ct.startsWith("text/html") || ct.startsWith("text/") -> ResourceKind.DOCUMENT
            else -> ResourceKind.OTHER
        }
    }

    // ------------------------------------------------------------------
    // cap: 会话目录与资源引用（落盘二进制：字体/图片/媒体原始字节）
    // ------------------------------------------------------------------

    /**
     * contextId 对应的落盘目录名（cacheDir/captures/<目录名>）。
     * contextId 形如 `cap:<uuid>`；不是 cap: 前缀或剥掉前缀后为空白一律返回 null——
     * 即任何输入都不可能把目录名解析成空串/`..`/路径分隔符外的任意位置。
     */
    fun captureDirName(contextId: String): String? {
        val name = contextId.removePrefix("cap:")
        if (name == contextId) return null            // 没有 cap: 前缀
        if (name.isBlank()) return null
        if (name.any { it == '/' || it == '\\' }) return null
        if (name == "." || name == "..") return null  // 特殊目录名，不能落盘
        return name
    }

    /**
     * 解析抓包落盘资源相对路径（`captures/<ctxDir>/<file>`）：做校验而非盲目拼路径。
     * 三段硬条件（缺一即 null）：
     * - 正则是 `captures/<一段>/<一段>`，段内不允许 `/`、`\`、`.`（即不含 `..`/`.` 段）；
     * - 目录段必须等于 [contextId] 会话自己的目录名——别的会话的 cap: 目录不能读；
     * - 文件名段只允许 [A-Za-z0-9._-]（写盘端本就是 `<txnSeq>.bin`，多余字符说明不是合法产物）。
     * 通过后 canonical 归一化再逐段比名，防大小写/重解析差异绕过。
     */
    fun captureResourcePath(cacheDir: File, contextId: String, resourceName: String): File? {
        val expectedDir = captureDirName(contextId) ?: return null
        val m = RESOURCE_PATH_REGEX.matchEntire(resourceName.trim()) ?: return null
        val dirName = m.groupValues[1]
        val fileName = m.groupValues[2]
        if (dirName != expectedDir) return null
        val root = File(cacheDir, "captures")
        val dir = File(root, dirName)
        val file = File(dir, fileName)
        return runCatching {
            // canonical 路径必须在 captures/<ctxDir>/ 内（双保险：正则已挡 ..，这里再挡符号链接等重解析）。
            if (file.canonicalFile.parentFile != dir.canonicalFile) return null
            if (dir.canonicalFile.parentFile != root.canonicalFile) return null
            file
        }.getOrNull()
    }

    private val RESOURCE_PATH_REGEX = Regex("""^captures/([^/\\.\s]+)/([A-Za-z0-9._-]+)$""")

    /**
     * 从日志正文提取 `[bodyFile=captures/<ctx>/<name>]` 资源名（与 [bodyFileMark] 同约定，
     * 解析规则和导出器 [com.mina.legadostudio.export.CaptureSessionExporter.bodyFileRef] 一致）。
     * 只提取第一个标记；非法形态（缺前缀/含 `..`）返回 null。
     */
    fun bodyFileRefFrom(responseBody: String): String? {
        val m = BODY_FILE_REF_PATTERN.find(responseBody) ?: return null
        val p = m.groupValues[1].trim()
        return if (p.startsWith("captures/") && !p.contains("..")) p else null
    }

    private val BODY_FILE_REF_PATTERN = Regex("""\[bodyFile=([^\]]+)\]""")

    /**
     * 会话汇总行（originKind=webview_capture 的结算行）正文里的 `mode=<形态>` 标记解析。
     * finishSession 写的是 `… mode=interactive …` / `mode=headless`；解析不到即旧数据 → null（未知）。
     */
    fun sessionModeFromSummary(summaryBody: String?): String? {
        if (summaryBody.isNullOrBlank()) return null
        val m = Regex("""(?:^|\s)mode=([A-Za-z_]+)""").find(summaryBody) ?: return null
        return m.groupValues[1]
    }

    /**
     * poll_capture 游标校验：会话当前最大 id < 客户端游标时，
     * 说明该游标指向已被删除的未来的行（或 contextId 传错），不是「没有新增」。
     */
    fun isStaleCursor(afterLogId: Long, latestLogId: Long?): Boolean =
        latestLogId != null && afterLogId > latestLogId
}
