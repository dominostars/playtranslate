package com.playtranslate.capture

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope

/**
 * A captured frame plus the immutable facts read at the moment the SOURCE
 * served it. The facts travel ON the data — downstream code must never
 * re-derive them from mutable state (the active backend, a source's live
 * properties, the current stream kind), because a bitmap outlives the state
 * that produced it: it crosses suspension points, gets saved to the
 * screenshot cache, and re-enters OCR days later through re-OCR. Review
 * rounds 6–9 were each one edge of that lifetime discovered separately;
 * this type closes the family by construction.
 *
 * Ownership: exactly the old `Bitmap` rules. Whoever received the frame
 * recycles it ([recycle]); a frame derived from a mutated copy ([derive])
 * has its own independent bitmap lifetime — deriving never touches the
 * parent.
 */
class CapturedFrame(
    val bitmap: Bitmap,
    /** Whether the frame contains system UI (status/nav bars). Accessibility
     *  screenshots and whole-display mirrors do; an API 34+ single-app
     *  ("task") mirror does not — so the OCR status-bar crop, which exists
     *  to keep the clock/battery glyphs out of OCR, would instead eat game
     *  content on such frames. Stamped by the source at serve time. */
    val includesSystemUi: Boolean,
    /** Uptime when the source served the frame — the anchor time-based
     *  consumers (e.g. [com.playtranslate.StabilityHold]'s cap) should use
     *  instead of reading a clock around their capture call. */
    val capturedAtMs: Long = SystemClock.uptimeMillis(),
) {
    /** Same facts, different pixels — for pipeline stages that OCR a mutated
     *  COPY of the frame (overlay-region fills, clean-ref patches). The
     *  derived frame owns [newBitmap]; the parent's bitmap is untouched. */
    fun derive(newBitmap: Bitmap) = CapturedFrame(newBitmap, includesSystemUi, capturedAtMs)

    fun recycle() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    val isRecycled: Boolean get() = bitmap.isRecycled
    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height
}

/**
 * Backend-agnostic screen-capture surface used by every one-shot consumer
 * (tap-to-translate, hold-to-preview, drag-to-lookup, region capture).
 *
 * A consumer never learns whether the active backend is the accessibility
 * service or MediaProjection — it asks the resolved [CaptureBackend] for a
 * [CaptureSource] and calls [requestClean]. Backend selection lives solely in
 * [CaptureBackendResolver].
 */
interface CaptureSource {

    /** Do this source's frames contain system UI (status/nav bars) RIGHT
     *  NOW? The source's own fact, used by implementations to stamp
     *  [CapturedFrame.includesSystemUi] at serve time. Consumers must read
     *  the stamped frame, never this live property — it can change between
     *  a frame's capture and its use (stream-kind resets/demotions). */
    val framesIncludeSystemUi: Boolean get() = true

    /** Capture a clean frame of [displayId] with the app's own overlays
     *  hidden. The caller owns the returned frame and must recycle it.
     *  Returns null if the capture could not be taken. */
    suspend fun requestClean(displayId: Int): CapturedFrame?

    /** Persist [bitmap] to the screenshot cache, keyed per display. Returns
     *  the absolute file path, or null on failure. (Pixels only — a saved
     *  frame's FACTS persist in OcrProvenance alongside the result.) */
    fun saveToCache(bitmap: Bitmap, displayId: Int): String?

    /** Release backend resources. */
    fun destroy()
}

/**
 * Frame-delivery signal exposed by streaming capture backends.
 *
 * MediaProjection mirrors the display into a persistent VirtualDisplay whose
 * compositor queues a frame into the backing ImageReader only when the display
 * content actually changes. That makes "a delivery happened" a cheap "the
 * screen changed" signal — and, crucially, delivery *silence* means the frame
 * most recently served is still what's on screen.
 *
 * Contract:
 *  - [seqNow] is monotonically non-decreasing. It advances on every delivered
 *    frame AND once on session teardown, so a consumer suspended in
 *    [awaitSeqAfter] always wakes when the projection dies (and can re-resolve
 *    its capture source) instead of hanging forever.
 *  - [lastServedSeq] is the seq of the frame most recently handed to a *raw*
 *    capture caller — the "what the consumer last saw" cursor. Clean captures
 *    do not advance it: they serve one-shot consumers, and marking their
 *    deliveries as "seen" could hide a change from the live-mode cycle.
 *  - [awaitSeqAfter] suspends until `seqNow() > seq`. Cancellable.
 *
 * Backends without a frame stream (accessibility `takeScreenshot`) leave
 * [LiveCaptureSource.deliverySignal] null; consumers must treat null as "no
 * silence evidence available" and poll as before.
 */
interface DeliverySignal {
    fun seqNow(): Long
    val lastServedSeq: Long
    suspend fun awaitSeqAfter(seq: Long)
}

/**
 * A [CaptureSource] that can additionally drive the continuous raw/clean
 * frame loop that live mode depends on.
 *
 * Backends that cannot stream frames implement only [CaptureSource], not this.
 * `CaptureService.startLive()` checks `CaptureBackend.supportsLiveMode` and
 * surfaces a user-facing error when the active backend lacks live capability,
 * so live-mode callers never branch on the backend themselves.
 */
interface LiveCaptureSource : CaptureSource {
    /** Frame-delivery signal, or null when this backend cannot observe
     *  per-frame deliveries (see [DeliverySignal]). */
    val deliverySignal: DeliverySignal? get() = null

    /** Minimum interval the capture loop must respect. The accessibility
     *  backend enforces the platform `takeScreenshot` rate limit; the
     *  MediaProjection backend has no platform limit and uses a small floor. */
    val minCaptureIntervalMs: Long

    suspend fun requestRaw(displayId: Int, onCaptured: (() -> Unit)? = null): CapturedFrame?
    fun startLoop(
        displayId: Int,
        scope: CoroutineScope,
        onCleanFrame: (CapturedFrame) -> Unit,
        onRawFrame: (CapturedFrame) -> Unit,
    )
    fun requestCleanCapture(displayId: Int)
    fun requestCleanCaptureAll()
    fun stopLoop(displayId: Int)
    fun stopAllLoops()
    fun isLoopRunning(displayId: Int): Boolean
    val hasAnyLoop: Boolean
}
