package com.playtranslate.yomitan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Guards the bounded read protecting `index.json` validation from OOM on a
 * crafted/oversized dictionary zip. The Gson→kotlinx migration briefly replaced
 * the streaming index parse with a `readText()` that materialised the whole
 * entry; [readUtf8Capped] re-bounds it so an oversized/zip-bombed entry is
 * rejected (→ `InvalidFormat`) rather than crashing the process.
 */
class IndexJsonCapTest {

    private fun read(bytes: ByteArray, cap: Int): String? =
        ByteArrayInputStream(bytes).readUtf8Capped(cap)

    @Test
    fun underCapReturnsFullContent() {
        val text = """{"title":"hi","format":3}"""
        assertEquals(text, read(text.toByteArray(Charsets.UTF_8), 1024))
    }

    @Test
    fun exactlyAtCapReturnsContent() {
        val bytes = ByteArray(64) { 'a'.code.toByte() }
        assertEquals(String(bytes, Charsets.UTF_8), read(bytes, 64))
    }

    @Test
    fun overCapReturnsNull() {
        // One byte past the cap — the OOM guard must reject, not materialise.
        val bytes = ByteArray(65) { 'a'.code.toByte() }
        assertNull(read(bytes, 64))
    }

    @Test
    fun emptyStreamReturnsEmptyString() {
        assertEquals("", read(ByteArray(0), 64))
    }
}
