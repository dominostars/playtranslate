package com.playtranslate.net

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException

/**
 * Central factory for the app's OkHttp clients. EVERY client is built here and
 * carries a request-boundary network interceptor that enforces the app's
 * cleartext policy — there is no client that sends traffic without enforcement.
 *
 * The manifest permits cleartext app-wide, but only so the OpenAI backend can
 * reach a user-configured http endpoint (a self-hosted LLM server on the LAN,
 * which almost always speaks plain http). Enforcement therefore lives in code,
 * at the request boundary, in two shapes:
 *
 *  - [clientBuilder] — https only ([HttpsOnlyInterceptor]). For every client
 *    except the OpenAI custom endpoint.
 *  - [customEndpointBuilder] — the OpenAI custom endpoint only: https to any
 *    host, or http ONLY to a loopback/LAN address ([CustomEndpointInterceptor]
 *    + [CustomEndpointPolicy]). This is what stops the Bearer key going
 *    cleartext to a public host even if the saved URL is ever stale, migrated,
 *    or written by a non-UI path — the settings validator is a fail-fast
 *    convenience, not the security control.
 *
 * `PtHttpEnforcementTest` fails the build if any client is constructed with a
 * raw OkHttp builder instead of via this factory, so the safe default can't be
 * silently bypassed.
 */
object PtHttp {

    /** Builder that rejects any non-https hop ([HttpsOnlyInterceptor], a network
     *  interceptor, so it also covers redirect targets). Use for ALL clients
     *  except the OpenAI custom-endpoint backend. */
    fun clientBuilder(): OkHttpClient.Builder =
        OkHttpClient.Builder().addNetworkInterceptor(HttpsOnlyInterceptor)

    /** Builder for the OpenAI custom endpoint: enforces [CustomEndpointPolicy]
     *  at every hop (https anywhere, or http only to loopback/LAN) and disables
     *  scheme-changing redirects so an https endpoint can't be silently
     *  downgraded to http (which would leak the Bearer key). */
    fun customEndpointBuilder(): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .followSslRedirects(false)
            .addNetworkInterceptor(CustomEndpointInterceptor)
}

/**
 * Rejects any non-https request. Installed by [PtHttp.clientBuilder]; runs per
 * hop (incl. redirect targets), so it blocks a plain http request and an
 * https→http downgrade before the request is sent. Throws [IOException] so it
 * surfaces through the normal call-failure path.
 */
object HttpsOnlyInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!url.isHttps) {
            throw IOException("blocked cleartext (non-https) request to $url")
        }
        return chain.proceed(chain.request())
    }
}

/**
 * Enforces [CustomEndpointPolicy] at the request boundary for the OpenAI
 * custom-endpoint client: https to any host, or http only to a loopback/LAN
 * address. A network interceptor, so it runs on every hop (incl. a redirect
 * target). This is the runtime guard behind the settings-time policy check — it
 * holds no matter how the base URL got into prefs.
 */
object CustomEndpointInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!CustomEndpointPolicy.isAcceptable(url)) {
            throw IOException("blocked cleartext request to non-local host: $url")
        }
        return chain.proceed(chain.request())
    }
}
