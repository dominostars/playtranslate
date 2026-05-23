package com.playtranslate.translation

import com.google.gson.Gson
import com.playtranslate.Prefs
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
    private val keyProvider: () -> String?,
    private val enabledProvider: () -> Boolean,
    private val modelProvider: () -> String,
    private val baseUrlProvider: () -> String,
    private val usageTracker: UsageTracker,
    private val client: OkHttpClient = defaultClient(),
) : TranslationBackend {

    override val id: BackendId = "openai"
    override val displayName: String = "OpenAI"
    override val priority: Int = 8
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
                    429 -> throw OpenAiRateLimitException()
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
                cleanLlmOutput(raw)
            }
        }

    /**
     * Verify the API key against the configured endpoint. Returns
     * [KeyStatus.Unreachable] when the base URL is not OpenAI's default —
     * the `/v1/models` endpoint exists for OpenAI itself but custom
     * OpenAI-compatible services have unknown key shapes, so probing
     * them would false-flag valid OpenRouter/DeepSeek/LM-Studio keys.
     */
    suspend fun validateKey(): KeyStatus = withContext(Dispatchers.IO) {
        val apiKey = keyProvider()?.takeIf { it.isNotBlank() }
            ?: return@withContext KeyStatus.Invalid("API key blank")
        val baseUrl = baseUrlProvider().trim().trimEnd('/')
        if (baseUrl != Prefs.DEFAULT_OPENAI_BASE_URL.trimEnd('/')) {
            return@withContext KeyStatus.Unreachable
        }
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

    companion object {
        // 30s timeouts: gpt-4o on long passages can take 15-20s; OkHttp's
        // default 10s read timeout would spuriously fail those calls.
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
