package com.playtranslate.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.playtranslate.R
import com.playtranslate.themeColor
import kotlin.math.ceil

/**
 * The word boxes of a capture's Definitions presentation, drawn as settled on
 * device (2026-11-01): every box stroked in the accent; the game dimmed
 * around the highlighted box with a hole its shape (the spotlight), and an
 * accent glow outside that box over the scrim, a tight bright core and a
 * wider haze; no fill. [WordLookupSession] decides what is in the boxes and
 * which is highlighted. Three plain children, hosted by the capture layer;
 * coordinates are the host's, which sits at the display origin. The scrim
 * ([spotlight]) is the lowest child; a host with a panel of its own moves it
 * into a parent beneath that panel ([CaptureOverlayLayer.scrimHolder]), and
 * it draws from this view's state wherever it lives.
 */
internal class WordBoxesView(context: Context) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val accent = context.themeColor(R.attr.ptAccent)
    private val boxRadius = dp(BOX_RADIUS_DP)

    private var boxes: List<TapLookupGeometry.WordBox> = emptyList()

    /** The box the spotlight leaves undimmed and the glow is around, or
     *  none (no dimming, no glow). */
    private var highlighted: Int? = null

    /** The spotlight: a dark scrim over the whole game with a hole the
     *  highlighted box's shape, so the word is the only part not dimmed,
     *  whatever the game's colours. Under everything else. */
    val spotlight: View = object : View(context) {
        private val scrim = Color.argb(SPOTLIGHT_SCRIM_ALPHA, 0, 0, 0)
        private val hole = Path()
        private val turn = Matrix()

        override fun onDraw(canvas: Canvas) {
            val lit = highlighted?.let { boxes.getOrNull(it) } ?: return
            hole.reset()
            hole.addRoundRect(lit.rect, boxRadius, boxRadius, Path.Direction.CW)
            if (lit.angleDeg != 0f) {
                turn.setRotate(lit.angleDeg, lit.pivotX, lit.pivotY)
                hole.transform(turn)
            }
            val save = canvas.save()
            canvas.clipOutPath(hole)
            canvas.drawColor(scrim)
            canvas.restoreToCount(save)
        }
    }

    /**
     * The accent glow around the highlighted box, over the scrim, outside
     * the box only (the word stays clear). A view of its own, the box plus
     * [GLOW_REACH_DP] on every side, turned with a slanted box, on a software
     * layer, so the blur does not rest on the hardware renderer's support for
     * mask filters.
     */
    private val glow = object : View(context) {
        private val reach = dp(GLOW_REACH_DP)
        private val core = glowPaint(GLOW_CORE_ALPHA, GLOW_CORE_BLUR_DP)
        private val haze = glowPaint(GLOW_HAZE_ALPHA, GLOW_HAZE_BLUR_DP)
        /** The box in this view's frame, [reach] in from every side. */
        private val box = RectF()
        private val grown = RectF()
        private val hole = Path()

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            visibility = GONE
        }

        private fun glowPaint(alpha: Int, blurDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(alpha, Color.red(accent), Color.green(accent), Color.blue(accent))
            maskFilter = BlurMaskFilter(dp(blurDp), BlurMaskFilter.Blur.NORMAL)
        }

        /** Around [b], or hidden for null. */
        fun show(b: TapLookupGeometry.WordBox?) {
            if (b == null) {
                visibility = GONE
                return
            }
            val r = b.rect
            box.set(reach, reach, reach + r.width(), reach + r.height())
            val lp = layoutParams as LayoutParams
            val w = ceil(r.width() + 2 * reach).toInt()
            val h = ceil(r.height() + 2 * reach).toInt()
            if (lp.width != w || lp.height != h) {
                lp.width = w
                lp.height = h
                layoutParams = lp
            }
            val originX = r.left - reach
            val originY = r.top - reach
            translationX = originX
            translationY = originY
            // The box's own turn about its line's pivot, as the boxes draw it.
            pivotX = b.pivotX - originX
            pivotY = b.pivotY - originY
            rotation = b.angleDeg
            visibility = VISIBLE
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            hole.reset()
            hole.addRoundRect(box, boxRadius, boxRadius, Path.Direction.CW)
            val save = canvas.save()
            canvas.clipOutPath(hole)
            drawGrown(canvas, dp(GLOW_HAZE_SPREAD_DP), haze)
            drawGrown(canvas, dp(GLOW_CORE_SPREAD_DP), core)
            canvas.restoreToCount(save)
        }

        /** The box grown by [spread] on every side, blurred by [paint]. */
        private fun drawGrown(canvas: Canvas, spread: Float, paint: Paint) {
            grown.set(box)
            grown.inset(-spread, -spread)
            canvas.drawRoundRect(grown, boxRadius + spread, boxRadius + spread, paint)
        }
    }

    /** Every box stroked, over the spotlight and its glow. */
    private val boxesView = object : View(context) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(BOX_STROKE_DP)
            color = accent
        }

        override fun onDraw(canvas: Canvas) {
            for (box in boxes) {
                val save = canvas.save()
                if (box.angleDeg != 0f) canvas.rotate(box.angleDeg, box.pivotX, box.pivotY)
                canvas.drawRoundRect(box.rect, boxRadius, boxRadius, stroke)
                canvas.restoreToCount(save)
            }
        }
    }

    init {
        layoutDirection = LAYOUT_DIRECTION_LTR
        addView(spotlight, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(glow, LayoutParams(0, 0, Gravity.TOP or Gravity.LEFT))
        addView(boxesView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** The boxes shown now. */
    val shownBoxes: List<TapLookupGeometry.WordBox> get() = boxes

    /** Show [list]. */
    fun setBoxes(list: List<TapLookupGeometry.WordBox>) {
        boxes = list
        boxesView.invalidate()
        showHighlight()
    }

    /** Dim the game around the box at [index] and set the glow around it,
     *  or neither for none. */
    fun setHighlight(index: Int?) {
        if (highlighted == index) return
        highlighted = index
        showHighlight()
    }

    private fun showHighlight() {
        spotlight.invalidate()
        glow.show(highlighted?.let { boxes.getOrNull(it) })
    }

    companion object {
        const val BOX_STROKE_DP = 2f
        const val BOX_RADIUS_DP = 6f
        /** 40% black over the game, the word's box the hole in it. */
        const val SPOTLIGHT_SCRIM_ALPHA = 0x66
        const val GLOW_CORE_SPREAD_DP = 2f
        const val GLOW_CORE_BLUR_DP = 5f
        const val GLOW_CORE_ALPHA = 0xFF
        const val GLOW_HAZE_SPREAD_DP = 4f
        const val GLOW_HAZE_BLUR_DP = 14f
        const val GLOW_HAZE_ALPHA = 0x99
        /** The glow view's margin past the box on every side. */
        const val GLOW_REACH_DP = 30f
    }
}
