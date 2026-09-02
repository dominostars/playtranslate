package com.playtranslate.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 *  [GeminiRateLimitException]. [httpCode] rides along so the registry's
 *  diagnostics ring can record the status without touching the message. */
class LingvaRateLimitException(val httpCode: Int) : IOException("Lingva rate limited (HTTP $httpCode)")

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
    override fun unavailableCause(): CooldownCause? = cooldownState?.unavailableCause()
    override fun recordSuccess(attemptStartedAtMs: Long) {
        cooldownState?.recordSuccess(attemptStartedAtMs)
    }

    override suspend fun translate(text: String, source: String, target: String): String =
        withContext(Dispatchers.IO) {
            try {
                val url = buildUrl(listOf(text), source, target)
                val body = fetchBody(url)
                val result = parseSingleBody(body)
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
            // Structural (response shape drift) — the registry retries
            // per-text on this same backend, so the provider isn't
            // unhealthy and nothing is recorded. Must be rethrown before
            // the IOException catch below: it IS an IOException subclass.
            throw e
        } catch (e: LingvaRateLimitException) { throw e }
        catch (e: StructuralFailureException) { throw e }
        catch (e: IOException) {
            cooldownState?.recordNetworkFailure("Connection failed")
            throw e
        }
    }

    private suspend fun translateBatchInner(
        texts: List<String>,
        source: String,
        target: String,
    ): List<String> {
        // gtx is undocumented; the multi-q convention used by tools like
        // translate-shell and LunaTranslator is to repeat &q= per input
        // and treat the top-level array as a list of per-q results, each
        // shaped like the single-q response. If Google ever changes that
        // shape, the size / JSONException checks in parseBatchBody throw
        // BatchParseException so the registry falls through to per-text
        // fan-out within the same backend turn — Lingva keeps working
        // either way, just loses the batching speedup.
        //
        // URL length is a packing boundary, not a failure. Many HTTP
        // servers / intermediaries cap request URIs around 8 KiB, and a
        // percent-encoded CJK character costs nine URL bytes, so a
        // text-heavy Japanese screen overruns MAX_BATCH_URL_LENGTH
        // easily. The old preflight threw BatchParseException here and
        // the registry's per-text retry then fanned the whole page out
        // as N parallel requests against the same per-IP limiter the
        // batching exists to protect. Now the pending texts are packed
        // greedily into the fewest chunks that each fit, and the page
        // costs ceil(bytes / cap) requests instead of N.
        //
        // Chunks go out SEQUENTIALLY and the first failure ends the
        // sequence: a 429 on chunk one must not be followed by chunk two
        // (parallel chunks would re-create the burst in miniature), and
        // a capture cancelled mid-sequence stops before its next send.
        // The batch contract stays all-or-nothing, so a failure on a
        // later chunk discards the earlier chunks' answers and the
        // registry moves the FULL pending list to the next backend.
        // Accepted: it costs Lingva-quality output on exactly the pass
        // that trips the limiter, the fallback tier is offline so the
        // discard spends no quota, and the cooldown keeps later passes
        // off Lingva anyway. A text that alone overruns the cap is sent
        // as its own single-q chunk — the identical URL the per-text
        // path would build for it — and the server decides.
        //
        // A one-text chunk (that lone oversize text, or a one-item
        // remainder after packing) carries a single q, and gtx answers
        // a single q with the SINGLE-q shape, not a one-element per-q
        // list: it is parsed by translate()'s parser, with its failures
        // classed the way this batch path classes them. A Codex native
        // review caught the multi-q parse being applied to it, which
        // would have turned every such page into the per-text retry
        // this change exists to remove.
        val prefix = urlPrefix(source, target)
        val params = texts.map { encodeQ(it) }
        val chunks = packChunks(prefix.length, params.map { it.length }, MAX_BATCH_URL_LENGTH)
        if (chunks.size > 1) {
            android.util.Log.d(
                "Lingva",
                "batch: ${texts.size} texts packed into ${chunks.size} requests (url cap $MAX_BATCH_URL_LENGTH)"
            )
        }
        val out = ArrayList<String>(texts.size)
        for ((k, range) in chunks.withIndex()) {
            if (k > 0) currentCoroutineContext().ensureActive()
            val url = prefix + "&" + params.subList(range.first, range.last + 1).joinToString("&")
            val body = fetchBody(url)
            val size = range.last - range.first + 1
            out += if (size == 1) parseSingleChunk(body, offset = range.first)
            else parseBatchBody(body, expected = size, offset = range.first)
        }
        return out
    }

    /** Single-q response shape: `[[["translated","original",...], ...], null, "ja", ...]`
     *  — the chunks array sits at index 0 of the top-level array, which
     *  is NOT a per-q list. May be blank; the caller decides what a
     *  blank means ([translate] makes it structural, the batch path a
     *  parse failure), and a JSONException is left to the caller for
     *  the same reason. */
    private fun parseSingleBody(body: String): String =
        reassembleChunks(JSONArray(body).getJSONArray(0))

    /** A one-text chunk of the batched path: the single-q shape, with
     *  failures classed as the batch classes them ([BatchParseException]
     *  so the registry retries per-text on this backend). [offset] is
     *  the text's page index. */
    private fun parseSingleChunk(body: String, offset: Int): List<String> {
        val s = try {
            parseSingleBody(body)
        } catch (e: JSONException) {
            throw BatchParseException("Lingva batch: single-q[$offset] parse failed", e)
        }
        if (s.isBlank()) throw BatchParseException("Lingva batch: blank result at index $offset")
        return listOf(s)
    }

    /** Parse one multi-q response body into exactly [expected] strings.
     *  [offset] is the global index of this chunk's first text, so a
     *  diagnostic names the page position rather than the chunk-local
     *  one. */
    private fun parseBatchBody(body: String, expected: Int, offset: Int): List<String> {
        val top = try {
            JSONArray(body)
        } catch (e: JSONException) {
            throw BatchParseException("Lingva batch: top-level JSON parse failed", e)
        }
        if (top.length() != expected) {
            throw BatchParseException(
                "Lingva batch: top length ${top.length()} != input size $expected"
            )
        }
        return (0 until top.length()).map { i ->
            val perQ = try {
                top.getJSONArray(i)
            } catch (e: JSONException) {
                throw BatchParseException("Lingva batch: per-q[${offset + i}] not array", e)
            }
            val chunks = try {
                perQ.getJSONArray(0)
            } catch (e: JSONException) {
                throw BatchParseException("Lingva batch: per-q[${offset + i}] missing chunks", e)
            }
            val s = reassembleChunks(chunks)
            if (s.isBlank()) throw BatchParseException("Lingva batch: blank result at index ${offset + i}")
            s
        }
    }

    /** Build the gtx URL with one or more URL-encoded `&q=` params.
     *  Single-text path; the batched path packs [encodeQ] params itself. */
    private fun buildUrl(texts: List<String>, source: String, target: String): String =
        urlPrefix(source, target) + "&" + texts.joinToString(separator = "&") { encodeQ(it) }

    private fun urlPrefix(source: String, target: String): String =
        "https://translate.googleapis.com/translate_a/single" +
            "?client=gtx&sl=$source&tl=$target&dt=t"

    private fun encodeQ(text: String): String = "q=" + URLEncoder.encode(text, "UTF-8")

    internal companion object {
        /** Conservative cap below the typical 8 KiB server URI limit.
         *  Leaves headroom for headers + the fixed query prefix. The
         *  batched path packs its `&q=` params so each request's URL
         *  stays at or under this; a lone param that can't fit is sent
         *  alone. Never measured against Google's own front end — that
         *  probe is still owed, and a higher measured limit would simply
         *  make chunking rarer. */
        const val MAX_BATCH_URL_LENGTH = 6 * 1024

        /**
         * Greedy first-fit packing of pre-encoded `q=` params into the
         * fewest contiguous chunks whose full URL fits [maxUrlLength].
         * Each param costs its own length plus one for the joining `&`
         * (the prefix carries the query `?` and the last fixed param, so
         * every q is `&`-joined). Order is preserved — the registry
         * recombines results positionally. A param whose lone URL would
         * still exceed the cap gets a chunk of its own rather than
         * failing the pack: the caller sends it and the server answers.
         * Pure and index-based so it is unit-testable without a client.
         */
        fun packChunks(prefixLength: Int, paramLengths: List<Int>, maxUrlLength: Int): List<IntRange> {
            if (paramLengths.isEmpty()) return emptyList()
            val chunks = ArrayList<IntRange>()
            var start = 0
            var urlLength = prefixLength
            for (i in paramLengths.indices) {
                val cost = 1 + paramLengths[i]
                if (i > start && urlLength + cost > maxUrlLength) {
                    chunks += start until i
                    start = i
                    urlLength = prefixLength
                }
                urlLength += cost
            }
            chunks += start until paramLengths.size
            return chunks
        }

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
                    val retryAfter = response.header("Retry-After")
                    logHttpFailure(response.code, retryAfter, response)
                    cooldownState?.recordRetryAfterFailure(retryAfter, "Rate limited")
                    throw LingvaRateLimitException(response.code)
                }
                response.code >= 500 -> {
                    logHttpFailure(response.code, null, response)
                    cooldownState?.recordLadderFailure(
                        CooldownLadder.RateLimit, "Server error",
                        CooldownCause.SERVER_ERROR,
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

    /** Header-level detail on a gtx refusal. PRIVACY: never log the URL
     *  (its `q=` params carry the captured text) or any body content
     *  (Google's 403 block page echoes the full request URL, text
     *  included). Content-Type + declared length still distinguish a
     *  bare API error from an HTML block page — which tells us how hard
     *  the block is — without reading a byte of the body. */
    private fun logHttpFailure(code: Int, retryAfter: String?, response: okhttp3.Response) {
        android.util.Log.w(
            "Lingva",
            "gtx $code: retryAfter=${retryAfter ?: "none"}" +
                " contentType=${response.header("Content-Type") ?: "?"}" +
                " contentLength=${response.body.contentLength()}"
        )
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
