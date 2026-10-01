package com.playtranslate

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/**
 * A START_STICKY restart must not call startForeground (field crashes
 * 2026-09-27 and 09-30). The platform restarts the service with a null
 * intent after the process died, with the app in the background, and API
 * 31+ refuses that call with ForegroundServiceStartNotAllowedException
 * unless an exemption applies; at targetSdk 35+ on Android 15+, holding
 * SYSTEM_ALERT_WINDOW no longer exempts an app with no visible overlay.
 * The shadow's start-foreground exception stands in for that refusal.
 *
 * The other cell pins what the restart guard must leave alone: a
 * startForegroundService delivery (every starter's, so a non-null intent)
 * still promotes before onStartCommand returns, as the 5-second rule
 * requires, and is then demoted when nothing is up to hold the foreground.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureServiceStickyRestartTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var service: ServiceController<CaptureService>? = null

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Before fun setUp() {
        clearPrefs()
        service = Robolectric.buildService(CaptureService::class.java).create()
    }

    @After fun tearDown() {
        service?.destroy()
        service = null
        clearPrefs()
    }

    @Test fun `a sticky restart stays out of the foreground when the platform would refuse it`() {
        val svc = service!!.get()
        shadowOf(svc).setThrowInStartForeground(
            ForegroundServiceStartNotAllowedException("background start refused")
        )

        val result = svc.onStartCommand(null, 0, 1)

        assertEquals("stays sticky", Service.START_STICKY, result)
        assertEquals("startForeground never ran", 0, shadowOf(svc).lastForegroundNotificationId)
    }

    @Test fun `a startForegroundService delivery still promotes before it settles`() {
        val svc = service!!.get()

        svc.onStartCommand(Intent(ctx, CaptureService::class.java), 0, 1)

        assertNotEquals("startForeground ran", 0, shadowOf(svc).lastForegroundNotificationId)
        assertTrue("then demoted, with nothing up to hold it", shadowOf(svc).isForegroundStopped)
    }
}
