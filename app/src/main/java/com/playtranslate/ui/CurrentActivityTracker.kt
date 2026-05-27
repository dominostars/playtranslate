package com.playtranslate.ui

import android.app.Activity
import java.lang.ref.WeakReference

/**
 * Single-slot tracker for "the most recent instance of [T] that's been
 * started". Used by activities that the drag flow re-launches with
 * FLAG_ACTIVITY_MULTIPLE_TASK — without dedupe, each new launch leaves
 * the previous instance alive in a hidden task, holding async work and
 * service bindings until Android reclaims it.
 *
 * Usage:
 *   companion object {
 *       private val tracker = CurrentActivityTracker<MyActivity>()
 *       fun finishCurrentIfAny() = tracker.finishCurrent()
 *   }
 *   override fun onCreate(...) {
 *       tracker.bind(this)          // before any saved-state early-return
 *       ...
 *   }
 *   override fun onDestroy() {
 *       tracker.unbind(this)
 *       super.onDestroy()
 *   }
 *
 * Identity-compared on unbind so a slow-finishing predecessor can't
 * clear the slot for a newer successor that already registered.
 */
class CurrentActivityTracker<T : Activity> {
    @Volatile
    private var ref: WeakReference<T>? = null

    fun bind(activity: T) {
        ref = WeakReference(activity)
    }

    fun unbind(activity: T) {
        if (ref?.get() === activity) ref = null
    }

    /** Finishes the most recently bound instance, if any. Safe when no
     *  instance is bound or it has already been finished — both become
     *  no-ops. */
    fun finishCurrent() {
        val activity = ref?.get()
        ref = null
        activity?.takeIf { !it.isFinishing }?.finish()
    }
}
