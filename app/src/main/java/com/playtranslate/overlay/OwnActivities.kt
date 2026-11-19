package com.playtranslate.overlay

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.annotation.VisibleForTesting

/**
 * Which displays one of this app's own Activities is on screen on: every
 * STARTED activity, opaque or translucent.
 *
 * Why it exists: the detached window is an overlay window, and an overlay
 * window lies above every Activity. Left up, a large one covers the app's
 * own screens and whatever they raise: Settings, the OCR download page, and
 * the translucent trampolines whose system dialogs (a runtime permission,
 * the screen-share consent) would sit under it. So the floating icon stands
 * in for the window on a display while one of our Activities is started
 * there ([com.playtranslate.shownControlsForm]), and [onChanged] is how the
 * controls hear that the answer moved.
 *
 * Not [com.playtranslate.capture.OwnWindowMask]'s set: that one leaves the
 * translucent Activities out on purpose (their windows show the game, which
 * a capture must keep), and those are exactly the ones whose dialogs matter
 * here.
 *
 * The set changes in the POST callbacks (API 29, the app's floor), after the
 * activity's own onStart or onStop has returned, so a listener that reads
 * what those methods write (MainActivity's foreground flag) reads it
 * settled. Main-thread only, like every lifecycle callback.
 */
object OwnActivities {

    private var app: Application? = null
    private val started = LinkedHashSet<Activity>()

    /** How an activity's display is read. Injectable because Robolectric
     *  ships no shadow for `Activity.getDisplay`. */
    @VisibleForTesting
    internal var displayIdProbe: (Activity) -> Int? = ::defaultDisplayId

    /** The started set changed. */
    var onChanged: (() -> Unit)? = null

    /** Register the lifecycle tracker. Idempotent per [Application]; a new
     *  Application starts with an empty set (a test JVM keeps statics, and an
     *  activity a previous test started and never stopped would stay in it). */
    fun install(app: Application) {
        if (this.app === app) return
        this.app = app
        started.clear()
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPostStarted(activity: Activity) {
                if (started.add(activity)) onChanged?.invoke()
            }
            override fun onActivityPostStopped(activity: Activity) {
                if (started.remove(activity)) onChanged?.invoke()
            }
            override fun onActivityPostDestroyed(activity: Activity) {
                if (started.remove(activity)) onChanged?.invoke()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** Whether one of our Activities is started on [displayId]. */
    fun anyOn(displayId: Int): Boolean = started.any { displayIdProbe(it) == displayId }

    private fun defaultDisplayId(activity: Activity): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display?.displayId
        } else {
            @Suppress("DEPRECATION")
            OwnWindows.managerOf(activity).defaultDisplay.displayId
        }
}
