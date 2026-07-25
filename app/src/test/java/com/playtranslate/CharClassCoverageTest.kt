package com.playtranslate

import com.playtranslate.ocr.core.CharClassCoverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port-parity tests for [CharClassCoverage]: every case here mirrors a fixture
 * in `scripts/char_norm_report.py --selftest` (the host-side twin the corpus
 * numbers came from), so a value drifting between the two implementations
 * fails loudly rather than silently invalidating the measurement.
 */
class CharClassCoverageTest {

    private fun cov(text: String): Double? = CharClassCoverage.lineCoverage(text)

    @Test
    fun descenderLineSpansFull() {
        // 'sample': l ascends (0.80), p descends (0.22) → full 1.02 span.
        assertEquals(1.02, cov("sample")!!, 1e-9)
    }

    @Test
    fun ascenderOnlyLine() {
        // 'for translation': f/l ascend, nothing descends.
        assertEquals(0.80, cov("for translation")!!, 1e-9)
    }

    @Test
    fun xHeightOnlyLine() {
        assertEquals(0.575, cov("nano")!!, 1e-9)
    }

    @Test
    fun capsOnlyLine() {
        assertEquals(0.755, cov("DECK")!!, 1e-9)
    }

    @Test
    fun accentRaisesTop() {
        assertTrue(cov("é")!! > cov("e")!!)
    }

    @Test
    fun dakutenStaysInsideEmBox() {
        // NFD splits だ into た+゙; the voicing mark must not stretch coverage —
        // treating it as an accent manufactured rescues for JA name-tag pairs.
        assertEquals(1.0, cov("だけど")!!, 1e-9)
    }

    @Test
    fun vietnameseHornStaysInside() {
        assertEquals(0.575, cov("ư")!!, 1e-9)
    }

    @Test
    fun vietnameseDStrokeMaps() {
        assertEquals(1.02, cov("đường")!!, 1e-9)
    }

    @Test
    fun cyrillicDescender() {
        // 'скорее': р descends, no ascenders.
        assertEquals(0.795, cov("скорее")!!, 1e-9)
    }

    @Test
    fun cyrillicPrecomposedBreveDecomposes() {
        // й arrives composed from OCR; NFD strips to и + breve (above-mark).
        assertEquals(0.795, cov("й")!!, 1e-9)
    }

    @Test
    fun cjkEmBox() {
        assertEquals(1.0, cov("鳴潮")!!, 1e-9)
    }

    @Test
    fun unmappedCharIneligible() {
        assertNull(cov("a★b"))
    }

    @Test
    fun thinOnlyLineIneligible() {
        assertNull(cov("ーーー"))
        assertNull(cov("..."))
    }

    @Test
    fun emptyIneligible() {
        assertNull(cov(""))
        assertNull(cov("   "))
    }

    @Test
    fun unknownMarkIneligible() {
        // Thai above-vowel: unmapped script must fall back, not mispredict.
        assertNull(cov("กิ"))
    }

    @Test
    fun verticalFullWidthColumn() {
        assertEquals(1.0, CharClassCoverage.columnCoverage("今日の移動は")!!, 1e-9)
    }

    @Test
    fun verticalLatinIneligible() {
        assertNull(CharClassCoverage.columnCoverage("abc"))
    }

    @Test
    fun verticalThinOnlyIneligible() {
        assertNull(CharClassCoverage.columnCoverage("ー、"))
    }

    @Test
    fun textSamplePairNormalizesFlat() {
        // The canonical wrong block: h=157 (descender line) vs h=120 (ascender
        // line) is raw delta 0.31; divided by predicted coverages the font-size
        // estimates agree within a few percent.
        val a = 157 / cov("This is a text sample")!!
        val b = 120 / cov("for translation")!!
        val delta = (maxOf(a, b) - minOf(a, b)) / minOf(a, b)
        assertTrue("normalized delta $delta should be < 0.30", delta < 0.30)
    }

    @Test
    fun realScaleDifferenceSurvivesNormalization() {
        // A genuine 1.45× Title-Case heading: content classes are near-equal,
        // so normalization must NOT explain the difference away.
        val a = 145 / cov("Item Name Here")!!
        val b = 100 / cov("item body here")!!
        val delta = (maxOf(a, b) - minOf(a, b)) / minOf(a, b)
        assertTrue("normalized delta $delta should stay > 0.30", delta > 0.30)
    }
}
