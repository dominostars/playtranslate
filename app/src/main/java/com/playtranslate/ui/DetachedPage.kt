package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.playtranslate.AnkiManager
import com.playtranslate.CaptureService
import com.playtranslate.CaptureSession
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.ask.Explanation
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.model.OcrProvenance
import com.playtranslate.model.TranslationResult
import com.playtranslate.ocr.registry.selectionToken
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlayThemedContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The detached window's results page: the in-app page's content
 * ([TranslationResultContent]) over a view-model of its own, as the third
 * shell beside [TranslationResultFragment] and [WorkspaceSentencePage]. It
 * shows the idle prompt until something is captured, then each capture of
 * its display that the controller hands it ([follow]) through the shared
 * [CapturePageDriver].
 *
 * One page per display, owned by [com.playtranslate.OverlayUiController]
 * and outliving the window's view: the icon standing in for the window (one
 * of our own screens in front, the region editor) takes the window down and
 * leaves the page, with its result, for the window that comes back. Hiding
 * the controls drops it ([release]).
 *
 * What this shell supplies is what an over-game surface with no Activity
 * knows, and all of it is the capture sheet's and the workspace page's own
 * routes: the tap-a-word lens in its own overlay window; a word's page, the
 * Anki editors and the language pickers in the floating workspace over the
 * window ([WorkspaceRoute.OpenNew]); alerts as overlay alerts, since the
 * window can be smaller than one; the OCR gear's re-read through the driver.
 * No source edit (the app's editor is its Activity's own views), no start
 * hint, and no show-on-screen boxes: a capture into the window draws nothing
 * on the game, and the toggle is offered only by a host that turns it on
 * ([TranslationSectionBinder.setShowOnScreenAvailable]), which this one never
 * does.
 *
 * The page is the compact one ([TranslationResultContent.Host.compact]): the
 * window is small and has a bar, so the page shows no language headers and
 * no floating Anki pill, and hands the window what they held
 * ([DetachedWindow.Page]): the headers' actions and the two languages for
 * the window's menu, Read aloud and Add to Anki for the bar's shortcuts
 * too, and its text-size picker to open in the window's popover.
 *
 * The page goes back to the idle prompt, dropping a capture on its way,
 * when either language setting changes: a result belongs to the languages
 * it was read and translated in. It does not listen to the service's
 * `statusUpdates`, which every capture sheet's own configure emits.
 *
 * Besides captures the page shows an auto-translate's results, while that
 * session's results are this window's ([followLive]): nothing is drawn over
 * the game, and each result the loop delivers replaces the last here, as on
 * the app's own page on a second screen. Then a language change leaves the
 * page looking, not idle, and a capture of the display made elsewhere takes
 * nothing down.
 */
internal class DetachedPage(
    displayCtx: Context,
    private val wm: WindowManager,
    val displayId: Int,
    private val overlayHost: OverlayHost,
) : DetachedWindow.Page {
    private val ctx: Context = overlayThemedContext(displayCtx)
    private val appCtx: Context = ctx.applicationContext
    private val prefs = Prefs(ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var alive = true

    val vm = TranslationResultViewModel(scope)

    /** The page's view tree; the window shows it under its bar. */
    override val view: View = LayoutInflater.from(ctx)
        .inflate(R.layout.fragment_translation_result, FrameLayout(ctx), false)

    private val pageHost = PageHost()
    private val content = TranslationResultContent(view, ctx, prefs, vm, pageHost)

    // ── What lives in the window's menu and bar ([DetachedWindow.Page]) ───

    /** The two languages, which the headers this page does not show named
     *  and opened: one menu row each, the language as its value. */
    private val gameLanguage = HeaderAction(
        R.id.detachedMenuGameLanguage, R.drawable.ic_translate, ctx.getString(R.string.lang_translate_from),
    ).apply { onClick = { pageHost.onChangeLanguage(true) } }
    private val yourLanguage = HeaderAction(
        R.id.detachedMenuYourLanguage, R.drawable.ic_translate, ctx.getString(R.string.lang_translate_to),
    ).apply { onClick = { pageHost.onChangeLanguage(false) } }

    override val readAloud: HeaderAction get() = content.binder.speakAction
    override fun textActions(): List<HeaderAction> = content.binder.textActions()

    override fun languageRows(): List<HeaderAction> {
        gameLanguage.detail = content.binder.sourceLanguageName
        yourLanguage.detail = content.binder.targetLanguageName
        return listOf(gameLanguage, yourLanguage)
    }

    override fun addActionListener(listener: FoldingActionRow.Listener) {
        for (row in content.binder.actionRows()) row.addListener(listener)
    }

    override fun removeActionListener(listener: FoldingActionRow.Listener) {
        for (row in content.binder.actionRows()) row.removeListener(listener)
    }

    /** A result is on show: text for the actions to act on, and to make a
     *  card of (the reach the page's floating pill has on the other
     *  surfaces). */
    override var resultOnShow: Boolean = false
        private set(value) {
            if (field == value) return
            field = value
            onResultOnShowChanged?.invoke()
        }
    override var onResultOnShowChanged: (() -> Unit)? = null
    override var popoverPresenter: ((PopoverContent, View) -> Unit)? = null

    override fun addToAnki() = pageHost.onAddToAnki()
    override fun ankiOneTap() = pageHost.onAnkiOneTap()

    private fun resultOnShow(state: ResultState): Boolean =
        state is ResultState.Translating || state is ResultState.Ready

    private val driver = CapturePageDriver(
        vm, appCtx, scope,
        service = { CaptureService.instance },
        collectWhileShown = { collect -> scope.launch { collect() } },
        onOriginalToRead = { content.autoPlayOriginal(it) },
        onCancelled = ::putBack,
    )

    init {
        showIdle()
        scope.launch {
            vm.result.collect {
                content.render(it)
                resultOnShow = resultOnShow(it)
            }
        }
        scope.launch { vm.wordLookups.collect { content.renderWordLookups(it) } }
        content.wordRows.attachCollectors(scope)
        scope.launch {
            prefs.observe(Prefs.KEY_SOURCE_LANG, Prefs.KEY_TARGET_LANG, Prefs.KEY_TARGET_CHINESE_VARIANT)
                .map { prefs.langContext() }
                .distinctUntilChanged()
                // The first value is the languages as they stand.
                .drop(1)
                .collect {
                    val svc = liveService
                    if (svc == null) {
                        driver.abandon()
                        showIdle()
                    } else {
                        // The session goes on in the new pair, so the page
                        // says it is looking, not that nothing was asked.
                        // Nothing is remembered as delivered (the same text
                        // is a new result now) and the loop looks again: a
                        // change of the game language restarts the session
                        // by itself, a change of the other does not.
                        svc.clearPanel()
                        vm.showStatus(searchingStatus(svc))
                        svc.refreshLiveOverlay()
                    }
                }
        }
    }

    // ── Auto-translate in the window ─────────────────────────────────────

    /** The session whose results this page shows, and the collector that
     *  shows them; null while it follows none. */
    private var liveService: CaptureService? = null
    private var liveFeed: Job? = null

    /** This page shows a running auto-translate's results. */
    val followsLive: Boolean get() = liveService != null

    /**
     * [svc]'s auto-translate shows its results here from now on, or with
     * null no longer does: the controller says which, at every start and
     * stop of a session and every change of where one shows
     * ([com.playtranslate.OverlayUiController.syncLivePages]). What each
     * state of the stream shows is the app's own page's mapping
     * ([showPanelState]).
     *
     * A capture still on its way ends here: a session start cancels it at
     * the service, and its Cancelled would otherwise put what the page
     * showed before it back over the session's "Searching". The stream is
     * sticky, so the collector is handed its current state first: at a
     * start that is the "Searching" the start wrote; for a session that was
     * showing in the app's page a moment ago, its result on show.
     *
     * No longer following, the page keeps what it shows, as the app's page
     * keeps a session's last result.
     */
    fun followLive(svc: CaptureService?) {
        if (svc === liveService || !alive) return
        liveFeed?.cancel()
        liveFeed = null
        liveService = svc
        // No service is picked for a result while a session feeds the page:
        // its next read would put its own translation in the pick's place.
        // When it stops, the result it left can be picked for.
        content.binder.translationPickerOffered = svc == null
        if (svc == null) return
        driver.abandon()
        liveFeed = scope.launch {
            svc.panelState.collect { state -> vm.showPanelState(state, appCtx) { searchingStatus(svc) } }
        }
    }

    private fun searchingStatus(svc: CaptureService): String =
        liveSearchingStatus(ctx, prefs.sourceLangId, svc.activeRegionForDisplay(displayId))

    /**
     * Read again with the OCR tool just picked. A session this page follows
     * re-reads itself, as on the app's page (MainActivity.onReOcrRequested):
     * the pick is already saved and each read resolves its tool, so a fresh
     * look reads the screen as it is now with the new one; the live tiers
     * never state device-only. The frame saved with a session's result is
     * not re-read: on whole-screen capture it holds this window. Otherwise
     * the frame on show is re-read ([CapturePageDriver.reOcr]).
     */
    private fun reRead(deviceOnly: Boolean) {
        val svc = liveService
        if (svc != null) svc.refreshLiveOverlay() else driver.reOcr(deviceOnly)
    }

    /** The prompt before anything is captured, and under it how the window
     *  itself is worked: this is where a new user first looks. */
    private fun showIdle() = vm.showStatus(
        ctx.getString(R.string.detached_status_idle),
        hint = ctx.getString(R.string.detached_status_idle_hint),
    )

    /** A capture of this page's display: its states are the page's from here. */
    fun follow(session: CaptureSession) = driver.follow(session)

    /**
     * A capture of this page's display wrote its frame, whoever it was for
     * (the sheet, with the window narrowed to its button or the icon
     * standing in; tap-to-lookup; a hold-to-preview; this page itself). A
     * result points at that one file for its screenshot, its OCR re-read
     * and its cards, so anything of an earlier capture is no longer whole
     * and the page goes back to its idle prompt. One result on show for a
     * display, always its latest capture, as the app's own page is on two
     * screens, where every capture is routed to it.
     *
     * The page's own capture in flight is that latest capture only while
     * the writer is the service's capture job ([byCaptureJob]): the service
     * runs one, and another would have cancelled this page's first. Then
     * nothing on show is taken down (a status, or a text still being
     * translated), and only what a cancel would have put back is forgotten.
     * A hold-to-preview or an auto-translate cycle runs beside that job: it
     * read the display after this capture did, this capture would finish
     * with that later frame for its image, and so it gives way like any
     * older result.
     *
     * A page that follows auto-translate ([followLive]) keeps what it
     * shows: the loop itself keeps the result and the picture the display's
     * latest, replacing both when the screen's text changes. Going idle
     * here (a hold-to-preview wrote the picture) would also stick, since
     * the service delivers a text once and would drop the same text as a
     * repeat when the loop read it again.
     */
    fun onCaptureImageReplaced(byCaptureJob: Boolean) {
        if (followsLive) return
        if (driver.inFlight && byCaptureJob) {
            driver.imageReplaced()
            return
        }
        val shown = vm.result.value
        val cameWithAnImage =
            shown is ResultState.Ready || (shown is ResultState.Status && shown.screenshotPath != null)
        if (!driver.inFlight && !cameWithAnImage) return
        driver.abandon()
        showIdle()
    }

    /**
     * A capture this page followed was cancelled from outside (another
     * capture took the service's one capture job, a region changed, the
     * service is going): the page stays up, so it goes back to what it showed
     * before, since nothing else will replace the capture's own status.
     */
    private fun putBack(shownBefore: ResultState) {
        when (shownBefore) {
            is ResultState.Ready, is ResultState.Status, is ResultState.Error -> vm.restore(shownBefore, appCtx)
            is ResultState.Translating, ResultState.Idle -> showIdle()
        }
    }

    /** The page is done: its capture, its lens and popovers, its Words page. */
    fun release() {
        if (!alive) return
        alive = false
        liveService = null
        driver.abandon()
        content.release()
        scope.cancel()
    }

    // ── A word's page ────────────────────────────────────────────────────

    private fun openWordPage(
        word: String,
        reading: String?,
        surface: String?,
        lookupForm: String,
        lookupReading: String?,
        explanation: Explanation?,
    ) {
        val result = content.currentReady()
        // The sentence as it stands at the open, holding what was answered
        // about this word; the page's card reads the live state over it.
        val opened = vm.sentenceContext(null)
            .explaining(pageCardTarget(surface, lookupForm, lookupReading), explanation)
        WorkspaceRoute.OpenNew(displayId).present(result?.screenshotPath) {
            WorkspaceWordDetailPage(
                word = word,
                reading = reading,
                surface = surface,
                lookupForm = lookupForm,
                lookupReading = lookupReading,
                screenshotPath = result?.screenshotPath,
                audioAnchorMs = result?.createdAtMs?.takeIf { it > 0 },
                sentenceContext = { vm.sentenceContext(opened) },
            )
        }
    }

    // ── Sentence Anki ────────────────────────────────────────────────────

    /** The bound result as a sentence-card payload, with the words as ONE
     *  snapshot of the settled rows (never the global cache). */
    private fun sentenceAnkiArgs(result: TranslationResult): SentenceAnkiArgs {
        val settled = content.settledLookups()
        return SentenceAnkiArgs(
            original = result.originalText,
            translation = result.translatedText,
            screenshotPath = result.screenshotPath,
            sourceLangId = prefs.sourceLangId,
            pendingTranslation = result.pendingTranslation,
            audioAnchorMs = result.createdAtMs.takeIf { it > 0 },
            words = settled?.rows?.let {
                LastSentenceCache.WordsPayload(
                    it.toLegacyMap(), it.toSurfaceMap(), it.toEnrichmentMap(),
                    annotation = settled.annotation,
                )
            },
            notes = content.sentenceCardNote(),
        )
    }

    private fun openSentenceAnkiReview() {
        val result = content.currentReady() ?: return
        // Anki consumes the sentence translation: a deferred result must
        // complete through the funnel (translation + History attach).
        content.requestDeferredCompletion(force = true)
        if (!AnkiManager(ctx).isAnkiDroidInstalled()) {
            showAnkiNotInstalledDialog(ctx, overlayHost, wm, displayId)
            return
        }
        presentSentenceAnkiReview(ctx, displayId, WorkspaceRoute.OpenNew(displayId), sentenceAnkiArgs(result))
    }

    private fun oneTapSentence() {
        val result = content.currentReady() ?: return
        val anki = AnkiManager(ctx)
        if (!anki.isAnkiDroidInstalled() || !anki.hasPermission() || prefs.ankiDeckId < 0L) {
            // No headless path available: the review (its gates explain).
            openSentenceAnkiReview()
            return
        }
        content.requestDeferredCompletion(force = true)
        launchSentenceOneTap(appCtx, sentenceAnkiArgs(result)) {
            // The mapping needs UI: the review, but only while this page is up.
            if (alive) openSentenceAnkiReview()
        }
    }

    // ── The content's host ───────────────────────────────────────────────

    private inner class PageHost : TranslationResultContent.Host {
        override val isAlive: Boolean get() = alive
        override val scope: CoroutineScope get() = this@DetachedPage.scope
        override val ttsAlertTarget: TtsAlertTarget = TtsAlertTarget.Overlay(ctx, overlayHost, wm, displayId)
        override val lensOverlayHost: OverlayHost get() = overlayHost
        override val lensWindowManager: WindowManager get() = wm
        override val lensDisplayId: Int get() = displayId
        override fun screenSize(): Point {
            val dm = ctx.resources.displayMetrics
            return Point(dm.widthPixels, dm.heightPixels)
        }

        /** Every unit opens, entry or not, as on the other over-game hosts. */
        override val opensWithoutEntry: Boolean get() = true

        /** The lens's actions through the shared router: a row's open and
         *  its Anki button reach the floating workspace over the window. */
        override fun wireLensActions(lens: MagnifierLens, resolved: SourceWordLookup.ResolvedAt) {
            val unit = resolved.word
            val secondaries = resolved.secondaries()
            fun context(u: SourceWordLookup.Resolved): LensActionContext {
                val result = content.currentReady()
                return LensActionContext(
                    u.word, u.reading, u.surface, u.entry,
                    result?.originalText, result?.screenshotPath,
                    audioAnchorMs = result?.createdAtMs?.takeIf { it > 0 },
                    entries = u.entries,
                    lookupForm = u.lookupForm,
                    lookupReading = u.lookupReading,
                )
            }
            SourceLensActions(
                appCtx, displayId, overlayHost, lens,
                showAnkiNotInstalled = { showAnkiNotInstalledDialog(ctx, overlayHost, wm, displayId) },
                route = WorkspaceRoute.OpenNew(displayId),
                currentSecondary = if (secondaries.isEmpty()) null else { i ->
                    secondaries.getOrNull(i)?.let { context(it) }
                },
            ) { context(unit) }
        }

        override fun onWordTapped(
            word: String,
            reading: String?,
            surface: String?,
            lookupForm: String,
            lookupReading: String?,
            explanation: Explanation?,
        ) = openWordPage(word, reading, surface, lookupForm, lookupReading, explanation)

        /** While the page follows auto-translate the gear is always
         *  offered, as on the app's page: the loop is what re-reads
         *  ([reRead]). */
        override fun canReOcr(): Boolean = followsLive || driver.reOcrTarget() != null

        /** The compact page: this window is small and has a bar. */
        override val compact: Boolean get() = true

        /** The page's popovers open in the window's, which can reach past
         *  the window; with no window, in the page. */
        override fun showPopover(content: PopoverContent, anchor: View): Boolean {
            val present = popoverPresenter ?: return false
            present(content, anchor)
            return true
        }

        override val showsClearAction: Boolean get() = true
        override fun onClear() {
            driver.abandon()
            showIdle()
        }

        /** The "Choose OCR tool" alert over the game. A pick of a device
         *  engine or the cloud's entry re-reads the frame on show; one that
         *  is not downloaded opens its page in the app. */
        override fun onChooseOcr(provenance: OcrProvenance) {
            OcrPicker.populate(
                OverlayAlert.Builder(ctx, overlayHost, wm, displayId),
                ctx,
                provenance.sourceLangId,
                provenance.engineToken,
                onReOcr = { reRead(deviceOnly = true) },
                onDownload = { backend ->
                    launchOnForegroundDisplay(
                        ctx,
                        CaptureOverlaySettingsActivity.downloadIntent(
                            appCtx, provenance.sourceLangId, backend.selectionToken,
                        ),
                        displayId,
                    )
                },
                cloud = OcrPicker.CloudChoice(
                    onReOcr = { reRead(deviceOnly = false) },
                    onOpenPage = {
                        launchOnForegroundDisplay(
                            ctx, Intent(appCtx, PtCloudSettingsActivity::class.java), displayId,
                        )
                    },
                ),
            ).showAsOverlay()
        }

        /** The picker page in the floating workspace over the window; the
         *  Activity where the workspace is not available. */
        override fun onChangeLanguage(isSource: Boolean) {
            val opened = CaptureBackendResolver.activeOverlayUi?.openWorkspace(
                displayId, content.currentReady()?.screenshotPath,
            ) { if (isSource) SourceListPage() else TargetListPage() } == true
            if (opened) return
            LanguageSetupActivity.selectionDelegate = null
            launchOnForegroundDisplay(
                ctx,
                Intent(appCtx, LanguageSetupActivity::class.java).putExtra(
                    LanguageSetupActivity.EXTRA_MODE,
                    if (isSource) LanguageSetupActivity.MODE_SOURCE else LanguageSetupActivity.MODE_TARGET,
                ),
                displayId,
            )
        }

        override fun onAddToAnki() = openSentenceAnkiReview()
        override fun onAnkiOneTap() = oneTapSentence()

        override fun completeDeferredTranslation() = driver.completeDeferred()

        override fun onAskNeedsLlm() = showAskNeedsLlm(ttsAlertTarget)

        /** Over the game, in a window of its own, as the page's other
         *  alerts are. */
        override fun showAlert(configure: (OverlayAlert.Builder) -> Unit) =
            showAlertOn(ttsAlertTarget) { configure(this) }

        override fun openTranslationServices() = openTranslationServices(ttsAlertTarget)
    }
}
