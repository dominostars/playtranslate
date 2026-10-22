package com.playtranslate

import com.playtranslate.ocr.ReaderProgress
import com.playtranslate.ocr.ReaderTranslations
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReaderTranslations], the one seam every consumer takes a reader's
 * translations through. One cell per rule:
 *  - what arrived with the OCR ([ReaderTranslations.ready]) is in every state
 *    and wins over anything delivered for the same group; blanks are no text;
 *    provisional deliveries are dropped by a settle with nothing; nothing
 *    changes once settled;
 *  - [ReaderTranslations.awaited]: the positions that take a group not
 *    already ready, empty once settled; [ReaderTranslations.pending]: true
 *    only while it is not empty;
 *  - [ReaderTranslations.finalSoFar]: ready until the settle, deliveries
 *    never included; the settled map after;
 *  - [ReaderTranslations.await]: no wait when not pending, each growth reported
 *    through onMore but neither the starting ready set nor the settled state,
 *    and a cancelled consumer stops waiting without settling the reader;
 *  - [ReaderTranslations.forPositions]: a view by position, composed through a
 *    view's own mapping, null when nothing maps, sharing the root's state;
 *  - [ReaderTranslations.isFor]: exactly the pair the reader sent.
 *
 * Plain JVM: nothing here touches Android. The reader's job is a bare [Job],
 * since no cell runs reader work.
 */
class ReaderTranslationsTest {

    private fun root(groupCount: Int, ready: Map<Int, String> = emptyMap(), job: Job = Job()) =
        ReaderTranslations("ja", "en", "Cloud", groupCount, ready, job)

    /** Lets the single-threaded test loop run other coroutines until [done]. */
    private suspend fun until(done: () -> Boolean) {
        withTimeout(5_000) { while (!done()) yield() }
    }

    // ── ready, now, deliver, settle ──────────────────────────────────────

    @Test fun `what arrived with the OCR is ready and is the first state, blanks dropped`() {
        val r = root(3, ready = mapOf(0 to "one", 1 to "  ", 2 to ""))
        assertEquals(mapOf(0 to "one"), r.ready)
        assertEquals(ReaderProgress(mapOf(0 to "one"), settled = false), r.now)
    }

    @Test fun `a delivery adds to ready, ready winning for its own group, blanks dropped`() {
        val r = root(3, ready = mapOf(0 to "one"))
        r.deliver(mapOf(0 to "uno", 1 to "two", 2 to " "))
        assertEquals(ReaderProgress(mapOf(0 to "one", 1 to "two"), settled = false), r.now)
    }

    @Test fun `a later delivery replaces the earlier one, not adds to it`() {
        val r = root(3)
        r.deliver(mapOf(1 to "two"))
        r.deliver(mapOf(2 to "three"))
        assertEquals(mapOf(2 to "three"), r.now.byPosition)
    }

    @Test fun `a settle with texts is the final word, ready kept`() {
        val r = root(3, ready = mapOf(0 to "one"))
        r.deliver(mapOf(1 to "tow"))
        r.settle(mapOf(0 to "other", 1 to "two", 2 to "three"))
        assertEquals(ReaderProgress(mapOf(0 to "one", 1 to "two", 2 to "three"), settled = true), r.now)
    }

    @Test fun `a settle with nothing drops what was delivered and keeps ready`() {
        val r = root(3, ready = mapOf(0 to "one"))
        r.deliver(mapOf(1 to "two", 2 to "three"))
        r.settle()
        assertEquals(ReaderProgress(mapOf(0 to "one"), settled = true), r.now)
    }

    @Test fun `nothing changes once settled`() {
        val r = root(2, ready = mapOf(0 to "one"))
        r.settle(mapOf(1 to "two"))
        r.deliver(mapOf(1 to "late"))
        r.settle()
        r.settle(mapOf(1 to "again"))
        assertEquals(ReaderProgress(mapOf(0 to "one", 1 to "two"), settled = true), r.now)
    }

    @Test fun `cancel cancels the reader's job`() {
        val job = Job()
        root(1, job = job).cancel()
        assertTrue(job.isCancelled)
    }

    // ── pending ──────────────────────────────────────────────────────────

    @Test fun `pending while a position's group is not ready and the reader has not settled`() {
        val r = root(2, ready = mapOf(0 to "one"))
        assertTrue(r.pending)
        r.deliver(mapOf(1 to "two"))
        assertTrue("a delivery is provisional: more can still arrive", r.pending)
    }

    @Test fun `not pending once settled`() {
        val r = root(2, ready = mapOf(0 to "one"))
        r.settle()
        assertFalse(r.pending)
    }

    @Test fun `not pending when every position it can name arrived with the OCR`() {
        val r = root(2, ready = mapOf(0 to "one", 1 to "two"))
        assertFalse(r.pending)
        // A view whose only position takes a ready group: nothing to wait for,
        // though the root waits for group 1.
        val partly = root(2, ready = mapOf(0 to "one"))
        assertTrue(partly.pending)
        assertFalse(partly.forPositions(listOf(0, null))!!.pending)
    }

    @Test fun `a position that takes no group never counts`() {
        val r = root(2, ready = mapOf(0 to "one"))
        assertFalse(r.forPositions(listOf(null, 0, null))!!.pending)
        assertTrue(r.forPositions(listOf(null, 1))!!.pending)
    }

    // ── awaited ──────────────────────────────────────────────────────────

    @Test fun `awaited is the positions whose group is not ready, by position, a null position never counted`() {
        val r = root(3, ready = mapOf(0 to "one"))
        assertEquals(setOf(1, 2), r.awaited)
        // Position 0 takes no group, position 2 takes the ready group 0.
        assertEquals(setOf(1, 3), r.forPositions(listOf(null, 2, 0, 1))!!.awaited)
    }

    @Test fun `awaited stays through a delivery and is empty once settled`() {
        val r = root(3, ready = mapOf(0 to "one"))
        r.deliver(mapOf(1 to "two", 2 to "three"))
        assertEquals("a delivery is provisional: more can still arrive", setOf(1, 2), r.awaited)
        r.settle(mapOf(1 to "two"))
        assertEquals(emptySet<Int>(), r.awaited)
    }

    @Test fun `awaited is empty when everything arrived with the OCR`() {
        assertEquals(emptySet<Int>(), root(2, ready = mapOf(0 to "one", 1 to "two")).awaited)
        // A view whose only position takes a ready group, though the root
        // waits for group 1.
        val partly = root(2, ready = mapOf(0 to "one"))
        assertEquals(emptySet<Int>(), partly.forPositions(listOf(null, 0))!!.awaited)
    }

    // ── finalSoFar ───────────────────────────────────────────────────────

    @Test fun `finalSoFar is what arrived with the OCR until the settle, even after deliveries`() {
        val r = root(3, ready = mapOf(0 to "one"))
        assertEquals(mapOf(0 to "one"), r.finalSoFar)
        r.deliver(mapOf(1 to "two", 2 to "three"))
        assertEquals(mapOf(0 to "one"), r.finalSoFar)
        // A view, by its own positions.
        assertEquals(mapOf(1 to "one"), r.forPositions(listOf(1, 0))!!.finalSoFar)
    }

    @Test fun `finalSoFar is the settled map after the settle`() {
        val r = root(3, ready = mapOf(0 to "one"))
        r.deliver(mapOf(2 to "tree"))
        r.settle(mapOf(1 to "two", 2 to "three"))
        assertEquals(mapOf(0 to "one", 1 to "two", 2 to "three"), r.finalSoFar)
        assertEquals(mapOf(0 to "three", 1 to "one"), r.forPositions(listOf(2, 0))!!.finalSoFar)
    }

    // ── await ────────────────────────────────────────────────────────────

    @Test fun `await returns at once when nothing more can arrive, on a reader that never settles`() = runBlocking {
        val r = root(2, ready = mapOf(0 to "one", 1 to "two"))
        val reported = mutableListOf<Map<Int, String>>()
        // Never settled: a wait would time out.
        val got = withTimeout(1_000) { r.await { reported += it } }
        assertEquals(mapOf(0 to "one", 1 to "two"), got)
        assertEquals(emptyList<Map<Int, String>>(), reported)
    }

    @Test fun `await reports each growth, not ready, not the settled state, and returns the settled map`() = runBlocking {
        val r = root(3, ready = mapOf(0 to "one"))
        val reported = mutableListOf<Map<Int, String>>()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { r.await { reported += it } }
        // Collecting now, and the starting ready set was not reported.
        assertEquals(emptyList<Map<Int, String>>(), reported)

        r.deliver(mapOf(1 to "two"))
        until { reported.size == 1 }
        assertEquals(mapOf(0 to "one", 1 to "two"), reported[0])

        // The same map again (a group delivered twice with the same text) is
        // no growth.
        r.deliver(mapOf(1 to "two"))
        r.deliver(mapOf(1 to "two", 2 to "three"))
        until { reported.size == 2 }
        assertEquals(mapOf(0 to "one", 1 to "two", 2 to "three"), reported[1])

        r.settle(mapOf(1 to "two", 2 to "three!"))
        assertEquals(mapOf(0 to "one", 1 to "two", 2 to "three!"), withTimeout(5_000) { waiting.await() })
        assertEquals("the settled state is returned, not reported", 2, reported.size)
    }

    @Test fun `a settle with nothing returns ready after the items were reported`() = runBlocking {
        val r = root(2, ready = mapOf(0 to "one"))
        val reported = mutableListOf<Map<Int, String>>()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { r.await { reported += it } }
        r.deliver(mapOf(1 to "two"))
        until { reported.size == 1 }
        r.settle()
        assertEquals(mapOf(0 to "one"), withTimeout(5_000) { waiting.await() })
    }

    @Test fun `a consumer cancelled while waiting stops waiting and leaves the reader as it was`() = runBlocking {
        val job = Job()
        val r = root(2, ready = mapOf(0 to "one"), job = job)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { r.await() }
        waiting.cancel()
        withTimeout(5_000) { waiting.join() }
        assertTrue(waiting.isCancelled)
        // Only the reader's own job ending settles it; the consumer's finally
        // cancels that job ([ReaderTranslations.cancel]).
        assertTrue(r.pending)
        assertFalse(job.isCancelled)
    }

    @Test fun `a view reports only a growth at its own positions`() = runBlocking {
        val r = root(3)
        val view = r.forPositions(listOf(2))!!
        val reported = mutableListOf<Map<Int, String>>()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { view.await { reported += it } }
        r.deliver(mapOf(0 to "one"))
        r.deliver(mapOf(0 to "one", 2 to "three"))
        until { reported.isNotEmpty() }
        assertEquals(listOf(mapOf(0 to "three")), reported)
        r.settle(mapOf(0 to "one", 2 to "three"))
        assertEquals(mapOf(0 to "three"), withTimeout(5_000) { waiting.await() })
    }

    // ── forPositions ─────────────────────────────────────────────────────

    @Test fun `a view's position i takes the group its index names, not the map's order`() {
        val r = root(3, ready = linkedMapOf(2 to "three", 0 to "one", 1 to "two"))
        val view = r.forPositions(listOf(1, null, 2))!!
        assertEquals(mapOf(0 to "two", 2 to "three"), view.ready)
        assertEquals(mapOf(0 to "two", 2 to "three"), view.now.byPosition)
    }

    @Test fun `a view of a view composes the mappings`() {
        val r = root(4, ready = mapOf(0 to "one", 1 to "two", 2 to "three", 3 to "four"))
        // The first view's positions are groups 3, 1, 2; the second takes the
        // first view's positions 2 and 0, so groups 2 and 3.
        val first = r.forPositions(listOf(3, 1, 2))!!
        val second = first.forPositions(listOf(2, 0))!!
        assertEquals(mapOf(0 to "three", 1 to "four"), second.ready)
    }

    @Test fun `an index outside the view, or null, takes no group`() {
        val r = root(3)
        // The first view has two positions (groups 0 and 1): its index 2 is
        // outside it, though the root has a group 2.
        val first = r.forPositions(listOf(0, 1))!!
        assertNull(first.forPositions(listOf(2, 5, -1, null)))
        val second = first.forPositions(listOf(7, 0))!!
        r.deliver(mapOf(0 to "one", 2 to "three"))
        assertEquals(mapOf(1 to "one"), second.now.byPosition)
    }

    @Test fun `no position mapped gives no view`() {
        val r = root(2, ready = mapOf(0 to "one"))
        assertNull(r.forPositions(listOf(null, null)))
        assertNull(r.forPositions(emptyList()))
    }

    @Test fun `a view sees the root's deliveries and settle, and keeps its pair and provider`() {
        val r = root(3)
        val view = r.forPositions(listOf(2, 0))!!
        r.deliver(mapOf(2 to "three"))
        assertEquals(ReaderProgress(mapOf(0 to "three"), settled = false), view.now)
        r.settle(mapOf(0 to "one", 2 to "three"))
        assertEquals(ReaderProgress(mapOf(0 to "three", 1 to "one"), settled = true), view.now)
        assertEquals("Cloud", view.providerName)
        assertTrue(view.isFor("ja", "en"))
    }

    @Test fun `a view's deliveries reach the root, since they share one state`() {
        val r = root(2)
        r.forPositions(listOf(1))!!.deliver(mapOf(1 to "two"))
        assertEquals(mapOf(1 to "two"), r.now.byPosition)
    }

    // ── isFor ────────────────────────────────────────────────────────────

    @Test fun `translations are for exactly the pair the reader sent`() {
        val r = root(1)
        assertTrue(r.isFor("ja", "en"))
        // The target changed during the read, or the source did: the consumer
        // translates as if there were no reader.
        assertFalse(r.isFor("ja", "de"))
        assertFalse(r.isFor("zh", "en"))
    }
}
