package com.playtranslate.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.SeekBar
import com.playtranslate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [SizeSliderView] at mdpi: a 400 px wide track inset 24 px (half the 48dp
 * touch box) at each end, 6..20, so each step is 352 / 14 px.
 */
@RunWith(RobolectricTestRunner::class)
class SizeSliderViewTest {

    private val changes = mutableListOf<Int>()

    private fun slider(value: Int = 6, rtl: Boolean = false): SizeSliderView {
        val ctx = RuntimeEnvironment.getApplication().apply { setTheme(R.style.Theme_PlayTranslate) }
        return SizeSliderView(ctx).apply {
            setRange(6, 20)
            this.value = value
            if (rtl) layoutDirection = View.LAYOUT_DIRECTION_RTL
            onValueChange = { changes += it }
            measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            layout(0, 0, 400, measuredHeight)
        }
    }

    private fun xOf(value: Int) = 24f + (value - 6) * 352f / 14f

    private fun SizeSliderView.press(keyCode: Int) =
        onKeyDown(keyCode, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))

    private fun SizeSliderView.touch(action: Int, x: Float) {
        val t = SystemClock.uptimeMillis()
        onTouchEvent(MotionEvent.obtain(t, t, action, x, height / 2f, 0))
    }

    @Test
    fun heightIsTheTouchBox() {
        assertEquals(48, slider().measuredHeight)
    }

    @Test
    fun settingTheValueInCode_clampsAndDoesNotNotify() {
        val s = slider()
        s.value = 30
        assertEquals(20, s.value)
        assertTrue(changes.isEmpty())
    }

    @Test
    fun dpad_stepsOne_andStopsAtTheEnds() {
        val s = slider()
        assertTrue(s.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(s.press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(8, s.value)
        repeat(3) { s.press(KeyEvent.KEYCODE_DPAD_LEFT) }
        assertEquals(6, s.value)
        assertEquals(listOf(7, 8, 7, 6), changes)
    }

    @Test
    fun rightToLeft_mirrorsTheArrows() {
        val s = slider(value = 10, rtl = true)
        s.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(9, s.value)
        s.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(10, s.value)
    }

    @Test
    fun tapOnBareTrack_jumpsThere() {
        val s = slider()
        s.touch(MotionEvent.ACTION_DOWN, xOf(13))
        s.touch(MotionEvent.ACTION_UP, xOf(13))
        assertEquals(13, s.value)
        assertEquals(listOf(13), changes)
    }

    @Test
    fun draggingTheHandle_movesItFromWhereItIs() {
        // Grabbed 6 px right of its centre: the drag keeps that offset
        // rather than snapping the handle under the finger.
        val s = slider()
        s.touch(MotionEvent.ACTION_DOWN, xOf(6) + 6f)
        assertEquals(6, s.value)
        s.touch(MotionEvent.ACTION_MOVE, xOf(8) + 6f)
        s.touch(MotionEvent.ACTION_UP, xOf(8) + 6f)
        assertEquals(8, s.value)
    }

    @Test
    fun rightToLeft_trackRunsTheOtherWay() {
        val s = slider(rtl = true)
        s.touch(MotionEvent.ACTION_DOWN, 400f - xOf(13))
        assertEquals(13, s.value)
    }

    @Test
    fun talkBack_seesAnIntegerSeekBar_andCanSetIt() {
        val s = slider(value = 9)
        val info = AccessibilityNodeInfo.obtain()
        s.onInitializeAccessibilityNodeInfo(info)
        assertEquals(SeekBar::class.java.name, info.className)
        assertEquals(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, info.rangeInfo.type)
        assertEquals(6f, info.rangeInfo.min, 0f)
        assertEquals(20f, info.rangeInfo.max, 0f)
        assertEquals(9f, info.rangeInfo.current, 0f)

        assertTrue(s.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        assertEquals(10, s.value)
        val args = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 15f) }
        assertTrue(s.performAccessibilityAction(android.R.id.accessibilityActionSetProgress, args))
        assertEquals(15, s.value)
        assertEquals(listOf(10, 15), changes)
    }
}
