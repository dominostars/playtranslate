package com.playtranslate.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.View.MeasureSpec
import com.playtranslate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * [DetachedWindowBar]: the order of its actions along the row, and which of
 * them fold behind the more button as the bar gets narrower. The widths the
 * fold is tried at are computed from the views' own measured widths, so the
 * table holds whatever a label measures here: at the width that just holds
 * the first k foldable actions exactly k stay inline, and one pixel less
 * keeps only what still fits beside the more button.
 */
@RunWith(RobolectricTestRunner::class)
class DetachedWindowBarTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private lateinit var activity: Host
    private lateinit var bar: DetachedWindowBar
    private lateinit var actions: DetachedWindowBar.Actions
    private var density = 1f

    private fun action(id: Int, label: String, pinned: Boolean = false) =
        HeaderAction(id, R.drawable.ic_settings, label, pinned = pinned)

    @Before fun setUp() {
        activity = Robolectric.buildActivity(Host::class.java).setup().get()
        density = activity.resources.displayMetrics.density
        actions = DetachedWindowBar.Actions(
            capture = action(R.id.detachedBarCapture, "Capture"),
            auto = action(R.id.detachedBarAuto, "Auto"),
            editRegion = action(R.id.detachedBarRegion, "Edit region"),
            settings = action(R.id.detachedBarSettings, "Settings"),
            minimize = action(R.id.detachedBarMinimize, "Minimize"),
            exit = action(R.id.detachedBarExit, "Turn Off", pinned = true),
        )
        bar = DetachedWindowBar(activity)
        bar.setActions(actions)
    }

    private fun view(a: HeaderAction): View = bar.findViewById(a.viewId)

    private fun layOut(width: Int) {
        val h = (DetachedWindowBar.HEIGHT_DP * density).toInt()
        bar.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        bar.layout(0, 0, width, h)
    }

    private val item get() = (40 * density).toInt()
    private val gap get() = (4 * density).toInt()

    /** An action's own width: an icon's fixed side, a pill's measured width. */
    private fun widthOf(a: HeaderAction): Int {
        val v = view(a)
        if (v is android.widget.ImageButton) return item
        v.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(item, MeasureSpec.EXACTLY))
        return v.measuredWidth
    }

    /** The narrowest bar that keeps the first [k] foldable actions inline. */
    private fun widthFor(k: Int): Int {
        val order = actions.foldOrder.filter { it.available }
        val n = order.size
        var sum = item // Exit
        var count = 1
        for (i in 0 until k) { sum += widthOf(order[i]); count++ }
        if (k < n) { sum += item; count++ }
        return bar.paddingLeft + bar.paddingRight + sum + gap * count
    }

    private fun inline(): List<HeaderAction> = actions.foldOrder.filter { view(it).visibility == View.VISIBLE }

    @Test fun `with room for everything the row runs Exit, Minimize, then Settings, Edit region, Auto and Capture at the end`() {
        layOut(widthFor(5) + 200)

        assertEquals(actions.foldOrder, inline())
        assertEquals(View.GONE, bar.moreButton.visibility)
        val leftToRight = listOf(
            actions.exit, actions.minimize, actions.settings, actions.editRegion, actions.auto, actions.capture,
        ).map { view(it).left }
        assertEquals("in order along the row", leftToRight.sorted(), leftToRight)
        assertTrue("no two share a place", leftToRight.distinct().size == leftToRight.size)
        // The gap falls between Minimize and Settings: Capture ends at the row's end.
        assertEquals(bar.width - bar.paddingRight, view(actions.capture).right)
        assertTrue(view(actions.settings).left - view(actions.minimize).right > 100)
    }

    /** How many foldable actions the fold rule keeps inline at [width]: the
     *  most whose row still fits. The more button takes a slot of its own,
     *  so an icon no wider than it never folds alone (HeaderFit), and the
     *  row for four is as wide as the row for all five. */
    private fun fitsAt(width: Int): Int = (5 downTo 0).first { widthFor(it) <= width }

    @Test fun `as the bar narrows the actions fold in order, Minimize first and Capture last`() {
        val order = actions.foldOrder
        val seen = mutableSetOf<Int>()
        for (k in 5 downTo 0) {
            for (width in listOf(widthFor(k), widthFor(k) - 1)) {
                if (width < widthFor(0)) continue
                val fits = fitsAt(width)
                seen += fits
                layOut(width)
                assertEquals("at $width px: the first $fits stay", order.take(fits), inline())
                assertEquals("the folded ones, in order", order.drop(fits), bar.foldedActions())
                assertEquals(if (fits < 5) View.VISIBLE else View.GONE, bar.moreButton.visibility)
                assertEquals("Exit never folds", View.VISIBLE, view(actions.exit).visibility)
            }
        }
        // The steps the widths above reach: everything, then Settings and
        // Minimize folded together (two icons for the more button's slot),
        // then Edit region, Auto and Capture one at a time.
        assertEquals(setOf(5, 3, 2, 1, 0), seen)
    }

    @Test fun `the more button sits right after Exit`() {
        layOut(widthFor(2))

        assertEquals(View.VISIBLE, bar.moreButton.visibility)
        assertTrue(bar.moreButton.left >= view(actions.exit).right)
        assertTrue(bar.moreButton.right <= view(actions.auto).left)
    }

    @Test fun `an action that is not available takes no place and is not folded`() {
        actions.editRegion.available = false

        // Exactly the room the four that are left need: a place kept for the
        // fifth would fold one of them.
        layOut(widthFor(4))
        assertEquals(View.GONE, view(actions.editRegion).visibility)
        assertEquals(listOf(actions.capture, actions.auto, actions.settings, actions.minimize), inline())
        assertEquals(View.GONE, bar.moreButton.visibility)

        layOut(widthFor(1))
        assertEquals(listOf(actions.auto, actions.settings, actions.minimize), bar.foldedActions())
    }

    @Test fun `a tap runs the action with its own view as the anchor`() {
        val ran = mutableListOf<View>()
        actions.settings.onClick = { ran += it }
        layOut(widthFor(5))

        view(actions.settings).performClick()

        assertEquals(listOf(view(actions.settings)), ran)
    }
}
