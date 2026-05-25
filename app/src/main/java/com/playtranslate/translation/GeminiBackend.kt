package com.playtranslate.translation

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.playtranslate.translation.llm.LlmBatchPrompt
import com.playtranslate.translation.llm.cleanLlmOutput
import com.playtranslate.translation.qwen.QwenChatTemplate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thrown when Gemini rejects the API key (HTTP 400 with API_KEY_INVALID). */
class GeminiAuthException : IOException("Invalid Gemini API key")

/** Thrown when Gemini rate-limits the call (HTTP 429). Falls through
 *  silently like the other backends' rate-limit exceptions. */
class GeminiRateLimitException : IOException("Gemini rate limit exceeded")

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
 */
class GeminiBackend(
    private val keyProvider: () -> String?,
    private val enabledProvider: () -> Boolean,
    private val modelProvider: () -> String,
    private val usageTracker: UsageTracker,
    private val cooldownState: CooldownState,
    private val client: OkHttpClient = defaultClient(),
) : TranslationBackend, BatchTranslator, ModelLister, KeyValidator, Cooldownable {

    override val id: BackendId = "gemini"
    override val displayName: String = "Gemini"
    override val priority: Int = 7
    override val requiresInternet: Boolean = true
    override val isDegradedFallback: Boolean = false
    override val qualityStars: StarRating = 4.5f

    private val gson = Gson()

    override val status: BackendStatus
        get() {
            val key = keyProvider()
            if (key.isNullOrBlank()) {
                return BackendStatus.Info("API Key Required", Tone.Neutral)
            }
            val total = usageTracker.todayTotal()
            return if (total == 0L) {
                BackendStatus.Info("No usage today", italic = true)
            } else {
                BackendStatus.Info("Today: ${usageTracker.todayString()} tokens")
            }
        }

    override fun unavailableUntil(): Long? {
        clearCooldownIfCredentialsChanged()
        return cooldownState.unavailableUntil()
    }
    override fun unavailableDescription(): String? = cooldownState.unavailableDescription()
    override fun recordSuccess(attemptStartedAtMs: Long) =
        cooldownState.recordSuccess(attemptStartedAtMs)

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
            cooldownState.recordSuccess(System.currentTimeMillis())
        }
    }

    override fun isUsable(source: String, target: String): Boolean =
        enabledProvider() && !keyProvider().isNullOrBlank()

    override suspend fun translate(text: String, source: String, target: String): String =
        withContext(Dispatchers.IO) {
            clearCooldownIfCredentialsChanged()
            val apiKey = keyProvider()?.takeIf { it.isNotBlank() }
                ?: throw IOException("Gemini API key not configured")
            val model = modelProvider()
            val system = QwenChatTemplate.systemPrompt(source, target)
            val user = QwenChatTemplate.userMessage(text, source, target)
            val translateStart = System.nanoTime()
            Log.i(TAG, "translate begin model=$model textLen=${text.length}")

            val bodyJson = gson.toJson(
                mapOf(
                    "systemInstruction" to mapOf(
                        "parts" to listOf(mapOf("text" to system)),
                    ),
                    "contents" to listOf(
                        mapOf(
                            "role" to "user",
                            "parts" to listOf(mapOf("text" to user)),
                        )
                    ),
                    "generationConfig" to mapOf("temperature" to 0.2),
                )
            )

            val request = Request.Builder()
                .url("$ENDPOINT_ROOT/models/$model:generateContent")
                .addHeader("x-goog-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string() ?: ""
                    when (response.code) {
                        400 -> {
                            // Gemini reports invalid keys as 400 with
                            // `error.status = "INVALID_ARGUMENT"` and a message
                            // containing "API key not valid" — disambiguate from
                            // other 400s (bad model id, malformed body).
                            if (bodyStr.contains("API_KEY_INVALID") ||
                                bodyStr.contains("API key not valid")) {
                                throw GeminiAuthException()
                            }
                            throw StructuralFailureException("Gemini 400: ${bodyStr.take(200)}")
                        }
                        429 -> {
                            Log.w(TAG, "429 body=${bodyStr.take(500)}")
                            recordGemini429(bodyStr)
                            throw GeminiRateLimitException()
                        }
                        else -> if (!response.isSuccessful) {
                            if (response.code >= 500) {
                                cooldownState.recordLadderFailure(
                                    CooldownLadder.RateLimit, "Server error"
                                )
                            }
                            throw StructuralFailureException("Gemini error ${response.code}")
                        }
                    }
                    if (bodyStr.isEmpty()) throw StructuralFailureException("Empty response from Gemini")
                    val parsed = gson.fromJson(bodyStr, GeminiResponse::class.java)
                    val raw = parsed.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
                        ?: throw StructuralFailureException("No translation in Gemini response")
                    parsed.usageMetadata?.let {
                        usageTracker.addTokens(it.promptTokenCount, it.candidatesTokenCount)
                    }
                    val totalMs = (System.nanoTime() - translateStart) / 1_000_000
                    Log.i(TAG, "translate ok totalMs=$totalMs outLen=${raw.length}")
                    cleanLlmOutput(raw)
                }
            } catch (e: GeminiAuthException) { throw e }
            catch (e: GeminiRateLimitException) { throw e }
            catch (e: StructuralFailureException) {
                // 4xx, 5xx (already recorded above), empty payload, missing
                // translation — deterministic upstream failures. Skip the
                // network-ladder bump so a misconfigured key/model doesn't
                // masquerade as "Connection failed."
                throw e
            }
            catch (e: IOException) {
                // True network / connection failure path: the first one is
                // forgiven inside recordNetworkFailure, the second within
                // the window engages the network cooldown ladder.
                cooldownState.recordNetworkFailure("Connection failed")
                throw e
            }
        }

    override suspend fun translateBatch(
        texts: List<String>,
        source: String,
        target: String,
    ): List<String> = withContext(Dispatchers.IO) {
        clearCooldownIfCredentialsChanged()
        val apiKey = keyProvider()?.takeIf { it.isNotBlank() }
            ?: throw IOException("Gemini API key not configured")
        val model = modelProvider()
        val system = LlmBatchPrompt.systemPrompt(source, target)
        val user = LlmBatchPrompt.userMessage(texts)
        val translateStart = System.nanoTime()
        Log.i(TAG, "translate batch begin model=$model batchSize=${texts.size} totalLen=${texts.sumOf { it.length }}")

        // generationConfig.responseSchema constrains the model to emit
        // a JSON object of the exact shape we need — OpenAPI-style
        // dialect, "OBJECT"/"ARRAY"/"STRING" type names (uppercase).
        val responseSchema = mapOf(
            "type" to "OBJECT",
            "properties" to mapOf(
                "translations" to mapOf(
                    "type" to "ARRAY",
                    "items" to mapOf("type" to "STRING"),
                ),
            ),
            "required" to listOf("translations"),
        )
        val bodyJson = gson.toJson(
            mapOf(
                "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to system))),
                "contents" to listOf(
                    mapOf(
                        "role" to "user",
                        "parts" to listOf(mapOf("text" to user)),
                    )
                ),
                "generationConfig" to mapOf(
                    "temperature" to 0.2,
                    "responseMimeType" to "application/json",
                    "responseSchema" to responseSchema,
                ),
            )
        )

        val request = Request.Builder()
            .url("$ENDPOINT_ROOT/models/$model:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                when (response.code) {
                    400 -> {
                        if (bodyStr.contains("API_KEY_INVALID") ||
                            bodyStr.contains("API key not valid")) {
                            throw GeminiAuthException()
                        }
                        // Mirrors the OpenAI batch 400 handling: a non-auth
                        // 400 on the batch path is most likely the model
                        // rejecting the responseSchema / responseMimeType
                        // body fields the single-text path doesn't send.
                        // Surface as BatchParseException so the registry
                        // retries this same Gemini backend's per-text
                        // translate() (no schema) before skipping to a
                        // lower-priority backend like DeepL or Lingva.
                        Log.w(TAG, "batch 400 body=${bodyStr.take(500)} — retrying per-text on same backend")
                        throw BatchParseException("Gemini batch 400: ${bodyStr.take(200)}")
                    }
                    429 -> {
                        Log.w(TAG, "429 body=${bodyStr.take(500)}")
                        recordGemini429(bodyStr)
                        throw GeminiRateLimitException()
                    }
                    else -> if (!response.isSuccessful) {
                        if (response.code >= 500) {
                            cooldownState.recordLadderFailure(
                                CooldownLadder.RateLimit, "Server error"
                            )
                        }
                        throw StructuralFailureException("Gemini error ${response.code}")
                    }
                }
                if (bodyStr.isEmpty()) throw StructuralFailureException("Empty response from Gemini")
                val parsed = gson.fromJson(bodyStr, GeminiResponse::class.java)
                val rawJson = parsed.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    ?: throw BatchParseException("Gemini batch: empty candidate payload")
                parsed.usageMetadata?.let {
                    usageTracker.addTokens(it.promptTokenCount, it.candidatesTokenCount)
                }
                val wrapper = try {
                    gson.fromJson(rawJson, BatchTranslationsWrapper::class.java)
                } catch (e: JsonSyntaxException) {
                    throw BatchParseException("Gemini batch: response JSON parse failed", e)
                }
                val arr = wrapper?.translations
                    ?: throw BatchParseException("Gemini batch: missing translations[]")
                if (arr.size != texts.size) {
                    throw BatchParseException(
                        "Gemini batch: returned ${arr.size} translations for ${texts.size} inputs"
                    )
                }
                val totalMs = (System.nanoTime() - translateStart) / 1_000_000
                Log.i(TAG, "translate batch ok totalMs=$totalMs batchSize=${arr.size}")
                arr.map { cleanLlmOutput(it) }
            }
        } catch (e: GeminiAuthException) { throw e }
        catch (e: GeminiRateLimitException) { throw e }
        catch (e: BatchParseException) {
            // Intra-backend retry signal — do NOT cooldown. The registry
            // re-tries this backend's per-text translate() on the same
            // call, and that path's own cooldown wiring stands.
            throw e
        }
        catch (e: StructuralFailureException) {
            // 4xx / 5xx (already recorded above) / empty payload —
            // deterministic upstream, no network-ladder bump.
            throw e
        }
        catch (e: IOException) {
            cooldownState.recordNetworkFailure("Connection failed")
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
     */
    override suspend fun validateKey(overrideKey: String?): KeyStatus = withContext(Dispatchers.IO) {
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
                        val body = response.body?.string() ?: ""
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
            val body = response.body?.string()
                ?: throw IOException("Empty /models response")
            val parsed = gson.fromJson(body, GeminiModelsResponse::class.java)
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

    private data class GeminiResponse(
        val candidates: List<Candidate> = emptyList(),
        val usageMetadata: UsageMetadata? = null,
    ) {
        data class Candidate(val content: Content? = null)
        data class Content(val parts: List<Part> = emptyList())
        data class Part(val text: String = "")
        data class UsageMetadata(
            val promptTokenCount: Long = 0,
            val candidatesTokenCount: Long = 0,
        )
    }

    private data class BatchTranslationsWrapper(
        val translations: List<String>? = null,
    )

    /**
     * Thin wrapper: parse → route to the right [CooldownState] entry.
     * Pure parsing lives in [parseGemini429Body] so unit tests can
     * exercise it without constructing a full backend.
     */
    private fun recordGemini429(body: String) {
        val parsed = parseGemini429Body(body, gson)
        if (parsed != null) {
            cooldownState.recordParsedFailure(parsed.first, parsed.second)
        } else {
            cooldownState.recordLadderFailure(CooldownLadder.RateLimit, "Rate limited")
        }
    }

    private data class GeminiModelsResponse(
        val models: List<ModelEntry> = emptyList(),
    ) {
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

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
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
    gson: Gson = Gson(),
    now: Long = System.currentTimeMillis(),
): Pair<Long, String>? {
    val envelope = try {
        gson.fromJson(body, GeminiErrorEnvelope::class.java)
    } catch (e: JsonSyntaxException) {
        return null
    }
    val details = envelope?.error?.details ?: return null

    for (detail in details) {
        val type = detail["@type"] as? String ?: continue
        if (!type.endsWith("QuotaFailure")) continue
        val violations = detail["violations"] as? List<*> ?: continue
        for (v in violations) {
            val quotaId = (v as? Map<*, *>)?.get("quotaId") as? String ?: continue
            if (PER_DAY_QUOTA_PATTERN.containsMatchIn(quotaId)) {
                return nextMidnightPacific(now) to "Daily quota used"
            }
        }
    }
    for (detail in details) {
        val type = detail["@type"] as? String ?: continue
        if (!type.endsWith("RetryInfo")) continue
        val delay = detail["retryDelay"] as? String ?: continue
        val ms = GoDuration.parse(delay)?.inWholeMilliseconds ?: continue
        return (now + ms) to "Rate limited"
    }
    return null
}

internal data class GeminiErrorEnvelope(val error: GeminiErrorBody? = null) {
    data class GeminiErrorBody(val details: List<Map<String, Any?>>? = null)
}

/** Loose pattern for Gemini's community-observed per-day quotaId
 *  substring conventions. */
internal val PER_DAY_QUOTA_PATTERN = Regex("""Per(Day|Daily)""", RegexOption.IGNORE_CASE)
