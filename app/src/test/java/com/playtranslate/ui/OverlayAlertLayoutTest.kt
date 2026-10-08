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
 * a large font, a long message): the part above the buttons scrolls and
 * the buttons stay on screen, while a card that fits is laid out at its
 * natural size with nothing to scroll.
 */
@RunWith(RobolectricTestRunner::class)
class OverlayAlertLayoutTest {

    private val activity: Activity = Robolectric.buildActivity(KeepRunningActivity::class.java).setup().get()

    private fun show(message: String): FrameLayout {
        val host = FrameLayout(activity)
        OverlayAlert.Builder(activity)
            .setTitle("Accessibility required")
            .setMessage(message)
            .addButton("Open Accessibility Settings", Color.BLUE, Color.WHITE) {}
            .addCancelButton("Cancel")
            .showInParent(host)
        return host
    }

    private fun layout(host: FrameLayout, width: Int, height: Int) {
        host.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        host.layout(0, 0, width, height)
    }

    private fun FrameLayout.card() = (getChildAt(0) as FrameLayout).getChildAt(0) as LinearLayout
    private fun LinearLayout.body() = getChildAt(0) as ScrollView
    private fun LinearLayout.lastButton(): View = getChildAt(childCount - 1)

    /** The buttons must keep the height they have in a window with room
     *  (a column with no weight would hand the body the whole window and
     *  squeeze the buttons to nothing, which a bounds check alone passes),
     *  and end inside the window. */
    @Test
    fun `a card taller than the window scrolls its body and keeps its buttons on screen`() {
        val host = show("line\n".repeat(200))
        layout(host, 800, 4000)
        val card = host.card()
        val naturalButton = card.lastButton().height
        assertTrue("button height $naturalButton", naturalButton > 0)

        layout(host, 800, 300)
        assertTrue("card top ${card.top}", card.top >= 0)
        assertTrue("card bottom ${card.bottom}", card.bottom <= 300)
        val body = card.body()
        assertTrue(
            "body ${body.height} against its content ${body.getChildAt(0).height}",
            body.getChildAt(0).height > body.height,
        )
        assertEquals(naturalButton, card.lastButton().height)
        assertTrue(
            "buttons end at ${card.top + card.lastButton().bottom}",
            card.top + card.lastButton().bottom <= 300,
        )
    }

    @Test
    fun `a card that fits lays out at its natural size with nothing to scroll`() {
        val host = show("Setting hotkeys requires the Accessibility permission.")
        layout(host, 800, 2000)
        val card = host.card()
        val body = card.body()
        assertEquals(body.getChildAt(0).height, body.height)
        assertTrue("card height ${card.height}", card.height in 1 until 2000)
    }
}
