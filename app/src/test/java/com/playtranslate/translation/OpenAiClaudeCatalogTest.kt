package com.playtranslate.translation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetSocketAddress

/**
 * The Claude preset's catalog path. Anthropic's OpenAI-compatible layer
 * covers chat-completions only; `/v1/models` is the native endpoint, which
 * rejects a Bearer token and reads `x-api-key` plus `anthropic-version`,
 * and answers in the native shape (`created_at` as a timestamp string, no
 * `owned_by`). The model picker has to send those headers and parse that
 * shape, or the preset offers no models at all.
 */
@RunWith(RobolectricTestRunner::class)
class OpenAiClaudeCatalogTest {

    private var server: HttpServer? = null

    @After
    fun tearDown() {
        server?.stop(0)
    }

    @Test
    fun claudeCatalogHeadersAreApiKeyAndVersion() {
        val headers = OnlineBackendFactory.modelsAuthHeadersFor(OpenAiPreset.CLAUDE, "sk-ant-k")
        assertEquals("sk-ant-k", headers["x-api-key"])
        assertEquals(OnlineServiceStore.CLAUDE_API_VERSION, headers["anthropic-version"])
        assertFalse(headers.containsKey("Authorization"))
    }

    /** The keyed presets' catalog dialect. A keyless preset never reaches
     *  this function: it has no key to put in a header
     *  ([keylessCatalogListsWithNoAuthHeader]). */
    @Test
    fun everyOtherKeyedPresetKeepsTheBearerToken() {
        OpenAiPreset.entries
            .filter { it != OpenAiPreset.CLAUDE && it.account.requiresKey }
            .forEach { preset ->
                assertEquals(
                    preset.name,
                    mapOf("Authorization" to "Bearer k"),
                    OnlineBackendFactory.modelsAuthHeadersFor(preset, "k"),
                )
            }
    }

    /** Index-Translate's /v1/models answers with no key at all; the picker
     *  lists it with no stored key and sends no Authorization. */
    @Test
    fun keylessCatalogListsWithNoAuthHeader() {
        val authHeaders = mutableListOf<String?>()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/models") { exchange ->
            authHeaders += exchange.requestHeaders.getFirst("Authorization")
            val bytes = KEYLESS_MODELS_BODY.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        server = http
        val base = "http://127.0.0.1:${http.address.port}"
        val context = ApplicationProvider.getApplicationContext<Context>()
        val backend = OpenAiBackend(
            id = "index-test",
            displayName = "Bilibili Index-Translate",
            priority = 15,
            keyProvider = { "" },
            enabledProvider = { true },
            modelProvider = { "Index-Translate-35B-A3B" },
            baseUrlProvider = { base },
            modelsAuthHeaders = { OnlineBackendFactory.modelsAuthHeadersFor(OpenAiPreset.INDEX_TRANSLATE, it) },
            account = { OpenAiPreset.INDEX_TRANSLATE.account },
            usageTracker = UsageTracker(
                context.getSharedPreferences("test_prefs", Context.MODE_PRIVATE), "index-test",
            ),
            applyOwnedByFilter = { false },
        )
        assertEquals(listOf("Index-Translate-35B-A3B"), runBlocking { backend.listModels() })
        assertEquals(listOf<String?>(null), authHeaders)
    }

    /** A stand-in for api.anthropic.com/v1/models: 401 unless `x-api-key`
     *  is present, native list shape when it is. */
    private fun startNativeModelsEndpoint(): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/models") { exchange ->
            val keyed = exchange.requestHeaders.containsKey("x-api-key")
            val body = if (keyed) NATIVE_MODELS_BODY else NATIVE_401_BODY
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(if (keyed) 200 else 401, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}"
    }

    private fun claudeBackend(base: String): OpenAiBackend {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("test_prefs", Context.MODE_PRIVATE)
        return OpenAiBackend(
            id = "claude-test",
            displayName = "Claude",
            priority = 15,
            keyProvider = { "sk-ant-test" },
            enabledProvider = { true },
            modelProvider = { "claude-opus-5" },
            baseUrlProvider = { base },
            modelsUrlProvider = { base },
            modelsAuthHeaders = { OnlineBackendFactory.modelsAuthHeadersFor(OpenAiPreset.CLAUDE, it) },
            usageTracker = UsageTracker(prefs, "claude-test"),
            applyOwnedByFilter = { false },
        )
    }

    /** The picker reaches the native list with the Claude headers and
     *  keeps the API's own order: the native entries carry no `created`
     *  integer, so the newest-first sort has nothing to reorder by. */
    @Test
    fun listModelsParsesTheNativeShapeInApiOrder() {
        val base = startNativeModelsEndpoint()
        val models = runBlocking { claudeBackend(base).listModels() }
        assertEquals(
            listOf("claude-opus-5", "claude-sonnet-5", "claude-haiku-4-5-20251001"),
            models,
        )
    }

    companion object {
        private const val NATIVE_MODELS_BODY =
            """{"data":[""" +
                """{"type":"model","id":"claude-opus-5","display_name":"Claude Opus 5","created_at":"2026-04-01T00:00:00Z"},""" +
                """{"type":"model","id":"claude-sonnet-5","display_name":"Claude Sonnet 5","created_at":"2026-03-01T00:00:00Z"},""" +
                """{"type":"model","id":"claude-haiku-4-5-20251001","display_name":"Claude Haiku 4.5","created_at":"2025-10-01T00:00:00Z"}""" +
                """],"has_more":false,"first_id":"claude-opus-5","last_id":"claude-haiku-4-5-20251001"}"""
        /** What index-translate.bilibili.com/v1/models answered on 2026-10-14. */
        private const val KEYLESS_MODELS_BODY =
            """{"object":"list","data":[{"id":"Index-Translate-35B-A3B","object":"model","created":1727712000,"owned_by":"index-translate"}]}"""
        private const val NATIVE_401_BODY =
            """{"type":"error","error":{"type":"authentication_error","message":"x-api-key header is required"}}"""
    }
}
