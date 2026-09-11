package com.playtranslate.ocr.core

import android.graphics.Rect
import com.playtranslate.language.TextOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * One cell per conjunct of [RubyFilter]'s rule, plus the corpus-derived cells
 * that set its constants (scripts/ruby_census.py over
 * results-1786212247819.jsonl). Geometry is in pixels straight from the census
 * rows where one exists, so a constant that moves shows which cliff it hit.
 */
@RunWith(RobolectricTestRunner::class)
class RubyFilterTest {

    private fun region(
        text: String,
        l: Int, t: Int, r: Int, b: Int,
        orientation: TextOrientation = TextOrientation.HORIZONTAL,
        angleDeg: Float = 0f,
        origin: RegionOrigin = RegionOrigin.LINE,
    ): RecognizedRegion {
        val rect = Rect(l, t, r, b)
        val box = if (angleDeg == 0f) OcrBox.upright(rect)
                  else OcrBox(rect, rect.width().toFloat(), rect.height().toFloat(), angleDeg)
        return RecognizedRegion(
            text = text, box = box, orientation = orientation, confidence = 0.9f,
            lines = listOf(RecognizedLine(text, box, orientation)), origin = origin,
        )
    }

    private fun demotedTexts(vararg regions: RecognizedRegion): List<String> =
        RubyFilter.apply(regions.toList()).demoted.map { it.region.text }

    /** A glyph-measuring engine's base line (Meiki, ML Kit): character boxes
     *  narrower than the line with gaps between them, evenly pitched. */
    private fun tightBase(text: String, l: Int, t: Int, r: Int, b: Int, orientation: TextOrientation = TextOrientation.HORIZONTAL): RecognizedRegion {
        val n = text.filter { !it.isWhitespace() }.length
        val span = if (orientation == TextOrientation.VERTICAL) b - t else r - l
        val step = span / n
        val start = if (orientation == TextOrientation.VERTICAL) t else l
        return regionWithChars(text, l, t, r, b, starts = List(n) { start + it * step }, cell = step - 2, orientation = orientation)
    }

    /** A PaddleOCR-shaped region: character cells inherit the line's full
     *  cross extent and tile it contiguously along the reading axis at the
     *  given starts — the tier [GlyphScale.hasMeasuredCharTier] rejects. */
    private fun paddleRegion(
        text: String,
        l: Int, t: Int, r: Int, b: Int,
        starts: List<Int>,
        orientation: TextOrientation = TextOrientation.HORIZONTAL,
    ): RecognizedRegion {
        val rect = Rect(l, t, r, b)
        val box = OcrBox.upright(rect)
        val cs = text.filter { !it.isWhitespace() }
        val chars = cs.mapIndexed { i, c ->
            val s = starts[i]
            val e = if (i + 1 < starts.size) starts[i + 1] else (if (orientation == TextOrientation.VERTICAL) b else r)
            val cb = if (orientation == TextOrientation.VERTICAL) Rect(l, s, r, e) else Rect(s, t, e, b)
            CharBox(c.toString(), OcrBox.upright(cb), i)
        }
        return RecognizedRegion(
            text = text, box = box, orientation = orientation, confidence = 0.9f,
            lines = listOf(RecognizedLine(text, box, orientation, chars = chars)),
        )
    }

    /** A region whose char tier places each character of [text] at the
     *  given reading-axis start, each box [cell] px long and spanning the
     *  line's cross extent — the shape every position-emitting engine
     *  produces. */
    private fun regionWithChars(
        text: String,
        l: Int, t: Int, r: Int, b: Int,
        starts: List<Int>,
        cell: Int,
        orientation: TextOrientation = TextOrientation.HORIZONTAL,
    ): RecognizedRegion {
        val rect = Rect(l, t, r, b)
        val box = OcrBox.upright(rect)
        val chars = text.filter { !it.isWhitespace() }.mapIndexed { i, c ->
            val s = starts[i]
            val cb = if (orientation == TextOrientation.VERTICAL) Rect(l, s, r, s + cell)
                     else Rect(s, t, s + cell, b)
            CharBox(c.toString(), OcrBox.upright(cb), i)
        }
        return RecognizedRegion(
            text = text, box = box, orientation = orientation, confidence = 0.9f,
            lines = listOf(RecognizedLine(text, box, orientation, chars = chars)),
        )
    }

    // ── constants are pinned: moving one is a deliberate re-calibration ──

    @Test
    fun constants_pinned() {
        assertEquals(1.5f, RubyFilter.SIZE_RATIO_MIN)
        assertEquals(1.4f, RubyFilter.PITCH_RATIO_MIN)
        assertEquals(2, RubyFilter.PITCH_MIN_CHARS)
        assertEquals(1.0f, RubyFilter.REACH_EM)
        assertEquals(-0.5f, RubyFilter.GAP_MIN_EM)
        assertEquals(0.5f, RubyFilter.OVERLAP_MIN)
        assertEquals(1, RubyFilter.MAX_HAN_IN_READING)
    }

    // ── the two orientations ───────────────────────────────────────────

    @Test
    fun horizontal_rubyAboveKanjiLine_demoted() {
        // Screenshot_20260724 / Paddle: ほし (h 22) over ケンゾールとふしぎな星 (h 38), gap 2 px.
        val ruby = region("ほし", 1784, 52, 1823, 74)
        val base = region("ケンゾールとふしぎな星", 1384, 76, 1821, 114)
        val res = RubyFilter.apply(listOf(base, ruby))
        assertEquals(listOf("ほし"), res.demoted.map { it.region.text })
        assertEquals(listOf(base), res.kept)
        val d = res.demoted.single()
        assertEquals(base, d.base)
        assertEquals(38f / 22f, d.sizeRatio, 1e-4f)
        assertEquals(2f / 38f, d.gapEm, 1e-4f)
        // Pitch is measured even in the tight band: 437 px / 11 chars over 39 px / 2 chars.
        assertEquals((437f / 11f) / (39f / 2f), d.pitchRatio, 1e-4f)
    }

    @Test
    fun vertical_rubyRightOfKanjiColumn_demoted() {
        // rEHbXlAk / Meiki: いなか (w 12) right of 田舎なのん? (w 24), touching.
        val base = region("田舎なのん?", 372, 731, 396, 874, TextOrientation.VERTICAL)
        val ruby = region("いなか", 396, 760, 408, 800, TextOrientation.VERTICAL)
        assertEquals(listOf("いなか"), demotedTexts(base, ruby))
    }

    // ── the corpus cells that set the constants ────────────────────────

    @Test
    fun vertical_kanaOnlyBodyColumn_atColumnGap_refusedByPitch() {
        // shingekinokyojin / ML Kit: すぐに is a BODY column read 16 px wide
        // beside the 26 px kanji column 仲良くなるさ — ratio 1.63 clears the
        // cross-axis test, so pitch decides: 3 chars over 96 px against 6
        // over 146 px is 0.76, a full-width advance, not a reading.
        val base = tightBase("仲良くなるさ", 616, 455, 642, 601, TextOrientation.VERTICAL)
        val body = region("すぐに", 651, 457, 667, 553, TextOrientation.VERTICAL)
        val res = RubyFilter.apply(listOf(base, body))
        assertTrue(res.demoted.isEmpty())
        assertEquals("pitch", res.refused.single().reason)
    }

    @Test
    fun vertical_kanaOnlyBodyColumn_atWideColumnGap_refusedByPitch() {
        // rEHbXlAk / ML Kit: ここって at a 0.61 em column gap, height ratio 1.5.
        // Inside reach now; its pitch (0.96, full-width advance) refuses it.
        val base = tightBase("田舎なのん?", 372, 731, 402, 874, TextOrientation.VERTICAL)
        val body = region("ここって", 420, 732, 440, 825, TextOrientation.VERTICAL)
        val res = RubyFilter.apply(listOf(base, body))
        assertTrue(res.demoted.isEmpty())
        assertEquals("pitch", res.refused.single().reason)
    }

    @Test
    fun horizontal_thorNotice_rubyPastOldCap_demoted() {
        // Thor 2026-09-09 / Meiki: いっしょいか (h 15, 6 chars over 121 px) sits
        // 10 px above 一緒に買うようにしてください。 (h 33, 15 chars over 491 px):
        // gap 0.303 em, which the earlier flat 0.30 cap leaked. Cross ratio
        // 2.2, pitch 1.62.
        val base = region("一緒に買うようにしてください。", 697, 738, 1188, 771)
        val ruby = region("いっしょいか", 703, 713, 824, 728)
        val res = RubyFilter.apply(listOf(base, ruby))
        assertEquals(listOf("いっしょいか"), res.demoted.map { it.region.text })
        assertTrue(res.demoted.single().gapEm > 0.30f)
        assertEquals((491f / 15f) / (121f / 6f), res.demoted.single().pitchRatio, 1e-4f)
    }

    @Test
    fun sizeTest_isKeyedOnEvidence_notOnGap() {
        // Glyph-measuring base, h 40, 12 chars over 500 px (pitch 41.7).
        // Every candidate is 4 px above it, a tight gap; the height ratio is
        // 2.0 throughout, so pitch is what decides.
        val base = tightBase("何が出るかは、お楽しみ。", 100, 200, 600, 240)
        // 2 chars over 40 px: pitch 20, ratio 2.08 — demoted.
        assertEquals(listOf("たの"), demotedTexts(base, region("たの", 300, 176, 340, 196)))
        // 4 chars over 80 px: pitch 20, ratio 2.08 — demoted.
        assertEquals(listOf("たのしみ"), demotedTexts(base, region("たのしみ", 300, 176, 380, 196)))
        // 4 chars over 120 px: pitch 30, ratio 1.39 — a full-width advance
        // at half height is not a reading, whatever the gap: kept.
        assertTrue(demotedTexts(base, region("たのしみ", 300, 176, 420, 196)).isEmpty())
        // 2 chars is where pitch starts being demanded: 2 chars over 60 px
        // (pitch 30, ratio 1.39) is kept; over 40 px (pitch 20) demoted.
        assertTrue(demotedTexts(base, region("たの", 300, 176, 360, 196)).isEmpty())
        assertEquals(listOf("たの"), demotedTexts(base, region("たの", 300, 176, 340, 196)))
    }

    @Test
    fun attached_meansNearestLineOnRubySide() {
        // A small kana-only label (5 chars at full-width pitch, so not itself
        // ruby-shaped) sits between the candidate and the kanji line: the
        // candidate's nearest neighbour below is that label, which has no
        // Han, so the candidate is not attached to anything — even though
        // the kanji line is within reach and twice its size.
        val ruby = region("たの", 300, 100, 340, 120)
        val between = region("はいいいえ", 100, 124, 600, 136)
        val kanji = tightBase("何が出るかは、お楽しみ。", 100, 139, 600, 179)
        assertTrue(demotedTexts(ruby, between, kanji).isEmpty())
        // Remove the interposer and the kanji line is the neighbour.
        assertEquals(listOf("たの"), demotedTexts(ruby, kanji))
    }

    @Test
    fun vertical_paddleBodyColumn_tightGap_refusedByPitch() {
        // rEHbXlAk / Paddle-fast: ここって (w 29) beside 田舎なのん？ (w 40) at a
        // padded 5 px gap (0.12 em). Its characters are as far apart as the
        // base's (pitch 0.95), a body column.
        val base = region("田舎なのん？", 365, 727, 405, 871, TextOrientation.VERTICAL)
        val body = region("ここって", 410, 726, 439, 827, TextOrientation.VERTICAL)
        val res = RubyFilter.apply(listOf(base, body))
        assertTrue(res.demoted.isEmpty())
        // Paddle base: either measurement would do, and both refused (1.38, 0.95).
        assertEquals("height and pitch", res.refused.single().reason)
    }

    @Test
    fun vertical_paddleRubyOverlappingBase_negativeGap_demoted() {
        // shingekinokyojin / Paddle: 巨人とは片思いの (w 34) with かたおん (w 19)
        // overlapping it by 8 px (-0.24 em) — padded boxes overlap.
        val base = region("巨人とは片思いの", 958, 441, 992, 638, TextOrientation.VERTICAL)
        val ruby = region("かたおん", 984, 541, 1003, 587, TextOrientation.VERTICAL)
        assertEquals(listOf("かたおん"), demotedTexts(base, ruby))
    }

    @Test
    fun allKanaBase_isNotABase_kept() {
        // Screenshot_20260724 / ML Kit: a smaller kana-only caption glued
        // above a kana-only body line on an all-hiragana screen. Ratio 1.60,
        // gap 0.1 em — every geometric term passes; base-Han refuses it.
        val base = region("だがきぼうはまだうしなわれてはいない", 100, 200, 900, 248)
        val cap = region("そしてかなしい…", 100, 165, 400, 195)
        assertTrue(demotedTexts(base, cap).isEmpty())
    }

    // ── one cell per remaining conjunct ────────────────────────────────

    @Test
    fun script_candidateWithHan_kept() {
        // A small kanji sub-heading above a body line: right size, right
        // place, wrong script.
        val base = region("何が出るかは、お楽しみ。", 100, 200, 600, 240)
        val sub = region("田舎", 100, 175, 160, 195)
        assertTrue(demotedTexts(base, sub).isEmpty())
    }

    @Test
    fun script_latinOnlyLabel_kept() {
        val base = region("体力の残り", 100, 200, 400, 240)
        val label = region("HP", 100, 176, 140, 196)
        assertTrue(demotedTexts(base, label).isEmpty())
    }

    @Test
    fun script_prolongedMarkAndKatakana_demoted() {
        // なかーよ: ー lives in the Katakana block, so a kana-only whitelist
        // would miss it; the negative predicate does not. Katakana readings
        // classify too.
        val base = region("仲良くなるさ", 616, 455, 642, 601, TextOrientation.VERTICAL)
        val ruby = region("なかーよ", 642, 470, 655, 530, TextOrientation.VERTICAL)
        val base2 = region("星を見た", 100, 200, 400, 240)
        val ruby2 = region("ホシ", 100, 176, 150, 196)
        assertEquals(listOf("なかーよ", "ホシ"), demotedTexts(base, ruby, base2, ruby2))
    }

    @Test
    fun script_iterationMarkCountsAsHan() {
        assertFalse(RubyFilter.isRubyText("々"))
        assertFalse(RubyFilter.isRubyText("ー"))          // no syllabic kana
        assertTrue(RubyFilter.isRubyText("なかーよ"))
        assertTrue(RubyFilter.isRubyText("ｶﾀｶﾅ"))
        assertTrue(RubyFilter.hasHan("昔々"))
    }

    @Test
    fun script_atMostOneKanji_theSingleMisread() {
        assertTrue(RubyFilter.isRubyText("ほうていたいりに人"))   // ん read as 人
        assertTrue(RubyFilter.isRubyText("きょだい一けん"))       // ー read as 一
        assertTrue(RubyFilter.isRubyText("なかー中"))            // ま read as 中 (corpus)
        assertTrue(RubyFilter.isRubyText("なか上"))              // よ read as 上 (corpus)
        assertTrue(RubyFilter.isRubyText("何をする"))            // one kanji: considered, geometry decides
        assertFalse(RubyFilter.isRubyText("一人"))               // no kana at all
        assertFalse(RubyFilter.isRubyText("い人"))               // kana do not outnumber
        assertFalse(RubyFilter.isRubyText("二人で"))             // two kanji
        assertFalse(RubyFilter.isRubyText("田舎で"))             // two kanji
        assertFalse(RubyFilter.isRubyText("何を買おうか？"))      // two kanji: a sentence (corpus false positive)
        assertTrue(RubyFilter.hasHan("一人"))                    // still a valid BASE
    }

    @Test
    fun horizontal_thorNotice_misreadRuby_demoted() {
        // Thor 2026-09-09 / Meiki: the reading ほうていだいりにん came back as
        // ほうていたいりに人 (h 15) above アブリの利用方法… (h 38), gap 0.16 em.
        val base = region("アブリの利用方法〔利用時間等〕は、法定代理人〔ご両親等〕とよく相談して決めてくだ", 216, 812, 1667, 850)
        val ruby = region("ほうていたいりに人", 810, 791, 949, 806)
        assertEquals(listOf("ほうていたいりに人"), demotedTexts(base, ruby))
    }

    @Test
    fun side_belowHorizontalBase_kept() {
        val base = region("何が出るかは、お楽しみ。", 100, 200, 600, 240)
        val below = region("たの", 300, 244, 340, 264)
        assertTrue(demotedTexts(base, below).isEmpty())
    }

    @Test
    fun side_leftOfVerticalColumn_kept() {
        val base = region("田舎なのん?", 372, 731, 396, 874, TextOrientation.VERTICAL)
        val left = region("いなか", 358, 760, 370, 800, TextOrientation.VERTICAL)
        assertTrue(demotedTexts(base, left).isEmpty())
    }

    @Test
    fun reach_boundsTheNeighbourSearch() {
        // Base h 40: 40 px above (1.0 em) is still a neighbour, 41 px is not.
        val base = region("何が出るかは、お楽しみ。", 100, 200, 600, 240)
        assertEquals(listOf("たの"), demotedTexts(base, region("たの", 300, 140, 340, 160)))
        assertTrue(demotedTexts(base, region("たの", 300, 139, 340, 159)).isEmpty())
    }

    @Test
    fun gap_overlapBeyondFloor_kept() {
        // Base h 40: -20 px = -0.5 em is still a neighbour, -21 px is not.
        val base = region("何が出るかは、お楽しみ。", 100, 200, 600, 240)
        assertEquals(listOf("たの"), demotedTexts(base, region("たの", 300, 200, 340, 220)))
        assertTrue(demotedTexts(base, region("たの", 300, 201, 340, 221)).isEmpty())
    }

    @Test
    fun size_boundary_lineHeight() {
        // Line height is consulted for a single-character read (no pitch to
        // measure). Base h 40: candidate h 26 (1.54) demotes, h 27 (1.48) does not.
        val base = region("何が出るかは、お楽しみ。", 100, 200, 600, 240)
        assertEquals(listOf("た"), demotedTexts(base, region("た", 300, 170, 320, 196)))
        assertTrue(demotedTexts(base, region("た", 300, 169, 320, 196)).isEmpty())
    }

    @Test
    fun overlap_belowHalfOfCandidate_kept() {
        // Candidate 40 px wide overhanging the base's right edge: 24 px
        // inside passes, 19 px inside does not.
        val base = region("何が出るかは、お楽しみ。", 100, 200, 600, 240)
        assertEquals(listOf("たの"), demotedTexts(base, region("たの", 576, 176, 616, 196)))
        assertTrue(demotedTexts(base, region("たの", 581, 176, 621, 196)).isEmpty())
    }

    @Test
    fun rotated_neverParticipates() {
        val base = region("何が出るかは、お楽しみ。", 100, 200, 600, 240)
        val slantedRuby = region("たの", 300, 176, 340, 196, angleDeg = 12f)
        assertTrue(demotedTexts(base, slantedRuby).isEmpty())
        val slantedBase = region("何が出るかは、お楽しみ。", 100, 200, 600, 240, angleDeg = 12f)
        val ruby = region("たの", 300, 176, 340, 196)
        assertTrue(demotedTexts(slantedBase, ruby).isEmpty())
    }

    @Test
    fun wholeRegion_neverParticipates() {
        val bubble = region("何が出るかは、お楽しみ。", 100, 200, 600, 240, origin = RegionOrigin.WHOLE_REGION)
        val ruby = region("たの", 300, 176, 340, 196)
        assertTrue(demotedTexts(bubble, ruby).isEmpty())
    }

    @Test
    fun keptPreservesInputOrder_andSingletonIsIdentity() {
        val a = region("たの", 300, 176, 340, 196)
        val base = region("何が出るかは、お楽しみ。", 100, 200, 600, 240)
        val c = region("次へ", 100, 300, 200, 340)
        val res = RubyFilter.apply(listOf(a, base, c))
        assertEquals(listOf(base, c), res.kept)
        assertEquals(listOf(a), RubyFilter.apply(listOf(a)).kept)
    }

    // ── pitch from character positions ──────────────────────────────────

    @Test
    fun pitch_fromPositions_ignoresTheHoleInAMergedRead() {
        // Thor 2026-09-09, the flickering leak: いっしょ over 一緒 and か over 買,
        // read as one 5-char region over the same 111 px box that read as 6
        // chars (with a filler for the hole) on the pass before. By count the
        // ratio is 1.35 and leaks; by position the four ruby advances (18 px)
        // outvote the one hole jump (40 px) and the ratio is 30/18.
        val base = regionWithChars(
            "一緒に買うようにしてください。", 727, 720, 1178, 752,
            starts = List(15) { 727 + it * 30 }, cell = 28,
        )
        val ruby = regionWithChars(
            "いっしょか", 732, 698, 843, 714,
            starts = listOf(732, 750, 768, 786, 826), cell = 16,
        )
        val res = RubyFilter.apply(listOf(base, ruby))
        assertEquals(listOf("いっしょか"), res.demoted.map { it.region.text })
        val d = res.demoted.single()
        assertEquals(30f / 18f, d.pitchRatio, 1e-4f)
        assertEquals((451f / 15f) / (111f / 5f), d.pitchRatioByCount, 1e-4f)
        assertTrue(d.pitchRatioByCount < RubyFilter.PITCH_RATIO_MIN)
    }

    @Test
    fun split_holesOutnumberingAdvances_demotedByRuns() {
        // Two single glyphs and one pair spread over the line: advances
        // 18, 60, 60. Judged whole, the median is a hole (ratio 0.5) and
        // the read stayed; cut at the two holes it is three runs — いっ on
        // spacing (30/18), か and て on the height already required.
        val base = regionWithChars(
            "一緒に買うようにしてください。", 727, 720, 1178, 752,
            starts = List(15) { 727 + it * 30 }, cell = 28,
        )
        val ruby = regionWithChars(
            "いっかて", 732, 698, 900, 714,
            starts = listOf(732, 750, 810, 870), cell = 16,
        )
        val d = RubyFilter.apply(listOf(base, ruby)).demoted.single()
        assertEquals(3, d.runs)
        assertTrue(d.pitchRatio < RubyFilter.PITCH_RATIO_MIN)   // the whole-read number, still reported
    }

    @Test
    fun split_thorAlbum_hoshiSuteii_demoted() {
        // Thor 2026-09-10 / Meiki, the "Discard" overlay: ほしていい over
        // ふしぎな星を手に入れた。 — ほし over 星, て over 手, い over 入, holes
        // over を and に. Height 1.78, whole-read spacing 1.06: refused on
        // every pass. Runs: [ほし] 33/15, [て], [いい] 33/15.
        val base = tightBase("ふしぎな星を手に入れた。", 968, 672, 1361, 704)
        val ruby = regionWithChars(
            "ほしていい", 1106, 651, 1271, 669,
            starts = listOf(1106, 1121, 1172, 1238, 1253), cell = 14,
        )
        val res = RubyFilter.apply(listOf(base, ruby))
        assertEquals(listOf("ほしていい"), res.demoted.map { it.region.text })
        assertEquals(3, res.demoted.single().runs)
    }

    @Test
    fun split_letterSpacedTitle_kept() {
        // A small subtitle set with wide letter spacing, right above a kanji
        // line at half its height: every cut leaves a single character, and
        // single characters carry no spacing evidence, so it stays.
        val base = tightBase("ふしぎな星を手に入れた。", 968, 672, 1361, 704)
        val title = regionWithChars(
            "ふしぎなほし", 1000, 651, 1240, 669,
            starts = List(6) { 1000 + it * 40 }, cell = 14,
        )
        val res = RubyFilter.apply(listOf(base, title))
        assertTrue(res.demoted.isEmpty())
        val x = res.refused.single()
        assertEquals("pitch", x.reason)
        assertEquals(6, x.runs)
    }

    @Test
    fun split_bodyLineWithWideSpace_kept() {
        // はい　いいえ at full pitch with a wide space: two runs, both at the
        // base's spacing, so neither passes and the line stays.
        val base = tightBase("何が出るかは、お楽しみ。", 100, 200, 600, 240)
        val body = regionWithChars(
            "はいいいえ", 100, 168, 400, 196,
            starts = listOf(100, 141, 260, 301, 342), cell = 36,
        )
        val res = RubyFilter.apply(listOf(base, body))
        assertTrue(res.demoted.isEmpty())
        assertEquals(2, res.refused.single().runs)
    }

    @Test
    fun split_notAppliedOnPaddle() {
        // Paddle's positions are sequence order, so a hole read there is
        // never cut: it is judged whole under the either-measurement rule.
        val base = paddleRegion("そのドカンの中に入れます。", 585, 842, 991, 877, starts = List(13) { 585 + it * 31 })
        val wide = paddleRegion("なかはい", 777, 818, 900, 843, starts = listOf(777, 808, 839, 870))
        val res = RubyFilter.apply(listOf(base, wide))
        assertTrue(res.demoted.isEmpty())
        assertEquals(1, res.refused.single().runs)
    }

    @Test
    fun pitch_baseMedian_ignoresHalfWidthDigits() {
        // 未成年(18歳未満の方へ: the two digits advance 15 px inside a 30 px line.
        // By count the base pitch reads 27.5; the median reads 30.
        val starts = mutableListOf<Int>(); var x = 728
        for (c in "未成年(18歳未満の方へ") { starts += x; x += if (c.isDigit()) 15 else 30 }
        val base = regionWithChars("未成年(18歳未満の方へ", 728, 497, 1056, 532, starts, cell = 28)
        val ruby = regionWithChars("さいみまん", 950, 474, 1030, 489, starts = List(5) { 950 + it * 15 }, cell = 14)
        val d = RubyFilter.apply(listOf(base, ruby)).demoted.single()
        assertEquals(30f / 15f, d.pitchRatio, 1e-4f)
        assertTrue(d.pitchRatioByCount < d.pitchRatio)
    }

    @Test
    fun pitch_evenTiledTier_matchesTheCountFallback() {
        // manga-ocr tiles characters evenly: median advance == extent / count.
        val text = "何が出るかは、お楽しみ。"
        val box = OcrBox.upright(Rect(100, 200, 700, 240))
        val chars = synthesizeEvenCharBoxes(text, box, vertical = false)
        val base = RecognizedRegion(
            text, box, TextOrientation.HORIZONTAL, 0.9f,
            lines = listOf(RecognizedLine(text, box, TextOrientation.HORIZONTAL, chars = chars)),
        )
        val ruby = region("たのしみ", 300, 176, 400, 196)     // no char tier: count fallback
        val d = RubyFilter.apply(listOf(base, ruby)).demoted.single()
        assertEquals(d.pitchRatioByCount, d.pitchRatio, 1e-4f)
    }

    @Test
    fun pitch_noCharTierOnEitherSide_equalsCountMeasure() {
        val base = region("何が出るかは、お楽しみ。", 100, 200, 600, 240)
        val d = RubyFilter.apply(listOf(base, region("たのしみ", 300, 176, 380, 196))).demoted.single()
        assertEquals(d.pitchRatioByCount, d.pitchRatio, 1e-4f)
        assertEquals((500f / 12f) / (80f / 4f), d.pitchRatio, 1e-4f)
    }

    @Test
    fun pitch_vertical_fromPositionsWithAHole() {
        // Vertical column: ruby なかよ over 仲良 with a hole before the last glyph.
        val base = regionWithChars(
            "仲良くなるさ", 616, 455, 642, 601,
            starts = List(6) { 455 + it * 24 }, cell = 22, orientation = TextOrientation.VERTICAL,
        )
        val ruby = regionWithChars(
            "なかよさ", 642, 460, 655, 560,
            starts = listOf(460, 472, 484, 540), cell = 11, orientation = TextOrientation.VERTICAL,
        )
        val d = RubyFilter.apply(listOf(base, ruby)).demoted.single()
        assertEquals(24f / 12f, d.pitchRatio, 1e-4f)
    }

    @Test
    fun charPitch_needsTwoBoxesAndAPositiveDistance() {
        val one = regionWithChars("た", 300, 176, 320, 196, starts = listOf(300), cell = 18)
        assertEquals(null, RubyFilter.charPitch(one, vertical = false))
        // Two boxes: one start-to-start distance, used as is.
        val two = regionWithChars("たの", 300, 176, 340, 196, starts = listOf(300, 320), cell = 18)
        assertEquals(20f, RubyFilter.charPitch(two, vertical = false)!!, 1e-4f)
        // Three boxes stacked on one x: no positive distance to measure.
        val stacked = regionWithChars("たのし", 300, 176, 340, 196, starts = listOf(300, 300, 300), cell = 18)
        assertEquals(null, RubyFilter.charPitch(stacked, vertical = false))
        val three = regionWithChars("たのし", 300, 176, 360, 196, starts = listOf(300, 318, 340), cell = 18)
        assertEquals(20f, RubyFilter.charPitch(three, vertical = false)!!, 1e-4f)   // median of 18, 22
    }

    // ── which engines get the line-height test ──────────────────────────

    @Test
    fun paddle_paddedBoxes_glyphSizeFromCharacterPitch() {
        // Thor 2026-09-09 / Paddle: ゆうりょう came back in a 22 px box over a
        // 30 px base line (line-height ratio 1.36, refused before), while its
        // characters sit 15 px apart against the base's 30 px. Paddle's cells
        // inherit the line height, so the line-height test is not consulted.
        val base = paddleRegion("有料でアイテムを買うときは", 390, 650, 780, 680, starts = List(13) { 390 + it * 30 })
        val ruby = paddleRegion("ゆうりょう", 400, 622, 478, 644, starts = listOf(400, 415, 430, 445, 460))
        val d = RubyFilter.apply(listOf(base, ruby)).demoted.single()
        assertEquals("ゆうりょう", d.region.text)
        assertFalse(d.glyphTight)
        assertEquals(30f / 15f, d.pitchRatio, 1e-4f)
        assertTrue(d.sizeRatio < RubyFilter.SIZE_RATIO_MIN)
    }

    @Test
    fun paddle_singleCharacter_keepsTheLineHeightTest() {
        // One character has no start-to-start distance: the padded line
        // height is all there is, and at 1.36 it leaks. Stated cost.
        val base = paddleRegion("有料でアイテムを買うときは", 390, 650, 780, 680, starts = List(13) { 390 + it * 30 })
        val res = RubyFilter.apply(listOf(base, paddleRegion("か", 400, 622, 420, 644, starts = listOf(400))))
        assertTrue(res.demoted.isEmpty())
        assertEquals("line height", res.refused.single().reason)
        // A single character in a tight box still demotes on height.
        assertEquals(listOf("か"), demotedTexts(base, paddleRegion("か", 400, 630, 415, 645, starts = listOf(400))))
    }

    @Test
    fun glyphTightEngine_lineHeightStillVetoes() {
        // Same two-character candidate, pitch ratio 2.08 but line-height
        // ratio 1.43: against a base whose boxes hug the glyphs (Meiki, ML
        // Kit) the height veto stands; against Paddle's cells it does not.
        val tightBase = regionWithChars("何が出るかは、お楽しみ。", 100, 200, 600, 240, starts = List(12) { 100 + it * 40 }, cell = 36)
        val paddleBase = paddleRegion("何が出るかは、お楽しみ。", 100, 200, 600, 240, starts = List(12) { 100 + it * 40 })
        val wide = regionWithChars("はい", 300, 168, 340, 196, starts = listOf(300, 320), cell = 18)
        val r1 = RubyFilter.apply(listOf(tightBase, wide))
        assertTrue(r1.demoted.isEmpty())
        assertEquals("line height", r1.refused.single().reason)
        assertTrue(r1.refused.single().glyphTight)
        val r2 = RubyFilter.apply(listOf(paddleBase, wide))
        assertEquals(listOf("はい"), r2.demoted.map { it.region.text })
        assertFalse(r2.demoted.single().glyphTight)
    }

    @Test
    fun paddle_eitherMeasurementSuffices() {
        // Thor 2026-09-10 / Paddle: なかはい (なか over 中, はい over 入) came back
        // with its four characters spread evenly over the box, the hole
        // invisible, so pitch read 0.97 while the padded height read 1.67.
        // Paddle's measurements only ever err toward refusing, so either one
        // saying half-size is enough.
        val base = paddleRegion("そのドカンの中に入れます。", 585, 842, 991, 877, starts = List(13) { 585 + it * 31 })
        val ruby = paddleRegion("なかはい", 777, 822, 880, 843, starts = listOf(777, 803, 829, 855))
        val d = RubyFilter.apply(listOf(base, ruby)).demoted.single()
        assertEquals("なかはい", d.region.text)
        assertTrue(d.pitchRatio < RubyFilter.PITCH_RATIO_MIN)
        assertTrue(d.sizeRatio >= RubyFilter.SIZE_RATIO_MIN)
        // Failing both is refused, and says so.
        val wide = paddleRegion("なかはい", 777, 818, 900, 843, starts = listOf(777, 808, 839, 870))   // h 25: 1.4; pitch 1.0
        val res = RubyFilter.apply(listOf(base, wide))
        assertTrue(res.demoted.isEmpty())
        assertEquals("height and pitch", res.refused.single().reason)
    }

    @Test
    fun base_withOneCharacter_hasNoPitch() {
        // random_manga / Paddle (corpus reach sweep): a body line above a lone
        // kanji region. One character has no "next", so the base's whole
        // width is not a pitch; height decides, and at 0.35 it is kept.
        // A wide, low box read as one kanji directly under a 40 px body line:
        // by count its "pitch" would be its whole 200 px width (ratio 10).
        val lone = paddleRegion("外", 280, 304, 480, 316, starts = listOf(280))
        val body = paddleRegion("とはおもわんかね", 300, 260, 460, 300, starts = List(8) { 300 + it * 20 })
        val res = RubyFilter.apply(listOf(lone, body))
        assertTrue(res.demoted.isEmpty())
        assertEquals("line height", res.refused.single().reason)
    }

    // ── neighbour pairing guards (Codex adversarial review, 2026-09-10) ──

    @Test
    fun horizontalLabelBesideVerticalColumn_notPaired() {
        // Paddle: a two-character horizontal label はい (40 x 20) sits just
        // right of a vertical kanji column 40 px wide, overlapping half its
        // height. Paired with the column it would be judged as vertical ruby:
        // "height" 40/40 fails, but "spacing" measured top to bottom is
        // 20 px over 2 chars against the column's 40, ratio 4.0, and on
        // Paddle either measurement suffices. A multi-character candidate
        // must run along its base's reading axis, so the column is not a
        // neighbour and the label stays.
        val column = paddleRegion("決定閉じる", 200, 100, 240, 300, starts = List(5) { 100 + it * 40 }, orientation = TextOrientation.VERTICAL)
        val label = paddleRegion("はい", 250, 150, 290, 170, starts = listOf(250, 270))
        val res = RubyFilter.apply(listOf(column, label))
        assertTrue(res.demoted.isEmpty())
        assertTrue(res.refused.isEmpty())          // no neighbour at all, not a refusal
        // A single square character beside the column IS what vertical ruby
        // looks like, and still pairs (judged on height alone: 40/20).
        val one = paddleRegion("か", 242, 150, 262, 170, starts = listOf(242))
        assertEquals(listOf("か"), demotedTexts(column, one))
    }

    @Test
    fun nearest_isSmallestAbsoluteSeparation() {
        // Two Han lines qualify below a reading: the true base touching it
        // (gap +2) and a detector box that encloses it, overlapping by 30 px.
        // Signed ranking would pick the deeper overlap; nearest means the
        // smallest separation either way.
        val ruby = regionWithChars("ほし", 300, 176, 340, 196, starts = listOf(300, 320), cell = 18)
        val base = regionWithChars("何が出るかは、お楽しみ。", 100, 198, 600, 238, starts = List(12) { 100 + it * 40 }, cell = 36)
        val enclosing = regionWithChars("星を見た", 100, 166, 400, 206, starts = List(4) { 100 + it * 40 }, cell = 36)
        val d = RubyFilter.apply(listOf(ruby, base, enclosing)).demoted.single()
        assertEquals(base, d.base)
        assertEquals(2f / 40f, d.gapEm, 1e-4f)
    }

    @Test
    fun refused_reportsTheNeighbourWithoutHan() {
        val base = region("だがきぼうはまだうしなわれてはいない", 100, 200, 900, 248)
        val cap = region("そしてかなしい…", 100, 165, 400, 195)
        val res = RubyFilter.apply(listOf(base, cap))
        assertEquals(listOf(base, cap), res.kept)
        assertEquals("neighbour has no Han", res.refused.single().reason)
    }

    @Test
    fun rubyStackedBetweenBodyRows_claimsTheRowBelow() {
        // Paragraph with ruby on each line: the reading for line 2 sits
        // between line 1 and line 2 and belongs to line 2 (below it).
        val line1 = region("昔々あるところに", 100, 100, 500, 140)
        val ruby2 = region("おじい", 100, 150, 160, 168)
        val line2 = region("お爺さんがいました", 100, 172, 500, 212)
        val res = RubyFilter.apply(listOf(line1, ruby2, line2))
        assertEquals(listOf(line1, line2), res.kept)
        assertEquals(line2, res.demoted.single().base)
    }
}
