package com.playtranslate.translation

import android.util.Log
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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thrown when OpenAI rejects the API key (HTTP 401). */
class OpenAiAuthException : IOException("Invalid OpenAI API key")

/** Thrown when OpenAI rate-limits the call (HTTP 429). Like
 *  [DeepLQuotaExceededException], this falls through to the next backend
 *  silently — no transient row-status flair in v1. */
class OpenAiRateLimitException : IOException("OpenAI rate limit exceeded")

/**
 * OpenAI chat-completions backend. Doubles as a generic OpenAI-compatible
 * client: the base URL is configurable via [baseUrlProvider], so OpenRouter,
 * DeepSeek, LM Studio, and similar services that speak the same JSON schema
 * work without per-provider code.
 *
 * Prompts come from [QwenChatTemplate]'s system/user helpers — the same
 * instruction text the on-device Qwen / MNN translators feed their engines.
 * Reusing the helper means a future prompt tweak lands once and propagates
 * to every LLM-backed tier.
 *
 * Known limitation: some OpenAI-compatible endpoints (older LM Studio builds,
 * some proxies) reject the `system` role and require everything inlined as
 * `user`. v1 doesn't handle that — users hitting it will see a 4xx and the
 * waterfall will fall through to DeepL/Lingva.
 *
 * Streaming note: both OpenAI and OpenAI-compatible endpoints support SSE
 * via `stream: true`, but [TranslationBackend.translate] returns a full
 * String. Adding streaming would require a `Flow<String>` overload across
 * the interface; deferred.
 */
class OpenAiBackend(
    override val id: BackendId,
    override val displayName: String,
    override val priority: Int,
    private val keyProvider: () -> String?,
    private val enabledProvider: () -> Boolean,
    private val modelProvider: () -> String,
    private val baseUrlProvider: () -> String,
    /** URL prefix for `/models` (validateKey + listModels). Defaults to
     *  [baseUrlProvider] for providers (OpenAI, OpenRouter, LM Studio)
     *  whose chat-completions and models endpoints share a prefix.
     *  DeepSeek requires `https://api.deepseek.com` here while
     *  [baseUrlProvider] stays `https://api.deepseek.com/v1` for
     *  `/chat/completions` — their /models endpoint sits at the root,
     *  not under /v1, and pointing /models at /v1/models returns an
     *  HTTP 200 with an empty body that listModels then rejects. */
    private val modelsUrlProvider: () -> String = baseUrlProvider,
    /** Builds, from a base URL, the endpoint whose 401 proves a key bad
     *  ([validateKey]). Defaults to `{base}/models`, which every provider
     *  authenticates — except OpenRouter, which serves its catalog publicly
     *  and needs `{base}/key` instead. Takes the base as a parameter rather
     *  than reading [baseUrlProvider] because the settings page validates a
     *  URL the user has typed but not yet saved. */
    private val keyProbeUrlProvider: (String) -> String = { "${it.trimEnd('/')}/models" },
    private val usageTracker: UsageTracker,
    /** Returns true when [listModels] should apply OpenAI's first-party
     *  `owned_by` filter (strips fine-tunes / user-uploaded models on
     *  platform.openai.com). A lambda, not a constant, because the OpenAI
     *  instance flips it per request off the live base URL: the filter
     *  applies only on the canonical endpoint. DeepSeek and custom user
     *  URLs tag models with their own org name, so this returns false
     *  there or the picker comes back empty. */
    private val applyOwnedByFilter: () -> Boolean,
    /** Null for backends that should NOT participate in the waterfall
     *  cooldown system (currently DeepSeek — its 10-minute TCP-hold
     *  behavior makes SocketTimeoutException categorisation too
     *  ambiguous for v1). When null, the [Cooldownable] methods all
     *  return null/no-op and the registry's skip check never fires. */
    private val cooldownState: CooldownState? = null,
    private val client: OkHttpClient = defaultClient(),
) : TranslationBackend, BatchTranslator, ModelLister, KeyValidator, Cooldownable {

    override val requiresInternet: Boolean = true
    override val isDegradedFallback: Boolean = false
    override val providesLlmContext: Boolean = true
    override val qualityStars: StarRating = 4.5f

    override val status: BackendStatus
        get() {
            val key = keyProvider()
            if (key.isNullOrBlank()) {
                // Every OpenAI preset (DeepSeek, Mistral, Groq, OpenRouter,
                // Custom) is an OPENAI-type instance, and all of them want an
                // account — so the type's requirement covers the whole family.
                return BackendStatus.Account(ServiceType.OPENAI.account)
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
        return cooldownState?.unavailableUntil()
    }
    override fun unavailableDescription(): String? = cooldownState?.unavailableDescription()
    override fun unavailableCause(): CooldownCause? = cooldownState?.unavailableCause()
    override fun recordSuccess(attemptStartedAtMs: Long) {
        cooldownState?.recordSuccess(attemptStartedAtMs)
    }

    /** Last-seen `(key | model | baseUrl)` fingerprint. When it changes
     *  (user swaps in a new key, picks a different model, etc.), any
     *  persisted cooldown is cleared — it belonged to the prior
     *  credentials. No-op when [cooldownState] is null (DeepSeek). */
    @Volatile private var lastCredentialsFingerprint: String? = null

    private fun clearCooldownIfCredentialsChanged() {
        val current = "${keyProvider() ?: ""}|${modelProvider()}|${baseUrlProvider()}"
        val prev = lastCredentialsFingerprint
        lastCredentialsFingerprint = current
        if (prev != null && prev != current) {
            cooldownState?.recordSuccess(System.currentTimeMillis())
        }
    }

    override fun isUsable(source: String, target: String): Boolean =
        // A future client-side daily cap could gate here too, but v1 lets
        // the meter be informational only.
        enabledProvider() && !keyProvider().isNullOrBlank()

    override suspend fun translate(text: String, source: String, target: String): String =
        withContext(Dispatchers.IO) {
            clearCooldownIfCredentialsChanged()
            val apiKey = keyProvider()?.takeIf { it.isNotBlank() }
                ?: throw IOException("OpenAI API key not configured")
            val baseUrl = baseUrlProvider().trim().trimEnd('/')
            val model = modelProvider()
            val system = QwenChatTemplate.systemPrompt(source, target)
            // Online backend: {context} is affordable here (server-side prefill)
            // and is gated off for the on-device tiers. See TOKEN_CONTEXT.
            val user = QwenChatTemplate.userMessage(text, source, target, includeContext = true)
            val translateStart = System.nanoTime()
            Log.i(TAG, "translate begin model=$model textLen=${text.length}")

            val bodyJson = buildJsonObject {
                put("model", model)
                putJsonArray("messages") {
                    addJsonObject { put("role", "system"); put("content", system) }
                    addJsonObject { put("role", "user"); put("content", user) }
                }
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    when (response.code) {
                        401 -> throw OpenAiAuthException()
                        429 -> {
                            val hints = RateLimitHints.from(response)
                            val body = response.body.string()
                            Log.w(TAG, "429 $hints body=${body.take(500)}")
                            recordOpenAi429(body, hints)
                            throw OpenAiRateLimitException()
                        }
                        else -> if (!response.isSuccessful) {
                            val errBody = response.body.string()
                                .take(300).replace('\n', ' ')
                            Log.w(TAG, "translate error code=${response.code} body=$errBody")
                            if (response.code >= 500) {
                                cooldownState?.recordLadderFailure(
                                    CooldownLadder.RateLimit, "Server error",
                                    CooldownCause.SERVER_ERROR,
                                )
                            }
                            throw StructuralFailureException("OpenAI error ${response.code}: $errBody")
                        }
                    }
                    val bodyStr = response.body.string()
                    val parsed = PtJson.lenient.decodeFromString<OpenAiChatResponse>(bodyStr)
                    val raw = parsed.choices.firstOrNull()?.message?.content
                        ?: throw StructuralFailureException("No translation in OpenAI response")
                    parsed.usage?.let { usageTracker.addTokens(it.prompt_tokens, it.completion_tokens) }
                    val totalMs = (System.nanoTime() - translateStart) / 1_000_000
                    Log.i(TAG, "translate ok totalMs=$totalMs outLen=${raw.length}")
                    cleanLlmOutput(raw)
                }
            } catch (e: OpenAiAuthException) { throw e }
            catch (e: OpenAiRateLimitException) { throw e }
            catch (e: StructuralFailureException) {
                // 4xx / 5xx (already recorded above) / empty payload —
                // deterministic upstream, no network-ladder bump.
                throw e
            }
            catch (e: IOException) {
                cooldownState?.recordNetworkFailure("Connection failed")
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
            ?: throw IOException("OpenAI API key not configured")
        val baseUrl = baseUrlProvider().trim().trimEnd('/')
        val model = modelProvider()
        val system = LlmBatchPrompt.systemPrompt(source, target)
        val user = LlmBatchPrompt.userMessage(texts, source, target, includeContext = true)
        val translateStart = System.nanoTime()
        Log.i(TAG, "translate batch begin model=$model batchSize=${texts.size} totalLen=${texts.sumOf { it.length }}")

        // OpenAI strict mode: requires additionalProperties:false +
        // required[] + strict:true together. Some OpenAI-compatible
        // endpoints (older models, some proxies) reject strict mode
        // with HTTP 400 — registry handles that by falling through.
        val schema = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonObject("properties") {
                putJsonObject("translations") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                }
            }
            putJsonArray("required") { add("translations") }
        }
        val bodyJson = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", system) }
                addJsonObject { put("role", "user"); put("content", user) }
            }
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "translations")
                    put("strict", true)
                    put("schema", schema)
                }
            }
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    401 -> throw OpenAiAuthException()
                    429 -> {
                        val hints = RateLimitHints.from(response)
                        val body = response.body.string()
                        Log.w(TAG, "429 $hints body=${body.take(500)}")
                        recordOpenAi429(body, hints)
                        throw OpenAiRateLimitException()
                    }
                    400 -> {
                        // 400 on the batch path is most often
                        // "this endpoint doesn't support strict json_schema"
                        // — common on OpenAI-compatible endpoints (older
                        // OpenRouter / LM Studio / non-`o` model families).
                        // Surface as BatchParseException so the registry
                        // retries this same backend's per-text translate()
                        // (which doesn't send response_format) before
                        // skipping to a degraded fallback.
                        val body = response.body.string().take(500)
                        Log.w(TAG, "batch 400 body=$body — retrying per-text on same backend")
                        throw BatchParseException("OpenAI batch 400: $body")
                    }
                    else -> if (!response.isSuccessful) {
                        val errBody = response.body.string()
                            .take(300).replace('\n', ' ')
                        Log.w(TAG, "translate batch error code=${response.code} body=$errBody")
                        if (response.code >= 500) {
                            cooldownState?.recordLadderFailure(
                                CooldownLadder.RateLimit, "Server error",
                                CooldownCause.SERVER_ERROR,
                            )
                        }
                        throw StructuralFailureException("OpenAI error ${response.code}: $errBody")
                    }
                }
                val bodyStr = response.body.string()
                val parsed = PtJson.lenient.decodeFromString<OpenAiChatResponse>(bodyStr)
                val rawJson = parsed.choices.firstOrNull()?.message?.content
                    ?: throw BatchParseException("OpenAI batch: empty message content")
                parsed.usage?.let { usageTracker.addTokens(it.prompt_tokens, it.completion_tokens) }
                val wrapper = try {
                    PtJson.lenient.decodeFromString<BatchTranslationsWrapper>(rawJson)
                } catch (e: SerializationException) {
                    throw BatchParseException("OpenAI batch: response JSON parse failed", e)
                }
                val arr = wrapper.translations
                    ?: throw BatchParseException("OpenAI batch: missing translations[]")
                if (arr.size != texts.size) {
                    throw BatchParseException(
                        "OpenAI batch: returned ${arr.size} translations for ${texts.size} inputs"
                    )
                }
                val totalMs = (System.nanoTime() - translateStart) / 1_000_000
                Log.i(TAG, "translate batch ok totalMs=$totalMs batchSize=${arr.size}")
                arr.map { cleanLlmOutput(it) }
            }
        } catch (e: OpenAiAuthException) { throw e }
        catch (e: OpenAiRateLimitException) { throw e }
        catch (e: BatchParseException) {
            // Intra-backend retry signal — registry re-tries per-text on
            // this same backend; no cooldown.
            throw e
        }
        catch (e: StructuralFailureException) {
            // 4xx / 5xx (already recorded above) / empty payload —
            // deterministic upstream, no network-ladder bump.
            throw e
        }
        catch (e: IOException) {
            cooldownState?.recordNetworkFailure("Connection failed")
            throw e
        }
    }

    /**
     * Verify the API key by hitting `/models` on the configured endpoint.
     * The OpenAI instance now exposes a user-settable base URL (the
     * ADVANCED settings section), so endpoints are no longer all
     * first-party-vetted: a custom OpenAI-compatible server may not
     * support `/models` or may use a different key shape. Validation is
     * therefore best-effort — a definitive 401/403 is [KeyStatus.Invalid],
     * anything else (incl. a missing `/models`) is [KeyStatus.Unreachable],
     * which the save path treats as "save anyway".
     *
     * [overrideKey] / [overrideBaseUrl] let the settings-save path validate
     * a key + URL the user just typed but hasn't persisted yet. Both fall
     * back to the registered providers when null.
     */
    override suspend fun validateKey(overrideKey: String?, overrideBaseUrl: String?): KeyStatus = withContext(Dispatchers.IO) {
        val apiKey = (overrideKey ?: keyProvider())?.takeIf { it.isNotBlank() }
            ?: return@withContext KeyStatus.Invalid("API key blank")
        val probeUrl = keyProbeUrlProvider((overrideBaseUrl ?: modelsUrlProvider()).trim())
        try {
            val keyed = probe(probeUrl, apiKey)
            when (keyed) {
                in 200..299 -> {
                    // A 2xx only means "the key was accepted" if the endpoint
                    // would have REFUSED us without one. Several OpenAI-
                    // compatible hosts serve /models publicly (OpenRouter,
                    // NVIDIA, DeepInfra, the HF router …), and there the same
                    // 2xx comes back for a key of pure gibberish. Ask the
                    // endpoint what it does with no key at all: if it still
                    // says 2xx, this probe cannot see keys, and reporting Ok
                    // would be a verification we never performed.
                    if (probe(probeUrl, apiKey = null) in 200..299) {
                        Log.w(TAG, "key probe endpoint is unauthenticated, cannot verify: $probeUrl")
                        KeyStatus.Unreachable
                    } else {
                        KeyStatus.Ok
                    }
                }
                401, 403 -> KeyStatus.Invalid("HTTP $keyed")
                else -> KeyStatus.Unreachable
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            KeyStatus.Unreachable
        }
    }

    /** GET [url], with a Bearer key when [apiKey] is non-null. Returns the
     *  status code; -1 when the control probe itself fails, which reads as
     *  "not a 2xx" and so leaves a keyed 2xx standing — a flaky second
     *  request must never turn a good key into a rejected one. */
    private fun probe(url: String, apiKey: String?): Int {
        val request = Request.Builder()
            .url(url)
            .apply { apiKey?.let { addHeader("Authorization", "Bearer $it") } }
            .build()
        return if (apiKey != null) {
            client.newCall(request).execute().use { it.code }
        } else {
            try {
                client.newCall(request).execute().use { it.code }
            } catch (e: IOException) {
                -1
            }
        }
    }

    /**
     * Fetches the list of models the configured key can call.
     * Honours the user-configured base URL so OpenAI-compatible
     * providers (OpenRouter, DeepSeek, LM Studio, etc.) return their
     * own catalog. The OpenAI /v1/models endpoint also returns
     * non-chat assets (embeddings, TTS, image, transcription, etc.) —
     * filter those out with a conservative regex so the picker only
     * shows chat-capable ids. The "Custom…" entry in the picker
     * remains as the escape hatch when a user wants a model the
     * filter excludes.
     *
     * Throws [IOException] on any network / parse / auth failure.
     */
    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val apiKey = keyProvider()?.takeIf { it.isNotBlank() }
            ?: throw IOException("OpenAI API key not configured")
        val modelsUrl = modelsUrlProvider().trim().trimEnd('/')
        val request = Request.Builder()
            .url("$modelsUrl/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .build()
        client.newCall(request).execute().use { response ->
            when (response.code) {
                401 -> throw OpenAiAuthException()
                else -> if (!response.isSuccessful) {
                    throw IOException("OpenAI /models error ${response.code}")
                }
            }
            val body = response.body.string()
            if (body.isBlank()) throw IOException("Blank /models response")
            // Gson can return null on malformed/unrecognized JSON.
            // Defends against provider quirks like DeepSeek's old
            // /v1/models = 200 + empty body behaviour (now avoided by
            // pointing modelsUrlProvider at the root) and similar
            // misconfigurations on future OpenAI-compatible providers.
            val parsed = try {
                PtJson.lenient.decodeFromString<OpenAiModelsResponse>(body)
            } catch (e: SerializationException) {
                throw IOException("Malformed /models response", e)
            }
            val sorted = parsed.data
                .asSequence()
                // Drop fine-tunes and user-owned models for canonical
                // OpenAI (where owned_by ∈ {system, openai, openai-internal}).
                // DeepSeek and custom user base URLs populate owned_by with
                // their own org name, so [applyOwnedByFilter] returns false
                // there and keeps the picker from coming back empty on those
                // endpoints.
                .filter { entry ->
                    !applyOwnedByFilter() ||
                        entry.owned_by.isBlank() ||
                        entry.owned_by == "system" ||
                        entry.owned_by == "openai" ||
                        entry.owned_by == "openai-internal"
                }
                .filter { it.id.isNotBlank() }
                .filterNot { NON_CHAT_MODEL_PATTERN.containsMatchIn(it.id) }
                // Drop dated snapshots ("gpt-4o-2024-05-13") so the
                // picker shows only the canonical aliases. Users can
                // still type a dated id via "Custom…" if they want to
                // pin to a specific snapshot.
                .filterNot { DATED_SNAPSHOT_PATTERN.containsMatchIn(it.id) }
                // Newest aliases at the top — sort by creation timestamp
                // descending. OpenAI's catalog has many legacy entries
                // (gpt-3.5, davinci, etc.) that fall to the bottom this
                // way.
                .sortedByDescending { it.created }
                .map { it.id }
                .toList()
            Log.i(TAG, "listModels returned ${sorted.size} entries (sorted newest → oldest):")
            sorted.forEachIndexed { i, m -> Log.i(TAG, "  [$i] $m") }
            sorted
        }
    }

    override fun close() {
        // Daemon thread because evictAll() can write TLS close-notify on the
        // calling thread; see the equivalent note in DeepLBackend.close().
        val c = client
        Thread {
            c.dispatcher.executorService.shutdown()
            c.connectionPool.evictAll()
        }.apply { isDaemon = true; name = "OpenAiBackend-close" }.start()
    }

    @Serializable
    private data class OpenAiChatResponse(
        val choices: List<Choice> = emptyList(),
        val usage: Usage? = null,
    ) {
        @Serializable
        data class Choice(val message: Message? = null)
        @Serializable
        data class Message(val content: String = "")
        @Serializable
        data class Usage(
            val prompt_tokens: Long = 0,
            val completion_tokens: Long = 0,
        )
    }

    @Serializable
    private data class BatchTranslationsWrapper(
        val translations: List<String>? = null,
    )

    /**
     * The "come back later" hints a 429 can carry. Every OpenAI-compatible
     * provider expresses the same idea in its own header dialect, and the
     * response is the only place it is ever stated, so we read them all and
     * let [recordOpenAi429] take the longest wait any of them names.
     *
     *  - OpenAI and Groq: `retry-after` (integer seconds) plus Go-duration
     *    `x-ratelimit-reset-requests` / `-tokens`.
     *  - OpenRouter: `retry-after`, but only when an upstream provider gave
     *    it one; often absent.
     *  - Mistral: none of the above. Its limits are token-denominated (it
     *    has no request-count header at all) and it reports the window in
     *    `ratelimitbysize-reset`, integer seconds. Note that Mistral's own
     *    docs tell you to read `X-RateLimit-Remaining`, a header it does not
     *    send — don't "fix" this by trusting them over the wire.
     *  - DeepSeek: never gets here; it holds the connection instead of
     *    429ing, which is why it has no [cooldownState] at all.
     */
    private data class RateLimitHints(
        /** Integer seconds. */
        val retryAfter: String?,
        /** Go-style duration ("1s", "6m0s", "4m12.172s"). */
        val resetRequests: String?,
        /** Go-style duration. */
        val resetTokens: String?,
        /** Integer seconds (Mistral). */
        val resetBySize: String?,
    ) {
        companion object {
            fun from(response: Response) = RateLimitHints(
                retryAfter = response.header("retry-after"),
                resetRequests = response.header("x-ratelimit-reset-requests"),
                resetTokens = response.header("x-ratelimit-reset-tokens"),
                resetBySize = response.header("ratelimitbysize-reset"),
            )
        }
    }

    /**
     * Parse a 429 response to decide what kind of cooldown to record. The
     * decision tree is:
     *
     *  1. `error.code == "insufficient_quota"` → billing dead, not a
     *     timer-based rate limit. Fixed 5 min cooldown surfaced as
     *     "Billing exhausted" so the row stays visible long enough for
     *     the user to notice and short enough that fixing billing
     *     doesn't leave them waiting. (OpenAI-only in practice: Mistral's
     *     429 body is a bare `{"message": ...}` with no code to read.)
     *  2. Otherwise take the longest wait any [RateLimitHints] header names.
     *     Azure and the new Responses API have been returning `-1` / `0` for
     *     these in 2025-2026; the sanity guard drops any value ≤ 0 ms so a
     *     spurious 0 doesn't translate to "ready right now."
     *  3. Nothing usable → fall through to the rate-limit ladder, which
     *     guesses an exponential backoff. Every header we learn to read is
     *     one provider that no longer has to be guessed at.
     *
     * No-op when [cooldownState] is null (DeepSeek path).
     */
    private fun recordOpenAi429(body: String, hints: RateLimitHints) {
        val state = cooldownState ?: return
        val errorCode = try {
            PtJson.lenient.decodeFromString<OpenAiErrorEnvelope>(body).error?.code
        } catch (e: SerializationException) {
            null
        }
        if (errorCode == "insufficient_quota") {
            state.recordParsedFailure(
                retryAt = System.currentTimeMillis() + INSUFFICIENT_QUOTA_MS,
                description = "Billing exhausted",
                cause = CooldownCause.BILLING,
            )
            return
        }
        fun seconds(raw: String?): Long? = raw?.trim()?.toLongOrNull()?.let { it * 1000 }
        val maxMs = listOfNotNull(
            seconds(hints.retryAfter),
            GoDuration.parse(hints.resetRequests)?.inWholeMilliseconds,
            GoDuration.parse(hints.resetTokens)?.inWholeMilliseconds,
            seconds(hints.resetBySize),
        ).filter { it > 0 }.maxOrNull()
        if (maxMs != null) {
            state.recordParsedFailure(
                retryAt = System.currentTimeMillis() + maxMs,
                description = "Rate limited",
            )
        } else {
            state.recordLadderFailure(CooldownLadder.RateLimit, "Rate limited")
        }
    }

    @Serializable
    private data class OpenAiErrorEnvelope(val error: OpenAiErrorBody? = null) {
        @Serializable
        data class OpenAiErrorBody(val code: String? = null)
    }

    @Serializable
    private data class OpenAiModelsResponse(
        val data: List<ModelEntry> = emptyList(),
    ) {
        @Serializable
        data class ModelEntry(
            val id: String = "",
            val created: Long = 0,
            val owned_by: String = "",
        )
    }

    companion object {
        private const val TAG = "OpenAI"

        /** Excludes non-chat asset ids from /v1/models responses so the
         *  picker isn't cluttered with embeddings, TTS, image, audio,
         *  transcription, moderation, and similar entries. Conservative
         *  — the user can still type the exact id via "Custom…" if they
         *  need one of these for some reason. */
        private val NON_CHAT_MODEL_PATTERN = Regex(
            "(audio|tts|transcribe|whisper|realtime|embedding|dall-e|moderation|image|babbage|davinci|ada|curie|search-preview|computer-use)",
            RegexOption.IGNORE_CASE,
        )

        /** Pattern matching dated snapshot suffixes like
         *  "gpt-4o-2024-05-13" or "gpt-4.1-2025-04-14". We filter
         *  these so the picker shows only the canonical aliases. */
        private val DATED_SNAPSHOT_PATTERN = Regex("""-\d{4}-\d{2}-\d{2}$""")

        /** Cooldown duration for `insufficient_quota` (billing dead).
         *  Short enough that fixing billing doesn't leave the user
         *  waiting; long enough not to spam the API. */
        private const val INSUFFICIENT_QUOTA_MS = 5L * 60 * 1000

        // 30s timeouts: gpt-4o on long passages can take 15-20s; OkHttp's
        // default 10s read timeout would spuriously fail those calls.
        // customEndpointBuilder: OpenAI is the ONE backend allowed to reach a
        // user-configured http endpoint (a self-hosted LAN LLM server). Its
        // client enforces CustomEndpointPolicy at the request boundary (https
        // anywhere, or http only to loopback/LAN) and disables scheme-changing
        // redirects — so the Bearer key can't go cleartext to a public host no
        // matter how the saved base URL got there, not just when the UI saves.
        private fun defaultClient(): OkHttpClient = PtHttp.customEndpointBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // Happy Eyeballs — see [GeminiBackend] for rationale.
            .fastFallback(true)
            // Logs per-phase latency (DNS, connect, TLS, ttfb, total) so
            // the "cold first call is slow" question is diagnosable from
            // logcat without an HTTP proxy.
            .eventListenerFactory(HttpTimingLogger.factory(TAG))
            .build()
    }
}
