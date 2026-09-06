package com.playtranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.translation.KeyStatus
import com.playtranslate.translation.KeyValidator
import com.playtranslate.translation.OnlineBackendFactory
import com.playtranslate.translation.OnlineServiceInstance
import com.playtranslate.translation.OpenAiPreset
import com.playtranslate.translation.ServiceType
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetSocketAddress

/**
 * [KeyValidator.validateKey] on the OpenAI-compatible backend, against a
 * stand-in provider that we can make behave like a real one.
 *
 * The case that matters is [openEndpointCannotVerifyKey]. The probe asks
 * `GET {base}/models` and used to read any 2xx as "key accepted" — but that
 * endpoint is served WITHOUT authentication by a good number of
 * OpenAI-compatible hosts (OpenRouter, NVIDIA, DeepInfra, the HF router…),
 * where the same 2xx comes back for a key of pure gibberish. Reporting Ok
 * there is a verification we never performed: the user saves a dead key and
 * finds out at translate time. A 2xx may only be believed once the endpoint
 * has shown it would refuse us without a key.
 */
@RunWith(RobolectricTestRunner::class)
class OpenAiValidateKeyTest {

    private var server: HttpServer? = null

    @After
    fun tearDown() {
        server?.stop(0)
    }

    /** A provider at 127.0.0.1 whose /models answers [codeWithKey] when the
     *  [keyHeader] is present and [codeWithoutKey] when it is not. Bearer by
     *  default; `x-api-key` stands in for Anthropic's native /models. */
    private fun startProvider(
        codeWithKey: Int,
        codeWithoutKey: Int,
        keyHeader: String = "Authorization",
        bodyWithKey: String = """{"object":"list","data":[]}""",
    ): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/models") { exchange ->
            val authed = exchange.requestHeaders.containsKey(keyHeader)
            val code = if (authed) codeWithKey else codeWithoutKey
            val body = (if (authed) bodyWithKey else """{"object":"list","data":[]}""").toByteArray()
            exchange.sendResponseHeaders(code, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}"
    }

    /** Validates through the factory-built backend for [preset], the way the
     *  settings page does with its unsaved-page shell; [baseUrl] rides in as
     *  the page's override, which is how a pinned preset reaches the local
     *  stand-in instead of its canonical host. */
    private fun validate(
        baseUrl: String,
        key: String,
        preset: OpenAiPreset = OpenAiPreset.CUSTOM,
    ): KeyStatus {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val backend = OnlineBackendFactory.build(
            context,
            context.getSharedPreferences("test_prefs", Context.MODE_PRIVATE),
            OnlineServiceInstance(
                id = "test-instance",
                type = ServiceType.OPENAI,
                enabled = true,
                preset = preset,
                baseUrl = baseUrl,
            ),
            live = false,
        )
        return runBlocking { (backend as KeyValidator).validateKey(key, baseUrl) }
    }

    /** The endpoint authenticates and took our key: a 2xx means what it says. */
    @Test
    fun gatedEndpointAcceptsGoodKey() {
        val base = startProvider(codeWithKey = 200, codeWithoutKey = 401)
        assertEquals(KeyStatus.Ok, validate(base, "good-key"))
    }

    /** The endpoint authenticates and rejected our key. */
    @Test
    fun gatedEndpointRejectsBadKey() {
        val base = startProvider(codeWithKey = 401, codeWithoutKey = 401)
        assertEquals(KeyStatus.Invalid("HTTP 401"), validate(base, "bad-key"))
    }

    /** The hole: /models is public, so it 200s for a key that is nonsense.
     *  The verdict must be "we could not tell" ([KeyStatus.Unreachable], which
     *  the save path treats as save-anyway), never [KeyStatus.Ok]. */
    @Test
    fun openEndpointCannotVerifyKey() {
        val base = startProvider(codeWithKey = 200, codeWithoutKey = 200)
        assertEquals(KeyStatus.Unreachable, validate(base, "total-gibberish"))
    }

    /** Claude: /models is Anthropic's native endpoint, which reads
     *  `x-api-key` and 401s a Bearer token. The preset's own catalog headers
     *  have to reach it for a good key to validate at all. */
    @Test
    fun claudePresetProbesModelsWithApiKeyHeader() {
        val base = startProvider(codeWithKey = 200, codeWithoutKey = 401, keyHeader = "x-api-key")
        assertEquals(KeyStatus.Ok, validate(base, "sk-ant-good", OpenAiPreset.CLAUDE))
    }

    /** The same endpoint through a Bearer-only preset: the key never
     *  arrives and the endpoint rejects the probe. This is what every valid
     *  Claude key would have been reported as without the preset's headers. */
    @Test
    fun bearerOnlyPresetIsRejectedByApiKeyEndpoint() {
        val base = startProvider(codeWithKey = 200, codeWithoutKey = 401, keyHeader = "x-api-key")
        assertEquals(KeyStatus.Invalid("HTTP 401"), validate(base, "sk-ant-good", OpenAiPreset.CUSTOM))
    }

    /** A pinned provider's catalog endpoint answering 400 to a keyed GET is
     *  talking about the key. Before this pin it fell into the "could not
     *  tell" bucket and the key saved silently. */
    @Test
    fun pinnedPresetTreats400AsRejection() {
        val base = startProvider(codeWithKey = 400, codeWithoutKey = 401)
        assertEquals(KeyStatus.Invalid("HTTP 400"), validate(base, "some-key", OpenAiPreset.MISTRAL))
    }

    /** A user-typed endpoint keeps the shrug: its 400 may mean no more than
     *  "no such route", and blocking the save over that is the wrong call. */
    @Test
    fun customEndpointKeeps400Lenient() {
        val base = startProvider(codeWithKey = 400, codeWithoutKey = 401)
        assertEquals(KeyStatus.Unreachable, validate(base, "some-key", OpenAiPreset.CUSTOM))
    }

    /** A rate-limited probe proves nothing about the key, pinned or not. */
    @Test
    fun pinnedPreset429StaysLenient() {
        val base = startProvider(codeWithKey = 429, codeWithoutKey = 401)
        assertEquals(KeyStatus.Unreachable, validate(base, "some-key", OpenAiPreset.MISTRAL))
    }

    /** Anthropic's 400 for a valid key not scoped to a workspace carries the
     *  condition-specific alert: re-checking the key would send the user in
     *  circles, and a key created inside a workspace is the fix. */
    @Test
    fun claudeWorkspaceScope400CarriesExplanation() {
        val base = startProvider(
            codeWithKey = 400, codeWithoutKey = 401, keyHeader = "x-api-key",
            bodyWithKey = WORKSPACE_SCOPE_400_BODY,
        )
        assertEquals(
            KeyStatus.Invalid("HTTP 400", R.string.llm_backend_key_needs_workspace_scope),
            validate(base, "sk-ant-account-level", OpenAiPreset.CLAUDE),
        )
    }

    /** Any other Claude 400 is a plain rejection with the generic alert. */
    @Test
    fun claudeOther400HasNoExplanation() {
        val base = startProvider(
            codeWithKey = 400, codeWithoutKey = 401, keyHeader = "x-api-key",
            bodyWithKey = """{"type":"error","error":{"type":"invalid_request_error","message":"something else"}}""",
        )
        assertEquals(KeyStatus.Invalid("HTTP 400"), validate(base, "sk-ant-x", OpenAiPreset.CLAUDE))
    }

    private companion object {
        /** Verbatim from the Thor's log, 2026-09-06. */
        const val WORKSPACE_SCOPE_400_BODY =
            """{"type":"error","error":{"type":"invalid_request_error","message":"This API key is not scoped to a workspace, so this request must include the anthropic-workspace-id header with the ID of the workspace to use. Add the header, or use an API key that is scoped to a workspace."}}"""
    }
}
