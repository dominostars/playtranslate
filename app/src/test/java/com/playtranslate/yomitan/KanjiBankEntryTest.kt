package com.playtranslate.yomitan

import com.google.gson.stream.JsonReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.StringReader

class KanjiBankEntryTest {

    /** Parses [json] as one kanji_bank entry; asserts the whole element was
     *  consumed (a trailing sentinel must still be readable). */
    private fun parse(json: String): KanjiBankEntry.Parsed? {
        val reader = JsonReader(StringReader("[$json, \"sentinel\"]"))
        reader.beginArray()
        val parsed = KanjiBankEntry.parse(reader)
        assertEquals("parser must consume exactly the entry", "sentinel", reader.nextString())
        reader.endArray()
        return parsed
    }

    @Test
    fun `full KANJIDIC entry parses positionally with freq`() {
        // [character, onyomi, kunyomi, tags, meanings, stats] — 校's real shape.
        val parsed = parse(
            """["校","コウ キョウ","","",["exam","school"],{"freq":"294","grade":"1","strokes":"10"}]"""
        )!!
        assertEquals("校", parsed.character)
        assertEquals("コウ キョウ", parsed.onyomi)
        assertEquals("", parsed.kunyomi)
        assertEquals(listOf("exam", "school"), parsed.meanings)
        val freq = parsed.freq
        assertNotNull(freq)
        // KANJIDIC ships the rank as a string, so it shows but doesn't sort.
        assertEquals("294", freq!!.display)
        assertNull(freq.value)
    }

    @Test
    fun `numeric freq stat carries a sortable value`() {
        val parsed = parse("""["水","スイ","みず","",["water"],{"freq":223}]""")!!
        val freq = parsed.freq
        assertNotNull(freq)
        assertEquals("223", freq!!.display)
        assertEquals(223.0, freq.value!!, 0.0)
    }

    @Test
    fun `freq object honours displayValue and value`() {
        val parsed =
            parse("""["語","ゴ","かた.る","",["word"],{"freq":{"value":301,"displayValue":"301㋕"}}]""")!!
        val freq = parsed.freq
        assertNotNull(freq)
        assertEquals("301㋕", freq!!.display)
        assertEquals(301.0, freq.value!!, 0.0)
    }

    @Test
    fun `stats without a freq key yields no freq`() {
        val parsed = parse("""["亜","ア","","",["Asia"],{"grade":"8","strokes":"7"}]""")!!
        assertEquals("亜", parsed.character)
        assertNull(parsed.freq)
    }

    @Test
    fun `entry without a stats object yields no freq`() {
        val parsed = parse("""["乙","オツ","きのと","",["latter"]]""")!!
        assertEquals(listOf("latter"), parsed.meanings)
        assertNull(parsed.freq)
    }

    @Test
    fun `short row runs out into defaults`() {
        val parsed = parse("""["校"]""")!!
        assertEquals("校", parsed.character)
        assertEquals("", parsed.onyomi)
        assertEquals("", parsed.kunyomi)
        assertEquals(emptyList<String>(), parsed.meanings)
        assertNull(parsed.freq)
    }

    @Test
    fun `non-array entry is consumed and rejected`() {
        assertNull(parse("""{"not":"an entry"}"""))
        assertNull(parse("42"))
    }

    @Test
    fun `blank character parses but is left for the caller to skip`() {
        val parsed = parse("""["","","","",[]]""")!!
        assertEquals("", parsed.character)
    }

    @Test
    fun `wrong-typed meanings default without losing the stats freq`() {
        // meanings is a string, not an array — it defaults to empty, and the
        // freq harvest must still find the stats object in the next slot.
        val parsed = parse("""["校","コウ","","","not-an-array",{"freq":"294"}]""")!!
        assertEquals(emptyList<String>(), parsed.meanings)
        assertEquals("294", parsed.freq!!.display)
    }

    @Test
    fun `extra trailing elements are tolerated`() {
        val parsed = parse("""["校","コウ","","",["school"],{"freq":"294"},{"future":true},99]""")!!
        assertEquals("校", parsed.character)
        assertEquals("294", parsed.freq!!.display)
    }
}
