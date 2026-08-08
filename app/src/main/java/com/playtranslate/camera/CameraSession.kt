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
import com.playtranslate.CaptureSession
import com.playtranslate.CaptureState
import com.playtranslate.OcrManager
import com.playtranslate.OneShotOverlayData
import com.playtranslate.OverlayMode
import com.playtranslate.OverlayToolkit
import com.playtranslate.Prefs
import com.playtranslate.cancelledStateOrNull
import com.playtranslate.ocr.registry.ocrLabel
import com.playtranslate.ocr.registry.selectionToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.playtranslate.camera.render.OverlayRasterizer
import com.playtranslate.camera.render.RasterRegion
import com.playtranslate.camera.render.SnapshotCore
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
import com.playtranslate.ui.noTextStatusMessage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat

private const val TAG = "CameraSession"

/** Saved-frame prefix for [SnapshotCore.saveFrame]/[SnapshotCore.sweepFrameFiles]
 *  — distinct from the capture flow's and the import tool's names. */
internal const val CAMERA_FRAME_PREFIX = "camera-snapshot-"

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
    /** A live acquire's OCR has been in flight past the shared slow
     *  threshold ([LiveSessionFeedback.OCR_SLOW_PROMPT_MS]) — the slow-device
     *  signal behind the rescue prompt. Fired on the main thread, at most
     *  once per session ([slowOcrFired]); the receiver owns every further
     *  gate (answered latch, rescue availability, frozen state). */
    private val onSlowOcr: () -> Unit = {},
) {
    private companion object {

        /** Debug pill refresh cadence (frames). */
        const val PILL_EVERY = 5

        /** Downscale factor of the color-sampling reference bitmap — the
         *  live acquire's [AcquireBuffers] shares the snapshot pipeline's
         *  sampling scale. Confidence/edge-gate thresholds live in
         *  [SnapshotCore] (shared with the import tool); their calibration
         *  notes moved with them. */
        const val COLOR_SCALE = SnapshotCore.COLOR_SCALE

        /** Re-raster for crispness when tracked scale drifts this far from
         *  the raster's native scale (either direction). */
        const val RASTER_SCALE_DRIFT = 1.3f

        /** Sustained anchor-less IDLE frames before the analysis rate halves
         *  (~12 s at 25 fps). Resets the moment anything locks. */
        const val IDLE_BACKOFF_AFTER_FRAMES = 300

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

    /** Ownership counter for every display publication — see [DisplayEpoch].
     *  Advanced (atomically with the re-flavor caches, under [stateLock]) by
     *  EVERY publication source: anchor install, relock, mode change, scene
     *  wipe, reset, shutdown. Its predecessor only counted config changes,
     *  so its checks read like race guards while ordering nothing between
     *  acquires — two display tails could share a value and interleave.
     *  Acquire lifecycle itself is owned by the engine (begin/finish ids);
     *  [acquireJob] remains as the launch-CAPACITY gate — a missed
     *  enrollment there now wastes compute instead of corrupting display. */
    private val displayEpoch = DisplayEpoch()
    private val nextAnchorId = AtomicLong(1L)

    /** The in-flight acquire's coroutine. Cancellation-first: invalidation
     *  (mode toggle, language reset, engine abandonment) CANCELS the work —
     *  5-16 s of OCR for a scene nobody wants — instead of letting it run to
     *  completion and discarding the result at commit points. The id guards
     *  remain as the backstop for anything that slips through. */
    @Volatile
    private var acquireJob: kotlinx.coroutines.Job? = null

    /** Once-per-session latch for [onSlowOcr], mirroring live mode's
     *  slowPassFired ([LiveSessionFeedback]): one slow pass earns one
     *  callback; the receiver's persisted answered-latch handles forever.
     *  Cleared by [reset] — a language change is a new-session boundary
     *  (the prompt is per-language), same as a fresh live start. Camera
     *  acquires are strictly serialized ([acquireJob]), so a plain flag
     *  replaces live mode's per-pass token bookkeeping. */
    @Volatile
    private var slowOcrFired = false

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

    /** Pre-tap frame history for the snapshot shutter: the freeze serves the
     *  sharpest frame received BEFORE the tap's impact started shaking the
     *  device — see [FreezeFrameRing]. */
    private val freezeRing = FreezeFrameRing()

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

    /** Per-group (bg, text) colors sampled from the keyframe at acquire —
     *  all the re-flavor path needs, index-aligned with the cached (gated)
     *  OCR groups. Colors are sampled ONCE so no bitmap is retained: the
     *  cached color-ref bitmap this replaces was aliased between this cache,
     *  [lastBuilt], and the anchor LRU across two threads, which made
     *  deterministic recycling a use-after-recycle trap and left reclamation
     *  to GC. */
    private var cachedGroupColors: List<Pair<Int, Int>>? = null
    private var cachedAuW = 0
    private var cachedAuH = 0

    /** Snapshot-only: the pipeline's per-group translations, index-aligned
     *  with [cachedOcr]'s groups. [showFrozenOverlays] renders skeletons
     *  while null and filled boxes once set — it must never translate on its
     *  own, or the overlays-first presentation would duplicate the
     *  pipeline's backend call (both fire before either could cache). */
    private var cachedSnapshotTranslations: List<String>? = null

    /** The display payload of the currently anchored scene once its final
     *  (filled) boxes exist — what the anchor LRU stores alongside the
     *  anchor for instant re-display on re-lock. */
    private var lastBuilt: BuiltOverlays? = null

    private class BuiltOverlays(
        val ocr: OcrManager.OcrResult,
        val boxes: List<TextBox>,
        val trackKeys: List<Int>,
        val trackRegionsAu: List<Pair<Int, android.graphics.Rect>>,
        val groupColors: List<Pair<Int, Int>>,
        val auW: Int,
        val auH: Int,
        val mode: OverlayMode,
        val langKey: String,
    )

    /** Recently replaced scenes (anchor + display payload), newest last.
     *  Analysis-thread only; anchors own native Mats → release on evict. */
    private val anchorCache = ArrayDeque<Pair<com.playtranslate.camera.tracker.Anchor, BuiltOverlays>>()
    private var relockCursor = 0

    // Includes the camera's effective OCR token (raw, not resolved — this runs
    // on the analysis thread per relock probe): anchor payloads carry the
    // engine's OCR output, so an engine switch must orphan cached scenes even
    // if some future switch path forgets its session reset.
    private fun langKey(): String =
        "${prefs.sourceLangId}|${prefs.targetLang}|${prefs.targetChineseVariant}|" +
            (
                prefs.cameraOcrBackendToken(prefs.sourceLangId)
                    ?: prefs.ocrBackendToken(prefs.sourceLangId) ?: ""
            )

    /** The warp surface; created lazily on main. */
    private var warpView: WarpOverlayView? = null

    /** Review-zoom crispness boost for the FROZEN display — 1f in every
     *  live mode (gestures only exist while frozen; [unfreeze] resets), so
     *  the live raster scale is untouched. Written on main at gesture
     *  settle, read at raster time. */
    @Volatile
    private var frozenRenderBoost = 1f

    /** The frozen review zoom's transform seams — see the import session's
     *  twins. Main thread. */
    fun setOverlayViewTransform(transform: DoubleArray?) {
        overlayHost.post { warpView?.viewTransform = transform }
    }

    fun setFrozenRenderBoost(boost: Float) {
        frozenRenderBoost = boost
    }

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

    /** Set when a TAP-to-focus sweep completes; consumed by the next
     *  analyzed frame, which forwards it to the engine on the analysis
     *  thread (the engine is thread-confined). Deliberate taps only —
     *  kicking on every passive AF convergence would OCR-hammer scenes
     *  where a budget module hunts continuously. */
    @Volatile
    private var pendingRefocusKick = false

    /** The user's tap-to-focus finished: the image changed without motion,
     *  so the current anchor/no-text verdict describes a stale picture.
     *  Any thread. */
    fun onDeliberateRefocus() {
        pendingRefocusKick = true
    }

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
            OcrManager.instance.recognise(
                bmp,
                sourceLang,
                screenshotWidth = bmp.width,
                engineTokenOverride = prefs.cameraOcrBackendToken(prefs.sourceLangId),
            )
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
            // Pre-tap ring: fed BEFORE the freeze service and the mode gate
            // (a freeze selects among frames pushed on PREVIOUS passes, and
            // PAUSED must keep the ring warm — freezes work from PAUSED).
            // FROZEN skips: the shutter is disabled while frozen, and the
            // freeze below just cleared the slots for the episode's
            // lifetime. The ring NEVER throws (containment contract in
            // FreezeFrameRing): analyze() runs unguarded on the analysis
            // executor, where an escaping throw kills the process.
            val onFrozen = freezeCallback
            if (mode != Mode.FROZEN) {
                freezeRing.push(
                    proxy, android.os.SystemClock.uptimeMillis(),
                    force = onFrozen != null,
                )
            }
            // Snapshot freeze: serviced BEFORE the mode gate so a freeze
            // works from PAUSED too. Entering FROZEN here (analysis thread)
            // plus the epoch advance means no live display tail can publish
            // over the snapshot; cancellation-first kills an in-flight
            // acquire's OCR rather than letting it run for nobody.
            if (onFrozen != null) {
                freezeCallback = null
                mode = Mode.FROZEN
                // The no-usable-text hint narrates AUTO-detection; a snapshot
                // replaces that mode, and no frame will ever clear the hint
                // while frozen (pause gets this for free from wipeDisplay —
                // the freeze deliberately skips the wipe to keep overlays).
                postHint(false)
                acquireJob?.takeIf { it.isActive }?.cancel()
                // The tap's IMPACT (ACTION_DOWN) is what shook the camera,
                // and THIS frame — the first delivered after the click —
                // sits mid-ring-down: freeze the ring's pick instead. The
                // frame in hand was force-pushed above, so it is the ring's
                // own fallback; the proxy path here only covers a failed
                // NV21 conversion.
                val pick = freezeRing.selectUpright(
                    freezeTapDownMs.takeIf { it > 0 },
                    android.os.SystemClock.uptimeMillis(),
                )
                freezeRing.clear()
                val frozen = pick?.bitmap ?: toUprightBitmap(proxy)
                // Kept overlays are pinned to the LATEST tracked frame's
                // transform, so "they sit correctly on the frozen frame"
                // holds ONLY when the pick is that frame. A pre-tap pick is
                // an older frame the boxes never tracked: pinning live
                // geometry on it shows boxes at the wrong coordinates for
                // the whole OCR+translate load, then pops them to the
                // corrected positions at Done — downgrade to the standard
                // cleared/skeleton loading arc instead. The callback reports
                // the outcome so the controller's skeleton logic agrees.
                val keepOverlays = freezeKeepsOverlays && (pick == null || pick.isNewestFrame)
                val retiredFrame: String?
                synchronized(stateLock) {
                    cachedOcr = null
                    cachedGroupColors = null
                    cachedSnapshotTranslations = null
                    retiredFrame = cachedScreenshotPath
                    cachedScreenshotPath = null
                    lastBuilt = null
                    displayEpoch.advance()
                }
                // The previous snapshot's saved frame is unreferenced now
                // (frames are per-cycle unique files); an in-flight Anki
                // send already pinned (copied) its own at send start.
                SnapshotCore.deleteFrame(retiredFrame)
                overlayHost.post {
                    // Overlays-preferred snapshots keep the live boxes as the
                    // loading state: analysis is halted, so they stay pinned
                    // at the exact transform of the frame just frozen. The
                    // snapshot's own showRegions swap replaces them at Done.
                    if (!keepOverlays) {
                        warpView?.clearRegions()
                        lastShownBoxes = null
                        lastShownRegions = null
                        lastShownKeys = emptyList()
                        lastShownAuW = 0
                        lastShownAuH = 0
                        rasterScale = 1f
                    }
                    onFrozen(frozen, keepOverlays)
                }
                return
            }
            if (mode != Mode.LIVE) return

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
            // A completed tap-to-focus invalidates whatever the scene read as
            // while defocused — expire the anchor age / no-text backoff so
            // the staleness trigger re-OCRs the now-sharp frame. Consumed
            // here (analysis thread) because the engine is thread-confined.
            if (pendingRefocusKick) {
                pendingRefocusKick = false
                engine.onDeliberateRefocus()
            }
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
                    val launchEpoch = displayEpoch.current()
                    Log.d(TAG, "acquire#$acquireId: keyframe ${buffers.keyframe.width}x${buffers.keyframe.height}")
                    // ATOMIC: a cancel that lands before first dispatch (mode
                    // toggle/reset in that window) must still run the body up
                    // to its first suspension, so the finally can close the
                    // buffers and complete the engine's acquire — a silently
                    // skipped body leaks the keyframe and pins ACQUIRING
                    // until the 30 s watchdog.
                    acquireJob = scope.launch(Dispatchers.Default, kotlinx.coroutines.CoroutineStart.ATOMIC) {
                        runAcquire(buffers, launchEpoch, acquireId)
                    }
                }
            }

            publish(decision, (System.nanoTime() - t0) / 1e6)
        } finally {
            proxy.close()
        }
    }

    /** True when the last posted update hid the overlays (null homography).
     *  Analysis thread only. */
    private var lastPublishHid = false

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
        // While hidden (Idle/Lost) the update is identical every frame, and
        // applyHomography(null) invalidates — an empty full-view redraw at
        // 25-30 fps in exactly the state the idle backoff makes cheap. Post
        // the FIRST hide (the warp view must actually blank), skip repeats.
        if (hAu == null && pill == null && lastPublishHid) return
        lastPublishHid = hAu == null
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
        private var colorRef: Bitmap? = null

        val auW = keyframe.width
        val auH = keyframe.height

        /** Create the ×4 color reference and immediately drop the multi-MB
         *  keyframe — nothing downstream needs full resolution. The returned
         *  bitmap stays OWNED by these buffers (sample from it inside the
         *  acquire, cache the sampled colors): close() recycles it
         *  unconditionally, so nothing bitmap-shaped outlives the acquire. */
        fun deriveColorRef(): Bitmap {
            val c = keyframe.scale(auW / COLOR_SCALE, auH / COLOR_SCALE, false)
            colorRef = c
            keyframe.recycle()
            return c
        }

        /** The single close path, safe on every exit (early return,
         *  exception, cancellation): recycles both bitmaps; the cnKeyframe
         *  Mat release is serialized onto the analysis executor behind any
         *  pending install block that may still be using it. */
        fun close() {
            if (!keyframe.isRecycled) keyframe.recycle()
            colorRef?.takeIf { !it.isRecycled }?.recycle()
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

    private suspend fun runAcquire(buffers: AcquireBuffers, launchEpoch: Int, acquireId: Long) {
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
            // Slow-pass rescue timer, live mode's threshold: fires MID-pass,
            // while the user is staring at a viewfinder that shows nothing
            // for their pointing. Started after the prewarm join so engine
            // warm-up never counts as slowness; cancelled with the pass
            // (finally), so a fast pass or a cancelled acquire never fires.
            // [scope] is the activity's lifecycleScope — the callback lands
            // on the main thread.
            val slowTimer = if (!slowOcrFired) scope.launch {
                kotlinx.coroutines.delay(com.playtranslate.LiveSessionFeedback.OCR_SLOW_PROMPT_MS)
                slowOcrFired = true
                onSlowOcr()
            } else null
            val ocr = try {
                OcrManager.instance.recognise(
                    buffers.keyframe,
                    sourceLang,
                    screenshotWidth = auW,
                    regionPreFilter = cameraRegionPreFilter(),
                    engineTokenOverride = prefs.cameraOcrBackendToken(prefs.sourceLangId),
                )
            } finally {
                slowTimer?.cancel()
            }
            // A newer publication source appeared while the OCR ran (mode
            // toggle / reset — cancellation-first usually got here earlier;
            // this is the structural backstop).
            if (!displayEpoch.isCurrent(launchEpoch)) return
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
                // describing a previous one. The wipe IS a publication source
                // (it owns the now-empty display), so it advances the epoch —
                // atomically with the caches — and any older tail dies at its
                // next commit. finally completes the acquire.
                synchronized(stateLock) {
                    cachedOcr = null
                    cachedGroupColors = null
                    lastBuilt = null
                    displayEpoch.advance()
                }
                withContext(Dispatchers.Main) { warpView?.clearRegions() }
                postHint(true)
                return
            }
            postHint(false)
            // Sample the per-group colors NOW: the ×4 color-ref bitmap dies
            // with the buffers instead of being retained (and aliased) by the
            // session cache, lastBuilt, and the anchor LRU.
            val groupColors = OverlayToolkit.sampleGroupColors(
                colorRef, groups.map { it.bounds }, 0, 0, COLOR_SCALE,
            )

            // Anchor install first (fast, ~15 ms) so tracking starts while
            // rasterization/translation still run. The engine's active-id
            // check makes a stale completion (watchdog fired, reset) a no-op
            // instead of a resurrection. Returns the display epoch this
            // acquire's tail publishes under, or 0 when the install failed.
            val installEpoch = onAnalysisThread {
                if (!engine.isAcquireActive(acquireId)) return@onAnalysisThread 0
                if (selfJob?.isActive == false) return@onAnalysisThread 0
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
                    // GONE. Drop the dead anchor now; returning 0 below stops
                    // its OCR output from being rasterized/cached/shown.
                    frameTracker.clearAnchor()
                    return@onAnalysisThread 0
                }
                // The new anchor owns the display from here. Epoch and the
                // re-flavor caches move together, atomically: a mode toggle
                // racing this either read the OLD caches under an epoch this
                // advance just staled, or reads THESE caches and re-flavors
                // the new scene — never the old scene's content over the new
                // anchor's geometry.
                synchronized(stateLock) {
                    cachedOcr = gated
                    cachedGroupColors = groupColors
                    cachedAuW = auW
                    cachedAuH = auH
                    displayEpoch.advance()
                }
            } ?: 0

            if (installEpoch == 0) return
            buildAndShow(gated!!, groupColors, auW, auH, installEpoch)
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
     * Implementation is the shared [SnapshotCore.regionPreFilter]; this
     * wrapper binds the camera's language pair.
     */
    private fun cameraRegionPreFilter(
        /** Live acquires drop edge-clipped detections before the expensive
         *  recognition stage; a deliberate SNAPSHOT reads the whole frame
         *  (a full-frame document is exactly what the edge gate would gut),
         *  keeping only the center-out priority order. */
        dropEdgeClipped: Boolean = true,
        /** Snapshot region — see [SnapshotCore.regionPreFilter]. */
        clipTo: android.graphics.Rect? = null,
        clipFrameW: Int = 0,
        clipFrameH: Int = 0,
    ): com.playtranslate.ocr.core.RegionPreFilter = SnapshotCore.regionPreFilter(
        dropEdgeClipped = dropEdgeClipped &&
            SourceLanguageProfiles[prefs.sourceLangId].translationCode != prefs.targetLang,
        clipTo = clipTo,
        clipFrameW = clipFrameW,
        clipFrameH = clipFrameH,
        tag = TAG,
    )

    /** Camera-frame quality gate — the shared [SnapshotCore.usableGroups]
     *  (confidence + edge gates; rationale and thresholds live there); this
     *  wrapper binds the camera's language pair. */
    private fun usableGroups(
        ocr: OcrManager.OcrResult,
        auWidth: Int,
        auHeight: Int,
        /** Snapshots keep the confidence gate (blur garbage still translates
         *  into fluent nonsense) but skip the edge gate — the user asked for
         *  THIS frame, clipped lines included. */
        skipEdgeGate: Boolean = false,
    ): List<OcrManager.OcrGroup> = SnapshotCore.usableGroups(
        ocr, auWidth, auHeight,
        translating = SourceLanguageProfiles[prefs.sourceLangId].translationCode != prefs.targetLang,
        skipEdgeGate = skipEdgeGate,
        tag = TAG,
    )

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

    /** Build boxes for the camera's current [Prefs.cameraOverlayMode],
     *  register the flavor's tracked regions (groups for translation, lines
     *  for reading — the per-region homography units), rasterize, and hand
     *  the raster regions to the warp view. Two-phase skeleton→filled for
     *  the translation flavor. */
    private suspend fun buildAndShow(
        ocr: OcrManager.OcrResult,
        groupColors: List<Pair<Int, Int>>,
        auW: Int,
        auH: Int,
        epoch: Int,
    ) {
        val mode = prefs.cameraOverlayMode
        when (mode) {
            OverlayMode.TRANSLATION -> {
                // usableGroups already dropped blank groups, so this filter is
                // a no-op that KEEPS the index alignment with [groupColors]
                // (sampled per gated group at acquire).
                val groups = ocr.groups.filter { it.text.isNotBlank() }
                val texts = groups.map { it.text }
                // One tracked region per group: key = group index.
                val trackKeys = groups.indices.toList()
                val regions = groups.mapIndexed { idx, g -> idx to g.bounds }
                installTrackRegions(regions, epoch)

                val placeholders = buildPlaceholderBoxes(groups, groupColors)
                showRegions(placeholders, trackKeys, auW, auH, epoch)

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
                if (!displayEpoch.isCurrent(epoch)) return
                val filled = placeholders.mapIndexed { idx, ph ->
                    ph.copy(translatedText = translations.getOrElse(idx) { "" })
                }
                showRegions(filled, trackKeys, auW, auH, epoch)
                rememberBuilt(ocr, filled, trackKeys, regions, groupColors, auW, auH, mode, epoch)
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
                installTrackRegions(lineRegions, epoch)

                val boxes = mutableListOf<TextBox>()
                val trackKeys = mutableListOf<Int>()
                for (fg in furigana) {
                    val groupLines = lineKeysByGroup[fg.groupBounds].orEmpty()
                    for (box in fg.boxes) {
                        boxes.add(box)
                        trackKeys.add(nearestLineKey(box, groupLines))
                    }
                }
                showRegions(boxes, trackKeys, auW, auH, epoch)
                rememberBuilt(ocr, boxes, trackKeys, lineRegions, groupColors, auW, auH, mode, epoch)
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
        groupColors: List<Pair<Int, Int>>,
        auW: Int,
        auH: Int,
        mode: OverlayMode,
        epoch: Int,
    ) {
        synchronized(stateLock) {
            // A stale tail must not snapshot: lastBuilt pairs with the
            // CURRENT anchor at the next detach, and a stale payload here is
            // how the LRU ends up serving one scene's content on another
            // scene's geometry.
            if (!displayEpoch.isCurrent(epoch)) return
            lastBuilt = BuiltOverlays(ocr, boxes, trackKeys, regions, groupColors, auW, auH, mode, langKey())
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
        val mode = prefs.cameraOverlayMode
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

        // The relocked scene owns the display: epoch and caches move together.
        val epoch = synchronized(stateLock) {
            cachedOcr = payload.ocr
            cachedGroupColors = payload.groupColors
            cachedAuW = payload.auW
            cachedAuH = payload.auH
            lastBuilt = payload
            displayEpoch.advance()
        }
        // Tracked as THE acquire job: the relock's display tail is acquire
        // display work like any other — canAcquire stays false until it
        // lands, and mode/language invalidation can cancel it.
        acquireJob = scope.launch(Dispatchers.Default) {
            try {
                installTrackRegions(payload.trackRegionsAu, epoch)
                showRegions(payload.boxes, payload.trackKeys, payload.auW, payload.auH, epoch)
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
     *  thread; no-op when no anchor is installed yet, or when [epoch] no
     *  longer owns the display — a stale tail registering its rects against
     *  a NEWER anchor's scale and points produces nonsense region membership
     *  (garbage per-region fits, spurious collapse re-OCRs). */
    private suspend fun installTrackRegions(auRegions: List<Pair<Int, android.graphics.Rect>>, epoch: Int) {
        onAnalysisThread {
            if (!displayEpoch.isCurrent(epoch)) return@onAnalysisThread
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
        groupColors: List<Pair<Int, Int>>,
    ): List<TextBox> = SnapshotCore.buildPlaceholderBoxes(groups, groupColors)

    // ── Overlay display ────────────────────────────────────────────────────

    /** Raster resolution: view pixels per AU pixel (the FILL_CENTER cover
     *  scale). Rasterizing at VIEW scale — not AU scale — makes every
     *  dp-based layout decision (grow thresholds, minimum legible widths,
     *  autosize caps) evaluate in true display pixels, exactly like the
     *  capture flow's on-screen boxes, and lands the rasters at 1:1
     *  physical pixels instead of being blown up by the warp afterwards.
     *  Falls back to 1 before the host lays out (never in practice — the
     *  first OCR outlasts the first layout). Main thread. */
    private fun baseRenderScale(auW: Int, auH: Int): Float {
        val w = overlayHost.width
        val h = overlayHost.height
        if (w <= 0 || h <= 0 || auW <= 0 || auH <= 0) return 1f
        return CameraCoordinates(auW, auH, w, h).scale
    }

    /** Rasterize AU-space boxes (main thread — view machinery) and install
     *  them in the warp view. [trackKeys] parallels [boxes]. */
    private suspend fun showRegions(
        boxes: List<TextBox>,
        trackKeys: List<Int>,
        auW: Int,
        auH: Int,
        epoch: Int,
    ) {
        withContext(Dispatchers.Main) {
            if (!displayEpoch.isCurrent(epoch)) return@withContext
            val rasterizer = OverlayRasterizer(
                context,
                verticalTextTarget = targetSupportsVerticalText(prefs.targetLang),
                verticalTextStackable = stackableTargetScript(prefs.targetLang),
                verticalGrowEnabled = prefs.verticalTextGrow,
            )
            // frozenRenderBoost: the review zoom's crispness factor — 1f in
            // every live mode (gestures only exist while FROZEN; unfreeze
            // resets it), so the live path's raster scale is untouched.
            val base = baseRenderScale(auW, auH) * frozenRenderBoost
            // Dirty diff against the last show at the same keyframe size —
            // a skeleton→filled swap re-renders only the boxes that changed.
            val previous = if (lastShownAuW == auW && lastShownAuH == auH) lastShownRegions else null
            val regions: List<RasterRegion> =
                rasterizer.rasterize(boxes, auW, auH, trackKeys, renderScale = base, previous = previous)
            ensureWarpView().setRegions(regions, auW, auH)
            lastShownBoxes = boxes
            lastShownKeys = trackKeys
            lastShownRegions = regions
            lastShownAuW = auW
            lastShownAuH = auH
            rasterScale = base
        }
    }

    /** Crispness re-raster: when the tracked scale has drifted well past the
     *  raster's native resolution, re-render the same boxes super-sampled
     *  (off the frame path; the warp keeps running on the old bitmaps until
     *  the swap). Main thread. */
    private fun maybeRerasterForScale(trackedScale: Float) {
        if (rerasterPending) return
        val boxes = lastShownBoxes ?: return
        // Desired raster resolution = the tracked zoom relative to the anchor
        // TIMES the base view scale (rasters are view-resolution now, not
        // AU-resolution) — both sides of the drift check share units.
        val desired = trackedScale * baseRenderScale(lastShownAuW, lastShownAuH)
        val ratio = if (desired > rasterScale) desired / rasterScale else rasterScale / desired
        if (ratio < RASTER_SCALE_DRIFT) return
        rerasterPending = true
        val keys = lastShownKeys
        val auW = lastShownAuW
        val auH = lastShownAuH
        val targetScale = desired.coerceIn(0.5f, 2.5f)
        // Observer, not a source: re-rasters whatever currently owns the
        // display, and dies if ownership changes before it lands.
        val epoch = displayEpoch.current()
        scope.launch(Dispatchers.Main) {
            try {
                if (!displayEpoch.isCurrent(epoch) || lastShownBoxes !== boxes) return@launch
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
        val ocr: OcrManager.OcrResult?
        val groupColors: List<Pair<Int, Int>>?
        val auW: Int
        val auH: Int
        val epoch: Int
        // Authorization snapshot: the epoch advances INSIDE the same lock
        // the caches are read under, so this re-flavor either owns the scene
        // it read, or an acquire install that races the read stales it at
        // its first commit — it can never publish an older scene's content
        // over a newer anchor.
        synchronized(stateLock) {
            ocr = cachedOcr
            groupColors = cachedGroupColors
            auW = cachedAuW
            auH = cachedAuH
            epoch = displayEpoch.advance()
        }
        if (ocr == null || groupColors == null || auW == 0) return
        // Tracked as THE acquire job (display-work capacity): a fresh acquire
        // must not launch under a still-translating re-flavor tail.
        acquireJob = scope.launch(Dispatchers.Default) {
            try {
                buildAndShow(ocr, groupColors, auW, auH, epoch)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "re-flavor failed", e)
            }
        }
    }

    /** Language/config change: drop everything; the next settled frame
     *  re-OCRs from scratch. Re-arms the slow-OCR callback — per-language
     *  prompt, new-session boundary (the engine-switch reset re-arms too,
     *  harmlessly: with the rescue engine selected the receiver finds no
     *  faster offer and shows nothing). */
    fun reset() {
        slowOcrFired = false
        wipeDisplay(purgeAnchorCache = true)
    }

    /** Cancel display work and clear every live-scene artifact (overlays,
     *  tracker anchor, engine state, re-flavor caches, stale hint). The
     *  anchor LRU survives unless [purgeAnchorCache]: pause/unfreeze keep it
     *  so a resumed session can re-lock a recent scene without re-OCR; a
     *  language/config change must not serve stale-language scenes. */
    private fun wipeDisplay(purgeAnchorCache: Boolean) {
        acquireJob?.cancel()
        synchronized(stateLock) {
            cachedOcr = null
            cachedGroupColors = null
            cachedAuW = 0
            cachedAuH = 0
            cachedSnapshotTranslations = null
            lastBuilt = null
            displayEpoch.advance()
        }
        analysisExecutor.execute {
            frameTracker.clearAnchor()
            engine.reset()
            if (purgeAnchorCache) {
                while (anchorCache.isNotEmpty()) anchorCache.removeFirst().first.release()
            }
        }
        postHint(false)
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

    // ── Pipeline mode (play/pause + snapshot freeze) ───────────────────────

    /** LIVE = auto-detection runs; PAUSED = user paused (clean viewfinder,
     *  frames arrive but are ignored); FROZEN = a snapshot owns the screen.
     *  Written from the main thread (controls) and the analysis thread (the
     *  freeze service); read per-frame. */
    enum class Mode { LIVE, PAUSED, FROZEN }

    @Volatile
    var mode: Mode = Mode.LIVE
        private set

    /** One-shot frame request serviced on the NEXT analyzed frame — which
     *  selects the frozen bitmap from the pre-tap ring, not necessarily that
     *  frame itself ([FreezeFrameRing]). Works from LIVE and PAUSED alike —
     *  the analysis use case stays bound in every mode, so frames keep
     *  reaching [analyze]; non-LIVE modes just ignore them. The callback
     *  receives, on the MAIN thread: the upright AU-space keyframe (which
     *  it owns) and whether the freeze actually kept the live overlays —
     *  the [freezeKeepsOverlays] REQUEST is downgraded when the ring picks
     *  a pre-tap frame the live boxes never tracked. */
    @Volatile
    private var freezeCallback: ((Bitmap, Boolean) -> Unit)? = null

    /** REQUEST to leave the CURRENT warp overlays on screen (the
     *  overlays-preferred snapshot flow keeps the live boxes as its loading
     *  state). Honored only when the frozen frame IS the frame those boxes
     *  were tracking (the ring's newest pick, or the proxy fallback) — the
     *  premise "they sit correctly on it" is false for an older pre-tap
     *  pick, so the freeze downgrades and reports the outcome through the
     *  callback. Written before [freezeCallback] on the main thread; the
     *  volatile callback write publishes it to the analysis thread. */
    private var freezeKeepsOverlays = false

    /** Uptime of the shutter tap's ACTION_DOWN (0 = activation wasn't a
     *  touch), the pre-tap selection boundary: the finger's impact starts
     *  the shake, so only frames received before it are safely pre-impulse.
     *  Published the same way as [freezeKeepsOverlays]. */
    private var freezeTapDownMs = 0L

    /** Whether warp overlays are currently being drawn. Main thread. */
    fun hasLiveOverlays(): Boolean = warpView?.hasVisibleRegions == true

    /** Stop auto-detection and clear the display; the viewfinder stays live. */
    fun pause() {
        mode = Mode.PAUSED
        wipeDisplay(purgeAnchorCache = false)
    }

    /** Resume auto-detection: the next settled frame re-acquires, or
     *  re-locks instantly from the anchor LRU that [pause] kept. */
    fun resume() {
        mode = Mode.LIVE
    }

    /** Freeze a frame. The pipeline enters FROZEN on the analysis thread
     *  BEFORE the callback is posted, so no live tail can publish over the
     *  snapshot. [keepOverlays] REQUESTS keeping the current warp overlays
     *  up as the snapshot's loading state; the freeze honors it only when
     *  the frozen frame is the one those overlays were tracking, and the
     *  callback's second argument reports the outcome. [tapDownUptimeMs] —
     *  the shutter tap's ACTION_DOWN uptime, 0 when the activation wasn't a
     *  touch — anchors pre-tap selection: the freeze serves the sharpest
     *  ring frame received before the finger's impact started shaking the
     *  device ([FreezeFrameRing]), falling back to the next analyzed
     *  frame. */
    fun requestFreeze(
        keepOverlays: Boolean = false,
        tapDownUptimeMs: Long = 0L,
        onFrozen: (Bitmap, Boolean) -> Unit,
    ) {
        freezeKeepsOverlays = keepOverlays
        freezeTapDownMs = tapDownUptimeMs
        freezeCallback = onFrozen
    }

    /** Leave FROZEN, dropping snapshot display state. [to] restores the
     *  pre-snapshot mode — a paused camera stays paused. */
    fun unfreeze(to: Mode) {
        require(to != Mode.FROZEN) { "unfreeze target must be LIVE or PAUSED" }
        // Outstanding snapshot cycles are superseded: a zombie whose engine
        // outlived the cooperative cancel must not write snapshot caches
        // into the live/paused display state.
        snapshotGeneration.incrementAndGet()
        // Episode over: drop the capture token and its frame reference
        // (which would otherwise pin the dead keyframe bitmap).
        captureToken = null
        captureTokenFrame = null
        // The review zoom dies with the snapshot — AUTHORITATIVE reset: the
        // warp view serves the LIVE path next, and a leftover view
        // transform would warp live boxes (the panel's dismiss funnel also
        // resets, but this is the guarantee).
        frozenRenderBoost = 1f
        overlayHost.post { warpView?.viewTransform = null }
        mode = to
        wipeDisplay(purgeAnchorCache = false)
    }

    // ── Snapshot pipeline (frozen-frame one-shot) ──────────────────────────

    /** Camera-owned recorder: rows land in the process-wide history store —
     *  the same History screen the capture flows feed. Main-thread only;
     *  the {context} ring being instance-local (separate from the
     *  service's) is accepted. */
    private val snapshotLogRecorder by lazy {
        com.playtranslate.translationlog.TranslationLogRecorder(context.applicationContext)
    }

    /** The current frozen episode's History grouping token, keyed on the
     *  frozen bitmap's IDENTITY: crop/settings re-runs pass the same
     *  instance back through [runSnapshot] and stay in the episode (the
     *  token's within-capture dedupe absorbs their re-OCR duplicates); a
     *  new shutter's frame mints fresh. Main-thread only, like the
     *  recorder. Cleared on [unfreeze] — [captureTokenFrame] is a strong
     *  reference that would otherwise pin the dead keyframe. */
    private var captureToken:
        com.playtranslate.translationlog.TranslationLogRecorder.CaptureSessionToken? = null
    private var captureTokenFrame: Bitmap? = null

    /** Saved-to-cache path of the frozen frame the display caches describe —
     *  rides the snapshot cache writes (same stateLock, same generation
     *  guard) so the word-lookup scene can attach it to Anki cards. Only
     *  meaningful while FROZEN with [cachedOcr] non-null. */
    private var cachedScreenshotPath: String? = null

    /** Frozen-frame word-lookup scene ([FrozenLookupScene]); null unless a
     *  snapshot currently owns the display. */
    fun frozenLookupScene(): com.playtranslate.camera.render.FrozenLookupScene? {
        if (mode != Mode.FROZEN) return null
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
            return com.playtranslate.camera.render.FrozenLookupScene(
                lines, cachedAuW, cachedAuH, cachedScreenshotPath,
            )
        }
    }

    /** Bumped by every [runSnapshot] and by [unfreeze]. A snapshot cycle may
     *  publish the shared display caches ONLY while its generation is still
     *  current: cancellation is cooperative, and an older cycle whose OCR
     *  engine doesn't observe it can return from recognise AFTER a newer
     *  region re-run already published — its straight-line tail would then
     *  land a stale cache write + epoch advance LAST, and the frozen boxes
     *  would show a different region than the panel (Codex adversarial
     *  review finding). */
    private val snapshotGeneration = java.util.concurrent.atomic.AtomicLong()

    /**
     * One-shot OCR + translate of the [frozen] snapshot, mirroring the
     * service's deliberate one-shot orchestration (attribution, provenance,
     * History recording) on the camera's own gating and translator — no
     * CaptureService involved; the service may not be running. The caller
     * owns [frozen] and must keep it unrecycled until the returned session
     * reaches a terminal state or is cancelled.
     *
     * Also primes the re-flavor caches and advances the display epoch (the
     * snapshot is a publication source), so [showFrozenOverlays] and mode
     * re-flavors read a coherent scene.
     */
    fun runSnapshot(frozen: Bitmap, regionAu: android.graphics.Rect? = null): CaptureSession {
        if (frozen !== captureTokenFrame) {
            captureToken = snapshotLogRecorder.beginCaptureSession()
            captureTokenFrame = frozen
        }
        val state = MutableStateFlow<CaptureState>(
            CaptureState.InProgress(context.getString(com.playtranslate.R.string.status_ocr))
        )
        val gen = snapshotGeneration.incrementAndGet()
        val job = scope.launch(Dispatchers.Default) {
            try {
                runSnapshotCycle(frozen, state, regionAu, gen)
            } catch (e: CancellationException) {
                // Let cancellation propagate; invokeOnCompletion writes Cancelled.
                throw e
            } catch (e: Exception) {
                // The service one-shot's catch, mirrored: a thrown OCR or
                // translation failure (cloud backend with no network, a
                // native engine error) becomes a readable terminal state.
                // Without this the exception escaped to the lifecycleScope —
                // which installs no handler — and crashed the app.
                Log.e(TAG, "snapshot cycle failed: ${e.message}", e)
                state.value = CaptureState.Failed(e.message ?: "Unknown error")
            }
        }
        job.invokeOnCompletion { cause ->
            cancelledStateOrNull(cause, state.value)?.let { state.value = it }
        }
        return CaptureSession(state.asStateFlow(), job)
    }

    private suspend fun runSnapshotCycle(
        frozen: Bitmap,
        state: MutableStateFlow<CaptureState>,
        /** User-drawn snapshot region (AU px): only text inside it is shown,
         *  translated, and surfaced in the panel. Null = whole frame. */
        regionAu: android.graphics.Rect? = null,
        /** This cycle's [snapshotGeneration] stamp — see the field. */
        gen: Long = snapshotGeneration.get(),
    ) {
        val srcId = prefs.sourceLangId
        val sourceLang = SourceLanguageProfiles[srcId].translationCode
        val auW = frozen.width
        val auH = frozen.height
        val screenshotPath = saveSnapshotToCache(frozen)

        val ocr = OcrManager.instance.recognise(
            frozen,
            sourceLang,
            screenshotWidth = auW,
            regionPreFilter = cameraRegionPreFilter(
                dropEdgeClipped = false, clipTo = regionAu,
                clipFrameW = auW, clipFrameH = auH,
            ),
            engineTokenOverride = prefs.cameraOcrBackendToken(srcId),
        )
        // The region gate proper lives HERE, at group level, not in the
        // pre-filter: single-model engines (ML Kit) never see the
        // detect/recognize seam, and a group is the translation/Anki unit —
        // center-inside keeps whole paragraphs, never half-clipped ones.
        // Superseded while the recognizer ran (region re-run, unfreeze)?
        // Abandon before touching any shared state — cancellation alone
        // can't be relied on (an engine that never checks it returns here
        // normally after the cancel). This cycle's frame file was never
        // published, so it dies with the cycle.
        if (snapshotGeneration.get() != gen) {
            SnapshotCore.deleteFrame(screenshotPath)
            return
        }
        val gatedGroups = ocr?.let { usableGroups(it, auW, auH, skipEdgeGate = true) }.orEmpty()
        val groups = SnapshotCore.regionCenterFilter(gatedGroups, regionAu)
        val provenance = snapshotProvenance(ocr, srcId)
        if (ocr == null || groups.isEmpty()) {
            // A no-text verdict OWNS the display exactly like a successful
            // run: a re-run (empty region, gear re-OCR) must not leave the
            // PREVIOUS scene's caches behind — the frozen-frame word lookup
            // reads them directly and would happily look up words the panel
            // no longer describes (Codex review finding). Same generation
            // guard + epoch protocol as the success write; the visible boxes
            // are taken down by the panel's NoText recovery. The frame-file
            // handoff rides the guard: winning retires the replaced file
            // (this cycle's own stays for the NoText panel until the orphan
            // sweep); losing retires this cycle's never-published one.
            var replaced: String? = null
            var won = false
            synchronized(stateLock) {
                if (snapshotGeneration.get() == gen) {
                    won = true
                    cachedOcr = null
                    cachedGroupColors = null
                    cachedSnapshotTranslations = null
                    replaced = cachedScreenshotPath
                    cachedScreenshotPath = null
                    displayEpoch.advance()
                }
            }
            SnapshotCore.deleteFrame(if (won) replaced else screenshotPath)
            state.value = CaptureState.NoText(
                noTextStatusMessage(context, com.playtranslate.R.string.camera_snapshot_no_text, srcId),
                provenance,
                screenshotPath,
            )
            return
        }

        val gated = ocr.copy(groups = groups)
        // Color sampling matches the acquire path: sample a transient ×4
        // reference, never retain a bitmap.
        val groupColors = SnapshotCore.sampleGroupColors(frozen, groups.map { it.bounds })
        // The snapshot owns the display: epoch and caches move together,
        // same protocol as the acquire install. Translations are not in yet
        // — showFrozenOverlays renders skeletons until they land below.
        // Generation re-checked INSIDE the lock: a newer run bumps the
        // counter before its own cycle can reach this block, so a stale
        // cycle can never publish after (or over) a newer one. The
        // frame-file handoff rides the same guard: the file becomes shared
        // state ONLY here (frames are per-cycle unique files).
        var replacedFrame: String? = null
        var published = false
        synchronized(stateLock) {
            if (snapshotGeneration.get() == gen) {
                published = true
                cachedOcr = gated
                cachedGroupColors = groupColors
                cachedAuW = auW
                cachedAuH = auH
                cachedSnapshotTranslations = null
                replacedFrame = cachedScreenshotPath
                cachedScreenshotPath = screenshotPath
                displayEpoch.advance()
            }
        }
        if (!published) {
            SnapshotCore.deleteFrame(screenshotPath)
            return
        }
        // The predecessor re-run's frame (same episode, earlier cycle) is
        // unreferenced now; an in-flight Anki send pinned its copy at send
        // start, so the only exposure is a pin copy racing this delete —
        // degrading to a card without a screenshot.
        if (replacedFrame != screenshotPath) SnapshotCore.deleteFrame(replacedFrame)

        // Panel text must match what gets translated — see
        // [SnapshotCore.panelTextFor].
        val (originalText, segments) = SnapshotCore.panelTextFor(ocr, groups)

        // Non-empty overlayData lights the panel's "Show on screen" action;
        // the camera's BoxPresenter ignores the boxes themselves and paints
        // through the warp path ([showFrozenOverlays]) instead.
        val overlayData = OneShotOverlayData(emptyList(), 0, 0, auW, auH)
        state.value = CaptureState.Translating(originalText, segments, provenance, overlayData)

        // Recording pair captured BEFORE the translate call — a mid-flight
        // language change must not relabel these rows.
        val recordSrc = sourceLang
        val recordTgt = prefs.targetLang
        val perGroup = translator.translateDetailed(groups.map { it.text })
        // Superseded while translating: mirror the post-recognise check for
        // the remaining side effects (History rows, Done). Today the
        // withContext below would refuse the cancelled job anyway — every
        // generation bump travels with a cancel in one main-thread block —
        // but that pairing is call-site convention, and History records
        // "shown" translations, which a superseded run never shows.
        if (snapshotGeneration.get() != gen) return
        // Deliberate capture → History, camera provenance, grouped under
        // the frozen episode's capture session. The recorder is
        // main-thread-only; a supersede cancels this job before the Main
        // hop, so the token read matches the cycle's own episode.
        withContext(Dispatchers.Main) {
            val token = captureToken
            if (token != null) {
                groups.forEachIndexed { i, g ->
                    val tr = perGroup.getOrNull(i)?.text.orEmpty()
                    if (tr.isNotEmpty()) snapshotLogRecorder.onCaptureShown(
                        token, g.text, tr, g.bounds, recordSrc, recordTgt,
                        com.playtranslate.translationlog.TranslationHistoryStore.PROVENANCE_CAMERA,
                        perGroup.getOrNull(i)?.backendDisplayName,
                        captureImage = com.playtranslate.translationlog
                            .HistoryImageStore.Source.FromBitmap(frozen),
                    )
                }
            }
        }

        // Promote the frozen overlays' data source BEFORE emitting Done: the
        // panel's Done handler asks the presenter to fill the on-frame boxes,
        // which reads this cache.
        synchronized(stateLock) {
            if (cachedOcr === gated) cachedSnapshotTranslations = perGroup.map { it.text }
        }

        state.value = SnapshotCore.doneState(
            originalText, segments, perGroup, screenshotPath, provenance,
            prefs.langContext(srcId), auW, auH,
        )
    }

    /** Mirror of the service's provenance builders: engine from the result
     *  when OCR ran, else the currently-selected backend — the no-text
     *  gear needs a token to key the picker. Full-frame region; frames are
     *  camera keyframes (no system UI, no own overlays). */
    private fun snapshotProvenance(
        ocr: OcrManager.OcrResult?,
        srcId: com.playtranslate.language.SourceLangId,
    ): com.playtranslate.model.OcrProvenance? = SnapshotCore.snapshotProvenance(
        context, ocr, srcId, prefs.cameraOcrBackendToken(srcId),
    )

    /** Save the frozen frame under a unique per-cycle name — see
     *  [SnapshotCore.saveFrame] for why fixed per-tool names were an
     *  aliasing bug class (stacked activity instances share no generation
     *  counter). Powers the panel's no-text affordances, Anki attachments,
     *  and the re-OCR path. */
    private fun saveSnapshotToCache(frozen: Bitmap): String? =
        SnapshotCore.saveFrame(context, frozen, CAMERA_FRAME_PREFIX, TAG)

    /** Paint the snapshot's boxes as the camera's own warp overlays: a
     *  static IDENTITY homography over the frozen frame (centerCrop ==
     *  FILL_CENTER == CameraCoordinates, so AU-space boxes land exactly on
     *  the frozen text). Boxes follow the current flavor — furigana
     *  readings in furigana mode (via [buildAndShow]'s furigana branch,
     *  which never translates), translation boxes otherwise — rendered from
     *  the snapshot pipeline's own translations: skeletons while those are
     *  in flight, filled once [cachedSnapshotTranslations] lands. Never
     *  calls the translator itself. Analysis is halted in FROZEN, so
     *  nothing overwrites the static transform. Safe to call again on the
     *  Done promotion — each call is its own publication SOURCE (epoch
     *  advanced atomically with the cache read, the live re-flavor's
     *  pattern), so a slower earlier repaint (skeleton raster) can never
     *  land over a faster later one (filled boxes on a cached-translation
     *  re-snap, flavor double-cycle): its commits fail the epoch check. */
    fun showFrozenOverlays() {
        val ocr: OcrManager.OcrResult?
        val colors: List<Pair<Int, Int>>?
        val translations: List<String>?
        val auW: Int
        val auH: Int
        val epoch: Int
        synchronized(stateLock) {
            ocr = cachedOcr
            colors = cachedGroupColors
            translations = cachedSnapshotTranslations
            auW = cachedAuW
            auH = cachedAuH
            epoch = displayEpoch.advance()
        }
        if (ocr == null || colors == null || auW == 0) return
        overlayHost.post { ensureWarpView().applyHomography(Homography.IDENTITY) }
        scope.launch(Dispatchers.Default) {
            try {
                when (prefs.cameraOverlayMode) {
                    OverlayMode.FURIGANA -> buildAndShow(ocr, colors, auW, auH, epoch)
                    OverlayMode.TRANSLATION ->
                        showFrozenTranslationBoxes(ocr, colors, translations, auW, auH, epoch)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "frozen overlay build failed", e)
            }
        }
    }

    /** The translation-flavor frozen render: placeholder boxes, filled from
     *  the pipeline's [translations] when available. No tracker regions —
     *  the frame is static and everything rides the IDENTITY transform. */
    private suspend fun showFrozenTranslationBoxes(
        ocr: OcrManager.OcrResult,
        colors: List<Pair<Int, Int>>,
        translations: List<String>?,
        auW: Int,
        auH: Int,
        epoch: Int,
    ) {
        val groups = ocr.groups.filter { it.text.isNotBlank() }
        val trackKeys = groups.indices.toList()
        val placeholders = buildPlaceholderBoxes(groups, colors)
        val boxes = if (translations == null) placeholders
        else placeholders.mapIndexed { idx, ph ->
            ph.copy(translatedText = translations.getOrElse(idx) { "" })
        }
        showRegions(boxes, trackKeys, auW, auH, epoch)
    }

    fun hideFrozenOverlays() {
        overlayHost.post { warpView?.clearRegions() }
    }

    /** The panel's in-place-edit re-translation, on the camera's translator.
     *  Null when the whole waterfall failed — the panel binds its "—"
     *  placeholder. */
    suspend fun translateForPanel(text: String): com.playtranslate.ui.CaptureResultOverlay.PanelTranslation? {
        val d = translator.translateDetailed(listOf(text)).firstOrNull() ?: return null
        if (d.text.isEmpty()) return null
        return com.playtranslate.ui.CaptureResultOverlay.PanelTranslation(
            d.text, d.note, d.backendDisplayName,
        )
    }

    /** Final teardown from the Activity. Not restartable. */
    fun shutdown() {
        analysisExecutor.execute {
            frameTracker.release()
            cnConverter.release()
            freezeRing.clear()
            while (anchorCache.isNotEmpty()) anchorCache.removeFirst().first.release()
        }
        analysisExecutor.shutdown()
        synchronized(stateLock) {
            cachedOcr = null
            cachedGroupColors = null
            lastBuilt = null
            displayEpoch.advance()
        }
    }
}
