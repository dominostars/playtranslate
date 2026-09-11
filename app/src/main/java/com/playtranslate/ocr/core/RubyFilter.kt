package com.playtranslate.ocr.core

import android.graphics.Rect
import com.playtranslate.language.TextOrientation
import kotlin.math.abs

/**
 * Furigana (ruby) demotion: drops the small kana readings printed beside kanji
 * from the region list BEFORE grouping, so they never become their own group,
 * never reach the translator, and never get an overlay.
 *
 * Debug-flag only for now (`Prefs.debugFilterFurigana`, Japanese source only,
 * gated by the caller). Off, the pipeline is byte-identical to before.
 *
 * ## The rule, from the typesetting facts
 *
 * Furigana is a kana reading, set at half the base size, attached to the
 * kanji it reads: above a horizontal line, to the right of a vertical column,
 * with no leading between them. Each clause is one term, and the constants
 * are nominal values with slack rather than fitted numbers:
 *
 *  1. **Reading** ([isRubyText]): at least one syllabic kana, and at most
 *     [MAX_HAN_IN_READING] Han, outnumbered by the kana. A reading never
 *     contains a kanji, but a recognizer reads one into it now and then
 *     (`ん` as `人`, `ー` as `一`, `ま` as `中`); that is a single misread, so
 *     one kanji is tolerated and two are not (`何を買おうか？` is a sentence,
 *     not a reading). `々` counts as Han. Latin, digits and punctuation are
 *     tolerated (misreads inside a reading); a read with no kana is never
 *     ruby.
 *  2. **Attached**: the candidate's nearest line on its ruby side — the
 *     nearest line BELOW it if that line is horizontal, the nearest column to
 *     its LEFT if vertical (columns run right to left, ruby precedes its
 *     column) — overlapping it along the reading axis by at least
 *     [OVERLAP_MIN] of the candidate's own extent and within [REACH_EM] of
 *     that line's em, must contain Han ([hasHan]). Nearest, not any: a
 *     kanji line further away with a kana line in between is not the
 *     candidate's base. [REACH_EM] is a sanity bound on the search, not a
 *     discriminator — the size tests decide, and a corpus sweep from half
 *     an em to a full one added nothing but junk reads; [GAP_MIN_EM] admits
 *     the overlap of padded detector boxes.
 *  3. **Half-size**: glyph size is measured from the glyphs, not from the
 *     OCR line box, wherever the engine lets us. Two glyph measurements:
 *      - **character pitch**, the start-to-start distance from one character
 *        box to the next along the line, which for square CJK glyphs is the
 *        glyph size ([charPitch]; the median when there are several; extent
 *        over count when a line has no boxes). Needs [PITCH_MIN_CHARS]
 *        characters on BOTH lines (one character has no "next"; a lone kanji
 *        base's whole width is not a pitch). The base's pitch must be at
 *        least [PITCH_RATIO_MIN] times the candidate's (nominal 2.0).
 *      - **line height** (width for vertical): the base must be at least
 *        [SIZE_RATIO_MIN] times taller (nominal 2.0).
 *     How the two combine depends on whether the engine measures glyphs,
 *     read off the base line's char tier ([GlyphScale.hasMeasuredCharTier]):
 *      - **Glyph-measuring engines** (ML Kit, Meiki): line boxes hug the
 *        glyphs and character boxes sit where the glyphs are, so both
 *        measurements are trustworthy and BOTH must say half-size (height
 *        alone when the read has one character). The spacing test is
 *        applied per RUN: a read whose characters are separated by a hole
 *        of at least one base character (て over 手 and い over 入, with に
 *        between them, read as one region) is several readings, so it is
 *        cut at every such hole ([splitRuns]) and every run must pass — a
 *        run of two or more on spacing, a single character by the height
 *        already required — and at least one run of two or more must exist
 *        and pass. That last clause is what keeps letter-spaced titles
 *        (ふ し ぎ な ほ し) out: they cut into single characters only, and
 *        single characters carry no spacing evidence.
 *      - **PaddleOCR**: both measurements are biased toward refusing and
 *        neither toward accepting, so EITHER suffices. Its detector boxes
 *        inflate small text by a roughly constant amount (15 px ruby glyphs
 *        come back as 20 to 26 px boxes) while leaving large text alone, so
 *        true ruby's height ratio lands at 1.2 to 1.9. And its character
 *        positions are sequence positions, not glyph positions: a read that
 *        merges two ruby runs across a base character between them
 *        (なかはい, なか over 中 and はい over 入) comes back with its four
 *        characters spread evenly over the box, the hole invisible, so its
 *        pitch reads like body text (Thor 2026-09-10: height 1.67, pitch
 *        0.97). A read that fails both is refused: single-character reads in
 *        padded boxes and hole reads whose padded height also falls short.
 *
 * Rotated regions and whole-region (manga-ocr) regions are neither candidate
 * nor neighbour. The rule is local and existential: no page-level statistic,
 * nothing to fail open on.
 *
 * ## Why two size axes
 *
 * The cross-axis line box carries content noise: on a glyph-tight engine a
 * kana-only column measures narrower than a kanji column of the same font,
 * by up to 1.6 times on ML Kit, inside ruby's range. Character pitch is
 * immune to that noise because full-width advance does not depend on glyph
 * shape: a kana body column advances one em per character, ruby half an em.
 * An earlier version of this filter compensated for the noisy axis with a
 * tight gap cap instead, and leaked on the first real game screen, whose
 * ruby sat further from its base than any corpus manga's.
 *
 * ## Why pitch is measured from character positions, not the count
 *
 * Extent over count assumes the characters tile the extent. A recognizer
 * that merges two ruby runs across the un-annotated base characters between
 * them (いっしょ over 一緒 and か over 買, read as one region) breaks that: the
 * hole inflates the extent, and whether a filler glyph gets emitted for the
 * hole moves the count, so the same box on an unchanged screen read 1.63 on
 * one pass and 1.36 on the next (Thor, 2026-09-09) and the reading flickered
 * in and out. Where the engine places its characters at the glyphs — ML
 * Kit's symbol boxes, Meiki's per-character detections — the median
 * start-to-start distance is the ruby size whatever the holes do, as long as
 * ruby-sized distances are at least half of them. Paddle's CTC cells do NOT
 * place characters at the glyphs (see above), and even-tiled tiers
 * (manga-ocr) give the fallback's number, so on those engines this measure
 * equals the count. On the base side the median also stops half-width
 * digits (`18` in 未成年(18歳未満) from deflating the base pitch.
 *
 * ## Census (source of record: scripts/ruby_census.py)
 *
 * Corpus: ocr-grouping/runs/results-1786212247819.jsonl, 57 Japanese seeds,
 * four engines, the `flowgraph` column, 2022 regions: 131 demotions, every
 * one a reading of a kanji in its base line, no body line demoted. Against
 * the earlier gap-capped rule the general form gains three misread readings
 * containing kanji no list anticipated (`小らおり`, `なかー中`, `なか上`) and
 * loses five merged-fragment reads. One Thor capture (2026-09-09, Meiki, a
 * mobile game's parental-notice screen): 26 demoted, 8 body lines kept,
 * including the two reads the capped rule leaked (`いっしょいか` at a 0.30 em
 * gap; `ほうていたいりに人`).
 *
 * The exposure that remains, stated so the device pass looks for it: a one-
 * or two-character kana body line adjacent to a larger kanji line on the
 * ruby side, which a glyph-tight engine measures at 1.5 times narrower. No
 * second axis exists for a two-glyph read. Also not handled: ruby beyond
 * half an em from its base, left-side vertical ruby (dual readings), ruby
 * printed below a horizontal line, slanted ruby, and Chinese zhuyin/pinyin
 * ruby (the reading test is kana-based). Ruby the recognizer merges INTO its
 * base line's text is a recognizer problem, not a box problem, and stays.
 * The corpus is manga-heavy and under-samples menu screens; the debug row
 * plus a device pass on real game menus is the validation for that
 * population, and the DEBUG `RubyFilter` log line in
 * [com.playtranslate.ocr.OcrPipeline] makes every demotion re-checkable.
 *
 * ## Decisions
 *
 * Demoted regions are REMOVED from the grouping input rather than carried as
 * a flagged group: marking would have to thread through classification, the
 * overlay, and tombstones for no v1 benefit. They are returned separately
 * ([Result.demoted]) for the debug-box overlay and the log only. The two
 * existing scale gates that stop ruby from MERGING (FlowGraph's
 * `linkScaleCap`, `LayoutAnalyzer.interposingLine`) are untouched; with the
 * flag on they simply never see ruby.
 */
object RubyFilter {

    /** Cross-axis: base extent over candidate extent. Ruby is set at half
     *  size (nominal 2.0); 1.5 leaves 25% for box noise. */
    const val SIZE_RATIO_MIN = 1.5f

    /** Reading-axis: base character pitch over candidate character pitch.
     *  Nominal 2.0; 1.4 leaves room for misread character counts. */
    const val PITCH_RATIO_MIN = 1.4f

    /** A read needs a second character to have a start-to-start distance. */
    const val PITCH_MIN_CHARS = 2

    /** Neighbour search: how far below (or left of) the candidate a line may
     *  start, in that line's ems, and still be its neighbour. A bound on the
     *  search, not a discriminator: the size tests decide. One line height;
     *  further than that is the previous line's territory, and games do set
     *  their ruby off the word (Thor: up to 0.3 em on one screen). */
    const val REACH_EM = 1.0f

    /** Neighbour search: how far a line may OVERLAP the candidate, in its
     *  ems. Padded detector boxes (PaddleOCR) overlap their neighbours. */
    const val GAP_MIN_EM = -0.5f

    /** Reading-axis overlap the candidate needs with a neighbour, as a
     *  fraction of the candidate's own reading extent. Ruby sits within its
     *  word. */
    const val OVERLAP_MIN = 0.5f

    /** A reading may carry this many Han: one misread kana. Two is a
     *  kanji-bearing sentence. */
    const val MAX_HAN_IN_READING = 1

    /** One demoted region with the base that claimed it and the measured
     *  terms, for the debug overlay and the census log. */
    data class Demoted(
        val region: RecognizedRegion,
        val base: RecognizedRegion,
        /** base cross extent / candidate cross extent, in the base's orientation. */
        val sizeRatio: Float,
        /** Gap between the two along the cross axis, in base ems (negative = overlap). */
        val gapEm: Float,
        /** base character pitch / candidate character pitch along the reading
         *  axis, from character positions ([charPitch]). Always measured; only
         *  DEMANDED for reads of [PITCH_MIN_CHARS]+. */
        val pitchRatio: Float,
        /** The same ratio from reading extent over character count — the
         *  pre-position measure, carried so the log shows what the switch
         *  changed. Equal to [pitchRatio] when neither line has a char tier. */
        val pitchRatioByCount: Float,
        /** Whether the engine's line boxes hug the glyphs (read off the base
         *  line's char tier), i.e. whether [sizeRatio] was consulted. */
        val glyphTight: Boolean,
        /** How many runs the read cut into ([splitRuns]); 1 when unsplit. */
        val runs: Int = 1,
    )

    /** A candidate that had a neighbour but was refused, with the same
     *  measurements as [Demoted] and the term that refused it — logged so a
     *  leak on device can be read back to its number. */
    data class Refused(
        val region: RecognizedRegion,
        val base: RecognizedRegion,
        val reason: String,
        val sizeRatio: Float,
        val gapEm: Float,
        val pitchRatio: Float,
        val pitchRatioByCount: Float,
        val glyphTight: Boolean,
        /** How many runs the read cut into ([splitRuns]); 1 when unsplit. */
        val runs: Int = 1,
    )

    data class Result(
        /** Input order preserved, demoted regions removed. */
        val kept: List<RecognizedRegion>,
        val demoted: List<Demoted>,
        /** Readings that had a neighbour on their ruby side but failed a
         *  term. Observability only; every one is also in [kept]. */
        val refused: List<Refused> = emptyList(),
    )

    /**
     * Partition [regions] into kept and demoted. Order-independent: every
     * verdict is taken against the input list, so a demotion never changes
     * another region's neighbourhood.
     */
    fun apply(regions: List<RecognizedRegion>): Result {
        if (regions.size < 2) return Result(regions, emptyList())
        val eligible = regions.filter { isEligible(it) }
        if (eligible.size < 2) return Result(regions, emptyList())
        val kept = ArrayList<RecognizedRegion>(regions.size)
        val demoted = ArrayList<Demoted>()
        val refused = ArrayList<Refused>()
        for (r in regions) {
            val verdict = if (isEligible(r) && isRubyText(r.text)) claimingBase(r, eligible) else null
            when (verdict) {
                is Demoted -> demoted += verdict
                is Refused -> { refused += verdict; kept += r }
                null -> kept += r
            }
        }
        return Result(kept, demoted, refused)
    }

    /** Line regions with an upright, non-degenerate box take part on either
     *  side of the rule; slanted and whole-region reads do not. */
    private fun isEligible(r: RecognizedRegion): Boolean =
        r.origin == RegionOrigin.LINE && !r.box.isRotated &&
            r.box.bounds.width() > 0 && r.box.bounds.height() > 0

    /** The candidate's nearest neighbour on its ruby side, with the numbers
     *  the size tests need. */
    private class Neighbour(
        val region: RecognizedRegion,
        val gapPx: Int,
        val gapEm: Float,
        val crossExtent: Int,
        val candidateCrossExtent: Int,
        val readingExtent: Int,
        val candidateReadingExtent: Int,
    )

    /**
     * The verdict on [r]: [Demoted] when its nearest line on the ruby side
     * carries Han and the glyph-size tests pass, [Refused] when that
     * neighbour exists but a term fails, null when there is no neighbour.
     */
    private fun claimingBase(r: RecognizedRegion, eligible: List<RecognizedRegion>): Any? {
        val n = nearestOnRubySide(r, eligible) ?: return null
        val b = n.region
        val ratio = n.crossExtent.toFloat() / n.candidateCrossExtent
        val rChars = charCount(r.text)
        val bChars = charCount(b.text)
        val vertical = b.orientation == TextOrientation.VERTICAL
        val byCount = ratioOf(
            pitchByCount(n.readingExtent, bChars), pitchByCount(n.candidateReadingExtent, rChars),
        )
        val pitchRatio = ratioOf(
            charPitch(b, vertical) ?: pitchByCount(n.readingExtent, bChars),
            charPitch(r, vertical) ?: pitchByCount(n.candidateReadingExtent, rChars),
        )
        // The base line is long, so its char tier says whether this engine
        // measures glyphs; a two-glyph candidate's could not.
        val glyphTight = b.lines.firstOrNull()?.let { GlyphScale.hasMeasuredCharTier(it) } ?: false
        val pitchAvailable = rChars >= PITCH_MIN_CHARS && bChars >= PITCH_MIN_CHARS
        fun refuse(reason: String, runs: Int = 1) = Refused(r, b, reason, ratio, n.gapEm, pitchRatio, byCount, glyphTight, runs)
        if (!hasHan(b.text)) return refuse("neighbour has no Han")
        val heightOk = ratio >= SIZE_RATIO_MIN
        // On a glyph-measuring engine the candidate's positions are real, so
        // spacing is judged per run (see [splitRuns]); elsewhere on the whole.
        val runs = if (glyphTight && pitchAvailable) {
            splitRuns(r, vertical, charPitch(b, vertical) ?: pitchByCount(n.readingExtent, bChars))
        } else {
            null
        }
        val pitchOk = pitchAvailable && (runs?.pass ?: (pitchRatio >= PITCH_RATIO_MIN))
        val ok = when {
            !pitchAvailable -> heightOk            // one character: height is all there is
            glyphTight -> heightOk && pitchOk      // both measurements trustworthy: both must agree
            else -> heightOk || pitchOk            // both biased toward refusing: either suffices
        }
        if (!ok) {
            return refuse(
                when {
                    !pitchAvailable -> "line height"
                    glyphTight -> if (!pitchOk) "pitch" else "line height"
                    else -> "height and pitch"
                },
                runs?.count ?: 1,
            )
        }
        return Demoted(r, b, ratio, n.gapEm, pitchRatio, byCount, glyphTight, runs?.count ?: 1)
    }

    /** The verdict of [splitRuns]: how many runs the read cut into, and
     *  whether they all pass (every run of two or more on spacing, with at
     *  least one such run present). */
    internal class Runs(val count: Int, val pass: Boolean)

    /**
     * Cut the candidate's characters into runs wherever two neighbours are
     * further apart than [basePitch] (one base character: inside a reading
     * the characters are half that apart, so a gap this wide only appears
     * over an un-annotated base character), then test each run's spacing
     * against the base's. Null when the candidate has no character boxes,
     * in which case the caller falls back to the whole-read pitch.
     */
    internal fun splitRuns(region: RecognizedRegion, vertical: Boolean, basePitch: Float): Runs? {
        val chars = region.lines.firstOrNull()?.chars ?: return null
        if (chars.size < PITCH_MIN_CHARS || basePitch <= 0f) return null
        val starts = IntArray(chars.size) { i ->
            val cb = chars[i].box.bounds
            if (vertical) cb.top else cb.left
        }
        starts.sort()
        var count = 0
        var allPass = true
        var anyMulti = false
        var runStart = 0
        fun close(endExclusive: Int) {
            count++
            val len = endExclusive - runStart
            if (len >= PITCH_MIN_CHARS) {
                val advances = ArrayList<Int>(len - 1)
                for (i in runStart + 1 until endExclusive) {
                    val d = starts[i] - starts[i - 1]
                    if (d > 0) advances += d
                }
                if (advances.isEmpty()) { allPass = false; return }
                advances.sort()
                val mid = advances.size / 2
                val runPitch = if (advances.size % 2 == 1) advances[mid].toFloat()
                               else (advances[mid - 1] + advances[mid]) / 2f
                if (basePitch / runPitch >= PITCH_RATIO_MIN) anyMulti = true else allPass = false
            }
        }
        for (i in 1 until starts.size) {
            if (starts[i] - starts[i - 1] > basePitch) { close(i); runStart = i }
        }
        close(starts.size)
        return Runs(count, allPass && anyMulti)
    }

    /**
     * Nearest line below [r] (if that line is horizontal) or left of it (if
     * vertical), overlapping [r] along the reading axis and starting within
     * [REACH_EM] of its own em. Each neighbour is tested in ITS orientation:
     * the base's layout decides where ruby would sit, and a long base line's
     * orientation is reliable where a two-glyph candidate's is not. Two
     * guards keep that from pairing a candidate with a line it cannot be
     * the ruby of (Codex adversarial review, 2026-09-10):
     *  - a candidate of two or more characters must run ALONG the
     *    neighbour's reading axis (wider than tall for a horizontal line,
     *    taller than wide for a vertical column) — a horizontal label beside
     *    a vertical column would otherwise be measured on an axis it has no
     *    characters on; single characters are square and may pair either way;
     *  - "nearest" is the smallest ABSOLUTE separation in pixels, so a box
     *    that overlaps the candidate deeply does not outrank one that touches
     *    it. Pixels, not ems: the neighbours' ems differ, and a normalised
     *    distance would rank in mixed units.
     */
    private fun nearestOnRubySide(r: RecognizedRegion, eligible: List<RecognizedRegion>): Neighbour? {
        var best: Neighbour? = null
        val rb = r.box.bounds
        val multiChar = charCount(r.text) >= PITCH_MIN_CHARS
        for (c in eligible) {
            if (c === r) continue
            val vertical = c.orientation == TextOrientation.VERTICAL
            val cb = c.box.bounds
            val eC = crossExtent(cb, vertical)
            val eR = crossExtent(rb, vertical)
            if (eC <= 0 || eR <= 0) continue
            // Gap runs from the candidate's far edge to the neighbour's near
            // edge; overlap along the reading axis.
            val gap: Int
            val overlap: Int
            val rReading: Int
            val cReading: Int
            if (vertical) {
                gap = rb.left - cb.right
                overlap = minOf(rb.bottom, cb.bottom) - maxOf(rb.top, cb.top)
                rReading = rb.height()
                cReading = cb.height()
            } else {
                gap = cb.top - rb.bottom
                overlap = minOf(rb.right, cb.right) - maxOf(rb.left, cb.left)
                rReading = rb.width()
                cReading = cb.width()
            }
            if (overlap < OVERLAP_MIN * rReading) continue
            if (multiChar && rReading < eR) continue
            val gapEm = gap.toFloat() / eC
            if (gapEm < GAP_MIN_EM || gapEm > REACH_EM) continue
            if (best == null || abs(gap) < abs(best.gapPx)) {
                best = Neighbour(c, gap, gapEm, eC, eR, cReading, rReading)
            }
        }
        return best
    }

    private fun crossExtent(r: Rect, vertical: Boolean): Int =
        if (vertical) r.width() else r.height()

    /** Non-whitespace characters: what the reading extent is spread over. */
    private fun charCount(text: String): Int = text.count { !it.isWhitespace() }

    /**
     * Character pitch from the line's char tier: the median start-to-start
     * distance between consecutive character boxes along the reading axis
     * (left edges for horizontal text, top edges for vertical), or null when
     * the tier has fewer than [PITCH_MIN_CHARS] boxes or no positive
     * distance to measure. The median is what makes this robust to holes: a
     * merged read's one or two large jumps are outliers among ruby-sized
     * distances. Non-positive distances (overlapping boxes) are ignored.
     * Padding on the line box does not enter: the distance is between two
     * characters inside it.
     */
    internal fun charPitch(region: RecognizedRegion, vertical: Boolean): Float? {
        val chars = region.lines.firstOrNull()?.chars ?: return null
        if (chars.size < PITCH_MIN_CHARS) return null
        val starts = IntArray(chars.size) { i ->
            val cb = chars[i].box.bounds
            if (vertical) cb.top else cb.left
        }
        starts.sort()
        val advances = ArrayList<Int>(starts.size - 1)
        for (i in 1 until starts.size) {
            val d = starts[i] - starts[i - 1]
            if (d > 0) advances += d
        }
        if (advances.isEmpty()) return null
        advances.sort()
        val mid = advances.size / 2
        return if (advances.size % 2 == 1) advances[mid].toFloat()
               else (advances[mid - 1] + advances[mid]) / 2f
    }

    /** Fallback pitch: reading extent over character count; 0 when degenerate. */
    private fun pitchByCount(reading: Int, chars: Int): Float =
        if (reading <= 0 || chars <= 0) 0f else reading.toFloat() / chars

    /** base pitch / candidate pitch; 0 when either is degenerate, which
     *  fails the pitch test. */
    private fun ratioOf(basePitch: Float, candidatePitch: Float): Float =
        if (basePitch <= 0f || candidatePitch <= 0f) 0f else basePitch / candidatePitch

    /** Han: CJK Unified (+ Extension A, Compatibility Ideographs) and the
     *  iteration mark `々`, which carries Script=Han and never appears in a
     *  reading. */
    internal fun isHan(c: Char): Boolean =
        c in '一'..'鿿' || c in '㐀'..'䶿' ||
            c in '豈'..'﫿' || c == '々'

    /** Syllabic kana: hiragana/katakana letters plus halfwidth katakana. The
     *  prolonged mark, middle dot and iteration marks are NOT counted here —
     *  they may appear inside a reading but cannot make one on their own. */
    internal fun isSyllabicKana(c: Char): Boolean =
        c in 'ぁ'..'ゖ' || c in 'ァ'..'ヺ' ||
            (c in 'ｦ'..'ﾟ' && c != 'ｰ')

    internal fun hasHan(text: String): Boolean = text.any { isHan(it) }

    /** A reading: at least one syllabic kana, at most [MAX_HAN_IN_READING]
     *  Han, and more kana than Han. See the class doc: one kanji is a
     *  misread, two are a sentence. */
    internal fun isRubyText(text: String): Boolean {
        var kana = 0
        var han = 0
        for (c in text) {
            if (isSyllabicKana(c)) kana++ else if (isHan(c)) han++
        }
        return kana > 0 && han <= MAX_HAN_IN_READING && kana > han
    }
}
