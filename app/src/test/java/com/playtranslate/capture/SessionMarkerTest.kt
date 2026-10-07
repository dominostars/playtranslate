package com.playtranslate.capture

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Process
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.diagnostics.ProcessExitDiag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder

/**
 * The persisted session record: the kill-versus-reboot verdict over the
 * stored identities, the exit reasons after which the controls come back,
 * and the end-to-end evaluation against the platform's exit records.
 */
@RunWith(RobolectricTestRunner::class)
class SessionMarkerTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val activityManager = shadowOf(ctx.getSystemService(ActivityManager::class.java))

    @Before fun setUp() {
        ProcessExitDiag.init(ctx)
        Settings.Global.putInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, 57)
        SessionMarker.markOff(ctx)
    }

    @After fun tearDown() {
        SessionMarker.consumeCutShort()
        SessionMarker.markOff(ctx)
        CaptureLifecycle.setFloatingIconSuppressed(ctx, true)
    }

    // ── decide ────────────────────────────────────────────────────────────

    @Test fun `no record means off`() {
        assertEquals(SessionMarker.Verdict.OFF, SessionMarker.decide(-1, 57, 0L, 10L))
    }

    @Test fun `a different boot count is a reboot, whatever the process start`() {
        assertEquals(SessionMarker.Verdict.REBOOTED, SessionMarker.decide(57, 58, 10L, 10L))
    }

    @Test fun `an unreadable boot count never matches`() {
        assertEquals(SessionMarker.Verdict.REBOOTED, SessionMarker.decide(57, -1, 10L, 99L))
    }

    @Test fun `the same boot and the same process is this process`() {
        assertEquals(SessionMarker.Verdict.SAME_PROCESS, SessionMarker.decide(57, 57, 10L, 10L))
    }

    @Test fun `the same boot and another process is a session cut short`() {
        assertEquals(SessionMarker.Verdict.CUT_SHORT, SessionMarker.decide(57, 57, 10L, 99L))
    }

    // ── restorable ────────────────────────────────────────────────────────

    @Test fun `only the system's own kills bring the controls back`() {
        for (reason in listOf(
            ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_FREEZER, ApplicationExitInfo.REASON_DEPENDENCY_DIED,
            ApplicationExitInfo.REASON_OTHER,
        )) assertTrue("reason $reason", SessionMarker.restorable(reason))
        for (reason in listOf(
            ApplicationExitInfo.REASON_UNKNOWN, ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE, ApplicationExitInfo.REASON_PACKAGE_UPDATED,
            99,
        )) assertFalse("reason $reason", SessionMarker.restorable(reason))
    }

    // ── evaluate, against the platform's records ──────────────────────────

    /** Stamps the record as a process that is gone (another start time,
     *  pid 4660) and files its death with [reason]. */
    private fun previousProcessDied(reason: Int, description: String? = null, filed: Boolean = true) {
        val prefs = Prefs(ctx)
        prefs.sessionOnBoot = 57
        prefs.sessionOnProcessStart = Process.getStartElapsedRealtime() + 1
        prefs.sessionOnPid = 4660
        if (filed) {
            activityManager.addApplicationExitInfo(
                ApplicationExitInfoBuilder.newBuilder()
                    .setProcessName(ctx.packageName).setPid(4660).setReason(reason)
                    .setDescription(description).setTimestamp(System.currentTimeMillis())
                    .build()
            )
        }
    }

    @Test fun `a low-memory kill of the previous process is restored and noticed`() {
        previousProcessDied(ApplicationExitInfo.REASON_LOW_MEMORY)
        SessionMarker.evaluate(ctx)
        assertTrue(SessionMarker.cutShort)
        assertNotNull(SessionMarker.killExit)
    }

    @Test fun `a crash of the previous process is noticed by the record but never restored`() {
        previousProcessDied(ApplicationExitInfo.REASON_CRASH, "crash")
        SessionMarker.evaluate(ctx)
        assertFalse(SessionMarker.cutShort)
        assertEquals(ApplicationExitInfo.REASON_CRASH, SessionMarker.killExit?.reason)
    }

    @Test fun `no record of the death means no restore and no notice`() {
        previousProcessDied(ApplicationExitInfo.REASON_LOW_MEMORY, filed = false)
        SessionMarker.evaluate(ctx)
        assertFalse(SessionMarker.cutShort)
        assertNull(SessionMarker.killExit)
    }

    @Test fun `a record for another pid is not this process's death`() {
        previousProcessDied(ApplicationExitInfo.REASON_LOW_MEMORY, filed = false)
        activityManager.addApplicationExitInfo(
            ApplicationExitInfoBuilder.newBuilder()
                .setProcessName(ctx.packageName).setPid(1).setReason(ApplicationExitInfo.REASON_LOW_MEMORY)
                .setTimestamp(System.currentTimeMillis()).build()
        )
        SessionMarker.evaluate(ctx)
        assertFalse(SessionMarker.cutShort)
        assertNull(SessionMarker.killExit)
    }

    @Test fun `after a reboot nothing is restored even with a kill on record`() {
        previousProcessDied(ApplicationExitInfo.REASON_LOW_MEMORY)
        Settings.Global.putInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, 58)
        SessionMarker.evaluate(ctx)
        assertFalse(SessionMarker.cutShort)
        assertNull(SessionMarker.killExit)
    }

    // ── a dead process's record survives until a restore or a Turn Off ───

    private fun recordIsStillThePreviousProcess() {
        val prefs = Prefs(ctx)
        assertEquals(57, prefs.sessionOnBoot)
        assertEquals(4660, prefs.sessionOnPid)
    }

    @Test fun `nothing in a fresh process rewrites the record before a restore runs`() {
        previousProcessDied(ApplicationExitInfo.REASON_LOW_MEMORY)
        SessionMarker.evaluate(ctx)
        assertTrue(SessionMarker.cutShort)

        // The lifts and pushes a new process performs with no icon up: the
        // app-open summon, the tile refresh. None of them touch the record;
        // only an icon install or removal does.
        CaptureLifecycle.setFloatingIconSuppressed(ctx, false)
        CaptureLifecycle.setFloatingIconSuppressed(ctx, true)

        recordIsStillThePreviousProcess()
        // A second death before any restore: the next process still finds
        // the kill.
        SessionMarker.evaluate(ctx)
        assertTrue(SessionMarker.cutShort)
    }

    @Test fun `a Turn Off before any icon is up clears the dead process's record`() {
        previousProcessDied(ApplicationExitInfo.REASON_LOW_MEMORY)
        SessionMarker.evaluate(ctx)

        CaptureLifecycle.deactivate(ctx)

        assertFalse(SessionMarker.cutShort)
        assertEquals(-1, Prefs(ctx).sessionOnBoot)
    }

    @Test fun `markOn stamps this process and markOff clears every field`() {
        SessionMarker.markOn(ctx)
        val prefs = Prefs(ctx)
        assertEquals(57, prefs.sessionOnBoot)
        assertEquals(Process.getStartElapsedRealtime(), prefs.sessionOnProcessStart)
        assertEquals(Process.myPid(), prefs.sessionOnPid)
        SessionMarker.markOff(ctx)
        assertEquals(-1, prefs.sessionOnBoot)
        assertEquals(0L, prefs.sessionOnProcessStart)
        assertEquals(0, prefs.sessionOnPid)
    }
}
