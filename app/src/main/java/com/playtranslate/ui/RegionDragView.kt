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

    private val darkPaint = Paint().apply {
        color = Color.argb((0.78f * 255).toInt(), 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint().apply {
        color = Color.argb(200, 255, 255, 255)
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val cornerPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        isAntiAlias = true
    }

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
        systemGestureExclusionRects = listOf(Rect(0, 0, width, height))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = h * topFraction
        val b = h * bottomFraction
        val l = w * leftFraction
        val r = w * rightFraction

        outlinePaint.strokeWidth = 1.5f * density
        cornerPaint.strokeWidth  = 3.5f * density

        // Dark areas outside the box (4 rects to avoid overdraw)
        if (t > 0f)       canvas.drawRect(0f, 0f, w, t, darkPaint)   // above
        if (b < h)        canvas.drawRect(0f, b, w, h, darkPaint)     // below
        if (l > 0f)       canvas.drawRect(0f, t, l, b, darkPaint)     // left strip
        if (r < w)        canvas.drawRect(r, t, w, b, darkPaint)      // right strip

        // Box outline
        canvas.drawRect(l, t, r, b, outlinePaint)

        // Bold corner L-shapes
        val cl = cornerLen
        // Top-left
        canvas.drawLine(l, t, l + cl, t, cornerPaint)
        canvas.drawLine(l, t, l, t + cl, cornerPaint)
        // Top-right
        canvas.drawLine(r, t, r - cl, t, cornerPaint)
        canvas.drawLine(r, t, r, t + cl, cornerPaint)
        // Bottom-left
        canvas.drawLine(l, b, l + cl, b, cornerPaint)
        canvas.drawLine(l, b, l, b - cl, cornerPaint)
        // Bottom-right
        canvas.drawLine(r, b, r - cl, b, cornerPaint)
        canvas.drawLine(r, b, r, b - cl, cornerPaint)
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
