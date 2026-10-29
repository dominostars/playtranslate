package com.playtranslate.translation

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.cloud.PtCloudConfig
import com.playtranslate.cloud.PtCloudService
import com.playtranslate.cloud.PtCloudTestSupport
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
 * counts PlayTranslate Cloud's reports while a usable key is stored, whatever
 * its switches say (2026-10-29): the frame route and the translate route
 * report under the one id, so either switch alone puts the cloud in play,
 * and when an ended entitlement turns BOTH off the attempt that found the
 * ending still reports after it, and is the one pill the user gets. With no
 * key nothing of the cloud's is counted. (A user's own switch-off drops the
 * reports of requests sent before it through the gesture's forget, which is
 * the tracker's rule and not read here.)
 *
 * The key is stored through the reversible test codec, on settings installed
 * as the service's ([PtCloudService.installForTest]), which the tracker reads:
 * production [Prefs] stores no key without the AndroidKeyStore.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationErrorsCloudActiveTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: Prefs
    private val shown = mutableListOf<String>()

    private val presenter = object : TranslationErrorPresenter {
        override fun show(error: TranslationError): Boolean { shown += error.key.owner; return true }
        override fun update(error: TranslationError) = Unit
        override fun hide(keys: Collection<TranslationErrorKey>) = Unit
    }

    @Before fun setUp() {
        clearPrefs()
        prefs = PtCloudTestSupport.prefs(ctx)
        PtCloudService.installForTest(CooldownState(null, PtCloudService.ID), PtCloudConfig(prefs))
        // Reports stamped before the session started are dropped.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
    }

    @After fun tearDown() {
        TranslationErrors.resetForTest()
        PtCloudService.installForTest(null)
        clearPrefs()
    }

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun withKey() { prefs.ptCloudClientKey = PtCloudTestSupport.KEY }

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
        withKey()
        prefs.ptCloudOcrEnabled = true
        reportCloudFailure()
        assertEquals(listOf(PtCloudService.ID), shown)
    }

    @Test fun `with the translation switch alone the cloud's failures count`() {
        withKey()
        prefs.ptCloudTranslateEnabled = true
        reportCloudFailure()
        assertEquals(listOf(PtCloudService.ID), shown)
    }

    /** The ending's case: the service turned both switches off itself, and
     *  the attempt that found the ending reports after that. */
    @Test fun `with a key and both switches off the cloud's failures count`() {
        withKey()
        reportCloudFailure()
        assertEquals(listOf(PtCloudService.ID), shown)
    }

    @Test fun `with no key the cloud's failures are dropped, whatever the switches hold`() {
        prefs.ptCloudOcrEnabled = true
        prefs.ptCloudTranslateEnabled = true
        reportCloudFailure()
        assertEquals(emptyList<String>(), shown)
    }
}
