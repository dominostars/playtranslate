package com.playtranslate.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.View.MeasureSpec
import com.playtranslate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            anki = action(R.id.detachedBarAnki, "Add to Anki"),
            editRegion = action(R.id.detachedBarRegion, "Edit region"),
            settings = action(R.id.detachedBarSettings, "Settings"),
            minimize = action(R.id.detachedBarMinimize, "Minimize"),
            exit = action(R.id.detachedBarExit, "Turn Off", pinned = true),
        )
        bar = DetachedWindowBar(activity)
        bar.setActions(actions)
        // Before any fit: the pills still carry their labels.
        labelled = actions.foldOrder.associateWith(::widthOf)
    }

    /** Each foldable action's width with its label on (the pills'), as
     *  measured before the first fit. */
    private lateinit var labelled: Map<HeaderAction, Int>

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

    /** The narrowest bar that keeps the first [k] available foldable actions
     *  inline, Exit and the more button always standing; the pills with
     *  their labels, or as icons under [iconOnly]. */
    private fun widthFor(k: Int, iconOnly: Boolean = false): Int {
        val order = actions.foldOrder.filter { it.available }
        var sum = item * 2 // Exit and the more button
        var count = 2
        for (i in 0 until k) {
            sum += if (iconOnly) item else labelled.getValue(order[i])
            count++
        }
        return bar.paddingLeft + bar.paddingRight + sum + gap * count
    }

    private fun inline(): List<HeaderAction> = actions.foldOrder.filter { view(it).visibility == View.VISIBLE }

    private fun labelOf(a: HeaderAction): View = (view(a) as android.view.ViewGroup).getChildAt(1)

    @Test fun `with room for everything the row runs Exit, More, Minimize, then Settings, Edit region, Anki, Auto and Capture at the end`() {
        layOut(widthFor(6) + 200)

        assertEquals(actions.foldOrder, inline())
        assertEquals("the more button always stands", View.VISIBLE, bar.moreButton.visibility)
        val leftToRight = listOf(
            view(actions.exit), bar.moreButton, view(actions.minimize), view(actions.settings),
            view(actions.editRegion), view(actions.anki), view(actions.auto), view(actions.capture),
        ).map { it.left }
        assertEquals("in order along the row", leftToRight.sorted(), leftToRight)
        assertTrue("no two share a place", leftToRight.distinct().size == leftToRight.size)
        // The gap falls between Minimize and Settings: Capture ends at the row's end.
        assertEquals(bar.width - bar.paddingRight, view(actions.capture).right)
        assertTrue(view(actions.settings).left - view(actions.minimize).right > 100)
    }

    /** What the fold rule keeps inline at [width], and whether the pills
     *  stand as icons: the most that fits with the labels on, unless a pill
     *  itself would fold, and then the most that fits with them as icons. */
    private fun expectedAt(width: Int): Pair<Int, Boolean> {
        val n = actions.foldOrder.count { it.available }
        val withLabels = (n downTo 0).first { widthFor(it) <= width }
        if (withLabels >= 2) return withLabels to false
        return ((n downTo 0).firstOrNull { widthFor(it, iconOnly = true) <= width } ?: 0) to true
    }

    @Test fun `as the bar narrows the actions fold one at a time, Minimize first, and the pills last of all drop their labels`() {
        val order = actions.foldOrder
        val seen = mutableSetOf<Pair<Int, Boolean>>()
        val widths = (6 downTo 0).flatMap { listOf(widthFor(it), widthFor(it) - 1) } +
            (6 downTo 0).flatMap { listOf(widthFor(it, iconOnly = true), widthFor(it, iconOnly = true) - 1) }
        for (width in widths.filter { it >= widthFor(0, iconOnly = true) }) {
            val (fits, iconOnly) = expectedAt(width)
            seen += fits to iconOnly
            layOut(width)
            assertEquals("at $width px: the first $fits stay", order.take(fits), inline())
            assertEquals("the folded ones, in order", order.drop(fits), bar.foldedActions())
            assertEquals("the pills as icons", iconOnly, bar.pillsIconOnly)
            assertEquals("the more button always stands", View.VISIBLE, bar.moreButton.visibility)
            assertEquals("Exit never folds", View.VISIBLE, view(actions.exit).visibility)
        }
        // With the labels on the actions fold one by one down to the two
        // pills; a pill never folds with its label on.
        for (k in 6 downTo 2) assertTrue("$k with labels", (k to false) in seen)
        assertTrue("no pill folds with its label on", seen.none { (fits, iconOnly) -> fits < 2 && !iconOnly })
        assertTrue("as icons both pills still fit where one labelled did not", (2 to true) in seen)
        assertTrue("then Auto folds, Capture the last to go", (1 to true) in seen)
    }

    @Test fun `where a pill with its label would fold, Capture and Auto stand as round icon buttons`() {
        layOut(widthFor(2))
        assertFalse(bar.pillsIconOnly)
        assertEquals(View.VISIBLE, labelOf(actions.capture).visibility)

        layOut(widthFor(2) - 1)
        assertTrue(bar.pillsIconOnly)
        assertEquals(View.GONE, labelOf(actions.capture).visibility)
        assertEquals(View.GONE, labelOf(actions.auto).visibility)
        assertEquals("the size of the other buttons", item, view(actions.capture).width)
        assertEquals(item, view(actions.auto).width)
        assertTrue("both still on the bar", actions.capture in inline() && actions.auto in inline())

        layOut(widthFor(2))
        assertFalse("the labels come back with the room", bar.pillsIconOnly)
        assertEquals(View.VISIBLE, labelOf(actions.auto).visibility)
    }

    @Test fun `the more button sits right after Exit`() {
        layOut(widthFor(2))

        assertEquals(View.VISIBLE, bar.moreButton.visibility)
        assertTrue(bar.moreButton.left >= view(actions.exit).right)
        assertTrue(bar.moreButton.right <= view(actions.auto).left)
    }

    @Test fun `an action that is not available takes no place and is not folded`() {
        // Anki with no result on show.
        actions.anki.available = false

        // Exactly the room the five that are left need: a place kept for the
        // sixth would fold one of them.
        layOut(widthFor(5))
        assertEquals(View.GONE, view(actions.anki).visibility)
        assertEquals(
            listOf(actions.capture, actions.auto, actions.editRegion, actions.settings, actions.minimize),
            inline(),
        )
        assertTrue(bar.foldedActions().isEmpty())

        layOut(widthFor(2))
        assertEquals(listOf(actions.editRegion, actions.settings, actions.minimize), bar.foldedActions())
    }

    @Test fun `a tap runs the action with its own view as the anchor`() {
        val ran = mutableListOf<View>()
        actions.settings.onClick = { ran += it }
        layOut(widthFor(6))

        view(actions.settings).performClick()

        assertEquals(listOf(view(actions.settings)), ran)
    }

    @Test fun `a hold on a button runs its hold, and no tap`() {
        var taps = 0
        var holds = 0
        actions.anki.onClick = { taps++ }
        bar.setOnHold(actions.anki) { holds++ }
        layOut(widthFor(6))

        assertTrue(view(actions.anki).performLongClick())

        assertEquals(1, holds)
        assertEquals(0, taps)
    }
}
