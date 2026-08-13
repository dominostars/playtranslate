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

    // ── Structured-glossary retention (the v8 tee) ──────────────────────

    @Test
    fun `plain-text glossaries retain nothing`() {
        val parsed = parse("""["猫","ねこ","n","",1,["cat","feline"],1,""]""")!!
        assertEquals(listOf("cat", "feline"), parsed.defs)
        assertNull(parsed.scJson)
    }

    @Test
    fun `text-item glossaries retain nothing`() {
        // {type:"text"} flattens losslessly — no reason to store it twice.
        val parsed = parse("""["猫","ねこ","n","",1,[{"type":"text","text":"cat"}],1,""]""")!!
        assertEquals(listOf("cat"), parsed.defs)
        assertNull(parsed.scJson)
    }

    @Test
    fun `structured-content glossaries retain their serialized JSON`() {
        val glossary =
            """[{"type":"structured-content","content":{"tag":"ul","content":[""" +
                """{"tag":"li","content":"cat"},{"tag":"li","content":"feline"}]}}]"""
        val parsed = parse("""["猫","ねこ","n","",1,$glossary,1,""]""")!!
        // Flat output is unchanged by the tee (ul items join with "; ").
        assertEquals(listOf("cat; feline"), parsed.defs)
        val sc = parsed.scJson!!
        // Serialized-tree equivalence, not byte equality (Gson normalizes
        // whitespace) — the structure survives verbatim.
        assertEquals(
            com.google.gson.JsonParser.parseString(glossary),
            com.google.gson.JsonParser.parseString(sc),
        )
    }

    @Test
    fun `image items force retention even though flat text drops them`() {
        val glossary = """["a gloss",{"type":"image","path":"img/cat.png"}]"""
        val parsed = parse("""["猫","ねこ","n","",1,$glossary,1,""]""")!!
        assertEquals(listOf("a gloss"), parsed.defs)
        assertEquals(
            com.google.gson.JsonParser.parseString(glossary),
            com.google.gson.JsonParser.parseString(parsed.scJson!!),
        )
    }

    @Test
    fun `tee preserves the stream-position invariant`() {
        // The sentinel assertion inside [parse] is the real check; this
        // makes it explicit for a structured entry followed by more data.
        val parsed = parse(
            """["猫","ねこ","n","",1,[{"type":"structured-content","content":"x"}],1,""]"""
        )!!
        assertEquals(listOf("x"), parsed.defs)
    }

    // ── The capture budget (OOM hardening, Codex adversarial catch) ────

    private fun capture(json: String, budget: Int): Pair<String?, String> {
        val reader = JsonReader(StringReader("[$json, \"sentinel\"]"))
        reader.beginArray()
        val captured = TermEntry.captureGlossary(reader, budget)
        val sentinel = reader.nextString()
        reader.endArray()
        return captured to sentinel
    }

    @Test
    fun `capture is source-faithful within budget`() {
        val glossary =
            """[{"type":"structured-content","content":{"tag":"img","width":253.12,"path":"a b"}},"猫",null,true,42]"""
        val (captured, sentinel) = capture(glossary, 4096)
        assertEquals("sentinel", sentinel)
        assertEquals(
            com.google.gson.JsonParser.parseString(glossary),
            com.google.gson.JsonParser.parseString(captured!!),
        )
        // Raw-token rewrite keeps the number's literal form.
        assertEquals(true, captured.contains("253.12"))
    }

    @Test
    fun `over-budget capture returns null and still consumes exactly the element`() {
        val big = "x".repeat(2048)
        val glossary = """[{"type":"structured-content","content":["$big","$big","$big"]}]"""
        val (captured, sentinel) = capture(glossary, 1024)
        assertEquals(null, captured)
        // The consume-exactly-one invariant survives the skip-mode tail.
        assertEquals("sentinel", sentinel)
    }

    @Test
    fun `over-budget glossary makes the whole entry skippable`() {
        // The caller's empty-defs check is what skips the entry — same
        // defensive fate as a malformed one. No half-megabyte glossary is
        // legitimate (Jitendex tops out around 100KB).
        val big = "y".repeat(TermEntry.MAX_RETAINED_GLOSSARY_CHARS + 64)
        val parsed = parse("""["猫","ねこ","n","",1,["$big"],1,""]""")!!
        assertEquals(emptyList<String>(), parsed.defs)
        assertNull(parsed.scJson)
    }

    @Test
    fun `budget boundary keeps a glossary that just fits`() {
        val glossary = """["abc"]"""
        val (captured, _) = capture(glossary, glossary.length)
        assertEquals(listOf("abc"), listOf(
            com.google.gson.JsonParser.parseString(captured!!).asJsonArray.first().asString,
        ))
    }
}
