package com.playtranslate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import com.playtranslate.R
import com.playtranslate.themeColor

/**
 * The controller cursor's indicator: a slightly-rounded accent rectangle,
 * stroked just OUTSIDE the selected item's bounds. Not a background/selector
 * on the item — the cursor also lands on source WORDS, which aren't views.
 * Fills the sheet's root; [setTarget] rects are in this view's coordinates.
 * Driven per-frame from the sheet's pre-draw hook, so it only invalidates
 * when the target actually moved.
 */
class FocusRingView(ctx: Context) : View(ctx) {
    private val strokePx = OUTLINE_STROKE_DP * resources.displayMetrics.density
    private val outsetPx = OUTLINE_OUTSET_DP * resources.displayMetrics.density
    private val radiusPx = OUTLINE_RADIUS_DP * resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        color = ctx.themeColor(R.attr.ptAccent)
    }
    private val item = Rect()
    private val clip = Rect()
    private var hasItem = false
    private var hasClip = false
    private val drawRect = RectF()
    private val clipRect = Rect()

    init {
        // A backgroundless View skips onDraw unless this is cleared (same as
        // the sheet's HandleView).
        setWillNotDraw(false)
    }

    /** Ring [itemRect] (clipped to [clipRect], the scroll viewport, when
     *  given); null hides the ring. No-ops when nothing changed — safe to
     *  drive every frame. */
    fun setTarget(itemRect: Rect?, clipRect: Rect?) {
        val newHasItem = itemRect != null
        val newHasClip = clipRect != null
        if (newHasItem == hasItem && newHasClip == hasClip &&
            (itemRect == null || itemRect == item) &&
            (clipRect == null || clipRect == clip)
        ) {
            return
        }
        hasItem = newHasItem
        hasClip = newHasClip
        itemRect?.let(item::set)
        clipRect?.let(clip::set)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!hasItem) return
        val save = canvas.save()
        if (hasClip) {
            // The ring sits OUTSIDE the item, so a fully-visible item at the
            // viewport edge needs its ring's overhang admitted — inflate the
            // clip by the overhang. A scrolled-off item still clips.
            val pad = (outsetPx + strokePx).toInt()
            clipRect.set(clip.left - pad, clip.top - pad, clip.right + pad, clip.bottom + pad)
            canvas.clipRect(clipRect)
        }
        drawRect.set(item)
        drawRect.inset(-outsetPx, -outsetPx)
        canvas.drawRoundRect(drawRect, radiusPx, radiusPx, paint)
        canvas.restoreToCount(save)
    }

    private companion object {
        const val OUTLINE_STROKE_DP = 2f
        const val OUTLINE_OUTSET_DP = 2f
        const val OUTLINE_RADIUS_DP = 6f
    }
}
