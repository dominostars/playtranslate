package com.playtranslate.camera.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.View
import com.playtranslate.camera.CameraCoordinates
import com.playtranslate.camera.tracker.Homography

/**
 * The camera tool's per-frame overlay surface: draws each [RasterRegion]
 * through a full-perspective [Matrix] every frame. All heavy work happened at
 * rasterization time — onDraw is a handful of matrix-warped bitmap blits and
 * allocation-free; the per-update matrix recompute allocates only a few small
 * arrays.
 *
 * The draw matrix per region is `viewFromAU · H_au · T(region origin)`:
 * region-local pixels → AU keyframe coords → warped current-frame AU coords →
 * view coords. Phase 2 uses one global H for all regions; Phase 3 swaps in
 * per-line homographies through the same [setHomography] seam.
 */
class WarpOverlayView(context: Context) : View(context) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private var regions: List<RasterRegion> = emptyList()
    private var matrices: Array<Matrix> = emptyArray()
    private var visibleCount = 0

    /** AU dims of the keyframe the current regions were rastered against. */
    private var auWidth = 0
    private var auHeight = 0

    /** Last homographies applied, kept so a region swap (skeleton → filled
     *  re-raster) can re-derive matrices without waiting for the next frame. */
    private var lastHAu: DoubleArray? = null
    private var lastPerRegionAu: Map<Int, DoubleArray> = emptyMap()

    // Scratch for matrix composition (avoid per-frame allocation).
    private val scratch = DoubleArray(9)

    /** Install freshly rastered regions for a keyframe of [auW]×[auH].
     *  Recycles the previous set. Main thread. */
    fun setRegions(newRegions: List<RasterRegion>, auW: Int, auH: Int) {
        val old = regions
        regions = newRegions
        auWidth = auW
        auHeight = auH
        matrices = Array(newRegions.size) { Matrix() }
        old.forEach { it.release() }
        applyHomography(lastHAu, lastPerRegionAu)
    }

    fun clearRegions() {
        val old = regions
        regions = emptyList()
        matrices = emptyArray()
        visibleCount = 0
        lastHAu = null
        lastPerRegionAu = emptyMap()
        old.forEach { it.release() }
        invalidate()
    }

    /**
     * Per-frame update: [hAu] maps keyframe-AU → current-frame-AU (null hides
     * the overlays, e.g. Idle/Lost); [perRegionAu] carries refined per-region
     * homographies keyed by [RasterRegion.trackKey], falling back to [hAu]
     * for regions without one. Main thread.
     */
    fun applyHomography(hAu: DoubleArray?, perRegionAu: Map<Int, DoubleArray> = emptyMap()) {
        lastHAu = hAu
        lastPerRegionAu = perRegionAu
        if (hAu == null || regions.isEmpty() || width == 0 || height == 0 ||
            auWidth == 0 || auHeight == 0
        ) {
            visibleCount = 0
            invalidate()
            return
        }
        val coords = CameraCoordinates(auWidth, auHeight, width, height)
        // viewFromAU as a homography (uniform scale + offset).
        val s = coords.scale.toDouble()
        val viewFromAu = doubleArrayOf(
            s, 0.0, coords.offsetX.toDouble(),
            0.0, s, coords.offsetY.toDouble(),
            0.0, 0.0, 1.0,
        )
        val combinedGlobal = Homography.multiply(viewFromAu, hAu)
        // Cache per-track-key compositions — regions sharing a key share one.
        val combinedByKey = HashMap<Int, DoubleArray>(perRegionAu.size)
        for (i in regions.indices) {
            val region = regions[i]
            val h = if (region.trackKey >= 0) {
                perRegionAu[region.trackKey]?.let { refined ->
                    combinedByKey.getOrPut(region.trackKey) {
                        Homography.multiply(viewFromAu, refined)
                    }
                } ?: combinedGlobal
            } else combinedGlobal
            val r = region.auRect
            // h · T(left, top): translating region-local pixels to their AU
            // position folds into the last column.
            scratch[0] = h[0]; scratch[1] = h[1]
            scratch[2] = h[0] * r.left + h[1] * r.top + h[2]
            scratch[3] = h[3]; scratch[4] = h[4]
            scratch[5] = h[3] * r.left + h[4] * r.top + h[5]
            scratch[6] = h[6]; scratch[7] = h[7]
            scratch[8] = h[6] * r.left + h[7] * r.top + h[8]
            Homography.toAndroidMatrix(scratch, matrices[i])
        }
        visibleCount = regions.size
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (i in 0 until visibleCount) {
            canvas.drawBitmap(regions[i].bitmap, matrices[i], paint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        clearRegions()
    }
}
