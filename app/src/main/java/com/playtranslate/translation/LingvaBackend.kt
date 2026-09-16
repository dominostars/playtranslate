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

/** Thrown when Google's endpoint rate-limits or blocks the caller
 *  (HTTP 429 / 403). The cooldown is recorded at the throw site; the
 *  registry treats the throw as "this backend failed", same as
 *  [GeminiRateLimitException]. [httpCode] rides along so the registry's
 *  diagnostics ring can record the status without touching the message. */
class LingvaRateLimitException(val httpCode: Int) : IOException("Lingva rate limited (HTTP $httpCode)")

/**
 * "Lingva" backend — historically a Lingva-proxy translator, currently
 * pointed at Google's undocumented `translate.googleapis.com/translate_a/t`
 * endpoint directly for lower latency. The class name intentionally
 * matches the user-facing brand and the future intent (we may switch
 * back to a real Lingva instance), even though today the implementation
 * hits Google.
 *
 * No API key required.
 *
 * Endpoint choice (probed against Google 2026-09-16):
 *  - `translate_a/t` answers a request with a JSON array of strings, one
 *    per `q=` param in request order, so one GET carries a whole page.
 *    `translate_a/single`, the previous endpoint, translates only the
 *    FIRST of several `q=` params whatever the client id, so the batch
 *    convention this class used to parse is not honoured; every
 *    multi-text page fell through to the registry's per-text retry.
 *  - `client=dict-chrome-ex` (the Google Translate Chrome extension's
 *    id) replaces `client=gtx`, the id every scraper library sends and
 *    the first one Google's abuse scoring refuses: since 2026-09-14
 *    networks trip a per-IP HTML "Sorry" 429 on gtx while the other
 *    ids answer from the same address. The two ids returned identical
 *    translations on every probe.
 *  - The path+query cap is 16384 bytes (measured to the byte: 16384
 *    answers, 16385 is a 400). POST with the `q=` params as a form body
 *    lifts it (63 KB of body answered) but the fixed params must stay in
 *    the query, and a page never approaches the GET cap, so GET stays.
 *
 * [enabledProvider] reflects the user's explicit on/off state from
 * Settings — the registry's waterfall skips this backend when disabled.
 *
 * Cooldown: Google rate-limits per IP with plain 429s (observed in the
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
    // expiring, by the registry recording a waterfall win, by the
    // services-page toggle (resetCooldown), or — for a connection-caused
    // one — by the device regaining a network (onConnectivityRestored).
    override fun unavailableUntil(): Long? = cooldownState?.unavailableUntil()
    override fun unavailableDescription(): String? = cooldownState?.unavailableDescription()
    override fun unavailableCause(): CooldownCause? = cooldownState?.unavailableCause()
    override fun recordSuccess(attemptStartedAtMs: Long) {
        cooldownState?.recordSuccess(attemptStartedAtMs)
    }
    override fun onConnectivityRestored(): Boolean =
        cooldownState?.onConnectivityRestored() ?: false
    override fun resetCooldown() {
        cooldownState?.resetCooldown()
    }

    override suspend fun translate(text: String, source: String, target: String): String =
        withContext(Dispatchers.IO) {
            try {
                val body = fetchBody(urlPrefix(source, target) + "&" + encodeQ(text))
                val result = try {
                    parseStrings(body, expected = 1)[0]
                } catch (e: JSONException) {
                    throw StructuralFailureException("Lingva: unexpected response shape", e)
                }
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
        } catch (e: LingvaRateLimitException) { throw e }
        catch (e: StructuralFailureException) {
            // A response that arrived but could not be used (shape drift,
            // a blank entry, a 4xx): categorized at the throw site,
            // nothing recorded. Must be rethrown before the IOException
            // catch below: it IS an IOException subclass.
            throw e
        }
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
        // One `&q=` per input; the endpoint answers with one string per
        // q in request order (a lone q gets a one-element array, so a
        // one-text chunk parses like any other). If Google ever changes
        // that shape, the size / type checks in parseStrings throw
        // StructuralFailureException, NOT BatchParseException: the
        // registry answers the latter with a per-text retry on the same
        // backend, which is right for the LLM backends (a batch prompt's
        // JSON can be malformed while the per-text prompt is a different
        // request) but wrong here, where translate() hits the same
        // endpoint with the same parser and would fail N more times the
        // same way. A structural throw moves the FULL pending list to
        // the next backend at the cost of the one request already sent.
        //
        // URL length is a packing boundary, not a failure. Google's
        // front end caps path+query at 16 KiB (measured), and a
        // percent-encoded CJK character costs nine URL bytes, so a very
        // text-heavy page can overrun MAX_BATCH_URL_LENGTH. The old
        // preflight threw BatchParseException here and the registry's
        // per-text retry then fanned the whole page out as N parallel
        // requests against the same per-IP limiter the batching exists
        // to protect. Instead the pending texts are packed greedily into
        // the fewest chunks that each fit, and the page costs
        // ceil(bytes / cap) requests instead of N.
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
            out += parseChunk(body, expected = range.last - range.first + 1, offset = range.first)
        }
        return out
    }

    /** Parse one chunk's body into exactly [expected] non-blank strings.
     *  A shape failure or a blank entry is [StructuralFailureException]
     *  (see [translateBatchInner] for why not [BatchParseException]).
     *  [offset] is the page index of the chunk's first text, so a
     *  diagnostic names the page position rather than the chunk-local
     *  one. */
    private fun parseChunk(body: String, expected: Int, offset: Int): List<String> {
        val strings = try {
            parseStrings(body, expected)
        } catch (e: JSONException) {
            throw StructuralFailureException("Lingva batch: chunk at index $offset: ${e.message}", e)
        }
        strings.forEachIndexed { i, s ->
            if (s.isBlank()) throw StructuralFailureException("Lingva batch: blank result at index ${offset + i}")
        }
        return strings
    }

    /** The `translate_a/t` response shape: a JSON array of exactly one
     *  string per `q=` param, in request order (`["Hello","world"]`).
     *  Anything else — a different length, a non-string entry (the
     *  `[text, detectedLang]` pairs `sl=auto` would produce, which this
     *  class never sends), or a non-array — is a [JSONException] for the
     *  caller to class. Blank entries are returned as-is; the caller
     *  decides what a blank means. */
    private fun parseStrings(body: String, expected: Int): List<String> {
        val top = JSONArray(body)
        if (top.length() != expected) {
            throw JSONException("top length ${top.length()} != q count $expected")
        }
        return List(expected) { i ->
            top.get(i) as? String ?: throw JSONException("entry $i is not a string")
        }
    }

    /** Everything before the `&q=` params. Callers append one [encodeQ]
     *  per text. */
    private fun urlPrefix(source: String, target: String): String =
        "https://translate.googleapis.com/translate_a/t" +
            "?client=dict-chrome-ex&sl=$source&tl=$target"

    private fun encodeQ(text: String): String = "q=" + URLEncoder.encode(text, "UTF-8")

    internal companion object {
        /** Cap on a batched request's full URL. Google's front end
         *  accepts a path+query of exactly 16384 bytes and answers 400 one
         *  byte past it (measured 2026-09-16; the count is independent of
         *  how many `q=` params make it up). 12 KiB leaves a quarter of
         *  that in hand while holding ~1,300 percent-encoded CJK
         *  characters, more than a screen. The batched path packs its
         *  `&q=` params so each request's URL stays at or under this; a
         *  lone param that can't fit is sent alone. */
        const val MAX_BATCH_URL_LENGTH = 12 * 1024

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
                // 403 rides with 429: the endpoint is keyless, so an
                // auth-flavored status can't mean "fix your credentials"
                // — Google's harder abuse blocks are the only thing it
                // can be, and those want the same back-off, not a retry
                // per cycle.
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
                    // Remaining 4xx (bad params; the 400 Google answers
                    // for a URL past its cap): deterministic rejection,
                    // not provider health — no cooldown, mirroring the
                    // other backends' structural path.
                    throw StructuralFailureException("Lingva error ${response.code}")
            }
            return response.body.string()
        }
    }

    /** Header-level detail on a refusal. PRIVACY: never log the URL
     *  (its `q=` params carry the captured text) or any body content
     *  (Google's 403 block page echoes the full request URL, text
     *  included). Content-Type + declared length still distinguish a
     *  bare API error from an HTML block page — which tells us how hard
     *  the block is — without reading a byte of the body. */
    private fun logHttpFailure(code: Int, retryAfter: String?, response: okhttp3.Response) {
        android.util.Log.w(
            "Lingva",
            "translate_a/t $code: retryAfter=${retryAfter ?: "none"}" +
                " contentType=${response.header("Content-Type") ?: "?"}" +
                " contentLength=${response.body.contentLength()}"
        )
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
