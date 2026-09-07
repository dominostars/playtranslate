package com.playtranslate.yomitan

import com.google.gson.stream.JsonReader
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringReader

class TermGlossaryTest {

    /** Parses [json] as a glossary array; asserts the whole element was
     *  consumed (a trailing sentinel must still be readable). */
    private fun parse(json: String): List<String> {
        val reader = JsonReader(StringReader("[$json, \"sentinel\"]"))
        reader.beginArray()
        val defs = TermGlossary.parseGlossary(reader)
        assertEquals("parser must consume exactly the glossary element", "sentinel", reader.nextString())
        reader.endArray()
        return defs
    }

    // ── Item shapes ─────────────────────────────────────────────────────

    @Test
    fun `bare strings pass through`() {
        assertEquals(listOf("cat", "feline"), parse("""["cat", "feline"]"""))
    }

    @Test
    fun `blank strings are dropped`() {
        assertEquals(listOf("cat"), parse("""["cat", "  "]"""))
    }

    @Test
    fun `text object`() {
        assertEquals(listOf("a definition"), parse("""[{"type": "text", "text": "a definition"}]"""))
    }

    @Test
    fun `type after text still parses`() {
        assertEquals(listOf("a definition"), parse("""[{"text": "a definition", "type": "text"}]"""))
    }

    @Test
    fun `image items emit nothing`() {
        assertEquals(
            listOf("after"),
            parse("""[{"type": "image", "path": "img/cat.png", "width": 16}, "after"]"""),
        )
    }

    @Test
    fun `deinflection redirect arrays are skipped with stream intact`() {
        assertEquals(listOf("real def"), parse("""[["食べる", ["v1"]], "real def"]"""))
    }

    // ── Structured content ──────────────────────────────────────────────

    @Test
    fun `ingest flatten keeps sibling junctions bare`() {
        // CONTRACT: the ingest flatten's output is stored, and every
        // stored-flat-text consumer (transport meaning strings, the flat
        // render tiers, rows diffed against earlier imports) relies on it
        // staying byte-identical across app versions. Chip-style sibling
        // spans therefore flatten FUSED here — the junction-spacing rule
        // is presentation-only and lives in flattenRetainedGlossary.
        assertEquals(
            listOf("nounsuru"),
            parse(
                """[{"type": "structured-content", "content":
                    [{"tag": "span", "content": "noun"},
                     {"tag": "span", "content": "suru"}]}]"""
            ),
        )
    }

    // ── Retained-glossary re-flatten (the Anki simplified tier) ─────────

    @Test
    fun `card re-flatten spaces sibling chips`() {
        // Jitendex-style POS chips: sibling spans whose spacing lives in
        // CSS. The stored bare concatenation reads
        // "nounsuruintransitiveno-adj" — the 2026-09-06 simplified-card
        // report — so the card-path re-flatten separates the runs.
        assertEquals(
            listOf("noun suru intransitive no-adj to consider"),
            TermGlossary.flattenRetainedGlossary(
                """[{"type": "structured-content", "content":
                    [{"tag": "span", "content": "noun"},
                     {"tag": "span", "content": "suru"},
                     {"tag": "span", "content": "intransitive"},
                     {"tag": "span", "content": "no-adj"},
                     {"tag": "span", "content": " to consider"}]}]"""
            ),
        )
    }

    @Test
    fun `card re-flatten keeps CJK flowing unspaced`() {
        // The junction rule is ASCII-only: inline spans inside CJK prose
        // (emphasis, references) must not gain spaces mid-sentence.
        assertEquals(
            listOf("よく考えること"),
            TermGlossary.flattenRetainedGlossary(
                """[{"type": "structured-content", "content":
                    ["よく", {"tag": "span", "content": "考える"}, "こと"]}]"""
            ),
        )
    }

    @Test
    fun `card re-flatten leaves whitespace and punctuation junctions alone`() {
        assertEquals(
            listOf("noun suru"),
            TermGlossary.flattenRetainedGlossary(
                """[{"type": "structured-content", "content":
                    [{"tag": "span", "content": "noun "},
                     {"tag": "span", "content": "suru"}]}]"""
            ),
        )
        assertEquals(
            listOf("(archaic)dated"),
            TermGlossary.flattenRetainedGlossary(
                """[{"type": "structured-content", "content":
                    [{"tag": "span", "content": "(archaic)"},
                     {"tag": "span", "content": "dated"}]}]"""
            ),
        )
    }

    @Test
    fun `card re-flatten matches ingest when no junctions fuse`() {
        val json = """[{"type": "structured-content", "content":
            [{"tag": "span", "content": "first part"},
             {"tag": "div", "content": "second line"}]}, "plain gloss"]"""
        assertEquals(
            parse(json),
            TermGlossary.flattenRetainedGlossary(json),
        )
    }

    @Test
    fun `flattenRetainedGlossary is null on garbage or empty`() {
        assertEquals(null, TermGlossary.flattenRetainedGlossary("not json"))
        assertEquals(null, TermGlossary.flattenRetainedGlossary("[]"))
        assertEquals(null, TermGlossary.flattenRetainedGlossary("""["  "]"""))
    }

    @Test
    fun `nested div and span flatten with line breaks`() {
        assertEquals(
            listOf("first part\nsecond line"),
            parse(
                """[{"type": "structured-content", "content":
                    [{"tag": "span", "content": "first part"},
                     {"tag": "div", "content": "second line"}]}]"""
            ),
        )
    }

    @Test
    fun `unordered list items join as parallel glosses`() {
        assertEquals(
            listOf("gloss one; gloss two"),
            parse(
                """[{"type": "structured-content", "content":
                    {"tag": "ul", "content":
                        [{"tag": "li", "content": "gloss one"},
                         {"tag": "li", "content": "gloss two"}]}}]"""
            ),
        )
    }

    @Test
    fun `ordered list items stay distinct lines`() {
        assertEquals(
            listOf("sense one\nsense two"),
            parse(
                """[{"type": "structured-content", "content":
                    {"tag": "ol", "content":
                        [{"tag": "li", "content": "sense one"},
                         {"tag": "li", "content": "sense two"}]}}]"""
            ),
        )
    }

    @Test
    fun `ruby keeps base text and drops rt`() {
        assertEquals(
            listOf("漢字 reading"),
            parse(
                """[{"type": "structured-content", "content":
                    [{"tag": "ruby", "content": ["漢字", {"tag": "rt", "content": "かんじ"}]},
                     " reading"]}]"""
            ),
        )
    }

    @Test
    fun `br breaks lines and a keeps its text`() {
        assertEquals(
            listOf("line one\nsee entry"),
            parse(
                """[{"type": "structured-content", "content":
                    ["line one", {"tag": "br"}, {"tag": "a", "href": "?query=x", "content": "see entry"}]}]"""
            ),
        )
    }

    @Test
    fun `table cells join and rows break`() {
        assertEquals(
            listOf("a | b\nc | d"),
            parse(
                """[{"type": "structured-content", "content":
                    {"tag": "table", "content": {"tag": "tbody", "content":
                        [{"tag": "tr", "content": [{"tag": "td", "content": "a"}, {"tag": "td", "content": "b"}]},
                         {"tag": "tr", "content": [{"tag": "td", "content": "c"}, {"tag": "td", "content": "d"}]}]}}}]"""
            ),
        )
    }

    @Test
    fun `img nodes inside content are dropped`() {
        assertEquals(
            listOf("before after"),
            parse(
                """[{"type": "structured-content", "content":
                    ["before ", {"tag": "img", "path": "x.png"}, "after"]}]"""
            ),
        )
    }

    @Test
    fun `tag after content applies the same rule`() {
        assertEquals(
            listOf("hidden gone"),
            parse(
                """[{"type": "structured-content", "content":
                    ["hidden ", {"content": "ふりがな", "tag": "rt"}, "gone"]}]"""
            ),
        )
    }

    @Test
    fun `style and data attributes are ignored`() {
        assertEquals(
            listOf("styled"),
            parse(
                """[{"type": "structured-content", "content":
                    {"tag": "span", "style": {"fontWeight": "bold"}, "data": {"k": "v"}, "content": "styled"}}]"""
            ),
        )
    }

    // ── Stream safety on malformed input ────────────────────────────────

    @Test
    fun `unknown object shape emits nothing and keeps the stream`() {
        assertEquals(listOf("ok"), parse("""[{"unknown": {"deep": [1, 2]}}, "ok"]"""))
    }

    @Test
    fun `numbers and nulls are skipped`() {
        assertEquals(listOf("ok"), parse("""[42, null, "ok"]"""))
    }

    @Test
    fun `empty glossary yields empty list`() {
        assertEquals(emptyList<String>(), parse("[]"))
    }

    // ── Headword-echo stripping ─────────────────────────────────────────

    @Test
    fun `reading-bracket echo line strips`() {
        assertEquals(
            "空から降る水滴。",
            TermGlossary.stripHeadwordEcho("あめ【雨】\n空から降る水滴。", "雨", "あめ"),
        )
    }

    @Test
    fun `variant-term entry strips via the reading`() {
        // The bracket carries the CANONICAL form, not this entry's term.
        assertEquals(
            "definition body",
            TermGlossary.stripHeadwordEcho(
                "いちねんのけいははるにあり【一年の計は春にあり】\ndefinition body",
                "1年の計は春にあり",
                "いちねんのけいははるにあり",
            ),
        )
    }

    @Test
    fun `okurigana dots and katakana in the echo normalize away`() {
        assertEquals(
            "口から摂取する。",
            TermGlossary.stripHeadwordEcho("た・べる【食べる】\n口から摂取する。", "食べる", "たべる"),
        )
    }

    @Test
    fun `bare headword line strips only when content follows`() {
        assertEquals(
            "definition body",
            TermGlossary.stripHeadwordEcho("ねこ\ndefinition body", "猫", "ねこ"),
        )
        // Alone it could be a legitimate kana gloss — keep it.
        assertEquals("ねこ", TermGlossary.stripHeadwordEcho("ねこ", "猫", "ねこ"))
    }

    @Test
    fun `echo-only definition becomes null`() {
        assertEquals(null, TermGlossary.stripHeadwordEcho("ねこ【猫】", "猫", "ねこ"))
    }

    @Test
    fun `unrelated bracket lines are kept`() {
        val def = "ほし【星】\nnot this entry's headword"
        assertEquals(def, TermGlossary.stripHeadwordEcho(def, "猫", "ねこ"))
    }

    @Test
    fun `english definitions never trigger the strip`() {
        val def = "cat; small domesticated feline"
        assertEquals(def, TermGlossary.stripHeadwordEcho(def, "猫", "ねこ"))
    }
}
