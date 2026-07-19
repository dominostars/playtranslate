package com.playtranslate.camera

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure zoom/pan state for the frozen review surfaces (camera snapshot,
 * import review): a uniform view-space transform `screen = zoom * p + pan`
 * over FIT-SPACE coordinates — the on-screen-at-rest positions the plain
 * [CameraCoordinates] projection produces. Identity at fit by construction,
 * which is the whole regression story: while [isAtFit], every consumer
 * receives null/identity and behaves byte-identically to a build without
 * zoom.
 *
 * CEILING-BASED: the max zoom derives from the content. `nativeCeiling =
 * 1/fitScale` is the zoom at which one image pixel maps to one screen pixel.
 * Per-tool policy:
 *  - [CeilingPolicy.IMPORT] (letterboxed FIT content): capped at native —
 *    upscaling a screenshot past 1:1 just blurs pixels. An image already
 *    displayed at/near native (ceiling below [COLLAPSE_THRESHOLD]) collapses
 *    to 1 = zoom disabled, the user-decided design.
 *  - [CeilingPolicy.CAMERA] (cover-cropped FILL photos): the frame displays
 *    at/above native by construction, so a native cap would leave no range;
 *    photographic content upscales acceptably and the overlay boxes
 *    re-raster crisp regardless — floor the ceiling at [PHOTO_MIN].
 *
 * No Android types — plain-JUnit testable. The fit-scale/offset formulas
 * deliberately replicate [CameraCoordinates]' three lines rather than
 * importing it (that class touches android.graphics.Rect).
 */
class ReviewZoom(private val policy: CeilingPolicy) {

    enum class CeilingPolicy { IMPORT, CAMERA }

    var zoom = 1f
        private set
    var panX = 0f
        private set
    var panY = 0f
        private set

    var ceiling = 1f
        private set

    private var viewW = 0f
    private var viewH = 0f

    /** Fit-space content edges: where the image's corners sit on screen at
     *  rest (FIT: inside the viewport, letterboxed; FILL: at/outside it,
     *  cover-cropped). */
    private var contentLeft = 0f
    private var contentTop = 0f
    private var contentRight = 0f
    private var contentBottom = 0f

    /** True when a pinch would actually do something; false = the gesture
     *  layer stays a pure passthrough. */
    val zoomEnabled: Boolean get() = ceiling > 1f + FIT_EPSILON

    /** At the resting fit view — lookup drags run, and every transform
     *  consumer receives identity/null. */
    val isAtFit: Boolean get() = abs(zoom - 1f) < FIT_EPSILON

    /**
     * Bind to a scene: content [auWidth]x[auHeight] displayed in a
     * [viewWidth]x[viewHeight] viewport under the policy's display mode
     * (IMPORT = contain/min, CAMERA = cover/max — matching the tools'
     * fitCenter / centerCrop image views). Resets to fit.
     */
    fun configure(auWidth: Int, auHeight: Int, viewWidth: Int, viewHeight: Int) {
        viewW = viewWidth.toFloat()
        viewH = viewHeight.toFloat()
        if (auWidth <= 0 || auHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            ceiling = 1f
            reset()
            return
        }
        val sx = viewW / auWidth
        val sy = viewH / auHeight
        val fitScale = when (policy) {
            CeilingPolicy.IMPORT -> min(sx, sy)
            CeilingPolicy.CAMERA -> max(sx, sy)
        }
        contentLeft = (viewW - auWidth * fitScale) / 2f
        contentTop = (viewH - auHeight * fitScale) / 2f
        contentRight = contentLeft + auWidth * fitScale
        contentBottom = contentTop + auHeight * fitScale
        val nativeCeiling = 1f / fitScale
        ceiling = when (policy) {
            CeilingPolicy.IMPORT ->
                if (nativeCeiling < COLLAPSE_THRESHOLD) 1f else nativeCeiling
            CeilingPolicy.CAMERA -> max(nativeCeiling, PHOTO_MIN)
        }
        reset()
    }

    /** Focal-point-stable scale: the content under ([focusX],[focusY]) stays
     *  under the finger. Snaps cleanly back to identity at fit so no
     *  residual pan survives into the at-fit passthrough state. */
    fun scaleBy(factor: Float, focusX: Float, focusY: Float) {
        val newZoom = (zoom * factor).coerceIn(1f, ceiling)
        panX = focusX - newZoom * (focusX - panX) / zoom
        panY = focusY - newZoom * (focusY - panY) / zoom
        zoom = newZoom
        if (isAtFit) {
            reset()
        } else {
            clampPan()
        }
    }

    fun panBy(dx: Float, dy: Float) {
        if (isAtFit) return
        panX += dx
        panY += dy
        clampPan()
    }

    fun reset() {
        zoom = 1f
        panX = 0f
        panY = 0f
    }

    /** The transform as a row-major 3x3 homography (the shape
     *  [com.playtranslate.camera.tracker.Homography] and WarpOverlayView
     *  consume). */
    fun toHomographyRow(): DoubleArray = doubleArrayOf(
        zoom.toDouble(), 0.0, panX.toDouble(),
        0.0, zoom.toDouble(), panY.toDouble(),
        0.0, 0.0, 1.0,
    )

    /** Per-axis clamp. Cover branch (scaled content spans the viewport on
     *  the axis): the content edges may never pull inside the viewport — a
     *  FILL frame at rest, or any axis once zoom grows it past the viewport;
     *  panning is bounded to the content. Center branch (content narrower
     *  than the viewport): the letterbox bars stay centered. At zoom=1 both
     *  branches force pan=0, so fit is exactly identity. */
    private fun clampPan() {
        panX = clampAxis(panX, contentLeft, contentRight, viewW)
        panY = clampAxis(panY, contentTop, contentBottom, viewH)
    }

    private fun clampAxis(t: Float, edge0: Float, edge1: Float, viewportLen: Float): Float {
        val scaledLen = zoom * (edge1 - edge0)
        return if (scaledLen >= viewportLen) {
            t.coerceIn(viewportLen - zoom * edge1, -zoom * edge0)
        } else {
            viewportLen / 2f - zoom * (edge0 + edge1) / 2f
        }
    }

    companion object {
        /** An IMPORT native ceiling below this collapses to "zoom disabled"
         *  — a barely-there range would only flip gesture modes without
         *  showing the user anything new. */
        const val COLLAPSE_THRESHOLD = 1.15f

        /** CAMERA ceiling floor: photographic upscale range. Matches the
         *  rasterizer's 2.5 renderScale clamp deliberately — the boxes stay
         *  crisp to the last zoom step it can serve. */
        const val PHOTO_MIN = 2.5f

        /** |zoom - 1| below this reads as "at fit". */
        const val FIT_EPSILON = 0.01f
    }
}
