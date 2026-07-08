package com.playtranslate.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.text.TextPaint
import android.util.Log
import android.util.Size
import android.widget.FrameLayout
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.core.graphics.scale
import com.playtranslate.OcrManager
import com.playtranslate.OverlayMode
import com.playtranslate.OverlayToolkit
import com.playtranslate.Prefs
import com.playtranslate.camera.render.OverlayRasterizer
import com.playtranslate.camera.render.RasterRegion
import com.playtranslate.camera.render.WarpOverlayView
import com.playtranslate.camera.tracker.CnFrameConverter
import com.playtranslate.camera.tracker.FrameDecision
import com.playtranslate.camera.tracker.FrameTracker
import com.playtranslate.camera.tracker.Homography
import com.playtranslate.camera.tracker.TrackState
import com.playtranslate.camera.tracker.TrackerConfig
import com.playtranslate.camera.tracker.TrackerEngine
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.language.stackableTargetScript
import com.playtranslate.language.targetSupportsVerticalText
import com.playtranslate.ui.TextBox
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat

private const val TAG = "CameraSession"

/**
 * Camera-tool pipeline orchestrator (Phase 2: keyframe OCR + planar
 * tracking).
 *
 * Per analysis frame (single-threaded executor): a coarse-luma settle
 * detector gates acquires; [CnFrameConverter] produces the upright canonical
 * gray; [FrameTracker] sustains anchor↔current correspondences (pyramidal LK
 * + periodic ORB drift reset) and fits the global RANSAC homography;
 * [TrackerEngine] (pure policy) decides state and re-OCR triggers. The
 * smoothed homography is posted to [WarpOverlayView], which redraws the
 * rastered overlay regions through it.
 *
 * On acquire (engine-triggered, settled scenes only): snapshot an upright
 * RGB keyframe + its CN gray, then off the frame path run
 * [OcrManager.recognise] → flavor boxes (translation skeleton→filled, or
 * furigana/pinyin) → [OverlayRasterizer] → install regions + a fresh ORB
 * anchor. Tracker mutations are serialized onto the analysis executor.
 */
class CameraSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val overlayHost: FrameLayout,
) {
    private companion object {

        /** Debug pill refresh cadence (frames). */
        const val PILL_EVERY = 5

        /** Groups whose known line confidences average below this are dropped
         *  before translation — garbage reads (rotated text, blur) translate
         *  into fluent-sounding nonsense otherwise. Engines that report no
         *  confidence (-1) are never gated by this. */
        const val MIN_GROUP_CONFIDENCE = 0.5f

        /** Groups whose bounds touch the frame edge (within this margin, AU
         *  px) on their reading axis are dropped: the line continues off
         *  frame, and fragment reads translate as non-sequiturs. */
        const val EDGE_MARGIN_PX = 12

        @Volatile private var cvLoaded = false
        fun ensureOpenCv() {
            if (!cvLoaded) synchronized(CameraSession::class.java) {
                if (!cvLoaded) {
                    check(OpenCVLoader.initLocal()) { "OpenCV initLocal() failed" }
                    cvLoaded = true
                }
            }
        }
    }

    private val prefs = Prefs(context)
    private val translator = CameraTranslator(context)

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    /** Bumped on mode/language change and shutdown; in-flight acquires check
     *  it before publishing so stale results drop silently. */
    private val generation = AtomicInteger(0)
    private val acquireInFlight = AtomicBoolean(false)
    private val nextAnchorId = AtomicLong(1L)

    // OpenCV must be loaded BEFORE the tracker fields below construct their
    // first Mat — this session may be the process's first OpenCV user (fresh
    // install, ML-Kit-floor OCR: Meiki/Paddle never loaded it). Kotlin runs
    // initializers in source order, so this init block must stay above them.
    init {
        ensureOpenCv()
    }

    // ── Analysis-thread state ──────────────────────────────────────────────
    private val cnConverter = CnFrameConverter()
    private val frameTracker = FrameTracker()
    private val engine = TrackerEngine()

    private var frameCount = 0L
    private var lastHeartbeatNs = 0L

    /** Analysis fps over the 15-frame heartbeat window. */
    private fun heartbeatFps(): Double {
        val now = System.nanoTime()
        val fps = if (lastHeartbeatNs > 0) 15e9 / (now - lastHeartbeatNs) else 0.0
        lastHeartbeatNs = now
        return fps
    }

    // ── Published pipeline state (guarded by [stateLock]) ─────────────────
    private val stateLock = Any()
    private var cachedOcr: OcrManager.OcrResult? = null
    private var cachedKeyframe: Bitmap? = null

    /** The warp surface; created lazily on main. */
    private var warpView: WarpOverlayView? = null

    /** Debug status sink (the on-screen pill); set by the Activity in debug
     *  builds. Called on the main thread. */
    var statusSink: ((String) -> Unit)? = null

    /** True while the camera's autofocus is actively scanning (Activity
     *  feeds this from the Camera2 AF-state callback). An acquire during a
     *  scan OCRs a defocused frame — garbage reads at moderate confidence
     *  were observed on device before this gate existed. */
    @Volatile
    var afScanning: Boolean = false

    private val furiganaPaint by lazy {
        TextPaint().apply {
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            textSize = 100f // arbitrary — only relative proportions matter
        }
    }

    /** OCR pre-warm: Meiki's engine is constructed lazily on first use (three
     *  .mnn model loads + MNN graph setup + first-inference kernel warmup),
     *  which used to land inside the FIRST acquire's OCR timing (~4.5 s fresh
     *  process vs ~1.3 s steady on the Moto G). Running a stamp-sized digit
     *  strip through the real pipeline while the user is still aiming hides
     *  that cost. [runAcquire] joins this job so the two never race the
     *  engine's internal caches. */
    private val prewarmJob = scope.launch(Dispatchers.Default) {
        try {
            val t0 = System.currentTimeMillis()
            val bmp = Bitmap.createBitmap(256, 96, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(bmp).apply {
                drawColor(Color.WHITE)
                drawText(
                    "0123456789",
                    16f, 64f,
                    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 48f
                        color = Color.BLACK
                    },
                )
            }
            val sourceLang = SourceLanguageProfiles[prefs.sourceLangId].translationCode
            OcrManager.instance.recognise(bmp, sourceLang, screenshotWidth = bmp.width)
            bmp.recycle()
            Log.d(TAG, "prewarm: OCR engine ready in ${System.currentTimeMillis() - t0}ms")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "prewarm failed (first acquire pays the cold start)", e)
        }
    }

    /** Build the analysis use case for the activity to bind. RGBA output
     *  (no YUV handling; the tracker grays it via OpenCV); 16:9 to match the
     *  Preview use case (shared FOV + deterministic FILL_CENTER mapping). */
    fun buildAnalysisUseCase(): ImageAnalysis {
        ensureOpenCv()
        // The aspect-ratio strategy is load-bearing: without it the resolver
        // may pick a 4:3 stream (Moto G handed us 1920×1440 for a 1920×1080
        // ask), giving the analysis a different FOV than the 16:9 preview and
        // shifting every overlay. 16:9 must match the Preview use case.
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1920, 1080), // sensor-orientation coordinates
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                )
            )
            .build()
        return ImageAnalysis.Builder()
            .setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(analysisExecutor, ::analyze) }
    }

    // ── Per-frame analysis ─────────────────────────────────────────────────

    private fun analyze(proxy: ImageProxy) {
        try {
            val t0 = System.nanoTime()
            frameCount++

            // Track + decide. Motion/settle comes from the tracker itself
            // (median LK displacement; anchorless probe while Idle) — the
            // former coarse-luma-grid detector needed per-device calibration
            // and broke twice on real sensors before being deleted.
            //
            // While an acquire's OCR is chewing the little cores, track only
            // every other frame so the two don't starve each other (Moto G:
            // analysis fps fell to ~10-15 and OCR stretched to 9 s under
            // contention); the overlay keeps its last matrices on skips.
            if (acquireInFlight.get() && frameCount % 2 == 1L) return
            val cn = cnConverter.convert(proxy)
            val m = frameTracker.track(cn)
            // canAcquire keeps the engine's state machine and this session's
            // launch capacity in agreement: a request granted while an acquire
            // was already in flight used to pin the engine in ACQUIRING
            // forever (the dropped launch meant no completion ever arrived).
            // AF scans also veto acquires — a keyframe mid-scan is defocused.
            val decision = engine.onFrame(m, canAcquire = !acquireInFlight.get() && !afScanning)

            // Diagnostic heartbeat for on-device tuning.
            if (frameCount % 15 == 0L) {
                Log.d(
                    TAG,
                    "frame#%d %s inl=%d trk=%d disp=%.2f settled=%b fps~%.1f".format(
                        frameCount, decision.state, decision.inliers,
                        m.trackedPoints, m.medianDispPx, decision.settled,
                        heartbeatFps(),
                    ),
                )
            }

            if (decision.requestAcquire && acquireInFlight.compareAndSet(false, true)) {
                val keyframe = toUprightBitmap(proxy)
                val cnKeyframe = cn.clone()
                val gen = generation.get()
                Log.d(TAG, "acquire: keyframe ${keyframe.width}x${keyframe.height} state=${decision.state}")
                scope.launch(Dispatchers.Default) { runAcquire(keyframe, cnKeyframe, gen) }
            }

            publish(decision, (System.nanoTime() - t0) / 1e6)
        } finally {
            proxy.close()
        }
    }

    /** Push the frame's homographies (CN→AU-conjugated) and pill text to main. */
    private fun publish(decision: FrameDecision, frameMs: Double) {
        val anchorScale = frameTracker.currentAnchor()?.cnScale
        val hAu = if (decision.hCn != null && anchorScale != null) {
            Homography.cnToAu(decision.hCn, anchorScale)
        } else null
        val perRegionAu = if (hAu != null && decision.perRegionHCn.isNotEmpty()) {
            decision.perRegionHCn.mapValues { Homography.cnToAu(it.value, anchorScale!!) }
        } else emptyMap()
        val pill = if (frameCount % PILL_EVERY == 0L && statusSink != null) {
            "%s inl=%d rg=%d sc=%.2f %.1fms".format(
                decision.state, decision.inliers, decision.perRegionHCn.size,
                decision.scale, frameMs,
            )
        } else null
        overlayHost.post {
            warpView?.applyHomography(hAu, perRegionAu)
            pill?.let { statusSink?.invoke(it) }
        }
    }

    /** RGBA ImageProxy → upright ARGB_8888 Bitmap (AnalysisUpright space).
     *  Handles rowStride padding, then rotates upright. Keyframes only. */
    private fun toUprightBitmap(proxy: ImageProxy): Bitmap {
        val plane = proxy.planes[0]
        val rowStridePx = plane.rowStride / plane.pixelStride
        plane.buffer.rewind()
        var bmp = Bitmap.createBitmap(rowStridePx, proxy.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(plane.buffer)
        if (rowStridePx != proxy.width) {
            val cropped = Bitmap.createBitmap(bmp, 0, 0, proxy.width, proxy.height)
            bmp.recycle()
            bmp = cropped
        }
        val rotation = proxy.imageInfo.rotationDegrees
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            bmp.recycle()
            bmp = rotated
        }
        return bmp
    }

    // ── Acquire pipeline (off the frame path) ──────────────────────────────

    private suspend fun runAcquire(keyframe: Bitmap, cnKeyframe: Mat, gen: Int) {
        var installed = false
        try {
            prewarmJob.join() // never race the engine's lazy construction
            val sourceLang = SourceLanguageProfiles[prefs.sourceLangId].translationCode
            val t0 = System.currentTimeMillis()
            val ocr = OcrManager.instance.recognise(
                keyframe,
                sourceLang,
                screenshotWidth = keyframe.width,
            )
            if (gen != generation.get()) return
            val rawCount = ocr?.groups?.size ?: 0
            val groups = ocr?.let { usableGroups(it, keyframe.width, keyframe.height) }.orEmpty()
            Log.d(
                TAG,
                "acquire: OCR $rawCount groups (${groups.size} usable) in ${System.currentTimeMillis() - t0}ms " +
                    "(engine=${ocr?.engineBackend ?: "ml-kit-floor/none"})",
            )

            val oldKeyframe: Bitmap?
            synchronized(stateLock) {
                // The GATED result is what everyone downstream must see (both
                // flavors, re-flavor on toggle, track regions) — one group set.
                cachedOcr = ocr?.copy(groups = groups)
                oldKeyframe = cachedKeyframe
                cachedKeyframe = keyframe
            }
            oldKeyframe?.recycle()

            if (groups.isEmpty()) {
                onAnalysisThread { engine.onAcquireFinished(locked = false) }
                withContext(Dispatchers.Main) { warpView?.clearRegions() }
                return
            }

            // Anchor install first (fast, ~15 ms) so tracking starts while
            // rasterization/translation still run.
            installed = true
            onAnalysisThread {
                val anchor = frameTracker.buildAnchor(
                    cnKeyframe,
                    nextAnchorId.getAndIncrement(),
                    keyframe.width, keyframe.height,
                    cnConverter.cnScale,
                    System.currentTimeMillis(),
                )
                val seeded = frameTracker.installAnchor(anchor, cnKeyframe)
                engine.onAcquireFinished(locked = seeded >= TrackerConfig.MIN_INLIERS_ACQUIRE)
                Log.d(TAG, "acquire: anchor #${anchor.id} seeded $seeded correspondences")
            }

            buildAndShow(ocr!!.copy(groups = groups), keyframe, gen)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "acquire failed", e)
            onAnalysisThread { engine.onAcquireFinished(locked = false) }
        } finally {
            if (!installed) cnKeyframe.release() else onAnalysisThread { cnKeyframe.release() }
            acquireInFlight.set(false)
        }
    }

    /**
     * Camera-frame quality gate. OCR output is deliberately NOT trusted here
     * (camera frames — unlike screenshots — carry blur, rotation, and
     * frame-edge clipping the engines weren't tuned for):
     *  - drop groups whose known line confidences average below
     *    [MIN_GROUP_CONFIDENCE] (garbage reads translate into fluent
     *    nonsense); engines reporting no confidence are not gated;
     *  - drop groups clipped at the frame edge on their reading axis
     *    (the line continues off-frame; the fragment reads as a non
     *    sequitur once translated).
     */
    private fun usableGroups(
        ocr: OcrManager.OcrResult,
        auWidth: Int,
        auHeight: Int,
    ): List<OcrManager.OcrGroup> = ocr.groups.filter { g ->
        if (g.text.isBlank()) return@filter false
        val known = g.lines.map { it.confidence }.filter { it >= 0f }
        if (known.isNotEmpty() && known.average() < MIN_GROUP_CONFIDENCE) {
            Log.d(TAG, "gate: dropped low-confidence (%.2f) group \"%s\"".format(known.average(), g.text.take(40)))
            return@filter false
        }
        if (known.isNotEmpty()) {
            // Kept-group confidences calibrate the threshold: we need to know
            // where GOOD reads sit on this device, not just the bad ones.
            Log.d(TAG, "gate: kept (%.2f) group \"%s\"".format(known.average(), g.text.take(40)))
        }
        val clipped = when (g.orientation) {
            com.playtranslate.language.TextOrientation.VERTICAL ->
                g.bounds.top <= EDGE_MARGIN_PX || g.bounds.bottom >= auHeight - EDGE_MARGIN_PX
            else ->
                g.bounds.left <= EDGE_MARGIN_PX || g.bounds.right >= auWidth - EDGE_MARGIN_PX
        }
        if (clipped) {
            Log.d(TAG, "gate: dropped edge-clipped group \"${g.text.take(40)}\"")
            return@filter false
        }
        true
    }

    /** Run [block] on the analysis executor (the only thread allowed to touch
     *  [frameTracker]/[engine]) and await completion. */
    private suspend fun onAnalysisThread(block: () -> Unit) {
        if (analysisExecutor.isShutdown) return
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            analysisExecutor.execute {
                try {
                    block()
                } finally {
                    if (cont.isActive) cont.resume(Unit) {}
                }
            }
        }
    }

    /** Build boxes for the current [Prefs.overlayMode], register the flavor's
     *  tracked regions (groups for translation, lines for reading — the
     *  per-region homography units), rasterize, and hand the raster regions
     *  to the warp view. Two-phase skeleton→filled for the translation
     *  flavor. */
    private suspend fun buildAndShow(ocr: OcrManager.OcrResult, keyframe: Bitmap, gen: Int) {
        when (prefs.overlayMode) {
            OverlayMode.TRANSLATION -> {
                val groups = ocr.groups.filter { it.text.isNotBlank() }
                val texts = groups.map { it.text }
                // One tracked region per group: key = group index.
                val trackKeys = groups.indices.toList()
                installTrackRegions(groups.mapIndexed { idx, g -> idx to g.bounds })

                val placeholders = buildPlaceholderBoxes(groups, keyframe)
                showRegions(placeholders, trackKeys, keyframe, gen)

                val t0 = System.currentTimeMillis()
                val translations = translator.translate(texts)
                Log.d(TAG, "acquire: translated ${texts.size} groups in ${System.currentTimeMillis() - t0}ms")
                // Quality forensics: the OCR text and its translation, so
                // "bad output" can be attributed to reading vs translating.
                texts.forEachIndexed { i, src ->
                    Log.d(TAG, "acquire text[$i]: \"${src.take(120)}\" -> \"${translations.getOrElse(i) { "" }.take(120)}\"")
                }
                if (gen != generation.get()) return
                val filled = placeholders.mapIndexed { idx, ph ->
                    ph.copy(translatedText = translations.getOrElse(idx) { "" })
                }
                showRegions(filled, trackKeys, keyframe, gen)
            }
            OverlayMode.FURIGANA -> {
                val engine = SourceLanguageEngines.get(context, prefs.sourceLangId)
                val furigana = OverlayToolkit.buildFuriganaBoxesByGroup(ocr, engine, furiganaPaint)
                // One tracked region per OCR LINE (reading marks ride their
                // line's plane); each furigana box keys to its nearest line.
                val lineRegions = mutableListOf<Pair<Int, android.graphics.Rect>>()
                val lineKeysByGroup = HashMap<android.graphics.Rect, List<Pair<Int, android.graphics.Rect>>>()
                var lineKey = 0
                for (group in ocr.groups) {
                    val keyed = group.lines.map { line -> (lineKey++) to line.bounds }
                    lineRegions.addAll(keyed)
                    lineKeysByGroup[group.bounds] = keyed
                }
                installTrackRegions(lineRegions)

                val boxes = mutableListOf<TextBox>()
                val trackKeys = mutableListOf<Int>()
                for (fg in furigana) {
                    val groupLines = lineKeysByGroup[fg.groupBounds].orEmpty()
                    for (box in fg.boxes) {
                        boxes.add(box)
                        trackKeys.add(nearestLineKey(box, groupLines))
                    }
                }
                showRegions(boxes, trackKeys, keyframe, gen)
            }
        }
    }

    /** Key of the line rect nearest to [box]'s center (reading marks sit
     *  adjacent to — not inside — their line), or -1 when none. */
    private fun nearestLineKey(
        box: TextBox,
        lines: List<Pair<Int, android.graphics.Rect>>,
    ): Int {
        var bestKey = -1
        var bestDist = Long.MAX_VALUE
        val cx = box.bounds.centerX()
        val cy = box.bounds.centerY()
        for ((key, rect) in lines) {
            val dx = maxOf(0, rect.left - cx, cx - rect.right).toLong()
            val dy = maxOf(0, rect.top - cy, cy - rect.bottom).toLong()
            val d = dx * dx + dy * dy
            if (d < bestDist) {
                bestDist = d
                bestKey = key
            }
        }
        return bestKey
    }

    /** Register the flavor's warp units with the tracker as anchor-CN rects
     *  (AU rects × the anchor's cnScale). Serialized onto the analysis
     *  thread; no-op when no anchor is installed yet. */
    private suspend fun installTrackRegions(auRegions: List<Pair<Int, android.graphics.Rect>>) {
        onAnalysisThread {
            val cs = frameTracker.currentAnchor()?.cnScale ?: return@onAnalysisThread
            frameTracker.setTrackRegions(
                auRegions.map { (key, r) ->
                    key to android.graphics.Rect(
                        (r.left * cs).toInt(), (r.top * cs).toInt(),
                        (r.right * cs).toInt(), (r.bottom * cs).toInt(),
                    )
                }
            )
        }
    }

    private fun buildPlaceholderBoxes(
        groups: List<OcrManager.OcrGroup>,
        keyframe: Bitmap,
    ): List<TextBox> {
        val colorScale = 4
        val bounds = groups.map { it.bounds }
        val colorRef = keyframe.scale(keyframe.width / colorScale, keyframe.height / colorScale, false)
        val colors: List<Pair<Int, Int>>
        try {
            colors = OverlayToolkit.sampleGroupColors(colorRef, bounds, 0, 0, colorScale)
        } finally {
            colorRef.recycle()
        }
        return groups.mapIndexed { idx, group ->
            val (bg, tc) = colors.getOrElse(idx) { Pair(Color.argb(224, 0, 0, 0), Color.WHITE) }
            TextBox(
                translatedText = "",
                bounds = group.bounds,
                bgColor = bg,
                textColor = tc,
                lineCount = group.lines.size,
                sourceText = group.text,
                orientation = group.orientation,
                alignment = group.alignment,
            )
        }
    }

    // ── Overlay display ────────────────────────────────────────────────────

    /** Rasterize AU-space boxes (main thread — view machinery) and install
     *  them in the warp view. [trackKeys] parallels [boxes]. */
    private suspend fun showRegions(
        boxes: List<TextBox>,
        trackKeys: List<Int>,
        keyframe: Bitmap,
        gen: Int,
    ) {
        withContext(Dispatchers.Main) {
            if (gen != generation.get()) return@withContext
            val rasterizer = OverlayRasterizer(
                context,
                verticalTextTarget = targetSupportsVerticalText(prefs.targetLang),
                verticalTextStackable = stackableTargetScript(prefs.targetLang),
                verticalGrowEnabled = prefs.verticalTextGrow,
            )
            val regions: List<RasterRegion> =
                rasterizer.rasterize(boxes, keyframe.width, keyframe.height, trackKeys)
            ensureWarpView().setRegions(regions, keyframe.width, keyframe.height)
        }
    }

    private fun ensureWarpView(): WarpOverlayView {
        warpView?.let { return it }
        val view = WarpOverlayView(context)
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

    // ── External events ────────────────────────────────────────────────────

    /** Overlay-mode toggle: re-flavor from the cached OCR result — no re-OCR,
     *  no anchor change; the tracker keeps running. */
    fun onOverlayModeChanged() {
        val gen = generation.incrementAndGet()
        val (ocr, keyframe) = synchronized(stateLock) { cachedOcr to cachedKeyframe }
        if (ocr == null || keyframe == null || keyframe.isRecycled) return
        scope.launch(Dispatchers.Default) {
            try {
                buildAndShow(ocr, keyframe, gen)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "re-flavor failed", e)
            }
        }
    }

    /** Language/config change: drop everything; the next settled frame
     *  re-OCRs from scratch. */
    fun reset() {
        generation.incrementAndGet()
        val oldKeyframe: Bitmap?
        synchronized(stateLock) {
            cachedOcr = null
            oldKeyframe = cachedKeyframe
            cachedKeyframe = null
        }
        oldKeyframe?.recycle()
        analysisExecutor.execute {
            frameTracker.clearAnchor()
            engine.reset()
        }
        overlayHost.post { warpView?.clearRegions() }
    }

    /** Final teardown from the Activity. Not restartable. */
    fun shutdown() {
        generation.incrementAndGet()
        analysisExecutor.execute {
            frameTracker.release()
            cnConverter.release()
        }
        analysisExecutor.shutdown()
        val oldKeyframe: Bitmap?
        synchronized(stateLock) {
            cachedOcr = null
            oldKeyframe = cachedKeyframe
            cachedKeyframe = null
        }
        oldKeyframe?.recycle()
    }
}
