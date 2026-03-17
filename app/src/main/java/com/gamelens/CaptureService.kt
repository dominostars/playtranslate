package com.gamelens

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
import com.gamelens.model.TranslationResult
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
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import com.gamelens.ui.TranslationOverlayView
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics

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

    private val ocrManager = OcrManager()
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

    // ── MediaProjection capture (alternative to AccessibilityService) ─────────

    private var mediaProjection: MediaProjection? = null

    val hasMediaProjection: Boolean get() = mediaProjection != null

    fun setMediaProjection(resultCode: Int, data: Intent) {
        mediaProjection?.stop()
        val mgr = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = mgr.getMediaProjection(resultCode, data)
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
        ocrManager.close()
        translationManager?.close()
        deeplTranslator?.close()
        lingvaTranslator?.close()
        mediaProjection?.stop()
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
    private var captureGeneration = 0

    fun captureOnce() {
        captureJob?.cancel()
        val gen = ++captureGeneration
        captureJob = serviceScope.launch { runCaptureCycle(gen) }
    }

    /**
     * Processes a pre-captured screenshot bitmap instead of taking a new one.
     * Used when the screenshot must be taken before an activity appears on screen
     * (e.g. single-screen region capture from the floating menu).
     */
    fun processScreenshot(raw: Bitmap) {
        captureJob?.cancel()
        val gen = ++captureGeneration
        captureJob = serviceScope.launch { runProcessCycle(gen, raw) }
    }

    private suspend fun runProcessCycle(generation: Int, raw: Bitmap) {
        if (!isConfigured) {
            onError?.invoke("Not configured — tap Translate to set up")
            raw.recycle()
            return
        }
        try {
            onStatusUpdate?.invoke(getString(R.string.status_capturing))
            val screenshotPath = saveScreenshotToCache(raw)

            val statusBarHeight = getStatusBarHeightForDisplay(gameDisplayId)
            val top    = maxOf((raw.height * captureTopFraction).toInt(), statusBarHeight)
            val left   = (raw.width  * captureLeftFraction).toInt()
            val bottom = (raw.height * captureBottomFraction).toInt()
            val right  = (raw.width  * captureRightFraction).toInt()
            val bitmap = cropBitmap(raw, top, bottom, left, right)

            val ocrBitmap = blackoutFloatingIcon(bitmap, left, top)
            onStatusUpdate?.invoke(getString(R.string.status_ocr))
            val ocrResult = ocrManager.recognise(ocrBitmap, sourceLang)
            ocrBitmap.recycle()

            if (ocrResult == null) {
                onStatusUpdate?.invoke(noTextMessage())
                return
            }

            onStatusUpdate?.invoke(getString(R.string.status_translating))
            val (translated, note) = translateGroups(ocrResult.groupTexts)

            if (generation != captureGeneration) return

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
        }
    }

    // ── Live mode ─────────────────────────────────────────────────────────
    //
    // Interaction-driven: capture once, show overlay, then wait for user
    // input. On input → hide overlay + start debounce timer. When the
    // timer expires (no further input) → capture again.

    private var liveActive = false
    private var liveTranslationJob: Job? = null
    private var interactionDebounceJob: Job? = null
    private var lastLiveOcrText: String? = null
    /** Cached overlay data so dedup-unchanged frames can re-show instantly. */
    private var cachedOverlayBoxes: List<TranslationOverlayView.TextBox>? = null
    private var cachedOverlayCropLeft = 0
    private var cachedOverlayCropTop = 0
    private var cachedOverlayScreenW = 0
    private var cachedOverlayScreenH = 0
    /** True until the first live capture shows the region indicator. */
    private var liveShowRegionFlash = false

    val isLive: Boolean get() = liveActive

    fun startLive() {
        liveActive = true
        liveShowRegionFlash = true
        lastLiveOcrText = null
        cachedOverlayBoxes = null
        interactionDebounceJob?.cancel()
        liveTranslationJob?.cancel()

        // Buttons and touch are detected via onKeyEvent and touch sentinel.
        PlayTranslateAccessibilityService.instance
            ?.startInputMonitoring(gameDisplayId) { onUserInteraction() }

        // Interaction-driven capture. Scene-change detection via pixel-diff
        // handles joystick/d-pad movement.
        interactionDebounceJob = serviceScope.launch { runLiveCaptureCycle() }
    }

    fun stopLive() {
        liveActive = false
        interactionDebounceJob?.cancel()
        interactionDebounceJob = null
        liveTranslationJob?.cancel()
        liveTranslationJob = null
        stopSceneChangeDetection()
        lastLiveOcrText = null
        cachedOverlayBoxes = null
        PlayTranslateAccessibilityService.instance?.stopInputMonitoring()
        PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
    }

    /** Trigger a fresh capture cycle in live mode (e.g. after hold-release). */
    fun refreshLiveOverlay() {
        if (!liveActive) return
        // Clear all previous live mode work so the fresh cycle runs
        // without interference from stale scene detection or translation.
        lastLiveOcrText = null
        cachedOverlayBoxes = null
        ++captureGeneration
        stopSceneChangeDetection()
        liveTranslationJob?.cancel()
        interactionDebounceJob?.cancel()
        interactionDebounceJob = serviceScope.launch { runLiveCaptureCycle() }
    }

    /** One-shot: capture, OCR, translate, show overlay (not live mode). */
    fun showOneShotOverlay() {
        ++captureGeneration
        serviceScope.launch { runLiveCaptureCycle() }
    }

    /** True while the user is holding the floating icon — suppresses overlay display. */
    var holdActive = false

    /** Path to the last clean screenshot (no overlays). Used by drag-to-lookup to
     *  avoid a second takeScreenshot call (which can be rate-limited by the OS). */
    var lastCleanScreenshotPath: String? = null
        private set

    /** Cancel any in-flight one-shot and invalidate its results. */
    fun cancelOneShot() {
        ++captureGeneration
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
    private val SCENE_CHECK_INTERVAL_MS = 500L
    private val SCENE_CHANGE_THRESHOLD = 0.40f  // 40% of sampled pixels must change
    private val PIXEL_DIFF_THRESHOLD = 30       // per-channel RGB difference

    /**
     * Start scene-change detection after overlays are shown.
     * [overlayBoxes] are the overlay bounding rects in full-display coordinates.
     * Everything runs inside a single coroutine on the main thread — no
     * async callbacks, no shared mutable state, no race conditions.
     */
    private fun startSceneChangeDetection(overlayBoxes: List<android.graphics.Rect>) {
        stopSceneChangeDetection()

        sceneCheckJob = serviceScope.launch {
            // Initial delay to let the overlay render before taking reference
            delay(SCENE_CHECK_INTERVAL_MS)
            val a11y = PlayTranslateAccessibilityService.instance ?: return@launch

            // Take reference frame (with overlays visible)
            val refBitmap = suspendCancellableCoroutine<Bitmap?> { cont ->
                a11y.captureDisplayRaw(gameDisplayId) { cont.resume(it) }
            } ?: return@launch

            // Build sample positions, skipping overlay regions
            val positions = mutableListOf<Pair<Int, Int>>()
            for (y in 0 until refBitmap.height step 10) {
                for (x in 0 until refBitmap.width step 10) {
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

            // Poll loop
            var sceneMoving = false
            var prevFramePixels: IntArray? = null

            while (liveActive) {
                delay(SCENE_CHECK_INTERVAL_MS)

                val bitmap = suspendCancellableCoroutine<Bitmap?> { cont ->
                    a11y.captureDisplayRaw(gameDisplayId) { cont.resume(it) }
                }
                if (bitmap == null) continue

                val currentPixels = IntArray(positions.size) { i ->
                    val (x, y) = positions[i]
                    if (x < bitmap.width && y < bitmap.height) bitmap.getPixel(x, y) else 0
                }
                bitmap.recycle()

                if (!sceneMoving) {
                    // Phase 1: compare against reference to detect movement start
                    val pct = pixelDiffPercent(refPixels, currentPixels)
                    if (pct >= SCENE_CHANGE_THRESHOLD) {
                        sceneMoving = true
                        ++captureGeneration // invalidate in-flight work
                        PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
                        liveTranslationJob?.cancel()
                    }
                } else {
                    // Phase 2: compare consecutive frames to detect stabilization
                    val prev = prevFramePixels
                    if (prev != null) {
                        val pct = pixelDiffPercent(prev, currentPixels)
                        if (pct < 0.05f) {
                            // Scene stabilized — recapture
                            interactionDebounceJob?.cancel()
                            interactionDebounceJob = serviceScope.launch {
                                val settleMs = Prefs(this@CaptureService).captureIntervalSec * 1000L
                                delay(settleMs)
                                while (PlayTranslateAccessibilityService.instance?.isInputActive == true) {
                                    delay(settleMs)
                                }
                                runLiveCaptureCycle()
                            }
                            return@launch
                        }
                    }
                }
                prevFramePixels = currentPixels
            }
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

        ++captureGeneration

        // Hide overlay so the next captureDisplay screenshot doesn't
        // include our translations.
        PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
        liveTranslationJob?.cancel()
        stopSceneChangeDetection()

        // Debounce until input stops
        interactionDebounceJob?.cancel()
        interactionDebounceJob = serviceScope.launch {
            val settleMs = Prefs(this@CaptureService).captureIntervalSec * 1000L
            delay(settleMs)
            while (PlayTranslateAccessibilityService.instance?.isInputActive == true) {
                delay(settleMs)
            }
            runLiveCaptureCycle()
        }
    }

    private fun showLiveOverlay(
        boxes: List<TranslationOverlayView.TextBox>,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int
    ) {
        if (holdActive) return // suppress while user is holding the icon
        val a11y = PlayTranslateAccessibilityService.instance ?: return
        val dm = getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(gameDisplayId) ?: return
        a11y.showTranslationOverlay(display, boxes, cropLeft, cropTop, screenshotW, screenshotH)

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
    }

    /**
     * Captures the given display using AccessibilityService.takeScreenshot
     * (hides overlays briefly via Choreographer to get a clean frame),
     * falling back to MediaProjection if the AccessibilityService isn't available.
     */
    private suspend fun captureScreen(displayId: Int): Bitmap? {
        val a11y = PlayTranslateAccessibilityService.instance
        return if (a11y != null) {
            suspendCancellableCoroutine { cont ->
                a11y.captureDisplay(displayId) { bmp -> cont.resume(bmp) }
            }
        } else {
            captureScreenViaMediaProjection(displayId)
        }
    }

    private suspend fun captureScreenViaMediaProjection(displayId: Int): Bitmap? {
        val mp = mediaProjection ?: return null
        val dm = getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(displayId) ?: return null
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val dpi = metrics.densityDpi
        return kotlinx.coroutines.withTimeoutOrNull(3000L) {
            suspendCancellableCoroutine { cont ->
                val imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
                val vd = try {
                    mp.createVirtualDisplay(
                        "PlayTranslateCapture", w, h, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        imageReader.surface, null, null
                    )
                } catch (e: Exception) {
                    imageReader.close()
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                imageReader.setOnImageAvailableListener({ reader ->
                    if (!cont.isActive) { vd.release(); reader.close(); return@setOnImageAvailableListener }
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val plane = image.planes[0]
                    val rowPadding = plane.rowStride - plane.pixelStride * w
                    val bmpW = w + rowPadding / plane.pixelStride
                    val bmp = Bitmap.createBitmap(bmpW, h, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(plane.buffer)
                    image.close()
                    vd.release()
                    reader.close()
                    val result = if (rowPadding == 0) bmp
                        else Bitmap.createBitmap(bmp, 0, 0, w, h).also { bmp.recycle() }
                    if (!cont.isActive) { result.recycle(); return@setOnImageAvailableListener }
                    cont.resume(result)
                }, Handler(Looper.getMainLooper()))
                cont.invokeOnCancellation { vd.release(); imageReader.close() }
            }
        }
    }

    private suspend fun runLiveCaptureCycle() {
        if (!isConfigured) return
        val cycleGen = captureGeneration
        try {
            val raw: Bitmap = captureScreen(gameDisplayId) ?: return

            // Crop for OCR without recycling raw — we need raw for the screenshot, but only
            // if the dedup check passes. Keeping raw alive avoids saving screenshots for
            // every cycle, which would rotate the 5-file cap and delete the screenshot that
            // is still referenced by the last displayed result.
            val statusBarHeight = getStatusBarHeightForDisplay(gameDisplayId)
            val top    = maxOf((raw.height * captureTopFraction).toInt(), statusBarHeight)
            val left   = (raw.width  * captureLeftFraction).toInt()
            val bottom = (raw.height * captureBottomFraction).toInt()
            val right  = (raw.width  * captureRightFraction).toInt()
            val needsCrop = top > 0 || left > 0 || bottom < raw.height || right < raw.width
            val bitmap = if (needsCrop)
                Bitmap.createBitmap(raw, left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1))
            else raw

            // Save screenshot and create color reference IMMEDIATELY — the bitmap
            // from captureDisplay can become invalid after any sub-bitmap operations
            // due to HardwareBuffer lifecycle issues.
            lastCleanScreenshotPath = saveScreenshotToCache(raw)

            // Flash region indicator AFTER screenshot is saved — safe from contamination
            if (liveShowRegionFlash) {
                liveShowRegionFlash = false
                flashRegionIndicator()
            }
            val colorScale = 4
            val colorRef = Bitmap.createScaledBitmap(raw, raw.width / colorScale, raw.height / colorScale, false)

            if (cycleGen != captureGeneration) { colorRef.recycle(); raw.recycle(); return }

            val ocrBitmap = blackoutFloatingIcon(bitmap, left, top)
            val ocrResult = ocrManager.recognise(ocrBitmap, sourceLang)
            if (ocrBitmap !== raw) ocrBitmap.recycle()

            if (cycleGen != captureGeneration) { colorRef.recycle(); raw.recycle(); return }

            if (ocrResult == null) {
                colorRef.recycle()
                raw.recycle()
                lastLiveOcrText = null
                cachedOverlayBoxes = null
                PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
                onLiveNoText?.invoke()
                return
            }

            val newText = ocrResult.fullText
            // Dedup key: only source-language characters (kana/kanji for Japanese).
            // Punctuation, spaces, and decorative glyphs can vary frame-to-frame.
            val dedupKey = newText.filter { c -> OcrManager.isSourceLangChar(c, sourceLang) }

            if (dedupKey.isEmpty()) {
                colorRef.recycle()
                raw.recycle()
                lastLiveOcrText = null
                cachedOverlayBoxes = null
                PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
                onLiveNoText?.invoke()
                return
            }

            // Fuzzy comparison: tolerate up to LIVE_DEDUP_TOLERANCE character-count
            // differences. This prevents oscillation where OCR alternates between two
            // slightly different outputs (e.g. one extra artifact character) for the
            // same static screen, which would fool an exact-match check every cycle.
            if (!isSignificantChange(lastLiveOcrText ?: "", dedupKey)) {
                colorRef.recycle()
                raw.recycle()
                // Text unchanged — re-show the cached overlay (it was hidden on interaction)
                if (cycleGen != captureGeneration) return // invalidated during OCR
                val boxes = cachedOverlayBoxes
                if (boxes != null) {
                    showLiveOverlay(boxes, cachedOverlayCropLeft, cachedOverlayCropTop,
                        cachedOverlayScreenW, cachedOverlayScreenH)
                }
                return
            }
            lastLiveOcrText = dedupKey
            liveTranslationJob?.cancel()
            val gen = ++captureGeneration
            onTranslationStarted?.invoke()

            val screenshotW = raw.width
            val screenshotH = raw.height
            val screenshotPath = lastCleanScreenshotPath
            raw.recycle()

            val liveGroupTexts = ocrResult.groupTexts
            val liveGroupBounds = ocrResult.groupBounds
            liveTranslationJob = serviceScope.launch {
                try {
                    val perGroup = translateGroupsSeparately(liveGroupTexts)
                    if (gen != captureGeneration) return@launch
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
                    // Show translation overlay on the game screen
                    if (liveGroupBounds.size == perGroup.size) {
                        // Sample background colors from a ring OUTSIDE each OCR box
                        // (avoids sampling text pixels). Text color is white or black
                        // for contrast against the sampled background.
                        val buffer = 10 / colorScale
                        val overlayBoxes = perGroup.zip(liveGroupBounds).map { (tr, bounds) ->
                            val sl = (bounds.left + left) / colorScale
                            val st = (bounds.top + top) / colorScale
                            val sr = (bounds.right + left) / colorScale
                            val sb = (bounds.bottom + top) / colorScale

                            val bgColor = averageColor(colorRef,
                                sl - buffer, st - buffer, sr + buffer, sb + buffer,
                                excludeInner = android.graphics.Rect(sl, st, sr, sb))

                            val textColor = if (colorLuminance(bgColor) > 128)
                                android.graphics.Color.BLACK else android.graphics.Color.WHITE

                            TranslationOverlayView.TextBox(tr.first, bounds, bgColor, textColor)
                        }
                        colorRef.recycle()
                        // Cache for re-display after dedup-unchanged interactions
                        cachedOverlayBoxes = overlayBoxes
                        cachedOverlayCropLeft = left
                        cachedOverlayCropTop = top
                        cachedOverlayScreenW = screenshotW
                        cachedOverlayScreenH = screenshotH
                        showLiveOverlay(overlayBoxes, left, top, screenshotW, screenshotH)
                    }
                } catch (e: Exception) { Log.w(TAG, "Live translation failed: ${e.message}") }
            }
        } catch (e: Exception) { Log.w(TAG, "Live capture cycle failed: ${e.message}") }
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
        return false
    }

    companion object {
        private const val LIVE_DEDUP_TOLERANCE = 3  // max character-count drift treated as noise

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

    private suspend fun runCaptureCycle(generation: Int) {
        if (!isConfigured) {
            onError?.invoke("Not configured — tap Translate to set up")
            return
        }

        try {
            onStatusUpdate?.invoke(getString(R.string.status_capturing))

            val raw: Bitmap = captureScreen(gameDisplayId) ?: run {
                onError?.invoke("Screenshot failed for display $gameDisplayId. Try a different display in Settings.")
                return
            }

            val screenshotPath = saveScreenshotToCache(raw)

            // Flash region indicator AFTER screenshot is saved — safe from contamination
            flashRegionIndicator()

            // Exclude the status bar: dynamically query its height for the game display.
            // maxOf() means if the user's region already starts below the status bar,
            // nothing changes; and on displays with no status bar (height == 0) this is a no-op.
            val statusBarHeight = getStatusBarHeightForDisplay(gameDisplayId)
            val top    = maxOf((raw.height * captureTopFraction).toInt(), statusBarHeight)
            val left   = (raw.width  * captureLeftFraction).toInt()
            val bottom = (raw.height * captureBottomFraction).toInt()
            val right  = (raw.width  * captureRightFraction).toInt()
            val bitmap = cropBitmap(raw, top, bottom, left, right)

            val ocrBitmap = blackoutFloatingIcon(bitmap, left, top)
            onStatusUpdate?.invoke(getString(R.string.status_ocr))
            val ocrResult = ocrManager.recognise(ocrBitmap, sourceLang)
            ocrBitmap.recycle()

            if (ocrResult == null) {
                onStatusUpdate?.invoke(noTextMessage())
                return
            }

            onStatusUpdate?.invoke(getString(R.string.status_translating))
            val (translated, note) = translateGroups(ocrResult.groupTexts)

            // Discard stale results if a newer capture was started while this one
            // was in-flight (OkHttp blocking calls aren't cooperatively cancellable).
            if (generation != captureGeneration) return

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
        }
    }

    /**
     * Translates each group in parallel and returns each result separately.
     */
    private suspend fun translateGroupsSeparately(groupTexts: List<String>): List<Pair<String, String?>> {
        if (groupTexts.size <= 1) {
            return listOf(translate(groupTexts.firstOrNull() ?: ""))
        }
        return groupTexts.map { group ->
            serviceScope.async { translate(group) }
        }.awaitAll()
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
                return Pair(deepl.translate(text), null)
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
                return Pair(lingva.translate(text), null)
            } catch (e: Exception) {
                Log.w(TAG, "Lingva failed (${e.message}), falling back to ML Kit")
            }
        }

        // 3. ML Kit offline fallback
        val note = if (isNetworkAvailable())
            getString(R.string.note_mlkit_service_unavailable)
        else
            getString(R.string.note_mlkit_no_internet)
        return Pair(mlKitTranslate(text), note)
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

    private fun saveScreenshotToCache(bitmap: Bitmap): String? {
        return try {
            val dir = File(cacheDir, "screenshots").apply { mkdirs() }
            val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(5)?.forEach { it.delete() }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "saveScreenshotToCache failed: ${e.message}")
            null
        }
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
