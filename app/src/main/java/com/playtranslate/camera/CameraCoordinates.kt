package com.playtranslate.camera

import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Maps between the camera pipeline's coordinate spaces (see the camera plan):
 *
 *  - **AnalysisUpright (AU)** — the analysis frame rotated upright. OCR
 *    results, the keyframe bitmap, and overlay [com.playtranslate.ui.TextBox]
 *    bounds all live here.
 *  - **View (V)** — [androidx.camera.view.PreviewView] pixels.
 *
 * With [FitMode.FILL] the AU frame is uniformly scaled to cover the view and
 * center-cropped, matching a FILL_CENTER preview / centerCrop ImageView.
 * Because the Preview and ImageAnalysis use cases are bound with the same
 * aspect ratio, both streams share a FOV and this transform is fully
 * determined by the two sizes. It also assumes the preview is UNMIRRORED —
 * true only for the back camera (PreviewView mirrors front-camera previews
 * while analysis frames stay unmirrored), which is why the tool binds the
 * back camera exclusively.
 *
 * With [FitMode.FIT] the frame is uniformly scaled to fit INSIDE the view and
 * letterboxed, matching a fitCenter ImageView — the import-image review,
 * where the frame is an arbitrary-aspect picked image that must never be
 * cropped. Same offset formulas: they come out ≥ 0 instead of ≤ 0.
 *
 * Pure math — no Android camera types — so it is JVM-unit-testable.
 */
class CameraCoordinates(
    val auWidth: Int,
    val auHeight: Int,
    val viewWidth: Int,
    val viewHeight: Int,
    val mode: FitMode = FitMode.FILL,
) {
    enum class FitMode { FILL, FIT }

    /** Uniform scale factor (AU pixels → view pixels): cover for FILL,
     *  contain for FIT. */
    val scale: Float = when (mode) {
        FitMode.FILL -> max(
            viewWidth.toFloat() / auWidth,
            viewHeight.toFloat() / auHeight,
        )
        FitMode.FIT -> min(
            viewWidth.toFloat() / auWidth,
            viewHeight.toFloat() / auHeight,
        )
    }

    /** Horizontal offset of the scaled AU frame's left edge in view coords
     *  (≤ 0 when the frame is cropped left/right, ≥ 0 when letterboxed). */
    val offsetX: Float = (viewWidth - auWidth * scale) / 2f

    /** Vertical offset of the scaled AU frame's top edge in view coords. */
    val offsetY: Float = (viewHeight - auHeight * scale) / 2f

    /** Map an AU-space rect to view space. */
    fun auToView(r: Rect): Rect = Rect(
        (r.left * scale + offsetX).roundToInt(),
        (r.top * scale + offsetY).roundToInt(),
        (r.right * scale + offsetX).roundToInt(),
        (r.bottom * scale + offsetY).roundToInt(),
    )

    /** Map a view-space rect back to AU space (inverse of [auToView]). */
    fun viewToAu(r: Rect): Rect = Rect(
        ((r.left - offsetX) / scale).roundToInt(),
        ((r.top - offsetY) / scale).roundToInt(),
        ((r.right - offsetX) / scale).roundToInt(),
        ((r.bottom - offsetY) / scale).roundToInt(),
    )
}
