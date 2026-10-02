package com.mina.legadostudio.domain

import com.google.gson.Gson
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.runtime.RhinoEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RhinoEvaluatorTest {
    @Test fun executesOfficialRhinoAndCapturesLog() {
        val result = RhinoEvaluator(HttpFetcher(), Gson()).evaluate("java.log('hello'); 1 + 2")
        assertEquals("3.0", result.value)
        assertEquals(listOf("hello"), result.logs)
    }

    @Test fun exposesCommonLegadoJavaHelpers() {
        val result = RhinoEvaluator(HttpFetcher(), Gson()).evaluate("java.hexDecodeToString(java.hexEncodeToString('中文')) + ':' + String(java.sha256Encode('x')).length")
        assertEquals("中文:64", result.value)
    }

    @Test fun supportsOfficialBase64ApisIncludingByteArray() {
        val evaluator = RhinoEvaluator(HttpFetcher(), Gson())
        val bytes = evaluator.evaluate("java.bytesToStr(java.base64DecodeToByteArray('aGk='))")
        assertEquals("hi", bytes.value)
        val decoded = evaluator.evaluate("java.base64Decode('aGk=', 'UTF-8')")
        assertEquals("hi", decoded.value)
    }

    @Test fun supportsSingleArgumentEncodeUri() {
        val result = RhinoEvaluator(HttpFetcher(), Gson()).evaluate("java.encodeURI('a b&c')")
        assertEquals("a%20b%26c", result.value)
    }

    @Test fun supportsOfficialThreadSleepUnderJavaLang() {
        val started = System.currentTimeMillis()
        val result = RhinoEvaluator(HttpFetcher(), Gson()).evaluate("java.lang.Thread.sleep(120); 'slept'")
        assertEquals("slept", result.value)
        assertTrue(System.currentTimeMillis() - started >= 100)
    }

    @Test fun supportsSymmetricCryptoLikeOfficialApp() {
        val evaluator = RhinoEvaluator(HttpFetcher(), Gson())
        val encrypted = evaluator.evaluate(
            "java.createSymmetricCrypto('AES/CBC/PKCS5Padding', java.strToBytes('0123456789abcdef'), java.strToBytes('abcdef9876543210')).encryptBase64Str('你好')"
        )
        val decrypted = evaluator.evaluate(
            "java.createSymmetricCrypto('AES/CBC/PKCS5Padding', '0123456789abcdef', 'abcdef9876543210').decryptStr('" + encrypted.value + "')"
        )
        assertEquals("你好", decrypted.value)
    }

    @Test fun supportsSymmetricCryptoEcbWithPkcs7Alias() {
        val evaluator = RhinoEvaluator(HttpFetcher(), Gson())
        val encrypted = evaluator.evaluate(
            "java.createSymmetricCrypto('AES/ECB/Pkcs7Padding', '0123456789abcdef').encryptBase64Str('hi')"
        )
        val decrypted = evaluator.evaluate(
            "java.createSymmetricCrypto('AES/ECB/PKCS5Padding', java.strToBytes('0123456789abcdef')).decryptStr('" + encrypted.value + "')"
        )
        assertEquals("hi", decrypted.value)
    }

    @Test fun exposesJsoupGlobalAndResponseHelpers() {
        val evaluator = RhinoEvaluator(HttpFetcher(), Gson())
        val js = """
            var doc = Jsoup.parse("<div class='item'>内容文本</div>");
            var txt = doc.select(".item").text();
            txt
        """.trimIndent()
        val result = evaluator.evaluate(js)
        assertEquals("内容文本", result.value)
    }

    @Test fun responseExposesHeaderBytesAndPriorResponse() {
        val evaluator = RhinoEvaluator(HttpFetcher(), Gson())
        val headers = mapOf("Set-Cookie" to "session=123", "Content-Type" to "text/html")
        val fakeResp = com.mina.legadostudio.runtime.StudioJsResponse(200, "http://a.com", "hello", headers, 10, "hello".toByteArray(), null)
        val js = """
            resp.header("set-cookie") + ":" + java.bytesToStr(resp.bytes(), "UTF-8") + ":" + (resp.raw().priorResponse() == null)
        """.trimIndent()
        val result = evaluator.evaluate(js, bindings = mapOf("resp" to fakeResp))
        assertEquals("session=123:hello:true", result.value)
    }

    @Test fun supportsSetContentAndGetStringListAndMemoryPutGet() {
        val evaluator = RhinoEvaluator(HttpFetcher(), Gson())
        val js = """
            java.put('search_key', '天命');
            var k = java.get('search_key');
            java.setContent("<div class='list'><p class='title'>修罗武神</p><p class='title'>天命之子</p></div>");
            var list = java.getStringList(".title@text");
            k + ":" + list.length + ":" + list[0] + ":" + list[1]
        """.trimIndent()
        val result = evaluator.evaluate(js)
        assertEquals("天命:2:修罗武神:天命之子", result.value)
    }
}
