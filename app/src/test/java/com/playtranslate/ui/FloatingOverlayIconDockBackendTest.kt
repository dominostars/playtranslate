package com.playtranslate.ui

import android.content.Context
import android.graphics.PixelFormat
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.overlay.Sides
import com.playtranslate.testutil.testEnvironment
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which bars the floating icon's dock stays inboard of depends on where its
 * window lies in Z, which the environment knows ([com.playtranslate.overlay.OverlayEnvironment.covered]).
 * Below the bars (TYPE_APPLICATION_OVERLAY, the MediaProjection backend) a
 * shown navigation bar would cover it, so the dock line moves inboard of the
 * bar. Above them (an accessibility overlay) nothing covers it: it docks on
 * the display edge, visible and reachable over the buttons. An inboard dock
 * there hid the window's outer half over the bar's inner strip while that
 * half kept the bar's touches (Codex adversarial review, 2026-10-10).
 */
@RunWith(RobolectricTestRunner::class)
class FloatingOverlayIconDockBackendTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** The icon's place on the right edge's middle slot over a 1080x2400
     *  display whose navigation bar is 84 px wide on the right, on the
     *  backend whose windows lie [belowBars] or above them. */
    private fun dockX(belowBars: Boolean): Int {
        val icon = FloatingOverlayIcon(ctx)
        val p = WindowManager.LayoutParams(
            icon.viewSizePx, icon.viewSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        icon.setSlot(FloatingOverlayIcon.Edge.RIGHT.ordinal, 0.5f)
        icon.layoutAtRest(testEnvironment(W, 2400, belowBars = belowBars, shownBars = Sides(0, 0, 84, 0)), p)
        return p.x + icon.viewSizePx / 2
    }

    @Test
    fun `below the bars, a right dock sits inboard of the right bar`() {
        assertEquals(W - 84, dockX(belowBars = true))
    }

    @Test
    fun `above the bars, the same display docks on its edge`() {
        assertEquals(W, dockX(belowBars = false))
    }

    private companion object {
        const val W = 1080
    }
}
