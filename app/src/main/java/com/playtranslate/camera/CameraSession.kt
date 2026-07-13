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

        /** Downscale factor of the color-sampling reference bitmap. */
        const val COLOR_SCALE = 4

        /** Re-raster for crispness when tracked scale drifts this far from
         *  the raster's native scale (either direction). */
        const val RASTER_SCALE_DRIFT = 1.3f

        /** Sustained anchor-less IDLE frames before the analysis rate halves
         *  (~12 s at 25 fps). Resets the moment anything locks. */
        const val IDLE_BACKOFF_AFTER_FRAMES = 300

        /** Groups whose known line confidences average below the engine's
         *  threshold are dropped before translation — garbage reads (rotated
         *  text, blur) translate into fluent-sounding nonsense otherwise.
         *  Engines that report no confidence (-1) are never gated.
         *  Thresholds are per-engine-family, calibrated from device logs
         *  (2026-07-07 Moto G): ML Kit good reads sit 0.76-0.84; Meiki
         *  garbage sat 0.32-0.45. Extend as kept/dropped logs accumulate. */
        const val MIN_GROUP_CONFIDENCE_DEFAULT = 0.5f
        const val MIN_GROUP_CONFIDENCE_MLKIT = 0.6f

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
     *  it before PUBLISHING so stale display work drops silently. Acquire
     *  lifecycle itself is owned by the engine (begin/finish ids) — this
     *  guards only the display path. */
    private val generation = AtomicInteger(0)
    private val nextAnchorId = AtomicLong(1L)

    /** The in-flight acquire's coroutine. Cancellation-first: invalidation
     *  (mode toggle, language reset, engine abandonment) CANCELS the work —
     *  5-16 s of OCR for a scene nobody wants — instead of letting it run to
     *  completion and discarding the result at commit points. The id guards
     *  remain as the backstop for anything that slips through. */
    @Volatile
    private var acquireJob: kotlinx.coroutines.Job? = null

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

    /** Consecutive frames the engine has reported IDLE (analysis thread).
     *  Sustained idling halves the analysis rate — no reason to burn
     *  full-rate CPU/battery pointing at a couch. */
    private var idleStreak = 0

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

    /** ×4-downscaled keyframe for background-color sampling — all the
     *  re-flavor path needs (the full-res keyframe is recycled right after
     *  OCR; 0.5 MB instead of 8 MB). */
    private var cachedColorRef: Bitmap? = null
    private var cachedAuW = 0
    private var cachedAuH = 0

    /** The display payload of the currently anchored scene once its final
     *  (filled) boxes exist — what the anchor LRU stores alongside the
     *  anchor for instant re-display on re-lock. */
    private var lastBuilt: BuiltOverlays? = null

    private class BuiltOverlays(
        val ocr: OcrManager.OcrResult,
        val boxes: List<TextBox>,
        val trackKeys: List<Int>,
        val trackRegionsAu: List<Pair<Int, android.graphics.Rect>>,
        val colorRef: Bitmap,
        val auW: Int,
        val auH: Int,
        val mode: OverlayMode,
        val langKey: String,
    )

    /** Recently replaced scenes (anchor + display payload), newest last.
     *  Analysis-thread only; anchors own native Mats → release on evict. */
    private val anchorCache = ArrayDeque<Pair<com.playtranslate.camera.tracker.Anchor, BuiltOverlays>>()
    private var relockCursor = 0

    private fun langKey(): String =
        "${prefs.sourceLangId}|${prefs.targetLang}|${prefs.targetChineseVariant}"

    /** The warp surface; created lazily on main. */
    private var warpView: WarpOverlayView? = null

    // Last-shown raster state (MAIN THREAD only): feeds the dirty diff and
    // the crispness re-raster on scale drift.
    private var lastShownBoxes: List<TextBox>? = null
    private var lastShownKeys: List<Int> = emptyList()
    private var lastShownRegions: List<RasterRegion>? = null
    private var lastShownAuW = 0
    private var lastShownAuH = 0
    private var rasterScale = 1f
    private var rerasterPending = false

    /** Debug status sink (the on-screen pill); set by the Activity in debug
     *  builds. Called on the main thread. */
    var statusSink: ((String) -> Unit)? = null

    /** User-facing hint sink (production): non-null shows the message, null
     *  hides it. A scan that finds nothing usable must SAY so — silence
     *  reads as "broken". Called on the main thread. */
    var hintSink: ((show: Boolean) -> Unit)? = null

    private fun postHint(show: Boolean) {
        overlayHost.post { hintSink?.invoke(show) }
    }

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

    /** Build the analysis use case for the activity to bind. YUV output —
     *  the luma plane IS the tracker's gray channel for free, where
     *  RGBA_8888 made CameraX run a YUV→RGBA conversion EVERY frame plus a
     *  full-res cvtColor on our side to throw the color back away; color is
     *  only needed once per acquire (keyframe + color ref) and is converted
     *  there. 16:9 to match the Preview use case (shared FOV + deterministic
     *  FILL_CENTER mapping). */
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
            // Engine state IS the acquire-in-flight truth (single writer).
            if (engine.state == TrackState.ACQUIRING && frameCount % 2 == 1L) return
            // Thermal/battery backoff: sustained anchor-less idling halves
            // the analysis rate (settle just takes 2× the frames to open).
            if (idleStreak > IDLE_BACKOFF_AFTER_FRAMES && frameCount % 2 == 1L) return
            val cn = cnConverter.convert(proxy)
            val m = frameTracker.track(cn)
            // canAcquire is the engine's documented launch-capacity contract:
            // AF scans veto offers (a keyframe mid-scan is defocused), and so
            // does a live acquire job — the engine leaves ACQUIRING at anchor
            // install, but the job's display tail (translation, rasterize)
            // still describes the PREVIOUS acquire. A second acquire started
            // under it interleaves showRegions calls and can pair the new
            // anchor with the old scene's payload in the LRU.
            val decision = engine.onFrame(
                m,
                canAcquire = !afScanning && acquireJob?.isActive != true,
            )

            // Keep tracker and engine agreeing about anchor existence. When
            // the engine settles on IDLE (dead anchor, lost-decay, watchdog)
            // the tracker must drop its anchor too — otherwise track() keeps
            // futilely rematching the corpse instead of running the motion
            // probe, the median displacement stays unknown, the settle gate
            // never opens, and IDLE becomes permanent (observed: a minute of
            // disp=-1 with text on screen).
            idleStreak = if (decision.state == TrackState.IDLE) idleStreak + 1 else 0

            if (decision.state == TrackState.IDLE && frameTracker.hasAnchor()) {
                frameTracker.clearAnchor()
            }

            // Engine IDLE means it is not waiting on ANY acquire (watchdog
            // fired, failed completion): a still-running acquire coroutine is
            // an orphan burning OCR time for a result nothing will accept.
            // (Runs before this frame's own launch below, so it can only
            // cancel a PREVIOUS orphan, never the acquire it starts.)
            if (decision.state == TrackState.IDLE) {
                acquireJob?.takeIf { it.isActive }?.cancel()
            }

            // Anchor LRU: while Idle, periodically probe one cached scene —
            // glancing back at known text re-locks with zero OCR/translation.
            val relocked = decision.state == TrackState.IDLE && !frameTracker.hasAnchor() &&
                anchorCache.isNotEmpty() &&
                frameCount % TrackerConfig.RELOCK_PROBE_INTERVAL_FRAMES == 0L &&
                tryRelock(cn)

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

            if (decision.requestAcquire && !relocked) {
                // A decision is an OFFER; the engine transitions only when we
                // commit to launching (beginAcquire), and completions must
                // quote the id — stale ones are structurally ignored. An offer
                // computed BEFORE a successful relock this same frame is
                // stale: the engine is now LOCKED on the restored anchor, and
                // launching would immediately re-OCR the scene the relock
                // just restored for free.
                val acquireId = engine.beginAcquire()
                if (acquireId != 0L) {
                    val buffers = AcquireBuffers(toUprightBitmap(proxy), cn.clone())
                    val gen = generation.get()
                    Log.d(TAG, "acquire#$acquireId: keyframe ${buffers.keyframe.width}x${buffers.keyframe.height}")
                    // ATOMIC: a cancel that lands before first dispatch (mode
                    // toggle/reset in that window) must still run the body up
                    // to its first suspension, so the finally can close the
                    // buffers and complete the engine's acquire — a silently
                    // skipped body leaks the keyframe and pins ACQUIRING
                    // until the 30 s watchdog.
                    acquireJob = scope.launch(Dispatchers.Default, kotlinx.coroutines.CoroutineStart.ATOMIC) {
                        runAcquire(buffers, gen, acquireId)
                    }
                }
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
            if (decision.state == TrackState.LOCKED && decision.scale > 0f) {
                maybeRerasterForScale(decision.scale)
            }
            pill?.let { statusSink?.invoke(it) }
        }
    }

    /** YUV ImageProxy → upright ARGB_8888 Bitmap (AnalysisUpright space).
     *  [ImageProxy.toBitmap] does the YUV→RGB conversion (plane strides
     *  included); we rotate upright. Keyframes only — once per acquire. */
    private fun toUprightBitmap(proxy: ImageProxy): Bitmap {
        var bmp = proxy.toBitmap()
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

    /**
     * Owns every buffer an acquire snapshots, with ONE close path and
     * explicit ownership transfer — buffer-lifecycle mistakes on early-exit
     * branches produced findings in two separate review rounds; grouping
     * makes the category unwritable rather than carefully written.
     */
    private inner class AcquireBuffers(
        val keyframe: Bitmap,
        val cnKeyframe: Mat,
    ) {
        var colorRef: Bitmap? = null
            private set
        private var colorRefKept = false

        val auW = keyframe.width
        val auH = keyframe.height

        /** Create the ×4 color reference and immediately drop the multi-MB
         *  keyframe — nothing downstream needs full resolution. */
        fun deriveColorRef(): Bitmap {
            val c = keyframe.scale(auW / COLOR_SCALE, auH / COLOR_SCALE, false)
            colorRef = c
            keyframe.recycle()
            return c
        }

        /** Transfer colorRef ownership to the session cache / LRU payload —
         *  close() will no longer touch it. */
        fun keepColorRef() {
            colorRefKept = true
        }

        /** The single close path, safe on every exit (early return,
         *  exception, cancellation): recycles what wasn't transferred; the
         *  cnKeyframe Mat release is serialized onto the analysis executor
         *  behind any pending install block that may still be using it. */
        fun close() {
            if (!keyframe.isRecycled) keyframe.recycle()
            colorRef?.takeIf { !colorRefKept && !it.isRecycled }?.recycle()
            try {
                if (!analysisExecutor.isShutdown) {
                    analysisExecutor.execute { cnKeyframe.release() }
                    return
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // shutdown() raced the isShutdown check. Fall through.
            }
            // Direct release is safe here: close() only runs in runAcquire's
            // finally, so if the install block is still queued we got here
            // via cancellation — and the block refuses on its job-liveness
            // check before ever touching this Mat.
            cnKeyframe.release()
        }
    }

    private suspend fun runAcquire(buffers: AcquireBuffers, gen: Int, acquireId: Long) {
        var installed = false
        val cnKeyframe = buffers.cnKeyframe
        val auW = buffers.auW
        val auH = buffers.auH
        // The install block runs detached on the analysis executor; it checks
        // this job's liveness so a cancellation that lands mid-await can't
        // have its anchor installed after the fact.
        val selfJob = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
        try {
            prewarmJob.join() // never race the engine's lazy construction
            val sourceLang = SourceLanguageProfiles[prefs.sourceLangId].translationCode
            val t0 = System.currentTimeMillis()
            val ocr = OcrManager.instance.recognise(
                buffers.keyframe,
                sourceLang,
                screenshotWidth = auW,
                regionPreFilter = cameraRegionPreFilter(),
            )
            if (gen != generation.get()) return
            val rawCount = ocr?.groups?.size ?: 0
            val groups = ocr?.let { usableGroups(it, auW, auH) }.orEmpty()
            Log.d(
                TAG,
                "acquire: OCR $rawCount groups (${groups.size} usable) in ${System.currentTimeMillis() - t0}ms " +
                    "(engine=${ocr?.engineBackend ?: "ml-kit-floor/none"})",
            )

            val colorRef = buffers.deriveColorRef()
            val gated = ocr?.copy(groups = groups)

            if (groups.isEmpty()) {
                // No text in this scene: the re-flavor cache must not keep
                // describing a previous one. finally completes the acquire.
                synchronized(stateLock) {
                    cachedOcr = null
                    cachedColorRef = null
                    lastBuilt = null
                }
                withContext(Dispatchers.Main) { warpView?.clearRegions() }
                postHint(true)
                return
            }
            postHint(false)

            // Anchor install first (fast, ~15 ms) so tracking starts while
            // rasterization/translation still run. The engine's active-id
            // check makes a stale completion (watchdog fired, reset) a no-op
            // instead of a resurrection.
            installed = onAnalysisThread {
                if (!engine.isAcquireActive(acquireId)) return@onAnalysisThread false
                if (selfJob?.isActive == false) return@onAnalysisThread false
                // The replaced scene goes to the LRU (with its display
                // payload) instead of being released — glancing back at it
                // re-locks without re-OCR.
                frameTracker.detachAnchor()?.let { old ->
                    val payload = synchronized(stateLock) { lastBuilt }
                    if (payload != null) cacheScene(old, payload) else old.release()
                }
                synchronized(stateLock) { lastBuilt = null }
                val anchor = frameTracker.buildAnchor(
                    cnKeyframe,
                    nextAnchorId.getAndIncrement(),
                    auW, auH,
                    cnConverter.cnScale,
                    System.currentTimeMillis(),
                )
                val seeded = frameTracker.installAnchor(anchor, cnKeyframe)
                val locked = seeded >= TrackerConfig.MIN_INLIERS_ACQUIRE
                engine.finishAcquire(acquireId, locked = locked)
                Log.d(TAG, "acquire#$acquireId: anchor #${anchor.id} verified $seeded live-frame inliers locked=$locked")
                if (!locked) {
                    // Live-frame verification failed (user moved away during
                    // the slow OCR): the scene this keyframe describes is
                    // GONE. Drop the dead anchor now; returning false below
                    // stops its OCR output from being rasterized/cached/shown.
                    frameTracker.clearAnchor()
                }
                locked
            } ?: false

            if (!installed) return
            // The re-flavor cache describes the CURRENTLY ANCHORED scene —
            // set only after the lock actually succeeded. colorRef ownership
            // transfers to the cache here.
            buffers.keepColorRef()
            synchronized(stateLock) {
                cachedOcr = gated
                cachedColorRef = colorRef
                cachedAuW = auW
                cachedAuH = auH
            }
            buildAndShow(gated!!, colorRef, auW, auH, gen)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "acquire failed", e)
        } finally {
            // TOTAL completion + single buffer close path, on every exit —
            // early returns, exceptions, and coroutine cancellation alike.
            // Both are fire-and-forget (suspend calls throw immediately in a
            // cancelled coroutine) and idempotent (the finish is a no-op when
            // the install path already completed this id).
            buffers.close()
            try {
                if (!analysisExecutor.isShutdown) {
                    analysisExecutor.execute { engine.finishAcquire(acquireId, locked = false) }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // shutdown() raced the check; the engine died with the
                // executor, so there is nothing left to complete.
            }
        }
    }

    /** Fraction of the image dimension that counts as "touching the edge"
     *  for pre-recognition gating (mirrors [EDGE_MARGIN_PX] post-OCR). */
    private val edgeMarginFrac = 0.012f

    /**
     * Detection-stage gate + priority order, applied INSIDE composite
     * engines between detect and recognize (recognition is the expensive
     * stage — ~400 ms/line on budget-SoC Paddle; one acquire paid for 28
     * discarded lines before this existed):
     *  - when translating, drop detections clipped at the frame edge on
     *    their reading axis (their groups would be gated post-OCR anyway);
     *  - recognize center-out, so if a future incremental-display path (or
     *    a cancellation) cuts the pass short, the text the user is aiming
     *    at is what got recognized.
     */
    private fun cameraRegionPreFilter(): com.playtranslate.ocr.core.RegionPreFilter {
        val translating =
            SourceLanguageProfiles[prefs.sourceLangId].translationCode != prefs.targetLang
        return com.playtranslate.ocr.core.RegionPreFilter { regions, w, h ->
            val mx = (w * edgeMarginFrac).toInt()
            val my = (h * edgeMarginFrac).toInt()
            val kept = if (!translating) regions else regions.filter { r ->
                val b = r.box.bounds
                val clipped = when (r.orientation) {
                    com.playtranslate.language.TextOrientation.VERTICAL ->
                        b.top <= my || b.bottom >= h - my
                    else -> b.left <= mx || b.right >= w - mx
                }
                !clipped
            }
            if (kept.size != regions.size) {
                Log.d(TAG, "gate: skipped recognition for ${regions.size - kept.size} edge-clipped detections")
            }
            val cx = w / 2f
            val cy = h / 2f
            kept.sortedBy { r ->
                val b = r.box.bounds
                val dx = b.exactCenterX() - cx
                val dy = b.exactCenterY() - cy
                dx * dx + dy * dy
            }
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
    ): List<OcrManager.OcrGroup> {
        // Edge-clipped fragments only hurt when TRANSLATED (a cut-off line
        // renders as a fluent non sequitur). In same-language OCR-only mode
        // a clipped line is still honest output — and on a full-frame
        // document the edge gate would otherwise discard most of the page
        // (28 of 36 groups observed).
        val translating =
            SourceLanguageProfiles[prefs.sourceLangId].translationCode != prefs.targetLang
        val confThreshold =
            if (ocr.engineBackend?.toString()?.startsWith("MLKit") == true) MIN_GROUP_CONFIDENCE_MLKIT
            else MIN_GROUP_CONFIDENCE_DEFAULT
        return ocr.groups.filter { g ->
            if (g.text.isBlank()) return@filter false
            val known = g.lines.map { it.confidence }.filter { it >= 0f }
            if (known.isNotEmpty() && known.average() < confThreshold) {
                // Camera OCR content is PRIVATE (documents, screens) — raw
                // text never reaches production logs, only debug builds.
                if (com.playtranslate.BuildConfig.DEBUG) {
                    Log.d(TAG, "gate: dropped low-confidence (%.2f) group \"%s\"".format(known.average(), g.text.take(40)))
                }
                return@filter false
            }
            if (known.isNotEmpty() && com.playtranslate.BuildConfig.DEBUG) {
                // Kept-group confidences calibrate the threshold: we need to
                // know where GOOD reads sit on this device, not just the bad.
                Log.d(TAG, "gate: kept (%.2f) group \"%s\"".format(known.average(), g.text.take(40)))
            }
            if (translating) {
                val clipped = when (g.orientation) {
                    com.playtranslate.language.TextOrientation.VERTICAL ->
                        g.bounds.top <= EDGE_MARGIN_PX || g.bounds.bottom >= auHeight - EDGE_MARGIN_PX
                    else ->
                        g.bounds.left <= EDGE_MARGIN_PX || g.bounds.right >= auWidth - EDGE_MARGIN_PX
                }
                if (clipped) {
                    if (com.playtranslate.BuildConfig.DEBUG) {
                        Log.d(TAG, "gate: dropped edge-clipped group \"${g.text.take(40)}\"")
                    }
                    return@filter false
                }
            }
            true
        }
    }

    /** Run [block] on the analysis executor (the only thread allowed to touch
     *  [frameTracker]/[engine]) and await its result; null when shut down. */
    private suspend fun <T> onAnalysisThread(block: () -> T): T? {
        if (analysisExecutor.isShutdown) return null
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            analysisExecutor.execute {
                val result = try {
                    block()
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resume(null) {}
                    throw t
                }
                if (cont.isActive) cont.resume(result) {}
            }
        }
    }

    /** Build boxes for the current [Prefs.overlayMode], register the flavor's
     *  tracked regions (groups for translation, lines for reading — the
     *  per-region homography units), rasterize, and hand the raster regions
     *  to the warp view. Two-phase skeleton→filled for the translation
     *  flavor. */
    private suspend fun buildAndShow(
        ocr: OcrManager.OcrResult,
        colorRef: Bitmap,
        auW: Int,
        auH: Int,
        gen: Int,
    ) {
        val mode = prefs.overlayMode
        when (mode) {
            OverlayMode.TRANSLATION -> {
                val groups = ocr.groups.filter { it.text.isNotBlank() }
                val texts = groups.map { it.text }
                // One tracked region per group: key = group index.
                val trackKeys = groups.indices.toList()
                val regions = groups.mapIndexed { idx, g -> idx to g.bounds }
                installTrackRegions(regions)

                val placeholders = buildPlaceholderBoxes(groups, colorRef)
                showRegions(placeholders, trackKeys, auW, auH, gen)

                val t0 = System.currentTimeMillis()
                val translations = translator.translate(texts)
                Log.d(TAG, "acquire: translated ${texts.size} groups in ${System.currentTimeMillis() - t0}ms")
                // Quality forensics: the OCR text and its translation, so
                // "bad output" can be attributed to reading vs translating.
                // Camera content is private — DEBUG builds only, never release.
                if (com.playtranslate.BuildConfig.DEBUG) {
                    texts.forEachIndexed { i, src ->
                        Log.d(TAG, "acquire text[$i]: \"${src.take(120)}\" -> \"${translations.getOrElse(i) { "" }.take(120)}\"")
                    }
                }
                if (gen != generation.get()) return
                val filled = placeholders.mapIndexed { idx, ph ->
                    ph.copy(translatedText = translations.getOrElse(idx) { "" })
                }
                showRegions(filled, trackKeys, auW, auH, gen)
                rememberBuilt(ocr, filled, trackKeys, regions, colorRef, auW, auH, mode)
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
                showRegions(boxes, trackKeys, auW, auH, gen)
                rememberBuilt(ocr, boxes, trackKeys, lineRegions, colorRef, auW, auH, mode)
            }
        }
    }

    /** Snapshot the finished display payload so the anchor LRU can restore
     *  this scene instantly on re-lock. */
    private fun rememberBuilt(
        ocr: OcrManager.OcrResult,
        boxes: List<TextBox>,
        trackKeys: List<Int>,
        regions: List<Pair<Int, android.graphics.Rect>>,
        colorRef: Bitmap,
        auW: Int,
        auH: Int,
        mode: OverlayMode,
    ) {
        synchronized(stateLock) {
            lastBuilt = BuiltOverlays(ocr, boxes, trackKeys, regions, colorRef, auW, auH, mode, langKey())
        }
    }

    /** Push a replaced scene into the LRU (analysis thread only). */
    private fun cacheScene(anchor: com.playtranslate.camera.tracker.Anchor, payload: BuiltOverlays) {
        anchorCache.addLast(anchor to payload)
        while (anchorCache.size > TrackerConfig.ANCHOR_CACHE_SIZE) {
            anchorCache.removeFirst().first.release()
        }
    }

    /** While Idle with cached scenes, probe one per call (round-robin); on a
     *  strong ORB match, reinstall the cached anchor + its display payload —
     *  a full re-lock with zero OCR/translation. Returns true when a re-lock
     *  actually happened (the caller must then discard this frame's stale
     *  acquire offer). Analysis thread only. */
    private fun tryRelock(cn: Mat): Boolean {
        val lk = langKey()
        val mode = prefs.overlayMode
        // Entries built under a different language/flavor can't be shown;
        // drop them (release native Mats) rather than probing them forever.
        val it = anchorCache.iterator()
        while (it.hasNext()) {
            val (a, p) = it.next()
            if (p.langKey != lk || p.mode != mode) {
                a.release()
                it.remove()
            }
        }
        if (anchorCache.isEmpty()) return false
        relockCursor %= anchorCache.size
        val (anchor, payload) = anchorCache.elementAt(relockCursor)
        relockCursor++
        // RANSAC-verified inliers, not raw descriptor matches: repetitive
        // text patterns match plentifully without agreeing on any geometry,
        // and a false re-lock shows stale translations over the wrong scene
        // (and destroys the cache entry). Verification happens BEFORE the
        // entry is consumed, and the successful probe IS the install — its
        // verified correspondences and fitted H carry over, so no second
        // ORB pass and no identity-position seeding of a re-aimed view.
        val probe = frameTracker.probeAnchor(anchor, cn) ?: return false
        if (probe.inliers < TrackerConfig.MIN_INLIERS_ACQUIRE) return false

        val id = engine.beginAcquire()
        if (id == 0L) return false
        anchorCache.remove(anchor to payload)
        val seeded = frameTracker.installFromProbe(anchor, probe)
        // installFromProbe cannot fail: the probe already verified the lock
        // criterion this call sits behind.
        engine.finishAcquire(id, locked = true)
        Log.d(TAG, "relock: anchor #${anchor.id} restored with $seeded verified inliers")

        synchronized(stateLock) {
            cachedOcr = payload.ocr
            cachedColorRef = payload.colorRef
            cachedAuW = payload.auW
            cachedAuH = payload.auH
            lastBuilt = payload
        }
        val gen = generation.get()
        // Tracked as THE acquire job: the relock's display tail is acquire
        // display work like any other — canAcquire stays false until it
        // lands, and mode/language invalidation can cancel it.
        acquireJob = scope.launch(Dispatchers.Default) {
            try {
                installTrackRegions(payload.trackRegionsAu)
                showRegions(payload.boxes, payload.trackKeys, payload.auW, payload.auH, gen)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "relock display failed", e)
            }
        }
        return true
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
            engine.onRegionsReplaced()
        }
    }

    private fun buildPlaceholderBoxes(
        groups: List<OcrManager.OcrGroup>,
        colorRef: Bitmap,
    ): List<TextBox> {
        val bounds = groups.map { it.bounds }
        val colors = OverlayToolkit.sampleGroupColors(colorRef, bounds, 0, 0, COLOR_SCALE)
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
        auW: Int,
        auH: Int,
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
            // Dirty diff against the last show at the same keyframe size —
            // a skeleton→filled swap re-renders only the boxes that changed.
            val previous = if (lastShownAuW == auW && lastShownAuH == auH) lastShownRegions else null
            val regions: List<RasterRegion> =
                rasterizer.rasterize(boxes, auW, auH, trackKeys, renderScale = 1f, previous = previous)
            ensureWarpView().setRegions(regions, auW, auH)
            lastShownBoxes = boxes
            lastShownKeys = trackKeys
            lastShownRegions = regions
            lastShownAuW = auW
            lastShownAuH = auH
            rasterScale = 1f
        }
    }

    /** Crispness re-raster: when the tracked scale has drifted well past the
     *  raster's native resolution, re-render the same boxes super-sampled
     *  (off the frame path; the warp keeps running on the old bitmaps until
     *  the swap). Main thread. */
    private fun maybeRerasterForScale(trackedScale: Float) {
        if (rerasterPending) return
        val boxes = lastShownBoxes ?: return
        val ratio = if (trackedScale > rasterScale) trackedScale / rasterScale else rasterScale / trackedScale
        if (ratio < RASTER_SCALE_DRIFT) return
        rerasterPending = true
        val keys = lastShownKeys
        val auW = lastShownAuW
        val auH = lastShownAuH
        val targetScale = trackedScale.coerceIn(0.5f, 2.5f)
        val gen = generation.get()
        scope.launch(Dispatchers.Main) {
            try {
                if (gen != generation.get() || lastShownBoxes !== boxes) return@launch
                val rasterizer = OverlayRasterizer(
                    context,
                    verticalTextTarget = targetSupportsVerticalText(prefs.targetLang),
                    verticalTextStackable = stackableTargetScript(prefs.targetLang),
                    verticalGrowEnabled = prefs.verticalTextGrow,
                )
                val regions = rasterizer.rasterize(boxes, auW, auH, keys, renderScale = targetScale)
                ensureWarpView().setRegions(regions, auW, auH)
                lastShownRegions = regions
                rasterScale = targetScale
            } finally {
                rerasterPending = false
            }
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
        // Cancellation-first: the in-flight acquire (possibly seconds of OCR)
        // was for the OLD flavor; kill it rather than discarding its result
        // later. Its finally completes the engine's acquire as failed, so the
        // next settle re-acquires under the new flavor.
        acquireJob?.cancel()
        val gen = generation.incrementAndGet()
        val ocr: OcrManager.OcrResult?
        val colorRef: Bitmap?
        val auW: Int
        val auH: Int
        synchronized(stateLock) {
            ocr = cachedOcr
            colorRef = cachedColorRef
            auW = cachedAuW
            auH = cachedAuH
        }
        if (ocr == null || colorRef == null || colorRef.isRecycled || auW == 0) return
        scope.launch(Dispatchers.Default) {
            try {
                buildAndShow(ocr, colorRef, auW, auH, gen)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "re-flavor failed", e)
            }
        }
    }

    /** Language/config change: drop everything; the next settled frame
     *  re-OCRs from scratch. */
    fun reset() {
        acquireJob?.cancel()
        generation.incrementAndGet()
        synchronized(stateLock) {
            cachedOcr = null
            cachedColorRef = null
            cachedAuW = 0
            cachedAuH = 0
            lastBuilt = null
        }
        analysisExecutor.execute {
            frameTracker.clearAnchor()
            engine.reset()
            while (anchorCache.isNotEmpty()) anchorCache.removeFirst().first.release()
        }
        overlayHost.post {
            warpView?.clearRegions()
            lastShownBoxes = null
            lastShownRegions = null
            lastShownKeys = emptyList()
            lastShownAuW = 0
            lastShownAuH = 0
            rasterScale = 1f
        }
    }

    /** Final teardown from the Activity. Not restartable. */
    fun shutdown() {
        generation.incrementAndGet()
        analysisExecutor.execute {
            frameTracker.release()
            cnConverter.release()
            while (anchorCache.isNotEmpty()) anchorCache.removeFirst().first.release()
        }
        analysisExecutor.shutdown()
        synchronized(stateLock) {
            cachedOcr = null
            cachedColorRef = null
            lastBuilt = null
        }
    }
}
