package com.playtranslate.ui

import com.playtranslate.AnkiManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AnkiCardTypeMapper.defaultsForModel] and
 * [AnkiCardTypeMapper.assembleNote]. Pure Kotlin — no Robolectric.
 *
 * Detection coverage:
 *  - Lapis by name; Lapis by field-set fingerprint.
 *  - JPMN by name (Aquafina + Arbyste); JPMN by field-set.
 *  - Basic shape (2-field + 3-field) with mode-aware Front/Back routing.
 *  - Unknown templates → empty map.
 *  - Name match falls through to fingerprint when the model is missing
 *    fields the named template would expect.
 *
 * Assembly coverage:
 *  - One output string per model field, in declaration order.
 *  - Unmapped / NONE → empty string.
 *  - Empty field list → empty result.
 */
class AnkiCardTypeMapperTest {

    private fun model(name: String, fields: List<String>) =
        AnkiManager.ModelInfo(id = 1L, name = name, fieldNames = fields, type = 0, sortf = 0)

    // ─── Lapis ───────────────────────────────────────────────────────────

    @Test fun `Lapis canonical name picks Lapis defaults`() {
        val m = model("Lapis", listOf(
            "Word", "WordReading", "Sentence", "SentenceMeaning", "Glossary", "Picture",
        ))
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.SENTENCE)
        assertEquals(ContentSource.EXPRESSION,           mapping["Word"])
        assertEquals(ContentSource.READING,              mapping["WordReading"])
        assertEquals(ContentSource.SENTENCE,             mapping["Sentence"])
        assertEquals(ContentSource.SENTENCE_TRANSLATION, mapping["SentenceMeaning"])
        assertEquals(ContentSource.DEFINITION,           mapping["Glossary"])
        assertEquals(ContentSource.PICTURE,              mapping["Picture"])
    }

    @Test fun `Lapis name matching is case-insensitive`() {
        val m = model("LAPIS-2024", listOf(
            "Word", "WordReading", "Sentence", "SentenceMeaning", "Glossary", "Picture",
        ))
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.WORD)
        assertEquals(ContentSource.EXPRESSION, mapping["Word"])
        assertEquals(ContentSource.DEFINITION, mapping["Glossary"])
    }

    @Test fun `Lapis detected by field-schema after rename`() {
        // Renamed model whose name no longer mentions Lapis, but the
        // characteristic Word + Glossary + SentenceMeaning fields are
        // present. Should still match.
        val m = model("My Mining Cards", listOf(
            "Word", "WordReading", "Sentence", "SentenceMeaning", "Glossary", "Picture",
        ))
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.SENTENCE)
        assertEquals(ContentSource.EXPRESSION,           mapping["Word"])
        assertEquals(ContentSource.SENTENCE_TRANSLATION, mapping["SentenceMeaning"])
    }

    // ─── JPMN ────────────────────────────────────────────────────────────

    @Test fun `JPMN canonical name picks JPMN defaults`() {
        val m = model("Japanese Mining Note", listOf(
            "Expression", "ExpressionReading", "ExpressionFurigana",
            "MainDefinition", "Sentence", "SentenceFurigana", "SentenceReading",
            "Picture", "FrequencySort",
        ))
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.SENTENCE)
        assertEquals(ContentSource.EXPRESSION, mapping["Expression"])
        assertEquals(ContentSource.READING,    mapping["ExpressionReading"])
        assertEquals(ContentSource.DEFINITION, mapping["MainDefinition"])
        assertEquals(ContentSource.SENTENCE,   mapping["Sentence"])
        assertEquals(ContentSource.PICTURE,    mapping["Picture"])
        assertEquals(ContentSource.FREQUENCY,  mapping["FrequencySort"])
        // Fields whose format we can't produce stay unmapped (callers
        // see them as ContentSource.NONE at the mapping dialog).
        assertEquals(null, mapping["ExpressionFurigana"])
        assertEquals(null, mapping["SentenceFurigana"])
        assertEquals(null, mapping["SentenceReading"])
    }

    @Test fun `JPMN abbreviated name also matches`() {
        val m = model("JPMN v3", listOf(
            "Expression", "ExpressionReading", "MainDefinition", "Sentence", "Picture",
        ))
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.SENTENCE)
        assertEquals(ContentSource.EXPRESSION, mapping["Expression"])
        assertEquals(ContentSource.DEFINITION, mapping["MainDefinition"])
    }

    @Test fun `JPMN detected by Expression+MainDefinition+ExpressionReading fingerprint`() {
        // Schema fingerprint kicks in when the name is unrecognisable.
        val m = model("MyVocab", listOf(
            "Expression", "ExpressionReading", "MainDefinition", "Sentence", "Picture",
        ))
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.SENTENCE)
        assertEquals(ContentSource.EXPRESSION, mapping["Expression"])
        assertEquals(ContentSource.READING,    mapping["ExpressionReading"])
        assertEquals(ContentSource.DEFINITION, mapping["MainDefinition"])
    }

    @Test fun `JPMN fingerprint accepts ExpressionFurigana instead of Reading`() {
        val m = model("MyVocab", listOf(
            "Expression", "ExpressionFurigana", "MainDefinition", "Sentence",
        ))
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.SENTENCE)
        assertEquals(ContentSource.EXPRESSION, mapping["Expression"])
        assertEquals(ContentSource.DEFINITION, mapping["MainDefinition"])
        // ExpressionFurigana stays unmapped — format mismatch.
        assertEquals(null, mapping["ExpressionFurigana"])
    }

    // ─── Basic shape ─────────────────────────────────────────────────────

    @Test fun `Basic 2-field word-mode defaults`() {
        val m = model("Basic", listOf("Front", "Back"))
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.WORD)
        assertEquals(ContentSource.EXPRESSION, mapping["Front"])
        assertEquals(ContentSource.DEFINITION, mapping["Back"])
    }

    @Test fun `Basic 2-field sentence-mode defaults`() {
        val m = model("Basic", listOf("Front", "Back"))
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.SENTENCE)
        assertEquals(ContentSource.SENTENCE,             mapping["Front"])
        assertEquals(ContentSource.SENTENCE_TRANSLATION, mapping["Back"])
    }

    @Test fun `Basic 3-field with Picture in sentence mode`() {
        val m = model("Basic with Picture", listOf("Front", "Back", "Picture"))
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.SENTENCE)
        assertEquals(ContentSource.SENTENCE,             mapping["Front"])
        assertEquals(ContentSource.SENTENCE_TRANSLATION, mapping["Back"])
        assertEquals(ContentSource.PICTURE,              mapping["Picture"])
    }

    @Test fun `Basic-like with extra field is NOT detected (too ambiguous)`() {
        val m = model("Basic-ish", listOf("Front", "Back", "Notes"))
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.WORD)
        assertTrue(mapping.isEmpty())
    }

    // ─── Unknown ─────────────────────────────────────────────────────────

    @Test fun `Unknown template returns empty map`() {
        val m = model("My Custom Type", listOf("Lemma", "Notes", "Image"))
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.SENTENCE)
        assertTrue(mapping.isEmpty())
    }

    @Test fun `Lapis-named model without Lapis fields returns empty intersection`() {
        // Name says Lapis but the fields don't actually exist on this
        // model. filterKeys narrows to fields that ARE present —
        // expected behavior is no spurious keys for absent fields.
        val m = model("Lapis", listOf("Word", "OtherField"))
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.SENTENCE)
        assertEquals(ContentSource.EXPRESSION, mapping["Word"])
        assertEquals(null, mapping["WordReading"])
        assertEquals(null, mapping["OtherField"])
    }

    // ─── Assembly ────────────────────────────────────────────────────────

    private fun sampleOutputs() = CardOutputs(
        expression = "expr",
        reading = "kana",
        sentence = "sent",
        sentenceTranslation = "trans",
        picture = "pic.jpg",
        definition = "<div>def</div>",
        frequency = "★★★",
        partOfSpeech = "noun",
        wordsTable = "<table>words</table>",
    )

    @Test fun `assembleNote walks fields in declaration order`() {
        val fields = listOf("Word", "WordReading", "Glossary")
        val mapping = mapOf(
            "Word" to ContentSource.EXPRESSION,
            "WordReading" to ContentSource.READING,
            "Glossary" to ContentSource.DEFINITION,
        )
        val out = AnkiCardTypeMapper.assembleNote(fields, mapping, sampleOutputs())
        assertEquals(listOf("expr", "kana", "<div>def</div>"), out)
    }

    @Test fun `assembleNote yields empty string for unmapped fields`() {
        val fields = listOf("Front", "Back", "Hint")
        val mapping = mapOf(
            "Front" to ContentSource.SENTENCE,
            "Back" to ContentSource.SENTENCE_TRANSLATION,
            // Hint deliberately unmapped.
        )
        val out = AnkiCardTypeMapper.assembleNote(fields, mapping, sampleOutputs())
        assertEquals(listOf("sent", "trans", ""), out)
    }

    @Test fun `assembleNote yields empty for NONE-mapped fields`() {
        val fields = listOf("Word", "Hint")
        val mapping = mapOf(
            "Word" to ContentSource.EXPRESSION,
            "Hint" to ContentSource.NONE,
        )
        val out = AnkiCardTypeMapper.assembleNote(fields, mapping, sampleOutputs())
        assertEquals(listOf("expr", ""), out)
    }

    @Test fun `assembleNote with empty field list returns empty list`() {
        val out = AnkiCardTypeMapper.assembleNote(emptyList(), emptyMap(), sampleOutputs())
        assertTrue(out.isEmpty())
    }
}
