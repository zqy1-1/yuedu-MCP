package com.mina.legadostudio.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** HttpLogCaps 上限语义的 JVM 单测（纯逻辑，不碰 Android）。 */
class HttpLogCapsTest {

    @Test
    fun `capFor defaults when not requested`() {
        assertEquals(HttpLogCaps.DEFAULT_BODY_CHARS, HttpLogCaps.capFor(0))
        assertEquals(HttpLogCaps.DEFAULT_BODY_CHARS, HttpLogCaps.capFor(-1))
    }

    @Test
    fun `capFor clamps to webview max`() {
        assertEquals(HttpLogCaps.MAX_WEBVIEW_BODY_CHARS, HttpLogCaps.capFor(Int.MAX_VALUE))
        assertEquals(4096, HttpLogCaps.capFor(4096))
    }

    @Test
    fun `takeMarked keeps short body unchanged`() {
        val s = "hello"
        assertEquals(s, HttpLogCaps.takeMarked(s, 10))
    }

    @Test
    fun `takeMarked truncates and marks`() {
        val s = "x".repeat(10_000)
        val out = HttpLogCaps.takeMarked(s, 100)
        assertTrue(out.startsWith("x".repeat(100)))
        assertTrue(out.endsWith(HttpLogCaps.markFor(10_000).trim()))
        assertTrue(HttpLogCaps.isTruncatedMarked(out))
        assertEquals(10_000, HttpLogCaps.truncatedFrom(out))
        assertTrue(out.length < 200)
    }

    @Test
    fun `truncated mark legacy format is recognized without length`() {
        // 旧库行（无原长旧标记）仍被识别为截断，但 truncatedFrom 返回 null。
        val legacy = "abc" + HttpLogCaps.TRUNCATED_MARK
        assertTrue(HttpLogCaps.isTruncatedMarked(legacy))
        assertEquals(null, HttpLogCaps.truncatedFrom(legacy))
        val full = "complete body"
        assertFalse(HttpLogCaps.isTruncatedMarked(full))
        assertEquals(null, HttpLogCaps.truncatedFrom(full))
    }
}
