package com.mina.legadostudio.export

import com.mina.legadostudio.data.db.HttpLogEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter
import java.util.Base64

/**
 * CaptureSessionExporter 的 JVM 单测：验证流式 JSON 产出形态、bodyFile 字节内嵌、
 * anchors 汇总、分页驱动（一次导出调 page 多次），不依赖 Android Context/数据库。
 */
class CaptureSessionExporterTest {

    private fun ent(
        id: Long,
        url: String = "https://www.example.com/r$id",
        method: String = "GET",
        statusCode: Int = 200,
        responseBody: String = "body-$id",
        originKind: String? = "webview_capture_resource",
        sourceAnchor: String? = "example.com",
        requestHeaders: String = "{}",
        responseHeaders: String = "{}",
        redirectChain: String = "[]",
    ) = HttpLogEntity(
        id = id, method = method, url = url, finalUrl = url, statusCode = statusCode,
        durationMs = 12L, requestHeaders = requestHeaders, responseHeaders = responseHeaders,
        requestBody = "", responseBody = responseBody, error = "", redirectChain = redirectChain,
        sourceAnchor = sourceAnchor, contextId = "cap:test", originKind = originKind, createdAt = 1000L + id,
    )

    private fun export(
        rows: List<HttpLogEntity>,
        files: Map<String, ByteArray> = emptyMap(),
        pageSize: Int = CaptureSessionExporter.EXPORT_PAGE_SIZE,
    ): Pair<String, Int> {
        val sw = StringWriter()
        val written = runBlocking {
            CaptureSessionExporter.writeSessionJson(
                out = sw,
                contextId = "cap:test",
                exportedAt = 42L,
                total = rows.size,
                page = { offset -> rows.drop(offset).take(pageSize) },
                fileReader = { files[it] },
            )
        }
        return sw.toString() to written
    }

    @Test
    fun `writes one json doc with count and anchors`() {
        val (json, n) = export(listOf(ent(1), ent(2, sourceAnchor = "other.cn"), ent(3, sourceAnchor = null)))
        assertEquals(3, n)
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
        assertTrue(json.contains("\"contextId\":\"cap:test\""))
        assertTrue(json.contains("\"total\":3"))
        assertTrue(json.contains("\"count\":3"))
        assertTrue(json.contains("\"anchors\":[\"example.com\",\"other.cn\"]"))
    }

    @Test
    fun `paginates until short page`() {
        // 51 行按 50/页：page(offset) 被调 offset=0（满页 50）→ offset=50（只返回 1 行 <页大小，
        // 即最后一页，不再多发一次空调用）。短页收尾语义 = 不足页大小即停。
        val rows = (1L..51L).map { ent(it) }
        val offsets = mutableListOf<Int>()
        val sw = StringWriter()
        runBlocking {
            CaptureSessionExporter.writeSessionJson(
                out = sw, contextId = "cap:test", exportedAt = 0L, total = rows.size,
                page = { offset -> offsets += offset; rows.drop(offset).take(CaptureSessionExporter.EXPORT_PAGE_SIZE) },
                fileReader = { null },
            )
        }
        assertEquals(listOf(0, 50), offsets)
        assertTrue(sw.toString().contains("\"count\":51"))
    }

    @Test
    fun `paginates exact-multiple pages with trailing empty probe`() {
        // 100 行恰好两满页：offset=0（50）→ offset=50（50，仍满页不知道是否到头）→ offset=100（空页收尾）。
        val rows = (1L..100L).map { ent(it) }
        val offsets = mutableListOf<Int>()
        val sw = StringWriter()
        runBlocking {
            CaptureSessionExporter.writeSessionJson(
                out = sw, contextId = "cap:test", exportedAt = 0L, total = rows.size,
                page = { offset -> offsets += offset; rows.drop(offset).take(CaptureSessionExporter.EXPORT_PAGE_SIZE) },
                fileReader = { null },
            )
        }
        assertEquals(listOf(0, 50, 100), offsets)
        assertTrue(sw.toString().contains("\"count\":100"))
    }

    @Test
    fun `embeds binary bodyFile bytes as base64`() {
        val bin = byteArrayOf(1, 2, 3, 250.toByte(), 251.toByte())
        val row = ent(
            7, responseBody = "[binary 5B font/woff2] [bodyFile=captures/abc123/7.bin]",
        )
        val (json, _) = export(listOf(row), files = mapOf("captures/abc123/7.bin" to bin))
        assertTrue(json.contains("\"responseBodyFile\":\"captures/abc123/7.bin\""))
        assertTrue(json.contains("\"responseBodyFileBytes\":5"))
        val b64 = Base64.getEncoder().encodeToString(bin)
        assertTrue(json.contains("\"responseBodyBase64\":\"$b64\""))
    }

    @Test
    fun `missing bodyFile file still records path only`() {
        val row = ent(8, responseBody = "[binary 5B font/woff2] [bodyFile=captures/x/8.bin]")
        val (json, _) = export(listOf(row), files = emptyMap())
        assertTrue(json.contains("\"responseBodyFile\":\"captures/x/8.bin\""))
        assertFalse(json.contains("responseBodyBase64"))
    }

    @Test
    fun `bodyFileRef extracts only safe captures path`() {
        assertEquals("captures/a/1.bin", CaptureSessionExporter.bodyFileRef("x [bodyFile=captures/a/1.bin]"))
        assertNull(CaptureSessionExporter.bodyFileRef("x [bodyFile=../etc/passwd]"))
        assertNull(CaptureSessionExporter.bodyFileRef("x [bodyFile=captures/../a.bin]"))
        assertNull(CaptureSessionExporter.bodyFileRef("no marker"))
    }

    @Test
    fun `json text fields expand as nested values`() {
        val row = ent(
            9,
            requestHeaders = "{\"A\":\"1\"}",
            responseHeaders = "{\"H\":\"v\"}",
            redirectChain = "[\"https://a\",\"https://b\"]",
        )
        val (json, _) = export(listOf(row))
        assertTrue(json.contains("\"requestHeadersJson\":{\"A\":\"1\"}"))
        assertTrue(json.contains("\"responseHeadersJson\":{\"H\":\"v\"}"))
        assertTrue(json.contains("\"redirectChainJson\":[\"https://a\",\"https://b\"]"))
        // 原字符串字段仍在
        assertTrue(json.contains("\"requestHeaders\":\"{\\\"A\\\":\\\"1\\\"}\""))
    }

    @Test
    fun `json text fields redact sensitive header values on expand`() {
        // 落库漏脱敏的敏感头（合法 JSON 但含明文 token）在展开 *Json 时必须再打 ***；
        // 原字符串字段保留原文（展开版是兜底脱敏通道）。
        val row = ent(
            13,
            requestHeaders = "{\"Authorization\":\"Bearer abc\",\"X-Api-Key\":\"k123\",\"Accept\":\"*/*\"}",
            responseHeaders = "{\"Set-Cookie\":\"sid=xyz\",\"Content-Type\":\"text/html\"}",
        )
        val (json, _) = export(listOf(row))
        assertTrue(json.contains("\"requestHeadersJson\":{\"Authorization\":\"***\",\"X-Api-Key\":\"***\",\"Accept\":\"*/*\"}"))
        assertTrue(json.contains("\"responseHeadersJson\":{\"Set-Cookie\":\"***\",\"Content-Type\":\"text/html\"}"))
    }

    @Test
    fun `json text field with malformed json falls back to escaped string`() {
        // 首尾配对但中间非法的脏 JSON：不原样内嵌（会产出非法文档），转义成字符串保证导出合法。
        val row = ent(14, requestHeaders = "{bad json}")
        val (json, _) = export(listOf(row))
        // 不以非法 JSON 形式内嵌（{bad json} 不是合法 JSON，会被转义成字符串）
        assertFalse(json.contains("\"requestHeadersJson\":{\"bad json\"}"))
        assertFalse(json.contains("\"requestHeadersJson\":{bad json}"))
    }

    @Test
    fun `truncated response body is marked honestly`() {
        // 正文尾部带 TRUNCATED_MARK（HttpLogCaps 截断标记）→ 导出 responseBodyTruncated=true，
        // 消费方知道这行不是完整响应；未截断行不带该字段。
        val truncated = ent(11, responseBody = "abc…" + com.mina.legadostudio.network.HttpLogCaps.TRUNCATED_MARK)
        val full = ent(12, responseBody = "complete body")
        val (json, _) = export(listOf(truncated, full))
        assertTrue(json.contains("\"responseBodyTruncated\":true"))
        // 只有截断行带标记
        val idx = json.indexOf("\"responseBodyTruncated\":true")
        assertTrue(idx > json.indexOf("abc"))
    }

    @Test
    fun `escapes quotes and control chars in strings`() {
        val row = ent(10, responseBody = "line1\n\"q\" \\ end")
        val (json, _) = export(listOf(row))
        assertTrue(json.contains("line1\\n\\\"q\\\" \\\\ end"))
    }
}
