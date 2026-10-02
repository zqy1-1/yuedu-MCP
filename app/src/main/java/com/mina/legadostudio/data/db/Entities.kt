package com.mina.legadostudio.data.db

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Keep
@Entity(tableName = "projects", indices = [Index("siteUrl")])
data class ProjectEntity(
    @PrimaryKey @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("siteUrl") val siteUrl: String,
    @SerializedName("sourceJson") val sourceJson: String,
    @SerializedName("stage") val stage: String,
    @SerializedName("notes") val notes: String,
    @SerializedName("createdAt") val createdAt: Long,
    @SerializedName("updatedAt") val updatedAt: Long,
)

@Keep
@Entity(tableName = "source_revisions", indices = [Index("projectId"), Index("createdAt")])
data class SourceRevisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: String,
    val sourceJson: String,
    val note: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Keep
@Entity(tableName = "http_logs", indices = [Index("createdAt"), Index("sourceAnchor"), Index("contextId")])
data class HttpLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val method: String,
    val url: String,
    val finalUrl: String,
    val statusCode: Int,
    val durationMs: Long,
    val requestHeaders: String,
    val responseHeaders: String,
    val requestBody: String,
    val responseBody: String,
    val error: String,
    val redirectChain: String,
    /** 显式书源锚点（注册域或站点 URL），null = 旧日志/无归属证据 */
    val sourceAnchor: String? = null,
    /** 发起请求的 MCP 任务上下文 ID */
    val contextId: String? = null,
    /** 实际发起链路：mcp_fetch/debug_source/check_source/eval_js/rule_js/webview/explore 等 */
    val originKind: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 列表轻量投影：排除 responseBody、requestBody 等大字段，
 * 避免成百上千行累积或大响应撑爆 Android CursorWindow (2MB) 导致 SQLiteBlobTooBigException 崩溃
 */
@Keep
data class HttpLogSummary(
    val id: Long,
    val method: String,
    val url: String,
    val finalUrl: String = "",
    val statusCode: Int = 0,
    val durationMs: Long = 0,
    val error: String = "",
    val createdAt: Long = 0L,
    val sourceAnchor: String? = null,
    val contextId: String? = null,
    val originKind: String? = null,
    /** 归属证据列：Referer 回溯需要请求头，重定向链需要 redirectChain；均不含 responseBody 大字段。 */
    val requestHeaders: String = "{}",
    val redirectChain: String = "[]",
) {
    /** 投影转全量实体占位：归属器只用 url/finalUrl/requestHeaders/redirectChain/sourceAnchor/contextId，其余字段填空。 */
    fun toAttributionEntity(): HttpLogEntity = HttpLogEntity(
        id = id, method = method, url = url, finalUrl = finalUrl, statusCode = statusCode,
        durationMs = durationMs, requestHeaders = requestHeaders, responseHeaders = "{}",
        requestBody = "", responseBody = "", error = error, redirectChain = redirectChain,
        sourceAnchor = sourceAnchor, contextId = contextId, originKind = originKind, createdAt = createdAt,
    )
}

/**
 * 抓包「会话」摘要投影：contextId LIKE 'cap:%' 的一组日志聚合为一行
 * （逐次抓包、可见浏览器、MCP 无头 webview_capture 三类会话同表同前缀）。
 * 仅聚合轻量字段；kind/来源由调用方按 hasOnce+webviewKinds+summaryBody 还原
 * （见 CaptureSessionKinds.classify），不靠域名/hopCount 猜。
 */
@Keep
data class CaptureSessionSummary(
    val contextId: String,
    /** 会话首条事务 id（增量游标下界）。 */
    val firstLogId: Long,
    /** 会话当前末条事务 id（poll_capture 游标/变更探测用，活会话会持续增长）。 */
    val latestLogId: Long,
    val totalCount: Int,
    val firstAt: Long,
    val lastAt: Long,
    /** capture_hop 行数：仅逐次抓包的重定向中间跳用这个 originKind。 */
    val hopCount: Int,
    /**
     * 入库序末行的状态码（ORDER BY id DESC LIMIT 1）：仅对逐次抓包有「最终落点」语义；
     * 对浏览器/未知会话如实是「最后一条记录」而非最终响应。旧字段名 finalStatus 已废弃，
     * 由 lastStatus 取代（旧聚合是 MAX(非 hop 状态码)，会把任意资源的最大码冒充成最终状态）。
     */
    val lastStatus: Int?,
    /** 会话内是否出现 capture_once/capture_hop 行（1/0）：逐次抓包的可靠判据之一。 */
    val hasOnce: Int,
    /** DISTINCT originKind 逗号串（NULL 行计入为 NULL 元素/空串由调用方容错）。 */
    val webviewKinds: String?,
    /** webview_capture 结算行（会话汇总）的正文：内含 mode=interactive|headless 标记。 */
    val summaryBody: String?,
)

/**
 * 单一会话的「结束证据」投影（poll_capture 的 sessionState 判定用）：
 * 逐次抓包（hasOnce）与无头 webview_capture 是一次性写入、写完即结束；
 * 只有可见浏览器会话会持续追加、直到用户「结束并保存」写出结算行（hasSettlement）。
 * 只靠「是否存在结算行」会把逐次抓包永远判成 active——三类会话分别判定。
 */
@Keep
data class CaptureSessionEndState(
    /** 会话内是否出现 capture_once/capture_hop 行（1/0）。 */
    val hasOnce: Int,
    /** 会话内是否出现任意 webview_capture* 行（1/0）：区分「浏览器会话」与「无证据的未知会话」。 */
    val hasWebView: Int,
    /** 是否已写 originKind='webview_capture' 结算行（1/0）：浏览器会话的唯一「结束」信号。 */
    val hasSettlement: Int,
)

@Keep
@Entity(tableName = "verification_sessions", indices = [Index("jobId"), Index("status")])
data class VerificationSessionEntity(
    @PrimaryKey @SerializedName("id") val id: String,
    @SerializedName("jobId") val jobId: String,
    @SerializedName("domain") val domain: String,
    @SerializedName("url") val url: String,
    @SerializedName("purpose") val purpose: String,
    @SerializedName("status") val status: String,
    @SerializedName("finalUrl") val finalUrl: String,
    @SerializedName("createdAt") val createdAt: Long,
    @SerializedName("updatedAt") val updatedAt: Long,
    /** 会话形态：webview=网页验证（CF/登录/WAF）；image_code=图片验证码（答案写入 answer）。 */
    @SerializedName("kind") val kind: String = "webview",
    /** image_code 会话：用户在验证中心输入的验证码文本；webview 会话恒为空。 */
    @SerializedName("answer") val answer: String = "",
    /** image_code 会话：验证码图 data:image/...;base64,...（本地渲染，不落网络）。 */
    @SerializedName("imageData") val imageData: String = "",
)

@Keep
@Entity(tableName = "operation_logs", indices = [Index("createdAt")])
data class OperationLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val level: String,
    val category: String,
    val message: String,
    val detail: String = "",
)

@Keep
@Entity(tableName = "diagnostic_snapshots", indices = [Index("createdAt")])
data class DiagnosticSnapshotEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val title: String,
    val path: String,
)