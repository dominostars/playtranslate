package com.playtranslate.ocr.core

import com.playtranslate.language.TextOrientation
import java.text.Normalizer

/**
 * Predicted glyph-tight coverage of a line from its TEXT — the content-noise
 * corrector for the scale gate.
 *
 * The gate's raw statistic is line-box cross extent, which tracks the line's
 * content, not just its font size: a Latin row with a descender measures ~1.3×
 * one without, same font, and that variance overlaps the 1.3–1.5× band real
 * headings occupy, so no cap can separate them (2026-07-24 corpus analysis;
 * `scripts/char_norm_report.py` is the host-side twin of this table and the
 * numbers' source of record). This object predicts, per line, how much of the
 * font's design space the text can reach — "p" drops below the baseline, "b"
 * rises above x-height, CJK fills the em box — so the gate can divide the
 * measured extent by the predicted coverage and compare font-size estimates
 * instead of content-skewed boxes.
 *
 * ## Contract
 *
 * ONE global codepoint table — typographic facts, never per-language tuning.
 * Scripts not mapped yet (Arabic, Thai, Devanagari…) return null, as does any
 * line containing an unmapped character: null means "no prediction", and the
 * caller falls back to the raw statistic. The values are generic font-metric
 * priors MEASURED as medians over 9 system fonts (2026-07-25 sweep: x-height
 * 0.575, identical for Latin and Cyrillic; caps 0.755; ascenders 0.80;
 * descenders 0.22); residual font-to-font spread ~8%, against the ~31%
 * content error of the raw box. Stylized game fonts can still reassign
 * classes outright (a RU pixel font draws в ascender-tall; JA handwriting
 * fonts vary per glyph) — that residual is why consumers HALF-BLEND this
 * prediction and cap its authority with a raw ceiling; see
 * [LayoutAnalyzer.blendedSizeRatio].
 * That residual is why consumers must use predictions PERMISSIVELY ONLY —
 * accept when raw OR normalized clears the cap — so a font that defeats the
 * prior (pixel fonts box everything out) degrades to raw behavior instead of
 * splitting same-font text. Measured on the 79-seed corpus: rescues 7 of the
 * bare cap's 15 wrong blocks, at 2 should-split rescues, both RU, one of them
 * a same-font pair the raw gate split by accident.
 *
 * ## Known sharp edges (each cost a wrong result before it was handled)
 *
 * - NFD splits が into か + U+3099, and the kana voicing mark is INSIDE the em
 *   box. Treating it as an above-accent inflated every dakuten-bearing line
 *   and manufactured rescues for the JA speaker-name-tag enemy pairs. Only
 *   marks that render above (ccc 230) or below (220/202) extend coverage;
 *   kana voicing (8) and the Vietnamese horn (216) do not.
 * - An UNKNOWN combining mark makes the line ineligible rather than being
 *   skipped: Thai stacked vowels reach outside the base's span, so ignoring
 *   them would produce confident wrong predictions for a whole script.
 * - Lines of only thin/hanging ink (`ーーー` separators, `...`) have no
 *   baseline-anchored span to predict — ineligible, not zero.
 */
internal object CharClassCoverage {

    private const val X_HEIGHT = 0.575
    private const val ASCENDER = 0.80
    private const val CAP_TOP = 0.755
    private const val T_TOP = 0.73          // t and dotted i/j stop short of full ascenders
    private const val DESCENDER = 0.22
    private const val SMALL_TAIL = 0.125     // Cyrillic д/ц/щ feet, Q's tail
    private const val COMMA_BOT = 0.18
    private const val CJK_TOP = 0.88
    private const val CJK_BOT = 0.12
    private const val MARK_ABOVE = 0.22     // first above-mark; stacked marks add less
    private const val MARK_STACK = 0.12
    private const val MARK_BELOW = 0.18
    private const val SMALL_KANA_TOP = 0.62
    private const val SMALL_KANA_BOT = 0.08
    private const val SMALL_KANA_WIDTH = 0.80

    /** (top, bottom) in em units above/below the baseline; [core] = baseline-
     *  anchored ink that can carry a line's prediction (thin/hanging marks
     *  can't — a line needs at least one core glyph to be eligible). */
    private class Metrics(val top: Double, val bot: Double, val core: Boolean)

    private val LATIN_X = "acemnorsuvwxz".toSet()
    private val LATIN_ASC = "bdfhkl".toSet()
    private val LATIN_DESC = "gpqy".toSet()
    private val CYR_DESC_FULL = "ру".toSet()
    private val CYR_SMALL_TAIL = "дцщ".toSet()
    private val CYR_CAPS_TAIL = "ЦЩД".toSet()
    private val SMALL_KANA = "ぁぃぅぇぉっゃゅょゎゕゖァィゥェォッャュョヮヵヶ".toSet()
    private val CJK_LOW_PUNCT = "、。，．".toSet()
    private val CJK_BRACKETS = "「」『』【】〔〕（）".toSet()

    private val PUNCT: Map<Char, Metrics> = run {
        val m = mutableMapOf<Char, Metrics>()
        fun put(chars: String, top: Double, bot: Double, core: Boolean) {
            for (c in chars) m[c] = Metrics(top, bot, core)
        }
        put(".…", 0.12, 0.0, core = false)
        put("·", 0.55, 0.0, core = false)
        put(",", 0.12, COMMA_BOT, core = false)
        put(":", X_HEIGHT, 0.0, core = false)
        put(";", X_HEIGHT, COMMA_BOT, core = false)
        put("!?", CAP_TOP, 0.0, core = true)
        put("'’‘\"“”", CAP_TOP, 0.0, core = false)
        put("-–—", 0.35, 0.0, core = false)
        put("~", 0.45, 0.0, core = false)
        put("()[]{}", ASCENDER, COMMA_BOT, core = true)
        put("/\\", ASCENDER, SMALL_TAIL, core = true)
        put("&%#", CAP_TOP, 0.0, core = true)
        put("*", CAP_TOP, 0.0, core = false)
        put("$", ASCENDER, SMALL_TAIL, core = true)
        put("@", CAP_TOP, COMMA_BOT, core = true)
        put("+<>", 0.55, 0.0, core = false)
        put("=", 0.45, 0.0, core = false)
        put("ー・〜～", 0.55, 0.0, core = false)   // centered bars: no baseline span
        m
    }

    // Combining marks by where they render, ported from canonical combining
    // classes (above = 230, below = 220, attached-below = 202). Marks that
    // stay inside the base's box — kana voicing U+3099/A (class 8), the
    // Vietnamese horn U+031B (216) — are listed to be IGNORED; any mark in
    // none of the three sets makes the line ineligible (see class kdoc).
    private val MARKS_ABOVE = ((0x0300..0x0315) + (0x0340..0x0344)).map { it.toChar() }.toSet()
    private val MARKS_BELOW = ((0x0316..0x0319) + (0x031C..0x0333) + (0x0339..0x033C) +
        (0x0347..0x0349) + listOf(0x034D, 0x034E) + (0x0353..0x0356) +
        listOf(0x0359, 0x035A)).map { it.toChar() }.toSet()
    private val MARKS_INSIDE = setOf('\u031B', '\u3099', '\u309A')

    private fun isCjkFull(cp: Int): Boolean =
        cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xF900..0xFAFF ||
            cp in 0x20000..0x2FA1F || cp in 0xAC00..0xD7A3 || cp in 0x1100..0x11FF ||
            cp in 0x3130..0x318F

    private fun isKana(cp: Int): Boolean = cp in 0x3040..0x309F || cp in 0x30A0..0x30FF

    /** Metrics for a base (non-combining) codepoint, or null when unmapped.
     *  Order matters: PUNCT catches ー/・ before the kana range does, and the
     *  CJK bracket set catches （） before the fullwidth-ASCII fold. */
    private fun baseMetrics(cp: Int): Metrics? {
        if (cp > 0xFFFF) {
            return if (isCjkFull(cp)) Metrics(CJK_TOP, CJK_BOT, core = true) else null
        }
        val ch = cp.toChar()
        PUNCT[ch]?.let { return it }
        if (ch in CJK_LOW_PUNCT) return Metrics(0.25, 0.0, core = false)
        if (ch in CJK_BRACKETS) return Metrics(CJK_TOP, CJK_BOT, core = true)
        if (ch in SMALL_KANA) return Metrics(SMALL_KANA_TOP, SMALL_KANA_BOT, core = true)
        if (isKana(cp) || isCjkFull(cp)) return Metrics(CJK_TOP, CJK_BOT, core = true)
        if (cp in 0xFF01..0xFF5E) return baseMetrics(cp - 0xFF00 + 0x20)  // fullwidth ASCII
        if (cp in 0xFF66..0xFF9D) return Metrics(CJK_TOP, CJK_BOT, core = true)  // halfwidth kana
        if (ch.isDigit()) return Metrics(CAP_TOP, 0.0, core = true)
        if (ch in 'a'..'z') {
            return when {
                ch in LATIN_X -> Metrics(X_HEIGHT, 0.0, core = true)
                ch in LATIN_ASC -> Metrics(ASCENDER, 0.0, core = true)
                ch in LATIN_DESC -> Metrics(X_HEIGHT, DESCENDER, core = true)
                ch == 't' || ch == 'i' -> Metrics(T_TOP, 0.0, core = true)
                ch == 'j' -> Metrics(T_TOP, DESCENDER, core = true)
                else -> null
            }
        }
        if (ch in 'A'..'Z') {
            return Metrics(CAP_TOP, if (ch == 'Q') SMALL_TAIL else 0.0, core = true)
        }
        // Cyrillic. й/ё arrive DECOMPOSED (NFD strips them to и/е + mark), so
        // only the composed-form-stable letters need rows here.
        if (ch in 'а'..'я') {
            return when {
                ch == 'б' -> Metrics(ASCENDER, 0.0, core = true)
                ch == 'ф' -> Metrics(ASCENDER, DESCENDER, core = true)
                ch in CYR_DESC_FULL -> Metrics(X_HEIGHT, DESCENDER, core = true)
                ch in CYR_SMALL_TAIL -> Metrics(X_HEIGHT, SMALL_TAIL, core = true)
                else -> Metrics(X_HEIGHT, 0.0, core = true)
            }
        }
        if (ch in 'А'..'Я') {
            return Metrics(CAP_TOP, if (ch in CYR_CAPS_TAIL) SMALL_TAIL else 0.0, core = true)
        }
        if (ch == 'đ') return Metrics(ASCENDER, 0.0, core = true)     // Vietnamese d-stroke
        if (ch == 'Đ') return Metrics(CAP_TOP, 0.0, core = true)
        return null
    }

    private fun isMark(ch: Char): Boolean = when (Character.getType(ch)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    private fun isSpace(ch: Char): Boolean =
        Character.isWhitespace(ch) || Character.isSpaceChar(ch)

    /**
     * Predicted vertical coverage span (em units, ascent + descent the text
     * can reach) for a HORIZONTAL line, or null when the line is ineligible:
     * any unmapped character or unknown mark, or no baseline-anchored core
     * glyph. Combining marks above/below (NFD) extend the preceding base, so
     * accented Latin and Vietnamese stacks price in.
     */
    fun lineCoverage(text: String): Double? {
        val nfd = Normalizer.normalize(text, Normalizer.Form.NFD)
        var top = 0.0
        var bot = 0.0
        var nCore = 0
        // Pending base char, mutable under its trailing marks.
        var curTop = 0.0
        var curBot = 0.0
        var curCore = false
        var haveBase = false
        var aboveMarks = 0

        fun flush() {
            if (!haveBase) return
            top = maxOf(top, curTop)
            bot = maxOf(bot, curBot)
            if (curCore) nCore++
        }

        var i = 0
        while (i < nfd.length) {
            val cp = nfd.codePointAt(i)
            i += Character.charCount(cp)
            if (cp <= 0xFFFF) {
                val ch = cp.toChar()
                if (isSpace(ch)) continue
                if (isMark(ch)) {
                    if (!haveBase) return null
                    when (ch) {
                        in MARKS_ABOVE -> {
                            curTop += if (aboveMarks == 0) MARK_ABOVE else MARK_STACK
                            aboveMarks++
                        }
                        in MARKS_BELOW -> curBot = maxOf(curBot, MARK_BELOW)
                        in MARKS_INSIDE -> {}
                        else -> return null   // unknown mark: no confident prediction
                    }
                    continue
                }
            }
            flush()
            val m = baseMetrics(cp) ?: return null
            curTop = m.top
            curBot = m.bot
            curCore = m.core
            haveBase = true
            aboveMarks = 0
        }
        flush()
        if (nCore == 0) return null
        return top + bot
    }

    /**
     * Predicted ink WIDTH (em units) for a VERTICAL column — the cross axis
     * there is width, where the height-class model does not apply. Full-width
     * CJK fills the em, small kana are narrower, centered thin marks carry no
     * width claim, and any non-CJK content (rotated/upright Latin) is not
     * modelled → null.
     */
    fun columnCoverage(text: String): Double? {
        var width = 0.0
        var nCore = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp > 0xFFFF) {
                // Supplementary plane: only Ext-B+ ideographs are modelled.
                if (isCjkFull(cp)) { width = maxOf(width, 1.0); nCore++ } else return null
                continue
            }
            val ch = cp.toChar()
            if (isSpace(ch)) continue
            if (ch in PUNCT || ch in CJK_LOW_PUNCT) continue   // no width claim
            when {
                ch in SMALL_KANA -> { width = maxOf(width, SMALL_KANA_WIDTH); nCore++ }
                ch in CJK_BRACKETS || isKana(cp) || isCjkFull(cp) -> { width = maxOf(width, 1.0); nCore++ }
                else -> return null
            }
        }
        return if (nCore > 0) width else null
    }

    /** Orientation-routed entry point for [LayoutAnalyzer.groupRegions]. */
    fun coverage(text: String, orientation: TextOrientation): Double? =
        if (orientation == TextOrientation.VERTICAL) columnCoverage(text) else lineCoverage(text)
}
