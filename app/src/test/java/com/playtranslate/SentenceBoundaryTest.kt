package com.playtranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [SentenceBoundary] — terminal detection across the language matrix,
 *  plus the priced-imprecision pins (abbreviations, decimals, interpunct). */
class SentenceBoundaryTest {

    // ── Japanese / CJK ────────────────────────────────────────────────────

    @Test
    fun ja_terminalMarks_end() {
        assertTrue(SentenceBoundary.endsAtBoundary("こんにちは。", "ja"))
        assertTrue(SentenceBoundary.endsAtBoundary("なんだと！？", "ja"))
        assertTrue(SentenceBoundary.endsAtBoundary("そうか…", "ja"))
        assertTrue("closer after terminal belongs to the sentence",
            SentenceBoundary.endsAtBoundary("「行くぞ。」", "ja"))
        assertTrue("trailing whitespace ignored",
            SentenceBoundary.endsAtBoundary("こんにちは。 ", "ja"))
    }

    @Test
    fun ja_midSentence_doesNotEnd() {
        assertFalse(SentenceBoundary.endsAtBoundary("こんにちは、", "ja"))
        assertFalse(SentenceBoundary.endsAtBoundary("私は彼を殺し", "ja"))
        assertFalse("text after the last terminal",
            SentenceBoundary.endsAtBoundary("そうだ。だが", "ja"))
    }

    @Test
    fun ja_interpunct_singleIsAName_runIsEllipsis() {
        assertNull("name separator is not a boundary",
            SentenceBoundary.terminalPrefix("デビッド・スミス", "ja"))
        assertTrue("・・・ is the game-ellipsis idiom",
            SentenceBoundary.endsAtBoundary("そうか・・・", "ja"))
    }

    @Test
    fun ja_interiorBoundary_prefixExtraction() {
        assertEquals("こんにちは、今日は晴れだ。",
            SentenceBoundary.terminalPrefix("こんにちは、今日は晴れだ。それから", "ja"))
        assertEquals("closers ride along",
            "「まだだ。」", SentenceBoundary.terminalPrefix("「まだだ。」それ", "ja"))
        assertNull(SentenceBoundary.terminalPrefix("こんにちは、今日", "ja"))
    }

    // ── ASCII context rules ───────────────────────────────────────────────

    @Test
    fun ascii_terminals_end() {
        assertTrue(SentenceBoundary.endsAtBoundary("Hello world.", "en"))
        assertTrue(SentenceBoundary.endsAtBoundary("It works!", "en"))
        assertTrue(SentenceBoundary.endsAtBoundary("Wait...", "en"))
        assertTrue(SentenceBoundary.endsAtBoundary("\"Go.\"", "en"))
    }

    @Test
    fun ascii_decimal_isNotABoundary() {
        assertNull(SentenceBoundary.terminalPrefix("Level 3.5", "en"))
        assertFalse(SentenceBoundary.endsAtBoundary("v1.2", "en"))
    }

    @Test
    fun ascii_abbreviation_isAcceptedImprecision() {
        // "Mr." reads as a boundary — priced: one extra dispatch at a read
        // Level 0 would also have dispatched, never a wrong hold.
        assertEquals("Mr.", SentenceBoundary.terminalPrefix("Mr. Smith", "en"))
    }

    @Test
    fun ascii_interiorBoundary_prefixExtraction() {
        assertEquals("He left.", SentenceBoundary.terminalPrefix("He left. Then the", "en"))
    }

    // ── Other scripts ─────────────────────────────────────────────────────

    @Test
    fun otherScripts_terminals() {
        assertTrue("danda", SentenceBoundary.endsAtBoundary("नमस्ते।", "hi"))
        assertTrue("arabic question mark", SentenceBoundary.endsAtBoundary("مرحبا؟", "ar"))
        assertTrue(SentenceBoundary.endsAtBoundary("Привет!", "ru"))
    }

    @Test
    fun thai_unsupported() {
        assertFalse(SentenceBoundary.supports("th"))
        assertNull("even ASCII marks stay inert for th",
            SentenceBoundary.terminalPrefix("สวัสดี.", "th"))
        assertFalse(SentenceBoundary.endsAtBoundary("สวัสดี.", "th"))
    }
}
