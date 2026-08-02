package com.playtranslate.audio.vad

/**
 * Pure segmentation + segment-pick logic over per-frame speech
 * probabilities — everything about the VAD snap that doesn't need the model,
 * kept separate so it unit-tests on the JVM.
 *
 * Segmentation is the reference Silero recipe: enter/exit hysteresis, gaps
 * shorter than [MIN_GAP_MS] merged (BGM ducking and breath pauses inside one
 * line), segments shorter than [MIN_SPEECH_MS] dropped (stingers, SFX
 * transients), then [PAD_MS] of lead-in/out so onsets aren't clipped.
 *
 * The pick is anchored: the anchor is the sentence's capture/display moment,
 * which TRAILS its voice line, so a segment overlapping the anchor wins
 * outright, an earlier segment costs its distance to the anchor, and a later
 * segment costs double — voice for on-screen text almost never starts after
 * the text was captured, but a small post-anchor reach keeps the
 * shuttered-mid-line case (live mode's MT latency) in play.
 */
internal object SpeechSnap {

    data class Segment(val startMs: Long, val endMs: Long)

    const val ENTER_THRESHOLD = 0.50f
    const val EXIT_THRESHOLD = 0.35f
    const val MIN_SPEECH_MS = 250L
    const val MIN_GAP_MS = 350L
    const val PAD_MS = 200L

    /** A picked line longer than this is end-aligned and cut to it — VAD
     *  merges back-to-back dialogue, and a card wants one line, not a scene. */
    const val MAX_LINE_MS = 15_000L

    /** Speech segments (ms, within `[0, totalMs]`) from per-frame
     *  probabilities, [frameMs] of audio per frame. */
    fun segments(
        probs: FloatArray,
        frameMs: Long,
        totalMs: Long,
    ): List<Segment> {
        // Hysteresis walk → raw runs.
        val raw = ArrayList<Segment>()
        var start = -1L
        for (i in probs.indices) {
            val t = i * frameMs
            if (start < 0) {
                if (probs[i] >= ENTER_THRESHOLD) start = t
            } else if (probs[i] < EXIT_THRESHOLD) {
                raw.add(Segment(start, t))
                start = -1
            }
        }
        if (start >= 0) raw.add(Segment(start, (probs.size * frameMs).coerceAtMost(totalMs)))

        // Merge across short gaps, then drop short survivors.
        val merged = ArrayList<Segment>()
        for (s in raw) {
            val last = merged.lastOrNull()
            if (last != null && s.startMs - last.endMs < MIN_GAP_MS) {
                merged[merged.size - 1] = Segment(last.startMs, s.endMs)
            } else {
                merged.add(s)
            }
        }
        merged.removeAll { it.endMs - it.startMs < MIN_SPEECH_MS }

        // Pad, clamp, re-merge overlaps the padding created.
        val padded = ArrayList<Segment>()
        for (s in merged) {
            val p = Segment(
                (s.startMs - PAD_MS).coerceAtLeast(0),
                (s.endMs + PAD_MS).coerceAtMost(totalMs),
            )
            val last = padded.lastOrNull()
            if (last != null && p.startMs <= last.endMs) {
                padded[padded.size - 1] = Segment(last.startMs, p.endMs)
            } else {
                padded.add(p)
            }
        }
        return padded
    }

    /** The segment the anchor most plausibly names, or null when [segments]
     *  is empty. Overlap costs 0; a segment ending before the anchor costs
     *  its distance; one starting after costs double. Ties go to the later
     *  segment (the most recent line). */
    fun pick(segments: List<Segment>, anchorMs: Long): Segment? =
        segments.minWithOrNull(
            compareBy<Segment> { s ->
                when {
                    anchorMs in s.startMs..s.endMs -> 0L
                    s.endMs < anchorMs -> anchorMs - s.endMs
                    else -> (s.startMs - anchorMs) * 2
                }
            }.thenByDescending { it.startMs },
        )

    /** [pick] bounded to a card-sized clip: over-long merges are end-aligned
     *  and cut to [MAX_LINE_MS] (the line the anchor names sits at the END
     *  of a run of merged dialogue, not its start). */
    fun snap(segments: List<Segment>, anchorMs: Long): Segment? {
        val s = pick(segments, anchorMs) ?: return null
        if (s.endMs - s.startMs <= MAX_LINE_MS) return s
        return Segment(s.endMs - MAX_LINE_MS, s.endMs)
    }

    /** Union of two segment lists, sorted, with overlapping AND touching
     *  runs coalesced. Touching matters: the background scan works in
     *  independent blocks whose pads clamp at block edges, so one line
     *  crossing a block boundary arrives as two segments meeting exactly
     *  there — the merged view must read as one line. */
    fun merge(a: List<Segment>, b: List<Segment>): List<Segment> {
        val all = (a + b).sortedBy { it.startMs }
        val out = ArrayList<Segment>(all.size)
        for (s in all) {
            val last = out.lastOrNull()
            if (last != null && s.startMs <= last.endMs) {
                if (s.endMs > last.endMs) out[out.size - 1] = Segment(last.startMs, s.endMs)
            } else {
                out.add(s)
            }
        }
        return out
    }
}
