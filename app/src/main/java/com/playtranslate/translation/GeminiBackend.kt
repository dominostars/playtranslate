package com.playtranslate.translation

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.playtranslate.translation.llm.LlmBatchPrompt
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
) : TranslationBackend, BatchTranslator, ModelLister {

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
                    429 -> {
                        // Log the body so the diagnostic answers "which
                        // quota was hit" — Gemini's 429 payload includes
                        // a structured `error.details` listing the
                        // specific QuotaFailure (per-minute requests,
                        // per-minute tokens, per-day requests, etc.).
                        // Truncated to keep the log line manageable.
                        Log.w(TAG, "429 body=${bodyStr.take(500)}")
                        throw GeminiRateLimitException()
                    }
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

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            when (response.code) {
                400 -> {
                    if (bodyStr.contains("API_KEY_INVALID") ||
                        bodyStr.contains("API key not valid")) {
                        throw GeminiAuthException()
                    }
                    throw IOException("Gemini 400: ${bodyStr.take(200)}")
                }
                429 -> {
                    Log.w(TAG, "429 body=${bodyStr.take(500)}")
                    throw GeminiRateLimitException()
                }
                else -> if (!response.isSuccessful) {
                    throw IOException("Gemini error ${response.code}")
                }
            }
            if (bodyStr.isEmpty()) throw IOException("Empty response from Gemini")
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
