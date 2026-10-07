package com.playtranslate.diagnostics

import android.app.Activity
import android.content.Context
import android.widget.Toast
import com.playtranslate.BuildConfig
import com.playtranslate.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Report a bug row's two gestures, shared by Settings → Support and the
 * Fix disappearing icon page so the two rows cannot drift apart: tap is an
 * email to support with the logs attached and the recipient filled in, so
 * the user only has to say what went wrong; hold is the same files through
 * the plain share sheet, for sending them somewhere other than email
 * (Discord). Logcat and any crash files are gathered off the main thread,
 * and a failure to gather them is a toast naming the exception.
 */
object BugReport {

    /** Tap: the pre-addressed email. */
    fun email(activity: Activity, scope: CoroutineScope) {
        scope.launch {
            collectLogs(activity).fold(
                onSuccess = { files ->
                    LogExporter.emailFiles(
                        activity, files,
                        subject = activity.getString(
                            R.string.settings_support_report_bug_email_subject,
                            BuildConfig.VERSION_NAME,
                        ),
                        body = activity.getString(R.string.settings_support_report_bug_email_body),
                        chooserTitle = activity.getString(R.string.settings_support_report_bug_chooser_title),
                        noFilesToast = R.string.toast_no_logs_to_share,
                    )
                },
                onFailure = { toastFailed(activity, it) },
            )
        }
    }

    /** Hold: the plain share sheet. */
    fun share(activity: Activity, scope: CoroutineScope) {
        scope.launch {
            collectLogs(activity).fold(
                onSuccess = { files ->
                    LogExporter.shareFiles(
                        activity, files, activity.getString(R.string.settings_debug_export_logs_subject),
                    )
                },
                onFailure = { toastFailed(activity, it) },
            )
        }
    }

    /** Logcat plus any crash files on disk, gathered off the main thread. */
    private suspend fun collectLogs(ctx: Context): Result<List<File>> = withContext(Dispatchers.IO) {
        runCatching {
            val logFile = LogExporter.exportLogcat(ctx)
            listOf(logFile) + LogExporter.getCrashFiles(ctx)
        }
    }

    private fun toastFailed(ctx: Context, t: Throwable) {
        Toast.makeText(
            ctx,
            ctx.getString(R.string.settings_debug_export_logs_failed, t.javaClass.simpleName),
            Toast.LENGTH_LONG,
        ).show()
    }
}
