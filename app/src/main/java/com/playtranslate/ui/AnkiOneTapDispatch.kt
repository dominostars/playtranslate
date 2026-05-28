package com.playtranslate.ui

import android.content.Context
import com.playtranslate.CaptureService
import com.playtranslate.Prefs
import com.playtranslate.language.SourceLangId

/**
 * "Headless" one-tap card-creation helpers — entry points used when the
 * `ankiOneTapEnabled` toggle is on, so the user can add a card without
 * passing through the review sheet. They layer **preloading awaits**
 * on top of [Context.sendSentenceCard] / [Context.sendWordCard]:
 * either reuse the data the caller hands them, or await the same
 * [LastSentenceCache] entries the review sheet would have observed.
 *
 * `oneTapSendWord` deliberately takes no sentence context: today's
 * word-card path discards sentence unless the user manually flips to
 * sentence mode inside the sheet, and `AnkiCardOutputBuilder.forWord`
 * hardcodes the SENTENCE / SENTENCE_TRANSLATION fields to "" anyway.
 * One-tap honors that — if the user wants sentence context, they
 * long-press to edit.
 *
 * These helpers do NOT show progress indicators, dismiss UI, or open
 * the mapping dialog — surface UX stays with the call site. Callers
 * launch in their own coroutine scope; cancelling the scope cancels
 * the pipeline cleanly.
 */

/**
 * Sentence one-tap. Awaits any in-flight translation / word-lookup
 * the caller didn't already have, then sends the card with the
 * user's saved audio prefs.
 *
 * Defaults mirror the review sheet exactly:
 *  - With a [targetWord] in the resolved lookup, that one word is
 *    the selected target and (if [Prefs.ankiWordAudioEnabled] is on)
 *    gets per-target-word audio. The lens / popup paths use this.
 *  - Without a [targetWord], NO words are selected — the card has
 *    the sentence + translation but no bolded target, matching what
 *    a freshly-opened sheet looks like on the result-screen flow
 *    before the user picks targets manually. No per-word audio is
 *    synthesized either.
 *
 * @param translation   pre-resolved sentence translation, or null to
 *   await via [LastSentenceCache.awaitOrStartTranslation]
 * @param wordsPayload  pre-resolved words + surface forms,
 *   snapshotted atomically (e.g. from a single
 *   [TranslationResultViewModel.WordLookupsState.Settled] read), or
 *   null to await via [LastSentenceCache.awaitOrStartWordLookups].
 *   The pair-shape matters: reading
 *   [LastSentenceCache.surfaceForms] separately races against
 *   live-mode rotating the cache between sentences, so callers
 *   MUST NOT split the read across two field accesses.
 * @param targetWord    when non-null and present in the lookup, the
 *   sole selected target on the card (drag-lens / word-popup)
 */
suspend fun Context.oneTapSendSentence(
    original: String,
    translation: String?,
    wordsPayload: LastSentenceCache.WordsPayload?,
    screenshotPath: String?,
    sourceLangId: SourceLangId,
    targetWord: String? = null,
): AnkiSendResult {
    val ctx = this
    val prefs = Prefs(ctx)

    val resolvedTranslation: String = translation ?: run {
        // Match the sheet's drag-flow translation builder
        // (WordAnkiReviewSheet.launchTranslationFill): use the
        // running CaptureService's on-demand translator. If the
        // service isn't alive we have no way to translate, so fall
        // back to the empty string — the card will land without a
        // translation field rather than failing the send.
        val outcome = LastSentenceCache.awaitOrStartTranslation(original) { text ->
            val svc = CaptureService.instance ?: error("CaptureService unavailable")
            val gt = svc.translateOnce(text)
            LastSentenceCache.TranslationOutcome(gt.text, gt.backendDisplayName)
        }
        outcome?.text.orEmpty()
    }

    val resolvedWords: Map<String, Triple<String, String, Int>>
    val resolvedSurfaces: Map<String, String>
    if (wordsPayload != null && wordsPayload.results.isNotEmpty()) {
        // Use the caller's atomic snapshot — words and surfaces are
        // guaranteed to be from the same lookup pass.
        resolvedWords = wordsPayload.results
        resolvedSurfaces = wordsPayload.surfaces
    } else {
        val payload = LastSentenceCache.awaitOrStartWordLookups(ctx, original)
        resolvedWords = payload.results
        resolvedSurfaces = payload.surfaces
    }
    val wordEntries: List<SentenceAnkiHtmlBuilder.WordEntry> =
        resolvedWords.map { (w, triple) ->
            SentenceAnkiHtmlBuilder.WordEntry(
                w,
                triple.first,
                triple.second,
                triple.third,
                surfaceForm = resolvedSurfaces[w] ?: "",
            )
        }

    // Match the sheet's defaults exactly:
    //  - With a target word (lens / popup path), select only that
    //    word. SentenceAnkiContentFragment.kt:228-231 does the same.
    //  - Without a target word (translation-result path), start with
    //    NO words selected. The sheet leaves selectedWords empty and
    //    relies on the user toggling targets via the per-word rows;
    //    one-tap honors that by sending a target-free sentence card.
    //    AnkiCardOutputBuilder.forSentence handles the no-highlight
    //    case by falling EXPRESSION back to the sentence text.
    val selectedWordsSet: Set<String> =
        if (targetWord != null && wordEntries.any { it.word == targetWord })
            setOf(targetWord)
        else
            emptySet()
    // Per-target-word audio: the sheet seeds each target's audio
    // toggle from prefs.ankiWordAudioEnabled (SentenceAnkiContentFragment.kt:509-510).
    // Mirror that — every selected target gets word audio when the
    // pref is on. With selectedWords empty (path A), this is empty
    // too; no extra TTS synthesis.
    val targetWordAudio: Set<String> =
        if (prefs.ankiWordAudioEnabled) selectedWordsSet else emptySet()
    val input = SentenceSendInput(
        original = original,
        translation = resolvedTranslation,
        words = wordEntries,
        selectedWords = selectedWordsSet,
        sourceLangId = sourceLangId,
        screenshotPath = screenshotPath,
        includeSentenceAudio = prefs.ankiSentenceAudioEnabled,
        // Voice override null = engine default for the user's saved
        // voice preference. The sheet's CardData seeds these from prefs
        // and lets the user tweak per-cell; one-tap takes the default.
        sentenceVoice = null,
        targetWordAudioWords = targetWordAudio,
        wordAudioVoices = emptyMap(),
        examplesHtml = "",
    )
    return ctx.sendSentenceCard(input, deckId = prefs.ankiDeckId)
}

/**
 * Word one-tap. No preloading (the caller already has the resolved
 * dictionary fields — that's the precondition for the Anki button
 * being tappable). Uses the flat fallback definition for both legacy
 * and structured paths via [WordAnkiHtmlBuilder.wrapFlatDefinitionHtml].
 * If the user wants the richer per-sense definition the sheet renders,
 * they long-press to edit.
 */
suspend fun Context.oneTapSendWord(
    word: String,
    reading: String,
    pos: String,
    fallbackDefinition: String,
    freqScore: Int,
    screenshotPath: String?,
    sourceLangId: SourceLangId,
): AnkiSendResult {
    val ctx = this
    val prefs = Prefs(ctx)
    val flatDefinitionHtml = WordAnkiHtmlBuilder.wrapFlatDefinitionHtml(fallbackDefinition)
    val input = WordSendInput(
        word = word,
        reading = reading,
        pos = pos,
        freqScore = freqScore,
        sourceLangId = sourceLangId,
        screenshotPath = screenshotPath,
        includeWordAudio = prefs.ankiWordAudioEnabled,
        wordVoice = null,
        classDefinitionHtml = flatDefinitionHtml,
        inlineDefinitionHtml = flatDefinitionHtml,
        inlineExamplesHtml = "",
    )
    return ctx.sendWordCard(input, deckId = prefs.ankiDeckId)
}
