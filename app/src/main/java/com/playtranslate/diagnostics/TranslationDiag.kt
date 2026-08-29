package com.playtranslate.diagnostics

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Support forensics for the translation waterfall (same family as
 * `HomeRoute` / `DisplayDump`): a small persisted ring of recent online-
 * backend failures plus a connectivity summary, both surfaced through
 * the log-export header so a user's export diagnoses a rate-limit /
 * network episode even after logcat has rolled past it. Motivated by
 * the gtx "shows as offline" reports, where the one useful logcat line
 * survived the buffer by two minutes of luck.
 *
 * PRIVACY — content-free by construction. Entries are built from typed
 * fields only: backend display name, exception CLASS name, HTTP status,
 * cooldown timestamps. Never pass exception messages (transport
 * exceptions embed request URLs, and Lingva URLs carry the captured
 * text in `q=` params), response bodies (Google's 403 block page echoes
 * the full request URL), request URLs, API keys, or custom base URLs.
 * The connectivity summary deliberately reports only wifi/cell/ethernet
 * plus the VALIDATED bit — no VPN flag, no SSIDs, no addresses.
 */
object TranslationDiag {

    private const val PREF_FILE = "playtranslate_translation_failures"
    private const val KEY_RING = "ring"
    private const val MAX_ENTRIES = 20

    @Volatile private var appContext: Context? = null
    @Volatile private var sp: SharedPreferences? = null

    /** Wire once from Application.onCreate. Before init (or in unit
     *  tests) every method degrades to a safe no-op / placeholder. */
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        sp = app.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
    }

    /** Test seam: back the ring with an in-memory SharedPreferences. */
    internal fun initForTest(prefs: SharedPreferences) {
        appContext = null
        sp = prefs
    }

    /** Test seam: return to the uninitialized state (the object is a
     *  process-wide singleton, so tests must not leak state). */
    internal fun resetForTest() {
        appContext = null
        sp = null
    }

    /**
     * Record one backend failure into the persisted ring. Callers pass
     * only typed, content-free fields — see the class doc; in particular
     * [exceptionClass] must be a class simple name, never a message.
     */
    @Synchronized
    fun recordFailure(
        backendName: String,
        exceptionClass: String,
        httpCode: Int? = null,
        cooldownUntil: Long? = null,
    ) {
        val prefs = sp ?: return
        val entry = buildString {
            append(timestampFormat().format(Date()))
            append("  ").append(backendName)
            append("  ").append(exceptionClass)
            if (httpCode != null) append("  http=").append(httpCode)
            if (cooldownUntil != null) {
                append("  cooldown-until=")
                append(timestampFormat().format(Date(cooldownUntil)))
            }
        }
        val lines = readRing(prefs) + entry
        val trimmed = if (lines.size > MAX_ENTRIES) lines.takeLast(MAX_ENTRIES) else lines
        prefs.edit { putString(KEY_RING, trimmed.joinToString("\n")) }
    }

    /** The recorded failures, oldest first. Empty before init or when
     *  nothing has failed. */
    fun recentFailures(): List<String> {
        val prefs = sp ?: return emptyList()
        return readRing(prefs)
    }

    /**
     * One short token describing the active network: transport plus the
     * VALIDATED capability — the bit that separates "wifi associating
     * after wake / captive portal" from "network fine, server refused".
     */
    fun connectivitySummary(): String {
        val ctx = appContext ?: return "net=?"
        return try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return "net=?"
            val active = cm.activeNetwork ?: return "net=none"
            val caps = cm.getNetworkCapabilities(active) ?: return "net=unknown"
            val transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
                else -> "other"
            }
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            "net=$transport,${if (validated) "validated" else "unvalidated"}"
        } catch (_: Exception) {
            "net=?"
        }
    }

    private fun readRing(prefs: SharedPreferences): List<String> {
        val raw = prefs.getString(KEY_RING, null) ?: return emptyList()
        return raw.split('\n').filter { it.isNotBlank() }
    }

    /** SimpleDateFormat isn't thread-safe — build per use; this only
     *  runs on failure paths and export, never in the capture loop. */
    private fun timestampFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
}
