package com.playtranslate.overlay

import com.playtranslate.overlay.OwnWindowClock.Decision
import com.playtranslate.overlay.OwnWindowClock.MAX_WAIT_MS
import com.playtranslate.overlay.OwnWindowClock.NO_EVENT
import com.playtranslate.overlay.OwnWindowClock.QUIET_MS
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the hold rule behind the MediaProjection VirtualDisplay creation —
 * the Thor display-service deadlock guard (see [OwnWindowClock]). The pure
 * [OwnWindowClock.decide] cells first, then the [OwnWindowClock.awaitQuiet]
 * loop against an injected clock and sleep, so a re-tick during the hold,
 * the refusal at the cap and quiet-wins-at-the-cap are exercised without
 * real time passing. The cap must REFUSE, never release: a release would
 * create the display right after an event, the overlap the whole guard
 * exists to avoid (Codex adversarial review, 2026-09-20).
 */
class OwnWindowClockTest {

    private var t = 10_000L

    @Before
    fun setUp() {
        OwnWindowClock.reset()
        OwnWindowClock.now = { t }
    }

    @After
    fun tearDown() {
        OwnWindowClock.reset()
        OwnWindowClock.now = { android.os.SystemClock.uptimeMillis() }
    }

    // ── decide ───────────────────────────────────────────────────────────

    @Test
    fun `no event ever is quiet`() {
        assertEquals(Decision.Quiet, OwnWindowClock.decide(now = 5_000, lastEventUptime = NO_EVENT, waitStartedAt = 5_000))
    }

    @Test
    fun `an event just now sleeps the full quiet span`() {
        assertEquals(Decision.Sleep(QUIET_MS), OwnWindowClock.decide(now = 5_000, lastEventUptime = 5_000, waitStartedAt = 5_000))
    }

    @Test
    fun `a partly elapsed quiet span sleeps the remainder`() {
        assertEquals(Decision.Sleep(QUIET_MS - 300), OwnWindowClock.decide(now = 5_300, lastEventUptime = 5_000, waitStartedAt = 5_300))
    }

    @Test
    fun `quiet elapsed exactly is quiet`() {
        assertEquals(Decision.Quiet, OwnWindowClock.decide(now = 5_000 + QUIET_MS, lastEventUptime = 5_000, waitStartedAt = 5_000 + QUIET_MS))
    }

    @Test
    fun `an event just before the cap refuses`() {
        val start = 5_000L
        val now = start + MAX_WAIT_MS
        assertEquals(Decision.Refuse, OwnWindowClock.decide(now = now, lastEventUptime = now - 10, waitStartedAt = start))
    }

    @Test
    fun `past the cap with an event still fresh refuses`() {
        val start = 5_000L
        val now = start + MAX_WAIT_MS + 400
        assertEquals(Decision.Refuse, OwnWindowClock.decide(now = now, lastEventUptime = now - 100, waitStartedAt = start))
    }

    @Test
    fun `quiet reached exactly at the cap is quiet, not refused`() {
        val start = 5_000L
        val now = start + MAX_WAIT_MS
        assertEquals(Decision.Quiet, OwnWindowClock.decide(now = now, lastEventUptime = now - QUIET_MS, waitStartedAt = start))
    }

    @Test
    fun `the next sleep never overshoots the cap`() {
        val start = 5_000L
        val now = start + MAX_WAIT_MS - 100
        assertEquals(Decision.Sleep(100), OwnWindowClock.decide(now = now, lastEventUptime = now, waitStartedAt = start))
    }

    // ── awaitQuiet loop ──────────────────────────────────────────────────

    /** Sleep that advances the injected clock, recording each span. */
    private class FakeSleep(private val advance: (Long) -> Unit) {
        val spans = mutableListOf<Long>()
        val sleep: suspend (Long) -> Unit = { ms -> spans += ms; advance(ms) }
    }

    @Test
    fun `awaitQuiet returns at once with no events`() = runBlocking {
        val sleep = FakeSleep { t += it }
        val r = OwnWindowClock.awaitQuiet(sleep.sleep)
        assertTrue(r.quiet)
        assertEquals(0L, r.heldMs)
        assertEquals(emptyList<Long>(), sleep.spans)
    }

    @Test
    fun `awaitQuiet returns at once when the last event is old`() = runBlocking {
        OwnWindowClock.tick()
        t += QUIET_MS
        val sleep = FakeSleep { t += it }
        val r = OwnWindowClock.awaitQuiet(sleep.sleep)
        assertTrue(r.quiet)
        assertEquals(0L, r.heldMs)
        assertEquals(emptyList<Long>(), sleep.spans)
    }

    @Test
    fun `awaitQuiet holds one quiet span after a fresh event`() = runBlocking {
        OwnWindowClock.tick()
        val sleep = FakeSleep { t += it }
        val r = OwnWindowClock.awaitQuiet(sleep.sleep)
        assertTrue(r.quiet)
        assertEquals(QUIET_MS, r.heldMs)
        assertEquals(listOf(QUIET_MS), sleep.spans)
    }

    @Test
    fun `an event during the hold extends it`() = runBlocking {
        OwnWindowClock.tick()
        var ticked = false
        // The first sleep is interrupted by an event 300 ms in: the clock
        // advances the full span, but the event happened at +300.
        val sleep = FakeSleep { ms ->
            if (!ticked) {
                t += 300
                OwnWindowClock.tick()
                ticked = true
                t += ms - 300
            } else {
                t += ms
            }
        }
        val r = OwnWindowClock.awaitQuiet(sleep.sleep)
        assertTrue(r.quiet)
        assertEquals(QUIET_MS + 300, r.heldMs)
        assertEquals(listOf(QUIET_MS, 300L), sleep.spans)
    }

    @Test
    fun `continuous events refuse at the cap`() = runBlocking {
        OwnWindowClock.tick()
        // Every sleep ends with another event, so quiet never comes.
        val sleep = FakeSleep { ms -> t += ms; OwnWindowClock.tick() }
        val r = OwnWindowClock.awaitQuiet(sleep.sleep)
        assertFalse(r.quiet)
        assertEquals(MAX_WAIT_MS, r.heldMs)
        assertEquals(MAX_WAIT_MS, sleep.spans.sum())
    }

    @Test
    fun `an event inside the final quiet span refuses at the cap`() = runBlocking {
        OwnWindowClock.tick()
        val start = t
        val cap = start + MAX_WAIT_MS
        // Events keep landing at every wake; the last one lands 10 ms
        // before the cap, inside the final quiet span.
        val sleep = FakeSleep { ms ->
            val wake = t + ms
            if (wake >= cap) {
                t = cap - 10
                OwnWindowClock.tick()
                t = wake
            } else {
                t = wake
                OwnWindowClock.tick()
            }
        }
        val r = OwnWindowClock.awaitQuiet(sleep.sleep)
        assertFalse(r.quiet)
        assertEquals(MAX_WAIT_MS, r.heldMs)
    }

    @Test
    fun `quiet reached exactly at the cap releases`() = runBlocking {
        OwnWindowClock.tick()
        val start = t
        val lastTickAt = start + MAX_WAIT_MS - QUIET_MS
        // Events keep landing at every wake until exactly one quiet span
        // before the cap, then stop: at the cap the span has just elapsed.
        val sleep = FakeSleep { ms ->
            val wake = t + ms
            if (wake <= lastTickAt) {
                t = wake
                OwnWindowClock.tick()
            } else {
                if (t < lastTickAt) {
                    t = lastTickAt
                    OwnWindowClock.tick()
                }
                t = wake
            }
        }
        val r = OwnWindowClock.awaitQuiet(sleep.sleep)
        assertTrue(r.quiet)
        assertEquals(MAX_WAIT_MS, r.heldMs)
    }

    @Test
    fun `sinceLastEventMs reads null before any event and the gap after`() {
        assertEquals(null, OwnWindowClock.sinceLastEventMs())
        OwnWindowClock.tick()
        t += 120
        assertEquals(120L, OwnWindowClock.sinceLastEventMs())
    }
}
