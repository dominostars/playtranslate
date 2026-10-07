package com.playtranslate

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CaptureLifecycle
import com.playtranslate.capture.MediaProjectionConsentActivity
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
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
 * [CaptureService] applies every change of the game language, whoever
 * makes it (Gilad, 2026-09-28): the floating icon's "Change game language",
 * a picker over the game or in the app. A Furigana overlay mode drops to
 * Translation on a language without readings, and a running auto-translate
 * restarts in the new language. The change here is the pref write each of
 * them makes.
 *
 * Sessions are real starts, on [LiveStartCancelsCaptureTest]'s footing: the
 * accessibility service is the active backend's host, created and never
 * connected, so a Furigana start is up when it returns and captures
 * nothing, and a Translation start there, without screen-record consent,
 * waits on the consent dialog, as every such start does.
 */
@RunWith(RobolectricTestRunner::class)
class SourceLanguageChangeTest {

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
        prefs.sourceLang = "ja"
        prefs.overlayMode = OverlayMode.FURIGANA
        val built = Robolectric.buildService(PlayTranslateAccessibilityService::class.java).create()
        a11y = built
        PlayTranslateAccessibilityService.instance = built.get()
        built.get().overlayUiController.reconcileFloatingIcons(freshAppearance = false)
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

    private fun startService(): CaptureService {
        val built = Robolectric.buildService(CaptureService::class.java).create()
        service = built
        built.get().gameDisplayIds = setOf(Display.DEFAULT_DISPLAY)
        settle()
        return built.get()
    }

    /** The session's live mode on the default display. */
    private fun CaptureService.mode(): LiveMode? {
        val modes = CaptureService::class.java.getDeclaredField("liveModes")
            .apply { isAccessible = true }.get(this) as Map<*, *>
        return modes[Display.DEFAULT_DISPLAY] as LiveMode?
    }

    private fun startedActivities(): List<Intent> {
        val app = shadowOf(ctx as Application)
        return generateSequence { app.nextStartedActivity }.toList()
    }

    @Test fun `a running session restarts in the new language`() {
        val svc = startService()
        svc.startLive()
        val first = svc.mode()
        assertNotNull("the session is up", first)

        prefs.sourceLang = "zh"
        settle()

        assertTrue(svc.isLive)
        val second = svc.mode()
        assertNotSame("a fresh session, not the first one refreshed", first, second)
        assertEquals("pinyin is Chinese's reading hint", OverlayMode.FURIGANA, prefs.overlayMode)

        // Simplified to Traditional is a change of the game language too.
        prefs.sourceLang = "zh-Hant"
        settle()
        assertTrue(svc.isLive)
        assertNotSame(second, svc.mode())
    }

    // Gilad, 2026-09-28: the restart is a normal start, consent ask
    // included, rather than one that skips it.
    @Test fun `on a language without readings Furigana drops to Translation, and the restart asks for consent as every Translation start here does`() {
        val svc = startService()
        svc.startLive()
        assertTrue(svc.isLive)

        prefs.sourceLang = "en"
        settle()

        assertEquals(OverlayMode.TRANSLATION, prefs.overlayMode)
        assertFalse(svc.isLive)
        assertTrue("waiting on the consent dialog", svc.isLiveStartPending)
        assertEquals(
            MediaProjectionConsentActivity::class.java.name,
            startedActivities().single().component?.className,
        )
    }

    @Test fun `with auto-translate off only the overlay mode follows the language`() {
        val svc = startService()

        prefs.sourceLang = "zh"
        settle()
        assertEquals(OverlayMode.FURIGANA, prefs.overlayMode)

        prefs.sourceLang = "en"
        settle()
        assertEquals(OverlayMode.TRANSLATION, prefs.overlayMode)

        // The drop is saved, as the app's Settings always saved it.
        prefs.sourceLang = "ja"
        settle()
        assertEquals(OverlayMode.TRANSLATION, prefs.overlayMode)

        assertFalse(svc.isLive)
        assertFalse(svc.isLiveStartPending)
        assertEquals(emptyList<Intent>(), startedActivities())
    }

    @Test fun `a start still pending is left to go ahead`() {
        val svc = startService()
        val pending = Job()   // a start, suspended in its consent await
        CaptureService::class.java.getDeclaredField("pendingLiveStart")
            .apply { isAccessible = true }.set(svc, pending)

        prefs.sourceLang = "zh"
        settle()

        assertFalse(pending.isCancelled)
        assertTrue(svc.isLiveStartPending)
        assertFalse(svc.isLive)
    }

    // Codex adversarial 2026-09-28: the start warmed the language it began
    // with; its session now reads the language live, so its first-cycle
    // gate warms the new one (LiveSessionFeedbackWarmUpTest).
    @Test fun `a start waiting on its consent dialog goes ahead, and its session reads the new language`() {
        prefs.overlayMode = OverlayMode.TRANSLATION
        val svc = startService()
        svc.startLive()
        assertTrue("waiting on the consent dialog", svc.isLiveStartPending)
        val pending = CaptureService::class.java.getDeclaredField("pendingLiveStart")
            .apply { isAccessible = true }.get(svc) as Job

        prefs.sourceLang = "zh"
        settle()

        assertFalse("not restarted", pending.isCancelled)
        assertTrue(svc.isLiveStartPending)
        val feedback = CaptureService::class.java.getDeclaredField("liveFeedback")
            .apply { isAccessible = true }.get(svc)!!
        @Suppress("UNCHECKED_CAST")
        val sourceLang = LiveSessionFeedback::class.java.getDeclaredField("sourceLang")
            .apply { isAccessible = true }.get(feedback) as () -> com.playtranslate.language.SourceLangId
        assertEquals(com.playtranslate.language.SourceLangId.ZH, sourceLang())
    }

    // A change made while no service ran was never applied.
    @Test fun `a service starting on a language without readings drops a saved Furigana mode`() {
        prefs.sourceLang = "en"

        startService()

        assertEquals(OverlayMode.TRANSLATION, prefs.overlayMode)
    }

    @Test fun `a service starting on a language with readings keeps it`() {
        startService()

        assertEquals(OverlayMode.FURIGANA, prefs.overlayMode)
    }

    @Test fun `a destroyed service no longer follows the language`() {
        startService()
        service!!.destroy()
        service = null

        prefs.sourceLang = "en"
        settle()

        assertEquals(OverlayMode.FURIGANA, prefs.overlayMode)
    }
}
