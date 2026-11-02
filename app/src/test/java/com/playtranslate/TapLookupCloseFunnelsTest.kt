package com.playtranslate

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Looper
import android.provider.Settings
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.capture.CaptureLifecycle
import com.playtranslate.language.TokenSpan
import com.playtranslate.ui.TapLookupSession
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 * What closes tap-to-lookup from outside it, through the capture service
 * and the display listener, which reach the ACTIVE backend's controller
 * ([CaptureBackendResolver.activeOverlayUi]): a live start (the session
 * starting is the restart the boxes owed), a change of the game language
 * (the boxes hold the old language's tokens), and a change of the boxes'
 * display's geometry (they are the old layout's).
 *
 * On [TapToLookupHotkeyTest]'s footing: the accessibility service is
 * created and never connected and resolved as the active backend, so its
 * controller is the one these reach; the capture service runs.
 */
@RunWith(RobolectricTestRunner::class)
class TapLookupCloseFunnelsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private var service: ServiceController<CaptureService>? = null
    private var a11y: ServiceController<PlayTranslateAccessibilityService>? = null

    private class OneWordReader : TapLookupSession.Reader {
        override suspend fun capture(displayId: Int): Bitmap? =
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        override suspend fun readLines(bitmap: Bitmap, displayId: Int) = listOf(
            OcrManager.OcrLine(text = "hello", bounds = Rect(100, 300, 200, 330)),
        )
        override suspend fun tokenize(text: String) = listOf(TokenSpan(text, text))
        override suspend fun saveScreenshot(bitmap: Bitmap): String? = null
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun controller(): OverlayUiController = a11y!!.get().overlayUiController

    private fun session(): TapLookupSession? =
        OverlayUiController::class.java.getDeclaredField("tapLookup")
            .apply { isAccessible = true }.get(controller()) as TapLookupSession?

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
        // Set before the capture service starts: set later it would be a
        // change of the game language.
        prefs.sourceLang = "en"
        prefs.overlayMode = OverlayMode.TRANSLATION
        val built = Robolectric.buildService(PlayTranslateAccessibilityService::class.java).create()
        a11y = built
        PlayTranslateAccessibilityService.instance = built.get()
        built.get().overlayUiController.tapLookupReader = OneWordReader()
        val svc = Robolectric.buildService(CaptureService::class.java).create()
        service = svc
        svc.get().gameDisplayIds = setOf(Display.DEFAULT_DISPLAY)
        assertTrue(CaptureBackendResolver.activeOverlayUi === built.get().overlayUiController)
        settle()
    }

    @After fun tearDown() {
        service?.get()?.stopLive()
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

    private fun open() {
        controller().toggleTapToLookupForDisplay(Display.DEFAULT_DISPLAY)
        settle()
        assertNotNull(session())
    }

    @Test fun `a live start closes the boxes`() {
        open()
        service!!.get().startLive()
        assertNull(session())
    }

    @Test fun `a change of the game language closes the boxes`() {
        open()
        prefs.sourceLang = "es"
        settle()
        assertNull(session())
    }

    @Test fun `a turn of the boxes' display closes them`() {
        controller().attach()
        open()

        ShadowDisplayManager.changeDisplay(Display.DEFAULT_DISPLAY, "+land")
        settle()

        assertNull(session())
    }
}
