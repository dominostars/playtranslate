package com.playtranslate.yomitan

import android.content.Context
import android.util.Log
import com.playtranslate.PtJson
import com.playtranslate.language.DownloadProgress
import com.playtranslate.language.LanguagePackDownloader
import com.playtranslate.net.PtHttp
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
        PtHttp.clientBuilder()
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

    /** Outcome of a user-initiated [checkOne]. The silent scan collapses
     *  everything into "did an update apply"; a manual check owes the user a
     *  distinct answer for each state (the same split
     *  [com.playtranslate.UpdateChecker] makes for the app's own updates). */
    sealed interface ManualCheck {
        /** The remote revision differs from the installed one. */
        data class UpdateAvailable(val remote: RemoteIndex) : ManualCheck
        /** Same revision, but the deck has NO ingested rows (a schema bump
         *  dropped them) — a re-download repairs it now instead of waiting
         *  for the debounced heal pass. */
        data class RepairAvailable(val remote: RemoteIndex) : ManualCheck
        /** The endpoint answered and the installed deck is current + intact. */
        data object UpToDate : ManualCheck
        /** Couldn't ask: offline, timeout, non-2xx, unparseable body, or the
         *  deck declares no update capability. */
        data object Failed : ManualCheck
    }

    /** Outcome of a user-initiated [downloadAndApply]. */
    sealed interface ManualApply {
        /** The swap committed. [dictionary] is the NEW registry entry — its
         *  id differs from the replaced one (ids are content-derived), so any
         *  UI holding the old id MUST re-point at this entry or its later
         *  writes silently no-op. */
        data class Updated(val dictionary: YomitanDictionary) : ManualApply
        /** The busy gate refused the apply (translation session in progress).
         *  The download is discarded; a retry re-checks idempotently. */
        data object Deferred : ManualApply
        /** Not enough disk for the download or the install. Pre-download the
         *  exact need is unknown, so [requiredBytes] reports the free-space
         *  margin the download refuses to eat into. */
        data class NoSpace(val requiredBytes: Long, val availableBytes: Long) : ManualApply
        /** The replacement was deliberately not applied (deck deleted or
         *  opted out mid-update, or the endpoint served a different
         *  dictionary). Expected-rare; logged at info. */
        data class Skipped(val reason: String) : ManualApply
        /** Download or apply failed (network error, invalid zip, IO). */
        data object Failed : ManualApply
    }

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
     *  (logged, never thrown). The client comes from [PtHttp.clientBuilder], so a
     *  non-https indexUrl (or an https→http redirect) is refused by the shared
     *  https-only interceptor and surfaces here as a skipped fetch. */
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
     * User-initiated check for one deck (the detail page's "Check for updates"
     * row). Distinguishes what the silent scan collapses: a newer revision, a
     * same-revision deck whose rows are gone (repair via re-download, the
     * manual analog of the heal pass), current-and-intact, and couldn't-ask.
     * The apply path detects missing rows itself, so [RepairAvailable] needs
     * no force flag downstream — [downloadAndApply] just runs.
     */
    suspend fun checkOne(ctx: Context, dict: YomitanDictionary): ManualCheck {
        val indexUrl = dict.indexUrl
        if (!dict.isUpdatable || indexUrl == null) return ManualCheck.Failed
        val remote = fetchRemoteIndex(indexUrl) ?: return ManualCheck.Failed
        return when {
            shouldUpdate(dict.revision, remote.revision) -> ManualCheck.UpdateAvailable(remote)
            !YomitanDataStore.isIngested(ctx, dict.id) -> ManualCheck.RepairAvailable(remote)
            else -> ManualCheck.UpToDate
        }
    }

    /**
     * Download + apply for one deck whose [remote] index is already fetched:
     * bounded download → (if not busy) apply. [isBusy] is evaluated
     * immediately before the registry-mutating apply; when it's true the
     * validated download is discarded and the cycle bails — a later attempt
     * re-checks and re-downloads (idempotent), so no in-progress translation
     * session is ever disrupted and no durable staged state is needed.
     * [onProgress] streams download progress to a UI and [onApplying] fires
     * once, after the busy gate passes and before the apply starts, so a UI
     * can switch its bar to an indeterminate "installing" state (a large
     * deck's ingest takes a while). The silent scan passes neither.
     * [userInitiated] lets the commit ignore the deck's auto-update opt-out
     * (an explicit tap outranks "don't update me silently"); the silent scan
     * passes false. Never throws except on cancellation.
     */
    suspend fun downloadAndApply(
        ctx: Context,
        dict: YomitanDictionary,
        remote: RemoteIndex,
        isBusy: () -> Boolean,
        onProgress: ((DownloadProgress.Downloading) -> Unit)? = null,
        onApplying: (() -> Unit)? = null,
        userInitiated: Boolean = false,
    ): ManualApply = withContext(Dispatchers.IO) {
        // IO-dispatched so the manual flow can call from a Main-dispatched
        // scope: the disk probes/cleanup here run outside the internally
        // dispatched download/apply. Callbacks fire on this dispatcher — a UI
        // caller posts to its own thread.
        val downloadUrl = remote.downloadUrl?.trim()?.takeUnless { it.isEmpty() }
            ?: dict.downloadUrl
            ?: run {
                Log.w(TAG, "update for ${dict.id}: no downloadUrl (remote or installed)")
                return@withContext ManualApply.Failed
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
            return@withContext ManualApply.NoSpace(DOWNLOAD_SPACE_MARGIN_BYTES, tmpDir.usableSpace)
        }
        try {
            tmp.delete() // mutable URL — never resume a stale partial
            LanguagePackDownloader().download(downloadUrl, tmp, maxBytes = maxBytes) {
                onProgress?.invoke(it)
            }

            // Gate immediately before the apply (the only step that mutates the
            // registry / ingests / invalidates caches). Download already done; if
            // the user is now translating, defer — a later attempt retries.
            if (isBusy()) {
                Log.i(TAG, "deferring apply for ${dict.id}: app busy")
                return@withContext ManualApply.Deferred
            }
            onApplying?.invoke()
            when (
                val result = YomitanDictionaryStore.applyUpdate(
                    ctx, dict, tmp,
                    remoteRevision = remote.revision,
                    userInitiated = userInitiated,
                )
            ) {
                is YomitanImportResult.Success -> ManualApply.Updated(result.dictionary)
                is YomitanImportResult.InsufficientSpace ->
                    ManualApply.NoSpace(result.requiredBytes, result.availableBytes)
                is YomitanImportResult.Skipped -> {
                    // Expected: the deck was deleted or opted out during the
                    // update, or superseded. Not a failure.
                    Log.i(TAG, "update skipped for ${dict.id}: ${result.reason}")
                    ManualApply.Skipped(result.reason)
                }
                else -> {
                    Log.w(TAG, "applyUpdate failed for ${dict.id}: $result")
                    ManualApply.Failed
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "update failed for ${dict.id}: ${e.message}", e)
            ManualApply.Failed
        } finally {
            tmp.delete()
        }
    }

    /**
     * Full single-deck cycle for the SILENT scan: check → (if newer) download
     * → (if not busy) apply, via [downloadAndApply]. Returns true iff an
     * update was applied.
     *
     * [force] (the auto-heal pass) skips ONLY the revision-inequality check —
     * an outdated deck needs its rows back even at the same revision. The
     * apply path detects the missing rows itself and re-ingests regardless of
     * byte identity.
     */
    suspend fun updateOne(
        ctx: Context,
        dict: YomitanDictionary,
        isBusy: () -> Boolean,
        force: Boolean = false,
    ): Boolean {
        val indexUrl = dict.indexUrl
        if (!dict.isUpdatable || indexUrl == null) return false

        val remote = fetchRemoteIndex(indexUrl) ?: return false
        if (!force && !shouldUpdate(dict.revision, remote.revision)) return false

        return downloadAndApply(ctx, dict, remote, isBusy) is ManualApply.Updated
    }
}
