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

    /** A provider at 127.0.0.1 whose /models answers [codeWithKey] when a
     *  Bearer header is present and [codeWithoutKey] when it is not. */
    private fun startProvider(codeWithKey: Int, codeWithoutKey: Int): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/models") { exchange ->
            val authed = exchange.requestHeaders.containsKey("Authorization")
            val code = if (authed) codeWithKey else codeWithoutKey
            val body = """{"object":"list","data":[]}""".toByteArray()
            exchange.sendResponseHeaders(code, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}"
    }

    private fun validate(baseUrl: String, key: String): KeyStatus {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val backend = OnlineBackendFactory.build(
            context,
            context.getSharedPreferences("test_prefs", Context.MODE_PRIVATE),
            OnlineServiceInstance(
                id = "test-instance",
                type = ServiceType.OPENAI,
                enabled = true,
                preset = OpenAiPreset.CUSTOM,
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
}
