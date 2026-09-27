package com.playtranslate.translation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * [classifyBackendFailure] and the kinds the online backends' own
 * exceptions carry. The translation-error pill words whatever comes out
 * of here, so a transport exception read as the service's fault (or the
 * reverse) is a wrong message on the user's screen.
 */
class BackendFailureTest {

    @Test fun `timeouts are TIMEOUT, OkHttp's call timeout included`() {
        assertEquals(BackendFailureKind.TIMEOUT, classifyBackendFailure(SocketTimeoutException("read")).kind)
        assertEquals(BackendFailureKind.TIMEOUT, classifyBackendFailure(InterruptedIOException("timeout")).kind)
    }

    @Test fun `other transport exceptions are UNREACHABLE`() {
        for (e in listOf(
            UnknownHostException("generativelanguage.googleapis.com"),
            ConnectException("refused"),
            SSLHandshakeException("portal"),
            IOException("unexpected end of stream"),
        )) {
            assertEquals(e.javaClass.simpleName, BackendFailureKind.UNREACHABLE, classifyBackendFailure(e).kind)
        }
    }

    @Test fun `a non-IO exception, such as a parser's on a garbled body, is BAD_RESPONSE`() {
        assertEquals(
            BackendFailureKind.BAD_RESPONSE,
            classifyBackendFailure(IllegalArgumentException("Unexpected JSON token")).kind,
        )
    }

    @Test fun `our exceptions carry their own kind, never the transport fallback`() {
        val cases = listOf(
            GeminiAuthException() to BackendFailureKind.AUTH,
            GeminiRateLimitException(BackendFailure(BackendFailureKind.DAILY_QUOTA, 429)) to
                BackendFailureKind.DAILY_QUOTA,
            OpenAiAuthException() to BackendFailureKind.AUTH,
            OpenAiRateLimitException(BackendFailure(BackendFailureKind.BILLING, 429)) to
                BackendFailureKind.BILLING,
            DeepLAuthException() to BackendFailureKind.AUTH,
            DeepLRateLimitException() to BackendFailureKind.RATE_LIMITED,
            DeepLQuotaExceededException() to BackendFailureKind.MONTHLY_QUOTA,
            LingvaRateLimitException(403) to BackendFailureKind.RATE_LIMITED,
            BatchParseException("envelope") to BackendFailureKind.BAD_RESPONSE,
            StructuralFailureException("x", BackendFailure(BackendFailureKind.BILLING, 400)) to
                BackendFailureKind.BILLING,
        )
        for ((e, kind) in cases) {
            // All of these ARE IOExceptions: the ClassifiedFailure branch
            // must win over the transport one.
            assertTrue(e is IOException)
            assertEquals(e.javaClass.simpleName, kind, classifyBackendFailure(e).kind)
        }
        assertEquals(403, classifyBackendFailure(LingvaRateLimitException(403)).httpCode)
    }

    @Test fun `httpStatusFailure splits server errors from refusals and keeps the code`() {
        assertEquals(BackendFailure(BackendFailureKind.SERVER_ERROR, 500), httpStatusFailure(500))
        assertEquals(BackendFailure(BackendFailureKind.SERVER_ERROR, 503), httpStatusFailure(503))
        assertEquals(BackendFailure(BackendFailureKind.REJECTED, 400), httpStatusFailure(400))
        assertEquals(BackendFailure(BackendFailureKind.REJECTED, 404), httpStatusFailure(404))
    }

    @Test fun `only the two no-answer kinds are transport`() {
        val transport = BackendFailureKind.entries.filter { it.isTransport }
        assertEquals(setOf(BackendFailureKind.TIMEOUT, BackendFailureKind.UNREACHABLE), transport.toSet())
        assertFalse(BackendFailureKind.SERVER_ERROR.isTransport)
    }

    @Test fun `DeepL's batch cap is refused before anything is sent, so it is no server answer`() {
        // Every other BatchParseException follows a reply, which the
        // registry counts as the server reached.
        val deepl = DeepLBackend(keyProvider = { "key" }, enabledProvider = { true })
        try {
            runBlocking { deepl.translateBatch(List(51) { "text $it" }, "ja", "en") }
            fail("expected the batch cap")
        } catch (e: BatchParseException) {
            assertFalse(e.serverAnswered)
        }
        assertTrue(BatchParseException("malformed reply").serverAnswered)
    }
}
