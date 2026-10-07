package com.playtranslate.diagnostics

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder

/**
 * The process-exit block of the log-export header: which of the platform's
 * exit records are printed, in what order and shape, and that the block
 * reaches the exported file.
 */
@RunWith(RobolectricTestRunner::class)
class ProcessExitDiagTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val activityManager = shadowOf(ctx.getSystemService(ActivityManager::class.java))

    @Before fun setUp() {
        ProcessExitDiag.init(ctx)
    }

    /** Records one death; the shadow, like the platform, lists the latest
     *  addition first. */
    private fun recordExit(
        process: String = ctx.packageName,
        pid: Int = 100,
        reason: Int = ApplicationExitInfo.REASON_LOW_MEMORY,
        importance: Int = RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE,
        status: Int = 0,
        rssKb: Long = 0,
        description: String? = null,
    ) {
        activityManager.addApplicationExitInfo(
            ApplicationExitInfoBuilder.newBuilder()
                .setProcessName(process)
                .setPid(pid)
                .setReason(reason)
                .setImportance(importance)
                .setStatus(status)
                .setRss(rssKb)
                .setDescription(description)
                .setTimestamp(System.currentTimeMillis())
                .build()
        )
    }

    @Test fun `a death prints its reason, importance, pid and the fields it has`() {
        recordExit(pid = 11923, rssKb = 412L * 1024)
        recordExit(
            pid = 12001,
            reason = ApplicationExitInfo.REASON_USER_REQUESTED,
            importance = RunningAppProcessInfo.IMPORTANCE_CACHED,
            status = 9,
            description = "remove task",
        )

        val (killed, swiped) = ProcessExitDiag.recentExits()
        val timestamp = """\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}"""
        assertTrue(
            killed,
            Regex("^$timestamp  LOW_MEMORY  importance=FOREGROUND_SERVICE  pid=11923  rss=412MB$")
                .matches(killed),
        )
        assertTrue(
            swiped,
            Regex("^$timestamp  USER_REQUESTED  importance=CACHED  pid=12001  status=9  desc=remove task$")
                .matches(swiped),
        )
    }

    @Test fun `a reason code this build cannot name prints as its number`() {
        recordExit(reason = 99)
        assertTrue(ProcessExitDiag.recentExits().single().contains("  REASON_99  "))
    }

    @Test fun `main-process deaths are listed oldest first, without the renderers`() {
        recordExit(pid = 1)
        recordExit(process = "com.google.android.webview:sandboxed_process0", pid = 2)
        recordExit(pid = 3)

        val exits = ProcessExitDiag.recentExits()
        assertEquals(2, exits.size)
        assertTrue(exits[0], exits[0].endsWith("pid=1"))
        assertTrue(exits[1], exits[1].endsWith("pid=3"))
    }

    @Test fun `renderer records that pushed the main process out are reported as such`() {
        recordExit(process = "com.google.android.webview:sandboxed_process0", pid = 2)
        recordExit(process = "com.google.android.webview:sandboxed_process1", pid = 3)

        assertEquals(
            listOf("no main-process record retained (2 on record, all from other processes)"),
            ProcessExitDiag.recentExits(),
        )
    }

    @Test fun `the exported header carries the block, and omits it with nothing on record`() {
        assertTrue(ProcessExitDiag.recentExits().isEmpty())
        assertFalse(LogExporter.exportLogcat(ctx).readText().contains("Process exits:"))

        recordExit(pid = 11923)
        val header = LogExporter.exportLogcat(ctx).readText().substringBefore("─")
        assertTrue(header, header.contains("Process exits:\n- "))
        assertTrue(header, header.trimEnd().endsWith("LOW_MEMORY  importance=FOREGROUND_SERVICE  pid=11923"))
    }
}
