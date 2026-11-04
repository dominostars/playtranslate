package com.playtranslate

import android.content.ComponentName
import android.content.Context
import android.os.Looper
import android.provider.Settings
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.capture.CaptureLifecycle
import com.playtranslate.ui.FloatingOverlayIcon
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import java.time.Duration

/**
 * The floating icons' spinner for the app's own reads on two screens
 * ([CaptureService.showReadOnIcons]): the page is on the other screen, so
 * the icons on the game screen spin while a read is in its first stage, and
 * stop at whatever comes next. A newer read takes the spinner over; a
 * hold's spinner and a read's share the icons, each ending only its own
 * share; an icon installed during a read wears it.
 *
 * On [CapturePauseFunnelsTest]'s footing: the accessibility service is
 * created and resolved as the active backend, so its controller is the one
 * the service pushes to.
 */
@RunWith(RobolectricTestRunner::class)
class IconReadSpinnerTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private var service: ServiceController<CaptureService>? = null
    private var a11y: ServiceController<PlayTranslateAccessibilityService>? = null

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun controller(): OverlayUiController = a11y!!.get().overlayUiController
    private fun svc(): CaptureService = service!!.get()

    @Before fun setUp() {
        clearPrefs()
        Settings.Secure.putString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ComponentName(ctx, PlayTranslateAccessibilityService::class.java).flattenToString(),
        )
        CaptureBackendResolver.reresolve(ctx)
        CaptureLifecycle.setFloatingIconSuppressed(ctx, false)
        prefs.captureDisplayIds = setOf(Display.DEFAULT_DISPLAY)
        MainActivity.isInForeground = false
        val built = Robolectric.buildService(PlayTranslateAccessibilityService::class.java).create()
        a11y = built
        PlayTranslateAccessibilityService.instance = built.get()
        val svc = Robolectric.buildService(CaptureService::class.java).create()
        service = svc
        svc.get().gameDisplayIds = setOf(Display.DEFAULT_DISPLAY)
        assertTrue(CaptureBackendResolver.activeOverlayUi === built.get().overlayUiController)
        settle()
    }

    @After fun tearDown() {
        service?.destroy()
        service = null
        a11y?.get()?.overlayUiController?.hideAll()
        a11y?.get()?.overlayUiController?.destroy()
        PlayTranslateAccessibilityService.instance = null
        a11y = null
        CaptureLifecycle.setFloatingIconSuppressed(ctx, true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        clearPrefs()
    }

    /** Install the default display's icon and return it. */
    private fun icon(): FloatingOverlayIcon {
        controller().reconcileFloatingIcons(freshAppearance = false)
        settle()
        val handles = OverlayUiController::class.java.getDeclaredField("iconHandles")
            .apply { isAccessible = true }.get(controller()) as Map<*, *>
        val handle = handles[Display.DEFAULT_DISPLAY]
        assertNotNull("an icon on the default display", handle)
        return handle!!.javaClass.getDeclaredField("icon").apply { isAccessible = true }
            .get(handle) as FloatingOverlayIcon
    }

    /** A read in its first stage, shown on the icons. */
    private fun read(): MutableStateFlow<CaptureState> {
        val state = MutableStateFlow<CaptureState>(CaptureState.InProgress("Capturing"))
        svc().showReadOnIcons(CaptureSession(state, Job()))
        settle()
        return state
    }

    @Test fun `the icons spin through a read's first stage and stop at what comes next`() {
        val icon = icon()

        val state = read()
        assertTrue(icon.showLoading)
        state.value = CaptureState.InProgress("Recognizing text")
        settle()
        assertTrue("still reading", icon.showLoading)

        state.value = CaptureState.Failed("no luck")
        settle()
        assertFalse(icon.showLoading)
    }

    @Test fun `a newer read takes the spinner over, and the older one's end leaves it`() {
        val icon = icon()
        val older = read()
        val newer = read()

        older.value = CaptureState.Cancelled
        settle()
        assertTrue("the newer read is still reading", icon.showLoading)

        newer.value = CaptureState.Cancelled
        settle()
        assertFalse(icon.showLoading)
    }

    @Test fun `a hold's spinner and a read's share the icons, each ending only its own`() {
        val icon = icon()

        val first = read()
        svc().setHoldLoading(true)
        first.value = CaptureState.Cancelled
        settle()
        assertTrue("the hold's one-shot is still running", icon.showLoading)
        svc().setHoldLoading(false)
        assertFalse(icon.showLoading)

        val second = read()
        svc().setHoldLoading(true)
        svc().setHoldLoading(false)
        assertTrue("the read is still reading", icon.showLoading)
        second.value = CaptureState.Cancelled
        settle()
        assertFalse(icon.showLoading)
    }

    // The icons outlive the service on this backend; its watch dies with it.
    @Test fun `a service that goes mid-read leaves the icons still`() {
        val icon = icon()
        read()
        svc().setHoldLoading(true)
        assertTrue(icon.showLoading)

        service!!.destroy()
        service = null
        settle()

        assertFalse(icon.showLoading)
    }

    @Test fun `an icon installed during a read wears the spinner`() {
        read()

        val icon = icon()

        assertTrue(icon.showLoading)
    }
}
