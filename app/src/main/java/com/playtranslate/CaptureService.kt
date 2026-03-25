package com.playtranslate

import android.app.Notification
import android.app.NotificationChannel
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.app.NotificationManager
import androidx.lifecycle.MutableLiveData
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.playtranslate.model.TranslationResult
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.hardware.display.DisplayManager
import com.playtranslate.ui.TranslationOverlayView

private const val TAG = "CaptureService"
private const val NOTIF_ID = 1001
private const val CHANNEL_ID = "playtranslate_capture"

/**
 * Foreground service that owns the OCR + translation pipeline.
 *
 * Translation priority:
 *  1. DeepL   — if an API key is configured in Settings
 *  2. Lingva  — free Google Translate proxy, no key required (default)
 *  3. ML Kit  — offline fallback when both online options are unavailable
 *
 * Notes are shown inline with the result only when ML Kit is used.
 */
class CaptureService : Service() {

    // ── Binder ────────────────────────────────────────────────────────────

    inner class LocalBinder : Binder() {
        fun getService(): CaptureService = this@CaptureService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    // ── Coroutines ────────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Region session ───────────────────────────────────────────────────
    //
    // All state tied to a specific capture region lives here. On region
    // change the old session is cancelled and replaced atomically — no
    // field-by-field reset needed.

    private inner class RegionSession(val region: RegionEntry) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        // Jobs
        var captureJob: Job? = null
        var liveCaptureJob: Job? = null
        var cleanProcessingJob: Job? = null
        var interactionDebounceJob: Job? = null

        // Live mode / dedup
        var lastLiveOcrText: String? = null
        var cachedOverlayBoxes: List<TranslationOverlayView.TextBox>? = null
        var cachedOverlayCropLeft = 0
        var cachedOverlayCropTop = 0
        var cachedOverlayScreenW = 0
        var cachedOverlayScreenH = 0
        var liveShowRegionFlash = false

        // Detection
        var sceneMoving = false
        var forceCheckC = false
        var detectionRefNonOverlay: IntArray? = null
        var detectionRefOverlay: IntArray? = null
        var detectionOverlayActive: BooleanArray? = null
        var detectionNonOverlayPositions: List<Pair<Int, Int>> = emptyList()
        var detectionOverlaySamples: List<OverlaySampleData> = emptyList()
        var detectionOverlayBoxes: List<android.graphics.Rect> = emptyList()
        var detectionPrevNonOverlay: IntArray? = null
        var detectionHasPrev = false
        var stabilizationFrameCount = 0
        var detectionOverlayTextBoxes: List<TranslationOverlayView.TextBox> = emptyList()

        /** Clear all cached overlay, dedup, and detection state. Keeps the scope alive. */
        fun clearCachedState() {
            lastLiveOcrText = null
            cachedOverlayBoxes = null
            sceneMoving = false
            forceCheckC = false
            detectionRefNonOverlay = null
            detectionRefOverlay = null
            detectionOverlayActive = null
            detectionNonOverlayPositions = emptyList()
            detectionOverlaySamples = emptyList()
            detectionOverlayBoxes = emptyList()
            detectionPrevNonOverlay = null
            detectionHasPrev = false
            stabilizationFrameCount = 0
            detectionOverlayTextBoxes = emptyList()
        }

        /** Cancel scope and all tracked jobs. */
        fun cancel() {
            interactionDebounceJob?.cancel()
            scope.cancel()
        }
    }

    private var session = RegionSession(RegionEntry("", 0f, 1f))

    // ── Pipeline ──────────────────────────────────────────────────────────

    private val ocrManager get() = OcrManager.instance
    private var translationManager: TranslationManager? = null  // ML Kit offline fallback
    private var deeplTranslator: DeepLTranslator?  = null       // optional, key required
    private var lingvaTranslator: LingvaTranslator? = null      // always present after configure()

    private var gameDisplayId: Int = 0
    private var sourceLang: String = TranslateLanguage.JAPANESE
    private var savedRegion = RegionEntry("", 0f, 1f)
    private var overrideRegion: RegionEntry? = null

    /** Observable active region — override if set, otherwise saved. */
    val activeRegionLiveData = MutableLiveData(RegionEntry("", 0f, 1f))
    /** Current active region snapshot for synchronous reads. */
    val activeRegion: RegionEntry get() = activeRegionLiveData.value!!
    /** True when a temporary override region is active. */
    val isOverride: Boolean get() = overrideRegion != null

    private fun updateActiveRegion() {
        session.cancel()
        val newRegion = overrideRegion ?: savedRegion
        activeRegionLiveData.value = newRegion
        session = RegionSession(newRegion)

        PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
        if (liveActive) {
            session.liveShowRegionFlash = true
            PlayTranslateAccessibilityService.instance?.screenshotManager?.requestCleanCapture()
        }
    }

    // ── Callbacks to Activity ─────────────────────────────────────────────

    var onResult: ((TranslationResult) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStatusUpdate: ((String) -> Unit)? = null
    var onTranslationStarted: (() -> Unit)? = null
    /** Fired during live mode when an OCR cycle finds no source-language text. */
    var onLiveNoText: (() -> Unit)? = null
    /** Emitted when hold-to-preview loading state changes. */
    var onHoldLoadingChanged: ((Boolean) -> Unit)? = null

    /** Observable degraded translation state (ML Kit fallback). */
    val degradedState = MutableLiveData(false)
    val translationDegraded: Boolean get() = degradedState.value == true

    private fun setDegraded(degraded: Boolean) {
        if (translationDegraded == degraded) return
        degradedState.postValue(degraded)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        PlayTranslateAccessibilityService.instance?.hideFloatingIcon()
    }

    override fun onDestroy() {
        instance = null
        stopLive()
        serviceScope.cancel()
        translationManager?.close()
        deeplTranslator?.close()
        lingvaTranslator?.close()
        // Clear callbacks to release Activity references
        onResult = null
        onError = null
        onStatusUpdate = null
        onTranslationStarted = null
        onLiveNoText = null
        onHoldLoadingChanged = null
        super.onDestroy()
    }

    // ── Public API ────────────────────────────────────────────────────────

    /** Apply a temporary override region. Does not change display/language/engines. */
    fun configureOverride(region: RegionEntry) {
        overrideRegion = region
        updateActiveRegion()
    }

    /** Clear any temporary override, reverting to the saved region. */
    fun clearOverride() {
        overrideRegion = null
        updateActiveRegion()
    }

    /** Configure the saved region and translation engines. Clears any override. */
    fun configureSaved(
        displayId: Int,
        sourceLang: String = TranslateLanguage.JAPANESE,
        targetLang: String = TranslateLanguage.ENGLISH,
        region: RegionEntry = RegionEntry("", 0f, 1f)
    ) {
        gameDisplayId    = displayId
        this.sourceLang  = sourceLang
        this.savedRegion = region
        this.overrideRegion = null
        updateActiveRegion()

        // Clear translation cache when settings change — translations may
        // be in the wrong language or from a different service.
        translationCache.clear()

        val deeplKey = Prefs(this).deeplApiKey

        // DeepL — only when a key is configured
        if (deeplKey.isNotBlank()) {
            val same = deeplTranslator?.let {
                it.apiKey == deeplKey && it.sourceLang == sourceLang && it.targetLang == targetLang
            } ?: false
            if (!same) {
                deeplTranslator?.close()
                deeplTranslator = DeepLTranslator(deeplKey, sourceLang, targetLang)
            }
        } else {
            deeplTranslator?.close()
            deeplTranslator = null
        }

        // Lingva — always present, recreate only when language pair changes
        val lingvaNeedsUpdate = lingvaTranslator?.let {
            it.sourceLang != sourceLang || it.targetLang != targetLang
        } ?: true
        if (lingvaNeedsUpdate) {
            lingvaTranslator?.close()
            lingvaTranslator = LingvaTranslator(sourceLang, targetLang)
        }

        // ML Kit — always kept as last-resort offline fallback.
        // Download happens silently in the background; no status shown because
        // Lingva is the primary online option and ML Kit is rarely needed.
        val existing = translationManager
        val needsNewManager = existing == null ||
                existing.sourceLang != sourceLang ||
                existing.targetLang != targetLang
        if (needsNewManager) {
            existing?.close()
            val newManager = TranslationManager(sourceLang, targetLang)
            translationManager = newManager
            serviceScope.launch {
                try {
                    newManager.ensureModelReady()
                } catch (e: Exception) {
                    Log.w(TAG, "ML Kit model download failed (offline fallback unavailable): ${e.message}")
                }
            }
        }

        onStatusUpdate?.invoke(getString(R.string.status_idle))
    }

    fun captureOnce() {
        session.captureJob?.cancel()
        session.captureJob = session.scope.launch { runCaptureCycle() }
    }

    /**
     * Processes a pre-captured screenshot bitmap instead of taking a new one.
     * Used when the screenshot must be taken before an activity appears on screen
     * (e.g. single-screen region capture from the floating menu).
     */
    fun processScreenshot(raw: Bitmap) {
        session.captureJob?.cancel()
        session.captureJob = session.scope.launch { runProcessCycle(raw) }
    }

    private suspend fun runProcessCycle(raw: Bitmap) {
        if (!isConfigured) {
            onError?.invoke("Not configured — tap Translate to set up")
            raw.recycle()
            return
        }
        var bitmap: Bitmap = raw
        try {
            onStatusUpdate?.invoke(getString(R.string.status_capturing))
            val mgr = PlayTranslateAccessibilityService.instance?.screenshotManager
            val screenshotPath = mgr?.saveToCache(raw)

            val statusBarHeight = getStatusBarHeightForDisplay(gameDisplayId)
            val top    = maxOf((raw.height * activeRegion.top).toInt(), statusBarHeight)
            val left   = (raw.width  * activeRegion.left).toInt()
            val bottom = (raw.height * activeRegion.bottom).toInt()
            val right  = (raw.width  * activeRegion.right).toInt()
            bitmap = cropBitmap(raw, top, bottom, left, right)

            val ocrBitmap = blackoutFloatingIcon(bitmap, left, top)
            bitmap = ocrBitmap // track current bitmap for finally
            onStatusUpdate?.invoke(getString(R.string.status_ocr))
            val ocrResult = ocrManager.recognise(ocrBitmap, sourceLang, screenshotWidth = raw.width)
            ocrBitmap.recycle()
            bitmap = raw // already recycled by cropBitmap or ocrBitmap.recycle()

            if (ocrResult == null) {
                onStatusUpdate?.invoke(noTextMessage())
                return
            }

            onStatusUpdate?.invoke(getString(R.string.status_translating))
            val (translated, note) = translateGroups(ocrResult.groupTexts)

            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            onResult?.invoke(
                TranslationResult(
                    originalText   = ocrResult.fullText,
                    segments       = ocrResult.segments,
                    translatedText = translated,
                    timestamp      = timestamp,
                    screenshotPath = screenshotPath,
                    note           = note
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Process cycle failed: ${e.message}", e)
            onError?.invoke(e.message ?: "Unknown error")
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    // ── Live mode ─────────────────────────────────────────────────────────
    //
    // Interaction-driven: capture once, show overlay, then wait for user
    // input. On input → hide overlay + start debounce timer. When the
    // timer expires (no further input) → capture again.

    /** Observable live mode state. Observe this to react to live mode changes. */
    val liveModeState = MutableLiveData(false)
    private var liveActive: Boolean
        get() = liveModeState.value == true
        set(v) { liveModeState.value = v }
    private val MAX_STABILIZATION_FRAMES = 10

    val isLive: Boolean get() = liveActive

    fun startLive() {
        liveActive = true
        session.liveShowRegionFlash = true
        PlayTranslateAccessibilityService.instance?.floatingIcon?.liveMode = true
        session.captureJob?.cancel()
        session.liveCaptureJob?.cancel()
        session.cleanProcessingJob?.cancel()

        // Buttons and touch are detected via onKeyEvent and touch sentinel.
        PlayTranslateAccessibilityService.instance
            ?.startInputMonitoring(gameDisplayId) { onUserInteraction() }

        val mgr = PlayTranslateAccessibilityService.instance?.screenshotManager
        if (mgr == null) {
            DetectionLog.log("ERROR: screenshotManager is null, can't start loop")
            return
        }
        DetectionLog.log("Starting loop on display $gameDisplayId")
        mgr.requestCleanCapture()  // first frame should be clean
        mgr.startLoop(gameDisplayId, serviceScope,
            onCleanFrame = { bitmap -> handleCleanFrame(bitmap) },
            onRawFrame = { bitmap -> handleRawFrame(bitmap) }
        )
    }

    fun stopLive() {
        liveActive = false
        session.cancel()
        session = RegionSession(activeRegion)
        PlayTranslateAccessibilityService.instance?.screenshotManager?.stopLoop()
        PlayTranslateAccessibilityService.instance?.stopInputMonitoring()
        PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
        PlayTranslateAccessibilityService.instance?.floatingIcon?.liveMode = false
        setDegraded(false)
    }

    // ── Unified loop handlers ─────────────────────────────────────────────

    /**
     * Called by the screenshot loop for each clean frame (overlays were hidden).
     * Launches OCR/translate asynchronously — the loop continues with raw frames.
     */
    private fun handleCleanFrame(raw: Bitmap) {
        DetectionLog.log("Clean frame received")

        // Cancel any in-flight processing from a previous clean frame
        session.cleanProcessingJob?.cancel()
        session.cleanProcessingJob = session.scope.launch {
            try {
                processCleanFrame(raw)
            } catch (e: kotlinx.coroutines.CancellationException) {
                DetectionLog.log("processClean: cancelled, requesting fresh capture")
                if (liveActive) {
                    PlayTranslateAccessibilityService.instance?.screenshotManager?.requestCleanCapture()
                }
                throw e
            }
        }
    }

    /**
     * Called by the screenshot loop for each raw frame (overlays visible).
     * Runs pixel diff detection synchronously — must be fast.
     */
    private fun handleRawFrame(bitmap: Bitmap) {
        if (session.cleanProcessingJob?.isActive == true) {
            // Still processing a clean frame — skip detection
            bitmap.recycle()
            return
        }

        val s = session
        val refNonOverlay = s.detectionRefNonOverlay
        val refOverlay = s.detectionRefOverlay
        val overlayActive = s.detectionOverlayActive
        val nonOverlayPositions = s.detectionNonOverlayPositions
        val overlaySamples = s.detectionOverlaySamples

        if (refNonOverlay == null || nonOverlayPositions.isEmpty()) {
            bitmap.recycle()
            return
        }

        // First raw frame after detection setup: set overlay reference
        if (s.detectionRefOverlay == null && overlaySamples.isNotEmpty()) {
            val ovrRef = IntArray(overlaySamples.size)
            val ovrActive = BooleanArray(overlaySamples.size)
            for (i in overlaySamples.indices) {
                val sample = overlaySamples[i]
                val px = if (sample.x < bitmap.width && sample.y < bitmap.height) bitmap.getPixel(sample.x, sample.y) else 0
                ovrRef[i] = px
                ovrActive[i] = !isColorMatch(px, sample.textColor)
            }
            s.detectionRefOverlay = ovrRef
            s.detectionOverlayActive = ovrActive
            val activeCount = ovrActive.count { it }
            DetectionLog.log("Overlay ref set: ${overlaySamples.size} ovr ($activeCount active)")
            bitmap.recycle()
            return  // skip this frame, start comparing from the next
        }

        // Read current pixels
        val currentNonOverlay = IntArray(nonOverlayPositions.size)
        for (i in nonOverlayPositions.indices) {
            val (x, y) = nonOverlayPositions[i]
            currentNonOverlay[i] = if (x < bitmap.width && y < bitmap.height) bitmap.getPixel(x, y) else 0
        }
        val currentOverlay = IntArray(overlaySamples.size)
        for (i in overlaySamples.indices) {
            val sample = overlaySamples[i]
            currentOverlay[i] = if (sample.x < bitmap.width && sample.y < bitmap.height) bitmap.getPixel(sample.x, sample.y) else 0
        }

        if (!s.sceneMoving) {
            // CHECK A: Non-overlay pixel diff
            val nonOverlayDiff = pixelDiffPercent(refNonOverlay, currentNonOverlay)
            if (nonOverlayDiff >= SCENE_CHANGE_THRESHOLD) {
                DetectionLog.log("A: Scene change (${"%.2f".format(nonOverlayDiff*100)}%)")
                bitmap.recycle()
                s.sceneMoving = true
                s.detectionHasPrev = false
                s.stabilizationFrameCount = 0
                s.lastLiveOcrText = null
                s.cachedOverlayBoxes = null
                PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
                return
            }

            // CHECK B: Per-box overlay diff — selective removal
            val overlayResult = if (refOverlay != null && overlayActive != null)
                overlayDiffPerBox(overlaySamples, refOverlay, currentOverlay, overlayActive)
            else OverlayDiffResult(0f, emptySet())
            val overlayDiff = overlayResult.maxDiff
            if (overlayResult.triggeredBoxIndices.isNotEmpty()) {
                val allBoxes = s.cachedOverlayBoxes
                val fullBoxes = s.detectionOverlayBoxes
                if (allBoxes != null && fullBoxes.isNotEmpty()) {
                    val toRemove = findNearbyBoxIndices(overlayResult.triggeredBoxIndices, fullBoxes)
                    DetectionLog.log("B: Removing ${toRemove.size}/${allBoxes.size} overlays (triggered=${overlayResult.triggeredBoxIndices})")

                    val remainingBoxes = allBoxes.filterIndexed { i, _ -> i !in toRemove }
                    val remainingFullBoxes = fullBoxes.filterIndexed { i, _ -> i !in toRemove }

                    s.cachedOverlayBoxes = remainingBoxes.ifEmpty { null }
                    s.detectionOverlayBoxes = remainingFullBoxes
                    // Clear dedup text so the removed boxes' text is re-discovered as "new"
                    s.lastLiveOcrText = null

                    if (remainingBoxes.isNotEmpty()) {
                        showLiveOverlay(remainingBoxes, s.cachedOverlayCropLeft, s.cachedOverlayCropTop,
                            s.cachedOverlayScreenW, s.cachedOverlayScreenH)
                    } else {
                        PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
                    }

                    s.forceCheckC = true
                    s.detectionRefOverlay = null
                    s.detectionOverlayActive = null
                    s.detectionOverlaySamples = s.detectionOverlaySamples.filter { it.boxIdx !in toRemove }

                    bitmap.recycle()
                    return
                }
            }

            // GATE: Any change at all? (forceCheckC bypasses this)
            val anyChange = s.forceCheckC || nonOverlayDiff > 0.005f || overlayDiff > 0.005f
            if (s.forceCheckC) s.forceCheckC = false
            if (anyChange) {
                DetectionLog.log("C: Change (non=${"%.2f".format(nonOverlayDiff*100)}% ovr=${"%.2f".format(overlayDiff*100)}%)")
                if (s.cachedOverlayBoxes.isNullOrEmpty()) {
                    // No overlays — raw screenshot is unobstructed, use as clean
                    DetectionLog.log("C: No overlays → raw as clean")
                    handleCleanFrame(bitmap)
                    return
                }
                // Fill-and-OCR to detect new text (runs async, bitmap ownership transferred)
                val overlayBoxesCopy = s.detectionOverlayBoxes.toList()
                s.scope.launch {
                    val triggered = performOcrRecheck(bitmap, overlayBoxesCopy)
                    if (triggered) {
                        DetectionLog.log("D: New text → recapture/merge")
                    }
                }
            } else {
                DetectionLog.log("Static (non=${"%.2f".format(nonOverlayDiff*100)}% ovr=${"%.2f".format(overlayDiff*100)}%)")
                bitmap.recycle()
            }
        } else {
            // Phase 2: Stabilization — compare consecutive frames
            s.stabilizationFrameCount++
            val prevNonOverlay = s.detectionPrevNonOverlay
            if (s.detectionHasPrev && prevNonOverlay != null) {
                val pct = pixelDiffPercent(prevNonOverlay, currentNonOverlay)
                if (pct < SCENE_CHANGE_THRESHOLD) {
                    DetectionLog.log("Stabilized (${"%.2f".format(pct*100)}%) frame ${s.stabilizationFrameCount} → clean as raw")
                    s.sceneMoving = false
                    handleCleanFrame(bitmap)
                    return
                }
                if (s.stabilizationFrameCount >= MAX_STABILIZATION_FRAMES) {
                    DetectionLog.log("Stabilization timeout (${"%.2f".format(pct*100)}%) after ${s.stabilizationFrameCount} frames → forcing clean")
                    s.sceneMoving = false
                    handleCleanFrame(bitmap)
                    return
                }
                DetectionLog.log("Waiting to stabilize (${"%.2f".format(pct*100)}%) frame ${s.stabilizationFrameCount}/$MAX_STABILIZATION_FRAMES")
            }
            s.detectionPrevNonOverlay = currentNonOverlay
            s.detectionHasPrev = true
            bitmap.recycle()
        }
    }

    /**
     * Process a clean frame: crop, OCR, translate, show overlays, set up detection.
     * Runs in a coroutine launched by [handleCleanFrame].
     */
    private suspend fun processCleanFrame(raw: Bitmap) {
        if (!isConfigured) {
            DetectionLog.log("processClean: not configured, skipping")
            raw.recycle(); return
        }

        var colorRef: Bitmap? = null
        try {
            val statusBarHeight = getStatusBarHeightForDisplay(gameDisplayId)
            val top    = maxOf((raw.height * activeRegion.top).toInt(), statusBarHeight)
            val left   = (raw.width  * activeRegion.left).toInt()
            val bottom = (raw.height * activeRegion.bottom).toInt()
            val right  = (raw.width  * activeRegion.right).toInt()
            val needsCrop = top > 0 || left > 0 || bottom < raw.height || right < raw.width
            val bitmap = if (needsCrop)
                Bitmap.createBitmap(raw, left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1))
            else raw

            val colorScale = 4
            colorRef = Bitmap.createScaledBitmap(raw, raw.width / colorScale, raw.height / colorScale, false)

            val s = session
            if (s.liveShowRegionFlash) {
                s.liveShowRegionFlash = false
                flashRegionIndicator()
            }

            val ocrBitmap = blackoutFloatingIcon(bitmap, left, top)
            val ocrResult = ocrManager.recognise(ocrBitmap, sourceLang, screenshotWidth = raw.width)
            if (ocrBitmap !== raw) ocrBitmap.recycle()

            if (ocrResult == null) {
                DetectionLog.log("processClean: OCR returned null")
                s.lastLiveOcrText = null
                s.cachedOverlayBoxes = null
                PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
                onLiveNoText?.invoke()
                setupDetection(raw, emptyList(), emptyList())
                return
            }

            val newText = ocrResult.fullText
            val dedupKey = newText.filter { c -> OcrManager.isSourceLangChar(c, sourceLang) }

            if (dedupKey.isEmpty()) {
                DetectionLog.log("processClean: no source-lang chars")
                s.lastLiveOcrText = null
                s.cachedOverlayBoxes = null
                PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
                onLiveNoText?.invoke()
                setupDetection(raw, emptyList(), emptyList())
                return
            }

            // Dedup
            if (s.lastLiveOcrText != null && !isSignificantChange(s.lastLiveOcrText!!, dedupKey)) {
                val boxes = s.cachedOverlayBoxes
                if (boxes != null) {
                    DetectionLog.log("processClean: dedup match, re-showing cached")
                    showLiveOverlay(boxes, s.cachedOverlayCropLeft, s.cachedOverlayCropTop,
                        s.cachedOverlayScreenW, s.cachedOverlayScreenH,
                        )
                    setupDetection(raw, boxes.map { b ->
                        android.graphics.Rect(
                            b.bounds.left + s.cachedOverlayCropLeft, b.bounds.top + s.cachedOverlayCropTop,
                            b.bounds.right + s.cachedOverlayCropLeft, b.bounds.bottom + s.cachedOverlayCropTop
                        )
                    }, boxes)
                    return
                }
                // No cached boxes (e.g. all selectively removed) — fall through to re-translate
                DetectionLog.log("processClean: dedup match but no cached boxes, re-translating")
            }
            s.lastLiveOcrText = dedupKey
            DetectionLog.log("processClean: new text, ${ocrResult.groupTexts.size} groups, translating...")

            val mgr = PlayTranslateAccessibilityService.instance?.screenshotManager
            val screenshotPath = mgr?.saveToCache(raw)
            val screenshotW = raw.width
            val screenshotH = raw.height

            val liveGroupTexts = ocrResult.groupTexts
            val liveGroupBounds = ocrResult.groupBounds
            val liveGroupLineCounts = ocrResult.groupLineCounts

            // Show shimmer placeholders
            val buffer = 10 / colorScale
            val cRef = colorRef!!
            val placeholderBoxes = liveGroupBounds.mapIndexed { idx, bounds ->
                val sl = (bounds.left + left) / colorScale
                val st = (bounds.top + top) / colorScale
                val sr = (bounds.right + left) / colorScale
                val sb = (bounds.bottom + top) / colorScale
                val bgColor = averageColor(cRef,
                    sl - buffer, st - buffer, sr + buffer, sb + buffer,
                    excludeInner = android.graphics.Rect(sl, st, sr, sb))
                val textColor = if (colorLuminance(bgColor) > 128)
                    android.graphics.Color.BLACK else android.graphics.Color.WHITE
                val lineCount = liveGroupLineCounts.getOrElse(idx) { 1 }
                TranslationOverlayView.TextBox("", bounds, bgColor, textColor, lineCount)
            }
            showLiveOverlay(placeholderBoxes, left, top, screenshotW, screenshotH,
                )

            // Translate
            onTranslationStarted?.invoke()
            val perGroup = translateGroupsSeparately(liveGroupTexts)
            val translated = perGroup.joinToString("\n\n") { it.first }
            val note = perGroup.mapNotNull { it.second }.firstOrNull()
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            onResult?.invoke(
                TranslationResult(
                    originalText   = newText,
                    segments       = ocrResult.segments,
                    translatedText = translated,
                    timestamp      = timestamp,
                    screenshotPath = screenshotPath,
                    note           = note
                )
            )

            // Show final overlays and set up detection
            if (liveGroupBounds.size == perGroup.size) {
                val overlayBoxes = perGroup.zip(placeholderBoxes).map { (tr, placeholder) ->
                    placeholder.copy(translatedText = tr.first)
                }
                s.cachedOverlayBoxes = overlayBoxes
                s.cachedOverlayCropLeft = left
                s.cachedOverlayCropTop = top
                s.cachedOverlayScreenW = screenshotW
                s.cachedOverlayScreenH = screenshotH
                showLiveOverlay(overlayBoxes, left, top, screenshotW, screenshotH,
                    )

                val fullDisplayBoxes = overlayBoxes.map { b ->
                    android.graphics.Rect(
                        b.bounds.left + left, b.bounds.top + top,
                        b.bounds.right + left, b.bounds.bottom + top
                    )
                }
                setupDetection(raw, fullDisplayBoxes, overlayBoxes)
                DetectionLog.log("processClean: done, ${overlayBoxes.size} overlays shown")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            DetectionLog.log("processClean: cancelled")
            throw e
        } catch (e: Throwable) {
            DetectionLog.log("processClean: ERROR ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "processCleanFrame failed: ${e.javaClass.simpleName}: ${e.message}", e)
        } finally {
            colorRef?.recycle()
            if (!raw.isRecycled) raw.recycle()
        }
    }

    /**
     * Set up detection reference pixels from a clean capture. Called after
     * overlays are displayed. The next raw frame from the loop will set the
     * overlay pixel reference (since it includes the rendered overlays).
     */
    private fun setupDetection(
        cleanRef: Bitmap,
        fullDisplayBoxes: List<android.graphics.Rect>,
        textBoxes: List<TranslationOverlayView.TextBox>
    ) {
        val regionTop = (cleanRef.height * activeRegion.top).toInt()
        val regionBottom = (cleanRef.height * activeRegion.bottom).toInt()
        val regionLeft = (cleanRef.width * activeRegion.left).toInt()
        val regionRight = (cleanRef.width * activeRegion.right).toInt()

        val nonOvrPos = mutableListOf<Pair<Int, Int>>()
        val ovrSamples = mutableListOf<OverlaySampleData>()

        for (y in regionTop until regionBottom step 10) {
            for (x in regionLeft until regionRight step 10) {
                if (fullDisplayBoxes.none { it.contains(x, y) }) {
                    nonOvrPos.add(x to y)
                }
            }
        }
        for ((boxIdx, box) in fullDisplayBoxes.withIndex()) {
            val textColor = textBoxes.getOrNull(boxIdx)?.textColor ?: 0
            val bTop = box.top.coerceIn(regionTop, regionBottom)
            val bBottom = box.bottom.coerceIn(regionTop, regionBottom)
            val bLeft = box.left.coerceIn(regionLeft, regionRight)
            val bRight = box.right.coerceIn(regionLeft, regionRight)
            for (y in bTop until bBottom step 3) {
                for (x in bLeft until bRight step 3) {
                    ovrSamples.add(OverlaySampleData(x, y, textColor, boxIdx))
                }
            }
        }

        // Non-overlay: use clean capture as baseline (catches typewriter changes)
        val s = session
        s.detectionRefNonOverlay = IntArray(nonOvrPos.size) { i ->
            val (x, y) = nonOvrPos[i]
            if (x < cleanRef.width && y < cleanRef.height) cleanRef.getPixel(x, y) else 0
        }
        s.detectionNonOverlayPositions = nonOvrPos
        s.detectionOverlaySamples = ovrSamples
        s.detectionOverlayBoxes = fullDisplayBoxes
        s.detectionOverlayTextBoxes = textBoxes

        // Overlay reference will be set from the FIRST raw frame after this
        // (since we need the rendered overlays in the screenshot).
        s.detectionRefOverlay = null
        s.detectionOverlayActive = null
        s.detectionHasPrev = false
        s.detectionPrevNonOverlay = null
        s.sceneMoving = false

        // Detection resumes when cleanProcessingJob finishes (job.isActive becomes false)
        // and these references are non-null. handleRawFrame will set overlay ref from the first frame.
        DetectionLog.log("Detection setup: ${nonOvrPos.size} non-ovr (clean), ${ovrSamples.size} ovr (waiting for raw ref)")
    }

    /** Trigger a fresh capture cycle in live mode (e.g. after hold-release). */
    fun refreshLiveOverlay() {
        if (!liveActive) return
        Log.d(TAG, "REFRESH: refreshLiveOverlay called")
        session.clearCachedState()
        session.interactionDebounceJob?.cancel()
        session.cleanProcessingJob?.cancel()
        // Request a clean capture on the next loop iteration
        PlayTranslateAccessibilityService.instance?.screenshotManager?.requestCleanCapture()
    }

    /** One-shot: capture, OCR, translate, show overlay (not live mode). */
    fun showOneShotOverlay() {
        val mgr = PlayTranslateAccessibilityService.instance?.screenshotManager
        if (mgr != null && mgr.isLoopRunning) {
            // Loop is active — request a clean frame through it
            mgr.requestCleanCapture()
        } else {
            // Not in loop mode — standalone capture
            session.liveCaptureJob?.cancel()
            session.liveCaptureJob = session.scope.launch { runLiveCaptureCycle() }
        }
    }

    /** True while a hold gesture or modal UI is active — suppresses overlay display in live mode. */
    var holdActive = false

    /** Path to the last clean screenshot. Delegates to [ScreenshotManager]. */
    val lastCleanScreenshotPath: String?
        get() = PlayTranslateAccessibilityService.instance?.screenshotManager?.lastCleanPath

    /** Begin a hold-to-preview gesture. In non-live: captures and shows overlay. In live: hides overlays. */
    fun holdStart() {
        if (liveActive) {
            holdActive = true
            PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
        } else {
            onHoldLoadingChanged?.invoke(true)
            showOneShotOverlay()
        }
    }

    /** End a hold-to-preview gesture. In non-live: removes overlay. In live: restores overlays. */
    fun holdEnd() {
        onHoldLoadingChanged?.invoke(false)
        if (liveActive) {
            holdActive = false
            refreshLiveOverlay()
        } else {
            session.liveCaptureJob?.cancel()
            PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
        }
    }

    /** Cancel a hold gesture (e.g. user started dragging). Cleans up without triggering refresh. */
    fun holdCancel() {
        onHoldLoadingChanged?.invoke(false)
        if (liveActive) {
            holdActive = false
        } else {
            session.liveCaptureJob?.cancel()
        }
        PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
    }

    /**
     * Briefly flash the capture region indicator on the game display.
     * Called after a screenshot is captured so the indicator doesn't
     * appear in the screenshot.
     */
    private fun flashRegionIndicator() {
        val a11y = PlayTranslateAccessibilityService.instance ?: return
        val dm = getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(gameDisplayId) ?: return
        a11y.showRegionIndicator(display, activeRegion)
    }

    // Old scene-change detection removed — replaced by unified poll loop.
    // See handleRawFrame() and handleCleanFrame().
    private val SCENE_CHANGE_THRESHOLD = 0.40f  // 40% of sampled pixels must change
    private val OVERLAY_CHANGE_THRESHOLD = 0.10f // 10% of overlay pixels (per box) must change
    private val PIXEL_DIFF_THRESHOLD = 30       // per-channel RGB difference for non-overlay pixels
    private val OVERLAY_PIXEL_DIFF_THRESHOLD = 6 // lower threshold for overlay pixels (attenuated by ~90% alpha)

    private data class OverlaySampleData(val x: Int, val y: Int, val textColor: Int, val boxIdx: Int)
    private data class OverlayDiffResult(val maxDiff: Float, val triggeredBoxIndices: Set<Int>)

    // FILL_PADDING defined in companion object below

    /** True if two rects are within proximity. Shared by Check B and performOcrRecheck. */
    private fun areRectsNearby(a: android.graphics.Rect, b: android.graphics.Rect): Boolean {
        val dx = maxOf(0, maxOf(a.left - b.right, b.left - a.right))
        val dy = maxOf(0, maxOf(a.top - b.bottom, b.top - a.bottom))
        val refHeight = maxOf(a.height(), b.height())
        val threshold = maxOf((refHeight * 1.5f).toInt(), FILL_PADDING + 15)
        return dx < threshold && dy < threshold
    }

    /** Find all box indices within proximity of the triggered indices. */
    private fun findNearbyBoxIndices(
        triggeredIndices: Set<Int>,
        fullDisplayBoxes: List<android.graphics.Rect>
    ): Set<Int> {
        val toRemove = triggeredIndices.toMutableSet()
        for (trigIdx in triggeredIndices) {
            if (trigIdx >= fullDisplayBoxes.size) continue
            for (otherIdx in fullDisplayBoxes.indices) {
                if (otherIdx in toRemove) continue
                if (areRectsNearby(fullDisplayBoxes[trigIdx], fullDisplayBoxes[otherIdx])) {
                    toRemove.add(otherIdx)
                }
            }
        }
        return toRemove
    }

    private fun isColorMatch(pixel: Int, color: Int): Boolean {
        val dr = kotlin.math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(color))
        val dg = kotlin.math.abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(color))
        val db = kotlin.math.abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(color))
        return dr <= PIXEL_DIFF_THRESHOLD && dg <= PIXEL_DIFF_THRESHOLD && db <= PIXEL_DIFF_THRESHOLD
    }

    private fun overlayDiffPerBox(
        samples: List<OverlaySampleData>,
        refPixels: IntArray,
        curPixels: IntArray,
        active: BooleanArray
    ): OverlayDiffResult {
        if (refPixels.isEmpty()) return OverlayDiffResult(0f, emptySet())
        val boxChanged = mutableMapOf<Int, Int>()
        val boxCounted = mutableMapOf<Int, Int>()
        for (i in refPixels.indices) {
            if (!active[i]) continue
            val boxIdx = samples[i].boxIdx
            boxCounted[boxIdx] = (boxCounted[boxIdx] ?: 0) + 1
            val dr = kotlin.math.abs(android.graphics.Color.red(refPixels[i]) - android.graphics.Color.red(curPixels[i]))
            val dg = kotlin.math.abs(android.graphics.Color.green(refPixels[i]) - android.graphics.Color.green(curPixels[i]))
            val db = kotlin.math.abs(android.graphics.Color.blue(refPixels[i]) - android.graphics.Color.blue(curPixels[i]))
            if (dr > OVERLAY_PIXEL_DIFF_THRESHOLD || dg > OVERLAY_PIXEL_DIFF_THRESHOLD || db > OVERLAY_PIXEL_DIFF_THRESHOLD) {
                boxChanged[boxIdx] = (boxChanged[boxIdx] ?: 0) + 1
            }
        }
        val triggered = mutableSetOf<Int>()
        var maxDiff = 0f
        for ((idx, count) in boxCounted) {
            if (count > 0) {
                val diff = (boxChanged[idx] ?: 0).toFloat() / count
                if (diff > maxDiff) maxDiff = diff
                if (diff >= OVERLAY_CHANGE_THRESHOLD) triggered.add(idx)
            }
        }
        return OverlayDiffResult(maxDiff, triggered)
    }

    private fun pixelDiffPercent(a: IntArray, b: IntArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var changed = 0
        for (i in a.indices) {
            val dr = kotlin.math.abs(android.graphics.Color.red(a[i]) - android.graphics.Color.red(b[i]))
            val dg = kotlin.math.abs(android.graphics.Color.green(a[i]) - android.graphics.Color.green(b[i]))
            val db = kotlin.math.abs(android.graphics.Color.blue(a[i]) - android.graphics.Color.blue(b[i]))
            if (dr > PIXEL_DIFF_THRESHOLD || dg > PIXEL_DIFF_THRESHOLD || db > PIXEL_DIFF_THRESHOLD) {
                changed++
            }
        }
        return changed.toFloat() / a.size
    }

    /**
     * OCR re-check using a raw bitmap. Fills overlay areas with opaque bgColor,
     * crops, OCRs, and checks for new source-language text.
     * Triggers a clean recapture if new text is near existing overlays,
     * or merges seamlessly if far away.
     * Consumes and recycles [bitmap].
     */
    private suspend fun performOcrRecheck(
        bitmap: Bitmap,
        overlayBoxes: List<android.graphics.Rect>
    ): Boolean {
        val s = session
        val overlays = s.cachedOverlayBoxes
        if (overlays == null) { bitmap.recycle(); return false }
        val cropL = s.cachedOverlayCropLeft
        val cropT = s.cachedOverlayCropTop
        var colorRef: Bitmap? = null

        try {
            val colorScale = 4
            colorRef = Bitmap.createScaledBitmap(bitmap, bitmap.width / colorScale, bitmap.height / colorScale, false)

            val fillPadding = FILL_PADDING
            val fillPaint = Paint()
            for (box in overlays) {
                val l = (box.bounds.left + cropL - fillPadding).coerceAtLeast(0)
                val t = (box.bounds.top + cropT - fillPadding).coerceAtLeast(0)
                val r = (box.bounds.right + cropL + fillPadding).coerceAtMost(bitmap.width)
                val b = (box.bounds.bottom + cropT + fillPadding).coerceAtMost(bitmap.height)
                fillPaint.color = box.bgColor or (0xFF shl 24)
                Canvas(bitmap).drawRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), fillPaint)
            }

            val statusBarHeight = getStatusBarHeightForDisplay(gameDisplayId)
            val top = maxOf((bitmap.height * activeRegion.top).toInt(), statusBarHeight)
            val left = (bitmap.width * activeRegion.left).toInt()
            val bottom = (bitmap.height * activeRegion.bottom).toInt()
            val right = (bitmap.width * activeRegion.right).toInt()
            val needsCrop = top > 0 || left > 0 || bottom < bitmap.height || right < bitmap.width
            val cropped = if (needsCrop)
                Bitmap.createBitmap(bitmap, left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1))
            else bitmap

            val ocrBitmap = blackoutFloatingIcon(cropped, left, top)
            val ocrResult = ocrManager.recognise(ocrBitmap, sourceLang, screenshotWidth = bitmap.width)
            if (ocrBitmap !== bitmap) ocrBitmap.recycle()
            if (ocrResult == null) return false

            val newDedupKey = ocrResult.fullText.filter { c -> OcrManager.isSourceLangChar(c, sourceLang) }
            if (newDedupKey.isEmpty()) return false
            val prevText = s.lastLiveOcrText
            // If prevText is null (e.g., after selective removal), treat all text as new.
            // Otherwise, check for significant additions.
            if (prevText != null && !hasSignificantAdditions(prevText, newDedupKey)) return false

            val anyClose = ocrResult.groupBounds.any { newRect ->
                overlays.any { existing ->
                    areRectsNearby(
                        android.graphics.Rect(existing.bounds.left + cropL, existing.bounds.top + cropT,
                            existing.bounds.right + cropL, existing.bounds.bottom + cropT),
                        android.graphics.Rect(newRect.left + left, newRect.top + top,
                            newRect.right + left, newRect.bottom + top)
                    )
                }
            }

            if (anyClose) {
                s.lastLiveOcrText = null
                s.cachedOverlayBoxes = null
                s.clearCachedState()
                PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
                PlayTranslateAccessibilityService.instance?.screenshotManager?.requestCleanCapture()
                return true
            } else {
                val newGroupTexts = ocrResult.groupTexts.filter { text ->
                    text.any { c -> OcrManager.isSourceLangChar(c, sourceLang) }
                }
                if (newGroupTexts.isEmpty()) return false

                val newGroupBounds = ocrResult.groupBounds.filterIndexed { i, _ ->
                    i < ocrResult.groupTexts.size &&
                        ocrResult.groupTexts[i].any { c -> OcrManager.isSourceLangChar(c, sourceLang) }
                }

                val perGroup = translateGroupsSeparately(newGroupTexts)
                val buffer = 10 / colorScale
                val cRef = colorRef ?: return false

                val newOverlayBoxes = if (newGroupBounds.size == perGroup.size) {
                    perGroup.zip(newGroupBounds).map { (tr, bounds) ->
                        val sl = (bounds.left + left) / colorScale
                        val st = (bounds.top + top) / colorScale
                        val sr = (bounds.right + left) / colorScale
                        val sb = (bounds.bottom + top) / colorScale
                        val bg = averageColor(cRef,
                            sl - buffer, st - buffer, sr + buffer, sb + buffer,
                            excludeInner = android.graphics.Rect(sl, st, sr, sb))
                        val tc = if (colorLuminance(bg) > 128)
                            android.graphics.Color.BLACK else android.graphics.Color.WHITE
                        TranslationOverlayView.TextBox(tr.first, bounds, bg, tc)
                    }
                } else emptyList()

                if (newOverlayBoxes.isNotEmpty()) {
                    val merged = overlays + newOverlayBoxes
                    s.cachedOverlayBoxes = merged
                    s.lastLiveOcrText = prevText + newDedupKey
                    showLiveOverlay(merged, cropL, cropT, s.cachedOverlayScreenW, s.cachedOverlayScreenH)
                    // Set up detection for the merged overlays
                    // (overlay ref will be set from next raw frame)
                    return true
                }
            }
            return false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "OCR re-check failed: ${e.message}")
            return false
        } finally {
            colorRef?.recycle()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * Called (on the main thread) every time user input is detected
     * (button press/release, touch) while live mode is active.
     */
    private fun onUserInteraction() {
        if (!liveActive) return

        Log.d(TAG, "REFRESH: onUserInteraction")
        session.cleanProcessingJob?.cancel()
        session.clearCachedState()
        PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()

        // Debounce until input stops, then request a clean capture
        session.interactionDebounceJob?.cancel()
        session.interactionDebounceJob = session.scope.launch {
            val settleMs = Prefs(this@CaptureService).captureIntervalMs
            delay(settleMs)
            while (PlayTranslateAccessibilityService.instance?.isInputActive == true) {
                delay(settleMs)
            }
            PlayTranslateAccessibilityService.instance?.screenshotManager?.requestCleanCapture()
        }
    }

    private fun showLiveOverlay(
        boxes: List<TranslationOverlayView.TextBox>,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int
    ) {
        if (holdActive) return
        val a11y = PlayTranslateAccessibilityService.instance ?: return
        val dm = getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(gameDisplayId) ?: return
        a11y.showTranslationOverlay(display, boxes, cropLeft, cropTop, screenshotW, screenshotH)
    }

    /**
     * Captures a clean screenshot via [ScreenshotManager].
     */
    private suspend fun captureScreen(displayId: Int): Bitmap? {
        val mgr = PlayTranslateAccessibilityService.instance?.screenshotManager
        return mgr?.requestClean(displayId)
    }

    /**
     * @param preCaptured If non-null, use this bitmap instead of taking a new
     *   screenshot. Used by scene detection which already has a clean frame.
     */
    private suspend fun runLiveCaptureCycle(preCaptured: Bitmap? = null) {
        DetectionLog.log("Capture cycle starting${if (preCaptured != null) " (pre-captured)" else ""}")
        if (!isConfigured) { preCaptured?.recycle(); return }
        val mgr = PlayTranslateAccessibilityService.instance?.screenshotManager

        val raw: Bitmap = preCaptured ?: captureScreen(gameDisplayId) ?: run {
            // Manager already retried once — persistent failure.
            // Stop live mode so it doesn't become a zombie.
            if (liveActive) {
                stopLive()
                onError?.invoke("Screenshot failed — live mode stopped. Check accessibility settings and try again.")
            }
            return
        }
        var colorRef: Bitmap? = null

        try {
            val statusBarHeight = getStatusBarHeightForDisplay(gameDisplayId)
            val top    = maxOf((raw.height * activeRegion.top).toInt(), statusBarHeight)
            val left   = (raw.width  * activeRegion.left).toInt()
            val bottom = (raw.height * activeRegion.bottom).toInt()
            val right  = (raw.width  * activeRegion.right).toInt()
            val needsCrop = top > 0 || left > 0 || bottom < raw.height || right < raw.width
            val bitmap = if (needsCrop)
                Bitmap.createBitmap(raw, left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1))
            else raw

            // Create color reference for adaptive overlay colors
            val colorScale = 4
            colorRef = Bitmap.createScaledBitmap(raw, raw.width / colorScale, raw.height / colorScale, false)

            val s = session
            // Flash region indicator AFTER screenshot is captured
            if (s.liveShowRegionFlash) {
                s.liveShowRegionFlash = false
                flashRegionIndicator()
            }

            val ocrBitmap = blackoutFloatingIcon(bitmap, left, top)
            val ocrResult = ocrManager.recognise(ocrBitmap, sourceLang, screenshotWidth = raw.width)
            if (ocrBitmap !== raw) ocrBitmap.recycle()

            if (ocrResult == null) {
                s.lastLiveOcrText = null
                s.cachedOverlayBoxes = null
                PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
                onLiveNoText?.invoke()
                // Start scene detection so we recapture when the game changes
                // (e.g. dialogue box opens). No overlay boxes to exclude.
                // Detection handled by the unified poll loop
                return
            }

            val newText = ocrResult.fullText
            val dedupKey = newText.filter { c -> OcrManager.isSourceLangChar(c, sourceLang) }

            if (dedupKey.isEmpty()) {
                s.lastLiveOcrText = null
                s.cachedOverlayBoxes = null
                PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
                onLiveNoText?.invoke()
                // Detection handled by the unified poll loop
                return
            }

            // Dedup: if text hasn't changed significantly, re-show cached overlay.
            // Skip dedup entirely if we have no previous text (first cycle after start/clear).
            if (s.lastLiveOcrText != null && !isSignificantChange(s.lastLiveOcrText!!, dedupKey)) {
                val boxes = s.cachedOverlayBoxes
                if (boxes != null) {
                    showLiveOverlay(boxes, s.cachedOverlayCropLeft, s.cachedOverlayCropTop,
                        s.cachedOverlayScreenW, s.cachedOverlayScreenH)
                }
                return
            }
            s.lastLiveOcrText = dedupKey

            // New text — save screenshot NOW (only on actual text changes, not dedup hits)
            val screenshotPath = mgr?.saveToCache(raw)
            val screenshotW = raw.width
            val screenshotH = raw.height

            val liveGroupTexts = ocrResult.groupTexts
            val liveGroupBounds = ocrResult.groupBounds
            val liveGroupLineCounts = ocrResult.groupLineCounts

            // Sample colors and show skeleton placeholders immediately
            // so the user sees overlay positions while translations load.
            val buffer = 10 / colorScale
            val cRef = colorRef!!
            val placeholderBoxes = liveGroupBounds.mapIndexed { idx, bounds ->
                val sl = (bounds.left + left) / colorScale
                val st = (bounds.top + top) / colorScale
                val sr = (bounds.right + left) / colorScale
                val sb = (bounds.bottom + top) / colorScale

                val bgColor = averageColor(cRef,
                    sl - buffer, st - buffer, sr + buffer, sb + buffer,
                    excludeInner = android.graphics.Rect(sl, st, sr, sb))

                val textColor = if (colorLuminance(bgColor) > 128)
                    android.graphics.Color.BLACK else android.graphics.Color.WHITE

                val lineCount = liveGroupLineCounts.getOrElse(idx) { 1 }
                TranslationOverlayView.TextBox("", bounds, bgColor, textColor, lineCount)
            }
            showLiveOverlay(placeholderBoxes, left, top, screenshotW, screenshotH,
                )

            // Translate (cancellation-safe — if liveCaptureJob is cancelled,
            // this coroutine is cancelled too)
            onTranslationStarted?.invoke()
            val perGroup = translateGroupsSeparately(liveGroupTexts)
            val translated = perGroup.joinToString("\n\n") { it.first }
            val note = perGroup.mapNotNull { it.second }.firstOrNull()
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            onResult?.invoke(
                TranslationResult(
                    originalText   = newText,
                    segments       = ocrResult.segments,
                    translatedText = translated,
                    timestamp      = timestamp,
                    screenshotPath = screenshotPath,
                    note           = note
                )
            )

            // Update overlay with translated text
            if (liveGroupBounds.size == perGroup.size) {
                val overlayBoxes = perGroup.zip(placeholderBoxes).map { (tr, placeholder) ->
                    placeholder.copy(translatedText = tr.first)
                }
                s.cachedOverlayBoxes = overlayBoxes
                s.cachedOverlayCropLeft = left
                s.cachedOverlayCropTop = top
                s.cachedOverlayScreenW = screenshotW
                s.cachedOverlayScreenH = screenshotH
                showLiveOverlay(overlayBoxes, left, top, screenshotW, screenshotH)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Live capture cycle failed: ${e.javaClass.simpleName}: ${e.message}", e)
        } finally {
            // Guarantee cleanup on all paths (normal, cancellation, exception)
            colorRef?.recycle()
            if (!raw.isRecycled) raw.recycle()
        }
    }

    private fun colorLuminance(color: Int): Double {
        return 0.299 * android.graphics.Color.red(color) +
            0.587 * android.graphics.Color.green(color) +
            0.114 * android.graphics.Color.blue(color)
    }

    private fun averageColor(
        bitmap: Bitmap, l: Int, t: Int, r: Int, b: Int,
        excludeInner: android.graphics.Rect? = null
    ): Int {
        val left = l.coerceIn(0, bitmap.width - 1)
        val top = t.coerceIn(0, bitmap.height - 1)
        val right = r.coerceIn(left + 1, bitmap.width)
        val bottom = b.coerceIn(top + 1, bitmap.height)
        var rSum = 0L; var gSum = 0L; var bSum = 0L; var count = 0
        for (y in top until bottom step 4) {
            for (x in left until right step 4) {
                // Skip pixels inside the exclusion rect (used for outer-ring sampling)
                if (excludeInner != null && excludeInner.contains(x, y)) continue
                val pixel = bitmap.getPixel(x, y)
                rSum += android.graphics.Color.red(pixel)
                gSum += android.graphics.Color.green(pixel)
                bSum += android.graphics.Color.blue(pixel)
                count++
            }
        }
        if (count == 0) return android.graphics.Color.argb(230, 0, 0, 0)
        return android.graphics.Color.argb(230,
            (rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt())
    }

    private fun noTextMessage(): String {
        val langName = java.util.Locale(sourceLang).getDisplayLanguage(java.util.Locale.ENGLISH)
            .replaceFirstChar { it.uppercase() }
        return getString(R.string.status_no_text, langName, activeRegion.label)
    }

    /**
     * Returns true if [a] and [b] differ by more than [LIVE_DEDUP_TOLERANCE] characters
     * when compared as bags (multisets). Tolerating small differences prevents oscillation
     * where OCR alternates between slightly different outputs for the same static screen.
     */
    private fun isSignificantChange(a: String, b: String): Boolean {
        if (a == b) return false
        val freqA = a.groupingBy { it }.eachCount()
        val freqB = b.groupingBy { it }.eachCount()
        var diff = 0
        for (c in (freqA.keys + freqB.keys).toSet()) {
            diff += kotlin.math.abs((freqA[c] ?: 0) - (freqB[c] ?: 0))
            if (diff > LIVE_DEDUP_TOLERANCE) return true
        }
        // For short text, use percentage: "屋上" → "屋下" is 50% different
        // even though absolute diff (2) is within tolerance
        val maxLen = maxOf(a.length, b.length)
        if (maxLen > 0 && diff.toFloat() / maxLen > LIVE_DEDUP_PCT_THRESHOLD) return true
        return false
    }

    /**
     * Returns true if [detected] contains more than 1 character not present
     * in [existing]. Missing characters are ignored — they're expected when
     * overlay fill hides previously visible text.
     */
    private fun hasSignificantAdditions(existing: String, detected: String): Boolean {
        val bag = existing.groupingBy { it }.eachCount().toMutableMap()
        var added = 0
        for (c in detected) {
            val count = bag[c] ?: 0
            if (count > 0) {
                bag[c] = count - 1
            } else {
                added++
                if (added > 1) return true
            }
        }
        return false
    }

    companion object {
        private const val LIVE_DEDUP_TOLERANCE = 3    // max character-count drift treated as noise
        private const val LIVE_DEDUP_PCT_THRESHOLD = 0.3f  // 30% change = significant for short text
        const val FILL_PADDING = 30  // pixels padded around overlay bounds when filling

        /** Process-scoped reference for in-process callers (e.g. DragLookupController). */
        @Volatile
        var instance: CaptureService? = null
            private set
    }

    /**
     * Blacks out the floating icon area so OCR doesn't read the icon's text.
     * Returns a mutable bitmap with the icon area painted black. If the icon
     * is not in the crop area, returns the original bitmap unchanged.
     *
     * @param bitmap   The (possibly immutable) cropped bitmap.
     * @param cropLeft Left offset of the crop in full-screen coordinates.
     * @param cropTop  Top offset of the crop in full-screen coordinates.
     */
    private fun blackoutFloatingIcon(bitmap: Bitmap, cropLeft: Int = 0, cropTop: Int = 0): Bitmap {
        // In compact mode the icon is a tiny sliver at the edge — skip blackout
        if (Prefs(this).compactOverlayIcon) return bitmap
        val iconRect = PlayTranslateAccessibilityService.instance?.getFloatingIconRect()
            ?: return bitmap
        // Shift icon rect into the cropped bitmap's coordinate space
        val left = (iconRect.left - cropLeft).coerceAtLeast(0)
        val top = (iconRect.top - cropTop).coerceAtLeast(0)
        val right = (iconRect.right - cropLeft).coerceAtMost(bitmap.width)
        val bottom = (iconRect.bottom - cropTop).coerceAtMost(bitmap.height)
        if (left >= right || top >= bottom) return bitmap  // icon not in this crop
        val mutable = if (bitmap.isMutable) bitmap
            else bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true).also { bitmap.recycle() }
        Canvas(mutable).drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), blackoutPaint)
        return mutable
    }

    private val blackoutPaint = Paint().apply { color = Color.BLACK }

    fun resetConfiguration() {
        translationManager?.close()
        translationManager = null
        deeplTranslator  = null
        lingvaTranslator = null
        gameDisplayId = 0
        onStatusUpdate?.invoke(getString(R.string.status_idle))
    }

    val isConfigured: Boolean get() = lingvaTranslator != null || translationManager != null

    // ── Capture cycle ─────────────────────────────────────────────────────

    private suspend fun runCaptureCycle() {
        if (!isConfigured) {
            onError?.invoke("Not configured — tap Translate to set up")
            return
        }

        var bitmap: Bitmap? = null
        try {
            onStatusUpdate?.invoke(getString(R.string.status_capturing))

            val raw: Bitmap = captureScreen(gameDisplayId) ?: run {
                onError?.invoke("Screenshot failed for display $gameDisplayId. Try a different display in Settings.")
                return
            }
            bitmap = raw

            val mgr = PlayTranslateAccessibilityService.instance?.screenshotManager
            val screenshotPath = mgr?.saveToCache(raw)

            // Flash region indicator AFTER screenshot is saved — safe from contamination
            flashRegionIndicator()

            val statusBarHeight = getStatusBarHeightForDisplay(gameDisplayId)
            val top    = maxOf((raw.height * activeRegion.top).toInt(), statusBarHeight)
            val left   = (raw.width  * activeRegion.left).toInt()
            val bottom = (raw.height * activeRegion.bottom).toInt()
            val right  = (raw.width  * activeRegion.right).toInt()
            bitmap = cropBitmap(raw, top, bottom, left, right)

            val ocrBitmap = blackoutFloatingIcon(bitmap, left, top)
            bitmap = ocrBitmap // track current bitmap for finally
            onStatusUpdate?.invoke(getString(R.string.status_ocr))
            val ocrResult = ocrManager.recognise(ocrBitmap, sourceLang, screenshotWidth = raw.width)
            ocrBitmap.recycle()
            bitmap = null // successfully recycled

            if (ocrResult == null) {
                onStatusUpdate?.invoke(noTextMessage())
                return
            }

            onStatusUpdate?.invoke(getString(R.string.status_translating))
            val (translated, note) = translateGroups(ocrResult.groupTexts)

            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            onResult?.invoke(
                TranslationResult(
                    originalText   = ocrResult.fullText,
                    segments       = ocrResult.segments,
                    translatedText = translated,
                    timestamp      = timestamp,
                    screenshotPath = screenshotPath,
                    note           = note
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Capture cycle failed: ${e.message}", e)
            onError?.invoke(e.message ?: "Unknown error")
        } finally {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    /** Cache of original text → (translated text, note). Avoids retranslating
     *  groups that haven't changed between live mode cycles (e.g. persistent
     *  UI labels while only the dialogue updates). Cleared on language change. */
    private val translationCache = object : LinkedHashMap<String, Pair<String, String?>>(50, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String?>>?) =
            size > 50
    }
    /** Set by [translate] when ML Kit fallback is used, so [translateGroupsSeparately]
     *  knows not to cache the lower-quality result. */
    private var mlKitFallbackUsed = false

    /**
     * Translates each group in parallel, using cached results for groups
     * whose original text hasn't changed. Only cache misses hit the network.
     */
    private suspend fun translateGroupsSeparately(groupTexts: List<String>): List<Pair<String, String?>> {
        val uncached = groupTexts.withIndex()
            .filter { (_, text) -> text !in translationCache }

        // Translate cache misses in parallel
        val freshTranslations = if (uncached.isNotEmpty()) {
            mlKitFallbackUsed = false
            val translations = uncached.map { (_, text) ->
                serviceScope.async { translate(text) }
            }.awaitAll()

            // Only cache results from online services (DeepL/Lingva).
            // ML Kit fallback results are lower quality and should be
            // retried when the online service recovers.
            if (!mlKitFallbackUsed) {
                uncached.zip(translations).forEach { (indexedText, result) ->
                    translationCache[indexedText.value] = result
                }
            }

            // Build a lookup for this batch
            uncached.map { it.value }.zip(translations).toMap()
        } else emptyMap()

        return groupTexts.map { translationCache[it] ?: freshTranslations[it]!! }
    }

    /**
     * Translates each group in parallel and joins results with double-newline.
     * Returns the combined translated text and an optional note (from ML Kit fallback).
     */
    private suspend fun translateGroups(groupTexts: List<String>): Pair<String, String?> {
        val results = translateGroupsSeparately(groupTexts)
        val translated = results.joinToString("\n\n") { it.first }
        val note = results.mapNotNull { it.second }.firstOrNull()
        return Pair(translated, note)
    }

    /** On-demand translation for a single text string (used by edit overlay, drag-sentence, etc.). */
    suspend fun translateOnce(text: String): Pair<String, String?> = translate(text)

    /**
     * Translation waterfall: DeepL → Lingva → ML Kit.
     * Returns the translated text and an optional inline note.
     * A note is only shown when ML Kit is used (lower quality).
     */
    private suspend fun translate(text: String): Pair<String, String?> {
        // 1. DeepL (if key is set)
        val deepl = deeplTranslator
        if (deepl != null) {
            try {
                return Pair(deepl.translate(text), null).also { setDegraded(false) }
            } catch (e: DeepLQuotaExceededException) {
                Log.w(TAG, "DeepL quota exceeded, trying Lingva")
            } catch (e: DeepLAuthException) {
                Log.w(TAG, "DeepL auth failed, trying Lingva")
            } catch (e: Exception) {
                Log.w(TAG, "DeepL failed (${e.message}), trying Lingva")
            }
        }

        // 2. Lingva (always attempted)
        val lingva = lingvaTranslator
        if (lingva != null) {
            try {
                return Pair(lingva.translate(text), null).also { setDegraded(false) }
            } catch (e: Exception) {
                Log.w(TAG, "Lingva failed (${e.message}), falling back to ML Kit")
            }
        }

        // 3. ML Kit offline fallback — don't cache these results since
        //    the online services may recover on the next attempt.
        val note = if (isNetworkAvailable())
            getString(R.string.note_mlkit_service_unavailable)
        else
            getString(R.string.note_mlkit_no_internet)
        return Pair(mlKitTranslate(text), note).also {
            mlKitFallbackUsed = true
            setDegraded(true)
        }
    }

    private suspend fun mlKitTranslate(text: String): String {
        val tm = translationManager ?: throw IllegalStateException("No offline translation model available")
        tm.ensureModelReady()
        return tm.translate(text)
    }

    /**
     * Returns the status bar height in pixels for [displayId], or 0 if there is no
     * status bar or it cannot be determined. Uses window insets on API 30+ and falls
     * back to comparing real vs. usable display metrics on older versions.
     */
    private fun getStatusBarHeightForDisplay(displayId: Int): Int {
        val dm = getSystemService(android.hardware.display.DisplayManager::class.java) ?: return 0
        val display = dm.getDisplay(displayId) ?: return 0
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val displayContext = createDisplayContext(display)
                val wm = displayContext.getSystemService(android.view.WindowManager::class.java) ?: return 0
                wm.currentWindowMetrics.windowInsets
                    .getInsets(android.view.WindowInsets.Type.statusBars()).top
            } catch (_: Exception) { 0 }
        } else {
            val real   = android.util.DisplayMetrics()
            val usable = android.util.DisplayMetrics()
            display.getRealMetrics(real)
            @Suppress("DEPRECATION") display.getMetrics(usable)
            (real.heightPixels - usable.heightPixels).coerceAtLeast(0)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        return cm?.activeNetwork != null
    }

    /**
     * Returns [raw] unchanged if it already matches the crop bounds, otherwise
     * creates a cropped copy and recycles [raw]. This avoids duplicating the
     * same conditional-crop block in both the one-shot and live capture paths.
     */
    private fun cropBitmap(raw: Bitmap, top: Int, bottom: Int, left: Int, right: Int): Bitmap {
        val needsCrop = top > 0 || left > 0 || bottom < raw.height || right < raw.width
        if (!needsCrop) return raw
        val cropped = Bitmap.createBitmap(
            raw, left, top,
            (right - left).coerceAtLeast(1),
            (bottom - top).coerceAtLeast(1)
        )
        raw.recycle()
        return cropped
    }


    // ── Notification ──────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
