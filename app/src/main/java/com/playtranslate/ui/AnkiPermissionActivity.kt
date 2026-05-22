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
                requestAnkiPermission.launch(AnkiManager.PERMISSION)
            }
        }
    }

    /** Hand the launch intent's extras to the opaque review activity, then
     *  finish so this trampoline isn't left on the back stack. */
    private fun forwardToReview() {
        startActivity(
            Intent(this, WordAnkiReviewActivity::class.java)
                .putExtras(intent.extras ?: Bundle())
        )
        finish()
    }
}
