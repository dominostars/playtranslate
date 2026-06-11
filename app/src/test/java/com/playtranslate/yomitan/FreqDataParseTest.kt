package com.playtranslate.yomitan

import com.google.gson.stream.JsonReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.StringReader

class FreqDataParseTest {

    /** Parses [json] as a freq data element; asserts the whole element was
     *  consumed (a trailing sentinel value must still be readable). */
    private fun parse(json: String): FreqData.Row? {
        val reader = JsonReader(StringReader("[$json, \"sentinel\"]"))
        reader.beginArray()
        val row = FreqData.parse(reader)
        assertEquals("parser must consume exactly the data element", "sentinel", reader.nextString())
        reader.endArray()
        return row
    }

    // ── Shape 1: bare number ────────────────────────────────────────────

    @Test
    fun `integral number trims the decimal point`() {
        assertEquals(FreqData.Row(null, "1234", 1234.0), parse("1234"))
    }

    @Test
    fun `non-integral number renders as-is`() {
        assertEquals(FreqData.Row(null, "12.5", 12.5), parse("12.5"))
    }

    // ── Shape 2: bare string ────────────────────────────────────────────

    @Test
    fun `string passes through as the display`() {
        assertEquals(FreqData.Row(null, "common", null), parse("\"common\""))
        assertEquals(FreqData.Row(null, "Top 5k", null), parse("\"Top 5k\""))
    }

    @Test
    fun `blank string is rejected`() {
        assertNull(parse("\"  \""))
    }

    // ── Shape 3: value object ───────────────────────────────────────────

    @Test
    fun `value alone formats the number`() {
        assertEquals(FreqData.Row(null, "42", 42.0), parse("""{"value": 42}"""))
    }

    @Test
    fun `displayValue wins over the formatted value`() {
        assertEquals(
            FreqData.Row(null, "1234㋕", 1234.0),
            parse("""{"value": 1234, "displayValue": "1234㋕"}"""),
        )
    }

    @Test
    fun `object keys may arrive in any order`() {
        assertEquals(
            FreqData.Row(null, "1-2k", 1500.0),
            parse("""{"displayValue": "1-2k", "value": 1500}"""),
        )
    }

    // ── Shape 4: reading-qualified ──────────────────────────────────────

    @Test
    fun `reading wraps a bare number`() {
        assertEquals(
            FreqData.Row("はし", "90", 90.0),
            parse("""{"reading": "はし", "frequency": 90}"""),
        )
    }

    @Test
    fun `reading wraps a string`() {
        assertEquals(
            FreqData.Row("はじ", "rare", null),
            parse("""{"reading": "はじ", "frequency": "rare"}"""),
        )
    }

    @Test
    fun `reading wraps a value object`() {
        assertEquals(
            FreqData.Row("はし", "90㋕", 90.0),
            parse("""{"reading": "はし", "frequency": {"value": 90, "displayValue": "90㋕"}}"""),
        )
    }

    @Test
    fun `frequency-first key order still resolves the reading`() {
        assertEquals(
            FreqData.Row("はし", "90", 90.0),
            parse("""{"frequency": 90, "reading": "はし"}"""),
        )
    }

    // ── Malformed input → null, stream still consumed ───────────────────

    @Test
    fun `non-numeric value is malformed`() {
        assertNull(parse("""{"value": "x"}"""))
    }

    @Test
    fun `empty object is malformed`() {
        assertNull(parse("{}"))
    }

    @Test
    fun `reading without frequency is malformed`() {
        assertNull(parse("""{"reading": "はし"}"""))
    }

    @Test
    fun `frequency without reading is accepted as readingless`() {
        assertEquals(FreqData.Row(null, "90", 90.0), parse("""{"frequency": 90}"""))
    }

    @Test
    fun `sibling displayValue wins over the frequency value`() {
        assertEquals(
            FreqData.Row(null, "1-2k", 123.0),
            parse("""{"frequency": 123, "displayValue": "1-2k"}"""),
        )
    }

    @Test
    fun `reading wrapper cannot nest inside frequency`() {
        assertNull(parse("""{"reading": "はし", "frequency": {"reading": "はじ", "frequency": 5}}"""))
    }

    @Test
    fun `displayValue alone has no sortable value`() {
        assertNull(parse("""{"displayValue": "1-2k"}"""))
    }

    @Test
    fun `array and boolean data are skipped as malformed`() {
        assertNull(parse("[1, 2]"))
        assertNull(parse("true"))
    }

    @Test
    fun `unknown keys are tolerated`() {
        assertEquals(
            FreqData.Row(null, "7", 7.0),
            parse("""{"value": 7, "futureField": {"nested": true}}"""),
        )
    }
}
