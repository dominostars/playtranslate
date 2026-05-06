package com.playtranslate.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Google's unofficial `translate.googleapis.com/translate_a/single`
 * endpoint with `client=gtx`. No API key required. Used by many
 * open-source translation tools.
 *
 * (Historically this lived under the name "Lingva"; the original
 * implementation went through a Lingva proxy and was switched to the
 * gtx endpoint directly for lower latency. The class name now matches
 * the reality.)
 */
class GoogleGtxBackend : TranslationBackend {

    override val id: BackendId = "google-gtx"
    override val priority: Int = 20
    override val requiresInternet: Boolean = true
    override val isDegradedFallback: Boolean = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    override fun isUsable(source: String, target: String): Boolean = true

    override suspend fun translate(text: String, source: String, target: String): String =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single" +
                      "?client=gtx&sl=$source&tl=$target&dt=t&q=$encoded"

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

    override fun close() {
        // Background daemon thread — see DeepLBackend.close() for the
        // NetworkOnMainThreadException rationale.
        val c = client
        Thread {
            c.dispatcher.executorService.shutdown()
            c.connectionPool.evictAll()
        }.apply { isDaemon = true; name = "GoogleGtxBackend-close" }.start()
    }
}
