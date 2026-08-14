package com.playtranslate.ui

import android.util.Log

private const val TAG = "PtModels"

/**
 * The two field-based PlayTranslate note types that back the "Default
 * (PlayTranslate)" card-type sentinel (`Prefs.ankiModelId == -1L`):
 * one model per [CardMode], created lazily on first send via
 * [com.playtranslate.AnkiManager.getOrCreatePtModel].
 *
 * Contract with AnkiDroid:
 *  - Models are matched by NAME ONLY. A user may add or reorder fields
 *    in Anki without spawning a duplicate model — [assemble] maps
 *    values by field name onto whatever field list the model actually
 *    has. (This is deliberately unlike the retired v005
 *    `getOrCreateModel`, which required an exact field match.)
 *  - An existing model's templates/CSS are NEVER rewritten — that
 *    would clobber user edits. A template fix ships as a version bump
 *    in the model name (v001 → v002), the same contract as the old
 *    v004 → v005 blob models. Old notes stay on their old model.
 */
object PtModels {

    /** Everything needed to create one note type through the provider. */
    data class Spec(
        val name: String,
        /** Canonical field names, in creation order. Index 0 is the
         *  sort field — Anki's duplicate key, and the csum the
         *  "already in Anki" detector rides. */
        val fields: List<String>,
        val css: String,
        val qfmt: String,
        val afmt: String,
    )

    val WORD = Spec(
        // v005: structured Yomitan glossaries in the Definition field
        // (GLOSSARY_CSS). The version-bump contract lives in the class doc.
        name = "PlayTranslate Word v005",
        fields = listOf(
            "Expression", "Reading", "PitchPosition", "PartOfSpeech",
            "Definition", "Examples", "Frequency", "Picture",
            "WordAudio", "AudioCredit",
        ),
        css = PtCardTemplates.WORD_CSS,
        qfmt = PtCardTemplates.WORD_QFMT,
        afmt = PtCardTemplates.WORD_AFMT,
    )

    val SENTENCE = Spec(
        // v005 in lockstep with WORD (shared CSS chain carries the
        // glossary styles into both).
        name = "PlayTranslate Sentence v005",
        fields = listOf(
            "Sentence", "SentenceFurigana", "Translation", "TargetWord",
            "WordsTable", "Picture", "SentenceAudio", "AudioCredit",
        ),
        css = PtCardTemplates.SENTENCE_CSS,
        qfmt = PtCardTemplates.SENTENCE_QFMT,
        afmt = PtCardTemplates.SENTENCE_AFMT,
    )

    fun specFor(mode: CardMode): Spec = when (mode) {
        CardMode.WORD -> WORD
        CardMode.SENTENCE -> SENTENCE
    }

    /**
     * Model-name prefixes for every synthetic PlayTranslate note type,
     * past and present — [com.playtranslate.AnkiManager.getModels]
     * hides matches from the Card Type picker (they're reached through
     * the "Default (PlayTranslate)" sentinel instead). An explicit
     * list, not a broad "PlayTranslate " prefix, so a user's own note
     * type named e.g. "PlayTranslate Mining" stays pickable.
     */
    private val SYNTHETIC_NAME_PREFIXES = listOf(
        "PlayTranslate v",          // v003/v004/v005 blob models
        "PlayTranslate Word v",
        "PlayTranslate Sentence v",
    )

    fun isSyntheticName(name: String): Boolean =
        SYNTHETIC_NAME_PREFIXES.any { name.startsWith(it) }

    /** What a stored question format says about who owns a same-named
     *  model's templates. The authority for repair decisions lives in
     *  AnkiDroid's own state — an app-local flag would not survive
     *  reinstall or a second device and could authorize clobbering
     *  user-edited templates. */
    enum class TemplateState {
        /** Carries the `pt-q` wrapper — our install landed (possibly
         *  user-tweaked since). Reuse untouched. */
        OURS,
        /** AnkiDroid's auto-generated template (bare `{{Field0}}`, or
         *  empty) — the one state a failed install leaves behind, and
         *  the only state repair may overwrite. */
        AUTO_GENERATED,
        /** Neither — a user rewrite that dropped our marker. Theirs;
         *  never touch. */
        FOREIGN,
    }

    /**
     * Classifies the template read back from `models/{id}/templates/0`
     * for [spec]'s model, from BOTH sides — a partial provider write
     * could land the front and drop the back, and a front-only check
     * would classify that model OURS forever, sticking every future
     * card with an auto-generated back. Pure so the repair-eligibility
     * rules are unit-testable without a provider.
     *
     * The misclassification asymmetry drives every rule: calling a
     * user rewrite AUTO_GENERATED destroys their template edits (no
     * undo in Anki), while calling a genuine auto template FOREIGN
     * merely leaves ugly-but-working cards the user can fix by
     * deleting the note type. So the auto shapes are matched EXACTLY —
     * `{{<field0>}}` fronts and `{{FrontSide}}\n\n<hr id=answer>\n\n
     * {{<field1>}}` backs, the shapes the provider builds for inserted
     * models (the v005-era cards rendered precisely them) — and
     * everything unrecognized is FOREIGN. That deliberately covers the
     * common hand-written Anki back that USES `{{FrontSide}}` and
     * `<hr id=answer>` but isn't byte-identical to the auto shape:
     * it stays FOREIGN, never repaired.
     */
    fun classifyStoredTemplate(
        storedQfmt: String?,
        storedAfmt: String?,
        spec: Spec,
    ): TemplateState {
        val qfmtOurs = storedQfmt?.contains("pt-q") == true
        if (!qfmtOurs) {
            val qfmtAuto = storedQfmt.isNullOrBlank() ||
                storedQfmt.trim() == "{{${spec.fields.first()}}}"
            return if (qfmtAuto) TemplateState.AUTO_GENERATED else TemplateState.FOREIGN
        }
        if (storedAfmt?.contains("pt-a") == true) return TemplateState.OURS
        val afmtAuto = storedAfmt.isNullOrBlank() ||
            storedAfmt.trim() == "{{FrontSide}}\n\n<hr id=answer>\n\n{{${spec.fields[1]}}}"
        return if (afmtAuto) TemplateState.AUTO_GENERATED else TemplateState.FOREIGN
    }

    /**
     * Builds the provider field array for [note] against the model's
     * ACTUAL field list (read back from AnkiDroid, not assumed).
     * Name-keyed so user-added or reordered fields are tolerated; a
     * field the user renamed simply gets "" (and if that was the sort
     * field, the provider will reject the note as a duplicate — the
     * warning here is the diagnostic for that otherwise-mysterious
     * failure).
     */
    fun assemble(modelFieldNames: List<String>, note: PtNote): List<String> {
        val values = note.toValues()
        val fields = modelFieldNames.map { values[it] ?: "" }
        val unmatched = values.keys - modelFieldNames.toSet()
        if (unmatched.isNotEmpty()) {
            Log.w(TAG, "assemble: model is missing canonical field(s) $unmatched " +
                "— their content is dropped (fields renamed in AnkiDroid?)")
        }
        if (fields.firstOrNull().isNullOrEmpty()) {
            Log.w(TAG, "assemble: sort field '${modelFieldNames.firstOrNull()}' is empty " +
                "— AnkiDroid will reject every note after the first as a duplicate")
        }
        return fields
    }
}

/**
 * Canonical field values for one note of a [PtModels] type. Field
 * names here are the single source of truth [PtModels.assemble] maps
 * from — they must match the corresponding [PtModels.Spec.fields].
 */
sealed interface PtNote {
    fun toValues(): Map<String, String>

    data class Word(
        val expression: String,
        val reading: String,
        val pitchPosition: String,
        val partOfSpeech: String,
        val definition: String,
        val examples: String,
        val frequency: String,
        val picture: String,
        val wordAudio: String,
        val audioCredit: String,
    ) : PtNote {
        override fun toValues(): Map<String, String> = mapOf(
            "Expression" to expression,
            "Reading" to reading,
            "PitchPosition" to pitchPosition,
            "PartOfSpeech" to partOfSpeech,
            "Definition" to definition,
            "Examples" to examples,
            "Frequency" to frequency,
            "Picture" to picture,
            "WordAudio" to wordAudio,
            "AudioCredit" to audioCredit,
        )
    }

    data class Sentence(
        val sentence: String,
        val sentenceFurigana: String,
        val translation: String,
        val targetWord: String,
        val wordsTable: String,
        val picture: String,
        val sentenceAudio: String,
        val audioCredit: String,
    ) : PtNote {
        override fun toValues(): Map<String, String> = mapOf(
            "Sentence" to sentence,
            "SentenceFurigana" to sentenceFurigana,
            "Translation" to translation,
            "TargetWord" to targetWord,
            "WordsTable" to wordsTable,
            "Picture" to picture,
            "SentenceAudio" to sentenceAudio,
            "AudioCredit" to audioCredit,
        )
    }
}
