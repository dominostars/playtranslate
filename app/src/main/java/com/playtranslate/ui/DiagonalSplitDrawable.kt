package com.playtranslate.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Rounded-rect background drawn as two halves split by a diagonal from the
 * top-right corner to the bottom-left. The triangle above the diagonal
 * (top-left side) is filled with [topLeftColor]; the triangle below (bottom-
 * right side) is filled with [bottomRightColor]. Used for the System theme-
 * mode preview tile so it visually combines Dark + Light at a glance.
 */
class DiagonalSplitDrawable(
    private val topLeftColor: Int,
    private val bottomRightColor: Int,
    private val cornerRadius: Float,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val clipPath = Path()
    private val rectF = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        rectF.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())

        // Bottom-right triangle fill = whole rounded rect in light color.
        paint.color = bottomRightColor
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)

        // Clip to the rounded rect so the diagonal triangle inherits the corner.
        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(rectF, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.clipPath(clipPath)

        // Top-left triangle on top: from top-left → top-right → bottom-left.
        path.reset()
        path.moveTo(rectF.left, rectF.top)
        path.lineTo(rectF.right, rectF.top)
        path.lineTo(rectF.left, rectF.bottom)
        path.close()
        paint.color = topLeftColor
        canvas.drawPath(path, paint)

        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
