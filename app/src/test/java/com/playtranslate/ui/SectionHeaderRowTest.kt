package com.playtranslate.ui

import android.app.Activity
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.playtranslate.R
import com.playtranslate.themeColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * [SectionHeaderRow] applying the [HeaderFit] plan to real views, inflated
 * from section_source.xml the way both results surfaces inflate it: the
 * tail folds behind ⋯, which sits just before the pinned eye; the eye is
 * always last and at the end edge (mirrored under RTL); the row's height
 * never depends on the plan; an unavailable action is GONE at once and
 * gives its width back; the label is only capped when the plan cuts it;
 * clicks run the model's handlers; fold changes reach listeners posted.
 * The label is emptied so the fit's boundaries are pure action widths.
 */
@RunWith(RobolectricTestRunner::class)
class SectionHeaderRowTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private lateinit var column: LinearLayout
    private lateinit var row: SectionHeaderRow
    private lateinit var label: TextView
    private lateinit var furigana: HeaderAction
    private lateinit var speak: HeaderAction
    private lateinit var edit: HeaderAction
    private lateinit var anki: HeaderAction
    private lateinit var eye: HeaderAction
    private val clicks = mutableListOf<Pair<String, View>>()
    private val longClicks = mutableListOf<Pair<String, View>>()

    private var icon = 0
    private var gap = 0
    private var pad = 0

    /** The width that fits all four actions and the eye beside an empty
     *  label: five icons, five gaps. */
    private val allInline get() = pad + 5 * icon + 5 * gap

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(Host::class.java).setup().get()
        column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        activity.setContentView(column)
        LayoutInflater.from(activity).inflate(R.layout.section_source, column, true)
        row = column.findViewById(R.id.sourceHeaderRow)
        label = column.findViewById(R.id.labelOriginal)
        label.text = ""
        furigana = action(R.id.btnToggleFurigana, "furigana")
        speak = action(R.id.btnSpeakOriginal, "speak")
        edit = action(R.id.btnEditOriginal, "edit")
        anki = action(R.id.btnAnkiOriginal, "anki").apply {
            onLongClick = { v -> longClicks += "anki" to v }
        }
        eye = HeaderAction(R.id.btnToggleOriginal, R.drawable.ic_visibility, "eye", pinned = true)
        row.setActions(listOf(furigana, speak, edit, anki, eye), R.id.btnMoreOriginal)
        icon = row.moreButton.layoutParams.width
        gap = (16 * activity.resources.displayMetrics.density).toInt()
        pad = row.paddingLeft + row.paddingRight
    }

    private fun action(id: Int, name: String) =
        HeaderAction(id, R.drawable.ic_edit, name).apply { onClick = { v -> clicks += name to v } }

    /** One traversal at [width]: the column is pinned to it, so a real
     *  traversal the looper runs later measures the same width. */
    private fun layoutAt(width: Int): Int {
        column.layoutParams = FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT)
        column.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        column.layout(0, 0, width, column.measuredHeight)
        return row.height
    }

    private fun viewOf(id: Int): View = row.findViewById(id)
    private fun shown(id: Int) = viewOf(id).visibility == View.VISIBLE
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `everything inline while it fits, no more button`() {
        layoutAt(allInline)
        for (id in listOf(R.id.btnToggleFurigana, R.id.btnSpeakOriginal, R.id.btnEditOriginal,
            R.id.btnAnkiOriginal, R.id.btnToggleOriginal)) {
            assertTrue(shown(id))
        }
        assertFalse(shown(R.id.btnMoreOriginal))
        assertEquals(emptyList<HeaderAction>(), row.foldedActions())
    }

    @Test
    fun `one pixel short folds the tail two behind more, before the eye`() {
        layoutAt(allInline - 1)
        assertTrue(shown(R.id.btnToggleFurigana))
        assertTrue(shown(R.id.btnSpeakOriginal))
        assertFalse(shown(R.id.btnEditOriginal))
        assertFalse(shown(R.id.btnAnkiOriginal))
        assertTrue(shown(R.id.btnMoreOriginal))
        assertTrue(shown(R.id.btnToggleOriginal))
        assertEquals(listOf(edit, anki), row.foldedActions())
        val more = viewOf(R.id.btnMoreOriginal)
        val eyeView = viewOf(R.id.btnToggleOriginal)
        assertEquals("the eye ends the row", allInline - 1 - row.paddingRight, eyeView.right)
        assertEquals("more sits just before it", eyeView.left - gap, more.right)
    }

    @Test
    fun `the eye stays at the end edge under RTL`() {
        row.layoutDirection = View.LAYOUT_DIRECTION_RTL
        layoutAt(allInline - 1)
        val eyeView = viewOf(R.id.btnToggleOriginal)
        assertEquals(row.paddingLeft, eyeView.left)
        assertEquals(eyeView.right + gap, viewOf(R.id.btnMoreOriginal).left)
    }

    @Test
    fun `the row's height never depends on the fold or the label`() {
        val h = layoutAt(allInline)
        assertEquals(h, layoutAt(allInline - 1))
        assertEquals(h, layoutAt(pad + 2 * icon + 2 * gap))
        label.text = "Chinese (Traditional) ".repeat(12)
        assertEquals(h, layoutAt(allInline))
    }

    @Test
    fun `a label with no room left is capped, keeping more and the eye`() {
        label.text = "Chinese (Traditional) ".repeat(12)
        layoutAt(allInline)
        assertTrue(shown(R.id.btnMoreOriginal))
        assertTrue(shown(R.id.btnToggleOriginal))
        assertEquals(listOf(furigana, speak, edit, anki), row.foldedActions())
        val room = allInline - pad - 2 * icon - 2 * gap
        assertEquals(room, label.maxWidth)
        assertTrue(label.width <= room)
        // Widen past the name: the cap comes off entirely.
        label.text = ""
        layoutAt(allInline)
        assertEquals(Int.MAX_VALUE, label.maxWidth)
    }

    @Test
    fun `generated views carry the header ids`() {
        assertSame(row.moreButton, viewOf(R.id.btnMoreOriginal))
        for (id in listOf(R.id.btnToggleFurigana, R.id.btnSpeakOriginal, R.id.btnEditOriginal,
            R.id.btnAnkiOriginal, R.id.btnToggleOriginal)) {
            assertTrue("missing ${row.resources.getResourceEntryName(id)}", viewOf(id).parent === row)
        }
    }

    @Test
    fun `an unavailable action is GONE at once and gives its width back`() {
        layoutAt(allInline - 1)
        assertTrue(shown(R.id.btnMoreOriginal))
        edit.available = false
        assertEquals("no layout pass needed", View.GONE, viewOf(R.id.btnEditOriginal).visibility)
        assertEquals("never offered in the menu", listOf(anki), row.foldedActions())
        layoutAt(allInline - 1)
        assertTrue("three actions + eye fit where four didn't", shown(R.id.btnAnkiOriginal))
        assertFalse(shown(R.id.btnMoreOriginal))
        edit.available = true
        layoutAt(allInline - 1)
        assertFalse(shown(R.id.btnEditOriginal))
        assertTrue(shown(R.id.btnMoreOriginal))
    }

    @Test
    fun `nav targets are the inline actions, more, then the eye, with hold where there's a long-press`() {
        layoutAt(allInline)
        assertEquals(
            listOf(
                viewOf(R.id.btnToggleFurigana) to false, viewOf(R.id.btnSpeakOriginal) to false,
                viewOf(R.id.btnEditOriginal) to false, viewOf(R.id.btnAnkiOriginal) to true,
                viewOf(R.id.btnToggleOriginal) to false,
            ),
            row.navActions().map { it.view to it.holdActivates },
        )
        layoutAt(allInline - 1)
        assertEquals(
            listOf(R.id.btnToggleFurigana, R.id.btnSpeakOriginal, R.id.btnMoreOriginal, R.id.btnToggleOriginal),
            row.navActions().map { it.view.id },
        )
    }

    @Test
    fun `inline taps run the model's handlers with the button as anchor`() {
        layoutAt(allInline)
        viewOf(R.id.btnEditOriginal).performClick()
        assertEquals(listOf("edit" to viewOf(R.id.btnEditOriginal)), clicks)
        viewOf(R.id.btnAnkiOriginal).performLongClick()
        assertEquals(listOf("anki" to viewOf(R.id.btnAnkiOriginal)), longClicks)
        assertFalse("no long-press, not long-clickable", viewOf(R.id.btnEditOriginal).isLongClickable)
    }

    @Test
    fun `the model paints the inline view`() {
        val b = viewOf(R.id.btnToggleFurigana) as android.widget.ImageButton
        val ctx = b.context
        assertEquals(ctx.themeColor(R.attr.ptTextMuted), b.imageTintList?.defaultColor)
        furigana.active = true
        assertEquals(ctx.themeColor(R.attr.ptAccent), b.imageTintList?.defaultColor)
        furigana.contentDescription = "Toggle inline pinyin"
        assertEquals("Toggle inline pinyin", b.contentDescription)
    }

    @Test
    fun `fold changes reach listeners posted, once per change`() {
        var folds = 0
        row.addListener(object : SectionHeaderRow.Listener {
            override fun onFoldChanged(row: SectionHeaderRow) { folds++ }
        })
        layoutAt(allInline - 1)
        assertEquals("never from inside the measure pass", 0, folds)
        idle()
        assertEquals(1, folds)
        layoutAt(allInline - 1)
        idle()
        assertEquals("the same fold again is no change", 1, folds)
        layoutAt(allInline)
        idle()
        assertEquals(2, folds)
    }
}
