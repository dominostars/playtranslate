package com.playtranslate.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.playtranslate.CaptureService

/**
 * Transparent, UI-less activity whose only job is to show the system
 * MediaProjection consent dialog and hand the result to
 * [MediaProjectionController]. Launched by the controller's `ensureConsent`
 * when consent is first needed — never from inside a capture.
 *
 * The controller's consent gate waits on exactly one instance of this
 * activity, launched for that request by identity ([EXTRA_REQUEST_ID]),
 * bound in [onCreate] ([MediaProjectionController.bindConsentUi])
 * and answered by exactly one report: the dialog's result, or a cancel when
 * the instance is finished with no result (it will never get one: the
 * platform drops results addressed to a finished record). An instance
 * destroyed without finishing is different: the platform is relaunching it
 * for a configuration change or reclaimed it for memory, and keeps its
 * record either way, so the gate stays for the recreation to bind and
 * answer ([MediaProjectionController.consentUiReclaimed]). A result
 * reaches a paused activity on its next resume, and the single-app grant
 * is even force-delivered to a stopped one (ActivityRecord.sendResult,
 * forceSendForMediaProjection), but a cancel queued while this activity is
 * stopped waits for a resume that nothing schedules: on the Moto, a Settings
 * page opened over the dialog, the system dialog finished, and this activity
 * sat stopped with its cancel parked for thirty seconds until the system
 * destroyed it, while every Turn On joined the gate and did nothing. So
 * [hidden] is exposed, and a request that finds the bound instance hidden
 * supersedes it ([abandon]) rather than joining it. Stopped alone is not a
 * cancel: the single-app picker brings the chosen task to the front, which
 * stops this activity, before its grant is force-delivered.
 */
class MediaProjectionConsentActivity : ComponentActivity() {

    /** True between [onStop] and the next [onStart]: nothing of this task
     *  is on screen, so no user action on the dialog can reach it. Read by
     *  the controller when a new request finds the gate pending. */
    var hidden: Boolean = false
        private set

    /** One report per instance: the dialog's result, or the destroy-without-
     *  result cancel. Whether a report counts is the controller's call: it
     *  takes one only from the instance bound to the pending request, so a
     *  late result from a superseded instance (the picker's force-delivered
     *  grant landing after a supersede) reaches no request. */
    private var reported = false

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        report(result.resultCode, result.data)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Bind to the request this instance was launched for (its identity
        // rides in the intent, which survives a relaunch). No pending
        // request (an instance the system restored after a process death),
        // a request that is no longer the pending one (a superseded record
        // the platform recreated), or a second instance for a request
        // another already serves means nothing to answer: finish rather
        // than sit transparent over the screen, or stack a second system
        // dialog on the first.
        val controller = CaptureService.instance?.mediaProjectionControllerIfInitialized
        val requestToken = intent.getStringExtra(EXTRA_REQUEST_ID)
        if (controller == null || !controller.bindConsentUi(this, requestToken)) {
            Log.i(TAG, "no pending consent request for this instance; finishing")
            finish()
            return
        }
        // Only launch the consent dialog on a fresh start. A config change
        // outside this activity's configChanges (uiMode / density / fontScale)
        // recreates it while the system dialog is still up — re-launching
        // would stack a duplicate. The registered launcher survives the
        // recreation and still delivers the original result.
        if (savedInstanceState != null) return
        val mgr = getSystemService(MediaProjectionManager::class.java)
        if (mgr == null) {
            report(Activity.RESULT_CANCELED, null)
            finish()
            return
        }
        // API 34+ shows the system's capture-scope chooser: "a single app" or
        // the entire screen. Single-app selection is deliberately allowed: a
        // task-scoped stream mirrors only the chosen app's surface subtree, so
        // our overlay windows are structurally absent from it, and live
        // TRANSLATION mode routes to a clean-stream pipeline when it detects
        // one. No public API reveals which option the user picked, so the
        // stream kind is measured at session start — see
        // [MediaProjectionController.resolveStreamKind]. The two reasons this
        // used to force createConfigForDefaultDisplay — the full-display
        // coord-space assumption, and live mode pausing when the captured app
        // leaves the foreground — are handled where they belong now: the
        // clean pipeline's identity guard and content-visibility handling.
        // API ≤ 33 has no single-app option, so the dialog is unchanged there.
        launcher.launch(mgr.createScreenCaptureIntent())
    }

    override fun onStart() {
        super.onStart()
        hidden = false
    }

    override fun onStop() {
        super.onStop()
        hidden = true
    }

    override fun onDestroy() {
        if (!isFinishing) {
            // Destroyed without finishing: the platform is relaunching this
            // instance for a configuration change, or reclaimed it for
            // memory. Either way its record, saved state and any parked
            // result survive, and a recreation binds in its own onCreate and
            // still receives the result (the launcher survives the saved
            // state). A relaunch recreates at once, in the same main-thread
            // transaction; a reclaim recreates only if the task is brought
            // forward, which nothing schedules, so the gate waits dormant
            // and a request arriving first supersedes it.
            CaptureService.instance?.mediaProjectionControllerIfInitialized?.consentUiReclaimed(this)
        } else if (!reported) {
            // Finished with no result delivered: this record is gone and the
            // platform drops results addressed to it, so the request ends
            // here as a cancel.
            Log.i(TAG, "finished without a consent result; reporting cancel")
            report(Activity.RESULT_CANCELED, null)
        }
        super.onDestroy()
    }

    /** Called by the controller when a new request supersedes this instance
     *  (hidden, no result yet), after unbinding it: its task goes, including
     *  any system dialog still alive above it. Whatever it reports from here
     *  on is unbound and dropped. */
    fun abandon() {
        finishAndRemoveTask()
    }

    private fun report(resultCode: Int, data: Intent?) {
        if (reported) return
        reported = true
        CaptureService.instance?.mediaProjectionControllerIfInitialized
            ?.reportConsent(this, resultCode, data)
    }

    companion object {
        private const val TAG = "MPConsentActivity"

        /** The request this launch answers (a token unique across controller
         *  and process lifetimes); see [MediaProjectionController.bindConsentUi]. */
        const val EXTRA_REQUEST_ID = "com.playtranslate.capture.CONSENT_REQUEST_ID"

        /** Launch the consent activity from a non-activity context for the
         *  request [requestToken]. Requires SYSTEM_ALERT_WINDOW for the
         *  background-start case, which the MediaProjection backend already
         *  depends on for its overlays. */
        fun launch(context: Context, requestToken: String) {
            context.startActivity(
                Intent(context, MediaProjectionConsentActivity::class.java)
                    .putExtra(EXTRA_REQUEST_ID, requestToken)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
