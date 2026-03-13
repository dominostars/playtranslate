package com.gamelens

import android.app.Notification
import android.app.NotificationChannel
import android.graphics.Bitmap
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
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
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
    private var skipTranslation: Boolean = false
    private var captureTopFraction: Float    = 0f
    private var captureBottomFraction: Float = 1f
    private var captureLeftFraction: Float   = 0f
    private var captureRightFraction: Float  = 1f
    private var captureRegionLabel: String   = ""

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
        this.skipTranslation = Prefs(this).hideTranslation
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

    // ── Live mode ─────────────────────────────────────────────────────────

    private var liveJob: Job? = null
    private var liveTranslationJob: Job? = null
    private var lastLiveOcrText: String? = null

    val isLive: Boolean get() = liveJob?.isActive == true

    fun startLive() {
        liveJob?.cancel()
        lastLiveOcrText = null
        liveJob = serviceScope.launch {
            while (true) {
                runLiveCaptureCycle()
                val intervalMs = Prefs(this@CaptureService).captureIntervalSec * 1000L
                delay(intervalMs)
            }
        }
    }

    fun stopLive() {
        liveJob?.cancel()
        liveJob = null
        liveTranslationJob?.cancel()
        liveTranslationJob = null
        lastLiveOcrText = null
    }

    /**
     * Captures the given display using AccessibilityService if available,
     * falling back to MediaProjection.
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
        return suspendCancellableCoroutine { cont ->
            val imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            val vd = mp.createVirtualDisplay(
                "PlayTranslateCapture", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, null
            )
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
                // Re-check after bitmap creation — cancel may have arrived in the narrow
                // window between the earlier isActive check and now.
                if (!cont.isActive) { result.recycle(); return@setOnImageAvailableListener }
                cont.resume(result)
            }, Handler(Looper.getMainLooper()))
            cont.invokeOnCancellation { vd.release(); imageReader.close() }
        }
    }

    private suspend fun runLiveCaptureCycle() {
        if (!isConfigured) return
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

            val ocrResult = ocrManager.recognise(bitmap, sourceLang)
            if (bitmap !== raw) bitmap.recycle()

            if (ocrResult == null) {
                // No text at all — reset dedup so the next hit retranslates, then notify.
                raw.recycle()
                lastLiveOcrText = null
                onLiveNoText?.invoke()
                return
            }

            val newText = ocrResult.fullText
            // Dedup key: only source-language characters (kana/kanji for Japanese).
            // Punctuation, spaces, and decorative glyphs can vary frame-to-frame.
            val dedupKey = newText.filter { c -> OcrManager.isSourceLangChar(c, sourceLang) }

            if (dedupKey.isEmpty()) {
                // OCR found text but none in the source language — treat as no text.
                raw.recycle()
                lastLiveOcrText = null
                onLiveNoText?.invoke()
                return
            }

            // Fuzzy comparison: tolerate up to LIVE_DEDUP_TOLERANCE character-count
            // differences. This prevents oscillation where OCR alternates between two
            // slightly different outputs (e.g. one extra artifact character) for the
            // same static screen, which would fool an exact-match check every cycle.
            if (!isSignificantChange(lastLiveOcrText ?: "", dedupKey)) {
                raw.recycle()
                return
            }
            lastLiveOcrText = dedupKey
            liveTranslationJob?.cancel()
            val gen = ++captureGeneration
            onTranslationStarted?.invoke()

            // Save screenshot only when new content will be shown — this keeps the file
            // referenced by lastResult alive until the next real change is detected.
            val screenshotPath = saveScreenshotToCache(raw)
            raw.recycle()

            val liveGroupTexts = ocrResult.groupTexts
            liveTranslationJob = serviceScope.launch {
                try {
                    val (translated, note) = if (skipTranslation) Pair("", null) else translateGroups(liveGroupTexts)
                    if (gen != captureGeneration) return@launch
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
                } catch (e: Exception) { Log.w(TAG, "Live translation failed: ${e.message}") }
            }
        } catch (e: Exception) { Log.w(TAG, "Live capture cycle failed: ${e.message}") }
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

            // Exclude the status bar: dynamically query its height for the game display.
            // maxOf() means if the user's region already starts below the status bar,
            // nothing changes; and on displays with no status bar (height == 0) this is a no-op.
            val statusBarHeight = getStatusBarHeightForDisplay(gameDisplayId)
            val top    = maxOf((raw.height * captureTopFraction).toInt(), statusBarHeight)
            val left   = (raw.width  * captureLeftFraction).toInt()
            val bottom = (raw.height * captureBottomFraction).toInt()
            val right  = (raw.width  * captureRightFraction).toInt()
            val bitmap = cropBitmap(raw, top, bottom, left, right)

            onStatusUpdate?.invoke(getString(R.string.status_ocr))
            val ocrResult = ocrManager.recognise(bitmap, sourceLang)
            bitmap.recycle()

            if (ocrResult == null) {
                onStatusUpdate?.invoke(noTextMessage())
                return
            }

            val (translated, note) = if (skipTranslation) {
                Pair("", null)
            } else {
                onStatusUpdate?.invoke(getString(R.string.status_translating))
                translateGroups(ocrResult.groupTexts)
            }

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
     * Translates each group in parallel and joins results with double-newline.
     * Returns the combined translated text and an optional note (from ML Kit fallback).
     */
    private suspend fun translateGroups(groupTexts: List<String>): Pair<String, String?> {
        if (groupTexts.size <= 1) {
            return translate(groupTexts.firstOrNull() ?: "")
        }
        val results = groupTexts.map { group ->
            serviceScope.async { translate(group) }
        }.awaitAll()
        val translated = results.joinToString("\n\n") { it.first }
        // Surface a note if any group fell back to ML Kit
        val note = results.mapNotNull { it.second }.firstOrNull()
        return Pair(translated, note)
    }

    /** On-demand translation for the "Show Translation" button when hideTranslation is active. */
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
