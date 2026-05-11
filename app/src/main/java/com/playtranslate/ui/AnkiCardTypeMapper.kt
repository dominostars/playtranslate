package com.playtranslate.ui

import com.playtranslate.AnkiManager
import java.util.Locale

/**
 * Maps a freshly-picked AnkiDroid note type to a starter field-mapping
 * for known templates (Lapis, JPMN, Anki Basic). Detection is
 * intentionally conservative — name first, field-schema fingerprint
 * second — and unknown templates return empty so the user wires them up
 * explicitly via [AnkiFieldMappingDialog]. Pure logic, no Android imports
 * beyond [AnkiManager.ModelInfo].
 */
object AnkiCardTypeMapper {

    /** Lapis (Donkuri / Marv) — popular simple mining template. */
    private val LAPIS_DEFAULTS: Map<String, ContentSource> = mapOf(
        "Word"            to ContentSource.EXPRESSION,
        "WordReading"     to ContentSource.READING,
        "Sentence"        to ContentSource.SENTENCE,
        "SentenceMeaning" to ContentSource.SENTENCE_TRANSLATION,
        "Glossary"        to ContentSource.DEFINITION,
        "Picture"         to ContentSource.PICTURE,
    )

    /**
     * JPMN (Aquafina / Arbyste). We deliberately leave
     * `ExpressionFurigana` / `SentenceFurigana` / `SentenceReading` at
     * NONE: JPMN expects bracketed `kanji[kana]` format in those fields,
     * which PlayTranslate doesn't currently produce. Filling them with
     * plain kana would silently break the `{{furigana:}}` filter the
     * JPMN template uses. Users who want plain kana there can override
     * in the mapping dialog.
     */
    private val JPMN_DEFAULTS: Map<String, ContentSource> = mapOf(
        "Expression"        to ContentSource.EXPRESSION,
        "ExpressionReading" to ContentSource.READING,
        "MainDefinition"    to ContentSource.DEFINITION,
        "Sentence"          to ContentSource.SENTENCE,
        "Picture"           to ContentSource.PICTURE,
        "FrequencySort"     to ContentSource.FREQUENCY,
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

        // Lapis: name OR characteristic field-set.
        if ("lapis" in nameLower) return LAPIS_DEFAULTS.filterKeys { it in fields }
        if (fields.containsAll(setOf("Word", "Glossary", "SentenceMeaning"))) {
            return LAPIS_DEFAULTS.filterKeys { it in fields }
        }

        // JPMN: name (Aquafina canonical "Japanese Mining Note" / common
        // "JPMN" abbreviation) OR characteristic field-set.
        if ("japanese mining note" in nameLower || "jpmn" in nameLower) {
            return JPMN_DEFAULTS.filterKeys { it in fields }
        }
        if (fields.containsAll(setOf("Expression", "MainDefinition")) &&
            (fields.contains("ExpressionReading") || fields.contains("ExpressionFurigana"))) {
            return JPMN_DEFAULTS.filterKeys { it in fields }
        }

        // Anki Basic shape: exact {Front, Back} or {Front, Back, Picture}.
        // Anything else with a Front field is too ambiguous — leave blank.
        if (fields == setOf("Front", "Back") ||
            fields == setOf("Front", "Back", "Picture")) {
            return when (mode) {
                CardMode.WORD     -> BASIC_WORD_DEFAULTS.filterKeys { it in fields }
                CardMode.SENTENCE -> BASIC_SENTENCE_DEFAULTS.filterKeys { it in fields }
            }
        }

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
