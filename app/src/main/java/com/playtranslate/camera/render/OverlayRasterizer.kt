package com.playtranslate.camera.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import com.playtranslate.ui.TextBox
import com.playtranslate.ui.TranslationOverlayView

/** One warpable overlay region: its rendered pixels, where those pixels sit
 *  in AnalysisUpright (keyframe) space, and which tracked region's refined
 *  homography it rides ([trackKey] -1 → always the global homography).
 *  [pixelsPerAu] > 1 means the bitmap was rendered super-sampled (zoomed-in
 *  crispness) and must be drawn scaled down by that factor. */
class RasterRegion(
    val bitmap: Bitmap,
    val auRect: Rect,
    val trackKey: Int = -1,
    val pixelsPerAu: Float = 1f,
    /** The box this raster was rendered from — the dirty-diff identity. */
    val sourceBox: TextBox? = null,
) {
    fun release() = bitmap.recycle()
}

/**
 * Rasterize overlay boxes once per acquire so the per-frame path is nothing
 * but matrix-warped bitmap draws (camera plan §4: rasterize-once,
 * warp-per-frame).
 *
 * Implementation: an offscreen [TranslationOverlayView] laid out at the
 * keyframe's AU size — identical box→child mapping to the live overlay
 * (auto-size fitting, GROW/ROTATE/STACK render modes, skeleton shimmer,
 * furigana outline) with zero duplicated logic. Each laid-out child is then
 * drawn into its own bitmap, and the child's frame IS its AU rect (the view
 * maps screenshot→view 1:1 when both are AU-sized).
 */
class OverlayRasterizer(
    private val context: Context,
    private val verticalTextTarget: Boolean,
    private val verticalTextStackable: Boolean,
    private val verticalGrowEnabled: Boolean,
) {
    /**
     * Render [boxes] (AU coords) for a keyframe of [auWidth]×[auHeight].
     * [trackKeys] parallels [boxes]: the tracked-region key each box's raster
     * should warp with (-1 or absent → global homography). Main-thread only
     * (view measure/layout/draw). Returns one region per laid-out child;
     * children with degenerate frames are skipped.
     */
    fun rasterize(
        boxes: List<TextBox>,
        auWidth: Int,
        auHeight: Int,
        trackKeys: List<Int> = emptyList(),
        /** Super-sampling factor: render at this scale for crispness when the
         *  user has zoomed in past the raster's native resolution. */
        renderScale: Float = 1f,
        /** Previous regions for the dirty diff: a child whose source box and
         *  laid-out rect are unchanged reuses the previous bitmap instead of
         *  re-rendering (skeleton→filled swaps only redraw filled boxes). */
        previous: List<RasterRegion>? = null,
    ): List<RasterRegion> {
        if (boxes.isEmpty()) return emptyList()
        val s = renderScale.coerceIn(0.5f, 2.5f)
        val renderBoxes = if (s == 1f) boxes else boxes.map { b ->
            b.copy(
                bounds = Rect(
                    (b.bounds.left * s).toInt(), (b.bounds.top * s).toInt(),
                    (b.bounds.right * s).toInt(), (b.bounds.bottom * s).toInt(),
                ),
                minWidthPx = (b.minWidthPx * s).toInt(),
            )
        }
        val vw = (auWidth * s).toInt()
        val vh = (auHeight * s).toInt()
        val view = TranslationOverlayView(
            context,
            pinholeMode = false,
            verticalTextTarget = verticalTextTarget,
            verticalTextStackable = verticalTextStackable,
            verticalGrowEnabled = verticalGrowEnabled,
        )
        val wSpec = View.MeasureSpec.makeMeasureSpec(vw, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(vh, View.MeasureSpec.EXACTLY)
        // First pass gives the view its size (setBoxes only builds children
        // once width/height are known); second pass lays out the children.
        view.measure(wSpec, hSpec)
        view.layout(0, 0, vw, vh)
        view.setBoxes(renderBoxes, 0, 0, vw, vh)
        view.measure(wSpec, hSpec)
        view.layout(0, 0, vw, vh)

        val regions = ArrayList<RasterRegion>(view.childCount)
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child.width <= 0 || child.height <= 0) continue
            val auRect = Rect(
                (child.left / s).toInt(), (child.top / s).toInt(),
                (child.right / s).toInt(), (child.bottom / s).toInt(),
            )
            val box = boxes.getOrNull(i)
            // Dirty diff: same box content, same geometry, same scale →
            // the previous bitmap is still exactly right.
            val reusable = previous?.firstOrNull { p ->
                p.sourceBox == box && p.auRect == auRect && p.pixelsPerAu == s &&
                    !p.bitmap.isRecycled
            }
            if (reusable != null) {
                regions.add(RasterRegion(reusable.bitmap, auRect, trackKeys.getOrElse(i) { -1 }, s, box))
                continue
            }
            val bmp = Bitmap.createBitmap(child.width, child.height, Bitmap.Config.ARGB_8888)
            child.draw(Canvas(bmp))
            regions.add(
                RasterRegion(
                    bitmap = bmp,
                    auRect = auRect,
                    trackKey = trackKeys.getOrElse(i) { -1 },
                    pixelsPerAu = s,
                    sourceBox = box,
                )
            )
        }
        return regions
    }
}
