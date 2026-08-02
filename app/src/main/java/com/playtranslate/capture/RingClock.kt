package com.playtranslate.capture

/**
 * Wall-clock addressing for the game-audio ring. The ring's timeline is
 * SPLICED, not wall-clock (see [SilenceGate]): silence collapse, reader
 * pauses, and stop/start cycles all remove wall time that never entered the
 * ring, so "epoch ms → ring frame" needs the splice points recorded as they
 * happen. This keeps a sparse list of (frame, wallMs) anchors — one per
 * write-resumption point — and interpolates by frame count in between (the
 * audio clock; wall drift against it over a 180 s ring is negligible).
 *
 * Anchors are appended when:
 *  - the first chunk of a reader run is written ([markGap] at loop start),
 *  - writing resumes after the [SilenceGate] dropped frames ([markGap]),
 *  - the projected wall time of a chunk disagrees with the measured one by
 *    more than [DRIFT_REANCHOR_MS] — a backstop for gap generators nobody
 *    enumerated (scheduler stalls, AudioRecord underruns), so mapping error
 *    is bounded by the threshold instead of accumulating.
 *
 * NOT thread-safe: [beforeWrite] and [frameFor] must run under the
 * recorder's ring lock ([markGap] is reader-thread-only state).
 * Wall times are epoch ms ([System.currentTimeMillis]) to match History's
 * `at_ms` — the one clock domain launch anchors arrive in. A user clock
 * jump inside the ring's ~3-minute horizon mis-maps; accepted.
 */
internal class RingClock(
    private val sampleRate: Int,
    private val capacityFrames: Int,
) {
    private class Anchor(val frame: Long, val wallMs: Long)

    private val anchors = ArrayDeque<Anchor>()

    /** Reader-thread flag: the next written chunk starts a new segment. */
    private var needAnchor = true

    /** The ring reset (fresh allocation) — all frame positions restart at 0. */
    fun reset() {
        anchors.clear()
        needAnchor = true
    }

    /** Wall time is about to pass the ring by without frames (silence drop,
     *  reader pause/stop): the next written chunk must re-anchor. */
    fun markGap() {
        needAnchor = true
    }

    /**
     * Called (under the ring lock) before a chunk of [chunkFrames] admitted
     * frames is written at frame position [framesWritten], where
     * [chunkEndWallMs] is the wall clock at the chunk's read return (≈ its
     * last frame).
     */
    fun beforeWrite(framesWritten: Long, chunkFrames: Int, chunkEndWallMs: Long) {
        val chunkStartWallMs = chunkEndWallMs - chunkFrames * 1000L / sampleRate
        if (!needAnchor) {
            val projected = anchors.lastOrNull()?.let { a ->
                a.wallMs + (framesWritten - a.frame) * 1000L / sampleRate
            }
            if (projected == null ||
                kotlin.math.abs(chunkStartWallMs - projected) > DRIFT_REANCHOR_MS
            ) {
                needAnchor = true
            }
        }
        if (needAnchor) {
            anchors.addLast(Anchor(framesWritten, chunkStartWallMs))
            needAnchor = false
        }
        // Prune anchors whose whole segment has been overwritten: the head is
        // droppable once its SUCCESSOR starts at/before the ring window.
        val windowStart = framesWritten + chunkFrames - capacityFrames
        while (anchors.size >= 2 && anchors[1].frame <= windowStart) {
            anchors.removeFirst()
        }
    }

    /**
     * Ring frame position for [wallMs], or null when it precedes all tracked
     * audio (older than the ring, or before recording started). A wall time
     * inside a spliced gap clamps to the gap's seam; a wall time past the
     * last write projects beyond it and the caller clamps to the data end.
     */
    fun frameFor(wallMs: Long): Long? {
        var prev: Anchor? = null
        var next: Anchor? = null
        for (a in anchors) {
            if (a.wallMs <= wallMs) prev = a else { next = a; break }
        }
        if (prev == null) return null
        var frame = prev.frame + (wallMs - prev.wallMs) * sampleRate / 1000
        if (next != null && frame > next.frame) frame = next.frame
        return frame
    }

    private companion object {
        /** Projected-vs-measured wall disagreement that forces a re-anchor.
         *  Big enough that read-return jitter and post-stall buffer drains
         *  (AudioRecord holds 1 s) don't churn anchors on continuous audio;
         *  small next to the several-second tolerance trim seeding needs. */
        const val DRIFT_REANCHOR_MS = 1_000L
    }
}
