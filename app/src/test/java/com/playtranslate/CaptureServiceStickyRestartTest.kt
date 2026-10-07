package com.playtranslate

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.capture.SessionMarker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder
import org.robolectric.shadows.ShadowSettings

/**
 * A START_STICKY restart must not call startForeground (field crashes
 * 2026-09-27 and 09-30). The platform restarts the service with a null
 * intent after the process died, with the app in the background, and API
 * 31+ refuses that call with ForegroundServiceStartNotAllowedException
 * unless an exemption applies; at targetSdk 35+ on Android 15+, holding
 * SYSTEM_ALERT_WINDOW no longer exempts an app with no visible overlay.
 * The shadow's start-foreground exception stands in for that refusal.
 *
 * The second cell pins what the restart guard must leave alone: a
 * startForegroundService delivery (every starter's, so a non-null intent)
 * still promotes before onStartCommand returns, as the 5-second rule
 * requires, and is then demoted when nothing is up to hold the foreground.
 *
 * The last three cover the one restore a restart carries: a MediaProjection
 * session cut short by a kill comes back on the restart itself when the
 * user has exempted the app from battery optimisation (the allowlist that
 * permits the background promotion and spares the app-idle stop); the
 * restart promotes first and restores only once promoted, so a refusal
 * despite the exemption, like no exemption at all, leaves the record
 * intact for the next app open.
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
        Settings.Global.putInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, 57)
        SessionMarker.evaluate(ctx)
        service = Robolectric.buildService(CaptureService::class.java).create()
    }

    @After fun tearDown() {
        service?.destroy()
        service = null
        setBatteryExempt(false)
        clearPrefs()
        // No record: evaluate leaves nothing cut short for the next test.
        SessionMarker.evaluate(ctx)
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

    // ── the MediaProjection restore on the restart itself ─────────────────

    private fun setBatteryExempt(exempt: Boolean) {
        shadowOf(ctx.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(ctx.packageName, exempt)
    }

    /** The previous process (pid 4660) died with the icon up, on the
     *  MediaProjection backend, and the system's record of the death is a
     *  low-memory kill, so the evaluation at process start allows a
     *  restore (the same seeding as SessionMarkerTest). */
    private fun sessionCutShortOnMediaProjection() {
        Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        ShadowSettings.setCanDrawOverlays(true)
        CaptureBackendResolver.reresolve(ctx)
        assertFalse(CaptureBackendResolver.active().requiresAccessibilityService)
        Prefs(ctx).apply {
            sessionOnBoot = 57
            sessionOnProcessStart = Process.getStartElapsedRealtime() + 1
            sessionOnPid = 4660
        }
        shadowOf(ctx.getSystemService(ActivityManager::class.java)).addApplicationExitInfo(
            ApplicationExitInfoBuilder.newBuilder()
                .setProcessName(ctx.packageName).setPid(4660)
                .setReason(ApplicationExitInfo.REASON_LOW_MEMORY)
                .setTimestamp(System.currentTimeMillis())
                .build()
        )
        SessionMarker.evaluate(ctx)
        assertTrue("seeded as cut short", SessionMarker.cutShort)
    }

    @Test fun `an exempted app's sticky restart brings the session back and promotes`() {
        val svc = service!!.get()
        sessionCutShortOnMediaProjection()
        setBatteryExempt(true)

        val result = svc.onStartCommand(null, 0, 1)

        assertEquals("stays sticky", Service.START_STICKY, result)
        assertTrue("activated again, consent to be asked at the next capture", svc.mediaProjectionActivated)
        assertFalse("the record is consumed by this restore", SessionMarker.cutShort)
        assertNotEquals("promoted on the restart", 0, shadowOf(svc).lastForegroundNotificationId)
        assertFalse("and held", shadowOf(svc).isForegroundStopped)
    }

    @Test fun `a refusal despite the exemption leaves the record for the next app open`() {
        val svc = service!!.get()
        sessionCutShortOnMediaProjection()
        setBatteryExempt(true)
        shadowOf(svc).setThrowInStartForeground(
            ForegroundServiceStartNotAllowedException("a ROM that ignores the allowlist")
        )

        val result = svc.onStartCommand(null, 0, 1)

        assertEquals("stays sticky", Service.START_STICKY, result)
        assertFalse("the promotion was asked first and refused, so nothing restored", svc.mediaProjectionActivated)
        assertTrue("the record waits for MainActivity's start", SessionMarker.cutShort)
        assertEquals("record untouched", 4660, Prefs(ctx).sessionOnPid)
        assertEquals("startForeground never succeeded", 0, shadowOf(svc).lastForegroundNotificationId)
    }

    @Test fun `without the exemption the restart leaves the session for the next app open`() {
        val svc = service!!.get()
        sessionCutShortOnMediaProjection()
        setBatteryExempt(false)
        shadowOf(svc).setThrowInStartForeground(
            ForegroundServiceStartNotAllowedException("background start refused")
        )

        svc.onStartCommand(null, 0, 1)

        assertFalse("not activated by the restart", svc.mediaProjectionActivated)
        assertTrue("the record waits for MainActivity's start", SessionMarker.cutShort)
        assertEquals("startForeground never ran", 0, shadowOf(svc).lastForegroundNotificationId)
    }
}
