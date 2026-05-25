package com.playtranslate.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Pure-JVM tests for the OpenAI rate-limit header parser. Real-world
 * headers seen across 2024-2026 OpenAI / DeepSeek / OpenRouter responses
 * include the simple "1s" and the compound "6m0s" / "4m12.172s" forms.
 */
class GoDurationTest {

    @Test fun `simple seconds parse`() {
        assertEquals(1.seconds, GoDuration.parse("1s"))
        assertEquals(30.seconds, GoDuration.parse("30s"))
    }

    @Test fun `compound minutes and seconds parse`() {
        assertEquals(6.minutes, GoDuration.parse("6m0s"))
        assertEquals((4.minutes + 12.seconds + 172.milliseconds), GoDuration.parse("4m12.172s"))
    }

    @Test fun `hours and minutes parse`() {
        assertEquals((1 * 60 + 30).minutes, GoDuration.parse("1h30m"))
    }

    @Test fun `sub-second units parse`() {
        assertEquals(500.milliseconds, GoDuration.parse("500ms"))
        // Microseconds and nanoseconds appear vanishingly rarely in
        // rate-limit headers, but Go's spec includes them — we accept.
        assertEquals(1.milliseconds, GoDuration.parse("1000us"))
        assertEquals(1.milliseconds, GoDuration.parse("1000µs"))
    }

    @Test fun `whitespace is trimmed`() {
        assertEquals(1.seconds, GoDuration.parse("  1s  "))
    }

    @Test fun `null and blank return null`() {
        assertNull(GoDuration.parse(null))
        assertNull(GoDuration.parse(""))
        assertNull(GoDuration.parse("   "))
    }

    @Test fun `unknown unit returns null`() {
        // "x" is not a Go duration unit.
        assertNull(GoDuration.parse("5x"))
        // Days are NOT supported by Go's time.ParseDuration.
        assertNull(GoDuration.parse("1d"))
    }

    @Test fun `bare numbers without units return null`() {
        assertNull(GoDuration.parse("42"))
        assertNull(GoDuration.parse("3.14"))
    }

    @Test fun `garbage strings return null`() {
        assertNull(GoDuration.parse("not a duration"))
        assertNull(GoDuration.parse("1s2"))   // trailing digit with no unit
        assertNull(GoDuration.parse("s"))     // unit with no number
    }
}
