package com.playtranslate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import com.playtranslate.R
import com.playtranslate.overlayThemedContext
import com.playtranslate.themeColor
import kotlin.math.abs
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withSave

/** Region dash pattern (dp) — shared by the drag editor and the camera
 *  snapshot's active-region indicator so the two read as one visual family. */
internal const val REGION_DASH_DP = 8f
internal const val REGION_GAP_DP = 6f

/** Draws dashes along an axis-aligned line at FIXED screen-space positions
 *  (period-aligned to the canvas origin), so the pattern doesn't swim when
 *  the box edges move. Endpoints clip mid-dash. */
internal fun drawScreenSpaceDashes(
    canvas: Canvas,
    paint: Paint,
    x1: Float, y1: Float, x2: Float, y2: Float,
    dashPx: Float, gapPx: Float,
) {
    val period = dashPx + gapPx
    if (y1 == y2) {
        var pos = (x1 / period).toInt() * period
        if (pos > x1) pos -= period
        while (pos < x2) {
            val segStart = pos.coerceAtLeast(x1)
            val segEnd = (pos + dashPx).coerceAtMost(x2)
            if (segEnd > segStart) canvas.drawLine(segStart, y1, segEnd, y1, paint)
            pos += period
        }
    } else {
        var pos = (y1 / period).toInt() * period
        if (pos > y1) pos -= period
        while (pos < y2) {
            val segStart = pos.coerceAtLeast(y1)
            val segEnd = (pos + dashPx).coerceAtMost(y2)
            if (segEnd > segStart) canvas.drawLine(x1, segStart, x1, segEnd, paint)
            pos += period
        }
    }
}

/**
 * Full-screen view placed on the game display via TYPE_ACCESSIBILITY_OVERLAY.
 * All four edges and four corners are independently draggable.
 * System back/forward gestures are excluded from the entire view area.
 */
class RegionDragView(context: Context) : View(context) {

    companion object {
        /** The smallest box a drag leaves on either axis, in dp. It was 5% of
         *  each axis, which the Thor's short axis (1080 px at 369 dpi, 468 dp)
         *  makes 24 dp, and that size is frozen: as a fraction, a tablet's
         *  floor was several times the one-line box a user may want, and a
         *  portrait phone's width floor was under a fingertip. A box seeded
         *  smaller is kept until a drag grows it. */
        const val MIN_EXTENT_DP = 24f
    }

    // The box, as fractions of the view: edges ordered, inside 0..1. setRegion
    // and the drag math are its only writers and both keep that; the drags
    // also keep each axis at MIN_EXTENT_DP once they have touched it.
    var topFraction    = 0.25f
        private set
    var bottomFraction = 0.75f
        private set
    var leftFraction   = 0.25f
        private set
    var rightFraction  = 0.75f
        private set

    /** Called on every drag move with the updated region. */
    var onRegionChanged: ((com.playtranslate.RegionEntry) -> Unit)? = null
    /** Called when the user starts dragging an edge/corner/center. */
    var onDragStart: (() -> Unit)? = null
    /** Called when the user lifts their finger after dragging. */
    var onDragEnd: (() -> Unit)? = null

    private val density get() = resources.displayMetrics.density

    /** [MIN_EXTENT_DP] as a fraction of each axis of this view. */
    private val minExtentX: Float get() = if (width > 0) MIN_EXTENT_DP * density / width else 0f
    private val minExtentY: Float get() = if (height > 0) MIN_EXTENT_DP * density / height else 0f

    // View(context) constructor stores the raw service context; theme
    // attrs aren't bound on it. Wrap it to resolve pt* tokens the same
    // way activities do.
    private val themedContext = overlayThemedContext(context)
    private val accentColor: Int = themedContext.themeColor(R.attr.ptAccent)
    private val dividerColor: Int = themedContext.themeColor(R.attr.ptDivider)

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
        color = "#E8E8E8".toColorInt()
        style = Paint.Style.FILL
    }
    private val dotStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.STROKE
    }
    // Dash params in dp — fixed screen-space intervals
    private val dashLen = REGION_DASH_DP
    private val gapLen = REGION_GAP_DP

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

    /** Seeds the box as it comes, edges ordered and clamped to the screen. A
     *  box under [MIN_EXTENT_DP] is kept, not grown: the camera's crop editor
     *  confirms an untouched box back as a change, and a drag grows the box
     *  the first time it moves an edge. The icon menu's drag-to-select box
     *  is only touch-slop wide at its smallest, and a 3.3.0 seed 42 px wide
     *  at the left edge crashed the first drag in a clamp whose bounds had
     *  crossed (Galaxy S23+, 2026-10-07). */
    fun setRegion(top: Float, bottom: Float, left: Float = 0.25f, right: Float = 0.75f) {
        topFraction    = minOf(top, bottom).coerceIn(0f, 1f)
        bottomFraction = maxOf(top, bottom).coerceIn(0f, 1f)
        leftFraction   = minOf(left, right).coerceIn(0f, 1f)
        rightFraction  = maxOf(left, right).coerceIn(0f, 1f)
        invalidate()
    }

    /** An edge moving toward the screen's start, held at 0 and at [limit],
     *  [MIN_EXTENT_DP] from its opposite. A box seeded under it puts the
     *  limit past the screen edge, and the screen edge wins; coerceIn
     *  throws on a crossed range. */
    private fun heldFromStart(v: Float, limit: Float): Float = v.coerceIn(0f, maxOf(0f, limit))

    /** The mirror: an edge moving toward the screen's end, held at [limit]
     *  and at 1. */
    private fun heldToEnd(v: Float, limit: Float): Float = v.coerceIn(minOf(limit, 1f), 1f)

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

        // Dark area outside the box — one full-screen rect with the box
        // clipped out. Four abutting band rects don't tessellate at the
        // shared fractional edges: a hairline transparent seam shows at
        // y=t and y=b across the whole screen.
        canvas.withSave {
            clipOutRect(l, t, r, b)
            drawRect(0f, 0f, w, h, darkPaint)
        }

        // Card-colored solid border
        val half = cardBorderPaint.strokeWidth / 2f
        canvas.drawRect(l - half, t - half, r + half, b + half, cardBorderPaint)

        // Accent dashed border — screen-space stable (dashes at fixed positions)
        val dashPx = dashLen * dp
        val gapPx = gapLen * dp
        drawScreenSpaceDashes(canvas, accentDashPaint, l, t, r, t, dashPx, gapPx) // top
        drawScreenSpaceDashes(canvas, accentDashPaint, r, t, r, b, dashPx, gapPx) // right
        drawScreenSpaceDashes(canvas, accentDashPaint, l, b, r, b, dashPx, gapPx) // bottom
        drawScreenSpaceDashes(canvas, accentDashPaint, l, t, l, b, dashPx, gapPx) // left

        // 8 dots (muted fill + accent border): 4 corners + 4 midpoints,
        // each pushed 3dp outward from the box center along its axis.
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        val out = 3f * dp
        drawDot(canvas, l - out, t - out, dotRadius)
        drawDot(canvas, r + out, t - out, dotRadius)
        drawDot(canvas, l - out, b + out, dotRadius)
        drawDot(canvas, r + out, b + out, dotRadius)
        drawDot(canvas, cx, t - out, dotRadius)
        drawDot(canvas, cx, b + out, dotRadius)
        drawDot(canvas, l - out, cy, dotRadius)
        drawDot(canvas, r + out, cy, dotRadius)
    }

    private fun drawDot(canvas: Canvas, x: Float, y: Float, radius: Float) {
        canvas.drawCircle(x, y, radius, dotFillPaint)
        canvas.drawCircle(x, y, radius, dotStrokePaint)
    }

    /** Required by ClickableViewAccessibility — this view is drag-only, no
     *  single-tap action. Drag handles aren't a meaningful accessibility
     *  surface; the region picker exposes its actions via separate
     *  buttons. */
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // onTouchEvent runs the drag state machine; there is no "click"
    // outcome to route through performClick. See performClick comment.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
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
                // Of two opposite edges both within reach, the finger is on
                // the nearer one. A box narrower than the touch zone has
                // every touch within reach of both, and a fixed order gave
                // its right handle to the left edge, which the minimum-size
                // rule then held against the screen edge: no drag moved it.
                val dTop    = abs(y - t)
                val dBottom = abs(y - b)
                val dLeft   = abs(x - l)
                val dRight  = abs(x - r)
                val nearTop    = dTop < tz && dTop <= dBottom
                val nearBottom = dBottom < tz && dBottom < dTop
                val nearLeft   = dLeft < tz && dLeft <= dRight
                val nearRight  = dRight < tz && dRight < dLeft

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
                val minX = minExtentX
                val minY = minExtentY

                when (dragging) {
                    DragTarget.TOP         -> topFraction    = heldFromStart(topFraction + dy, bottomFraction - minY)
                    DragTarget.BOTTOM      -> bottomFraction = heldToEnd(bottomFraction + dy, topFraction + minY)
                    DragTarget.LEFT        -> leftFraction   = heldFromStart(leftFraction + dx, rightFraction - minX)
                    DragTarget.RIGHT       -> rightFraction  = heldToEnd(rightFraction + dx, leftFraction + minX)
                    DragTarget.TOP_LEFT    -> {
                        topFraction  = heldFromStart(topFraction + dy, bottomFraction - minY)
                        leftFraction = heldFromStart(leftFraction + dx, rightFraction - minX)
                    }
                    DragTarget.TOP_RIGHT   -> {
                        topFraction   = heldFromStart(topFraction + dy, bottomFraction - minY)
                        rightFraction = heldToEnd(rightFraction + dx, leftFraction + minX)
                    }
                    DragTarget.BOTTOM_LEFT -> {
                        bottomFraction = heldToEnd(bottomFraction + dy, topFraction + minY)
                        leftFraction   = heldFromStart(leftFraction + dx, rightFraction - minX)
                    }
                    DragTarget.BOTTOM_RIGHT -> {
                        bottomFraction = heldToEnd(bottomFraction + dy, topFraction + minY)
                        rightFraction  = heldToEnd(rightFraction + dx, leftFraction + minX)
                    }
                    DragTarget.MIDDLE -> {
                        val newTop  = heldFromStart(topFraction + dy, 1f - middleDragH)
                        val newLeft = heldFromStart(leftFraction + dx, 1f - middleDragW)
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
