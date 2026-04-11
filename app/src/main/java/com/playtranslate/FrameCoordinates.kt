package com.playtranslate

import android.graphics.Rect
import android.util.Log

/**
 * Coordinate mapping for a capture cycle.
 *
 * Owns conversions between three coordinate spaces used by the live-mode
 * capture/detection pipeline:
 *
 *  - **Screen/view space** — from [android.view.View.getLocationOnScreen]
 *    and [com.playtranslate.ui.TranslationOverlayView.getChildScreenRects].
 *  - **Bitmap space** — pixel indices into the raw / cleanRef / overlayBitmap
 *    bitmaps returned by [android.accessibilityservice.AccessibilityService.takeScreenshot].
 *  - **OCR-crop space** — OCR output from ML Kit, relative to the cropped
 *    bitmap that was fed into OCR. Adding [cropLeft] / [cropTop] converts it
 *    back to bitmap space.
 *
 * In the common case, all three spaces are numerically identical (scale 1.0)
 * because accessibility-based `takeScreenshot` returns a bitmap at the same
 * resolution as the overlay view. This class detects that case and
 * short-circuits its conversions; callers don't pay an allocation cost at
 * identity scale.
 *
 * ## Scope — what this class delivers
 *
 * - Centralization: one place to look up view↔bitmap and ocr-crop↔bitmap
 *   coordinate conversions. Call sites are self-documenting about which
 *   coordinate space a rect lives in.
 * - Runtime detection: if ever constructed with mismatched bitmap and view
 *   dims, logs once per process via [logNonIdentityOnce] so a deployed build
 *   has a diagnostic signal.
 * - Math: at identity scale everything passes through unchanged; at
 *   non-identity scale [viewToBitmap] applies the scale factor (with
 *   `.toInt()` truncation that may clip 1px at right/bottom edges).
 *
 * ## Scope — what this class does NOT deliver
 *
 * **Non-identity scale is NOT a supported configuration for pinhole
 * detection.** [com.playtranslate.PinholeOverlayMode.runCycle] fails closed
 * at non-identity scale before entering its classification and pinhole
 * detection phases, because those phases depend on assumptions that do not
 * hold under screenshot-vs-view resolution mismatch. A partial attempt was
 * made to scale `overlayBitmap` into bitmap dimensions via a canvas-scaled
 * `renderToOffscreen`; it was reverted after it became clear the underlying
 * pinhole math is what's actually broken, not just the coordinate plumbing.
 *
 * The specific reasons pinhole detection breaks at non-identity scale:
 *
 *   1. **The pinhole mask is generated at view resolution.** See
 *      [com.playtranslate.ui.TranslationOverlayView.createPinholeMask]. The
 *      mask's 3-pixel spacing is in view coordinates. Sampling every 3rd
 *      bitmap pixel no longer hits real pinhole positions when view and
 *      bitmap don't align.
 *   2. **The `predicted = (cleanRef + overlay) / 2` blend math assumes a
 *      50/50 pixel exists.** In the downsampling case (bitmap smaller than
 *      view, e.g. MediaProjection virtual display), each bitmap pixel is an
 *      average of multiple view pixels, most of which are non-pinhole. The
 *      averaged alpha ≈ 87% overlay uniformly. No bitmap pixel is at 50%
 *      blend, so `predicted` never matches `raw` and the detector over-
 *      flags every position as changed.
 *   3. **The detection thresholds
 *      ([com.playtranslate.PinholeOverlayMode.SPLATTER_THRESHOLD] etc.) are
 *      calibrated against the 50/50 blend assumption** and would need to
 *      be re-tuned for any new scheme.
 *
 * Supporting non-identity scale would require a pinhole pattern that
 * survives downsampling (larger mask elements, or a bitmap-resolution mask
 * generated independently of the view), a reworked `checkPinholes`
 * implementation, and fresh threshold tuning. That's a design effort, not
 * a mechanical refactor, and it's explicitly out of scope for the work
 * this class was introduced as part of.
 *
 * **FuriganaMode** is scale-agnostic — its raw-frame patching is a bulk
 * region copy, not a per-pixel blend — so it keeps running at non-identity
 * scale. See `FuriganaMode.handleRawFrame` for the asymmetry note.
 *
 * ## For future maintainers
 *
 * - If you see `FrameCoordinates: Non-identity scale detected` in logcat on
 *   a device we expected to be identity, something changed in the capture
 *   path (OEM takeScreenshot quirk, mid-session display reconfig, a new
 *   feature that uses MediaProjection). Investigate before anything else,
 *   because pinhole detection will be silent (fail-closed).
 * - If you want to actually support non-identity scale: start by rewriting
 *   the pinhole pattern / mask generation / detection math, then remove the
 *   fail-closed check in [com.playtranslate.PinholeOverlayMode.runCycle].
 *   Don't remove the fail-closed before reworking the math.
 * - The [scaleX] / [scaleY] fields and the `viewToBitmap` conversion are
 *   intentionally kept even though nothing currently uses them at non-
 *   identity scale. They're useful documentation of the assumption and
 *   provide a hook for future non-identity support.
 */
class FrameCoordinates(
    val bitmapWidth: Int,
    val bitmapHeight: Int,
    val viewWidth: Int,
    val viewHeight: Int,
    val cropLeft: Int,
    val cropTop: Int,
) {
    // Validation runs before any property initializers.
    init {
        require(bitmapWidth > 0 && bitmapHeight > 0) {
            "bitmap dims must be positive: ${bitmapWidth}x${bitmapHeight}"
        }
    }

    /** Bitmap/view ratio. 1.0 at the only currently supported case. */
    val scaleX: Float = if (viewWidth > 0) bitmapWidth.toFloat() / viewWidth else 1f
    val scaleY: Float = if (viewHeight > 0) bitmapHeight.toFloat() / viewHeight else 1f

    /** True when view dims exactly match bitmap dims (scale 1.0). Treats
     *  zero view dims as identity so callers can pass 0 when the overlay
     *  view isn't attached or laid out yet — e.g., on the very first cycle
     *  before `showOverlayAndCapture` has ever created the view. */
    val isIdentityScale: Boolean =
        (viewWidth == 0 || bitmapWidth == viewWidth) &&
        (viewHeight == 0 || bitmapHeight == viewHeight)

    // Second init block runs AFTER the property initializers above, so
    // isIdentityScale has been assigned by the time this executes. Kotlin
    // runs init blocks and property initializers in declaration order;
    // putting the warning check here is load-bearing to avoid reading
    // isIdentityScale before it's initialized.
    init {
        if (!isIdentityScale) logNonIdentityOnce(this)
    }

    /**
     * Convert a view-space (screen) rect to bitmap pixel coordinates.
     *
     * At identity scale (the only currently supported case), returns the
     * input [Rect] by reference — no allocation. Callers can compare result
     * and input with `===` to detect the short-circuit.
     *
     * Rounding: uses `.toInt()` truncation at non-identity scale, which
     * can clip one pixel row/column at the right/bottom edge. Acceptable
     * for pinhole / furigana detection where a 1 px difference doesn't
     * affect classification.
     */
    fun viewToBitmap(rect: Rect): Rect =
        if (isIdentityScale) rect
        else Rect(
            (rect.left * scaleX).toInt(),
            (rect.top * scaleY).toInt(),
            (rect.right * scaleX).toInt(),
            (rect.bottom * scaleY).toInt(),
        )

    /**
     * Batch-convert a list of view-space rects. Maps each via [viewToBitmap].
     *
     * At identity scale, the returned list contains the same [Rect] instances
     * as the input — per-cycle cost is one `ArrayList` allocation with N
     * pointers (typically < 10). At non-identity scale, each entry is a
     * fresh [Rect].
     */
    fun viewListToBitmap(rects: List<Rect>): List<Rect> =
        rects.map { viewToBitmap(it) }

    /**
     * Convert an OCR-crop-space rect (e.g. from
     * `OcrPipelineResult.ocrResult.groupBounds`) to bitmap space by adding
     * the crop offsets. This is always an allocation; callers that need
     * many conversions should batch.
     */
    fun ocrToBitmap(ocrRect: Rect): Rect = Rect(
        ocrRect.left + cropLeft, ocrRect.top + cropTop,
        ocrRect.right + cropLeft, ocrRect.bottom + cropTop,
    )

    companion object {
        private const val TAG = "FrameCoordinates"

        @Volatile private var warnedNonIdentity: Boolean = false

        private fun logNonIdentityOnce(fc: FrameCoordinates) {
            if (warnedNonIdentity) return
            warnedNonIdentity = true
            Log.w(
                TAG,
                "Non-identity scale detected: bitmap=${fc.bitmapWidth}x${fc.bitmapHeight}, " +
                    "view=${fc.viewWidth}x${fc.viewHeight}. Rect conversions apply the scale " +
                    "factor; callers must ensure view-sourced bitmaps (overlayBitmap via " +
                    "renderToOffscreen) are rendered at bitmap dimensions."
            )
        }

        /** Reset the log-once flag. Test-only. */
        internal fun resetWarnedForTests() {
            warnedNonIdentity = false
        }
    }
}
