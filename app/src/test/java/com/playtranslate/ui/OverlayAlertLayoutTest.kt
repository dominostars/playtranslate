package com.playtranslate.ui

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The alert card against a window too short for it (a phone in landscape,
 * a large font, a long message, a picker's column of buttons): the whole
 * card scrolls, buttons included, and ends inside the window, while a card
 * that fits is laid out at its natural size with nothing to scroll.
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
}
