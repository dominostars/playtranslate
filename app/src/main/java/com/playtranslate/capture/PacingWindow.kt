package com.playtranslate.capture

import java.util.concurrent.ConcurrentHashMap

/**
 * Self-expiring per-display fast-poll window for the push-model capture
 * loops ([LiveCaptureSource.pokeFastPoll]). A mode observing a typewriter
 * reveal pokes the window; while it is open the loop polls at its floor
 * interval instead of the user's, so the confirming read after a reveal
 * lands one floor interval away instead of a full user interval later.
 *
 * This is the push-loop analog of the pull-model tiers' typewriter pacing
 * (`PinholeOverlayMode.typewriterPacing`, `ReconcilerLiveMode.pacing`):
 * those engines RETURN each cycle's next delay, a shape a push loop cannot
 * express — here the loop instead consults the window when it re-reads its
 * poll interval each iteration. TTL-based on purpose: there is no
 * "un-poke" call to forget — pacing decays back to the user interval by
 * itself when the reveal stops refreshing the window.
 */
internal class PacingWindow {

    private val fastUntilMs = ConcurrentHashMap<Int, Long>()

    /** Open (or extend) the fast window for [displayId]. Never shrinks an
     *  already-longer window. */
    fun poke(displayId: Int, nowMs: Long, windowMs: Long) {
        fastUntilMs.merge(displayId, nowMs + windowMs) { a, b -> maxOf(a, b) }
    }

    /** True while [displayId]'s window is open — the loop polls at floor. */
    fun floorActive(displayId: Int, nowMs: Long): Boolean =
        nowMs < (fastUntilMs[displayId] ?: 0L)
}
