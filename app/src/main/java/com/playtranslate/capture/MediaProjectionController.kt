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
import android.view.Display
import com.playtranslate.CaptureService
import com.playtranslate.PlayTranslateTileService
import com.playtranslate.displaySizePx
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
 * Consent is secured up front via [ensureConsent] — by `startLive()` before
 * the live-mode loop exists, by the one-shot capture path
 * ([MediaProjectionCaptureSource.requestClean]), or by the Settings /
 * Quick-Settings activate path — never lazily from inside a capture.
 * [captureFrame] requires consent to
 * already be held and returns null otherwise; it never launches the dialog. (A
 * prompt mid-loop has its Cancel tap caught by the live-mode touch sentinel as
 * game input, restarting the loop and re-prompting in a cycle.) Once granted,
 * the session is kept warm for the process lifetime — MediaProjection tokens
 * can't be persisted, so a process restart or a user revoke needs fresh
 * consent.
 *
 * MediaProjection captures the display the projection was authorized for —
 * always the default display ([projectedDisplayId]); it can't target an
 * arbitrary display the way the accessibility backend's `takeScreenshot` can.
 * [captureFrame] always captures [projectedDisplayId].
 */
class MediaProjectionController(private val service: CaptureService) {

    private val mainHandler = Handler(Looper.getMainLooper())

    // Session fields are touched from the consent-result callback, the
    // suspend capture path, and the projection teardown callback — @Volatile
    // gives every reader (notably hasConsent, polled off-main through
    // CaptureLifecycle) the latest write. Visibility only; no compound update.
    @Volatile private var resultCode: Int = Activity.RESULT_CANCELED
    @Volatile private var resultData: Intent? = null
    @Volatile private var projection: MediaProjection? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var imageReader: ImageReader? = null
    @Volatile private var readerW = 0
    @Volatile private var readerH = 0

    /** Non-null while a consent dialog is in flight; every concurrent
     *  [captureFrame] awaits the same gate so only one dialog shows. */
    @Volatile private var consentGate: CompletableDeferred<Boolean>? = null

    /** True once the user has granted a token still valid for this process. */
    val hasConsent: Boolean get() = resultData != null

    /** The display this backend can capture. MediaProjection's
     *  `createScreenCaptureIntent()` only ever projects the default display,
     *  so capture, OCR, and overlays under this backend all stay on it — there
     *  is no API to mirror a secondary display. */
    val projectedDisplayId: Int = Display.DEFAULT_DISPLAY

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
     * Capture one clean frame of the projected display ([projectedDisplayId])
     * at its current resolution. Lazily establishes the projection + virtual
     * display, but NOT consent — consent must already be held (see
     * [ensureConsent]); returns null without prompting if it isn't. Returns
     * null on any capture failure too. Call on the main thread — the heavy
     * pixel copy is moved off it internally.
     */
    suspend fun captureFrame(): Bitmap? {
        if (!ensureProjection()) return null
        val (w, h) = captureSize(projectedDisplayId) ?: return null
        val reader = ensureVirtualDisplay(w, h) ?: return null
        return acquireBitmap(reader, w, h)
    }

    private fun ensureProjection(): Boolean {
        if (projection != null) return true
        // captureFrame never prompts — consent is secured up front by
        // ensureConsent() (startLive / the activate path). A loop reaching
        // here without consent means a mid-session revoke; fail so the
        // caller's checkConsentLost stops live mode, rather than the dialog
        // re-appearing every frame.
        if (!hasConsent) return false
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
            // The held consent token couldn't be turned into a session and is
            // now useless. Drop it (and refresh the UI) so the next capture
            // re-prompts, instead of looping forever on a dead token that
            // still reads as hasConsent == true.
            onProjectionLost()
            return false
        }
        // The callback must be registered before createVirtualDisplay.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { onProjectionLost() }
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
        val dpi = service.resources.displayMetrics.densityDpi
        val newReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val oldReader = imageReader
        val vd = virtualDisplay
        if (vd == null) {
            // First use of this projection — build the VirtualDisplay around
            // the new ImageReader's surface.
            virtualDisplay = proj.createVirtualDisplay(
                "PlayTranslateCapture", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface, null, mainHandler,
            )
        } else {
            // Resolution changed (rotation / reconfig). API 34+ allows a
            // MediaProjection to create only ONE VirtualDisplay per token —
            // a second proj.createVirtualDisplay throws SecurityException
            // ("Cannot create more than one VirtualDisplay"). So reuse the
            // existing VirtualDisplay: resize it and swap its output Surface
            // to the new reader. setSurface first, then close the old reader
            // so the VD never targets a closed surface.
            vd.resize(w, h, dpi)
            vd.setSurface(newReader.surface)
        }
        imageReader = newReader
        readerW = w
        readerH = h
        oldReader?.close()
        return newReader
    }

    /** Pixel size of [displayId] in its current rotation — the resolution the
     *  capture [VirtualDisplay] + [ImageReader] are built at.
     *
     *  Sourced from [displaySizePx], the same window-context `WindowMetrics`
     *  query the overlays size off — so the captured frame and the overlay
     *  coordinate space are identical by construction. The pinhole detector
     *  ([com.playtranslate.FrameCoordinates]) assumes that identity scale.
     *  `displaySizePx` already reports post-rotation bounds, so no manual
     *  rotation adjustment is needed here. */
    private fun captureSize(displayId: Int): Pair<Int, Int>? {
        val dm = service.getSystemService(DisplayManager::class.java) ?: return null
        val display = dm.getDisplay(displayId) ?: return null
        val size = service.createDisplayContext(display).displaySizePx()
        return if (size.x > 0 && size.y > 0) size.x to size.y else null
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

    /** Drop the consent token without tearing down any live projection /
     *  VirtualDisplay / ImageReader. Used by the foreground-service-type
     *  catch in [CaptureService.enterForeground] when the platform rejects
     *  the mediaProjection FGS type: the consent that claimed the type is
     *  invalid (and the catch fires before getMediaProjection, so no live
     *  projection exists yet to tear down), so [hasConsent] should reflect
     *  that and the next capture attempt re-prompts cleanly. */
    fun invalidateConsent() {
        val hadConsent = resultData != null
        resultCode = Activity.RESULT_CANCELED
        resultData = null
        if (hadConsent) teardownListeners.toList().forEach { it() }
    }

    /** The projection is gone — stopped by the system or the user (a revoke /
     *  sleep teardown), or [getMediaProjection] failed to turn a held consent
     *  token into a session. Not our own [destroy]. Tear the session down,
     *  drop the now-ungated floating controls, and refresh the QS tile so the
     *  UI catches up with the lost consent. Always invoked on the main thread
     *  (the projection callback posts to [mainHandler]; the failure path is the
     *  capture path), so the main-thread-only reconcile is safe. */
    private fun onProjectionLost() {
        teardown()
        CaptureBackendResolver.activeOverlayUi?.reconcileFloatingIcons()
        PlayTranslateTileService.TileSync.refresh(service.applicationContext)
    }

    /** Release the projection and virtual display. */
    fun destroy() = teardown()
}
