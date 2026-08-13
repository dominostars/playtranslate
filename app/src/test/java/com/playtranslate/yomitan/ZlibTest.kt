package com.playtranslate.yomitan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZlibTest {

    @Test
    fun `round trip preserves bytes`() {
        val original = """[{"type":"structured-content","content":"猫だよ"}]"""
            .toByteArray(Charsets.UTF_8)
        val inflated = Zlib.inflate(Zlib.deflate(original))!!
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
        assertNull(Zlib.inflate(byteArrayOf(0x21, 0x43, 0x65, 0x0f)))
    }

    @Test
    fun `truncated blob inflates to null`() {
        val deflated = Zlib.deflate(ByteArray(4096) { (it % 251).toByte() })
        assertNull(Zlib.inflate(deflated.copyOf(deflated.size / 2)))
    }

    @Test
    fun `empty payload round trips`() {
        assertEquals(0, Zlib.inflate(Zlib.deflate(ByteArray(0)))!!.size)
    }
}
