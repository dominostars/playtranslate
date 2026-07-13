package com.playtranslate.camera

import android.graphics.Rect
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Maps between the camera pipeline's coordinate spaces (see the camera plan):
 *
 *  - **AnalysisUpright (AU)** — the analysis frame rotated upright. OCR
 *    results, the keyframe bitmap, and overlay [com.playtranslate.ui.TextBox]
 *    bounds all live here.
 *  - **View (V)** — [androidx.camera.view.PreviewView] pixels.
 *
 * The preview is displayed with FILL_CENTER: the AU frame is uniformly scaled
 * to cover the view and center-cropped. Because the Preview and ImageAnalysis
 * use cases are bound with the same aspect ratio, both streams share a FOV and
 * this transform is fully determined by the two sizes. It also assumes the
 * preview is UNMIRRORED — true only for the back camera (PreviewView mirrors
 * front-camera previews while analysis frames stay unmirrored), which is why
 * the tool binds the back camera exclusively.
 *
 * Pure math — no Android camera types — so it is JVM-unit-testable.
 */
class CameraCoordinates(
    val auWidth: Int,
    val auHeight: Int,
    val viewWidth: Int,
    val viewHeight: Int,
) {
    /** Uniform FILL_CENTER scale factor (AU pixels → view pixels). */
    val scale: Float = max(
        viewWidth.toFloat() / auWidth,
        viewHeight.toFloat() / auHeight,
    )

    /** Horizontal offset of the scaled AU frame's left edge in view coords
     *  (≤ 0 when the frame is cropped left/right). */
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
