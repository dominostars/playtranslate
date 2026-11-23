package com.playtranslate.ui

import android.app.Activity
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.playtranslate.R
import com.playtranslate.themeColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * [ActionOverflowMenu], the table behind a section header's ⋯: its rows are
 * the header's folded, available actions in order, painted from their
 * models (and repainted live); a tap closes the menu and THEN runs the
 * action with ⋯ as its anchor; no row has a hold; a row outliving its menu
 * does nothing; a change to the
 * header's fold closes the menu, while a re-layout that folds the same
 * actions leaves it open.
 */
@RunWith(RobolectricTestRunner::class)
class ActionOverflowMenuTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private lateinit var root: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var row: SectionHeaderRow
    private lateinit var popovers: PopoverHost
    private lateinit var edit: HeaderAction
    private lateinit var size: HeaderAction

    /** (action, anchor, a popover was still showing when it ran). */
    private val runs = mutableListOf<Triple<String, View, Boolean>>()

    private var allInline = 0

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(Host::class.java).setup().get()
        root = FrameLayout(activity)
        column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column)
        activity.setContentView(root)
        LayoutInflater.from(activity).inflate(R.layout.section_source, column, true)
        row = column.findViewById(R.id.sourceHeaderRow)
        column.findViewById<TextView>(R.id.labelOriginal).text = ""
        fun action(id: Int, name: String) = HeaderAction(id, R.drawable.ic_edit, name).apply {
            onClick = { v -> runs += Triple(name, v, popovers.isShowing) }
        }
        edit = action(R.id.btnEditOriginal, "Edit text")
        size = action(R.id.btnFontSizeOriginal, "Text size")
        row.setActions(
            listOf(
                action(R.id.btnToggleFurigana, "Furigana"), action(R.id.btnSpeakOriginal, "Read aloud"),
                edit, size,
                HeaderAction(R.id.btnToggleOriginal, R.drawable.ic_visibility, "eye", pinned = true),
            ),
            R.id.btnMoreOriginal,
        )
        popovers = PopoverHost(root)
        val gap = (16 * activity.resources.displayMetrics.density).toInt()
        allInline = row.paddingLeft + row.paddingRight + 5 * row.moreButton.layoutParams.width + 5 * gap
        layoutAt(allInline - 1)   // folds Edit and Text size
    }

    private fun layoutAt(width: Int, hostHeight: Int = 1500) {
        column.layoutParams = FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(hostHeight, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1000, hostHeight)
    }

    private fun open() = popovers.show(ActionOverflowMenu(row), row.moreButton)
    private fun rows(): List<View> = popovers.navActions().orEmpty().map { it.view }
    private fun labelOf(v: View) = v.findViewById<TextView>(R.id.overflowRowLabel).text.toString()
    private fun tintOf(v: View) = v.findViewById<ImageView>(R.id.overflowRowIcon).imageTintList?.defaultColor
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `rows are the folded available actions, in order, painted from their models`() {
        size.active = true
        open()
        assertEquals(listOf("Edit text", "Text size"), rows().map(::labelOf))
        val ctx = row.context
        assertEquals(ctx.themeColor(R.attr.ptTextMuted), tintOf(rows()[0]))
        assertEquals(ctx.themeColor(R.attr.ptAccent), tintOf(rows()[1]))
    }

    @Test
    fun `a row repaints live while the menu is open`() {
        open()
        size.active = true
        assertEquals(row.context.themeColor(R.attr.ptAccent), tintOf(rows()[1]))
    }

    @Test
    fun `a tap closes the menu, then runs the action anchored on more`() {
        open()
        rows()[0].performClick()
        assertEquals(listOf(Triple("Edit text", row.moreButton as View, false)), runs)
        assertFalse(popovers.isShowing)
    }

    @Test
    fun `no row has a long-press`() {
        open()
        for (r in rows()) assertFalse(r.isLongClickable)
    }

    @Test
    fun `a row outliving its menu does nothing`() {
        open()
        val stale = rows()[0]
        popovers.dismiss()
        stale.performClick()
        assertEquals(emptyList<Triple<String, View, Boolean>>(), runs)
    }

    @Test
    fun `nav targets are the rows, none of them a hold`() {
        open()
        assertEquals(listOf(false, false), popovers.navActions()?.map { it.holdActivates })
    }

    @Test
    fun `the same fold again keeps it open, a new fold closes it`() {
        open()
        layoutAt(allInline - 1)
        idle()
        assertTrue(popovers.isShowing)
        layoutAt(allInline)
        idle()
        assertFalse(popovers.isShowing)
    }

    @Test
    fun `an action going unavailable under it closes it`() {
        open()
        edit.available = false
        layoutAt(allInline - 1)
        idle()
        assertFalse(popovers.isShowing)
    }

    @Test
    fun `in a host too short for it the rows scroll, every one reachable`() {
        val short = (70 * row.resources.displayMetrics.density).toInt()
        layoutAt(allInline - 1, hostHeight = short)
        open()
        val box = root.getChildAt(root.childCount - 1)
        assertTrue("capped to the host", box.layoutParams.height <= short)
        layoutAt(allInline - 1, hostHeight = short)   // lay the card out
        val last = rows().last()
        val scroll = last.parent.parent as ScrollView
        assertTrue("two rows don't fit in the capped card", scroll.canScrollVertically(1))
        last.revealInScrollingAncestor()
        assertTrue(scroll.scrollY > 0)
        assertTrue("the last row now sits inside the card", last.bottom - scroll.scrollY <= scroll.height)
        last.performClick()
        assertEquals(listOf(Triple("Text size", row.moreButton as View, false)), runs)
    }

    @Test
    fun `under RTL every heading sits at the start edge, a Latin one too`() {
        val grouped = object : FoldingActionRow {
            override fun foldedActions() = emptyList<HeaderAction>()
            override fun menuGroups() = listOf(
                MenuGroup("هذه النتيجة", listOf(edit)),
                MenuGroup("PlayTranslate", listOf(size)),
            )
            override val moreAnchor: View get() = row.moreButton
            override fun addListener(listener: FoldingActionRow.Listener) {}
            override fun removeListener(listener: FoldingActionRow.Listener) {}
        }
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        popovers.show(ActionOverflowMenu(grouped), row.moreButton)
        layoutAt(allInline - 1)   // lay the card out
        val list = rows().first().parent as LinearLayout
        val headings = (0 until list.childCount).map(list::getChildAt)
            .filterIsInstance<TextView>().filter { it.isAccessibilityHeading }
        assertEquals(listOf("هذه النتيجة", "PlayTranslate"), headings.map { it.text.toString() })
        for (h in headings) {
            val layout = h.layout
            assertTrue("«${h.text}» is narrower than its line", layout.getLineWidth(0) < layout.width)
            assertEquals("«${h.text}» ends at the right edge", layout.width.toFloat(), layout.getLineRight(0), 0.5f)
        }
    }

    @Test
    fun `the card is at least its minimum width`() {
        open()
        val box = root.getChildAt(root.childCount - 1)
        assertTrue(box.layoutParams.width >= (160 * row.resources.displayMetrics.density).toInt())
    }
}
