package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
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
import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.ocr.registry.selectionToken
import com.playtranslate.themeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.drop
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import androidx.core.view.isGone
import androidx.core.view.isEmpty

/**
 * Reset [ScrollView] scroll to (0, 0) without firing the registered
 * scroll listener — i.e. without making a programmatic reset look like
 * user intent. Detach → scrollTo (synchronous, fires onScrollChanged
 * inline on the main thread, sees no listener) → reattach.
 *
 * Only safe with the synchronous [ScrollView.scrollTo]; do not use with
 * [ScrollView.smoothScrollTo] which dispatches asynchronously and would
 * fire onScrollChanged after the reattach.
 */
private fun ScrollView.scrollToTopSilently(listener: View.OnScrollChangeListener) {
    setOnScrollChangeListener(null)
    scrollTo(0, 0)
    setOnScrollChangeListener(listener)
}

/**
 * Restore a saved scroll [y] without firing the registered listener — the
 * preserve-position counterpart to [scrollToTopSilently], used when only the
 * translation changed (so the user's place in the result shouldn't jump).
 * [ScrollView.scrollTo] clamps [y] to the current content range, so an offset
 * that outran a now-shorter result lands at the bottom rather than in empty
 * space. Same synchronous-scrollTo caveat as [scrollToTopSilently].
 */
private fun ScrollView.restoreScrollSilently(y: Int, listener: View.OnScrollChangeListener) {
    setOnScrollChangeListener(null)
    scrollTo(0, y)
    setOnScrollChangeListener(listener)
}

/**
 * Top of [descendant] in this ScrollView's content coordinate space —
 * i.e. independent of the current scroll offset (offsetDescendantRectToMyCoords
 * stops at this view, so it never subtracts our own scrollY). Subtract
 * [ScrollView.getScrollY] to get the view's offset from the viewport top.
 */
private fun ScrollView.contentTopOf(descendant: View): Int {
    val r = Rect(0, 0, descendant.width, descendant.height)
    offsetDescendantRectToMyCoords(descendant, r)
    return r.top
}

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

    // ── Views ─────────────────────────────────────────────────────────────
    private lateinit var tvStatus: TextView
    private lateinit var tvStatusHint: TextView
    private lateinit var tvLiveHint: TextView
    private lateinit var statusContainer: View
    private lateinit var resultsContent: ScrollView
    private lateinit var tvOriginal: ClickableTextView
    private lateinit var tvMainWordsLoading: TextView
    private lateinit var mainWordsContainer: LinearLayout

    /** Renders the source + target sections (shared with the over-game capture
     *  panel): inline furigana, the word highlight, section visibility, copy, and
     *  the source speak button. */
    private lateinit var binder: TranslationSectionBinder

    /** Text-size range picker, hosted in this fragment's root FrameLayout. */
    private var fontPopover: FontSizeRangePopover? = null

    /** Session cache of headword → Anki deck names, shared by the words list
     *  and the in-app word lens so re-renders / re-taps don't re-query. */
    private val ankiDecksByWord = HashMap<String, List<String>>()
    /** Word cells from the last word-list render, so onResume / a successful
     *  in-app send can refresh the deck badges in place (no row rebuild). */
    private var lastRenderedCells: Map<String, List<WordResultCell>> = emptyMap()
    private lateinit var btnToggleWords: ImageButton
    private lateinit var wordsContent: LinearLayout
    private lateinit var cardWords: com.google.android.material.card.MaterialCardView
    private lateinit var tvNoWords: TextView
    private lateinit var resultActionButtons: View
    private lateinit var btnResultClear: View

    /** Maps character ranges in original text to (displayWord, reading).
     *  Recomputed in [renderWordLookups] Settled branch from the VM's
     *  tokenSpans on each Settled emission, so it tracks the displayed
     *  text (which has OCR newlines). */
    private var wordSpans = mutableListOf<Triple<IntRange, String, String>>()
    private var furiganaPopup: PopupWindow? = null

    /** Bumped on every [renderResult] call. The results reveal is async (hide →
     *  fit → show across two posts), so a fast Translating→Status/Error/Idle
     *  transition could otherwise let a queued reveal re-show stale results over
     *  the status screen. Each posted step bails if the generation moved on. */
    private var renderGeneration = 0

    /** Source text of the last Translating/Ready render, used to decide
     *  whether a new Ready emission should keep the user's scroll position
     *  (same source → only the translation changed, e.g. a Translating→Ready
     *  promotion or a backend re-translate) or reset to the top (source
     *  changed → a fresh capture or an edit-overlay commit, where the old
     *  offset is meaningless). Cleared to null on status/idle/error so the
     *  next translation always starts at the top. */
    private var lastRenderedSourceText: String? = null

    /** Reified scroll listener so [scrollToTopSilently] can detach + reattach
     *  it around programmatic scrolls — otherwise the framework's
     *  onScrollChanged callback for our own [resultsContent.scrollTo] would
     *  be misread as user intent and pause live mode the instant a fresh
     *  result lands. */
    private val scrollListener = View.OnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
        if (scrollY != oldScrollY) {
            dismissFurigana()
            dismissWordPopup()
            // NOT the font popover: its scrim makes the scroll untouchable
            // while it's open, so any scroll seen here is our OWN re-fit
            // reflowing the cards — dismissing on it would close the popover
            // out from under the drag that caused it.
            host?.onUserScrolled()
        }
    }

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
        bindViews(view)
        binder = TranslationSectionBinder(
            view,
            requireContext(),
            prefs,
            viewLifecycleOwner.lifecycleScope,
            TtsAlertTarget.InActivity(requireActivity()),
        )
        // The layout root is a FrameLayout, so the popover floats over the
        // results scroll without a wrapper. Its scrim covers this fragment
        // only — a tap on surrounding activity chrome won't dismiss it.
        fontPopover = FontSizeRangePopover(requireContext(), view as FrameLayout, prefs).apply {
            // fitTextSizes fits each section to half the (unchanged) scroll
            // height, so the sections resize in place and the anchor button —
            // topmost in the scroll — never moves under the user's finger.
            onRangeChanged = { fitTextSizes() }
        }
        setupButtons()
        // Observe activity-scoped VM state. Both flows are activity-scoped
        // (survive fragment view recreation), so a rotation re-renders the
        // last state without re-running the pipeline. The collectors run
        // only while the fragment is STARTED, so they cleanly stop when
        // the view is destroyed and resume when recreated.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.result.collect { renderResult(it) } }
                launch { vm.wordLookups.collect { renderWordLookups(it) } }
                // Refresh deck badges when a card is added anywhere in the app
                // (word detail, review sheet, one-tap). Fires while those
                // dialogs are on top — this fragment stays STARTED beneath them,
                // which an onResume hook would miss.
                launch {
                    AnkiManager.noteAddedTick.drop(1).collect { refreshWordBadges() }
                }
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
        dismissFurigana()
        dismissWordPopup()
        fontPopover?.dismiss()
        fontPopover = null
        binder.release()
        super.onDestroyView()
    }

    private fun bindViews(view: View) {
        tvStatus             = view.findViewById(R.id.tvStatus)
        tvStatusHint         = view.findViewById(R.id.tvStatusHint)
        tvLiveHint           = view.findViewById(R.id.tvLiveHint)
        statusContainer      = view.findViewById(R.id.statusContainer)
        resultsContent       = view.findViewById(R.id.resultsContent)
        tvOriginal           = view.findViewById(R.id.tvOriginal)
        tvMainWordsLoading   = view.findViewById(R.id.tvMainWordsLoading)
        mainWordsContainer   = view.findViewById(R.id.mainWordsContainer)
        btnToggleWords       = view.findViewById(R.id.btnToggleWords)
        wordsContent         = view.findViewById(R.id.wordsContent)
        cardWords            = view.findViewById(R.id.cardWords)
        tvNoWords            = view.findViewById(R.id.tvNoWords)
        resultActionButtons  = view.findViewById(R.id.resultActionButtons)
        btnResultClear       = view.findViewById(R.id.btnResultClear)
    }

    private fun setupButtons() {
        // Copy / show-hide / furigana toggle / speak live in the shared binder.
        // Editing is surface-specific: in-app it opens the host's edit overlay.
        binder.setupSectionButtons(
            onEdit = {
                dismissFurigana()
                dismissWordPopup()
                host?.onEditOriginalRequested()
            },
            onAddToAnki = { onAnkiClicked() },
            onAnkiOneTap = { oneTapSentenceFromResult() },
        )
        binder.onChooseFontSize = {
            fontPopover?.toggle(binder.fontSizeAnchor)
        }
        binder.onChooseOcr = {
            currentReady()?.ocrProvenance?.let { showOcrPicker(it.sourceLangId, it.engineToken) }
        }
        binder.onChooseLanguage = { isSource -> host?.onChangeLanguageRequested(isSource) }
        binder.setShowOnScreenAction { onShowOnScreenTapped() }
        // This vertical page just reflows on an eye toggle (no re-layout work),
        // but REVEALING the translation section on a deferred result must run
        // the translation that was skipped while it was hidden.
        binder.onSectionVisibilityChanged = { maybeRequestDeferredCompletion() }
        resultsContent.setOnScrollChangeListener(scrollListener)
        btnToggleWords.setOnClickListener {
            prefs.hideWordsSection = !prefs.hideWordsSection
            applyWordsVisibility()
        }
        btnResultClear.setOnClickListener {
            // Pure state action — no host context needed. Reset directly
            // to idle status; the fragment will re-render from the VM.
            vm.showStatus(getString(R.string.status_idle), showHint = true)
        }
        // The Anki action now lives on the source/target section headers
        // (tap = review sheet, long-press = one-tap), wired via the binder's
        // setupSectionButtons above.
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

    private fun applyWordsVisibility() {
        val hidden = prefs.hideWordsSection
        cardWords.visibility = if (hidden) View.GONE else View.VISIBLE
        btnToggleWords.setImageResource(if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
    }

    // ── Result render (driven by vm.result observation) ──────────────────

    private fun renderResult(state: ResultState) {
        if (view == null) return
        // Every render supersedes any pending async reveal from a prior one —
        // see [renderGeneration]. Captured by [revealResultsContentFitted].
        val generation = ++renderGeneration
        // Reconcile the on-screen boxes BEFORE rendering: any state that
        // doesn't carry boxes (a new capture's InProgress status, Clear, a
        // live result, an edit commit) dismisses them and disables the toggle
        // — the single funnel for "new content ends the presentation".
        syncOnScreenBoxes(
            when (state) {
                is ResultState.Translating -> state.onScreenBoxes
                is ResultState.Ready -> state.onScreenBoxes
                else -> null
            }
        )
        when (state) {
            is ResultState.Idle -> {
                showStatusUi(getString(R.string.status_idle), showHint = true)
            }
            is ResultState.Status -> {
                showStatusUi(state.message, state.showHint, state.ocrProvenance)
            }
            is ResultState.Error -> {
                showStatusUi(getString(R.string.status_error, state.message), showHint = false)
            }
            is ResultState.Translating -> {
                // A placeholder is always a freshly-started translation (drag
                // sentence / edit commit), so reset to top; record the source
                // so the matching Ready promotion preserves the user's scroll.
                lastRenderedSourceText = state.originalText
                binder.bindSource(state.segments)
                tvOriginal.onTapAtOffset = { offset -> onOriginalTapped(offset) }
                // Capture placeholders carry OCR provenance — show "Scanned by …" with
                // the source as soon as OCR finishes, before the translation lands.
                // Drag/edit placeholders carry null, which hides the row. The gear
                // stays hidden until the result settles (Ready/Status): re-OCR can't
                // act on a transient Translating state, so a gear here is a dead control.
                binder.bindSourceOcr(state.ocrProvenance, canReOcr = false)
                binder.setTargetTranslatingPlaceholder()
                binder.applyTranslationVisibility()
                binder.applyOriginalVisibility()
                applyWordsVisibility()
                binder.updateLabels()
                statusContainer.isGone = true
                resultActionButtons.isVisible = showsClearAction
                // The source text is final the instant the placeholder shows, so
                // fit it now — not when the translation later lands, which would
                // make it visibly resize. revealResultsContentFitted is the shared
                // hide→fit→show both states run, so the two can't drift on sizing.
                revealResultsContentFitted(generation) { scrollToFreshResultStart() }
            }
            is ResultState.Ready -> {
                val result = state.result
                // Keep the user's place when only the translation changed
                // (Translating→Ready promotion, backend re-translate); reset to
                // top only when the source text itself changed — a fresh capture
                // or an edit-overlay commit — where the old offset is meaningless.
                // The anchor is captured before the binder mutates content, so it
                // reflects what the user was looking at. See [lastRenderedSourceText].
                val preserveScroll = result.originalText == lastRenderedSourceText
                val scrollAnchor = if (preserveScroll) captureScrollAnchor() else null
                lastRenderedSourceText = result.originalText
                binder.bindSource(result.segments)
                binder.bindSourceOcr(result.ocrProvenance, canReOcr = host?.canReOcr() == true)
                tvOriginal.onTapAtOffset = { offset -> onOriginalTapped(offset) }
                // A blank translation on a Ready result means a re-translate is
                // in flight: the edit-overlay commit clears the old translation
                // (updateOriginalText) before the new one lands (updateTranslation).
                // The binder shows the same "Translating…" placeholder instead of
                // an empty card and suppresses the now-stale backend label.
                binder.bindTargetReady(result)
                binder.applyTranslationVisibility()
                binder.applyOriginalVisibility()
                applyWordsVisibility()
                binder.updateLabels()
                statusContainer.isGone = true
                resultActionButtons.isVisible = showsClearAction
                revealResultsContentFitted(generation) {
                    if (scrollAnchor != null) restoreScrollAnchor(scrollAnchor)
                    else scrollToFreshResultStart()
                }
                // A deferred result bound while the section is visible (revealed
                // on another surface — the pref is global and nothing listens
                // for flips) must run its skipped translation now. No-op for
                // results without a pending, so safe on every Ready render.
                maybeRequestDeferredCompletion()
            }
        }
    }

    /** Fresh-result scroll reset: always the top.
     *
     *  Deliberately NOT the hidden-section park that [CaptureResultOverlay]
     *  does (scrolling the collapsed translation header off the top when its
     *  eye is closed). That park belongs to the panels that sit over the game,
     *  where the panel is a transient sheet and every pixel is borrowed from
     *  the game. The in-app results page is a page: it starts at its top, its
     *  own top bar stays put, and a self-inflicted scroll here reads as user
     *  intent — in dual-screen live mode it tripped [scrollListener] →
     *  host.onUserScrolled() → pauseLiveMode on every incoming result. */
    private fun scrollToFreshResultStart() {
        resultsContent.scrollToTopSilently(scrollListener)
    }

    /** Shared status / error / idle layout — single status container,
     *  results hidden, Anki gone. [showHint] gates the
     *  "press X to start" hint line under the message. */
    private fun showStatusUi(
        message: String,
        showHint: Boolean,
        ocrProvenance: OcrProvenance? = null,
    ) {
        // Leaving the results view drops the scroll anchor: the next translation
        // is unrelated content and should land at the top.
        lastRenderedSourceText = null
        // No-text status affordances, each its own tappable span (so tapping one can't
        // trigger the other): the source-language name is accent-colored → source picker
        // (same as the source header); the gear → OCR picker, shown when the switch will
        // actually be acted on (a pinned frame to re-OCR, or a live loop that will look
        // again — the host owns that fact) AND there's >1 OCR tool for the language.
        val showGear = ocrProvenance != null && host?.canReOcr() == true &&
            OcrModelManager.availableBackends(requireContext(), ocrProvenance.sourceLangId).size > 1
        tvStatus.setNoTextStatus(
            message,
            showGear,
            onLanguageTap = { host?.onChangeLanguageRequested(true) },
            onGearTap = { ocrProvenance?.let { showOcrPicker(it.sourceLangId, it.engineToken) } },
        )
        tvStatusHint.visibility = if (showHint) View.VISIBLE else View.GONE
        tvLiveHint.isGone = true
        statusContainer.isVisible = true
        resultsContent.isGone = true
    }

    /** True iff the activity is currently showing a translation result
     *  (vs status/error/translating). View-state helper for the host. */
    val isShowingResults: Boolean
        get() = view != null && vm.result.value is ResultState.Ready

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
            binder.setShowOnScreenAvailable(true)
            binder.setShowOnScreenToggled(liveState)
        } else {
            binder.setShowOnScreenAvailable(
                currentOnScreenBoxes != null && host?.supportsShowOnScreen() == true
            )
            binder.setShowOnScreenToggled(host?.isResultBoxesShownOnScreen() == true)
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
            maybeRequestDeferredCompletion(force = true)
        }
        // Ownership flipped synchronously (or the show was refused); the
        // refresh reads whichever reality landed.
        refreshShowOnScreen()
    }

    /** Deferred-translation trigger funnel (mirror of the over-game panel's
     *  maybeCompleteDeferred): ask the host to run the skipped translation
     *  when the bound Ready result still carries a pending AND either the
     *  translation section is visible or [force] — a consumer needs the
     *  translation regardless of the section's visibility (on-screen boxes,
     *  an Anki flow). The host is the single completion owner and guards
     *  against duplicate triggers. */
    private fun maybeRequestDeferredCompletion(force: Boolean = false) {
        if (currentReady()?.pendingTranslation == null) return
        if (prefs.hideTranslationSection && !force) return
        host?.completeDeferredTranslation()
    }

    private companion object {
        const val WORD_DIVIDER_TAG = "pt_word_divider"
        /** Word-cell text-size factor for the results list — a notch below
         *  [WordResultCell.DEFAULT_SCALE] (the "large" factor reserved for the
         *  full-screen dictionary results page) so the rows read denser here. */
        const val WORD_CELL_SCALE = 1.0f
    }

    /** 1dp ptDivider line inset from the start by pt_row_h_padding, matching
     *  `settings_row_divider` for word rows inside the Words card. */
    private fun inflateWordDivider(): View {
        val ctx = requireContext()
        val dp1 = ctx.resources.displayMetrics.density.toInt().coerceAtLeast(1)
        return View(ctx).apply {
            tag = WORD_DIVIDER_TAG
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp1
            ).apply {
                marginStart = ctx.resources.getDimensionPixelSize(R.dimen.pt_row_h_padding)
            }
            setBackgroundColor(ctx.themeColor(R.attr.ptDivider))
        }
    }

    /**
     * Shrink translation and original text so each tries to fit within half the
     * visible scroll area (the binder owns the per-view shrink).
     */
    private fun fitTextSizes() {
        val scrollHeight = resultsContent.height.takeIf { it > 0 } ?: return
        val halfHeight = scrollHeight / 2
        // Fit the source to its PLAIN text: ruby lands before the Ready fit, and the
        // scroll absorbs its extra height, so measuring ruby here would shrink the
        // source font the moment the translation lands (it must stay put). See
        // [TranslationSectionBinder.fitText].
        binder.fitText(translationTargetPx = halfHeight, sourceTargetPx = halfHeight, sourceMeasuresRuby = false)
    }

    /**
     * Reveal the results card with its text already sized: hide it, fit the
     * source + translation to their halves once laid out, then position the
     * scroll and show. Fitting BEFORE the first paint is why the source doesn't
     * visibly resize when the translation later lands — Translating and Ready
     * both size it through this one path, so they can't drift apart.
     *
     * [positionScroll] runs in a nested post so it measures the *post-fit*
     * layout (fitText's shrink is a relayout deferred behind the traversal's
     * sync barrier): the Ready anchor restore needs settled geometry, and a
     * scroll-to-top is harmless to defer.
     */
    private fun revealResultsContentFitted(generation: Int, positionScroll: () -> Unit) {
        resultsContent.visibility = View.INVISIBLE
        resultsContent.post {
            // A newer render (a result update, or a switch to status/error/idle
            // that already hid the results) supersedes this reveal — bail so we
            // don't resurrect stale content over the status screen. The newer
            // render owns visibility from here.
            if (view == null || generation != renderGeneration) return@post
            fitTextSizes()
            resultsContent.post {
                if (view == null || generation != renderGeneration) return@post
                positionScroll()
                resultsContent.isVisible = true
            }
        }
    }

    /** A view to keep visually pinned across a result re-render, plus its
     *  pixel offset from the top of the scroll viewport when captured. */
    private class ScrollAnchor(val view: View, val offsetFromViewportTop: Int)

    /**
     * Snapshot the view sitting at the top of the results viewport so a
     * re-render that changes the height of the translation/source cards
     * restores the user to the same *content*, not the same raw pixel offset
     * (which would drift as the growing translation card pushes rows down).
     *
     * Candidates run top-to-bottom: each result block, plus every word row so a
     * deep scroll into the list anchors on the right row. The most specific
     * (largest top) block straddling the viewport top wins; if the top sits in
     * a gap, the first block below it is used. Null when there's nothing to
     * anchor on.
     */
    private fun captureScrollAnchor(): ScrollAnchor? {
        val content = resultsContent.getChildAt(0) as? ViewGroup ?: return null
        val scrollY = resultsContent.scrollY
        var straddler: View? = null
        var straddlerTop = Int.MIN_VALUE
        var firstBelow: View? = null
        var firstBelowTop = Int.MAX_VALUE
        fun consider(v: View) {
            if (!v.isVisible || v.height == 0) return
            val top = resultsContent.contentTopOf(v)
            if (top <= scrollY && scrollY < top + v.height) {
                if (top > straddlerTop) { straddler = v; straddlerTop = top }
            } else if (top >= scrollY && top < firstBelowTop) {
                firstBelow = v; firstBelowTop = top
            }
        }
        for (i in 0 until content.childCount) consider(content.getChildAt(i))
        for (i in 0 until mainWordsContainer.childCount) consider(mainWordsContainer.getChildAt(i))
        val anchor = straddler ?: firstBelow ?: return null
        return ScrollAnchor(anchor, resultsContent.contentTopOf(anchor) - scrollY)
    }

    /** Re-scroll so [anchor]'s view returns to the viewport offset it had when
     *  captured, measured against the now-settled layout. No-op if the anchored
     *  view was detached by the re-render. */
    private fun restoreScrollAnchor(anchor: ScrollAnchor) {
        if (anchor.view.parent == null) {
            resultsContent.scrollToTopSilently(scrollListener)
            return
        }
        val target = resultsContent.contentTopOf(anchor.view) - anchor.offsetFromViewportTop
        resultsContent.restoreScrollSilently(target, scrollListener)
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
     *  the word) and own their AnkiDroid-installed gate. Shared by [launchWordAnki]
     *  (lens/popup, resolves an entry) and [launchWordAnkiFromRow] (result cell,
     *  reads a RowState). */
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
        maybeRequestDeferredCompletion(force = true)
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
                        val msgRes = sendResult.mediaShortfallRes()
                            ?: ankiAddedSuccessRes(CardMode.SENTENCE)
                        Toast.makeText(appCtx, msgRes, Toast.LENGTH_SHORT).show()
                        refreshWordBadges()
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
                        val msgRes = result.mediaShortfallRes() ?: ankiAddedSuccessRes(mode)
                        Toast.makeText(appCtx, msgRes, Toast.LENGTH_SHORT).show()
                        refreshWordBadges()
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
        maybeRequestDeferredCompletion(force = true)
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

    private fun onOriginalTapped(offset: Int) {
        dismissFurigana()
        // Find which word span the tap falls in
        val span = wordSpans.firstOrNull { offset in it.first } ?: return
        val lookupForm = span.second
        val reading = span.third

        // Look up in dictionary and show the floating popup
        val ctx = context ?: return
        val activity = activity ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val appCtx = ctx.applicationContext
                // Shared phrase-aware resolution + tier branching (parity with
                // the over-game capture panel — both surfaces resolve
                // identically here). The popup is the tapped unit's; the
                // related units — a containing multi-word expression (Latin)
                // or a fused expression's member words (JA) — ride along as
                // the lens's secondary sections.
                val resolvedAt = SourceWordLookup.resolveAt(
                    appCtx, tvOriginal.text?.toString().orEmpty(), span.first.first, lookupForm, reading,
                )
                val resolved = resolvedAt.word
                val phrase = resolvedAt.phrase
                val secondaries = phrase?.let { listOf(it) } ?: resolvedAt.members
                val word = resolved.word
                val popupReading = resolved.reading
                val popupLabel = resolved.label
                val lensData = resolved.data
                val entry = resolved.entry

                // Calculate position: center on the tapped word, above it
                val layout = tvOriginal.layout ?: return@launch
                val lineStart = layout.getLineForOffset(span.first.first)
                val xStart = layout.getPrimaryHorizontal(span.first.first)
                val xEnd = layout.getPrimaryHorizontal(span.first.last + 1)
                val wordCenterX = ((xStart + xEnd) / 2).toInt() + tvOriginal.paddingLeft
                val lineTop = layout.getLineTop(lineStart) - tvOriginal.scrollY + tvOriginal.paddingTop
                val lineH = layout.getLineBottom(lineStart) - layout.getLineTop(lineStart)

                val loc = IntArray(2)
                tvOriginal.getLocationOnScreen(loc)
                val screenX = loc[0] + wordCenterX

                val dm = resources.displayMetrics
                // Anchor on the tapped line's top edge — paired with
                // [anchorHeight] = lineH, the lens lands cleanly above
                // the line when there's room and cleanly below the
                // line when it has to flip. Passing center + height=0
                // (the drag-flow default) lands the flipped lens on
                // top of the line itself.
                val anchorY = loc[1] + lineTop
                dismissWordPopup()
                val canOpen = entry != null
                val displayEntry = entry
                wordLens = MagnifierLens(
                    activity,
                    activity.windowManager,
                    android.view.Display.DEFAULT_DISPLAY,
                ).apply {
                    if (canOpen) {
                        onOpenTap = {
                            dismissWordPopup()
                            host?.onInteraction()
                            val ready = currentReady()
                            val wr = currentSettledRows()?.toLegacyMap() ?: emptyMap()
                            host?.onWordTapped(
                                word, popupReading,
                                ready?.screenshotPath,
                                ready?.originalText,
                                ready?.translatedText,
                                wr,
                            )
                        }
                    }
                    if (secondaries.isNotEmpty()) {
                        // Secondary-section drill-in (containing phrase or
                        // member words): same detail route as the tapped
                        // unit — the sheet re-looks the string up, and a
                        // multi-word key round-trips it unchanged.
                        onSecondaryOpenTap = { i ->
                            secondaries.getOrNull(i)?.let { sec ->
                                dismissWordPopup()
                                host?.onInteraction()
                                val ready = currentReady()
                                val wr = currentSettledRows()?.toLegacyMap() ?: emptyMap()
                                host?.onWordTapped(
                                    sec.word, sec.reading,
                                    ready?.screenshotPath,
                                    ready?.originalText,
                                    ready?.translatedText,
                                    wr,
                                )
                            }
                        }
                    }
                    // Tap opens the editable review sheet (default).
                    // Long-press is the headless one-tap shortcut —
                    // documented by the pro-tip footer in Settings.
                    onAnkiTap = {
                        host?.onInteraction()
                        launchWordAnki(activity, word, popupReading, displayEntry)
                    }
                    onAnkiLongPress = {
                        host?.onInteraction()
                        oneTapWordFromPopup(activity, word, popupReading, displayEntry)
                    }
                    // onDismiss is the single funnel for every teardown path
                    // (tap-outside, LensSpeakChip's no-engine action,
                    // dismissWordPopup), so speak-chip + lens cleanup lives
                    // here, not only in dismissWordPopup.
                    onDismiss = {
                        binder.setWordHighlight(null)
                        wordSpeakChip?.release()
                        wordSpeakChip = null
                        wordLens = null
                    }
                }
                wordSpeakChip = wordLens?.let { lens ->
                    LensSpeakChip(
                        lens,
                        viewLifecycleOwner.lifecycleScope,
                        TtsAlertTarget.InActivity(activity),
                    ) { LensSpeakChip.Request(word, prefs.sourceLangId, reading = popupReading) }
                }
                binder.setWordHighlight(span.first)
                wordLens?.show(
                    screenX, anchorY,
                    dm.widthPixels, dm.heightPixels,
                    anchorHeight = lineH,
                )
                if (secondaries.isNotEmpty()) {
                    // Split body: tapped unit (pill identity) + the related
                    // units — containing phrase above it (Latin) or member
                    // words below it (JA) — each with its own drill-in. The
                    // deck back-fill rebinds the SPLIT shape so it can't
                    // collapse the secondary sections.
                    val secondarySections = secondaries.map { LensSection(it.data, it.label, opens = true) }
                    val secondariesOnTop = phrase != null
                    wordLens?.setSplitDefinitions(
                        LensSection(lensData, popupLabel, opens = canOpen),
                        secondarySections, secondariesOnTop,
                    )
                    wordLens?.let { lens ->
                        maybeUpdateLensDecks(lens, lensData, word) { updated ->
                            lens.setSplitDefinitions(
                                LensSection(updated, popupLabel, opens = canOpen),
                                secondarySections, secondariesOnTop,
                            )
                        }
                    }
                } else {
                    wordLens?.setDefinitions(lensData, popupLabel)
                    wordLens?.let { lens ->
                        maybeUpdateLensDecks(lens, lensData, word) { updated ->
                            lens.setDefinitions(updated, popupLabel)
                        }
                    }
                }
                wordLens?.makeInteractive()
            } catch (_: Exception) {}
        }
    }

    private var wordLens: MagnifierLens? = null
    private var wordSpeakChip: LensSpeakChip? = null

    private fun dismissWordPopup() {
        // dismiss() fires the lens's onDismiss, which releases the speak chip
        // and clears wordLens / wordSpeakChip.
        wordLens?.dismiss()
    }

    private fun showFurigana(range: IntRange, reading: String) {
        val ctx = context ?: return
        val layout = tvOriginal.layout ?: return
        val textLen = tvOriginal.text?.length ?: return
        val dm = resources.displayMetrics
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm).toInt()

        val bgColor = ctx.themeColor(R.attr.ptCard)
        val arrowW = dp(12f)
        val arrowH = dp(6f)

        val cornerR = dp(6f).toFloat()
        val tv = TextView(ctx).apply {
            text = reading
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ctx.themeColor(R.attr.ptText))
            setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = cornerR
            }
            elevation = dp(4f).toFloat()
        }

        // Small triangle arrow pointing down
        val arrowView = object : View(ctx) {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
            private val path = android.graphics.Path()
            override fun onDraw(canvas: android.graphics.Canvas) {
                path.rewind()
                path.moveTo(0f, 0f)
                path.lineTo(width.toFloat(), 0f)
                path.lineTo(width / 2f, height.toFloat())
                path.close()
                canvas.drawPath(path, paint)
            }
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            addView(tv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(arrowView, LinearLayout.LayoutParams(arrowW, arrowH).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
        }

        val popup = PopupWindow(container, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            isOutsideTouchable = true
            setOnDismissListener { furiganaPopup = null }
        }
        furiganaPopup = popup

        // Position above the tapped word, centered horizontally
        val safeEnd = (range.last + 1).coerceAtMost(textLen)
        val startLine = layout.getLineForOffset(range.first)
        val startX = layout.getPrimaryHorizontal(range.first)
        val endX = layout.getPrimaryHorizontal(safeEnd)
        val midX = ((startX + endX) / 2).toInt()
        val lineTop = layout.getLineTop(startLine)

        // Measure popup to center it
        container.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popupW = container.measuredWidth
        val popupH = container.measuredHeight

        val loc = IntArray(2)
        tvOriginal.getLocationOnScreen(loc)
        val anchorX = loc[0] + tvOriginal.totalPaddingLeft + midX - popupW / 2
        val anchorY = loc[1] + tvOriginal.totalPaddingTop + lineTop - tvOriginal.scrollY - popupH

        popup.showAtLocation(tvOriginal, Gravity.NO_GRAVITY, anchorX.coerceAtLeast(0), anchorY.coerceAtLeast(0))
    }

    private fun dismissFurigana() {
        furiganaPopup?.dismiss()
        furiganaPopup = null
    }

    fun setLiveHintText(text: CharSequence) {
        if (view != null) tvLiveHint.text = text
    }

    /** Returns the displayed original text (with OCR line breaks preserved). */
    fun getDisplayedOriginalText(): String =
        if (view != null) tvOriginal.text?.toString() ?: "" else ""

    // ── Word lookups (rendering only — pipeline lives in VM) ─────────────

    /** Observation-driven render of [vm.wordLookups]. The pipeline
     *  itself runs on [viewModelScope] inside the VM so rotation
     *  mid-lookup preserves progress; this method just mirrors the
     *  current state into the views. */
    private fun renderWordLookups(state: WordLookupsState) {
        if (view == null) return
        when (state) {
            is WordLookupsState.Idle -> {
                tvMainWordsLoading.isGone = true
                tvNoWords.isGone = true
                mainWordsContainer.removeAllViews()
                wordSpans.clear()
            }
            is WordLookupsState.Loading -> {
                dismissFurigana()
                dismissWordPopup()
                mainWordsContainer.removeAllViews()
                wordSpans.clear()
                tvMainWordsLoading.isVisible = true
                tvMainWordsLoading.text = getString(R.string.words_loading)
                tvNoWords.isGone = true
            }
            is WordLookupsState.Settled -> {
                renderWordRows(state.rows)
                recomputeWordSpans(state.tokenSpans, state.lookupToReading)
                // Furigana is NOT applied here: it's driven by bindSource in
                // renderResult, so it paints with the source text (during the
                // Translating placeholder), not after this heavier lookup settles.
                tvMainWordsLoading.isGone = true
                tvNoWords.visibility = if (state.rows.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun renderWordRows(rows: List<RowState>) {
        mainWordsContainer.removeAllViews()
        if (rows.isEmpty()) return
        val cellsByWord = HashMap<String, MutableList<WordResultCell>>()
        rows.forEachIndexed { idx, rowState ->
            if (idx > 0) mainWordsContainer.addView(inflateWordDivider())
            val cell = WordResultCell(requireContext())
            bindWordCell(cell, rowState)
            mainWordsContainer.addView(cell)
            cellsByWord.getOrPut(rowState.displayWord) { mutableListOf() }.add(cell)
        }
        lastRenderedCells = cellsByWord
        loadAnkiDeckBadges(rows.map { it.displayWord }, cellsByWord)
    }

    override fun onResume() {
        super.onResume()
        // Deck membership can change while we're away (a card added here, in the
        // review sheet, or in AnkiDroid). Re-evaluate so badges aren't stuck on
        // the cached pre-add state.
        refreshWordBadges()
    }

    /** Clears the per-word cache and re-queries deck membership for the
     *  currently-rendered rows, updating each badge in place. No-op until the
     *  list is built. */
    private fun refreshWordBadges() {
        if (!this::mainWordsContainer.isInitialized || mainWordsContainer.isEmpty()) return
        val cells = lastRenderedCells
        if (cells.isEmpty()) return
        ankiDecksByWord.clear()
        val anki = AnkiManager(requireContext())
        if (!anki.isAnkiDroidInstalled() || !anki.hasPermission()) {
            cells.values.flatten().forEach { it.updateAnkiDecks(emptyList()) }
            return
        }
        val words = cells.keys.toList()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { anki.decksByWord(words) }
            if (!isAdded) return@launch
            for ((word, list) in cells) {
                val decks = result[word].orEmpty()
                ankiDecksByWord[word] = decks
                list.forEach { it.updateAnkiDecks(decks) }
            }
        }
    }

    /** Batched "already in Anki" lookup for the words list. Caches results
     *  (shared with the in-app lens) and re-renders each matching cell's body
     *  so its meta row carries the deck pill. Gated + silent; words already in
     *  [ankiDecksByWord] were applied during bind, so only the rest queried. */
    private fun loadAnkiDeckBadges(
        words: List<String>,
        cellsByWord: Map<String, List<WordResultCell>>,
    ) {
        val anki = AnkiManager(requireContext())
        if (!anki.isAnkiDroidInstalled() || !anki.hasPermission()) return
        val uncached = words.distinct().filter { it !in ankiDecksByWord }
        if (uncached.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { anki.decksByWord(uncached) }
            if (!isAdded) return@launch
            for (w in uncached) {
                val decks = result[w].orEmpty()
                ankiDecksByWord[w] = decks
                if (decks.isEmpty()) continue
                cellsByWord[w]?.forEach { it.updateAnkiDecks(decks) }
            }
        }
    }

    /** In-app word lens counterpart: once decks are known, re-render the lens
     *  body so its meta row carries the same deck pill. Reuses the words-list
     *  cache and no-ops when the lens has since been dismissed/replaced. */
    private fun maybeUpdateLensDecks(
        lens: MagnifierLens,
        base: WordDefinitionData,
        word: String,
        // The caller owns the rebind shape — single vs split body — so the
        // deck back-fill can't collapse a split lens to a single section.
        rebind: (WordDefinitionData) -> Unit,
    ) {
        ankiDecksByWord[word]?.let { cached ->
            if (cached.isNotEmpty()) rebind(base.copy(ankiDecks = cached))
            return
        }
        val anki = AnkiManager(requireContext())
        if (!anki.isAnkiDroidInstalled() || !anki.hasPermission()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val decks = withContext(Dispatchers.IO) {
                anki.decksByWord(listOf(word))[word].orEmpty()
            }
            if (!isAdded) return@launch
            ankiDecksByWord[word] = decks
            if (decks.isEmpty() || wordLens !== lens) return@launch
            rebind(base.copy(ankiDecks = decks))
        }
    }

    private fun bindWordCell(cell: WordResultCell, rowState: RowState) {
        // Any already-known Anki decks (cache / re-render) render immediately;
        // uncached words are filled in by renderWordRows' batched query.
        val data = WordDefinitionData(
            word = rowState.displayWord,
            reading = rowState.reading.ifEmpty { null },
            senses = rowState.senses,
            freqScore = rowState.freqScore,
            isCommon = rowState.isCommon,
            ankiDecks = ankiDecksByWord[rowState.displayWord].orEmpty(),
            pitch = rowState.pitch,
            frequencies = rowState.frequencies,
            readingRows = rowState.readingRows,
        )
        cell.bind(
            data = data,
            scale = WORD_CELL_SCALE,
            inflectedForms = rowState.inflectedForms,
            onCellTap = {
                host?.onInteraction()
                val ready = currentReady()
                val wr = currentSettledRows()?.toLegacyMap() ?: emptyMap()
                host?.onWordTapped(
                    rowState.displayWord,
                    rowState.reading.ifEmpty { null },
                    ready?.screenshotPath,
                    ready?.originalText,
                    ready?.translatedText,
                    wr,
                )
            },
            onSpeak = { speakWordFromCell(cell, rowState) },
            onAnki = {
                host?.onInteraction()
                launchWordAnkiFromRow(rowState)
            },
        )
    }

    /** Speak a result cell's word, driving that cell's own spinner. Each cell
     *  owns its in-flight [WordResultCell.speakJob] so concurrent taps on
     *  different rows don't clobber one another. */
    private fun speakWordFromCell(cell: WordResultCell, rowState: RowState) {
        if (cell.speakJob?.isActive == true) return
        val activity = activity ?: return
        cell.speakJob = viewLifecycleOwner.lifecycleScope.launch {
            cell.setSpeakLoading(true)
            try {
                speakWord(
                    TtsAlertTarget.InActivity(activity),
                    LensSpeakChip.Request(
                        rowState.displayWord,
                        prefs.sourceLangId,
                        reading = rowState.reading.ifEmpty { null },
                    ),
                )
            } finally {
                cell.setSpeakLoading(false)
            }
        }
    }

    /** Per-word Anki add from a result cell. Mirrors [launchWordAnki] but
     *  sources POS / definition straight from the [RowState] so the cell
     *  needn't re-resolve the dictionary entry. Tap opens the editable review
     *  sheet (the cell exposes no long-press one-tap shortcut). */
    private fun launchWordAnkiFromRow(rowState: RowState) {
        val activity = activity ?: return
        val ankiManager = AnkiManager(activity)
        if (!ankiManager.isAnkiDroidInstalled()) {
            showAnkiNotInstalledDialog(activity)
            return
        }
        launchWordAnkiIntent(
            activity, rowState.displayWord,
            reading = rowState.reading.takeIf { it.isNotEmpty() && it != rowState.displayWord } ?: "",
            pos = rowState.ankiPos, definition = rowState.meaning, freqScore = rowState.freqScore,
        )
    }

    /** Derive view-side word spans from the VM's per-occurrence
     *  tokenSpans plus the displayed text (which may have OCR
     *  newlines inserted that aren't in [TranslationResult.originalText]).
     *  The JMdict-resolved reading wins, then surface-keyed reading,
     *  then the tokenizer's own reading (Kuromoji) as a last fallback
     *  so out-of-dictionary tokens still carry a reading into the
     *  word-tap popup. */
    private fun recomputeWordSpans(
        tokenSpans: List<com.playtranslate.language.TokenSpan>,
        lookupToReading: Map<String, String>,
    ) {
        wordSpans.clear()
        val displayedText = tvOriginal.text?.toString() ?: return
        wordSpans.addAll(SourceWordLookup.computeSpans(displayedText, tokenSpans, lookupToReading))
    }

}
