package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * PASSIVE telemetry for the parked quiet-pixel release accelerator — no
 * behavior, debug-gated at the call site. Answers the two questions that
 * decide whether the accelerator gets built, from one evening of normal
 * play:
 *
 *  1. **Fire-rate**: how long is a held reveal's region pixel-stable
 *     before its release? Logged as `qp END … streak=N quietMs=M` when a
 *     hold ends — long streaks mean the release-without-OCR variant would
 *     have fired and saved the agreement read.
 *  2. **Apron correctness**: does text ever GROW while the padded read
 *     union was pixel-quiet? Logged as `qp APRON-MISS` — any occurrence
 *     means the flow apron under-covers the glyph frontier and a real
 *     accelerator would have released a partial. Zero misses over real
 *     play is the safety case.
 *
 * Sampling is CYCLE-granular (rides the existing full-look cycles at the
 * hold-window floor pace, ~0.5s) rather than per-latched-frame like the
 * real accelerator would be — coarser, but sufficient for both questions
 * and free of frames-thread plumbing. Signatures are strided row sums
 * (scanline-lab precedent: MP frames are byte-deterministic on Thor, so
 * zero tolerance). Comparison always runs over the PREVIOUS cycle's
 * padded rect — that is what makes the apron-miss detector sound: growth
 * this batch + zero change inside the OLD sampled area = the new glyphs
 * landed outside the apron.
 */
class TypewriterQuietProbe {

    private class Track(
        var rect: Rect,
        var sums: IntArray,
        var quietStreak: Int,
        var lastChangeMs: Long,
        /** At least one valid same-rect comparison happened — the first
         *  sample after a rect change proves nothing. */
        var compared: Boolean,
    )

    private val tracks = HashMap<Int, Track>()

    /** Test seams. */
    var apronMisses = 0
        private set
    var lastEndStreak = -1
        private set

    fun streakOf(id: Int): Int? = tracks[id]?.quietStreak

    /** Sample [frame] against every open hold. Call once per full-look
     *  cycle, after the gate batch, debug only. */
    fun sample(frame: Bitmap, holds: List<TypewriterGate.QuietHoldProbe>, nowMs: Long) {
        val seen = HashSet<Int>()
        for (h in holds) {
            seen.add(h.id)
            val clipped = Rect(h.paddedBounds)
            if (!clipped.intersect(0, 0, frame.width, frame.height)) continue
            val t = tracks[h.id]
            if (t == null) {
                // A hold that opened and released within one batch spans no
                // gap — nothing to measure.
                if (!h.released) {
                    tracks[h.id] = Track(clipped, rowSums(frame, clipped), 0, nowMs, compared = false)
                }
                continue
            }
            // Compare over the PREVIOUS rect (apron-miss soundness).
            val cur = rowSums(frame, t.rect)
            val changedRows = diffRows(t.sums, cur)
            if (changedRows == 0) {
                t.quietStreak++
                if (h.grew && t.compared) {
                    apronMisses++
                    DetectionLog.log(
                        "qp APRON-MISS id=${h.id} rect=${t.rect.toShortString()} " +
                            "streak=${t.quietStreak}"
                    )
                }
            } else {
                t.quietStreak = 0
                t.lastChangeMs = nowMs
            }
            t.compared = true
            if (h.released) {
                // The releasing cycle's own comparison IS the datum:
                // agreement releases fire at the first settled cycle, so
                // without this the streak was structurally always 0.
                // streak ≥ 1 here = the region's pixels were identical
                // across the final gap's endpoints — the accelerator
                // would have fired.
                lastEndStreak = t.quietStreak
                DetectionLog.log(
                    "qp END id=${h.id} streak=${t.quietStreak} quietMs=${nowMs - t.lastChangeMs}"
                )
                tracks.remove(h.id)
                continue
            }
            // Store the fresh signature over the CURRENT padded rect.
            if (t.rect != clipped) {
                t.rect = clipped
                t.sums = rowSums(frame, clipped)
                t.compared = false
            } else {
                t.sums = cur
            }
        }
        // Holds that ended (released or swept) — the fire-rate datum.
        val it = tracks.entries.iterator()
        while (it.hasNext()) {
            val (id, t) = it.next()
            if (id in seen) continue
            DetectionLog.log(
                "qp END id=$id streak=${t.quietStreak} quietMs=${nowMs - t.lastChangeMs}"
            )
            it.remove()
        }
    }

    fun clear() = tracks.clear()

    /** Strided row sums: one Int per sampled row (rows stride 2, columns
     *  stride 8, RGB summed — max ~765 × width/8, no overflow at 1080p). */
    private fun rowSums(frame: Bitmap, r: Rect): IntArray {
        val rows = ((r.height() + 1) / 2).coerceAtLeast(1)
        val out = IntArray(rows)
        var i = 0
        var y = r.top
        while (y < r.bottom) {
            var s = 0
            var x = r.left
            while (x < r.right) {
                val p = frame.getPixel(x, y)
                s += (p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)
                x += 8
            }
            if (i < out.size) out[i] = s
            i++
            y += 2
        }
        return out
    }

    private fun diffRows(a: IntArray, b: IntArray): Int {
        if (a.size != b.size) return maxOf(a.size, b.size)
        var n = 0
        for (i in a.indices) if (a[i] != b[i]) n++
        return n
    }
}
