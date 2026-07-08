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
 *  homography it rides ([trackKey] -1 → always the global homography). */
class RasterRegion(
    val bitmap: Bitmap,
    val auRect: Rect,
    val trackKey: Int = -1,
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
    ): List<RasterRegion> {
        if (boxes.isEmpty()) return emptyList()
        val view = TranslationOverlayView(
            context,
            pinholeMode = false,
            verticalTextTarget = verticalTextTarget,
            verticalTextStackable = verticalTextStackable,
            verticalGrowEnabled = verticalGrowEnabled,
        )
        val wSpec = View.MeasureSpec.makeMeasureSpec(auWidth, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(auHeight, View.MeasureSpec.EXACTLY)
        // First pass gives the view its size (setBoxes only builds children
        // once width/height are known); second pass lays out the children.
        view.measure(wSpec, hSpec)
        view.layout(0, 0, auWidth, auHeight)
        view.setBoxes(boxes, 0, 0, auWidth, auHeight)
        view.measure(wSpec, hSpec)
        view.layout(0, 0, auWidth, auHeight)

        val regions = ArrayList<RasterRegion>(view.childCount)
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child.width <= 0 || child.height <= 0) continue
            val bmp = Bitmap.createBitmap(child.width, child.height, Bitmap.Config.ARGB_8888)
            child.draw(Canvas(bmp))
            regions.add(
                RasterRegion(
                    bitmap = bmp,
                    auRect = Rect(child.left, child.top, child.right, child.bottom),
                    trackKey = trackKeys.getOrElse(i) { -1 },
                )
            )
        }
        return regions
    }
}
