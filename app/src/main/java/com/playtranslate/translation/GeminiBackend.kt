package com.playtranslate.translation

import android.util.Log
import com.playtranslate.BuildConfig
import com.playtranslate.PtJson
import com.playtranslate.R
import com.playtranslate.net.PtHttp
import com.playtranslate.translation.llm.LlmBatchPrompt
import com.playtranslate.translation.llm.cleanLlmOutput
import com.playtranslate.translation.qwen.QwenChatTemplate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thrown when Gemini rejects the API key (HTTP 400 with API_KEY_INVALID). */
class GeminiAuthException : IOException("Invalid Gemini API key"), ClassifiedFailure {
    override val failure = BackendFailure(BackendFailureKind.AUTH, 400)
}

/** Thrown when Gemini answers 429. The waterfall falls through to the next
 *  backend as for every rate-limit exception; [failure] tells a spent
 *  daily quota from a plain rate limit (see [GeminiBackend.recordGemini429]). */
class GeminiRateLimitException(override val failure: BackendFailure) :
    IOException("Gemini rate limit exceeded"), ClassifiedFailure

/**
 * Google Gemini (`generativelanguage.googleapis.com`) backend.
 *
 * Authentication uses the `x-goog-api-key` request header, NOT the
 * `?key=…` query parameter Google's docs alternately show. Query-string
 * keys leak into OkHttp interceptor logs, Android network history,
 * crash-report URL captures, and any reverse-proxy access logs along
 * the route — header auth keeps the secret out of those surfaces.
 *
 * Unlike [OpenAiBackend], there is no on-save validation ping: the
 * lightweight free-tier endpoints Gemini exposes either cost a token or
 * have non-trivial body shapes, so an invalid key surfaces only after
 * the first failed translate. Documented asymmetry.
 *
 * Streaming note: Gemini supports `:streamGenerateContent`; deferred
 * for the same reason as [OpenAiBackend].
 *
 * Every generateContent call (a translation, a batch, a free-form answer)
 * is one round trip through [exchange], as in [OpenAiBackend].
 */
class GeminiBackend(
    // Identity is parameterized (defaults preserve the legacy singleton)
    // so the store-driven multi-instance wiring can register several
    // Gemini instances under distinct ids. See OnlineBackendFactory.
    override val id: BackendId = "gemini",
    override val displayName: String = "Gemini",
    override val priority: Int = 7,
    private val keyProvider: () -> String?,
    private val enabledProvider: () -> Boolean,
    private val modelProvider: () -> String,
    /** Extra fields inside `generationConfig`, chosen per model: the
     *  instance's thinking config as [ReasoningControls] sends it, or
     *  nothing ([OnlineBackendFactory.generationConfigExtrasFor]). Both
     *  paths merge it beside their own fields. */
    private val generationConfigExtras: (String) -> Map<String, JsonElement> = { emptyMap() },
    private val usageTracker: UsageTracker,
    private val cooldownState: CooldownState,
    private val client: OkHttpClient = defaultClient(),
) : TranslationBackend, BatchTranslator, PromptCompleter, ModelLister, KeyValidator, Cooldownable {

    override val requiresInternet: Boolean = true
    override val isDegradedFallback: Boolean = false
    override val providesLlmContext: Boolean = true
    override val qualityStars: StarRating = 4.5f

    override val status: BackendStatus
        get() {
            val key = keyProvider()
            if (key.isNullOrBlank()) {
                return BackendStatus.Account(ServiceType.GEMINI.account)
            }
            val total = usageTracker.todayTotal()
            return if (total == 0L) {
                BackendStatus.Info(R.string.tr_service_status_no_usage_today, italic = true)
            } else {
                BackendStatus.Info(
                    R.string.tr_service_status_usage_today_fmt,
                    listOf(usageTracker.todayString()),
                )
            }
        }

    override fun unavailableUntil(): Long? {
        clearCooldownIfCredentialsChanged()
        return cooldownState.unavailableUntil()
    }
    override fun unavailableDescription(): String? = cooldownState.unavailableDescription()
    override fun unavailableCause(): CooldownCause? = cooldownState.unavailableCause()
    override fun recordSuccess(attemptStartedAtMs: Long) =
        cooldownState.recordSuccess(attemptStartedAtMs)
    override fun onConnectivityRestored(): Boolean = cooldownState.onConnectivityRestored()
    override fun resetCooldown() = cooldownState.resetCooldown()

    /** Last-seen `(key | model)` fingerprint. When it changes, any
     *  persisted cooldown (e.g. a daily-quota or insufficient_quota
     *  Retry-At days from now) is cleared because it belonged to the
     *  prior credentials — the new key/model could be perfectly
     *  healthy and shouldn't be skipped by the waterfall. */
    @Volatile private var lastCredentialsFingerprint: String? = null

    private fun clearCooldownIfCredentialsChanged() {
        val current = "${keyProvider() ?: ""}|${modelProvider()}"
        val prev = lastCredentialsFingerprint
        lastCredentialsFingerprint = current
        if (prev != null && prev != current) {
            cooldownState.resetCooldown()
        }
    }

    override fun isUsable(source: String, target: String): Boolean =
        enabledProvider() && isSetUpFor(source, target)

    override fun isSetUpFor(source: String, target: String): Boolean =
        !keyProvider().isNullOrBlank()

    override suspend fun translate(text: String, source: String, target: String): String {
        val system = QwenChatTemplate.systemPrompt(source, target)
        // Online backend: {context} is affordable here (server-side prefill)
        // and is gated off for the on-device tiers. See TOKEN_CONTEXT.
        val user = QwenChatTemplate.userMessage(text, source, target, includeContext = true)
        return cleanLlmOutput(exchange(system, user, textCall))
    }

    override suspend fun translateBatch(
        texts: List<String>,
        source: String,
        target: String,
    ): List<String> {
        val system = LlmBatchPrompt.systemPrompt(source, target)
        val user = LlmBatchPrompt.userMessage(texts, source, target, includeContext = true)
        val rawJson = exchange(system, user, batchCall)
        val wrapper = try {
            PtJson.lenient.decodeFromString<BatchTranslationsWrapper>(rawJson)
        } catch (e: SerializationException) {
            throw BatchParseException("Gemini batch: response JSON parse failed", e)
        }
        val arr = wrapper.translations
            ?: throw BatchParseException("Gemini batch: missing translations[]")
        if (arr.size != texts.size) {
            throw BatchParseException(
                "Gemini batch: returned ${arr.size} translations for ${texts.size} inputs"
            )
        }
        return arr.map { cleanLlmOutput(it) }
    }

    override fun canComplete(): Boolean = enabledProvider() && !keyProvider().isNullOrBlank()

    override suspend fun complete(system: String, user: String): String =
        exchange(system, user, answerCall)

    /** How one kind of generateContent call differs from the others;
     *  everything else about the round trip is [exchange]'s. */
    private class GenerateCall(
        /** Names the call in the log. */
        val label: String,
        /** `generationConfig` fields beside the model's thinking config. */
        val generationConfig: JsonObjectBuilder.() -> Unit = {},
        /** What a 400 that is not a rejected key means, from its body. */
        val on400: (body: String) -> IOException,
        /** What a reply with no candidate text in it means. */
        val onEmptyReply: () -> IOException,
        /** An answer to a free-form prompt ([PromptCompleter]), not a
         *  translation. Three things follow, all in [exchange]: it goes out
         *  on [answerClient] (an answer's wait, not the translation calls'
         *  8 s cap), it is cancelled with its coroutine, and a failure of
         *  the transport is not counted against the service. */
        val isAnswer: Boolean = false,
    )

    private val textCall = GenerateCall(
        label = "translate",
        generationConfig = { put("temperature", 0.2) },
        on400 = { body ->
            StructuralFailureException("Gemini 400: ${body.take(200)}", httpStatusFailure(400))
        },
        onEmptyReply = {
            StructuralFailureException("No translation in Gemini response", BackendFailure.BAD_RESPONSE)
        },
    )

    private val batchCall = GenerateCall(
        label = "translate batch",
        // generationConfig.responseSchema constrains the model to emit
        // a JSON object of the exact shape we need — OpenAPI-style
        // dialect, "OBJECT"/"ARRAY"/"STRING" type names (uppercase).
        generationConfig = {
            put("temperature", 0.2)
            put("responseMimeType", "application/json")
            putJsonObject("responseSchema") {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    putJsonObject("translations") {
                        put("type", "ARRAY")
                        putJsonObject("items") { put("type", "STRING") }
                    }
                }
                putJsonArray("required") { add("translations") }
            }
        },
        // Mirrors the OpenAI batch 400 handling: a non-auth 400 on the
        // batch path is most likely the model rejecting the
        // responseSchema / responseMimeType body fields the single-text
        // path doesn't send. Surface as BatchParseException so the
        // registry retries this same Gemini backend's per-text
        // translate() (no schema) before skipping to a lower-priority
        // backend like DeepL or Lingva.
        on400 = { body ->
            Log.w(TAG, "batch 400 body=${body.take(500)} — retrying per-text on same backend")
            BatchParseException("Gemini batch 400: ${body.take(200)}")
        },
        onEmptyReply = { BatchParseException("Gemini batch: empty candidate payload") },
    )

    private val answerCall = GenerateCall(
        label = "complete",
        on400 = { body ->
            StructuralFailureException("Gemini 400: ${body.take(200)}", httpStatusFailure(400))
        },
        onEmptyReply = {
            StructuralFailureException("No answer in Gemini response", BackendFailure.BAD_RESPONSE)
        },
        isAnswer = true,
    )

    /** The client an answer goes out on: this backend's own (its pool)
     *  with an answer's wait in place of the 8 s whole-call cap, which is
     *  sized for a translation ([CALL_TIMEOUT_S]). */
    private val answerClient: OkHttpClient by lazy {
        client.newBuilder()
            .callTimeout(PromptCompleter.ANSWER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PromptCompleter.ANSWER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * One generateContent round trip: [system] and [user] out, the reply's
     * text back. The ONE place a request is built, its status read and the
     * service's standing recorded, for every kind of call ([GenerateCall]):
     *  - Gemini reports an invalid key as a 400 with
     *    `error.status = "INVALID_ARGUMENT"` and a message containing "API
     *    key not valid"; any other 400 (bad model id, malformed body) is the
     *    call's own to interpret ([GenerateCall.on400]).
     *  - 429 records a cooldown: the day's quota or a rate limit
     *    ([recordGemini429]).
     *  - A 5xx climbs the rate-limit ladder.
     *  - A failure of the transport (no connection, the call timeout)
     *    climbs the network ladder, whose first failure is forgiven, except
     *    for an answer ([GenerateCall.isAnswer]). Every exception thrown on
     *    purpose here is a [ClassifiedFailure] and has done its own
     *    bookkeeping (a misconfigured key or model must not masquerade as
     *    "Connection failed"), so that is the test, never the catch order
     *    (they are all IOExceptions).
     */
    private suspend fun exchange(system: String, user: String, call: GenerateCall): String =
        withContext(Dispatchers.IO) {
            clearCooldownIfCredentialsChanged()
            val apiKey = keyProvider()?.takeIf { it.isNotBlank() }
                ?: throw StructuralFailureException(
                    "Gemini API key not configured", BackendFailure(BackendFailureKind.AUTH),
                )
            val model = modelProvider()
            val startNs = System.nanoTime()
            Log.i(TAG, "${call.label} begin model=$model inLen=${user.length}")

            val bodyJson = buildJsonObject {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { addJsonObject { put("text", system) } }
                }
                putJsonArray("contents") {
                    addJsonObject {
                        put("role", "user")
                        putJsonArray("parts") { addJsonObject { put("text", user) } }
                    }
                }
                putJsonObject("generationConfig") {
                    call.generationConfig(this)
                    generationConfigExtras(model).forEach { (name, value) -> put(name, value) }
                }
            }.toString()

            val request = Request.Builder()
                .url("$ENDPOINT_ROOT/models/$model:generateContent")
                .addHeader("x-goog-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            try {
                val http = (if (call.isAnswer) answerClient else client).newCall(request)
                // An answer is long and its user may put it away, so its
                // coroutine's cancel cancels the call wherever the exchange
                // stands, the reply's body included ([blockingOn] over
                // [executeToEnd]); a translation is short and runs to its
                // end.
                val reply = if (call.isAnswer) blockingOn(http) { http.executeToEnd() } else http.execute()
                reply.use { response ->
                    val bodyStr = response.body.string()
                    when (response.code) {
                        400 -> {
                            if (bodyStr.contains("API_KEY_INVALID") ||
                                bodyStr.contains("API key not valid")) {
                                throw GeminiAuthException()
                            }
                            throw call.on400(bodyStr)
                        }
                        429 -> {
                            Log.w(TAG, "429 body=${bodyStr.take(500)}")
                            throw GeminiRateLimitException(recordGemini429(bodyStr))
                        }
                        else -> if (!response.isSuccessful) {
                            // The error's own words (a 503 says why Gemini is
                            // unavailable), on debug builds only.
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "${call.label} error ${response.code} body=${bodyStr.take(500)}")
                            }
                            if (response.code >= 500) {
                                cooldownState.recordLadderFailure(
                                    CooldownLadder.RateLimit, "Server error",
                                    CooldownCause.SERVER_ERROR,
                                )
                            }
                            throw StructuralFailureException(
                                "Gemini error ${response.code}", httpStatusFailure(response.code),
                            )
                        }
                    }
                    if (bodyStr.isEmpty()) {
                        throw StructuralFailureException(
                            "Empty response from Gemini", BackendFailure.BAD_RESPONSE,
                        )
                    }
                    val parsed = PtJson.lenient.decodeFromString<GeminiResponse>(bodyStr)
                    val text = parsed.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
                        ?: throw call.onEmptyReply()
                    parsed.usageMetadata?.let {
                        usageTracker.addTokens(it.promptTokenCount, it.candidatesTokenCount)
                    }
                    val totalMs = (System.nanoTime() - startNs) / 1_000_000
                    Log.i(TAG, "${call.label} ok totalMs=$totalMs outLen=${text.length}")
                    text
                }
            } catch (e: IOException) {
                if (e !is ClassifiedFailure && !call.isAnswer) {
                    cooldownState.recordNetworkFailure("Connection failed")
                }
                throw e
            }
        }

    /**
     * Fetches the list of models the configured API key can call.
     * Filters to models whose `supportedGenerationMethods` include
     * `generateContent` (skips embedding-only / count-token-only
     * entries) and strips the `models/` prefix off the canonical name
     * so the picker can show bare ids like `gemini-2.5-flash`.
     *
     * Throws [IOException] on any network / parse failure — the picker
     * catches it and falls back to a default minimum so the user
     * always has at least one selectable option.
     */
    /**
     * Verify the API key against `/v1beta/models`. Cheap auth-only call
     * (no tokens billed). Used by the settings-save path to block on
     * known-invalid keys instead of saving them.
     *
     * [overrideKey] lets the caller pass a typed-but-unsaved key.
     * Gemini has no configurable base URL, so [overrideBaseUrl] is ignored.
     */
    override suspend fun validateKey(overrideKey: String?, overrideBaseUrl: String?): KeyStatus = withContext(Dispatchers.IO) {
        val apiKey = (overrideKey ?: keyProvider())?.takeIf { it.isNotBlank() }
            ?: return@withContext KeyStatus.Invalid("API key blank")
        val request = Request.Builder()
            .url("$ENDPOINT_ROOT/models")
            .addHeader("x-goog-api-key", apiKey)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code in 200..299 -> KeyStatus.Ok
                    response.code == 400 -> {
                        // Gemini reports invalid keys as 400 INVALID_ARGUMENT
                        // with "API key not valid" / "API_KEY_INVALID" in the
                        // body — distinguish from other 400s (which would be
                        // backend issues we shouldn't block on).
                        val body = response.body.string()
                        if (body.contains("API_KEY_INVALID") ||
                            body.contains("API key not valid")) {
                            KeyStatus.Invalid("HTTP 400 (invalid key)")
                        } else {
                            KeyStatus.Unreachable
                        }
                    }
                    response.code == 401 || response.code == 403 ->
                        KeyStatus.Invalid("HTTP ${response.code}")
                    else -> KeyStatus.Unreachable
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            KeyStatus.Unreachable
        }
    }

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val apiKey = keyProvider()?.takeIf { it.isNotBlank() }
            ?: throw IOException("Gemini API key not configured")
        val request = Request.Builder()
            .url("$ENDPOINT_ROOT/models")
            .addHeader("x-goog-api-key", apiKey)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Gemini /models error ${response.code}")
            }
            val body = response.body.string()
            val parsed = PtJson.lenient.decodeFromString<GeminiModelsResponse>(body)
            val sorted = parsed.models
                .asSequence()
                .filter { it.supportedGenerationMethods.contains("generateContent") }
                .map { it.name.removePrefix("models/") }
                .filter { it.isNotBlank() }
                .filterNot { id -> EXCLUDED_MODEL_PATTERN.containsMatchIn(id) }
                // Sort by extracted version number desc, then by name asc
                // within the same version. Result: the newest family's
                // smallest/cheapest stable variant comes first
                // (gemini-2.5-flash before gemini-2.5-pro; 2.5 family
                // before 2.0; etc.).
                .sortedWith(compareByDescending<String> { extractVersion(it) }.thenBy { it })
                .toList()
            Log.i(TAG, "listModels returned ${sorted.size} entries (sorted top → bottom):")
            sorted.forEachIndexed { i, m -> Log.i(TAG, "  [$i] $m") }
            sorted
        }
    }

    /** Extract the version number from a Gemini model id ("gemini-2.5-flash" → 2.5).
     *  Returns -1.0 for ids that don't match the standard pattern so they
     *  sink to the bottom of the sort. */
    private fun extractVersion(id: String): Double {
        val match = VERSION_PATTERN.find(id) ?: return -1.0
        return match.value.toDoubleOrNull() ?: -1.0
    }

    override fun close() {
        val c = client
        Thread {
            c.dispatcher.executorService.shutdown()
            c.connectionPool.evictAll()
        }.apply { isDaemon = true; name = "GeminiBackend-close" }.start()
    }

    @Serializable
    private data class GeminiResponse(
        val candidates: List<Candidate> = emptyList(),
        val usageMetadata: UsageMetadata? = null,
    ) {
        @Serializable
        data class Candidate(val content: Content? = null)
        @Serializable
        data class Content(val parts: List<Part> = emptyList())
        @Serializable
        data class Part(val text: String = "")
        @Serializable
        data class UsageMetadata(
            val promptTokenCount: Long = 0,
            val candidatesTokenCount: Long = 0,
        )
    }

    @Serializable
    private data class BatchTranslationsWrapper(
        val translations: List<String>? = null,
    )

    /**
     * Thin wrapper: parse → route to the right [CooldownState] entry, and
     * return what this 429 was, for the exception that reports it. Pure
     * parsing lives in [parseGemini429Body] so unit tests can exercise it
     * without constructing a full backend.
     */
    private fun recordGemini429(body: String): BackendFailure {
        val parsed = parseGemini429Body(body)
        if (parsed != null) {
            // parseGemini429Body's two descriptions are file-local
            // constants; the daily-quota one maps to the QUOTA message
            // class (resets at midnight PT), everything else is transient.
            val daily = parsed.second == "Daily quota used"
            val cause = if (daily) CooldownCause.DAILY_QUOTA else CooldownCause.RATE_LIMITED
            cooldownState.recordParsedFailure(parsed.first, parsed.second, cause)
            return BackendFailure(
                if (daily) BackendFailureKind.DAILY_QUOTA else BackendFailureKind.RATE_LIMITED,
                429,
            )
        }
        cooldownState.recordLadderFailure(CooldownLadder.RateLimit, "Rate limited")
        return BackendFailure(BackendFailureKind.RATE_LIMITED, 429)
    }

    @Serializable
    private data class GeminiModelsResponse(
        val models: List<ModelEntry> = emptyList(),
    ) {
        @Serializable
        data class ModelEntry(
            val name: String = "",
            val supportedGenerationMethods: List<String> = emptyList(),
        )
    }

    companion object {
        private const val TAG = "Gemini"

        private const val ENDPOINT_ROOT =
            "https://generativelanguage.googleapis.com/v1beta"

        /** Models excluded from the picker — preview / experimental /
         *  tuning / image-generation / thinking variants. Users who
         *  really want one can still type its id via "Custom…". */
        private val EXCLUDED_MODEL_PATTERN = Regex(
            "(-exp|-preview|-tuning|-thinking|-image-generation|-embedding|^embedding-|^aqa\$|-aqa\$)",
            RegexOption.IGNORE_CASE,
        )

        /** Pattern for the "X.Y" version number in a Gemini model id.
         *  Matches "2.5" in "gemini-2.5-flash". */
        private val VERSION_PATTERN = Regex("""\d+\.\d+""")

        /**
         * Ceiling on a WHOLE call — DNS, connect, TLS, request body, server
         * processing, response body — as opposed to the per-timeout budgets
         * below, which each bound one phase and so can't bound the sum.
         *
         * The failure this closes: a press-and-hold one-shot paints skeleton
         * placeholders, then awaits the translation before it can paint the
         * result. Gemini answered a 32-char request in 22,976 ms of pure TTFB
         * (field trace 2026-08-12) — a 200 with a perfectly good body, just far
         * too late. The request body had gone out in 1 ms and the read timeout
         * bounds each individual read, so nothing capped the wait; the user
         * watched the shimmer for ~20 s, released the hold, and the answer
         * landed 2.4 s later into a cancelled cycle and was discarded.
         *
         * 8 s is ~6.5x the measured p95 (same trace: n=84, p50 799 ms, p90
         * 1,097 ms, p95 1,246 ms, slowest healthy call 1,540 ms), so it can't
         * fire on a merely-sluggish call. Blowing it throws InterruptedIOException
         * — an IOException, so the waterfall falls through to the next backend
         * and the on-device tier answers instead of the user getting nothing.
         * [CooldownState.recordNetworkFailure] forgives an isolated one, so a
         * single stall costs one fallback translation rather than a cooldown.
         *
         * Applies to [validateKey] / [listModels] too (same client); both are
         * small GETs that finish far inside it. Deliberately NOT mirrored onto
         * [OpenAiBackend], whose long-passage and self-hosted-LAN calls
         * legitimately run 15-20 s.
         */
        private const val CALL_TIMEOUT_S = 8L

        private fun defaultClient(): OkHttpClient = PtHttp.clientBuilder()
            .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // Happy Eyeballs (RFC 8305) — race v4 and v6 connect attempts
            // in parallel, take whichever responds first. Critical for
            // networks where DNS returns AAAA records but v6 routing is
            // broken (very common on home wifi). Default-on in OkHttp
            // 5.0.0+; the explicit call here documents the intent.
            .fastFallback(true)
            // Logs per-phase latency (DNS, connect, TLS, ttfb, total) so
            // the "cold first call is slow" question is diagnosable from
            // logcat without an HTTP proxy.
            .eventListenerFactory(HttpTimingLogger.factory(TAG))
            .build()
    }
}

/**
 * Parse Gemini's 429 body into a `(retryAt, description)` pair, or
 * null when the body provides no usable signal (caller falls back to
 * the ladder).
 *
 * Decision tree:
 *  - `QuotaFailure.violations[].quotaId` matching `/Per(Day|Daily)/i`
 *    → `nextMidnightPacific(now)` / "Daily quota used".
 *  - `RetryInfo.retryDelay` ("34s" / "0.5s" / etc.) →
 *    `now + parsedDelay` / "Rate limited".
 *  - Anything else (including the minimal
 *    `{code:429,status:RESOURCE_EXHAUSTED}` shape with no `details[]`)
 *    → null.
 *
 * The PerDay/PerMinute substring convention is community-observed and
 * not formally spec'd; the regex is loose and the ladder fallback at
 * the call site covers anything we miss.
 *
 * `internal` so unit tests in this module can exercise the parser
 * without constructing a full [GeminiBackend].
 */
internal fun parseGemini429Body(
    body: String,
    now: Long = System.currentTimeMillis(),
): Pair<Long, String>? {
    val envelope = try {
        PtJson.lenient.decodeFromString<GeminiErrorEnvelope>(body)
    } catch (e: SerializationException) {
        return null
    }
    val details = envelope.error?.details ?: return null

    for (detail in details) {
        val type = (detail["@type"] as? JsonPrimitive)?.contentOrNull ?: continue
        if (!type.endsWith("QuotaFailure")) continue
        val violations = detail["violations"] as? JsonArray ?: continue
        for (v in violations) {
            val quotaId = ((v as? JsonObject)?.get("quotaId") as? JsonPrimitive)?.contentOrNull ?: continue
            if (PER_DAY_QUOTA_PATTERN.containsMatchIn(quotaId)) {
                return nextMidnightPacific(now) to "Daily quota used"
            }
        }
    }
    for (detail in details) {
        val type = (detail["@type"] as? JsonPrimitive)?.contentOrNull ?: continue
        if (!type.endsWith("RetryInfo")) continue
        val delay = (detail["retryDelay"] as? JsonPrimitive)?.contentOrNull ?: continue
        val ms = GoDuration.parse(delay)?.inWholeMilliseconds ?: continue
        return (now + ms) to "Rate limited"
    }
    return null
}

@Serializable
internal data class GeminiErrorEnvelope(val error: GeminiErrorBody? = null) {
    @Serializable
    data class GeminiErrorBody(val details: List<JsonObject>? = null)
}

/** Loose pattern for Gemini's community-observed per-day quotaId
 *  substring conventions. */
internal val PER_DAY_QUOTA_PATTERN = Regex("""Per(Day|Daily)""", RegexOption.IGNORE_CASE)
