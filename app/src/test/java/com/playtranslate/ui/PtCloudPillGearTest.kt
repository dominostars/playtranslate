package com.playtranslate.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.OverlayUiController
import com.playtranslate.PlayTranslateApplication
import com.playtranslate.Prefs
import com.playtranslate.capture.CaptureLifecycle
import com.playtranslate.cloud.PtCloudService
import com.playtranslate.net.NetworkConnectivity.InternetState
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.OnlineAttempt
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.translation.TranslationError
import com.playtranslate.translation.TranslationErrorKey
import com.playtranslate.translation.TranslationErrorPresenter
import com.playtranslate.translation.TranslationErrorTracker
import com.playtranslate.translation.TranslationErrors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * The gear on PlayTranslate Cloud's translation-error pill opens the cloud's
 * own page, not Translation services, where the cloud has no row. Every
 * other owner's gear, and everything the gear closes and stops on the way,
 * is ErrorPillSettingsGearTest's, on the same real controller, tracker and
 * pills.
 */
@RunWith(RobolectricTestRunner::class)
class PtCloudPillGearTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val controller =
        OverlayUiController(ctx, OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY))
    private val errorPills: TranslationErrorPills =
        OverlayUiController::class.java.getDeclaredField("errorPills")
            .apply { isAccessible = true }.get(controller) as TranslationErrorPills

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Before fun setUp() {
        // The gear launches on the foreground activity's display when one of
        // ours is resumed. That registry is process-static and cleared only
        // when its activity pauses, so a page another test class left resumed
        // outlives it, and a Robolectric activity has no display (reading it
        // throws). No activity of ours is in front here.
        PlayTranslateApplication::class.java.getDeclaredField("resumedActivity")
            .apply { isAccessible = true }.set(null, null)
        clearPrefs()
        CaptureLifecycle.setFloatingIconSuppressed(ctx, false)
        Prefs(ctx).captureDisplayIds = setOf(Display.DEFAULT_DISPLAY)
        val tracker = TranslationErrorTracker(
            now = SystemClock::elapsedRealtime,
            internet = { InternetState.VALIDATED },
        )
        tracker.presenter = object : TranslationErrorPresenter {
            override fun show(error: TranslationError): Boolean = controller.showTranslationErrorPill(error)
            override fun update(error: TranslationError) = controller.updateTranslationErrorPill(error)
            override fun hide(keys: Collection<TranslationErrorKey>) = controller.hideTranslationErrorPills(keys)
        }
        TranslationErrors.install(tracker)
    }

    @After fun tearDown() {
        controller.hideAll()
        TranslationErrors.resetForTest()
        CaptureLifecycle.setFloatingIconSuppressed(ctx, true)
        // Let the churn gate finish every removal inside this test.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        clearPrefs()
    }

    @Test fun `the gear on PlayTranslate Cloud's pill opens the cloud page`() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        TranslationBackendRegistry.onlineAttemptListener!!.invoke(
            listOf(
                OnlineAttempt(
                    PtCloudService.ID, PtCloudService.DISPLAY_NAME, BackendFailure(BackendFailureKind.NOT_RUNNING),
                    SystemClock.elapsedRealtime(), reachedServer = true,
                ),
            ),
        )
        settle()
        assertNotNull("the pill is up", errorPills.stackRoot)

        (errorPills.stackRoot!!.getChildAt(0) as ViewGroup).getChildAt(2).performClick()
        settle()

        assertNull("the pill is down", errorPills.stackRoot)
        val app = shadowOf(ctx as Application)
        val launches = generateSequence { app.nextStartedActivityForResult }.toList()
        assertEquals(listOf(PtCloudSettingsActivity::class.java.name), launches.map { it.intent.component?.className })
        val flags = launches.single().intent.flags
        assertTrue(flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue("never a second copy on top of itself", flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }
}
