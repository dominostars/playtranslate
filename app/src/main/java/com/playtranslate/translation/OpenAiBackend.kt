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
    private val usageTracker: UsageTracker,
    /** When true, [listModels] drops entries whose `owned_by` isn't
     *  one of OpenAI's first-party values — strips out fine-tunes /
     *  user-uploaded models on platform.openai.com. For other
     *  OpenAI-compatible providers (DeepSeek etc.) every model's
     *  `owned_by` is their own org name, so this must be false or
     *  the picker comes back empty. */
    private val filterFineTunes: Boolean,
    private val client: OkHttpClient = defaultClient(),
) : TranslationBackend, BatchTranslator, ModelLister {

    override val requiresInternet: Boolean = true
    override val isDegradedFallback: Boolean = false
    override val quality: BackendQuality = BackendQuality.Better

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

    override fun isUsable(source: String, target: String): Boolean =
        // A future client-side daily cap could gate here too, but v1 lets
        // the meter be informational only.
        enabledProvider() && !keyProvider().isNullOrBlank()

    override suspend fun translate(text: String, source: String, target: String): String =
        withContext(Dispatchers.IO) {
            val apiKey = keyProvider()?.takeIf { it.isNotBlank() }
                ?: throw IOException("OpenAI API key not configured")
            val baseUrl = baseUrlProvider().trim().trimEnd('/')
            val model = modelProvider()
            val system = QwenChatTemplate.systemPrompt(source, target)
            val user = QwenChatTemplate.userMessage(text, source, target)
            val translateStart = System.nanoTime()
            Log.i(TAG, "translate begin model=$model textLen=${text.length}")

            val bodyJson = gson.toJson(
                mapOf(
                    "model" to model,
                    "messages" to listOf(
                        mapOf("role" to "system", "content" to system),
                        mapOf("role" to "user", "content" to user),
                    ),
                    "temperature" to 0.2,
                )
            )

            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                when (response.code) {
                    401 -> throw OpenAiAuthException()
                    429 -> {
                        // Capture the body + retry-after so the
                        // "which quota was hit" question is diagnosable
                        // from logcat. OpenAI's 429 has a JSON error
                        // object describing the limit; `retry-after`
                        // header (when present) gives a wall-clock
                        // hint. Truncated to keep the log readable.
                        val retryAfter = response.header("retry-after")
                        val body = response.body?.string()?.take(500) ?: ""
                        Log.w(TAG, "429 retryAfter=$retryAfter body=$body")
                        throw OpenAiRateLimitException()
                    }
                    else -> if (!response.isSuccessful) {
                        throw IOException("OpenAI error ${response.code}")
                    }
                }
                val bodyStr = response.body?.string()
                    ?: throw IOException("Empty response from OpenAI")
                val parsed = gson.fromJson(bodyStr, OpenAiChatResponse::class.java)
                val raw = parsed.choices.firstOrNull()?.message?.content
                    ?: throw IOException("No translation in OpenAI response")
                parsed.usage?.let { usageTracker.addTokens(it.prompt_tokens, it.completion_tokens) }
                val totalMs = (System.nanoTime() - translateStart) / 1_000_000
                Log.i(TAG, "translate ok totalMs=$totalMs outLen=${raw.length}")
                cleanLlmOutput(raw)
            }
        }

    override suspend fun translateBatch(
        texts: List<String>,
        source: String,
        target: String,
    ): List<String> = withContext(Dispatchers.IO) {
        val apiKey = keyProvider()?.takeIf { it.isNotBlank() }
            ?: throw IOException("OpenAI API key not configured")
        val baseUrl = baseUrlProvider().trim().trimEnd('/')
        val model = modelProvider()
        val system = LlmBatchPrompt.systemPrompt(source, target)
        val user = LlmBatchPrompt.userMessage(texts)
        val translateStart = System.nanoTime()
        Log.i(TAG, "translate batch begin model=$model batchSize=${texts.size} totalLen=${texts.sumOf { it.length }}")

        // OpenAI strict mode: requires additionalProperties:false +
        // required[] + strict:true together. Some OpenAI-compatible
        // endpoints (older models, some proxies) reject strict mode
        // with HTTP 400 — registry handles that by falling through.
        val schema = mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "properties" to mapOf(
                "translations" to mapOf(
                    "type" to "array",
                    "items" to mapOf("type" to "string"),
                ),
            ),
            "required" to listOf("translations"),
        )
        val bodyJson = gson.toJson(
            mapOf(
                "model" to model,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to system),
                    mapOf("role" to "user", "content" to user),
                ),
                "temperature" to 0.2,
                "response_format" to mapOf(
                    "type" to "json_schema",
                    "json_schema" to mapOf(
                        "name" to "translations",
                        "strict" to true,
                        "schema" to schema,
                    ),
                ),
            )
        )

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            when (response.code) {
                401 -> throw OpenAiAuthException()
                429 -> {
                    val retryAfter = response.header("retry-after")
                    val body = response.body?.string()?.take(500) ?: ""
                    Log.w(TAG, "429 retryAfter=$retryAfter body=$body")
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
                    val body = response.body?.string()?.take(500) ?: ""
                    Log.w(TAG, "batch 400 body=$body — retrying per-text on same backend")
                    throw BatchParseException("OpenAI batch 400: $body")
                }
                else -> if (!response.isSuccessful) {
                    throw IOException("OpenAI error ${response.code}")
                }
            }
            val bodyStr = response.body?.string()
                ?: throw IOException("Empty response from OpenAI")
            val parsed = gson.fromJson(bodyStr, OpenAiChatResponse::class.java)
            val rawJson = parsed.choices.firstOrNull()?.message?.content
                ?: throw BatchParseException("OpenAI batch: empty message content")
            parsed.usage?.let { usageTracker.addTokens(it.prompt_tokens, it.completion_tokens) }
            val wrapper = try {
                gson.fromJson(rawJson, BatchTranslationsWrapper::class.java)
            } catch (e: JsonSyntaxException) {
                throw BatchParseException("OpenAI batch: response JSON parse failed", e)
            }
            val arr = wrapper?.translations
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
    }

    /**
     * Verify the API key against the configured endpoint by hitting
     * `/v1/models`. Each registered provider (OpenAI, DeepSeek) has a
     * known base URL so the gate that previously skipped validation on
     * "custom" URLs is no longer needed — every endpoint we configure
     * is one we've vetted to support `/v1/models`.
     */
    suspend fun validateKey(): KeyStatus = withContext(Dispatchers.IO) {
        val apiKey = keyProvider()?.takeIf { it.isNotBlank() }
            ?: return@withContext KeyStatus.Invalid("API key blank")
        val baseUrl = baseUrlProvider().trim().trimEnd('/')
        val request = Request.Builder()
            .url("$baseUrl/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    in 200..299 -> KeyStatus.Ok
                    401, 403 -> KeyStatus.Invalid("HTTP ${response.code}")
                    else -> KeyStatus.Unreachable
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            KeyStatus.Unreachable
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
        val baseUrl = baseUrlProvider().trim().trimEnd('/')
        val request = Request.Builder()
            .url("$baseUrl/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .build()
        client.newCall(request).execute().use { response ->
            when (response.code) {
                401 -> throw OpenAiAuthException()
                else -> if (!response.isSuccessful) {
                    throw IOException("OpenAI /models error ${response.code}")
                }
            }
            val body = response.body?.string()
                ?: throw IOException("Empty /models response")
            if (body.isBlank()) throw IOException("Blank /models response")
            // Gson can return null on malformed/unrecognized JSON.
            // Concrete trigger: providers that return HTTP 200 with an
            // empty body when an endpoint URL is wrong (DeepSeek does
            // this on /v1/models since their models endpoint is at
            // /models, no v1 prefix).
            val parsed = gson.fromJson(body, OpenAiModelsResponse::class.java)
                ?: throw IOException("Malformed /models response")
            val sorted = parsed.data
                .asSequence()
                // Drop fine-tunes and user-owned models for OpenAI itself
                // (where owned_by ∈ {system, openai, openai-internal}).
                // Other OpenAI-compatible providers (DeepSeek) populate
                // owned_by with their own org name, so gating this filter
                // on the [filterFineTunes] constructor flag keeps the
                // picker from coming back empty on those providers.
                .filter { entry ->
                    !filterFineTunes ||
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

    private data class OpenAiChatResponse(
        val choices: List<Choice> = emptyList(),
        val usage: Usage? = null,
    ) {
        data class Choice(val message: Message? = null)
        data class Message(val content: String = "")
        data class Usage(
            val prompt_tokens: Long = 0,
            val completion_tokens: Long = 0,
        )
    }

    private data class BatchTranslationsWrapper(
        val translations: List<String>? = null,
    )

    private data class OpenAiModelsResponse(
        val data: List<ModelEntry> = emptyList(),
    ) {
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

        // 30s timeouts: gpt-4o on long passages can take 15-20s; OkHttp's
        // default 10s read timeout would spuriously fail those calls.
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
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
