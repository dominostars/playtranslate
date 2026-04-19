package com.playtranslate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Full-screen view placed on the game display via TYPE_ACCESSIBILITY_OVERLAY.
 * All four edges and four corners are independently draggable.
 * System back/forward gestures are excluded from the entire view area.
 */
class RegionDragView(context: Context) : View(context) {

    var topFraction    = 0.25f
    var bottomFraction = 0.75f
    var leftFraction   = 0.25f
    var rightFraction  = 0.75f

    /** Called on every drag move with the updated region. */
    var onRegionChanged: ((com.playtranslate.RegionEntry) -> Unit)? = null
    /** Called when the user starts dragging an edge/corner/center. */
    var onDragStart: (() -> Unit)? = null
    /** Called when the user lifts their finger after dragging. */
    var onDragEnd: (() -> Unit)? = null

    private val density get() = resources.displayMetrics.density

    private val accentColor: Int = com.playtranslate.OverlayColors.accent(context)
    private val dividerColor: Int = com.playtranslate.OverlayColors.divider(context)

    private val darkPaint = Paint().apply {
        color = Color.argb(200, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dividerColor
        style = Paint.Style.STROKE
    }
    private val accentDashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8E8E8")
        style = Paint.Style.FILL
    }
    private val dotStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.STROKE
    }
    // Dash params in dp — fixed screen-space intervals
    private val dashLen = 8f
    private val gapLen = 6f

    // Scratch reused in onLayout — allocating per frame is lint DrawAllocation.
    private val gestureRect = Rect()
    private val gestureRectList = listOf(gestureRect)

    private enum class DragTarget {
        NONE, MIDDLE,
        TOP, BOTTOM, LEFT, RIGHT,
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }

    private var dragging     = DragTarget.NONE
    private var lastX        = 0f
    private var lastY        = 0f
    private var middleDragW  = 0f
    private var middleDragH  = 0f

    private val touchZone get() = 52f * density
    private val cornerLen get() = 28f * density

    fun setRegion(top: Float, bottom: Float, left: Float = 0.25f, right: Float = 0.75f) {
        topFraction    = top
        bottomFraction = bottom
        leftFraction   = left
        rightFraction  = right
        invalidate()
    }

    fun getRegion() = Pair(topFraction, bottomFraction)
    fun getFullRegion() = arrayOf(topFraction, bottomFraction, leftFraction, rightFraction)

    // Exclude the whole view from Android's system gesture areas (back/forward swipes)
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        gestureRect.set(0, 0, width, height)
        systemGestureExclusionRects = gestureRectList
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = h * topFraction
        val b = h * bottomFraction
        val l = w * leftFraction
        val r = w * rightFraction
        val dp = density

        cardBorderPaint.strokeWidth = 2f * dp
        accentDashPaint.strokeWidth = 2f * dp
        dotStrokePaint.strokeWidth = 2f * dp
        val dotRadius = 5f * dp

        // Dark areas outside the box
        if (t > 0f) canvas.drawRect(0f, 0f, w, t, darkPaint)
        if (b < h)  canvas.drawRect(0f, b, w, h, darkPaint)
        if (l > 0f) canvas.drawRect(0f, t, l, b, darkPaint)
        if (r < w)  canvas.drawRect(r, t, w, b, darkPaint)

        // Card-colored solid border
        val half = cardBorderPaint.strokeWidth / 2f
        canvas.drawRect(l - half, t - half, r + half, b + half, cardBorderPaint)

        // Accent dashed border — screen-space stable (dashes at fixed positions)
        val dashPx = dashLen * dp
        val gapPx = gapLen * dp
        val period = dashPx + gapPx
        drawScreenSpaceDashes(canvas, l, t, r, t, dashPx, gapPx, period, true)  // top
        drawScreenSpaceDashes(canvas, r, t, r, b, dashPx, gapPx, period, false) // right
        drawScreenSpaceDashes(canvas, l, b, r, b, dashPx, gapPx, period, true)  // bottom
        drawScreenSpaceDashes(canvas, l, t, l, b, dashPx, gapPx, period, false) // left

        // 8 dots (muted fill + accent border): 4 corners + 4 midpoints
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        drawDot(canvas, l, t, dotRadius)
        drawDot(canvas, r, t, dotRadius)
        drawDot(canvas, l, b, dotRadius)
        drawDot(canvas, r, b, dotRadius)
        drawDot(canvas, cx, t, dotRadius)
        drawDot(canvas, cx, b, dotRadius)
        drawDot(canvas, l, cy, dotRadius)
        drawDot(canvas, r, cy, dotRadius)
    }

    private fun drawDot(canvas: Canvas, x: Float, y: Float, radius: Float) {
        canvas.drawCircle(x, y, radius, dotFillPaint)
        canvas.drawCircle(x, y, radius, dotStrokePaint)
    }

    /** Draws dashes along a line at fixed screen-space positions (no swimming on drag). */
    private fun drawScreenSpaceDashes(
        canvas: Canvas,
        x1: Float, y1: Float, x2: Float, y2: Float,
        dashPx: Float, gapPx: Float, period: Float,
        horizontal: Boolean
    ) {
        if (horizontal) {
            val y = y1
            // Start at the nearest period-aligned position before x1
            var pos = (x1 / period).toInt() * period
            if (pos > x1) pos -= period
            while (pos < x2) {
                val segStart = pos.coerceAtLeast(x1)
                val segEnd = (pos + dashPx).coerceAtMost(x2)
                if (segEnd > segStart) {
                    canvas.drawLine(segStart, y, segEnd, y, accentDashPaint)
                }
                pos += period
            }
        } else {
            val x = x1
            var pos = (y1 / period).toInt() * period
            if (pos > y1) pos -= period
            while (pos < y2) {
                val segStart = pos.coerceAtLeast(y1)
                val segEnd = (pos + dashPx).coerceAtMost(y2)
                if (segEnd > segStart) {
                    canvas.drawLine(x, segStart, x, segEnd, accentDashPaint)
                }
                pos += period
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w  = width.toFloat()
        val h  = height.toFloat()
        val x  = event.x
        val y  = event.y
        val t  = h * topFraction
        val b  = h * bottomFraction
        val l  = w * leftFraction
        val r  = w * rightFraction
        val tz = touchZone

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val nearTop    = abs(y - t) < tz
                val nearBottom = abs(y - b) < tz
                val nearLeft   = abs(x - l) < tz
                val nearRight  = abs(x - r) < tz

                dragging = when {
                    // Corners first (highest priority)
                    nearTop    && nearLeft  -> DragTarget.TOP_LEFT
                    nearTop    && nearRight -> DragTarget.TOP_RIGHT
                    nearBottom && nearLeft  -> DragTarget.BOTTOM_LEFT
                    nearBottom && nearRight -> DragTarget.BOTTOM_RIGHT
                    // Edges
                    nearTop    && x in l..r -> DragTarget.TOP
                    nearBottom && x in l..r -> DragTarget.BOTTOM
                    nearLeft   && y in t..b -> DragTarget.LEFT
                    nearRight  && y in t..b -> DragTarget.RIGHT
                    // Interior — move the whole box
                    x in l..r && y in t..b -> {
                        middleDragW = rightFraction - leftFraction
                        middleDragH = bottomFraction - topFraction
                        DragTarget.MIDDLE
                    }
                    else -> DragTarget.NONE
                }
                lastX = x; lastY = y
                if (dragging != DragTarget.NONE) onDragStart?.invoke()
                return dragging != DragTarget.NONE
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging == DragTarget.NONE) return false
                val dx = (x - lastX) / w
                val dy = (y - lastY) / h
                lastX = x; lastY = y

                when (dragging) {
                    DragTarget.TOP         -> topFraction    = (topFraction + dy).coerceIn(0f, bottomFraction - 0.05f)
                    DragTarget.BOTTOM      -> bottomFraction = (bottomFraction + dy).coerceIn(topFraction + 0.05f, 1f)
                    DragTarget.LEFT        -> leftFraction   = (leftFraction + dx).coerceIn(0f, rightFraction - 0.05f)
                    DragTarget.RIGHT       -> rightFraction  = (rightFraction + dx).coerceIn(leftFraction + 0.05f, 1f)
                    DragTarget.TOP_LEFT    -> {
                        topFraction  = (topFraction + dy).coerceIn(0f, bottomFraction - 0.05f)
                        leftFraction = (leftFraction + dx).coerceIn(0f, rightFraction - 0.05f)
                    }
                    DragTarget.TOP_RIGHT   -> {
                        topFraction   = (topFraction + dy).coerceIn(0f, bottomFraction - 0.05f)
                        rightFraction = (rightFraction + dx).coerceIn(leftFraction + 0.05f, 1f)
                    }
                    DragTarget.BOTTOM_LEFT -> {
                        bottomFraction = (bottomFraction + dy).coerceIn(topFraction + 0.05f, 1f)
                        leftFraction   = (leftFraction + dx).coerceIn(0f, rightFraction - 0.05f)
                    }
                    DragTarget.BOTTOM_RIGHT -> {
                        bottomFraction = (bottomFraction + dy).coerceIn(topFraction + 0.05f, 1f)
                        rightFraction  = (rightFraction + dx).coerceIn(leftFraction + 0.05f, 1f)
                    }
                    DragTarget.MIDDLE -> {
                        val newTop  = (topFraction + dy).coerceIn(0f, 1f - middleDragH)
                        val newLeft = (leftFraction + dx).coerceIn(0f, 1f - middleDragW)
                        topFraction    = newTop;  bottomFraction = newTop  + middleDragH
                        leftFraction   = newLeft; rightFraction  = newLeft + middleDragW
                    }
                    DragTarget.NONE -> {}
                }
                invalidate()
                onRegionChanged?.invoke(com.playtranslate.RegionEntry("", topFraction, bottomFraction, leftFraction, rightFraction))
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging != DragTarget.NONE) onDragEnd?.invoke()
                dragging = DragTarget.NONE
            }
        }
        return true
    }
}
