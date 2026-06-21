package com.playtranslate.net

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Both PtHttp interceptors' behaviour + the factory wiring. Together these prove
 * enforcement lives at the request boundary on every client: standard clients
 * are https-only, and the OpenAI custom-endpoint client enforces
 * [CustomEndpointPolicy] (https anywhere; http only to loopback/LAN) — not just
 * in the settings UI.
 */
class PtHttpTest {

    // ── HttpsOnlyInterceptor (standard clients) ──

    @Test fun `https-only interceptor rejects a cleartext http request`() {
        assertRejected(clientWith(HttpsOnlyInterceptor), "http://evil.example/x")
    }

    @Test fun `https-only interceptor allows an https request`() {
        assertEquals(200, code(clientWith(HttpsOnlyInterceptor), "https://good.example/x"))
    }

    // ── CustomEndpointInterceptor (OpenAI custom endpoint) ──

    @Test fun `custom-endpoint interceptor rejects http to a public host`() {
        assertRejected(clientWith(CustomEndpointInterceptor), "http://api.openai.com/v1")
    }

    @Test fun `custom-endpoint interceptor allows http to a LAN host`() {
        assertEquals(200, code(clientWith(CustomEndpointInterceptor), "http://192.168.1.50:1234/v1"))
    }

    @Test fun `custom-endpoint interceptor allows https anywhere`() {
        assertEquals(200, code(clientWith(CustomEndpointInterceptor), "https://api.openai.com/v1"))
    }

    // ── Factory wiring ──

    @Test fun `clientBuilder installs the https-only network interceptor`() {
        assertTrue(PtHttp.clientBuilder().build().networkInterceptors.contains(HttpsOnlyInterceptor))
    }

    @Test fun `customEndpointBuilder installs the policy interceptor and disables ssl redirects`() {
        val client = PtHttp.customEndpointBuilder().build()
        assertTrue(client.networkInterceptors.contains(CustomEndpointInterceptor))
        assertFalse(client.networkInterceptors.contains(HttpsOnlyInterceptor))
        assertFalse(client.followSslRedirects)
    }

    // ── helpers ──

    private fun assertRejected(client: OkHttpClient, url: String) {
        try {
            client.newCall(Request.Builder().url(url).build()).execute()
            fail("expected IOException for $url")
        } catch (e: IOException) {
            // expected
        }
    }

    private fun code(client: OkHttpClient, url: String): Int =
        client.newCall(Request.Builder().url(url).build()).execute().use { it.code }

    /** [guard] in front of a canned 200 responder, so its accept/reject logic
     *  runs against a real OkHttp chain with no network. (Added as an
     *  application interceptor for the test; production installs both as
     *  *network* interceptors, where they also cover redirect hops.) */
    private fun clientWith(guard: Interceptor): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(guard)
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("ok".toResponseBody())
                    .build()
            }
            .build()
}
