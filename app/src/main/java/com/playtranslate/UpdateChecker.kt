package com.playtranslate

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Checks the GitHub releases API for a newer version of the app and exposes a
 * single [maybeCheck] entry point that honors a 24h network debounce plus the
 * user's "Skip this version" preference.
 *
 * A successful/unsuccessful network attempt both consume the debounce window,
 * so we don't hammer the GitHub API on rapid restarts or while offline.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val RELEASES_URL =
        "https://api.github.com/repos/dominostars/playtranslate/releases/latest"
    private val DEBOUNCE_MS = TimeUnit.HOURS.toMillis(24)

    data class Release(
        /** Raw tag from GitHub, e.g. "v1.2.0". Used as the skip-match key. */
        val tag: String,
        /** Release page URL to hand to ACTION_VIEW. */
        val url: String,
    )

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Returns a [Release] if an update is available and the user should be
     * prompted, or `null` in every other case (debounced, no network, same
     * or older version, explicitly skipped).
     */
    suspend fun maybeCheck(context: Context): Release? {
        val prefs = Prefs(context)
        val now = System.currentTimeMillis()
        if (now - prefs.lastUpdateCheckTime < DEBOUNCE_MS) return null

        // Consume debounce up front so a failed network call doesn't trigger
        // a retry storm on every onResume.
        prefs.lastUpdateCheckTime = now

        val release = withContext(Dispatchers.IO) {
            try {
                fetchLatest()
            } catch (e: Exception) {
                Log.d(TAG, "update check failed: ${e.message}")
                null
            }
        } ?: return null

        if (!isNewer(release.tag, BuildConfig.VERSION_NAME)) return null
        if (release.tag == prefs.updateCheckSkippedTag) return null
        return release
    }

    private fun fetchLatest(): Release? {
        val req = Request.Builder()
            .url(RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "").takeIf { it.isNotEmpty() }
                ?: return null
            val url = json.optString("html_url", "").ifEmpty {
                "https://github.com/dominostars/playtranslate/releases/tag/$tag"
            }
            return Release(tag, url)
        }
    }

    /**
     * Element-wise numeric compare of dotted versions. Leading `v`/`V` is
     * stripped and any SemVer prerelease (`-xxx`) or build-metadata (`+xxx`)
     * suffix is ignored, so `1.2.0`, `v1.2.0`, `1.2.0-rc1`, and `1.2.0+5`
     * all compare as equal. This is not strict SemVer (strict would order a
     * prerelease below the release), but it is adequate for an update nudge
     * and avoids the lossy split-on-dot parser that silently dropped
     * non-numeric segments.
     */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parse(latest)
        val b = parse(current)
        val len = maxOf(a.size, b.size)
        for (i in 0 until len) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parse(v: String): List<Int> =
        v.trim()
            .trimStart('v', 'V')
            .substringBefore('-')
            .substringBefore('+')
            .split('.')
            .mapNotNull { it.toIntOrNull() }
}
