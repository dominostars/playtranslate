package com.playtranslate.camera.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Log
import android.view.View
import com.playtranslate.BuildConfig
import com.playtranslate.ui.TextBox
import com.playtranslate.ui.TranslationOverlayView

private const val TAG = "OverlayRasterizer"

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
    /** A skeleton placeholder (source text awaiting its translation). The
     *  live overlay animates its bars, but rasterizing bakes that view
     *  static — the warp view pulses these regions' draw alpha instead. */
    val isSkeleton: Boolean
        get() = sourceBox?.let { it.translatedText.isEmpty() && it.sourceText.isNotEmpty() } == true

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
 * drawn into its own bitmap through its render transform, and its AU rect is
 * the TRANSFORMED frame (the view maps screenshot→view 1:1 when both are
 * AU-sized) — the layout frame alone is wrong for children the view
 * positions via translationX/Y (furigana) or rotates in place (ROTATE mode).
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

        // The child-i ↔ box-i pairing below is load-bearing: trackKeys (which
        // homography each raster warps with) and the dirty diff both index by
        // it. TranslationOverlayView currently builds exactly one child per
        // box; if that contract ever changes (skipped/merged boxes), every
        // raster after the divergence silently rides the wrong region.
        if (view.childCount != renderBoxes.size) {
            check(!BuildConfig.DEBUG) {
                "rasterizer child/box mismatch: ${view.childCount} children for ${renderBoxes.size} boxes"
            }
            Log.w(TAG, "child/box mismatch (${view.childCount} vs ${renderBoxes.size}); trackKey pairing suspect")
        }

        val regions = ArrayList<RasterRegion>(view.childCount)
        val transform = android.graphics.Matrix()
        val footprint = android.graphics.RectF()
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child.width <= 0 || child.height <= 0) continue
            // A child's render position is not always its layout frame:
            // mainline translation boxes are margin-positioned, but furigana
            // children lay out at (0,0) and move via translationX/Y, and
            // ROTATE-mode children add a 90° rotation about their pivot.
            // Reproduce the parent-draw transform (view-property matrix, then
            // the layout offset) and bake it into the raster: the footprint
            // is the transformed frame, the pixels are drawn through the same
            // matrix. Identity-transform children reduce to the old
            // left/top path bit-for-bit.
            transform.reset()
            if (child.rotation != 0f) transform.setRotate(child.rotation, child.pivotX, child.pivotY)
            transform.postTranslate(child.left + child.translationX, child.top + child.translationY)
            footprint.set(0f, 0f, child.width.toFloat(), child.height.toFloat())
            transform.mapRect(footprint)
            val fpLeft = kotlin.math.floor(footprint.left)
            val fpTop = kotlin.math.floor(footprint.top)
            val bmpW = kotlin.math.ceil(footprint.right - fpLeft).toInt()
            val bmpH = kotlin.math.ceil(footprint.bottom - fpTop).toInt()
            if (bmpW <= 0 || bmpH <= 0) continue
            val auRect = Rect(
                (fpLeft / s).toInt(), (fpTop / s).toInt(),
                ((fpLeft + bmpW) / s).toInt(), ((fpTop + bmpH) / s).toInt(),
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
            val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.translate(-fpLeft, -fpTop)
            canvas.concat(transform)
            child.draw(canvas)
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
