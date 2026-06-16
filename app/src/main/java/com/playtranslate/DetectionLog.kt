package com.playtranslate

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only log of live mode detection state changes.
 * CaptureService posts messages; MainActivity displays the last [MAX_ENTRIES].
 */
object DetectionLog {

    private const val MAX_ENTRIES = 25
    private val entries = ArrayDeque<String>(MAX_ENTRIES + 1)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val mainHandler = Handler(Looper.getMainLooper())

    var enabled = false
    var onUpdate: ((String) -> Unit)? = null

    fun log(message: String) {
        android.util.Log.d("DetectionLog", message)
        if (!enabled) return
        val ts = timeFormat.format(Date())
        val entry = "$ts  $message"
        entries.addLast(entry)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        val text = entries.joinToString("\n")
        mainHandler.post { onUpdate?.invoke(text) }
    }

    fun clear() {
        entries.clear()
        mainHandler.post { onUpdate?.invoke("") }
    }
}
