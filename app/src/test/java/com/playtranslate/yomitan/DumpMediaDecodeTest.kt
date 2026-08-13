package com.playtranslate.yomitan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * The dump media pre-decode gate (Codex adversarial catch): the base64
 * LENGTH check must reject a hostile row before the decode allocation; the
 * decoded-size check remains as the exact belt behind the approximate
 * length gate.
 */
class DumpMediaDecodeTest {

    @Test
    fun `small valid media round trips`() {
        val bytes = ByteArray(1024) { (it % 251).toByte() }
        assertArrayEquals(
            bytes,
            YomitanDataStore.decodeDumpMediaContent(Base64.getEncoder().encodeToString(bytes)),
        )
    }

    @Test
    fun `mime line wrapping is tolerated`() {
        val bytes = ByteArray(300) { it.toByte() }
        val wrapped = Base64.getMimeEncoder().encodeToString(bytes) // inserts \r\n
        assertArrayEquals(bytes, YomitanDataStore.decodeDumpMediaContent(wrapped))
    }

    @Test
    fun `oversized base64 text is rejected on length before any decode`() {
        // Not valid base64 on purpose: if the gate ran AFTER decoding,
        // this would throw-or-null via the decoder instead of the length
        // path — either way it must be null, but the length makes the
        // rejection allocation-free.
        assertNull(YomitanDataStore.decodeDumpMediaContent("A".repeat(15 * 1024 * 1024 + 1)))
    }

    @Test
    fun `decoded payload over the file cap is rejected by the belt check`() {
        // ~10.5MB decoded: passes the approximate length gate, fails the
        // exact decoded-size check.
        val bytes = ByteArray(10 * 1024 * 1024 + 512 * 1024)
        assertNull(YomitanDataStore.decodeDumpMediaContent(Base64.getEncoder().encodeToString(bytes)))
    }

    @Test
    fun `garbage base64 is rejected`() {
        assertNull(YomitanDataStore.decodeDumpMediaContent("!!not base64!!"))
    }
}
