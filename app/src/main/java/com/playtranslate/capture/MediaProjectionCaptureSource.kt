package com.playtranslate.capture

import android.graphics.Bitmap
import android.view.Choreographer
import com.playtranslate.CaptureService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * [CaptureSource] backed by MediaProjection. Implements only the one-shot
 * clean-capture surface — it is deliberately NOT a [LiveCaptureSource], so
 * `CaptureService.startLive()` reports live mode as unsupported on this
 * backend (a compile-time guarantee, not a runtime flag).
 */
class MediaProjectionCaptureSource(
    private val controller: MediaProjectionController,
) : CaptureSource {

    override suspend fun requestClean(displayId: Int): Bitmap? {
        // Blank this backend's overlays so they don't appear in the mirror,
        // wait for the compositor to flush, capture, then restore.
        val host = CaptureBackendResolver.active().overlayHost
        val state = host?.prepareForCleanCapture(displayId)
        return try {
            waitVsync(2)
            controller.captureFrame(displayId)
        } finally {
            if (host != null && state != null) host.restoreAfterCapture(state)
        }
    }

    override fun saveToCache(bitmap: Bitmap, displayId: Int): String? {
        val ctx = CaptureService.instance ?: return null
        return CaptureCache.save(ctx, bitmap, displayId)
    }

    override fun destroy() = controller.destroy()

    private suspend fun waitVsync(frames: Int) {
        repeat(frames) {
            suspendCancellableCoroutine<Unit> { cont ->
                Choreographer.getInstance().postFrameCallback {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }
}
