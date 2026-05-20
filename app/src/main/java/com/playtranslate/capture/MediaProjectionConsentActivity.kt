package com.playtranslate.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.playtranslate.CaptureService

/**
 * Transparent, UI-less activity whose only job is to show the system
 * MediaProjection consent dialog and hand the result to
 * [MediaProjectionController]. Launched by the controller's `ensureConsent`
 * when consent is first needed — never from inside a capture.
 */
class MediaProjectionConsentActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        CaptureService.instance?.mediaProjectionController
            ?.onConsentResult(result.resultCode, result.data)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only launch the consent dialog on a fresh start. A config change
        // outside this activity's configChanges (uiMode / density / fontScale)
        // recreates it while the system dialog is still up — re-launching
        // would stack a duplicate. The registered launcher survives the
        // recreation and still delivers the original result.
        if (savedInstanceState != null) return
        val mgr = getSystemService(MediaProjectionManager::class.java)
        if (mgr == null) {
            CaptureService.instance?.mediaProjectionController
                ?.onConsentResult(Activity.RESULT_CANCELED, null)
            finish()
            return
        }
        launcher.launch(mgr.createScreenCaptureIntent())
    }

    companion object {
        /** Launch the consent activity from a non-activity context. Requires
         *  SYSTEM_ALERT_WINDOW for the background-start case, which the
         *  MediaProjection backend already depends on for its overlays. */
        fun launch(context: Context) {
            context.startActivity(
                Intent(context, MediaProjectionConsentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
