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
import android.view.ViewGroup
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
import org.junit.Assert.assertNotEquals
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.time.Duration

/**
 * The test runtime's window manager, refusing the adds [refuses] names the
 * way the platform refuses a window (a thrown BadTokenException). A shadow
 * and not a wrapper: the controller takes its manager from the display's
 * own context, which no test hands it.
 */
@Implements(className = "android.view.WindowManagerImpl", isInAndroidSdk = false)
class RefusingWindowManager : ShadowWindowManagerImpl() {
    @Implementation
    public override fun addView(view: View, layoutParams: ViewGroup.LayoutParams) {
        if (refuses?.invoke(view) == true) throw WindowManager.BadTokenException("refused (test)")
        super.addView(view, layoutParams)
    }

    companion object {
        @JvmStatic var refuses: ((View) -> Boolean)? = null
    }
}

/**
 * The floating controls' two forms on the real controller: the icon's quick
 * settings detach it into the window, the window's Minimize brings the icon
 * back, the form is what a later reconcile shows, every gate that hides the
 * icon hides the window, the icon stands in wherever the window cannot be,
 * a change of form never reads as "no controls", and auto-translate runs in
 * the window's page with nothing over the game: a start leaves the window
 * up, a change of form moves a running session between the page and the
 * boxes over the game, and only a session the window cannot hold (two
 * captured displays, the app in front on a second screen) docks it.
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
        // A test that paced its frames hands the runtime's own pacing back.
        ShadowChoreographer.setPaused(false)
        activities.forEach { runCatching { it.pause().stop().destroy() } }
        MainActivity.isInForeground = false
        MainActivity.isInMultiWindowMode = false
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

    // ── The flight between the two forms ─────────────────────────────────

    /** Frames come as the clock is moved, so a flight can be seen under
     *  way; the runtime would otherwise run it out in one turn. */
    private fun paceFrames() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    /** Let a flight run out, a frame at a time. */
    private fun land() = repeat(60) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }

    private fun paramsOf(view: View) = view.layoutParams as WindowManager.LayoutParams

    /** The centre of a window on the display. */
    private fun centreOf(view: View): Pair<Int, Int> =
        paramsOf(view).let { (it.x + it.width / 2) to (it.y + it.height / 2) }

    private fun cardOf(window: DetachedWindow): View = window.root.findViewById(R.id.detachedCard)

    /** Whether [window] has been taken down: hidden and deaf at once (the
     *  churn gate's), destroyed a quiet moment later. */
    private fun takenDown(window: DetachedWindow) =
        paramsOf(window.root).alpha == 0f || !window.root.isAttachedToWindow

    @Test fun `Detach flies the window out of the icon's place, and Minimize flies it back into the icon's`() {
        paceFrames()
        val iconAt = centreOf(icon()!!)

        detach()
        val window = window()!!
        assertWindowOnly()
        assertEquals("it starts on the icon", iconAt, centreOf(window.root))
        assertEquals("unseen", 0f, cardOf(window).alpha, 0.001f)
        assertTrue("and small", cardOf(window).scaleX < 0.5f)

        land()
        assertNotEquals("it has left the icon's place", iconAt, centreOf(window.root))
        assertEquals("at rest, whole and solid", 1f, cardOf(window).scaleX, 0f)
        assertEquals(1f, cardOf(window).alpha, 0f)
        val rest = centreOf(window.root)

        tap(R.id.detachedBarMinimize)
        assertIconOnly()
        assertFalse("the window it was is still up, on its way", takenDown(window))
        assertEquals("from where it stood", rest, centreOf(window.root))

        land()
        assertTrue("and gone once it is there", takenDown(window))
        assertEquals("into the icon that took its place", centreOf(icon()!!), centreOf(window.root))
    }

    @Test fun `a window docked because an auto-translate it cannot hold starts goes at once`() {
        paceFrames()
        val svc = captureService()
        detach()
        land()
        val docked = window()!!
        // Two captured displays: one stream of results, a page each.
        svc.gameDisplayIds = setOf(d0, secondDisplay())

        svc.startLive()

        assertIconOnly()
        assertTrue("no flight ahead of the first capture", takenDown(docked))
    }

    @Test fun `the icon standing in for the window, and the window coming back, change at once`() {
        paceFrames()
        detach()
        land()
        val covered = window()!!

        val screen = ownActivity().create().start()
        settle()
        assertIconOnly()
        assertTrue("no flight over a screen of ours", takenDown(covered))

        screen.stop()
        settle()
        val back = window()!!
        assertEquals("the window that comes back is in place at once", 1f, cardOf(back).alpha, 0f)
        assertEquals(1f, cardOf(back).scaleX, 0f)
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

    // ── A window the icon could not replace ──────────────────────────────

    @Config(shadows = [RefusingWindowManager::class])
    @Test fun `a window the icon could not take the place of stays up with its page`() {
        detach()
        val page = controller.detachedPageForTest(d0)
        assertNotNull(page)

        try {
            RefusingWindowManager.refuses = { it is FloatingOverlayIcon }
            tap(R.id.detachedBarMinimize)
        } finally {
            RefusingWindowManager.refuses = null
        }

        assertWindowOnly()
        assertSame("the page it shows is still the display's", page, controller.detachedPageForTest(d0))

        // The next pass makes the change the system refused, and the page
        // goes with the window.
        controller.reconcileFloatingIcons(freshAppearance = false)
        settle()
        assertIconOnly()
        assertNull(controller.detachedPageForTest(d0))
    }

    // ── A summoned window is a fresh placement ───────────────────────────

    /** The game-audio consent prompt a user's summon owes a fresh placement,
     *  parked for the capture service these tests have not started. */
    private fun promptParked(): Boolean {
        assertNull("no capture service, so a prompt owed is parked", CaptureService.instance)
        return field("placementPromptPending").getBoolean(controller)
    }

    @Test fun `a window the user's summon brings straight up is owed the game-audio prompt, as an icon is`() {
        detach()
        CaptureLifecycle.setFloatingIconSuppressed(ctx, true)
        controller.reconcileFloatingIcons(freshAppearance = false)
        settle()
        assertFalse("nothing is owed with no controls up", promptParked())

        CaptureLifecycle.setFloatingIconSuppressed(ctx, false)
        controller.reconcileFloatingIcons(userSummoned = true)
        settle()

        assertWindowOnly()
        assertTrue(promptParked())
    }

    @Test fun `a window that takes the icon's place in a summon is no fresh placement`() {
        assertIconOnly()
        prefs.setControlsForm(d0, ControlsForm.WINDOW)

        controller.reconcileFloatingIcons(userSummoned = true)
        settle()

        assertWindowOnly()
        assertFalse(promptParked())
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

    /** A second display, connected and on, and its id. */
    private fun secondDisplay(): Int = ShadowDisplayManager.addDisplay("w640dp-h480dp")

    private fun page() = controller.detachedPageForTest(d0)

    private fun autoLabel(): String =
        (window()!!.root.findViewById<ViewGroup>(R.id.detachedBarAuto).getChildAt(1) as android.widget.TextView)
            .text.toString()

    private fun pageStatus(): String? =
        (page()!!.vm.result.value as? com.playtranslate.ui.ResultState.Status)?.message

    /** The session runs with nothing over the game, its results the page's. */
    private fun assertRunsInTheWindow(svc: CaptureService) {
        assertTrue("auto-translate runs", svc.isLive)
        assertTrue("results only: nothing is drawn over the game", svc.isInAppOnly)
        assertEquals(LiveSurface.WINDOW_PAGE, svc.liveSurface())
        assertTrue("the window's page follows it", page()!!.followsLive)
    }

    @Test fun `the bar's Auto starts auto-translate in the window, which stays, and then reads Pause`() {
        val svc = captureService()
        detach()
        assertEquals(ctx.getString(R.string.live_mode_auto_label), autoLabel())

        tap(R.id.detachedBarAuto)

        assertWindowOnly()
        assertEquals("the form is the user's to change", ControlsForm.WINDOW, prefs.detachedStateForDisplay(d0).form)
        assertRunsInTheWindow(svc)
        assertEquals(ctx.getString(R.string.live_mode_pause_label), autoLabel())
        assertTrue("the page says it is looking", pageStatus()!!.startsWith("Searching for"))
        // The app is behind the whole time: a results-only session whose
        // page is the app's would have stopped by now.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertRunsInTheWindow(svc)

        tap(R.id.detachedBarAuto)

        assertFalse("the same button stops it", svc.isLive)
        assertEquals(ctx.getString(R.string.live_mode_auto_label), autoLabel())
        assertFalse(page()!!.followsLive)
        assertWindowOnly()
    }

    @Test fun `what the session reports shows in the window's page`() {
        val svc = captureService()
        detach()
        tap(R.id.detachedBarAuto)

        svc.emitError("the backend is down")
        settle()

        assertEquals(
            "the backend is down",
            (page()!!.vm.result.value as com.playtranslate.ui.ResultState.Error).message,
        )
    }

    @Test fun `a start from anywhere leaves the window and runs in its page`() {
        val svc = captureService()
        detach()

        svc.startLive()
        settle()

        assertWindowOnly()
        assertEquals(ControlsForm.WINDOW, prefs.detachedStateForDisplay(d0).form)
        assertRunsInTheWindow(svc)
        assertTrue(window()!!.autoRunning)
    }

    @Test fun `a start while the icon stands in is the window's, which comes back to it running`() {
        val svc = captureService()
        detach()
        val screen = ownActivity().create().start()
        settle()

        svc.startLive()
        settle()
        assertIconOnly()
        assertEquals("the form stays", ControlsForm.WINDOW, prefs.detachedStateForDisplay(d0).form)
        assertRunsInTheWindow(svc)

        screen.stop()
        settle()

        assertWindowOnly()
        assertRunsInTheWindow(svc)
        assertTrue("and its Auto says so", window()!!.autoRunning)
    }

    @Test fun `one of our screens coming and going leaves a session in the window running`() {
        val svc = captureService()
        detach()
        tap(R.id.detachedBarAuto)
        val following = page()

        val screen = ownActivity().create().start()
        settle()
        assertIconOnly()
        assertRunsInTheWindow(svc)
        assertSame("the same page, still following", following, page())

        screen.stop()
        settle()
        assertWindowOnly()
        assertRunsInTheWindow(svc)
    }

    @Test fun `the app coming forward on a second screen moves a session in the window to boxes over the game, and its leaving moves it back`() {
        val svc = captureService()
        detach()
        tap(R.id.detachedBarAuto)
        assertRunsInTheWindow(svc)
        secondDisplay()

        // The app's resume: its foreground flag, then its reconcile.
        MainActivity.isInForeground = true
        controller.reconcileFloatingIcons(freshAppearance = false)
        settle()

        assertIconOnly()
        assertEquals("the form stays the window", ControlsForm.WINDOW, prefs.detachedStateForDisplay(d0).form)
        assertTrue("auto-translate goes on", svc.isLive)
        assertEquals(LiveSurface.OVERLAYS, svc.liveSurface())
        assertFalse("as boxes over the game, which Show on screen asks for", svc.isInAppOnly)
        assertTrue("and the icon says it runs", icon()!!.liveMode)
        assertFalse("the page follows it no more", page()!!.followsLive)

        // Something the app's page was shown meanwhile.
        svc.emitError("shown in the app")

        // The app's stop: the flag, then the pass its leaving runs.
        MainActivity.isInForeground = false
        controller.applyControlsForms()
        settle()

        assertWindowOnly()
        assertRunsInTheWindow(svc)
        assertTrue(
            "back in the page it starts as a start does, not from what the app was shown",
            pageStatus()!!.startsWith("Searching for"),
        )
    }

    @Test fun `the app coming forward in split-screen moves a session in the window to boxes over the game too`() {
        val svc = captureService()
        detach()
        tap(R.id.detachedBarAuto)
        // The app's start, on the game's own display: the icon stands in.
        ownActivity().create().start()
        settle()
        assertIconOnly()
        assertRunsInTheWindow(svc)

        // Its resume, beside the game: the flag, then its reconcile, which
        // has no controls left to change.
        MainActivity.isInMultiWindowMode = true
        MainActivity.isInForeground = true
        controller.reconcileFloatingIcons(freshAppearance = false)
        settle()

        assertIconOnly()
        assertTrue("auto-translate goes on", svc.isLive)
        assertEquals(LiveSurface.OVERLAYS, svc.liveSurface())
        assertFalse("as boxes over the game", svc.isInAppOnly)
        assertFalse("and the page learns the session is not its own", page()!!.followsLive)
    }

    @Config(shadows = [RefusingWindowManager::class])
    @Test fun `a Detach whose window the system refuses did not happen, and a running session keeps its boxes`() {
        val svc = captureService()
        val overGame = running(svc)

        try {
            RefusingWindowManager.refuses = { it is DetachedWindow.Root }
            detach()
        } finally {
            RefusingWindowManager.refuses = null
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertIconOnly()
        assertEquals("the saved form is the icon again", ControlsForm.ICON, prefs.detachedStateForDisplay(d0).form)
        assertTrue("auto-translate goes on", svc.isLive)
        assertEquals(LiveSurface.OVERLAYS, svc.liveSurface())
        assertFalse("as boxes over the game, not waiting on a window that never came", svc.isInAppOnly)
        assertTrue("the tier that was running was swapped out on the way", overGame.stops > 0)
        assertNull("no page is kept for a window that never was", page())

        // The next pass has nothing left to try.
        controller.reconcileFloatingIcons(freshAppearance = false)
        settle()
        assertIconOnly()
    }

    @Test fun `a start with two displays captured docks the window, as before`() {
        val svc = captureService()
        detach()
        svc.gameDisplayIds = setOf(d0, secondDisplay())

        svc.startLive()
        settle()

        assertIconOnly()
        assertEquals("docked for good", ControlsForm.ICON, prefs.detachedStateForDisplay(d0).form)
        assertTrue(svc.isLive)
        assertFalse("boxes over the game, from the icon", svc.isInAppOnly)
    }

    @Test fun `a start with the app in front on a second screen docks the window, as before`() {
        val svc = captureService()
        detach()
        secondDisplay()
        MainActivity.isInForeground = true
        controller.reconcileFloatingIcons(freshAppearance = false)
        settle()
        assertIconOnly()

        svc.startLive()
        settle()

        assertEquals("docked for good", ControlsForm.ICON, prefs.detachedStateForDisplay(d0).form)
        MainActivity.isInForeground = false
        controller.reconcileFloatingIcons(freshAppearance = false)
        settle()
        assertIconOnly()
    }

    @Test fun `Minimize leaves a session running, as boxes over the game from the icon`() {
        val svc = captureService()
        detach()
        tap(R.id.detachedBarAuto)

        tap(R.id.detachedBarMinimize)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertIconOnly()
        assertTrue("auto-translate goes on", svc.isLive)
        assertFalse("as the icon's", svc.isInAppOnly)
        assertEquals(LiveSurface.OVERLAYS, svc.liveSurface())
        assertTrue("and the icon says it runs", icon()!!.liveMode)
    }

    @Test fun `the dock gesture leaves a session running too`() {
        val svc = captureService()
        detach()
        tap(R.id.detachedBarAuto)

        dockAt(left = true, y = 250f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertIconOnly()
        assertTrue(svc.isLive)
        assertFalse(svc.isInAppOnly)
    }

    @Test fun `Capture stops a session running in the window, and nothing restarts it`() {
        val svc = captureService()
        detach()
        tap(R.id.detachedBarAuto)
        assertTrue(svc.isLive)

        tap(R.id.detachedBarCapture)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertFalse("the page is the capture's now", svc.isLive)
        assertFalse(page()!!.followsLive)
        assertEquals(ctx.getString(R.string.live_mode_auto_label), autoLabel())
        assertWindowOnly()
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

    /** Drag the window by its bar on to the display's [left] or right side
     *  at height [y], where it docks, and let go. */
    private fun dockAt(left: Boolean, y: Float) {
        val (bx, by) = barPoint()
        val sideX = if (left) 3f else 1277f
        touch(MotionEvent.ACTION_DOWN, bx, by)
        touch(MotionEvent.ACTION_MOVE, (bx + sideX) / 2, (by + y) / 2)
        touch(MotionEvent.ACTION_MOVE, sideX, y)
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

    // ── The window pulled out of the icon ────────────────────────────────

    /** A touch on the icon at display point ([rawX], [rawY]). */
    private fun iconTouch(action: Int, rawX: Float, rawY: Float) {
        val icon = icon() ?: return
        val p = icon.params!!
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, rawX, rawY, 0)
        ev.setLocation(rawX - p.x, rawY - p.y)
        icon.dispatchTouchEvent(ev)
        ev.recycle()
    }

    /** The icon's centre on the display, and which way is in from its side. */
    private fun iconGrip(): Triple<Float, Float, Int> {
        val icon = icon()!!
        val p = icon.params!!
        val inward = if (icon.currentEdge == FloatingOverlayIcon.Edge.LEFT) 1 else -1
        return Triple(p.x + icon.viewSizePx / 2f, p.y + icon.viewSizePx / 2f, inward)
    }

    /** The window a drag of the icon is pulling out, if one is. */
    private fun pulled(): Any? = field("pull").get(controller)

    /** Press the icon and drag far enough for the drag to begin, the
     *  finger still by the side. */
    private fun beginIconDrag(): Triple<Float, Float, Int> {
        val grip = iconGrip()
        val (cx, cy, inward) = grip
        iconTouch(MotionEvent.ACTION_DOWN, cx + inward * 6, cy)
        iconTouch(MotionEvent.ACTION_MOVE, cx + inward * 26, cy)
        return grip
    }

    @Test fun `bound to pull the window out, a drag of the icon away from its side and a release leave the window, as Detach does`() {
        prefs.iconDragAction = DragAction.PULL_OUT_WINDOW
        val svc = captureService()
        val mode = running(svc)

        val (cx, cy, inward) = beginIconDrag()
        assertNotNull("a window is being pulled out", pulled())
        assertIconOnly()
        assertEquals("nothing is saved while the finger is down", ControlsForm.ICON, prefs.detachedStateForDisplay(d0).form)

        iconTouch(MotionEvent.ACTION_MOVE, cx + inward * 400, cy + 40)
        assertIconOnly()
        iconTouch(MotionEvent.ACTION_UP, cx + inward * 400, cy + 40)
        settle()

        assertWindowOnly()
        assertNull(pulled())
        assertEquals(ControlsForm.WINDOW, prefs.detachedStateForDisplay(d0).form)
        assertNotNull("with the page it was pulled out with", controller.detachedPageForTest(d0))
        assertNotNull("resting where it was let go", prefs.detachedStateForDisplay(d0).bounds(landscape = true))
        assertTrue("the drag stopped the session that painted over the game", mode.stops > 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertRunsInTheWindow(svc)
        assertTrue("its Auto says it runs", window()!!.autoRunning)
        assertWindowOnly()
        // And it is the display's window: Minimize takes it back to the icon.
        tap(R.id.detachedBarMinimize)
        assertIconOnly()
    }

    @Test fun `an auto-translate still starting when the window is pulled out lands in the window`() {
        prefs.iconDragAction = DragAction.PULL_OUT_WINDOW
        val svc = captureService()
        // A start waiting on something (the screen-share prompt): not
        // running, so the drag has nothing to pause.
        CaptureService::class.java.getDeclaredField("pendingLiveStart").apply { isAccessible = true }
            .set(svc, kotlinx.coroutines.Job())
        assertTrue(svc.isLiveStartPending)

        val (cx, cy, inward) = beginIconDrag()
        iconTouch(MotionEvent.ACTION_MOVE, cx + inward * 400, cy + 40)
        iconTouch(MotionEvent.ACTION_UP, cx + inward * 400, cy + 40)
        settle()

        assertWindowOnly()
        assertTrue("it is not called off: where it lands is the window's page now", svc.isLiveStartPending)

        // The start lands.
        svc.startLive()
        settle()

        assertWindowOnly()
        assertRunsInTheWindow(svc)
    }

    @Test fun `with two displays captured, pulling the window out stops auto-translate for good, as before`() {
        prefs.iconDragAction = DragAction.PULL_OUT_WINDOW
        val svc = captureService()
        svc.gameDisplayIds = setOf(d0, secondDisplay())
        running(svc)

        val (cx, cy, inward) = beginIconDrag()
        iconTouch(MotionEvent.ACTION_MOVE, cx + inward * 400, cy + 40)
        iconTouch(MotionEvent.ACTION_UP, cx + inward * 400, cy + 40)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertWindowOnly()
        assertFalse("no overlay tier runs with a window up", svc.isLive)
    }

    @Test fun `dragged back to its side and let go it is still the icon, at the height the finger left it`() {
        prefs.iconDragAction = DragAction.PULL_OUT_WINDOW
        val before = prefs.iconPositionForDisplay(d0)

        val (cx, cy, inward) = beginIconDrag()
        iconTouch(MotionEvent.ACTION_MOVE, cx + inward * 400, cy + 40)
        iconTouch(MotionEvent.ACTION_MOVE, cx + inward * 4, 600f)
        iconTouch(MotionEvent.ACTION_UP, cx + inward * 4, 600f)
        settle()

        assertIconOnly()
        assertNull(pulled())
        assertEquals(ControlsForm.ICON, prefs.detachedStateForDisplay(d0).form)
        assertNull("its page went with it", controller.detachedPageForTest(d0))
        val after = prefs.iconPositionForDisplay(d0)
        assertEquals("the same side", before.edge, after.edge)
        assertEquals(slotFraction(600), after.fraction, 0.001f)
        assertEquals("and the icon stands there", centreOf(icon()!!).second, 600)
    }

    @Test fun `a pulling drag the system takes away leaves the icon where it was`() {
        prefs.iconDragAction = DragAction.PULL_OUT_WINDOW
        val before = prefs.iconPositionForDisplay(d0)

        val (cx, cy, inward) = beginIconDrag()
        iconTouch(MotionEvent.ACTION_MOVE, cx + inward * 400, cy + 40)
        iconTouch(MotionEvent.ACTION_CANCEL, cx + inward * 400, cy + 40)
        settle()

        assertIconOnly()
        assertNull(pulled())
        assertEquals(ControlsForm.ICON, prefs.detachedStateForDisplay(d0).form)
        assertEquals(before, prefs.iconPositionForDisplay(d0))
    }

    @Test fun `the icon taken down while its window is being pulled out takes that window with it`() {
        prefs.iconDragAction = DragAction.PULL_OUT_WINDOW
        beginIconDrag()
        assertNotNull(pulled())

        controller.hideAll()
        settle()

        assertNull(pulled())
        assertNull(window())
        assertNull(icon())
    }

    @Test fun `where the window cannot show, the drag bound to pull it out is the lens`() {
        prefs.iconDragAction = DragAction.PULL_OUT_WINDOW
        ShadowDisplayManager.addDisplay("w640dp-h480dp")
        MainActivity.isInForeground = true

        val (cx, cy, inward) = beginIconDrag()
        iconTouch(MotionEvent.ACTION_MOVE, cx + inward * 400, cy + 40)
        assertNull("no window is pulled out", pulled())
        iconTouch(MotionEvent.ACTION_UP, cx + inward * 400, cy + 40)
        settle()

        assertIconOnly()
        assertEquals(ControlsForm.ICON, prefs.detachedStateForDisplay(d0).form)
    }

    @Test fun `bound to the lens, a drag of the icon pulls no window out`() {
        val (cx, cy, inward) = beginIconDrag()
        iconTouch(MotionEvent.ACTION_MOVE, cx + inward * 400, cy + 40)

        assertNull(pulled())
        iconTouch(MotionEvent.ACTION_UP, cx + inward * 400, cy + 40)
        settle()
        assertIconOnly()
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

    private fun displayReportsAChange() {
        (field("displayListener").get(controller) as DisplayManager.DisplayListener).onDisplayChanged(d0)
        settle()
    }

    @Test fun `a display report with nothing behind it leaves a move under way alone`() {
        detach()
        val restX = windowParams().x
        val restY = windowParams().y
        val (bx, by) = barPoint()
        touch(MotionEvent.ACTION_DOWN, bx, by)
        touch(MotionEvent.ACTION_MOVE, bx - 100, by + 100)
        assertEquals("the move is under way", restX - 100, windowParams().x)

        // What a display that adapts its refresh rate sends mid-gesture.
        displayReportsAChange()
        assertEquals("still under the finger", (restX - 100) to (restY + 100), windowParams().x to windowParams().y)

        touch(MotionEvent.ACTION_MOVE, bx - 200, by + 100)
        assertEquals("and it goes on", (restX - 200) to (restY + 100), windowParams().x to windowParams().y)
        touch(MotionEvent.ACTION_UP, bx - 200, by + 100)
        settle()
        assertEquals("resting where it was let go", (restX - 200) to (restY + 100), windowParams().x to windowParams().y)
    }

    @Test fun `a display that is really another shape ends a move under way, and the window goes back to rest`() {
        detach()
        val (bx, by) = barPoint()
        touch(MotionEvent.ACTION_DOWN, bx, by)
        touch(MotionEvent.ACTION_MOVE, bx - 100, by + 100)
        val moved = windowParams().x to windowParams().y

        // Turned on its side.
        a11y.overlayHost.environmentReader = { testEnvironment(800, 1280, belowBars = false) }
        displayReportsAChange()
        val rest = windowParams().x to windowParams().y
        assertNotEquals("back at rest in the display as it is now", moved, rest)

        touch(MotionEvent.ACTION_MOVE, bx - 200, by + 200)
        assertEquals("the rest of the touch moves nothing", rest, windowParams().x to windowParams().y)
        touch(MotionEvent.ACTION_UP, bx - 200, by + 200)
        settle()
    }

    @Test fun `detaching leaves a running auto-translate running, in the window's page from there`() {
        val svc = captureService()
        val overGame = running(svc)

        detach()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertWindowOnly()
        assertEquals("the tier that painted over the game is replaced, once", 1, overGame.stops)
        assertRunsInTheWindow(svc)
        assertTrue("a session moved into the page starts it as a start does", pageStatus()!!.startsWith("Searching for"))
        assertTrue(window()!!.autoRunning)
    }

    // ── What a session in the window waits for ───────────────────────────

    @Test fun `a window standing still holds nothing, and one under a finger or in flight does`() {
        paceFrames()
        detach()
        assertTrue("flying out of the icon", controller.windowBusy(d0))
        land()
        assertFalse("at rest", controller.windowBusy(d0))
        assertEquals(
            "and what a frame of the whole screen has of it is its rect",
            windowParams().let { android.graphics.Rect(it.x, it.y, it.x + it.width, it.y + it.height) },
            window()!!.screenRect(),
        )
        assertTrue(window()!!.screenRect() in controller.ownChromeRects(d0))

        val (bx, by) = barPoint()
        touch(MotionEvent.ACTION_DOWN, bx, by)
        touch(MotionEvent.ACTION_MOVE, bx - 100, by + 100)
        assertTrue("being moved", controller.windowBusy(d0))
        touch(MotionEvent.ACTION_UP, bx - 100, by + 100)
        land()
        assertFalse("let go", controller.windowBusy(d0))
        assertTrue("its rect follows it", window()!!.screenRect() in controller.ownChromeRects(d0))
    }

    @Test fun `with the icon standing in the window is not there to read around`() {
        detach()
        assertFalse(controller.windowBusy(d0))

        val screen = ownActivity().create().start()
        settle()
        assertTrue(controller.windowBusy(d0))
        assertTrue("and no rect of it is blacked out", controller.ownChromeRects(d0).none { it.width() > 400 })

        screen.stop()
        settle()
        assertFalse(controller.windowBusy(d0))
    }

    @Test fun `the window's own menu holds a session in it`() {
        detach()

        window()!!.bar.moreButton.performClick()
        settle()
        assertTrue("its rows are text over the game", controller.windowBusy(d0))

        window()!!.popovers.dismiss()
        settle()
        assertFalse(controller.windowBusy(d0))
    }

    // ── What stops a session ─────────────────────────────────────────────

    @Test fun `hiding the controls stops a session in the window`() {
        val svc = captureService()
        detach()
        tap(R.id.detachedBarAuto)
        assertTrue(svc.isLive)

        controller.hideAll()
        settle()

        assertFalse("nothing is left to see or stop it with", svc.isLive)
    }

    @Test fun `a session in the app's page still stops when the app leaves the front`() {
        val svc = captureService()
        secondDisplay()
        prefs.hideGameOverlays = true
        MainActivity.isInForeground = true
        svc.startLive()
        settle()
        assertTrue(svc.isLive)
        assertEquals(LiveSurface.APP_PAGE, svc.liveSurface())

        MainActivity.isInForeground = false
        svc.updateForegroundState()

        assertFalse("its results show nowhere now, though the icon is up", svc.isLive)
    }

    @Test fun `a session in the app's page in split-screen stops when the app leaves, and does not turn into boxes over the game`() {
        val svc = captureService()
        prefs.hideGameOverlays = true
        MainActivity.isInMultiWindowMode = true
        MainActivity.isInForeground = true
        svc.startLive()
        settle()
        assertTrue(svc.isLive)
        assertEquals(LiveSurface.APP_PAGE, svc.liveSurface())

        // The app's stop. With the app gone the one screen is the game's
        // alone, where a session would be boxes over the game: the stop
        // rule has to see the app's page's session before anything swaps it.
        MainActivity.isInForeground = false

        assertFalse(svc.isLive)
    }

    @Test fun `with two displays captured, detaching stops a running auto-translate, as before`() {
        val svc = captureService()
        svc.gameDisplayIds = setOf(d0, secondDisplay())
        val mode = running(svc)

        detach()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertWindowOnly()
        assertFalse("auto-translate stopped", svc.isLive)
        assertTrue(mode.stops > 0)
    }
}
