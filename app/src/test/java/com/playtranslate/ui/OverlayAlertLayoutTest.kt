package com.playtranslate.ui

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The alert card against a window too short for it (a phone in landscape,
 * a large font, a long message, a picker's column of buttons): the whole
 * card scrolls, buttons included, and ends inside the window, while a card
 * that fits is laid out at its natural size with nothing to scroll.
 *
 * And the two arrangements of an alert with a footer and headed groups
 * ([OverlayAlert.Builder.beginFooter]). In a narrow window it is that same
 * one column: the groups one under another, then a rule and the footer's
 * buttons stacked, all of it scrolling together. In a wide window the card
 * widens, the groups stand side by side, and the footer's buttons share one
 * row under the rule that stays on screen at its full height while the
 * groups scroll above it. The width of what the alert fills decides, when
 * it is shown and again when it changes under an alert that stays up. An
 * alert with neither a footer nor several groups is the narrow column in
 * any window.
 */
@RunWith(RobolectricTestRunner::class)
class OverlayAlertLayoutTest {

    private val activity: Activity = Robolectric.buildActivity(KeepRunningActivity::class.java).setup().get()

    private fun show(message: String, buttons: Int = 1): FrameLayout {
        val host = FrameLayout(activity)
        val builder = OverlayAlert.Builder(activity)
            .setTitle("Accessibility required")
            .setMessage(message)
        repeat(buttons) { builder.addButton("Open Accessibility Settings $it", Color.BLUE, Color.WHITE) {} }
        builder.addCancelButton("Cancel").showInParent(host)
        return host
    }

    private fun layout(host: FrameLayout, width: Int, height: Int) {
        host.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        host.layout(0, 0, width, height)
    }

    /** The card is the scrim's one child, a scroll view around the column. */
    private fun FrameLayout.card() = (getChildAt(0) as FrameLayout).getChildAt(0) as ScrollView
    private fun ScrollView.column() = getChildAt(0) as LinearLayout
    private fun LinearLayout.lastButton(): View = getChildAt(childCount - 1)

    /** The card ends inside a window of [height], its column is taller than
     *  it (so it scrolls), and scrolled to the end the last button sits in
     *  the card's visible band at the height it has with room. */
    private fun assertScrollsToItsButtons(host: FrameLayout, height: Int, naturalButton: Int) {
        val card = host.card()
        val column = card.column()
        assertTrue("card top ${card.top}", card.top >= 0)
        assertTrue("card bottom ${card.bottom}", card.bottom <= height)
        assertTrue("column ${column.height} against the card ${card.height}", column.height > card.height)
        assertEquals(naturalButton, column.lastButton().height)
        card.scrollTo(0, column.height + card.paddingTop + card.paddingBottom - card.height)
        val buttonBottom = column.top + column.lastButton().bottom - card.scrollY
        assertTrue(
            "last button ends at $buttonBottom in a card ${card.height} tall",
            buttonBottom <= card.height - card.paddingBottom,
        )
    }

    /** The buttons must keep the height they have in a window with room:
     *  a card that clamps instead of scrolling squeezes them to nothing,
     *  which a bounds check alone passes. */
    @Test
    fun `a card taller than the window from its message scrolls as a whole and keeps its buttons`() {
        val host = show("line\n".repeat(200))
        layout(host, 800, 4000)
        val naturalButton = host.card().column().lastButton().height
        assertTrue("button height $naturalButton", naturalButton > 0)

        layout(host, 800, 300)
        assertScrollsToItsButtons(host, 300, naturalButton)
    }

    /** A short message under a column of buttons (the OCR picker's shape):
     *  the buttons are what makes the card tall, and they scroll with it. */
    @Test
    fun `a card taller than the window from its buttons alone scrolls as a whole`() {
        val host = show("Pick a tool.", buttons = 12)
        layout(host, 800, 4000)
        val naturalButton = host.card().column().lastButton().height
        assertTrue("button height $naturalButton", naturalButton > 0)

        layout(host, 800, 300)
        assertScrollsToItsButtons(host, 300, naturalButton)
    }

    @Test
    fun `a card that fits lays out at its natural size with nothing to scroll`() {
        val host = show("Setting hotkeys requires the Accessibility permission.")
        layout(host, 800, 2000)
        val card = host.card()
        assertEquals(card.column().height + card.paddingTop + card.paddingBottom, card.height)
        assertTrue("card height ${card.height}", card.height in 1 until 2000)
    }

    // ── An alert with a footer and headed groups ─────────────────────────

    /** A picker's shape: two headed groups ([first] and [second] buttons),
     *  then a footer of a settings button and Cancel. */
    private fun picker(first: Int, second: Int, into: FrameLayout = FrameLayout(activity)): FrameLayout {
        val builder = OverlayAlert.Builder(activity).setTitle("Choose").setMessage("What a pick does.")
        builder.addSectionLabel("Group A")
        repeat(first) { builder.addButton("A$it", Color.BLUE, Color.WHITE) {} }
        builder.addSectionLabel("Group B")
        repeat(second) { builder.addButton("B$it", Color.BLUE, Color.WHITE) {} }
        builder.beginFooter()
        builder.addOutlinedButton("Settings") {}
        builder.addCancelButton("Cancel").showInParent(into)
        return into
    }

    private fun FrameLayout.cardView(): View = (getChildAt(0) as FrameLayout).getChildAt(0)
    private fun View.children(): List<View> = (this as ViewGroup).let { g -> (0 until g.childCount).map { g.getChildAt(it) } }
    private fun View.descendants(): List<View> = listOf(this) + ((this as? ViewGroup)?.children()?.flatMap { it.descendants() } ?: emptyList())
    private fun FrameLayout.button(label: String): Button =
        descendants().filterIsInstance<Button>().single { it.text.toString() == label }
    private fun FrameLayout.label(text: String): TextView =
        descendants().filterIsInstance<TextView>().single { it !is Button && it.text.toString() == text }
    private fun View.insideAScroll(): Boolean = generateSequence(parent) { it.parent }.any { it is ScrollView }

    /** [v]'s left edge in [host]. */
    private fun leftIn(host: View, v: View): Int {
        var x = 0
        var cur: View = v
        while (cur !== host) {
            x += cur.left
            cur = cur.parent as View
        }
        return x
    }

    private fun topIn(host: View, v: View): Int {
        var y = 0
        var cur: View = v
        while (cur !== host) {
            y += cur.top - (cur.parent as View).scrollY
            cur = cur.parent as View
        }
        return y
    }

    @Test
    fun `in a narrow window a footer is the end of the one column, under a rule, and scrolls with it`() {
        val host = picker(first = 2, second = 2)
        layout(host, 320, 2000)

        val card = host.card()
        assertEquals("the narrow card", 280, card.width)
        val column = card.column().children()
        val texts = column.map { (it as? TextView)?.text?.toString() }
        assertEquals(
            listOf("Choose", "What a pick does.", "Group A", "A0", "A1", "Group B", "B0", "B1", null, "Settings", "Cancel"),
            texts,
        )
        val rule = column[8]
        assertFalse("a line, not a label", rule is TextView)
        assertTrue("a hairline", rule.height in 1..2)
        assertTrue("stacked", host.button("Settings").bottom <= host.button("Cancel").top)
        assertTrue(host.button("Settings").insideAScroll() && host.button("Cancel").insideAScroll())
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land")
    fun `in a wide window the card widens, the groups stand side by side and the footer's buttons share a row under the rule`() {
        val host = picker(first = 2, second = 2)
        layout(host, 800, 2000)

        val card = host.cardView()
        assertFalse("not the one scrolling column", card is ScrollView)
        assertEquals("the wide card", 560, card.width)
        val (scroll, footer) = card.children()
        assertTrue(scroll is ScrollView)

        // The groups: each header over its own buttons, the two side by side.
        val a = host.label("Group A")
        val b = host.label("Group B")
        assertEquals("on one line", topIn(host, a), topIn(host, b))
        assertTrue("B to the right of A", leftIn(host, b) > leftIn(host, a) + a.width / 2)
        assertEquals(leftIn(host, a), leftIn(host, host.button("A0")))
        assertEquals(leftIn(host, b), leftIn(host, host.button("B1")))
        assertTrue("under its header", topIn(host, host.button("B0")) > topIn(host, b))
        assertTrue(host.button("A0").insideAScroll() && host.button("B1").insideAScroll())

        // The footer: a rule, then the two buttons in one row, outside the scroll.
        val (rule, row) = footer.children()
        assertTrue("a hairline", rule !is TextView && rule.height in 1..2)
        assertEquals(listOf("Settings", "Cancel"), row.children().map { (it as Button).text.toString() })
        val settings = host.button("Settings")
        val cancel = host.button("Cancel")
        assertEquals("one row", topIn(host, settings), topIn(host, cancel))
        assertEquals("one height", settings.height, cancel.height)
        assertTrue("side by side", leftIn(host, cancel) >= leftIn(host, settings) + settings.width)
        assertFalse(settings.insideAScroll() || cancel.insideAScroll())
        assertTrue("the rule is under the groups", topIn(host, rule) > topIn(host, host.button("A1")))
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land")
    fun `in a wide window too short for its groups, they scroll and the footer stays on screen at its full height`() {
        val roomy = picker(first = 12, second = 2)
        layout(roomy, 800, 4000)
        val naturalButton = roomy.button("Cancel").height
        assertTrue("button height $naturalButton", naturalButton > 0)

        val host = picker(first = 12, second = 2)
        layout(host, 800, 300)

        val card = host.cardView()
        assertTrue("card top ${card.top}", card.top >= 0)
        assertTrue("card bottom ${card.bottom}", card.bottom <= 300)
        val scroll = card.children()[0] as ScrollView
        assertTrue(
            "the groups are taller than their window: ${scroll.getChildAt(0).height} against ${scroll.height}",
            scroll.getChildAt(0).height > scroll.height,
        )
        assertTrue("and are given room to be read in", scroll.height >= naturalButton * 2)
        val cancel = host.button("Cancel")
        assertEquals("the footer is not squeezed", naturalButton, cancel.height)
        assertTrue("and ends inside the card", topIn(host, cancel) + cancel.height <= card.bottom)
        assertTrue(topIn(host, host.button("Settings")) >= card.top)
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land")
    fun `an alert with no footer and no groups is the narrow column in a wide window too`() {
        val host = show("Pick a tool.", buttons = 3)
        layout(host, 800, 2000)

        val card = host.card()
        assertEquals(280, card.width)
        assertEquals(
            listOf("Open Accessibility Settings 0", "Open Accessibility Settings 1", "Open Accessibility Settings 2", "Cancel"),
            card.column().children().filterIsInstance<Button>().map { it.text.toString() },
        )
    }

    @Test
    @Config(qualifiers = "w900dp-h600dp")
    fun `the arrangement follows the width of what the alert fills, when shown and when that changes`() {
        fun idle() = shadowOf(Looper.getMainLooper()).idle()
        val root = FrameLayout(activity)
        val host = FrameLayout(activity)
        root.addView(host, FrameLayout.LayoutParams(300, ViewGroup.LayoutParams.MATCH_PARENT))
        activity.setContentView(root)
        idle()
        assertEquals("precondition: the host is laid out narrow on a wide screen", 300, host.width)

        picker(first = 2, second = 2, into = host)
        idle()
        assertTrue("a narrow host on a wide screen: its width decides, not the screen's", host.cardView() is ScrollView)

        // The same alert, and what it fills turns wide (a rotation).
        host.layoutParams = FrameLayout.LayoutParams(800, ViewGroup.LayoutParams.MATCH_PARENT)
        idle()
        assertFalse(host.cardView() is ScrollView)
        assertEquals(560, host.cardView().width)
        assertEquals("one card, the new one", 1, (host.getChildAt(0) as FrameLayout).childCount)
        assertEquals("with no entrance to sit through", 1f, host.cardView().alpha, 0f)

        // A window with less than the wide card's widest for it (a small
        // phone on its side): still side by side, the card a margin short
        // of the window on each side.
        host.layoutParams = FrameLayout.LayoutParams(540, ViewGroup.LayoutParams.MATCH_PARENT)
        idle()
        assertFalse(host.cardView() is ScrollView)
        assertEquals(540 - 2 * 16, host.cardView().width)
        assertTrue("inside what it fills", host.cardView().left >= 0 && host.cardView().right <= 540)

        // And narrow again.
        host.layoutParams = FrameLayout.LayoutParams(300, ViewGroup.LayoutParams.MATCH_PARENT)
        idle()
        assertTrue(host.cardView() is ScrollView)
        assertEquals(280, host.cardView().width)
    }

    @Test
    @Config(qualifiers = "w592dp-h360dp-land")
    fun `a small phone on its side still gets the wide arrangement, its labels on one line`() {
        val host = picker(first = 2, second = 2)
        layout(host, 592, 360)

        val card = host.cardView()
        assertFalse(card is ScrollView)
        assertEquals("a margin short of the window on each side", 560, card.width)
        assertTrue("card bottom ${card.bottom}", card.bottom <= 360)
        val settings = host.button("Settings")
        val cancel = host.button("Cancel")
        assertEquals(1, settings.lineCount)
        assertEquals("one row", topIn(host, settings), topIn(host, cancel))
    }

    @Test
    fun `an outlined button's icon stands beside its label, the two centred as one`() {
        val host = FrameLayout(activity)
        OverlayAlert.Builder(activity).setTitle("Choose")
            .addButton("A service", Color.BLUE, Color.WHITE) {}
            .addOutlinedButton("Settings", com.playtranslate.R.drawable.ic_settings) {}
            .addCancelButton("Cancel")
            .showInParent(host)
        layout(host, 320, 2000)

        val settings = host.button("Settings")
        val icon = settings.compoundDrawablesRelative[0]!!
        val pair = icon.bounds.width() + settings.compoundDrawablePadding +
            settings.paint.measureText("Settings").toInt()
        assertEquals("the icon is at the label's size, not its own", 18, icon.bounds.width())
        assertEquals("as much room before the pair as after it", settings.paddingStart, settings.paddingEnd)
        assertEquals((settings.width - pair) / 2, settings.paddingStart)
        assertTrue(
            "the pair starts where that leaves it, not at the button's edge",
            settings.paddingStart > host.button("A service").paddingStart,
        )
        assertEquals("the same height as a filled button", host.button("Cancel").height, settings.height)
    }

    /** The pair is centred by its measured width. This runtime measures a
     *  label at a pixel a character, so "too long" here is a label of more
     *  characters than the button has pixels; on a device it is one that
     *  does not fit on the line. */
    @Test
    fun `an outlined button whose label is too long for its width keeps the plain padding`() {
        val host = FrameLayout(activity)
        val long = "A label for a settings page that is far too long for one line. ".repeat(8)
        OverlayAlert.Builder(activity).setTitle("Choose")
            .addButton("A service", Color.BLUE, Color.WHITE) {}
            .addOutlinedButton(long, com.playtranslate.R.drawable.ic_settings) {}
            .showInParent(host)
        layout(host, 320, 2000)

        val button = host.button(long)
        assertTrue(
            "precondition: the pair is wider than the button",
            button.paint.measureText(long) > button.width,
        )
        assertEquals(host.button("A service").paddingStart, button.paddingStart)
        assertEquals(button.paddingStart, button.paddingEnd)
    }
}
