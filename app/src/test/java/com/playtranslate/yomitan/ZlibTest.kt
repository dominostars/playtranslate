package com.playtranslate.yomitan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZlibTest {

    private val roomy = 64 * 1024 * 1024 // effectively-uncapped for round trips

    @Test
    fun `round trip preserves bytes`() {
        val original = """[{"type":"structured-content","content":"猫だよ"}]"""
            .toByteArray(Charsets.UTF_8)
        val inflated = Zlib.inflate(Zlib.deflate(original), roomy)!!
        assertEquals(String(original, Charsets.UTF_8), String(inflated, Charsets.UTF_8))
    }

    @Test
    fun `repetitive glossary JSON actually compresses`() {
        val json = buildString {
            append("[")
            repeat(50) {
                if (it > 0) append(",")
                append("""{"tag":"li","data":{"content":"sense-group"},"content":"gloss $it"}""")
            }
            append("]")
        }.toByteArray(Charsets.UTF_8)
        assertTrue(Zlib.deflate(json).size < json.size / 3)
    }

    @Test
    fun `corrupt blob inflates to null, not an exception`() {
        assertNull(Zlib.inflate(byteArrayOf(0x21, 0x43, 0x65, 0x0f), roomy))
    }

    @Test
    fun `truncated blob inflates to null`() {
        val deflated = Zlib.deflate(ByteArray(4096) { (it % 251).toByte() })
        assertNull(Zlib.inflate(deflated.copyOf(deflated.size / 2), roomy))
    }

    @Test
    fun `empty payload round trips`() {
        assertEquals(0, Zlib.inflate(Zlib.deflate(ByteArray(0)), roomy)!!.size)
    }

    // ── The output ceiling (zlib-bomb guard, Codex adversarial catch) ──

    @Test
    fun `a bomb stops expanding at the cap instead of allocating its payload`() {
        // 16MB of zeros deflates to ~16KB — a ~1000:1 ratio, the bomb shape.
        val bomb = Zlib.deflate(ByteArray(16 * 1024 * 1024))
        assertTrue(bomb.size < 64 * 1024)
        assertNull(Zlib.inflate(bomb, 512 * 1024))
    }

    @Test
    fun `content exactly at the cap survives`() {
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        val inflated = Zlib.inflate(Zlib.deflate(payload), payload.size)!!
        assertEquals(payload.size, inflated.size)
    }

    @Test
    fun `content one byte over the cap is rejected`() {
        val payload = ByteArray(64 * 1024 + 1)
        assertNull(Zlib.inflate(Zlib.deflate(payload), payload.size - 1))
    }
}
