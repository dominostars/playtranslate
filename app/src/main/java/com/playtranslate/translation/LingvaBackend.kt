package com.playtranslate.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * "Lingva" backend — historically a Lingva-proxy translator, currently
 * pointed at Google's `translate.googleapis.com/translate_a/single`
 * endpoint with `client=gtx` directly for lower latency. The class
 * name intentionally matches the user-facing brand and the future
 * intent (we may switch back to a real Lingva instance), even though
 * today the implementation hits the gtx endpoint.
 *
 * No API key required.
 *
 * [enabledProvider] reflects the user's explicit on/off state from
 * Settings — the registry's waterfall skips this backend when disabled.
 */
class LingvaBackend(
    private val enabledProvider: () -> Boolean,
) : TranslationBackend, BatchTranslator {

    override val id: BackendId = "lingva"
    override val displayName: String = "Lingva"
    override val priority: Int = 20
    override val requiresInternet: Boolean = true
    override val isDegradedFallback: Boolean = false
    override val qualityStars: StarRating = 4.0f

    override val status: BackendStatus = BackendStatus.Info("No API key required")

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    override fun isUsable(source: String, target: String): Boolean = enabledProvider()

    override suspend fun translate(text: String, source: String, target: String): String =
        withContext(Dispatchers.IO) {
            val url = buildUrl(listOf(text), source, target)
            val body = fetchBody(url)
            // Single-q response shape: [[["translated","original",...], ...], null, "ja", ...]
            // The chunks array is at index 0 of the top-level array.
            val top = JSONArray(body)
            val chunks = top.getJSONArray(0)
            val result = reassembleChunks(chunks)
            if (result.isBlank()) throw IOException("Blank translation in response")
            result
        }

    override suspend fun translateBatch(
        texts: List<String>,
        source: String,
        target: String,
    ): List<String> = withContext(Dispatchers.IO) {
        // gtx is undocumented; the multi-q convention used by tools like
        // translate-shell and LunaTranslator is to repeat &q= per input
        // and treat the top-level array as a list of per-q results, each
        // shaped like the single-q response. If Google ever changes that
        // shape, the size / JSONException checks below throw
        // BatchParseException so the registry falls through to per-text
        // fan-out within the same backend turn — Lingva keeps working
        // either way, just loses the batching speedup.
        val url = buildUrl(texts, source, target)
        // Preflight URL length. Many HTTP servers / intermediaries cap
        // request URIs around 8 KiB (default Tomcat, common nginx
        // builds). Throwing BatchParseException before the request so
        // the registry retries per-text on the same backend means an
        // OCR pass with many long groups still translates via Lingva
        // (per-text URLs are short) instead of silently dropping to
        // ML Kit on a 414 / connection reset.
        if (url.length > MAX_BATCH_URL_LENGTH) {
            throw BatchParseException(
                "Lingva batch: URL too long (${url.length} > $MAX_BATCH_URL_LENGTH chars); retrying per-text"
            )
        }
        val body = fetchBody(url)
        val top = try {
            JSONArray(body)
        } catch (e: JSONException) {
            throw BatchParseException("Lingva batch: top-level JSON parse failed", e)
        }
        if (top.length() != texts.size) {
            throw BatchParseException(
                "Lingva batch: top length ${top.length()} != input size ${texts.size}"
            )
        }
        (0 until top.length()).map { i ->
            val perQ = try {
                top.getJSONArray(i)
            } catch (e: JSONException) {
                throw BatchParseException("Lingva batch: per-q[$i] not array", e)
            }
            val chunks = try {
                perQ.getJSONArray(0)
            } catch (e: JSONException) {
                throw BatchParseException("Lingva batch: per-q[$i] missing chunks", e)
            }
            val s = reassembleChunks(chunks)
            if (s.isBlank()) throw BatchParseException("Lingva batch: blank result at index $i")
            s
        }
    }

    /** Build the gtx URL with one or more URL-encoded `&q=` params.
     *  Re-used by both single-text and batched paths. */
    private fun buildUrl(texts: List<String>, source: String, target: String): String {
        val qs = texts.joinToString(separator = "&") { t ->
            "q=" + URLEncoder.encode(t, "UTF-8")
        }
        return "https://translate.googleapis.com/translate_a/single" +
            "?client=gtx&sl=$source&tl=$target&dt=t&$qs"
    }

    private companion object {
        /** Conservative cap below the typical 8 KiB server URI limit.
         *  Leaves headroom for headers + the fixed query prefix. */
        const val MAX_BATCH_URL_LENGTH = 6 * 1024
    }

    private fun fetchBody(url: String): String {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Translate error ${response.code}")
            return response.body?.string() ?: throw IOException("Empty response")
        }
    }

    /** Reassemble the per-q chunks array (`[[translated, original, ...], ...]`)
     *  into a single string. Mirrors the original single-q loop exactly. */
    private fun reassembleChunks(chunks: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until chunks.length()) {
            val chunk = chunks.optJSONArray(i)
            if (chunk != null) sb.append(chunk.optString(0))
        }
        return sb.toString()
    }

    override fun close() {
        // Background daemon thread — see DeepLBackend.close() for the
        // NetworkOnMainThreadException rationale.
        val c = client
        Thread {
            c.dispatcher.executorService.shutdown()
            c.connectionPool.evictAll()
        }.apply { isDaemon = true; name = "LingvaBackend-close" }.start()
    }
}
