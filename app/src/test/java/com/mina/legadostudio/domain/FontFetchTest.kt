package com.mina.legadostudio.domain

import com.mina.legadostudio.mcp.TaskContextStore
import com.mina.legadostudio.network.HttpFetcher
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * allowFontBinary 通道（HttpFetcher 字体放行）+ TaskContextStore binary 槽 + JS fetchFont/decodeWoff2。
 * 字体 fixture 与 Woff2DecoderTest 共用（fontfix/font.woff2 = wOF2 + 内部 Brotli + cmap format4/12）。
 */
class FontFetchTest {

    private fun woff2(): ByteArray =
        javaClass.classLoader.getResourceAsStream("fontfix/font.woff2")!!.readBytes()

    private fun png(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, *ByteArray(32) { 1 },
    )

    // ---------- HttpFetcher allowFontBinary ----------

    @Test fun defaultFetchStillSkipsWoff2Binary() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse()
                .setHeader("Content-Type", "font/woff2")
                .setBody(Buffer().write(woff2())))
            server.start()
            val drafts = mutableListOf<com.mina.legadostudio.network.HttpLogRecorder.Draft>()
            val fetcher = HttpFetcher(unsafeSyncRecorder = { drafts += it })
            val result = fetcher.fetch(HttpFetcher.FetchRequest(server.url("/f.woff2").toString()))
            assertEquals("", result.body)
            assertTrue(result.rawBytes == null)
            assertTrue(result.bodyNote.contains("二进制"))
            // 日志只记跳过说明、不落二进制字节（不出现 wOF2 魔数、不含大段数据）
            val logged = drafts.single().responseBody
            assertTrue(logged.contains("二进制"))
            assertTrue(!logged.contains("wOF2"))
            assertTrue(logged.length < 500)
        }
    }

    @Test fun allowFontBinaryReturnsRawBytesAndKeepsBodyEmpty() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse()
                .setHeader("Content-Type", "font/woff2")
                .setBody(Buffer().write(woff2())))
            server.start()
            val drafts = mutableListOf<com.mina.legadostudio.network.HttpLogRecorder.Draft>()
            val fetcher = HttpFetcher(unsafeSyncRecorder = { drafts += it })
            val result = fetcher.fetch(HttpFetcher.FetchRequest(
                server.url("/f.woff2").toString(), allowFontBinary = true, maxBodyBytes = HttpFetcher.MAX_FONT_BYTES))
            assertEquals(200, result.code)
            assertEquals("", result.body)
            assertTrue(result.rawBytes!!.contentEquals(woff2()))
            // 日志只记说明不落二进制
            val logged = drafts.single().responseBody
            assertTrue(logged.contains("字体"))
            assertTrue(!logged.contains("wOF2"))
            assertTrue(logged.length < 200)
        }
    }

    @Test fun allowFontBinaryWorksByExtensionWhenOctetStream() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse()
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(Buffer().write(woff2())))
            server.start()
            val result = HttpFetcher().fetch(HttpFetcher.FetchRequest(
                server.url("/cdn/abc.woff2").toString(), allowFontBinary = true))
            assertTrue(result.rawBytes != null)
        }
    }

    @Test fun allowFontBinaryRejectsPngDisguisedAsFont() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse()
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(Buffer().write(png())))
            server.start()
            val err = runCatching {
                HttpFetcher().fetch(HttpFetcher.FetchRequest(
                    server.url("/evil.woff2").toString(), allowFontBinary = true))
            }.exceptionOrNull()
            assertTrue(err != null && err.message.orEmpty().contains("FONT_NOT_FONT"))
        }
    }

    @Test fun allowFontBinaryNeverLetsNonFontBinaryThrough() {
        // .png 扩展名 + image/png：仍走旧二进制跳过（allowFontBinary 只放行字体）
        MockWebServer().use { server ->
            server.enqueue(MockResponse()
                .setHeader("Content-Type", "image/png")
                .setBody(Buffer().write(png())))
            server.start()
            val result = HttpFetcher().fetch(HttpFetcher.FetchRequest(
                server.url("/a.png").toString(), allowFontBinary = true))
            assertTrue(result.rawBytes == null)
            assertTrue(result.bodyNote.contains("二进制"))
        }
    }

    @Test fun isFontContentRules() {
        assertTrue(HttpFetcher.isFontContent("font/woff2", "https://x/a"))
        assertTrue(HttpFetcher.isFontContent("application/x-font-ttf", "https://x/a"))
        assertTrue(HttpFetcher.isFontContent("application/octet-stream", "https://x/a.woff2"))
        assertTrue(HttpFetcher.isFontContent(null, "https://x/a.ttf?v=2"))
        assertTrue(!HttpFetcher.isFontContent("application/octet-stream", "https://x/a.bin"))
        assertTrue(!HttpFetcher.isFontContent("text/html", "https://x/a.woff2"))
        assertTrue(!HttpFetcher.isFontContent("image/png", "https://x/a.ttf"))
        assertTrue(!HttpFetcher.isFontContent(null, "https://x/a"))
    }

    // ---------- TaskContextStore binary 槽 ----------

    @Test fun binarySlotStoresAndReadsBytesWithoutSnapshot() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("ctx-bin").toFile()
        try {
            val store = TaskContextStore(snapshotDir = dir)
            val id = store.create("font-task")
            val entry = store.saveBinary(id, woff2(), "fp", label = "https://x/f.woff2")
            assertEquals(TaskContextStore.KIND_BINARY, entry.kind)
            val back = store.binary(id, entry.id, "fp")
            assertTrue(back.contentEquals(woff2()))
            // 快照文件不含二进制字节（只可能为零/元信息文件；字体魔数与内容绝不应落盘）
            Thread.sleep(300)
            dir.listFiles()?.forEach { f ->
                val text = f.readBytes()
                assertTrue(!text.copyOfRange(0, minOf(text.size, 4)).contentEquals(byteArrayOf('w'.code.toByte(), 'O'.code.toByte(), 'F'.code.toByte(), '2'.code.toByte())))
            }
            // read() 对 binary 条目拒绝
            assertTrue(runCatching { store.read(entry) }.isFailure)
            // 跨上下文与指纹校验沿用既有 get()
            val other = store.create("other")
            assertTrue(runCatching { store.binary(other, entry.id, "fp") }.isFailure)
            assertTrue(runCatching { store.binary(id, entry.id, "wrong") }.isFailure)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun binarySlotEvictsOldestBeyondLimit() = runBlocking {
        val store = TaskContextStore()
        val id = store.create()
        val entries = (1..TaskContextStore.MAX_BINARIES_PER_CONTEXT + 2).map {
            store.saveBinary(id, byteArrayOf(it.toByte(), 2, 3), "fp")
        }
        val meta = store.describe(id)["entries"] as List<*>
        assertEquals(TaskContextStore.MAX_BINARIES_PER_CONTEXT, meta.size)
        // 最早两个已被驱逐
        assertTrue(runCatching { store.binary(id, entries[0].id, "fp") }.isFailure)
        assertTrue(runCatching { store.binary(id, entries[1].id, "fp") }.isFailure)
        assertTrue(store.binary(id, entries.last().id, "fp").isNotEmpty())
    }

    @Test fun binarySlotRejectsOversize() = runBlocking {
        val store = TaskContextStore()
        val id = store.create()
        assertTrue(runCatching {
            store.saveBinary(id, ByteArray(TaskContextStore.MAX_BINARY_BYTES + 1), "fp")
        }.isFailure)
    }

    // ---------- JS fetchFont / decodeWoff2 ----------

    @Test fun jsFetchFontAndDecodeWoff2RoundTrip() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse()
                .setHeader("Content-Type", "font/woff2")
                .setBody(Buffer().write(woff2())))
            server.start()
            val evaluator = com.mina.legadostudio.runtime.RhinoEvaluator(HttpFetcher(), com.google.gson.Gson())
            val fontUrl = server.url("/font.woff2").toString()
            val raw = evaluator.evaluateRaw(
                """
                var bytes = java.fetchFont("$fontUrl");
                var map = java.decodeWoff2(bytes);
                var keys = []; for (var k in map) keys.push(k + "=" + map[k]);
                keys.join(",");
                """.trimIndent()
            )
            val value = raw.value.toString()
            assertTrue(value.contains("U+4E2D=1"))
            assertTrue(value.contains("U+6587=1"))
        }
    }

    @Test fun jsFetchFontRejectsNonFontResponse() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<html>not font</html>"))
            server.start()
            val evaluator = com.mina.legadostudio.runtime.RhinoEvaluator(HttpFetcher(), com.google.gson.Gson())
            val err = runCatching {
                evaluator.evaluateRaw("""java.fetchFont("${server.url("/page")}")""")
            }.exceptionOrNull()
            // 文本响应走正文路径也会带 rawBytes，fetchFont 的魔数终检必须拒绝（HTML 无字体魔数）
            assertTrue(err != null)
            assertTrue(err!!.message.orEmpty().contains("FONT_NOT_FONT"))
        }
    }

    @Test fun jsDecodeWoff2AcceptsBase64AndRejectsGarbage() {
        val evaluator = com.mina.legadostudio.runtime.RhinoEvaluator(HttpFetcher(), com.google.gson.Gson())
        val b64 = java.util.Base64.getEncoder().encodeToString(woff2())
        val ok = evaluator.evaluateRaw("""java.decodeWoff2("$b64")["U+4E2D"]""")
        assertEquals("1", ok.value.toString())
        val bad = runCatching {
            evaluator.evaluateRaw("""java.decodeWoff2("aGVsbG8=")""")
        }.exceptionOrNull()
        assertTrue(bad != null)
    }

    @Test fun jsFetchFontRejectsEmbeddedCredentials() {
        val evaluator = com.mina.legadostudio.runtime.RhinoEvaluator(HttpFetcher(), com.google.gson.Gson())
        val err = runCatching {
            evaluator.evaluateRaw("""java.fetchFont("https://user:pass@example.com/f.woff2")""")
        }.exceptionOrNull()
        assertTrue(err != null && err.message.orEmpty().contains("用户名/密码"))
    }

    // ---------- fetch_font 状态与并发安全 ----------

    @Test fun nonSuccessStatusIsRejectedByFontPath() {
        // 404 返回字体 Content-Type + 错误页正文：HttpFetcher 拿到 code=404，
        // fetch_font 层 require(2xx) 拒绝（这里是 HttpFetcher 层确认 code 透出）。
        MockWebServer().use { server ->
            server.enqueue(MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "font/woff2")
                .setBody(Buffer().write(woff2())))
            server.start()
            val result = HttpFetcher().fetch(HttpFetcher.FetchRequest(
                server.url("/missing.woff2").toString(), allowFontBinary = true))
            assertEquals(404, result.code)
            assertTrue(result.rawBytes != null) // 字节仍在，消费端按 code 拒绝
        }
    }

    @Test fun binarySlotSuspendSafeUnderRunBlocking() = runBlocking {
        // suspend mutex 实现：runBlocking 下单层 withLock 不得挂起（嵌套同锁才会死等）
        val store = TaskContextStore()
        val id = store.create()
        val e = store.saveBinary(id, woff2(), "fp")
        assertTrue(store.binary(id, e.id, "fp").isNotEmpty())
        // 并发往返：saveBinary/binary/create/describe 交错，同一把 Mutex 串行
        repeat(4) { i ->
            store.saveBinary(id, byteArrayOf(i.toByte()), "fp")
            store.describe(id)
        }
        assertTrue(store.binary(id, e.id, "fp").isNotEmpty())
    }

    @Test fun binaryDecodedCachesAcrossCalls() = runBlocking {
        val store = TaskContextStore()
        val id = store.create()
        val e = store.saveBinary(id, woff2(), "fp")
        val (d1, lines1) = store.binaryDecoded(id, e.id, "fp")
        val (d2, lines2) = store.binaryDecoded(id, e.id, "fp")
        assertTrue(d1 === d2 && lines1 === lines2) // 同一实例=缓存命中，不重解 Brotli/不重排序
        assertEquals(1, d1.mappings[0x4E2D])
        assertTrue(lines1.any { it.startsWith("U+004E2D") })
    }
}
