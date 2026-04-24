package com.playtranslate.language

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches additional word-level example sentence pairs from Tatoeba's
 * `api.tatoeba.org/unstable/sentences` endpoint. Results are word-level,
 * NOT sense-tied — Tatoeba has no sense disambiguation — so the caller
 * should surface these under a generic "More examples" section rather
 * than under a specific definition.
 *
 * Online-only and best-effort: every failure mode (offline, API error,
 * rate limited, parse error, unsupported language code) collapses to
 * `null` so the UI can hide the section cleanly.
 *
 * The response is cached in-memory for the process lifetime so rapid
 * re-opens of the same word don't hammer the API.
 *
 * License: sentences are CC-BY-2.0-FR. Callers MUST display attribution.
 */
object TatoebaClient {

    private const val TAG = "TatoebaClient"
    private const val BASE_URL = "https://api.tatoeba.org/unstable/sentences"
    private const val LIMIT = 10
    private const val MAX_PAIRS = 5

    /**
     * ISO-639-1 (what the app's pack codes use) → ISO-639-3 (what
     * Tatoeba expects). Unlisted codes return null from [fetch] —
     * better to hide the section than guess a wrong mapping.
     *
     * ZH_HANT ("zh-Hant") collapses to Mandarin because Tatoeba
     * doesn't partition simplified vs traditional — sentences are
     * tagged `cmn` regardless of script.
     */
    private val ISO_639_3 = mapOf(
        "en" to "eng", "ja" to "jpn", "zh" to "cmn", "zh-Hant" to "cmn",
        "ko" to "kor", "fr" to "fra", "de" to "deu", "es" to "spa",
        "it" to "ita", "pt" to "por", "nl" to "nld", "sv" to "swe",
        "da" to "dan", "no" to "nob", "nb" to "nob", "fi" to "fin",
        "hu" to "hun", "ro" to "ron", "ca" to "cat", "tr" to "tur",
        "vi" to "vie", "id" to "ind",
    )

    data class SentencePair(val source: String, val target: String)

    /** Matches the smallest slice of Tatoeba's JSON we need. */
    private data class ApiResponse(val data: List<ApiSentence>?)
    private data class ApiSentence(val text: String?, val translations: List<ApiTranslation>?)
    private data class ApiTranslation(val text: String?, val lang: String?)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val gson = Gson()

    private val cache = TatoebaCache()

    /**
     * Returns up to [MAX_PAIRS] bilingual sentence pairs for [word] in
     * the given language pair, or null on any failure. Empty list when
     * the API responded but had no results (distinct from failure so
     * the caller can cache and avoid re-fetching known-empty words).
     */
    /**
     * True when Tatoeba can produce meaningful results for this pack
     * pair — both codes resolve and normalize to distinct Tatoeba
     * language codes. Used by the UI to decide whether to render the
     * "More examples" section at all, so an unsupported pair (e.g.
     * zh ↔ zh-Hant both collapse to `cmn`) is hidden instead of
     * rendering a placeholder that would later flip into a misleading
     * "check your connection" error.
     */
    fun supports(sourceLang: String, targetLang: String): Boolean {
        val src = ISO_639_3[sourceLang]
        val tgt = ISO_639_3[targetLang]
        return src != null && tgt != null && src != tgt
    }

    suspend fun fetch(word: String, sourceLang: String, targetLang: String): List<SentencePair>? {
        if (!supports(sourceLang, targetLang) || word.isBlank()) return null
        val src = ISO_639_3.getValue(sourceLang)
        val tgt = ISO_639_3.getValue(targetLang)

        val cacheKey = "$word$src$tgt"
        cache.get(cacheKey)?.let { return it }

        val url = BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("q", word)
            .addQueryParameter("lang", src)
            .addQueryParameter("trans:lang", tgt)
            .addQueryParameter("sort", "relevance")
            .addQueryParameter("limit", LIMIT.toString())
            .build()

        val req = Request.Builder().url(url).build()

        val result: List<SentencePair>? = withContext(Dispatchers.IO) {
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.d(TAG, "fetch($word): HTTP ${resp.code}")
                        return@withContext null
                    }
                    val body = resp.body?.string() ?: return@withContext null
                    val parsed = gson.fromJson(body, ApiResponse::class.java)
                    val sentences = parsed?.data ?: return@withContext emptyList()
                    sentences.mapNotNull { s ->
                        val source = s.text?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val translation = s.translations
                            ?.firstOrNull { it.lang == tgt && !it.text.isNullOrBlank() }
                            ?.text ?: return@mapNotNull null
                        SentencePair(source, translation)
                    }.take(MAX_PAIRS)
                }
            } catch (e: Exception) {
                Log.d(TAG, "fetch($word) failed: ${e.message}")
                null
            }
        }

        if (result != null) cache.put(cacheKey, result)
        return result
    }
}
