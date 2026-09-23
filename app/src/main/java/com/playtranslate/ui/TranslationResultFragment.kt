package com.playtranslate.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.playtranslate.overlay.OverlayHost
import android.view.WindowManager
import android.graphics.Point
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.playtranslate.AnkiManager
import com.playtranslate.CaptureService
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.model.OcrProvenance
import com.playtranslate.model.TranslationResult
import com.playtranslate.model.headwordDisplay
import com.playtranslate.model.selectHeadword
import com.playtranslate.language.SourceLangId
import com.playtranslate.ocr.registry.selectionToken
import com.playtranslate.themeColor
import com.playtranslate.overlay.OwnWindows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Shared fragment that displays translation results: original text, translation,
 * word lookups, copy/Anki buttons. Used by both MainActivity and TranslationResultActivity.
 */
class TranslationResultFragment : Fragment() {

    /**
     * Host interface for activities that embed this fragment. Bundles
     * service-binding queries, word-tap routing, ankiPermissionLauncher
     * access, and user-input event handlers into a single contract.
     * The compiler enforces implementation — there's no optional
     * "remember to wire this" var. Pure state actions (Clear → reset
     * to idle status) bypass this interface and call the VM directly,
     * since they don't need host context.
     */
    interface TranslationResultHost {
        fun getCaptureService(): CaptureService?

        /** The bound Ready result carries a deferred translation
         *  ([com.playtranslate.model.TranslationResult.pendingTranslation]) and a
         *  consumer needs it NOW: the section was revealed, a bind landed while
         *  it was visible, or the user asked for the on-screen boxes. The host
         *  runs the translate + History attach on its own scope and lands the
         *  outcome via [TranslationResultViewModel.applyDeferredTranslation].
         *  With no service available right now the host no-ops — the pending
         *  stays set and the next trigger retries. Must tolerate repeat calls
         *  while a completion is already in flight. */
        fun completeDeferredTranslation()
        fun onWordTapped(
            word: String,
            reading: String?,
            screenshotPath: String?,
            sentenceOriginal: String?,
            sentenceTranslation: String?,
            wordResults: Map<String, Triple<String, String, Int>>
        )
        fun onInteraction()
        fun getAnkiPermissionLauncher(): androidx.activity.result.ActivityResultLauncher<String>?

        /** User tapped Edit on the original-text card. The host opens
         *  its edit overlay UI. No-op for hosts without one. */
        fun onEditOriginalRequested()

        /** User picked a different (already-downloaded) OCR tool from the source
         *  OCR picker. The new token is already persisted; the host re-reads the
         *  screen with it — from the current result's cached screenshot, or, when
         *  live mode is running, by forcing a fresh look.
         *  No-op for hosts/results without OCR provenance. */
        fun onReOcrRequested()

        /** Whether an OCR-tool switch would actually be acted on right now —
         *  the gate for offering the gear at all. True when the host has a
         *  pinned frame to re-OCR, OR when a live loop is running that will
         *  read the screen again with the new engine. False = a gear here
         *  would be a dead control, so no surface shows one. */
        fun canReOcr(): Boolean

        /** User tapped a language section header to change the source ([isSource] =
         *  true) or target language. The host opens the language picker (the same
         *  flow as Settings) and ends the current result — the picker dismisses /
         *  clears it, and the user re-captures to see it in the new language. */
        fun onChangeLanguageRequested(isSource: Boolean)

        /** User scrolled the result content. The host can use this to
         *  pause live-mode capture, etc. No-op for hosts without
         *  live-mode behavior. */
        fun onUserScrolled()

        /** Whether the result screen should offer the "Clear" action. The
         *  in-app host shows it (resets the screen to idle); standalone hosts
         *  launched outside the app (single-screen, or backgrounded
         *  dual-screen) hide it — there's no persistent session to clear, the
         *  user just closes the screen. */
        fun showsClearAction(): Boolean

        // ── "Show on screen" (dual-screen) ────────────────────────────────
        // The target header's toggle, mirroring the single-screen capture
        // panel's. Two mutually-exclusive semantics, split by
        // [liveShowOnScreenState]:
        //  - one-shot: paint/hide the current result's boxes over the game
        //    (the three methods below);
        //  - live mode: the toggle IS the hide-overlays-during-auto setting
        //    (inverted), switching the running live mode's flavor in place.

        /** Whether this host can paint one-shot boxes over the game at all
         *  (dual-screen MainActivity). Gates the toggle's visibility alongside
         *  the state's [OnScreenBoxes]. Must depend only on inputs whose
         *  changes reach [refreshShowOnScreen] — a term that flips without a
         *  refresh renders a stale toggle (the service-binding term was
         *  removed for exactly that). */
        fun supportsShowOnScreen(): Boolean

        /** Whether the one-shot boxes are painted over the game RIGHT NOW.
         *  The single source of truth the toggle's selected state renders
         *  from — the fragment holds no mirror flag, so no teardown path can
         *  desync the pill from the window (it derives, the host's
         *  [onScreenBoxesDismissed] pokes are freshness only). */
        fun isResultBoxesShownOnScreen(): Boolean

        /** Paint [boxes] over the game display. The paint can be refused (no
         *  overlay UI, live mode owns the surface) — success or refusal is
         *  read back through [isResultBoxesShownOnScreen], never assumed. */
        fun showResultBoxesOnScreen(boxes: OnScreenBoxes)

        /** Swap the painted boxes in place (skeleton → translated promotion).
         *  No-op when nothing is painted. */
        fun updateResultBoxesOnScreen(boxes: OnScreenBoxes)

        /** Tear the painted boxes down. Idempotent. The host pokes
         *  [onScreenBoxesDismissed] on every window teardown so the pill
         *  refreshes promptly, but correctness never rides on the poke. */
        fun hideResultBoxesOnScreen()

        /** Live-mode semantics for the toggle: non-null exactly when a live
         *  session is running AND the hide-overlays-during-auto setting is
         *  consequential (dual-screen, a single capture display) — the value
         *  is the setting's inverse ("show on screen" = overlays on the
         *  game). Null routes the toggle to the one-shot semantics. */
        fun liveShowOnScreenState(): Boolean?

        /** Flip the hide-overlays-during-auto setting to `!on` and swap the
         *  running live mode's flavor in place. Only called while
         *  [liveShowOnScreenState] is non-null. */
        fun setLiveShowOnScreen(on: Boolean)
    }

    /** The page content — sections, Words card, lens, text-size popover
     *  and the render funnel — shared with the workspace's Sentence page
     *  ([TranslationResultContent]); this fragment is its in-app shell,
     *  supplying what only the Activity side knows through [ContentHost]. */
    private lateinit var content: TranslationResultContent

    /** Activity-scoped source of truth for the result + lookup state.
     *  Activities mutate via VM methods; this fragment observes
     *  [vm.result] and [vm.wordLookups] to render. */
    private val vm: TranslationResultViewModel by activityViewModels()

    /** The current Ready result, or null in any other state. Narrows the VM's
     *  result StateFlow in one place so the call sites don't each hand-cast. */
    private fun currentReady(): TranslationResult? =
        (vm.result.value as? ResultState.Ready)?.result

    /** The settled word-lookup rows, or null while idle/loading. Named
     *  `currentSettledRows` (not `settledRows`) so it doesn't shadow the local
     *  `val settledRows` snapshots the Anki paths take. */
    private fun currentSettledRows(): List<RowState>? =
        (vm.wordLookups.value as? WordLookupsState.Settled)?.rows

    private fun currentSettledAnnotation(): com.playtranslate.language.SentenceAnnotation? =
        (vm.wordLookups.value as? WordLookupsState.Settled)?.annotation

    private val host: TranslationResultHost?
        get() = activity as? TranslationResultHost

    /** Standalone hosts (single-screen, backgrounded dual-screen) suppress the
     *  Clear action — see [TranslationResultHost.showsClearAction]. Defaults to
     *  shown if the host isn't attached yet. */
    private val showsClearAction: Boolean
        get() = host?.showsClearAction() ?: true

    private val prefs: Prefs by lazy { Prefs(requireContext()) }

    // ── "Show on screen" toggle state (dual-screen) ───────────────────────

    /** The paintable boxes carried by the currently-rendered state, or null.
     *  Tracks [ResultState.Translating]/[ResultState.Ready.onScreenBoxes]
     *  through the render funnel — see [syncOnScreenBoxes]. Deliberately the
     *  ONLY show-on-screen state this fragment holds: whether the boxes are
     *  painted is derived from [TranslationResultHost.isResultBoxesShownOnScreen]
     *  at each render, never mirrored. */
    private var currentOnScreenBoxes: OnScreenBoxes? = null

    // ── Fragment lifecycle ─────────────────────────────────────────────────

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_translation_result, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        content = TranslationResultContent(view, requireContext(), prefs, vm, ContentHost(requireActivity()))
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.result.collect { content.render(it) } }
                launch { vm.wordLookups.collect { content.renderWordLookups(it) } }
                // Deck badges on any card added anywhere in the app, and
                // hidden-word changes from anywhere — both fire while dialogs
                // are on top (this fragment stays STARTED beneath them, which
                // an onResume hook would miss), and the store's StateFlow
                // replays on STARTED, which also covers returning from the
                // sheet.
                content.wordRows.attachCollectors(this)
                // Keep the live-mode "show on screen" pill in lockstep with
                // the hide-overlays-during-auto setting, whichever surface
                // writes it (this toggle, or the Settings row on return).
                launch {
                    prefs.observe(Prefs.KEY_HIDE_GAME_OVERLAYS).collect { refreshShowOnScreen() }
                }
            }
        }
    }

    override fun onDestroyView() {
        content.release()
        super.onDestroyView()
    }

    /** Open the "Choose OCR tool" picker for source language [id], highlighting
     *  [appliedToken] (the engine that produced or attempted the current result).
     *  Switching to a downloaded engine re-OCRs via the host; a not-downloaded
     *  engine deep-links to the OCR settings screen to fetch it. Shared by the
     *  source attribution row and the "no text detected" status gear. */
    private fun showOcrPicker(id: SourceLangId, appliedToken: String) {
        val ctx = context ?: return
        OcrPicker.populate(
            OverlayAlert.Builder(requireActivity()),
            ctx,
            id,
            appliedToken,
            onReOcr = { host?.onReOcrRequested() },
            onDownload = { backend ->
                startActivity(CaptureOverlaySettingsActivity.downloadIntent(ctx, id, backend.selectionToken))
            },
        ).show()
    }

    /** True iff the activity is currently showing a translation result
     *  (vs status/error/translating). View-state helper for the host. */
    val isShowingResults: Boolean
        get() = view != null && content.isShowingResults

    // ── "Show on screen" toggle (dual-screen) ─────────────────────────────

    /** Render-funnel reconciliation for the on-screen boxes: every state
     *  change lands here first. A state without boxes tears a paint down
     *  (Clear, a fresh capture's status hop, a live/drag/edit result) — the
     *  hide is unconditional because it's idempotent and the host owns the
     *  truth; a state WITH boxes while painted is the same session's
     *  skeleton → translated promotion (a new capture always hops through a
     *  boxless InProgress status first, so it can't masquerade as a
     *  promotion), which swaps the paint in place. */
    private fun syncOnScreenBoxes(boxes: OnScreenBoxes?) {
        currentOnScreenBoxes = boxes
        if (boxes == null) {
            host?.hideResultBoxesOnScreen()
        } else if (host?.isResultBoxesShownOnScreen() == true) {
            host?.updateResultBoxesOnScreen(boxes)
        }
        refreshShowOnScreen()
    }

    /** The host's poke that the boxes window state changed underneath us
     *  (tap on the boxes, live start, display change, onStop). Freshness
     *  only: the refresh re-reads the host's ownership truth, so a late,
     *  duplicate, or self-initiated poke can't render a wrong state. */
    fun onScreenBoxesDismissed() {
        if (view == null) return
        refreshShowOnScreen()
    }

    /** Recompute the toggle's visibility + accent from the current mode.
     *  Selected state derives from the host's window ownership on every call
     *  (never a fragment-side mirror). Public so the host can poke it on
     *  transitions the fragment can't see (live-mode start/stop with an
     *  unchanged VM state, viewport flips). */
    fun refreshShowOnScreen() {
        if (view == null) return
        val liveState = host?.liveShowOnScreenState()
        if (liveState != null) {
            // Live semantics: the pill mirrors the setting, no boxes needed.
            content.binder.setShowOnScreenAvailable(true)
            content.binder.setShowOnScreenToggled(liveState)
        } else {
            content.binder.setShowOnScreenAvailable(
                currentOnScreenBoxes != null && host?.supportsShowOnScreen() == true
            )
            content.binder.setShowOnScreenToggled(host?.isResultBoxesShownOnScreen() == true)
        }
    }

    private fun onShowOnScreenTapped() {
        val liveState = host?.liveShowOnScreenState()
        if (liveState != null) {
            host?.setLiveShowOnScreen(!liveState)
            // The pref observer refreshes too; this keeps the pill snappy.
            refreshShowOnScreen()
            return
        }
        if (host?.isResultBoxesShownOnScreen() == true) {
            host?.hideResultBoxesOnScreen()
        } else {
            currentOnScreenBoxes?.let { host?.showResultBoxesOnScreen(it) }
            // Boxes on a deferred result go up as skeletons — run the skipped
            // translation; its completion swaps the filled boxes in.
            content.requestDeferredCompletion(force = true)
        }
        // Ownership flipped synchronously (or the show was refused); the
        // refresh reads whichever reality landed.
        refreshShowOnScreen()
    }

    /** Game-audio ring anchor for an Anki launch from this page: a
     *  history-seeded page anchors at the ROW's capture moment (this page's
     *  result object is stamped at page-open, which says nothing about when
     *  the row's line was heard); every other launch anchors at the result's
     *  own creation. */
    private fun audioAnchorMsFor(result: com.playtranslate.model.TranslationResult?): Long? =
        activity?.intent
            ?.getLongExtra(TranslationResultActivity.EXTRA_HISTORY_AT_MS, 0L)
            ?.takeIf { it > 0 }
            ?: result?.createdAtMs?.takeIf { it > 0 }

    /** First sense's POS (blank-filtered, " · "-joined) + the flattened card
     *  definition — the shared (POS, definition) extraction the word-Anki paths use. */
    private fun com.playtranslate.model.DictionaryEntry.ankiPosAndDefinition(): Pair<String, String> {
        val pos = senses.firstOrNull()?.partsOfSpeech
            ?.filter { it.isNotBlank() }?.joinToString(" · ") ?: ""
        return pos to flatCardDefinition(this)
    }

    /** Build + launch the per-word Anki review Activity: the word's own fields plus
     *  the current Ready result's sentence context (source + translation +
     *  screenshot). Callers pre-compute the cleaned [reading] (blank when it equals
     *  the word) and own their AnkiDroid-installed gate. Used by [launchWordAnki]
     *  (lens/popup, resolves an entry); the result cells no longer carry an
     *  Anki button (their trailing slot is the hidden-words eye), so a word
     *  card from the list goes through Word Detail. */
    private fun launchWordAnkiIntent(
        activity: Activity,
        word: String,
        reading: String,
        pos: String,
        definition: String,
        freqScore: Int,
    ) {
        val ready = currentReady()
        val intent = Intent(activity, AnkiPermissionActivity::class.java).apply {
            putExtra(WordAnkiReviewActivity.EXTRA_WORD, word)
            putExtra(WordAnkiReviewActivity.EXTRA_READING, reading)
            putExtra(WordAnkiReviewActivity.EXTRA_POS, pos)
            putExtra(WordAnkiReviewActivity.EXTRA_DEFINITION, definition)
            putExtra(WordAnkiReviewActivity.EXTRA_FREQ_SCORE, freqScore)
            ready?.screenshotPath?.let { putExtra(WordAnkiReviewActivity.EXTRA_SCREENSHOT_PATH, it) }
            ready?.originalText?.let { putExtra(WordAnkiReviewActivity.EXTRA_SENTENCE_ORIGINAL, it) }
            ready?.translatedText?.let { putExtra(WordAnkiReviewActivity.EXTRA_SENTENCE_TRANSLATION, it) }
            audioAnchorMsFor(ready)?.let { putExtra(WordAnkiReviewActivity.EXTRA_AUDIO_ANCHOR_MS, it) }
            // Same result as the sentence extras above, so the pending rides
            // its own original (resolveAnkiTranslation's caller contract) —
            // the sheet's fill then COMPLETES a deferred capture instead of
            // translating around its null History rows.
            ready?.pendingTranslation?.let { putExtra(WordAnkiReviewActivity.EXTRA_SENTENCE_PENDING, it) }
            putExtra(WordAnkiReviewActivity.EXTRA_SOURCE_LANG, prefs.sourceLangId.code)
        }
        activity.startActivity(intent)
    }

    /** Lens Anki chip handler — adds the tapped word (not the sentence)
     *  to Anki. Mirrors [DragLookupController.openAnkiReviewForLens]:
     *  installation gate here, permission gate handled by the launched
     *  [AnkiPermissionActivity]. Sentence context comes from the current
     *  VM result so the card carries the source sentence + translation +
     *  screenshot. */
    private fun launchWordAnki(
        activity: Activity,
        word: String,
        reading: String?,
        entry: com.playtranslate.model.DictionaryEntry?,
    ) {
        val ankiManager = AnkiManager(activity)
        if (!ankiManager.isAnkiDroidInstalled()) {
            showAnkiNotInstalledDialog(activity)
            return
        }
        val (pos, definition) = entry?.ankiPosAndDefinition() ?: ("" to "")
        dismissWordPopup()
        launchWordAnkiIntent(
            activity, word,
            reading = reading?.takeIf { it != word } ?: "",
            pos = pos, definition = definition, freqScore = entry?.freqScore ?: 0,
        )
    }

    /**
     * One-tap sentence-card send from the section-header Anki button's
     * long-press. Falls back to the existing sheet flow ([onAnkiClicked])
     * on any gate failure (AnkiDroid missing, permission denied, no deck
     * picked) so the user can still resolve the prerequisite. Progress +
     * outcome are reported via Toasts; NeedsMapping still opens the
     * field-mapping dialog inline so the user can configure their custom
     * card type without leaving the result screen.
     */
    private fun oneTapSentenceFromResult() {
        host?.onInteraction()
        val result = currentReady() ?: return
        // Anki consumes the sentence translation — a deferred result must
        // complete through the funnel (translation + History attach + ring),
        // not only through the dispatch's own lazy translateOnce, which
        // would leave the capture's null rows unfilled and the pending set.
        // The dispatch's fill still covers the card if it runs first; at
        // worst this flow costs one duplicate backend call.
        content.requestDeferredCompletion(force = true)
        val activity = activity ?: return
        val ankiManager = AnkiManager(activity)
        if (!ankiManager.isAnkiDroidInstalled() || !ankiManager.hasPermission()) {
            onAnkiClicked()  // existing dialogs handle these gates
            return
        }
        if (prefs.ankiDeckId < 0L) {
            onAnkiClicked()  // sheet shows the deck picker
            return
        }
        val original = getDisplayedOriginalText()
        val translation = result.translatedText.takeIf { it.isNotEmpty() }
        // Snapshot rows ONCE so the words map and the surface map
        // come from the same Settled emission — no surfaceForms race
        // (see LastSentenceCache.awaitOrStartWordLookups docs).
        val settledRows = currentSettledRows()
        val wordsPayload = settledRows?.let {
            LastSentenceCache.WordsPayload(
                it.toLegacyMap(), it.toSurfaceMap(), it.toEnrichmentMap(),
                annotation = currentSettledAnnotation(),
            )
        }
        val screenshotPath = result.screenshotPath
        val appCtx = requireContext().applicationContext
        val langId = prefs.sourceLangId
        Toast.makeText(appCtx, R.string.anki_adding_in_progress, Toast.LENGTH_SHORT).show()
        // launchOneTapSend: the send outlives this fragment (navigating away
        // must not cancel a card the user already asked for); the result UI
        // runs only with the view lifecycle STARTED, else it degrades to an
        // app-context toast.
        launchOneTapSend(
            appCtx = appCtx,
            send = {
                appCtx.oneTapSendSentence(
                    original = original,
                    translation = translation,
                    wordsPayload = wordsPayload,
                    screenshotPath = screenshotPath,
                    sourceLangId = langId,
                    // Deferred result: the lazy translate runs the deferred
                    // completion; overlapping with the host funnel triggered
                    // above is fine — the attach is idempotent and the second
                    // per-group batch is cache-served.
                    pendingTranslation = result.pendingTranslation,
                )
            },
            resultOf = { it },
            modeOf = { CardMode.SENTENCE },
            presentResult = { sendResult ->
                when (sendResult) {
                    is AnkiSendResult.Success -> {
                        val msgRes = sendResult.shortfallRes()
                            ?: ankiAddedSuccessRes(CardMode.SENTENCE)
                        Toast.makeText(appCtx, msgRes, Toast.LENGTH_SHORT).show()
                        content.wordRows.refreshWordBadges()
                    }
                    is AnkiSendResult.Failed -> {
                        val ctx = requireContext()
                        OverlayAlert.Builder(requireActivity())
                            .setTitle(getString(R.string.anki_send_failed_title))
                            .setMessage(sendResult.message ?: getString(sendResult.messageRes))
                            .addButton(
                                getString(android.R.string.ok),
                                ctx.themeColor(R.attr.ptAccent),
                                ctx.themeColor(R.attr.ptAccentOn),
                            ) {}
                            .show()
                    }
                    is AnkiSendResult.NeedsMapping -> {
                        // Dispatcher already toasted; open the mapping dialog
                        // so the user can fix the unmapped card type.
                        showAnkiCardTypeMappingDialog(sendResult.model, CardMode.SENTENCE) { _, _ -> }
                    }
                    // Unreachable: one-tap passes no oversizePrompt.
                    is AnkiSendResult.Declined -> Unit
                }
            },
        )
    }

    /**
     * Headless one-tap counterpart to [launchWordAnki] for the in-app
     * word popup. Same data extraction (POS, joined definition) and
     * the same fallback to the existing Activity flow on gate failure.
     * Result Toast lands on the result screen so the user has feedback
     * without the popup needing to stay open during the send.
     */
    private fun oneTapWordFromPopup(
        activity: Activity,
        word: String,
        reading: String?,
        entry: com.playtranslate.model.DictionaryEntry?,
        entries: List<com.playtranslate.model.DictionaryEntry>,
    ) {
        val ankiManager = AnkiManager(activity)
        if (!ankiManager.isAnkiDroidInstalled() || !ankiManager.hasPermission()) {
            launchWordAnki(activity, word, reading, entry)
            return
        }
        if (prefs.ankiDeckId < 0L) {
            launchWordAnki(activity, word, reading, entry)
            return
        }
        if (entry == null) {
            // No resolved entry — fall back so the user sees the error
            // path from inside the sheet rather than silently failing.
            launchWordAnki(activity, word, reading, entry)
            return
        }
        val (pos, definition) = entry.ankiPosAndDefinition()
        val ready = currentReady()
        val screenshotPath = ready?.screenshotPath
        val readingClean = reading?.takeIf { it != word } ?: ""
        // The popup is anchored inside a translated sentence on the
        // result screen — the same context the lens chip has. Match
        // the lens behavior: send a sentence card with the tapped
        // word highlighted when sentence context is available, and
        // only fall back to a word card when the source text isn't a
        // sentence we have.
        val ready_sentence = ready?.originalText?.takeIf { it.isNotEmpty() }
        val ready_translation = ready?.translatedText?.takeIf { it.isNotEmpty() }
        // Atomic snapshot — see oneTapSentenceFromResult for the
        // surface-forms-race rationale.
        val settledRows = currentSettledRows()
        val wordsPayload = settledRows?.let {
            LastSentenceCache.WordsPayload(
                it.toLegacyMap(), it.toSurfaceMap(), it.toEnrichmentMap(),
                annotation = currentSettledAnnotation(),
            )
        }
        dismissWordPopup()
        val appCtx = activity.applicationContext
        val langId = prefs.sourceLangId
        Toast.makeText(appCtx, R.string.anki_adding_in_progress, Toast.LENGTH_SHORT).show()
        // launchOneTapSend — see oneTapSentenceFromResult.
        launchOneTapSend(
            appCtx = appCtx,
            send = {
                val hw = entry.headwordDisplay(entry.selectHeadword(word, word, readingClean), word)
                // Shared word-vs-sentence routing (single-word-sentence rule
                // included) lives in oneTapSend; the popup ignores the returned mode.
                appCtx.oneTapSend(
                    word = word,
                    reading = readingClean,
                    pos = pos,
                    entry = entry,
                    entries = entries,
                    fallbackDefinition = definition,
                    freqScore = entry.freqScore,
                    pitch = hw.pitch,
                    frequencies = hw.frequencies,
                    sentenceOriginal = ready_sentence,
                    sentenceTranslation = ready_translation,
                    wordsPayload = wordsPayload,
                    screenshotPath = screenshotPath,
                    sourceLangId = langId,
                    // Same result as ready_sentence — a deferred pending
                    // rides so the sentence branch completes, not bypasses.
                    pendingTranslation = ready?.pendingTranslation,
                )
            },
            resultOf = { it.first },
            modeOf = { it.second },
            presentResult = { (result, mode) ->
                when (result) {
                    is AnkiSendResult.Success -> {
                        // Sentence-mode one-tap can drop per-target-word
                        // audio (the target word may fail TTS or upload)
                        // or the screenshot; surface that the same way the
                        // other handlers do.
                        val msgRes = result.shortfallRes() ?: ankiAddedSuccessRes(mode)
                        Toast.makeText(appCtx, msgRes, Toast.LENGTH_SHORT).show()
                        content.wordRows.refreshWordBadges()
                    }
                    is AnkiSendResult.Failed -> {
                        Toast.makeText(appCtx,
                            result.message ?: appCtx.getString(result.messageRes),
                            Toast.LENGTH_LONG).show()
                    }
                    is AnkiSendResult.NeedsMapping -> {
                        // Re-launch the Activity so the user can configure
                        // the mapping inside the sheet (dialog needs
                        // Fragment infrastructure).
                        launchWordAnki(activity, word, reading, entry)
                    }
                    // Unreachable: one-tap passes no oversizePrompt.
                    is AnkiSendResult.Declined -> Unit
                }
            },
        )
    }

    /** Anki button tap handler — view-side dialog work, kept fragment-
     *  internal. Reads sentence + word data from VM state. */
    private fun onAnkiClicked() {
        host?.onInteraction()
        val result = currentReady() ?: return
        // Anki consumes the sentence translation — a deferred result must
        // complete through the funnel (translation + History attach + ring),
        // not only through the review sheet's own lazy fill, which would
        // leave the capture's null rows unfilled and the pending set. The
        // sheet's fill still covers the card if it opens before the
        // completion lands; at worst this flow costs one duplicate backend
        // call.
        content.requestDeferredCompletion(force = true)
        val activity = activity ?: return
        val ankiManager = AnkiManager(activity)
        // Snapshot the settled rows ONCE so wordResults + surfaces + enrichment
        // all come from the same emission; the sheet renders from this atomic
        // snapshot, never the global cache (see AnkiReviewBottomSheet.newInstance).
        val settledRows = currentSettledRows()
        val wordResults = settledRows?.toLegacyMap() ?: emptyMap()
        when {
            !ankiManager.isAnkiDroidInstalled() ->
                showAnkiNotInstalledDialog(activity)
            !ankiManager.hasPermission() ->
                showAnkiPermissionRationaleDialog(activity) {
                    host?.getAnkiPermissionLauncher()?.launch(AnkiManager.PERMISSION)
                }
            else -> {
                // A one-word result opens the word card directly (no
                // sentence/word toggle) — a sentence card would just repeat
                // the word. WordAnkiReviewSheet renders word-only with no
                // toggle whenever it's launched without sentence args.
                val audioAnchorMs = audioAnchorMsFor(result)
                val singleRow = (vm.wordLookups.value as? WordLookupsState.Settled)
                    ?.singleWordRow(getDisplayedOriginalText())
                if (singleRow != null) {
                    WordAnkiReviewSheet.newInstance(
                        word = singleRow.displayWord,
                        reading = singleRow.reading,
                        pos = singleRow.ankiPos,
                        definition = singleRow.meaning,
                        screenshotPath = result.screenshotPath,
                        freqScore = singleRow.freqScore,
                        isCommon = singleRow.isCommon,
                        sourceLangId = prefs.sourceLangId,
                    ).show(childFragmentManager, WordAnkiReviewSheet.TAG)
                } else {
                    AnkiReviewBottomSheet.newInstance(
                        getDisplayedOriginalText(), result.translatedText, wordResults,
                        settledRows?.toSurfaceMap() ?: emptyMap(),
                        settledRows?.toEnrichmentMap() ?: emptyMap(),
                        result.screenshotPath, prefs.sourceLangId,
                        // Deferred result: the sheet's lazy fill runs the
                        // deferred completion; overlapping with the host
                        // funnel triggered above is fine — the attach is
                        // idempotent and the second batch is cache-served.
                        pendingTranslation = result.pendingTranslation,
                        audioAnchorMs = audioAnchorMs,
                    ).show(childFragmentManager, AnkiReviewBottomSheet.TAG)
                }
            }
        }
    }

    /** The in-app lens's chips: the open chevron (only into a matched
     *  entry) and the secondary sections route through the host's word-tap
     *  (the detail sheet); the Anki chip's tap opens the editable review,
     *  its long-press is the headless one-tap shortcut (the pro-tip footer
     *  in Settings). */
    private fun wireLensActions(lens: MagnifierLens, resolvedAt: SourceWordLookup.ResolvedAt) {
        val activity = activity ?: return
        val resolved = resolvedAt.word
        val phrase = resolvedAt.phrase
        val secondaries = phrase?.let { listOf(it) } ?: resolvedAt.members
        val word = resolved.word
        val popupReading = resolved.reading
        val displayEntry = resolved.entry
        val displayEntries = resolved.entries
        fun openDetail(w: String, reading: String?) {
            dismissWordPopup()
            host?.onInteraction()
            val ready = currentReady()
            host?.onWordTapped(
                w, reading,
                ready?.screenshotPath,
                ready?.originalText,
                ready?.translatedText,
                currentSettledRows()?.toLegacyMap() ?: emptyMap(),
            )
        }
        if (displayEntry != null) {
            lens.onOpenTap = { openDetail(word, popupReading) }
        }
        if (secondaries.isNotEmpty()) {
            // Secondary-section drill-in (containing phrase or member words):
            // same detail route as the tapped unit — the sheet re-looks the
            // string up, and a multi-word key round-trips it unchanged.
            lens.onSecondaryOpenTap = { i ->
                secondaries.getOrNull(i)?.let { sec -> openDetail(sec.word, sec.reading) }
            }
        }
        lens.onAnkiTap = {
            host?.onInteraction()
            launchWordAnki(activity, word, popupReading, displayEntry)
        }
        lens.onAnkiLongPress = {
            host?.onInteraction()
            oneTapWordFromPopup(activity, word, popupReading, displayEntry, displayEntries)
        }
    }

    private fun dismissWordPopup() {
        content.dismissLens()
    }

    fun setLiveHintText(text: CharSequence) {
        if (view != null) content.setLiveHintText(text)
    }

    /** Returns the displayed original text (with OCR line breaks preserved). */
    fun getDisplayedOriginalText(): String =
        if (view != null) content.displayedOriginalText() else ""

    override fun onResume() {
        super.onResume()
        // Deck membership can change while we're away (a card added here, in the
        // review sheet, or in AnkiDroid). Re-evaluate so badges aren't stuck on
        // the cached pre-add state.
        content.wordRows.refreshWordBadges()
    }

    /** The in-app side of the content seam: the lens lives in the activity
     *  window and its chips route through this fragment's host + Anki
     *  launchers; a word tap opens the host's detail sheet; editing, the
     *  OCR and language pickers, Clear and the show-on-screen toggle are
     *  this page's; the render hook reconciles the on-screen boxes; the
     *  host activity completes deferred translations. */
    private inner class ContentHost(private val activity: Activity) : TranslationResultContent.Host {
        override val isAlive: Boolean get() = isAdded && view != null
        override val scope: CoroutineScope get() = viewLifecycleOwner.lifecycleScope
        override val ttsAlertTarget: TtsAlertTarget = TtsAlertTarget.InActivity(activity)
        override val lensOverlayHost: OverlayHost? get() = null
        override val lensWindowManager: WindowManager get() = OwnWindows.managerOf(activity)
        override val lensDisplayId: Int get() = android.view.Display.DEFAULT_DISPLAY
        override fun screenSize(): Point {
            val dm = resources.displayMetrics
            return Point(dm.widthPixels, dm.heightPixels)
        }

        /** No entry, nothing to open into, no chevron. */
        override val opensWithoutEntry: Boolean get() = false

        override fun wireLensActions(lens: MagnifierLens, resolved: SourceWordLookup.ResolvedAt) =
            this@TranslationResultFragment.wireLensActions(lens, resolved)

        override fun onWordTapped(word: String, reading: String?) {
            val ready = currentReady()
            host?.onWordTapped(
                word, reading,
                ready?.screenshotPath,
                ready?.originalText,
                ready?.translatedText,
                currentSettledRows()?.toLegacyMap() ?: emptyMap(),
            )
        }

        override fun onInteraction() {
            host?.onInteraction()
        }

        override fun onUserScrolled() {
            host?.onUserScrolled()
        }

        /** Reconcile the on-screen boxes BEFORE rendering: any state that
         *  doesn't carry boxes (a new capture's InProgress status, Clear, a
         *  live result, an edit commit) dismisses them and disables the
         *  toggle — the single funnel for "new content ends the
         *  presentation". */
        override fun onRender(state: ResultState) {
            syncOnScreenBoxes(
                when (state) {
                    is ResultState.Translating -> state.onScreenBoxes
                    is ResultState.Ready -> state.onScreenBoxes
                    else -> null
                },
            )
        }

        override fun canReOcr(): Boolean = host?.canReOcr() == true

        override val showsClearAction: Boolean get() = this@TranslationResultFragment.showsClearAction

        /** Pure state action — reset directly to idle status; the fragment
         *  re-renders from the VM. */
        override fun onClear() {
            vm.showStatus(getString(R.string.status_idle), showHint = true)
        }

        override val showsStartHint: Boolean get() = true

        /** Editing is surface-specific: in-app it opens the host's edit overlay. */
        override val editAvailable: Boolean get() = true
        override fun onEditRequested() {
            host?.onEditOriginalRequested()
        }

        override fun onChooseOcr(provenance: OcrProvenance) =
            showOcrPicker(provenance.sourceLangId, provenance.engineToken)

        override fun onChangeLanguage(isSource: Boolean) {
            host?.onChangeLanguageRequested(isSource)
        }

        override fun onAddToAnki() = onAnkiClicked()
        override fun onAnkiOneTap() = oneTapSentenceFromResult()
        override fun onShowOnScreenTapped() = this@TranslationResultFragment.onShowOnScreenTapped()

        override fun completeDeferredTranslation() {
            host?.completeDeferredTranslation()
        }
    }
}
