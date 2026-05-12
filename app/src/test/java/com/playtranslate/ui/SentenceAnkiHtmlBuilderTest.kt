package com.playtranslate.ui

import com.playtranslate.language.SourceLangId
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [SentenceAnkiHtmlBuilder.annotateText] language branching.
 * Pure JVM — no Android classes needed.
 */
class SentenceAnkiHtmlBuilderTest {

    // ── Japanese: ruby + deinflection ────────────────────────────────────

    @Test fun `JA direct match produces ruby tag`() {
        val result = SentenceAnkiHtmlBuilder.annotateText(
            "食べる", mapOf("食べる" to "たべる"),
            newlineAsBr = false, sourceLangId = SourceLangId.JA
        )
        assertTrue("Expected ruby tag", result.contains("<ruby>食べる<rt>たべる</rt></ruby>"))
    }

    @Test fun `JA skips ruby when reading equals word`() {
        val result = SentenceAnkiHtmlBuilder.annotateText(
            "たべる", mapOf("たべる" to "たべる"),
            newlineAsBr = false, sourceLangId = SourceLangId.JA
        )
        assertFalse("Should not have ruby when reading == word", result.contains("<ruby>"))
        assertTrue(result.contains("たべる"))
    }

    @Test fun `JA skips ruby when reading is empty`() {
        val result = SentenceAnkiHtmlBuilder.annotateText(
            "食べる", mapOf("食べる" to ""),
            newlineAsBr = false, sourceLangId = SourceLangId.JA
        )
        assertFalse("Should not have ruby when reading is empty", result.contains("<ruby>"))
    }

    @Test fun `JA deinflection finds conjugated form`() {
        // 食べた is past tense of 食べる — Deinflector should produce 食べる as candidate
        val result = SentenceAnkiHtmlBuilder.annotateText(
            "食べた", mapOf("食べる" to "たべる"),
            newlineAsBr = false, sourceLangId = SourceLangId.JA
        )
        assertTrue("Expected deinflected ruby", result.contains("<ruby>") && result.contains("<rt>"))
    }

    // ── Chinese: ruby (pinyin), no deinflection ──────────────────────────

    @Test fun `ZH direct match produces ruby tag with pinyin`() {
        val result = SentenceAnkiHtmlBuilder.annotateText(
            "今天", mapOf("今天" to "jīn tiān"),
            newlineAsBr = false, sourceLangId = SourceLangId.ZH
        )
        assertTrue("Expected ruby tag", result.contains("<ruby>今天<rt>jīn tiān</rt></ruby>"))
    }

    @Test fun `ZH does not attempt deinflection`() {
        // Even with CJK text that has no direct match, ZH should NOT run Deinflector
        val result = SentenceAnkiHtmlBuilder.annotateText(
            "食べた", mapOf("食べる" to "たべる"),
            newlineAsBr = false, sourceLangId = SourceLangId.ZH
        )
        assertFalse("ZH should not deinflect", result.contains("<ruby>"))
        assertTrue("Should pass through as plain text", result.contains("食べた"))
    }

    // ── English: no ruby, no deinflection ────────────────────────────────

    @Test fun `EN produces plain text, no ruby`() {
        val result = SentenceAnkiHtmlBuilder.annotateText(
            "hello world", mapOf("hello" to ""),
            newlineAsBr = false, sourceLangId = SourceLangId.EN
        )
        assertFalse("EN should not produce ruby", result.contains("<ruby>"))
        assertTrue(result.contains("hello"))
        assertTrue(result.contains("world"))
    }

    @Test fun `EN does not attempt deinflection`() {
        val result = SentenceAnkiHtmlBuilder.annotateText(
            "running fast", mapOf("run" to ""),
            newlineAsBr = false, sourceLangId = SourceLangId.EN
        )
        assertFalse("EN should not deinflect", result.contains("run</"))
        assertTrue("Should pass through as-is", result.contains("running"))
    }

    // ── Spanish: no ruby, no deinflection ────────────────────────────────

    @Test fun `ES produces plain text, no ruby`() {
        val result = SentenceAnkiHtmlBuilder.annotateText(
            "hola mundo", mapOf("hola" to ""),
            newlineAsBr = false, sourceLangId = SourceLangId.ES
        )
        assertFalse("ES should not produce ruby", result.contains("<ruby>"))
        assertTrue(result.contains("hola"))
    }

    // ── Highlighting (all languages) ─────────────────────────────────────

    @Test fun `highlighted word gets bold span`() {
        val result = SentenceAnkiHtmlBuilder.annotateText(
            "食べる", mapOf("食べる" to "たべる"),
            newlineAsBr = false, highlightedWords = setOf("食べる"),
            sourceLangId = SourceLangId.JA
        )
        assertTrue("Expected bold", result.contains("font-weight:800"))
    }

    @Test fun `EN highlighted word gets bold span`() {
        val result = SentenceAnkiHtmlBuilder.annotateText(
            "hello world", mapOf("hello" to ""),
            newlineAsBr = false, highlightedWords = setOf("hello"),
            sourceLangId = SourceLangId.EN
        )
        assertTrue("Expected bold", result.contains("font-weight:800"))
        assertTrue(result.contains("hello"))
    }

    // ── Newline handling ─────────────────────────────────────────────────

    @Test fun `newlineAsBr replaces newlines with br`() {
        val result = SentenceAnkiHtmlBuilder.annotateText(
            "line1\nline2", mapOf("line1" to ""),
            newlineAsBr = true, sourceLangId = SourceLangId.EN
        )
        assertTrue(result.contains("<br>"))
        assertFalse(result.contains("\n"))
    }

    @Test fun `newlineAsBr false replaces newlines with space`() {
        val result = SentenceAnkiHtmlBuilder.annotateText(
            "line1\nline2", mapOf("line1" to ""),
            newlineAsBr = false, sourceLangId = SourceLangId.EN
        )
        assertTrue(result.contains(" "))
        assertFalse(result.contains("\n"))
        assertFalse(result.contains("<br>"))
    }

    // ── SENTENCE (plain) ─────────────────────────────────────────────────
    // Plain Japanese text with `<b>` around each highlighted-word
    // surface form. Mirrors JPMN's `Sentence` authoring convention:
    // raw kanji + `<b>` highlights, no bracket markup.

    @Test fun `Plain sentence wraps highlighted dict-form in bold`() {
        val words = listOf(SentenceAnkiHtmlBuilder.WordEntry(
            word = "聞く", reading = "きく", meaning = "to hear",
            surfaceForm = "聞いた",
        ))
        val result = SentenceAnkiHtmlBuilder.buildSentencePlain(
            "友達に聞いた", words, highlightedWords = setOf("聞く"),
        )
        assertEquals("友達に<b>聞いた</b>", result)
    }

    @Test fun `Plain sentence falls back to dict-form when no surface match`() {
        val result = SentenceAnkiHtmlBuilder.buildSentencePlain(
            "今日はいい天気", words = emptyList(), highlightedWords = setOf("天気"),
        )
        assertEquals("今日はいい<b>天気</b>", result)
    }

    @Test fun `Plain sentence emits raw text when nothing highlighted`() {
        val result = SentenceAnkiHtmlBuilder.buildSentencePlain(
            "友達に聞いた", words = emptyList(), highlightedWords = emptySet(),
        )
        assertEquals("友達に聞いた", result)
    }

    @Test fun `Plain sentence collapses newlines to br`() {
        val result = SentenceAnkiHtmlBuilder.buildSentencePlain(
            "line1\nline2", words = emptyList(), highlightedWords = emptySet(),
        )
        assertEquals("line1<br>line2", result)
    }

    // ── SENTENCE_FURIGANA brackets ───────────────────────────────────────
    // `kanji[reading]` per kanji block; kana stays bare. Anki's
    // `{{furigana:Field}}` filter strips brackets and renders ruby.

    @Test fun `Sentence furigana isolates kanji from its okurigana`() {
        // Tap on 聞 should show just き (the kanji's reading), not きい.
        // Each kanji bracket is wrapped in `<wbr>` separators —
        // invisible word-break opportunities that (a) Anki's furigana
        // regex (` ?([^ >]+?)\[(.+?)\]`) can't span across because of
        // the `>` in the tag, and (b) Migaku's DOM-walking parser
        // should treat as word boundaries. Net effect: each kanji is
        // its own ruby base AND its own Migaku word, with no visible
        // whitespace in the rendered card.
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "聞いた", sourceLangId = SourceLangId.JA
        )
        assertEquals("聞[き]<wbr>いた", result)
    }

    @Test fun `Sentence furigana isolates each kanji in compound verbs`() {
        // 取り出す: per-kanji split with both kanji blocks bordered by
        // `<wbr>` so each tap-popup surfaces just one kanji's reading.
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "取り出す", sourceLangId = SourceLangId.JA
        )
        assertEquals("取[と]<wbr>り<wbr>出[だ]<wbr>す", result)
    }

    @Test fun `Sentence furigana isolates kanji word from following particle`() {
        // Regression: tapping 友達 in 友達に聞いた used to show ともだちに.
        // The `<wbr>` after each kanji bracket gives Migaku's parser
        // a word boundary so に doesn't get pulled into 友達's popup.
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "友達に聞いた", sourceLangId = SourceLangId.JA
        )
        assertEquals("友達[ともだち]<wbr>に<wbr>聞[き]<wbr>いた", result)
    }

    @Test fun `Sentence furigana isolates kanji from trailing kana plus non-CJK suffix`() {
        // Regression: 今度はC was popping up こんどはC because Migaku
        // merged everything from `今度[こんど]` to the next whitespace
        // into one word. The `<wbr>` after the bracket isolates 今度.
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "今度はC", sourceLangId = SourceLangId.JA
        )
        assertEquals("今度[こんど]<wbr>はC", result)
    }

    @Test fun `Sentence furigana preserves newlines as br tags`() {
        // Regression / robustness: the builder must not depend on
        // Kuromoji emitting whitespace as its own token. Multi-line
        // OCR captures need their line breaks preserved as `<br>` on
        // the rendered card — the plain-sentence builder already does
        // this character-by-character; the furigana builder used to
        // rely on Kuromoji's whitespace token behaviour.
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "友達に\n聞いた", sourceLangId = SourceLangId.JA
        )
        assertTrue(
            "Expected newline preserved as <br>; was: $result",
            result.contains("<br>"),
        )
    }

    @Test fun `Sentence furigana preserves literal spaces from source`() {
        // Spaces inside OCR'd Japanese (e.g. line-wrap artefacts) get
        // copied through unchanged — same as the plain builder.
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "今日 は", sourceLangId = SourceLangId.JA
        )
        assertTrue(
            "Expected literal space preserved; was: $result",
            result.contains(" は"),
        )
    }

    @Test fun `Sentence furigana wraps highlighted dict-form in bold`() {
        // Matches JPMN's `<b> 偽者[にせもの]</b>` SentenceReading shape:
        // `<b>` wraps the entire highlighted surface (which may span
        // multiple Kuromoji tokens), including the bracket form and
        // any okurigana. The bracket's leading `<wbr>` lands inside
        // the `<b>` because emit happens after opening the bold — the
        // wbr is invisible and `<b>` itself already serves as a
        // boundary for Anki's regex (its `>` is excluded from the
        // base-text class), so the extra wbr inside is harmless.
        val words = listOf(SentenceAnkiHtmlBuilder.WordEntry(
            word = "聞く", reading = "きく", meaning = "to hear",
            surfaceForm = "聞いた",
        ))
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "友達に聞いた",
            words = words,
            highlightedWords = setOf("聞く"),
            sourceLangId = SourceLangId.JA,
        )
        assertEquals("友達[ともだち]<wbr>に<b><wbr>聞[き]<wbr>いた</b>", result)
    }

    @Test fun `Expression furigana isolates each kanji in compound headword`() {
        // Both kanji blocks in 取り出す render as their own bracket-
        // words separated by `<wbr>` so tapping 取 → と, tapping 出 →
        // だ. The internal kana (り, す) sits as plain text outside
        // any word.
        val result = SentenceAnkiHtmlBuilder.buildExpressionFurigana(
            word = "取り出す", reading = "とりだす", sourceLangId = SourceLangId.JA,
        )
        assertEquals("取[と]<wbr>り<wbr>出[だ]<wbr>す", result)
    }

    @Test fun `Sentence furigana leaves non-JA text untouched`() {
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "今天", sourceLangId = SourceLangId.ZH
        )
        assertEquals("今天", result)
    }

    @Test fun `Sentence furigana leaves pure-kana words bare`() {
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "ありがとう", sourceLangId = SourceLangId.JA
        )
        assertFalse("No brackets expected for pure-kana", result.contains("["))
    }

    // ── EXPRESSION furigana brackets (word-mode) ────────────────────────

    @Test fun `Expression furigana isolates kanji from okurigana`() {
        val result = SentenceAnkiHtmlBuilder.buildExpressionFurigana(
            word = "聞く", reading = "きく", sourceLangId = SourceLangId.JA,
        )
        // Tap on 聞 shows just き — the okurigana く sits outside the
        // bracket-word, separated by `<wbr>`.
        assertEquals("聞[き]<wbr>く", result)
    }

    @Test fun `Expression furigana passes pure-kana headwords through unchanged`() {
        val result = SentenceAnkiHtmlBuilder.buildExpressionFurigana(
            word = "ありがとう", reading = "ありがとう", sourceLangId = SourceLangId.JA,
        )
        assertEquals("ありがとう", result)
    }

    @Test fun `Expression furigana falls through to bare word when reading empty`() {
        val result = SentenceAnkiHtmlBuilder.buildExpressionFurigana(
            word = "聞く", reading = "", sourceLangId = SourceLangId.JA,
        )
        assertEquals("聞く", result)
    }
}
