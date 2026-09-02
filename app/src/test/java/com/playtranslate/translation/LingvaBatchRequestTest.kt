package com.playtranslate.translation

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicLong

/**
 * The request sequence [LingvaBackend.translateBatch] emits once a page
 * overruns the URL cap: several requests each at or under the cap,
 * sent one after another, results recombined in input order, and the
 * sequence cut short by the first failure. Robolectric because the gtx
 * body parse needs a real org.json — the plain unit-test classpath
 * stubs it, which is why the sibling cooldown tests never exercise a
 * 200. The packer's own boundaries are pinned in [LingvaBatchPackingTest].
 */
@RunWith(RobolectricTestRunner::class)
class LingvaBatchRequestTest {

    /** Twenty distinct 100-character Japanese texts. Percent-encoded a
     *  CJK character costs nine URL chars, so each param is ~900 chars
     *  and the 6 KiB cap holds six per request: four requests. */
    private val page: List<String> = List(20) { i -> "テキスト$i".padEnd(100, 'あ') }

    private fun newCooldown(clock: AtomicLong): CooldownState =
        CooldownState(context = null, backendId = "lingva", nowMs = { clock.get() })

    private fun lingvaWith(cooldown: CooldownState?, client: OkHttpClient): LingvaBackend =
        LingvaBackend(enabledProvider = { true }, cooldownState = cooldown, client = client)

    @Test fun `an over-cap page is sent as several requests, each under the cap, results in input order`() =
        runBlocking {
            val seen = ArrayList<Request>()
            val backend = lingvaWith(null, echoingClient(seen))

            val out = backend.translateBatch(page, "ja", "en")

            assertEquals(page.map { "T:$it" }, out)
            assertTrue("expected more than one request, got ${seen.size}", seen.size > 1)
            assertTrue("expected far fewer requests than texts, got ${seen.size}", seen.size < page.size / 2)
            seen.forEach { r ->
                assertTrue(
                    "request URL ${r.url.toString().length} chars exceeds the cap",
                    r.url.toString().length <= LingvaBackend.MAX_BATCH_URL_LENGTH,
                )
            }
            // The union of every request's q params is the page, in order,
            // with nothing repeated: no text travels twice.
            assertEquals(page, seen.flatMap { it.url.qs() })
        }

    @Test fun `a one-text remainder chunk is parsed with the single-q shape`() = runBlocking {
        // Nineteen texts pack as 6+6+6+1: the last request carries one
        // q, and gtx answers one q with the single-q body. A multi-q
        // parse of that body throws, which would send the whole page
        // to the per-text retry this packing exists to remove.
        val seen = ArrayList<Request>()
        val backend = lingvaWith(null, echoingClient(seen))
        val nineteen = page.take(19)

        val out = backend.translateBatch(nineteen, "ja", "en")

        assertEquals(nineteen.map { "T:$it" }, out)
        assertEquals(1, seen.last().url.qs().size)
        assertEquals(nineteen, seen.flatMap { it.url.qs() })
    }

    @Test fun `a lone oversize text travels as its own single-q request beside its neighbours`() =
        runBlocking {
            // 800 CJK characters percent-encode past the cap on their
            // own, so the packer isolates that text; its neighbours
            // can't share a request with it and go alone too. Three
            // single-q requests, three single-q parses, order kept.
            val seen = ArrayList<Request>()
            val backend = lingvaWith(null, echoingClient(seen))
            val texts = listOf("こんにちは", "長".padEnd(800, 'い'), "さようなら")

            val out = backend.translateBatch(texts, "ja", "en")

            assertEquals(texts.map { "T:$it" }, out)
            assertEquals(3, seen.size)
            seen.forEach { assertEquals(1, it.url.qs().size) }
            assertTrue(seen[1].url.toString().length > LingvaBackend.MAX_BATCH_URL_LENGTH)
            assertEquals(texts, seen.flatMap { it.url.qs() })
        }

    @Test fun `a blank single-q chunk is BatchParseException at its page index`() = runBlocking {
        val seen = ArrayList<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val qs = chain.request().url.qs()
            seen += chain.request()
            ok(chain.request(), echoBody(qs, blankAt = if (qs.size == 1) 0 else -1))
        }.build()
        val backend = lingvaWith(null, client)

        val thrown = runCatching { backend.translateBatch(page.take(19), "ja", "en") }.exceptionOrNull()
        assertTrue("expected BatchParseException, got $thrown", thrown is BatchParseException)
        assertTrue(thrown!!.message!!, "blank result at index 18" in thrown.message!!)
    }

    @Test fun `a page that fits stays one request`() = runBlocking {
        val seen = ArrayList<Request>()
        val backend = lingvaWith(null, echoingClient(seen))
        val small = listOf("こんにちは", "さようなら", "ありがとう")
        assertEquals(small.map { "T:$it" }, backend.translateBatch(small, "ja", "en"))
        assertEquals(1, seen.size)
    }

    @Test fun `a 429 on the first request stops the sequence there`() = runBlocking {
        val clock = AtomicLong(1_000_000L)
        val cooldown = newCooldown(clock)
        val seen = ArrayList<Request>()
        val backend = lingvaWith(cooldown, sequencedClient(seen, failAt = 0, code = 429))

        val thrown = runCatching { backend.translateBatch(page, "ja", "en") }.exceptionOrNull()
        assertTrue("expected LingvaRateLimitException, got $thrown", thrown is LingvaRateLimitException)
        assertEquals("later chunks must not follow a rate limit", 1, seen.size)
        assertNotNull(cooldown.unavailableUntil())
    }

    @Test fun `a 429 on a later request stops the sequence and fails the batch`() = runBlocking {
        val clock = AtomicLong(1_000_000L)
        val cooldown = newCooldown(clock)
        val seen = ArrayList<Request>()
        val backend = lingvaWith(cooldown, sequencedClient(seen, failAt = 1, code = 429))

        val thrown = runCatching { backend.translateBatch(page, "ja", "en") }.exceptionOrNull()
        assertTrue("expected LingvaRateLimitException, got $thrown", thrown is LingvaRateLimitException)
        assertEquals("exactly the successful chunk plus the failing one", 2, seen.size)
        assertEquals(clock.get() + 60_000L, cooldown.unavailableUntil())
    }

    @Test fun `shape drift on a later request is BatchParseException and does not cool down`() = runBlocking {
        val clock = AtomicLong(1_000_000L)
        val cooldown = newCooldown(clock)
        val seen = ArrayList<Request>()
        // Second request answers 200 with an empty top-level array.
        val backend = lingvaWith(cooldown, sequencedClient(seen, failAt = 1, code = 200, failBody = "[]"))

        val thrown = runCatching { backend.translateBatch(page, "ja", "en") }.exceptionOrNull()
        assertTrue("expected BatchParseException, got $thrown", thrown is BatchParseException)
        assertEquals(2, seen.size)
        assertNull(cooldown.unavailableUntil())
    }

    @Test fun `a blank result names the page index, not the chunk-local one`() = runBlocking {
        val seen = ArrayList<Request>()
        // Every request after the first answers with a blank at its own
        // index 0; the reported index must be that chunk's first page
        // position, which is the size of the first chunk.
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val qs = chain.request().url.qs()
            seen += chain.request()
            val body = if (seen.size == 1) echoBody(qs) else echoBody(qs, blankAt = 0)
            ok(chain.request(), body)
        }.build()
        val backend = lingvaWith(null, client)

        val thrown = runCatching { backend.translateBatch(page, "ja", "en") }.exceptionOrNull()
        assertTrue("expected BatchParseException, got $thrown", thrown is BatchParseException)
        val firstChunk = seen[0].url.qs().size
        assertTrue(thrown!!.message!!, "blank result at index $firstChunk" in thrown.message!!)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** The request's `q` params in order. OkHttp types them nullable for
     *  bare `?q` forms the backend never emits. */
    private fun HttpUrl.qs(): List<String> = queryParameterValues("q").map { it ?: "" }

    /** What gtx answers for a request's q params, faithful to the two
     *  shapes it actually uses. ONE q gets the single-q shape
     *  `[[[translated, original]], null, "ja"]` — the chunks array at
     *  top-level index 0, no per-q wrapper — exactly what
     *  [LingvaBackend.translate] has always parsed. Several q get one
     *  top-level entry per q, each shaped like the single-q body.
     *  Translation is `T:` + original so positional recombination is
     *  checkable end to end. */
    private fun echoBody(qs: List<String>, blankAt: Int = -1): String {
        fun perQ(i: Int, q: String): JSONArray {
            val translated = if (i == blankAt) "" else "T:$q"
            val chunk = JSONArray().put(translated).put(q)
            return JSONArray().put(JSONArray().put(chunk)).put(JSONObject.NULL).put("ja")
        }
        if (qs.size == 1) return perQ(0, qs[0]).toString()
        val top = JSONArray()
        qs.forEachIndexed { i, q -> top.put(perQ(i, q)) }
        return top.toString()
    }

    private fun ok(request: Request, body: String, code: Int = 200): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    /** Answers every request with a well-formed echo of its own q params. */
    private fun echoingClient(seen: MutableList<Request>): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            seen += chain.request()
            ok(chain.request(), echoBody(chain.request().url.qs()))
        }.build()

    /** Echoes until the [failAt]-th request (0-based), which answers
     *  [code] with [failBody] instead. */
    private fun sequencedClient(
        seen: MutableList<Request>,
        failAt: Int,
        code: Int,
        failBody: String = "rate limited",
    ): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val index = seen.size
            seen += chain.request()
            if (index == failAt) ok(chain.request(), failBody, code)
            else ok(chain.request(), echoBody(chain.request().url.qs()))
        }.build()
}
