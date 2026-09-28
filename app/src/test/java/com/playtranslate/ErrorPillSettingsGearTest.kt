package com.playtranslate

import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CaptureLifecycle
import com.playtranslate.net.NetworkConnectivity.InternetState
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.OwnWindows
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.OnlineAttempt
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.translation.TranslationError
import com.playtranslate.translation.TranslationErrorKey
import com.playtranslate.translation.TranslationErrorPresenter
import com.playtranslate.translation.TranslationErrorTracker
import com.playtranslate.translation.TranslationErrors
import com.playtranslate.language.SourceLangId
import com.playtranslate.ui.AnkiEditorPage
import com.playtranslate.ui.AnkiSentenceEditorPage
import com.playtranslate.ui.CaptureResultOverlay
import com.playtranslate.ui.DragLookupController
import com.playtranslate.ui.FloatingIconMenu
import com.playtranslate.ui.FloatingOverlayIcon
import com.playtranslate.ui.MagnifierLens
import com.playtranslate.ui.OverlayAlert
import com.playtranslate.ui.OverlayWorkspace
import com.playtranslate.ui.TranslationErrorPills
import com.playtranslate.ui.TranslationServicesActivity
import com.playtranslate.ui.WorkspaceHost
import com.playtranslate.ui.WorkspacePage
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * The gear on a translation-error pill, through a real controller, a real
 * tracker wired to it as the app wires its own, and real pills
 * (2026-09-28): it stops auto-translate (directly on this screen, through the
 * app when the app is in front on the other one), has the tracker show the
 * owner's next failure again but not one already out when the gear was
 * tapped, opens the Translation services page on the pill's display without
 * stacking it on itself, and first closes the over-game surfaces that would
 * cover it: the quick menu, the capture panel, the workspace, the region
 * editor and the word lens, whose close must not restart a session it paused.
 * With an Anki card open in the workspace it asks first ("Discard card?"),
 * and a Cancel leaves everything as it was.
 *
 * "Running" is a [CaptureService] with a stand-in [LiveMode] in its mode map,
 * as in [IconToggleGestureTest].
 */
@RunWith(RobolectricTestRunner::class)
class ErrorPillSettingsGearTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private val host = OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    private val controller = OverlayUiController(ctx, host)
    private val errorPills = field<TranslationErrorPills>("errorPills")
    private var service: ServiceController<CaptureService>? = null

    /** Every pill the tracker put up. */
    private val shown = mutableListOf<TranslationError>()

    private class StandInLiveMode(override val flavor: OverlayFlavor) : LiveMode {
        var stops = 0
        override fun start() {}
        override fun stop() { stops++ }
        override fun refresh() {}
        override fun getCachedState(): CachedOverlayState? = null
    }

    private class StubPage : WorkspacePage {
        override fun title(ctx: Context): CharSequence = "Stub"
        override fun onCreateView(ctx: Context, parent: ViewGroup, host: WorkspaceHost): View = View(ctx)
    }

    /** A card editor, as the workspace's Anki editors are; its [host] lets
     *  a test push a picker above it. */
    private class StubEditor : WorkspacePage {
        var host: WorkspaceHost? = null
        override val isCardEditor: Boolean get() = true
        override fun title(ctx: Context): CharSequence = "Card"
        override fun onCreateView(ctx: Context, parent: ViewGroup, host: WorkspaceHost): View {
            this.host = host
            return View(ctx)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T =
        OverlayUiController::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(controller) as T

    @Suppress("UNCHECKED_CAST")
    private fun <T> Any.member(name: String): T =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    /** The default display's floating icon, installed: its handle (icon,
     *  drag lookup controller). */
    private fun iconHandle(): Any {
        controller.reconcileFloatingIcons(freshAppearance = false)
        settle()
        return field<Map<*, *>>("iconHandles")[Display.DEFAULT_DISPLAY]!!
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Before fun setUp() {
        clearPrefs()
        CaptureLifecycle.setFloatingIconSuppressed(ctx, false)
        prefs.captureDisplayIds = setOf(Display.DEFAULT_DISPLAY)
        val tracker = TranslationErrorTracker(
            now = SystemClock::elapsedRealtime,
            internet = { InternetState.VALIDATED },
        )
        tracker.presenter = object : TranslationErrorPresenter {
            override fun show(error: TranslationError): Boolean =
                controller.showTranslationErrorPill(error).also { if (it) shown += error }
            override fun update(error: TranslationError) = controller.updateTranslationErrorPill(error)
            override fun hide(keys: Collection<TranslationErrorKey>) = controller.hideTranslationErrorPills(keys)
        }
        TranslationErrors.install(tracker)
    }

    @After fun tearDown() {
        MainActivity.isInForeground = false
        controller.hideAll()
        TranslationErrors.resetForTest()
        CaptureLifecycle.setFloatingIconSuppressed(ctx, true)
        service?.destroy()
        service = null
        // Let the churn gate finish every removal inside this test.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        clearPrefs()
    }

    /** Claude's request, sent now, a second after whatever came before it,
     *  failed out of credits. Reported by [report], whenever the test says. */
    private fun outOfCredits(): OnlineAttempt {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        return OnlineAttempt(
            "claude", "Claude", BackendFailure(BackendFailureKind.BILLING),
            SystemClock.elapsedRealtime(), reachedServer = true,
        )
    }

    /** The registry reports [attempt]'s pass, as it does from any pass. */
    private fun report(attempt: OnlineAttempt) {
        TranslationBackendRegistry.onlineAttemptListener!!.invoke(listOf(attempt))
        settle()
    }

    private fun tapGear() {
        val row = errorPills.stackRoot!!.getChildAt(0) as ViewGroup
        row.getChildAt(2).performClick()
        settle()
    }

    /** Every activity started so far, in the order it was started
     *  (Robolectric hands them back newest first). */
    private fun started(): List<ShadowActivity.IntentForResult> {
        val app = shadowOf(ctx as Application)
        return generateSequence { app.nextStartedActivityForResult }.toList().reversed()
    }

    private fun ShadowActivity.IntentForResult.isTranslationServices() =
        intent.component?.className == TranslationServicesActivity::class.java.name

    /** Auto-translate running on the default display. */
    private fun running(): StandInLiveMode {
        val built = Robolectric.buildService(CaptureService::class.java).create()
        service = built
        val svc = built.get()
        svc.gameDisplayIds = setOf(Display.DEFAULT_DISPLAY)
        val mode = StandInLiveMode(OverlayFlavor.TRANSLATION)
        val modes = CaptureService::class.java.getDeclaredField("liveModes")
            .apply { isAccessible = true }.get(svc)
        MutableMap::class.java.getMethod("put", Any::class.java, Any::class.java)
            .invoke(modes, Display.DEFAULT_DISPLAY, mode)
        assertTrue(svc.isLive)
        return mode
    }

    private fun defaultDisplay(): Display =
        ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)

    /** A workspace open over the game with a card editor on it. */
    private fun cardOpen(): StubEditor {
        val editor = StubEditor()
        assertTrue(controller.openWorkspace(Display.DEFAULT_DISPLAY) { editor })
        settle()
        return editor
    }

    /** The gear's "Discard card?" confirm, while it is up. */
    private fun confirm(): OverlayAlert? = field("discardCardConfirm")

    private fun OverlayAlert.scrim(): ViewGroup = member("scrim")

    private fun workspaceModals(): ViewGroup = field<OverlayWorkspace>("workspace").member("modalLayer")

    private fun View.withText(text: String): View? {
        if (this is TextView && this.text.toString() == text) return this
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).withText(text)?.let { return it }
        return null
    }

    // ── auto-translate stops ───────────────────────────────────────────

    @Test fun `the gear stops auto-translate and opens Translation services, once`() {
        val mode = running()
        report(outOfCredits())
        assertNotNull("the pill is up", errorPills.stackRoot)

        tapGear()

        assertEquals(1, mode.stops)
        assertFalse(service!!.get().isLive)
        assertNull("the pill is down", errorPills.stackRoot)
        val launch = started().single()
        assertTrue(launch.isTranslationServices())
        assertTrue(launch.intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue("never a second copy on top of itself", launch.intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }

    // Codex adversarial 2026-09-28: the stop used to be a message to the
    // app, so a pass sent before the app got to it came after the cutoff
    // and could put the pill back up.
    @Test fun `with the app in front on the other screen the gear still stops it here and now`() {
        ShadowDisplayManager.addDisplay("w640dp-h480dp")
        MainActivity.isInForeground = true
        val mode = running()
        report(outOfCredits())

        tapGear()

        assertEquals("stopped before the gear returns", 1, mode.stops)
        assertFalse(service!!.get().isLive)
        assertTrue("no stop message to the app", started().single().isTranslationServices())
    }

    // Codex adversarial 2026-09-28: a start still waiting on the consent
    // dialog isn't live, so the gear skipped the stop, and the start began
    // once the dialog was answered.
    @Test fun `a start still pending when the gear is tapped never begins`() {
        ShadowDisplayManager.addDisplay("w640dp-h480dp")
        MainActivity.isInForeground = true   // the app ignores a stop while nothing is live
        val built = Robolectric.buildService(CaptureService::class.java).create()
        service = built
        val svc = built.get()
        val pending = kotlinx.coroutines.Job()   // the start, suspended in its consent await
        CaptureService::class.java.getDeclaredField("pendingLiveStart")
            .apply { isAccessible = true }.set(svc, pending)
        assertFalse(svc.isLive)
        assertTrue(svc.isLiveStartPending)
        report(outOfCredits())

        tapGear()

        assertTrue("the start is cancelled", pending.isCancelled)
        assertFalse(svc.isLiveStartPending)
        assertTrue(started().single().isTranslationServices())
    }

    @Test fun `with auto-translate off the gear only opens the page`() {
        report(outOfCredits())

        tapGear()

        assertTrue(started().single().isTranslationServices())
    }

    // ── where the page opens ───────────────────────────────────────────

    @Test fun `the page opens on the pill's display when none of our screens is in front`() {
        val second = ShadowDisplayManager.addDisplay("w640dp-h480dp")
        prefs.captureDisplayIds = setOf(second)
        report(outOfCredits())
        assertEquals("the pill went where capture is", second, errorPills.displayId)

        tapGear()

        val options = started().single().options
        assertNotNull(options)
        assertEquals(second, options!!.getInt("android.activity.launchDisplayId", -1))
    }

    // ── the tracker tries again ────────────────────────────────────────

    @Test fun `after the gear the same failure shows again, but not one already out`() {
        report(outOfCredits())
        report(outOfCredits())
        assertEquals("once per outage", 1, shown.size)
        val inFlight = outOfCredits()   // out when the gear is tapped

        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        tapGear()
        report(inFlight)
        assertEquals("already out: not the next try", 1, shown.size)
        assertNull(errorPills.stackRoot)

        report(outOfCredits())
        assertEquals("the next try shows", 2, shown.size)
        assertNotNull(errorPills.stackRoot)
    }

    @Test fun `the × doesn't try again, or open anything`() {
        report(outOfCredits())
        val row = errorPills.stackRoot!!.getChildAt(0) as ViewGroup
        row.getChildAt(row.childCount - 1).performClick()
        settle()

        report(outOfCredits())
        assertEquals("closed is seen: same outage", 1, shown.size)
        assertTrue(started().isEmpty())
    }

    // ── what would cover the page goes first ───────────────────────────

    @Test fun `the gear closes the quick menu under the pill`() {
        iconHandle().member<FloatingOverlayIcon>("icon").onTap!!.invoke()   // the default Tap: the quick menu
        assertNotNull(field<FloatingIconMenu?>("floatingMenu"))
        report(outOfCredits())   // its pill goes up over the menu

        tapGear()

        assertNull(field<FloatingIconMenu?>("floatingMenu"))
        assertTrue(started().single().isTranslationServices())
    }

    @Test fun `the gear closes the capture panel and the workspace`() {
        val wm = OwnWindows.manager(ctx.createDisplayContext(defaultDisplay()))!!
        OverlayUiController::class.java.getDeclaredField("captureResultOverlay")
            .apply { isAccessible = true }
            .set(controller, CaptureResultOverlay(ctx, wm, Display.DEFAULT_DISPLAY, host))
        assertTrue(controller.openWorkspace(Display.DEFAULT_DISPLAY) { StubPage() })
        settle()
        report(outOfCredits())

        tapGear()

        assertNull(field<CaptureResultOverlay?>("captureResultOverlay"))
        assertNull(field<Any?>("workspace"))
    }

    // ── an Anki card open in the workspace: ask first (2026-09-28) ─────

    @Test fun `with a card open the gear asks first, over the card, and does nothing yet`() {
        val mode = running()
        cardOpen()
        report(outOfCredits())

        tapGear()

        val alert = confirm()
        assertNotNull("the confirm is up", alert)
        val scrim = alert!!.scrim()
        assertNotNull(scrim.withText("Discard card?"))
        assertNotNull(scrim.withText(
            "If you launch settings, you will lose this card and have to regenerate it from a new capture.",
        ))
        assertSame("in the workspace's window, over the card", workspaceModals(), scrim.parent)
        assertNotNull("the pill is still up", errorPills.stackRoot)
        assertNotNull("the card is still open", field<OverlayWorkspace?>("workspace"))
        assertEquals(0, mode.stops)
        assertTrue(started().isEmpty())
    }

    @Test fun `Go to settings, the top button in the destructive style, goes on and closes the card`() {
        val mode = running()
        cardOpen()
        report(outOfCredits())
        tapGear()
        val scrim = confirm()!!.scrim()
        val go = scrim.withText("Go to settings") as TextView
        val cancel = scrim.withText("Cancel") as TextView
        assertSame("Go to settings, then Cancel beneath it", go.parent, cancel.parent)
        val buttons = go.parent as ViewGroup
        assertTrue(buttons.indexOfChild(go) < buttons.indexOfChild(cancel))
        val themed = com.playtranslate.overlayThemedContext(ctx)
        assertEquals(
            themed.themeColor(R.attr.ptDanger),
            (go.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor,
        )

        go.performClick()
        settle()

        assertNull("the card is closed", field<OverlayWorkspace?>("workspace"))
        assertNull("the pill is down", errorPills.stackRoot)
        assertEquals(1, mode.stops)
        assertTrue(started().single().isTranslationServices())
        assertNull(confirm())
    }

    @Test fun `Cancel leaves everything as it was, the pill included`() {
        val mode = running()
        cardOpen()
        report(outOfCredits())
        tapGear()

        confirm()!!.scrim().withText("Cancel")!!.performClick()
        settle()

        assertNull(confirm())
        assertNotNull("the card is still open", field<OverlayWorkspace?>("workspace"))
        assertNotNull("the pill is still up", errorPills.stackRoot)
        assertEquals(0, mode.stops)
        assertTrue(started().isEmpty())
        report(outOfCredits())
        assertEquals("nothing tried again: the same outage", 1, shown.size)
    }

    @Test fun `a card under a picker it opened still asks`() {
        cardOpen().host!!.push(StubPage())   // the deck picker, say
        settle()
        report(outOfCredits())

        tapGear()

        assertNotNull(confirm())
        assertTrue(started().isEmpty())
    }

    @Test fun `a second tap while it asks adds no second confirm`() {
        cardOpen()
        report(outOfCredits())

        tapGear()
        val first = confirm()
        tapGear()

        assertSame(first, confirm())
        assertEquals(1, workspaceModals().childCount)
    }

    @Test fun `the workspace's two Anki editors are card editors`() {
        assertTrue(AnkiEditorPage(android.os.Bundle()).isCardEditor)
        assertTrue(
            AnkiSentenceEditorPage("original", "translation", emptyMap(), emptyMap(), emptyMap(), null, SourceLangId.JA)
                .isCardEditor,
        )
        assertFalse(StubPage().isCardEditor)
    }

    // Codex adversarial 2026-09-28: a lens that paused auto-translate kept its
    // restart through the gear and stayed up over the page, and closing it
    // there restarted auto-translate.
    @Test fun `a session the word lens paused stays stopped, and the lens closes`() {
        // The app in front on the other screen, so a restart is a message to
        // it that this test can see.
        ShadowDisplayManager.addDisplay("w640dp-h480dp")
        MainActivity.isInForeground = true
        val handle = iconHandle()
        running()
        handle.member<FloatingOverlayIcon>("icon").onDragStart!!.invoke()   // the lens pauses it
        service!!.get().stopLive()   // as the app does with that pause's stop
        // The drag ends on a word: the lens stays up, settled.
        val lens = handle.member<DragLookupController>("dragController")
        DragLookupController::class.java.getDeclaredField("dragInProgress")
            .apply { isAccessible = true }.setBoolean(lens, false)
        val magnifier = lens.member<MagnifierLens>("magnifier")
        val screen = ctx.resources.displayMetrics
        magnifier.show(screen.widthPixels / 2, screen.heightPixels / 2, screen.widthPixels, screen.heightPixels)
        magnifier.makeInteractive()
        settle()
        assertNotNull("the lens is up", magnifier.member<Any?>("lensView"))
        report(outOfCredits())   // a pass already out fails: its pill goes up over the lens
        started()                // (the pause's stop)

        tapGear()

        assertNull("the lens closed", magnifier.member<Any?>("lensView"))
        assertEquals(
            "and restarted nothing as it closed",
            listOf(TranslationServicesActivity::class.java.name),
            started().map { it.intent.component?.className },
        )
    }

    @Test fun `the gear closes the region editor`() {
        val regions = field<RegionOverlayController>("regionController")
        regions.showRegionEditor(defaultDisplay())
        settle()
        assertTrue(controller.isRegionEditorActive)
        report(outOfCredits())

        tapGear()

        assertFalse(controller.isRegionEditorActive)
    }
}
