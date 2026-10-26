package com.playtranslate.translation

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.cloud.PtCloudService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * The process's error tracker, as [TranslationErrors.install] builds it,
 * counts PlayTranslate Cloud's reports while EITHER of its switches is on:
 * the frame route (the OCR switch) and the translate route (the translation
 * switch) report under the one id, and each switch alone puts one route in
 * play, so a tracker reading one switch would drop the other route's
 * failures. With both off nothing of the cloud's is counted.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationErrorsCloudActiveTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private val shown = mutableListOf<String>()

    private val presenter = object : TranslationErrorPresenter {
        override fun show(error: TranslationError): Boolean { shown += error.key.owner; return true }
        override fun update(error: TranslationError) = Unit
        override fun hide(keys: Collection<TranslationErrorKey>) = Unit
    }

    @Before fun setUp() {
        clearPrefs()
        // Reports stamped before the session started are dropped.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
    }

    @After fun tearDown() {
        TranslationErrors.resetForTest()
        clearPrefs()
    }

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** Installs the real tracker and feeds it one failed cloud frame. */
    private fun reportCloudFailure() {
        TranslationErrors.install(ctx, presenter)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        TranslationBackendRegistry.onlineAttemptListener!!.invoke(
            listOf(
                OnlineAttempt(
                    PtCloudService.ID, PtCloudService.DISPLAY_NAME, BackendFailure(BackendFailureKind.NOT_RUNNING),
                    SystemClock.elapsedRealtime(), reachedServer = true,
                ),
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `with the OCR switch alone the cloud's failures count`() {
        prefs.ptCloudOcrEnabled = true
        reportCloudFailure()
        assertEquals(listOf(PtCloudService.ID), shown)
    }

    @Test fun `with the translation switch alone the cloud's failures count`() {
        prefs.ptCloudTranslateEnabled = true
        reportCloudFailure()
        assertEquals(listOf(PtCloudService.ID), shown)
    }

    @Test fun `with both switches off the cloud's failures are dropped`() {
        reportCloudFailure()
        assertEquals(emptyList<String>(), shown)
    }
}
