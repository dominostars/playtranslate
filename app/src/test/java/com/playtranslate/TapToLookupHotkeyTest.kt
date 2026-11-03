package com.playtranslate

import android.content.ComponentName
import android.content.Context
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.capture.CaptureLifecycle
import com.playtranslate.language.LanguagePackStore
import com.playtranslate.language.SourceLangId
import com.playtranslate.ui.CaptureResultOverlay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import java.time.Duration

/**
 * The "Enable tap-to-lookup" hotkey, driven from a controller key press
 * through the accessibility service's hotkey dispatch: a capture of the
 * primary game display in Definitions, by an override of that one capture
 * that writes no setting. A second press is the user's close; a press over
 * a capture in another mode switches it to Definitions in place; a plain
 * capture afterwards opens in the remembered mode; the capture hotkey
 * closes it as it closes any capture, and so does the game-language
 * hotkey's switch, its words having been read in the old language.
 *
 * On [ChangeGameLanguageHotkeyTest]'s footing: the accessibility service is
 * created and never connected, and its floating icon is up, which is what
 * lets a hotkey fire; the capture service runs, since the dispatch is wired
 * to it. One screen, so the capture is the over-game sheet.
 */
@RunWith(RobolectricTestRunner::class)
class TapToLookupHotkeyTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private var service: ServiceController<CaptureService>? = null
    private var a11y: ServiceController<PlayTranslateAccessibilityService>? = null
    private val lookupKey = KeyEvent.KEYCODE_BUTTON_L1
    private val captureKey = KeyEvent.KEYCODE_BUTTON_X
    private val languageKey = KeyEvent.KEYCODE_BUTTON_Y

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    /** Past the sheet's exit slide. */
    private fun settleExit() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))

    private fun sp() = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    private fun controller(): OverlayUiController = a11y!!.get().overlayUiController

    private fun sheet(): CaptureResultOverlay? = controller().captureSheetForTest

    @Before fun setUp() {
        sp().edit().clear().commit()
        // The resolver flag is process-global: a class that ran before this
        // one may have left MediaProjection active. Resolve into
        // accessibility, whose controller the hotkeys reach.
        Settings.Secure.putString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ComponentName(ctx, PlayTranslateAccessibilityService::class.java).flattenToString(),
        )
        CaptureBackendResolver.reresolve(ctx)
        LanguagePackStore.rootDir(ctx).deleteRecursively()
        CaptureLifecycle.setFloatingIconSuppressed(ctx, false)
        prefs.captureDisplayIds = setOf(Display.DEFAULT_DISPLAY)
        prefs.hotkeyTapToLookupTap = lookupKey.toString()
        prefs.hotkeyCaptureTap = captureKey.toString()
        prefs.hotkeyChangeGameLanguageTap = languageKey.toString()
        // English before the capture service starts, as in
        // ChangeGameLanguageHotkeyTest: set later, it is a change of the game
        // language, whose engine warm-up finds a faked pack corrupt and
        // deletes it.
        prefs.sourceLang = "en"
        MainActivity.isInForeground = false
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
        service?.get()?.stopLive()
        service?.destroy()
        service = null
        a11y?.get()?.overlayUiController?.hideAll()
        PlayTranslateAccessibilityService.instance = null
        a11y = null
        CaptureLifecycle.setFloatingIconSuppressed(ctx, true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        LanguagePackStore.rootDir(ctx).deleteRecursively()
        sp().edit().clear().commit()
    }

    /** [ids]' packs on disk, as the installed check reads a finished
     *  download (ChangeGameLanguageHotkeyTest's fake). */
    private fun downloaded(vararg ids: SourceLangId) {
        for (id in ids) {
            val dict = LanguagePackStore.dictDbFor(ctx, id)
            dict.parentFile!!.mkdirs()
            dict.writeText("")
            LanguagePackStore.manifestFileFor(ctx, id).writeText("{}")
        }
    }

    /** Press and release [key] on a controller, as the system delivers it
     *  (onKeyEvent is the framework's protected callback). */
    private fun press(key: Int) {
        val onKeyEvent = PlayTranslateAccessibilityService::class.java
            .getDeclaredMethod("onKeyEvent", KeyEvent::class.java)
            .apply { isAccessible = true }
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            onKeyEvent.invoke(
                a11y!!.get(),
                KeyEvent(0L, 0L, action, key, 0, 0, 0, 0, 0, InputDevice.SOURCE_GAMEPAD),
            )
        }
        settle()
    }

    @Test fun `a press captures the game display in Definitions without remembering the mode, and the next closes it`() {
        press(lookupKey)

        val opened = sheet()
        assertNotNull("the capture's sheet is up", opened)
        assertEquals(OverlayMode.DEFINITIONS, opened!!.overlayMode)
        assertFalse("no mode remembered", sp().contains(Prefs.KEY_CAPTURE_OVERLAY_MODE))
        assertEquals(OverlayMode.TRANSLATION, prefs.overlayModeFor(OverlaySurface.CAPTURE))

        press(lookupKey)
        settleExit()

        assertNull(sheet())
    }

    @Test fun `a press over a capture in another mode switches it to Definitions in place`() {
        press(captureKey)
        val capture = sheet()
        assertNotNull(capture)
        assertEquals(OverlayMode.TRANSLATION, capture!!.overlayMode)

        press(lookupKey)

        assertSame("the same sheet", capture, sheet())
        assertEquals(OverlayMode.DEFINITIONS, capture.overlayMode)
        assertFalse("no mode remembered", sp().contains(Prefs.KEY_CAPTURE_OVERLAY_MODE))
    }

    @Test fun `a plain capture after tap-to-lookup opens in the remembered mode`() {
        press(lookupKey)
        press(lookupKey)
        settleExit()
        assertNull(sheet())

        press(captureKey)

        assertEquals(OverlayMode.TRANSLATION, sheet()!!.overlayMode)
    }

    @Test fun `the capture hotkey closes a Definitions capture as it closes any capture`() {
        press(lookupKey)
        assertNotNull(sheet())

        press(captureKey)
        settleExit()

        assertNull(sheet())
    }

    @Test fun `the game-language hotkey's switch closes the Definitions capture`() {
        // Its words were read in English; a card would look them up in
        // Spanish. Nothing was running, so nothing comes back.
        downloaded(SourceLangId.EN, SourceLangId.ES)
        press(lookupKey)
        assertEquals(OverlayMode.DEFINITIONS, sheet()!!.overlayMode)

        press(languageKey)
        settle()

        assertEquals(SourceLangId.ES, prefs.sourceLangId)
        assertNull(sheet())
    }
}
