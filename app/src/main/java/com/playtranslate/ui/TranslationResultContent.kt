package com.playtranslate.ui

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.Prefs
import com.playtranslate.OverlayMode
import com.playtranslate.R
import com.playtranslate.ask.AskState
import com.playtranslate.ask.Explanation
import com.playtranslate.deferredCompletionNeeded
import com.playtranslate.model.OcrProvenance
import com.playtranslate.model.TranslationResult
import com.playtranslate.overlay.OverlayHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The results page's content — `fragment_translation_result.xml` driven by
 * a [TranslationResultViewModel] — as a host-agnostic renderer: the status
 * screen, the source + target sections ([TranslationSectionBinder]), the
 * Ask section ([AskSectionBinder]) over the view-model's [AskSession], the
 * Words card ([WordRowsBinder]), the tap-a-word lens ([SourceTextLens]),
 * the in-window popovers ([popovers]: the text-size picker, the section
 * headers' ⋯ menus), and the render funnel that ties them to the VM's
 * states (fit-before-reveal, scroll preservation across a translation
 * update, the deferred-translation request). The in-app
 * [TranslationResultFragment] and the floating workspace's
 * [WorkspaceSentencePage] are shells over this, each supplying what only
 * it knows through [Host]: where the lens's window lives, what its rows'
 * actions do, what a word tap opens, the Anki entries (the floating pill's
 * tap and hold), editing, the OCR and language pickers, the Clear action,
 * the show-on-screen toggle, and who completes a deferred translation.
 * Same constraints as every other shared binder: plain views, ids from the
 * shared layout.
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
 * results over the status screen. The word lookup's two tiers render apart
 * ([WordLookupsState]): the tap spans follow the analysis
 * ([WordLookupsState.Analyzed]) whether or not the Words card is shown, and
 * the dictionary rows are built only on the card's request
 * ([WordRowsBinder.onRowsWanted] → [TranslationResultViewModel.requestWordRows]),
 * so a hidden card costs no row work.
 */
class TranslationResultContent(
    val root: View,
    private val ctx: Context,
    private val prefs: Prefs,
    private val vm: TranslationResultViewModel,
    private val host: Host,
    /** Whether the soft keyboard is up over this page's window
     *  ([keyboardShownOver]). A parameter so a test can raise one. */
    private val keyboardShown: () -> Boolean = { keyboardShownOver(root) },
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

        /** Wire a freshly built lens's actions for this tap. */
        fun wireLensActions(lens: MagnifierLens, resolved: SourceWordLookup.ResolvedAt)

        /** A word row's body tap: open that word's detail. [surface] is the
         *  row's occurrence text, for the detail header's conjugation line;
         *  [lookupForm] and [lookupReading] the key the row was resolved
         *  under, which the detail page resolves with
         *  ([WordDetailBinder.Args.lookupForm]); [explanation] the answer
         *  that stood in the row, about the word in the sentence on show:
         *  the host hands it to the detail page as part of that sentence
         *  (its launch sentence's [WordDetailBinder.Args.explanation], or
         *  its context's [SentenceContext.explanations]), which is where
         *  the page's Anki card reads its note. */
        fun onWordTapped(
            word: String,
            reading: String?,
            surface: String?,
            lookupForm: String,
            lookupReading: String?,
            explanation: Explanation?,
        )

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

        /** The floating Anki pill: a tap opens the review ([onAddToAnki]),
         *  a hold sends the card at once ([onAnkiOneTap]). */
        fun onAddToAnki() {}
        fun onAnkiOneTap() {}

        /** The target header's show-on-screen toggle (in-app only; the
         *  host gates its visibility through [binder]). */
        fun onShowOnScreenTapped() {}

        /** The bound Ready result carries a deferred translation and a
         *  consumer needs it now — see [requestDeferredCompletion]. */
        fun completeDeferredTranslation() {}

        /** The user reached for an Ask question with no LLM set up: show
         *  the alert that says so ([askNeedsLlm]) on this surface, its
         *  button opening Translation services from here. */
        fun onAskNeedsLlm()

        /** The overlay mode the capture's boxes are painted in on the game
         *  screen right now (two screens), or null with none painted. In
         *  Translations they need the deferred translation whatever the
         *  section's eye says ([requestDeferredCompletion]). */
        val captureModeOnScreen: OverlayMode? get() = null

        /**
         * The compact page, for a host that is a small window with a bar of
         * its own (the detached window). The same page and the same code,
         * arranged to spend less: no language headers (their actions are
         * the host's to list, [TranslationSectionBinder.textActions]), the
         * Original and the Translation as plain text with a hairline
         * between, no floating Anki pill (the host offers [onAddToAnki] and
         * [onAnkiOneTap] itself), slim Ask and Words headers, and the texts
         * fitted to the room they actually have, again on every change of
         * the page's size.
         */
        val compact: Boolean get() = false

        /** Show one of the page's own popovers (the text-size picker) for
         *  [anchor]. True when the host showed it itself, which one whose
         *  window can be smaller than the popover does; false leaves it to
         *  the page's in-window host. */
        fun showPopover(content: PopoverContent, anchor: View): Boolean = false
    }

    val scrollView: ScrollView = root.findViewById(R.id.resultsContent)

    /** The sections' layout: the scroll's content, and the owner of
     *  whether Original and Translation are stacked or paired. */
    private val sections: ResultSectionsLayout = root.findViewById(R.id.resultsScrollContent)
    private val statusContainer: View = root.findViewById(R.id.statusContainer)
    private val statusText: TextView = root.findViewById(R.id.tvStatus)
    private val statusHint: TextView = root.findViewById(R.id.tvStatusHint)
    private val liveHint: TextView = root.findViewById(R.id.tvLiveHint)
    private val actionButtons: View = root.findViewById(R.id.resultActionButtons)

    /** The floating Anki pill ([syncAnkiPill] says when it shows). */
    private val ankiPill: FrameLayout = root.findViewById(R.id.btnAnkiPill)

    /** Re-reads the pill's rule after every layout of the window: the
     *  keyboard coming or going lays the window out again, and a keyboard
     *  closed by its own button or Back leaves the question field focused,
     *  so its closing is seen only here. Registered while [root] is
     *  attached. */
    private val keyboardWatch = ViewTreeObserver.OnGlobalLayoutListener {
        if (host.isAlive) syncAnkiPill()
    }

    /** The page's one in-window popover (the text-size picker, a header's
     *  ⋯ menu), floating over the results inside the layout's root
     *  FrameLayout — a child of the surface, never a sibling window. Shells
     *  route back / B to it first. */
    val popovers = PopoverHost(root as FrameLayout)

    val binder: TranslationSectionBinder =
        TranslationSectionBinder(root, ctx, prefs, host.scope, host.ttsAlertTarget, popovers)
    val wordRows: WordRowsBinder
    val sourceLens: SourceTextLens

    /** The Ask section, over the view-model's session. */
    val ask: AskSectionBinder
    private val lookupsRenderer: WordLookupsRenderer

    /** The note a card of the sentence on show starts with
     *  ([sentenceCardNote]): the Ask section's answer, when one stands
     *  (the session's, which is bound to the sentence on show), and the
     *  answers standing in the Words card's rows; "" with none. */
    fun sentenceCardNote(): String =
        sentenceCardNote(vm.ask.state.value as? AskState.Answered, wordRows.explainedWords())

    /** Bumped on every [render]; see the class doc's reveal rule. */
    private var renderGeneration = 0

    /** Source text of the last Translating/Ready render — the scroll
     *  preservation key (class doc). Null after status/idle/error. */
    private var lastRenderedSourceText: String? = null

    /** The state the last [render] bound, so a box fill (a Translating that
     *  differs from it only in its on-screen boxes) is told from a freshly
     *  started translation. */
    private var lastRendered: ResultState? = null

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
                override fun onWordTapped(row: RowState, explanation: Explanation?) =
                    host.onWordTapped(
                        row.displayWord, row.reading.ifEmpty { null }, row.surface,
                        row.lookupForm, row.lookupReading, explanation,
                    )
                override fun onAskNeedsLlm() = host.onAskNeedsLlm()
            },
        )
        // The card asks for its rows whenever it is shown and may need them;
        // the VM no-ops unless an analysis is waiting for its rows.
        wordRows.onRowsWanted = { vm.requestWordRows(ctx.applicationContext) }
        sourceLens = SourceTextLens(
            ctx, host.lensWindowManager, host.lensDisplayId, host.lensOverlayHost,
            scope = host.scope,
            ttsAlertTarget = host.ttsAlertTarget,
            binder = binder,
            screenSize = { host.screenSize() },
            opensWithoutEntry = host.opensWithoutEntry,
            deckBadges = true,
            onAskNeedsLlm = { host.onAskNeedsLlm() },
            wireActions = { lens, resolved -> host.wireLensActions(lens, resolved) },
        )
        lookupsRenderer = WordLookupsRenderer(
            wordRows, sourceLens,
            displayedSourceText = { binder.displayedSourceText() },
            scope = { host.scope },
            isAlive = { host.isAlive },
            awaitEnterSettled = { host.awaitEnterSettled() },
        )
        binder.tvOriginal.onTapAtOffset = { offset -> sourceLens.onTapAtOffset(offset) }
        binder.editAvailable = host.editAvailable
        // Copy / show-hide / furigana toggle / speak live in the binder;
        // editing is the host's; Anki is the floating pill (tap = review,
        // hold = one-tap).
        binder.setupSectionButtons(
            onEdit = {
                sourceLens.dismiss()
                host.onEditRequested()
            },
        )
        PillAnkiButton(ankiPill, onTap = { host.onAddToAnki() }, onHold = { host.onAnkiOneTap() })
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.viewTreeObserver.addOnGlobalLayoutListener(keyboardWatch)
            }

            override fun onViewDetachedFromWindow(v: View) {
                v.viewTreeObserver.removeOnGlobalLayoutListener(keyboardWatch)
            }
        })
        if (root.isAttachedToWindow) root.viewTreeObserver.addOnGlobalLayoutListener(keyboardWatch)
        val fontPicker = FontSizeRangePopover(ctx, prefs).apply {
            // fitTextSizes fits each section against the (unchanged) scroll
            // height, so the sections resize in place and the anchor never
            // moves under the user's finger: it is the button or ⋯ of the
            // topmost header that still has a card (the Translation's, or
            // the Original's while the Translation is hidden, whose bare
            // header above it keeps its height).
            onRangeChanged = { fitTextSizes() }
        }
        binder.onChooseFontSize = { anchor ->
            if (!host.showPopover(fontPicker, anchor)) popovers.toggle(fontPicker, anchor)
        }
        binder.onChooseOcr = {
            currentReady()?.ocrProvenance?.let { host.onChooseOcr(it) }
        }
        binder.onChooseLanguage = { isSource -> host.onChangeLanguage(isSource) }
        binder.setShowOnScreenAction { host.onShowOnScreenTapped() }
        ask = AskSectionBinder(
            root, ctx, prefs, vm.ask, popovers,
            object : AskSectionBinder.Host {
                override fun onInteraction() = host.onInteraction()
                override fun onLlmNeeded() = host.onAskNeedsLlm()
                // The pill makes way while the question is typed in.
                override fun onFieldFocusChanged(focused: Boolean) = syncAnkiPill()
            },
        )
        // The session is the view-model's and outlives this page: draw
        // whatever it holds now, and every change after.
        host.scope.launch { vm.ask.state.collect { if (host.isAlive) ask.render(it) } }
        // The page just reflows on an eye toggle (no re-layout work), but
        // REVEALING the translation section on a deferred result must run
        // the translation that was skipped while it was hidden, and an
        // answer on show says whether it may give a hidden translation away.
        binder.onSectionVisibilityChanged = {
            requestDeferredCompletion()
            ask.syncSpoilerWarning()
        }
        scrollView.setOnScrollChangeListener(scrollListener)
        // A rotation or a resized window re-arranges the sections (their
        // layout follows its width) without this page being rebuilt, and
        // the text was fitted to the old arrangement: fit it again once the
        // new one has laid out. Width only: a height change is the keyboard,
        // which must not resize the text being asked about. The first
        // layout of a reveal is not this path (the results are still
        // INVISIBLE; [revealFitted] fits them).
        //
        // The compact page fits again on a height change too: its window is
        // resized by hand, and a keyboard moves that window instead of
        // resizing it.
        scrollView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val resized = right - left != oldRight - oldLeft ||
                (host.compact && bottom - top != oldBottom - oldTop)
            if (resized && scrollView.isVisible) {
                scrollView.post { if (host.isAlive) fitTextSizes() }
            }
        }
        root.findViewById<View>(R.id.btnResultClear).setOnClickListener { host.onClear() }
        if (host.compact) applyCompactLook()
    }

    /** The compact page's arrangement ([Host.compact]), applied once. */
    private fun applyCompactLook() {
        val density = ctx.resources.displayMetrics.density
        binder.applyCompactLook()
        sections.flushTop = true
        sections.sectionRules = true
        // No pill to clear at the end of the scroll.
        sections.setPadding(
            sections.paddingLeft, sections.paddingTop, sections.paddingRight,
            (COMPACT_END_PAD_DP * density).toInt(),
        )
        // The hint under a status is a second line here, smaller and closer
        // than the full page's, and gives way where the window is too small
        // for it ([fitStatusHint]).
        statusHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, COMPACT_HINT_SP)
        (statusHint.layoutParams as ViewGroup.MarginLayoutParams).topMargin = (COMPACT_HINT_GAP_DP * density).toInt()
        statusContainer.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                statusContainer.post { if (host.isAlive && statusContainer.isVisible) fitStatusHint() }
            }
        }
        // The headers that stay (Ask, Words) lose the room a card above them
        // needed.
        for (id in intArrayOf(R.id.askHeaderRow, R.id.wordsHeaderRow)) {
            val header = root.findViewById<View>(id) ?: continue
            header.setPadding(header.paddingLeft, 0, header.paddingRight, 0)
        }
    }

    // ── Reads ────────────────────────────────────────────────────────────

    /** The current Ready result, or null in any other state. */
    fun currentReady(): TranslationResult? = (vm.result.value as? ResultState.Ready)?.result

    /** The settled word lookups, or null while idle/loading, and while
     *  [WordLookupsState.Analyzed] (the rows not requested, or in flight). */
    fun settledLookups(): WordLookupsState.Settled? = vm.wordLookups.value as? WordLookupsState.Settled

    /** True iff a translation result is showing (vs status/error/translating). */
    val isShowingResults: Boolean get() = host.isAlive && vm.result.value is ResultState.Ready

    /** The displayed source text (OCR line breaks preserved). */
    fun displayedOriginalText(): String = binder.displayedSourceText()

    fun dismissLens() = sourceLens.dismiss()

    /** A new capture's original [text] has arrived, ahead of the state
     *  that renders it ([TranslationSectionBinder.autoPlayOriginal]). A
     *  word lens still open goes first: the new result's render closes it
     *  a moment later ([WordLookupsRenderer]), and a lens closing stops
     *  whatever is playing, which would cut off the read just started. */
    fun autoPlayOriginal(text: String) {
        sourceLens.dismiss()
        binder.autoPlayOriginal(text)
    }

    fun setLiveHintText(text: CharSequence) {
        liveHint.text = text
    }

    // ── Render (driven by the VM's states) ───────────────────────────────

    fun render(state: ResultState) {
        if (!host.isAlive) return
        // A box fill of the placeholder already bound
        // ([TranslationResultViewModel.applyTranslatingBoxes]): only the
        // host's boxes change. Binding again would hide the results, refit
        // them and scroll back to the top under the user as each translation
        // arrives, and bumping the generation would drop a reveal still
        // pending from the placeholder's own render.
        val boxFill = state is ResultState.Translating &&
            (lastRendered as? ResultState.Translating)?.copy(onScreenBoxes = null) ==
            state.copy(onScreenBoxes = null)
        lastRendered = state
        if (boxFill) {
            host.onRender(state)
            return
        }
        val generation = ++renderGeneration
        host.onRender(state)
        // The Ask section's question is about the sentence on show. Saying
        // which that is, here, on every render, is the whole of keeping an
        // answer with its sentence: binding another one ends the question.
        vm.ask.bind(
            when (state) {
                is ResultState.Translating -> askSentence(state.originalText, prefs.langContext())
                is ResultState.Ready -> askSentence(state.result.originalText, state.result.langContext)
                else -> null
            },
        )
        when (state) {
            is ResultState.Idle ->
                showStatus(ctx.getString(R.string.status_idle), showHint = host.showsStartHint)
            is ResultState.Status ->
                showStatus(state.message, state.showHint, state.ocrProvenance, state.hint)
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
                // A deferred result bound while a consumer needs it runs its
                // skipped translation now: the section visible (the pref is
                // global and nothing listens for flips), or Translation
                // boxes on the game screen, picked while it was being read.
                // No-op without a pending.
                requestDeferredCompletion()
            }
        }
        syncAnkiPill()
    }

    /**
     * The floating Anki pill shows while a result is on show (a Translating
     * placeholder or a Ready result: the reach the section headers' Anki
     * buttons had), and makes way while the Ask question is typed in, its
     * field focused with the keyboard up, since the field can then sit under
     * it. Both read live: the state the funnel last drew, the field's focus
     * and the window's keyboard.
     */
    private fun syncAnkiPill() {
        val onShow = lastRendered is ResultState.Translating || lastRendered is ResultState.Ready
        val typing = ask.fieldHasFocus && keyboardShown()
        ankiPill.isVisible = !host.compact && onShow && !typing
    }

    /** Mirror a word-lookup state ([WordLookupsRenderer]): the tap spans +
     *  the lens follow it synchronously (from the analysis on, rows or
     *  not); the settled rows build after the host's enter-settle gate
     *  (immediate in-app), unless a newer lookup state arrived first. */
    fun renderWordLookups(state: WordLookupsState) = lookupsRenderer.render(state)

    /** Deferred-translation trigger funnel, the over-game panel's rule
     *  ([deferredCompletionNeeded], which its maybeCompleteDeferred applies):
     *  ask the host to run the skipped translation when the bound Ready
     *  result still carries a pending AND a consumer needs it: the
     *  translation section is visible, the capture's boxes on the game
     *  screen are Translations ([Host.captureModeOnScreen], which need their
     *  text whatever the eye says), or [force] (an Anki flow). Run on every
     *  Ready bind as well as when the boxes go up or change mode on the game
     *  screen, so a pick of Translations made while the capture was still
     *  being read completes when its result lands. The host is the single
     *  completion owner and guards against duplicate triggers. */
    fun requestDeferredCompletion(force: Boolean = false) {
        if (currentReady()?.pendingTranslation == null) return
        val needed = force || deferredCompletionNeeded(
            sectionHidden = prefs.hideTranslationSection,
            parked = false,
            mode = host.captureModeOnScreen ?: OverlayMode.NONE,
        )
        if (needed) host.completeDeferredTranslation()
    }

    /** Shared status / error / idle layout — single status container,
     *  results hidden. [showHint] gates the "press X to start" hint line
     *  under the message; [hint] is the state's own line in its place. */
    private fun showStatus(
        message: String,
        showHint: Boolean,
        ocrProvenance: OcrProvenance? = null,
        hint: String? = null,
    ) {
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
        statusHint.text = hint ?: ctx.getString(R.string.status_hold_hint)
        hintWanted = showHint || hint != null
        statusHint.visibility = if (hintWanted) View.VISIBLE else View.GONE
        liveHint.isGone = true
        statusContainer.isVisible = true
        scrollView.isGone = true
        if (host.compact) fitStatusHint()
    }

    /** Whether the status on show has a hint line under it. */
    private var hintWanted = false

    /**
     * The compact page's hint line shows only where the message and it both
     * fit: its window can be made a few lines tall, and the container
     * centres what it holds, so a hint that did not fit would push the top
     * of the message itself out of view. Run when a status is shown and on
     * every change of the container's size; before the first layout there
     * is no size to fit against and the hint stays as it is.
     */
    private fun fitStatusHint() {
        if (!hintWanted || statusContainer.width == 0) return
        val width = statusContainer.width - statusContainer.paddingLeft - statusContainer.paddingRight
        val room = statusContainer.height - statusContainer.paddingTop - statusContainer.paddingBottom
        fun heightOf(v: TextView): Int {
            v.measure(
                View.MeasureSpec.makeMeasureSpec(width.coerceAtLeast(0), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            return v.measuredHeight
        }
        val margin = (statusHint.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        val fits = heightOf(statusText) + margin + heightOf(statusHint) <= room
        val want = if (fits) View.VISIBLE else View.GONE
        if (statusHint.visibility != want) statusHint.visibility = want
    }

    // ── Fit + reveal + scroll ────────────────────────────────────────────

    /** Shrink translation and original text to the visible scroll area:
     *  half of it each when stacked, all of it each when paired
     *  ([TranslationSectionBinder.fitToViewport]); on the compact page, an
     *  equal part each of what is really left of it
     *  ([TranslationSectionBinder.fitToRoom]). */
    fun fitTextSizes() {
        val height = scrollView.height.takeIf { it > 0 } ?: return
        val paired = sections.arrangement == ResultSectionsLayout.Arrangement.PAIRED
        if (host.compact) binder.fitToRoom(height, paired) else binder.fitToViewport(height, paired)
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

    /** Fresh-result scroll reset: always the top, a hidden section's header
     *  included (a hidden section is its header, in place). Silent, because
     *  a self-inflicted scroll here reads as user intent (in dual-screen
     *  live mode it tripped the scroll listener → pause on every incoming
     *  result). */
    private fun scrollToFreshResultStart() {
        scrollView.scrollToTopSilently(scrollListener)
    }

    /** A view to keep visually pinned across a result re-render, plus its
     *  pixel offset from the top of the scroll viewport when captured. */
    /** [view] at [offsetFromViewportTop]; with [rowId], that row of the
     *  Words card's document ([view] is then the document's view). */
    private class ScrollAnchor(val view: View, val offsetFromViewportTop: Int, val rowId: String? = null)

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
        // The word rows are one view (their document), so a deep scroll
        // anchors on the row inside it that the viewport top is in.
        wordRows.rowsView?.takeIf { it.isShown && it.height > 0 }?.let { rows ->
            val rowsTop = scrollView.contentTopOf(rows)
            if (scrollY in rowsTop until rowsTop + rows.height) {
                val id = wordRows.rowAt(scrollY - rowsTop)
                val top = id?.let { wordRows.rowTop(it) }
                if (id != null && top != null) return ScrollAnchor(rows, rowsTop + top - scrollY, id)
            }
        }
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
        // A row that is gone leaves its document as the anchor.
        val rowTop = anchor.rowId?.let { wordRows.rowTop(it) } ?: 0
        val target = scrollView.contentTopOf(anchor.view) + rowTop - anchor.offsetFromViewportTop
        scrollView.restoreScrollSilently(target, scrollListener)
    }

    /** Tear down: the lens, the popovers, the sections' speak job, the Words
     *  card's page, the keyboard watch. Idempotent. */
    fun release() {
        sourceLens.dismiss()
        popovers.release()
        binder.release()
        wordRows.release()
        root.viewTreeObserver.removeOnGlobalLayoutListener(keyboardWatch)
    }
}

/** Whether the soft keyboard is up over [view]'s window, from the insets the
 *  window last dispatched. False while [view] is not attached. */
internal fun keyboardShownOver(view: View): Boolean =
    ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true

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

/** The room at the end of the compact page's scroll, where the full page
 *  leaves the Anki pill's clearance. */
private const val COMPACT_END_PAD_DP = 8f
private const val COMPACT_HINT_SP = 14f
private const val COMPACT_HINT_GAP_DP = 12f

/** Top of [descendant] in this ScrollView's content coordinate space —
 *  independent of the current scroll offset (offsetDescendantRectToMyCoords
 *  stops at this view, so it never subtracts our own scrollY). */
private fun ScrollView.contentTopOf(descendant: View): Int {
    val r = Rect(0, 0, descendant.width, descendant.height)
    offsetDescendantRectToMyCoords(descendant, r)
    return r.top
}
