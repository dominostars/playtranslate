package com.playtranslate.audio.sources

import com.playtranslate.BuildConfig
import com.playtranslate.PtJson
import com.playtranslate.audio.Attribution
import com.playtranslate.audio.AudioCandidate
import com.playtranslate.audio.AudioRequest
import com.playtranslate.audio.CandidateLabel
import com.playtranslate.audio.WikimediaLangCodes
import com.playtranslate.net.PtHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Keyless client for Wikimedia Commons pronunciation audio (the MediaWiki Action
 * API needs no key for reads — only a descriptive `User-Agent`, per Wikimedia
 * policy). `Context`-free so it unit-tests without Android.
 *
 * Two-step lookup: a [candidateFilenames] guess set (pure, testable) plus a
 * `list=search` pass, resolved together by one `imageinfo` call to URLs +
 * licenses, filtered to audio. Filename matching is the deliberate
 * iterate-and-tune surface; misses are harmless (the resolver falls back to TTS).
 */
class CommonsClient(
    private val client: OkHttpClient = defaultClient(),
) {

    /** Audio candidates for [req], ranked best-first. Returns **null** on a FAILED
     *  query (network/parse error) — distinct from an **empty list** (query
     *  succeeded, nothing matched) — so callers don't cache a transient failure
     *  as a permanent "no results". */
    suspend fun candidates(req: AudioRequest): List<AudioCandidate>? = withContext(Dispatchers.IO) {
        runCatching {
            val exact = candidateFilenames(req).map { "File:$it" }
            val found = search(req.surface)
            val titles = (exact + found).distinct().take(MAX_TITLES)
            val audio = imageInfo(titles).filter { it.mediatype.equals("AUDIO", ignoreCase = true) }
            // Require positive language evidence (an exact language-tagged
            // filename, or a Lingua Libre QID for THIS language) so the global
            // text search can't promote a same-spelling recording from another
            // language. No evidence → drop, and the resolver falls back to TTS.
            val confirmed = audio.filter { hasLanguageEvidence(it.title, req, exact) }
            android.util.Log.i(
                TAG,
                "Commons candidates word='${req.surface}' search=${found.size} audio=${audio.size} confirmed=${confirmed.size}",
            )
            rank(confirmed, req).map { toCandidate(it) }.take(MAX_RESULTS)
        }.onFailure { android.util.Log.w(TAG, "Commons candidates query FAILED word='${req.surface}'", it) }.getOrNull()
    }

    /** True when [title] carries positive evidence it is in [req]'s language: it
     *  matches one of the language-tagged [exactTitles], or it is a Lingua Libre
     *  file whose QID is this language's — and not a longer QID that merely shares
     *  a prefix (Q150 must not match Q1500). */
    internal fun hasLanguageEvidence(title: String, req: AudioRequest, exactTitles: List<String>): Boolean {
        if (exactTitles.any { it.equals(title, ignoreCase = true) }) return true
        val qid = WikimediaLangCodes.linguaLibreQid(req.lang) ?: return false
        val marker = "LL-$qid"
        val idx = title.indexOf(marker)
        return idx >= 0 && title.getOrNull(idx + marker.length)?.isDigit() != true
    }

    /** Downloads the audio bytes at [url], rejecting anything larger than
     *  [MAX_CLIP_BYTES] (declared OR streamed) so a mis-matched search result
     *  can't OOM the app or blow the cache budget. Null on any failure. */
    suspend fun download(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    android.util.Log.w(TAG, "Commons download HTTP ${resp.code} url=$url")
                    return@use null
                }
                val len = resp.body.contentLength()
                if (len > MAX_CLIP_BYTES) {
                    android.util.Log.w(TAG, "Commons download too large len=$len url=$url")
                    return@use null
                }
                readCapped(resp.body.byteStream(), MAX_CLIP_BYTES).also {
                    android.util.Log.i(TAG, "Commons download ok bytes=${it?.size} url=$url")
                }
            }
        }.onFailure { android.util.Log.w(TAG, "Commons download exception url=$url", it) }.getOrNull()
    }

    // --- matcher seam (pure; unit-tested apart from networking) ---

    /** Exact-filename guesses from naming conventions that carry no speaker
     *  component (the `{code}-{word}.ext` family). Lingua Libre files embed a
     *  speaker, so those are found via [search], not constructed here. */
    fun candidateFilenames(req: AudioRequest): List<String> {
        val code = WikimediaLangCodes.wikiCode(req.lang)
        val cap = code.replaceFirstChar { it.uppercase() }
        val words = listOfNotNull(req.surface.trim().ifBlank { null }, req.reading?.trim()?.ifBlank { null }).distinct()
        return buildList {
            for (w in words) for (ext in EXTS) {
                add("$code-$w.$ext")
                add("$cap-$w.$ext")
            }
        }.distinct()
    }

    // --- network ---

    private fun search(word: String): List<String> {
        val url = API.toHttpUrl().newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .addQueryParameter("list", "search")
            .addQueryParameter("srnamespace", "6") // File:
            .addQueryParameter("srsearch", "$word filetype:audio")
            .addQueryParameter("srlimit", SEARCH_LIMIT.toString())
            .build()
        return get(url)?.query?.search?.map { it.title }.orEmpty()
    }

    private fun imageInfo(titles: List<String>): List<Info> {
        val url = API.toHttpUrl().newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .addQueryParameter("prop", "imageinfo")
            .addQueryParameter("iiprop", "url|extmetadata|mediatype")
            .addQueryParameter("titles", titles.joinToString("|"))
            .build()
        return get(url)?.query?.pages.orEmpty()
            .filter { it.missing != true && it.imageinfo.isNotEmpty() }
            .map { page ->
                val ii = page.imageinfo.first()
                Info(
                    title = page.title,
                    url = ii.url,
                    mediatype = ii.mediatype,
                    descriptionUrl = ii.descriptionurl,
                    artist = ii.extmetadata["Artist"]?.value?.let(::stripHtml),
                    license = ii.extmetadata["LicenseShortName"]?.value?.let(::stripHtml),
                )
            }
    }

    private fun get(url: okhttp3.HttpUrl): CommonsResponse? {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                android.util.Log.w(TAG, "Commons API HTTP ${resp.code} url=$url")
                return null
            }
            val body = resp.body.string()
            return runCatching { PtJson.lenient.decodeFromString(CommonsResponse.serializer(), body) }
                .onFailure { android.util.Log.w(TAG, "Commons API parse failed url=$url", it) }
                .getOrNull()
        }
    }

    // --- mapping / ranking ---

    private fun rank(infos: List<Info>, req: AudioRequest): List<Info> {
        val qid = WikimediaLangCodes.linguaLibreQid(req.lang)
        return infos.sortedByDescending { score(it, qid) }
    }

    private fun score(info: Info, qid: String?): Int {
        var s = 0
        if (qid != null && info.title.contains(qid)) s += 3      // Lingua Libre file for this language
        if (info.license != null) s += 1                          // known license is preferable
        if (info.artist != null) s += 1
        return s
    }

    private fun toCandidate(info: Info): AudioCandidate {
        val name = info.title.removePrefix("File:")
        val attribution = Attribution(
            author = info.artist,
            license = info.license,
            sourceName = "Wikimedia Commons",
            sourceUrl = info.descriptionUrl.ifBlank { null },
        )
        val display = info.artist?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.')
        return AudioCandidate(
            sourceId = WikimediaCommonsAudioSource.ID,
            key = name,
            title = CandidateLabel.Text(display),
            subtitle = info.license?.let { CandidateLabel.Text(it) },
            attribution = attribution,
            locator = info.url,
        )
    }

    private fun stripHtml(s: String): String =
        Regex("<[^>]*>").replace(s, "").replace("&amp;", "&").replace("&nbsp;", " ").trim()

    private class Info(
        val title: String,
        val url: String,
        val mediatype: String,
        val descriptionUrl: String,
        val artist: String?,
        val license: String?,
    )

    @Serializable private data class CommonsResponse(val query: Query? = null)
    @Serializable private data class Query(
        val pages: List<Page> = emptyList(),
        val search: List<SearchItem> = emptyList(),
    )
    @Serializable private data class Page(
        val title: String = "",
        val missing: Boolean = false,
        val imageinfo: List<ImageInfo> = emptyList(),
    )
    @Serializable private data class ImageInfo(
        val url: String = "",
        val mediatype: String = "",
        val descriptionurl: String = "",
        val extmetadata: Map<String, ExtValue> = emptyMap(),
    )
    @Serializable private data class ExtValue(val value: String = "")
    @Serializable private data class SearchItem(val title: String = "")

    companion object {
        private const val TAG = "PtAudio"
        private const val API = "https://commons.wikimedia.org/w/api.php"
        private const val SEARCH_LIMIT = 12
        private const val MAX_TITLES = 40    // MediaWiki allows up to 50 titles/imageinfo call
        private const val MAX_RESULTS = 8
        private const val MAX_CLIP_BYTES = 5L * 1024 * 1024 // reject clips larger than 5 MB
        private val EXTS = listOf("ogg", "wav", "mp3")

        /** Reads up to [max] bytes from [stream] (closing it); null if the source
         *  exceeds [max]. Pure + testable apart from networking. */
        internal fun readCapped(stream: java.io.InputStream, max: Long): ByteArray? {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0L
            stream.use {
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > max) return null
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray()
        }

        /** Wikimedia requires a descriptive UA with a contact; the repo URL is the contact. */
        private val USER_AGENT =
            "PlayTranslate/${BuildConfig.VERSION_NAME} (+https://github.com/dominostars/playtranslate)"

        private fun defaultClient(): OkHttpClient = PtHttp.clientBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS) // backstop the whole call (incl. download)
            .build()
    }
}
