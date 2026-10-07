package com.playtranslate.diagnostics

import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.playtranslate.Prefs
import com.playtranslate.capture.SessionMarker

/**
 * The one-time "Android closed PlayTranslate" alert after a process that
 * replaced one that died with the floating controls on. The death and its
 * record come from [SessionMarker] (the record is matched by pid, never
 * guessed); the record's reason picks the wording and rules out the deaths
 * that are not a kill from the user's point of view: a crash (the crash
 * prompt owns that), an app update, and the user's own Recents swipe.
 * Shown once per process, and never again once the user has opened the
 * Keep-running page the alert points at. Without a record (Android 10, or
 * one evicted before this process started) nothing is said: no claim about
 * what the phone did without the phone's own word for it.
 */
object KillNotice {

    /** [MEMORY]: the record says low memory. [STOPPED]: a force stop, which
     *  the record cannot attribute (the user's own Settings force stop and a
     *  ROM cleaner's both read "stop <pkg> due to from pid <N>", the
     *  caller's pid, meaningless afterwards), so the wording leaves it to
     *  the user. [OTHER]: every other kill the phone made. */
    enum class Kind { MEMORY, STOPPED, OTHER }

    @Volatile private var shown = false

    fun pending(ctx: Context): Kind? {
        if (shown) return null
        val exit = SessionMarker.killExit ?: return null
        if (Prefs(ctx).keepRunningPageSeen) return null
        return kindFor(exit.reason, exit.description)
    }

    fun markShown() {
        shown = true
    }

    /** Pure mapping from the platform's exit reason to the words, or null
     *  for "say nothing". An ALLOWLIST, like [SessionMarker.restorable]: a
     *  reason this code has not weighed (unknown, exit-self, a crash, an
     *  ANR, an initialization or permission failure, the Android user being
     *  stopped, a package change, anything newer than this build) gets no
     *  alert rather than a guess. Descriptions are the system's own words
     *  (ActivityManagerService, AOSP). */
    fun kindFor(reason: Int, description: String?): Kind? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return when (reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> Kind.MEMORY
            // The system's own kills, for its own reasons.
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_FREEZER,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_OTHER -> Kind.OTHER
            // "User requested" covers four things the record distinguishes
            // only by its description: an install on Android 13 and below
            // ("stop <pkg> due to installPackageLI"), a Recents swipe on a
            // ROM that kills on swipe ("remove task"), the Stop button in
            // the running-apps panel ("fully stop <pkg>/<user> by user
            // request"), and a force stop ("stop <pkg> due to from pid
            // <N>"), which the user's Settings and a ROM's cleaner both
            // perform. Only the last is worth a word, and a hedged one.
            ApplicationExitInfo.REASON_USER_REQUESTED -> when {
                description == null -> null
                description.contains("installPackageLI") -> null
                description == "remove task" -> null
                description.contains("by user request") -> null
                description.startsWith("stop ") -> Kind.STOPPED
                else -> null
            }
            else -> null
        }
    }
}
