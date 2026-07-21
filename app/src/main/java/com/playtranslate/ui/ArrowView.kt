package com.playtranslate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * The little triangle that ties a popup to the thing it describes: drawn to fill
 * the view's bounds, pointing DOWN when the popup sits above its subject and UP
 * when it sits below. Give it the popup's own fill [color] and butt it against
 * the popup's edge (overlapping any stroke) so the two read as one shape.
 *
 * Shared by [WordLookupPopup] (points at the looked-up word) and
 * [FontSizeRangePopover] (points at the header button that opened it).
 */
internal class ArrowView(
    ctx: Context,
    color: Int,
    private val pointsDown: Boolean,
) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        path.reset()
        val w = width.toFloat()
        val h = height.toFloat()
        if (pointsDown) {
            path.moveTo(0f, 0f)
            path.lineTo(w, 0f)
            path.lineTo(w / 2f, h)
        } else {
            path.moveTo(w / 2f, 0f)
            path.lineTo(w, h)
            path.lineTo(0f, h)
        }
        path.close()
        canvas.drawPath(path, paint)
    }
}
