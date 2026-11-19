package com.playtranslate

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CaptureLifecycle
import com.playtranslate.overlay.OwnActivities
import com.playtranslate.testutil.testEnvironment
import com.playtranslate.ui.DetachedWindow
import com.playtranslate.ui.FloatingIconMenu
import com.playtranslate.ui.FloatingOverlayIcon
import com.playtranslate.ui.IconDock
import com.playtranslate.ui.QuickSetting
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowDisplayManager
import java.time.Duration

/**
 * The floating controls' two forms on the real controller: the icon's quick
 * settings detach it into the window, the window's Minimize brings the icon
 * back, the form is what a later reconcile shows, every gate that hides the
 * icon hides the window, the icon stands in wherever the window cannot be,
 * a change of form never reads as "no controls", and auto-translate and the
 * window never share the screen.
 *
 * The accessibility service is the active backend's host (created, never
 * connected), so its controller is the one the service's live start and the
 * app's lifecycle hook reach. The display is a 1280 x 800 tablet through the
 * host's reader seam, where the default window is the page.
 */
@RunWith(RobolectricTestRunner::class)
class DetachedControlsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private val d0 = Display.DEFAULT_DISPLAY
    private lateinit var a11y: PlayTranslateAccessibilityService
    private lateinit var controller: OverlayUiController
    private var service: ServiceController<CaptureService>? = null
    private val activities = mutableListOf<ActivityController<Activity>>()

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    @Before fun setUp() {
        clearPrefs()
        // A real boot count, so the session record's "on" (the boot count)
        // can be told from its "off" (-1), as SessionMarkerTest does.
        android.provider.Settings.Global.putInt(ctx.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 57)
        CaptureLifecycle.setFloatingIconSuppressed(ctx, false)
        prefs.captureDisplayIds = setOf(d0)
        prefs.overlayMode = OverlayMode.FURIGANA
        OwnActivities.displayIdProbe = { d0 }

        a11y = Robolectric.buildService(PlayTranslateAccessibilityService::class.java).create().get()
        PlayTranslateAccessibilityService.instance = a11y
        a11y.overlayHost.environmentReader = { testEnvironment(1280, 800, belowBars = false) }
        controller = a11y.overlayUiController
        controller.reconcileFloatingIcons(freshAppearance = false)
        settle()
    }

    @After fun tearDown() {
        activities.forEach { runCatching { it.pause().stop().destroy() } }
        MainActivity.isInForeground = false
        service?.destroy()
        service = null
        controller.hideAll()
        PlayTranslateAccessibilityService.instance = null
        CaptureLifecycle.setFloatingIconSuppressed(ctx, true)
        // Let the churn gate finish every removal inside this test.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        clearPrefs()
    }

    private fun field(name: String) =
        OverlayUiController::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun icon(): FloatingOverlayIcon? {
        val handle = (field("iconHandles").get(controller) as Map<*, *>)[d0] ?: return null
        return handle.javaClass.getDeclaredField("icon").apply { isAccessible = true }
            .get(handle) as FloatingOverlayIcon
    }

    private fun window(): DetachedWindow? = controller.detachedWindowForTest(d0)

    private fun menu(): FloatingIconMenu? = field("floatingMenu").get(controller) as FloatingIconMenu?

    /** The quick menu's panel row for [id], the menu opened by the icon's tap. */
    private fun menuRow(id: QuickSetting.Id): QuickSetting? {
        icon()!!.onTap!!.invoke()
        settle()
        return menu()!!.panelSetting(id)
    }

    /** Detach through the quick settings, as the user does. */
    private fun detach() {
        menuRow(QuickSetting.Id.DETACH)!!.click()
        settle()
    }

    private fun tap(id: Int) {
        window()!!.root.findViewById<View>(id).performClick()
        settle()
    }

    /** Open the window's menu from its bar and tap the row named [label]. */
    private fun tapMenuRow(label: String) {
        val window = window()!!
        window.bar.moreButton.performClick()
        settle()
        // The menu's own window, laid out as the window manager would, and
        // a turn of the looper for its card to come up.
        val root = window.popovers.rootForTest as android.view.ViewGroup
        val p = root.layoutParams
        repeat(2) {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(p.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(p.height, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, p.width, p.height)
            settle()
        }
        fun find(v: View): View? {
            if (v is android.widget.TextView && v.text.toString() == label) return v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        var row = find(root) ?: error("no menu row \"$label\"")
        while (!row.hasOnClickListeners()) row = row.parent as View
        row.performClick()
        settle()
    }

    private fun assertWindowOnly() {
        assertNotNull("the window is up", window())
        assertNull("no icon beside it", icon())
    }

    private fun assertIconOnly() {
        assertNotNull("the icon is up", icon())
        assertNull("no window beside it", window())
    }

    // ── Detach and back ──────────────────────────────────────────────────

    @Test fun `the quick settings offer Detach, and it turns the icon into the window`() {
        assertIconOnly()

        detach()

        assertWindowOnly()
        assertNull("the menu closed", menu())
        assertEquals(ControlsForm.WINDOW, prefs.detachedStateForDisplay(d0).form)
        assertTrue("the default window on a tablet shows the page", window()!!.showsPage)
    }

    @Test fun `Minimize brings the icon back where it was`() {
        prefs.setIconPositionForDisplay(d0, IconPosition(edge = 0, fraction = 0.25f))
        detach()

        tap(R.id.detachedBarMinimize)

        assertIconOnly()
        assertEquals(ControlsForm.ICON, prefs.detachedStateForDisplay(d0).form)
        assertEquals(IconPosition(edge = 0, fraction = 0.25f), prefs.iconPositionForDisplay(d0))
    }

    @Test fun `the saved form is what comes up again`() {
        detach()
        controller.hideAll()
        settle()
        assertNull(window())

        controller.reconcileFloatingIcons(freshAppearance = false)
        settle()

        assertWindowOnly()
    }

    @Test fun `a reconcile with the window up leaves it as it is`() {
        detach()
        val up = window()

        repeat(3) { controller.reconcileFloatingIcons() }
        settle()

        assertSame("the same window, not a rebuilt one", up, window())
        assertNull(icon())
    }

    // ── The gates ────────────────────────────────────────────────────────

    @Test fun `the controls' switch off hides the window, and on brings it back`() {
        detach()

        prefs.showOverlayIcon = false
        controller.reconcileFloatingIcons(freshAppearance = false)
        settle()
        assertNull(window())
        assertNull(icon())
        assertFalse(controller.hasControlsOnScreen)

        prefs.showOverlayIcon = true
        controller.reconcileFloatingIcons(freshAppearance = false)
        settle()
        assertWindowOnly()
    }

    @Test fun `Hide for Now hides the window until the controls are summoned again`() {
        detach()

        CaptureLifecycle.setFloatingIconSuppressed(ctx, true)
        controller.reconcileFloatingIcons(freshAppearance = false)
        settle()
        assertNull(window())
        assertNull(icon())

        CaptureLifecycle.setFloatingIconSuppressed(ctx, false)
        controller.reconcileFloatingIcons(userSummoned = true)
        settle()
        assertWindowOnly()
    }

    // ── Reachable, and never "off" across a swap ─────────────────────────

    @Test fun `with only the window up the controls count as on screen`() {
        detach()

        assertTrue(controller.hasControlsOnScreen)
        assertTrue("hotkeys stay reachable", a11y.isUserReachable())
        assertEquals("the session record stands", 57, prefs.sessionOnBoot)
    }

    @Test fun `a change of form never reads as no controls`() {
        val sp = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
        val offs = mutableListOf<String>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == Prefs.KEY_SESSION_ON_BOOT && p.getInt(key, -1) == -1) offs += "session record cleared"
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
        try {
            detach()
            tap(R.id.detachedBarMinimize)
            detach()
        } finally {
            sp.unregisterOnSharedPreferenceChangeListener(listener)
        }

        assertEquals(emptyList<String>(), offs)
        assertEquals(57, prefs.sessionOnBoot)
    }

    // ── The icon stands in ───────────────────────────────────────────────

    private fun ownActivity(): ActivityController<Activity> =
        Robolectric.buildActivity(Activity::class.java).also { activities += it }

    @Test fun `one of our own screens in front puts the icon in the window's place, and leaving brings the window back`() {
        detach()

        val screen = ownActivity().create().start()
        settle()
        assertIconOnly()
        assertEquals("the saved form is still the window", ControlsForm.WINDOW, prefs.detachedStateForDisplay(d0).form)

        screen.stop()
        settle()
        assertWindowOnly()
    }

    @Test fun `one of our screens coming or going brings no controls up by itself`() {
        // The controls' first appearance, with its intro, is the reconcile's
        // alone; the screens' hook only swaps the form of controls already up.
        for (saved in ControlsForm.entries) {
            prefs.setControlsForm(d0, saved)
            controller.hideAll()
            settle()
            assertFalse(controller.hasControlsOnScreen)

            val screen = ownActivity().create().start()
            settle()
            assertFalse("saved as $saved, a screen of ours started", controller.hasControlsOnScreen)
            screen.stop()
            settle()
            assertFalse("saved as $saved, it stopped", controller.hasControlsOnScreen)
        }
    }

    @Test fun `a screen of ours on another display leaves the window up`() {
        detach()
        val up = window()
        OwnActivities.displayIdProbe = { d0 + 7 }

        ownActivity().create().start()
        settle()

        assertSame(up, window())
        assertNull(icon())
    }

    @Test fun `the icon standing in offers no Detach`() {
        detach()
        ownActivity().create().start()
        settle()

        assertNull(menuRow(QuickSetting.Id.DETACH))
    }

    @Test fun `with the app in front on a second screen the icon stands in`() {
        detach()

        ShadowDisplayManager.addDisplay("w640dp-h480dp")
        MainActivity.isInForeground = true
        controller.reconcileFloatingIcons(freshAppearance = false)
        settle()
        assertIconOnly()

        MainActivity.isInForeground = false
        controller.reconcileFloatingIcons(freshAppearance = false)
        settle()
        assertWindowOnly()
    }

    @Test fun `with the app in front on a second screen the icon does not offer Detach`() {
        ShadowDisplayManager.addDisplay("w640dp-h480dp")
        MainActivity.isInForeground = true

        assertNull(menuRow(QuickSetting.Id.DETACH))
    }

    @Test fun `the region editor takes the window down for the icon, and its close brings the window back`() {
        detach()

        tapMenuRow(ctx.getString(R.string.floating_menu_edit_region))
        assertIconOnly()

        val region = field("regionController").get(controller) as RegionOverlayController
        region.hideRegionEditor()
        settle()
        assertWindowOnly()
    }

    // ── Auto-translate ───────────────────────────────────────────────────

    private fun captureService(): CaptureService {
        val built = Robolectric.buildService(CaptureService::class.java).create()
        service = built
        return built.get().also { it.gameDisplayIds = setOf(d0) }
    }

    private class StandInLiveMode(override val flavor: OverlayFlavor) : LiveMode {
        var stops = 0
        override fun start() {}
        override fun stop() { stops++ }
        override fun refresh() {}
        override fun getCachedState(): CachedOverlayState? = null
    }

    private fun running(svc: CaptureService): StandInLiveMode {
        val mode = StandInLiveMode(OverlayFlavor.FURIGANA)
        val modes = CaptureService::class.java.getDeclaredField("liveModes")
            .apply { isAccessible = true }.get(svc)
        MutableMap::class.java.getMethod("put", Any::class.java, Any::class.java).invoke(modes, d0, mode)
        assertTrue(svc.isLive)
        return mode
    }

    @Test fun `the bar's Auto docks the window and starts auto-translate`() {
        val svc = captureService()
        detach()

        tap(R.id.detachedBarAuto)

        assertIconOnly()
        assertEquals("docked for good", ControlsForm.ICON, prefs.detachedStateForDisplay(d0).form)
        assertTrue("auto-translate runs", svc.isLive)
    }

    @Test fun `a start from anywhere docks the window`() {
        val svc = captureService()
        detach()

        svc.startLive()
        settle()

        assertIconOnly()
        assertEquals(ControlsForm.ICON, prefs.detachedStateForDisplay(d0).form)
    }

    @Test fun `a start while the icon stands in leaves the icon for good`() {
        val svc = captureService()
        detach()
        val screen = ownActivity().create().start()
        settle()

        svc.startLive()
        settle()
        screen.stop()
        settle()

        assertIconOnly()
        assertEquals(ControlsForm.ICON, prefs.detachedStateForDisplay(d0).form)
    }

    @Test fun `the bar's Capture follows a change of the capture region`() {
        val svc = captureService()
        detach()
        val capture = window()!!.root.findViewById<View>(R.id.detachedBarCapture)
        assertEquals("Capture Screen", capture.contentDescription.toString())

        svc.configureOverride(d0, RegionEntry("", 0.2f, 0.8f, 0.2f, 0.8f))
        settle()

        assertEquals("Capture Region", capture.contentDescription.toString())
    }

    // ── Touches on the window ────────────────────────────────────────────

    private fun windowParams() = window()!!.root.layoutParams as WindowManager.LayoutParams

    private var downTime = 0L

    /** A touch on the window at display point ([rawX], [rawY]); the root is
     *  given the size its params say first, as the window manager would. */
    private fun touch(action: Int, rawX: Float, rawY: Float) {
        val root = window()?.root ?: return
        val p = windowParams()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(p.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(p.height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, p.width, p.height)
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, rawX, rawY, 0)
        ev.setLocation(rawX - p.x, rawY - p.y)
        root.dispatchTouchEvent(ev)
        ev.recycle()
    }

    /** A point on the window's bar, on the display. */
    private fun barPoint(): Pair<Float, Float> = windowParams().let { (it.x + it.width / 2f) to (it.y + 28f) }

    /** Drag the window by its bar to the display's [left] or right side at
     *  height [y], keep the finger there for the dock's hold, and let go. */
    private fun dockAt(left: Boolean, y: Float) {
        val (bx, by) = barPoint()
        val sideX = if (left) 3f else 1277f
        touch(MotionEvent.ACTION_DOWN, bx, by)
        touch(MotionEvent.ACTION_MOVE, (bx + sideX) / 2, (by + y) / 2)
        touch(MotionEvent.ACTION_MOVE, sideX, y)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DetachedWindow.DOCK_HOLD_MS))
        touch(MotionEvent.ACTION_UP, sideX, y)
        settle()
    }

    /** The slot the icon's own drag would save for a release at height [y]. */
    private fun slotFraction(y: Int): Float {
        val env = a11y.overlayHost.environment(d0)!!
        val inset = (IconDock.GESTURE_INSET_DP * ctx.resources.displayMetrics.density).toInt()
        return IconDock.fraction(y, env.geometry.height, env.covered, inset)
    }

    @Test fun `the dock gesture puts the icon on that side, at the height the finger was`() {
        detach()

        dockAt(left = true, y = 250f)

        assertIconOnly()
        assertEquals(ControlsForm.ICON, prefs.detachedStateForDisplay(d0).form)
        val left = prefs.iconPositionForDisplay(d0)
        assertEquals(FloatingOverlayIcon.Edge.LEFT.ordinal, left.edge)
        assertEquals(slotFraction(250), left.fraction, 0.001f)

        detach()
        dockAt(left = false, y = 600f)

        assertIconOnly()
        val right = prefs.iconPositionForDisplay(d0)
        assertEquals(FloatingOverlayIcon.Edge.RIGHT.ordinal, right.edge)
        assertEquals(slotFraction(600), right.fraction, 0.001f)
        assertTrue("lower on the screen than the first", right.fraction > left.fraction)
    }

    @Test fun `a touch on the window makes its display the one the hotkeys act on`() {
        val svc = captureService()
        detach()
        svc.lastInteractedDisplayId = d0 + 3
        val (bx, by) = barPoint()

        touch(MotionEvent.ACTION_DOWN, bx, by)
        touch(MotionEvent.ACTION_UP, bx, by)
        settle()

        assertEquals(d0, svc.lastInteractedDisplayId)
    }

    @Test fun `a change of the display ends a move under way, and the window goes back to rest`() {
        detach()
        val restX = windowParams().x
        val restY = windowParams().y
        val (bx, by) = barPoint()
        touch(MotionEvent.ACTION_DOWN, bx, by)
        touch(MotionEvent.ACTION_MOVE, bx - 100, by + 100)
        assertEquals("the move is under way", restX - 100, windowParams().x)

        (field("displayListener").get(controller) as DisplayManager.DisplayListener).onDisplayChanged(d0)
        settle()
        assertEquals("back at rest", restX to restY, windowParams().x to windowParams().y)

        touch(MotionEvent.ACTION_MOVE, bx - 200, by + 200)
        assertEquals("the rest of the touch moves nothing", restX to restY, windowParams().x to windowParams().y)
        touch(MotionEvent.ACTION_UP, bx - 200, by + 200)
        settle()
    }

    @Test fun `detaching stops a running auto-translate, and nothing restarts it`() {
        val svc = captureService()
        val mode = running(svc)

        detach()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertWindowOnly()
        assertFalse("auto-translate stopped", svc.isLive)
        assertTrue(mode.stops > 0)
    }
}
