package com.playtranslate

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
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
import com.playtranslate.language.TokenSpan
import com.playtranslate.ui.TapLookupSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * through the accessibility service's hotkey dispatch: a press opens the
 * boxes on the primary game display, the next closes them, another hotkey
 * closes them before it acts, and the game-language hotkey's switch leaves
 * them to the language change, which closes them. The session itself is
 * [TapLookupSessionTest]'s; here its scene comes from a stand-in reader.
 *
 * On [ChangeGameLanguageHotkeyTest]'s footing: the accessibility service is
 * created and never connected, and its floating icon is up, which is what
 * lets a hotkey fire; the capture service runs, since the dispatch is wired
 * to it.
 */
@RunWith(RobolectricTestRunner::class)
class TapToLookupHotkeyTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private var service: ServiceController<CaptureService>? = null
    private var a11y: ServiceController<PlayTranslateAccessibilityService>? = null
    private val lookupKey = KeyEvent.KEYCODE_BUTTON_L1
    private val autoKey = KeyEvent.KEYCODE_BUTTON_R1
    private val languageKey = KeyEvent.KEYCODE_BUTTON_Y

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
        prefs.hotkeyTranslationTap = autoKey.toString()
        prefs.hotkeyChangeGameLanguageTap = languageKey.toString()
        // English before the capture service starts, as in
        // ChangeGameLanguageHotkeyTest: set later, it is a change of the game
        // language, whose engine warm-up finds a faked pack corrupt and
        // deletes it.
        prefs.sourceLang = "en"
        val built = Robolectric.buildService(PlayTranslateAccessibilityService::class.java).create()
        a11y = built
        PlayTranslateAccessibilityService.instance = built.get()
        built.get().overlayUiController.tapLookupReader = OneWordReader()
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
        clearPrefs()
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

    @Test fun `a press opens the boxes on the primary game display, and the next closes them`() {
        press(lookupKey)
        val opened = session()
        assertNotNull(opened)
        assertNotNull(opened!!.javaClass.getDeclaredField("overlay").apply { isAccessible = true }.get(opened))

        press(lookupKey)

        assertNull(session())
    }

    @Test fun `another hotkey closes the boxes before it acts`() {
        press(lookupKey)
        assertNotNull(session())

        press(autoKey)

        assertNull(session())
    }

    @Test fun `the game-language hotkey's switch leaves the boxes to the language change, which closes them`() {
        downloaded(SourceLangId.EN, SourceLangId.ES)
        press(lookupKey)
        assertNotNull(session())

        press(languageKey)

        assertEquals(SourceLangId.ES, prefs.sourceLangId)
        assertNull("the language change closed them", session())
    }
}
