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
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Display
import com.playtranslate.CaptureService
import com.playtranslate.DetectionLog
import com.playtranslate.PlayTranslateTileService
import com.playtranslate.Prefs
import com.playtranslate.displaySizePx
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import androidx.core.graphics.createBitmap

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

    // ── Frame stream: delivery seq + latest-frame latch ──────────────────
    //
    // The mirrored VirtualDisplay composites a frame into the ImageReader only
    // when the display content changes. An OnImageAvailableListener (on a
    // dedicated HandlerThread) latches the newest Image and advances a
    // monotonic delivery seq. Captures then serve the latched frame directly:
    // "latched frame + delivery silence since" IS the current screen, which
    // retires acquireBitmap's delay(64)/delay(48) freshness dance for raw
    // captures and gives clean captures a provable post-blank frame.
    //
    // Threading: the listener is the ONLY caller of acquireLatestImage (the
    // single-consumer rule — a second consumer would race it for frames).
    // Ownership of the latched Image transfers by AtomicReference.getAndSet:
    // whoever swaps a non-null frame out closes it, exactly once.

    private class LatchedFrame(val image: Image, val seq: Long)

    private val latch = AtomicReference<LatchedFrame?>(null)

    /** Monotonic delivery counter, doubling as the wake signal: collectors of
     *  the flow wake on every advance. Advanced by the listener per delivered
     *  frame, and once by [teardown] so a consumer parked in awaitSeqAfter
     *  re-checks its world instead of sleeping on a dead stream. */
    private val deliverySeq = MutableStateFlow(0L)

    /** Seq of the frame most recently served to a RAW capture caller. Clean
     *  captures deliberately don't advance this — see [DeliverySignal]. */
    @Volatile private var lastServedSeq = 0L

    @Volatile private var frameThread: HandlerThread? = null
    @Volatile private var frameHandler: Handler? = null

    // Step-0 characterization counters. Written on the frame thread
    // (deliveredTotal) and the capture path (served counts, serialized by the
    // capture source's mutex); read for the 5s summary on the frame thread.
    @Volatile private var deliveredTotal = 0L
    @Volatile private var rawServedCount = 0L
    @Volatile private var cleanServedCount = 0L
    private var summaryLastDelivered = 0L
    private var summaryMsSinceLog = 0L

    /** The delivery-signal surface handed to [MediaProjectionCaptureSource]. */
    val deliverySignal: DeliverySignal = object : DeliverySignal {
        override fun seqNow(): Long = deliverySeq.value
        override val lastServedSeq: Long
            get() = this@MediaProjectionController.lastServedSeq
        override suspend fun awaitSeqAfter(seq: Long) {
            deliverySeq.first { it > seq }
        }
    }

    /** Current delivery seq — the pre-blank marker clean captures pass to
     *  [captureFrameNewerThan]. */
    val deliverySeqNow: Long get() = deliverySeq.value

    private val frameListener = ImageReader.OnImageAvailableListener { reader ->
        val img = try {
            reader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            // Two documented causes, both transient here: the reader was
            // closed under us (teardown race), or maxImages is momentarily
            // exhausted (one claimed in-flight + one latched + this one).
            // Drop the frame WITHOUT advancing the seq — a frame nobody could
            // observe must not count as a delivery.
            null
        } ?: return@OnImageAvailableListener
        val seq = deliverySeq.updateAndGet { it + 1 }
        deliveredTotal++
        latch.getAndSet(LatchedFrame(img, seq))?.image?.close()
    }

    private fun ensureFrameHandler(): Handler {
        frameHandler?.let { return it }
        val t = HandlerThread("PtCaptureFrames").also { it.start() }
        frameThread = t
        val h = Handler(t.looper)
        frameHandler = h
        h.postDelayed(summaryRunnable, SUMMARY_INTERVAL_MS)
        return h
    }

    /** 5s delivery-rate summary while the stream is alive, debug-gated. Runs
     *  on the frame thread; the DetectionLog write is posted to main because
     *  its ring buffer is only ever touched from there. */
    private val summaryRunnable = object : Runnable {
        override fun run() {
            val total = deliveredTotal
            val delta = total - summaryLastDelivered
            summaryLastDelivered = total
            // Log when something happened this window, or once per minute as
            // a heartbeat proving the stream is alive-but-silent.
            summaryMsSinceLog += SUMMARY_INTERVAL_MS
            val heartbeat = summaryMsSinceLog >= 60_000L
            if ((delta > 0 || heartbeat) && Prefs(service).debugLiveMode) {
                summaryMsSinceLog = 0
                val line = "MP stream: +$delta deliveries/${SUMMARY_INTERVAL_MS / 1000}s " +
                    "(total=$total rawServed=$rawServedCount cleanServed=$cleanServedCount)"
                mainHandler.post { DetectionLog.log(line) }
            }
            frameHandler?.postDelayed(this, SUMMARY_INTERVAL_MS)
        }
    }

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
     * Capture one frame of the projected display ([projectedDisplayId]) at its
     * current resolution. Lazily establishes the projection + virtual display,
     * but NOT consent — consent must already be held (see [ensureConsent]);
     * returns null without prompting if it isn't. Returns null on any capture
     * failure too. Call on the main thread — the heavy pixel copy is moved off
     * it internally.
     *
     * Serves the latched frame when one exists — the most recent composition
     * the mirror delivered, which delivery silence proves is still current —
     * with no freshness delay. Falls back to awaiting the first delivery
     * (bounded by [FRESHNESS_BUDGET_MS]) right after VirtualDisplay creation.
     */
    suspend fun captureFrame(): Bitmap? = captureNewerThan(minSeq = 0L, advanceCursor = true)

    /**
     * Clean-capture variant: serve only a frame delivered AFTER [minSeq] —
     * the seq the caller observed BEFORE blanking its overlays, so the
     * blank's own repaint always qualifies (anchoring after the blank
     * starved static screens; 2026-07-10 review finding).
     *
     * A qualifying delivery is necessary but not sufficient: a game frame
     * composited just before the blank can be delivered just after the
     * anchor. Deliveries are composition-ordered on a single stream, so the
     * fix is to DRAIN: after each qualifying claim, wait one quiet window
     * ([DRAIN_QUIET_MS]) for anything newer and re-claim until the stream
     * goes quiet or the budget expires. Once the blank's repaint has been
     * delivered — guaranteed, since blanking a visible window changes the
     * screen — the newest delivery is blank-inclusive, and on static
     * screens the repaint is the only delivery, so the first quiet window
     * ends the drain.
     *
     * On budget expiry this FAILS (null) rather than serving a frame that
     * cannot be proven post-blank — a contaminated "clean" capture poisons
     * OCR/baselines silently, which is strictly worse than a retryable
     * failure. Callers with nothing blanked should use
     * [captureFrameUngated] instead of paying the gate at all.
     *
     * Does not advance the raw-consumer cursor (see [DeliverySignal]).
     */
    suspend fun captureFrameNewerThan(minSeq: Long): Bitmap? =
        captureNewerThan(minSeq, advanceCursor = false)

    /** Current frame with no freshness gate and no raw-cursor advance — the
     *  clean-capture path for "nothing was blanked": the frame provably
     *  cannot contain this backend's overlays, and no blank repaint is
     *  coming, so gating would only burn the budget. Distinct from
     *  [captureFrame], which advances the live delivery-gate cursor and
     *  would eat a parked live cycle's pending wake. */
    suspend fun captureFrameUngated(): Bitmap? =
        captureNewerThan(minSeq = 0L, advanceCursor = false)

    private suspend fun captureNewerThan(minSeq: Long, advanceCursor: Boolean): Bitmap? {
        if (!ensureProjection()) return null
        val (w, h) = captureSize(projectedDisplayId) ?: return null
        ensureVirtualDisplay(w, h) ?: return null

        val frame: LatchedFrame? = withTimeoutOrNull(FRESHNESS_BUDGET_MS) {
            var claimed: LatchedFrame? = null
            while (claimed == null) {
                val observed = deliverySeq.value
                val f = tryClaim(w, h)
                if (f != null && f.seq > minSeq) {
                    claimed = f
                } else {
                    f?.image?.close() // stale for this caller — discard, wait on
                    deliverySeq.first { it > observed }
                }
            }
            if (minSeq > 0) {
                // Drain to the newest delivery (see captureFrameNewerThan
                // kdoc). Each round waits one quiet window; a newer arrival
                // restarts it. Bounded by the enclosing budget.
                while (true) {
                    val newerSeq = withTimeoutOrNull(DRAIN_QUIET_MS) {
                        deliverySeq.first { it > claimed!!.seq }
                    } ?: break
                    val newer = tryClaim(w, h)
                    if (newer != null && newer.seq >= newerSeq) {
                        claimed!!.image.close()
                        claimed = newer
                    } else {
                        newer?.image?.close()
                    }
                }
            }
            claimed
        }
        if (frame == null && minSeq > 0) {
            Log.w(
                TAG,
                "clean capture FAILED: no post-blank delivery in " +
                    "${FRESHNESS_BUDGET_MS}ms (anchor=$minSeq, latest=${deliverySeq.value})"
            )
        }
        val claimed = frame ?: return null
        beginDecode()
        return try {
            withContext(Dispatchers.Default) { imageToBitmap(claimed.image, w, h) }.also {
                if (advanceCursor) {
                    lastServedSeq = maxOf(lastServedSeq, claimed.seq)
                    rawServedCount++
                } else {
                    cleanServedCount++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "imageToBitmap failed: ${e.message}")
            null
        } finally {
            claimed.image.close()
            endDecode()
        }
    }

    // ── Decode-vs-close serialization (review finding) ───────────────────
    //
    // imageToBitmap copies the claimed Image's buffer on Dispatchers.Default.
    // Captures hold the source's captureMutex, but teardown() (projection
    // revoke posts onProjectionLost to main) does not — ImageReader.close()
    // while the copy is mid-buffer is a native use-after-free, not a
    // catchable exception. Reader closes therefore defer while a decode is
    // in flight and complete on the decoding thread when it finishes. The
    // resize-swap close can't actually race (it runs under the same mutex as
    // every decode) but routes through the same guard for uniformity.

    private val decodeLock = Any()
    private var decodesInFlight = 0
    private val deferredReaderCloses = mutableListOf<ImageReader>()

    private fun beginDecode() {
        synchronized(decodeLock) { decodesInFlight++ }
    }

    private fun endDecode() {
        var toClose: List<ImageReader>? = null
        synchronized(decodeLock) {
            decodesInFlight--
            if (decodesInFlight == 0 && deferredReaderCloses.isNotEmpty()) {
                toClose = deferredReaderCloses.toList()
                deferredReaderCloses.clear()
            }
        }
        toClose?.forEach { it.close() }
    }

    /** Close [reader] now, or after the in-flight decode finishes. */
    private fun closeReaderSafely(reader: ImageReader?) {
        reader ?: return
        val closeNow: Boolean
        synchronized(decodeLock) {
            closeNow = decodesInFlight == 0
            if (!closeNow) deferredReaderCloses.add(reader)
        }
        if (closeNow) reader.close()
    }

    /** Claim the latch if it holds a frame matching the current capture size.
     *  A mismatched frame (pre-resize straggler) is closed and dropped —
     *  serving it would feed imageToBitmap a buffer of the wrong geometry.
     *  A frame whose reader was closed under it (late straggler swept by a
     *  swap) throws on the size read and is treated as no-frame. */
    private fun tryClaim(w: Int, h: Int): LatchedFrame? {
        val f = latch.getAndSet(null) ?: return null
        val matches = try {
            f.image.width == w && f.image.height == h
        } catch (e: IllegalStateException) {
            false
        }
        if (matches) return f
        try { f.image.close() } catch (_: Exception) {}
        return null
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
            null
        }
        if (proj == null) {
            // The held consent token couldn't be turned into a session and is
            // now useless. Drop it (and refresh the UI) so the next capture
            // re-prompts, instead of looping forever on a dead token that
            // still reads as hasConsent == true. getMediaProjection returns
            // nullable on API 35+ (the signature was annotated nullable in
            // compileSdk 35); pre-35 it only signaled failure via exception.
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
        // If startActivity throws (e.g. a future BAL tightening blocks the
        // launch, or some OEM-specific restriction kicks in), the exception
        // would otherwise leave consentGate set on a never-completed gate,
        // wedging every subsequent ensureConsent caller on it.await(). Clear
        // the field and complete the gate=false so concurrent waiters return
        // cleanly and the NEXT activate attempt can install a fresh gate.
        try {
            MediaProjectionConsentActivity.launch(service)
        } catch (e: Exception) {
            Log.e(TAG, "MediaProjectionConsentActivity launch failed: ${e.message}")
            consentGate = null
            gate.complete(false)
            return false
        }
        return gate.await()
    }

    private fun ensureVirtualDisplay(w: Int, h: Int): ImageReader? {
        val proj = projection ?: return null
        imageReader?.let { if (readerW == w && readerH == h) return it }
        val dpi = service.resources.displayMetrics.densityDpi
        // maxImages = 3: one latched + one claimed in-flight by a capture +
        // one for the listener's acquireLatestImage swap moment. At 2 the
        // producer stalls (or the listener throws) whenever a capture holds a
        // claimed frame while a new delivery lands.
        val newReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        // Register the frame listener before the reader's surface is wired
        // into the VirtualDisplay so the very first composited frame is
        // latched rather than lost.
        newReader.setOnImageAvailableListener(frameListener, ensureFrameHandler())
        val oldReader = imageReader
        val vd = virtualDisplay
        // Android 15 (targetSdk ≥ 35) enforces stricter MediaProjection token
        // staleness — a token that getMediaProjection succeeded on can still
        // throw at createVirtualDisplay time, and the resize/setSurface
        // reuse branch can throw IllegalStateException / IllegalArgumentException
        // on a VirtualDisplay the platform has released out from under us.
        // Mirror the getMediaProjection catch above: broad Exception, log,
        // tear down so the next attempt re-prompts cleanly instead of
        // looping on a dead session.
        try {
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
        } catch (e: Exception) {
            Log.e(TAG, "VirtualDisplay creation/update failed: ${e.message}")
            // newReader was allocated before the try block and never installed
            // — close it explicitly so a failed setup doesn't leak the reader.
            newReader.close()
            onProjectionLost()
            return null
        }
        imageReader = newReader
        readerW = w
        readerH = h
        // Straggler discipline on the swap (review finding): detach the old
        // reader's listener so late old-reader frames can't re-latch, close
        // the reader, and only THEN sweep the latch — a straggler latched
        // between a sweep and the close would otherwise survive as an
        // invalid Image. (No-op on first create.)
        oldReader?.setOnImageAvailableListener(null, null)
        closeReaderSafely(oldReader)
        latch.getAndSet(null)?.let { try { it.image.close() } catch (_: Exception) {} }
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

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        // A row-padded buffer needs a wider bitmap; crop back to width after.
        val padded = createBitmap(
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
        imageReader?.setOnImageAvailableListener(null, null)
        closeReaderSafely(imageReader)
        imageReader = null
        readerW = 0
        readerH = 0
        // Latch cleanup AFTER the reader closes: a listener invocation racing
        // this teardown can re-latch right up until the reader is closed, so
        // clearing first could leak that late frame. Closing an Image whose
        // reader is already closed can itself throw on some builds — the
        // buffers are freed with the reader either way, so swallow it.
        latch.getAndSet(null)?.let { try { it.image.close() } catch (_: Exception) {} }
        // Advance the seq once so anything suspended in awaitSeqAfter wakes,
        // re-checks its capture source, and discovers the session is gone —
        // instead of sleeping forever on a stream that will never deliver.
        deliverySeq.updateAndGet { it + 1 }
        frameThread?.quitSafely()
        frameThread = null
        frameHandler = null
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
     *  drop every overlay this backend owns (a drag/lookup in flight at the
     *  moment of loss can otherwise leave the magnifier lens, region
     *  indicator, translation boxes, or floating menu orphaned on screen —
     *  reconcileFloatingIcons only knew about the floating icon), and refresh
     *  the QS tile so the UI catches up with the lost consent. Always
     *  invoked on the main thread (the projection callback posts to
     *  [mainHandler]; the failure path is the capture path), so the
     *  main-thread-only hideAll is safe. */
    private fun onProjectionLost() {
        teardown()
        CaptureBackendResolver.activeOverlayUi?.hideAll()
        PlayTranslateTileService.TileSync.refresh(service.applicationContext)
    }

    /** Release the projection and virtual display. */
    fun destroy() = teardown()

    private companion object {
        /** Bound on waiting for a delivery when the latch is empty (first
         *  capture after VD creation) or a clean capture awaits its post-blank
         *  frame. Sized to the legacy delay(64)+delay(48) freshness budget the
         *  old poll path allowed, so availability semantics don't regress. */
        const val FRESHNESS_BUDGET_MS = 112L

        /** Clean-capture drain: how long the delivery stream must stay quiet
         *  after a qualifying frame before it's accepted as the newest. One
         *  60Hz composite interval (16.7ms) plus margin — long enough that a
         *  blank repaint following an in-flight pre-blank frame lands inside
         *  the window and displaces it; short enough that a static screen
         *  (repaint already claimed, stream silent) adds only this much to a
         *  one-shot. */
        const val DRAIN_QUIET_MS = 24L

        /** Cadence of the debug delivery-rate summary. */
        const val SUMMARY_INTERVAL_MS = 5_000L
    }
}
