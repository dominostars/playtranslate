package com.playtranslate.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.playtranslate.net.PtHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Thrown when the gtx endpoint rate-limits or blocks the caller
 *  (HTTP 429 / 403). The cooldown is recorded at the throw site; the
 *  registry treats the throw as "this backend failed", same as
 *  [GeminiRateLimitException]. */
class LingvaRateLimitException(code: Int) : IOException("Lingva rate limited (HTTP $code)")

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
 *
 * Cooldown: gtx rate-limits per IP with plain 429s (observed in the
 * field: sustained live-mode cadence trips it, and continued retries
 * every capture cycle keep the limiter hot indefinitely). [Cooldownable]
 * participation means the waterfall stops hammering a limited endpoint
 * — which is also what lets the limiter recover without the user
 * restarting anything.
 */
class LingvaBackend(
    // Identity is parameterized (defaults preserve the legacy singleton)
    // so the store-driven multi-instance wiring can register a re-added
    // Lingva instance under a fresh id. See OnlineBackendFactory.
    override val id: BackendId = "lingva",
    override val displayName: String = "Lingva",
    override val priority: Int = 20,
    private val enabledProvider: () -> Boolean,
    /** Null (legacy/test constructors) = no cooldown participation:
     *  [unavailableUntil] stays null and failures aren't recorded. The
     *  factory always passes a real instance. */
    private val cooldownState: CooldownState? = null,
    private val client: OkHttpClient = defaultClient(),
) : TranslationBackend, BatchTranslator, Cooldownable {

    override val requiresInternet: Boolean = true
    override val isDegradedFallback: Boolean = false
    override val qualityStars: StarRating = 4.0f

    /** Constant: Lingva has no key to hold, so it never leaves this state. */
    override val status: BackendStatus = BackendStatus.Account(ServiceType.LINGVA.account)

    override fun isUsable(source: String, target: String): Boolean = enabledProvider()

    // No credentials-change escape hatch here (nothing to reconfigure,
    // unlike Gemini/OpenAI's fingerprint clear): a cooldown ends by
    // expiring or by the registry recording a waterfall win.
    override fun unavailableUntil(): Long? = cooldownState?.unavailableUntil()
    override fun unavailableDescription(): String? = cooldownState?.unavailableDescription()
    override fun recordSuccess(attemptStartedAtMs: Long) {
        cooldownState?.recordSuccess(attemptStartedAtMs)
    }

    override suspend fun translate(text: String, source: String, target: String): String =
        withContext(Dispatchers.IO) {
            try {
                val url = buildUrl(listOf(text), source, target)
                val body = fetchBody(url)
                // Single-q response shape: [[["translated","original",...], ...], null, "ja", ...]
                // The chunks array is at index 0 of the top-level array.
                val top = JSONArray(body)
                val chunks = top.getJSONArray(0)
                val result = reassembleChunks(chunks)
                if (result.isBlank()) throw StructuralFailureException("Blank translation in response")
                result
            } catch (e: LingvaRateLimitException) { throw e }
            catch (e: StructuralFailureException) { throw e }
            catch (e: IOException) {
                // True transport failure (connect/DNS/timeout/body read) —
                // the typed throws above are all recorded (or deliberately
                // not) at their categorization site, so anything reaching
                // this catch is a real connection problem. First one is
                // forgiven inside recordNetworkFailure.
                cooldownState?.recordNetworkFailure("Connection failed")
                throw e
            }
        }

    override suspend fun translateBatch(
        texts: List<String>,
        source: String,
        target: String,
    ): List<String> = withContext(Dispatchers.IO) {
        try {
            translateBatchInner(texts, source, target)
        } catch (e: BatchParseException) {
            // Structural (URL too long, response shape drift) — the
            // registry retries per-text on this same backend, so the
            // provider isn't unhealthy and nothing is recorded. Must be
            // rethrown before the IOException catch below: it IS an
            // IOException subclass.
            throw e
        } catch (e: LingvaRateLimitException) { throw e }
        catch (e: StructuralFailureException) { throw e }
        catch (e: IOException) {
            cooldownState?.recordNetworkFailure("Connection failed")
            throw e
        }
    }

    private fun translateBatchInner(
        texts: List<String>,
        source: String,
        target: String,
    ): List<String> {
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
        return (0 until top.length()).map { i ->
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

        fun defaultClient(): OkHttpClient = PtHttp.clientBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private fun fetchBody(url: String): String {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            when {
                // 403 rides with 429: gtx is keyless, so an auth-flavored
                // status can't mean "fix your credentials" — Google's
                // harder abuse blocks are the only thing it can be, and
                // those want the same back-off, not a retry per cycle.
                response.code == 429 || response.code == 403 -> {
                    cooldownState?.recordRetryAfterFailure(
                        response.header("Retry-After"), "Rate limited"
                    )
                    throw LingvaRateLimitException(response.code)
                }
                response.code >= 500 -> {
                    cooldownState?.recordLadderFailure(
                        CooldownLadder.RateLimit, "Server error"
                    )
                    throw StructuralFailureException("Lingva error ${response.code}")
                }
                !response.isSuccessful ->
                    // Remaining 4xx (bad params, 414 the preflight missed):
                    // deterministic rejection, not provider health — no
                    // cooldown, mirroring the other backends' structural path.
                    throw StructuralFailureException("Lingva error ${response.code}")
            }
            return response.body.string()
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
