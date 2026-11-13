package com.playtranslate.ui

import com.playtranslate.AnkiManager
import com.playtranslate.model.FrequencyTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Part-of-speech tokens joined as they are: a stand-in for the app's
 *  labels, which need a Context. */
private val RAW_POS: (List<String>) -> String = { it.joinToString(" · ") }


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
        AnkiManager.ModelInfo(id = 1L, name = name, fieldNames = fields, type = 0)

    // Field fixtures use the canonical schemas (verified June 2026):
    //  - Lapis from donkuri/lapis (v1.7.0)
    //  - JPMN from Aquafina-water-bottle / arbyste jp-mining-note (0.11.0.6)
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
        "Key", "Word", "WordReading", "WordReadingHiragana",
        "PrimaryDefinition", "PrimaryDefinitionPicture",
        "Sentence", "SentenceReading",
        "AltDisplay", "AdditionalNotes",
        "IsSentenceCard", "IsClickCard", "IsHoverCard", "IsTargetedSentenceCard",
        "Hint", "HintNotHidden", "Picture",
        "WordAudio", "SentenceAudio",
        "PAOverride", "PAOverrideText", "PAPositions", "PAGraphs", "PAGraphsText",
        "FrequenciesStylized", "FrequencySort", "SecondaryDefinition", "ExtraDefinitions",
        "AJTWordPitch", "UtilityDictionaries",
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
        // Lapis is made for Yomitan: its two definition fields take the
        // definitions in Yomitan's own markup, split the way its README
        // maps them (one dictionary in MainDefinition, all of them in
        // Glossary). Our own panel in either gives its card script and
        // its per-dictionary CSS nothing to read.
        assertEquals(ContentSource.GLOSSARY_PRIMARY,         mapping["MainDefinition"])
        assertEquals(ContentSource.GLOSSARY,                 mapping["Glossary"])
        // Lapis has no notes field; the user's note goes to MiscInfo,
        // which nothing else of ours fills.
        assertEquals(ContentSource.NOTES,                    mapping["MiscInfo"])
        assertEquals(ContentSource.SENTENCE,                 mapping["Sentence"])
        assertEquals(ContentSource.PICTURE,                  mapping["Picture"])
        assertEquals(ContentSource.FREQUENCY_VALUES,         mapping["Frequency"])
        // Lapis renders `{{Expression}}` raw on the vocab-card front,
        // so the plain Expression slot maps to the plain EXPRESSION
        // source (no brackets — they'd display as literal text). The
        // ExpressionFurigana slot is filtered through `{{furigana:}}`
        // on the back, so it takes the bracketed variant. Same shape
        // for Sentence / SentenceFurigana.
        assertEquals(ContentSource.SENTENCE_FURIGANA,        mapping["SentenceFurigana"])
        assertEquals(ContentSource.EXPRESSION_FURIGANA,      mapping["ExpressionFurigana"])
        // New flag wiring: word-mode and sentence-mode variants get
        // their own Lapis variant flags. Lapis allows only one selector
        // at a time, which the mode-aware flag values enforce
        // (exactly one of vocabularyCardFlag / sentenceCardFlag is
        // non-empty per send).
        assertEquals(ContentSource.VOCABULARY_CARD_FLAG,     mapping["IsWordAndSentenceCard"])
        assertEquals(ContentSource.SENTENCE_CARD_FLAG,       mapping["IsSentenceCard"])
        // Audio fields carry PT-synthesized TTS.
        assertEquals(ContentSource.WORD_AUDIO,     mapping["ExpressionAudio"])
        assertEquals(ContentSource.SENTENCE_AUDIO, mapping["SentenceAudio"])
        // Pitch position, frequency list, and frequency-sort now auto-map
        // from Yomitan data.
        assertEquals(ContentSource.PITCH_POSITION,    mapping["PitchPosition"])
        assertEquals(ContentSource.FREQUENCY_HARMONIC, mapping["FreqSort"])
        // The OTHER state flags (IsClickCard / IsAudioCard), the text
        // selection and PitchCategories — none of which we produce — stay
        // null (treated as ContentSource.NONE by the dialog).
        assertEquals(null, mapping["SelectionText"])
        assertEquals(null, mapping["IsClickCard"])
        assertEquals(null, mapping["IsAudioCard"])
        assertEquals(null, mapping["PitchCategories"])
    }

    @Test fun `Lapis name matching is case-insensitive`() {
        val m = model("LAPIS-2024", LAPIS_FIELDS)
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.WORD)
        assertEquals(ContentSource.EXPRESSION, mapping["Expression"])
        assertEquals(ContentSource.GLOSSARY_PRIMARY, mapping["MainDefinition"])
    }

    @Test fun `Lapis detected by MainDefinition+Expression fingerprint after rename`() {
        // Renamed Lapis model. `MainDefinition` is unique to Lapis
        // (JPMN uses `PrimaryDefinition`) so the fingerprint catches it.
        val m = model("My Mining Cards", LAPIS_FIELDS)
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.SENTENCE)
        assertEquals(ContentSource.EXPRESSION, mapping["Expression"])
        assertEquals(ContentSource.GLOSSARY_PRIMARY, mapping["MainDefinition"])
    }

    // ─── JPMN ────────────────────────────────────────────────────────────

    // JPMN reads its own definition markup, not Yomitan's, so its
    // definition field keeps our panel; its notes field takes the note.
    // Mutations: give JPMN the Yomitan-format source; leave its note field unmapped.
    @Test fun `JPMN keeps the panel in PrimaryDefinition and takes the note in AdditionalNotes`() {
        val mapping = AnkiCardTypeMapper.defaultsForModel(model("JPMN", JPMN_FIELDS), CardMode.WORD)
        assertEquals(ContentSource.DEFINITION, mapping["PrimaryDefinition"])
        assertEquals(ContentSource.NOTES, mapping["AdditionalNotes"])
        assertEquals(null, mapping["SecondaryDefinition"])
    }

    // Migaku has no notes field, and a note never rides in a definition
    // field (2026-11-11), so nothing of Migaku's maps to it.
    @Test fun `Migaku maps no field to Notes`() {
        val mapping = AnkiCardTypeMapper.defaultsForModel(model("Migaku Japanese", MIGAKU_FIELDS), CardMode.WORD)
        assertFalse(mapping.values.contains(ContentSource.NOTES))
        assertEquals(ContentSource.DEFINITION, mapping["Definitions"])
    }

    // ─── Saved mappings brought up to the 2026-11 sources ────────────────

    private fun lapisSaved(main: ContentSource, glossary: ContentSource?, misc: ContentSource? = ContentSource.NONE) =
        buildMap {
            put("Expression", ContentSource.EXPRESSION)
            put("MainDefinition", main)
            glossary?.let { put("Glossary", it) }
            misc?.let { put("MiscInfo", it) }
        }

    // Mutations: rewrite MainDefinition alone; rewrite Glossary alone;
    // rewrite the pair from a state the user arranged.
    @Test fun `a saved Lapis mapping moves its two definition fields as a pair, from the old default only`() {
        val moved = AnkiCardTypeMapper.withGlossaryAndNotesSources(
            lapisSaved(ContentSource.DEFINITION, ContentSource.NONE),
        )
        assertEquals(ContentSource.GLOSSARY_PRIMARY, moved["MainDefinition"])
        assertEquals(ContentSource.GLOSSARY, moved["Glossary"])

        // The user put the panel in Glossary: both stay (one field alone
        // would leave Yomitan's markup on page one and our panel on page two).
        val both = lapisSaved(ContentSource.DEFINITION, ContentSource.DEFINITION)
        assertEquals(both - "MiscInfo", AnkiCardTypeMapper.withGlossaryAndNotesSources(both) - "MiscInfo")
        // The user cleared or changed MainDefinition: Glossary is not filled.
        for (main in listOf(ContentSource.NONE, ContentSource.WORDS_TABLE)) {
            val own = lapisSaved(main, ContentSource.NONE)
            assertEquals(own - "MiscInfo", AnkiCardTypeMapper.withGlossaryAndNotesSources(own) - "MiscInfo")
        }
        // A trimmed Lapis with no Glossary field keeps everything in its panel.
        val trimmed = lapisSaved(ContentSource.DEFINITION, glossary = null)
        assertEquals(ContentSource.DEFINITION, AnkiCardTypeMapper.withGlossaryAndNotesSources(trimmed)["MainDefinition"])
    }

    // Mutations: fill a note field that holds something; fill it without
    // the template's fingerprint.
    @Test fun `a saved mapping's unmapped note field takes Notes, on its own template only`() {
        assertEquals(
            ContentSource.NOTES,
            AnkiCardTypeMapper.withGlossaryAndNotesSources(
                lapisSaved(ContentSource.WORDS_TABLE, ContentSource.NONE),
            )["MiscInfo"],
        )
        val kept = lapisSaved(ContentSource.DEFINITION, ContentSource.NONE, misc = ContentSource.SENTENCE)
        assertEquals(ContentSource.SENTENCE, AnkiCardTypeMapper.withGlossaryAndNotesSources(kept)["MiscInfo"])

        val jpmn = mapOf(
            "Word" to ContentSource.EXPRESSION, "PrimaryDefinition" to ContentSource.DEFINITION,
            "AdditionalNotes" to ContentSource.NONE,
        )
        val movedJpmn = AnkiCardTypeMapper.withGlossaryAndNotesSources(jpmn)
        assertEquals(ContentSource.NOTES, movedJpmn["AdditionalNotes"])
        assertEquals("JPMN's definition field is not a Yomitan-format field", ContentSource.DEFINITION, movedJpmn["PrimaryDefinition"])

        // The same field names on a note type that is neither: untouched,
        // the very instance.
        val custom = mapOf(
            "Front" to ContentSource.EXPRESSION, "MainDefinition" to ContentSource.DEFINITION,
            "Glossary" to ContentSource.NONE, "MiscInfo" to ContentSource.NONE,
            "AdditionalNotes" to ContentSource.NONE,
        )
        assertTrue(AnkiCardTypeMapper.withGlossaryAndNotesSources(custom) === custom)
    }

    @Test fun `JPMN canonical name picks JPMN defaults`() {
        val m = model("Japanese Mining Note", JPMN_FIELDS)
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.SENTENCE)
        // JPMN has dedicated plain + bracketed slots for both Word
        // and Sentence. The three Word fields differentiate:
        //   Word               = plain kanji ("偽者")
        //   WordReading        = bracketed furigana ("偽者[にせもの]")
        //   WordReadingHiragana = pure kana ("にせもの")
        // Same shape for the sentence fields.
        assertEquals(ContentSource.EXPRESSION,                  mapping["Word"])
        assertEquals(ContentSource.EXPRESSION_FURIGANA,         mapping["WordReading"])
        assertEquals(ContentSource.READING,                     mapping["WordReadingHiragana"])
        assertEquals(ContentSource.DEFINITION,                  mapping["PrimaryDefinition"])
        assertEquals(ContentSource.SENTENCE,                    mapping["Sentence"])
        assertEquals(ContentSource.SENTENCE_FURIGANA,           mapping["SentenceReading"])
        assertEquals(ContentSource.PICTURE,                     mapping["Picture"])
        // JPMN's vocab-default has no flag; we only fire sentence-mode
        // flags. Targeted-sentence flag carries the selectedWords
        // condition inside the builder.
        assertEquals(ContentSource.SENTENCE_CARD_FLAG,          mapping["IsSentenceCard"])
        assertEquals(ContentSource.TARGETED_SENTENCE_CARD_FLAG, mapping["IsTargetedSentenceCard"])
        // Audio fields carry PT-synthesized TTS.
        assertEquals(ContentSource.WORD_AUDIO,     mapping["WordAudio"])
        assertEquals(ContentSource.SENTENCE_AUDIO, mapping["SentenceAudio"])
        // Pitch position → PAOverride (raw comma-separated downsteps, takes
        // display priority), frequency list → FrequenciesStylized (JPMN's own
        // markup), frequency sort → FrequencySort. All now auto-map.
        assertEquals(ContentSource.PITCH_POSITION,     mapping["PAOverride"])
        assertEquals(ContentSource.FREQUENCY_STYLIZED, mapping["FrequenciesStylized"])
        assertEquals(ContentSource.FREQUENCY_HARMONIC, mapping["FrequencySort"])
        // Secondary definition slots, user-preference flags, the
        // rendered-SVG pitch-graph field (PAGraphs needs an SVG we don't
        // produce), and AJT's pitch field — stay unmapped.
        assertEquals(null, mapping["SecondaryDefinition"])
        assertEquals(null, mapping["IsHoverCard"])
        assertEquals(null, mapping["IsClickCard"])
        assertEquals(null, mapping["PAGraphs"])
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
        assertEquals(ContentSource.EXPRESSION,           mapping["Word"])
        assertEquals(ContentSource.EXPRESSION_FURIGANA,  mapping["WordReading"])
        assertEquals(ContentSource.DEFINITION,           mapping["PrimaryDefinition"])
    }

    // ─── Migaku (Browser Extension schema) ───────────────────────────────

    @Test fun `Migaku canonical name picks Migaku defaults`() {
        val m = model("Migaku Japanese", MIGAKU_FIELDS)
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.SENTENCE)
        // Migaku Sentence and Target Word both render with furigana
        // via Migaku's support.html — both map to the bracketed
        // variants for ruby + tap-popup.
        assertEquals(ContentSource.SENTENCE_FURIGANA,     mapping["Sentence"])
        assertEquals(ContentSource.SENTENCE_TRANSLATION,  mapping["Translation"])
        assertEquals(ContentSource.EXPRESSION_FURIGANA,   mapping["Target Word"])
        assertEquals(ContentSource.DEFINITION,            mapping["Definitions"])
        assertEquals(ContentSource.PICTURE,               mapping["Screenshot"])
        assertEquals(ContentSource.EXAMPLE_SENTENCES,     mapping["Example Sentences"])
        // Is Vocabulary Card now wired to the mode-aware vocab flag —
        // fires "x" on word sends, empty on sentence sends.
        assertEquals(ContentSource.VOCABULARY_CARD_FLAG, mapping["Is Vocabulary Card"])
        // Word/Sentence Audio carry PT-synthesized TTS. Is Audio Card
        // stays unmapped — it's a state flag, not a content slot.
        assertEquals(ContentSource.SENTENCE_AUDIO, mapping["Sentence Audio"])
        assertEquals(ContentSource.WORD_AUDIO, mapping["Word Audio"])
        assertEquals(null, mapping["Images"])
        assertEquals(null, mapping["Is Audio Card"])
    }

    @Test fun `Migaku name matching is case-insensitive`() {
        val m = model("MIGAKU Custom", MIGAKU_FIELDS)
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.SENTENCE)
        assertEquals(ContentSource.SENTENCE_FURIGANA,   mapping["Sentence"])
        assertEquals(ContentSource.EXPRESSION_FURIGANA, mapping["Target Word"])
    }

    @Test fun `Migaku detected by Is Vocabulary Card fingerprint after rename`() {
        // Renamed Migaku model. "Is Vocabulary Card" (with spaces) is
        // distinctive — Lapis uses `IsAudioCard` without spaces.
        val m = model("My Custom Cards", MIGAKU_FIELDS)
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.SENTENCE)
        assertEquals(ContentSource.SENTENCE_FURIGANA,    mapping["Sentence"])
        assertEquals(ContentSource.SENTENCE_TRANSLATION, mapping["Translation"])
        assertEquals(ContentSource.EXPRESSION_FURIGANA,  mapping["Target Word"])
    }

    @Test fun `Migaku wins over Lapis when both could match (name)`() {
        // Defensive: a "Migaku-Lapis Hybrid" with Migaku-style fields
        // routes via Migaku (its schema is wholly different — name's
        // "lapis" substring shouldn't drag it through Lapis defaults
        // and silently mis-fill).
        val m = model("Migaku Lapis Hybrid", MIGAKU_FIELDS)
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.SENTENCE)
        assertEquals(ContentSource.SENTENCE_FURIGANA, mapping["Sentence"])
    }

    // ─── Audio field defaults ────────────────────────────────────────────
    // AUDIO_FIELD_DEFAULTS is the field-name → source table the v2.1.0→
    // v2.2.0 prefs migration (Prefs.migrateAnkiAudioFieldMappings) uses
    // to back-fill audio fields. It is derived from the per-template
    // defaults, so this pins the exact result of that derivation.

    @Test fun `AUDIO_FIELD_DEFAULTS exposes every template audio field`() {
        assertEquals(
            mapOf(
                "ExpressionAudio" to ContentSource.WORD_AUDIO,      // Lapis
                "SentenceAudio"   to ContentSource.SENTENCE_AUDIO,  // Lapis + JPMN
                "WordAudio"       to ContentSource.WORD_AUDIO,      // JPMN
                "Word Audio"      to ContentSource.WORD_AUDIO,      // Migaku
                "Sentence Audio"  to ContentSource.SENTENCE_AUDIO,  // Migaku
            ),
            AnkiCardTypeMapper.AUDIO_FIELD_DEFAULTS,
        )
    }

    // ─── Pitch/frequency field migration table ───────────────────────────
    // PITCH_FREQ_FIELD_MIGRATION drives the one-shot prefs back-fill
    // (Prefs.migrateAnkiPitchFreqFieldMappings). Pin the exact (field, from,
    // to) rules so a future tweak can't silently change which old defaults
    // get upgraded.

    @Test fun `PITCH_FREQ_FIELD_MIGRATION upgrades each old auto-default`() {
        assertEquals(
            listOf(
                Triple("PitchPosition", ContentSource.NONE, ContentSource.PITCH_POSITION),
                Triple("FreqSort", ContentSource.NONE, ContentSource.FREQUENCY_HARMONIC),
                Triple("Frequency", ContentSource.FREQUENCY, ContentSource.FREQUENCY_VALUES),
                Triple("PAOverride", ContentSource.NONE, ContentSource.PITCH_POSITION),
                Triple("FrequencySort", ContentSource.NONE, ContentSource.FREQUENCY_HARMONIC),
                Triple("FrequenciesStylized", ContentSource.NONE, ContentSource.FREQUENCY_STYLIZED),
            ),
            AnkiCardTypeMapper.PITCH_FREQ_FIELD_MIGRATION,
        )
    }

    // ─── Yomitan pitch/frequency outputs ─────────────────────────────────
    // forWord threads Headword.pitch / .frequencies into the four new
    // CardOutputs fields via AnkiFrequencyFormat. (forSentence sources them
    // from the highlighted WordEntry; covered by AnkiFrequencyFormatTest.)

    @Test fun `forWord emits comma-joined pitch positions`() {
        val outputs = AnkiCardOutputBuilder.forWord(
            word = "心", reading = "こころ", pos = "noun",
            definitionHtml = "", freqScore = 0,
            pitch = listOf(2, 0), frequencies = emptyList(), imageFilename = null,
        )
        assertEquals("2,0", outputs.pitchPosition)
    }

    @Test fun `forWord builds a frequency list with stars then dictionaries`() {
        val outputs = AnkiCardOutputBuilder.forWord(
            word = "猫", reading = "ねこ", pos = "noun",
            definitionHtml = "", freqScore = 3,
            pitch = emptyList(),
            frequencies = listOf(
                FrequencyTag("JPDB", "1234", value = 1234.0),
                FrequencyTag("CC100", "5678", value = 5678.0),
            ),
            imageFilename = null,
        )
        assertEquals(
            "<ul><li>★★★</li><li>JPDB: 1234</li><li>CC100: 5678</li></ul>",
            outputs.frequencyValues,
        )
    }

    @Test fun `forWord harmonic mean excludes the star score`() {
        val outputs = AnkiCardOutputBuilder.forWord(
            word = "猫", reading = "ねこ", pos = "noun",
            definitionHtml = "", freqScore = 5,
            pitch = emptyList(),
            frequencies = listOf(
                FrequencyTag("A", "10", value = 10.0),
                FrequencyTag("B", "30", value = 30.0),
            ),
            imageFilename = null,
        )
        // 2 / (1/10 + 1/30) = 15 — freqScore=5 must NOT drag it toward a constant.
        assertEquals("15", outputs.frequencyHarmonic)
    }

    @Test fun `forWord leaves pitch and frequency blank without data`() {
        val outputs = AnkiCardOutputBuilder.forWord(
            word = "猫", reading = "ねこ", pos = "noun",
            definitionHtml = "", freqScore = 0,
            pitch = emptyList(), frequencies = emptyList(), imageFilename = null,
        )
        assertEquals("", outputs.pitchPosition)
        assertEquals("", outputs.frequencyValues)
        assertEquals("", outputs.frequencyHarmonic)
    }

    @Test fun `forSentence sources pitch and frequency from the highlighted word`() {
        val data = SentenceAnkiContentView.CardData(
            source = "猫が好き",
            target = "I like cats",
            words = listOf(
                SentenceAnkiHtmlBuilder.WordEntry(
                    "猫", "ねこ", "cat", freqScore = 2,
                    pitch = listOf(0),
                    frequencies = listOf(FrequencyTag("JPDB", "1234", value = 1234.0)),
                ),
            ),
            selectedWords = setOf("猫"),
            screenshot = null,
            sourceLangId = com.playtranslate.language.SourceLangId.JA,
        )
        val outputs = AnkiCardOutputBuilder.forSentence(data, imageFilename = null)
        assertEquals("0", outputs.pitchPosition)
        assertEquals("<ul><li>★★</li><li>JPDB: 1234</li></ul>", outputs.frequencyValues)
        assertEquals("1234", outputs.frequencyHarmonic)
    }

    // ─── Basic shape ─────────────────────────────────────────────────────
    // Basic-shape templates bypass the mapping system entirely — fields
    // are assembled at dispatch time from the current mode via
    // assembleBasicNote. See AnkiCardTypeMapper.isBasicShape for the
    // full rationale (mainly: a stored mode-dependent mapping
    // commits the user to a mode at picker time and silently
    // mismatches when they later toggle the sheet).

    @Test fun `Basic 2-field model is recognised as Basic shape`() {
        assertTrue(AnkiCardTypeMapper.isBasicShape(listOf("Front", "Back")))
    }

    @Test fun `Basic 3-field with Picture is recognised as Basic shape`() {
        assertTrue(AnkiCardTypeMapper.isBasicShape(listOf("Front", "Back", "Picture")))
    }

    @Test fun `Basic-like with extra field is NOT recognised as Basic shape`() {
        // Anything beyond {Front, Back} or {Front, Back, Picture} is
        // too ambiguous to auto-assemble — falls through to custom-
        // template handling and the mapping dialog.
        assertFalse(AnkiCardTypeMapper.isBasicShape(listOf("Front", "Back", "Notes")))
    }

    @Test fun `defaultsForModel returns empty for Basic shape`() {
        // No mapping is stored for Basic — assembleBasicNote handles
        // the assembly at dispatch time instead.
        val m = model("Basic", listOf("Front", "Back"))
        assertTrue(AnkiCardTypeMapper.defaultsForModel(m, CardMode.WORD).isEmpty())
        assertTrue(AnkiCardTypeMapper.defaultsForModel(m, CardMode.SENTENCE).isEmpty())
    }

    @Test fun `assembleBasicNote word-mode emits Expression on Front and Definition on Back`() {
        val flds = AnkiCardTypeMapper.assembleBasicNote(
            modelFieldNames = listOf("Front", "Back"),
            mode = CardMode.WORD,
            outputs = sampleOutputs().copy(notes = ""),
        )
        assertEquals(listOf("expr", "<div>def</div>"), flds)
    }

    @Test fun `assembleBasicNote sentence-mode emits Sentence on Front and Translation on Back`() {
        val flds = AnkiCardTypeMapper.assembleBasicNote(
            modelFieldNames = listOf("Front", "Back"),
            mode = CardMode.SENTENCE,
            outputs = sampleOutputs().copy(notes = ""),
        )
        assertEquals(listOf("sent", "trans"), flds)
    }

    // Basic has two fields and no mapping, so it is the one note type
    // where the note shares a field: under the Back's content.
    // Mutations: drop the note; put it on the Front; glue it to the Back
    // with no gap; leave a gap above a note that stands alone.
    @Test fun `assembleBasicNote puts the user's note under the Back in both modes`() {
        fun basic(mode: CardMode, outputs: CardOutputs) =
            AnkiCardTypeMapper.assembleBasicNote(listOf("Front", "Back"), mode, outputs)

        assertEquals(listOf("expr", "<div>def</div><br><br>my note"), basic(CardMode.WORD, sampleOutputs()))
        assertEquals(listOf("sent", "trans<br><br>my note"), basic(CardMode.SENTENCE, sampleOutputs()))
        assertEquals(
            listOf("sent", "my note"),
            basic(CardMode.SENTENCE, sampleOutputs().copy(sentenceTranslation = "")),
        )
    }

    @Test fun `assembleBasicNote 3-field includes Picture in both modes`() {
        val wordFlds = AnkiCardTypeMapper.assembleBasicNote(
            modelFieldNames = listOf("Front", "Back", "Picture"),
            mode = CardMode.WORD,
            outputs = sampleOutputs(),
        )
        assertEquals(listOf("expr", "<div>def</div><br><br>my note", "pic.jpg"), wordFlds)
        val sentenceFlds = AnkiCardTypeMapper.assembleBasicNote(
            modelFieldNames = listOf("Front", "Back", "Picture"),
            mode = CardMode.SENTENCE,
            outputs = sampleOutputs(),
        )
        assertEquals(listOf("sent", "trans<br><br>my note", "pic.jpg"), sentenceFlds)
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
        expressionFurigana = "expr[fur]",
        reading = "kana",
        sentence = "sent",
        sentenceFurigana = "sent[fur]",
        sentenceTranslation = "trans",
        picture = "pic.jpg",
        wordAudio = "[sound:word.wav]",
        sentenceAudio = "[sound:sentence.wav]",
        definition = "<div>def</div>",
        examples = "<div>ex</div>",
        frequency = "★★★",
        partOfSpeech = "noun",
        wordsTable = "<table>words</table>",
        notes = "my note",
        glossary = "<div class=\"yomitan-glossary\">all</div>",
        glossaryPrimary = "<div class=\"yomitan-glossary\">first</div>",
        pitchPosition = "0,2",
        frequencyValues = "<ul><li>★★★</li></ul>",
        frequencyStylized = "<div class=\"frequencies__group\"></div>",
        frequencyHarmonic = "1234",
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

    // ─── First-field identity fallback ───────────────────────────────────
    // Anki identifies a note by its first field (duplicate csum + browser
    // row), but Migaku's schema puts `Sentence` first and word-mode sends
    // have no sentence. assembleNote falls back to the expression variants
    // for the FIRST field only.

    @Test fun `Migaku word send falls back first-field Sentence to expression furigana`() {
        // Word-send shape: sentence-family outputs are empty.
        val outputs = sampleOutputs().copy(
            sentence = "", sentenceFurigana = "", sentenceTranslation = "",
        )
        val m = model("Migaku Japanese", MIGAKU_FIELDS)
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.WORD)
        val out = AnkiCardTypeMapper.assembleNote(MIGAKU_FIELDS, mapping, outputs)
        assertEquals("expr[fur]", out[0])
    }

    @Test fun `first-field fallback uses plain expression for SENTENCE source`() {
        val outputs = sampleOutputs().copy(sentence = "")
        val fields = listOf("Sentence", "Back")
        val mapping = mapOf(
            "Sentence" to ContentSource.SENTENCE,
            "Back" to ContentSource.DEFINITION,
        )
        val out = AnkiCardTypeMapper.assembleNote(fields, mapping, outputs)
        assertEquals(listOf("expr", "<div>def</div>"), out)
    }

    @Test fun `first-field fallback does not fire when the sentence value is present`() {
        val m = model("Migaku Japanese", MIGAKU_FIELDS)
        val mapping = AnkiCardTypeMapper.defaultsForModel(m, CardMode.SENTENCE)
        val out = AnkiCardTypeMapper.assembleNote(MIGAKU_FIELDS, mapping, sampleOutputs())
        assertEquals("sent[fur]", out[0])
    }

    @Test fun `sentence-mapped fields past index 0 stay empty on word sends`() {
        // Custom templates hide sentence sections behind {{#Sentence}}
        // conditionals on word cards — the fallback must not fill them.
        val outputs = sampleOutputs().copy(sentence = "", sentenceFurigana = "")
        val fields = listOf("Word", "Sentence")
        val mapping = mapOf(
            "Word" to ContentSource.EXPRESSION,
            "Sentence" to ContentSource.SENTENCE,
        )
        val out = AnkiCardTypeMapper.assembleNote(fields, mapping, outputs)
        assertEquals(listOf("expr", ""), out)
    }

    @Test fun `first-field fallback leaves non-sentence sources alone`() {
        // A PICTURE-mapped first field with no screenshot stays empty —
        // the dispatcher's guard reports it rather than inventing content.
        val outputs = sampleOutputs().copy(picture = "")
        val fields = listOf("Image", "Word")
        val mapping = mapOf(
            "Image" to ContentSource.PICTURE,
            "Word" to ContentSource.EXPRESSION,
        )
        val out = AnkiCardTypeMapper.assembleNote(fields, mapping, outputs)
        assertEquals(listOf("", "expr"), out)
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
            pitch = emptyList(),
            frequencies = emptyList(),
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
            pitch = emptyList(),
            frequencies = emptyList(),
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
            definitionHtml = "", freqScore = 0,
            pitch = emptyList(), frequencies = emptyList(), imageFilename = null,
        )
        assertEquals("x", outputs.vocabularyCardFlag)
        assertEquals("",  outputs.sentenceCardFlag)
        assertEquals("",  outputs.targetedSentenceCardFlag)
        assertEquals("x", outputs.alwaysOnMarker)
    }

    @Test fun `forSentence with selectedWords emits sentence and targeted flags and always-on`() {
        val data = SentenceAnkiContentView.CardData(
            source = "私は猫が好き",
            target = "I like cats",
            words = emptyList(),
            selectedWords = setOf("猫"),
            screenshot = null,
            sourceLangId = com.playtranslate.language.SourceLangId.JA,
        )
        val outputs = AnkiCardOutputBuilder.forSentence(data, imageFilename = null)
        assertEquals("",  outputs.vocabularyCardFlag)
        assertEquals("x", outputs.sentenceCardFlag)
        assertEquals("x", outputs.targetedSentenceCardFlag)
        assertEquals("x", outputs.alwaysOnMarker)
    }

    @Test fun `forSentence concatenates per-target-word sound tags into wordAudio`() {
        // Per-target-word audio is also embedded inline in WORDS_TABLE,
        // but most structured templates (Lapis / JPMN / Migaku) map
        // their audio field to ContentSource.WORD_AUDIO — which reads
        // CardOutputs.wordAudio. If we leave that empty, the user's
        // audio uploads are silently dropped from the rendered card.
        // This locks in the routing: every requested word's [sound:…]
        // tag goes into wordAudio in sentence order.
        val words = listOf(
            SentenceAnkiHtmlBuilder.WordEntry("私", "わたし", "I", 0),
            SentenceAnkiHtmlBuilder.WordEntry("猫", "ねこ", "cat", 0),
            SentenceAnkiHtmlBuilder.WordEntry("好き", "すき", "fond", 0),
        )
        val data = SentenceAnkiContentView.CardData(
            source = "私は猫が好き",
            target = "I like cats",
            words = words,
            selectedWords = setOf("猫", "好き"),
            screenshot = null,
            sourceLangId = com.playtranslate.language.SourceLangId.JA,
            targetWordAudioWords = setOf("猫", "好き"),
        )
        val outputs = AnkiCardOutputBuilder.forSentence(
            cardData = data,
            imageFilename = null,
            wordAudioFilenames = mapOf("猫" to "cat.mp3", "好き" to "suki.mp3"),
        )
        // Sentence-order concatenation: 猫 appears before 好き in the
        // source, so the tags follow that order regardless of map
        // iteration.
        assertEquals("[sound:cat.mp3][sound:suki.mp3]", outputs.wordAudio)
        // WORDS_TABLE still carries the per-word inline tags too.
        assertTrue(outputs.wordsTable.contains("[sound:cat.mp3]"))
        assertTrue(outputs.wordsTable.contains("[sound:suki.mp3]"))
    }

    @Test fun `forSentence with no wordAudioFilenames leaves wordAudio empty`() {
        val data = SentenceAnkiContentView.CardData(
            source = "今日はいい天気だ",
            target = "Nice weather today",
            words = emptyList(),
            selectedWords = setOf("天気"),
            screenshot = null,
            sourceLangId = com.playtranslate.language.SourceLangId.JA,
        )
        val outputs = AnkiCardOutputBuilder.forSentence(data, imageFilename = null)
        assertEquals("", outputs.wordAudio)
    }

    @Test fun `forSentence without selectedWords leaves targeted flag empty`() {
        // No bolded word in the sentence means JPMN's
        // IsTargetedSentenceCard would have nothing to target — the
        // flag must stay empty so JPMN renders a pure sentence card
        // (whole-sentence test) rather than a broken targeted card.
        val data = SentenceAnkiContentView.CardData(
            source = "今日はいい天気だ",
            target = "Nice weather today",
            words = emptyList(),
            selectedWords = emptySet(),
            screenshot = null,
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

    // ─── DEFINITION on sentence sends (issue #31) ────────────────────────
    // A custom note type mapping a field to DEFINITION got the flat
    // transport text on sentence-mode sends — one run, tags glued, source
    // in trailing parens — while word-mode sends shipped the styled panel.
    // forSentence now describes the highlighted word's transported senses
    // as a WordCardDefinition and renders it with the pipeline's styled
    // payload, inline-styled, under the localized header.

    private val structuredCat =
        """[{"type":"structured-content","content":{"tag":"ul","content":[{"tag":"li","content":"cat"}]}}]"""

    private fun sentenceWith(
        vararg senses: SenseDisplay,
        meaning: String = "cat (Jitendex)",
    ) = SentenceAnkiContentView.CardData(
        source = "猫が好き",
        target = "I like cats",
        words = listOf(SentenceAnkiHtmlBuilder.WordEntry("猫", "ねこ", meaning, senses = senses.toList())),
        selectedWords = setOf("猫"),
        screenshot = null,
        sourceLangId = com.playtranslate.language.SourceLangId.JA,
    )

    @Test fun `forSentence renders the highlighted word's senses as the word card's Definition panel`() {
        val imported = SenseDisplay(
            pos = listOf("Jitendex · n"), definition = "cat (flat)", misc = emptyList(),
            imported = true, scRowid = 7L, dictId = "d1",
        )
        val glossaries = mapOf(7L to structuredCat)
        val css = mapOf("d1" to "li{color:red}")

        val outputs = AnkiCardOutputBuilder.forSentence(
            sentenceWith(imported), imageFilename = null,
            definitionsHeader = "Definitions",
            structuredGlossaries = glossaries, dictStyles = css,
            dictTitles = mapOf("d1" to "Jitendex.org [2025]"),
        )

        assertEquals(
            WordCardDefinition.fromSenses("猫", listOf(imported), fallback = "cat (Jitendex)")
                .glossary(
                    YomitanStyledData(glossaries, css), mapOf("d1" to "Jitendex.org [2025]"),
                    RAW_POS,
                ) { null }
                .panelHtml(inlineStyler, "Definitions"),
            outputs.definition,
        )
        assertTrue(outputs.definition, outputs.definition.contains("data-dictionary=\"Jitendex.org [2025]\""))
        assertFalse(outputs.definition, outputs.definition.contains("d1"))
        assertTrue(outputs.definition, outputs.definition.contains("<style>"))
        assertTrue(outputs.definition, outputs.definition.contains("Definitions"))
        assertFalse("flat transport text no longer ships", outputs.definition.contains("cat (Jitendex)"))
        assertFalse("structured sense replaces its flat text", outputs.definition.contains("cat (flat)"))
    }

    @Test fun `forSentence labels the Definition panel's part of speech with the caller's labels`() {
        // The word card's field and this one are the same panel, so both
        // take the app's labels: a description the pack stored whole must
        // not reach the card as stored.
        val stored = "noun or participle which takes the aux. verb suru"
        val packSense = SenseDisplay(pos = listOf("Noun", stored), definition = "study", misc = emptyList())

        val outputs = AnkiCardOutputBuilder.forSentence(
            sentenceWith(packSense), imageFilename = null,
            localizePos = { tokens -> tokens.joinToString(" + ") { "L${it.length}" } },
        )

        assertTrue(outputs.definition, outputs.definition.contains("L4 + L${stored.length}"))
        assertFalse(outputs.definition, outputs.definition.contains(stored))
    }

    @Test fun `forSentence without transported senses wraps the flat meaning in the panel chrome`() {
        val outputs = AnkiCardOutputBuilder.forSentence(
            sentenceWith(meaning = "1. cat\n2. kitten"), imageFilename = null,
        )
        assertEquals(
            WordCardDefinition(fallback = "1. cat\n2. kitten")
                .glossary(null, emptyMap(), RAW_POS) { null }
                .panelHtml(inlineStyler, "Definitions"),
            outputs.definition,
        )
    }

    @Test fun `forSentence with nothing highlighted leaves definition empty`() {
        val data = sentenceWith(
            SenseDisplay(pos = listOf("noun"), definition = "cat", misc = emptyList()),
        ).copy(selectedWords = emptySet())
        assertEquals("", AnkiCardOutputBuilder.forSentence(data, imageFilename = null).definition)
    }

    // ─── The 2026-11 sources: Notes and the Yomitan-format definitions ───

    // Mutation: route one of the three to another output.
    @Test fun `assembleNote routes Notes and the two glossary sources`() {
        val flds = AnkiCardTypeMapper.assembleNote(
            modelFieldNames = listOf("MiscInfo", "MainDefinition", "Glossary"),
            mapping = mapOf(
                "MiscInfo" to ContentSource.NOTES,
                "MainDefinition" to ContentSource.GLOSSARY_PRIMARY,
                "Glossary" to ContentSource.GLOSSARY,
            ),
            outputs = sampleOutputs(),
        )
        assertEquals(
            listOf(
                "my note",
                "<div class=\"yomitan-glossary\">first</div>",
                "<div class=\"yomitan-glossary\">all</div>",
            ),
            flds,
        )
    }

    // The sentence card's glossary sources are the highlighted word's
    // rows, the ones DEFINITION draws as the panel, in Yomitan's markup.
    // Mutations: fill them from the whole sentence; swap all and primary.
    @Test fun `forSentence fills the glossary sources from the highlighted word, primary first dictionary only`() {
        val imported = SenseDisplay(
            pos = listOf("Jitendex · n"), definition = "cat (flat)", misc = emptyList(),
            imported = true, scRowid = 7L, dictId = "d1",
        )
        val own = SenseDisplay(pos = listOf("noun"), definition = "cat", misc = emptyList())
        val outputs = AnkiCardOutputBuilder.forSentence(
            sentenceWith(imported, own), imageFilename = null,
            structuredGlossaries = mapOf(7L to structuredCat),
            dictStyles = mapOf("d1" to "li{color:red}"),
            dictTitles = mapOf("d1" to "Jitendex.org [2025]"),
        )

        val item = "<li data-dictionary=\"Jitendex.org [2025]\"><i>(n, Jitendex)</i> " +
            "<span class=\"structured-content\"><div class=\"gloss-item\">" +
            "<ul class=\"gloss-sc-ul\"><li class=\"gloss-sc-li\">cat</li></ul></div></span></li>"
        val style = "<style>.yomitan-glossary [data-dictionary=\"Jitendex.org [2025]\"] li{color:red;}</style>"
        assertEquals(
            "<div style=\"text-align: left;\" class=\"yomitan-glossary\"><ol>$item</ol></div>$style",
            outputs.glossaryPrimary,
        )
        assertEquals(
            "<div style=\"text-align: left;\" class=\"yomitan-glossary\"><ol>$item" +
                "<li data-dictionary=\"PlayTranslate\"><i>(noun, PlayTranslate)</i> cat</li></ol></div>$style",
            outputs.glossary,
        )
        // Nothing highlighted: empty, as DEFINITION is.
        val none = AnkiCardOutputBuilder.forSentence(
            sentenceWith(own).copy(selectedWords = emptySet()), imageFilename = null,
        )
        assertEquals("", none.glossary)
        assertEquals("", none.glossaryPrimary)
    }

    // Mutations: ship the note raw; keep its outer whitespace; drop its
    // line breaks; fill the field for a blank note.
    @Test fun `the typed note is the Notes source in both modes, as plain escaped text`() {
        val sentence = AnkiCardOutputBuilder.forSentence(
            sentenceWith().copy(notes = "  line <one>\nline & two\r\n\n3  "), imageFilename = null,
        )
        assertEquals("line &lt;one&gt;<br>line &amp; two<br><br>3", sentence.notes)
        assertEquals("", AnkiCardOutputBuilder.forSentence(sentenceWith().copy(notes = " \n "), imageFilename = null).notes)

        val word = AnkiCardOutputBuilder.forWord(
            word = "猫", reading = "ねこ", pos = "noun", definitionHtml = "<div>def</div>",
            freqScore = 0, pitch = emptyList(), frequencies = emptyList(), imageFilename = null,
            glossaryHtml = "ALL", glossaryPrimaryHtml = "FIRST", notes = "a <b> note",
        )
        assertEquals("a &lt;b&gt; note", word.notes)
        assertEquals("ALL", word.glossary)
        assertEquals("FIRST", word.glossaryPrimary)
    }

    // The pipeline rebuilds the sheet's card data from its input by hand,
    // which is where a field gets left behind.
    // Mutation: leave the note out of the rebuilt card data.
    @Test fun `the pipeline's card data carries the note the sheet handed over`() {
        val input = SentenceSendInput(
            original = "猫が好き", translation = "I like cats", words = emptyList(),
            selectedWords = emptySet(), sourceLangId = com.playtranslate.language.SourceLangId.JA,
            screenshot = null, includeSentenceAudio = false, notes = "from chapter 3",
        )
        assertEquals("from chapter 3", input.toCardData().notes)
    }

    // The three hosts of the sentence editor copy the sheet's card into a
    // send through this one function.
    // Mutation: leave any one field of the card behind (the note first).
    @Test fun `a sheet's card becomes the send input whole`() {
        val entry = SentenceAnkiHtmlBuilder.WordEntry("猫", "ねこ", "cat")
        val override = com.playtranslate.language.ReadingOverride("猫", "猫", "ねこ")
        val shot = ScreenshotAttachment("/tmp/shot.jpg")
        val pick = com.playtranslate.audio.AudioSelection.Auto
        val data = SentenceAnkiContentView.CardData(
            source = "猫が好き", target = "I like cats", words = listOf(entry),
            selectedWords = setOf("猫"), screenshot = shot,
            sourceLangId = com.playtranslate.language.SourceLangId.ZH,
            targetWordAudioWords = setOf("猫"), sentenceSelection = pick,
            wordSelections = mapOf("猫" to pick), readingOverride = override,
            notes = "from chapter 3",
        )

        assertEquals(
            SentenceSendInput(
                original = "猫が好き", translation = "I like cats", words = listOf(entry),
                selectedWords = setOf("猫"), sourceLangId = com.playtranslate.language.SourceLangId.ZH,
                screenshot = shot, includeSentenceAudio = true, notes = "from chapter 3",
                targetWordAudioWords = setOf("猫"), sentenceSelection = pick,
                wordSelections = mapOf("猫" to pick), examplesHtml = "<div>ex</div>",
                readingOverride = override,
            ),
            data.toSendInput(includeSentenceAudio = true, examplesHtml = "<div>ex</div>"),
        )
        assertEquals("", data.toSendInput(includeSentenceAudio = false).examplesHtml)
        assertFalse(data.toSendInput(includeSentenceAudio = false).includeSentenceAudio)
    }

    // ─── Where a typed note lands, and when Save must ask ────────────────

    // Mutations: count a stale mapping entry as a destination; count any
    // mapped field; hold a note on an own model without the field.
    @Test fun `a card type holds a note when the assembler has a field to write it to`() {
        assertTrue(NotesDestination.ownModel(PtModels.WORD.fields))
        assertTrue(NotesDestination.ownModel(PtModels.SENTENCE.fields))
        // The user renamed or removed the field in Anki, or the model is
        // a generation from before the field existed.
        assertFalse(NotesDestination.ownModel(PtModels.WORD.fields - "Notes"))

        assertTrue(NotesDestination.basic(listOf("Front", "Back")))
        assertTrue(NotesDestination.basic(listOf("Front", "Back", "Picture")))

        val lapis = AnkiCardTypeMapper.defaultsForModel(model("Lapis", LAPIS_FIELDS), CardMode.WORD)
        assertTrue(NotesDestination.mapped(LAPIS_FIELDS, lapis))
        val jpmn = AnkiCardTypeMapper.defaultsForModel(model("JPMN", JPMN_FIELDS), CardMode.WORD)
        assertTrue(NotesDestination.mapped(JPMN_FIELDS, jpmn))
        val migaku = AnkiCardTypeMapper.defaultsForModel(model("Migaku Japanese", MIGAKU_FIELDS), CardMode.WORD)
        assertFalse(NotesDestination.mapped(MIGAKU_FIELDS, migaku))
        // A mapping entry for a field the model no longer has is no destination.
        assertFalse(NotesDestination.mapped(listOf("Word", "Meaning"), mapOf("OldNotes" to ContentSource.NOTES)))
        assertTrue(NotesDestination.mapped(listOf("Word", "Extra"), mapOf("Extra" to ContentSource.NOTES)))
    }

    // Mutations: ask about a blank note; ask when the note has a place.
    @Test fun `Save asks only for a typed note with nowhere to go`() {
        assertTrue(notesNeedConsent(hasNotes = true, holdsNotes = false))
        assertFalse(notesNeedConsent(hasNotes = true, holdsNotes = true))
        assertFalse(notesNeedConsent(hasNotes = false, holdsNotes = false))
        assertFalse(notesNeedConsent(hasNotes = false, holdsNotes = true))
    }
}
