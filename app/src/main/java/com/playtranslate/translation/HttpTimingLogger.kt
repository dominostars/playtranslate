package com.playtranslate.translation

import android.util.Log
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * OkHttp [EventListener] that logs the per-phase latency of a single
 * HTTP call. Built for diagnosing the "cold first request takes
 * forever" question on the new LLM backends — DNS resolution, TLS
 * handshake, and server first-byte are all hidden inside `execute()`
 * otherwise.
 *
 * The factory returns a fresh instance per call so the start
 * timestamps are per-call (a shared instance would mix interleaved
 * calls' timestamps on the same OkHttpClient).
 *
 * Phase boundaries: callStart → dnsStart → dnsEnd → connectStart →
 * secureConnectStart → secureConnectEnd → connectEnd →
 * requestHeadersStart → requestHeadersEnd → requestBodyStart →
 * requestBodyEnd → responseHeadersStart (≈ server first byte) →
 * responseHeadersEnd → responseBodyStart → responseBodyEnd → callEnd.
 *
 * [tag] disambiguates which backend logged the line ("Gemini",
 * "OpenAI", etc.). Output looks like:
 *   "Gemini: dns=42ms connect=210ms tls=180ms ttfb=1380ms total=1745ms"
 *
 * Connection reuse is reported as `connect=reused` so a warm second
 * call is visibly distinct from the cold first call. Failures log via
 * [callFailed] with the elapsed-so-far.
 */
internal class HttpTimingLogger(
    private val tag: String,
) : EventListener() {

    private var callStart = 0L
    private var dnsStart = 0L
    private var dnsMs = -1L
    private var connectStart = 0L
    private var connectMs = -1L
    private var tlsStart = 0L
    private var tlsMs = -1L
    private var responseHeadersStart = 0L
    private var ttfbMs = -1L
    private var connectionReused = false

    override fun callStart(call: Call) {
        callStart = System.nanoTime()
        Log.i(tag, "phase:callStart host=${call.request().url.host}")
    }

    override fun dnsStart(call: Call, domainName: String) {
        dnsStart = System.nanoTime()
        Log.i(tag, "phase:dnsStart at=${ms(callStart)}ms")
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        dnsMs = ms(dnsStart)
        Log.i(tag, "phase:dnsEnd took=${dnsMs}ms addrs=${inetAddressList.size}")
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectStart = System.nanoTime()
        Log.i(tag, "phase:connectStart at=${ms(callStart)}ms")
    }

    override fun secureConnectStart(call: Call) {
        tlsStart = System.nanoTime()
        Log.i(tag, "phase:secureConnectStart at=${ms(callStart)}ms")
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        tlsMs = ms(tlsStart)
        Log.i(tag, "phase:secureConnectEnd took=${tlsMs}ms")
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        connectMs = ms(connectStart)
        Log.i(tag, "phase:connectEnd took=${connectMs}ms protocol=$protocol")
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        // If we never saw connectStart, the connection was pulled out of
        // OkHttp's pool — warm reuse. Mark it so the summary line can
        // distinguish cold from warm without ambiguity.
        if (connectStart == 0L) connectionReused = true
        Log.i(tag, "phase:connectionAcquired at=${ms(callStart)}ms reused=$connectionReused")
    }

    override fun requestHeadersStart(call: Call) {
        Log.i(tag, "phase:requestHeadersStart at=${ms(callStart)}ms")
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        Log.i(tag, "phase:requestHeadersEnd at=${ms(callStart)}ms")
    }

    override fun requestBodyStart(call: Call) {
        Log.i(tag, "phase:requestBodyStart at=${ms(callStart)}ms")
    }

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        Log.i(tag, "phase:requestBodyEnd at=${ms(callStart)}ms bytes=$byteCount")
    }

    override fun responseHeadersStart(call: Call) {
        responseHeadersStart = System.nanoTime()
        // Time to first byte from request-end to response-headers-start
        // is what users feel as "the server is thinking" — record it.
        ttfbMs = ms(callStart)
        Log.i(tag, "phase:responseHeadersStart at=${ttfbMs}ms")
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        Log.i(tag, "phase:responseHeadersEnd at=${ms(callStart)}ms code=${response.code}")
    }

    override fun responseBodyStart(call: Call) {
        Log.i(tag, "phase:responseBodyStart at=${ms(callStart)}ms")
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        Log.i(tag, "phase:responseBodyEnd at=${ms(callStart)}ms bytes=$byteCount")
    }

    override fun callEnd(call: Call) {
        Log.i(tag, summary("ok"))
    }

    override fun callFailed(call: Call, ioe: IOException) {
        Log.w(tag, summary("err=${ioe.javaClass.simpleName}:${ioe.message}"))
    }

    private fun summary(status: String): String {
        val total = ms(callStart)
        return buildString {
            append(status)
            append(" total=").append(total).append("ms")
            if (connectionReused) {
                append(" connect=reused")
            } else {
                if (dnsMs >= 0) append(" dns=").append(dnsMs).append("ms")
                if (connectMs >= 0) append(" connect=").append(connectMs).append("ms")
                if (tlsMs >= 0) append(" tls=").append(tlsMs).append("ms")
            }
            if (ttfbMs >= 0) append(" ttfb=").append(ttfbMs).append("ms")
        }
    }

    private fun ms(startNanos: Long): Long {
        if (startNanos == 0L) return -1
        return (System.nanoTime() - startNanos) / 1_000_000
    }

    companion object {
        fun factory(tag: String): EventListener.Factory = object : EventListener.Factory {
            override fun create(call: Call): EventListener = HttpTimingLogger(tag)
        }
    }
}

