package com.playtranslate.translation

import com.google.gson.Gson
import com.playtranslate.translation.llm.cleanLlmOutput
import com.playtranslate.translation.qwen.QwenChatTemplate
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
    private val client: OkHttpClient = defaultClient(),
) : TranslationBackend {

    override val id: BackendId = "gemini"
    override val displayName: String = "Gemini"
    override val priority: Int = 7
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
        enabledProvider() && !keyProvider().isNullOrBlank()

    override suspend fun translate(text: String, source: String, target: String): String =
        withContext(Dispatchers.IO) {
            val apiKey = keyProvider()?.takeIf { it.isNotBlank() }
                ?: throw IOException("Gemini API key not configured")
            val model = modelProvider()
            val system = QwenChatTemplate.systemPrompt(source, target)
            val user = QwenChatTemplate.userMessage(text, source, target)

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
                        throw IOException("Gemini 400: ${bodyStr.take(200)}")
                    }
                    429 -> throw GeminiRateLimitException()
                    else -> if (!response.isSuccessful) {
                        throw IOException("Gemini error ${response.code}")
                    }
                }
                if (bodyStr.isEmpty()) throw IOException("Empty response from Gemini")
                val parsed = gson.fromJson(bodyStr, GeminiResponse::class.java)
                val raw = parsed.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    ?: throw IOException("No translation in Gemini response")
                parsed.usageMetadata?.let {
                    usageTracker.addTokens(it.promptTokenCount, it.candidatesTokenCount)
                }
                cleanLlmOutput(raw)
            }
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

    companion object {
        private const val ENDPOINT_ROOT =
            "https://generativelanguage.googleapis.com/v1beta"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
