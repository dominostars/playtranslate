package com.playtranslate.capture

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.playtranslate.CaptureService
import com.playtranslate.PlayTranslateTileService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "MediaProjectionCtl"

/**
 * Owns the MediaProjection session — the consent token, the [MediaProjection],
 * a per-resolution [VirtualDisplay], and the [ImageReader] frames are pulled
 * from. One instance per [CaptureService].
 *
 * Lazily established on the first [captureFrame]: with no consent, a
 * transparent [MediaProjectionConsentActivity] is launched and the call
 * suspends on [consentGate] until the user responds. The session is then kept
 * warm for the process lifetime — MediaProjection tokens can't be persisted,
 * so a process restart (or a user revoke) needs fresh consent.
 *
 * MediaProjection captures the display the projection was authorized for
 * (typically the default display); it can't target an arbitrary displayId the
 * way the accessibility backend's `takeScreenshot` can. [captureFrame] thus
 * captures the projected display regardless of [displayId].
 */
class MediaProjectionController(private val service: CaptureService) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var resultCode: Int = Activity.RESULT_CANCELED
    private var resultData: Intent? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var readerW = 0
    private var readerH = 0

    /** Non-null while a consent dialog is in flight; every concurrent
     *  [captureFrame] awaits the same gate so only one dialog shows. */
    private var consentGate: CompletableDeferred<Boolean>? = null

    /** True once the user has granted a token still valid for this process. */
    val hasConsent: Boolean get() = resultData != null

    /** Observers notified right after a teardown drops the held consent. The
     *  Settings sheet registers one to refresh its Turn On/Off buttons —
     *  MediaProjection "active" is held consent, not a pref it could watch. */
    private val teardownListeners = mutableListOf<() -> Unit>()

    fun addTeardownListener(listener: () -> Unit) { teardownListeners += listener }
    fun removeTeardownListener(listener: () -> Unit) { teardownListeners -= listener }

    /** Delivered by [MediaProjectionConsentActivity]. Completes any pending
     *  [consentGate] so suspended [captureFrame] calls resume. */
    fun onConsentResult(resultCode: Int, data: Intent?) {
        val granted = resultCode == Activity.RESULT_OK && data != null
        if (granted) {
            this.resultCode = resultCode
            this.resultData = data
        }
        val gate = consentGate
        consentGate = null
        gate?.complete(granted)
    }

    /**
     * Ensure a MediaProjection consent token is held, prompting the user via
     * [MediaProjectionConsentActivity] when it isn't. Returns true once consent
     * is granted. Safe to call with consent already held — returns true with no
     * prompt; concurrent callers share the single in-flight dialog.
     */
    suspend fun ensureConsent(): Boolean {
        if (hasConsent) return true
        return requestConsent()
    }

    /**
     * Capture one clean frame of the projected display at its native
     * resolution. Lazily establishes consent + projection + virtual display.
     * Returns null on denied consent or any failure. Call on the main thread —
     * the heavy pixel copy is moved off it internally.
     */
    suspend fun captureFrame(displayId: Int): Bitmap? {
        if (!ensureProjection()) return null
        val (w, h) = nativeSize(displayId) ?: return null
        val reader = ensureVirtualDisplay(w, h) ?: return null
        return acquireBitmap(reader, w, h)
    }

    private suspend fun ensureProjection(): Boolean {
        if (projection != null) return true
        if (!ensureConsent()) return false
        // API 34+: the foreground service must already carry the
        // mediaProjection type before getMediaProjection() is called.
        service.ensureMediaProjectionForegroundType()
        val mgr = service.applicationContext
            .getSystemService(MediaProjectionManager::class.java) ?: return false
        val data = resultData ?: return false
        val proj = try {
            mgr.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed: ${e.message}")
            return false
        }
        // The callback must be registered before createVirtualDisplay.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { onProjectionRevoked() }
        }, mainHandler)
        projection = proj
        return true
    }

    private suspend fun requestConsent(): Boolean {
        consentGate?.let { return it.await() }
        val gate = CompletableDeferred<Boolean>()
        consentGate = gate
        MediaProjectionConsentActivity.launch(service)
        return gate.await()
    }

    private fun ensureVirtualDisplay(w: Int, h: Int): ImageReader? {
        val proj = projection ?: return null
        imageReader?.let { if (readerW == w && readerH == h) return it }
        // First use, or resolution changed (rotation / reconfig) — rebuild.
        virtualDisplay?.release()
        imageReader?.close()
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val dpi = service.resources.displayMetrics.densityDpi
        virtualDisplay = proj.createVirtualDisplay(
            "PlayTranslateCapture", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, mainHandler,
        )
        imageReader = reader
        readerW = w
        readerH = h
        return reader
    }

    /** Native panel resolution for [displayId], oriented to the current
     *  rotation. Uses Display.getMode() — the metrics APIs misreport on some
     *  dual-screen devices, so the physical mode dims are the reliable source. */
    private fun nativeSize(displayId: Int): Pair<Int, Int>? {
        val dm = service.getSystemService(DisplayManager::class.java) ?: return null
        val display = dm.getDisplay(displayId) ?: return null
        val mode = display.mode
        val pw = mode.physicalWidth
        val ph = mode.physicalHeight
        val landscape = display.rotation == Surface.ROTATION_90 ||
            display.rotation == Surface.ROTATION_270
        return if (landscape) maxOf(pw, ph) to minOf(pw, ph)
        else minOf(pw, ph) to maxOf(pw, ph)
    }

    private suspend fun acquireBitmap(reader: ImageReader, width: Int, height: Int): Bitmap? {
        // The overlay-blanking + vsync wait already happened in the caller.
        // Give the virtual-display → ImageReader pipeline a few frames to
        // deliver the post-blank frame, then take the latest. The exact
        // frame-freshness discipline is a known device-testing tuning point.
        delay(64)
        var image = acquireLatest(reader)
        if (image == null) {
            delay(48)
            image = acquireLatest(reader) ?: return null
        }
        return try {
            withContext(Dispatchers.Default) { imageToBitmap(image, width, height) }
        } catch (e: Exception) {
            Log.e(TAG, "imageToBitmap failed: ${e.message}")
            null
        } finally {
            image.close()
        }
    }

    /** [ImageReader.acquireLatestImage] that returns null instead of throwing
     *  when the reader has already been closed. teardown() — a projection
     *  revoke, or CaptureService.onDestroy — can close the reader while a
     *  capture is suspended mid-[acquireBitmap]; a closed reader has no frame
     *  to deliver, so the capture fails into the normal null path. Catches
     *  IllegalStateException only (the documented closed/maxImages signal);
     *  acquireLatestImage is not a suspend call, so this can't swallow a
     *  CancellationException. */
    private fun acquireLatest(reader: ImageReader): Image? =
        try {
            reader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "capture reader closed mid-acquire: ${e.message}")
            null
        }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        // A row-padded buffer needs a wider bitmap; crop back to width after.
        val padded = Bitmap.createBitmap(
            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888,
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        return if (rowPadding == 0) padded
        else Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
    }

    private fun teardown() {
        val hadConsent = resultData != null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        readerW = 0
        readerH = 0
        projection?.let { try { it.stop() } catch (_: Exception) {} }
        projection = null
        // The token is single-use on API 34+ — once the projection stops, the
        // next capture must re-prompt for consent.
        resultCode = Activity.RESULT_CANCELED
        resultData = null
        // Notify observers once consent is actually gone (resultData cleared),
        // so a listener that re-reads hasConsent sees false. Snapshot the list
        // — a listener may unregister itself as it runs.
        if (hadConsent) teardownListeners.toList().forEach { it() }
    }

    /** The projection was stopped by the system or the user (a revoke / sleep
     *  teardown — not our own [destroy]). Tear the session down, then drop the
     *  now-ungated floating controls and refresh the QS tile so the UI catches
     *  up with the lost consent. Runs on [mainHandler] (the callback thread),
     *  so the main-thread-only reconcile is safe. */
    private fun onProjectionRevoked() {
        teardown()
        CaptureBackendResolver.activeOverlayUi?.reconcileFloatingIcons()
        PlayTranslateTileService.TileSync.refresh(service.applicationContext)
    }

    /** Release the projection and virtual display. */
    fun destroy() = teardown()
}
