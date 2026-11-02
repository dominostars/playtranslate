package com.playtranslate.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import com.playtranslate.R
import com.playtranslate.overlayThemedContext
import com.playtranslate.themeColor
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The tap-to-lookup window over the game ([TapLookupSession]): a full-screen
 * root that strokes a rounded rect around every word box, dims the game
 * around the highlighted one and sets an accent glow around it (the
 * controller cursor's box, or the selected one's; [setHighlight]), and
 * carries the X over the floating icon:
 * the icon's own disc ([IconDiscStyle]) made opaque, its centre on the dock
 * line where the icon rests ([Dock], from [closeDock]), so its inner half
 * shows, with a small X in that half. The disc is there from the window's
 * first frame, a spinner in the X's place until the boxes arrive.
 *
 * It decides nothing. Every DOWN goes to [onDown] with its screen position
 * (the root sits at the display origin, so view and screen coordinates are
 * the same), the X included: the session judges every tap in one place,
 * whichever window it reached ([closeHit]). Keys go to [onKey] after the
 * key-pair bookkeeping; Back reaches [onBack] as a key on the UP of a DOWN
 * this window saw, or, where the platform no longer sends apps the back
 * key (Android 16 at target 36), as an [OnBackInvokedCallback].
 */
internal class TapLookupOverlay(
    ctx: Context,
    /** Where the floating icon rests on this display now, read each time
     *  the X is placed (the boxes' arrival, a change of the insets). */
    private val closeDock: () -> Dock,
    private val onDown: (rawX: Float, rawY: Float) -> Unit,
    private val onKey: (KeyEvent) -> Boolean,
    private val onBack: () -> Unit,
) : FrameLayout(overlayThemedContext(ctx)) {

    /** The icon's rest: the centre of its disc, on the dock line of the
     *  left or the right edge ([IconDock]). */
    data class Dock(val centreX: Float, val centreY: Float, val edgeLeft: Boolean)

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val accent = context.themeColor(R.attr.ptAccent)
    private val discRadius = dp(IconDiscStyle.DIAMETER_DP) / 2f

    private var boxes: List<TapLookupGeometry.WordBox> = emptyList()
    /** The box the spotlight leaves undimmed and the glow is around, or
     *  none (no dimming, no glow). */
    private var highlighted: Int? = null

    private val boxRadius = dp(BOX_RADIUS_DP)

    /** The spotlight: a dark scrim over the whole game with a hole the
     *  highlighted box's shape, so the word is the only part not dimmed,
     *  whatever the game's colours. Under everything else. */
    private val spotlight = object : View(context) {
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
     * the box only (the word stays clear): a tight bright core and a wider
     * soft haze. A view of its own, the box plus [GLOW_REACH_DP] on every
     * side, turned with a slanted box, on a software layer, so the blur does
     * not rest on the hardware renderer's support for mask filters.
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

    /** How far the X's centre sits inboard of the dock line: the middle of
     *  the disc's inner half, moved [CLOSE_GLYPH_EDGE_SHIFT_DP] toward the
     *  edge (device review). The X and the spinner that stands in for it
     *  while the read runs share it. */
    private val glyphInset = discRadius / 2f - dp(CLOSE_GLYPH_EDGE_SHIFT_DP)

    /** The X: the icon's disc, a [discRadius] circle centred in this view,
     *  which is placed so the circle's centre sits on the dock line; the
     *  half past the line lies off the display. Its fill is the icon's made
     *  opaque, since the icon itself lies under it and would show through
     *  the icon's own 80%. The X, small, sits [glyphInset] inboard of the
     *  line, drawn once the boxes are up ([loading] off). */
    private val closeButton = object : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(
                Color.red(IconDiscStyle.FILL), Color.green(IconDiscStyle.FILL), Color.blue(IconDiscStyle.FILL),
            )
        }
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(IconDiscStyle.BORDER_DP)
            color = IconDiscStyle.BORDER
        }
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(CLOSE_GLYPH_STROKE_DP)
            strokeCap = Paint.Cap.ROUND
            color = IconDiscStyle.GLYPH
        }
        var edgeLeft = false
            set(value) { if (field != value) { field = value; invalidate() } }
        /** True until the boxes arrive: the spinner stands in the X's place. */
        var loading = true
            set(value) { if (field != value) { field = value; invalidate() } }

        override fun onDraw(canvas: Canvas) {
            val c = discRadius
            // Nothing past the dock line, as the icon draws: off the display
            // on its edge, or over a navigation bar it docks inboard of.
            if (edgeLeft) canvas.clipRect(c, 0f, 2 * c, 2 * c) else canvas.clipRect(0f, 0f, c, 2 * c)
            canvas.drawCircle(c, c, discRadius, fill)
            canvas.drawCircle(c, c, discRadius - rim.strokeWidth / 2f, rim)
            if (loading) return
            val gx = if (edgeLeft) c + glyphInset else c - glyphInset
            val s = dp(CLOSE_GLYPH_HALF_DP)
            canvas.drawLine(gx - s, c - s, gx + s, c + s, glyph)
            canvas.drawLine(gx - s, c + s, gx + s, c - s, glyph)
        }
    }

    /** The loading indicator: centred where the X is drawn on the disc,
     *  twice its span, in its silver, until the boxes arrive. */
    private val spinner = ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(IconDiscStyle.GLYPH)
    }

    /** Where the X's disc is centred, once placed ([placeCloseButton]). */
    private var dock: Dock? = null

    /** Back is acted on at the UP of a DOWN this window saw, so a back
     *  pressed before the window existed cannot close it on release. */
    private var backDownSeen = false

    /** Pair bookkeeping and the removal linger ([remove]). */
    private val keyGuard = WindowKeyPairGuard()

    private var backCallback: Any? = null

    init {
        // Absolute directions throughout: a root WindowManager window takes
        // the configuration's layout direction, and the boxes are absolute
        // screen geometry.
        layoutDirection = LAYOUT_DIRECTION_LTR
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        addView(spotlight, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(glow, LayoutParams(0, 0, Gravity.TOP or Gravity.LEFT))
        addView(boxesView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val size = (discRadius * 2).roundToInt()
        closeButton.contentDescription = context.getString(R.string.cd_close)
        addView(closeButton, LayoutParams(size, size, Gravity.TOP or Gravity.LEFT))
        val spin = dp(CLOSE_SPINNER_DP).roundToInt()
        addView(spinner, LayoutParams(spin, spin, Gravity.TOP or Gravity.LEFT))
        placeCloseButton()
    }

    /** Show [list], and the X in the spinner's place. */
    fun setBoxes(list: List<TapLookupGeometry.WordBox>) {
        boxes = list
        spinner.visibility = GONE
        closeButton.loading = false
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

    /** True when ([rawX], [rawY]) lands on the X: within its disc, grown by
     *  [CLOSE_SLOP_DP]; only its inner half is on the display to be tapped.
     *  False while the X is not shown. */
    fun closeHit(rawX: Float, rawY: Float): Boolean {
        if (closeButton.visibility != VISIBLE) return false
        val d = dock ?: return false
        return hypot(rawX - d.centreX, rawY - d.centreY) <= discRadius + dp(CLOSE_SLOP_DP)
    }

    /** Put the X's disc on the icon's rest as it is now, and the spinner
     *  where the X is drawn on it. */
    private fun placeCloseButton() {
        val d = closeDock()
        dock = d
        closeButton.edgeLeft = d.edgeLeft
        closeButton.translationX = d.centreX - discRadius
        closeButton.translationY = d.centreY - discRadius
        val half = dp(CLOSE_SPINNER_DP) / 2f
        val glyphX = if (d.edgeLeft) d.centreX + glyphInset else d.centreX - glyphInset
        spinner.translationX = glyphX - half
        spinner.translationY = d.centreY - half
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        // A bar the icon docks inboard of came or went (the
        // MediaProjection backend): the icon moves, and the X with it.
        placeCloseButton()
        return super.onApplyWindowInsets(insets)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (keyGuard.isLingering) return true
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) onDown(ev.rawX, ev.rawY)
        // Modal: the rest of every gesture stays here, and nothing reaches
        // the game beneath.
        return true
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (keyGuard.isLingering) return keyGuard.lingerKey(ev)
        keyGuard.track(ev)
        if (ControllerKeys.isBack(ev.keyCode)) {
            when (ev.action) {
                KeyEvent.ACTION_DOWN -> if (ev.repeatCount == 0) backDownSeen = true
                KeyEvent.ACTION_UP -> if (backDownSeen) {
                    backDownSeen = false
                    onBack()
                }
            }
            return true
        }
        if (onKey(ev)) return true
        return super.dispatchKeyEvent(ev)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerBackCallback()
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) unregisterBackCallback()
        super.onDetachedFromWindow()
    }

    /** Back as the platform delivers it to an app that opts into (or, at
     *  target 36 on Android 16, is given) callback-based back: the key
     *  above never arrives then. The two paths are exclusive by the
     *  platform's mode, so a press runs [onBack] once. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun registerBackCallback() {
        val dispatcher = findOnBackInvokedDispatcher() ?: return
        val callback = OnBackInvokedCallback { if (!keyGuard.isLingering) onBack() }
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        backCallback = callback
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun unregisterBackCallback() {
        val callback = backCallback as? OnBackInvokedCallback ?: return
        backCallback = null
        findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(callback)
    }

    /**
     * Take the window down through [removeWindow]. While a key that went
     * down on this window is still held, it first lingers as an invisible,
     * untouchable key sink until that key comes up ([WindowKeyPairGuard]),
     * as the sticky lens does, so the release does not land in the game.
     */
    fun remove(wm: WindowManager, params: WindowManager.LayoutParams, removeWindow: () -> Unit) {
        if (!keyGuard.hasPendingDown) {
            removeWindow()
            return
        }
        visibility = INVISIBLE
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { wm.updateViewLayout(this, params) } catch (_: Exception) {}
        keyGuard.beginLinger(removeWindow)
    }

    private companion object {
        const val BOX_STROKE_DP = 2f
        const val BOX_RADIUS_DP = 6f
        /** The spotlight's scrim over everything but the highlighted box:
         *  40% black. */
        const val SPOTLIGHT_SCRIM_ALPHA = 0x66
        /** The glow's bright core, hugging the box: grown this far past it,
         *  then blurred, so it is near full strength at the box's edge. */
        const val GLOW_CORE_SPREAD_DP = 2f
        const val GLOW_CORE_BLUR_DP = 5f
        const val GLOW_CORE_ALPHA = 0xFF
        /** The glow's soft haze past the core. */
        const val GLOW_HAZE_SPREAD_DP = 4f
        const val GLOW_HAZE_BLUR_DP = 14f
        /** 60%. */
        const val GLOW_HAZE_ALPHA = 0x99
        /** How far past the box the glow's view reaches: the haze's spread
         *  plus about three of its blur's sigmas (BlurMaskFilter's sigma is
         *  0.577 of its radius, plus half a pixel). */
        const val GLOW_REACH_DP = 30f
        /** Half the X's arm span: an X about 9dp across. */
        const val CLOSE_GLYPH_HALF_DP = 4.5f
        /** The spinner that stands in for the X: twice its span. */
        const val CLOSE_SPINNER_DP = 4 * CLOSE_GLYPH_HALF_DP
        /** How much closer to the edge than the middle of the disc's inner
         *  half the X (and the spinner) sit. */
        const val CLOSE_GLYPH_EDGE_SHIFT_DP = 2f
        const val CLOSE_GLYPH_STROKE_DP = 2f
        /** How far past the disc a tap still lands on the X. */
        const val CLOSE_SLOP_DP = 4f
    }
}
