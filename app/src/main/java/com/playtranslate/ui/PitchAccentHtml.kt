package com.playtranslate.ui

import com.playtranslate.dictionary.pitch.Mora

/**
 * Renders a kana reading with its pitch-accent contour as pure HTML/CSS — the
 * card-side counterpart to [PitchAccentSpan] / [drawPitchContour], which draw
 * the same NHK line notation on an Android Canvas. Used by PlayTranslate's own
 * (legacy) Anki card backs, whose `Back` field is a plain-HTML blob.
 *
 * No Android dependencies (only [htmlEscape] and the pure [Mora] helpers) so
 * the exact markup is unit-testable.
 */
internal object PitchAccentHtml {

    /**
     * CSS for [pitchAccentHtml]'s markup — appended to the legacy card backs'
     * own `<style>` blocks. Borders use `currentColor` so the diagram inherits
     * the surrounding (muted) reading color; `.pa` reserves top padding so the
     * overline isn't clipped.
     */
    const val PITCH_CSS =
        ".pa{display:inline-block;padding-top:0.45em;}" +
        ".pa-m{display:inline-block;}" +
        ".pa-h{border-top:0.1em solid currentColor;}" +
        ".pa-d{border-right:0.1em solid currentColor;}" +
        ".pa-pos{font-size:0.75em;opacity:0.7;}"

    /**
     * The reading drawn with the PRIMARY (first) pitch variant's contour, plus
     * a small `[0]·[2]` suffix listing every variant (mirrors
     * [buildPitchAnnotatedReading]). Returns `""` when [reading] or [pitch] is
     * empty so callers fall back to the plain reading.
     *
     * Mirrors [drawPitchContour]: an overline (`pa-h`) on each HIGH mora, and a
     * drop tick (`pa-d`, a right border) on a HIGH mora that falls — i.e. the
     * next mora is LOW, or it is word-final and the accent isn't heiban
     * (`ghostHigh`). Rises are unmarked (NHK convention).
     *
     * Mora spans are emitted with NO intervening whitespace: inline-block spans
     * separated by whitespace render a ~4px gap that breaks the continuous
     * overline across consecutive HIGH morae.
     */
    fun pitchAccentHtml(reading: String, pitch: List<Int>): String {
        if (reading.isEmpty() || pitch.isEmpty()) return ""
        val morae = Mora.segment(reading)
        if (morae.isEmpty()) return ""
        val contour = Mora.contour(pitch.first(), morae.size)
        val high = contour.high
        val n = morae.size

        val sb = StringBuilder()
        sb.append("<span class=\"pa\">")
        for (k in 0 until n) {
            val isHigh = high[k]
            // The fall lands on mora k when it's HIGH and either the next mora
            // is LOW (mid-word) or k is word-final and the accent isn't heiban
            // (odaka — the drop is on the particle). Matches drawPitchContour.
            val drops = isHigh && (if (k + 1 < n) !high[k + 1] else !contour.ghostHigh)
            sb.append("<span class=\"pa-m")
            if (isHigh) sb.append(" pa-h")
            if (drops) sb.append(" pa-d")
            sb.append("\">")
            sb.append(htmlEscape(reading.substring(morae[k].start, morae[k].end)))
            sb.append("</span>")
        }
        sb.append("</span>")
        // Variant suffix, e.g. " [0]·[2]" — outside the gap-free mora run.
        sb.append("<span class=\"pa-pos\"> ")
        sb.append(htmlEscape(pitch.joinToString("·") { "[$it]" }))
        sb.append("</span>")
        return sb.toString()
    }
}
