package com.playtranslate.overlay

import com.playtranslate.overlay.WindowChurnGate.COMPOSITE_TIMEOUT_MS
import com.playtranslate.overlay.WindowChurnGate.DISPLAY_UNKNOWN
import com.playtranslate.overlay.WindowChurnGate.MAX_LINGER_MS
import com.playtranslate.overlay.WindowChurnGate.QUIET_GAP_MS
import com.playtranslate.overlay.WindowChurnGate.SEQ_UNKNOWN
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the churn gate's scheduling contract — the pure decisions behind
 * deferring our overlay-surface destroys away from our surface creations
 * (the Thor firmware system_server race; see [WindowChurnGate]). The
 * Handler/WindowManager plumbing is untestable off-device (returnDefaultValues,
 * same caveat as the Lingva client tests); the timing rules are the part a
 * regression would silently break.
 */
class WindowChurnGateScheduleTest {

    @Test
    fun `destroy waits while adds are recent`() {
        val submit = 1_000L
        val lastAdd = 1_100L
        assertFalse(
            WindowChurnGate.destroyEligible(
                now = lastAdd + QUIET_GAP_MS - 1, lastAddUptime = lastAdd, submittedAtUptime = submit
            )
        )
    }

    @Test
    fun `destroy runs once the quiet gap elapses`() {
        val submit = 1_000L
        val lastAdd = 1_100L
        assertTrue(
            WindowChurnGate.destroyEligible(
                now = lastAdd + QUIET_GAP_MS, lastAddUptime = lastAdd, submittedAtUptime = submit
            )
        )
    }

    @Test
    fun `each new add pushes the destroy back`() {
        val submit = 1_000L
        // An add lands 300ms after submission; at what would have been the
        // original quiet deadline the destroy must still be held.
        val laterAdd = submit + 300
        assertFalse(
            WindowChurnGate.destroyEligible(
                now = submit + QUIET_GAP_MS,
                lastAddUptime = laterAdd,
                submittedAtUptime = submit,
            )
        )
    }

    @Test
    fun `linger cap forces the destroy despite continuous adds`() {
        val submit = 1_000L
        val now = submit + MAX_LINGER_MS
        // Adds still churning — last one a moment ago — but the cap bounds
        // ghost lifetime: eligible anyway.
        assertTrue(
            WindowChurnGate.destroyEligible(
                now = now, lastAddUptime = now - 10, submittedAtUptime = submit
            )
        )
    }

    @Test
    fun `next check lands exactly at the quiet deadline`() {
        val submit = 1_000L
        val lastAdd = 1_200L
        val now = 1_300L
        assertEquals(
            lastAdd + QUIET_GAP_MS - now,
            WindowChurnGate.nextCheckDelayMs(
                now = now, lastAddUptime = lastAdd, oldestSubmittedAtUptime = submit
            )
        )
    }

    @Test
    fun `next check lands at the cap when adds keep refreshing the gap`() {
        val submit = 1_000L
        // The add clock keeps getting pushed so far forward that the cap
        // deadline is the nearer of the two.
        val lastAdd = submit + MAX_LINGER_MS - 100
        val now = lastAdd
        assertEquals(
            submit + MAX_LINGER_MS - now,
            WindowChurnGate.nextCheckDelayMs(
                now = now, lastAddUptime = lastAdd, oldestSubmittedAtUptime = submit
            )
        )
    }

    // ── Fast-path blocking (OverlayState.uncompositedGhost's input) ──────
    // A capture must leave its no-blank fast path exactly while a defused
    // ghost's alpha-0 may not have composited yet; blocking longer would
    // burn quiet-screen freshness budgets, shorter would re-open the
    // menu-dim-in-first-frame contamination.

    @Test
    fun `fresh uncomposited ghost on the captured display blocks the fast path`() {
        assertTrue(
            WindowChurnGate.ghostBlocksFastPath(
                now = 1_050L, defusedAtUptime = 1_000L, composited = false, visibleAtDefuse = true,
                ghostDisplayId = 2, captureDisplayId = 2,
            )
        )
    }

    @Test
    fun `composited ghost frees the fast path immediately`() {
        assertFalse(
            WindowChurnGate.ghostBlocksFastPath(
                now = 1_050L, defusedAtUptime = 1_000L, composited = true, visibleAtDefuse = true,
                ghostDisplayId = 2, captureDisplayId = 2,
            )
        )
    }

    @Test
    fun `composite timeout bounds a never-fired frame signal`() {
        assertFalse(
            WindowChurnGate.ghostBlocksFastPath(
                now = 1_000L + COMPOSITE_TIMEOUT_MS, defusedAtUptime = 1_000L, composited = false, visibleAtDefuse = true,
                ghostDisplayId = 2, captureDisplayId = 2,
            )
        )
    }

    @Test
    fun `ghost on another display never blocks this capture`() {
        assertFalse(
            WindowChurnGate.ghostBlocksFastPath(
                now = 1_050L, defusedAtUptime = 1_000L, composited = false, visibleAtDefuse = true,
                ghostDisplayId = 0, captureDisplayId = 2,
            )
        )
    }

    @Test
    fun `unknown-display ghost conservatively blocks every capture`() {
        assertTrue(
            WindowChurnGate.ghostBlocksFastPath(
                now = 1_050L, defusedAtUptime = 1_000L, composited = false, visibleAtDefuse = true,
                ghostDisplayId = DISPLAY_UNKNOWN, captureDisplayId = 2,
            )
        )
    }

    @Test
    fun `ghost that was already capture-blanked at removal never blocks`() {
        // A window removed while a clean capture held it at alpha 0: its
        // defuse composites nothing (0 -> 0) and every frame since its blank
        // already excludes it. Arming the capture wait on it would starve
        // the wait on a static screen and null the capture — the wait's
        // termination proof requires a repaint that this ghost never owed.
        assertFalse(
            WindowChurnGate.ghostBlocksFastPath(
                now = 1_050L, defusedAtUptime = 1_000L, composited = false, visibleAtDefuse = false,
                ghostDisplayId = 2, captureDisplayId = 2,
            )
        )
    }

    // ── Ghost-only capture anchor (the delivery-seq proof's input) ───────
    // Every blocking ghost was visible at defuse, so its repaint is
    // guaranteed to be delivered above its own recorded anchor. The LATEST
    // anchor is the conservative wait; one unknowable anchor voids the whole
    // proof (callers then fall back to the heuristic).

    @Test
    fun `anchor is the latest defuse seq across blocking ghosts`() {
        assertEquals(
            42L,
            WindowChurnGate.selectGhostAnchor(listOf(17L, 42L, 5L))
        )
    }

    @Test
    fun `no blocking ghosts means no anchor`() {
        assertNull(WindowChurnGate.selectGhostAnchor(emptyList()))
    }

    @Test
    fun `one unknowable anchor voids the proof entirely`() {
        // A max() over a list containing SEQ_UNKNOWN would still return a
        // real-looking anchor while one ghost's repaint is unprovable —
        // the whole selection must go null so the caller uses its fallback.
        assertNull(WindowChurnGate.selectGhostAnchor(listOf(42L, SEQ_UNKNOWN)))
    }

    @Test
    fun `overdue check never yields a non-positive delay`() {
        // Both deadlines already passed (a busy main thread delivered the
        // callback late): the reschedule must still post a real delay, not 0
        // or negative.
        assertEquals(
            1L,
            WindowChurnGate.nextCheckDelayMs(
                now = 10_000L, lastAddUptime = 1_000L, oldestSubmittedAtUptime = 1_000L
            )
        )
    }
}
