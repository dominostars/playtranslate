package com.playtranslate

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Thrown when the DeepL free quota for the month has been exhausted (HTTP 456). */
class DeepLQuotaExceededException : IOException("DeepL monthly quota exceeded")

/** Thrown when the API key is rejected (HTTP 403). */
class DeepLAuthException : IOException("Invalid DeepL API key")

/**
 * Translates text via the DeepL REST API.
 *
 * Free API keys end with ":fx" and use api-free.deepl.com.
 * Paid API keys use api.deepl.com.  The correct host is chosen automatically.
 *
 * [sourceLang] and [targetLang] use ML Kit language codes (e.g. "ja", "en").
 */
class DeepLTranslator(
    val apiKey: String,
    val sourceLang: String,
    val targetLang: String
) {
    private val client = OkHttpClient()
    private val gson   = Gson()
    private val host   = if (apiKey.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"

    fun close() {
        // Background daemon thread — see LingvaTranslator.close() for the
        // NetworkOnMainThreadException rationale. Same OkHttp shutdown path.
        val c = client
        Thread {
            c.dispatcher.executorService.shutdown()
            c.connectionPool.evictAll()
        }.apply { isDaemon = true; name = "DeepLTranslator-close" }.start()
    }

    suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        val body = gson.toJson(
            mapOf(
                "text"        to listOf(text),
                "target_lang" to toDeepLCode(targetLang),
                "source_lang" to toDeepLCode(sourceLang)
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
        else -> mlKitCode.uppercase(java.util.Locale.ROOT)
    }

    private data class DeepLResponse(val translations: List<Translation>) {
        data class Translation(val text: String = "", val detected_source_language: String = "")
    }
}
