package com.playtranslate.capture

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope

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
    /** Capture a clean frame of [displayId] with the app's own overlays
     *  hidden. The caller owns the returned bitmap and must recycle it.
     *  Returns null if the capture could not be taken. */
    suspend fun requestClean(displayId: Int): Bitmap?

    /** Persist [bitmap] to the screenshot cache, keyed per display. Returns
     *  the absolute file path, or null on failure. */
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

    suspend fun requestRaw(displayId: Int, onCaptured: (() -> Unit)? = null): Bitmap?
    fun startLoop(
        displayId: Int,
        scope: CoroutineScope,
        onCleanFrame: (Bitmap) -> Unit,
        onRawFrame: (Bitmap) -> Unit,
    )
    fun requestCleanCapture(displayId: Int)
    fun requestCleanCaptureAll()
    fun stopLoop(displayId: Int)
    fun stopAllLoops()
    fun isLoopRunning(displayId: Int): Boolean
    val hasAnyLoop: Boolean
}
