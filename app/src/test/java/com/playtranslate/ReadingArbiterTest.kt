package com.playtranslate

import android.graphics.Rect
import com.playtranslate.ReadingArbiter.Preference
import com.playtranslate.ReadingArbiter.Reading
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Decision matrix for [ReadingArbiter] — the "which reading is canonical?"
 * ranking that [ScanlineReconciler] consults when a fresh read is
 * fuzz-same-but-different from the displayed text. TIE must mean "no
 * evidence — status quo", containment pairs must never be ranked, and
 * every verdict must clear a margin (the anti-flip guarantee).
 */
@RunWith(RobolectricTestRunner::class)
class ReadingArbiterTest {

    private fun prefer(
        inc: Reading,
        ch: Reading,
        lang: String? = "ja",
    ) = ReadingArbiter.prefer(inc, ch, lang)

    // ── Guards ────────────────────────────────────────────────────────────

    @Test
    fun identicalTexts_tie() {
        assertEquals(Preference.TIE,
            prefer(Reading("こんにちは", 0.3f, 0.3f), Reading("こんにちは", 0.9f, 0.9f)))
    }

    @Test
    fun containmentPair_isGrowthNotAQualityContest() {
        // The challenger merely extends the incumbent (typewriter step) —
        // confidence must not decide; growth belongs to the gate.
        assertEquals(Preference.TIE,
            prefer(Reading("こんにちは、旅", 0.4f, 0.4f), Reading("こんにちは、旅の", 0.95f, 0.95f)))
        // And the shrink direction likewise.
        assertEquals(Preference.TIE,
            prefer(Reading("こんにちは、旅の", 0.4f, 0.4f), Reading("こんにちは、旅", 0.95f, 0.95f)))
    }

    // ── Tier 1: confidence ───────────────────────────────────────────────

    @Test
    fun minSeparatedByMargin_decides() {
        assertEquals(Preference.CHALLENGER,
            prefer(Reading("こんにちほ", 0.55f, 0.55f), Reading("こんにちは", 0.80f, 0.80f)))
        assertEquals(Preference.INCUMBENT,
            prefer(Reading("こんにちは", 0.80f, 0.80f), Reading("こんにちほ", 0.55f, 0.55f)))
    }

    @Test
    fun minWithinMargin_isNotEvidence() {
        // 0.05 apart — inside CONF_MARGIN, means equal too → TIE (no junk
        // difference either: both clean).
        assertEquals(Preference.TIE,
            prefer(Reading("こんにちほ", 0.75f, 0.80f), Reading("こんにちは", 0.80f, 0.83f)))
    }

    @Test
    fun minTied_meanBreaksIt_oneSuspectLineBeatsTwo() {
        // The (low,hi,hi) vs (hi,lo,lo) case: mins tie at 0.4, means 0.7
        // vs 0.5 — the incumbent's single suspect line wins.
        assertEquals(Preference.INCUMBENT,
            prefer(Reading("こんにちは", 0.4f, 0.7f), Reading("こんにちほ", 0.4f, 0.5f)))
    }

    @Test
    fun unknownConfidence_skipsTier1() {
        // Challenger scored, incumbent unknown → confidence proves nothing
        // (unknown is "no signal", never "low"); both texts clean → TIE.
        assertEquals(Preference.TIE,
            prefer(Reading("こんにちほ", -1f, -1f), Reading("こんにちは", 0.9f, 0.9f)))
    }

    // ── Tier 2: junk ─────────────────────────────────────────────────────

    @Test
    fun junkierIncumbent_losesWithoutConfidence() {
        // Substitution garble: ¦ for t. Bag diff 2 (fuzz-same), junk 1/9
        // vs 0 — the clean read wins with no confidence signal at all.
        assertEquals(Preference.CHALLENGER,
            prefer(Reading("Inven¦ory"), Reading("Inventory"), lang = "en"))
        assertEquals(Preference.INCUMBENT,
            prefer(Reading("Inventory"), Reading("Inven¦ory"), lang = "en"))
    }

    @Test
    fun confidenceOutranksJunk() {
        // Tier 1 decides first: a strongly better-scored junky read wins
        // (the engine saw the pixels; the junk heuristic did not).
        assertEquals(Preference.CHALLENGER,
            prefer(Reading("Inventory", 0.5f, 0.5f), Reading("Inven¦ory", 0.9f, 0.9f), lang = "en"))
    }

    @Test
    fun noLanguage_skipsJunkTier() {
        assertEquals(Preference.TIE,
            prefer(Reading("Inven¦ory"), Reading("Inventory"), lang = null))
    }

    @Test
    fun commonPunctuationIsNotJunk() {
        assertEquals(0f, ReadingArbiter.junkRatio("こんにちは、旅の人よ。", "ja"), 1e-6f)
        assertEquals(0f, ReadingArbiter.junkRatio("Hello, world! (Nr. 5)", "en"), 1e-6f)
    }

    @Test
    fun strayScriptCountsAsJunk() {
        // A stray kana inside French text is garble evidence, not punct.
        val junky = ReadingArbiter.junkRatio("Bonjoうr", "fr")
        assertEquals(1f / 7f, junky, 1e-4f)
    }

    // ── scoreOf ──────────────────────────────────────────────────────────

    private fun grp(vararg conf: Float): OcrManager.OcrGroup {
        val r = Rect(0, 0, 100, 20)
        return OcrManager.OcrGroup(
            text = "x", bounds = r,
            lines = conf.map { OcrManager.LineBox(text = "x", bounds = r, groupIndex = 0, confidence = it) },
        )
    }

    @Test
    fun scoreOf_minAndMean() {
        val (min, mean) = ReadingArbiter.scoreOf(grp(0.9f, 0.6f, 0.9f))
        assertEquals(0.6f, min, 1e-6f)
        assertEquals(0.8f, mean, 1e-6f)
    }

    @Test
    fun scoreOf_anyUnknownLine_poisonsTheAggregate() {
        assertEquals(-1f to -1f, ReadingArbiter.scoreOf(grp(0.9f, -1f, 0.9f)))
        assertEquals(-1f to -1f, ReadingArbiter.scoreOf(null))
    }
}
