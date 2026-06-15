package com.playtranslate.ocr.core

import android.icu.text.Bidi

/**
 * Visual→logical reordering for right-to-left source scripts (Arabic).
 *
 * PaddleOCR's CTC recognizer emits glyphs in **visual** order — left-to-right
 * along the recognition strip — because CTC alignment is monotonic along that
 * axis. For an RTL script that is reversed-logical: the word كتاب comes out of
 * the recognizer as باتك (verified on-device). Everything downstream — line
 * assembly, dictionary lookup, translation, overlay rendering — assumes
 * logical/storage order, so we convert exactly once here, right after
 * recognition, before any consumer sees the text.
 *
 * **Atomic OCR contract** (honored by [toLogical]): after this, every line
 * string is logical-order Unicode, each [CharBox.charOffset] indexes the i-th
 * *logical* UTF-16 unit, and each [CharBox] keeps its true on-screen pixel box
 * (the glyph doesn't move on screen — only its index in the string changes).
 *
 * Uses ICU **inverse** Bidi (input is visual, output is logical) rather than a
 * naive whole-string reverse, so embedded LTR runs — Western digits, Latin
 * tokens — keep their own order instead of being flipped (e.g. "السعر 100",
 * not "100 رعسلا"). The exact handling of mixed runs is pinned by
 * `RtlReorderTest`.
 */
internal object RtlReorder {

    /** Reorder a recognized region's text + char boxes from visual to logical order. */
    fun toLogical(region: RecognizedRegion): RecognizedRegion {
        if (region.text.isBlank()) return region
        return region.copy(
            text = reordered(region.text),
            lines = region.lines.map { line ->
                if (line.text.isEmpty()) return@map line
                val map = visualToLogical(line.text)
                line.copy(
                    text = applyMap(line.text, map),
                    // Keep the char LIST in its visual order but rebase each offset to
                    // the glyph's logical index; consumers index by charOffset, not by
                    // list position. The pixel box is unchanged.
                    chars = line.chars.map { c ->
                        if (c.charOffset in map.indices) c.copy(charOffset = map[c.charOffset]) else c
                    },
                )
            },
        )
    }

    /** `map[visualIndex] = logicalIndex` for [visual] (treated as visually ordered). */
    private fun visualToLogical(visual: String): IntArray {
        val bidi = Bidi().apply {
            isInverse = true
            // Per-region base auto-detected from the first strong directional char,
            // LTR fallback: an Arabic word resolves RTL and reverses to logical; a
            // pure number / Latin token resolves LTR and is left untouched (no
            // "100" → "001"). The recognizer emits per-word regions for word-spaced
            // scripts, so each region is a single word/number/token — paragraph-level
            // RTL ordering across regions is LineAssembler's job, not this.
            setPara(visual, Bidi.LEVEL_DEFAULT_LTR, null)
        }
        return IntArray(visual.length) { v -> bidi.getLogicalIndex(v) }
    }

    private fun applyMap(visual: String, map: IntArray): String {
        val out = CharArray(visual.length)
        for (v in visual.indices) out[map[v]] = visual[v]
        return String(out)
    }

    private fun reordered(visual: String): String = applyMap(visual, visualToLogical(visual))
}
