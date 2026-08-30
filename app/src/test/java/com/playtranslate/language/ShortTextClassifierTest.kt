package com.playtranslate.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins [isShortText]'s rule set: ≤2 content words routes to the fast offline
 * tier. Counters are stubbed — the per-language segmentation contracts live in
 * their own tests; this one pins the classifier's branching, the content-char
 * predicate, the fold, and the char ceiling.
 */
class ShortTextClassifierTest {

    private val en = SourceLanguageProfiles[SourceLangId.EN]
    private val ja = SourceLanguageProfiles[SourceLangId.JA]
    private val ko = SourceLanguageProfiles[SourceLangId.KO]
    private val th = SourceLanguageProfiles[SourceLangId.TH]

    /** For spaced-language tests, where the counter must never be consulted. */
    private val neverCalled = ContentTokenCounter { fail("counter consulted on a spaced language"); 0 }

    @Test fun `one and two content words are short, three are not`() {
        assertTrue(isShortText("Attack", en, neverCalled))
        assertTrue(isShortText("New Game", en, neverCalled))
        assertFalse(isShortText("Save your game here", en, neverCalled))
    }

    @Test fun `digits are never content - Level 5 is one content word`() {
        assertTrue(isShortText("Level 5", en, neverCalled))
    }

    @Test fun `zero content chars is not short`() {
        assertFalse(isShortText("$12.50", en, neverCalled))
        assertFalse(isShortText("12:34", en, neverCalled))
        assertFalse(isShortText("", en, neverCalled))
        assertFalse(isShortText("   ", en, neverCalled))
    }

    @Test fun `char ceiling wins even when the counter claims one word`() {
        val text = "あ".repeat(SHORT_TEXT_MAX_CONTENT_CHARS + 1)
        assertFalse(isShortText(text, ja, ContentTokenCounter { 1 }))
    }

    @Test fun `counter null means no signal - not short`() {
        assertFalse(isShortText("こんにちは", ja, ContentTokenCounter { null }))
    }

    @Test fun `counter zero is not short`() {
        assertFalse(isShortText("こんにちは", ja, ContentTokenCounter { 0 }))
    }

    @Test fun `counter one and two are short, three is not`() {
        assertTrue(isShortText("こんにちは", ja, ContentTokenCounter { 1 }))
        assertTrue(isShortText("電源ボタン", ja, ContentTokenCounter { 2 }))
        assertFalse(isShortText("難易度を選択して", ja, ContentTokenCounter { 3 }))
    }

    @Test fun `fullwidth text folds before counting`() {
        assertTrue(isShortText("ＭＥＮＵ", en, neverCalled))
    }

    @Test fun `counter receives NFKC text with small kana INTACT`() {
        // Regression pin (review find): LogWriteGate.fold's small-kana fold
        // (っ→つ, ょ→よ) is comparison-only and destroys the morphology Sudachi
        // needs — the classifier must NFKC-normalize but never kana-fold.
        var seen: String? = null
        isShortText("ちょっと", ja, ContentTokenCounter { seen = it; 1 })
        assertEquals("ちょっと", seen)
        // Half-width katakana still normalizes (NFKC's actual job here).
        isShortText("ﾒﾆｭｰ", ja, ContentTokenCounter { seen = it; 1 })
        assertEquals("メニュー", seen)
    }

    @Test fun `terminal punctuation does not exclude a short`() {
        // Settled decision: punctuated shorts route offline too — the output
        // is a translation either way (はい！ → "Yes!").
        assertTrue(isShortText("Salida.", en, neverCalled))
        assertTrue(isShortText("はい！", ja, ContentTokenCounter { 1 }))
    }

    @Test fun `korean takes the whitespace branch`() {
        assertTrue(isShortText("새 게임", ko, neverCalled))
        assertFalse(isShortText("여기에서 게임을 저장합니다", ko, neverCalled))
    }

    @Test fun `thai takes the counter branch`() {
        var consulted = false
        assertTrue(isShortText("บันทึก", th, ContentTokenCounter { consulted = true; 1 }))
        assertTrue(consulted)
    }
}
