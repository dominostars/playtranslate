package com.playtranslate.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.SeekBar
import com.playtranslate.R
import com.playtranslate.themeColor
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A single-handle whole-number slider drawn like the results Text size
 * picker ([SliderTrackPainter]). The handle rests grey at the range's low
 * end, the "off" position, and turns accent at any other value.
 *
 * Touch: a grabbed handle moves from where it is; a tap on bare track jumps
 * it there. Focusable for gamepads and keyboards: left/right step one
 * (mirrored in a right-to-left layout). TalkBack sees a SeekBar with an
 * integer range and can step or set it.
 */
class SizeSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var valueFrom: Int = 0
        private set
    var valueTo: Int = 1
        private set

    /** Current value; setting it clamps and redraws but doesn't notify. */
    var value: Int = 0
        set(v) {
            val clamped = v.coerceIn(valueFrom, valueTo)
            if (field == clamped) return
            field = clamped
            invalidate()
        }

    /** Called after every change the user makes (drag, tap, key, TalkBack). */
    var onValueChange: ((Int) -> Unit)? = null

    private val painter = SliderTrackPainter(context)
    private val accent = context.themeColor(R.attr.ptAccent)
    private val resting = context.themeColor(R.attr.ptTextMuted)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.ptAccentTint)
    }

    /** Handle x − finger x at grab time, so a dragged handle moves from where
     *  it is instead of jumping under the finger. */
    private var grabOffset = 0f

    init {
        isFocusable = true
    }

    fun setRange(from: Int, to: Int) {
        require(to > from) { "empty range $from..$to" }
        valueFrom = from
        valueTo = to
        value = value
        invalidate()
    }

    private val isRtl: Boolean get() = layoutDirection == LAYOUT_DIRECTION_RTL

    private fun xFor(v: Int): Float {
        val x = painter.xFor(v, valueFrom, valueTo, width)
        return if (isRtl) width - x else x
    }

    private fun valueFor(x: Float): Int =
        painter.valueFor(if (isRtl) width - x else x, valueFrom, valueTo, width)

    private fun setFromUser(v: Int) {
        val clamped = v.coerceIn(valueFrom, valueTo)
        if (clamped == value) return
        value = clamped
        onValueChange?.invoke(clamped)
        // What SeekBar sends on a change, so TalkBack reads the new value.
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = resolveSize((painter.touchHalf * 2).roundToInt(), heightMeasureSpec)
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        painter.drawTrack(canvas, painter.touchHalf, width - painter.touchHalf, cy)
        val x = xFor(value)
        if (isFocused) canvas.drawCircle(x, cy, painter.touchHalf / 2f, haloPaint)
        painter.drawHandle(canvas, x, cy, if (value == valueFrom) resting else accent)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // The row sits in a scrolling page: keep a sideways drag ours.
                parent?.requestDisallowInterceptTouchEvent(true)
                val handleX = xFor(value)
                if (abs(event.x - handleX) <= painter.touchHalf) {
                    grabOffset = handleX - event.x
                } else {
                    grabOffset = 0f
                    setFromUser(valueFor(event.x))
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                setFromUser(valueFor(event.x + grabOffset))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val step = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (isRtl) -1 else 1
            KeyEvent.KEYCODE_DPAD_LEFT -> if (isRtl) 1 else -1
            else -> return super.onKeyDown(keyCode, event)
        }
        setFromUser(value + step)
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = SeekBar::class.java.name
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
            valueFrom.toFloat(), valueTo.toFloat(), value.toFloat(),
        )
        if (value > valueFrom) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        if (value < valueTo) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> setFromUser(value + 1)
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> setFromUser(value - 1)
            android.R.id.accessibilityActionSetProgress -> {
                val target = arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)
                    ?: return false
                setFromUser(target.roundToInt())
            }
            else -> return super.performAccessibilityAction(action, arguments)
        }
        return true
    }
}
