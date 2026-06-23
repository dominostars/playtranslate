package com.playtranslate.ui

import android.content.Context
import androidx.fragment.app.Fragment
import com.playtranslate.audio.Attribution
import com.playtranslate.audio.AudioRequest
import com.playtranslate.audio.AudioSelection
import com.playtranslate.audio.AudioSelections
import com.playtranslate.audio.ResolvedAudio
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.model.FrequencyTag
import com.playtranslate.tts.TtsEngine
import com.playtranslate.tts.ttsTextForWord
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

/** Inputs needed to send a sentence card. Mirrors the fields of
 *  [SentenceAnkiContentFragment.CardData] plus the audio-toggle state
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
    /** Voice override for the sentence audio. null = engine default. */
    val sentenceVoice: String? = null,
    /** Words whose per-target audio is enabled. Empty in one-tap (the
     *  sheet's per-word toggle isn't surfaced). */
    val targetWordAudioWords: Set<String> = emptySet(),
    /** Voice overrides for per-target-word audio. null entries =
     *  engine default. */
    val wordAudioVoices: Map<String, String?> = emptyMap(),
    /** Multi-source audio selection for the sentence cell. null = the legacy
     *  TTS path via [sentenceVoice] (one-tap callers). */
    val sentenceSelection: AudioSelection? = null,
    /** Per-target-word audio selections. Missing entry = legacy TTS via
     *  [wordAudioVoices]. */
    val wordSelections: Map<String, AudioSelection> = emptyMap(),
    /** Pre-built Tatoeba "more examples" block for the structured
     *  path's EXAMPLE_SENTENCES content source. The word-tab's
     *  sentence flow has examples; the result-screen sentence flow
     *  passes empty. */
    val examplesHtml: String = "",
)

/** Inputs needed to send a word card. The sheet pre-renders rich,
 *  curation-aware definition HTML in both [classDefinitionHtml] (for
 *  the legacy v004 back) and [inlineDefinitionHtml] (for the
 *  structured path). One-tap callers pass a flat fallback in both via
 *  [WordAnkiHtmlBuilder.wrapFlatDefinitionHtml]. */
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
    /** Voice override for the word audio. null = engine default. Used only by
     *  the legacy path when [wordSelection] is null. */
    val wordVoice: String? = null,
    /** Multi-source audio selection for the headword (Commons-first → TTS).
     *  null = the legacy TTS path via [wordVoice] (one-tap callers). */
    val wordSelection: AudioSelection? = null,
    /** Definition body for the legacy v004 back. Built with
     *  [classStyler] in the sheet (the back's CSS block supplies the
     *  gl-* classes); one-tap passes the inline-styled flat fallback
     *  (works either way — class refs without matching CSS just don't
     *  bind, which is fine for the flat case). */
    val classDefinitionHtml: String,
    /** Definition body for the structured (mapped) path's DEFINITION
     *  content source. Built with [inlineStyler] in the sheet; one-tap
     *  passes the same flat-fallback HTML. */
    val inlineDefinitionHtml: String,
    /** Tatoeba "more examples" block for the structured path's
     *  EXAMPLE_SENTENCES content source. Empty for one-tap (no
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
    // Sentence audio: a multi-source selection (sheet) resolves Commons-first
    // → TTS; the legacy one-tap path (null selection) synthesizes TTS directly.
    val sentenceResolved: ResolvedAudio? = if (input.includeSentenceAudio) {
        val sel = input.sentenceSelection
        if (sel != null) {
            AudioSelections.toFile(ctx, sel, AudioRequest.sentence(input.original, input.sourceLangId))
        } else {
            // Speak the kana pronunciation so the engine doesn't re-guess compound
            // readings (初夏 → はつか) in the card's sentence audio; identity for non-JA.
            val spokenOriginal = SourceLanguageEngines.get(ctx, input.sourceLangId)
                .spokenForm(input.original)
            TtsEngine.synthesizeToFile(
                ctx, spokenOriginal, input.sourceLangId,
                voiceNameOverride = input.sentenceVoice,
            )?.let { ResolvedAudio(it, null) }
        }
    } else null
    val audioFile: File? = sentenceResolved?.file
    // Per-target-word audio. Words whose synth/fetch returns null are skipped —
    // the rest of the card still lands. Keyed by the same WordEntry.word the
    // media files are stored under.
    val readingByWord = input.words.associate { it.word to it.reading }
    val wordResolved: Map<String, ResolvedAudio> = buildMap {
        for (word in input.targetWordAudioWords) {
            val sel = input.wordSelections[word]
            val resolved = if (sel != null) {
                AudioSelections.toFile(
                    ctx, sel,
                    AudioRequest.word(word, readingByWord[word]?.ifBlank { null }, input.sourceLangId),
                )
            } else {
                TtsEngine.synthesizeToFile(
                    ctx,
                    ttsTextForWord(word, readingByWord[word]?.ifBlank { null }, input.sourceLangId),
                    input.sourceLangId,
                    voiceNameOverride = input.wordAudioVoices[word],
                )?.let { ResolvedAudio(it, null) }
            }
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
        val cardData = input.toCardData()
        ctx.dispatchSendToAnki(
            deckId = deckId,
            mode = CardMode.SENTENCE,
            screenshotPath = input.screenshotPath,
            audioPath = audioFile?.absolutePath,
            wordAudioPaths = wordAudioFiles.mapValues { it.value.absolutePath },
            legacyFront = {
                SentenceAnkiHtmlBuilder.buildFrontHtml(
                    input.original, input.words, input.selectedWords, input.sourceLangId,
                )
            },
            legacyBack = { imageFilename, audioFilename, wordAudioFilenames ->
                SentenceAnkiHtmlBuilder.buildBackHtml(
                    input.original, input.translation, input.words,
                    imageFilename, input.selectedWords, input.sourceLangId,
                    audioFilename = audioFilename,
                    wordAudioFilenames = wordAudioFilenames,
                    audioCredit = audioCredit,
                )
            },
            structured = { imageFilename, audioFilename, wordAudioFilenames ->
                AnkiCardOutputBuilder.forSentence(
                    cardData = cardData,
                    imageFilename = imageFilename,
                    examplesHtml = input.examplesHtml,
                    audioFilename = audioFilename,
                    wordAudioFilenames = wordAudioFilenames,
                    sentenceAudioCredit = sentenceCredit,
                    wordAudioCredit = wordCredit,
                )
            },
        )
    } finally {
        // Only delete ephemeral TTS temp files; cached Commons clips stay in
        // the audio cache for reuse.
        if (sentenceResolved?.ephemeral != false) audioFile?.delete()
        wordResolved.values.forEach { if (it.ephemeral) it.file.delete() }
    }
    // The dispatcher only knows about UPLOAD failures (audioPath was
    // non-null but addMediaFromFile dropped it). Synthesis failures
    // (TtsEngine.synthesizeToFile returned null) never reach it because
    // we pass null audioPath in that case. Fold them in here so
    // callers can read a single "audio requested but missing" flag.
    return result.foldInLocalAudioMisses(
        sentenceMissing = input.includeSentenceAudio && audioFile == null,
        wordAudioMissing = input.targetWordAudioWords.isNotEmpty() &&
            wordAudioFiles.size < input.targetWordAudioWords.size,
    )
}

/**
 * Word-card send pipeline. Synthesizes word audio, dispatches the
 * card, and cleans up the temp WAV in a finally.
 */
suspend fun Context.sendWordCard(
    input: WordSendInput,
    deckId: Long,
): AnkiSendResult {
    val ctx = this
    // Headword audio: a multi-source selection (sheet) resolves Commons-first
    // → TTS; the legacy one-tap path (null selection) synthesizes TTS directly.
    val wordResolved: ResolvedAudio? = if (input.includeWordAudio) {
        val sel = input.wordSelection
        if (sel != null) {
            AudioSelections.toFile(
                ctx, sel,
                AudioRequest.word(input.word, input.reading.ifBlank { null }, input.sourceLangId),
            )
        } else {
            TtsEngine.synthesizeToFile(
                ctx,
                ttsTextForWord(input.word, input.reading.ifBlank { null }, input.sourceLangId),
                input.sourceLangId,
                voiceNameOverride = input.wordVoice,
            )?.let { ResolvedAudio(it, null) }
        }
    } else null
    val audioFile: File? = wordResolved?.file
    // CC credit for a Commons clip, co-located with the word audio field so the
    // attribution travels with redistributed cards. Null for plain TTS audio.
    val audioCredit: String? =
        wordResolved?.attribution?.let { Attribution.creditBlock(listOf(it)) }
    val result = try {
        ctx.dispatchSendToAnki(
            deckId = deckId,
            mode = CardMode.WORD,
            screenshotPath = input.screenshotPath,
            audioPath = audioFile?.absolutePath,
            legacyFront = { WordAnkiHtmlBuilder.buildFrontHtml(input.word) },
            legacyBack = { imageFilename, audioFilename, _ ->
                // Word cards have no per-target-word audio — drop the
                // third arg.
                WordAnkiHtmlBuilder.buildBackHtml(
                    word = input.word,
                    reading = input.reading,
                    pos = input.pos,
                    freqScore = input.freqScore,
                    pitch = input.pitch,
                    frequencies = input.frequencies,
                    imageFilename = imageFilename,
                    audioFilename = audioFilename,
                    audioCredit = audioCredit,
                    definitionHtml = input.classDefinitionHtml,
                )
            },
            structured = { imageFilename, audioFilename, _ ->
                AnkiCardOutputBuilder.forWord(
                    word = input.word,
                    reading = input.reading,
                    pos = input.pos,
                    definitionHtml = input.inlineDefinitionHtml,
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

/** Reconstitutes a [SentenceAnkiContentFragment.CardData] from the
 *  pipeline input so the structured builder
 *  ([AnkiCardOutputBuilder.forSentence]) — which still takes the
 *  CardData type — keeps working unchanged. */
private fun SentenceSendInput.toCardData(): SentenceAnkiContentFragment.CardData =
    SentenceAnkiContentFragment.CardData(
        source = original,
        target = translation,
        words = words,
        selectedWords = selectedWords,
        screenshotPath = screenshotPath,
        sourceLangId = sourceLangId,
        targetWordAudioWords = targetWordAudioWords,
        sentenceVoice = sentenceVoice,
        wordAudioVoices = wordAudioVoices,
    )
