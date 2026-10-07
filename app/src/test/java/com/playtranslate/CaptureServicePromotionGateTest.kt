package com.playtranslate

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/**
 * The uncredited promotion path ([CaptureService.updateForegroundState]):
 * the call is made and the platform's refusal is a deferral, never a
 * crash, with the service alive to try again; and a plain restore start
 * carries no unconditional promotion. The shadow's start-foreground
 * exception stands in for the platform's
 * ForegroundServiceStartNotAllowedException.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureServicePromotionGateTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var service: ServiceController<CaptureService>? = null

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Before fun setUp() {
        clearPrefs()
        MainActivity.isInForeground = false
        service = Robolectric.buildService(CaptureService::class.java).create()
    }

    @After fun tearDown() {
        MainActivity.isInForeground = false
        service?.destroy()
        service = null
        clearPrefs()
    }

    @Test fun `a refusal from the platform is caught and deferred, and retried`() {
        val svc = service!!.get()
        svc.mediaProjectionActivated = true
        shadowOf(svc).setThrowInStartForeground(
            ForegroundServiceStartNotAllowedException("background start refused")
        )

        svc.updateForegroundState()

        assertEquals("not promoted", 0, shadowOf(svc).lastForegroundNotificationId)
        // The service is alive, and the next call tries again.
        shadowOf(svc).setThrowInStartForeground(null)
        svc.updateForegroundState()
        assertNotEquals("promoted once allowed", 0, shadowOf(svc).lastForegroundNotificationId)
    }

    @Test fun `the pre-capture type promotion is also a deferral, not a crash`() {
        val svc = service!!.get()
        shadowOf(svc).setThrowInStartForeground(
            ForegroundServiceStartNotAllowedException("background start refused")
        )

        svc.ensureMediaProjectionForegroundType()

        assertEquals("not promoted", 0, shadowOf(svc).lastForegroundNotificationId)
    }

    @Test fun `a plain restore start does not promote unconditionally`() {
        val svc = service!!.get()
        shadowOf(svc).setThrowInStartForeground(
            ForegroundServiceStartNotAllowedException("background start refused")
        )

        svc.onStartCommand(
            Intent(ctx, CaptureService::class.java).setAction(CaptureService.ACTION_PLAIN_START), 0, 1,
        )

        assertEquals("startForeground never ran", 0, shadowOf(svc).lastForegroundNotificationId)
    }

    @Test fun `a credited start still promotes before it settles`() {
        val svc = service!!.get()

        svc.onStartCommand(Intent(ctx, CaptureService::class.java), 0, 1)

        assertNotEquals("startForeground ran", 0, shadowOf(svc).lastForegroundNotificationId)
    }
}
