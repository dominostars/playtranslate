package com.playtranslate.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.R
import com.playtranslate.overlay.OwnWindows
import com.playtranslate.overlay.Sides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The quick menu's PlayTranslate Cloud strip: present only when the menu is
 * built with it, naming the service with a wifi glyph (accent) or wifi_off
 * (muted) for [FloatingIconMenu.cloudAvailable]. The strip adds its height
 * to the card and none of its width, and the card's height is a derivation
 * the "Record audio" bar and the drag hint read, so the bar must ride the
 * taller card.
 */
@RunWith(RobolectricTestRunner::class)
class FloatingIconMenuCloudHeaderTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dp = ctx.resources.displayMetrics.density

    /** A menu in a window: its card is placed in a posted block, which a
     *  view outside a window holds until it is attached. */
    private fun menu(showCloudHeader: Boolean) = FloatingIconMenu(ctx, showCloudHeader).also { menu ->
        OwnWindows.manager(ctx)!!.addView(
            menu,
            WindowManager.LayoutParams(
                SCREEN_W, SCREEN_H,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ),
        )
    }

    private fun FloatingIconMenu.field(name: String): Any? =
        FloatingIconMenu::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this)

    private fun place(menu: FloatingIconMenu, edge: FloatingOverlayIcon.Edge = FloatingOverlayIcon.Edge.RIGHT) {
        val iconCx = if (edge == FloatingOverlayIcon.Edge.RIGHT) SCREEN_W else 0
        menu.positionNearIcon(iconCx, SCREEN_H / 2, edge, SCREEN_W, SCREEN_H, Sides.NONE, animateEntrance = false)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun strip(menu: FloatingIconMenu) = menu.field("cloudHeader") as View
    private fun glyph(menu: FloatingIconMenu) = menu.field("cloudStatusIcon") as ImageView
    private fun stripRow(menu: FloatingIconMenu) = menu.field("cloudHeaderRow") as View
    private fun card(menu: FloatingIconMenu) = menu.field("menuCard") as View
    /** The menu's own resolved colors (its theme's, or its fallbacks). */
    private fun accent(menu: FloatingIconMenu) = menu.field("accentColor") as Int
    private fun muted(menu: FloatingIconMenu) = menu.field("mutedColor") as Int

    private fun glyphRes(menu: FloatingIconMenu): Int = shadowOf(glyph(menu).drawable).createdFromResId

    @Test fun `without the switch the menu has no strip`() {
        val menu = menu(showCloudHeader = false)
        place(menu)
        assertEquals(View.GONE, strip(menu).visibility)
    }

    @Test fun `the strip names the service and reads unavailable until told otherwise`() {
        val menu = menu(showCloudHeader = true)
        place(menu)
        assertEquals(View.VISIBLE, strip(menu).visibility)
        val row = stripRow(menu) as LinearLayout
        assertEquals("PlayTranslate Cloud", (row.getChildAt(0) as TextView).text.toString())
        assertSame("the glyph sits after the name", glyph(menu), row.getChildAt(1))
        assertEquals(R.drawable.ic_wifi_off, glyphRes(menu))
        assertEquals(muted(menu), glyph(menu).imageTintList!!.defaultColor)
        assertEquals("PlayTranslate Cloud: unavailable", stripRow(menu).contentDescription)
    }

    @Test fun `available shows wifi in the accent, and unavailable takes it back`() {
        val menu = menu(showCloudHeader = true)
        menu.cloudAvailable = true
        assertEquals(R.drawable.ic_wifi, glyphRes(menu))
        assertEquals(accent(menu), glyph(menu).imageTintList!!.defaultColor)
        assertEquals("PlayTranslate Cloud: available", stripRow(menu).contentDescription)

        menu.cloudAvailable = false
        assertEquals(R.drawable.ic_wifi_off, glyphRes(menu))
        assertEquals(muted(menu), glyph(menu).imageTintList!!.defaultColor)
        assertEquals("PlayTranslate Cloud: unavailable", stripRow(menu).contentDescription)
    }

    @Test fun `the strip adds its row and divider to the card's height and nothing to its width`() {
        val plain = menu(showCloudHeader = false).also { place(it) }
        val withStrip = menu(showCloudHeader = true).also { place(it) }
        assertEquals(card(plain).measuredWidth, card(withStrip).measuredWidth)
        assertEquals(plain.field("collapsedCardWidthPx") as Int, card(withStrip).measuredWidth)
        assertEquals(
            card(plain).measuredHeight + (26 * dp).toInt() + (1 * dp).toInt(),
            card(withStrip).measuredHeight,
        )
        assertEquals(
            "the placement's height is the card's",
            card(withStrip).measuredHeight, withStrip.field("collapsedCardHeightPx") as Int,
        )
    }

    @Test fun `the Record audio bar rides below the taller card`() {
        val menu = menu(showCloudHeader = true)
        menu.showRecordAudio = true
        place(menu)
        val cardLp = card(menu).layoutParams as FrameLayout.LayoutParams
        val bar = menu.field("recordAudioBtn") as View
        val barLp = bar.layoutParams as FrameLayout.LayoutParams
        assertEquals(View.VISIBLE, bar.visibility)
        assertEquals(
            "the bar's gap is measured from the card's real bottom",
            cardLp.topMargin + card(menu).measuredHeight + (11 * dp).toInt(), barLp.topMargin,
        )
    }

    @Test fun `beside a left icon the lanes swap and the strip stays on top`() {
        val menu = menu(showCloudHeader = true)
        place(menu, FloatingOverlayIcon.Edge.LEFT)
        val lanes = menu.field("lanesRow") as LinearLayout
        assertSame(menu.field("rightStack"), lanes.getChildAt(0))
        assertSame(strip(menu), (card(menu) as LinearLayout).getChildAt(0))

        place(menu, FloatingOverlayIcon.Edge.RIGHT)
        assertSame(menu.field("secondaryLane"), lanes.getChildAt(0))
        assertSame(strip(menu), (card(menu) as LinearLayout).getChildAt(0))
    }

    private companion object {
        const val SCREEN_W = 1604
        const val SCREEN_H = 720
    }
}
