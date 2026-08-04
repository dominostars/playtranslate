package com.playtranslate.ui

import com.playtranslate.dictionary.JaCategory
import com.playtranslate.dictionary.JaToken
import com.playtranslate.dictionary.JapaneseTokenizer
import com.playtranslate.language.SourceLangId
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [SentenceAnkiHtmlBuilder]'s field-value builders
 * (plain sentence, furigana brackets, words table) and their
 * HTML-escaping contract. Pure JVM — no Android classes needed.
 */
class SentenceAnkiHtmlBuilderTest {

    // Sudachi can't tokenize in a plain JVM test (it needs a pack .dic file),
    // so the furigana-assembly tests inject canned tokens. This validates the
    // builder's <wbr>/bold/offset logic; real tokenization is covered on-device.
    private fun fakeTokenizer(vararg entries: Pair<String, List<JaToken>>): JapaneseTokenizer {
        val map = entries.toMap()
        return object : JapaneseTokenizer {
            override fun analyze(text: String): List<JaToken> = map[text] ?: emptyList()
        }
    }

    private fun jaTok(surface: String, begin: Int, readingKatakana: String) = JaToken(
        surface = surface, begin = begin, end = begin + surface.length,
        category = JaCategory.VERB, dictionaryForm = surface, normalizedForm = surface,
        reading = readingKatakana, isOov = false,
    )

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
            "聞いた", sourceLangId = SourceLangId.JA,
            tokenizer = fakeTokenizer("聞いた" to listOf(jaTok("聞いた", 0, "キイタ"))),
        )
        assertEquals("聞[き]<wbr>いた", result)
    }

    @Test fun `Sentence furigana isolates each kanji in compound verbs`() {
        // 取り出す: per-kanji split with both kanji blocks bordered by
        // `<wbr>` so each tap-popup surfaces just one kanji's reading.
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "取り出す", sourceLangId = SourceLangId.JA,
            tokenizer = fakeTokenizer("取り出す" to listOf(jaTok("取り出す", 0, "トリダス"))),
        )
        assertEquals("取[と]<wbr>り<wbr>出[だ]<wbr>す", result)
    }

    @Test fun `Sentence furigana isolates kanji word from following particle`() {
        // Regression: tapping 友達 in 友達に聞いた used to show ともだちに.
        // The `<wbr>` after each kanji bracket gives Migaku's parser
        // a word boundary so に doesn't get pulled into 友達's popup.
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "友達に聞いた", sourceLangId = SourceLangId.JA,
            tokenizer = fakeTokenizer(
                "友達に聞いた" to listOf(jaTok("友達", 0, "トモダチ"), jaTok("聞いた", 3, "キイタ")),
            ),
        )
        assertEquals("友達[ともだち]<wbr>に<wbr>聞[き]<wbr>いた", result)
    }

    @Test fun `Sentence furigana isolates kanji from trailing kana plus non-CJK suffix`() {
        // Regression: 今度はC was popping up こんどはC because Migaku
        // merged everything from `今度[こんど]` to the next whitespace
        // into one word. The `<wbr>` after the bracket isolates 今度.
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "今度はC", sourceLangId = SourceLangId.JA,
            tokenizer = fakeTokenizer("今度はC" to listOf(jaTok("今度", 0, "コンド"))),
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
            tokenizer = fakeTokenizer(
                "友達に聞いた" to listOf(jaTok("友達", 0, "トモダチ"), jaTok("聞いた", 3, "キイタ")),
            ),
        )
        assertEquals("友達[ともだち]<wbr>に<b><wbr>聞[き]<wbr>いた</b>", result)
    }

    // ── Words-table reading override (number+counter euphony) ────────────
    // Sudachi SplitMode A splits number+counter compounds and per-morpheme
    // readingForm carries no cross-morpheme sandhi (一泊 → 一[いち]泊[はく]).
    // When a words-table word covers a multi-token span uninflected, the
    // bracket takes the word's reading — the same string the words table
    // shows — so the card can't contradict itself.

    @Test fun `Furigana takes words-table reading over multi-token span`() {
        val words = listOf(SentenceAnkiHtmlBuilder.WordEntry(
            word = "一泊", reading = "いっぱく", meaning = "one night's stay",
        ))
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "一泊した", words, sourceLangId = SourceLangId.JA,
            tokenizer = fakeTokenizer("一泊した" to listOf(
                jaTok("一", 0, "イチ"), jaTok("泊", 1, "ハク"),
                jaTok("し", 2, "シ"), jaTok("た", 3, "タ"),
            )),
        )
        assertEquals("一泊[いっぱく]<wbr>した", result)
    }

    @Test fun `Furigana override skips span ending mid-token`() {
        // 一泊 the word must not fire inside 一泊まり (tokens 一|泊まり) —
        // the boundary guard fails open to per-token brackets.
        val words = listOf(SentenceAnkiHtmlBuilder.WordEntry(
            word = "一泊", reading = "いっぱく", meaning = "one night's stay",
        ))
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "一泊まり", words, sourceLangId = SourceLangId.JA,
            tokenizer = fakeTokenizer("一泊まり" to listOf(
                jaTok("一", 0, "イチ"), jaTok("泊まり", 1, "トマリ"),
            )),
        )
        // Adjacent brackets each carry their own <wbr> separators — the
        // doubled <wbr><wbr> between them is pre-existing and zero-width.
        assertEquals("一[いち]<wbr><wbr>泊[と]<wbr>まり", result)
    }

    @Test fun `Furigana override leaves single-token words to the tokenizer`() {
        // Sudachi context-picks single-token readings (明日 as あした); a
        // context-free dictionary row (あす) must not displace them.
        val words = listOf(SentenceAnkiHtmlBuilder.WordEntry(
            word = "明日", reading = "あす", meaning = "tomorrow",
        ))
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "明日行く", words, sourceLangId = SourceLangId.JA,
            tokenizer = fakeTokenizer("明日行く" to listOf(
                jaTok("明日", 0, "アシタ"), jaTok("行く", 2, "イク"),
            )),
        )
        assertEquals("明日[あした]<wbr><wbr>行[い]<wbr>く", result)
    }

    @Test fun `Furigana override skips inflected surfaces`() {
        // A citation reading pasted over an inflected surface would be
        // garbage; surfaceForm != word excludes the entry entirely.
        val words = listOf(SentenceAnkiHtmlBuilder.WordEntry(
            word = "聞く", reading = "きく", meaning = "to hear",
            surfaceForm = "聞いた",
        ))
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "聞いた", words, sourceLangId = SourceLangId.JA,
            tokenizer = fakeTokenizer("聞いた" to listOf(jaTok("聞いた", 0, "キイタ"))),
        )
        assertEquals("聞[き]<wbr>いた", result)
    }

    @Test fun `Furigana override skips mixed kanji-kana words`() {
        // 泊まり込み as one bracket would smear ruby over its kana; the
        // all-kanji guard keeps the anchored per-kanji split.
        val words = listOf(SentenceAnkiHtmlBuilder.WordEntry(
            word = "泊まり込み", reading = "とまりこみ", meaning = "staying over",
        ))
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "泊まり込み", words, sourceLangId = SourceLangId.JA,
            tokenizer = fakeTokenizer("泊まり込み" to listOf(
                jaTok("泊まり", 0, "トマリ"), jaTok("込み", 3, "コミ"),
            )),
        )
        assertEquals("泊[と]<wbr>まり<wbr>込[こ]<wbr>み", result)
    }

    @Test fun `Furigana override applies at every occurrence of a repeated surface`() {
        // The words list carries one reading per written form; stamping it
        // on a repeat is safe exactly when the tokenizer read both
        // occurrences identically.
        val words = listOf(SentenceAnkiHtmlBuilder.WordEntry(
            word = "一泊", reading = "いっぱく", meaning = "one night's stay",
        ))
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "一泊か一泊", words, sourceLangId = SourceLangId.JA,
            tokenizer = fakeTokenizer("一泊か一泊" to listOf(
                jaTok("一", 0, "イチ"), jaTok("泊", 1, "ハク"),
                jaTok("か", 2, "カ"),
                jaTok("一", 3, "イチ"), jaTok("泊", 4, "ハク"),
            )),
        )
        assertEquals("一泊[いっぱく]<wbr>か<wbr>一泊[いっぱく]", result)
    }

    @Test fun `Furigana override is vetoed when the tokenizer discriminates occurrences`() {
        // The same written form read two ways in one sentence: the single
        // cached words-table reading must not erase that distinction, so
        // BOTH occurrences keep their per-token brackets.
        val words = listOf(SentenceAnkiHtmlBuilder.WordEntry(
            word = "大人気", reading = "だいにんき", meaning = "very popular",
        ))
        val text = "大人気と大人気"
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            text, words, sourceLangId = SourceLangId.JA,
            tokenizer = fakeTokenizer(text to listOf(
                jaTok("大", 0, "ダイ"), jaTok("人気", 1, "ニンキ"),
                jaTok("と", 3, "ト"),
                jaTok("大人", 4, "オトナ"), jaTok("気", 6, "ゲ"),
            )),
        )
        assertEquals(
            "大[だい]<wbr><wbr>人気[にんき]<wbr>と<wbr>大人[おとな]<wbr><wbr>気[げ]",
            result,
        )
    }

    @Test fun `Embedded occurrence does not veto the aligned one`() {
        // 一泊 aligned once and embedded in 一泊まり (tokens 一|泊まり):
        // the embedded occurrence can never be overridden, so it must not
        // veto the aligned occurrence's sandhi fix.
        val words = listOf(SentenceAnkiHtmlBuilder.WordEntry(
            word = "一泊", reading = "いっぱく", meaning = "one night's stay",
        ))
        val text = "一泊と一泊まり"
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            text, words, sourceLangId = SourceLangId.JA,
            tokenizer = fakeTokenizer(text to listOf(
                jaTok("一", 0, "イチ"), jaTok("泊", 1, "ハク"),
                jaTok("と", 2, "ト"),
                jaTok("一", 3, "イチ"), jaTok("泊まり", 4, "トマリ"),
            )),
        )
        assertEquals("一泊[いっぱく]<wbr>と<wbr>一[いち]<wbr><wbr>泊[と]<wbr>まり", result)
    }

    @Test fun `Furigana override nests inside highlight bold`() {
        val words = listOf(SentenceAnkiHtmlBuilder.WordEntry(
            word = "一泊", reading = "いっぱく", meaning = "one night's stay",
        ))
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "一泊した", words, highlightedWords = setOf("一泊"),
            sourceLangId = SourceLangId.JA,
            tokenizer = fakeTokenizer("一泊した" to listOf(
                jaTok("一", 0, "イチ"), jaTok("泊", 1, "ハク"),
                jaTok("し", 2, "シ"), jaTok("た", 3, "タ"),
            )),
        )
        assertEquals("<b><wbr>一泊[いっぱく]<wbr></b>した", result)
    }

    @Test fun `Furigana override rides inside word wrapper span`() {
        val words = listOf(SentenceAnkiHtmlBuilder.WordEntry(
            word = "一泊", reading = "いっぱく", meaning = "one night's stay",
        ))
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "一泊した", words, sourceLangId = SourceLangId.JA, wrapWords = true,
            tokenizer = fakeTokenizer("一泊した" to listOf(
                jaTok("一", 0, "イチ"), jaTok("泊", 1, "ハク"),
                jaTok("し", 2, "シ"), jaTok("た", 3, "タ"),
            )),
        )
        assertEquals("<span data-pt-w=\"一泊\">一泊[いっぱく]</span>した", result)
    }

    @Test fun `Expression furigana separates each kanji block with a leading space`() {
        // Expression furigana uses the native leading-space separator (not
        // <wbr>) so {{kana:}} reconstructs the reading for Lapis's pitch. The
        // space before 出 bounds Anki's regex (so り isn't absorbed) and is
        // consumed by the filter. Internal kana (り, す) stay plain.
        val result = SentenceAnkiHtmlBuilder.buildExpressionFurigana(
            word = "取り出す", reading = "とりだす", sourceLangId = SourceLangId.JA,
        )
        assertEquals("取[と]り 出[だ]す", result)
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
        // Leading kanji needs no separator; the okurigana く stays plain.
        assertEquals("聞[き]く", result)
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

    @Test fun `Expression furigana spaces a kanji block that follows okurigana`() {
        // Leading okurigana (お) then 父: the space before 父 bounds the regex
        // and is consumed; {{kana:}} reconstructs おとうさん.
        val result = SentenceAnkiHtmlBuilder.buildExpressionFurigana(
            word = "お父さん", reading = "おとうさん", sourceLangId = SourceLangId.JA,
        )
        assertEquals("お 父[とう]さん", result)
    }

    @Test fun `Expression furigana keeps an all-kanji compound as one block`() {
        // splitFurigana merges adjacent kanji into one block, so no separator
        // is emitted (and none is needed — the bracket itself bounds it).
        val result = SentenceAnkiHtmlBuilder.buildExpressionFurigana(
            word = "日本", reading = "にほん", sourceLangId = SourceLangId.JA,
        )
        assertEquals("日本[にほん]", result)
    }

    @Test fun `Expression furigana falls back to a whole-word bracket when the split drops a mora`() {
        // splitFurigana("可愛い","かわいい") assigns 可愛=かわ and loses the trailing
        // い (it matches the い inside かわい). The completeness guard detects the
        // lossy split and emits one bracket so {{kana:}} still == reading.
        val result = SentenceAnkiHtmlBuilder.buildExpressionFurigana(
            word = "可愛い", reading = "かわいい", sourceLangId = SourceLangId.JA,
        )
        assertEquals("可愛い[かわいい]", result)
    }

    @Test fun `Expression furigana is wbr-free and its kana reconstructs the reading`() {
        // The invariant Lapis's pitch relies on: no <wbr> leaks into the field,
        // and Anki's {{kana:}} over it equals the reading — covering both the
        // <wbr>-leak and the dropped-mora bugs.
        for ((word, reading) in listOf(
            "聞く" to "きく",
            "取り出す" to "とりだす",
            "お父さん" to "おとうさん",
            "日本" to "にほん",
            "可愛い" to "かわいい",
        )) {
            val f = SentenceAnkiHtmlBuilder.buildExpressionFurigana(word, reading, SourceLangId.JA)
            assertFalse("no <wbr> in expression furigana for $word: $f", f.contains("<wbr>"))
            assertEquals("kana of $f must reconstruct $reading", reading, ankiKana(f))
        }
    }

    /** Mirrors Anki's `{{kana:…}}` filter: ` ?([^ >]+?)\[(.+?)\]` → reading. */
    private fun ankiKana(furigana: String): String =
        Regex(" ?([^ >]+?)\\[(.+?)\\]").replace(furigana) { it.groupValues[2] }

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

    // ── HTML escaping (injection regression) ─────────────────────────────
    // Translation backends (especially the on-device LLM) and OCR'd
    // source text can contain `<`, `>`, `&`, or quote characters. These
    // tests pin the contract that every external string flowing into
    // card HTML — translation, source, dictionary glosses, headwords,
    // readings — is escaped before interpolation. Without escaping,
    // AnkiDroid's WebView renders `<script>` payloads live in custom
    // note templates.

    @Test fun `buildSentencePlain escapes HTML metacharacters in non-highlighted text`() {
        val result = SentenceAnkiHtmlBuilder.buildSentencePlain(
            text = "a<script>b", words = emptyList(), highlightedWords = emptySet(),
        )
        assertEquals("a&lt;script&gt;b", result)
    }

    @Test fun `buildSentencePlain escapes metacharacters inside a highlighted bold span`() {
        val result = SentenceAnkiHtmlBuilder.buildSentencePlain(
            text = "<x>", words = emptyList(), highlightedWords = setOf("<x>"),
        )
        assertEquals("<b>&lt;x&gt;</b>", result)
    }

    @Test fun `buildSentenceFurigana escapes metacharacters in non-bracket characters`() {
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "a<b>c", sourceLangId = SourceLangId.JA,
        )
        assertEquals("a&lt;b&gt;c", result)
    }

    @Test fun `buildExpressionFurigana escapes metacharacters in plain headword`() {
        // Empty reading → bare word; must still escape.
        val result = SentenceAnkiHtmlBuilder.buildExpressionFurigana(
            word = "<x>", reading = "", sourceLangId = SourceLangId.JA,
        )
        assertEquals("&lt;x&gt;", result)
    }

    @Test fun `buildExpressionFurigana escapes non-kanji headword on the EN path`() {
        // Non-JA/ZH source — buildExpressionFurigana returns the word verbatim;
        // must still escape.
        val result = SentenceAnkiHtmlBuilder.buildExpressionFurigana(
            word = "<script>", reading = "irrelevant", sourceLangId = SourceLangId.EN,
        )
        assertEquals("&lt;script&gt;", result)
    }

    @Test fun `buildWordsHtmlWith escapes HTML metacharacters in entry fields`() {
        val entries = listOf(
            SentenceAnkiHtmlBuilder.WordEntry(
                word = "<x>", reading = "<r>", meaning = "<m>", freqScore = 0,
            ),
        )
        val result = SentenceAnkiHtmlBuilder.buildWordsHtmlWith(
            entries, highlightedWords = emptySet(), styler = inlineStyler,
        )
        assertTrue(result.contains("&lt;x&gt;"))
        assertTrue(result.contains("&lt;r&gt;"))
        assertTrue(result.contains("&lt;m&gt;"))
        assertFalse(
            "Raw < must not appear in word table HTML: $result",
            result.contains("<x>") || result.contains("<r>") || result.contains("<m>"),
        )
    }

    @Test fun `buildWordsHtmlWith renders pitch only when renderPitch is on`() {
        val entry = SentenceAnkiHtmlBuilder.WordEntry(
            word = "猫", reading = "ねこ", meaning = "cat", freqScore = 0,
            pitch = listOf(1),
        )
        val on = SentenceAnkiHtmlBuilder.buildWordsHtmlWith(
            listOf(entry), emptySet(), inlineStyler, renderPitch = true,
        )
        assertTrue("pitch rendered when on", on.contains("class=\"pa-m"))
        // The structured WORDS_TABLE path uses the default (false) and ships no
        // pitch CSS — it must not emit pa-* markup (Finding B: no leak).
        val off = SentenceAnkiHtmlBuilder.buildWordsHtmlWith(
            listOf(entry), emptySet(), inlineStyler,
        )
        assertFalse("no pitch markup when off (structured path)", off.contains("class=\"pa-m"))
        assertTrue("plain reading when off", off.contains("ねこ"))
    }

    @Test fun `buildWordsHtmlWith renders pitch over a kana-only word`() {
        // Kana-only word in a sentence: blank reading, pitch present → the
        // contour rides on the all-kana word (legacy back only).
        val entry = SentenceAnkiHtmlBuilder.WordEntry(
            word = "なるほど", reading = "", meaning = "I see", freqScore = 0,
            pitch = listOf(0),
        )
        val html = SentenceAnkiHtmlBuilder.buildWordsHtmlWith(
            listOf(entry), emptySet(), inlineStyler, renderPitch = true,
        )
        assertTrue("pitch over kana-only word", html.contains("class=\"pa-m"))
    }

    // ── Words table: v002 sense cells ────────────────────────────────────

    private fun sensedEntry(vararg senses: SenseDisplay) = SentenceAnkiHtmlBuilder.WordEntry(
        word = "封", reading = "ふう", meaning = "1. seal\n2. closing", freqScore = 3,
        frequencies = listOf(com.playtranslate.model.FrequencyTag("JPDB", "3,241")),
        isCommon = true, senses = senses.toList(),
    )

    @Test fun `words table renders every sense with POS header only on change`() {
        val html = SentenceAnkiHtmlBuilder.buildWordsHtmlWith(
            listOf(sensedEntry(
                SenseDisplay(pos = listOf("noun"), definition = "seal", misc = emptyList()),
                SenseDisplay(pos = listOf("noun"), definition = "closing", misc = emptyList()),
                SenseDisplay(pos = listOf("verb"), definition = "to seal", misc = emptyList()),
            )),
            highlightedWords = emptySet(), styler = classStyler,
        )
        // No sense caps: all three render, numbered continuously.
        assertTrue(html.contains(">seal<"))
        assertTrue(html.contains(">closing<"))
        assertTrue(html.contains(">to seal<"))
        assertTrue(html.contains(">1.</span>"))
        assertTrue(html.contains(">3.</span>"))
        // One header for the noun run, one for verb — not one per sense.
        assertEquals(2, Regex("gl-pos-h").findAll(html).count())
        // The flat meaning fallback must NOT also render.
        assertFalse(html.contains("gl-dtext gl-secondary"))
    }

    @Test fun `words table meta row carries pill stars and chips`() {
        val html = SentenceAnkiHtmlBuilder.buildWordsHtmlWith(
            listOf(sensedEntry(SenseDisplay(listOf("noun"), "seal", emptyList()))),
            highlightedWords = setOf("封"), styler = classStyler,
            commonLabel = "Häufig",
        )
        assertTrue("target cell surface", html.contains("class=\"gl-w-target\""))
        assertTrue("localized common pill", html.contains(">Häufig</span>"))
        assertTrue(html.contains(">★★★</span>"))
        assertTrue(html.contains(">JPDB: 3,241</span>"))
    }

    @Test fun `words table imported sense header renders verbatim not localized`() {
        val html = SentenceAnkiHtmlBuilder.buildWordsHtmlWith(
            listOf(sensedEntry(
                SenseDisplay(pos = listOf("Jitendex · n"), definition = "seal",
                    misc = emptyList(), imported = true),
                SenseDisplay(pos = listOf("noun"), definition = "closing", misc = emptyList()),
            )),
            highlightedWords = emptySet(), styler = classStyler,
            localizePos = { "LOCALIZED" },
        )
        assertTrue("imported header verbatim", html.contains(">Jitendex · n</div>"))
        assertTrue("pack POS localized", html.contains(">LOCALIZED</div>"))
        // No Kotlin uppercasing — caps come from the CSS text-transform.
        assertFalse(html.contains("JITENDEX"))
    }

    @Test fun `words table renders misc via the injected renderer`() {
        val html = SentenceAnkiHtmlBuilder.buildWordsHtmlWith(
            listOf(sensedEntry(
                SenseDisplay(listOf("noun"), "seal", misc = listOf("uk", "arch")),
            )),
            highlightedWords = emptySet(), styler = classStyler,
            renderMisc = { it.joinToString("+") },
        )
        assertTrue(html.contains(">uk+arch</div>"))
    }

    @Test fun `words table falls back to meaning lines when senses are empty`() {
        val entry = SentenceAnkiHtmlBuilder.WordEntry(
            word = "封", reading = "ふう", meaning = "1. seal\n2. closing", freqScore = 0,
        )
        val html = SentenceAnkiHtmlBuilder.buildWordsHtmlWith(
            listOf(entry), highlightedWords = emptySet(), styler = classStyler,
        )
        // Lines carry their own baked numbering — no gl-num column.
        assertTrue(html.contains(">1. seal</div>"))
        assertTrue(html.contains(">2. closing</div>"))
        assertFalse(html.contains("gl-num"))
    }

    // ── SentenceFurigana pitch word-wrappers (v002 tooltip) ──────────────

    @Test fun `furigana wraps pitch words in data attributes when enabled`() {
        // Kana-only word: no tokenizer tokens needed (kanji-free tokens are
        // never indexed), the wrapper kana falls back to the all-kana word.
        val words = listOf(SentenceAnkiHtmlBuilder.WordEntry(
            word = "なるほど", reading = "", meaning = "I see", pitch = listOf(0, 2),
        ))
        val html = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            text = "なるほど", words = words, sourceLangId = SourceLangId.JA,
            wrapWords = true, tokenizer = fakeTokenizer(),
        )
        assertEquals(
            "<span data-pt-w=\"なるほど\" data-pt-kana=\"なるほど\"" +
                " data-pt-pitch=\"0,2\">なるほど</span>",
            html,
        )
    }

    @Test fun `furigana wraps pitch-less words with the key only`() {
        val words = listOf(SentenceAnkiHtmlBuilder.WordEntry(
            word = "なるほど", reading = "", meaning = "I see",
        ))
        val html = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            text = "なるほど", words = words, sourceLangId = SourceLangId.JA,
            wrapWords = true, tokenizer = fakeTokenizer(),
        )
        assertEquals("<span data-pt-w=\"なるほど\">なるほど</span>", html)
    }

    @Test fun `furigana word wrapper nests inside the bold highlight`() {
        val words = listOf(SentenceAnkiHtmlBuilder.WordEntry(
            word = "なるほど", reading = "", meaning = "I see", pitch = listOf(0),
        ))
        val html = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            text = "なるほど", words = words, highlightedWords = setOf("なるほど"),
            sourceLangId = SourceLangId.JA, wrapWords = true,
            tokenizer = fakeTokenizer(),
        )
        assertEquals(
            "<b><span data-pt-w=\"なるほど\" data-pt-kana=\"なるほど\"" +
                " data-pt-pitch=\"0\">なるほど</span></b>",
            html,
        )
    }

    @Test fun `furigana emits no wrappers by default (structured path)`() {
        val words = listOf(SentenceAnkiHtmlBuilder.WordEntry(
            word = "なるほど", reading = "", meaning = "I see", pitch = listOf(0),
        ))
        val html = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            text = "なるほど", words = words, sourceLangId = SourceLangId.JA,
            tokenizer = fakeTokenizer(),
        )
        assertEquals("なるほど", html)
        assertFalse(html.contains("data-pt-"))
    }

    @Test fun `words table cells carry the word key for tap-to-scroll`() {
        val html = SentenceAnkiHtmlBuilder.buildWordsHtmlWith(
            listOf(sensedEntry(SenseDisplay(listOf("noun"), "seal", emptyList()))),
            highlightedWords = setOf("封"), styler = classStyler,
        )
        assertTrue(html.contains("<div data-pt-w=\"封\" class=\"gl-w-target\">"))
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
