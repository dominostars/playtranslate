package com.playtranslate.imageimport

import android.content.Context
import android.graphics.Bitmap
import android.text.TextPaint
import android.util.Log
import android.widget.FrameLayout
import com.playtranslate.CaptureSession
import com.playtranslate.CaptureState
import com.playtranslate.OcrManager
import com.playtranslate.OverlayMode
import com.playtranslate.OverlayToolkit
import com.playtranslate.Prefs
import com.playtranslate.camera.CameraCoordinates
import com.playtranslate.camera.CameraTranslator
import com.playtranslate.camera.DisplayEpoch
import com.playtranslate.camera.FrozenReviewBackend
import com.playtranslate.camera.render.FrozenLookupScene
import com.playtranslate.camera.render.OverlayRasterizer
import com.playtranslate.camera.render.SnapshotCore
import com.playtranslate.camera.render.WarpOverlayView
import com.playtranslate.camera.tracker.Homography
import com.playtranslate.cancelledStateOrNull
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.language.stackableTargetScript
import com.playtranslate.language.targetSupportsVerticalText
import com.playtranslate.ui.CaptureResultOverlay
import com.playtranslate.ui.TextBox
import com.playtranslate.ui.noTextStatusMessage
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The import tool's review pipeline: the camera snapshot cycle without the
 * camera — one-shot OCR + translate over a picked image, rendered as static
 * IDENTITY-warped boxes over a letterboxed ([CameraCoordinates.FitMode.FIT])
 * display. All SEMANTICS (gating, region filtering, provenance, panel-text
 * alignment, result assembly) come from the shared [SnapshotCore]; only the
 * sequencing and the display substrate live here.
 *
 * Substrate discipline mirrors the camera's, minus its live-path sources:
 *  - [generation] supersedes runs: OCR engines may not observe cooperative
 *    cancellation, so a superseded cycle can return from recognise normally
 *    and reach the cache write — every cache write re-checks the generation
 *    INSIDE [stateLock], making a stale publish structurally impossible;
 *  - [displayEpoch] orders display work: every publication source (a cycle's
 *    cache write, each [showOverlays] repaint, [endEpisode]) advances it
 *    atomically with its cache access, and every raster commit re-checks —
 *    a slower earlier repaint (skeleton raster) can never land over a faster
 *    later one (filled boxes).
 *
 * Deliberately records NOTHING into translation-log History: imports are
 * look-ups of stored images, not gameplay moments.
 *
 * [showOverlays] must NEVER translate — it renders skeletons from the cached
 * translations being null and filled boxes once the cycle promotes them; a
 * translating repaint would duplicate the pipeline's backend call.
 */
class ImageImportSession(
    private val context: Context,
    /** The hosting activity's lifecycleScope — slow-OCR callbacks land on
     *  the main thread. */
    private val scope: CoroutineScope,
    private val overlayHost: FrameLayout,
    /** Fired once per image when recognition exceeds the shared slow-OCR
     *  threshold (main thread). Re-armed by [startEpisode]; re-runs on the
     *  same image do not re-arm. */
    private val onSlowOcr: () -> Unit = {},
    /** A cycle finished a COMPLETE scene (Done emitted) — the page cache's
     *  hook to [exportScene]. Posted to the main thread with the completing
     *  cycle's GENERATION: cache admission must depend on the cycle's own
     *  identity, not on whatever the caches hold when the callback runs — a
     *  settings refresh clears the page cache and bumps the generation
     *  WITHOUT wiping the session caches, so a superseded cycle's callback
     *  would otherwise export the intact old-settings scene into the
     *  freshly-cleared cache (persistent wrong-language revisits). */
    private val onSceneCompleted: (Long) -> Unit = {},
) : FrozenReviewBackend {

    private val prefs = Prefs(context)
    private val translator = CameraTranslator(context)

    // ── Display substrate (see class doc) ───────────────────────────────
    private val stateLock = Any()
    private var cachedOcr: OcrManager.OcrResult? = null
    private var cachedGroupColors: List<Pair<Int, Int>>? = null
    private var cachedAuW = 0
    private var cachedAuH = 0

    /** Per-group translations WITH attribution ([CameraTranslator.Detailed])
     *  — the page cache re-emits Done from this, so text alone is not
     *  enough. Null while the pipeline's translations are in flight. */
    private var cachedPerGroup: List<CameraTranslator.Detailed>? = null

    /** The scene's panel payload, retained so a revisited page republishes
     *  without re-running anything. */
    private var cachedProvenance: com.playtranslate.model.OcrProvenance? = null
    private var cachedPanelText: String? = null
    private var cachedSegments: List<com.playtranslate.model.TextSegment>? = null

    private var cachedScreenshotPath: String? = null
    private val displayEpoch = DisplayEpoch()
    private val generation = AtomicLong()

    @Volatile
    private var slowOcrFired = false

    /** Whether OCR grouping may use the document-layout prior (page-rhythm
     *  bootstrap in the ambiguous gap band). Set by the controller from the
     *  SOURCE TYPE via [documentLayoutBiasFor] — declared documents (PDF /
     *  CBZ / multi-image pages) only; a lone imported image is as likely a
     *  game screenshot and stays prior-free (2026-07-19 review finding).
     *  Defaults off so restore and any unwired path are screenshot-safe. */
    var documentLayoutBias = false

    private var warpView: WarpOverlayView? = null

    /** Review-zoom crispness boost multiplied into the raster resolution —
     *  1f at fit (byte-identical rendering). Written on main at gesture
     *  settle, read at raster time so every repaint (settle, Done
     *  promotion, flavor cycle) bakes at the same effective scale. */
    @Volatile
    private var overlayRenderBoost = 1f

    /** The review zoom's transform seams: apparent size via the warp view's
     *  pre-multiplied transform, crispness via the raster boost. Main
     *  thread. */
    fun setOverlayViewTransform(transform: DoubleArray?) {
        overlayHost.post { warpView?.viewTransform = transform }
    }

    fun setOverlayRenderBoost(boost: Float) {
        overlayRenderBoost = boost
    }

    private val furiganaPaint by lazy {
        TextPaint().apply {
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            textSize = 100f // arbitrary — only relative proportions matter
        }
    }

    /** A new image is about to be reviewed: re-arm the slow-OCR prompt (a
     *  new-image boundary, matching the camera's per-session latch) and
     *  supersede any zombie run from the previous episode. */
    fun startEpisode() {
        slowOcrFired = false
        overlayRenderBoost = 1f
        generation.incrementAndGet()
    }

    /** The episode ended (review dismissed): wipe the caches — the word
     *  lookup reads them directly and must not resolve words from a scene
     *  the panel no longer describes — and take down the boxes. The wipe is
     *  a publication source. The saved frame is retired with it: a graceful
     *  dismissal saves no restore state, so nothing will read it again. */
    fun endEpisode() {
        generation.incrementAndGet()
        overlayRenderBoost = 1f
        setOverlayViewTransform(null)
        val retired: String?
        synchronized(stateLock) {
            retired = cachedScreenshotPath
            wipeCachesLocked()
            displayEpoch.advance()
        }
        deleteQuiet(retired)
        hideOverlays()
    }

    /** Callers hold [stateLock]. */
    private fun wipeCachesLocked() {
        cachedOcr = null
        cachedGroupColors = null
        cachedPerGroup = null
        cachedProvenance = null
        cachedPanelText = null
        cachedSegments = null
        cachedScreenshotPath = null
    }

    // ── FrozenReviewBackend ─────────────────────────────────────────────

    override fun runReview(
        bitmap: Bitmap,
        regionAu: android.graphics.Rect?,
        preOcrDelayMs: Long,
    ): CaptureSession {
        val state = MutableStateFlow<CaptureState>(
            CaptureState.InProgress(context.getString(com.playtranslate.R.string.status_ocr))
        )
        val gen = generation.incrementAndGet()
        val job = scope.launch(Dispatchers.Default) {
            try {
                // The page-flip dwell: InProgress is already showing, and
                // this delay is the cheapest cancellation point — a rapid
                // flip's observe() cancels the job before any OCR spends.
                if (preOcrDelayMs > 0) kotlinx.coroutines.delay(preOcrDelayMs)
                runCycle(bitmap, state, regionAu, gen)
            } catch (e: CancellationException) {
                // Let cancellation propagate; invokeOnCompletion writes Cancelled.
                throw e
            } catch (e: Exception) {
                // A thrown OCR or translation failure (cloud backend with no
                // network, a native engine error) becomes a readable terminal
                // state instead of escaping to a handler-less scope.
                Log.e(TAG, "review cycle failed: ${e.message}", e)
                state.value = CaptureState.Failed(e.message ?: "Unknown error")
            }
        }
        job.invokeOnCompletion { cause ->
            cancelledStateOrNull(cause, state.value)?.let { state.value = it }
        }
        return CaptureSession(state.asStateFlow(), job)
    }

    /** A completed scene's full display + panel payload — what a revisited
     *  page needs to republish without OCR or translation. */
    data class CachedScene(
        val gatedOcr: OcrManager.OcrResult,
        val groupColors: List<Pair<Int, Int>>,
        val perGroup: List<CameraTranslator.Detailed>,
        val provenance: com.playtranslate.model.OcrProvenance?,
        val panelText: String,
        val segments: List<com.playtranslate.model.TextSegment>,
        val auW: Int,
        val auH: Int,
    )

    /** The current caches as a reusable scene, or null unless a COMPLETED
     *  (translations promoted) scene owns the display — a wiped, superseded,
     *  or still-translating scene self-guards to null. [expectedGen] is the
     *  exporting cycle's generation: for CACHE ADMISSION it must be the
     *  callback-carried value, so a superseded cycle exports null even when
     *  the caches still hold its (or any) intact scene. The no-arg form
     *  snapshots the current scene and must never feed the page cache. */
    fun exportScene(expectedGen: Long = generation.get()): CachedScene? = synchronized(stateLock) {
        if (generation.get() != expectedGen) return null
        val ocr = cachedOcr ?: return null
        val colors = cachedGroupColors ?: return null
        val perGroup = cachedPerGroup ?: return null
        val panelText = cachedPanelText ?: return null
        val segments = cachedSegments ?: return null
        CachedScene(ocr, colors, perGroup, cachedProvenance, panelText, segments, cachedAuW, cachedAuH)
    }

    /** Republish a completed [scene] for [bitmap] (a revisited page): no
     *  OCR, no translation — save a fresh frame file (Anki attachments and
     *  word lookup read it), publish the caches under the same
     *  generation/epoch discipline as a live cycle, and emit Done. */
    fun publishScene(bitmap: Bitmap, scene: CachedScene): CaptureSession {
        val state = MutableStateFlow<CaptureState>(
            CaptureState.InProgress(context.getString(com.playtranslate.R.string.status_ocr))
        )
        val gen = generation.incrementAndGet()
        val job = scope.launch(Dispatchers.Default) {
            val screenshotPath = saveToCache(bitmap)
            var replaced: String? = null
            var published = false
            synchronized(stateLock) {
                if (generation.get() == gen) {
                    published = true
                    replaced = cachedScreenshotPath
                    cachedOcr = scene.gatedOcr
                    cachedGroupColors = scene.groupColors
                    cachedAuW = scene.auW
                    cachedAuH = scene.auH
                    cachedPerGroup = scene.perGroup
                    cachedProvenance = scene.provenance
                    cachedPanelText = scene.panelText
                    cachedSegments = scene.segments
                    cachedScreenshotPath = screenshotPath
                    displayEpoch.advance()
                }
            }
            if (!published) {
                deleteQuiet(screenshotPath)
                return@launch
            }
            if (replaced != screenshotPath) deleteQuiet(replaced)
            state.value = SnapshotCore.doneState(
                scene.panelText, scene.segments, scene.perGroup, screenshotPath,
                scene.provenance, prefs.langContext(prefs.sourceLangId),
                scene.auW, scene.auH,
            )
        }
        job.invokeOnCompletion { cause ->
            cancelledStateOrNull(cause, state.value)?.let { state.value = it }
        }
        return CaptureSession(state.asStateFlow(), job)
    }

    override fun lookupScene(): FrozenLookupScene? {
        synchronized(stateLock) {
            val ocr = cachedOcr ?: return null
            val lines = ocr.groups.flatMapIndexed { gi, g ->
                g.lines.map { line ->
                    OcrManager.OcrLine(
                        text = line.text,
                        bounds = line.bounds,
                        groupIndex = gi,
                        groupText = g.text,
                        symbols = line.symbols,
                        orientation = line.orientation,
                        angleDeg = line.angleDeg,
                        orientedWidth = line.orientedWidth,
                        orientedHeight = line.orientedHeight,
                    )
                }
            }
            if (lines.isEmpty()) return null
            return FrozenLookupScene(lines, cachedAuW, cachedAuH, cachedScreenshotPath)
        }
    }

    override suspend fun translateForPanel(text: String): CaptureResultOverlay.PanelTranslation? {
        val d = translator.translateDetailed(listOf(text)).firstOrNull() ?: return null
        if (d.text.isEmpty()) return null
        return CaptureResultOverlay.PanelTranslation(d.text, d.note, d.backendDisplayName)
    }

    // ── The cycle ───────────────────────────────────────────────────────

    private suspend fun runCycle(
        frame: Bitmap,
        state: MutableStateFlow<CaptureState>,
        regionAu: android.graphics.Rect?,
        gen: Long,
    ) {
        val srcId = prefs.sourceLangId
        val sourceLang = SourceLanguageProfiles[srcId].translationCode
        val auW = frame.width
        val auH = frame.height
        val screenshotPath = saveToCache(frame)
        val importToken = prefs.importOcrBackendToken(srcId)

        // Slow-OCR rescue timer, the live/camera threshold: fires MID-pass,
        // once per image; cancelled with the pass so a fast read never
        // fires. [scope] is the activity's lifecycleScope — the callback
        // lands on the main thread.
        val slowTimer = if (!slowOcrFired) scope.launch {
            kotlinx.coroutines.delay(com.playtranslate.LiveSessionFeedback.OCR_SLOW_PROMPT_MS)
            slowOcrFired = true
            onSlowOcr()
        } else null
        val ocr = try {
            OcrManager.instance.recognise(
                frame,
                sourceLang,
                screenshotWidth = auW,
                // Whole-frame read (no edge gate — the user asked for THIS
                // image, clipped lines included); the region clip is a
                // recognition-cost saver, projected into the processed
                // image's space.
                regionPreFilter = SnapshotCore.regionPreFilter(
                    dropEdgeClipped = false, clipTo = regionAu,
                    clipFrameW = auW, clipFrameH = auH, tag = TAG,
                ),
                engineTokenOverride = importToken,
                // Declared documents only (see the property's kdoc): PDFs
                // and page-sets get the page-rhythm grouping prior; a lone
                // imported screenshot must not.
                documentLayoutBias = documentLayoutBias,
            )
        } finally {
            slowTimer?.cancel()
        }
        // Superseded while the recognizer ran (region re-run, dismissal)?
        // Abandon before touching any shared state — cancellation alone
        // can't be relied on (an engine that never checks it returns here
        // normally after the cancel). This cycle's frame file was never
        // published, so it dies with the cycle.
        if (generation.get() != gen) {
            deleteQuiet(screenshotPath)
            return
        }
        val translating = SourceLanguageProfiles[srcId].translationCode != prefs.targetLang
        val gatedGroups = ocr?.let {
            SnapshotCore.usableGroups(it, auW, auH, translating, skipEdgeGate = true, tag = TAG)
        }.orEmpty()
        val groups = SnapshotCore.regionCenterFilter(gatedGroups, regionAu)
        val provenance = SnapshotCore.snapshotProvenance(context, ocr, srcId, importToken)
        if (ocr == null || groups.isEmpty()) {
            // A no-text verdict OWNS the display exactly like a successful
            // run: a re-run (empty region, gear re-OCR) must not leave the
            // PREVIOUS scene's caches behind — the word lookup reads them
            // directly. Same generation guard + epoch protocol as the
            // success write. The path handoff is guarded with the caches:
            // winning retires the replaced frame file; losing retires this
            // cycle's own (never-published) one.
            var replaced: String? = null
            var won = false
            synchronized(stateLock) {
                if (generation.get() == gen) {
                    won = true
                    replaced = cachedScreenshotPath
                    wipeCachesLocked()
                    displayEpoch.advance()
                }
            }
            // On a win, this cycle's file stays for the NoText panel's
            // affordances (unreferenced by the cache — the orphan sweep
            // reclaims it eventually).
            if (won) deleteQuiet(replaced) else deleteQuiet(screenshotPath)
            state.value = CaptureState.NoText(
                noTextStatusMessage(context, com.playtranslate.R.string.image_import_no_text, srcId),
                provenance,
                screenshotPath,
            )
            return
        }

        val gated = ocr.copy(groups = groups)
        val groupColors = SnapshotCore.sampleGroupColors(frame, groups.map { it.bounds })
        val (originalText, segments) = SnapshotCore.panelTextFor(ocr, groups)
        // The run owns the display: epoch and caches move together.
        // Translations are not in yet — showOverlays renders skeletons until
        // they land below. Generation re-checked INSIDE the lock: a newer
        // run bumps the counter before its own cycle can reach this block,
        // so a stale cycle can never publish after (or over) a newer one.
        // The frame-file handoff rides the same guard: the file becomes
        // shared state ONLY here, so a stale cycle's file can never alias
        // the active review's (each cycle writes its own unique name).
        var replacedPath: String? = null
        var published = false
        synchronized(stateLock) {
            if (generation.get() == gen) {
                published = true
                replacedPath = cachedScreenshotPath
                cachedOcr = gated
                cachedGroupColors = groupColors
                cachedAuW = auW
                cachedAuH = auH
                cachedPerGroup = null
                cachedProvenance = provenance
                cachedPanelText = originalText
                cachedSegments = segments
                cachedScreenshotPath = screenshotPath
                displayEpoch.advance()
            }
        }
        if (!published) {
            deleteQuiet(screenshotPath)
            return
        }
        // The predecessor re-run's frame (same episode, earlier cycle) is
        // now unreferenced. An Anki send that captured the old path pins
        // (copies) it at send START, so the only exposure is a send whose
        // ms-scale pin copy races this delete — it degrades to a card
        // without a screenshot.
        if (replacedPath != screenshotPath) deleteQuiet(replacedPath)

        val overlayData = com.playtranslate.OneShotOverlayData(emptyList(), 0, 0, auW, auH)
        state.value = CaptureState.Translating(originalText, segments, provenance, overlayData)

        val perGroup = translator.translateDetailed(groups.map { it.text })
        // Superseded while translating: mirror the post-recognise check for
        // the remaining side effects. NO History write here — deliberate,
        // see the class doc.
        if (generation.get() != gen) return

        // Promote the boxes' data source BEFORE emitting Done: the panel's
        // Done handler asks the presenter to fill the on-frame boxes, which
        // reads this cache. The full Detailed list is retained — the page
        // cache re-emits Done from it. The identity check alone suffices
        // for wipe-style supersessions; the generation joins it as the belt
        // for wipe-FREE ones (settings refresh), mirroring the camera's own
        // belt-guard precedent.
        synchronized(stateLock) {
            if (generation.get() == gen && cachedOcr === gated) cachedPerGroup = perGroup
        }

        state.value = SnapshotCore.doneState(
            originalText, segments, perGroup, screenshotPath, provenance,
            prefs.langContext(srcId), auW, auH,
        )
        // [scope] is the activity's main-dispatched lifecycleScope. The
        // cycle's own generation rides along — see [onSceneCompleted].
        scope.launch { onSceneCompleted(gen) }
    }

    /** Save the reviewed frame under a unique per-cycle name — see
     *  [SnapshotCore.saveFrame] for the rationale (fixed names were the
     *  aliasing bug class). */
    private fun saveToCache(frame: Bitmap): String? =
        SnapshotCore.saveFrame(context, frame, FRAME_PREFIX, TAG)

    private fun deleteQuiet(path: String?) = SnapshotCore.deleteFrame(path)

    /** The active review's saved-frame path (null before the first cache
     *  publication or after a no-text verdict) — the activity persists it
     *  for process-death restore. */
    fun currentFramePath(): String? = synchronized(stateLock) { cachedScreenshotPath }

    // ── Static overlay display ──────────────────────────────────────────

    /** Paint the review's boxes as static IDENTITY-warped overlays over the
     *  letterboxed image. Skeletons while translations are in flight, filled
     *  once [cachedPerGroup] lands; never calls the translator itself.
     *  Safe to call again on the Done promotion — each call is its own
     *  publication SOURCE (epoch advanced atomically with the cache read),
     *  so a slower earlier repaint can never land over a faster later one. */
    fun showOverlays() {
        val ocr: OcrManager.OcrResult?
        val colors: List<Pair<Int, Int>>?
        val translations: List<String>?
        val auW: Int
        val auH: Int
        val epoch: Int
        synchronized(stateLock) {
            ocr = cachedOcr
            colors = cachedGroupColors
            translations = cachedPerGroup?.map { it.text }
            auW = cachedAuW
            auH = cachedAuH
            epoch = displayEpoch.advance()
        }
        if (ocr == null || colors == null || auW == 0) return
        overlayHost.post { ensureWarpView().applyHomography(Homography.IDENTITY) }
        scope.launch(Dispatchers.Default) {
            try {
                when (prefs.importOverlayMode) {
                    OverlayMode.FURIGANA -> showFurigana(ocr, auW, auH, epoch)
                    OverlayMode.TRANSLATION ->
                        showTranslationBoxes(ocr, colors, translations, auW, auH, epoch)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "overlay build failed", e)
            }
        }
    }

    fun hideOverlays() {
        overlayHost.post { warpView?.clearRegions() }
    }

    /** True while boxes are actually painted (flavor cycling repaints only
     *  then — a repaint from an expanded panel would resurrect boxes it
     *  didn't ask for). Main thread. */
    fun hasVisibleOverlays(): Boolean = warpView?.hasVisibleRegions == true

    /** Translation-flavor render: placeholder boxes, filled from the
     *  pipeline's [translations] when available. Everything rides the
     *  global IDENTITY transform — the frame is static. */
    private suspend fun showTranslationBoxes(
        ocr: OcrManager.OcrResult,
        colors: List<Pair<Int, Int>>,
        translations: List<String>?,
        auW: Int,
        auH: Int,
        epoch: Int,
    ) {
        val groups = ocr.groups.filter { it.text.isNotBlank() }
        val placeholders = SnapshotCore.buildPlaceholderBoxes(groups, colors)
        val boxes = if (translations == null) placeholders
        else placeholders.mapIndexed { idx, ph ->
            ph.copy(translatedText = translations.getOrElse(idx) { "" })
        }
        showRegions(boxes, auW, auH, epoch)
    }

    /** Furigana/pinyin-flavor render: reading marks over their lines
     *  (their colors come from the toolkit, not the sampled group colors).
     *  Never translates (readings come from the tokenizer). */
    private suspend fun showFurigana(
        ocr: OcrManager.OcrResult,
        auW: Int,
        auH: Int,
        epoch: Int,
    ) {
        val engine = SourceLanguageEngines.get(context, prefs.sourceLangId)
        val furigana = OverlayToolkit.buildFuriganaBoxesByGroup(ocr, engine, furiganaPaint)
        val boxes = furigana.flatMap { it.boxes }
        showRegions(boxes, auW, auH, epoch)
    }

    /** Rasterize AU-space boxes (main thread — view machinery) and install
     *  them in the warp view. All boxes ride the global transform (trackKey
     *  -1): there is no per-region tracking on a static image. */
    private suspend fun showRegions(boxes: List<TextBox>, auW: Int, auH: Int, epoch: Int) {
        withContext(Dispatchers.Main) {
            if (!displayEpoch.isCurrent(epoch)) return@withContext
            val rasterizer = OverlayRasterizer(
                context,
                verticalTextTarget = targetSupportsVerticalText(prefs.targetLang),
                verticalTextStackable = stackableTargetScript(prefs.targetLang),
                verticalGrowEnabled = prefs.verticalTextGrow,
            )
            val regions = rasterizer.rasterize(
                boxes, auW, auH,
                trackKeys = boxes.map { -1 },
                // Boost read at raster time (not captured earlier) so a
                // settle repaint and a Done promotion bake identically.
                renderScale = baseRenderScale(auW, auH) * overlayRenderBoost,
            )
            // The rasterize above runs view machinery synchronously, but a
            // cycle on another thread may have advanced the epoch meanwhile —
            // re-check before the install commit.
            if (!displayEpoch.isCurrent(epoch)) {
                regions.forEach { it.release() }
                return@withContext
            }
            ensureWarpView().setRegions(regions, auW, auH)
        }
    }

    /** Raster resolution: view pixels per AU pixel under FIT — dp-based
     *  layout decisions evaluate in true display pixels, and rasters land at
     *  1:1 physical pixels instead of being scaled by the warp afterwards. */
    private fun baseRenderScale(auW: Int, auH: Int): Float {
        val w = overlayHost.width
        val h = overlayHost.height
        if (w <= 0 || h <= 0 || auW <= 0 || auH <= 0) return 1f
        return CameraCoordinates(auW, auH, w, h, CameraCoordinates.FitMode.FIT).scale
    }

    private fun ensureWarpView(): WarpOverlayView {
        warpView?.let { return it }
        val view = WarpOverlayView(context).apply {
            fitMode = CameraCoordinates.FitMode.FIT
        }
        overlayHost.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        warpView = view
        return view
    }

    companion object {
        private const val TAG = "ImageImportSession"
        private const val FRAME_PREFIX = "import-image-"

        /** Collect this tool's saved frames orphaned by a crash or process
         *  death — [SnapshotCore.sweepFrameFiles] on the import prefix. */
        fun sweepOrphanedFrames(ctx: Context, keepPath: String?) =
            SnapshotCore.sweepFrameFiles(ctx, FRAME_PREFIX, keepPath)
    }
}
