package com.playtranslate.yomitan

import com.google.gson.stream.JsonReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.StringReader

class TermEntryTest {

    /** Parses [json] as one term_bank entry; asserts the whole element was
     *  consumed (a trailing sentinel must still be readable). */
    private fun parse(json: String): TermEntry.Parsed? {
        val reader = JsonReader(StringReader("[$json, \"sentinel\"]"))
        reader.beginArray()
        val parsed = TermEntry.parse(reader)
        assertEquals("parser must consume exactly the entry", "sentinel", reader.nextString())
        reader.endArray()
        return parsed
    }

    @Test
    fun `full entry parses positionally`() {
        val parsed = parse("""["猫","ねこ","n exp","",100,["cat"],1,""]""")!!
        assertEquals("猫", parsed.term)
        assertEquals("ねこ", parsed.reading)
        assertEquals("n exp", parsed.defTags)
        assertEquals(100.0, parsed.score, 0.0)
        assertEquals(listOf("cat"), parsed.defs)
    }

    @Test
    fun `null defTags keeps later fields aligned`() {
        // term-bank-v3 allows defTags to be null — the slot must be
        // CONSUMED or score/glossary shift one position and the entry
        // silently loses its definitions.
        val parsed = parse("""["猫","ねこ",null,"v1",100,["cat"],1,""]""")!!
        assertEquals("", parsed.defTags)
        assertEquals(100.0, parsed.score, 0.0)
        assertEquals(listOf("cat"), parsed.defs)
    }

    @Test
    fun `wrong-typed fields default without shifting`() {
        val parsed = parse("""["猫",42,null,7,"not-a-score",["cat"]]""")!!
        assertEquals("猫", parsed.term)
        assertEquals("", parsed.reading)
        assertEquals(0.0, parsed.score, 0.0)
        assertEquals(listOf("cat"), parsed.defs)
    }

    @Test
    fun `short rows run out into defaults`() {
        val parsed = parse("""["猫"]""")!!
        assertEquals("猫", parsed.term)
        assertEquals("", parsed.reading)
        assertEquals(emptyList<String>(), parsed.defs)
    }

    @Test
    fun `empty row parses to all defaults`() {
        val parsed = parse("[]")!!
        assertEquals("", parsed.term)
        assertEquals(emptyList<String>(), parsed.defs)
    }

    @Test
    fun `non-array entry is consumed and rejected`() {
        assertNull(parse("""{"not": "an entry"}"""))
        assertNull(parse("42"))
    }

    @Test
    fun `extra trailing elements are tolerated`() {
        val parsed = parse("""["猫","ねこ","n","",1,["cat"],1,"",{"future": true},99]""")!!
        assertEquals(listOf("cat"), parsed.defs)
    }
}
