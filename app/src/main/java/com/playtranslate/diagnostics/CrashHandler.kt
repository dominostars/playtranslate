package com.playtranslate.diagnostics

import android.content.Context
import android.os.Build
import com.playtranslate.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashHandler private constructor(
    private val appContext: Context,
    private val previous: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            writeCrashFile(thread, throwable)
        } catch (_: Throwable) {
        }
        previous?.uncaughtException(thread, throwable)
    }

    private fun writeCrashFile(thread: Thread, throwable: Throwable) {
        val dir = File(appContext.filesDir, CRASHES_DIR).apply { mkdirs() }
        trimOldFiles(dir)

        val timestamp = FILE_NAME_FORMAT.format(Date())
        val file = File(dir, "crash-$timestamp.txt")

        val stack = StringWriter().also { sw ->
            PrintWriter(sw).use { throwable.printStackTrace(it) }
        }.toString()

        val header = buildString {
            appendLine("PlayTranslate crash report")
            appendLine("Time: ${HEADER_FORMAT.format(Date())}")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine()
        }

        file.writeText(header + stack)
    }

    private fun trimOldFiles(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") } ?: return
        if (files.size < MAX_FILES) return
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_FILES - 1)
            .forEach { it.delete() }
    }

    companion object {
        const val CRASHES_DIR = "crashes"
        private const val MAX_FILES = 5
        private val FILE_NAME_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        private val HEADER_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)

        fun install(context: Context) {
            val app = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            if (previous is CrashHandler) return
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(app, previous))
        }
    }
}
