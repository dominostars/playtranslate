package com.playtranslate.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.overlay.OwnWindows
import com.playtranslate.overlay.Sides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The quick menu's card is placed from the icon's dock line, the icon's
 * centre x, not from the display edge, and everything around it stays
 * inside what nothing covers ([com.playtranslate.overlay.OverlayEnvironment.covered]). The two coincide while the icon
 * docks on the edge, and part when it docks inboard of a side navigation
 * bar on the MediaProjection backend, where a card placed from the edge
 * sat under the bar (Codex review, 2026-10-11). The "Record audio" bar and
 * the drag-hint pill's obstacle read the same placement: each used to
 * derive the card's position from the display edge on its own, and stayed
 * there when the card moved (Codex review, 2026-10-11).
 */
@RunWith(RobolectricTestRunner::class)
class FloatingIconMenuPlacementTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val menu = FloatingIconMenu(ctx).also { menu ->
        // Attached: the card's placement runs in a posted block, which a
        // view not in a window holds until it is attached.
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
    private val card: View = menu.javaClass.getDeclaredField("menuCard")
        .apply { isAccessible = true }.get(menu) as View
    private val recordBar: View = menu.javaClass.getDeclaredField("recordAudioBtn")
        .apply { isAccessible = true }.get(menu) as View
    private val pill: View = menu.javaClass.getDeclaredField("instructionPill")
        .apply { isAccessible = true }.get(menu) as View
    private val dp = ctx.resources.displayMetrics.density
    private val margin = (16 * dp).toInt()

    private fun place(
        iconCx: Int,
        edge: FloatingOverlayIcon.Edge,
        iconCy: Int = 500,
        screenW: Int = SCREEN_W,
        screenH: Int = SCREEN_H,
        covered: Sides = Sides.NONE,
    ): FrameLayout.LayoutParams {
        menu.positionNearIcon(iconCx, iconCy, edge, screenW, screenH, covered, animateEntrance = false)
        shadowOf(Looper.getMainLooper()).idle()
        return card.layoutParams as FrameLayout.LayoutParams
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun `an icon on the right display edge keeps the card a margin from the edge`() {
        val lp = place(iconCx = SCREEN_W, edge = FloatingOverlayIcon.Edge.RIGHT)
        assertEquals(SCREEN_W - card.measuredWidth - margin, lp.leftMargin)
    }

    @Test fun `an icon docked inboard of a right bar brings the card inboard with it`() {
        val lp = place(iconCx = SCREEN_W - BAR, edge = FloatingOverlayIcon.Edge.RIGHT, covered = Sides(0, 0, BAR, 0))
        assertEquals(SCREEN_W - BAR - card.measuredWidth - margin, lp.leftMargin)
    }

    @Test fun `an icon docked inboard of a left bar brings the card inboard with it`() {
        val lp = place(iconCx = BAR, edge = FloatingOverlayIcon.Edge.LEFT, covered = Sides(BAR, 0, 0, 0))
        assertEquals(BAR + margin, lp.leftMargin)
    }

    @Test fun `an icon on the left display edge keeps the card a margin from the edge`() {
        val lp = place(iconCx = 0, edge = FloatingOverlayIcon.Edge.LEFT)
        assertEquals(margin, lp.leftMargin)
    }

    @Test fun `the Record audio bar rides the card inboard of a right bar`() {
        menu.showRecordAudio = true
        val lp = place(iconCx = SCREEN_W - BAR, edge = FloatingOverlayIcon.Edge.RIGHT, covered = Sides(0, 0, BAR, 0))
        val bar = recordBar.layoutParams as FrameLayout.LayoutParams
        assertEquals(View.VISIBLE, recordBar.visibility)
        assertEquals("the bar's left edge is the card's", lp.leftMargin, bar.leftMargin)
        assertEquals("the bar spans the card", card.measuredWidth, bar.width)
    }

    @Test fun `the drag-hint pill halved beside the card measures the gap from the card inboard of a right bar`() {
        // A screen too short for the pill above or below the card, and too
        // narrow for a pill centred in the clear width to clear it: the pill
        // is halved into the gap between the card's inner edge and the far
        // edge of the clear area.
        val w = 500
        val h = 240
        val lp = place(
            iconCx = w - BAR, edge = FloatingOverlayIcon.Edge.RIGHT, iconCy = h / 2, screenW = w, screenH = h,
            covered = Sides(0, 0, BAR, 0),
        )
        val p = pill.layoutParams as FrameLayout.LayoutParams
        assertEquals(View.VISIBLE, pill.visibility)
        val pillCx = p.leftMargin + pill.measuredWidth / 2
        val gapCx = lp.leftMargin / 2
        assertTrue(
            "centred in the gap beside the card: pill centre $pillCx, gap centre $gapCx (card left ${lp.leftMargin})",
            kotlin.math.abs(pillCx - gapCx) <= 1,
        )
        assertTrue(
            "clear of the card: pill right ${p.leftMargin + pill.measuredWidth}, card left ${lp.leftMargin}",
            p.leftMargin + pill.measuredWidth + margin <= lp.leftMargin,
        )
    }

    @Test fun `over a bottom bar the card, the Record audio bar and the warning stay above it`() {
        // Portrait with three-button navigation at the bottom, the icon on
        // its lowest slot: its centre a 48 dp clearance above the bar.
        val w = 720
        val h = 1604
        val navBar = 84
        val covered = Sides(0, 0, 0, navBar)
        menu.showRecordAudio = true
        val lp = place(
            iconCx = w, edge = FloatingOverlayIcon.Edge.RIGHT, iconCy = h - navBar - (48 * dp).toInt(),
            screenW = w, screenH = h, covered = covered,
        )
        val clearBottom = h - navBar
        assertTrue("card bottom ${lp.topMargin + card.measuredHeight} above $clearBottom",
            lp.topMargin + card.measuredHeight <= clearBottom - margin)
        val bar = recordBar.layoutParams as FrameLayout.LayoutParams
        assertTrue("the Record audio bar above the nav bar", bar.topMargin + bar.height <= clearBottom)
        val warning = menu.javaClass.getDeclaredField("degradedWarningView")
            .apply { isAccessible = true }.get(menu) as View
        assertEquals((32 * dp).toInt() + navBar, (warning.layoutParams as FrameLayout.LayoutParams).bottomMargin)
    }

    @Test fun `an unchanged anchor re-places nothing, and the newest anchor asked for wins`() {
        menu.placeNear(SCREEN_W, 300, FloatingOverlayIcon.Edge.RIGHT, SCREEN_W, SCREEN_H, Sides.NONE)
        idle()
        val lp = card.layoutParams as FrameLayout.LayoutParams
        val placedTop = lp.topMargin
        // Something else moved the card; an unchanged anchor leaves it,
        // where a re-anchor would end an expand/collapse animation in flight.
        lp.topMargin = placedTop + 7
        card.layoutParams = lp
        menu.placeNear(SCREEN_W, 300, FloatingOverlayIcon.Edge.RIGHT, SCREEN_W, SCREEN_H, Sides.NONE)
        idle()
        assertEquals(placedTop + 7, (card.layoutParams as FrameLayout.LayoutParams).topMargin)

        // Two requests before the posted placement runs: the newer applies.
        menu.placeNear(SCREEN_W, 200, FloatingOverlayIcon.Edge.RIGHT, SCREEN_W, SCREEN_H, Sides.NONE)
        menu.placeNear(SCREEN_W - BAR, 300, FloatingOverlayIcon.Edge.RIGHT, SCREEN_W, SCREEN_H, Sides(0, 0, BAR, 0))
        idle()
        assertEquals(SCREEN_W - BAR - card.measuredWidth - margin, (card.layoutParams as FrameLayout.LayoutParams).leftMargin)
        assertEquals(placedTop, (card.layoutParams as FrameLayout.LayoutParams).topMargin)
    }

    private companion object {
        const val SCREEN_W = 1604
        const val SCREEN_H = 720
        const val BAR = 84
    }
}
