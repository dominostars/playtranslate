package com.playtranslate.diagnostics

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Support forensics for process deaths (same family as [TranslationDiag]):
 * the platform's own record of why earlier PlayTranslate processes died,
 * surfaced through the log-export header. A death is the one event the
 * dying process cannot log, and logcat rarely keeps the system's side of
 * it: a field export from a nubia Neo 5 GT (2026-10-05) held 13 seconds
 * of main buffer, and the Samsung crash report of 2026-10-01 showed every
 * restart crash but not what had killed the process before each one.
 *
 * Read live from [ActivityManager.getHistoricalProcessExitReasons] (API
 * 30+; the list is empty below that). Nothing is persisted here, so the
 * platform's retention is the limit: about 16 records per package, shared
 * with the WebView renderer processes the lookup UI spawns. On the Thor,
 * half an hour of lookups replaced every main-process record with renderer
 * ones (2026-09-13). When that has happened [recentExits] says so in one
 * line instead of printing nothing, since nothing would read as "the app
 * never died".
 *
 * Content-free: every field is the system's own (reason and importance
 * codes, pid, exit status, memory sizes, and its wording of the kill such
 * as "remove task"). For a crash or an ANR, AOSP writes the literal
 * "crash", "anr" or "bg anr" there, never the exception message, so the
 * header's no-messages rule ([TranslationDiag]) holds for this block too.
 */
object ProcessExitDiag {

    @Volatile private var appContext: Context? = null

    /** Wire once from Application.onCreate. Before init [recentExits] is
     *  empty. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** One line per recorded death of the app's main process, oldest
     *  first; renderer processes are left out. Empty when the platform
     *  holds no record at all. Blocking (one Binder call): call off the
     *  main thread. */
    fun recentExits(): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val all = allRecords() ?: return emptyList()
        if (all.isEmpty()) return emptyList()
        val main = all.filter { it.processName == mainProcessName() }
        if (main.isEmpty()) {
            return listOf(
                "no main-process record retained (${all.size} on record, all from other processes)"
            )
        }
        return main.asReversed().map(::format)
    }

    /** The newest recorded death of the main process, or null when there
     *  is none: below API 30, before init, or evicted by renderer records. */
    fun lastMainExit(): ApplicationExitInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return allRecords()?.firstOrNull { it.processName == mainProcessName() }
    }

    /** The recorded death of the main process that had [pid], or null when
     *  there is none: below API 30, before init, evicted by renderer records,
     *  or a pid the platform has no record for. The platform keeps about 16
     *  records per package and reuses pids only after wrapping, so within
     *  one boot a match is that process. */
    fun exitOfPid(pid: Int): ApplicationExitInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || pid <= 0) return null
        return allRecords()?.firstOrNull { it.pid == pid && it.processName == mainProcessName() }
    }

    /** The app runs in one process, so "this process's name" is the main
     *  process; everything else on record is a renderer. */
    private fun mainProcessName(): String? = Application.getProcessName()

    /** Every record for the package, newest first (pid 0 = every process,
     *  maxNum 0 = all on record); null before init. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun allRecords(): List<ApplicationExitInfo>? {
        val ctx = appContext ?: return null
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return null
        return am.getHistoricalProcessExitReasons(ctx.packageName, 0, 0)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun format(info: ApplicationExitInfo): String = buildString {
        append(timestampFormat().format(Date(info.timestamp)))
        append("  ").append(reasonName(info.reason))
        // What the process was when it died: FOREGROUND_SERVICE or VISIBLE
        // means a session was up (the icon and the capture service), CACHED
        // is the ordinary end of an idle process.
        append("  importance=").append(importanceName(info.importance))
        append("  pid=").append(info.pid)
        // The signal for SIGNALED / CRASH_NATIVE, the exit code for EXIT_SELF.
        if (info.status != 0) append("  status=").append(info.status)
        // Sizes are in kB and 0 when the platform never sampled them.
        if (info.pss > 0) append("  pss=").append(info.pss / 1024).append("MB")
        if (info.rss > 0) append("  rss=").append(info.rss / 1024).append("MB")
        info.description?.takeIf { it.isNotBlank() }?.let { append("  desc=").append(it) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "REASON_$reason"
    }

    private fun importanceName(importance: Int): String = when (importance) {
        RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
        RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE"
        RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
        RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE"
        RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
        RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "TOP_SLEEPING"
        RunningAppProcessInfo.IMPORTANCE_CANT_SAVE_STATE -> "CANT_SAVE_STATE"
        RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
        RunningAppProcessInfo.IMPORTANCE_GONE -> "GONE"
        else -> importance.toString()
    }

    /** SimpleDateFormat isn't thread-safe: build per use, export only. */
    private fun timestampFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
}
