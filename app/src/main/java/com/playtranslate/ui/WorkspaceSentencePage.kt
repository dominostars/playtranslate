package com.playtranslate.ui

import android.content.Context
import android.graphics.Point
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.playtranslate.AnkiManager
import com.playtranslate.CaptureService
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.model.TranslationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The results page as a floating-workspace page — the Sentence tab of the
 * drag flow's [WorkspaceLookupPage]: the source and target sections
 * ([TranslationSectionBinder], shared with the in-app page and the capture
 * sheet), the Words card ([WordRowsBinder], shared with the in-app page),
 * the tap-a-word lens ([SourceTextLens], shared with both), and the
 * sentence Anki entries ([presentSentenceAnkiReview] /
 * [launchSentenceOneTap], shared with the sheet), over the same
 * `fragment_translation_result.xml` the in-app page inflates. The
 * [TranslationResultViewModel] is the lookup page's (shared with its word
 * tab); this page drives it through the shared [SentenceTranslationFlow].
 *
 * The translation starts when this tab is first shown, not when the popup
 * opens: over the game, the word is what the user asked for, and a backend
 * call (an online service's cost, a cooldown) is spent only once the
 * sentence is. A translation the drag flow already cached binds directly;
 * a hidden translation section defers it to the eye reveal, exactly as the
 * Activities do.
 *
 * Word taps — a row body, the lens's open chevron, its secondary sections
 * — PUSH a nested word page whose Anki card reads this page's live sentence
 * state; the lens's Anki chip pushes the word editor. Both go through the
 * lens action router on the [WorkspaceRoute.PushInto] route, so back returns
 * here. The language headers push the picker pages (selection dismisses the
 * workspace, the sheet's contract). No in-place source edit (the button is
 * hidden) and no show-on-screen boxes: neither has an over-game host on a
 * drag sentence.
 */
class WorkspaceSentencePage(
    private val vm: TranslationResultViewModel,
    private val args: LensDetailArgs,
) : WorkspacePage {

    private var pageScope: CoroutineScope? = null
    private var pageView: View? = null
    private var hostRef: WorkspaceHost? = null
    private var binder: TranslationSectionBinder? = null
    private var wordRows: WordRowsBinder? = null
    private var sourceLens: SourceTextLens? = null
    private var flow: SentenceTranslationFlow? = null
    private var fontPopover: FontSizeRangePopover? = null
    private var statusContainer: View? = null
    private var statusText: TextView? = null
    private var resultsContent: ScrollView? = null

    /** Bumped per render so a superseded reveal can't resurrect stale
     *  content over a newer one (the fragment's renderGeneration). */
    private var renderGeneration = 0

    private val isAlive: Boolean get() = pageView != null

    override fun title(ctx: Context): CharSequence = ctx.getString(R.string.anki_mode_sentence)

    override fun onCreateView(ctx: Context, parent: ViewGroup, host: WorkspaceHost): View {
        hostRef = host
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        pageScope = scope
        val prefs = Prefs(ctx)
        val view = LayoutInflater.from(ctx).inflate(R.layout.fragment_translation_result, parent, false)
        pageView = view
        statusContainer = view.findViewById(R.id.statusContainer)
        statusText = view.findViewById(R.id.tvStatus)
        resultsContent = view.findViewById(R.id.resultsContent)
        // In-app-only chrome: the hold hint, the live hint, the Clear row.
        view.findViewById<View>(R.id.tvStatusHint).isGone = true
        view.findViewById<View>(R.id.tvLiveHint).isGone = true
        view.findViewById<View>(R.id.resultActionButtons).isGone = true
        statusContainer?.isGone = true

        val alertTarget = TtsAlertTarget.Overlay(ctx, host.overlayHost, host.wm, host.displayId)
        val b = TranslationSectionBinder(view, ctx, prefs, scope, alertTarget)
        binder = b
        b.editAvailable = false
        b.setShowOnScreenAvailable(false)
        b.setupSectionButtons(
            onEdit = {},
            onAddToAnki = { openSentenceAnkiReview() },
            onAnkiOneTap = { oneTapSentence() },
        )
        b.onChooseLanguage = { isSource ->
            host.push(if (isSource) SourceListPage() else TargetListPage())
        }
        // The popover floats over the results scroll inside this page's root
        // FrameLayout — a child of the workspace window, never a sibling
        // window (the QTI rule).
        fontPopover = FontSizeRangePopover(ctx, view as FrameLayout, prefs).apply {
            onRangeChanged = { fitTextSizes() }
        }
        b.onChooseFontSize = { fontPopover?.toggle(b.fontSizeAnchor) }
        // Revealing the translation section on a deferred result must run
        // the translation that was skipped while it was hidden.
        b.onSectionVisibilityChanged = { maybeCompleteDeferred() }

        val rows = WordRowsBinder(
            view, ctx, prefs,
            object : WordRowsBinder.Host {
                override val isAlive: Boolean get() = this@WorkspaceSentencePage.isAlive
                override val scope: CoroutineScope get() = scope
                override val ttsAlertTarget: TtsAlertTarget get() = alertTarget
                override fun onWordTapped(row: RowState) {
                    pushWordPage(row.displayWord, row.reading.ifEmpty { null })
                }
            },
        )
        wordRows = rows
        val lens = SourceTextLens(
            ctx, host.wm, host.displayId, host.overlayHost,
            scope = scope,
            ttsAlertTarget = alertTarget,
            binder = b,
            screenSize = {
                val dm = ctx.resources.displayMetrics
                Point(dm.widthPixels, dm.heightPixels)
            },
            showAnkiChip = { it.word.entry != null },
            // Every unit opens (into a nested word page), entry or not.
            opensWithoutEntry = true,
            decks = rows.deckSource,
            wireActions = { lens, resolvedAt -> wireLensActions(lens, resolvedAt) },
        )
        sourceLens = lens
        b.tvOriginal.onTapAtOffset = { offset -> lens.onTapAtOffset(offset) }
        resultsContent?.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (scrollY != oldScrollY) lens.dismiss()
        }

        flow = SentenceTranslationFlow(
            ctx.applicationContext, vm, scope,
            backend = { CaptureService.instance?.sentenceTranslationBackend() },
        )
        scope.launch { vm.result.collect { render(it) } }
        scope.launch { vm.wordLookups.collect { renderWordLookups(it) } }
        rows.attachCollectors(scope)
        // Start after the enter animation has settled (a no-op hold once it
        // has): a Sentence-default open would otherwise land the placeholder
        // bind and the settled rows inside the entrance.
        scope.launch {
            host.awaitEnterSettled()
            if (isAlive) start()
        }
        return view
    }

    /** Translate the sentence (or bind the drag flow's cached translation)
     *  — once: a re-shown tab keeps whatever state the VM already holds. */
    private fun start() {
        if (vm.result.value !is ResultState.Idle) return
        val cached = args.sentenceContext.translation
            ?.takeIf { it.isNotBlank() }
            ?.let { SentenceTranslationFlow.Cached(it, args.cachedTranslationSource) }
        flow?.show(args.sentence, args.screenshotPath, cached)
    }

    // ── Render (driven by the VM) ────────────────────────────────────────

    private fun render(state: ResultState) {
        val b = binder ?: return
        val rows = wordRows ?: return
        val generation = ++renderGeneration
        when (state) {
            is ResultState.Idle -> Unit
            is ResultState.Status -> showStatus(state.message)
            is ResultState.Error -> showStatus(b.ctxString(R.string.status_error, state.message))
            is ResultState.Translating -> {
                b.bindTranslating(state.segments, state.ocrProvenance)
                rows.applyWordsVisibility()
                revealFitted(generation)
            }
            is ResultState.Ready -> {
                b.bindResult(state.result, canReOcr = false)
                rows.applyWordsVisibility()
                revealFitted(generation)
                // A deferred result bound while the section is visible (the
                // pref is global and nothing listens for flips) must run its
                // skipped translation now. No-op without a pending.
                maybeCompleteDeferred()
            }
        }
    }

    private fun showStatus(message: String) {
        statusText?.text = message
        statusContainer?.isVisible = true
        resultsContent?.isGone = true
    }

    /** Reveal the results with their text already sized (the fragment's
     *  hide → fit → show, so the source doesn't visibly resize when the
     *  translation later lands). */
    private fun revealFitted(generation: Int) {
        val scroll = resultsContent ?: return
        statusContainer?.isGone = true
        scroll.visibility = View.INVISIBLE
        scroll.post {
            if (!isAlive || generation != renderGeneration) return@post
            fitTextSizes()
            scroll.post {
                if (!isAlive || generation != renderGeneration) return@post
                scroll.isVisible = true
            }
        }
    }

    private fun fitTextSizes() {
        val scroll = resultsContent ?: return
        val height = scroll.height.takeIf { it > 0 } ?: return
        binder?.fitToViewport(height)
    }

    private fun renderWordLookups(state: WordLookupsState) {
        val lens = sourceLens ?: return
        val b = binder ?: return
        when (state) {
            is WordLookupsState.Idle -> lens.wordSpans = emptyList()
            is WordLookupsState.Loading -> {
                lens.dismiss()
                lens.wordSpans = emptyList()
            }
            is WordLookupsState.Settled -> {
                lens.wordSpans = SourceWordLookup.computeTapSpans(
                    b.displayedSourceText(), state.tokenSpans, state.lookupToReading, state.phrases,
                )
            }
        }
        wordRows?.render(state)
    }

    private fun maybeCompleteDeferred(force: Boolean = false) {
        val ctx = hostRef?.ctx ?: return
        if (Prefs(ctx).hideTranslationSection && !force) return
        flow?.completeDeferred()
    }

    // ── Word taps ────────────────────────────────────────────────────────

    /** The nested word page's Anki card reads THIS page's live sentence
     *  state (a translation that lands later is on the card), with the
     *  lens's snapshot as the fallback. */
    private fun liveSentenceContext(): SentenceContext = vm.sentenceContext(args.sentenceContext)

    private fun pushWordPage(word: String, reading: String?) {
        val host = hostRef ?: return
        host.push(
            WorkspaceWordDetailPage(
                word = word,
                reading = reading,
                screenshotPath = args.screenshotPath,
                audioAnchorMs = args.audioAnchorMs,
                sentenceContext = { liveSentenceContext() },
            ),
        )
    }

    /** The lens's chips through the shared router, pushing onto this
     *  workspace: the open chevron (and secondary sections) push a nested
     *  word page, the Anki chip pushes the word editor. */
    private fun wireLensActions(lens: MagnifierLens, resolvedAt: SourceWordLookup.ResolvedAt) {
        val host = hostRef ?: return
        val ctx = host.ctx
        val resolved = resolvedAt.word
        val phrase = resolvedAt.phrase
        val secondaries = phrase?.let { listOf(it) } ?: resolvedAt.members
        fun context(unit: SourceWordLookup.Resolved) = LensActionContext(
            unit.word, unit.reading, unit.entry, args.sentence, args.screenshotPath,
            audioAnchorMs = args.audioAnchorMs,
            entries = unit.entries,
        )
        SourceLensActions(
            ctx.applicationContext, host.displayId, host.overlayHost, lens,
            showAnkiNotInstalled = { showAnkiNotInstalledDialog(ctx, host.modalLayer) },
            route = WorkspaceRoute.PushInto(host),
            detailPage = { a ->
                WorkspaceWordDetailPage(
                    word = a.word,
                    reading = a.reading,
                    screenshotPath = a.screenshotPath,
                    audioAnchorMs = a.audioAnchorMs,
                    sentenceContext = { liveSentenceContext() },
                )
            },
            currentSecondary = if (secondaries.isEmpty()) null else { i ->
                secondaries.getOrNull(i)?.let { context(it) }
            },
        ) { context(resolved) }
    }

    // ── Sentence Anki ────────────────────────────────────────────────────

    private fun currentReady(): TranslationResult? =
        (vm.result.value as? ResultState.Ready)?.result

    /** The bound result as a sentence-card payload, with the words as ONE
     *  snapshot of the settled rows (results + surfaces + enrichment from
     *  the same emission — never the global cache). Null once the page is
     *  destroyed. */
    private fun sentenceAnkiArgs(result: TranslationResult): SentenceAnkiArgs? {
        val ctx = pageView?.context ?: return null
        val settled = vm.wordLookups.value as? WordLookupsState.Settled
        return SentenceAnkiArgs(
            original = result.originalText,
            translation = result.translatedText,
            screenshotPath = result.screenshotPath,
            sourceLangId = Prefs(ctx).sourceLangId,
            pendingTranslation = result.pendingTranslation,
            audioAnchorMs = args.audioAnchorMs,
            words = settled?.rows?.let {
                LastSentenceCache.WordsPayload(
                    it.toLegacyMap(), it.toSurfaceMap(), it.toEnrichmentMap(),
                    annotation = settled.annotation,
                )
            },
        )
    }

    private fun openSentenceAnkiReview() {
        val host = hostRef ?: return
        val result = currentReady() ?: return
        // Anki consumes the sentence translation — a deferred result must
        // complete through the funnel (translation + History attach), not
        // only through the editor's own lazy fill. At worst this costs one
        // duplicate backend call; the attach is idempotent.
        maybeCompleteDeferred(force = true)
        val ctx = host.ctx
        if (!AnkiManager(ctx).isAnkiDroidInstalled()) {
            showAnkiNotInstalledDialog(ctx, host.modalLayer)
            return
        }
        val ankiArgs = sentenceAnkiArgs(result) ?: return
        // A missing permission takes the Activity trampoline; the route tears
        // the workspace down first (its overlay window would otherwise sit
        // above the launched activity).
        presentSentenceAnkiReview(ctx, host.displayId, WorkspaceRoute.PushInto(host), ankiArgs)
    }

    private fun oneTapSentence() {
        val host = hostRef ?: return
        val result = currentReady() ?: return
        val ctx = host.ctx
        val anki = AnkiManager(ctx)
        if (!anki.isAnkiDroidInstalled() || !anki.hasPermission() || Prefs(ctx).ankiDeckId < 0L) {
            // No headless path available → the review (its gates explain).
            openSentenceAnkiReview()
            return
        }
        maybeCompleteDeferred(force = true)
        val ankiArgs = sentenceAnkiArgs(result) ?: return
        launchSentenceOneTap(ctx.applicationContext, ankiArgs) {
            // The mapping needs UI: the review, but only while this page is up.
            if (isAlive) openSentenceAnkiReview()
        }
    }

    // ── Workspace contract ───────────────────────────────────────────────

    override fun navActions(): List<NavAction> = collectWorkspaceNavActions(pageView)

    override fun scrollView(): ViewGroup? = resultsContent

    override fun onBack(): Boolean {
        if (fontPopover?.isShowing == true) {
            fontPopover?.dismiss()
            return true
        }
        if (sourceLens?.isShowing == true) {
            sourceLens?.dismiss()
            return true
        }
        return false
    }

    override fun onDestroy() {
        sourceLens?.dismiss()
        sourceLens = null
        fontPopover?.dismiss()
        fontPopover = null
        binder?.release()
        binder = null
        wordRows = null
        flow = null
        pageScope?.cancel()
        pageScope = null
        pageView = null
        statusContainer = null
        statusText = null
        resultsContent = null
        hostRef = null
    }
}

/** A resource string through the binder's context (the page's themed
 *  display context). */
private fun TranslationSectionBinder.ctxString(resId: Int, vararg args: Any): String =
    tvOriginal.context.getString(resId, *args)
