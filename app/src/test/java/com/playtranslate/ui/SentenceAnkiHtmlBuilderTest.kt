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

    @Test fun `Sentence furigana ZH with empty words list passes hanzi through plain`() {
        // Defensive: if no WordEntry list is supplied, the ZH path
        // can't annotate anything and just emits source-canonical
        // hanzi. Matches buildSentencePlain semantics.
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

    // ── ZH EXPRESSION furigana brackets (per-hanzi pinyin) ───────────────
    // Mirror of the JA tests above for the Chinese path. CC-CEDICT
    // readings arrive as whitespace-separated tone-marked pinyin
    // (`ChineseDictionaryManager` normalises via PinyinFormatter), so a
    // direct zip with hanzi positions is the natural alignment.

    @Test fun `ZH expression furigana aligns pinyin per hanzi`() {
        // 今天 + "jīn tiān" — clean 2-hanzi-2-syllable alignment. Adjacent
        // hanzi share a single boundary `<wbr>` rather than emitting a
        // doubled `<wbr><wbr>` between them.
        val result = SentenceAnkiHtmlBuilder.buildExpressionFurigana(
            word = "今天", reading = "jīn tiān", sourceLangId = SourceLangId.ZH,
        )
        assertEquals("今[jīn]<wbr>天[tiān]", result)
    }

    @Test fun `ZH_HANT expression furigana also annotates`() {
        // Traditional Chinese routes through the same emitter as
        // simplified — the SourceLangId branch covers both.
        val result = SentenceAnkiHtmlBuilder.buildExpressionFurigana(
            word = "今天", reading = "jīn tiān", sourceLangId = SourceLangId.ZH_HANT,
        )
        assertEquals("今[jīn]<wbr>天[tiān]", result)
    }

    @Test fun `ZH expression furigana falls through to bare word when reading empty`() {
        val result = SentenceAnkiHtmlBuilder.buildExpressionFurigana(
            word = "今天", reading = "", sourceLangId = SourceLangId.ZH,
        )
        assertEquals("今天", result)
    }

    @Test fun `ZH expression furigana falls back to per-word bracket on syllable count mismatch`() {
        // 好玩儿 is érhuà — 3 hanzi but CC-CEDICT gives 2 syllables
        // ("hǎo wánr"). Per-character alignment isn't possible, so emit
        // a single bracket spanning the whole word. Anki's
        // `{{furigana:}}` filter still renders it — just as one ruby
        // block instead of per-hanzi.
        val result = SentenceAnkiHtmlBuilder.buildExpressionFurigana(
            word = "好玩儿", reading = "hǎo wánr", sourceLangId = SourceLangId.ZH,
        )
        assertEquals("好玩儿[hǎo wánr]", result)
    }

    @Test fun `ZH expression furigana passes non-hanzi headwords through unchanged`() {
        val result = SentenceAnkiHtmlBuilder.buildExpressionFurigana(
            word = "hello", reading = "hello", sourceLangId = SourceLangId.ZH,
        )
        assertEquals("hello", result)
    }

    // ── ZH SENTENCE furigana brackets ────────────────────────────────────

    @Test fun `ZH sentence furigana annotates each matched word`() {
        // Greedy-longest-prefix walk over the WordEntry list: at i=0
        // matches 今天; at i=2 matches 天气. Between the two emits we
        // get a doubled `<wbr><wbr>` (each emit's leading + trailing)
        // — functionally identical to single, just slightly noisy in
        // raw HTML. Acceptable; matches the JA convention.
        val words = listOf(
            SentenceAnkiHtmlBuilder.WordEntry(word = "今天", reading = "jīn tiān", meaning = "today"),
            SentenceAnkiHtmlBuilder.WordEntry(word = "天气", reading = "tiān qì", meaning = "weather"),
        )
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            text = "今天天气", words = words, sourceLangId = SourceLangId.ZH,
        )
        assertEquals(
            "今[jīn]<wbr>天[tiān]<wbr><wbr>天[tiān]<wbr>气[qì]",
            result,
        )
    }

    @Test fun `ZH sentence furigana wraps highlighted dict-form in bold`() {
        val words = listOf(
            SentenceAnkiHtmlBuilder.WordEntry(word = "今天", reading = "jīn tiān", meaning = "today"),
            SentenceAnkiHtmlBuilder.WordEntry(word = "天气", reading = "tiān qì", meaning = "weather"),
        )
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            text = "今天天气",
            words = words,
            highlightedWords = setOf("天气"),
            sourceLangId = SourceLangId.ZH,
        )
        // `<b>` opens at the start of 天气, closes after 气. The
        // bracket emit's leading `<wbr>` lands inside `<b>` — same
        // shape as the JA bold test.
        assertEquals(
            "今[jīn]<wbr>天[tiān]<wbr><b><wbr>天[tiān]<wbr>气[qì]<wbr></b>",
            result,
        )
    }

    @Test fun `ZH sentence furigana passes punctuation through plain`() {
        val words = listOf(
            SentenceAnkiHtmlBuilder.WordEntry(word = "今天", reading = "jīn tiān", meaning = "today"),
            SentenceAnkiHtmlBuilder.WordEntry(word = "你好", reading = "nǐ hǎo", meaning = "hello"),
        )
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            text = "今天，你好。", words = words, sourceLangId = SourceLangId.ZH,
        )
        // Full-width punctuation (，。) emits character-by-character;
        // it never matches a WordEntry so it stays bare.
        assertEquals(
            "今[jīn]<wbr>天[tiān]<wbr>，<wbr>你[nǐ]<wbr>好[hǎo]<wbr>。",
            result,
        )
    }

    @Test fun `ZH sentence furigana skips entries with empty reading`() {
        // Defensive: a WordEntry with an empty reading isn't useful for
        // annotation. The filter in buildSentenceFurigana drops it, so
        // the hanzi pass through plain rather than getting a stray
        // `[]` bracket.
        val words = listOf(
            SentenceAnkiHtmlBuilder.WordEntry(word = "今天", reading = "", meaning = "today"),
        )
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            text = "今天", words = words, sourceLangId = SourceLangId.ZH,
        )
        assertEquals("今天", result)
    }

    @Test fun `ZH sentence furigana applies same reading at every occurrence of a surface`() {
        // Pipeline invariant: WordEntries for ZH come from a Map keyed
        // by surface (LastSentenceCache.lookupWords) and the lookup is
        // surface-keyed without context (ChineseDictionaryManager.lookup).
        // So a surface always resolves to one reading, and the walk's
        // `firstOrNull` (offset-agnostic) is safe — both occurrences of
        // 今天 in 今天今天 must get jīn tiān. This test locks in that
        // invariant; if a future change introduces per-position
        // readings (e.g., heteronym disambiguation), the walk needs an
        // offset-indexed token list and this test will need a richer
        // input model.
        val words = listOf(
            SentenceAnkiHtmlBuilder.WordEntry(word = "今天", reading = "jīn tiān", meaning = "today"),
        )
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            text = "今天今天", words = words, sourceLangId = SourceLangId.ZH,
        )
        assertEquals(
            "今[jīn]<wbr>天[tiān]<wbr><wbr>今[jīn]<wbr>天[tiān]",
            result,
        )
    }

    @Test fun `ZH sentence furigana picks longest matching word at each position`() {
        // Defensive: if both 小心地 (3-char adverb) and 小心 (2-char
        // adjective) happen to be in the WordEntry list, longest-first
        // sort must win so 小心地 isn't truncated to 小心 + bare 地.
        val words = listOf(
            SentenceAnkiHtmlBuilder.WordEntry(word = "小心", reading = "xiǎo xīn", meaning = "careful"),
            SentenceAnkiHtmlBuilder.WordEntry(word = "小心地", reading = "xiǎo xīn de", meaning = "carefully"),
        )
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            text = "小心地", words = words, sourceLangId = SourceLangId.ZH,
        )
        assertEquals("小[xiǎo]<wbr>心[xīn]<wbr>地[de]", result)
    }
}
