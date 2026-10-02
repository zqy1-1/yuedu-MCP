package com.mina.legadostudio.runtime

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import com.google.gson.Gson
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.network.HttpOrigin
import com.mina.legadostudio.verification.RuntimeCookieStore
import com.mina.legadostudio.verification.WebViewPageLoader
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

class RhinoEvaluator(
    private val fetcher: HttpFetcher,
    private val gson: Gson,
    private val webViewLoader: WebViewPageLoader? = null,
    private val cookies: RuntimeCookieStore? = null,
    private val userAgentProvider: () -> String = { HttpFetcher.DEFAULT_UA },
    private val originProvider: () -> HttpOrigin? = { null },
    private val httpLogs: com.mina.legadostudio.network.HttpLogRecorder? = null,
    /** 图片验证码：入参图 URL，返回用户输入的答案（null=超时/取消）；由 StudioApplication 注入 */
    private val verificationProvider: (suspend (String) -> String?)? = null,
) {
    @Keep data class Result(
        @SerializedName("value") val value: String?,
        @SerializedName("logs") val logs: List<String>,
        @SerializedName("elapsedMs") val elapsedMs: Long,
    )
    data class RawResult(val value: Any?, val logs: List<String>, val elapsedMs: Long)

    fun evaluate(js: String, baseUrl: String = "", previous: Any? = null, bindings: Map<String, Any?> = emptyMap(), origin: HttpOrigin? = null): Result {
        val raw = evaluateRaw(js, baseUrl, previous, bindings, origin)
        return Result(normalize(raw.value), raw.logs, raw.elapsedMs)
    }

    fun evaluateRaw(js: String, baseUrl: String = "", previous: Any? = null, bindings: Map<String, Any?> = emptyMap(), origin: HttpOrigin? = null): RawResult {
        require(js.isNotBlank()) { "JavaScript 不能为空" }
        val logs = mutableListOf<String>()
        // 登录桩锚点：一次求值内 java.upLoginData 与 source.getLoginInfo* 必须同键——
        // 优先书源锚点（debug 链路经 BINDING_SOURCE_ANCHOR 传入 bookSourceUrl），退到 origin 锚点、baseUrl。
        val loginAnchor = (bindings[BINDING_SOURCE_ANCHOR] as? String)
            ?: (origin ?: originProvider())?.sourceAnchor ?: baseUrl
        val api = StudioJsApi(
            fetcher, logs::add, webViewLoader, cookies, userAgentProvider, httpLogs,
            useCookieJar = bindings[BINDING_USE_COOKIE_JAR] as? Boolean ?: true,
            loginAnchor = loginAnchor,
            originProvider = { origin ?: originProvider() },
            verificationProvider = verificationProvider,
        )
        val started = System.currentTimeMillis()
        // 每次求值挂一套全新标准对象作用域：var 声明与原型修改都不跨调用泄漏；
        // 跨调用共享只能显式走 cache / source（bindings）等注入对象，没有隐式共享全局。
        val scope = ScriptBindings()
        scope.put("java", api)
        scope.put("result", previous)
        scope.put("baseUrl", baseUrl)
        scope.put("src", previous)
        // 官方同名对象：cookie/cache/source。bindings 传入的 source 状态图包成 SourceJsApi，JS 写入回传调试流程
        scope.put("cookie", CookieJsApi(cookies))
        scope.put("cache", CacheJsApi())
        val jsoup = JsoupJsApi()
        scope.put("Jsoup", jsoup)
        // AI 常把官方 java.ajax 风格与 org.jsoup.Jsoup 包路径混写；沙箱没有 Java 包树，
        // 用 Map 桥出 org.jsoup.Jsoup 的常用静态入口，调用落到同一 JsoupJsApi 代理。
        scope.put("org", mapOf("jsoup" to mapOf("Jsoup" to jsoup)))
        bindings.forEach { (key, item) -> if (key != "source" && key != BINDING_JSLIB && key != BINDING_SOURCE_ANCHOR) scope.put(key, item) }
        @Suppress("UNCHECKED_CAST")
        scope.put("source", when (val bound = bindings["source"]) {
            is SourceJsApi -> bound
            is MutableMap<*, *> -> SourceJsApi(bound as MutableMap<String, String>, loginAnchor, logs::add)
            else -> SourceJsApi(anchor = loginAnchor, warn = logs::add)
        })
        // jsLib 装载：官方 BookSource 的 jsLib 字段是纯 JS 片段（函数/全局 var 声明），
        // 官方 App 把它 eval 进该书源的共享作用域供所有规则 JS 直接调用。沙箱按书源隔离实现：
        // 每书源建立一个 jsLib 父层作用域（自身挂在全新标准对象上），bindings 作用域 chainTo 其下。
        // 表达式里同名 var/赋值只写进本作用域的 globalThis（TopLevel.put 直写 globalThis），
        // 不覆盖父层 jsLib 函数；jsLib 的 `const {java,source}=this` 惯用法依赖
        // FEATURE_LEGADO_DYNAMIC_DEFAULT_THIS：函数被调用时 this 取当次顶层作用域的 globalThis，
        // 即表达式所在作用域，java/cookie/source 等注入绑定在调用点生效。
        // jsLib 为 http(s) URL 时在 EmbeddedLegadoRuntime 层被拒（不做外部加载），到不了这里；
        // eval_js 不传该 binding 时不会凭空装载任何 jsLib。
        @Suppress("UNCHECKED_CAST")
        val jsLibProvider = bindings[BINDING_JSLIB] as? () -> String?
        val parent = jsLibProvider?.let { provider ->
            val libSource = provider()
            if (libSource.isNullOrBlank()) null
            else RhinoScriptEngine.newStandardTopLevel().also { libScope ->
                try {
                    RhinoScriptEngine.eval(libSource, libScope)
                } catch (error: Exception) {
                    throw decorateApiError(
                        com.script.ScriptException("jsLib 执行失败：${error.message.orEmpty()}", "jsLib", -1)
                            .also { it.initCause(error) }
                    )
                }
            }
        }
        scope.chainTo(parent ?: RhinoScriptEngine.newStandardTopLevel())
        val value = try {
            RhinoScriptEngine.eval(js, scope)
        } catch (error: Exception) {
            throw decorateApiError(error)
        }
        return RawResult(value, logs, System.currentTimeMillis() - started)
    }

    /**
     * 只对确凿的「方法不存在 / 签名不匹配 / 调用的不是函数」异常追加沙箱 API 清单提示；
     * 其余 JS 一般异常原样抛出，不猜测改写。
     */
    private fun decorateApiError(error: Exception): Exception {
        val message = error.message.orEmpty()
        val firstLine = message.substringBefore('\n')
        val sureMissingApi = MISSING_API_PATTERNS.any { it.containsMatchIn(firstLine) }
        if (!sureMissingApi) return error
        return com.script.ScriptException(
            message + API_HINT,
            (error as? com.script.ScriptException)?.fileName,
            (error as? com.script.ScriptException)?.lineNumber ?: -1,
            (error as? com.script.ScriptException)?.columnNumber ?: -1,
        ).also { it.initCause(error.cause ?: error) }
    }

    companion object {
        /**
         * bindings 内部保留键：值是 `() -> String?`，惰性返回该书源的 jsLib 纯 JS 片段。
         * 由 EmbeddedLegadoRuntime.debug 按书源传入；eval_js 不传 → 不装载。
         * 返回 null/空白 = 无 jsLib；返回 http(s) URL 字符串表示书源声明了外链库，
         * 沙箱不外部加载（由调用方决定报错或降级）。
         */
        const val BINDING_JSLIB = "__legadoJsLib"

        /**
         * bindings 内部保留键：值是 `Boolean`，书源级 `enabledCookieJar` 开关——
         * `false` 时本次求值里 java.ajax/connect/get/post 的二次请求禁用自动 Cookie jar
         * （不回带、不吸收 Set-Cookie；显式用户 cookie 经 cookieHeaderProvider 照旧回带）。
         * 由 EmbeddedLegadoRuntime.debug 按书源注入；eval_js 等不传该键 → 缺省 true（旧行为不变）。
         */
        const val BINDING_USE_COOKIE_JAR = "__legadoUseCookieJar"

        /**
         * bindings 内部保留键：值是 `String`，书源锚点（bookSourceUrl）——
         * 进程内登录信息桩（source.getLoginInfo/putLoginInfo、java.upLoginData）按它分桶共享。
         * 由 EmbeddedLegadoRuntime.debug 按书源注入；不传则回退 origin 锚点 / baseUrl。
         */
        const val BINDING_SOURCE_ANCHOR = "__legadoSourceAnchor"
        /**
         * htmlunit-core-js 对「方法不存在/签名不匹配」的固定报错文案。
         * jar 内带 Messages_zh_CN 本地化资源，错误文案随 JVM Locale 走——
         * zh_CN 下是「找不到函数/找不到方法/未定义/不是一个函数」等中文，英文正则不匹配，所以两套都列。
         */
        private val MISSING_API_PATTERNS = listOf(
            // en
            Regex("Cannot find function \\S+"),
            Regex("Method \"\\S+\" not found"),
            Regex("Cannot call method \"\\S+\" of"),
            Regex("Cannot call property \\S+ in object"),
            Regex("\\S+ is not a function"),
            Regex("Java class \"\\S+\" has no public instance field or method"),
            Regex("The choice of Java method .* is ambiguous"),
            Regex("Constructor for \"\\S+\" not found"),
            Regex("\\S+ is not defined"),
            // zh_CN（Messages_zh_CN.properties）
            Regex("找不到函数 \\S+"),
            Regex("找不到方法 “\\S+”"),
            Regex("无法调用 \\S+ 的方法"),
            Regex("无法调用对象 \\S+ 中的属性"),
            Regex("\\S+ 不是函数"),
            Regex("\\S+ 未定义"),
            Regex("Java 方法 .* 的选择不明确"),
            Regex("未找到 “\\S+” 的构造函数"),
        )
        // 与 evaluateRaw 实际注入的对象/方法保持一致；新增 API 时同步更新本清单与
        // references/js-api.md「Studio 沙箱可用 API 清单」一节。
        private const val API_HINT = "\n【API 提示】以上是确凿的「方法不存在/签名不匹配」错误。沙箱实际可用 API：\n" +
            "java.*：log(msg) logType(obj) toast(msg) longToast(msg)；ajax(url) ajaxAll(urls) connect(url[,header][,methodOrTimeout][,body][,charset]) " +
            "get(url,headers?) post(url,body,headers?) head(url,headers?) webView(url|html,url,js) startBrowserAwait(url,title?,refetch?) fetchFont(url) decodeWoff2(data)；" +
            "setContent(html) getString(rule,content?) getStringList(rule,content?) put(key,value) get(key或url[,headers])；" +
            "encodeURI(str,charset?) base64Encode(str) base64Decode(str,charset?) base64DecodeToByteArray(str) " +
            "md5Encode(str) md5Encode16(str) sha1Encode(str) sha256Encode(str) digestHex(str,算法) digestBase64Str(str,算法) HMacHex(str,算法,key) HMacBase64(str,算法,key) " +
            "hexEncodeToString(str) hexDecodeToString(str) hexDecodeToByteArray(str) strToBytes(str,charset?) bytesToStr(bytes,charset?) " +
            "htmlFormat(str) timeFormat(time[,format]) timeFormatUTC(time,format,时区偏移毫秒) toURL(url[,baseUrl]) randomUUID() getWebViewUA() " +
            "getCookie(url[,key]) setCookie(url,cookie) createSymmetricCrypto(transformation,key,iv?) java.lang.Thread.sleep(ms)\n" +
            "Jsoup.*（全局对象）：Jsoup.parse(html[,baseUri]) Jsoup.parseBodyFragment(html) Jsoup.connect(url)；org.jsoup.Jsoup.* 写法已桥接到同一对象，但 Packages.org.jsoup 不可用。\n" +
            "cookie.*：getCookie(url) getKey(url,key) setCookie(url,cookie) replaceCookie(url,cookie) removeCookie(url) setWebCookie(url,cookie)\n" +
            "cache.*：put(key,value,saveTime秒?) get(key) delete(key) putMemory(key,value) getFromMemory(key) deleteMemory(key)\n" +
            "source.*：getVariable() setVariable(json) putVariable(json) put(key,value) get(key) " +
            "getLoginInfo([key]) getLoginInfoMap() putLoginInfo(json或map或key,value)（登录信息为进程内桩存储，默认空）\n" +
            "内置变量：java result(上一步结果) src(同 result) baseUrl cookie cache source。\n" +
            "java.getVerificationCode(url)：真实实现——拉验证码图→验证中心 image_code 会话等用户输入（最长120s），返回用户输入的字符串；\n" +
            "官方有但沙箱为桩实现（能调用、返回空值并记日志提示，真机行为不同）：java.upLoginData(data?)\n" +
            "官方有但沙箱未实现（写了必报错）：java.getElement/getElements java.importScript java.cacheFile java.toNumChapter java.t2s/s2t " +
            "java.androidId java.openUrl java.searchBook java.startBrowser java.getReadBookConfig/getThemeConfig " +
            "java.downloadFile/readTxtFile 等文件读写 java.getZip*/getRar*/get7z* java.createAsymmetricCrypto/createSign；" +
            "java.lang.* 除 Thread.sleep 外全部不可用，标准库类请走 Packages.*（如 new Packages.java.lang.String(b,\"UTF-8\")）。\n" +
            "完整说明见 references/js-api.md「Studio 沙箱可用 API 清单」。"
    }

    private fun normalize(value: Any?): String? = when (value) {
        null -> null
        is String, is Number, is Boolean -> value.toString()
        else -> runCatching { gson.toJson(value) }.getOrElse { value.toString() }
    }
}

class StudioJsApi(
    private val fetcher: HttpFetcher,
    private val logger: (String) -> Unit,
    private val webViewLoader: WebViewPageLoader? = null,
    private val cookies: RuntimeCookieStore? = null,
    private val userAgentProvider: () -> String = { HttpFetcher.DEFAULT_UA },
    private val httpLogs: com.mina.legadostudio.network.HttpLogRecorder? = null,
    private val useCookieJar: Boolean = true,
    /** 登录桩锚点（书源 bookSourceUrl）；与 source.getLoginInfo* 同键，由 evaluateRaw 统一解析 */
    private val loginAnchor: String = "",
    private val originProvider: () -> HttpOrigin? = { null },
    /**
     * 图片验证码会话供应器：入参验证码图 URL，返回用户在验证中心输入的答案（null=超时/取消）。
     * 由 RhinoEvaluator 构造时注入（包 VerificationCoordinator.create+awaitAnswer 与抓图）。
     */
    private val verificationProvider: (suspend (String) -> String?)? = null,
) {
    /** 官方兼容：java.lang.Thread.sleep(ms)（沙箱仅放行这一个 java.lang 用法，其余包路径不可用） */
    @JvmField
    val lang: StudioLangApi = StudioLangApi()

    /** 官方 java.log 返回入参本身，便于链式调试 */
    fun log(message: Any?): Any? {
        logger(message?.toString().orEmpty())
        return message
    }

    fun toast(message: Any?) { logger("toast: ${message?.toString().orEmpty()}") }
    fun longToast(message: Any?) { logger("longToast: ${message?.toString().orEmpty()}") }

    /** 官方 java.logType(obj)：打印对象 Java 类型名（null 打印 "null"），用于调试 */
    fun logType(any: Any?) { logger(any?.javaClass?.name ?: "null") }

    /**
     * 官方 java.upLoginData(data?)：刷新登录界面用户数据。沙箱无登录 UI，退化为写进程内登录桩存储
     * （source.getLoginInfo* 可读回），并记一行桩提示。null = 清空（真机重置为默认值）。
     */
    @JvmOverloads
    fun upLoginData(data: Any? = null) {
        logger("java.upLoginData 为沙箱桩实现：沙箱无登录界面，仅写入进程内登录信息（source.getLoginInfo* 可见）；真机会刷新书源 loginUi 表单值")
        when (data) {
            null -> RuntimeLoginStore.map(loginAnchor).clear()
            is Map<*, *> -> data.forEach { (k, v) -> if (k != null) RuntimeLoginStore.map(loginAnchor)[k.toString()] = v?.toString().orEmpty() }
            is String -> runCatching {
                com.google.gson.JsonParser.parseString(data).asJsonObject.entrySet()
                    .forEach { RuntimeLoginStore.map(loginAnchor)[it.key] = it.value.asString }
            }
            else -> { /* 不认识的形态忽略，别让脚本崩 */ }
        }
    }

    /**
     * 官方 java.getVerificationCode(imageUrl)：拉验证码图 → 在 App 验证中心弹 image_code 会话等用户输入。
     * 返回用户在验证中心提交的验证码文本；超时/用户关闭返回空串（官方约定书源脚本对空值降级）。
     *
     * 沙箱行为对齐真机语义：真机弹 VerificationCodeDialog 阻塞等输入；这里创建
     * kind=image_code 的验证会话走「验证中心」UI，等 answer 写回。需要 [verificationProvider]
     * 由 StudioApplication 注入（把 VerificationCoordinator.create+awaitAnswer 包成一个函数）。
     */
    fun getVerificationCode(imageUrl: Any? = null): String {
        val url = imageUrl?.toString().orEmpty()
        val provider = verificationProvider
        if (provider == null) {
            logger("java.getVerificationCode($url) 需要验证中心支持：当前沙箱未注入 verificationProvider，返回空串")
            return ""
        }
        logger("java.getVerificationCode($url)：拉取验证码图并在验证中心等待用户输入（最长 120s）…")
        return try {
            kotlinx.coroutines.runBlocking { provider(url) ?: "" }
        } catch (e: Exception) {
            logger("java.getVerificationCode 失败：${e.message.orEmpty().take(120)}")
            ""
        }
    }

    fun ajax(url: String): String = fetcher.fetch(HttpFetcher.FetchRequest(url, origin = withKind("js_ajax"), useCookieJar = useCookieJar)).body
    fun connect(url: CharSequence): StudioJsResponse = connectInternal(url.toString(), null, null, null, null)

    fun connect(url: CharSequence, header: Any?): StudioJsResponse = connectInternal(url.toString(), header, null, null, null)

    fun connect(url: CharSequence, header: Any?, param3: Any?): StudioJsResponse {
        // param3 可能是 timeout (数值) 或 method (如 "POST")
        val str3 = param3?.toString().orEmpty().trim()
        val isMethod = str3.equals("GET", true) || str3.equals("POST", true) || str3.equals("HEAD", true)
        val method = if (isMethod) str3.uppercase() else null
        return connectInternal(url.toString(), header, method, null, null)
    }

    fun connect(url: CharSequence, header: Any?, method: Any?, body: Any?): StudioJsResponse {
        return connectInternal(url.toString(), header, method?.toString(), body?.toString(), null)
    }

    fun connect(url: CharSequence, header: Any?, method: Any?, body: Any?, charset: Any?): StudioJsResponse {
        return connectInternal(url.toString(), header, method?.toString(), body?.toString(), charset?.toString())
    }

    private fun connectInternal(url: String, header: Any?, explicitMethod: String?, explicitBody: String?, explicitCharset: String?): StudioJsResponse {
        val parsed = LegadoUrlOptions.parse(url)
        val extra = when (header) {
            is Map<*, *> -> header.entries.associate { it.key.toString() to it.value.toString() }
            is CharSequence -> runCatching { com.google.gson.JsonParser.parseString(header.toString()).asJsonObject.entrySet().associate { it.key to it.value.asString } }.getOrDefault(emptyMap())
            else -> emptyMap()
        }
        val absolute = parsed.url.ifBlank { url }
        val finalMethod = explicitMethod ?: parsed.method
        val finalBody = explicitBody ?: parsed.body
        val finalCharset = explicitCharset ?: parsed.charset
        val result = fetcher.fetch(HttpFetcher.FetchRequest(absolute, finalMethod, parsed.headers + extra, finalBody, finalCharset, origin = withKind("js_connect"), useCookieJar = useCookieJar))
        return StudioJsResponse(result.code, result.finalUrl, result.body, result.headers, result.elapsedMs, result.rawBytes)
    }
    @JvmOverloads
    fun startBrowserAwait(url: String, title: Any? = null, refetch: Any? = null): String {
        throw com.mina.legadostudio.verification.VerificationRequiredException(url, com.mina.legadostudio.verification.DomainKey.fromUrl(url))
    }
    private var currentDocument: org.jsoup.nodes.Document? = null
    private val memoryStore = java.util.concurrent.ConcurrentHashMap<String, Any?>()

    fun setContent(html: Any?) {
        currentDocument = org.jsoup.Jsoup.parse(html?.toString().orEmpty())
    }

    fun put(key: String, value: Any?): Any? {
        if (value == null) memoryStore.remove(key) else memoryStore[key] = value
        return value
    }

    @JvmOverloads
    fun get(keyOrUrl: String, headers: Any? = null): Any? {
        if (keyOrUrl.startsWith("http://", ignoreCase = true) ||
            keyOrUrl.startsWith("https://", ignoreCase = true) ||
            keyOrUrl.startsWith("/")
        ) {
            return request(keyOrUrl, "GET", null, headers)
        }
        return memoryStore[keyOrUrl]
    }
    @JvmOverloads
    fun post(url: String, body: String, headers: Any? = null): StudioJsResponse = request(url, "POST", body, headers)
    @JvmOverloads
    fun head(url: String, headers: Any? = null): StudioJsResponse = request(url, "HEAD", null, headers)

    @JvmOverloads
    fun getString(rule: String, content: Any? = null): String {
        val root = resolveElement(content)
        val (css, attr) = parseRuleParts(rule)
        val target = if (css.isNotBlank()) root.select(css).firstOrNull() ?: root else root
        return extractValue(target, attr)
    }

    @JvmOverloads
    fun getStringList(rule: String, content: Any? = null): List<String> {
        val root = resolveElement(content)
        val (css, attr) = parseRuleParts(rule)
        val elements = if (css.isNotBlank()) root.select(css) else org.jsoup.select.Elements(root)
        return elements.map { extractValue(it, attr) }
    }

    private fun resolveElement(content: Any?): org.jsoup.nodes.Element {
        return when (content) {
            is org.jsoup.nodes.Element -> content
            null -> currentDocument?.body() ?: org.jsoup.Jsoup.parse("").body()
            else -> org.jsoup.Jsoup.parse(content.toString()).body()
        }
    }

    private fun parseRuleParts(rule: String): Pair<String, String> {
        val trimmed = rule.trim()
        if ('@' !in trimmed) return trimmed to "all"
        val parts = trimmed.split('@').filter { it.isNotBlank() }
        if (parts.size == 1) return "" to parts[0]
        val css = parts.dropLast(1).joinToString(" ")
        val attr = parts.last()
        return css to attr
    }

    private fun extractValue(element: org.jsoup.nodes.Element, attr: String): String {
        return when (attr.lowercase()) {
            "text", "textnodes" -> element.text()
            "html" -> element.html()
            "all" -> element.outerHtml()
            else -> element.attr(attr).ifBlank { element.text() }
        }
    }
    @JvmOverloads
    fun encodeURI(value: String, charset: String = "UTF-8"): String = URLEncoder.encode(value, charset).replace("+", "%20")
    fun base64Encode(value: String): String = Base64.getEncoder().withoutPadding().encodeToString(value.toByteArray())
    @JvmOverloads
    fun base64Decode(value: String, charset: String = "UTF-8"): String = String(Base64.getDecoder().decode(value), java.nio.charset.Charset.forName(charset))
    fun base64DecodeToByteArray(value: String): ByteArray = Base64.getDecoder().decode(value)
    fun md5Encode(value: String): String = digest(value, "MD5")
    fun md5Encode16(value: String): String = md5Encode(value).substring(8, 24)
    fun digestHex(data: String, algorithm: String): String = digest(data, algorithm)
    fun digestBase64Str(data: String, algorithm: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance(algorithm).digest(data.toByteArray()))
    /** 官方 JsEncodeUtils.HMacHex/HMacBase64（官方就叫大写 HMac 开头，不额外提供小写别名——大小写并存会让 Rhino 把方法当 CatchableJavaFunction 引用而非调用） */
    @Suppress("FunctionName")
    fun HMacHex(data: String, algorithm: String, key: String): String =
        hmac(data, algorithm, key).joinToString("") { "%02x".format(it) }
    @Suppress("FunctionName")
    fun HMacBase64(data: String, algorithm: String, key: String): String =
        Base64.getEncoder().encodeToString(hmac(data, algorithm, key))
    @JvmOverloads
    fun timeFormat(value: Long, format: String = "yyyy-MM-dd HH:mm:ss"): String = java.text.SimpleDateFormat(format, java.util.Locale.getDefault()).format(java.util.Date(value))
    fun timeFormatUTC(time: Long, format: String, sh: Int): String =
        java.text.SimpleDateFormat(format, java.util.Locale.getDefault()).run {
            timeZone = java.util.SimpleTimeZone(sh, "UTC")
            format(java.util.Date(time))
        }
    @JvmOverloads
    fun toURL(url: String, baseUrl: Any? = null): StudioJsURL = StudioJsURL(url, baseUrl?.toString())
    fun randomUUID(): String = java.util.UUID.randomUUID().toString()
    fun getWebViewUA(): String = userAgentProvider()
    fun getCookie(url: String): String = cookies?.headerFor(url).orEmpty()
    fun getCookie(url: String, key: String?): String {
        val all = getCookie(url)
        if (key.isNullOrBlank()) return all
        return all.split(';').map { it.trim() }.firstOrNull { it.substringBefore('=') == key }?.substringAfter('=', "").orEmpty()
    }
    fun setCookie(url: String, cookie: String) { cookies?.set(url, cookie) ?: error("CookieStore 不可用") }
    fun hexEncodeToString(value: String): String = value.toByteArray().joinToString("") { "%02x".format(it) }
    fun hexDecodeToString(value: String): String = String(value.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    fun sha1Encode(value: String): String = digest(value, "SHA-1")
    fun sha256Encode(value: String): String = digest(value, "SHA-256")
    fun hexDecodeToByteArray(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    fun strToBytes(value: String): ByteArray = value.toByteArray()
    fun strToBytes(value: String, charset: String): ByteArray = value.toByteArray(Charsets.UTF_8.takeIf { charset.equals("UTF-8", true) } ?: java.nio.charset.Charset.forName(charset))
    fun bytesToStr(value: ByteArray): String = String(value)
    fun bytesToStr(value: ByteArray, charset: String): String = String(value, java.nio.charset.Charset.forName(charset))
    fun htmlFormat(value: String): String = org.jsoup.Jsoup.parseBodyFragment(value).body().html()
    fun ajaxAll(urls: Array<String>): Array<String> = urls.map(::ajax).toTypedArray()
    fun webView(html: String?, url: String?, js: String?): String? {
        // 先校验：危险 scheme/回环私网/超大 html 的报错必须优先于「Loader 不可用」浮出
        val plan = com.mina.legadostudio.verification.WebViewLoadPlan.plan(url, html)
        val loader = webViewLoader ?: error("WebView Loader 不可用")
        val target = plan.url
        val origin = withKind("webview")
        // 主文档 + 子资源都记入 HTTP 日志；子资源走 shouldInterceptRequest 观测回调（statusCode=0，响应由 WebView 自管）
        // html 非空时以 url 为 baseURL 直接渲染交接文档（loadDataWithBaseURL）；null/空白仍走 loadUrl
        val result = kotlinx.coroutines.runBlocking {
            loader.load(target, js, html = html) { method, requestUrl, headers ->
                // recordAsync：shouldInterceptRequest 回调线程不能阻塞，高频子资源排队异步写库
                httpLogs?.recordAsync(
                    com.mina.legadostudio.network.HttpLogRecorder.Draft(
                        method = method, url = requestUrl, statusCode = 0,
                        requestHeaders = headers,
                        sourceAnchor = origin?.sourceAnchor, contextId = origin?.contextId,
                        originKind = "webview_resource",
                    )
                )
            }
        }
        httpLogs?.record(
            com.mina.legadostudio.network.HttpLogRecorder.Draft(
                method = "WEBVIEW", url = target, finalUrl = result.finalUrl, statusCode = 200,
                durationMs = result.elapsedMs, responseBody = result.html,
                sourceAnchor = origin?.sourceAnchor, contextId = origin?.contextId,
                originKind = "webview",
            )
        )
        return result.html
    }
    fun webView(url: String): String? = webView(null, url, null)

    /**
     * 字体专用抓取：仅放行字体 Content-Type 或 .woff/.woff2/.ttf/.otf URL 的响应，
     * 服务端 4 字节魔数必须命中 wOF2/wOFF/TTF/OTTO，否则报错。返回原始字体 ByteArray。
     * 与其他 js 请求一样走 HttpFetcher（含日志）；body 恒为空，二进制只在内存 ByteArray 传递。
     */
    fun fetchFont(url: String): ByteArray {
        require(url.startsWith("http://") || url.startsWith("https://")) { "fetchFont 仅支持 http(s) URL" }
        // 凭据注入校验：与 java.ajax/connect 一致，JS 沙箱允许书源访问任意主机（含内网测试），
        // 但 URL 内嵌用户名/密码始终是异常特征，拒绝。完整三层 SSRF 防护在 MCP fetch_font 工具。
        val parsed = url.toHttpUrlOrNull() ?: error("fetchFont：URL 无法解析")
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "fetchFont：URL 不允许内嵌用户名/密码" }
        val result = fetcher.fetch(
            HttpFetcher.FetchRequest(
                url = url, method = "GET", timeoutSec = 30,
                maxBodyBytes = 2 * 1024 * 1024, allowFontBinary = true,
                origin = withKind("js_fetchFont"), useCookieJar = useCookieJar,
            )
        )
        // 只认成功响应；文本/兜底路径也会把正文字节塞进 rawBytes，消费端必须做魔数终检：
        // Content-Type 与扩展名同时撒谎（如 text/html 伪装 .woff2）时在此拒绝。
        val bytes = result.rawBytes
        if (result.code !in 200..299 || bytes == null || result.body.isNotEmpty() ||
            !com.mina.legadostudio.network.Woff2Decoder.looksLikeFont(bytes)) {
            error("fetchFont：响应不是可放行的字体二进制（code=${result.code} FONT_NOT_FONT）：${result.bodyNote.take(160)}")
        }
        return bytes
    }

    /**
     * 解 WOFF2/WOFF/TTF/OTTO 的 cmap，返回 {codepointHex: glyphId} 的 Map。
     * 入参可为 ByteArray（fetchFont 的结果）或 base64 字符串。
     * 注意：返回的是 glyphId 而非汉字——gid→真实汉字需要字形比对，本 API 只做码点→gid。
     */
    fun decodeWoff2(data: Any): Map<String, Int> {
        val bytes = when (data) {
            is ByteArray -> data
            is String -> runCatching { Base64.getDecoder().decode(data) }
                .getOrElse { throw IllegalArgumentException("decodeWoff2：字符串参数必须是 base64 字体数据") }
            else -> throw IllegalArgumentException("decodeWoff2 参数必须是 ByteArray 或 base64 字符串")
        }
        require(bytes.size <= com.mina.legadostudio.network.Woff2Decoder.MAX_INPUT_BYTES) {
            "decodeWoff2：输入超过 ${com.mina.legadostudio.network.Woff2Decoder.MAX_INPUT_BYTES / 1024 / 1024}MB"
        }
        val decoded = com.mina.legadostudio.network.Woff2Decoder.decode(bytes)
        return decoded.mappings.entries.associate { (cp, gid) -> "U+%04X".format(cp) to gid }
    }

    private fun request(url: String, method: String, body: String?, headers: Any?): StudioJsResponse {
        val map = when (headers) {
            is Map<*, *> -> headers.entries.associate { it.key.toString() to it.value.toString() }
            is String -> runCatching { com.google.gson.JsonParser.parseString(headers).asJsonObject.entrySet().associate { it.key to it.value.asString } }.getOrDefault(emptyMap())
            else -> emptyMap()
        }
        val result = fetcher.fetch(HttpFetcher.FetchRequest(url, method, map, body, origin = withKind("js_http"), useCookieJar = useCookieJar))
        return StudioJsResponse(result.code, result.finalUrl, result.body, result.headers, result.elapsedMs, result.rawBytes)
    }
    /** 把当前调用的来源链路与 JS 子请求类型合并，逐事务传递 */
    private fun withKind(kind: String): HttpOrigin? {
        val base = originProvider()
        if (base == null && kind.isBlank()) return null
        return HttpOrigin(
            sourceAnchor = base?.sourceAnchor,
            contextId = base?.contextId,
            originKind = kind,
        )
    }
    private fun digest(value: String, algorithm: String): String = MessageDigest.getInstance(algorithm).digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun hmac(data: String, algorithm: String, key: String): ByteArray {
        val normalized = if (algorithm.startsWith("Hmac", true)) algorithm else "Hmac$algorithm"
        return javax.crypto.Mac.getInstance(normalized).run {
            init(javax.crypto.spec.SecretKeySpec(key.toByteArray(), normalized))
            doFinal(data.toByteArray())
        }
    }

    @JvmOverloads
    fun createSymmetricCrypto(transformation: String, key: Any, iv: Any? = null): StudioSymmetricCrypto =
        StudioSymmetricCrypto(transformation, key, iv)
}

/** 官方阅读兼容的对称加解密对象：java.createSymmetricCrypto("AES/CBC/PKCS5Padding", key, iv) */
class StudioSymmetricCrypto(
    private val transformation: String,
    key: Any,
    iv: Any?,
) {
    private val keyBytes: ByteArray = key.toBytes()
    private val ivBytes: ByteArray? = iv?.toBytes()

    private fun Any.toBytes(): ByteArray = when (this) {
        is ByteArray -> this
        is String -> this.toByteArray()
        else -> this.toString().toByteArray()
    }

    private fun algorithm(): String = transformation.substringBefore('/').ifBlank { "AES" }

    private fun needsIv(): Boolean = transformation.uppercase().contains("/CBC/") ||
        transformation.uppercase().contains("/CFB/") ||
        transformation.uppercase().contains("/OFB/") ||
        transformation.uppercase().contains("/CTR/")

    private fun newCipher(mode: Int): javax.crypto.Cipher {
        val cipher = runCatching { javax.crypto.Cipher.getInstance(transformation) }
            .getOrElse { javax.crypto.Cipher.getInstance(transformation.replace(Regex("(?i)pkcs7padding"), "PKCS5Padding")) }
        val keySpec = javax.crypto.spec.SecretKeySpec(keyBytes, algorithm())
        if (needsIv()) {
            val vector = ivBytes ?: error("$transformation 需要 iv 参数")
            cipher.init(mode, keySpec, javax.crypto.spec.IvParameterSpec(vector))
        } else {
            cipher.init(mode, keySpec)
        }
        return cipher
    }

    fun decrypt(data: ByteArray): ByteArray = newCipher(javax.crypto.Cipher.DECRYPT_MODE).doFinal(data)

    /** 入参为 Base64 字符串，输出 UTF-8 字符串（官方 legado SymmetricCrypto.decryptStr 语义） */
    fun decryptStr(value: String): String = String(decrypt(Base64.getDecoder().decode(value)))

    fun decryptBase64Str(value: String): String = decryptStr(value)

    fun encrypt(data: ByteArray): ByteArray = newCipher(javax.crypto.Cipher.ENCRYPT_MODE).doFinal(data)

    fun encryptStr(value: String): ByteArray = encrypt(value.toByteArray())

    fun encryptBase64Str(value: String): String = Base64.getEncoder().encodeToString(encrypt(value.toByteArray()))
}

/** 官方 java.toURL 返回对象：java.net.URL 解析出的 host/origin/pathname/searchParams */
@Keep
@Suppress("MemberVisibilityCanBePrivate")
class StudioJsURL(url: String, baseUrl: String? = null) {
    val host: String
    val origin: String
    val pathname: String
    val searchParams: Map<String, String>?

    init {
        val mUrl = if (!baseUrl.isNullOrEmpty()) java.net.URL(java.net.URL(baseUrl), url) else java.net.URL(url)
        host = mUrl.host.orEmpty()
        origin = if (mUrl.port > 0) "${mUrl.protocol}://$host:${mUrl.port}" else "${mUrl.protocol}://$host"
        pathname = mUrl.path.orEmpty()
        searchParams = mUrl.query?.let { query ->
            query.split('&').mapNotNull { piece ->
                val pair = piece.split('=', limit = 2)
                if (pair.size == 2) pair[0] to java.net.URLDecoder.decode(pair[1], "utf-8") else null
            }.toMap()
        }
    }
}

data class StudioJsResponse(
    private val status: Int,
    private val finalUrl: String,
    private val content: String,
    private val responseHeaders: Map<String, String>,
    private val duration: Long,
    private val rawBytes: ByteArray? = null,
    private val prior: StudioJsResponse? = null,
) {
    fun code(): Int = status
    /** 官方阅读兼容别名：部分旧书源写 r.statusCode() */
    fun statusCode(): Int = status
    fun url(): String = finalUrl
    fun body(): String = content
    fun headers(): Map<String, String> = responseHeaders

    /** 兼容单 header 查询（如 res.header("set-cookie") 或 "Set-Cookie"） */
    fun header(name: String): String? {
        return responseHeaders.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    /** 兼容二进制字节获取（用于转 GBK 等非 UTF-8 编码或图片处理） */
    fun bytes(): ByteArray = rawBytes ?: content.toByteArray(Charsets.UTF_8)

    /** 兼容重定向链路前置响应 */
    fun priorResponse(): StudioJsResponse? = prior

    fun callTime(): Long = duration
    fun raw(): StudioJsResponse = this
    fun request(): StudioJsResponse = this
    override fun toString(): String = content
}

/** 官方兼容 java.lang 子路径：沙箱仅放行 Thread.sleep，其余 java.lang.* 一律不可用 */
class StudioLangApi {
    @JvmField
    val Thread: ThreadApi = ThreadApi()

    fun String(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)
    fun String(bytes: ByteArray, charset: String): String = String(bytes, java.nio.charset.Charset.forName(charset))

    class ThreadApi {
        fun sleep(ms: Long) {
            val wait = if (ms < 0) 0 else ms
            try {
                Thread.sleep(wait)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}

/** 官方 Jsoup 全局包装代理，方便在 JS 脚本中直接使用 Jsoup.parse(...) */
class JsoupJsApi {
    fun parse(html: String): org.jsoup.nodes.Document = org.jsoup.Jsoup.parse(html)
    fun parse(html: String, baseUri: String): org.jsoup.nodes.Document = org.jsoup.Jsoup.parse(html, baseUri)
    fun parseBodyFragment(bodyHtml: String): org.jsoup.nodes.Document = org.jsoup.Jsoup.parseBodyFragment(bodyHtml)
    fun connect(url: String): org.jsoup.Connection = org.jsoup.Jsoup.connect(url)
}
