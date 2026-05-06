package com.playtranslate.translation

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale

/** Thrown when the DeepL free quota for the month has been exhausted (HTTP 456). */
class DeepLQuotaExceededException : IOException("DeepL monthly quota exceeded")

/** Thrown when the API key is rejected (HTTP 403). */
class DeepLAuthException : IOException("Invalid DeepL API key")

/**
 * DeepL REST API backend.
 *
 * Free API keys end with ":fx" and use api-free.deepl.com.
 * Paid API keys use api.deepl.com. Host is chosen per-call from the
 * key returned by [keyProvider] so a Settings change propagates without
 * rebuilding the registry.
 *
 * [keyProvider] and [enabledProvider] are invoked on the calling
 * coroutine's thread (Dispatchers.IO inside [translate]). Implementations
 * must be cheap and thread-safe; `Prefs(context).deeplApiKey` /
 * `Prefs(context).deeplEnabled` qualify.
 *
 * [enabledProvider] reflects the user's explicit on/off state from
 * Settings, which is independent of whether the key is set. The user
 * may disable DeepL while keeping the saved key (for later re-enable),
 * so `isUsable` AND's both signals.
 */
class DeepLBackend(
    private val keyProvider: () -> String?,
    private val enabledProvider: () -> Boolean,
) : TranslationBackend, QuotaAware {

    override val id: BackendId = "deepl"
    override val displayName: String = "DeepL"
    override val priority: Int = 10
    override val requiresInternet: Boolean = true
    override val isDegradedFallback: Boolean = false

    private val client = OkHttpClient()
    private val gson = Gson()

    override fun isUsable(source: String, target: String): Boolean =
        enabledProvider() && !keyProvider().isNullOrBlank()

    override suspend fun translate(text: String, source: String, target: String): String =
        withContext(Dispatchers.IO) {
            val apiKey = keyProvider()?.takeIf { it.isNotBlank() }
                ?: throw IOException("DeepL API key not configured")

            val host = if (apiKey.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
            val body = gson.toJson(
                mapOf(
                    "text"        to listOf(text),
                    "target_lang" to toDeepLCode(target),
                    "source_lang" to toDeepLCode(source),
                )
            )
            val request = Request.Builder()
                .url("https://$host/v2/translate")
                .addHeader("Authorization", "DeepL-Auth-Key $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                when (response.code) {
                    403 -> throw DeepLAuthException()
                    456 -> throw DeepLQuotaExceededException()
                    else -> if (!response.isSuccessful) throw IOException("DeepL error ${response.code}")
                }
                val responseBody = response.body?.string() ?: throw IOException("Empty response from DeepL")
                gson.fromJson(responseBody, DeepLResponse::class.java)
                    .translations.firstOrNull()?.text
                    ?: throw IOException("No translation in DeepL response")
            }
        }

    /** Quota readout via DeepL's `/v2/usage` endpoint is not yet wired —
     *  returns null so callers treat it as "unknown". The seam is in
     *  place; the implementation will land alongside the Settings UI
     *  meter that consumes it. */
    override suspend fun currentQuota(): QuotaStatus? = null

    override fun close() {
        // Background daemon thread — evictAll() synchronously writes a
        // TLS close-notify on any live socket, which StrictMode flags as
        // NetworkOnMainThreadException when close() is invoked from the
        // UI thread. Fire-and-forget on a daemon to dodge that.
        val c = client
        Thread {
            c.dispatcher.executorService.shutdown()
            c.connectionPool.evictAll()
        }.apply { isDaemon = true; name = "DeepLBackend-close" }.start()
    }

    private fun toDeepLCode(mlKitCode: String): String = when (mlKitCode) {
        "ja" -> "JA"
        "en" -> "EN"
        "zh" -> "ZH"
        "ko" -> "KO"
        "fr" -> "FR"
        "de" -> "DE"
        "es" -> "ES"
        "it" -> "IT"
        "pt" -> "PT-PT"
        "ru" -> "RU"
        else -> mlKitCode.uppercase(Locale.ROOT)
    }

    private data class DeepLResponse(val translations: List<Translation>) {
        data class Translation(val text: String = "", val detected_source_language: String = "")
    }
}
