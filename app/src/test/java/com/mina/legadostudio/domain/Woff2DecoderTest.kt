package com.mina.legadostudio.domain

import com.mina.legadostudio.network.Woff2Decoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Woff2Decoder 纯 JVM 单测：fixtures 由 fontTools 生成（test/resources/fontfix/）。
 * font.ttf/woff/woff2：cmap format4（中→gid1、文→gid1）；
 * font12.ttf/woff2：cmap format4 + format12，format12 含 BMP 外码点（😀→gid2）。
 */
class Woff2DecoderTest {

    private fun res(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("fontfix/$name")!!.readBytes()

    @Test fun ttfFormat4DecodesCmap() {
        val d = Woff2Decoder.decode(res("font.ttf"))
        assertEquals(0x00010000, d.sfntVersion.toInt())
        assertEquals(4, d.cmapFormat) // font.ttf 只有 format4（BMP 内）；font12.ttf 才带 format12
        assertEquals(1, d.mappings[0x4E2D])
        assertEquals(1, d.mappings[0x6587])
    }

    @Test fun woff2DecodesInternalBrotliAndCmap() {
        // WOFF2 内部 brotli 压缩必须解开：okhttp-brotli 只管 HTTP Content-Encoding
        val raw = res("font.woff2")
        assertTrue(raw.copyOfRange(0, 4).decodeToString() == "wOF2")
        val d = Woff2Decoder.decode(raw)
        assertEquals(0x00010000, d.sfntVersion.toInt())
        assertEquals(1, d.mappings[0x4E2D])
    }

    @Test fun woff2Format12MapsBeyondBmp() {
        val d = Woff2Decoder.decode(res("font12.woff2"))
        assertEquals(12, d.cmapFormat)
        assertEquals(1, d.mappings[0x4E2D])
        assertEquals(2, d.mappings[0x1F600]) // 😀 超出 BMP，只能由 format12 给出
    }

    @Test fun woff2MultiByteUIntBase128DirectoryEntry() {
        // fontbig.woff2 带自定义 zzzz 表（origLength=20000，目录编码 0x81 0x9C 0x20，3 字节 UIntBase128）。
        // 若误用 255UShort 会解成 129 → 表长度全错、解码失败；正确 UIntBase128 才能还原。
        val d = Woff2Decoder.decode(res("fontbig.woff2"))
        assertEquals(11, d.numTables)
        assertEquals(12, d.cmapFormat)
        assertEquals(1, d.mappings[0x4E2D])
        assertEquals(2, d.mappings[0x1F600])
    }

    @Test fun rejectsMalformedUIntBase128() {
        val raw = res("fontbig.woff2")
        // zzzz 目录项在最后一个：前 10 项共 22B，zzzz flags@70=0x3F（自定义 tag），tag 4B@71-74，
        // origLength 编码从 offset 75 开始（0x81 0x9C 0x20），压缩流 @78 起。
        val dirOffset = 75
        assertEquals(0x81.toByte(), raw[dirOffset]) // 确认改的是 3 字节编码的首字节
        // 首字节改 0x80：UIntBase128 规定首字节不得为 0x80（非最短编码）
        val bad1 = raw.copyOf()
        bad1[dirOffset] = 0x80.toByte()
        assertTrue("首字节 0x80 应拒绝", runCatching { Woff2Decoder.decode(bad1) }.isFailure)
        // 延续位全部置位：5 字节读满仍带延续位 → 拒绝（超 5 字节上限）
        val bad2 = raw.copyOf()
        bad2[dirOffset] = 0xFF.toByte(); bad2[dirOffset + 1] = 0xFF.toByte(); bad2[dirOffset + 2] = 0xFF.toByte()
        assertTrue("超长延续应拒绝", runCatching { Woff2Decoder.decode(bad2) }.isFailure)
    }

    @Test fun rejectsCompressedSizeBeyondFileEnd() {
        val raw = res("font.woff2")
        // totalCompressedSize（offset 20, u32）改成 src.size=264：头部校验 1..src.size 通过，
        // 但 compressedStart(≈77) + 264 > 264 → 必须被严格区间检查拒绝（旧 coerceAtMost 会截断容忍）。
        val inflated = raw.copyOf()
        inflated[20] = 0; inflated[21] = 0; inflated[22] = 1; inflated[23] = 0x08 // 264
        assertTrue("压缩区越过文件尾应拒绝", runCatching { Woff2Decoder.decode(inflated) }.isFailure)
    }

    @Test fun woff1DecodesDirectly() {
        val d = Woff2Decoder.decode(res("font.woff"))
        assertEquals(1, d.mappings[0x4E2D])
    }

    @Test fun ttfAndWoff2GiveSameMapping() {
        val ttf = Woff2Decoder.decode(res("font12.ttf")).mappings
        val woff2 = Woff2Decoder.decode(res("font12.woff2")).mappings
        assertEquals(ttf, woff2)
    }

    @Test fun rejectsNonFontBytes() {
        val cases = listOf(
            ByteArray(0),
            "<html>not a font</html>".toByteArray(),
            byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3, 4), // zip 魔数
            ByteArray(64) { 0x41 },
        )
        cases.forEach { bytes ->
            val err = runCatching { Woff2Decoder.decode(bytes) }.exceptionOrNull()
            assertTrue("input should be rejected: ${bytes.size}", err != null)
        }
    }

    @Test fun rejectsTruncatedWoff2() {
        val raw = res("font.woff2")
        val truncated = raw.copyOfRange(0, raw.size - 40)
        val err = runCatching { Woff2Decoder.decode(truncated) }.exceptionOrNull()
        assertTrue(err != null)
    }

    @Test fun rejectsWoff2HeaderLengthMismatch() {
        val raw = res("font.woff2")
        // length 字段（offset 8, u32）篡改
        val mutated = raw.copyOf()
        mutated[8] = 0; mutated[9] = 0; mutated[10] = 0; mutated[11] = 1
        assertTrue(runCatching { Woff2Decoder.decode(mutated) }.isFailure)
    }

    @Test fun rejectsExcessiveTableLengthAndCompressedSize() {
        val raw = res("font.woff2")
        // 篡改 totalCompressedSize（offset 20, u32）为超大 → 拒绝
        val badComp = raw.copyOf()
        badComp[20] = 0x7F; badComp[21] = 0xFF.toByte(); badComp[22] = 0xFF.toByte(); badComp[23] = 0xFF.toByte()
        assertTrue(runCatching { Woff2Decoder.decode(badComp) }.isFailure)
        // totalSfntSize（offset 16, u32）超 8MB 上限 → 拒绝
        val badSfnt = raw.copyOf()
        badSfnt[16] = 0x7F; badSfnt[17] = 0xFF.toByte(); badSfnt[18] = 0xFF.toByte(); badSfnt[19] = 0xFF.toByte()
        assertTrue(runCatching { Woff2Decoder.decode(badSfnt) }.isFailure)
    }

    @Test fun mappingLinesSortedAndCappedShape() {
        val lines = Woff2Decoder.decode(res("font12.woff2")).mappingLines()
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.all { it.matches(Regex("U\\+[0-9A-F]{4,6}\\t\\d+")) })
        val codepoints = lines.map { it.substringBefore('\t') }
        assertEquals(codepoints, codepoints.sorted())
    }

    @Test fun glyphIdsForSkipsUnmappedCodepoints() {
        val d = Woff2Decoder.decode(res("font.woff2"))
        val pairs = d.glyphIdsFor("中x文")
        assertEquals(listOf(0x4E2D to 1, 0x6587 to 1), pairs)
    }
}
