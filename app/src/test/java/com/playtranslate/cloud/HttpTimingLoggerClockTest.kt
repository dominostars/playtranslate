package com.playtranslate.cloud

import com.playtranslate.translation.HttpCallClock
import com.playtranslate.translation.HttpTimingLogger
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/**
 * [HttpTimingLogger]'s hook for PlayTranslate Cloud's clock line: a request
 * tagged with an [HttpCallClock] gets whether its connection was reused and
 * how long its body took to write filled in; a request without one goes
 * through the same events untouched. The events are driven by hand, since
 * the test clients answer from an interceptor and never reach a connection.
 */
@RunWith(RobolectricTestRunner::class)
class HttpTimingLoggerClockTest {

    private val okhttp = OkHttpClient()

    private fun call(tagged: HttpCallClock?): Call {
        val builder = Request.Builder().url("https://pt.example.test/v1/frame")
        if (tagged != null) builder.tag(HttpCallClock::class.java, tagged)
        return okhttp.newCall(builder.build())
    }

    /** Never asked for anything but its protocol by the logger. */
    private val connection = object : Connection {
        override fun route(): Route = throw UnsupportedOperationException()
        override fun socket(): Socket = throw UnsupportedOperationException()
        override fun handshake(): Handshake? = null
        override fun protocol(): Protocol = Protocol.HTTP_1_1
    }

    private val address = InetSocketAddress.createUnresolved("pt.example.test", 443)

    @Test fun `a pooled connection is reported reused and a body's write time is filled in`() {
        val clock = HttpCallClock()
        val c = call(clock)
        val logger = HttpTimingLogger("PtCloud")
        logger.callStart(c)
        // No connectStart: the connection came out of the pool.
        logger.connectionAcquired(c, connection)
        assertEquals(true, clock.connectionReused)
        assertNull("not until the body ends", clock.uploadMs)
        logger.requestBodyStart(c)
        logger.requestBodyEnd(c, 1_000)
        assertTrue("upload ${clock.uploadMs}", clock.uploadMs!! >= 0)
    }

    @Test fun `a connection this call opened is reported not reused`() {
        val clock = HttpCallClock()
        val c = call(clock)
        val logger = HttpTimingLogger("PtCloud")
        logger.callStart(c)
        logger.connectStart(c, address, Proxy.NO_PROXY)
        logger.connectEnd(c, address, Proxy.NO_PROXY, Protocol.HTTP_1_1)
        logger.connectionAcquired(c, connection)
        assertEquals(false, clock.connectionReused)
    }

    @Test fun `a request without a clock, or with a tag of another type, goes through every event untouched`() {
        for (c in listOf(call(tagged = null), okhttp.newCall(Request.Builder().url("https://x.test/").tag("a string tag").build()))) {
            val logger = HttpTimingLogger("Gemini")
            logger.callStart(c)
            logger.connectStart(c, address, Proxy.NO_PROXY)
            logger.connectionAcquired(c, connection)
            logger.requestBodyStart(c)
            logger.requestBodyEnd(c, 10)
            logger.callEnd(c)
            assertNull(c.request().tag(HttpCallClock::class.java))
        }
    }
}
