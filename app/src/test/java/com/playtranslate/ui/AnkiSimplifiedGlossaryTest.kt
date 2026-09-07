package com.playtranslate.ui

import com.playtranslate.language.SourceLangId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [simplifiedGlossaryJson] feeds the Anki simplified tier: a retained
 * glossary re-flattened to spaced plain text and re-encoded as a
 * bare-strings glossary array, replacing the sense's stored flat text
 * (flattened at import, where pre-spacing-rule dictionaries baked in
 * fused tag chips). Pinned here: the chip spacing survives the round
 * trip, JA headword-echo parity with import, and the null fallbacks
 * that keep the stored text.
 */
class AnkiSimplifiedGlossaryTest {

    private val chipsJson = """[{"type": "structured-content", "content":
        [{"tag": "span", "content": "noun"},
         {"tag": "span", "content": "suru"},
         {"tag": "span", "content": "intransitive"},
         {"tag": "span", "content": "no-adj"}]}]"""

    @Test
    fun `re-encodes the spaced flatten as a bare-strings array`() {
        assertEquals(
            """["noun suru intransitive no-adj"]""",
            simplifiedGlossaryJson(chipsJson, "勉強", "べんきょう", SourceLangId.JA),
        )
    }

    @Test
    fun `JA headword echo is stripped like import does`() {
        val json = """["ねこ【猫】\nネコ目の哺乳類。"]"""
        assertEquals(
            """["ネコ目の哺乳類。"]""",
            simplifiedGlossaryJson(json, "猫", "ねこ", SourceLangId.JA),
        )
    }

    @Test
    fun `non-JA sources keep bracket lines`() {
        // The echo matcher mis-fires on non-JA scripts (Chinese POS
        // markers like 【名】) — resolveTermDefs gates it to JA, so the
        // re-flatten must too.
        val json = """["word【note】\na gloss"]"""
        assertEquals(
            """["word【note】\na gloss"]""",
            simplifiedGlossaryJson(json, "word", "", SourceLangId.EN),
        )
    }

    @Test
    fun `unparseable or empty JSON keeps the stored text`() {
        assertNull(simplifiedGlossaryJson("not json", "w", "", SourceLangId.EN))
        assertNull(simplifiedGlossaryJson("[]", "w", "", SourceLangId.EN))
    }

    @Test
    fun `echo-only JA glossary keeps the stored text`() {
        assertNull(simplifiedGlossaryJson("""["ねこ【猫】"]""", "猫", "ねこ", SourceLangId.JA))
    }
}
