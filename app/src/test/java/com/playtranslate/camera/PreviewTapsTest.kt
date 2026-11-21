package com.playtranslate.camera

import android.content.Context
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.ui.TapLookupGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * A touch on the camera's live preview ([PreviewTaps]): a tap on a word of
 * the live Definitions outlines is the word's, and takes its snapshot;
 * every other release is the focus point, a drag or a several-finger
 * gesture that ends over a word included.
 */
@RunWith(RobolectricTestRunner::class)
class PreviewTapsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val view = View(ctx)

    /** One word, over (100, 100) to (200, 130). */
    private val word = CameraSession.LiveWord(
        TapLookupGeometry.WordBox(0, 0, 0, "word", RectF(100f, 100f, 200f, 130f)),
        lineKey = 0,
        lineText = "word",
    )

    private val words = mutableListOf<String>()
    private val focuses = mutableListOf<Pair<Float, Float>>()

    private val taps = PreviewTaps(
        ctx,
        wordAt = { x, y -> word.takeIf { x in 100f..200f && y in 100f..130f } },
        onWord = { w, rawX, rawY, down -> words += "${w.surface}@$rawX,$rawY down=$down" },
        onFocus = { x, y -> focuses += x to y },
    )

    private fun send(downTime: Long, at: Long, action: Int, x: Float, y: Float) {
        val ev = MotionEvent.obtain(downTime, at, action, x, y, 0)
        assertTrue(taps.onTouch(view, ev))
        ev.recycle()
    }

    /** A second finger at ([x], [y]) joining or leaving, the first at (150, 115). */
    private fun sendSecondFinger(downTime: Long, at: Long, down: Boolean, x: Float, y: Float) {
        val props = Array(2) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { this.x = 150f; this.y = 115f },
            MotionEvent.PointerCoords().apply { this.x = x; this.y = y },
        )
        val base = if (down) MotionEvent.ACTION_POINTER_DOWN else MotionEvent.ACTION_POINTER_UP
        val action = base or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val ev = MotionEvent.obtain(downTime, at, action, 2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        taps.onTouch(view, ev)
        ev.recycle()
    }

    @Test fun `a tap on a word is the word's, with where and when the finger came down, and moves no focus`() {
        val t = SystemClock.uptimeMillis()
        send(t, t, MotionEvent.ACTION_DOWN, 150f, 115f)
        send(t, t + 60, MotionEvent.ACTION_UP, 151f, 116f)
        assertEquals(listOf("word@151.0,116.0 down=$t"), words)
        assertTrue(focuses.isEmpty())
    }

    @Test fun `a tap off every word is the focus point`() {
        val t = SystemClock.uptimeMillis()
        send(t, t, MotionEvent.ACTION_DOWN, 400f, 300f)
        send(t, t + 60, MotionEvent.ACTION_UP, 400f, 300f)
        assertTrue(words.isEmpty())
        assertEquals(listOf(400f to 300f), focuses)
    }

    @Test fun `a drag that ends over a word takes no snapshot, and is the focus point as any release was`() {
        val t = SystemClock.uptimeMillis()
        send(t, t, MotionEvent.ACTION_DOWN, 500f, 400f)
        send(t, t + 40, MotionEvent.ACTION_MOVE, 300f, 250f)
        send(t, t + 80, MotionEvent.ACTION_MOVE, 150f, 115f)
        send(t, t + 120, MotionEvent.ACTION_UP, 150f, 115f)
        assertTrue(words.isEmpty())
        assertEquals(listOf(150f to 115f), focuses)
    }

    @Test fun `a finger that rests on a word before lifting still tapped it`() {
        val t = SystemClock.uptimeMillis()
        send(t, t, MotionEvent.ACTION_DOWN, 150f, 115f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        send(t, t + 2000, MotionEvent.ACTION_UP, 150f, 115f)
        assertEquals(1, words.size)
        assertTrue(focuses.isEmpty())
    }

    @Test fun `a gesture a second finger joined is never a word's, though it ends over one`() {
        val t = SystemClock.uptimeMillis()
        send(t, t, MotionEvent.ACTION_DOWN, 150f, 115f)
        sendSecondFinger(t, t + 20, down = true, x = 400f, y = 300f)
        sendSecondFinger(t, t + 60, down = false, x = 400f, y = 300f)
        send(t, t + 90, MotionEvent.ACTION_UP, 150f, 115f)
        assertTrue(words.isEmpty())
        assertEquals(1, focuses.size)
    }
}
