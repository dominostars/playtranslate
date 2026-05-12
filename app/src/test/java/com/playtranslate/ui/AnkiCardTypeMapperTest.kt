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

    // Field fixtures use the canonical schemas as of 2026-05:
    //  - Lapis from donkuri/lapis README
    //  - JPMN from Aquafina-water-bottle/jp-mining-note templates
    //  - Migaku from the Browser Extension note type (confirmed against
    //    a real install)

    private val LAPIS_FIELDS = listOf(
        "Expression", "ExpressionFurigana", "ExpressionReading", "ExpressionAudio",
        "SelectionText", "MainDefinition", "DefinitionPicture",
        "Sentence", "SentenceFurigana", "SentenceAudio",
        "Picture", "Glossary", "Hint",
        "IsWordAndSentenceCard", "IsClickCard", "IsSentenceCard", "IsAudioCard",
        "PitchPosition", "PitchCategories",
        "Frequency", "FreqSort", "MiscInfo",
    )

    private val JPMN_FIELDS = listOf(
        "Word", "WordReading", "WordReadingHiragana", "WordAudio",
        "Sentence", "SentenceReading", "SentenceAudio",
        "PrimaryDefinition", "PrimaryDefinitionPicture",
        "SecondaryDefinition", "AdditionalNotes", "ExtraDefinitions",
        "Picture", "Hint", "HintNotHidden",
        "Key", "AltDisplay",
        "IsHoverCard", "IsSentenceCard", "IsClickCard", "IsTargetedSentenceCard",
        "FrequenciesStylized", "AJTWordPitch", "UtilityDictionaries",
    )

    private val MIGAKU_FIELDS = listOf(
        "Sentence", "Translation", "Target Word", "Definitions", "Screenshot",
        "Sentence Audio", "Word Audio", "Images", "Example Sentences",
        "Is Vocabulary Card", "Is Audio Card",
    )

    // ─── Lapis ───────────────────────────────────────────────────────────

    @Test fun `Lapis canonical name picks Lapis defaults`() {
        val m = model("Lapis", LAPIS_FIELDS)
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.SENTENCE)
        assertEquals(ContentSource.EXPRESSION,               mapping["Expression"])
        assertEquals(ContentSource.READING,                  mapping["ExpressionReading"])
        assertEquals(ContentSource.DEFINITION,               mapping["MainDefinition"])
        assertEquals(ContentSource.SENTENCE,                 mapping["Sentence"])
        assertEquals(ContentSource.PICTURE,                  mapping["Picture"])
        assertEquals(ContentSource.FREQUENCY,                mapping["Frequency"])
        // Lapis's bracketed-furigana fields and its plain
        // Sentence/Expression fields receive identical payloads now
        // that SENTENCE / EXPRESSION already carry Anki-native
        // furigana brackets. Lapis's `{{furigana:}}` filter renders
        // both correctly.
        assertEquals(ContentSource.SENTENCE,                 mapping["SentenceFurigana"])
        assertEquals(ContentSource.EXPRESSION,               mapping["ExpressionFurigana"])
        // New flag wiring: word-mode and sentence-mode variants get
        // their own Lapis variant flags. Lapis allows only one selector
        // at a time, which the mode-aware flag values enforce
        // (exactly one of vocabularyCardFlag / sentenceCardFlag is
        // non-empty per send).
        assertEquals(ContentSource.VOCABULARY_CARD_FLAG,     mapping["IsWordAndSentenceCard"])
        assertEquals(ContentSource.SENTENCE_CARD_FLAG,       mapping["IsSentenceCard"])
        // Audio, alternative-definition slots, the OTHER state flags
        // (IsClickCard / IsAudioCard), pitch — none of which we produce
        // or want to auto-populate — stay null (treated as
        // ContentSource.NONE by the dialog).
        assertEquals(null, mapping["ExpressionAudio"])
        assertEquals(null, mapping["SentenceAudio"])
        assertEquals(null, mapping["Glossary"])
        assertEquals(null, mapping["IsClickCard"])
        assertEquals(null, mapping["IsAudioCard"])
        assertEquals(null, mapping["FreqSort"])
        assertEquals(null, mapping["PitchPosition"])
        assertEquals(null, mapping["MiscInfo"])
    }

    @Test fun `Lapis name matching is case-insensitive`() {
        val m = model("LAPIS-2024", LAPIS_FIELDS)
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.WORD)
        assertEquals(ContentSource.EXPRESSION, mapping["Expression"])
        assertEquals(ContentSource.DEFINITION, mapping["MainDefinition"])
    }

    @Test fun `Lapis detected by MainDefinition+Expression fingerprint after rename`() {
        // Renamed Lapis model. `MainDefinition` is unique to Lapis
        // (JPMN uses `PrimaryDefinition`) so the fingerprint catches it.
        val m = model("My Mining Cards", LAPIS_FIELDS)
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.SENTENCE)
        assertEquals(ContentSource.EXPRESSION, mapping["Expression"])
        assertEquals(ContentSource.DEFINITION, mapping["MainDefinition"])
    }

    // ─── JPMN ────────────────────────────────────────────────────────────

    @Test fun `JPMN canonical name picks JPMN defaults`() {
        val m = model("Japanese Mining Note", JPMN_FIELDS)
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.SENTENCE)
        assertEquals(ContentSource.EXPRESSION,                  mapping["Word"])
        assertEquals(ContentSource.READING,                     mapping["WordReading"])
        assertEquals(ContentSource.DEFINITION,                  mapping["PrimaryDefinition"])
        assertEquals(ContentSource.SENTENCE,                    mapping["Sentence"])
        assertEquals(ContentSource.PICTURE,                     mapping["Picture"])
        // JPMN's vocab-default has no flag; we only fire sentence-mode
        // flags. Targeted-sentence flag carries the selectedWords
        // condition inside the builder.
        assertEquals(ContentSource.SENTENCE_CARD_FLAG,          mapping["IsSentenceCard"])
        assertEquals(ContentSource.TARGETED_SENTENCE_CARD_FLAG, mapping["IsTargetedSentenceCard"])
        // Per-token sentence kana, audio, secondary slots, hover/click
        // user-preference flags, pre-stylized frequency / pitch HTML —
        // all stay null because we don't produce content in those
        // formats or auto-populate user preferences.
        assertEquals(null, mapping["WordReadingHiragana"])
        assertEquals(null, mapping["SentenceReading"])
        assertEquals(null, mapping["WordAudio"])
        assertEquals(null, mapping["SentenceAudio"])
        assertEquals(null, mapping["SecondaryDefinition"])
        assertEquals(null, mapping["IsHoverCard"])
        assertEquals(null, mapping["IsClickCard"])
        assertEquals(null, mapping["FrequenciesStylized"])
        assertEquals(null, mapping["AJTWordPitch"])
    }

    @Test fun `JPMN abbreviated name also matches`() {
        val m = model("JPMN v3", JPMN_FIELDS)
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.SENTENCE)
        assertEquals(ContentSource.EXPRESSION, mapping["Word"])
        assertEquals(ContentSource.DEFINITION, mapping["PrimaryDefinition"])
    }

    @Test fun `JPMN detected by PrimaryDefinition+Word fingerprint after rename`() {
        // Renamed JPMN model. `PrimaryDefinition` is unique to JPMN
        // (Lapis uses `MainDefinition`) so the fingerprint catches it.
        val m = model("My Vocab Cards", JPMN_FIELDS)
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.SENTENCE)
        assertEquals(ContentSource.EXPRESSION, mapping["Word"])
        assertEquals(ContentSource.READING,    mapping["WordReading"])
        assertEquals(ContentSource.DEFINITION, mapping["PrimaryDefinition"])
    }

    // ─── Migaku (Browser Extension schema) ───────────────────────────────

    @Test fun `Migaku canonical name picks Migaku defaults`() {
        val m = model("Migaku Japanese", MIGAKU_FIELDS)
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.SENTENCE)
        // SENTENCE / EXPRESSION already carry furigana brackets, so
        // Migaku's Sentence / Target Word fields receive the bracketed
        // payload and Migaku's `{{furigana:}}`-filtered template
        // renders ruby.
        assertEquals(ContentSource.SENTENCE,             mapping["Sentence"])
        assertEquals(ContentSource.SENTENCE_TRANSLATION, mapping["Translation"])
        assertEquals(ContentSource.EXPRESSION,           mapping["Target Word"])
        assertEquals(ContentSource.DEFINITION,           mapping["Definitions"])
        assertEquals(ContentSource.PICTURE,              mapping["Screenshot"])
        assertEquals(ContentSource.EXAMPLE_SENTENCES,    mapping["Example Sentences"])
        // Is Vocabulary Card now wired to the mode-aware vocab flag —
        // fires "x" on word sends, empty on sentence sends. Is Audio
        // Card stays unmapped (we don't produce audio).
        assertEquals(ContentSource.VOCABULARY_CARD_FLAG, mapping["Is Vocabulary Card"])
        assertEquals(null, mapping["Sentence Audio"])
        assertEquals(null, mapping["Word Audio"])
        assertEquals(null, mapping["Images"])
        assertEquals(null, mapping["Is Audio Card"])
    }

    @Test fun `Migaku name matching is case-insensitive`() {
        val m = model("MIGAKU Custom", MIGAKU_FIELDS)
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.SENTENCE)
        assertEquals(ContentSource.SENTENCE,   mapping["Sentence"])
        assertEquals(ContentSource.EXPRESSION, mapping["Target Word"])
    }

    @Test fun `Migaku detected by Is Vocabulary Card fingerprint after rename`() {
        // Renamed Migaku model. "Is Vocabulary Card" (with spaces) is
        // distinctive — Lapis uses `IsAudioCard` without spaces.
        val m = model("My Custom Cards", MIGAKU_FIELDS)
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.SENTENCE)
        assertEquals(ContentSource.SENTENCE,             mapping["Sentence"])
        assertEquals(ContentSource.SENTENCE_TRANSLATION, mapping["Translation"])
        assertEquals(ContentSource.EXPRESSION,           mapping["Target Word"])
    }

    @Test fun `Migaku wins over Lapis when both could match (name)`() {
        // Defensive: a "Migaku-Lapis Hybrid" with Migaku-style fields
        // routes via Migaku (its schema is wholly different — name's
        // "lapis" substring shouldn't drag it through Lapis defaults
        // and silently mis-fill).
        val m = model("Migaku Lapis Hybrid", MIGAKU_FIELDS)
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.SENTENCE)
        assertEquals(ContentSource.SENTENCE, mapping["Sentence"])
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

    @Test fun `Lapis-named model with subset of Lapis fields narrows correctly`() {
        // Name says Lapis but the model only carries a subset of the
        // canonical fields. filterKeys narrows to fields that are
        // actually present — expected behavior is no spurious keys for
        // absent fields, no mapping at all for unknown fields.
        val m = model("Lapis", listOf("Expression", "OtherField"))
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.SENTENCE)
        assertEquals(ContentSource.EXPRESSION, mapping["Expression"])
        assertEquals(null, mapping["MainDefinition"])
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
        examples = "<div>ex</div>",
        frequency = "★★★",
        partOfSpeech = "noun",
        wordsTable = "<table>words</table>",
        vocabularyCardFlag = "x",
        sentenceCardFlag = "",
        targetedSentenceCardFlag = "",
        alwaysOnMarker = "x",
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

    @Test fun `assembleNote routes EXAMPLE_SENTENCES through outputs examples`() {
        val fields = listOf("Example Sentences")
        val mapping = mapOf("Example Sentences" to ContentSource.EXAMPLE_SENTENCES)
        val out = AnkiCardTypeMapper.assembleNote(fields, mapping, sampleOutputs())
        assertEquals(listOf("<div>ex</div>"), out)
    }

    @Test fun `assembleNote with empty field list returns empty list`() {
        val out = AnkiCardTypeMapper.assembleNote(emptyList(), emptyMap(), sampleOutputs())
        assertTrue(out.isEmpty())
    }

    // ─── Picture HTML ────────────────────────────────────────────────────
    // CardOutputs.picture must contain <img> markup so it renders in
    // user templates that emit {{Picture}} directly (Lapis / JPMN /
    // Migaku / Basic). Shipping a bare filename leaves the literal
    // string visible on the card.

    @Test fun `forWord wraps image filename in img markup`() {
        val outputs = AnkiCardOutputBuilder.forWord(
            word = "猫",
            reading = "ねこ",
            pos = "noun",
            definitionHtml = "<div>cat</div>",
            freqScore = 3,
            imageFilename = "1746876543.jpg",
        )
        assertEquals(
            "<img src=\"1746876543.jpg\" style=\"max-width:100%;\">",
            outputs.picture,
        )
    }

    @Test fun `forWord leaves picture empty when no image`() {
        val outputs = AnkiCardOutputBuilder.forWord(
            word = "猫",
            reading = "ねこ",
            pos = "noun",
            definitionHtml = "",
            freqScore = 0,
            imageFilename = null,
        )
        assertEquals("", outputs.picture)
    }

    // ─── Card-type state flags ───────────────────────────────────────────
    // Mode-aware values live inside the builder; tests pin the exact
    // "x" / "" matrix so a future tweak can't silently flip variants.

    @Test fun `forWord emits vocabulary flag and always-on, leaves sentence flags empty`() {
        val outputs = AnkiCardOutputBuilder.forWord(
            word = "猫", reading = "ねこ", pos = "noun",
            definitionHtml = "", freqScore = 0, imageFilename = null,
        )
        assertEquals("x", outputs.vocabularyCardFlag)
        assertEquals("",  outputs.sentenceCardFlag)
        assertEquals("",  outputs.targetedSentenceCardFlag)
        assertEquals("x", outputs.alwaysOnMarker)
    }

    @Test fun `forSentence with selectedWords emits sentence and targeted flags and always-on`() {
        val data = SentenceAnkiContentFragment.CardData(
            source = "私は猫が好き",
            target = "I like cats",
            words = emptyList(),
            selectedWords = setOf("猫"),
            screenshotPath = null,
            sourceLangId = com.playtranslate.language.SourceLangId.JA,
        )
        val outputs = AnkiCardOutputBuilder.forSentence(data, imageFilename = null)
        assertEquals("",  outputs.vocabularyCardFlag)
        assertEquals("x", outputs.sentenceCardFlag)
        assertEquals("x", outputs.targetedSentenceCardFlag)
        assertEquals("x", outputs.alwaysOnMarker)
    }

    @Test fun `forSentence without selectedWords leaves targeted flag empty`() {
        // No bolded word in the sentence means JPMN's
        // IsTargetedSentenceCard would have nothing to target — the
        // flag must stay empty so JPMN renders a pure sentence card
        // (whole-sentence test) rather than a broken targeted card.
        val data = SentenceAnkiContentFragment.CardData(
            source = "今日はいい天気だ",
            target = "Nice weather today",
            words = emptyList(),
            selectedWords = emptySet(),
            screenshotPath = null,
            sourceLangId = com.playtranslate.language.SourceLangId.JA,
        )
        val outputs = AnkiCardOutputBuilder.forSentence(data, imageFilename = null)
        assertEquals("",  outputs.vocabularyCardFlag)
        assertEquals("x", outputs.sentenceCardFlag)
        assertEquals("",  outputs.targetedSentenceCardFlag)
        assertEquals("x", outputs.alwaysOnMarker)
    }

    @Test fun `assembleNote routes ALWAYS_ON_MARKER to whichever field is mapped`() {
        // User maps a custom IsCustom field → always-on marker.
        // Verifies the assembly path treats flag sources just like
        // content sources — flat valueFor lookup, no special-casing.
        val fields = listOf("IsCustom")
        val mapping = mapOf("IsCustom" to ContentSource.ALWAYS_ON_MARKER)
        val out = AnkiCardTypeMapper.assembleNote(fields, mapping, sampleOutputs())
        assertEquals(listOf("x"), out)
    }
}
