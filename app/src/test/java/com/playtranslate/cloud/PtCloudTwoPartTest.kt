package com.playtranslate.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.cloud.PtCloudTestSupport.GatedSource
import com.playtranslate.translation.CooldownState
import com.playtranslate.translation.OnlineAttempt
import com.playtranslate.translation.TranslationBackendRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The two-part response read as it streams: [PtCloudClient.postFrame]
 * returns at part 1 while part 2 is still held back by the server, part 2
 * then resolves [FrameResponse.translation] without ever throwing into the
 * caller, and cancelling the caller cancels the HTTP call at once and
 * reports nothing. The body is a gated source: each line waits for its own
 * latch, the way a server flushes part 1 and answers part 2 a second later.
 */
@RunWith(RobolectricTestRunner::class)
class PtCloudTwoPartTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val attempts = Collections.synchronizedList(mutableListOf<OnlineAttempt>())
    private val calls = Collections.synchronizedList(mutableListOf<Call>())
    private lateinit var cooldown: CooldownState

    @Before fun setUp() {
        PtCloudTestSupport.sharedPrefs(ctx).edit().clear().commit()
        cooldown = CooldownState(null, PtCloudService.ID)
        PtCloudService.installForTest(cooldown)
        TranslationBackendRegistry.onlineAttemptListener = { attempts += it }
    }

    @After fun tearDown() {
        PtCloudService.installForTest(null)
        TranslationBackendRegistry.onlineAttemptListener = null
        PtCloudTestSupport.sharedPrefs(ctx).edit().clear().commit()
    }

    private val part1 = PtCloudTestSupport.part1(PtCloudTestSupport.JA)

    private fun client(lines: List<String>, gates: List<CountDownLatch?>, waiting: CountDownLatch = CountDownLatch(0)) =
        PtCloudClient(
            PtCloudTestSupport.config(ctx),
            clientFactory = {
                PtCloudTestSupport.streamingClient(calls) { call -> GatedSource(call, lines, gates, waiting) }
            },
        )

    private fun assertNothingRecorded() {
        assertTrue("no attempt reported: $attempts", attempts.isEmpty())
        assertNull("no cooldown recorded", cooldown.unavailableUntil())
    }

    @Test fun `postFrame returns at part 1 while part 2 is still held, and part 2 fills in on release`() = runBlocking {
        val part2Gate = CountDownLatch(1)
        val c = client(listOf(part1, PtCloudTestSupport.part2()), listOf(null, part2Gate))

        // Called straight from this job, never inside a scoped builder
        // (withTimeout, withContext, coroutineScope): part 2 is a child of
        // the calling job, and a scope waits for its children, so it would
        // not return before part 2.
        val response = c.postFrame(PtCloudTestSupport.frame(), PtCloudTestSupport.meta())
        // Part 1 is decoded while the server still holds part 2.
        assertEquals(1, response.ocr.groups.size)
        assertEquals("bof3_camping_01", response.ocr.frameId)
        assertFalse("part 2 must still be pending", response.translation.isCompleted)

        part2Gate.countDown()
        val part2 = withTimeout(5_000) { response.translation.await() }
        part2 as Part2Result.Line
        assertEquals("ok", part2.part.translationStatus)
        assertEquals(listOf(0 to "Hello"), part2.part.translations!!.map { it.id to it.translation })
        // Success is the reader's to record and report, not the client's.
        assertNothingRecorded()
        // The two stamps are on their own clocks.
        assertTrue(response.attemptStartedAtMs > 1_000_000_000_000L)
    }

    @Test fun `a body that ends after part 1 resolves Ended`() = runBlocking {
        val response = client(listOf(part1), emptyList())
            .postFrame(PtCloudTestSupport.frame(), PtCloudTestSupport.meta())
        assertEquals(Part2Result.Ended, withTimeout(5_000) { response.translation.await() })
        assertNothingRecorded()
    }

    @Test fun `a garbage second line resolves Failed and never fails the caller`() = runBlocking {
        for (garbage in listOf("not json at all", """{"part":"translation"}""", "[1,2,3]")) {
            val response = client(listOf(part1, garbage), emptyList())
                .postFrame(PtCloudTestSupport.frame(), PtCloudTestSupport.meta())
            val result = withTimeout(5_000) { response.translation.await() }
            assertTrue("$garbage -> $result", result is Part2Result.Failed)
            assertTrue((result as Part2Result.Failed).cause is IllegalArgumentException)
        }
        // A failed async child would have cancelled this job.
        assertTrue(coroutineContext.job.isActive)
        assertNothingRecorded()
    }

    @Test fun `cancelling the caller while part 1 is pending cancels the call and records nothing`() = runBlocking {
        val part1Gate = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val c = client(listOf(part1, PtCloudTestSupport.part2()), listOf(part1Gate, null), waiting)
        val thrown = AtomicReference<Throwable?>()
        val job = launch(Dispatchers.IO) {
            try {
                c.postFrame(PtCloudTestSupport.frame(), PtCloudTestSupport.meta())
            } catch (t: Throwable) {
                thrown.set(t)
                throw t
            }
        }
        assertTrue("the read must reach part 1's gate", waiting.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) { job.cancelAndJoin() }

        assertTrue("the HTTP call is cancelled", calls.single().isCanceled())
        assertTrue("postFrame ends in cancellation, not a read failure: ${thrown.get()}", thrown.get() is CancellationException)
        assertFalse(thrown.get() is PtCloudReadException)
        assertNothingRecorded()
    }

    @Test fun `cancelling the caller while part 2 is pending cancels the call and records nothing`() = runBlocking {
        val part2Gate = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val c = client(listOf(part1, PtCloudTestSupport.part2()), listOf(null, part2Gate), waiting)
        val response = AtomicReference<FrameResponse?>()
        val job = launch(Dispatchers.IO) {
            val r = c.postFrame(PtCloudTestSupport.frame(), PtCloudTestSupport.meta())
            response.set(r)
            r.translation.await()
        }
        assertTrue("part 2's read must reach its gate", waiting.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) { job.cancelAndJoin() }

        assertTrue(calls.single().isCanceled())
        assertTrue("part 2's reader is cancelled with its caller", response.get()!!.translation.isCancelled)
        assertNothingRecorded()
    }

    @Test fun `a caller that lets part 2 go cancels it without failing itself`() = runBlocking {
        val part2Gate = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val c = client(listOf(part1, PtCloudTestSupport.part2()), listOf(null, part2Gate), waiting)
        val response = c.postFrame(PtCloudTestSupport.frame(), PtCloudTestSupport.meta())
        assertTrue(waiting.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) {
            response.translation.cancel()
            response.translation.join()
        }
        assertTrue(calls.single().isCanceled())
        assertTrue(coroutineContext.job.isActive)
        assertNothingRecorded()
    }
}
