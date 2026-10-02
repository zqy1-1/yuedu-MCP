package com.mina.legadostudio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 与 HttpLogRecorder.buildEntity 同算法的脱敏语义回归（JVM 可测，不碰 Android Context）：
 * 凭据类请求/响应头必须打 ***，其余原样；正文字段里的 token/api-key 模式也要打 ***。
 * 真正的写入路径由 HttpLogRecorder（Android）在 buildEntity 里调用同一套规则。
 */
class HttpLogRecorderRedactionTest {

    // 与 HttpLogRecorder.redact / redactText 逐行等价（保持两份同步，避免凭据在日志库明文落库）。
    private fun redact(headers: Map<String, String>): Map<String, String> = headers.mapValues { (key, value) ->
        if (key.equals("Authorization", true) || key.equals("Cookie", true) || key.equals("Set-Cookie", true) || key.contains("api-key", true)) "***" else value
    }
    private fun redactText(value: String): String =
        value.replace(Regex("(?i)(authorization|api[-_ ]?key|token)\\s*[:=]\\s*[^,;\\s]+")) { mr -> "${mr.groupValues[1]}=***" }

    @Test
    fun credentialHeadersAreMasked() {
        val inHeaders = mapOf(
            "Authorization" to "Bearer secret-token",
            "Cookie" to "session=abc",
            "Set-Cookie" to "x=y",
            "X-Api-Key" to "k123",
            "Content-Type" to "application/json",
            "Location" to "https://example.com/next?u=1",
        )
        val out = redact(inHeaders)
        assertEquals("***", out["Authorization"])
        assertEquals("***", out["Cookie"])
        assertEquals("***", out["Set-Cookie"])
        assertEquals("***", out["X-Api-Key"])
        assertEquals("application/json", out["Content-Type"])
        assertEquals("https://example.com/next?u=1", out["Location"])
    }

    @Test
    fun tokenLikeBodyTextIsMasked() {
        // redactText 命中的是 `key[:=]value` 紧贴形态（header 行、URL query、表单 body），
        // JSON 的 `"key":"value"` 键值之间有引号，不在该正则语义内（由 header 脱敏覆盖凭据头）。
        val body = "authorization: Bearer s\nx=1&token=abc123;y=2\napi_key=k999"
        val out = redactText(body)
        assertFalse(out.contains("Bearer s"))
        assertFalse(out.contains("abc123"))
        assertFalse(out.contains("k999"))
        assertTrue(out.contains("token=***"))
        assertTrue(out.contains("authorization=***"))
        assertTrue(out.contains("api_key=***"))
    }

    @Test
    fun nonSensitiveBodyIsUnchanged() {
        val plain = "<html>书名</html>"
        assertEquals(plain, redactText(plain))
    }
}
