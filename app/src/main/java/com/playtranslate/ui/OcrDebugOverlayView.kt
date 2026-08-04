package com.playtranslate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import com.playtranslate.OcrManager
import androidx.core.graphics.toColorInt

/**
 * Transparent overlay that draws OCR bounding boxes on the game screen.
 * Currently shows:
 * - TextBlock boxes: thick red border
 * - Group boxes (combined TextBlocks): thick blue border
 * Line and element boxes are collected but not drawn (available for future use).
 */
class OcrDebugOverlayView(context: Context) : View(context) {

    private val dp = context.resources.displayMetrics.density

    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f * dp
    }

    private val groupPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#4488FF".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f * dp
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * dp
    }

    private var debugBoxes: OcrManager.OcrDebugBoxes? = null
    private var cropOffsetX = 0
    private var cropOffsetY = 0
    private var displayScaleX = 1f
    private var displayScaleY = 1f
    private var screenshotW = 1
    private var screenshotH = 1

    fun setBoxes(
        boxes: OcrManager.OcrDebugBoxes,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int
    ) {
        debugBoxes = boxes
        cropOffsetX = cropLeft
        cropOffsetY = cropTop
        this.screenshotW = screenshotW
        this.screenshotH = screenshotH
        if (width > 0 && height > 0) updateScales()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateScales()
    }

    private fun updateScales() {
        displayScaleX = width.toFloat() / screenshotW
        displayScaleY = height.toFloat() / screenshotH
    }

    private fun mapRect(r: Rect, scaleFactor: Float): RectF {
        val left   = (r.left   / scaleFactor + cropOffsetX) * displayScaleX
        val top    = (r.top    / scaleFactor + cropOffsetY) * displayScaleY
        val right  = (r.right  / scaleFactor + cropOffsetX) * displayScaleX
        val bottom = (r.bottom / scaleFactor + cropOffsetY) * displayScaleY
        return RectF(left, top, right, bottom)
    }

    /** Outline for one debug box. A slanted box draws its TRUE oriented
     *  footprint — the oriented dims rotated about the AABB center, the shape
     *  the detector found and the chip renders at — so lean, size, and
     *  placement are all judged against the on-screen text. Rotating the AABB
     *  instead would outline a shape that exists nowhere in the pipeline.
     *  Upright boxes draw their plain rect. */
    private fun drawBox(canvas: Canvas, box: OcrManager.DebugBox, sf: Float, paint: android.graphics.Paint) {
        val rf = mapRect(box.bounds, sf)
        if (box.angleDeg == 0f || box.orientedWidth <= 0f || box.orientedHeight <= 0f) {
            canvas.drawRect(rf, paint)
            return
        }
        val hw = box.orientedWidth / sf * displayScaleX / 2f
        val hh = box.orientedHeight / sf * displayScaleY / 2f
        val cx = rf.centerX()
        val cy = rf.centerY()
        canvas.save()
        canvas.rotate(box.angleDeg, cx, cy)
        canvas.drawRect(cx - hw, cy - hh, cx + hw, cy + hh, paint)
        canvas.restore()
    }

    override fun onDraw(canvas: Canvas) {
        val boxes = debugBoxes ?: return
        val sf = boxes.scaleFactor

        // Individual TextBlock boxes (red)
        for (box in boxes.blockBoxes) {
            drawBox(canvas, box, sf, blockPaint)
        }

        // Line boxes (thin green): true oriented footprints for slanted lines.
        for (box in boxes.lineBoxes) {
            drawBox(canvas, box, sf, linePaint)
        }

        // Combined group boxes (blue): ALWAYS the plain AABB — this tier shows
        // what the grouping kernel and downstream carriers actually consumed,
        // so a slanted line renders as green oriented footprint inside its
        // blue axis-aligned envelope.
        for (box in boxes.groupBoxes) {
            canvas.drawRect(mapRect(box.bounds, sf), groupPaint)
        }
    }
}
