package com.playtranslate.ui

import android.app.Activity
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.playtranslate.R
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The accent indicator of both sliding segmented controls ([buildPillToggle]
 * and [buildAnkiModeToggle]) sits under the selected segment, at first
 * layout and after a tap's slide, in either layout direction. Under RTL the
 * row lays segment 0 out at the right edge, so the indicator's position has
 * to come from where the segment actually is, not from its index.
 */
@RunWith(RobolectricTestRunner::class)
class PillToggleIndicatorTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private lateinit var container: FrameLayout

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(Host::class.java).setup().get()
        container = FrameLayout(activity)
        activity.setContentView(container)
    }

    /** The container's width. Pinned in its layout params too, so the
     *  window's own traversals measure the same width as [settle]'s. */
    private var width = 300

    /** Traversals at [width] with the looper run between them, so posted
     *  work, a relayout the control requested and a tap's slide all land. */
    private fun settle() {
        container.layoutParams = FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT)
        repeat(3) {
            container.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            container.layout(0, 0, width, container.measuredHeight)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        }
    }

    private val track get() = container.getChildAt(0) as FrameLayout
    private val indicator get() = track.getChildAt(0)
    private val row get() = track.getChildAt(1) as LinearLayout

    /** Where the indicator is drawn and where segment [idx] is, both as
     *  left edges in the track's coordinates. */
    private fun assertIndicatorUnder(idx: Int) {
        val segment = row.getChildAt(idx)
        assertEquals(
            "indicator's left edge",
            (row.left + segment.left).toFloat(),
            indicator.left + indicator.translationX,
            0.5f,
        )
        assertEquals("indicator's width", segment.width.toFloat(), indicator.width.toFloat(), 1f)
    }

    private fun pillToggle(selected: Int) = buildPillToggle(
        container, listOf("Off" to 0, "Low" to 1, "Medium" to 2, "High" to 3), selected,
    ) {}

    @Test
    fun `pill toggle starts under the selected segment, LTR`() {
        pillToggle(selected = 2)
        settle()
        assertIndicatorUnder(2)
    }

    @Test
    fun `pill toggle starts under the selected segment, RTL`() {
        container.layoutDirection = View.LAYOUT_DIRECTION_RTL
        pillToggle(selected = 2)
        settle()
        assertIndicatorUnder(2)
    }

    @Test
    fun `pill toggle slides to the tapped segment, RTL`() {
        container.layoutDirection = View.LAYOUT_DIRECTION_RTL
        pillToggle(selected = 0)
        settle()
        assertIndicatorUnder(0)
        row.getChildAt(3).performClick()
        settle()
        assertIndicatorUnder(3)
    }

    @Test
    fun `pill toggle slides to the tapped segment, LTR`() {
        pillToggle(selected = 0)
        settle()
        row.getChildAt(3).performClick()
        settle()
        assertIndicatorUnder(3)
    }

    @Test
    fun `anki mode toggle starts under the selected segment, RTL`() {
        container.layoutDirection = View.LAYOUT_DIRECTION_RTL
        buildAnkiModeToggle(container, "Sentence", "Word", leftActive = false) {}
        settle()
        assertIndicatorUnder(1)
    }

    @Test
    fun `anki mode toggle slides to the tapped segment, RTL`() {
        container.layoutDirection = View.LAYOUT_DIRECTION_RTL
        buildAnkiModeToggle(container, "Sentence", "Word", leftActive = true) {}
        settle()
        assertIndicatorUnder(0)
        row.getChildAt(1).performClick()
        settle()
        assertIndicatorUnder(1)
    }

    @Test
    fun `anki mode toggle starts under the selected segment, LTR`() {
        buildAnkiModeToggle(container, "Sentence", "Word", leftActive = false) {}
        settle()
        assertIndicatorUnder(1)
    }

    @Test
    fun `pill toggle follows a resize, LTR`() {
        pillToggle(selected = 1)
        settle()
        width = 200
        settle()
        assertIndicatorUnder(1)
    }

    @Test
    fun `pill toggle follows a resize, RTL`() {
        container.layoutDirection = View.LAYOUT_DIRECTION_RTL
        pillToggle(selected = 1)
        settle()
        width = 200
        settle()
        assertIndicatorUnder(1)
    }

    @Test
    fun `anki mode toggle follows a resize, RTL`() {
        container.layoutDirection = View.LAYOUT_DIRECTION_RTL
        buildAnkiModeToggle(container, "Sentence", "Word", leftActive = false) {}
        settle()
        width = 200
        settle()
        assertIndicatorUnder(1)
    }
}
