package com.playtranslate.cloud

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.PtJson
import com.playtranslate.cloud.PtCloudTestSupport.GatedSource
import com.playtranslate.cloud.PtCloudTestSupport.item
import com.playtranslate.cloud.PtCloudTestSupport.part2
import com.playtranslate.language.SourceLangId
import com.playtranslate.ocr.ReadRequest
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.CooldownCause
import com.playtranslate.translation.CooldownState
import com.playtranslate.translation.OnlineAttempt
import com.playtranslate.translation.TranslationBackendRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import okhttp3.Call
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The read after part 1 when the frame asked for item lines ("Incremental
 * translations" in docs/api.md), one test per row of the line table:
 *
 * | line | action |
 * |---|---|
 * | `"part":"translation"` | decoded, the read's result |
 * | `"part":"translation_item"` | stamped for the clock line and added to the response's items, the read goes on |
 * | a JSON object with any other `part`, or none | skipped |
 * | not a JSON object, or a final part that does not decode | Failed |
 * | the body ends before the final part | Ended |
 *
 * Plus: an item that does not decode is skipped, not a failure; the
 * response's items ([FrameResponse.items]) grow as each item arrives, before
 * the final part, keep a repeated group's last text, and keep their value
 * once the read ends, however it ends; through the reader, the settled
 * translations and the cooldown are exactly those of the two-line response
 * (items are provisional: [PtCloudFrameReaderTest] has how they reach the
 * reading before the final part); an older server's two lines read
 * unchanged; a caller cancelled while an item is awaited cancels the call and
 * records, reports and logs nothing; and the two clock lines carry what the
 * read saw.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class PtCloudItemLinesTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val clock = AtomicLong(1_750_000_000_000L)
    private val attempts = Collections.synchronizedList(mutableListOf<OnlineAttempt>())
    private val calls = Collections.synchronizedList(mutableListOf<Call>())
    private lateinit var cooldown: CooldownState
    private val bitmap: Bitmap = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888)

    @Before fun setUp() {
        PtCloudTestSupport.sharedPrefs(ctx).edit().clear().commit()
        cooldown = CooldownState(null, PtCloudService.ID, nowMs = { clock.get() })
        PtCloudService.installForTest(cooldown)
        TranslationBackendRegistry.onlineAttemptListener = { attempts += it }
        ShadowLog.clear()
    }

    @After fun tearDown() {
        PtCloudService.installForTest(null)
        TranslationBackendRegistry.onlineAttemptListener = null
        PtCloudTestSupport.sharedPrefs(ctx).edit().clear().commit()
    }

    private val part1 = PtCloudTestSupport.part1(PtCloudTestSupport.JA)

    private fun client(
        lines: List<String>,
        gates: List<CountDownLatch?> = emptyList(),
        waiting: CountDownLatch = CountDownLatch(0),
        headers: Map<String, String> = emptyMap(),
    ) = PtCloudClient(
        PtCloudTestSupport.config(ctx),
        clientFactory = {
            PtCloudTestSupport.streamingClient(calls, headers) { call -> GatedSource(call, lines, gates, waiting) }
        },
    )

    /** Posts a frame that asks for items, answered with [lines], and waits
     *  for the read after part 1 to end. */
    private suspend fun postAndRead(lines: List<String>): FrameResponse {
        // Called straight from the test's job: part 2 is its child.
        val response = client(lines).postFrame(PtCloudTestSupport.frame(), PtCloudTestSupport.meta(streamTranslations = true))
        withTimeout(5_000) { response.translation.await() }
        return response
    }

    /** [postAndRead]'s result: what the read after part 1 produced. */
    private suspend fun readAfterPart1(lines: List<String>): Part2Result = postAndRead(lines).translation.await()

    private fun decoded(line: String): TranslationPart = PtJson.lenient.decodeFromString(line)

    private fun logLines(prefix: String) = ShadowLog.getLogsForTag("PtCloud").map { it.msg }.filter { it.startsWith(prefix) }

    private fun assertNothingRecorded() {
        assertTrue("no attempt reported: $attempts", attempts.isEmpty())
        assertNull("no cooldown recorded", cooldown.unavailableUntil())
    }

    // ── The line table, row by row ─────────────────────────────────────

    @Test fun `the final part after item lines is the result, and its wording wins`() = runBlocking {
        val final = part2(translations = """[{"id":0,"translation":"Hello"},{"id":1,"translation":"World"}]""")
        val result = readAfterPart1(listOf(part1, item(0, "Helo"), item(1, "World"), final))
        assertEquals(Part2Result.Line(decoded(final)), result)
        assertEquals(
            TranslationOutcome.Translated(mapOf(0 to "Hello", 1 to "World")),
            PtCloudErrors.classifyPart2(result, clock.get()),
        )
        // The read went past both items to the final part.
        assertTrue(logLines("final ").single(), " items=2 " in logLines("final ").single())
    }

    @Test fun `a JSON object with an unknown part, or none, is skipped`() = runBlocking {
        val result = readAfterPart1(
            listOf(
                part1,
                """{"part":"progress","schema_version":1,"done":1}""",
                """{"id":0,"translation":"no part at all"}""",
                """{"part":null}""",
                """{"part":"ocr","schema_version":1,"groups":[]}""",
                item(0, "Hello"),
                part2(),
            ),
        )
        assertEquals(Part2Result.Line(decoded(part2())), result)
        // Skipped lines are not items.
        assertTrue(logLines("final ").single(), " items=1 " in logLines("final ").single())
    }

    @Test fun `a line that is not a JSON object is Failed, a bad response`() = runBlocking {
        for (garbage in listOf("not json at all", "[1,2,3]", "\"translation\"", "42", "")) {
            val result = readAfterPart1(listOf(part1, item(0, "Hello"), garbage, part2()))
            assertTrue("$garbage -> $result", result is Part2Result.Failed)
            assertTrue(garbage, (result as Part2Result.Failed).cause is IllegalArgumentException)
            assertEquals(
                BackendFailure.BAD_RESPONSE,
                PtCloudErrors.classifyPart2(result, clock.get()).failure,
            )
        }
        // A failed read never fails the caller.
        assertTrue(coroutineContext.job.isActive)
        assertNothingRecorded()
    }

    @Test fun `a final part that does not decode is Failed, a bad response`() = runBlocking {
        for (bad in listOf(
            """{"part":"translation"}""",
            """{"part":"translation","schema_version":1,"translation_status":"ok","translations":"nope"}""",
        )) {
            val result = readAfterPart1(listOf(part1, item(0, "Hello"), bad))
            assertTrue("$bad -> $result", result is Part2Result.Failed)
            assertTrue(bad, (result as Part2Result.Failed).cause is IllegalArgumentException)
        }
        assertNothingRecorded()
    }

    @Test fun `a body that ends after item lines, before the final part, is Ended`() = runBlocking {
        val result = readAfterPart1(listOf(part1, item(0, "Hello"), item(1, "World")))
        assertEquals(Part2Result.Ended, result)
        // Treated as deferred, no cooldown, as a body that ends after part 1.
        assertEquals(
            BackendFailure(BackendFailureKind.TIMEOUT),
            PtCloudErrors.classifyPart2(result, clock.get()).failure,
        )
        val line = logLines("final ").single()
        assertTrue(line, " status=ended " in line && " items=2 " in line)
        assertNothingRecorded()
    }

    @Test fun `an item line that does not decode as an item is skipped, not a failure`() = runBlocking {
        val result = readAfterPart1(
            listOf(
                part1,
                """{"part":"translation_item","schema_version":1,"translation":"no id"}""",
                """{"part":"translation_item","schema_version":1,"id":"zero","translation":"bad id"}""",
                """{"part":"translation_item","id":0,"translation":"no schema"}""",
                item(0, "Hello"),
                part2(),
            ),
        )
        assertEquals(Part2Result.Line(decoded(part2())), result)
        // Only the item that decoded counts.
        assertTrue(logLines("final ").single(), " items=1 " in logLines("final ").single())
    }

    @Test fun `an older server's two lines read unchanged with items asked for`() = runBlocking {
        val response = postAndRead(listOf(part1, part2()))
        assertEquals(Part2Result.Line(decoded(part2())), response.translation.await())
        assertEquals(emptyMap<Int, String>(), response.items.value)
        assertTrue(logLines("final ").single(), " items=0 item_first=- item_last=- " in logLines("final ").single())
    }

    // ── The response's items ───────────────────────────────────────────

    @Test fun `the items grow one by one as each arrives, before the final part, and keep their value after it`() =
        runBlocking {
            val firstItem = CountDownLatch(1)
            val secondItem = CountDownLatch(1)
            val finalPart = CountDownLatch(1)
            val final = part2(translations = """[{"id":0,"translation":"Hello"},{"id":1,"translation":"World"}]""")
            val c = client(
                listOf(part1, item(0, "Helo"), item(1, "World"), final),
                listOf(null, firstItem, secondItem, finalPart),
            )
            try {
                val response = c.postFrame(PtCloudTestSupport.frame(), PtCloudTestSupport.meta(streamTranslations = true))
                assertEquals("no item has arrived", emptyMap<Int, String>(), response.items.value)

                firstItem.countDown()
                assertEquals(mapOf(0 to "Helo"), withTimeout(5_000) { response.items.first { it.isNotEmpty() } })
                assertFalse("the final part is still held", response.translation.isCompleted)

                secondItem.countDown()
                assertEquals(mapOf(0 to "Helo", 1 to "World"), withTimeout(5_000) { response.items.first { it.size == 2 } })
                assertFalse("the final part is still held", response.translation.isCompleted)

                finalPart.countDown()
                withTimeout(5_000) { response.translation.await() }
                // The items stay as the server sent them: applying the final
                // part's wording is the reader's job.
                assertEquals(mapOf(0 to "Helo", 1 to "World"), response.items.value)
            } finally {
                firstItem.countDown()
                secondItem.countDown()
                finalPart.countDown()
            }
        }

    @Test fun `a group repeated across items keeps its last text`() = runBlocking {
        val response = postAndRead(listOf(part1, item(0, "Helo"), item(1, "World"), item(0, "Hello"), part2()))
        assertEquals(mapOf(0 to "Hello", 1 to "World"), response.items.value)
        assertTrue(logLines("final ").single(), " items=3 " in logLines("final ").single())
    }

    @Test fun `an item that does not decode leaves the items as they were, and the next one still adds`() = runBlocking {
        val response = postAndRead(
            listOf(
                part1,
                item(0, "Hello"),
                """{"part":"translation_item","schema_version":1,"translation":"no id"}""",
                """{"part":"translation_item","schema_version":1,"id":"zero","translation":"bad id"}""",
                """{"part":"translation_item","schema_version":1,"id":1}""",
                item(1, "World"),
                part2(),
            ),
        )
        assertEquals(mapOf(0 to "Hello", 1 to "World"), response.items.value)
    }

    @Test fun `the items keep their value when the body ends early or the read fails`() = runBlocking {
        val ended = postAndRead(listOf(part1, item(0, "Hello")))
        assertEquals(Part2Result.Ended, ended.translation.await())
        assertEquals(mapOf(0 to "Hello"), ended.items.value)

        val failed = postAndRead(listOf(part1, item(0, "Hello"), "not json at all", item(1, "World"), part2()))
        assertTrue(failed.translation.await() is Part2Result.Failed)
        assertEquals(mapOf(0 to "Hello"), failed.items.value)
    }

    // ── Through the reader ─────────────────────────────────────────────

    private fun reader(lines: List<String>): PtCloudFrameReader {
        val config = PtCloudTestSupport.config(ctx)
        return PtCloudFrameReader(
            PtCloudClient(config, clientFactory = {
                PtCloudTestSupport.streamingClient(calls) { call -> GatedSource(call, lines, emptyList()) }
            }),
            config,
            encode = { _, _, format -> PtCloudTestSupport.frame(format = format) },
        )
    }

    private val request = ReadRequest(SourceLangId.JA, screenshotWidth = 1920, targetLang = "en", frameId = "capture-0042")

    /** What one read leaves: the reading's settled translations, the
     *  cooldown, and the attempt reported. */
    private data class Settled(
        val translations: Map<Int, String>,
        val until: Long?,
        val cause: CooldownCause?,
        val attempts: List<BackendFailure?>,
    )

    private suspend fun settle(lines: List<String>): Settled {
        cooldown.resetCooldown()
        attempts.clear()
        val reading = reader(lines).read(bitmap, request)
        val translations = withTimeout(5_000) { reading.translations!!.await() }
        return Settled(translations, cooldown.unavailableUntil(), cooldown.unavailableCause(), attempts.map { it.failure })
    }

    @Test fun `items then a failed or deferred final settle exactly as the two-line response does`() = runBlocking {
        val serverError = part2("failed", "[]", extra = ""","translation_error":{"class":"server_error","http_status":503}""")
        val deferred = part2("deferred", "[]")
        val expected = mapOf(
            serverError to Settled(
                emptyMap(), clock.get() + 60_000, CooldownCause.SERVER_ERROR,
                listOf(BackendFailure(BackendFailureKind.SERVER_ERROR, 503)),
            ),
            deferred to Settled(
                emptyMap(), null, null,
                listOf(BackendFailure(BackendFailureKind.TIMEOUT)),
            ),
        )
        for ((final, want) in expected) {
            val twoLines = settle(listOf(part1, final))
            val withItems = settle(listOf(part1, item(0, "Hello"), item(1, "World"), final))
            assertEquals("two lines", want, twoLines)
            assertEquals("with items", twoLines, withItems)
        }
    }

    @Test fun `an item's text is not what the reading settles on, the final part's is`() = runBlocking {
        val settled = settle(
            listOf(part1, item(0, "Helo"), item(5, "Not in the final list"), part2()),
        )
        assertEquals(mapOf(0 to "Hello"), settled.translations)
        assertEquals(listOf<BackendFailure?>(null), settled.attempts)
    }

    // ── Cancellation and the clock lines ───────────────────────────────

    @Test fun `cancelling the caller while an item line is awaited cancels the call and records, reports and logs nothing`() =
        runBlocking {
            val itemGate = CountDownLatch(1)
            val waiting = CountDownLatch(1)
            val c = client(
                listOf(part1, item(0, "Hello"), item(1, "World"), part2()),
                listOf(null, null, itemGate, null),
                waiting,
            )
            val response = AtomicReference<FrameResponse?>()
            val job = launch(Dispatchers.IO) {
                val r = c.postFrame(PtCloudTestSupport.frame(), PtCloudTestSupport.meta(streamTranslations = true))
                response.set(r)
                r.translation.await()
            }
            try {
                // The first item was read; the read now waits for the second.
                assertTrue("the read must reach the second item's gate", waiting.await(5, TimeUnit.SECONDS))
                withTimeout(5_000) { job.cancelAndJoin() }
            } finally {
                itemGate.countDown()
            }

            assertTrue("the HTTP call is cancelled", calls.single().isCanceled())
            assertTrue("the read is cancelled with its caller", response.get()!!.translation.isCancelled)
            assertEquals("the item read before the cancel stays", mapOf(0 to "Hello"), response.get()!!.items.value)
            assertNothingRecorded()
            assertEquals("a cancelled read logs no final line", emptyList<String>(), logLines("final "))
            assertEquals(1, logLines("part1 ").size)
        }

    @Test fun `the two clock lines carry what the read saw`() = runBlocking {
        val final = part2(translations = """[{"id":0,"translation":"Hello"},{"id":1,"translation":"World"}]""")
        val response = client(
            listOf(part1, item(0, "Helo"), item(1, "World"), final),
            headers = mapOf("x-pt-edge-upload-ms" to "118", "x-pt-edge-ms" to "640.4"),
        ).postFrame(PtCloudTestSupport.frame(), PtCloudTestSupport.meta(streamTranslations = true))
        withTimeout(5_000) { response.translation.await() }

        // The test clients have no event listener and Robolectric's clock
        // stands still, so reuse and upload are dashes and the times are 0.
        assertEquals(
            listOf(
                "part1 id=capture-0042 fmt=jpeg bytes=64 size=1920x1080 encode=0 hdr=0 part1=0 groups=1 tr1=0 " +
                    "reused=- upload=- warm=none edge_upload=118 edge=640 recv=- ocr=665 ocr_total=-",
            ),
            logLines("part1 "),
        )
        assertEquals(
            listOf(
                "final id=capture-0042 status=ok err=- items=2 item_first=0 item_last=0 final=0 reworded=1 " +
                    "translate=988 total=1210",
            ),
            logLines("final "),
        )
    }
}
