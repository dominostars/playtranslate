package com.playtranslate

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.playtranslate.net.PtHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Checks the GitHub releases API for a newer version of the app. Two entry
 * points, differing only in which gates they honor:
 *
 *  - [maybeCheck] — the launch-time nudge. Honors the 24h network debounce and
 *    the user's "Skip this version" preference, and answers a single question
 *    ("is there something to prompt about?") with a nullable [Release].
 *  - [checkNow] — the Settings "Check for updates" row. The user asked for the
 *    check explicitly, so both gates are bypassed; the caller needs to tell
 *    "nothing newer" from "couldn't ask", so it answers with a [ManualCheck].
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
        /** Direct-download URL of the release's `.apk` asset, or null when
         *  the release carries none — the in-app download offer needs it. */
        val apkUrl: String? = null,
        /** Exact byte size of the APK asset (0 when [apkUrl] is null). */
        val apkSize: Long = 0L,
        /** Lowercase hex SHA-256 from the asset's `digest` field, or null on
         *  releases predating that GitHub API field — checksum verification
         *  is skipped (structural checks still gate). */
        val apkSha256: String? = null,
    )

    /** Outcome of a user-initiated [checkNow]. The launch path can collapse
     *  "nothing newer" and "the network was down" into one silent `null`;
     *  a manual check owes the user a distinct answer for each. */
    sealed interface ManualCheck {
        /** A newer release exists (skip-tag ignored) — hand to the update prompt. */
        data class Available(val release: Release) : ManualCheck
        /** GitHub answered and the installed build is current. */
        data object UpToDate : ManualCheck
        /** Couldn't ask: offline, timeout, non-2xx, or an unparseable body. */
        data object Failed : ManualCheck
    }

    private val client by lazy {
        PtHttp.clientBuilder()
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

        if (!record(prefs, release.tag)) return null
        if (release.tag == prefs.updateCheckSkippedTag) return null
        return release
    }

    /**
     * The Settings "Check for updates" row: hit GitHub now, whatever the
     * debounce says, and report a newer release even if the user once tapped
     * "Skip this version" — an explicit check outranks both gates.
     *
     * It still STAMPS the debounce, because the stamp records "we talked to
     * GitHub at T", not "we were allowed to". Without it the very next
     * onResume would fire the launch-time check over the same answer, and
     * could open a second update prompt (or a second download) on top of the
     * one this check just produced.
     *
     * The skipped tag is deliberately left in place: bypassing it is a
     * one-shot for this check, not a silent un-skip of the launch nudge.
     */
    suspend fun checkNow(context: Context): ManualCheck {
        val prefs = Prefs(context)
        prefs.lastUpdateCheckTime = System.currentTimeMillis()
        val release = withContext(Dispatchers.IO) {
            try {
                fetchLatest()
            } catch (e: Exception) {
                Log.d(TAG, "manual update check failed: ${e.message}")
                null
            }
        } ?: return ManualCheck.Failed
        return if (record(prefs, release.tag)) {
            ManualCheck.Available(release)
        } else {
            ManualCheck.UpToDate
        }
    }

    /**
     * The single writer of [Prefs.updateAvailableTag]: files what a COMPLETED
     * check learned about [tag], and answers whether it beats this build.
     *
     * Both entry points funnel their verdict through here so the Settings cell
     * can't disagree with the dialog — and so the record is filed BEFORE the
     * skip filter, which is the whole point of a separate flag: a skipped
     * version still exists, and Settings is where the user goes looking for it.
     *
     * Only a completed check writes. A failed fetch leaves the last known
     * answer standing: stale-but-once-true beats blanking the cell every time
     * the handheld is offline.
     */
    private fun record(prefs: Prefs, tag: String): Boolean {
        val newer = isNewer(tag, BuildConfig.VERSION_NAME)
        val stored = if (newer) tag else ""
        if (prefs.updateAvailableTag != stored) prefs.updateAvailableTag = stored
        return newer
    }

    private fun fetchLatest(): Release? {
        val req = Request.Builder()
            .url(RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body.string()
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "").takeIf { it.isNotEmpty() }
                ?: return null
            val url = json.optString("html_url", "").ifEmpty {
                "https://github.com/dominostars/playtranslate/releases/tag/$tag"
            }
            var apkUrl: String? = null
            var apkSize = 0L
            var apkSha256: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    if (!asset.optString("name").endsWith(".apk")) continue
                    apkUrl = asset.optString("browser_download_url").takeIf { it.isNotEmpty() }
                    apkSize = asset.optLong("size", 0L)
                    apkSha256 = asset.optString("digest")
                        .takeIf { it.startsWith("sha256:") }
                        ?.removePrefix("sha256:")
                        ?.lowercase()
                    break
                }
            }
            return Release(tag, url, apkUrl, apkSize, apkSha256)
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
