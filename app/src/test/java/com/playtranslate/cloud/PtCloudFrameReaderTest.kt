package com.playtranslate.cloud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.cloud.PtCloudTestSupport.GatedSource
import com.playtranslate.cloud.PtCloudTestSupport.with
import com.playtranslate.language.SourceLangId
import com.playtranslate.ocr.FrameReading
import com.playtranslate.ocr.ProvidedTranslations
import com.playtranslate.ocr.ReadRequest
import com.playtranslate.ocr.ReaderAttribution
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.CooldownCause
import com.playtranslate.translation.CooldownLadder
import com.playtranslate.translation.CooldownState
import com.playtranslate.translation.OnlineAttempt
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.translation.llm.LlmPromptTemplates
import com.playtranslate.translation.llm.PromptKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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
 * [PtCloudFrameReader] against canned two-part responses: part 1 becomes the
 * reading's OCR in the bitmap's coordinates, part 2 its translations, and
 * part 2's outcome is recorded on the cooldown and reported once. The
 * request's `meta` is read back from the call the reader made. The encoder
 * is faked (real compression needs native graphics, [FrameEncoderNativeTest]);
 * the bitmap is only the argument.
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
    private val formats = Collections.synchronizedList(mutableListOf<FrameFormat>())
    private val bitmap: Bitmap = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888)

    @Before fun setUp() {
        PtCloudTestSupport.sharedPrefs(ctx).edit().clear().commit()
        cooldown = CooldownState(null, PtCloudService.ID, nowMs = { clock.get() })
        PtCloudService.installForTest(cooldown)
        TranslationBackendRegistry.onlineAttemptListener = { attempts += it }
    }

    @After fun tearDown() {
        PtCloudService.installForTest(null)
        TranslationBackendRegistry.onlineAttemptListener = null
        LlmPromptTemplates.resetOverrides()
        PtCloudTestSupport.sharedPrefs(ctx).edit().clear().commit()
    }

    private val part1 = PtCloudTestSupport.part1(PtCloudTestSupport.JA)

    private fun request(src: SourceLangId = SourceLangId.JA, target: String = "en") =
        ReadRequest(src, screenshotWidth = 1920, targetLang = target, frameId = "capture-0042")

    private fun reader(client: OkHttpClient, scale: Float = 1f): PtCloudFrameReader {
        val config = PtCloudTestSupport.config(ctx)
        return PtCloudFrameReader(
            PtCloudClient(config, clientFactory = { client }),
            config,
            encode = { _, cap, format ->
                caps += cap
                formats += format
                PtCloudTestSupport.frame(scale = scale, format = format)
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

    private suspend fun FrameReading.part2(): ProvidedTranslations = withTimeout(5_000) { translations!!.await() }

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
            assertFalse(reading.translations!!.isCompleted)
            val group = reading.ocr!!.groups.single()
            // The fixture's group is [562, 765, 1288, 962] and its first line
            // [562, 765, 1137, 832] in uploaded pixels; at half scale the
            // bitmap's boxes are twice those.
            assertEquals(Rect(1124, 1530, 2576, 1924), group.bounds)
            assertEquals(Rect(1124, 1530, 2274, 1664), group.lines.first().bounds)
            assertEquals(ReaderAttribution("PlayTranslate Cloud", "ptcloud"), reading.attribution)
            // The cap and the format are read from the config, per read.
            assertEquals(listOf(PtCloudConfig.DEFAULT_UPLOAD_LONG_SIDE_CAP), caps.toList())
            assertEquals(listOf(FrameFormat.JPEG), formats.toList())
        } finally {
            part2Gate.countDown()
        }
        reading.part2()
        Unit
    }

    @Test fun `part 2 ok is the translations for the app's pair, clears a cooldown, and reports a success`() = runBlocking {
        cooldown.recordLadderFailure(CooldownLadder.RateLimit, "Server error", CooldownCause.SERVER_ERROR)
        assertTrue(cooldown.unavailableUntil() != null)
        // A source whose wire code and translation code differ: the wire
        // carries the OCR code, the translations the translation code.
        val reading = reader(streaming(listOf(part1, PtCloudTestSupport.part2())))
            .read(bitmap, request(SourceLangId.ZH_HANT, "en"))

        assertEquals(ProvidedTranslations.Ok(mapOf(0 to "Hello"), "zh", "en", "PlayTranslate Cloud"), reading.part2())
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

        assertEquals(ProvidedTranslations.Ok(mapOf(0 to "Hello"), "ja", "en", "PlayTranslate Cloud"), reading.part2())
        // Still a working service: a success, not a failure.
        assertOneAttempt(failure = null)
    }

    @Test fun `part 2 rate_limited is Unavailable, cools down until its retry time, and reports RATE_LIMITED`() = runBlocking {
        val failed = PtCloudTestSupport.part2(
            "failed", "[]", extra = ""","translation_error":{"class":"rate_limited","http_status":429,"retry_after_s":12}""",
        )
        val before = System.currentTimeMillis()
        val reading = reader(streaming(listOf(part1, failed))).read(bitmap, request())
        assertEquals(ProvidedTranslations.Unavailable("RATE_LIMITED"), reading.part2())
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
            assertEquals(ProvidedTranslations.Unavailable("skipped"), reading.part2())
            assertOneAttempt(failure = null)
        }
    }

    @Test fun `persona and context go out when the user has them, keyed by the translation pair`() = runBlocking {
        val asked = mutableListOf<Pair<String, String>>()
        LlmPromptTemplates.overrideProvider = { kind -> if (kind == PromptKind.SYSTEM) "You translate {source}." else null }
        LlmPromptTemplates.contextProvider = { s, t -> asked += s to t; "Recent dialogue:\nA: hi\n\n" }
        reader(streaming(listOf(part1, PtCloudTestSupport.part2()))).read(bitmap, request(SourceLangId.ZH_HANT, "en")).part2()
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
            reader(streaming(listOf(part1, PtCloudTestSupport.part2()))).read(bitmap, request()).part2()
            val meta = sentMeta()
            // stream_translations is the item-lines switch, on by default.
            assertEquals(setOf("source", "target", "frame_id", "stream_translations"), meta.keys)
        }
    }

    @Test fun `item lines are asked for while the switch is on and not mentioned while it is off`() = runBlocking {
        val reader = reader(streaming(listOf(part1, PtCloudTestSupport.part2())))
        reader.read(bitmap, request()).part2()
        assertEquals("true", sentMeta().str("stream_translations"))

        calls.clear()
        PtCloudTestSupport.prefs(ctx).ptCloudStreamItems = false
        reader.read(bitmap, request()).part2()
        assertFalse("absent, not false: ${sentMeta()}", "stream_translations" in sentMeta())
    }

    @Test fun `the upload format is read from the config at each read`() = runBlocking {
        // One reader for both reads: nothing is rebuilt between them.
        val reader = reader(streaming(listOf(part1, PtCloudTestSupport.part2())))
        reader.read(bitmap, request()).part2()
        PtCloudTestSupport.prefs(ctx).ptCloudUploadFormat = FrameFormat.WEBP.wireName
        reader.read(bitmap, request()).part2()
        assertEquals(listOf(FrameFormat.JPEG, FrameFormat.WEBP), formats.toList())
        // Each frame went out in the format it was encoded in.
        val types = calls.map { (it.request().body as MultipartBody).parts[0].body.contentType().toString() }
        assertEquals(listOf("image/jpeg", "image/webp"), types)
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

    @Test fun `cancelling the translations while part 2 is pending cancels the call and reports nothing`() = runBlocking {
        val part2Gate = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val reading = reader(streaming(listOf(part1, PtCloudTestSupport.part2()), listOf(null, part2Gate), waiting))
            .read(bitmap, request())
        try {
            assertTrue("part 2's read must reach its gate", waiting.await(5, TimeUnit.SECONDS))
            withTimeout(5_000) {
                reading.translations!!.cancel()
                reading.translations!!.join()
            }
            assertTrue("the HTTP call is cancelled with the translations", calls.single().isCanceled())
            coroutineContext.job.joinChildren()
        } finally {
            part2Gate.countDown()
        }
        assertTrue("no attempt reported: $attempts", attempts.isEmpty())
        assertNull(cooldown.unavailableUntil())
        assertTrue("the caller is not failed", coroutineContext.job.isActive)
    }

    @Test fun `a part 2 that never comes is Unavailable, a TIMEOUT attempt, and no cooldown`() = runBlocking {
        val reading = reader(streaming(listOf(part1))).read(bitmap, request())
        assertEquals(ProvidedTranslations.Unavailable("TIMEOUT"), reading.part2())
        assertNull(cooldown.unavailableUntil())
        assertOneAttempt(BackendFailure(BackendFailureKind.TIMEOUT))
    }

    @Test fun `a bug settling part 2 is Unavailable, reports nothing, and never fails the caller`() = runBlocking {
        val part2Gate = CountDownLatch(1)
        val reading = reader(streaming(listOf(part1, PtCloudTestSupport.part2()), listOf(null, part2Gate)))
            .read(bitmap, request())
        // Recording throws: the service's state is gone.
        PtCloudService.installForTest(null)
        part2Gate.countDown()
        assertEquals(ProvidedTranslations.Unavailable("IllegalStateException"), reading.part2())
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
}
