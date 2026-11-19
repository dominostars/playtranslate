package com.playtranslate.ui

import android.app.Activity
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.playtranslate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * [DetachedWindowBar]: what stands on it, in what order, and what gives way
 * as it gets narrower. The widths the fit is tried at are computed from the
 * views' own measured widths, so the table holds whatever a label measures
 * here: at the width that just holds the first k of Capture, Auto, Anki and
 * Read aloud exactly k stand, and one pixel less keeps one fewer.
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
    private lateinit var readAloud: HeaderAction
    private var density = 1f

    private fun action(id: Int, label: String) = HeaderAction(id, R.drawable.ic_settings, label)

    /** The order the bar keeps them in: the last is the first to go. */
    private lateinit var order: List<HeaderAction>

    /** Each one's width with its name on, as measured before the first fit. */
    private lateinit var labelled: Map<HeaderAction, Int>

    @Before fun setUp() {
        activity = Robolectric.buildActivity(Host::class.java).setup().get()
        density = activity.resources.displayMetrics.density
        actions = DetachedWindowBar.Actions(
            capture = action(R.id.detachedBarCapture, "Capture"),
            auto = action(R.id.detachedBarAuto, "Auto"),
            anki = action(R.id.detachedBarAnki, "Anki"),
            minimize = action(R.id.detachedBarMinimize, "Minimize"),
        )
        bar = DetachedWindowBar(activity)
        bar.setActions(actions)
        readAloud = action(R.id.btnSpeakOriginal, "Read aloud")
        bar.setReadAloud(readAloud)
        bar.shortcutsOffered = true
        order = listOf(actions.capture, actions.auto, actions.anki, readAloud)
        labelled = order.associateWith(::widthOf)
    }

    private fun view(a: HeaderAction): View = bar.findViewById(a.viewId)

    private fun layOut(width: Int) {
        val h = (DetachedWindowBar.HEIGHT_DP * density).toInt()
        bar.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        bar.layout(0, 0, width, h)
    }

    private val item get() = (40 * density).toInt()
    private val gap get() = (4 * density).toInt()

    private fun widthOf(a: HeaderAction): Int {
        val v = view(a)
        v.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(item, MeasureSpec.EXACTLY))
        return v.measuredWidth
    }

    /** The narrowest bar that holds the first [k] of [of], Minimize and the
     *  menu button always standing; Capture and Auto with their names, or
     *  as icons under [iconOnly]. */
    private fun widthFor(k: Int, iconOnly: Boolean = false, of: List<HeaderAction> = order): Int {
        var sum = item * 2 // Minimize and the menu button
        var count = 2
        for (i in 0 until k) {
            sum += if (iconOnly) item else labelled.getValue(of[i])
            count++
        }
        return bar.paddingLeft + bar.paddingRight + sum + gap * count
    }

    private fun standing(): List<HeaderAction> = order.filter { view(it).visibility == View.VISIBLE }

    private fun labelOf(a: HeaderAction): TextView = (view(a) as ViewGroup).getChildAt(1) as TextView

    private fun assertAlwaysThere() {
        assertEquals("Minimize always stands", View.VISIBLE, view(actions.minimize).visibility)
        assertEquals("the menu button always stands", View.VISIBLE, bar.moreButton.visibility)
    }

    @Test fun `with room and a result on show the row runs Minimize, the menu button, then Read aloud, Anki, Auto and Capture at the end`() {
        layOut(widthFor(4) + 200)

        assertEquals(order, standing())
        assertAlwaysThere()
        val leftToRight = listOf(
            view(actions.minimize), bar.moreButton, view(readAloud), view(actions.anki),
            view(actions.auto), view(actions.capture),
        ).map { it.left }
        assertEquals("in order along the row", leftToRight.sorted(), leftToRight)
        assertTrue("no two share a place", leftToRight.distinct().size == leftToRight.size)
        // The gap falls after the menu button: Capture ends at the row's end.
        assertEquals(bar.width - bar.paddingRight, view(actions.capture).right)
        assertTrue(view(readAloud).left - bar.moreButton.right > 100)
        assertTrue("nothing folded for the menu to list", bar.foldedActions().isEmpty())
        for (a in order) assertEquals("${a.label} by name", View.VISIBLE, labelOf(a).visibility)
    }

    @Test fun `with no result on show the bar is Minimize, the menu button, Auto and Capture, however wide`() {
        bar.shortcutsOffered = false

        layOut(widthFor(4) + 200)

        assertEquals(listOf(actions.capture, actions.auto), standing())
        assertEquals(View.GONE, view(actions.anki).visibility)
        assertEquals(View.GONE, view(readAloud).visibility)
        assertAlwaysThere()
        assertTrue("a shortcut not shown is not folded: the menu lists it anyway", bar.foldedActions().isEmpty())
    }

    /** What stands at [width], and whether Capture and Auto are icons: the
     *  most that fits with the names on, unless Capture or Auto itself would
     *  go, and then the most of those two that fits as icons. */
    private fun expectedAt(width: Int): Pair<Int, Boolean> {
        val withNames = (4 downTo 0).first { widthFor(it) <= width }
        if (withNames >= 2) return withNames to false
        return ((2 downTo 0).firstOrNull { widthFor(it, iconOnly = true) <= width } ?: 0) to true
    }

    @Test fun `as the bar narrows Read aloud goes, then Anki, then Capture and Auto drop their names, then Auto folds and last Capture`() {
        val seen = mutableSetOf<Pair<Int, Boolean>>()
        val widths = (4 downTo 0).flatMap { listOf(widthFor(it), widthFor(it) - 1) } +
            (2 downTo 0).flatMap { listOf(widthFor(it, iconOnly = true), widthFor(it, iconOnly = true) - 1) }
        for (width in widths.filter { it >= widthFor(0) }) {
            val (fits, iconOnly) = expectedAt(width)
            seen += fits to iconOnly
            layOut(width)
            assertEquals("at $width px: the first $fits stand", order.take(fits), standing())
            assertEquals(
                "only Capture and Auto ever fold into the menu",
                listOf(actions.capture, actions.auto).drop(fits),
                bar.foldedActions(),
            )
            assertEquals("Capture and Auto as icons", iconOnly, bar.pillsIconOnly)
            if (iconOnly) assertTrue("no shortcut on a bar that narrow", fits <= 2)
            for (a in standing().drop(2)) assertEquals("a shortcut keeps its name", View.VISIBLE, labelOf(a).visibility)
            assertAlwaysThere()
        }
        for (k in 4 downTo 2) assertTrue("$k with names", (k to false) in seen)
        assertTrue("neither Capture nor Auto goes with its name on", seen.none { (fits, iconOnly) -> fits < 2 && !iconOnly })
        assertTrue("as icons both still fit where one named did not", (2 to true) in seen)
        assertTrue("then Auto folds, Capture the last to go", (1 to true) in seen)
    }

    @Test fun `where Capture or Auto would go with its name on, both stand as round icon buttons`() {
        layOut(widthFor(2))
        assertFalse(bar.pillsIconOnly)
        assertEquals(View.VISIBLE, labelOf(actions.capture).visibility)

        layOut(widthFor(2) - 1)
        assertTrue(bar.pillsIconOnly)
        assertEquals(View.GONE, labelOf(actions.capture).visibility)
        assertEquals(View.GONE, labelOf(actions.auto).visibility)
        assertEquals("the size of the other buttons", item, view(actions.capture).width)
        assertEquals(item, view(actions.auto).width)
        assertEquals(listOf(actions.capture, actions.auto), standing())

        layOut(widthFor(2))
        assertFalse("the names come back with the room", bar.pillsIconOnly)
        assertEquals(View.VISIBLE, labelOf(actions.auto).visibility)
    }

    @Test fun `a shortcut with nothing to act on takes no place`() {
        // The Original is hidden: nothing to read aloud.
        readAloud.available = false
        bar.actionChanged(readAloud)

        // Exactly the room the three that are left need.
        layOut(widthFor(3))
        assertEquals(listOf(actions.capture, actions.auto, actions.anki), standing())
        assertEquals(View.GONE, view(readAloud).visibility)

        readAloud.available = true
        bar.actionChanged(readAloud)
        layOut(widthFor(4))
        assertEquals(order, standing())
    }

    @Test fun `Read aloud shows its state, and the bar tells an open menu of every change it is told of`() {
        layOut(widthFor(4))
        val heard = mutableListOf<HeaderAction>()
        var lists = 0
        bar.addListener(object : FoldingActionRow.Listener {
            override fun onActionChanged(action: HeaderAction) { heard += action }
            override fun onFoldChanged(row: FoldingActionRow) { lists++ }
        })
        val idle = labelOf(readAloud).currentTextColor

        readAloud.active = true
        bar.actionChanged(readAloud)
        assertNotEquals("accent while it reads", idle, labelOf(readAloud).currentTextColor)
        assertEquals(listOf(readAloud), heard)

        val menuOnly = action(R.id.detachedMenuRow, "Edit region")
        bar.actionChanged(menuOnly)
        assertEquals("one that is only the menu's is passed on too", listOf(readAloud, menuOnly), heard)

        bar.menuChanged()
        assertEquals(1, lists)
    }

    @Test fun `the page's Read aloud comes and goes with the page, before Anki`() {
        bar.setReadAloud(null)
        assertNull(bar.findViewById<View?>(R.id.btnSpeakOriginal))
        layOut(widthFor(4) + 200)
        for (a in listOf(actions.capture, actions.auto, actions.anki)) assertEquals(View.VISIBLE, view(a).visibility)

        val next = action(R.id.btnSpeakOriginal, "Read aloud")
        bar.setReadAloud(next)
        layOut(widthFor(4) + 200)
        assertEquals(View.VISIBLE, view(next).visibility)
        assertTrue(view(next).right <= view(actions.anki).left)
        assertTrue(view(next).left >= bar.moreButton.right)
    }

    @Test fun `a tap runs the action with its own view as the anchor`() {
        val ran = mutableListOf<View>()
        actions.minimize.onClick = { ran += it }
        readAloud.onClick = { ran += it }
        layOut(widthFor(4))

        view(actions.minimize).performClick()
        view(readAloud).performClick()

        assertEquals(listOf(view(actions.minimize), view(readAloud)), ran)
    }

    @Test fun `a hold on the Anki shortcut runs its hold, and no tap`() {
        var taps = 0
        var holds = 0
        actions.anki.onClick = { taps++ }
        bar.setOnHold(actions.anki) { holds++ }
        layOut(widthFor(4))

        assertTrue(view(actions.anki).performLongClick())

        assertEquals(1, holds)
        assertEquals(0, taps)
    }

    @Test fun `a change of what folded is told to an open menu, after the layout that changed it`() {
        // In a window, where a view's posts run: the bar at a width of its
        // own inside the activity's content.
        val h = (DetachedWindowBar.HEIGHT_DP * density).toInt()
        val holder = FrameLayout(activity)
        holder.addView(bar, FrameLayout.LayoutParams(widthFor(4), h))
        activity.setContentView(holder)
        var lists = 0
        bar.addListener(object : FoldingActionRow.Listener {
            override fun onFoldChanged(row: FoldingActionRow) { lists++ }
        })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(order, standing())
        assertEquals("nothing folded, nothing to tell", 0, lists)

        bar.layoutParams = FrameLayout.LayoutParams(widthFor(1, iconOnly = true), h)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(actions.auto), bar.foldedActions())
        assertEquals(1, lists)
    }
}
