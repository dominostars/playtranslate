package com.playtranslate.capture

import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import com.playtranslate.Prefs
import com.playtranslate.diagnostics.ProcessExitDiag

/**
 * Persisted record that a floating icon was on screen, so a process that
 * replaces one the system killed mid-session can tell that apart from a
 * fresh boot, a crash or an update, and put the icon back (field reports
 * 2026-10-04..06: the icon "disappears and turns off on its own"; a Moto G
 * reproduced a low-memory kill of the live session at screen-off).
 *
 * The record is the observable itself: [markOn] when an icon is installed
 * and [markOff] when the last one is removed, both in
 * [com.playtranslate.OverlayUiController], the one place icons come and
 * go. Every deliberate end (Turn Off, Hide for Now, a Recents swipe, a
 * backend swap, a graceful service end) removes the icons, so it clears
 * the record without knowing about it; a kill removes nothing, so the
 * record outlives it. Two explicit [markOff]s cover a Turn Off issued in a
 * new process before any icon is up ([CaptureLifecycle.deactivate] and
 * the accessibility service's disable), where no removal can run.
 *
 * Three values are stored: the OS boot count, the process start time and
 * the pid. At process start [evaluate] compares them:
 *  - a different boot count means the device rebooted since, and the icon
 *    stays away as it always has at boot;
 *  - the same boot count with a different process start time means the
 *    process that had the icon up is gone;
 *  - the same process start time is this very process: nothing to do.
 *
 * A gone process is not yet a kill. The pid finds that process's death in
 * the platform's exit records (API 30+), and only a death the SYSTEM caused
 * for its own reasons ([restorable]) brings the icon back: low memory, a
 * signal (OEM cleaners and lmkd both kill with SIGKILL, and lmkd's own
 * report may land after the record is first written), the freezer, a dead
 * dependency, or the platform's catch-all. A crash, an ANR, an app update,
 * a force stop or a Recents swipe (the user's own or a ROM's) leaves the
 * icon off, so a crash during session startup cannot loop, and an update
 * or the user's own stop never re-raises an icon unasked. No record (API
 * 29, a record evicted by renderer deaths) is treated the same way: never
 * restore on a guess. The matched record also picks the words of the
 * one-time notice ([com.playtranslate.diagnostics.KillNotice]).
 *
 * Android gives no callback for a kill, so this is the only way to know
 * one happened. The verdict is computed once per process, before any
 * writer can overwrite the record, and read through [cutShort]. Until the
 * restore has run, nothing in the new process rewrites the record (no
 * icon exists to install or remove), so a second kill before the screen
 * comes on still finds it.
 */
object SessionMarker {

    private const val TAG = "SessionMarker"

    enum class Verdict { OFF, SAME_PROCESS, CUT_SHORT, REBOOTED }

    /** The previous process died with the controls on, in this boot, for a
     *  [restorable] reason. Set by [evaluate] at process start and cleared
     *  by [consumeCutShort] once a restore (or a deliberate Turn Off) has
     *  acted on it. */
    @Volatile var cutShort: Boolean = false
        private set

    /** The matched exit record of the process that died with the controls
     *  on, kept for the process lifetime for the user-facing notice; null
     *  when the previous process did not die with them on, or when the
     *  platform has no record of that death. Restores consume [cutShort],
     *  not this. */
    @Volatile var killExit: ApplicationExitInfo? = null
        private set

    /** Call once from Application.onCreate, before anything can write. */
    fun evaluate(ctx: Context) {
        val prefs = Prefs(ctx)
        val verdict = decide(
            storedBoot = prefs.sessionOnBoot,
            currentBoot = bootCount(ctx),
            storedProcessStart = prefs.sessionOnProcessStart,
            thisProcessStart = Process.getStartElapsedRealtime(),
        )
        val exit = if (verdict == Verdict.CUT_SHORT) ProcessExitDiag.exitOfPid(prefs.sessionOnPid) else null
        killExit = exit
        cutShort = exit != null && restorable(exit.reason)
        Log.i(
            TAG,
            "evaluate: $verdict" +
                (exit?.let { " exit reason=${it.reason} restorable=${restorable(it.reason)}" }
                    ?: if (verdict == Verdict.CUT_SHORT) " exit record=none" else ""),
        )
    }

    /** Pure decision over the stored and current identities; see the class
     *  doc for the cases. A missing boot count (-1 from the OS) never counts
     *  as a match. */
    fun decide(
        storedBoot: Int,
        currentBoot: Int,
        storedProcessStart: Long,
        thisProcessStart: Long,
    ): Verdict = when {
        storedBoot < 0 -> Verdict.OFF
        currentBoot < 0 || storedBoot != currentBoot -> Verdict.REBOOTED
        storedProcessStart == thisProcessStart -> Verdict.SAME_PROCESS
        else -> Verdict.CUT_SHORT
    }

    /** Whether a death with this [ApplicationExitInfo] reason is the
     *  system's doing, after which the controls come back on their own.
     *  Every other reason, and any reason this build cannot name, leaves
     *  them off. The constants are compile-time ints, so naming the newer
     *  ones is safe below the API they arrived in. */
    fun restorable(reason: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return when (reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_FREEZER,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED,
            ApplicationExitInfo.REASON_OTHER -> true
            else -> false
        }
    }

    /** An icon is on screen as of this process: stamp the record. */
    fun markOn(ctx: Context) {
        val prefs = Prefs(ctx)
        prefs.sessionOnBoot = bootCount(ctx)
        prefs.sessionOnProcessStart = Process.getStartElapsedRealtime()
        prefs.sessionOnPid = Process.myPid()
    }

    /** No icon is on screen: clear the record so the next process reads
     *  nothing into it. */
    fun markOff(ctx: Context) {
        val prefs = Prefs(ctx)
        prefs.sessionOnBoot = -1
        prefs.sessionOnProcessStart = 0L
        prefs.sessionOnPid = 0
    }

    /** A restore has run, or a Turn Off made it moot. */
    fun consumeCutShort() {
        cutShort = false
    }

    /** The OS boot counter (API 24+); -1 when the setting is unreadable. */
    fun bootCount(ctx: Context): Int = try {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (_: Exception) {
        -1
    }
}
