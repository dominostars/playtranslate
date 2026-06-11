package com.playtranslate.dictionary.pitch

/** One mora's character range within a kana reading ([start], [end]) — end exclusive. */
data class MoraSpan(val start: Int, val end: Int)

/**
 * Per-mora pitch levels for one accent variant. [high] has one entry per
 * mora; [ghostHigh] is the level of the following particle. Renderers use
 * it to decide whether a word-final HIGH run ends in a drop tick (odaka —
 * the fall lands on the particle) or bare (heiban); that final hook is
 * what keeps the two visually distinct in line notation.
 */
data class PitchContour(val high: List<Boolean>, val ghostHigh: Boolean)

/**
 * Mora segmentation + standard-Japanese pitch-contour expansion. Pure
 * functions — renderers ([com.playtranslate.ui.PitchAccentSpan],
 * FuriganaSpan) consume these; no Android types.
 */
object Mora {

    /** Small kana that merge into the PRECEDING mora (きょ is one mora).
     *  っ/ッ, ん/ン, and ー are deliberately absent — each is its own mora. */
    private const val SMALL_KANA = "ぁぃぅぇぉゃゅょゎァィゥェォャュョヮ"

    /** Splits [reading] into morae: each base character absorbs any small
     *  kana that follow it. Degenerate input (leading small kana) yields a
     *  mora starting with the small kana rather than throwing. */
    fun segment(reading: String): List<MoraSpan> {
        val spans = mutableListOf<MoraSpan>()
        var i = 0
        while (i < reading.length) {
            var end = i + 1
            while (end < reading.length && reading[end] in SMALL_KANA) end++
            spans += MoraSpan(i, end)
            i = end
        }
        return spans
    }

    /**
     * Expands a downstep position into per-mora levels (standard Japanese):
     *  - 0 (heiban): L H H … and the particle stays HIGH
     *  - 1 (atamadaka): H L L … particle low
     *  - n ≥ 2: L H … H through mora n, then low; particle low
     *    (n == moraCount is odaka — the drop lands ON the particle)
     * [downstep] outside 0..[moraCount] is clamped — callers log if they care.
     */
    fun contour(downstep: Int, moraCount: Int): PitchContour {
        if (moraCount <= 0) return PitchContour(emptyList(), ghostHigh = false)
        val n = downstep.coerceIn(0, moraCount)
        return if (n == 0) {
            PitchContour(List(moraCount) { it > 0 }, ghostHigh = true)
        } else {
            PitchContour(
                List(moraCount) { idx ->
                    val mora = idx + 1
                    if (n == 1) mora == 1 else mora in 2..n
                },
                ghostHigh = false,
            )
        }
    }
}
