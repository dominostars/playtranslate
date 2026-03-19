package com.playtranslate

import android.app.Notification
import android.app.NotificationChannel
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.app.NotificationManager
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

    // ── Pipeline ──────────────────────────────────────────────────────────

    private val ocrManager get() = OcrManager.instance
    private var translationManager: TranslationManager? = null  // ML Kit offline fallback
    private var deeplTranslator: DeepLTranslator?  = null       // optional, key required
    private var lingvaTranslator: LingvaTranslator? = null      // always present after configure()

    private var gameDisplayId: Int = 0
    private var sourceLang: String = TranslateLanguage.JAPANESE
    private var captureTopFraction: Float    = 0f
    private var captureBottomFraction: Float = 1f
    private var captureLeftFraction: Float   = 0f
    private var captureRightFraction: Float  = 1f

    /** Active capture region as [top, bottom, left, right] fractions. */
    val activeRegion: FloatArray
        get() = floatArrayOf(captureTopFraction, captureBottomFraction, captureLeftFraction, captureRightFraction)
    var captureRegionLabel: String = ""
        private set

    // ── Callbacks to Activity ─────────────────────────────────────────────

    var onResult: ((TranslationResult) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStatusUpdate: ((String) -> Unit)? = null
    var onTranslationStarted: (() -> Unit)? = null
    /** Fired during live mode when an OCR cycle finds no source-language text. */
    var onLiveNoText: (() -> Unit)? = null
    var onLiveStopped: (() -> Unit)? = null
    var onDegradedStateChanged: ((Boolean) -> Unit)? = null

    /** True when translations are using ML Kit fallback (lower quality). */
    var translationDegraded = false
        private set

    private fun setDegraded(degraded: Boolean) {
        if (translationDegraded == degraded) return
        translationDegraded = degraded
        onDegradedStateChanged?.invoke(degraded)
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
        onLiveStopped = null
        onDegradedStateChanged = null
        super.onDestroy()
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun configure(
        displayId: Int,
        sourceLang: String = TranslateLanguage.JAPANESE,
        targetLang: String = TranslateLanguage.ENGLISH,
        captureTopFraction: Float = 0f,
        captureBottomFraction: Float = 1f,
        captureLeftFraction: Float = 0f,
        captureRightFraction: Float = 1f,
        regionLabel: String = ""
    ) {
        gameDisplayId    = displayId
        this.sourceLang  = sourceLang
        this.captureTopFraction    = captureTopFraction
        this.captureBottomFraction = captureBottomFraction
        this.captureLeftFraction   = captureLeftFraction
        this.captureRightFraction  = captureRightFraction
        this.captureRegionLabel    = regionLabel

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

    private var captureJob: Job? = null

    fun captureOnce() {
        captureJob?.cancel()
        captureJob = serviceScope.launch { runCaptureCycle() }
    }

    /**
     * Processes a pre-captured screenshot bitmap instead of taking a new one.
     * Used when the screenshot must be taken before an activity appears on screen
     * (e.g. single-screen region capture from the floating menu).
     */
    fun processScreenshot(raw: Bitmap) {
        captureJob?.cancel()
        captureJob = serviceScope.launch { runProcessCycle(raw) }
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
            val top    = maxOf((raw.height * captureTopFraction).toInt(), statusBarHeight)
            val left   = (raw.width  * captureLeftFraction).toInt()
            val bottom = (raw.height * captureBottomFraction).toInt()
            val right  = (raw.width  * captureRightFraction).toInt()
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

    private var liveActive = false
    private var interactionDebounceJob: Job? = null
    /** Separate from [interactionDebounceJob] so cancelling the debounce
     *  doesn't kill an in-flight capture. */
    private var liveCaptureJob: Job? = null
    private var lastLiveOcrText: String? = null
    /** Cached overlay data so dedup-unchanged frames can re-show instantly. */
    private var cachedOverlayBoxes: List<TranslationOverlayView.TextBox>? = null
    private var cachedOverlayCropLeft = 0
    private var cachedOverlayCropTop = 0
    private var cachedOverlayScreenW = 0
    private var cachedOverlayScreenH = 0
    /** True until the first live capture shows the region indicator. */
    private var liveShowRegionFlash = false
    /** Set after showing overlays — scene detection runs one OCR re-check on its next poll. */
    private var recheckNeeded = false

    val isLive: Boolean get() = liveActive

    fun startLive() {
        liveActive = true
        liveShowRegionFlash = true
        PlayTranslateAccessibilityService.instance?.floatingIcon?.liveMode = true
        lastLiveOcrText = null
        cachedOverlayBoxes = null
        captureJob?.cancel()
        interactionDebounceJob?.cancel()
        liveCaptureJob?.cancel()
        recheckNeeded = false

        // Buttons and touch are detected via onKeyEvent and touch sentinel.
        PlayTranslateAccessibilityService.instance
            ?.startInputMonitoring(gameDisplayId) { onUserInteraction() }

        // Interaction-driven capture. Scene-change detection via pixel-diff
        // handles joystick/d-pad movement.
        liveCaptureJob = serviceScope.launch { runLiveCaptureCycle() }
    }

    fun stopLive() {
        liveActive = false
        MainActivity.isLiveModeActive = false
        interactionDebounceJob?.cancel()
        interactionDebounceJob = null
        liveCaptureJob?.cancel()
        liveCaptureJob = null
        recheckNeeded = false
        stopSceneChangeDetection()
        lastLiveOcrText = null
        cachedOverlayBoxes = null
        PlayTranslateAccessibilityService.instance?.stopInputMonitoring()
        PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
        PlayTranslateAccessibilityService.instance?.floatingIcon?.liveMode = false
        setDegraded(false)
        onLiveStopped?.invoke()
    }

    /** Trigger a fresh capture cycle in live mode (e.g. after hold-release). */
    fun refreshLiveOverlay() {
        if (!liveActive) return
        Log.d(TAG, "REFRESH: refreshLiveOverlay called")
        // Clear all previous live mode work so the fresh cycle runs
        // without interference from stale scene detection or translation.
        lastLiveOcrText = null
        cachedOverlayBoxes = null
        stopSceneChangeDetection()
        recheckNeeded = false
        interactionDebounceJob?.cancel()
        liveCaptureJob?.cancel()
        liveCaptureJob = serviceScope.launch { runLiveCaptureCycle() }
    }

    /** One-shot: capture, OCR, translate, show overlay (not live mode). */
    fun showOneShotOverlay() {
        liveCaptureJob?.cancel()
        liveCaptureJob = serviceScope.launch { runLiveCaptureCycle() }
    }

    /** True while the user is holding the floating icon — suppresses overlay display. */
    var holdActive = false

    /** Path to the last clean screenshot. Delegates to [ScreenshotManager]. */
    val lastCleanScreenshotPath: String?
        get() = PlayTranslateAccessibilityService.instance?.screenshotManager?.lastCleanPath

    /** Cancel any in-flight one-shot and invalidate its results. */
    fun cancelOneShot() {
        liveCaptureJob?.cancel()
        stopSceneChangeDetection()
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
        a11y.showRegionIndicator(
            display,
            captureTopFraction, captureBottomFraction,
            captureLeftFraction, captureRightFraction,
            captureRegionLabel
        )
    }

    // ── Scene-change detection (API < 34) ────────────────────────────────
    //
    // On API < 34, we can't use MediaProjection to capture without overlays,
    // so we detect major scene changes (character movement / camera pan) by
    // comparing screenshots. Takes a screenshot WITH overlays visible, then
    // compares non-overlay pixels against a reference frame. If >80% of
    // sampled pixels changed, the game is moving and overlays are stale.

    private var sceneCheckJob: Job? = null
    private val SCENE_CHANGE_THRESHOLD = 0.30f  // 30% of sampled pixels must change
    private val PIXEL_DIFF_THRESHOLD = 30       // per-channel RGB difference

    /**
     * Start scene-change detection after overlays are shown.
     * [overlayBoxes] are the overlay bounding rects in full-display coordinates.
     * Everything runs inside a single coroutine on the main thread — no
     * async callbacks, no shared mutable state, no race conditions.
     */
    private fun startSceneChangeDetection(overlayBoxes: List<android.graphics.Rect>) {
        stopSceneChangeDetection()
        val mgr = PlayTranslateAccessibilityService.instance?.screenshotManager ?: return

        sceneCheckJob = serviceScope.launch {
            // Brief delay to let the overlay render before taking reference
            delay(300L)

            // Take reference frame — keep trying until it succeeds or live
            // mode stops. requestRaw handles rate limiting internally.
            var refBitmap: Bitmap? = null
            while (liveActive && refBitmap == null) {
                refBitmap = mgr.requestRaw(gameDisplayId)
            }
            if (refBitmap == null) return@launch

            // Build sample positions: only inside the capture region, skipping overlay areas.
            // This prevents our own overlays outside the region (region indicator, etc.)
            // from triggering false scene changes, and makes detection more relevant
            // by only watching the area we actually OCR.
            val regionTop = (refBitmap.height * captureTopFraction).toInt()
            val regionBottom = (refBitmap.height * captureBottomFraction).toInt()
            val regionLeft = (refBitmap.width * captureLeftFraction).toInt()
            val regionRight = (refBitmap.width * captureRightFraction).toInt()
            val positions = mutableListOf<Pair<Int, Int>>()
            for (y in regionTop until regionBottom step 10) {
                for (x in regionLeft until regionRight step 10) {
                    if (overlayBoxes.none { it.contains(x, y) }) {
                        positions.add(x to y)
                    }
                }
            }
            if (positions.isEmpty()) { refBitmap.recycle(); return@launch }

            val refPixels = IntArray(positions.size) { i ->
                refBitmap.getPixel(positions[i].first, positions[i].second)
            }
            refBitmap.recycle()

            // Poll loop — reuse pixel arrays to avoid GC pressure
            var sceneMoving = false
            var hasPrev = false
            val pixelsA = IntArray(positions.size)
            val pixelsB = IntArray(positions.size)
            var current = pixelsA
            var prev = pixelsB

            while (liveActive) {

                val bitmap = mgr.requestRaw(gameDisplayId)
                if (bitmap == null) continue

                for (i in positions.indices) {
                    val (x, y) = positions[i]
                    current[i] = if (x < bitmap.width && y < bitmap.height) bitmap.getPixel(x, y) else 0
                }

                if (!sceneMoving) {
                    // Phase 1: compare against reference to detect movement start
                    val pct = pixelDiffPercent(refPixels, current)
                    if (pct >= SCENE_CHANGE_THRESHOLD) {
                        Log.d(TAG, "REFRESH: Scene Phase1 triggered (diff=${(pct*100).toInt()}%)")
                        bitmap.recycle()
                        sceneMoving = true
                        liveCaptureJob?.cancel()
                        recheckNeeded = false
                        lastLiveOcrText = null
                        cachedOverlayBoxes = null
                        PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
                    } else if (recheckNeeded) {
                        // One-shot OCR re-check: detect new text that appeared
                        // after the initial capture (e.g. typewriter animation).
                        recheckNeeded = false
                        val triggered = performOcrRecheck(bitmap, overlayBoxes)
                        // bitmap ownership transferred to performOcrRecheck.
                        // If it triggered a recapture or merge, exit this loop —
                        // showLiveOverlay will start a new scene detection.
                        if (triggered) return@launch
                    } else {
                        bitmap.recycle()
                    }
                } else {
                    // Phase 2: compare consecutive frames to detect stabilization
                    if (hasPrev) {
                        val pct = pixelDiffPercent(prev, current)
                        if (pct < 0.05f) {
                            Log.d(TAG, "REFRESH: Scene Phase2 stabilized (diff=${(pct*100).toInt()}%)")
                            // Scene stabilized — reuse this screenshot directly
                            // for OCR instead of taking another (saves ~1s rate limit).
                            // The bitmap is already clean: Phase 1 hid the overlay,
                            // and FLAG_SECURE excludes the floating icon.
                            liveCaptureJob?.cancel()
                            liveCaptureJob = serviceScope.launch {
                                runLiveCaptureCycle(preCaptured = bitmap)
                            }
                            return@launch
                        }
                    }
                    bitmap.recycle()
                }
                // Swap arrays so current becomes prev for next iteration
                hasPrev = true
                val tmp = prev; prev = current; current = tmp
            }
        }
    }

    /**
     * OCR re-check using scene detection's raw bitmap. Fills overlay areas
     * with surrounding background color, crops, OCRs, and checks for new
     * source-language text. Triggers a clean recapture if new text is near
     * existing overlays, or merges seamlessly if far away.
     * Consumes and recycles [bitmap].
     */
    /**
     * Returns true if it triggered a recapture or merge (caller should exit
     * the scene detection loop since a new one will be started).
     */
    private suspend fun performOcrRecheck(
        bitmap: Bitmap,
        overlayBoxes: List<android.graphics.Rect>
    ): Boolean {
        val overlays = cachedOverlayBoxes
        if (overlays == null) { bitmap.recycle(); return false }
        val cropL = cachedOverlayCropLeft
        val cropT = cachedOverlayCropTop
        var colorRef: Bitmap? = null

        try {
            // Color reference BEFORE filling overlay areas
            val colorScale = 4
            colorRef = Bitmap.createScaledBitmap(bitmap, bitmap.width / colorScale, bitmap.height / colorScale, false)

            // Fill overlay areas with the overlay's own background color (fully opaque)
            val fillPadding = 30
            val fillPaint = Paint()
            for (box in overlays) {
                val l = (box.bounds.left + cropL - fillPadding).coerceAtLeast(0)
                val t = (box.bounds.top + cropT - fillPadding).coerceAtLeast(0)
                val r = (box.bounds.right + cropL + fillPadding).coerceAtMost(bitmap.width)
                val b = (box.bounds.bottom + cropT + fillPadding).coerceAtMost(bitmap.height)
                fillPaint.color = box.bgColor or (0xFF shl 24)
                Canvas(bitmap).drawRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), fillPaint)
            }

            // Crop to capture region
            val statusBarHeight = getStatusBarHeightForDisplay(gameDisplayId)
            val top = maxOf((bitmap.height * captureTopFraction).toInt(), statusBarHeight)
            val left = (bitmap.width * captureLeftFraction).toInt()
            val bottom = (bitmap.height * captureBottomFraction).toInt()
            val right = (bitmap.width * captureRightFraction).toInt()
            val needsCrop = top > 0 || left > 0 || bottom < bitmap.height || right < bitmap.width
            val cropped = if (needsCrop)
                Bitmap.createBitmap(bitmap, left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1))
            else bitmap

            // OCR
            val ocrResult = ocrManager.recognise(cropped, sourceLang, collectDebugBoxes = true, screenshotWidth = bitmap.width)
            if (cropped !== bitmap) cropped.recycle()
            if (ocrResult == null) return false

            // Log all detected text for diagnosing garbled OCR from filled areas
            ocrResult.debugBoxes?.let { debug ->
                debug.blockBoxes.forEachIndexed { i, box ->
                    Log.d(TAG, "RECHECK-OCR: block[$i] text='${box.text.take(50)}' lang='${box.lang}' bounds=${box.bounds}")
                }
                debug.lineBoxes.forEachIndexed { i, box ->
                    Log.d(TAG, "RECHECK-OCR: line[$i] text='${box.text.take(50)}' lang='${box.lang}' conf=${box.confidence} bounds=${box.bounds}")
                }
                debug.elementBoxes.forEachIndexed { i, box ->
                    Log.d(TAG, "RECHECK-OCR: elem[$i] text='${box.text}' lang='${box.lang}' conf=${box.confidence} bounds=${box.bounds}")
                }
            }

            // Filter and compare against previous clean OCR text.
            // Only count characters that are NEW (not in the existing text) —
            // missing characters are expected because the fill hides them.
            val newDedupKey = ocrResult.fullText.filter { c -> OcrManager.isSourceLangChar(c, sourceLang) }
            if (newDedupKey.isEmpty()) return false
            val prevText = lastLiveOcrText ?: return false
            if (!hasSignificantAdditions(prevText, newDedupKey)) return false
            Log.d(TAG, "RECHECK: prev='${prevText.take(30)}' new='${newDedupKey.take(30)}' groups=${ocrResult.groupTexts.size}")

            // New text found — check proximity to existing overlays
            val anyClose = ocrResult.groupBounds.any { newRect ->
                overlays.any { existing ->
                    val dx = maxOf(0, maxOf(existing.bounds.left - newRect.right, newRect.left - existing.bounds.right))
                    val dy = maxOf(0, maxOf(existing.bounds.top - newRect.bottom, newRect.top - existing.bounds.bottom))
                    val threshold = maxOf((newRect.height() * 1.5f).toInt(), fillPadding + 15)
                    dx < threshold && dy < threshold
                }
            }

            if (anyClose) {
                val nearTexts = ocrResult.groupTexts.zip(ocrResult.groupBounds).filter { (_, newRect) ->
                    overlays.any { existing ->
                        val dx = maxOf(0, maxOf(existing.bounds.left - newRect.right, newRect.left - existing.bounds.right))
                        val dy = maxOf(0, maxOf(existing.bounds.top - newRect.bottom, newRect.top - existing.bounds.bottom))
                        val threshold = maxOf((newRect.height() * 1.5f).toInt(), fillPadding + 15)
                        dx < threshold && dy < threshold
                    }
                }
                Log.d(TAG, "REFRESH: OCR recheck — new text near overlays: ${nearTexts.map { "'${it.first.take(30)}' bounds=${it.second}" }}")
                liveCaptureJob?.cancel()
                liveCaptureJob = serviceScope.launch { runLiveCaptureCycle() }
                return true
            } else {
                // New text far from overlays → translate and merge seamlessly
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
                    Log.d(TAG, "REFRESH: OCR recheck — merging ${newOverlayBoxes.size} new boxes")
                    val merged = overlays + newOverlayBoxes
                    cachedOverlayBoxes = merged
                    lastLiveOcrText = prevText + newDedupKey
                    showLiveOverlay(merged, cropL, cropT, cachedOverlayScreenW, cachedOverlayScreenH)
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

    private fun stopSceneChangeDetection() {
        sceneCheckJob?.cancel()
        sceneCheckJob = null
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
     * Called (on the main thread) every time user input is detected
     * (button press/release, touch) while live mode is active.
     */
    private fun onUserInteraction() {
        if (!liveActive) return

        // Cancel any in-flight capture/translation and hide the overlay
        // so the next screenshot is clean. Clear dedup state so the next
        // cycle always translates fresh — user input means the screen
        // likely changed, even if only a few characters differ.
        Log.d(TAG, "REFRESH: onUserInteraction")
        liveCaptureJob?.cancel()
        recheckNeeded = false
        lastLiveOcrText = null
        cachedOverlayBoxes = null
        PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
        stopSceneChangeDetection()

        // Debounce until input stops, then launch capture in its own job
        interactionDebounceJob?.cancel()
        interactionDebounceJob = serviceScope.launch {
            val settleMs = Prefs(this@CaptureService).captureIntervalSec * 1000L
            delay(settleMs)
            while (PlayTranslateAccessibilityService.instance?.isInputActive == true) {
                delay(settleMs)
            }
            liveCaptureJob = serviceScope.launch { runLiveCaptureCycle() }
        }
    }

    private fun showLiveOverlay(
        boxes: List<TranslationOverlayView.TextBox>,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int,
        startSceneDetection: Boolean = true
    ) {
        if (holdActive) return
        val a11y = PlayTranslateAccessibilityService.instance ?: return
        val dm = getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(gameDisplayId) ?: return
        a11y.showTranslationOverlay(display, boxes, cropLeft, cropTop, screenshotW, screenshotH)

        if (!startSceneDetection) return

        // Start scene-change detection. The coroutine captures its own
        // reference frame after a short delay.
        val fullDisplayBoxes = boxes.map { b ->
            android.graphics.Rect(
                b.bounds.left + cropLeft,
                b.bounds.top + cropTop,
                b.bounds.right + cropLeft,
                b.bounds.bottom + cropTop
            )
        }
        startSceneChangeDetection(fullDisplayBoxes)

        // Flag for scene detection to run one OCR re-check on its next poll
        // to detect text that appeared after our capture (e.g. typewriter finish).
        recheckNeeded = true
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
            val top    = maxOf((raw.height * captureTopFraction).toInt(), statusBarHeight)
            val left   = (raw.width  * captureLeftFraction).toInt()
            val bottom = (raw.height * captureBottomFraction).toInt()
            val right  = (raw.width  * captureRightFraction).toInt()
            val needsCrop = top > 0 || left > 0 || bottom < raw.height || right < raw.width
            val bitmap = if (needsCrop)
                Bitmap.createBitmap(raw, left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1))
            else raw

            // Create color reference for adaptive overlay colors
            val colorScale = 4
            colorRef = Bitmap.createScaledBitmap(raw, raw.width / colorScale, raw.height / colorScale, false)

            // Flash region indicator AFTER screenshot is captured
            if (liveShowRegionFlash) {
                liveShowRegionFlash = false
                flashRegionIndicator()
            }

            val ocrBitmap = blackoutFloatingIcon(bitmap, left, top)
            val ocrResult = ocrManager.recognise(ocrBitmap, sourceLang, screenshotWidth = raw.width)
            if (ocrBitmap !== raw) ocrBitmap.recycle()

            if (ocrResult == null) {
                lastLiveOcrText = null
                cachedOverlayBoxes = null
                PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
                onLiveNoText?.invoke()
                // Start scene detection so we recapture when the game changes
                // (e.g. dialogue box opens). No overlay boxes to exclude.
                if (liveActive) startSceneChangeDetection(emptyList())
                return
            }

            val newText = ocrResult.fullText
            val dedupKey = newText.filter { c -> OcrManager.isSourceLangChar(c, sourceLang) }

            if (dedupKey.isEmpty()) {
                lastLiveOcrText = null
                cachedOverlayBoxes = null
                PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
                onLiveNoText?.invoke()
                if (liveActive) startSceneChangeDetection(emptyList())
                return
            }

            // Dedup: if text hasn't changed significantly, re-show cached overlay.
            // Skip dedup entirely if we have no previous text (first cycle after start/clear).
            if (lastLiveOcrText != null && !isSignificantChange(lastLiveOcrText!!, dedupKey)) {
                val boxes = cachedOverlayBoxes
                if (boxes != null) {
                    showLiveOverlay(boxes, cachedOverlayCropLeft, cachedOverlayCropTop,
                        cachedOverlayScreenW, cachedOverlayScreenH)
                }
                return
            }
            lastLiveOcrText = dedupKey

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
                startSceneDetection = false)

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
                cachedOverlayBoxes = overlayBoxes
                cachedOverlayCropLeft = left
                cachedOverlayCropTop = top
                cachedOverlayScreenW = screenshotW
                cachedOverlayScreenH = screenshotH
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
        return getString(R.string.status_no_text, langName, captureRegionLabel)
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
            val top    = maxOf((raw.height * captureTopFraction).toInt(), statusBarHeight)
            val left   = (raw.width  * captureLeftFraction).toInt()
            val bottom = (raw.height * captureBottomFraction).toInt()
            val right  = (raw.width  * captureRightFraction).toInt()
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
