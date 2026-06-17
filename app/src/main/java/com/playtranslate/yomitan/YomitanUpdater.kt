package com.playtranslate.yomitan

import android.content.Context
import android.util.Log
import com.playtranslate.PtJson
import com.playtranslate.language.LanguagePackDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Yomitan dictionary auto-update: for one installed deck, checks its remote
 * index.json `revision` and, when it differs from the installed one, downloads
 * the new zip and applies it. Mirrors Yomitan's update semantics — revision
 * comparison is INEQUALITY (remote ≠ installed ⇒ update), not ordered. Network
 * failures degrade to "no update" (logged, never thrown), so a dead third-party
 * host just skips its deck and never breaks anything else.
 *
 * This object is the per-deck mechanism; [YomitanAutoUpdateOrchestrator] decides
 * WHEN to run it (launch, debounced, single-flight) and supplies the active-use
 * gate. The download (network) is always safe during active use; only the apply
 * touches the lookup DB and is gated by [isBusy].
 */
object YomitanUpdater {

    private const val TAG = "YomitanUpdater"

    /** Size bounds for UNTRUSTED third-party update endpoints — a deck's
     *  index/download URL is whatever its author baked in, fetched SILENTLY at
     *  launch. The index read matches the local index.json cap; the zip is
     *  bounded (absolute ceiling + a free-space margin) so a malicious or
     *  looping endpoint can't OOM the process or fill storage before
     *  installZip's post-download guard can run. */
    private const val MAX_REMOTE_INDEX_BYTES = 256 * 1024              // 256 KB
    private const val MAX_UPDATE_ZIP_BYTES = 512L * 1024 * 1024        // 512 MB ceiling
    private const val DOWNLOAD_SPACE_MARGIN_BYTES = 100L * 1024 * 1024 // keep ≥100 MB free

    /** Short-timeout client for the few-KB index.json GET (the zip download uses
     *  [LanguagePackDownloader]'s own client). Reuses the IPv4-preferred DNS so a
     *  v6-broken CDN doesn't burn a full connect-timeout before falling back. */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .dns(LanguagePackDownloader.Ipv4PreferredDns)
            .build()
    }

    /** Update-relevant fields of a remote index.json; the rest is ignored
     *  ([PtJson.lenient]). */
    @Serializable
    data class RemoteIndex(
        val revision: String? = null,
        val downloadUrl: String? = null,
    )

    /**
     * Yomitan's update rule: an update exists when the remote revision is
     * present AND differs from the installed one — string INEQUALITY, not
     * ordered (many decks date-stamp the revision, and Yomitan itself compares
     * by inequality). A blank/absent remote revision is "no update".
     */
    fun shouldUpdate(installedRevision: String?, remoteRevision: String?): Boolean {
        val remote = remoteRevision?.trim().orEmpty()
        if (remote.isEmpty()) return false
        return remote != installedRevision?.trim().orEmpty()
    }

    /** GETs and parses [indexUrl]. Returns null on any network/parse failure
     *  (logged, never thrown). HTTPS is enforced by the network security
     *  config, so an `http://` indexUrl simply fails the fetch and is skipped. */
    suspend fun fetchRemoteIndex(indexUrl: String): RemoteIndex? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(indexUrl).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                // Untrusted endpoint: cap the read so a huge body can't OOM the
                // process (mirrors the local index.json cap).
                val text = resp.body.byteStream().use { it.readUtf8Capped(MAX_REMOTE_INDEX_BYTES) }
                    ?: run {
                        Log.d(TAG, "remote index exceeds ${MAX_REMOTE_INDEX_BYTES / 1024} KB for $indexUrl")
                        return@withContext null
                    }
                PtJson.lenient.decodeFromString<RemoteIndex>(text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "remote index fetch failed for $indexUrl: ${e.message}")
            null
        }
    }

    /**
     * Full single-deck cycle: check → (if newer) download → (if not busy) apply.
     * Returns true iff an update was applied. [isBusy] is evaluated immediately
     * before the registry-mutating apply; when it's true the validated download
     * is discarded and the cycle bails — the next launch re-checks and
     * re-downloads (idempotent), so no in-progress translation session is ever
     * disrupted and no durable staged state is needed.
     */
    suspend fun updateOne(ctx: Context, dict: YomitanDictionary, isBusy: () -> Boolean): Boolean {
        val indexUrl = dict.indexUrl
        if (!dict.isUpdatable || indexUrl == null) return false

        val remote = fetchRemoteIndex(indexUrl) ?: return false
        if (!shouldUpdate(dict.revision, remote.revision)) return false

        val downloadUrl = remote.downloadUrl?.trim()?.takeUnless { it.isEmpty() }
            ?: dict.downloadUrl
            ?: run {
                Log.w(TAG, "update for ${dict.id}: no downloadUrl (remote or installed)")
                return false
            }

        val tmpDir = File(ctx.cacheDir, "yomitan-update").apply { mkdirs() }
        val tmp = File(tmpDir, "${dict.id}.zip")
        // Bound the download for an UNTRUSTED endpoint: never exceed the absolute
        // ceiling, and never write so much it threatens the cache filesystem
        // (installZip's post-download disk guard is too late to stop a fill).
        val maxBytes = minOf(
            MAX_UPDATE_ZIP_BYTES,
            (tmpDir.usableSpace - DOWNLOAD_SPACE_MARGIN_BYTES).coerceAtLeast(0L),
        )
        if (maxBytes <= 0L) {
            Log.w(TAG, "update for ${dict.id}: insufficient cache space to download")
            return false
        }
        return try {
            tmp.delete() // mutable URL — never resume a stale partial
            LanguagePackDownloader().download(downloadUrl, tmp, maxBytes = maxBytes) { /* no UI progress */ }

            // Gate immediately before the apply (the only step that mutates the
            // registry / ingests / invalidates caches). Download already done; if
            // the user is now translating, defer — next launch retries.
            if (isBusy()) {
                Log.i(TAG, "deferring apply for ${dict.id}: app busy")
                return false
            }
            when (
                val result =
                    YomitanDictionaryStore.applyUpdate(ctx, dict, tmp, remoteRevision = remote.revision)
            ) {
                is YomitanImportResult.Success -> true
                is YomitanImportResult.Skipped -> {
                    // Expected: the deck was deleted or opted out during the
                    // update, or superseded. Not a failure.
                    Log.i(TAG, "update skipped for ${dict.id}: ${result.reason}")
                    false
                }
                else -> {
                    Log.w(TAG, "applyUpdate failed for ${dict.id}: $result")
                    false
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "update failed for ${dict.id}: ${e.message}", e)
            false
        } finally {
            tmp.delete()
        }
    }
}
