package com.playtranslate

import android.content.Context
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CaptureLifecycle
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.Sides
import com.playtranslate.testutil.testEnvironment
import com.playtranslate.ui.FloatingIconMenu
import com.playtranslate.ui.FloatingOverlayIcon
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The windows placed from the floating icon follow it when the display
 * changes under it (here a navigation bar the app beneath shows): the host
 * lays every window on the display out in one refresh, each reading the
 * icon as it is ([OverlayHost.refresh]), whatever order they were added in.
 * The sonar intro is the one that shows: it plays for 3.6 s after every
 * fresh appearance with the icon hidden under it, and on the
 * MediaProjection backend the icon appears as the consent dialog closes,
 * docked inboard of the bars that dialog showed; the game then goes
 * immersive and the icon settles to the edge (Codex review, 2026-10-11).
 * A re-raise replaces the icon's window, so the intro, added before it,
 * now precedes it in the host's list.
 */
@RunWith(RobolectricTestRunner::class)
class IconDockDependentsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)

    /** The right-side navigation bar the display reports. */
    private var bar = 0
    private val screen = ctx.displaySizePx()
    private val host = OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY).also {
        it.environmentReader = { testEnvironment(screen.x, screen.y, shownBars = Sides(0, 0, bar, 0)) }
    }
    private val controller = OverlayUiController(ctx, host)

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Before fun setUp() {
        clearPrefs()
        CaptureLifecycle.setFloatingIconSuppressed(ctx, false)
        prefs.captureDisplayIds = setOf(Display.DEFAULT_DISPLAY)
        prefs.setIconPositionForDisplay(Display.DEFAULT_DISPLAY, IconPosition(FloatingOverlayIcon.Edge.RIGHT.ordinal, 0.5f))
    }

    @After fun tearDown() {
        controller.hideAll()
        CaptureLifecycle.setFloatingIconSuppressed(ctx, true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        clearPrefs()
    }

    private fun <T> field(name: String): T =
        OverlayUiController::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(controller) as T

    /** The default display's installed icon, as the controller holds it now. */
    private fun installedIcon(): FloatingOverlayIcon {
        val handle = field<Map<*, *>>("iconHandles")[Display.DEFAULT_DISPLAY]
        assertNotNull("an icon on the default display", handle)
        return handle!!.javaClass.getDeclaredField("icon").apply { isAccessible = true }
            .get(handle) as FloatingOverlayIcon
    }

    /** The sonar intro playing on the default display. */
    private fun intro(): View {
        val intro = field<Map<*, *>>("activeSonarIntros")[Display.DEFAULT_DISPLAY]
        assertNotNull("the intro is playing", intro)
        return intro as View
    }

    /** Where the intro's window goes for the icon as it is now. */
    private fun carrierX(icon: FloatingOverlayIcon, lp: WindowManager.LayoutParams): Int {
        val dockX = icon.params!!.x + icon.viewSizePx / 2
        return when (icon.currentEdge) {
            FloatingOverlayIcon.Edge.RIGHT -> dockX - lp.width
            FloatingOverlayIcon.Edge.LEFT -> dockX
        }
    }

    private fun barAppears() {
        bar = BAR
        host.refresh(Display.DEFAULT_DISPLAY, "bars", force = false)
    }

    @Test fun `the sonar intro follows the icon's re-dock`() {
        // A fresh appearance: the intro plays. No idle: under Robolectric
        // the intro's animator reaches its end on the first frame the looper
        // runs, and the install and the intro's add are synchronous.
        controller.reconcileFloatingIcons()
        val icon = installedIcon()
        val lp = intro().layoutParams as WindowManager.LayoutParams
        assertEquals("anchored on the dock line", carrierX(icon, lp), lp.x)
        val iconX = icon.params!!.x

        barAppears()

        assertEquals("the icon re-docked", iconX - BAR, icon.params!!.x)
        assertEquals("the carrier moved with it", carrierX(icon, lp), lp.x)
    }

    @Test fun `after a re-raise the intro follows the fresh icon, added after it`() {
        controller.reconcileFloatingIcons()
        val first = installedIcon()
        val lp = intro().layoutParams as WindowManager.LayoutParams
        controller.bringFloatingIconsToFront(Display.DEFAULT_DISPLAY)
        val fresh = installedIcon()
        assertNotSame("the re-raise replaced the icon", first, fresh)
        assertEquals("the fresh icon on the same slot", first.params!!.x, fresh.params!!.x)

        barAppears()

        assertEquals(first.params!!.x - BAR, fresh.params!!.x)
        assertEquals("the carrier follows the icon the display has now", carrierX(fresh, lp), lp.x)
    }

    @Test fun `an open menu's card follows the icon's re-dock`() {
        prefs.iconTapAction = TapAction.OPEN_QUICK_MENU
        controller.reconcileFloatingIcons()
        val icon = installedIcon()
        icon.performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        val menu = field<FloatingIconMenu?>("floatingMenu")
        assertNotNull("the menu opened", menu)
        val card = FloatingIconMenu::class.java.getDeclaredField("menuCard")
            .apply { isAccessible = true }.get(menu) as View
        val cardX = (card.layoutParams as FrameLayout.LayoutParams).leftMargin

        barAppears()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

        assertEquals("the card moved with the icon", cardX - BAR, (card.layoutParams as FrameLayout.LayoutParams).leftMargin)
    }

    private companion object {
        const val BAR = 84
    }
}
