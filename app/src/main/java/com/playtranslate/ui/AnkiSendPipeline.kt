package com.playtranslate.ui

import android.content.Context
import android.util.Log
import androidx.fragment.app.Fragment
import com.playtranslate.R
import com.playtranslate.audio.Attribution
import com.playtranslate.audio.AudioRequest
import com.playtranslate.audio.AudioSelection
import com.playtranslate.audio.AudioSelections
import com.playtranslate.audio.ResolvedAudio
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.FrequencyTag
import java.io.File

/**
 * Shared "synthesize audio → dispatch → clean up" envelope. Both the
 * review sheets and the one-tap helpers call into this file so the
 * audio handling and temp-file lifecycle live in one place.
 *
 * The pipeline does NOT own:
 *  - **Preloading** (waiting for in-flight translation or word lookups
 *    to land). Sheets preload eagerly on view-create; one-tap helpers
 *    await the same caches before building the input.
 *  - **Result-handling UX** (toast, dismiss, fragment-result, button
 *    restore). Each caller maps [AnkiSendResult] onto its own surface.
 */

/**
 * True when [sentenceOriginal] adds nothing over the headword [word] — it's
 * absent, or equals the word once whitespace is stripped (a single-word lookup
 * whose "sentence" is just the word). The Anki flow uses this as the **one**
 * shared rule for "is there a real sentence here?", so every entry point treats
 * such a case as a plain word card instead of a degenerate Sentence card that
 * merely repeats the headword.
 *
 * Whitespace-stripped comparison mirrors
 * [TranslationResultViewModel.WordLookupsState.Settled.singleWordRow]; punctuation
 * stays significant (a trailing 。 means there is genuinely more than the bare word).
 */
fun sentenceIsJustTheWord(sentenceOriginal: String?, word: String): Boolean {
    if (sentenceOriginal == null) return true
    fun bare(s: String) = s.filterNot(Char::isWhitespace)
    return bare(sentenceOriginal) == bare(word)
}

/** Inputs needed to send a sentence card. Mirrors the fields of
 *  [SentenceAnkiContentView.CardData] plus the audio-toggle state
 *  the sheet keeps separately. One-tap callers build this directly
 *  from their available context. */
data class SentenceSendInput(
    val original: String,
    val translation: String,
    val words: List<SentenceAnkiHtmlBuilder.WordEntry>,
    val selectedWords: Set<String>,
    val sourceLangId: SourceLangId,
    val screenshotPath: String?,
    val includeSentenceAudio: Boolean,
    /** Words whose per-target audio is enabled. Empty in one-tap (the
     *  sheet's per-word toggle isn't surfaced). */
    val targetWordAudioWords: Set<String> = emptySet(),
    /** Multi-source audio selection for the sentence cell. [AudioSelection.Auto]
     *  (the default, and what one-tap sends) is the registry's Commons-first →
     *  TTS resolution with the user's saved voice — a fresh sheet's behavior. */
    val sentenceSelection: AudioSelection = AudioSelection.Auto,
    /** Per-target-word audio selections. Missing entry = [AudioSelection.Auto]. */
    val wordSelections: Map<String, AudioSelection> = emptyMap(),
    /** Pre-built Tatoeba "more examples" block for the structured
     *  path's EXAMPLE_SENTENCES content source. The word-tab's
     *  sentence flow has examples; the result-screen sentence flow
     *  passes empty. */
    val examplesHtml: String = "",
)

/** Inputs needed to send a word card. The Definition field arrives as
 *  DATA ([definition]) — the pipeline fetches its structured-glossary
 *  payload and renders both the default model's class-styled HTML and the
 *  structured path's inline-styled HTML, so the sheet and one-tap cannot
 *  drift (see [WordCardDefinition]). */
data class WordSendInput(
    val word: String,
    val reading: String,
    val pos: String,
    val freqScore: Int,
    /** Pitch downsteps + per-dictionary frequencies for the word (from
     *  `entry.headwordDisplay(word)`), feeding the structured path's
     *  PITCH_POSITION / FREQUENCY_* sources. Required (not defaulted) so a
     *  word-send construction site that forgets to thread them is a compile
     *  error rather than a silently-blank field. */
    val pitch: List<Int>,
    val frequencies: List<FrequencyTag>,
    val sourceLangId: SourceLangId,
    val screenshotPath: String?,
    val includeWordAudio: Boolean,
    /** Multi-source audio selection for the headword (Commons-first → TTS).
     *  [AudioSelection.Auto] (the default, and what one-tap sends) resolves
     *  the user's saved voice — see [SentenceSendInput.sentenceSelection]. */
    val wordSelection: AudioSelection = AudioSelection.Auto,
    /** What the Definition field renders: the resolved entry plus the
     *  sheet's curation (one-tap: the bare entry). Rendered by
     *  [sendWordCard] with [classStyler] for the default model and
     *  [inlineStyler] for the structured path. */
    val definition: WordCardDefinition,
    /** Tatoeba "More examples" block — WITH its localized gl-section
     *  header — for the default model's Examples field. Built with
     *  [classStyler] in the sheet; empty for one-tap. */
    val defaultExamplesHtml: String = "",
    /** The same "More examples" block, inline-styled, appended after the
     *  definition panel in the structured path's DEFINITION content source
     *  (that source carries the whole definition body). Empty for one-tap
     *  (no Tatoeba lookup). */
    val inlineMoreExamplesHtml: String = "",
    /** Tatoeba "more examples" block for the structured path's
     *  EXAMPLE_SENTENCES content source — headerless (the receiving
     *  field's template carries its own label). Empty for one-tap (no
     *  resolved entry → no Tatoeba lookup). */
    val inlineExamplesHtml: String = "",
)

/**
 * Sentence-card send pipeline. Synthesizes sentence + per-target-word
 * audio, dispatches the card, and cleans up temp WAVs in a finally.
 * The dispatcher's resolved-model UX (NeedsMapping) propagates back
 * via [AnkiSendResult] for the caller to handle.
 */
suspend fun Context.sendSentenceCard(
    input: SentenceSendInput,
    deckId: Long,
): AnkiSendResult {
    val ctx = this
    // Pin the screenshot BEFORE the audio synthesis below: the capture
    // surfaces overwrite fixed cache filenames, and the seconds this
    // pipeline spends on audio + binder are exactly when a rapid re-capture
    // would swap the frame under the upload. The card must attach what the
    // user was looking at when they sent it.
    val pinnedScreenshotPath = AnkiScreenshotPin.pin(ctx, input.screenshotPath)
    // Sentence audio: multi-source resolution, Commons-first → TTS floor with
    // the user's saved voice. Every caller — sheet and one-tap — goes through
    // the registry; the TTS floor speaks SpokenText's kana pronunciation, so
    // compound readings (初夏 → はつか) aren't re-guessed by the engine.
    val sentenceResolved: ResolvedAudio? = if (input.includeSentenceAudio) {
        AudioSelections.toFile(
            ctx, input.sentenceSelection,
            AudioRequest.sentence(input.original, input.sourceLangId),
        )
    } else null
    val audioFile: File? = sentenceResolved?.file
    // Per-target-word audio. Words whose synth/fetch returns null are skipped —
    // the rest of the card still lands. Keyed by the same WordEntry.word the
    // media files are stored under.
    val readingByWord = input.words.associate { it.word to it.reading }
    val wordResolved: Map<String, ResolvedAudio> = buildMap {
        for (word in input.targetWordAudioWords) {
            val resolved = AudioSelections.toFile(
                ctx, input.wordSelections[word] ?: AudioSelection.Auto,
                AudioRequest.word(word, readingByWord[word]?.ifBlank { null }, input.sourceLangId),
            )
            if (resolved != null) put(word, resolved)
        }
    }
    val wordAudioFiles: Map<String, File> = wordResolved.mapValues { it.value.file }
    // CC attribution for Commons clips, kept separate per audio field so the
    // credit travels even when the card type maps only one of the audio fields.
    // The legacy single-field back uses the aggregate.
    val sentenceCredit: String? =
        sentenceResolved?.attribution?.let { Attribution.creditBlock(listOf(it)) }
    val wordCredit: String? = wordResolved.values.mapNotNull { it.attribution }
        .takeIf { it.isNotEmpty() }?.let { Attribution.creditBlock(it) }
    val audioCredit: String? = run {
        val all = listOfNotNull(sentenceResolved?.attribution) +
            wordResolved.values.mapNotNull { it.attribution }
        if (all.isEmpty()) null else Attribution.creditBlock(all)
    }
    val result = try {
        // Everything from here sits INSIDE the pin/audio cleanup scope: the
        // annotation fetch is a suspend point that can throw or be
        // cancelled, and the finally below must release the screenshot pin
        // and delete ephemeral audio on EVERY post-pin failure path
        // (adversarial-review finding — the fetch briefly lived above this
        // try and could leak both).
        val cardData = input.toCardData()
        // ONE analysis for the card: reuse the cached annotation when it
        // still matches the (possibly edited) sentence text, else
        // re-annotate the final text. snapshotFor only returns
        // import-generation-CURRENT annotations, so a Yomitan install
        // mid-session can never leak pre-import readings onto a card. The
        // renderers draw this annotation — card furigana, highlights, and
        // wrappers must describe the text actually being sent, and must
        // match what the result screen displayed and TTS spoke.
        val annotation = LastSentenceCache.snapshotFor(cardData.source)?.annotation
            ?.takeIf { it.text == cardData.source }
            ?: com.playtranslate.language.SourceLanguageEngines
                .get(ctx.applicationContext, cardData.sourceLangId)
                .annotate(cardData.source)
        // Styled payload for the words table: fetched ONCE here so both
        // the sheet path and one-tap (which funnel through this function)
        // render imported senses as real structure on the card, with each
        // dictionary's CSS scoped inline (Tier 2). Null (flat dicts,
        // styling off) = today's flat rows.
        val styledPayload = fetchStyledForSenses(
            ctx.applicationContext,
            cardData.sourceLangId.yomitanConsumingLang(),
            cardData.words.flatMap { it.senses },
        )
        val structuredGlossaries = styledPayload?.structured.orEmpty()
        val cardDictStyles = styledPayload?.dictStyles.orEmpty()
        ctx.dispatchSendToAnki(
            deckId = deckId,
            mode = CardMode.SENTENCE,
            screenshotPath = pinnedScreenshotPath,
            audioPath = audioFile?.absolutePath,
            wordAudioPaths = wordAudioFiles.mapValues { it.value.absolutePath },
            ptNote = { imageFilename, audioFilename, wordAudioFilenames ->
                PtNoteBuilder.forSentence(
                    cardData = cardData,
                    annotation = annotation,
                    imageFilename = imageFilename,
                    audioFilename = audioFilename,
                    wordAudioFilenames = wordAudioFilenames,
                    // Aggregate credit (sentence + per-word) — the default
                    // model has one AudioCredit field.
                    audioCredit = audioCredit,
                    wordsSectionHeader = ctx.getString(R.string.card_words_in_sentence),
                    commonLabel = ctx.getString(R.string.word_detail_common),
                    localizePos = ctx::localizePos,
                    renderMisc = ctx::renderMiscText,
                    structuredGlossaries = structuredGlossaries,
                    dictStyles = cardDictStyles,
                )
            },
            structured = { imageFilename, audioFilename, wordAudioFilenames ->
                AnkiCardOutputBuilder.forSentence(
                    cardData = cardData,
                    annotation = annotation,
                    imageFilename = imageFilename,
                    examplesHtml = input.examplesHtml,
                    audioFilename = audioFilename,
                    wordAudioFilenames = wordAudioFilenames,
                    sentenceAudioCredit = sentenceCredit,
                    wordAudioCredit = wordCredit,
                    commonLabel = ctx.getString(R.string.word_detail_common),
                    localizePos = ctx::localizePos,
                    renderMisc = ctx::renderMiscText,
                    definitionsHeader = ctx.getString(R.string.anki_group_definitions),
                    structuredGlossaries = structuredGlossaries,
                    dictStyles = cardDictStyles,
                )
            },
        )
    } finally {
        // Only delete ephemeral TTS temp files; cached Commons clips stay in
        // the audio cache for reuse.
        if (sentenceResolved?.ephemeral != false) audioFile?.delete()
        wordResolved.values.forEach { if (it.ephemeral) it.file.delete() }
        // The upload (or its failure) is behind us — the pin's job is done.
        // A synthesis throw above skips this; the pin sweep collects it.
        AnkiScreenshotPin.release(ctx, pinnedScreenshotPath)
    }
    // The dispatcher only knows about UPLOAD failures (audioPath was
    // non-null but addMediaFromFile dropped it). Resolution failures
    // (AudioSelections.toFile returned null — synth error, dead pick,
    // blown budget) never reach it because we pass null audioPath in
    // that case. Fold them in here so callers can read a single
    // "audio requested but missing" flag.
    return result.foldInLocalAudioMisses(
        sentenceMissing = input.includeSentenceAudio && audioFile == null,
        wordAudioMissing = input.targetWordAudioWords.isNotEmpty() &&
            wordAudioFiles.size < input.targetWordAudioWords.size,
    )
}

private const val WORD_SEND_TAG = "AnkiWordSend"

/**
 * Word-card send pipeline. Renders the Definition field from
 * [WordSendInput.definition] (structured-glossary fetch included),
 * synthesizes word audio, dispatches the card, and cleans up the temp WAV
 * in a finally.
 */
suspend fun Context.sendWordCard(
    input: WordSendInput,
    deckId: Long,
): AnkiSendResult {
    val ctx = this
    // Pin before synthesis — see sendSentenceCard.
    val pinnedScreenshotPath = AnkiScreenshotPin.pin(ctx, input.screenshotPath)
    // Headword audio: multi-source resolution (Commons-first → TTS floor with
    // the user's saved voice), same registry walk for sheet and one-tap.
    val wordResolved: ResolvedAudio? = if (input.includeWordAudio) {
        AudioSelections.toFile(
            ctx, input.wordSelection,
            AudioRequest.word(input.word, input.reading.ifBlank { null }, input.sourceLangId),
        )
    } else null
    val audioFile: File? = wordResolved?.file
    // CC credit for a Commons clip, co-located with the word audio field so the
    // attribution travels with redistributed cards. Null for plain TTS audio.
    val audioCredit: String? =
        wordResolved?.attribution?.let { Attribution.creditBlock(listOf(it)) }
    val result = try {
        // The Definition field renders HERE, for every caller: structured
        // glossaries for the entry's imported groups (null = flat text
        // throughout), then both stylers' HTML from one builder. Inside the
        // try so a throw still releases the audio temp file and the pin.
        val definition = input.definition
        val styled = fetchYomitanStyledData(
            ctx.applicationContext,
            input.sourceLangId.yomitanConsumingLang(),
            definition.importedGroups,
        )
        Log.i(
            WORD_SEND_TAG,
            "word card: groups=${definition.importedGroups.size} " +
                "styled=${styled?.structured?.size ?: 0}",
        )
        val definitionsHeader = ctx.getString(R.string.anki_group_definitions)
        val renderMisc: (List<String>) -> String? = ctx::renderMiscText
        val defaultDefinitionHtml =
            definition.panelHtml(classStyler, styled, definitionsHeader, renderMisc)
        val inlineDefinitionHtml =
            definition.panelHtml(inlineStyler, styled, definitionsHeader, renderMisc) +
                input.inlineMoreExamplesHtml
        ctx.dispatchSendToAnki(
            deckId = deckId,
            mode = CardMode.WORD,
            screenshotPath = pinnedScreenshotPath,
            audioPath = audioFile?.absolutePath,
            ptNote = { imageFilename, audioFilename, _ ->
                // Word cards have no per-target-word audio — drop the
                // third arg.
                PtNoteBuilder.forWord(
                    word = input.word,
                    reading = input.reading,
                    pos = input.pos,
                    definitionHtml = defaultDefinitionHtml,
                    examplesHtml = input.defaultExamplesHtml,
                    freqScore = input.freqScore,
                    pitch = input.pitch,
                    frequencies = input.frequencies,
                    imageFilename = imageFilename,
                    audioFilename = audioFilename,
                    audioCredit = audioCredit,
                )
            },
            structured = { imageFilename, audioFilename, _ ->
                AnkiCardOutputBuilder.forWord(
                    word = input.word,
                    reading = input.reading,
                    pos = input.pos,
                    definitionHtml = inlineDefinitionHtml,
                    freqScore = input.freqScore,
                    pitch = input.pitch,
                    frequencies = input.frequencies,
                    imageFilename = imageFilename,
                    examplesHtml = input.inlineExamplesHtml,
                    sourceLangId = input.sourceLangId,
                    audioFilename = audioFilename,
                    audioCredit = audioCredit,
                )
            },
        )
    } finally {
        // Only delete an ephemeral TTS temp file; a cached Commons clip stays
        // in the audio cache for reuse.
        if (wordResolved?.ephemeral != false) audioFile?.delete()
        AnkiScreenshotPin.release(ctx, pinnedScreenshotPath)
    }
    return result.foldInLocalAudioMisses(
        sentenceMissing = input.includeWordAudio && audioFile == null,
        wordAudioMissing = false,
    )
}

/**
 * Merges local synthesis failures into the dispatcher's
 * [AnkiSendResult.Success.audioDropped] / [wordAudioDropped] flags so
 * callers see a unified "requested-but-missing" signal regardless of
 * whether synth or upload failed. Non-Success results pass through.
 */
private fun AnkiSendResult.foldInLocalAudioMisses(
    sentenceMissing: Boolean,
    wordAudioMissing: Boolean,
): AnkiSendResult = when (this) {
    is AnkiSendResult.Success -> copy(
        audioDropped = audioDropped || sentenceMissing,
        wordAudioDropped = wordAudioDropped || wordAudioMissing,
    )
    else -> this
}

/**
 * Fragment-flavored wrapper that delegates to [Context.sendSentenceCard]
 * and opens the field-mapping dialog when the dispatcher returns
 * [AnkiSendResult.NeedsMapping]. Sheet callers use this so a user
 * with an unmapped custom card type can still configure it without
 * leaving the review sheet — exactly the UX the
 * [Fragment.dispatchSendToAnki] wrapper preserves at the dispatcher
 * layer.
 *
 * Overlay-context callers (PR 2's paths C and D) keep calling the
 * Context version directly and handle [NeedsMapping] their own way
 * (re-launching the review activity so the sheet's dialog is
 * reachable).
 */
suspend fun Fragment.sendSentenceCard(
    input: SentenceSendInput,
    deckId: Long,
): AnkiSendResult {
    val result = requireContext().sendSentenceCard(input, deckId)
    if (result is AnkiSendResult.NeedsMapping) {
        showAnkiCardTypeMappingDialog(result.model, CardMode.SENTENCE) { _, _ -> }
    }
    return result
}

/**
 * Fragment-flavored wrapper around [Context.sendWordCard] — see
 * [Fragment.sendSentenceCard] for rationale.
 */
suspend fun Fragment.sendWordCard(
    input: WordSendInput,
    deckId: Long,
): AnkiSendResult {
    val result = requireContext().sendWordCard(input, deckId)
    if (result is AnkiSendResult.NeedsMapping) {
        showAnkiCardTypeMappingDialog(result.model, CardMode.WORD) { _, _ -> }
    }
    return result
}

/** Reconstitutes a [SentenceAnkiContentView.CardData] from the
 *  pipeline input so the structured builder
 *  ([AnkiCardOutputBuilder.forSentence]) — which still takes the
 *  CardData type — keeps working unchanged. */
private fun SentenceSendInput.toCardData(): SentenceAnkiContentView.CardData =
    SentenceAnkiContentView.CardData(
        source = original,
        target = translation,
        words = words,
        selectedWords = selectedWords,
        screenshotPath = screenshotPath,
        sourceLangId = sourceLangId,
        targetWordAudioWords = targetWordAudioWords,
    )
