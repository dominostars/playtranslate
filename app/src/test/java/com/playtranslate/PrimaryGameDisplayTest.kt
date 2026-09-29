package com.playtranslate

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CaptureLifecycle
import com.playtranslate.ui.TranslationResultActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowDisplayManager
import java.time.Duration

/**
 * [CaptureService.primaryGameDisplayId] passes over a display that no
 * longer exists. With auto-translate off nothing repairs the remembered
 * display when one is removed (the display listener runs only while live),
 * and the capture hotkey used to take the dead display to the full-screen
 * result page. On [ChangeGameLanguageHotkeyTest]'s footing; that test has
 * the game-language hotkey's case.
 */
@RunWith(RobolectricTestRunner::class)
class PrimaryGameDisplayTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private var service: ServiceController<CaptureService>? = null
    private var a11y: ServiceController<PlayTranslateAccessibilityService>? = null

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Before fun setUp() {
        clearPrefs()
        CaptureLifecycle.setFloatingIconSuppressed(ctx, false)
        prefs.captureDisplayIds = setOf(Display.DEFAULT_DISPLAY)
        val built = Robolectric.buildService(PlayTranslateAccessibilityService::class.java).create()
        a11y = built
        PlayTranslateAccessibilityService.instance = built.get()
        built.get().overlayUiController.reconcileFloatingIcons(freshAppearance = false)
        settle()
        val svc = Robolectric.buildService(CaptureService::class.java).create()
        service = svc
        svc.get().gameDisplayIds = setOf(Display.DEFAULT_DISPLAY)
        built.get().registerHotkeyCallbacks()
        settle()
    }

    @After fun tearDown() {
        service?.destroy()
        service = null
        a11y?.get()?.overlayUiController?.hideAll()
        PlayTranslateAccessibilityService.instance = null
        a11y = null
        CaptureLifecycle.setFloatingIconSuppressed(ctx, true)
        // Let the churn gate finish every removal inside this test.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        clearPrefs()
    }

    private fun svc() = service!!.get()

    private fun addDisplay(): Int = ShadowDisplayManager.addDisplay("w640dp-h480dp")

    @Test fun `the last display touched is the primary while it exists`() {
        val second = addDisplay()
        val third = addDisplay()
        svc().gameDisplayIds = linkedSetOf(second, third)
        svc().lastInteractedDisplayId = third
        assertEquals(third, svc().primaryGameDisplayId())
    }

    @Test fun `a removed last-touched display gives way to the first game display that exists`() {
        val second = addDisplay()
        val third = addDisplay()
        svc().gameDisplayIds = linkedSetOf(second, third)
        svc().lastInteractedDisplayId = second

        ShadowDisplayManager.removeDisplay(second)

        assertEquals(third, svc().primaryGameDisplayId())
    }

    @Test fun `a removed first game display gives way to the next`() {
        val second = addDisplay()
        val third = addDisplay()
        svc().gameDisplayIds = linkedSetOf(second, third)
        svc().lastInteractedDisplayId = null

        ShadowDisplayManager.removeDisplay(second)

        assertEquals(third, svc().primaryGameDisplayId())
    }

    @Test fun `with every game display gone the primary is the default display`() {
        val second = addDisplay()
        svc().gameDisplayIds = setOf(second)
        svc().lastInteractedDisplayId = second

        ShadowDisplayManager.removeDisplay(second)

        assertEquals(Display.DEFAULT_DISPLAY, svc().primaryGameDisplayId())
    }

    // Before: the panel found no such display and fell back to the
    // full-screen result page, which captured the dead display.
    @Test fun `the capture hotkey captures a display that exists after the last-touched one is removed`() {
        val key = KeyEvent.KEYCODE_BUTTON_X
        prefs.hotkeyCaptureTap = key.toString()
        val second = addDisplay()
        svc().lastInteractedDisplayId = second
        ShadowDisplayManager.removeDisplay(second)
        assertEquals("auto-translate off", false, svc().isLive)

        val onKeyEvent = PlayTranslateAccessibilityService::class.java
            .getDeclaredMethod("onKeyEvent", KeyEvent::class.java)
            .apply { isAccessible = true }
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            onKeyEvent.invoke(a11y!!.get(), KeyEvent(0L, 0L, action, key, 0, 0, 0, 0, 0, InputDevice.SOURCE_GAMEPAD))
        }
        settle()

        val captured = OverlayUiController::class.java.getDeclaredField("captureDisplayId")
            .apply { isAccessible = true }.get(a11y!!.get().overlayUiController) as Int?
        assertEquals("the panel went up over the default display", Display.DEFAULT_DISPLAY, captured)
        val app = shadowOf(ctx as Application)
        val launched = generateSequence { app.nextStartedActivity }.toList()
        assertEquals(
            "no full-screen result page",
            emptyList<String>(),
            launched.filter { it.component?.className == TranslationResultActivity::class.java.name }
                .map(Intent::toString),
        )
    }
}
