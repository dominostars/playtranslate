package com.playtranslate.diagnostics

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import com.playtranslate.BuildConfig
import com.playtranslate.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogExporter {

    /** Where the crash dialog and Settings → Report a bug address their email. */
    const val SUPPORT_EMAIL = "support@playtranslate.com"
    private const val FILE_PROVIDER_AUTHORITY = "com.playtranslate.fileprovider"
    private const val LOGS_DIR = "logs"
    private const val LOGCAT_LINES = "5000"

    private val FILE_NAME_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val HEADER_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)

    fun exportLogcat(context: Context): File {
        val dir = File(context.cacheDir, LOGS_DIR).apply { mkdirs() }
        // Keep only the most recent file: delete everything before writing.
        dir.listFiles()?.forEach { it.delete() }

        val file = File(dir, "logcat-${FILE_NAME_FORMAT.format(Date())}.txt")
        val header = buildHeader()
        val body = runLogcat()
        file.writeText(header + body)
        return file
    }

    fun getCrashFiles(context: Context): List<File> {
        val dir = File(context.filesDir, CrashHandler.CRASHES_DIR)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /**
     * Copies of [files] next to the logcat file in the export directory, for
     * attaching in place of the originals. The chooser returns before the
     * receiving app opens the attachment URIs, so a source deleted right after
     * launch is gone by the time Gmail reads it; a copy here outlives that,
     * and [exportLogcat] clearing this directory on the next export is the
     * only cleanup it needs. Call AFTER [exportLogcat], which empties the
     * directory first. Blocking; call off the main thread.
     */
    fun stageCrashFiles(context: Context, files: List<File>): List<File> {
        val dir = File(context.cacheDir, LOGS_DIR).apply { mkdirs() }
        return files.map { src -> src.copyTo(File(dir, src.name), overwrite = true) }
    }

    fun deleteCrashFiles(context: Context) {
        val dir = File(context.filesDir, CrashHandler.CRASHES_DIR)
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Plain share sheet, no recipient: Settings → Report a bug on hold, and
     *  the fallback [emailFiles] takes when no email app is installed. */
    fun shareFiles(activity: Activity, files: List<File>, subject: String) {
        if (files.isEmpty()) {
            Toast.makeText(activity, activity.getString(R.string.toast_no_logs_to_share), Toast.LENGTH_SHORT).show()
            return
        }
        val uris = files.map { fileToUri(activity, it) }
        val intent = buildSendIntent(uris).apply {
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        startChooser(activity, intent, activity.getString(R.string.share_chooser_share_logs))
    }

    /**
     * Email to [SUPPORT_EMAIL] with [files] attached and the recipient,
     * [subject] and [body] filled in — the crash dialog and Settings → Report
     * a bug. Falls back to [shareFiles] when no email app is installed. That
     * check is resolveActivity, which on API 30+ only sees apps the manifest's
     * <queries> declares; the SEND_MULTIPLE / message/rfc822 entry there is
     * what keeps it from answering "none" on every phone that has Gmail.
     */
    fun emailFiles(
        activity: Activity,
        files: List<File>,
        subject: String,
        body: String,
        chooserTitle: String,
        @StringRes noFilesToast: Int,
    ) {
        if (files.isEmpty()) {
            Toast.makeText(activity, activity.getString(noFilesToast), Toast.LENGTH_SHORT).show()
            return
        }
        // ACTION_SEND for one file, SEND_MULTIPLE for more, like the share
        // path: a client that registers only the single-file action would
        // otherwise read as "no email app" for a logcat-only report.
        val uris = files.map { fileToUri(activity, it) }
        val intent = buildSendIntent(uris, mimeType = "message/rfc822").apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        if (intent.resolveActivity(activity.packageManager) == null) {
            // The share sheet carries no recipient, so the toast names it.
            Toast.makeText(
                activity,
                activity.getString(R.string.email_no_app_fallback, SUPPORT_EMAIL),
                Toast.LENGTH_LONG
            ).show()
            shareFiles(activity, files, subject)
            return
        }
        startChooser(activity, intent, chooserTitle)
    }

    private fun runLogcat(): String {
        return try {
            val process = ProcessBuilder(
                "logcat", "-d", "-v", "threadtime", "-t", LOGCAT_LINES
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output
        } catch (t: Throwable) {
            "logcat failed: ${t.javaClass.simpleName}: ${t.message}\n"
        }
    }

    private fun buildHeader(): String = buildString {
        appendLine("PlayTranslate log export")
        appendLine("Time: ${HEADER_FORMAT.format(Date())}")
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, ${Build.HARDWARE})")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}, patch ${Build.VERSION.SECURITY_PATCH})")
        appendTranslationDiagnostics()
        appendProcessExits()
        appendLine("─".repeat(60))
        appendLine()
    }

    /** Translation-waterfall forensics — so an export made AFTER logcat
     *  rolled past a rate-limit episode still carries the evidence.
     *  SILENT when there is nothing to show: the header is shared by
     *  every support flow, and translation earns space in it only when
     *  it has recorded failures or an active cooldown. Content-free by
     *  construction (see [TranslationDiag]); best-effort so a half-
     *  initialized process can never break a crash export. */
    private fun StringBuilder.appendTranslationDiagnostics() {
        runCatching {
            val cooldowns = com.playtranslate.translation.TranslationBackendRegistry
                .activeCooldowns()
            val failures = TranslationDiag.recentFailures()
            if (cooldowns.isEmpty() && failures.isEmpty()) return
            appendLine("Translation diagnostics:")
            cooldowns.forEach { appendLine("- $it") }
            failures.forEach { appendLine("- $it") }
        }
    }

    /** Why earlier processes died (see [ProcessExitDiag]), which logcat
     *  cannot answer once the dying process's lines have rolled. Silent
     *  when the platform holds no record; best-effort like the block
     *  above. */
    private fun StringBuilder.appendProcessExits() {
        runCatching {
            val exits = ProcessExitDiag.recentExits()
            if (exits.isEmpty()) return
            appendLine("Process exits:")
            exits.forEach { appendLine("- $it") }
        }
    }

    private fun fileToUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)

    private fun buildSendIntent(uris: List<Uri>, mimeType: String = "text/plain"): Intent {
        return if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private fun startChooser(activity: Activity, intent: Intent, title: String) {
        val chooser = Intent.createChooser(intent, title)
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(chooser)
    }
}
