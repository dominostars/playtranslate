package com.playtranslate.ui

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import com.playtranslate.CaptureService
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.FrequencyTag
import com.playtranslate.model.PendingTranslation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * "Headless" card-creation helpers — entry points used when the user
 * long-presses any Add-to-Anki button, so a card lands without
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
 * the mapping dialog — surface UX stays with the call site. Fragment
 * callers go through [launchOneTapSend]; overlay/service callers launch
 * on [ankiOneTapSendScope] directly and degrade their result UX via
 * [oneTapResultToast] when their surface is gone.
 */

/**
 * Process-lived scope for one-tap sends. A long-press send runs detached
 * from the surface that launched it: the card (and its result toast) must
 * land even when that overlay / sheet / fragment is dismissed mid-send —
 * a lifecycle scope would silently cancel the card. One shared scope so
 * no call site re-derives the lifetime decision.
 */
val ankiOneTapSendScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/**
 * Runs [send] on [ankiOneTapSendScope] and presents its result on this
 * fragment — with the send's lifetime and the presentation's lifetime
 * deliberately decoupled, because they have opposite requirements: the
 * card must OUTLIVE the fragment, while the result UI must NOT.
 *
 * Rather than guarding the presentation with lifecycle flags (`isAdded`
 * proves only attachment — not that the view exists, nor that dialogs
 * may be shown; each result arm needs a different check, which is how
 * this went wrong the first time), the safety rules are encoded
 * structurally, so there is nothing to check and nothing to race:
 *
 *  - [presentResult] runs in the VIEW-lifecycle scope, deferred by
 *    [withStarted] until the view is at least STARTED. View death
 *    cancels it before it can touch anything; STARTED implies the
 *    fragment is attached and — on minSdk 29, where onStop precedes
 *    onSaveInstanceState — that FragmentManager commits (the
 *    NeedsMapping dialog) are legal. A send that finishes while the
 *    app is backgrounded simply presents when the user returns.
 *  - If the presentation coroutine is CANCELLED before it ran (view
 *    destroyed mid-send, or while parked in [withStarted]), its
 *    completion cause triggers the degraded path: [oneTapResultToast]
 *    on [appCtx] once the send lands. The cancellation cause is the
 *    handoff signal — exactly one path presents, with no shared flag.
 *
 * [resultOf] and [modeOf] extract the [AnkiSendResult] and the
 * [CardMode] actually sent from [send]'s payload for the degraded
 * toast (identity / a static mode for sentence sends; `first` /
 * `second` for the funnel's result+mode pair).
 *
 * [send] is exception-free by design — the whole pipeline models
 * failures as [AnkiSendResult] values (the cache helpers, audio
 * resolution, and AnkiManager all contain their own throws). A bug
 * that escaped anyway would crash the app from a detached coroutine,
 * disconnected from the gesture and possibly after the UI is gone —
 * so the seam contains it: loud log plus a failure toast, exactly
 * once, through whichever presentation path is live.
 */
fun <T> Fragment.launchOneTapSend(
    appCtx: Context,
    send: suspend () -> T,
    resultOf: (T) -> AnkiSendResult,
    modeOf: (T) -> CardMode,
    presentResult: (T) -> Unit,
) {
    val sendJob = ankiOneTapSendScope.async {
        try {
            Result.success(send())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(ONE_TAP_TAG, "one-tap send escaped an exception", e)
            Result.failure(e)
        }
    }
    val viewLifecycle = viewLifecycleOwner
    val presentation = viewLifecycle.lifecycleScope.launch {
        val payload = sendJob.await().getOrElse {
            oneTapSendFailedToast(appCtx)
            return@launch
        }
        viewLifecycle.lifecycle.withStarted { presentResult(payload) }
    }
    presentation.invokeOnCompletion { cause ->
        if (cause is CancellationException) {
            ankiOneTapSendScope.launch {
                sendJob.await().fold(
                    onSuccess = { oneTapResultToast(appCtx, resultOf(it), modeOf(it)) },
                    onFailure = { oneTapSendFailedToast(appCtx) },
                )
            }
        }
    }
}

/** Failure surface for a send whose exception was contained at the
 *  [launchOneTapSend] boundary: same message the dispatcher uses for a
 *  rejected card, on the app context so it works from any state. */
private fun oneTapSendFailedToast(appCtx: Context) {
    Toast.makeText(appCtx, R.string.anki_send_failed_message, Toast.LENGTH_LONG).show()
}

/** Mode-named success message for one-tap sends. One-tap applies the
 *  remembered card mode with no UI showing it, so the toast names what
 *  was actually created — a silently-applied WORD default is visible
 *  immediately instead of discovered later in AnkiDroid. */
fun ankiAddedSuccessRes(mode: CardMode): Int = when (mode) {
    CardMode.WORD     -> R.string.anki_added_word_success
    CardMode.SENTENCE -> R.string.anki_added_sentence_success
}

private const val ONE_TAP_TAG = "AnkiOneTap"

/**
 * Result surfacing for a one-tap send whose launching UI is gone by the
 * time the send finishes: success / failure toasts on the app context.
 * [AnkiSendResult.NeedsMapping] adds nothing here — the dispatcher already
 * toasted the explanation, and the mapping dialog needs UI that no longer
 * exists.
 */
fun oneTapResultToast(appCtx: Context, result: AnkiSendResult, mode: CardMode) {
    when (result) {
        is AnkiSendResult.Success -> Toast.makeText(
            appCtx,
            result.mediaShortfallRes() ?: ankiAddedSuccessRes(mode),
            Toast.LENGTH_SHORT,
        ).show()
        is AnkiSendResult.Failed -> Toast.makeText(
            appCtx,
            result.message ?: appCtx.getString(result.messageRes),
            Toast.LENGTH_LONG,
        ).show()
        is AnkiSendResult.NeedsMapping -> Unit
    }
}

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
 * @param translation   pre-resolved sentence translation; null OR BLANK
 *   awaits via [resolveAnkiTranslation] — blank is the deferred results'
 *   "never ran" sentinel, and callers reading `translatedText` straight
 *   off a result (the word-detail context chain) pass it through
 *   un-normalized, so the sink must treat both spellings as unresolved
 * @param pendingTranslation the result's deferred-translation payload, when
 *   the launching surface had one — routes the lazy translate through the
 *   deferred completion (see [ankiTranslationFor]) so the capture's History
 *   rows fill instead of a bare translateOnce leaving them null
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
    pendingTranslation: PendingTranslation? = null,
): AnkiSendResult {
    val ctx = this
    val prefs = Prefs(ctx)

    // Blank-aware, not just null-aware: deferred results carry "" as their
    // translatedText, and not every caller normalizes it to null before
    // passing it here. A blank slipping through as "the translation" would
    // send an empty card AND skip the deferred completion the pending
    // exists for.
    val resolvedTranslation: String = translation?.takeIf { it.isNotBlank() } ?: run {
        // If the service isn't alive we have no way to translate, so fall
        // back to the empty string — the card will land without a
        // translation field rather than failing the send (the null outcome
        // is resolveAnkiTranslation's contained-failure signal).
        val outcome = resolveAnkiTranslation(pendingTranslation, original)
        outcome?.text.orEmpty()
    }

    val resolvedWords: Map<String, Triple<String, String, Int>>
    val resolvedSurfaces: Map<String, String>
    val resolvedEnrichment: Map<String, WordEnrichment>
    if (wordsPayload != null && wordsPayload.isTrustedFor(original)) {
        // Use the caller's atomic snapshot — words, surfaces, and
        // enrichment are guaranteed to be from the same lookup pass, and
        // isTrustedFor proved (via the carried annotation) that the pass
        // ran against the CURRENT import generation for THIS sentence.
        resolvedWords = wordsPayload.results
        resolvedSurfaces = wordsPayload.surfaces
        resolvedEnrichment = wordsPayload.enrichment
    } else {
        // No supplied snapshot, or one that can't prove freshness (a
        // Yomitan mutation after the rows settled, a hand-built payload
        // with no annotation): re-derive through the cache, whose own
        // generation gate refreshes stale words — an instant hit when the
        // cache is fresh.
        val payload = LastSentenceCache.awaitOrStartWordLookups(ctx, original)
        resolvedWords = payload.results
        resolvedSurfaces = payload.surfaces
        resolvedEnrichment = payload.enrichment
    }
    val wordEntries: List<SentenceAnkiHtmlBuilder.WordEntry> =
        resolvedWords.map { (w, triple) ->
            SentenceAnkiHtmlBuilder.WordEntry(
                w,
                triple.first,
                triple.second,
                triple.third,
                surfaceForm = resolvedSurfaces[w] ?: "",
                pitch = resolvedEnrichment[w]?.pitch.orEmpty(),
                frequencies = resolvedEnrichment[w]?.frequencies.orEmpty(),
                isCommon = resolvedEnrichment[w]?.isCommon ?: false,
                senses = resolvedEnrichment[w]?.senses.orEmpty(),
            )
        }

    // Match the sheet's defaults exactly:
    //  - With a target word (lens / popup path), select only that
    //    word. SentenceAnkiContentView.handleAudioPick does the same.
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
    // toggle from prefs.ankiWordAudioEnabled (SentenceAnkiContentView.rebuildWordRows).
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
        // Audio selections stay at their Auto defaults — the registry's
        // Commons-first → TTS resolution with the user's saved voice,
        // exactly what a freshly-opened sheet sends. (One-tap used to
        // bypass the registry and synthesize with the engine-default
        // voice, silently ignoring the picked voice.)
        targetWordAudioWords = targetWordAudio,
        examplesHtml = "",
    )
    return ctx.sendSentenceCard(input, deckId = prefs.ankiDeckId)
}

/**
 * Word one-tap. No preloading (the caller already has the resolved
 * dictionary fields — that's the precondition for the Anki button
 * being tappable). Uses the flat fallback definition for both default
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
    pitch: List<Int>,
    frequencies: List<FrequencyTag>,
    screenshotPath: String?,
    sourceLangId: SourceLangId,
): AnkiSendResult {
    val ctx = this
    val prefs = Prefs(ctx)
    // Two stylers, one shape: the default model's CSS defines the gl-*
    // classes, the structured path inlines them.
    val definitionsHeader = ctx.getString(R.string.anki_group_definitions)
    val input = WordSendInput(
        word = word,
        reading = reading,
        pos = pos,
        freqScore = freqScore,
        pitch = pitch,
        frequencies = frequencies,
        sourceLangId = sourceLangId,
        screenshotPath = screenshotPath,
        includeWordAudio = prefs.ankiWordAudioEnabled,
        // wordSelection stays Auto — saved-voice TTS (Commons-first when
        // enabled), matching the sheet's default cell.
        defaultDefinitionHtml = WordAnkiHtmlBuilder.wrapFlatDefinitionHtml(
            fallbackDefinition, classStyler, definitionsHeader),
        inlineDefinitionHtml = WordAnkiHtmlBuilder.wrapFlatDefinitionHtml(
            fallbackDefinition, inlineStyler, definitionsHeader),
        inlineExamplesHtml = "",
    )
    return ctx.sendWordCard(input, deckId = prefs.ankiDeckId)
}

/**
 * One-tap funnel: routes to a word or sentence card so the long-press call
 * sites don't each re-decide. Absent / just-the-word sentence
 * ([sentenceIsJustTheWord]) forces a word card; when a real surrounding
 * sentence exists, the remembered default ([Prefs.ankiPreferredCardMode] —
 * written by the review sheet's Sentence/Word toggle) picks the shape, so
 * a long-press creates the same card the sheet would open on. A word-routed
 * send discards the sentence context by design (see the file doc above) —
 * including any deferred [pendingTranslation], which then completes on its
 * usual reveal trigger instead of this send.
 *
 * Returns the send result plus the [CardMode] actually used — callers
 * surface mode-specific recovery (the review-sheet NeedsMapping dialog)
 * and the mode-named success toast from it.
 */
suspend fun Context.oneTapSend(
    word: String,
    reading: String,
    pos: String,
    fallbackDefinition: String,
    freqScore: Int,
    pitch: List<Int>,
    frequencies: List<FrequencyTag>,
    sentenceOriginal: String?,
    sentenceTranslation: String?,
    wordsPayload: LastSentenceCache.WordsPayload?,
    screenshotPath: String?,
    sourceLangId: SourceLangId,
    pendingTranslation: PendingTranslation? = null,
): Pair<AnkiSendResult, CardMode> =
    if (sentenceIsJustTheWord(sentenceOriginal, word) ||
        Prefs(this).ankiPreferredCardMode == CardMode.WORD) {
        oneTapSendWord(
            word = word,
            reading = reading,
            pos = pos,
            fallbackDefinition = fallbackDefinition,
            freqScore = freqScore,
            pitch = pitch,
            frequencies = frequencies,
            screenshotPath = screenshotPath,
            sourceLangId = sourceLangId,
        ) to CardMode.WORD
    } else {
        oneTapSendSentence(
            original = sentenceOriginal!!,
            translation = sentenceTranslation,
            wordsPayload = wordsPayload,
            screenshotPath = screenshotPath,
            sourceLangId = sourceLangId,
            targetWord = word,
            // Word routed to a sentence card: the sentence half is the
            // deferred result's — its pending must ride, or the sentence
            // branch would translate without completing (null rows).
            pendingTranslation = pendingTranslation,
        ) to CardMode.SENTENCE
    }

/**
 * The Anki flows' lazy sentence translation. A deferred CAPTURE result
 * routes through [CaptureService.completeDeferredTranslation] — one backend
 * batch that also fills the capture's null History rows and feeds the
 * context ring under its capture-time eligibility, idempotently — instead
 * of a bare translateOnce that would leave those rows null forever (the
 * launching surface may already be dismissed, so no funnel of its own will
 * run). Everything else (no pending; a sentence-shape pending, whose
 * deliberate-row attach rules live in the launching activity's funnel)
 * keeps the plain on-demand translate.
 *
 * Throws when no service is alive or the completion produced nothing —
 * [resolveAnkiTranslation] contains the throw as a null outcome (the
 * sheet's "couldn't translate" hint / the one-tap's empty field).
 */
internal suspend fun ankiTranslationFor(
    pending: PendingTranslation?,
    text: String,
): LastSentenceCache.TranslationOutcome {
    val svc = CaptureService.instance ?: error("CaptureService unavailable")
    if (pending != null && pending.isCapture) {
        val perGroup = svc.completeDeferredTranslation(pending)
        val joined = perGroup.joinToString("\n\n") { it.text }
        if (joined.isBlank()) error("deferred completion produced no translation")
        return LastSentenceCache.TranslationOutcome(
            joined, perGroup.mapNotNull { it.backendDisplayName }.firstOrNull(),
        )
    }
    val gt = svc.translateOnce(text)
    return LastSentenceCache.TranslationOutcome(gt.text, gt.backendDisplayName)
}

/**
 * Resolve the Anki flows' lazy sentence translation. A capture pending
 * NEVER goes through the sentence-text cache gate: [LastSentenceCache] is
 * keyed by text alone, so a warm entry written by a non-attaching path (the
 * word sheet's fill, an earlier same-text lookup) would return before the
 * lambda runs — skipping [CaptureService.completeDeferredTranslation] and
 * leaving the capture's null History rows exactly as this routing exists to
 * fill. The completion is idempotent and cache-served at the group layer,
 * so bypassing the gate costs at most one cache-hit batch. Everything else
 * keeps the cache's await-or-start coalescing. Null = translation isn't
 * possible right now (no service, completion produced nothing).
 *
 * CALLER CONTRACT: pass [pending] only alongside its own result's full
 * original text — a capture completion translates the pending's group
 * texts, so pairing it with any other [text] (an edited sentence, a
 * different line) would return content that doesn't match.
 */
internal suspend fun resolveAnkiTranslation(
    pending: PendingTranslation?,
    text: String,
): LastSentenceCache.TranslationOutcome? {
    return if (pending != null && pending.isCapture) {
        try {
            ankiTranslationFor(pending, text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("AnkiOneTapDispatch", "deferred Anki translation failed: ${e.message}")
            null
        }
    } else {
        LastSentenceCache.awaitOrStartTranslation(text) { ankiTranslationFor(pending, it) }
    }
}
