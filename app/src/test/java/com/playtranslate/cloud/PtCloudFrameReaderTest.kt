package com.playtranslate.cloud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.OcrManager
import com.playtranslate.TranslationCache
import com.playtranslate.cloud.PtCloudTestSupport.GatedSource
import com.playtranslate.cloud.PtCloudTestSupport.item
import com.playtranslate.cloud.PtCloudTestSupport.with
import com.playtranslate.language.SourceLangId
import com.playtranslate.ocr.FallbackFrameReader
import com.playtranslate.ocr.FrameReader
import com.playtranslate.ocr.FrameReading
import com.playtranslate.ocr.ReadRequest
import com.playtranslate.ocr.ReaderProgress
import com.playtranslate.ocr.ReaderAttribution
import com.playtranslate.ocr.readLines
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.CooldownCause
import com.playtranslate.translation.CooldownLadder
import com.playtranslate.translation.CooldownState
import com.playtranslate.translation.OnlineAttempt
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.translation.llm.LlmPromptTemplates
import com.playtranslate.translation.llm.PromptKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * [PtCloudFrameReader] against canned responses: part 1 becomes the reading's
 * OCR in the bitmap's coordinates and its translations' ready set (the
 * groups the server's cache answered), item lines arrive in the translations
 * provisionally, the final part settles them, and the final part's outcome
 * is recorded on the cooldown and reported once (at part 1 when part 1
 * decides it: every group translated, or nothing to translate, a
 * same-language request or no groups); the router's question is asked
 * again once the frame is encoded, so a change landing during the encode
 * refuses the read before any request. The request's `meta` is read back from the call
 * the reader made. The encoder is faked (real compression needs native
 * graphics, [FrameEncoderNativeTest]); the bitmap is only the argument.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class PtCloudFrameReaderTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val clock = AtomicLong(1_750_000_000_000L)
    private lateinit var cooldown: CooldownState
    private val attempts = Collections.synchronizedList(mutableListOf<OnlineAttempt>())
    private val calls = Collections.synchronizedList(mutableListOf<Call>())
    private val caps = Collections.synchronizedList(mutableListOf<Int>())
    private val bitmap: Bitmap = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888)

    @Before fun setUp() {
        PtCloudTestSupport.sharedPrefs(ctx).edit().clear().commit()
        cooldown = CooldownState(null, PtCloudService.ID, nowMs = { clock.get() })
        PtCloudService.installForTest(cooldown)
        TranslationBackendRegistry.onlineAttemptListener = { attempts += it }
        OcrManager.instance.filterFuriganaEnabled = true
    }

    @After fun tearDown() {
        PtCloudService.installForTest(null)
        TranslationBackendRegistry.onlineAttemptListener = null
        LlmPromptTemplates.resetOverrides()
        PtCloudTestSupport.sharedPrefs(ctx).edit().clear().commit()
        // A process-wide singleton the other tests share.
        OcrManager.instance.filterFuriganaEnabled = true
    }

    private val part1 = PtCloudTestSupport.part1(PtCloudTestSupport.JA)

    private fun request(src: SourceLangId = SourceLangId.JA, target: String = "en") =
        ReadRequest(src, screenshotWidth = 1920, targetLang = target, frameId = "capture-0042")

    private fun reader(client: OkHttpClient, scale: Float = 1f): PtCloudFrameReader {
        val config = PtCloudTestSupport.switchedOn(ctx)
        return PtCloudFrameReader(
            PtCloudClient(config, clientFactory = { client }),
            config,
            encode = { _, cap ->
                caps += cap
                PtCloudTestSupport.frame(scale = scale)
            },
        )
    }

    private fun streaming(lines: List<String>, gates: List<CountDownLatch?> = emptyList(), waiting: CountDownLatch = CountDownLatch(0)) =
        PtCloudTestSupport.streamingClient(calls) { call -> GatedSource(call, lines, gates, waiting) }

    /** The `meta` field of the one request the reader made. */
    private fun sentMeta(): JsonObject {
        val body = calls.single().request().body as MultipartBody
        val text = Buffer().also { body.parts[1].body.writeTo(it) }.readUtf8()
        return Json.parseToJsonElement(text) as JsonObject
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content

    /** The reading's translations once nothing more can arrive. */
    private suspend fun FrameReading.settled(): Map<Int, String> = withTimeout(5_000) { translations!!.await() }

    /** Waits, off the test's thread, until [done]: the reader's job runs on IO. */
    private suspend fun until(done: () -> Boolean) {
        withTimeout(5_000) { while (!done()) delay(5) }
    }

    /** A two-group part 1 ([PtCloudTestSupport.LATIN]) whose groups [translated]
     *  names carry a translation, as the server's cache answers them. */
    private fun twoGroups(translated: Map<Int, String> = emptyMap()) =
        PtCloudTestSupport.part1(PtCloudTestSupport.LATIN, translated)

    private val serverError =
        PtCloudTestSupport.part2("failed", "[]", extra = ""","translation_error":{"class":"server_error","http_status":503}""")

    private fun assertOneAttempt(failure: BackendFailure?) {
        val attempt = attempts.single()
        assertEquals(PtCloudService.ID, attempt.serviceId)
        assertEquals(PtCloudService.DISPLAY_NAME, attempt.serviceName)
        assertEquals(failure, attempt.failure)
        assertTrue("part 2 always reached the server", attempt.reachedServer)
    }

    /** Waits for every child of the calling job (the translations and the
     *  client's part-2 read), so a test sees their final effects. Listed
     *  before the timeout starts, whose own coroutine is a child too. */
    private suspend fun Job.joinChildren() {
        val kids = children.toList()
        withTimeout(5_000) { kids.forEach { it.join() } }
    }

    @Test fun `part 1 is the reading's OCR in bitmap coordinates, read by PlayTranslate Cloud`() = runBlocking {
        val part2Gate = CountDownLatch(1)
        val reading = reader(streaming(listOf(part1, PtCloudTestSupport.part2()), listOf(null, part2Gate)), scale = 0.5f)
            .read(bitmap, request())
        try {
            // Returned at part 1, with part 2 still held by the server.
            assertTrue(reading.translations!!.pending)
            val group = reading.ocr!!.groups.single()
            // The fixture's group is [562, 765, 1288, 962] and its first line
            // [562, 765, 1137, 832] in uploaded pixels; at half scale the
            // bitmap's boxes are twice those.
            assertEquals(Rect(1124, 1530, 2576, 1924), group.bounds)
            assertEquals(Rect(1124, 1530, 2274, 1664), group.lines.first().bounds)
            assertEquals(ReaderAttribution("PlayTranslate Cloud", "ptcloud"), reading.attribution)
            // The cap is read from the config, per read.
            assertEquals(listOf(PtCloudConfig.DEFAULT_UPLOAD_LONG_SIDE_CAP), caps.toList())
        } finally {
            part2Gate.countDown()
        }
        reading.settled()
        Unit
    }

    @Test fun `part 2 ok is the translations for the app's pair, clears a cooldown, and reports a success`() = runBlocking {
        cooldown.recordLadderFailure(CooldownLadder.RateLimit, "Server error", CooldownCause.SERVER_ERROR)
        assertTrue(cooldown.unavailableUntil() != null)
        // A source whose wire code and translation code differ: the wire
        // carries the OCR code, the translations the translation code.
        val reading = reader(streaming(listOf(part1, PtCloudTestSupport.part2())))
            .read(bitmap, request(SourceLangId.ZH_HANT, "en"))

        assertEquals(mapOf(0 to "Hello"), reading.settled())
        val translations = reading.translations!!
        assertTrue("for the app's pair: the translation code", translations.isFor("zh", "en"))
        assertEquals("PlayTranslate Cloud", translations.providerName)
        assertEquals("zh-Hant", sentMeta().str("source"))
        assertEquals("en", sentMeta().str("target"))
        assertEquals("capture-0042", sentMeta().str("frame_id"))
        assertNull("the success cleared the cooldown", cooldown.unavailableUntil())
        assertOneAttempt(failure = null)
    }

    @Test fun `a group the server translates to a blank is left unnamed`() = runBlocking {
        val blanks = PtCloudTestSupport.part2(
            translations = """[{"id":0,"translation":"Hello"},{"id":1,"translation":""},{"id":2,"translation":"  "}]""",
        )
        val reading = reader(streaming(listOf(part1, blanks))).read(bitmap, request())

        assertEquals(mapOf(0 to "Hello"), reading.settled())
        // Still a working service: a success, not a failure.
        assertOneAttempt(failure = null)
    }

    @Test fun `part 2 rate_limited settles with nothing, cools down until its retry time, and reports RATE_LIMITED`() = runBlocking {
        val failed = PtCloudTestSupport.part2(
            "failed", "[]", extra = ""","translation_error":{"class":"rate_limited","http_status":429,"retry_after_s":12}""",
        )
        val before = System.currentTimeMillis()
        val reading = reader(streaming(listOf(part1, failed))).read(bitmap, request())
        assertEquals(emptyMap<Int, String>(), reading.settled())
        val after = System.currentTimeMillis()

        val until = cooldown.unavailableUntil()!!
        assertTrue("until $until", until in (before + 12_000)..(after + 12_000))
        assertEquals(CooldownCause.RATE_LIMITED, cooldown.unavailableCause())
        assertOneAttempt(BackendFailure(BackendFailureKind.RATE_LIMITED, 429))
    }

    @Test fun `a same-language pair asks the server to skip translation and sends no context`() = runBlocking {
        LlmPromptTemplates.contextProvider = { _, _ -> "Recent dialogue:\nA: hi\n\n" }
        for ((src, target, wire) in listOf(
            Triple(SourceLangId.JA, "ja", "ja"),
            // Traditional Chinese translates as "zh": a "zh" target is the
            // same language, and the server must see source == target.
            Triple(SourceLangId.ZH_HANT, "zh", "zh-Hant"),
        )) {
            calls.clear()
            attempts.clear()
            val reading = reader(streaming(listOf(part1, PtCloudTestSupport.part2("skipped", "[]"))))
                .read(bitmap, request(src, target))
            val meta = sentMeta()
            assertEquals(wire, meta.str("source"))
            assertEquals("target equals source, so the server skips translation", wire, meta.str("target"))
            assertFalse("no context for a pair that is not translated", "context" in meta)
            assertEquals(emptyMap<Int, String>(), reading.settled())
            // Every child over, the skipped final part read: it adds nothing
            // to the one attempt part 1 reported.
            coroutineContext.job.joinChildren()
            assertOneAttempt(failure = null)
        }
    }

    @Test fun `a lines-only read sends the same-language pair and no context`() = runBlocking {
        LlmPromptTemplates.contextProvider = { _, _ -> "Recent dialogue:\nA: hi\n\n" }
        val lines = reader(streaming(listOf(part1, PtCloudTestSupport.part2("skipped", "[]"))))
            .readLines(bitmap, SourceLangId.ZH_HANT, "id")
        val meta = sentMeta()
        assertEquals("zh-Hant", meta.str("source"))
        assertEquals("target equals source, so the server skips translation", "zh-Hant", meta.str("target"))
        assertFalse("no context for a pair that is not translated", "context" in meta)
        assertTrue("part 1's lines", lines!!.isNotEmpty())
        // The drag cancels the translations as it returns: the frame still
        // counts, reported at part 1.
        assertOneAttempt(failure = null)
    }

    @Test fun `persona and context go out when the user has them, keyed by the translation pair`() = runBlocking {
        val asked = mutableListOf<Pair<String, String>>()
        LlmPromptTemplates.overrideProvider = { kind -> if (kind == PromptKind.SYSTEM) "You translate {source}." else null }
        LlmPromptTemplates.contextProvider = { s, t -> asked += s to t; "Recent dialogue:\nA: hi\n\n" }
        reader(streaming(listOf(part1, PtCloudTestSupport.part2()))).read(bitmap, request(SourceLangId.ZH_HANT, "en")).settled()
        val meta = sentMeta()
        assertEquals("You translate {source}.", meta.str("persona"))
        assertEquals("Recent dialogue:\nA: hi\n\n", meta.str("context"))
        // The context ring is keyed as the waterfall asks for it: the
        // translation code, not the wire's OCR code.
        assertEquals(listOf("zh" to "en"), asked)
    }

    @Test fun `no persona override and a blank context send neither`() = runBlocking {
        for (context in listOf("", "  \n")) {
            calls.clear()
            LlmPromptTemplates.contextProvider = { _, _ -> context }
            reader(streaming(listOf(part1, PtCloudTestSupport.part2()))).read(bitmap, request()).settled()
            val meta = sentMeta()
            // stream_translations and filter_furigana are always sent.
            assertEquals(setOf("source", "target", "frame_id", "stream_translations", "filter_furigana"), meta.keys)
        }
    }

    @Test fun `every frame asks for item lines`() = runBlocking {
        reader(streaming(listOf(part1, PtCloudTestSupport.part2()))).read(bitmap, request()).settled()
        assertEquals("true", sentMeta().str("stream_translations"))
    }

    @Test fun `the furigana filter goes out as the device OCR reads it, at each read`() = runBlocking {
        // One reader for both reads: nothing is rebuilt between them.
        val reader = reader(streaming(listOf(part1, PtCloudTestSupport.part2())))
        reader.read(bitmap, request()).settled()
        assertEquals("true", sentMeta().str("filter_furigana"))

        calls.clear()
        OcrManager.instance.filterFuriganaEnabled = false
        reader.read(bitmap, request()).settled()
        assertEquals("false", sentMeta().str("filter_furigana"))
    }

    @Test fun `the furigana filter is sent for every source, not only Japanese`() = runBlocking {
        // The server applies it to a Japanese source only, so the reader
        // sends the setting as it is.
        for ((src, filter) in listOf(SourceLangId.EN to true, SourceLangId.ZH_HANT to true, SourceLangId.EN to false)) {
            calls.clear()
            OcrManager.instance.filterFuriganaEnabled = filter
            reader(streaming(listOf(part1, PtCloudTestSupport.part2()))).read(bitmap, request(src, "ja")).settled()
            assertEquals("$src", "$filter", sentMeta().str("filter_furigana"))
        }
    }

    @Test fun `a lines-only read carries the furigana filter too`() = runBlocking {
        for (filter in listOf(true, false)) {
            calls.clear()
            OcrManager.instance.filterFuriganaEnabled = filter
            reader(streaming(listOf(part1, PtCloudTestSupport.part2("skipped", "[]"))))
                .readLines(bitmap, SourceLangId.JA, "id")
            assertEquals("setting $filter", "$filter", sentMeta().str("filter_furigana"))
        }
    }

    @Test fun `a failure before part 1 propagates the client's PtCloudReadException`() = runBlocking {
        try {
            reader(PtCloudTestSupport.cannedClient(404, "")).read(bitmap, request())
            fail("expected PtCloudReadException")
        } catch (e: PtCloudReadException) {
            assertEquals(BackendFailure(BackendFailureKind.NOT_RUNNING, 404), e.failure)
        }
        // Reported once, by the client; nothing left running.
        assertEquals(BackendFailureKind.NOT_RUNNING, attempts.single().failure!!.kind)
        assertTrue(coroutineContext.job.children.none())
    }

    @Test fun `cancelling the translations while part 2 is pending cancels the call, settles them, and reports nothing`() =
        runBlocking {
            val part2Gate = CountDownLatch(1)
            val waiting = CountDownLatch(1)
            val reading = reader(
                streaming(listOf(twoGroups(mapOf(0 to "Hi")), item(1, "World"), PtCloudTestSupport.part2()), listOf(null, null, part2Gate), waiting),
            ).read(bitmap, request())
            try {
                assertTrue("part 2's read must reach its gate", waiting.await(5, TimeUnit.SECONDS))
                reading.translations!!.cancel()
                coroutineContext.job.joinChildren()
                assertTrue("the HTTP call is cancelled with the translations", calls.single().isCanceled())
            } finally {
                part2Gate.countDown()
            }
            // Settled with part 1's alone, so nobody awaiting them could hang.
            assertEquals(ReaderProgress(mapOf(0 to "Hi"), settled = true), reading.translations.now)
            assertTrue("no attempt reported: $attempts", attempts.isEmpty())
            assertNull(cooldown.unavailableUntil())
            assertTrue("the caller is not failed", coroutineContext.job.isActive)
        }

    @Test fun `cancelling the translations the moment read returns cancels the call too, whether or not their job has started`() =
        runBlocking {
            val part2Gate = CountDownLatch(1)
            val reading = reader(
                streaming(listOf(twoGroups(mapOf(0 to "Hi")), item(1, "World"), PtCloudTestSupport.part2()), listOf(null, null, part2Gate)),
            ).read(bitmap, request())
            try {
                // No wait for the job to reach its body: the completion
                // handler cancels part 2 whichever side of the start the
                // cancel lands on (a consumer's superseded cycle does this).
                reading.translations!!.cancel()
                coroutineContext.job.joinChildren()
                assertTrue("the HTTP call is cancelled with the translations", calls.single().isCanceled())
            } finally {
                part2Gate.countDown()
            }
            assertEquals(ReaderProgress(mapOf(0 to "Hi"), settled = true), reading.translations.now)
            assertTrue("no attempt reported: $attempts", attempts.isEmpty())
            assertTrue("the caller is not failed", coroutineContext.job.isActive)
        }

    @Test fun `a part 2 that never comes settles with nothing, a TIMEOUT attempt, and no cooldown`() = runBlocking {
        val reading = reader(streaming(listOf(part1))).read(bitmap, request())
        assertEquals(emptyMap<Int, String>(), reading.settled())
        assertNull(cooldown.unavailableUntil())
        assertOneAttempt(BackendFailure(BackendFailureKind.TIMEOUT))
    }

    @Test fun `a bug settling part 2 settles with part 1's translations, reports nothing, and never fails the caller`() =
        runBlocking {
            val part2Gate = CountDownLatch(1)
            val final = PtCloudTestSupport.part2(translations = """[{"id":0,"translation":"Hi"},{"id":1,"translation":"World"}]""")
            val reading = reader(streaming(listOf(twoGroups(mapOf(0 to "Hi")), final), listOf(null, part2Gate)))
                .read(bitmap, request())
            // Recording throws: the service's state is gone.
            PtCloudService.installForTest(null)
            part2Gate.countDown()
            assertEquals("the final part never got through", mapOf(0 to "Hi"), reading.settled())
            assertTrue(attempts.isEmpty())
            assertTrue(coroutineContext.job.isActive)
        }

    @Test fun `a part 1 in another schema propagates PtCloudReadException and leaves nothing running`() = runBlocking {
        val schema2 = PtCloudTestSupport.part1(PtCloudTestSupport.JA) { it.with("schema_version", JsonPrimitive(2)) }
        try {
            reader(streaming(listOf(schema2, PtCloudTestSupport.part2()))).read(bitmap, request())
            fail("expected PtCloudReadException")
        } catch (e: PtCloudReadException) {
            assertEquals(BackendFailure.BAD_RESPONSE, e.failure)
        }
        assertTrue("no pending part 2", coroutineContext.job.children.none())
        assertEquals(BackendFailure.BAD_RESPONSE, attempts.single().failure)
        assertNull(cooldown.unavailableUntil())
    }

    // ── Part 1's translations, item lines and the final part ─────────────

    @Test fun `part 1's translations are ready, a blank one absent, and the reading waits only for the rest`() =
        runBlocking {
            val part2Gate = CountDownLatch(1)
            val final = PtCloudTestSupport.part2(translations = """[{"id":0,"translation":"Hi"},{"id":1,"translation":"World"}]""")
            val reading = reader(streaming(listOf(twoGroups(mapOf(0 to "Hi", 1 to "  ")), final), listOf(null, part2Gate)))
                .read(bitmap, request())
            try {
                val translations = reading.translations!!
                assertEquals(mapOf(0 to "Hi"), translations.ready)
                assertEquals(ReaderProgress(mapOf(0 to "Hi"), settled = false), translations.now)
                assertTrue("group 1 is still to come", translations.pending)
            } finally {
                part2Gate.countDown()
            }
            assertEquals(mapOf(0 to "Hi", 1 to "World"), reading.settled())
            assertOneAttempt(failure = null)
        }

    @Test fun `an item appears in the translations before the final part, whose wording wins`() = runBlocking {
        val part2Gate = CountDownLatch(1)
        val final = PtCloudTestSupport.part2(translations = """[{"id":0,"translation":"Hello"},{"id":1,"translation":"World"}]""")
        val reading = reader(streaming(listOf(twoGroups(), item(1, "Wrld"), final), listOf(null, null, part2Gate)))
            .read(bitmap, request())
        val translations = reading.translations!!
        try {
            until { translations.now.byPosition[1] == "Wrld" }
            assertEquals(ReaderProgress(mapOf(1 to "Wrld"), settled = false), translations.now)
            assertTrue("an item is provisional", translations.pending)
            assertTrue("nothing reported before the final part", attempts.isEmpty())
        } finally {
            part2Gate.countDown()
        }
        assertEquals(mapOf(0 to "Hello", 1 to "World"), reading.settled())
        assertOneAttempt(failure = null)
    }

    @Test fun `an ok final part settles its texts, part 1's kept over a different wording`() = runBlocking {
        val final = PtCloudTestSupport.part2(translations = """[{"id":0,"translation":"Hello"},{"id":1,"translation":"World"}]""")
        val reading = reader(streaming(listOf(twoGroups(mapOf(0 to "Hi")), final))).read(bitmap, request())
        assertEquals(mapOf(0 to "Hi", 1 to "World"), reading.settled())
        assertOneAttempt(failure = null)
    }

    @Test fun `a failed, deferred or ended final part keeps part 1's, drops the items, and records as before`() =
        runBlocking {
            data class Want(val failure: BackendFailure, val until: Long?, val cause: CooldownCause?)
            val cases = listOf(
                listOf(serverError) to Want(
                    BackendFailure(BackendFailureKind.SERVER_ERROR, 503), clock.get() + 60_000, CooldownCause.SERVER_ERROR,
                ),
                listOf(PtCloudTestSupport.part2("deferred", "[]")) to Want(BackendFailure(BackendFailureKind.TIMEOUT), null, null),
                // The body ends after the item, before any final part.
                emptyList<String>() to Want(BackendFailure(BackendFailureKind.TIMEOUT), null, null),
            )
            for ((final, want) in cases) {
                cooldown.resetCooldown()
                attempts.clear()
                calls.clear()
                val reading = reader(streaming(listOf(twoGroups(mapOf(0 to "Hi")), item(1, "World")) + final))
                    .read(bitmap, request())
                assertEquals("$final", mapOf(0 to "Hi"), reading.settled())
                assertTrue(reading.translations!!.now.settled)
                assertOneAttempt(want.failure)
                assertEquals("$final", want.until, cooldown.unavailableUntil())
                assertEquals("$final", want.cause, cooldown.unavailableCause())
            }
        }

    @Test fun `part 1 translating every group settles at once and reports its success once, whatever the final part says`() =
        runBlocking {
            cooldown.recordLadderFailure(CooldownLadder.RateLimit, "Server error", CooldownCause.SERVER_ERROR)
            val part2Gate = CountDownLatch(1)
            // The contract says the final part is ok here; a failed one proves
            // nothing more is recorded from it.
            val reading = reader(
                streaming(listOf(twoGroups(mapOf(0 to "Hi", 1 to "There")), serverError), listOf(null, part2Gate)),
            ).read(bitmap, request())
            try {
                val translations = reading.translations!!
                assertFalse(translations.pending)
                assertEquals(ReaderProgress(mapOf(0 to "Hi", 1 to "There"), settled = true), translations.now)
                // A consumer has nothing to wait for, with the final part held.
                assertEquals(mapOf(0 to "Hi", 1 to "There"), withTimeout(1_000) { translations.await() })
                assertOneAttempt(failure = null)
                assertNull("the success cleared the cooldown", cooldown.unavailableUntil())
            } finally {
                part2Gate.countDown()
            }
            coroutineContext.job.joinChildren()
            assertOneAttempt(failure = null)
            assertNull("nothing recorded from the final part", cooldown.unavailableUntil())
            assertEquals(ReaderProgress(mapOf(0 to "Hi", 1 to "There"), settled = true), reading.translations.now)
        }

    @Test fun `cancelling translations part 1 fully answered leaves the call to read its final part`() =
        runBlocking {
            val part2Gate = CountDownLatch(1)
            val waiting = CountDownLatch(1)
            val reading = reader(
                streaming(
                    listOf(twoGroups(mapOf(0 to "Hi", 1 to "There")), PtCloudTestSupport.part2()),
                    listOf(null, part2Gate), waiting,
                ),
            ).read(bitmap, request())
            try {
                assertTrue("part 2's read must reach its gate", waiting.await(5, TimeUnit.SECONDS))
                // What every consumer does once it is done with a reading.
                reading.translations!!.cancel()
                assertFalse("nothing here for the cancel to stop", calls.single().isCanceled())
            } finally {
                part2Gate.countDown()
            }
            coroutineContext.job.joinChildren()
            assertFalse("the exchange ended by itself", calls.single().isCanceled())
            assertOneAttempt(failure = null)
        }

    // ── Nothing to translate: the outcome is known at part 1 too ─────────

    @Test fun `a same-language reading dropped the moment read returns still counts the frame, once, at part 1`() =
        runBlocking {
            // A server error, long expired, left the RateLimit rung at 1: the
            // next one would cost 10 minutes unless a success resets it.
            cooldown.recordLadderFailure(CooldownLadder.RateLimit, "Server error", CooldownCause.SERVER_ERROR)
            clock.addAndGet(61_000)
            assertNull(cooldown.unavailableUntil())
            val part2Gate = CountDownLatch(1)
            val reading = reader(streaming(listOf(part1, PtCloudTestSupport.part2("skipped", "[]")), listOf(null, part2Gate)))
                .read(bitmap, request(SourceLangId.JA, "ja"))
            try {
                // What every consumer that wants text alone does (the furigana
                // tier, the drag): nothing awaits the translations.
                reading.translations!!.cancel()
                assertOneAttempt(failure = null)
            } finally {
                part2Gate.countDown()
            }
            coroutineContext.job.joinChildren()
            assertOneAttempt(failure = null)
            assertFalse("the exchange ended by itself", calls.single().isCanceled())
            // The success reset the rung: the next server error costs the first step.
            cooldown.recordLadderFailure(CooldownLadder.RateLimit, "Server error", CooldownCause.SERVER_ERROR)
            assertEquals(clock.get() + 60_000, cooldown.unavailableUntil())
        }

    @Test fun `a part 1 with no groups dropped the moment read returns still counts the frame, once, at part 1`() =
        runBlocking {
            cooldown.recordLadderFailure(CooldownLadder.RateLimit, "Server error", CooldownCause.SERVER_ERROR)
            assertTrue(cooldown.unavailableUntil() != null)
            val part2Gate = CountDownLatch(1)
            val empty = PtCloudTestSupport.part1(PtCloudTestSupport.JA) { it.with("groups", JsonArray(emptyList())) }
            // A translating pair, so the empty part 1 alone decides the outcome.
            val reading = reader(streaming(listOf(empty, PtCloudTestSupport.part2("skipped", "[]")), listOf(null, part2Gate)))
                .read(bitmap, request())
            try {
                assertNull("no text", reading.ocr)
                // What the pipeline does with a reading that has no text.
                reading.translations!!.cancel()
                assertOneAttempt(failure = null)
                assertNull("the success cleared the cooldown", cooldown.unavailableUntil())
            } finally {
                part2Gate.countDown()
            }
            coroutineContext.job.joinChildren()
            assertOneAttempt(failure = null)
            assertFalse("the exchange ended by itself", calls.single().isCanceled())
        }

    @Test fun `the translations carry the cache's configuration from before the request went out`() = runBlocking {
        val part1Gate = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val before = TranslationCache.shared.configGeneration
        val pending = async {
            reader(streaming(listOf(part1, PtCloudTestSupport.part2()), listOf(part1Gate), waiting)).read(bitmap, request())
        }
        try {
            until { waiting.count == 0L }
            // A settings clear while the frame reads (the switch or the key
            // changed): the frame's translations were made under the old one.
            TranslationCache.shared.clear()
        } finally {
            part1Gate.countDown()
        }
        val reading = withTimeout(5_000) { pending.await() }
        assertEquals(before, reading.translations!!.configGeneration)
        assertTrue("the clear moved the cache on", TranslationCache.shared.configGeneration != before)
        reading.settled()
        Unit
    }

    // ── The router's question, asked again before the upload ──────────

    /** A reader whose encode runs [duringEncode] first, as a change landing
     *  while the frame encodes does. Switched on with a key stored, as every
     *  reader the router routes to. */
    private fun readerEncoding(client: OkHttpClient, duringEncode: () -> Unit): PtCloudFrameReader {
        val config = PtCloudTestSupport.switchedOn(ctx)
        return PtCloudFrameReader(
            PtCloudClient(config, clientFactory = { client }),
            config,
            encode = { _, _ ->
                duringEncode()
                PtCloudTestSupport.frame()
            },
        )
    }

    /** Reads a frame whose encode runs [change] and asserts the read was
     *  refused for [expected] before any request, with nothing reported and
     *  nothing left running. */
    private suspend fun CoroutineScope.assertRefusedDuringEncode(expected: CloudRefusal, change: () -> Unit) {
        val cloud = readerEncoding(streaming(listOf(part1, PtCloudTestSupport.part2())), change)
        try {
            cloud.read(bitmap, request())
            fail("expected PtCloudRefusedException")
        } catch (e: PtCloudRefusedException) {
            assertEquals(expected, e.refusal)
        }
        assertTrue("no request went out", calls.isEmpty())
        assertTrue("a refusal is not a failure: nothing reported", attempts.isEmpty())
        assertTrue(coroutineContext.job.children.none())
    }

    @Test fun `the switch turned off while the frame encodes keeps it off the wire, and the fallback reads it on the device`() =
        runBlocking {
            val deviceReading = FrameReading(null, ReaderAttribution("device", "device"), translations = null)
            var deviceRequest: ReadRequest? = null
            val device = object : FrameReader {
                override suspend fun read(bitmap: Bitmap, request: ReadRequest): FrameReading {
                    deviceRequest = request
                    return deviceReading
                }
            }
            val cloud = readerEncoding(streaming(listOf(part1, PtCloudTestSupport.part2()))) {
                PtCloudTestSupport.prefs(ctx).ptCloudEnabled = false
            }
            val request = request()

            val reading = FallbackFrameReader(cloud, device).read(bitmap, request)

            assertSame(deviceReading, reading)
            assertSame(request, deviceRequest)
            assertTrue("no request went out", calls.isEmpty())
            assertTrue("a refusal is not a failure: nothing reported", attempts.isEmpty())
            assertNull("nothing recorded", cooldown.unavailableUntil())
            assertTrue(coroutineContext.job.children.none())
        }

    @Test fun `the switch turned off during the encode is the router's refusal, asked again`() = runBlocking {
        assertRefusedDuringEncode(CloudRefusal.SWITCH_OFF) { PtCloudTestSupport.prefs(ctx).ptCloudEnabled = false }
        assertNull("nothing recorded", cooldown.unavailableUntil())
    }

    @Test fun `the key cleared during the encode refuses the read before it, not as the client's key failure`() = runBlocking {
        assertRefusedDuringEncode(CloudRefusal.NO_KEY) { PtCloudTestSupport.prefs(ctx).ptCloudClientKey = "" }
        assertNull("nothing recorded", cooldown.unavailableUntil())
    }

    @Test fun `the network gone during the encode refuses the read before it, not as a transport failure`() = runBlocking {
        assertRefusedDuringEncode(CloudRefusal.OFFLINE) { PtCloudTestSupport.offline(ctx) }
        assertNull("nothing recorded", cooldown.unavailableUntil())
    }

    @Test fun `a cooldown begun during the encode, from either route, refuses the read and climbs no rung`() = runBlocking {
        // The service judges a cooldown against the wall clock.
        clock.set(System.currentTimeMillis())
        var until: Long? = null
        assertRefusedDuringEncode(CloudRefusal.COOLING_DOWN) {
            cooldown.recordLadderFailure(CooldownLadder.RateLimit, "Rate limited")
            until = cooldown.unavailableUntil()
        }
        assertEquals("the one rung the other route recorded", until, cooldown.unavailableUntil())
    }
}
