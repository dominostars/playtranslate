package com.playtranslate.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure-JVM tests for the [CooldownState] state machine. Tests use the
 * `context=null` constructor (no SharedPreferences) and inject a
 * controllable clock via the `nowMs` parameter so we can fast-forward
 * past cooldown windows without sleeping.
 */
class CooldownStateTest {

    private fun newState(clock: AtomicLong = AtomicLong(1_000_000L)): CooldownState =
        CooldownState(context = null, backendId = "test", nowMs = { clock.get() })

    @Test fun `initial state reports no cooldown`() {
        val s = newState()
        assertNull(s.unavailableUntil())
        assertNull(s.unavailableDescription())
    }

    @Test fun `recordParsedFailure sets cooldown to the supplied retryAt`() {
        val clock = AtomicLong(1_000_000L)
        val s = newState(clock)
        val future = clock.get() + 60_000
        s.recordParsedFailure(future, "Rate limited")
        assertEquals(future, s.unavailableUntil())
        assertEquals("Rate limited", s.unavailableDescription())
    }

    @Test fun `concurrent recordParsedFailure during cooldown is absorbed`() {
        val clock = AtomicLong(1_000_000L)
        val s = newState(clock)
        val first = clock.get() + 60_000
        s.recordParsedFailure(first, "Rate limited")
        // Simulates batched fan-out where 5 texts each see the same 429.
        // The second call should NOT extend or replace the cooldown.
        s.recordParsedFailure(first + 100_000, "Different reason")
        assertEquals(first, s.unavailableUntil())
        assertEquals("Rate limited", s.unavailableDescription())
    }

    @Test fun `recordLadderFailure rate-limit tier starts at 1 minute`() {
        val clock = AtomicLong(1_000_000L)
        val s = newState(clock)
        s.recordLadderFailure(CooldownLadder.RateLimit, "Rate limited")
        assertEquals(clock.get() + 60_000L, s.unavailableUntil())
    }

    @Test fun `recordLadderFailure network tier starts at 30 seconds`() {
        val clock = AtomicLong(1_000_000L)
        val s = newState(clock)
        s.recordLadderFailure(CooldownLadder.Network, "Connection failed")
        assertEquals(clock.get() + 30_000L, s.unavailableUntil())
    }

    @Test fun `ladder rung advances across separate failure cycles`() {
        val clock = AtomicLong(1_000_000L)
        val s = newState(clock)

        // Cycle 1: rung 0 → 1m cooldown
        s.recordLadderFailure(CooldownLadder.RateLimit, "A")
        assertEquals(clock.get() + 60_000L, s.unavailableUntil())

        // Fast-forward past the cooldown window. unavailableUntil()
        // auto-clears the stale timestamp but preserves the rung.
        clock.addAndGet(120_000)
        assertNull(s.unavailableUntil())

        // Cycle 2: rung 1 → 10m cooldown
        s.recordLadderFailure(CooldownLadder.RateLimit, "B")
        assertEquals(clock.get() + 600_000L, s.unavailableUntil())

        // Cycle 3
        clock.addAndGet(1_200_000)
        assertNull(s.unavailableUntil())
        s.recordLadderFailure(CooldownLadder.RateLimit, "C")
        assertEquals(clock.get() + 3_600_000L, s.unavailableUntil())  // 60m

        // Cycle 4 → 4h
        clock.addAndGet(7_200_000)
        assertNull(s.unavailableUntil())
        s.recordLadderFailure(CooldownLadder.RateLimit, "D")
        assertEquals(clock.get() + 4 * 60 * 60 * 1000L, s.unavailableUntil())
    }

    @Test fun `recordSuccess clears state and resets rung`() {
        val clock = AtomicLong(1_000_000L)
        val s = newState(clock)
        s.recordLadderFailure(CooldownLadder.RateLimit, "A")
        // Pass a "started after the failure" timestamp so the
        // stale-success guard doesn't kick in.
        s.recordSuccess(attemptStartedAtMs = clock.get())
        assertNull(s.unavailableUntil())
        assertNull(s.unavailableDescription())

        // Next ladder failure starts at the bottom rung again.
        s.recordLadderFailure(CooldownLadder.RateLimit, "B")
        assertEquals(clock.get() + 60_000L, s.unavailableUntil())
    }

    @Test fun `recordSuccess refuses to clear a cooldown set after the attempt started`() {
        val clock = AtomicLong(1_000_000L)
        val s = newState(clock)

        // Simulate the concurrent-success race:
        //   T=0:  capture A starts (records attemptStartedAt)
        //   T=10: capture B starts on the same backend
        //   T=20: capture B fails with a 429 → records cooldown
        //   T=30: capture A completes successfully → recordSuccess(attemptStartedAt=0)
        // The guard must keep the cooldown set by B.
        val attemptStartedAt = clock.get()  // T=0
        clock.addAndGet(20)                  // T=20
        s.recordLadderFailure(CooldownLadder.RateLimit, "from sibling")
        val coolUntilSet = s.unavailableUntil()
        assertNotNull(coolUntilSet)

        clock.addAndGet(10)                  // T=30 — A completes
        s.recordSuccess(attemptStartedAtMs = attemptStartedAt)

        // Cooldown must still be set: the success pre-dated the failure.
        assertEquals(coolUntilSet, s.unavailableUntil())
        assertEquals("from sibling", s.unavailableDescription())
    }

    @Test fun `unavailableUntil clears stale timestamp automatically`() {
        val clock = AtomicLong(1_000_000L)
        val s = newState(clock)
        s.recordParsedFailure(clock.get() + 5_000, "expired soon")
        assertNotNull(s.unavailableUntil())

        clock.addAndGet(10_000)
        assertNull(s.unavailableUntil())
        assertNull(s.unavailableDescription())
    }

    @Test fun `first network failure is ignored`() {
        val s = newState()
        s.recordNetworkFailure("Connection failed")
        // First IOException is forgiven — single wifi blip shouldn't penalise.
        assertNull(s.unavailableUntil())
    }

    @Test fun `second network failure within window engages ladder`() {
        val clock = AtomicLong(1_000_000L)
        val s = newState(clock)
        s.recordNetworkFailure("Connection failed")
        clock.addAndGet(10_000)  // within the 60s pair window
        s.recordNetworkFailure("Connection failed")
        assertNotNull(s.unavailableUntil())
        assertEquals("Connection failed", s.unavailableDescription())
    }

    @Test fun `network failures spaced beyond pair window stay forgiven`() {
        val clock = AtomicLong(1_000_000L)
        val s = newState(clock)
        s.recordNetworkFailure("first")
        clock.addAndGet(90_000)  // beyond the 60s pair window
        s.recordNetworkFailure("second")
        // Both treated as isolated first failures — no cooldown.
        assertNull(s.unavailableUntil())
    }

    @Test fun `ladder caps at maximum rung`() {
        val clock = AtomicLong(1_000_000L)
        val s = newState(clock)
        // Drive rung through all 4 tiers, then beyond.
        for (i in 0..5) {
            s.recordLadderFailure(CooldownLadder.RateLimit, "cycle$i")
            // Fast-forward past whatever cooldown was set.
            clock.addAndGet(5L * 60 * 60 * 1000)
            assertNull(s.unavailableUntil())
        }
        // After 6 cycles, the rung is capped at 3 → 4h cooldown.
        s.recordLadderFailure(CooldownLadder.RateLimit, "final")
        assertEquals(clock.get() + 4 * 60 * 60 * 1000L, s.unavailableUntil())
    }

    @Test fun `Cooldownable interface delegates correctly`() {
        val clock = AtomicLong(1_000_000L)
        val s = newState(clock)
        val cooldownable: Cooldownable = s
        s.recordParsedFailure(clock.get() + 60_000, "Rate limited")
        assertNotNull(cooldownable.unavailableUntil())
        assertEquals("Rate limited", cooldownable.unavailableDescription())
        cooldownable.recordSuccess(clock.get())
        assertNull(cooldownable.unavailableUntil())
    }
}
