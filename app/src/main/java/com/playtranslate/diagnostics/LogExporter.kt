package com.playtranslate.diagnostics

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import com.playtranslate.BuildConfig
import com.playtranslate.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogExporter {

    const val CRASH_REPORT_EMAIL = "support@playtranslate.com"
    private const val FILE_PROVIDER_AUTHORITY = "com.playtranslate.fileprovider"
    private const val LOGS_DIR = "logs"
    private const val LOGCAT_LINES = "5000"

    /** A clip crosses a Binder transaction — ~1 MB per process, and text
     *  parcels as UTF-16 — so a full [LOGCAT_LINES] dump can overflow it and
     *  throw. ~200k chars lands at ~400 KB on the wire. */
    private const val CLIPBOARD_MAX_CHARS = 200_000
    private const val TRIM_MARKER = "[older lines trimmed to fit the clipboard]\n"

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

    /**
     * The same header + logcat [exportLogcat] writes, as one blob capped to
     * [CLIPBOARD_MAX_CHARS] — used by Settings → Export logs (long-press).
     * Crash files are left to the share path: their stacks reach logcat through
     * the default handler anyway, and the files outlive only a rolled-over log.
     *
     * Blocking (spawns `logcat`) — call off the main thread.
     */
    fun clipboardLogText(): String {
        val header = buildHeader()
        val body = runLogcat()
        val room = CLIPBOARD_MAX_CHARS - header.length - TRIM_MARKER.length
        if (body.length <= room) return header + body
        // Drop the oldest output, never the newest — a bug report lives in the
        // tail. Resume on a line boundary so the paste doesn't open mid-line.
        val from = body.length - room
        val start = body.indexOf('\n', from).let { if (it < 0) from else it + 1 }
        return header + TRIM_MARKER + body.substring(start)
    }

    fun getCrashFiles(context: Context): List<File> {
        val dir = File(context.filesDir, CrashHandler.CRASHES_DIR)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun deleteCrashFiles(context: Context) {
        val dir = File(context.filesDir, CrashHandler.CRASHES_DIR)
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Generic share sheet — used by Settings → Export logs. */
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
     * Pre-filled email to [CRASH_REPORT_EMAIL] — used by the crash dialog.
     * Falls back to [shareFiles] if no email app is installed.
     */
    fun emailFiles(
        activity: Activity,
        files: List<File>,
        subject: String,
        body: String
    ) {
        if (files.isEmpty()) {
            Toast.makeText(activity, activity.getString(R.string.toast_no_crash_report), Toast.LENGTH_SHORT).show()
            return
        }
        val uris = ArrayList(files.map { fileToUri(activity, it) })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(CRASH_REPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (intent.resolveActivity(activity.packageManager) == null) {
            Toast.makeText(
                activity,
                "No email app found — choose another way to share",
                Toast.LENGTH_LONG
            ).show()
            shareFiles(activity, files, subject)
            return
        }
        startChooser(activity, intent, "Send crash report")
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

    private fun fileToUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)

    private fun buildSendIntent(uris: List<Uri>): Intent {
        return if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
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
