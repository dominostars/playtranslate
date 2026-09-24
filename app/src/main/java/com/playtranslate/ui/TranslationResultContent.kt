package com.playtranslate.ui

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.model.OcrProvenance
import com.playtranslate.model.TranslationResult
import com.playtranslate.overlay.OverlayHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The results page's content — `fragment_translation_result.xml` driven by
 * a [TranslationResultViewModel] — as a host-agnostic renderer: the status
 * screen, the source + target sections ([TranslationSectionBinder]), the
 * Words card ([WordRowsBinder]), the tap-a-word lens ([SourceTextLens]),
 * the in-window popovers ([popovers]: the text-size picker, the section
 * headers' ⋯ menus), and the render funnel that ties them to the VM's
 * states (fit-before-reveal, scroll preservation across a translation
 * update, the deferred-translation request). The in-app
 * [TranslationResultFragment] and the floating workspace's
 * [WorkspaceSentencePage] are shells over this, each supplying what only
 * it knows through [Host]: where the lens's window lives, what its chips
 * do, what a word tap opens, the Anki entries, editing, the OCR and
 * language pickers, the Clear action, the show-on-screen toggle, and who
 * completes a deferred translation. Same constraints as every other
 * shared binder: plain views, ids from the shared layout.
 *
 * Render rules the funnel owns (pinned here so neither shell re-derives
 * them): a Translating placeholder always starts at the top and records
 * its source; a Ready whose source matches the last render keeps the
 * user's place (a Translating→Ready promotion, a backend re-translate),
 * anchored on the CONTENT under the viewport top, not a raw pixel offset
 * (a growing translation card would drift it); a Ready with a new source
 * resets to the top; leaving the results (status / idle / error) drops the
 * anchor. The reveal is asynchronous (hide → fit → show across two posts),
 * so every posted step bails when a newer render has moved the generation
 * on — a fast Translating→Status transition must not resurrect stale
 * results over the status screen.
 */
class TranslationResultContent(
    val root: View,
    private val ctx: Context,
    private val prefs: Prefs,
    private val vm: TranslationResultViewModel,
    private val host: Host,
) {
    interface Host {
        /** The host view tree is still live — guards every async re-entry. */
        val isAlive: Boolean

        /** Where the sections' async work, the words' badge queries and
         *  the lens's resolves run (view-scoped in-app, page-scoped over
         *  the game). */
        val scope: CoroutineScope

        val ttsAlertTarget: TtsAlertTarget

        // ── The lens's window ──
        /** Null: the lens attaches to the activity window; else the
         *  backend's overlay host. */
        val lensOverlayHost: OverlayHost?
        val lensWindowManager: WindowManager
        val lensDisplayId: Int
        fun screenSize(): Point

        /** Whether a tapped unit without a dictionary entry still opens
         *  (see [SourceTextLens]). */
        val opensWithoutEntry: Boolean

        /** Wire a freshly built lens's chips for this tap. */
        fun wireLensActions(lens: MagnifierLens, resolved: SourceWordLookup.ResolvedAt)

        /** A word row's body tap: open that word's detail. */
        fun onWordTapped(word: String, reading: String?)

        /** Any user interaction (live-mode hosts pause on it). */
        fun onInteraction() {}

        /** A user scroll of the results (never the funnel's own resets). */
        fun onUserScrolled() {}

        /** Every render, before the state is drawn — the in-app page
         *  reconciles its show-on-screen boxes here. */
        fun onRender(state: ResultState) {}

        /** Hold point before a settled word list is built (a host with an
         *  entrance transition suspends until it has settled). */
        suspend fun awaitEnterSettled() {}

        /** Whether a switch of OCR tool can act on the bound result (the
         *  host still holds the capture). */
        fun canReOcr(): Boolean = false

        /** The Clear row under the results. */
        val showsClearAction: Boolean get() = false
        fun onClear() {}

        /** The idle status's "hold to start" hint line. */
        val showsStartHint: Boolean get() = false

        /** Source editing: false hides the Edit button. */
        val editAvailable: Boolean get() = false
        fun onEditRequested() {}

        /** The OCR picker for [provenance] (the attribution row's gear and
         *  the no-text status's). Only reached when [canReOcr]. */
        fun onChooseOcr(provenance: OcrProvenance) {}

        /** A language header tap (and the no-text status's language name). */
        fun onChangeLanguage(isSource: Boolean) {}

        /** The section headers' Anki button: tap = review, long-press =
         *  one-tap. */
        fun onAddToAnki() {}
        fun onAnkiOneTap() {}

        /** The target header's show-on-screen toggle (in-app only; the
         *  host gates its visibility through [binder]). */
        fun onShowOnScreenTapped() {}

        /** The bound Ready result carries a deferred translation and a
         *  consumer needs it now — see [requestDeferredCompletion]. */
        fun completeDeferredTranslation() {}
    }

    val scrollView: ScrollView = root.findViewById(R.id.resultsContent)
    private val statusContainer: View = root.findViewById(R.id.statusContainer)
    private val statusText: TextView = root.findViewById(R.id.tvStatus)
    private val statusHint: TextView = root.findViewById(R.id.tvStatusHint)
    private val liveHint: TextView = root.findViewById(R.id.tvLiveHint)
    private val actionButtons: View = root.findViewById(R.id.resultActionButtons)

    /** The page's one in-window popover (the text-size picker, a header's
     *  ⋯ menu), floating over the results inside the layout's root
     *  FrameLayout — a child of the surface, never a sibling window. Shells
     *  route back / B to it first. */
    val popovers = PopoverHost(root as FrameLayout)

    val binder: TranslationSectionBinder =
        TranslationSectionBinder(root, ctx, prefs, host.scope, host.ttsAlertTarget, popovers)
    val wordRows: WordRowsBinder
    val sourceLens: SourceTextLens

    /** Bumped on every [render]; see the class doc's reveal rule. */
    private var renderGeneration = 0

    /** Bumped per word-lookup render so a settled build held at the
     *  enter-settle gate lands only if no newer lookup state arrived. */
    private var lookupGeneration = 0

    /** Source text of the last Translating/Ready render — the scroll
     *  preservation key (class doc). Null after status/idle/error. */
    private var lastRenderedSourceText: String? = null

    /** Reified so the funnel's own resets can detach + reattach it — the
     *  framework's onScrollChanged for our scrollTo would otherwise read as
     *  user intent and pause live mode the instant a fresh result lands. */
    private val scrollListener = View.OnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
        if (scrollY != oldScrollY) {
            sourceLens.dismiss()
            // NOT the popovers: their scrim makes the scroll untouchable
            // while one is open, so a scroll seen here is our OWN re-fit
            // reflowing the cards (or a stick scroll) — dismissing on it
            // would close the size picker out from under the drag that
            // caused it.
            host.onUserScrolled()
        }
    }

    init {
        wordRows = WordRowsBinder(
            root, ctx, prefs,
            object : WordRowsBinder.Host {
                override val isAlive: Boolean get() = host.isAlive
                override val scope: CoroutineScope get() = host.scope
                override val ttsAlertTarget: TtsAlertTarget get() = host.ttsAlertTarget
                override fun onInteraction() = host.onInteraction()
                override fun onWordTapped(row: RowState) =
                    host.onWordTapped(row.displayWord, row.reading.ifEmpty { null })
            },
        )
        sourceLens = SourceTextLens(
            ctx, host.lensWindowManager, host.lensDisplayId, host.lensOverlayHost,
            scope = host.scope,
            ttsAlertTarget = host.ttsAlertTarget,
            binder = binder,
            screenSize = { host.screenSize() },
            showAnkiChip = { true },
            opensWithoutEntry = host.opensWithoutEntry,
            decks = wordRows.deckSource,
            wireActions = { lens, resolved -> host.wireLensActions(lens, resolved) },
        )
        binder.tvOriginal.onTapAtOffset = { offset -> sourceLens.onTapAtOffset(offset) }
        binder.editAvailable = host.editAvailable
        // Copy / show-hide / furigana toggle / speak live in the binder; the
        // Anki action lives on the section headers (tap = review, long-press
        // = one-tap); editing is the host's.
        binder.setupSectionButtons(
            onEdit = {
                sourceLens.dismiss()
                host.onEditRequested()
            },
            onAddToAnki = { host.onAddToAnki() },
            onAnkiOneTap = { host.onAnkiOneTap() },
        )
        val fontPicker = FontSizeRangePopover(ctx, prefs).apply {
            // fitTextSizes fits each section to half the (unchanged) scroll
            // height, so the sections resize in place and the anchor — the
            // target header's button or ⋯, topmost in the scroll — never
            // moves under the user's finger.
            onRangeChanged = { fitTextSizes() }
        }
        binder.onChooseFontSize = { anchor -> popovers.toggle(fontPicker, anchor) }
        binder.onChooseOcr = {
            currentReady()?.ocrProvenance?.let { host.onChooseOcr(it) }
        }
        binder.onChooseLanguage = { isSource -> host.onChangeLanguage(isSource) }
        binder.setShowOnScreenAction { host.onShowOnScreenTapped() }
        // This vertical page just reflows on an eye toggle (no re-layout
        // work), but REVEALING the translation section on a deferred result
        // must run the translation that was skipped while it was hidden.
        binder.onSectionVisibilityChanged = { requestDeferredCompletion() }
        scrollView.setOnScrollChangeListener(scrollListener)
        root.findViewById<View>(R.id.btnResultClear).setOnClickListener { host.onClear() }
    }

    // ── Reads ────────────────────────────────────────────────────────────

    /** The current Ready result, or null in any other state. */
    fun currentReady(): TranslationResult? = (vm.result.value as? ResultState.Ready)?.result

    /** The settled word lookups, or null while idle/loading. */
    fun settledLookups(): WordLookupsState.Settled? = vm.wordLookups.value as? WordLookupsState.Settled

    /** True iff a translation result is showing (vs status/error/translating). */
    val isShowingResults: Boolean get() = host.isAlive && vm.result.value is ResultState.Ready

    /** The displayed source text (OCR line breaks preserved). */
    fun displayedOriginalText(): String = binder.displayedSourceText()

    fun dismissLens() = sourceLens.dismiss()

    fun setLiveHintText(text: CharSequence) {
        liveHint.text = text
    }

    // ── Render (driven by the VM's states) ───────────────────────────────

    fun render(state: ResultState) {
        if (!host.isAlive) return
        val generation = ++renderGeneration
        host.onRender(state)
        when (state) {
            is ResultState.Idle ->
                showStatus(ctx.getString(R.string.status_idle), showHint = host.showsStartHint)
            is ResultState.Status ->
                showStatus(state.message, state.showHint, state.ocrProvenance)
            is ResultState.Error ->
                showStatus(ctx.getString(R.string.status_error, state.message), showHint = false)
            is ResultState.Translating -> {
                // A placeholder is always a freshly-started translation (drag
                // sentence / edit commit), so reset to top; record the source
                // so the matching Ready promotion preserves the user's scroll.
                lastRenderedSourceText = state.originalText
                binder.bindTranslating(state.segments, state.ocrProvenance)
                wordRows.applyWordsVisibility()
                statusContainer.isGone = true
                actionButtons.isVisible = host.showsClearAction
                // The source text is final the instant the placeholder shows,
                // so fit it now — not when the translation later lands, which
                // would make it visibly resize.
                revealFitted(generation) { scrollToFreshResultStart() }
            }
            is ResultState.Ready -> {
                val result = state.result
                // Keep the user's place when only the translation changed;
                // reset to top only when the source itself changed. The anchor
                // is captured before the binder mutates content, so it reflects
                // what the user was looking at.
                val preserveScroll = result.originalText == lastRenderedSourceText
                val anchor = if (preserveScroll) captureScrollAnchor() else null
                lastRenderedSourceText = result.originalText
                // A blank translation on a Ready result means a re-translate is
                // in flight (an edit commit clears the old translation before
                // the new one lands); the binder shows the "Translating…"
                // placeholder instead of an empty card.
                binder.bindResult(result, canReOcr = host.canReOcr())
                wordRows.applyWordsVisibility()
                statusContainer.isGone = true
                actionButtons.isVisible = host.showsClearAction
                revealFitted(generation) {
                    if (anchor != null) restoreScrollAnchor(anchor) else scrollToFreshResultStart()
                }
                // A deferred result bound while the section is visible (the
                // pref is global and nothing listens for flips) must run its
                // skipped translation now. No-op without a pending.
                requestDeferredCompletion()
            }
        }
    }

    /** Mirror a word-lookup state: the tap spans + the lens follow it
     *  synchronously; the settled rows build after the host's enter-settle
     *  gate (immediate in-app), unless a newer lookup state arrived first. */
    fun renderWordLookups(state: WordLookupsState) {
        if (!host.isAlive) return
        val generation = ++lookupGeneration
        when (state) {
            is WordLookupsState.Idle -> {
                sourceLens.wordSpans = emptyList()
                wordRows.render(state)
            }
            is WordLookupsState.Loading -> {
                sourceLens.dismiss()
                sourceLens.wordSpans = emptyList()
                wordRows.render(state)
            }
            is WordLookupsState.Settled -> {
                // Spans project against the displayed text (which may carry
                // OCR newlines the result's text doesn't).
                sourceLens.wordSpans = SourceWordLookup.computeTapSpans(
                    binder.displayedSourceText(), state.tokenSpans, state.lookupToReading, state.phrases,
                )
                // Furigana is NOT applied here: it's driven by bindSource in
                // render, so it paints with the source text (during the
                // Translating placeholder), not after this heavier lookup settles.
                host.scope.launch {
                    host.awaitEnterSettled()
                    if (!host.isAlive || generation != lookupGeneration) return@launch
                    wordRows.render(state)
                }
            }
        }
    }

    /** Deferred-translation trigger funnel (mirror of the over-game panel's
     *  maybeCompleteDeferred): ask the host to run the skipped translation
     *  when the bound Ready result still carries a pending AND either the
     *  translation section is visible or [force] — a consumer needs the
     *  translation regardless of the section's visibility (on-screen boxes,
     *  an Anki flow). The host is the single completion owner and guards
     *  against duplicate triggers. */
    fun requestDeferredCompletion(force: Boolean = false) {
        if (currentReady()?.pendingTranslation == null) return
        if (prefs.hideTranslationSection && !force) return
        host.completeDeferredTranslation()
    }

    /** Shared status / error / idle layout — single status container,
     *  results hidden. [showHint] gates the "press X to start" hint line
     *  under the message. */
    private fun showStatus(message: String, showHint: Boolean, ocrProvenance: OcrProvenance? = null) {
        // Leaving the results view drops the scroll anchor: the next
        // translation is unrelated content and should land at the top.
        lastRenderedSourceText = null
        // No-text status affordances, each its own tappable span (so tapping
        // one can't trigger the other): the source-language name → source
        // picker (same as the source header); the gear → OCR picker, shown
        // when the switch will actually be acted on (a pinned frame to
        // re-OCR, or a live loop that will look again — the host owns that
        // fact) AND there's >1 OCR tool for the language.
        val showGear = ocrProvenance != null && host.canReOcr() &&
            OcrModelManager.availableBackends(ctx, ocrProvenance.sourceLangId).size > 1
        statusText.setNoTextStatus(
            message,
            showGear,
            onLanguageTap = { host.onChangeLanguage(true) },
            onGearTap = { ocrProvenance?.let { host.onChooseOcr(it) } },
        )
        statusHint.visibility = if (showHint) View.VISIBLE else View.GONE
        liveHint.isGone = true
        statusContainer.isVisible = true
        scrollView.isGone = true
    }

    // ── Fit + reveal + scroll ────────────────────────────────────────────

    /** Shrink translation and original text so each tries to fit within
     *  half the visible scroll area. */
    fun fitTextSizes() {
        val height = scrollView.height.takeIf { it > 0 } ?: return
        binder.fitToViewport(height)
    }

    /**
     * Reveal the results with their text already sized: hide, fit the source
     * + translation to their halves once laid out, then position the scroll
     * and show. Fitting BEFORE the first paint is why the source doesn't
     * visibly resize when the translation later lands — Translating and
     * Ready both size it through this one path. [positionScroll] runs in a
     * nested post so it measures the post-fit layout (fitText's shrink is a
     * relayout deferred behind the traversal's sync barrier).
     */
    private fun revealFitted(generation: Int, positionScroll: () -> Unit) {
        scrollView.visibility = View.INVISIBLE
        scrollView.post {
            // A newer render (a result update, or a switch to status that
            // already hid the results) supersedes this reveal — bail so we
            // don't resurrect stale content over the status screen.
            if (!host.isAlive || generation != renderGeneration) return@post
            fitTextSizes()
            scrollView.post {
                if (!host.isAlive || generation != renderGeneration) return@post
                positionScroll()
                scrollView.isVisible = true
            }
        }
    }

    /** Fresh-result scroll reset: always the top. Deliberately NOT the
     *  hidden-section park the over-game panel does: a page starts at its
     *  top, and a self-inflicted scroll here reads as user intent (in
     *  dual-screen live mode it tripped the scroll listener → pause on every
     *  incoming result). */
    private fun scrollToFreshResultStart() {
        scrollView.scrollToTopSilently(scrollListener)
    }

    /** A view to keep visually pinned across a result re-render, plus its
     *  pixel offset from the top of the scroll viewport when captured. */
    private class ScrollAnchor(val view: View, val offsetFromViewportTop: Int)

    /** Snapshot the view at the top of the viewport so a re-render that
     *  changes the cards' heights restores the same CONTENT. Candidates run
     *  top-to-bottom: each result block, plus every word row so a deep
     *  scroll anchors on the right row. The most specific (largest top)
     *  block straddling the viewport top wins; if the top sits in a gap, the
     *  first block below it is used. Null when there's nothing to anchor on. */
    private fun captureScrollAnchor(): ScrollAnchor? {
        val content = scrollView.getChildAt(0) as? android.view.ViewGroup ?: return null
        val scrollY = scrollView.scrollY
        var straddler: View? = null
        var straddlerTop = Int.MIN_VALUE
        var firstBelow: View? = null
        var firstBelowTop = Int.MAX_VALUE
        fun consider(v: View) {
            if (!v.isVisible || v.height == 0) return
            val top = scrollView.contentTopOf(v)
            if (top <= scrollY && scrollY < top + v.height) {
                if (top > straddlerTop) { straddler = v; straddlerTop = top }
            } else if (top >= scrollY && top < firstBelowTop) {
                firstBelow = v; firstBelowTop = top
            }
        }
        for (i in 0 until content.childCount) consider(content.getChildAt(i))
        val rows = wordRows.container
        for (i in 0 until rows.childCount) consider(rows.getChildAt(i))
        val anchor = straddler ?: firstBelow ?: return null
        return ScrollAnchor(anchor, scrollView.contentTopOf(anchor) - scrollY)
    }

    /** Re-scroll so [anchor]'s view returns to the viewport offset it had,
     *  measured against the now-settled layout. Top if the view is gone. */
    private fun restoreScrollAnchor(anchor: ScrollAnchor) {
        if (anchor.view.parent == null) {
            scrollView.scrollToTopSilently(scrollListener)
            return
        }
        val target = scrollView.contentTopOf(anchor.view) - anchor.offsetFromViewportTop
        scrollView.restoreScrollSilently(target, scrollListener)
    }

    /** Tear down: the lens, the popovers, the sections' speak job, the Words
     *  card's styled renderers. Idempotent. */
    fun release() {
        sourceLens.dismiss()
        popovers.release()
        binder.release()
        wordRows.release()
    }
}

/**
 * Reset scroll to (0, 0) without firing the registered scroll listener —
 * i.e. without making a programmatic reset look like user intent. Detach →
 * scrollTo (synchronous, fires onScrollChanged inline, sees no listener) →
 * reattach. Only safe with the synchronous [ScrollView.scrollTo]; never
 * [ScrollView.smoothScrollTo], which dispatches asynchronously.
 */
private fun ScrollView.scrollToTopSilently(listener: View.OnScrollChangeListener) {
    setOnScrollChangeListener(null)
    scrollTo(0, 0)
    setOnScrollChangeListener(listener)
}

/** Restore a saved scroll [y] without firing the listener — the
 *  preserve-position counterpart to [scrollToTopSilently]. [ScrollView.scrollTo]
 *  clamps to the content range, so an offset that outran a now-shorter
 *  result lands at the bottom rather than in empty space. */
private fun ScrollView.restoreScrollSilently(y: Int, listener: View.OnScrollChangeListener) {
    setOnScrollChangeListener(null)
    scrollTo(0, y)
    setOnScrollChangeListener(listener)
}

/** Top of [descendant] in this ScrollView's content coordinate space —
 *  independent of the current scroll offset (offsetDescendantRectToMyCoords
 *  stops at this view, so it never subtracts our own scrollY). */
private fun ScrollView.contentTopOf(descendant: View): Int {
    val r = Rect(0, 0, descendant.width, descendant.height)
    offsetDescendantRectToMyCoords(descendant, r)
    return r.top
}
