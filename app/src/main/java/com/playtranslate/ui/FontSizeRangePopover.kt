package com.playtranslate.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
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
 * The results text-size picker: a rounded card holding a two-handle pill slider
 * that edits [Prefs.resultsFontMinSp] / [Prefs.resultsFontMaxSp] live, with an
 * [ArrowView] tying it back to the header button that opened it (the same
 * treatment [WordLookupPopup] gives the word it defines). Opened from
 * [TranslationSectionBinder.onChooseFontSize].
 *
 * Shown as a CHILD VIEW of [host], never as its own window. The capture panel's
 * sheet is one full-screen window whose in-window children are the only things
 * MediaProjection's QTI clamp leaves undimmed — a sibling overlay window renders
 * at ~80% and steals the panel's taps (the same constraint that governs the
 * on-frame boxes). Hosting as a child also means the in-app page and every
 * overlay panel share one implementation.
 *
 * Two views go into [host]: a transparent full-size scrim, then the popup.
 * Reverse z-order dispatch makes that popup > scrim > content, so the popover is
 * modal while open and any tap outside it dismisses.
 *
 * Everything is built in code (no XML, no AppCompat widgets) because the capture
 * overlay inflates with a plain [android.view.LayoutInflater] that silently drops
 * `app:` attributes — the same reason [WordLookupPopup] and [buildPillToggle]
 * are code-built.
 */
class FontSizeRangePopover(
    private val ctx: Context,
    private val host: FrameLayout,
    private val prefs: Prefs,
) {
    /** Invoked after every committed (integer) change to either bound. The
     *  surface re-fits its sections here; the pref is already written. */
    var onRangeChanged: (() -> Unit)? = null

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var scrim: View? = null
    private var popup: View? = null

    val isShowing: Boolean get() = popup != null

    /** Open anchored on [anchor], or close if already open (the button is a
     *  toggle). */
    fun toggle(anchor: View) {
        if (isShowing) dismiss() else show(anchor)
    }

    fun show(anchor: View) {
        dismiss()

        val fill = ctx.themeColor(R.attr.ptElevated)
        val strokePx = dp(1f).toInt()
        val card = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                setColor(fill)
                cornerRadius = dp(CARD_RADIUS_DP)
                setStroke(strokePx, ctx.themeColor(R.attr.ptDivider))
            }
            // The card must swallow touches that land on its PADDING. Without
            // this the bare FrameLayout declines them, dispatch falls through to
            // the scrim underneath, and grabbing a handle a few dp off closes
            // the popover instead.
            isClickable = true
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

        val cardW = dp(CARD_W_DP).toInt()
        card.measure(
            View.MeasureSpec.makeMeasureSpec(cardW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val cardH = card.measuredHeight
        val arrowH = dp(ARROW_H_DP).toInt()
        val totalH = cardH + arrowH

        // Where the anchor sits in host space (it lives several levels down
        // inside a scroll on both surfaces), and whether the popup clears the
        // host's top edge above it.
        val r = Rect(0, 0, anchor.width, anchor.height)
        host.offsetDescendantRectToMyCoords(anchor, r)
        val gap = dp(ANCHOR_GAP_DP).toInt()
        val above = r.top - totalH - gap
        val pointsDown = above >= 0
        val y = if (pointsDown) above else r.bottom + gap
        val x = (r.centerX() - cardW / 2)
            .coerceIn(0, (host.width - cardW).coerceAtLeast(0))

        // The arrow overlaps the card's edge by the stroke width so its fill
        // covers the boundary line — otherwise the stroke draws straight across
        // the arrow's base and the two read as separate shapes.
        val arrow = ArrowView(ctx, fill, pointsDown = pointsDown)
        val arrowW = dp(ARROW_W_DP).toInt()
        val arrowEdge = dp(CARD_RADIUS_DP).toInt() + arrowW / 2
        val arrowCenterX = (r.centerX() - x)
            .coerceIn(arrowEdge, (cardW - arrowEdge).coerceAtLeast(arrowEdge))

        val container = FrameLayout(ctx)
        container.addView(
            card,
            FrameLayout.LayoutParams(cardW, cardH, Gravity.TOP or Gravity.LEFT).apply {
                topMargin = if (pointsDown) 0 else arrowH
            },
        )
        // Added AFTER the card so it draws over that edge stroke.
        container.addView(
            arrow,
            FrameLayout.LayoutParams(
                arrowW, arrowH + strokePx,
                (if (pointsDown) Gravity.BOTTOM else Gravity.TOP) or Gravity.LEFT,
            ).apply { leftMargin = arrowCenterX - arrowW / 2 },
        )

        // A scrim UNDER the popup, sized to the whole host: it is the only
        // outside-tap handler, which keeps dismissal identical on a surface
        // whose root pre-empts touches (the capture panel) and one that
        // doesn't (the in-app page).
        val outside = View(ctx).apply {
            isClickable = true
            isFocusable = false
            setOnClickListener { dismiss() }
        }
        host.addView(outside, FrameLayout.LayoutParams(MATCH, MATCH))
        host.addView(
            container,
            // ABSOLUTE left/top, not the START default: the placement below uses
            // setX/setY, which are offsets from the laid-out left edge. Under
            // RTL (Arabic) START resolves to the RIGHT edge, so the default
            // would land the popup a full host-width off.
            FrameLayout.LayoutParams(cardW, totalH, Gravity.TOP or Gravity.LEFT),
        )
        scrim = outside
        popup = container

        container.x = x.toFloat()
        container.y = y.coerceIn(0, (host.height - totalH).coerceAtLeast(0)).toFloat()
    }

    fun dismiss() {
        popup?.let { host.removeView(it) }
        scrim?.let { host.removeView(it) }
        popup = null
        scrim = null
    }

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
        const val CARD_RADIUS_DP = 12f
        const val CARD_V_PAD_DP = 6f
        /** Gap between the anchor button and the arrow's tip. */
        const val ANCHOR_GAP_DP = 4f
        const val ARROW_W_DP = 20f
        const val ARROW_H_DP = 10f

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
