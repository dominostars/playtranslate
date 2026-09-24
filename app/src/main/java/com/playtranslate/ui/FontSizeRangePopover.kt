package com.playtranslate.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.themeColor
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The results text-size picker: a two-handle pill slider that edits
 * [Prefs.resultsFontMinSp] / [Prefs.resultsFontMaxSp] live. Shown by the
 * surface's [PopoverHost] (card, arrow, scrim, placement and the in-window
 * rules all live there), anchored on whichever view presents the target
 * header's Text size action: its button, or the header's ⋯ when the action
 * is folded. Opened from [TranslationSectionBinder.onChooseFontSize].
 *
 * Built in code (no XML, no AppCompat widgets) because the capture overlay
 * inflates with a plain [android.view.LayoutInflater] that silently drops
 * `app:` attributes — the same reason [WordLookupPopup] and [buildPillToggle]
 * are code-built.
 */
class FontSizeRangePopover(
    private val ctx: Context,
    private val prefs: Prefs,
) : PopoverContent {
    /** Invoked after every committed (integer) change to either bound. The
     *  surface re-fits its sections here; the pref is already written. */
    var onRangeChanged: (() -> Unit)? = null

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    override fun createView(ctx: Context, host: PopoverHost): View =
        FrameLayout(ctx).apply {
            val padV = dp(CARD_V_PAD_DP).toInt()
            // No horizontal padding: the track view spans the card's full width
            // so an end handle still has its whole 48dp box inside a view that
            // receives touches (see RangeTrackView.edgeInset).
            setPadding(0, padV, 0, padV)
            addView(
                RangeTrackView(ctx),
                FrameLayout.LayoutParams(MATCH, dp(TRACK_ROW_H_DP).toInt()),
            )
        }

    override fun cardWidth(ctx: Context, natural: Int): Int = dp(CARD_W_DP).toInt()

    /**
     * The pill slider itself: a full-width track that is plain outside the two
     * handles and accent-filled between them, with each handle's current size
     * printed above it. Values are whole sp only — this control picks a range
     * to read, not a typographic measurement.
     *
     * Each handle carries a [TOUCH_BOX_DP]-square grab area centred on its (much
     * smaller) painted circle. The row is sized, and the track inset, so that box
     * always lies inside this view — a parent won't dispatch a touch that misses
     * the child's bounds, so a box hanging off the edge would silently shrink.
     */
    @SuppressLint("ClickableViewAccessibility")
    private inner class RangeTrackView(c: Context) : View(c) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ctx.themeColor(R.attr.ptSurface)
        }
        private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ctx.themeColor(R.attr.ptAccent)
        }
        private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ctx.themeColor(R.attr.ptCard)
        }
        private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ctx.themeColor(R.attr.ptAccent)
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ctx.themeColor(R.attr.ptText)
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, LABEL_SP, ctx.resources.displayMetrics,
            )
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        private val trackRect = RectF()
        private val handleRadius = dp(HANDLE_RADIUS_DP)
        private val trackHalf = dp(TRACK_H_DP) / 2f
        private val touchHalf = dp(TOUCH_BOX_DP) / 2f
        private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop

        /** Which handle the in-flight gesture owns. */
        private var grabbed = NONE

        /** The gesture landed on a near-coincident pair, where "nearer handle"
         *  is meaningless — hold off until the drag direction says which one the
         *  user meant (see ACTION_MOVE). */
        private var awaitingDirection = false
        private var downX = 0f

        /** handleX − touchX at grab time. Dragging by a handle you've actually
         *  grabbed should move it FROM where it is, not teleport it under your
         *  finger; only a tap that misses both boxes jumps (offset 0). */
        private var grabOffset = 0f

        /** Half the touch box, so an end handle's whole grab area is inside this
         *  view's bounds. The painted circle is [handleRadius] — much smaller —
         *  so the track simply starts further in. */
        private val edgeInset: Float get() = touchHalf
        private val spanPx: Float get() = (width - edgeInset * 2).coerceAtLeast(1f)
        private val trackCenterY: Float get() = dp(LABEL_BAND_DP) + touchHalf

        private fun xFor(value: Int): Float {
            val t = (value - Prefs.FONT_SP_FLOOR).toFloat() /
                (Prefs.FONT_SP_CEIL - Prefs.FONT_SP_FLOOR)
            return edgeInset + t * spanPx
        }

        private fun valueFor(x: Float): Int {
            val t = ((x - edgeInset) / spanPx).coerceIn(0f, 1f)
            val raw = Prefs.FONT_SP_FLOOR +
                t * (Prefs.FONT_SP_CEIL - Prefs.FONT_SP_FLOOR)
            return raw.roundToInt().coerceIn(Prefs.FONT_SP_FLOOR, Prefs.FONT_SP_CEIL)
        }

        private fun xForHandle(which: Int): Float =
            xFor(if (which == MIN) prefs.resultsFontMinSp else prefs.resultsFontMaxSp)

        override fun onDraw(canvas: Canvas) {
            val minX = xFor(prefs.resultsFontMinSp)
            val maxX = xFor(prefs.resultsFontMaxSp)
            val cy = trackCenterY

            // Plain pill across the full span, then the accent stretch between
            // the handles painted over it.
            trackRect.set(edgeInset, cy - trackHalf, width - edgeInset, cy + trackHalf)
            canvas.drawRoundRect(trackRect, trackHalf, trackHalf, trackPaint)
            trackRect.set(minX, cy - trackHalf, maxX, cy + trackHalf)
            canvas.drawRoundRect(trackRect, trackHalf, trackHalf, activePaint)

            for (x in listOf(minX, maxX)) {
                canvas.drawCircle(x, cy, handleRadius, handleFill)
                canvas.drawCircle(x, cy, handleRadius - handleRing.strokeWidth / 2f, handleRing)
            }

            // Labels ride above their handle. Two nudges: apart from each other
            // when the handles close to within a label's width (adjacent values
            // would otherwise smear into an unreadable overlap), then inward at
            // the extremes so a two-digit value can't run off the card.
            val baseline = -labelPaint.fontMetrics.top
            val halfLabel = labelPaint.measureText("88") / 2f
            var minLx = minX
            var maxLx = maxX
            val minGap = halfLabel * 2 + dp(4f)
            // Equal values sit at one x and read as a single label — leave them
            // stacked rather than splitting one number into two.
            if (maxLx - minLx < minGap && prefs.resultsFontMinSp != prefs.resultsFontMaxSp) {
                val push = (minGap - (maxLx - minLx)) / 2f
                minLx -= push
                maxLx += push
            }
            for ((x, value) in listOf(
                minLx to prefs.resultsFontMinSp,
                maxLx to prefs.resultsFontMaxSp,
            )) {
                val lx = x.coerceIn(halfLabel, width - halfLabel)
                canvas.drawText(value.toString(), lx, baseline, labelPaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // The in-app page puts this inside a ScrollView and the panel
                    // wraps it in its own gesture layers; both would otherwise
                    // claim a drag that wanders vertically.
                    parent?.requestDisallowInterceptTouchEvent(true)
                    beginGesture(event.x)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (awaitingDirection && !resolveDirection(event.x)) return true
                    if (grabbed == NONE) return true
                    applyDrag(event.x + grabOffset)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    grabbed = NONE
                    awaitingDirection = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun beginGesture(x: Float) {
            downX = x
            grabbed = NONE
            awaitingDirection = false

            val minX = xFor(prefs.resultsFontMinSp)
            val maxX = xFor(prefs.resultsFontMaxSp)
            val inMin = abs(x - minX) <= touchHalf
            val inMax = abs(x - maxX) <= touchHalf

            when {
                // Sitting on a pair too close to tell apart: which handle the
                // user wants is only knowable from which way they drag.
                (inMin || inMax) && maxX - minX < dp(AMBIGUOUS_GAP_DP) -> {
                    awaitingDirection = true
                }
                // Inside both boxes but the handles are far enough apart for
                // "nearer" to mean something.
                inMin && inMax -> grab(if (abs(x - minX) <= abs(x - maxX)) MIN else MAX, x)
                inMin -> grab(MIN, x)
                inMax -> grab(MAX, x)
                // Missed both boxes: a tap on bare track jumps the nearer handle
                // to the finger, which stays the fastest way to cross the range.
                // A near-coincident pair resolves by side, since the touch is
                // unambiguously left or right of it.
                else -> {
                    grabbed = if (x < minX) MIN else if (x > maxX) MAX
                        else if (abs(x - minX) <= abs(x - maxX)) MIN else MAX
                    grabOffset = 0f
                    applyDrag(x)
                }
            }
        }

        private fun grab(which: Int, x: Float) {
            grabbed = which
            grabOffset = xForHandle(which) - x
        }

        /** Commit the deferred grab once the finger has travelled far enough to
         *  read a direction: left takes the min handle, right the max. Returns
         *  true once resolved. */
        private fun resolveDirection(x: Float): Boolean {
            val dx = x - downX
            if (abs(dx) < touchSlop) return false
            grab(if (dx < 0) MIN else MAX, downX)
            awaitingDirection = false
            return true
        }

        private fun applyDrag(x: Float) {
            val value = valueFor(x)
            val changed = if (grabbed == MIN) {
                // The handles may MEET (a met pair pins one fixed size) but
                // never cross.
                val clamped = value.coerceAtMost(prefs.resultsFontMaxSp)
                (clamped != prefs.resultsFontMinSp).also {
                    if (it) prefs.resultsFontMinSp = clamped
                }
            } else {
                val clamped = value.coerceAtLeast(prefs.resultsFontMinSp)
                (clamped != prefs.resultsFontMaxSp).also {
                    if (it) prefs.resultsFontMaxSp = clamped
                }
            }
            if (changed) {
                invalidate()
                onRangeChanged?.invoke()
            }
        }
    }

    private companion object {
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT

        const val NONE = -1
        const val MIN = 0
        const val MAX = 1

        const val CARD_W_DP = 220f
        const val CARD_V_PAD_DP = 6f

        /** Label band above the handles, plus the handles' own [TOUCH_BOX_DP]
         *  band — together the row height, so a 48dp box fits vertically. */
        const val LABEL_BAND_DP = 18f
        const val TOUCH_BOX_DP = 48f
        const val TRACK_ROW_H_DP = LABEL_BAND_DP + TOUCH_BOX_DP
        const val TRACK_H_DP = 10f
        const val HANDLE_RADIUS_DP = 9f
        const val LABEL_SP = 13f

        /** Below this separation the two handles' grab boxes overlap so heavily
         *  that "nearer" is noise; the gesture waits for a drag direction. */
        const val AMBIGUOUS_GAP_DP = 20f
    }
}
