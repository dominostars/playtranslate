package com.playtranslate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Translates text via the unofficial Google Translate API endpoint.
 * No API key or account required.
 *
 * Uses translate.googleapis.com directly (same backend as Lingva but no proxy
 * hop) for much lower latency.  The `client=gtx` parameter is the same one
 * used by many open-source translation tools.
 */
class LingvaTranslator(val sourceLang: String, val targetLang: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun close() {
        // Dispatch to a background daemon thread — evictAll() synchronously
        // writes a TLS close-notify on any live socket, which StrictMode
        // flags as NetworkOnMainThreadException when close() is invoked from
        // the UI thread (e.g. during a source-language switch via
        // CaptureService.ensureLanguageManagersFor).
        val c = client
        Thread {
            c.dispatcher.executorService.shutdown()
            c.connectionPool.evictAll()
        }.apply { isDaemon = true; name = "LingvaTranslator-close" }.start()
    }

    suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(text, "UTF-8")
        val url = "https://translate.googleapis.com/translate_a/single" +
                  "?client=gtx&sl=$sourceLang&tl=$targetLang&dt=t&q=$encoded"

        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Translate error ${response.code}")
            val body = response.body?.string() ?: throw IOException("Empty response")

            // Response: [[["translated","original",...], ...], null, "ja", ...]
            val chunks = JSONArray(body).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until chunks.length()) {
                val chunk = chunks.optJSONArray(i)
                if (chunk != null) sb.append(chunk.optString(0))
            }
            val result = sb.toString()
            if (result.isBlank()) throw IOException("Blank translation in response")
            result
        }
    }
}
