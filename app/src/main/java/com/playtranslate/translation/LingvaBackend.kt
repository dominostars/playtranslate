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
 * "Lingva" backend — historically a Lingva-proxy translator, currently
 * pointed at Google's `translate.googleapis.com/translate_a/single`
 * endpoint with `client=gtx` directly for lower latency. The class
 * name intentionally matches the user-facing brand and the future
 * intent (we may switch back to a real Lingva instance), even though
 * today the implementation hits the gtx endpoint.
 *
 * No API key required.
 *
 * [enabledProvider] reflects the user's explicit on/off state from
 * Settings — the registry's waterfall skips this backend when disabled.
 */
class LingvaBackend(
    private val enabledProvider: () -> Boolean,
) : TranslationBackend {

    override val id: BackendId = "lingva"
    override val displayName: String = "Lingva"
    override val priority: Int = 20
    override val requiresInternet: Boolean = true
    override val isDegradedFallback: Boolean = false

    override val status: BackendStatus = BackendStatus.Info("No API key required")

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    override fun isUsable(source: String, target: String): Boolean = enabledProvider()

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
        }.apply { isDaemon = true; name = "LingvaBackend-close" }.start()
    }
}
