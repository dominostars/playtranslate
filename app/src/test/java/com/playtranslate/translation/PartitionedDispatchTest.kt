package com.playtranslate.translation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins [dispatchPartitioned]'s alignment contract — the seam where the
 * short-text offline route splits a miss-batch and recombines it positionally.
 * Index-alignment bugs here would serve group A's translation on group B's
 * box, so every recombination path is pinned.
 */
class PartitionedDispatchTest {

    private class OnlineRecorder {
        val calls = mutableListOf<List<String>>()
        suspend fun serve(batch: List<String>): List<String> {
            calls += batch
            return batch.map { "on:$it" }
        }
    }

    @Test fun `offline and online results recombine in original order`() = runBlocking {
        val online = OnlineRecorder()
        val result = dispatchPartitioned(
            texts = listOf("a", "b", "c", "d", "e"),
            routeOffline = listOf(true, false, true, false, true),
            offline = { "off:$it" },
            online = online::serve,
        )
        assertEquals(listOf("off:a", "on:b", "off:c", "on:d", "off:e"), result.all)
        assertEquals(listOf("on:b", "on:d"), result.fromOnline)
    }

    @Test fun `online is invoked exactly once with only the long texts in order`() = runBlocking {
        val online = OnlineRecorder()
        dispatchPartitioned(
            texts = listOf("s1", "long1", "s2", "long2"),
            routeOffline = listOf(true, false, true, false),
            offline = { "off:$it" },
            online = online::serve,
        )
        assertEquals(listOf(listOf("long1", "long2")), online.calls)
    }

    @Test fun `an offline failure folds into the same single online call at its index`() = runBlocking {
        val online = OnlineRecorder()
        val result = dispatchPartitioned(
            texts = listOf("ok", "fails", "long"),
            routeOffline = listOf(true, true, false),
            offline = { if (it == "fails") null else "off:$it" },
            online = online::serve,
        )
        assertEquals(1, online.calls.size)
        assertEquals(listOf("fails", "long"), online.calls.single())
        assertEquals(listOf("off:ok", "on:fails", "on:long"), result.all)
        assertEquals(listOf("on:fails", "on:long"), result.fromOnline)
    }

    @Test fun `duplicate texts classified identically both get results`() = runBlocking {
        val result = dispatchPartitioned(
            texts = listOf("dup", "long", "dup"),
            routeOffline = listOf(true, false, true),
            offline = { "off:$it" },
            online = { batch -> batch.map { "on:$it" } },
        )
        assertEquals(listOf("off:dup", "on:long", "off:dup"), result.all)
    }

    @Test fun `all-short never invokes online and fromOnline is empty`() = runBlocking {
        val online = OnlineRecorder()
        val result = dispatchPartitioned(
            texts = listOf("a", "b"),
            routeOffline = listOf(true, true),
            offline = { "off:$it" },
            online = online::serve,
        )
        assertTrue(online.calls.isEmpty())
        assertTrue(result.fromOnline.isEmpty())
        assertEquals(listOf("off:a", "off:b"), result.all)
    }

    @Test fun `all-long never invokes offline`() = runBlocking {
        var offlineCalls = 0
        val result = dispatchPartitioned(
            texts = listOf("a", "b"),
            routeOffline = listOf(false, false),
            offline = { offlineCalls++; "off:$it" },
            online = { batch -> batch.map { "on:$it" } },
        )
        assertEquals(0, offlineCalls)
        assertEquals(listOf("on:a", "on:b"), result.all)
        assertEquals(result.all, result.fromOnline)
    }

    @Test fun `empty input invokes neither lambda`() = runBlocking {
        var touched = 0
        val result = dispatchPartitioned<String>(
            texts = emptyList(),
            routeOffline = emptyList(),
            offline = { touched++; it },
            online = { touched++; it },
        )
        assertEquals(0, touched)
        assertTrue(result.all.isEmpty())
    }

    @Test fun `routeOffline size mismatch is rejected`() = runBlocking {
        try {
            dispatchPartitioned(
                texts = listOf("a", "b"),
                routeOffline = listOf(true),
                offline = { it },
                online = { it },
            )
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test fun `online result-count mismatch trips the contract guard`() = runBlocking {
        try {
            dispatchPartitioned(
                texts = listOf("a", "b"),
                routeOffline = listOf(false, false),
                offline = { it },
                online = { listOf("only-one") },
            )
            fail("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
        }
    }
}
