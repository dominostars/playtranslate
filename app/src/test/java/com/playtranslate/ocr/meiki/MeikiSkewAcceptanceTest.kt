package com.playtranslate.ocr.meiki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [MeikiRecognizer.skewRetryAccepted] — the terminal guard of the skew retry.
 * Pins the adversarial-review finding: mean per-char confidence has no length
 * term and the deskew warp is center-pinned, so a WRONG angle estimate can
 * yield a few clean center glyphs whose mean confidence beats a fuller garbled
 * base read; the length-coverage floor must block that fragment while still
 * admitting legitimate recoveries that are slightly shorter than a
 * hallucination-padded base.
 */
@RunWith(RobolectricTestRunner::class)
class MeikiSkewAcceptanceTest {

    private fun res(text: String, conf: Float) =
        MeikiSession.RecResult(text, emptyList(), conf)

    @Test
    fun confidentFragmentIsRejected() {
        // 3 clean pivot glyphs at 0.95 vs a 10-char garbled base at 0.55:
        // without the coverage floor this replaced the fuller text.
        val base = res("ふィホに疾風廣じ。中", 0.55f)
        val retry = res("疾風属", 0.95f)
        assertFalse(MeikiRecognizer.skewRetryAccepted(base, retry))
    }

    @Test
    fun fullRecoveryIsAccepted() {
        val base = res("ふィホに疾風廣じ。", 0.47f)
        val retry = res("敵1体に疾風属性で", 0.88f)
        assertTrue(MeikiRecognizer.skewRetryAccepted(base, retry))
    }

    @Test
    fun slightlyShorterRecoveryIsAccepted() {
        // A correct read can be SHORTER than a hallucination-padded base
        // (device: 'Fromn' -> 'From'); the floor is a ratio, not >=.
        val base = res("Fromn", 0.66f)
        val retry = res("From", 0.68f)
        assertTrue(MeikiRecognizer.skewRetryAccepted(base, retry))
    }

    @Test
    fun confidenceLossIsRejectedRegardlessOfLength() {
        val base = res("日常生活", 0.83f)
        val retry = res("日常生活0%", 0.61f)
        assertFalse(MeikiRecognizer.skewRetryAccepted(base, retry))
    }

    @Test
    fun blankRetryIsRejected() {
        assertFalse(MeikiRecognizer.skewRetryAccepted(res("テキスト", 0.5f), res("", 0.9f)))
    }

    @Test
    fun shortBaseBoundary_ceilNotFloor() {
        // 4-char base: ceil(4 × 0.7) = 3. Flooring gave 2 — a half-length
        // fragment through a guard promising 70% coverage.
        val base = res("メニュー", 0.5f)
        assertFalse(MeikiRecognizer.skewRetryAccepted(base, res("メニ", 0.95f)))
        assertTrue(MeikiRecognizer.skewRetryAccepted(base, res("メニュ", 0.95f)))
    }
}
