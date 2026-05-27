package com.playtranslate.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.playtranslate.AnkiManager
import com.playtranslate.R

/**
 * Translucent, content-less trampoline that makes sure the AnkiDroid
 * read/write permission is held, then forwards to [WordAnkiReviewActivity]
 * with the launch intent's extras.
 *
 * The rationale and the system permission dialog layer over whatever is
 * behind the translucent window, so the permission is asked in place — a
 * missing permission never drops the user onto a blank screen. The review
 * activity is kept separate and opaque so its sheet keeps its own slide
 * animation; a translucent host skews that slide toward a corner. Mirrors
 * the capture package's MediaProjectionConsentActivity.
 */
class AnkiPermissionActivity : ComponentActivity() {

    private val requestAnkiPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            forwardToReview()
        } else {
            // Covers an explicit Deny and the silent auto-deny once the
            // permission has been permanently declined.
            Toast.makeText(this, R.string.anki_permission_denied, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // Register before any early-return so a follow-up
        // [finishCurrentIfAny] call from a rapid second tap can dismiss
        // this in-flight trampoline (e.g. if the rationale / system
        // permission dialog is still up) before it forwards a now-stale
        // intent to WordAnkiReviewActivity.
        tracker.bind(this)

        // A recreation while the system permission dialog is up is recovered
        // by the registered launcher re-delivering its result.
        if (savedInstanceState != null) return

        if (AnkiManager(this).hasPermission()) {
            forwardToReview()
        } else {
            showAnkiPermissionRationaleDialog(
                activity = this,
                onCancel = { finish() },
            ) {
                // A concurrent [finishCurrentIfAny] from a rapid second
                // Anki tap could have set isFinishing while this dialog
                // was up; launching the permission request after that
                // either throws or queues a result that no one consumes.
                if (isFinishing || isDestroyed) return@showAnkiPermissionRationaleDialog
                requestAnkiPermission.launch(AnkiManager.PERMISSION)
            }
        }
    }

    /** Hand the launch intent's extras to the opaque review activity, then
     *  finish so this trampoline isn't left on the back stack.
     *
     *  Bails out if this trampoline has already been finished externally
     *  (via [finishCurrentIfAny] from a rapid second Anki tap). Without
     *  this guard, the permission-result callback can still fire on a
     *  finish-requested instance and forward its now-stale intent extras
     *  into a new WordAnkiReviewActivity — surfacing the old sentence
     *  the user already abandoned. */
    private fun forwardToReview() {
        if (isFinishing || isDestroyed) return
        startActivity(
            Intent(this, WordAnkiReviewActivity::class.java)
                .putExtras(intent.extras ?: Bundle())
        )
        finish()
    }

    override fun onDestroy() {
        tracker.unbind(this)
        super.onDestroy()
    }

    companion object {
        /** See [CurrentActivityTracker]. Called from
         *  [DragLookupController.openAnkiReviewForLens] alongside
         *  [WordAnkiReviewActivity.finishCurrentIfAny] so a rapid second
         *  Anki tap can also cancel an in-flight permission trampoline
         *  before it forwards its now-stale intent. */
        private val tracker = CurrentActivityTracker<AnkiPermissionActivity>()
        fun finishCurrentIfAny() = tracker.finishCurrent()
    }
}
