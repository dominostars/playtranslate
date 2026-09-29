package com.playtranslate

import android.content.Context
import android.os.Looper
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CaptureLifecycle
import com.playtranslate.language.LanguagePackStore
import com.playtranslate.language.SourceLangId
import com.playtranslate.ui.FloatingOverlayIcon
import com.playtranslate.ui.OverlayWorkspace
import com.playtranslate.ui.SourceListPage
import com.playtranslate.ui.WorkspacePage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * The "Change game language" hotkey runs the floating icon's gesture of
 * that name, whose every case IconChangeGameLanguageGestureTest covers;
 * here it is driven from a controller key press through the accessibility
 * service's hotkey dispatch, on the primary game display.
 *
 * On [SourceLanguageChangeTest]'s footing: the accessibility service is
 * created and never connected, and its floating icon is up, which is what
 * lets a hotkey fire; the capture service runs, since the dispatch is wired
 * to it. Packs are faked on disk as in the gesture's test.
 */
@RunWith(RobolectricTestRunner::class)
class ChangeGameLanguageHotkeyTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private var service: ServiceController<CaptureService>? = null
    private var a11y: ServiceController<PlayTranslateAccessibilityService>? = null
    private val key = KeyEvent.KEYCODE_BUTTON_Y

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Before fun setUp() {
        clearPrefs()
        LanguagePackStore.rootDir(ctx).deleteRecursively()
        CaptureLifecycle.setFloatingIconSuppressed(ctx, false)
        prefs.captureDisplayIds = setOf(Display.DEFAULT_DISPLAY)
        prefs.hotkeyChangeGameLanguageTap = key.toString()
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
        // Let the churn gate finish every removal inside this test, the
        // pill's fade-out included.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        LanguagePackStore.rootDir(ctx).deleteRecursively()
        clearPrefs()
    }

    /** [ids]' packs on disk, as a finished download leaves them. */
    private fun downloaded(vararg ids: SourceLangId) {
        for (id in ids) {
            val dict = LanguagePackStore.dictDbFor(ctx, id)
            dict.parentFile!!.mkdirs()
            dict.writeText("")
            LanguagePackStore.manifestFileFor(ctx, id).writeText("{}")
        }
    }

    /** Press and release the bound key on a controller, as the system
     *  delivers it (onKeyEvent is the framework's protected callback). */
    private fun pressHotkey() {
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

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T =
        OverlayUiController::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(a11y!!.get().overlayUiController) as T

    /** What the transient pill says, or null when none is up. */
    private fun pill(): String? {
        val view = field<View?>("pillView") ?: return null
        return view.javaClass.getDeclaredField("\$message").apply { isAccessible = true }
            .get(view) as String
    }

    /** Open the quick menu as a tap on the icon does. */
    private fun openQuickMenu() {
        prefs.iconTapAction = TapAction.OPEN_QUICK_MENU
        val handle = field<Map<*, *>>("iconHandles")[Display.DEFAULT_DISPLAY]!!
        val icon = handle.javaClass.getDeclaredField("icon").apply { isAccessible = true }
            .get(handle) as FloatingOverlayIcon
        icon.onTap!!.invoke()
        settle()
        assertNotNull("the menu is open", field<Any?>("floatingMenu"))
        assertTrue("it pauses live capture", service!!.get().holdActive)
    }

    /** The workspace's root page while one is open over the game. */
    private fun workspacePage(): WorkspacePage? {
        val ws = field<OverlayWorkspace?>("workspace") ?: return null
        val stack = OverlayWorkspace::class.java.getDeclaredField("stack")
            .apply { isAccessible = true }.get(ws) as List<*>
        val entry = stack.first()!!
        return entry.javaClass.getDeclaredField("page").apply { isAccessible = true }
            .get(entry) as WorkspacePage
    }

    @Test fun `with two languages downloaded the hotkey switches to the other and says so`() {
        downloaded(SourceLangId.EN, SourceLangId.ES)
        prefs.sourceLang = "en"

        pressHotkey()

        assertEquals(SourceLangId.ES, prefs.sourceLangId)
        assertEquals("Translating from Spanish", pill())
        assertNull("no picker", workspacePage())
    }

    @Test fun `with one language downloaded the hotkey opens the picker`() {
        downloaded(SourceLangId.EN)
        prefs.sourceLang = "en"

        pressHotkey()

        assertTrue(workspacePage() is SourceListPage)
        assertEquals("unchanged until a pick", SourceLangId.EN, prefs.sourceLangId)
        assertNull("no pill", pill())
    }

    // The capture hotkey's target: the display the user last touched among
    // the game displays.
    @Test fun `the pill goes to the primary game display`() {
        val second = ShadowDisplayManager.addDisplay("w640dp-h480dp")
        service!!.get().lastInteractedDisplayId = second
        downloaded(SourceLangId.EN, SourceLangId.ES)
        prefs.sourceLang = "en"

        pressHotkey()

        assertEquals(SourceLangId.ES, prefs.sourceLangId)
        assertEquals(second, field<Int?>("pillDisplayId"))
    }

    // Codex review, 2026-09-29: with auto-translate off nothing repairs the
    // remembered display when it is removed, and the press did nothing.
    @Test fun `after the last-touched display is removed the hotkey acts on one that exists`() {
        val second = ShadowDisplayManager.addDisplay("w640dp-h480dp")
        service!!.get().lastInteractedDisplayId = second
        ShadowDisplayManager.removeDisplay(second)
        downloaded(SourceLangId.EN, SourceLangId.ES)
        prefs.sourceLang = "en"

        pressHotkey()

        assertEquals(SourceLangId.ES, prefs.sourceLangId)
        assertEquals("Translating from Spanish", pill())
        assertEquals(Display.DEFAULT_DISPLAY, field<Int?>("pillDisplayId"))
    }

    // Codex review, 2026-09-29: the gesture can't fire with the quick menu
    // open (the menu covers the icon); a hotkey can, and it left the menu up.
    @Test fun `with the quick menu open the hotkey closes it and switches`() {
        downloaded(SourceLangId.EN, SourceLangId.ES)
        prefs.sourceLang = "en"
        openQuickMenu()

        pressHotkey()

        assertNull("the menu closed", field<Any?>("floatingMenu"))
        assertFalse("live capture runs again", service!!.get().holdActive)
        assertEquals(SourceLangId.ES, prefs.sourceLangId)
        assertEquals("Translating from Spanish", pill())
    }

    @Test fun `with the quick menu open the hotkey closes it and opens the picker`() {
        downloaded(SourceLangId.EN)
        prefs.sourceLang = "en"
        openQuickMenu()

        pressHotkey()

        assertNull("the menu closed", field<Any?>("floatingMenu"))
        assertFalse("live capture runs again", service!!.get().holdActive)
        assertTrue(workspacePage() is SourceListPage)
    }
}
