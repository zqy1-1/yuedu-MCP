package com.mina.legadostudio

import android.app.Application
import com.google.gson.GsonBuilder
import com.mina.legadostudio.analyzer.HtmlAnalyzer
import com.mina.legadostudio.data.LegacyProjectMigrator
import com.mina.legadostudio.data.ProjectRepository
import com.mina.legadostudio.data.db.StudioDatabase
import com.mina.legadostudio.data.db.OperationLogEntity
import com.mina.legadostudio.diagnostic.CrashLogStore
import com.mina.legadostudio.diagnostic.DiagnosticSnapshotStore
import com.mina.legadostudio.domain.BookSourceValidator
import com.mina.legadostudio.mcp.StudioLog
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.network.HttpLogRecorder
import com.mina.legadostudio.network.RuntimeConfigStore
import com.mina.legadostudio.skills.KnowledgeRepository
import com.mina.legadostudio.skills.SkillRepository
import com.mina.legadostudio.runtime.EmbeddedLegadoRuntime
import com.mina.legadostudio.runtime.RhinoEvaluator
import com.mina.legadostudio.verification.DomainModeStore
import com.mina.legadostudio.verification.RuntimeCookieStore
import com.mina.legadostudio.verification.VerificationCoordinator
import com.mina.legadostudio.verification.VerificationWebViewStateStore
import com.mina.legadostudio.verification.WebViewPageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Instant

class StudioApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        crashLogs.install()
        wireOperationLogs()
        scope.launch {
            LegacyProjectMigrator(this@StudioApplication, projects).migrate()
        }
    }

    val crashLogs by lazy { CrashLogStore(this) }
    val snapshots by lazy { DiagnosticSnapshotStore(this) }
    val gson by lazy { GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create() }
    val database by lazy { StudioDatabase.get(this) }
    val projects by lazy { ProjectRepository(database.dao()) }
    val skills by lazy { SkillRepository(this) }
    val knowledge by lazy { KnowledgeRepository(this) }
    val validator by lazy { BookSourceValidator() }
    /** 会话 Cookie jar（Set-Cookie 自动落库/回带）与 RuntimeCookieStore 显式串共用一份：写入侧互相可见。 */
    val sessionCookieJar: com.mina.legadostudio.network.StudioCookieJar by lazy {
        com.mina.legadostudio.network.StudioCookieJar(explicitHeaderProvider = { url -> cookieStore.headerFor(url) })
    }
    val cookieStore: RuntimeCookieStore by lazy { RuntimeCookieStore(this, sessionCookieJar) }
    val domainModes by lazy { DomainModeStore(this) }
    val verificationWebState by lazy { VerificationWebViewStateStore(this) }
    val runtimeConfig by lazy { RuntimeConfigStore(this) }
    val httpLogs by lazy { HttpLogRecorder(this, database.dao(), gson) }
    val fetcher by lazy { HttpFetcher(cookieStore::headerFor, sessionCookieJar, httpLogs, runtimeConfig::userAgent, { runtimeConfig.bookSourceType }) }
    val webViewLoader by lazy { WebViewPageLoader(this, runtimeConfig::userAgent) }
    val verification by lazy { VerificationCoordinator(this, database.dao(), cookieStore, verificationWebState) }
    val contextEpoch by lazy { com.mina.legadostudio.mcp.ContextEpochStore(this) }
    val pageLoader by lazy { com.mina.legadostudio.verification.AdaptivePageLoader(fetcher, webViewLoader, domainModes, httpLogs) }
    /** 逐次抓包编排：UI 表单与 MCP capture_once 共用；独立 fetcher 带逐跳重定向+DNS 私网拦截。WebView 域在 CaptureOnce 内被拒绝。 */
    val captureOnce by lazy {
        com.mina.legadostudio.network.CaptureOnce(
            fetcherFactory = { redirectGuard, dnsGuard, perHop ->
                HttpFetcher(cookieStore::headerFor, sessionCookieJar, httpLogs, runtimeConfig::userAgent, { runtimeConfig.bookSourceType }, redirectGuard, dnsGuard, perHop)
            },
            logStore = object : com.mina.legadostudio.network.CaptureOnce.LogStore {
                override val recordingEnabled get() = httpLogs.enabled
                override suspend fun logsByContextId(contextId: String) = database.dao().httpLogsByContextId(contextId).map {
                    com.mina.legadostudio.network.CaptureOnce.LogEntry(
                        it.id, it.method, it.url, it.finalUrl, it.statusCode, it.durationMs, it.error, it.originKind,
                    )
                }
                /** 中间跳走异步写库：调用线程是 OkHttp 拦截器，recorder 内已做私网/脱敏/截断。 */
                override fun recordHop(hop: com.mina.legadostudio.network.HttpFetcher.RedirectHop, sourceAnchor: String?, contextId: String, originKind: String) {
                    httpLogs.recordAsync(
                        com.mina.legadostudio.network.HttpLogRecorder.Draft(
                            method = hop.method,
                            url = hop.requestUrl,
                            finalUrl = hop.nextUrl.orEmpty(),
                            statusCode = hop.statusCode,
                            durationMs = 0,
                            requestHeaders = emptyMap(),
                            responseHeaders = hop.responseHeaders,
                            requestBody = "",
                            responseBody = "",
                            error = "",
                            redirectChain = emptyList(),
                            sourceAnchor = sourceAnchor,
                            contextId = contextId,
                            originKind = originKind,
                        )
                    )
                }
            },
            requiresWebView = { url -> domainModes.requiresWebView(url) },
        )
    }
    /**
     * 浏览器抓包供给引擎：MCP webview_capture 无头模式与 UI「浏览器抓包」可见页共用。
     * 可见页走 newSession/configure/clientFor 装配到交互式 WebView（可点章节/翻页）；
     * run() 是一次性无头 WebView（不可交互，跑完即销毁）。
     * 供给 = 独立 OkHttpClient 对同一 URL 发自己的请求，如实记录后把该批字节喂回 WebView；
     * 私网/DNS 私网（连接级钉住地址）被空响应真正阻断，3xx 供给侧记 OBSERVED_ONLY 后 WebView
     * 对同一 URL 重发自跳（不宣称逐跳）。
     */
    val webViewCapture by lazy {
        com.mina.legadostudio.verification.WebViewCaptureEngine(
            context = this,
            logStore = httpLogs,
            logsByContextId = { ctx ->
                database.dao().httpLogsByContextId(ctx).map {
                    com.mina.legadostudio.network.CaptureOnce.LogEntry(
                        it.id, it.method, it.url, it.finalUrl, it.statusCode, it.durationMs, it.error, it.originKind,
                        bodyFile = com.mina.legadostudio.verification.WebViewCapture.bodyFileRefFrom(it.responseBody),
                    )
                }
            },
            recordingEnabled = { httpLogs.enabled },
            userAgentProvider = runtimeConfig::userAgent,
        )
    }
    val corpus by lazy { com.mina.legadostudio.domain.CorpusRepository(this) }
    val taskContexts by lazy { com.mina.legadostudio.mcp.TaskContextStore(snapshotDir = java.io.File(filesDir, "contexts")) }
    val analyzer by lazy { HtmlAnalyzer() }
    val runtime by lazy {
        // verificationProvider：java.getVerificationCode 的搬运工——拉图→base64→建 image_code 会话→等答案。
        // 做成懒挂：RhinoEvaluator 在 runtime 初始化前就要构造，而 verification 依赖 dao，避免环。
        val vprov: suspend (String) -> String? = vprov@{ imageUrl ->
            if (imageUrl.isBlank()) return@vprov null
            val fetched = runCatching {
                fetcher.fetch(com.mina.legadostudio.network.HttpFetcher.FetchRequest(
                    imageUrl, "GET", origin = com.mina.legadostudio.network.HttpOrigin(originKind = "js_captcha_fetch"),
                    maxBodyBytes = 2_000_000,
                ))
            }.getOrNull() ?: return@vprov null
            val mime = fetched.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
                ?.substringBefore(';')?.trim().orEmpty().ifBlank { "image/png" }
            val b64 = java.util.Base64.getEncoder().encodeToString(fetched.rawBytes ?: fetched.body.toByteArray())
            val session = verification.create("mcp", imageUrl, "图片验证码（java.getVerificationCode）", kind = "image_code", imageData = "data:$mime;base64,$b64")
            verification.awaitAnswer(session.id, 120)
        }
        EmbeddedLegadoRuntime(
            fetcher, validator,
            rhino = RhinoEvaluator(fetcher, gson, webViewLoader, cookieStore, runtimeConfig::userAgent, httpLogs = httpLogs, verificationProvider = vprov),
            webViewLoader = webViewLoader, domainModes = domainModes, httpLogs = httpLogs,
        )
    }

    private fun wireOperationLogs() {
        StudioLog.sink = StudioLog.Sink { level, category, message, detail ->
            scope.launch {
                val dao = database.dao()
                dao.addOperationLog(OperationLogEntity(level = level, category = category, message = message, detail = detail))
                dao.trimOperationLogs(500)
            }
        }
        StudioLog.reader = StudioLog.Reader { limit ->
            runBlocking {
                database.dao().latestOperationLogs(limit).asReversed().map { log ->
                    "${Instant.ofEpochMilli(log.createdAt)} ${log.message}"
                }
            }
        }
    }
}
