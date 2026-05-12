package com.playtranslate.ui

import android.util.Log
import com.playtranslate.AnkiManager
import java.util.Locale

private const val TAG = "CardTypeMapper"

/**
 * Maps a freshly-picked AnkiDroid note type to a starter field-mapping
 * for known templates (Lapis, JPMN, Anki Basic). Detection is
 * intentionally conservative — name first, field-schema fingerprint
 * second — and unknown templates return empty so the user wires them up
 * explicitly via [AnkiFieldMappingDialog]. Pure logic, no Android imports
 * beyond [AnkiManager.ModelInfo].
 */
object AnkiCardTypeMapper {

    /**
     * Lapis (donkuri/lapis). Per the canonical README, Lapis uses an
     * Expression/MainDefinition/Glossary schema (NOT Word/Glossary like
     * older docs sometimes claim). Furigana / Reading variants (the
     * bracketed `kanji[kana]` form) stay NONE because we don't compute
     * that format; ditto audio fields. `Glossary` is an alternative
     * primary definition slot — user can swap to DEFINITION via the
     * mapping dialog if they prefer it over `MainDefinition`.
     *
     * Flag wiring: `IsWordAndSentenceCard` fires for PT word sends
     * (the closest Lapis variant — word on front, sentence below as
     * hint, matching PT's word-flow data shape). `IsSentenceCard`
     * fires for PT sentence sends. Lapis recommends "only one
     * selector at a time", which the mode-aware sources guarantee:
     * exactly one of the two flag fields is non-empty per send.
     */
    private val LAPIS_DEFAULTS: Map<String, ContentSource> = mapOf(
        // Lapis splits both Expression and Sentence into plain +
        // furigana variants. `Expression` and `Sentence` are rendered
        // raw via `{{Expression}}` / `{{kanji:Sentence}}` (the latter
        // strips brackets but plain works too); the `*Furigana` fields
        // are rendered with `{{furigana:}}` and need the bracketed
        // payload to produce ruby.
        "Expression"            to ContentSource.EXPRESSION,
        "ExpressionFurigana"    to ContentSource.EXPRESSION_FURIGANA,
        "ExpressionReading"     to ContentSource.READING,
        "MainDefinition"        to ContentSource.DEFINITION,
        "Sentence"              to ContentSource.SENTENCE,
        "SentenceFurigana"      to ContentSource.SENTENCE_FURIGANA,
        "Picture"               to ContentSource.PICTURE,
        "Frequency"             to ContentSource.FREQUENCY,
        "IsWordAndSentenceCard" to ContentSource.VOCABULARY_CARD_FLAG,
        "IsSentenceCard"        to ContentSource.SENTENCE_CARD_FLAG,
    )

    /**
     * JPMN — jp-mining-note (Aquafina-water-bottle). Per the actual
     * apkg's template references, JPMN has THREE word fields and TWO
     * sentence fields with distinct semantics:
     *
     *  - `Word`                = plain kanji form (e.g. `偽者`),
     *                            rendered raw via `{{Word}}` and
     *                            `{{text:Word}}`
     *  - `WordReading`         = bracketed furigana form
     *                            (e.g. `偽者[にせもの]`), rendered via
     *                            `{{furigana:WordReading}}` on the back
     *  - `WordReadingHiragana` = pure hiragana (e.g. `にせもの`), used
     *                            for sorting and the kana-only display
     *
     *  - `Sentence`        = plain text with `<b>` highlight
     *                        (e.g. `…<b>偽者</b>だな！`), rendered raw
     *                        via `{{Sentence}}` on every card type
     *  - `SentenceReading` = bracketed furigana form with `<b>`
     *                        (e.g. `…<b> 偽者[にせもの]</b>だな！`),
     *                        rendered via `{{furigana:SentenceReading}}`
     *
     * Audio fields are unmapped — PT doesn't produce audio.
     */
    private val JPMN_DEFAULTS: Map<String, ContentSource> = mapOf(
        "Word"                    to ContentSource.EXPRESSION,
        "WordReading"             to ContentSource.EXPRESSION_FURIGANA,
        "WordReadingHiragana"     to ContentSource.READING,
        "PrimaryDefinition"       to ContentSource.DEFINITION,
        "Sentence"                to ContentSource.SENTENCE,
        "SentenceReading"         to ContentSource.SENTENCE_FURIGANA,
        "Picture"                 to ContentSource.PICTURE,
        // JPMN's vocab variant is the no-flag default; we only fire the
        // sentence + targeted-sentence flags. IsTargetedSentenceCard
        // combines with IsSentenceCard per JPMN's compound-flag rules:
        // sentence sends with bolded words → "Targeted Sentence Card"
        // (sentence on front, bolded word is the test target).
        "IsSentenceCard"          to ContentSource.SENTENCE_CARD_FLAG,
        "IsTargetedSentenceCard"  to ContentSource.TARGETED_SENTENCE_CARD_FLAG,
    )

    /**
     * Migaku — the modern Migaku Browser Extension note type. Field
     * names use spaces (e.g. `Target Word`) — opposite of Lapis/JPMN.
     * Importantly, `Is Vocabulary Card` and `Is Audio Card` are STATE
     * FLAGS: any non-empty content flips Migaku's card rendering.
     * Those MUST stay NONE.
     *
     * Audio fields (`Sentence Audio`, `Word Audio`) and `Example
     * Sentences` are unmapped — we don't produce that content. `Images`
     * is a secondary media slot; we put screenshots in `Screenshot`
     * which is the canonical PT-side equivalent.
     */
    private val MIGAKU_DEFAULTS: Map<String, ContentSource> = mapOf(
        // Migaku's `Sentence` and `Target Word` both render with
        // furigana — Migaku's support.html parses the bracket syntax
        // for ruby + the tap-popup. Both map to bracketed variants.
        "Sentence"           to ContentSource.SENTENCE_FURIGANA,
        "Translation"        to ContentSource.SENTENCE_TRANSLATION,
        "Target Word"        to ContentSource.EXPRESSION_FURIGANA,
        "Definitions"        to ContentSource.DEFINITION,
        "Screenshot"         to ContentSource.PICTURE,
        // Migaku is the only template among the four we recognize with
        // a dedicated example-sentences slot. Filled from Tatoeba pairs
        // when the send routes through WordAnkiReviewSheet (which
        // carries the word-lookup context); empty otherwise.
        "Example Sentences"  to ContentSource.EXAMPLE_SENTENCES,
        // Is Vocabulary Card fires "x" for word sends only (matches
        // Migaku's own addon: "x" toggles vocab variant; empty leaves
        // the default sentence-card layout). Is Audio Card stays
        // unmapped because PT doesn't produce audio.
        "Is Vocabulary Card" to ContentSource.VOCABULARY_CARD_FLAG,
    )

    private val BASIC_WORD_DEFAULTS: Map<String, ContentSource> = mapOf(
        "Front"   to ContentSource.EXPRESSION,
        "Back"    to ContentSource.DEFINITION,
        "Picture" to ContentSource.PICTURE,
    )

    private val BASIC_SENTENCE_DEFAULTS: Map<String, ContentSource> = mapOf(
        "Front"   to ContentSource.SENTENCE,
        "Back"    to ContentSource.SENTENCE_TRANSLATION,
        "Picture" to ContentSource.PICTURE,
    )

    /**
     * Returns starter defaults for a recognised template, or empty map
     * if [model] doesn't match a known shape. Detection layered:
     *
     * 1. Model name substring (case-insensitive) — catches renamed
     *    models whose schema may have drifted.
     * 2. Field-schema fingerprint — catches recognisable mining-note
     *    layouts even when the user renamed the model.
     * 3. Otherwise empty — the user wires it up manually.
     *
     * [mode] only affects Basic-shape detection; mining-note templates
     * (Lapis, JPMN) have stable field semantics across word and
     * sentence cards.
     */
    fun defaultsForModel(
        model: AnkiManager.ModelInfo,
        mode: CardMode,
    ): Map<String, ContentSource> {
        val fields = model.fieldNames.toSet()
        val nameLower = model.name.lowercase(Locale.ROOT)
        Log.d(TAG, "defaultsForModel: name='${model.name}' " +
            "mode=$mode fieldCount=${model.fieldNames.size}")
        Log.d(TAG, "  fields=${model.fieldNames}")

        // Migaku (modern Browser Extension schema). Name match catches
        // the canonical "Migaku Japanese" model; the field fingerprint
        // `Is Vocabulary Card` is distinctive because (a) Lapis uses
        // `IsAudioCard` / `IsSentenceCard` *without* spaces, and (b) no
        // other mining template uses a "Vocabulary Card" state flag.
        // Checked first so a "Migaku JPMN" combo name routes via the
        // Migaku schema (whose field names are completely different).
        if ("migaku" in nameLower) {
            val out = MIGAKU_DEFAULTS.filterKeys { it in fields }
            Log.d(TAG, "  matched: Migaku (name); applied=$out")
            return out
        }
        if ("Is Vocabulary Card" in fields || "Is Audio Card" in fields) {
            val out = MIGAKU_DEFAULTS.filterKeys { it in fields }
            Log.d(TAG, "  matched: Migaku (state-flag fingerprint); applied=$out")
            return out
        }

        // Lapis: name OR characteristic field-set. `MainDefinition` is
        // unique to Lapis among the templates we know (JPMN uses
        // `PrimaryDefinition`), so its presence alongside `Expression`
        // is a strong fingerprint.
        if ("lapis" in nameLower) {
            val out = LAPIS_DEFAULTS.filterKeys { it in fields }
            Log.d(TAG, "  matched: Lapis (name); applied=$out")
            return out
        }
        if ("MainDefinition" in fields && "Expression" in fields) {
            val out = LAPIS_DEFAULTS.filterKeys { it in fields }
            Log.d(TAG, "  matched: Lapis (fingerprint); applied=$out")
            return out
        }

        // JPMN: name (Aquafina canonical "Japanese Mining Note" / common
        // "JPMN" abbreviation) OR characteristic field-set. JPMN uses
        // `Word`/`PrimaryDefinition` (NOT `Expression`/`MainDefinition`
        // — that's Lapis), so the fingerprint targets `PrimaryDefinition`
        // alongside `Word`.
        if ("japanese mining note" in nameLower || "jpmn" in nameLower) {
            val out = JPMN_DEFAULTS.filterKeys { it in fields }
            Log.d(TAG, "  matched: JPMN (name); applied=$out")
            return out
        }
        if ("PrimaryDefinition" in fields && "Word" in fields) {
            val out = JPMN_DEFAULTS.filterKeys { it in fields }
            Log.d(TAG, "  matched: JPMN (fingerprint); applied=$out")
            return out
        }

        // Anki Basic shape: exact {Front, Back} or {Front, Back, Picture}.
        // Anything else with a Front field is too ambiguous — leave blank.
        if (fields == setOf("Front", "Back") ||
            fields == setOf("Front", "Back", "Picture")) {
            val out = when (mode) {
                CardMode.WORD     -> BASIC_WORD_DEFAULTS.filterKeys { it in fields }
                CardMode.SENTENCE -> BASIC_SENTENCE_DEFAULTS.filterKeys { it in fields }
            }
            Log.d(TAG, "  matched: Basic shape ($mode); applied=$out")
            return out
        }

        Log.d(TAG, "  no template match — mapping will start blank")
        return emptyMap()
    }

    /**
     * Builds the field array for AnkiDroid. Walks [modelFieldNames] in
     * declaration order and writes [outputs].valueFor(mapping[fieldName] ?: NONE)
     * for each. The user's saved mapping is authoritative — no
     * heuristics, no fallbacks, no collisions.
     */
    fun assembleNote(
        modelFieldNames: List<String>,
        mapping: Map<String, ContentSource>,
        outputs: CardOutputs,
    ): List<String> = modelFieldNames.map { fieldName ->
        outputs.valueFor(mapping[fieldName] ?: ContentSource.NONE)
    }
}
