package com.playtranslate.ui

import android.app.Activity
import android.app.Dialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager

/**
 * The topmost showing [DialogFragment] dialog under [activity] whose window is
 * live, or null when none is up.
 *
 * [OverlayAlert] / [OverlayProgress] attach their scrim to a decorView. A
 * DialogFragment (the full-screen Settings sheet, its nested pickers, the Anki
 * sheet…) puts its content in a *separate window stacked above* the activity's
 * window — so a scrim parented to the activity decorView is z-ordered behind
 * that dialog and is invisible. Parenting to the top dialog's own decorView
 * instead is what lets [OverlayAlert.Builder.show] cover both the
 * activity-on-top and dialog-on-top cases without callers choosing between
 * `show` and `showInDialog`.
 *
 * Recurses through child FragmentManagers because the Settings sheet shows its
 * sub-dialogs (hotkey setup, Anki pickers, confirms) from its own
 * `childFragmentManager`; the deepest / last-resumed dialog with a live window
 * is the one drawn on top.
 */
internal fun topmostDialogWindow(activity: Activity): Dialog? {
    val fm = (activity as? FragmentActivity)?.supportFragmentManager ?: return null
    return topmostDialogIn(fm)
}

private fun topmostDialogIn(fm: FragmentManager): Dialog? {
    var top: Dialog? = null
    for (f in fm.fragments) {
        if (!f.isAdded) continue
        if (f is DialogFragment && f.isResumed) {
            val window = f.dialog?.window
            if (window != null && window.decorView.isAttachedToWindow) top = f.dialog
        }
        topmostDialogIn(f.childFragmentManager)?.let { top = it }
    }
    return top
}
