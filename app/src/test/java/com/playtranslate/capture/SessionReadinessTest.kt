package com.playtranslate.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the first-frame readiness the guarded clean-capture order anchors
 * on ([SessionReadiness]): not ready until a delivery above the creation
 * seq, ready thereafter, per creation, and — the Codex round-2 cell — a
 * warm-up that timed out leaves the state not ready so the retry waits
 * for the late first delivery instead of anchoring without it.
 */
class SessionReadinessTest {

    @Test
    fun `nothing created is never ready`() {
        val r = SessionReadiness()
        assertFalse(r.isReady)
        assertFalse(r.observe(0))
        assertFalse(r.observe(Long.MAX_VALUE))
    }

    @Test
    fun `ready on the first delivery above the creation seq, and stays ready`() {
        val r = SessionReadiness()
        r.onCreated(5)
        assertFalse(r.isReady)
        assertFalse(r.observe(5))
        assertTrue(r.observe(6))
        assertTrue(r.isReady)
        assertTrue(r.observe(3))
        assertTrue(r.isReady)
    }

    @Test
    fun `a new output surface starts over, resize included`() {
        val r = SessionReadiness()
        r.onCreated(5)
        r.observe(6)
        r.onCreated(10)
        assertFalse(r.isReady)
        assertFalse(r.observe(10))
        assertTrue(r.observe(11))
    }

    @Test
    fun `a timed-out warm-up leaves the session not ready until the late first delivery`() {
        val r = SessionReadiness()
        r.onCreated(5)
        // Attempt 1: the bound expires having seen only the pre-creation seq.
        assertFalse(r.observe(5))
        assertFalse(r.isReady)
        // Attempt 2 finds the state still not ready, waits, and the late
        // first delivery lands.
        assertFalse(r.isReady)
        assertTrue(r.observe(6))
        assertTrue(r.isReady)
    }

    @Test
    fun `teardown resets everything`() {
        val r = SessionReadiness()
        r.onCreated(5)
        r.observe(6)
        r.reset()
        assertFalse(r.isReady)
        assertFalse(r.observe(100))
    }
}
