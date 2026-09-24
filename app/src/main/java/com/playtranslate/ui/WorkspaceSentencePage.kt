package com.playtranslate.ui

import android.content.Context
import android.graphics.Point
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.updatePadding
import com.playtranslate.AnkiManager
import com.playtranslate.CaptureService
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.model.TranslationResult
import com.playtranslate.overlay.OverlayHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The results page as a floating-workspace page — the Sentence tab of the
 * drag flow's [WorkspaceLookupPage]: a shell over [TranslationResultContent]
 * (the same content the in-app [TranslationResultFragment] is a shell over)
 * driving the lookup page's shared [TranslationResultViewModel] through
 * the shared [SentenceTranslationFlow]. What this shell supplies is only
 * what the workspace knows: the lens lives in the workspace's overlay
 * window and its chips push onto this workspace ([WorkspaceRoute.PushInto]);
 * a word tap pushes a nested word page whose Anki card reads this page's
 * live sentence state; the sentence Anki entries are the shared over-game
 * ones ([presentSentenceAnkiReview] / [launchSentenceOneTap]); the
 * language headers push the picker pages (selection dismisses the
 * workspace, the sheet's contract); a deferred translation completes
 * through the flow. No source edit (the button is hidden), no Clear, no
 * show-on-screen boxes: none has an over-game host on a drag sentence.
 *
 * The translation starts when this tab is first shown, not when the popup
 * opens: over the game, the word is what the user asked for, and a backend
 * call (an online service's cost, a cooldown) is spent only once the
 * sentence is. A translation the drag flow already cached binds directly;
 * a hidden translation section defers it to the eye reveal, exactly as the
 * Activities do. The settled word rows build after the workspace's enter
 * animation has settled (the content's gate), so a Sentence-default open
 * can't drop entrance frames on them.
 */
class WorkspaceSentencePage(
    private val vm: TranslationResultViewModel,
    private val args: LensDetailArgs,
) : WorkspacePage {

    private var pageScope: CoroutineScope? = null
    private var pageView: View? = null
    private var hostRef: WorkspaceHost? = null
    private var content: TranslationResultContent? = null
    private var flow: SentenceTranslationFlow? = null

    private val isAlive: Boolean get() = pageView != null

    override fun title(ctx: Context): CharSequence = ctx.getString(R.string.anki_mode_sentence)

    override fun onCreateView(ctx: Context, parent: ViewGroup, host: WorkspaceHost): View {
        hostRef = host
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        pageScope = scope
        val view = LayoutInflater.from(ctx).inflate(R.layout.fragment_translation_result, parent, false)
        pageView = view
        // The scroll's 8dp end padding was sized for the Activities' square
        // window edge; the workspace card's rounded bottom crowds it, so the
        // Words card gets another 8dp of clearance from the corner here.
        view.findViewById<View>(R.id.resultsScrollContent).let {
            val extra = (EXTRA_BOTTOM_PAD_DP * ctx.resources.displayMetrics.density).toInt()
            it.updatePadding(bottom = it.paddingBottom + extra)
        }
        val c = TranslationResultContent(view, ctx, Prefs(ctx), vm, PageHost(ctx, host, scope))
        content = c
        c.binder.setShowOnScreenAvailable(false)
        // A controller cursor follows a ⋯ menu: onto its first row once the
        // rows have laid out, back onto ⋯ as it closes. The size picker has
        // no rows, so its opening leaves the cursor where it is (on the
        // button, under the scrim) for B to close it back onto.
        c.popovers.addListener(object : PopoverHost.Listener {
            override fun onPopoverChanged(open: Boolean, content: PopoverContent, anchor: View) {
                if (!open) hostRef?.invalidateNav(prefer = anchor)
            }

            override fun onPopoverLaidOut(content: PopoverContent, anchor: View) {
                c.popovers.navActions()?.firstOrNull()?.let { hostRef?.invalidateNav(prefer = it.view) }
            }
        })
        flow = SentenceTranslationFlow(
            ctx.applicationContext, vm, scope,
            backend = { CaptureService.instance?.sentenceTranslationBackend() },
        )
        scope.launch { vm.result.collect { c.render(it) } }
        scope.launch { vm.wordLookups.collect { c.renderWordLookups(it) } }
        c.wordRows.attachCollectors(scope)
        start()
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

    /** The workspace's side of the content seam. */
    private inner class PageHost(
        private val ctx: Context,
        private val host: WorkspaceHost,
        override val scope: CoroutineScope,
    ) : TranslationResultContent.Host {
        override val isAlive: Boolean get() = this@WorkspaceSentencePage.isAlive
        override val ttsAlertTarget: TtsAlertTarget =
            TtsAlertTarget.Overlay(ctx, host.overlayHost, host.wm, host.displayId)
        override val lensOverlayHost: OverlayHost get() = host.overlayHost
        override val lensWindowManager: WindowManager get() = host.wm
        override val lensDisplayId: Int get() = host.displayId
        override fun screenSize(): Point {
            val dm = ctx.resources.displayMetrics
            return Point(dm.widthPixels, dm.heightPixels)
        }

        /** Every unit opens (into a nested word page), entry or not. */
        override val opensWithoutEntry: Boolean get() = true

        override suspend fun awaitEnterSettled() = host.awaitEnterSettled()

        /** The lens's chips through the shared router, pushing onto this
         *  workspace: the open chevron (and secondary sections) push a nested
         *  word page, the Anki chip pushes the word editor. */
        override fun wireLensActions(lens: MagnifierLens, resolved: SourceWordLookup.ResolvedAt) {
            val unit = resolved.word
            val phrase = resolved.phrase
            val secondaries = phrase?.let { listOf(it) } ?: resolved.members
            fun context(u: SourceWordLookup.Resolved) = LensActionContext(
                u.word, u.reading, u.entry, args.sentence, args.screenshotPath,
                audioAnchorMs = args.audioAnchorMs,
                entries = u.entries,
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
            ) { context(unit) }
        }

        override fun onWordTapped(word: String, reading: String?) = pushWordPage(word, reading)

        override fun onChangeLanguage(isSource: Boolean) {
            host.push(if (isSource) SourceListPage() else TargetListPage())
        }

        override fun onAddToAnki() = openSentenceAnkiReview()
        override fun onAnkiOneTap() = oneTapSentence()

        override fun completeDeferredTranslation() {
            flow?.completeDeferred()
        }
    }

    // ── Sentence Anki ────────────────────────────────────────────────────

    /** The bound result as a sentence-card payload, with the words as ONE
     *  snapshot of the settled rows (results + surfaces + enrichment from
     *  the same emission — never the global cache). Null once destroyed. */
    private fun sentenceAnkiArgs(result: TranslationResult): SentenceAnkiArgs? {
        val ctx = pageView?.context ?: return null
        val settled = content?.settledLookups()
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
        val c = content ?: return
        val result = c.currentReady() ?: return
        // Anki consumes the sentence translation — a deferred result must
        // complete through the funnel (translation + History attach), not
        // only through the editor's own lazy fill. At worst this costs one
        // duplicate backend call; the attach is idempotent.
        c.requestDeferredCompletion(force = true)
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
        val c = content ?: return
        val result = c.currentReady() ?: return
        val ctx = host.ctx
        val anki = AnkiManager(ctx)
        if (!anki.isAnkiDroidInstalled() || !anki.hasPermission() || Prefs(ctx).ankiDeckId < 0L) {
            // No headless path available → the review (its gates explain).
            openSentenceAnkiReview()
            return
        }
        c.requestDeferredCompletion(force = true)
        val ankiArgs = sentenceAnkiArgs(result) ?: return
        launchSentenceOneTap(ctx.applicationContext, ankiArgs) {
            // The mapping needs UI: the review, but only while this page is up.
            if (isAlive) openSentenceAnkiReview()
        }
    }

    // ── Workspace contract ───────────────────────────────────────────────

    /** The page's clickables, or an open popover's targets alone (none for
     *  the size picker: B closes it). */
    override fun navActions(): List<NavAction> =
        content?.popovers?.navActions() ?: collectWorkspaceNavActions(pageView)

    override fun scrollView(): ViewGroup? = content?.scrollView

    override val isPopoverOpen: Boolean get() = content?.popovers?.isShowing == true

    override fun dismissPopovers() {
        content?.popovers?.dismiss()
    }

    override fun onBack(): Boolean {
        val c = content ?: return false
        if (c.popovers.dismiss()) return true
        if (c.sourceLens.isShowing) {
            c.dismissLens()
            return true
        }
        return false
    }

    override fun onDestroy() {
        content?.release()
        content = null
        flow = null
        pageScope?.cancel()
        pageScope = null
        pageView = null
        hostRef = null
    }

    private companion object {
        /** Added to the results scroll's own end padding on this host. */
        const val EXTRA_BOTTOM_PAD_DP = 8f
    }
}
