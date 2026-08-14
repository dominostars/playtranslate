package com.playtranslate.ui

import com.playtranslate.dictionary.DictionaryManager.Companion.phraseCandidatesFor
import com.playtranslate.dictionary.DictionaryManager.Companion.reglobSpans
import com.playtranslate.dictionary.JaCategory
import com.playtranslate.dictionary.JaToken
import com.playtranslate.dictionary.SentenceAnnotator
import com.playtranslate.language.AnnotatedSpan
import com.playtranslate.language.EntryRef
import com.playtranslate.language.SentenceAnnotation
import com.playtranslate.language.SourceLangId
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [SentenceAnkiHtmlBuilder]'s field-value RENDERERS (plain
 * sentence, furigana brackets, words table) and their HTML-escaping
 * contract. Pure JVM — no Android classes needed.
 *
 * Furigana tests drive the REAL annotator (reglobSpans + SentenceAnnotator
 * over canned tokens) and render its annotation — pipeline tests, not
 * fixture theater. Reading-POLICY semantics (override guards, per-occurrence
 * resolution) are pinned in SentenceAnnotatorTest; here we pin the rendered
 * bytes: <wbr> conventions, <b> nesting, data-pt-* wrappers.
 */
class SentenceAnkiHtmlBuilderTest {

    private var pos = 0
    private fun tok(
        surface: String,
        cat: JaCategory,
        readingKatakana: String? = null,
        dict: String = surface,
    ): JaToken {
        val begin = pos; pos += surface.length
        return JaToken(
            surface = surface, begin = begin, end = begin + surface.length,
            category = cat, dictionaryForm = dict, normalizedForm = dict,
            reading = readingKatakana, isOov = false,
        )
    }

    private fun jaAnn(
        text: String,
        tokens: List<JaToken>,
        knownPhrases: Set<String> = emptySet(),
        knownForms: Set<String> = emptySet(),
        resolutions: Map<SentenceAnnotator.ResolutionKey, SentenceAnnotator.WordResolution> = emptyMap(),
    ): SentenceAnnotation = SentenceAnnotator.annotate(
        text, SourceLangId.JA, tokens,
        reglobSpans(tokens, phraseCandidatesFor(tokens), knownPhrases, knownForms),
        resolutions, importGeneration = 0,
    )

    private fun pack(id: Long, reading: String) =
        SentenceAnnotator.WordResolution(EntryRef.Pack(id), reading)

    /** Hand-tiled ZH annotation: [terms] anchored in order, gaps plain —
     *  the shape ChineseEngine.annotate produces (renderer readings come
     *  from the words list, so parts stay empty here). */
    private fun zhAnn(text: String, terms: List<String>): SentenceAnnotation {
        val spans = mutableListOf<AnnotatedSpan>()
        var emitted = 0
        for (t in terms) {
            val at = text.indexOf(t, emitted)
            require(at >= 0) { "term $t not found" }
            if (at > emitted) spans.add(AnnotatedSpan(emitted, at, text.substring(emitted, at)))
            spans.add(AnnotatedSpan(at, at + t.length, t, lookupForm = t))
            emitted = at + t.length
        }
        if (emitted < text.length) spans.add(AnnotatedSpan(emitted, text.length, text.substring(emitted)))
        return SentenceAnnotation(text, SourceLangId.ZH, 0, spans)
    }

    /** Kana-only single-word JA annotation (the pitch-wrapper tests). */
    private fun kanaAnn(): SentenceAnnotation {
        pos = 0
        return jaAnn(
            "なるほど",
            listOf(tok("なるほど", JaCategory.INTERJECTION, "ナルホド")),
            knownForms = setOf("なるほど"),
        )
    }

    // ── SENTENCE (plain) ─────────────────────────────────────────────────

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

    // ── SENTENCE_FURIGANA (JA) — renderer over the real annotator ────────

    @Test fun `Sentence furigana isolates kanji from its okurigana`() {
        pos = 0
        val ann = jaAnn(
            "聞いた",
            listOf(tok("聞い", JaCategory.VERB, "キイ", dict = "聞く"), tok("た", JaCategory.AUX, "タ")),
            knownForms = setOf("聞く"),
        )
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "聞いた", sourceLangId = SourceLangId.JA, annotation = ann,
        )
        assertEquals("聞[き]<wbr>いた", result)
    }

    @Test fun `Sentence furigana isolates each kanji in compound verbs`() {
        pos = 0
        val ann = jaAnn(
            "取り出す",
            listOf(tok("取り出す", JaCategory.VERB, "トリダス")),
            knownForms = setOf("取り出す"),
        )
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "取り出す", sourceLangId = SourceLangId.JA, annotation = ann,
        )
        assertEquals("取[と]<wbr>り<wbr>出[だ]<wbr>す", result)
    }

    @Test fun `Sentence furigana isolates kanji word from following particle`() {
        pos = 0
        val ann = jaAnn(
            "友達に聞いた",
            listOf(
                tok("友達", JaCategory.NOUN, "トモダチ"), tok("に", JaCategory.PARTICLE, "ニ"),
                tok("聞い", JaCategory.VERB, "キイ", dict = "聞く"), tok("た", JaCategory.AUX, "タ"),
            ),
            knownForms = setOf("友達", "聞く"),
        )
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "友達に聞いた", sourceLangId = SourceLangId.JA, annotation = ann,
        )
        assertEquals("友達[ともだち]<wbr>に<wbr>聞[き]<wbr>いた", result)
    }

    @Test fun `Sentence furigana isolates kanji from trailing kana plus non-CJK suffix`() {
        pos = 0
        val ann = jaAnn(
            "今度はC",
            listOf(tok("今度", JaCategory.NOUN, "コンド")),
            knownForms = setOf("今度"),
        )
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "今度はC", sourceLangId = SourceLangId.JA, annotation = ann,
        )
        assertEquals("今度[こんど]<wbr>はC", result)
    }

    @Test fun `Sentence furigana without an annotation degrades to plain with highlights`() {
        // Never-wrong beats sometimes-ruby: no analysis → the plain+<b>
        // form, which {{furigana:}} renders ruby-less but correct.
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "友達に\n聞いた", sourceLangId = SourceLangId.JA,
        )
        assertTrue(result.contains("<br>"))
        assertFalse(result.contains("["))
    }

    @Test fun `Sentence furigana wraps highlighted dict-form in bold`() {
        pos = 0
        val words = listOf(SentenceAnkiHtmlBuilder.WordEntry(
            word = "聞く", reading = "きく", meaning = "to hear",
            surfaceForm = "聞いた",
        ))
        val ann = jaAnn(
            "友達に聞いた",
            listOf(
                tok("友達", JaCategory.NOUN, "トモダチ"), tok("に", JaCategory.PARTICLE, "ニ"),
                tok("聞い", JaCategory.VERB, "キイ", dict = "聞く"), tok("た", JaCategory.AUX, "タ"),
            ),
            knownForms = setOf("友達", "聞く"),
        )
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "友達に聞いた", words = words, highlightedWords = setOf("聞く"),
            sourceLangId = SourceLangId.JA, annotation = ann,
        )
        assertEquals("友達[ともだち]<wbr>に<b><wbr>聞[き]<wbr>いた</b>", result)
    }

    // ── Annotator policy through the renderer (sandhi override et al.) ───
    // Policy guards are pinned in SentenceAnnotatorTest; these pin bytes.

    @Test fun `Furigana takes the annotator's resolved reading over multi-token spans`() {
        pos = 0
        val ann = jaAnn(
            "一泊した",
            listOf(
                tok("一", JaCategory.NOUN, "イチ"), tok("泊", JaCategory.NOUN, "ハク"),
                tok("し", JaCategory.VERB, "シ", dict = "する"), tok("た", JaCategory.AUX, "タ"),
            ),
            knownPhrases = setOf("一泊"),
            resolutions = mapOf(
                SentenceAnnotator.ResolutionKey("一泊", "いちはく") to pack(1165700, "いっぱく"),
            ),
        )
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "一泊した", sourceLangId = SourceLangId.JA, annotation = ann,
        )
        assertEquals("一泊[いっぱく]<wbr>した", result)
    }

    @Test fun `Furigana renders each occurrence with its own resolved reading`() {
        // Per-occurrence resolution replaced the old all-or-nothing veto:
        // a homograph the tokenizer read two ways gets BOTH readings right.
        pos = 0
        val ann = jaAnn(
            "大人気と大人気",
            listOf(
                tok("大", JaCategory.NOUN, "ダイ"), tok("人気", JaCategory.NOUN, "ニンキ"),
                tok("と", JaCategory.PARTICLE, "ト"),
                tok("大人", JaCategory.NOUN, "オトナ"), tok("気", JaCategory.NOUN, "ゲ"),
            ),
            knownPhrases = setOf("大人気"),
            resolutions = mapOf(
                SentenceAnnotator.ResolutionKey("大人気", "だいにんき") to pack(10, "だいにんき"),
                SentenceAnnotator.ResolutionKey("大人気", "おとなげ") to pack(11, "おとなげ"),
            ),
        )
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "大人気と大人気", sourceLangId = SourceLangId.JA, annotation = ann,
        )
        assertEquals("大人気[だいにんき]<wbr>と<wbr>大人気[おとなげ]", result)
    }

    @Test fun `Furigana renders inflected spans per token`() {
        pos = 0
        val words = listOf(SentenceAnkiHtmlBuilder.WordEntry(
            word = "聞く", reading = "きく", meaning = "to hear", surfaceForm = "聞いた",
        ))
        val ann = jaAnn(
            "聞いた",
            listOf(tok("聞い", JaCategory.VERB, "キイ", dict = "聞く"), tok("た", JaCategory.AUX, "タ")),
            knownForms = setOf("聞く"),
            resolutions = mapOf(
                SentenceAnnotator.ResolutionKey("聞く", "きい") to pack(2, "きく"),
            ),
        )
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "聞いた", words = words, sourceLangId = SourceLangId.JA, annotation = ann,
        )
        assertEquals("聞[き]<wbr>いた", result)
    }

    @Test fun `Furigana renders mixed kanji-kana spans per token`() {
        pos = 0
        val ann = jaAnn(
            "泊まり込み",
            listOf(
                tok("泊まり", JaCategory.VERB, "トマリ", dict = "泊まる"),
                tok("込み", JaCategory.NOUN, "コミ", dict = "込む"),
            ),
            knownPhrases = setOf("泊まり込み"),
            resolutions = mapOf(
                SentenceAnnotator.ResolutionKey("泊まり込み", "とまりこみ") to pack(20, "とまりこみ"),
            ),
        )
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "泊まり込み", sourceLangId = SourceLangId.JA, annotation = ann,
        )
        assertEquals("泊[と]<wbr>まり<wbr>込[こ]<wbr>み", result)
    }

    @Test fun `Furigana override nests inside highlight bold`() {
        pos = 0
        val words = listOf(SentenceAnkiHtmlBuilder.WordEntry(
            word = "一泊", reading = "いっぱく", meaning = "one night's stay",
        ))
        val ann = jaAnn(
            "一泊した",
            listOf(
                tok("一", JaCategory.NOUN, "イチ"), tok("泊", JaCategory.NOUN, "ハク"),
                tok("し", JaCategory.VERB, "シ", dict = "する"), tok("た", JaCategory.AUX, "タ"),
            ),
            knownPhrases = setOf("一泊"),
            resolutions = mapOf(
                SentenceAnnotator.ResolutionKey("一泊", "いちはく") to pack(1165700, "いっぱく"),
            ),
        )
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "一泊した", words = words, highlightedWords = setOf("一泊"),
            sourceLangId = SourceLangId.JA, annotation = ann,
        )
        assertEquals("<b><wbr>一泊[いっぱく]<wbr></b>した", result)
    }

    @Test fun `Furigana override rides inside word wrapper span`() {
        pos = 0
        val words = listOf(SentenceAnkiHtmlBuilder.WordEntry(
            word = "一泊", reading = "いっぱく", meaning = "one night's stay",
        ))
        val ann = jaAnn(
            "一泊した",
            listOf(
                tok("一", JaCategory.NOUN, "イチ"), tok("泊", JaCategory.NOUN, "ハク"),
                tok("し", JaCategory.VERB, "シ", dict = "する"), tok("た", JaCategory.AUX, "タ"),
            ),
            knownPhrases = setOf("一泊"),
            resolutions = mapOf(
                SentenceAnnotator.ResolutionKey("一泊", "いちはく") to pack(1165700, "いっぱく"),
            ),
        )
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            "一泊した", words = words, sourceLangId = SourceLangId.JA,
            wrapWords = true, annotation = ann,
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
            annotation = zhAnn("今天天气", listOf("今天", "天气")),
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
            annotation = zhAnn("今天天气", listOf("今天", "天气")),
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
            annotation = zhAnn("今天，你好。", listOf("今天", "你好")),
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
            annotation = zhAnn("今天", listOf("今天")),
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
            annotation = zhAnn("今天今天", listOf("今天", "今天")),
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
            wrapWords = true, annotation = kanaAnn(),
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
            wrapWords = true, annotation = kanaAnn(),
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
            annotation = kanaAnn(),
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
            annotation = kanaAnn(),
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

    @Test fun `ZH sentence furigana renders the annotation's segmentation`() {
        // Segmentation is the ANNOTATOR's decision now: the span for
        // 小心地 matches its own entry exactly by lookup form — there is
        // no longest-prefix text scan left to truncate it to 小心 + 地.
        val words = listOf(
            SentenceAnkiHtmlBuilder.WordEntry(word = "小心", reading = "xiǎo xīn", meaning = "careful"),
            SentenceAnkiHtmlBuilder.WordEntry(word = "小心地", reading = "xiǎo xīn de", meaning = "carefully"),
        )
        val result = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            text = "小心地", words = words, sourceLangId = SourceLangId.ZH,
            annotation = zhAnn("小心地", listOf("小心地")),
        )
        assertEquals("小[xiǎo]<wbr>心[xīn]<wbr>地[de]", result)
    }

    // ── Structured glossaries in the words table (v005) ─────────────────

    @Test
    fun `structured senses render as gl-sc blocks, others stay flat`() {
        val words = listOf(
            SentenceAnkiHtmlBuilder.WordEntry(
                word = "猫", reading = "ねこ", meaning = "cat",
                senses = listOf(
                    SenseDisplay(
                        pos = listOf("Gauntlet"), definition = "flat mash; text",
                        misc = emptyList(), imported = true,
                        scRowid = 7L, dictId = "d1",
                    ),
                    SenseDisplay(
                        pos = listOf("Other"), definition = "plain def",
                        misc = emptyList(), imported = true,
                    ),
                ),
            ),
        )
        val html = SentenceAnkiHtmlBuilder.buildWordsHtmlWith(
            words, highlightedWords = setOf("猫"),
            styler = { cls, extra -> "class=\"$cls\" style=\"$extra\"" },
            structuredGlossaries = mapOf(
                7L to """[{"type":"structured-content","content":
                    {"tag":"ul","content":[{"tag":"li","content":"cat"}]}}]""",
            ),
        )
        // Sense with a retained glossary: structured block, scoped.
        assertTrue(html.contains("data-dictionary=\"d1\""))
        assertTrue(html.contains("<li class=\"gloss-sc-li\">cat</li>"))
        // No dictStyles passed: no style blocks.
        assertTrue(!html.contains("<style>"))
        // Sense without: today's flat row.
        assertTrue(html.contains("plain def"))
        // The structured sense's flat text must NOT also render (no double).
        assertTrue(!html.contains("flat mash"))
    }

    @Test
    fun `tier 2 - rendering dictionaries ship their scoped css inline`() {
        val words = listOf(
            SentenceAnkiHtmlBuilder.WordEntry(
                word = "猫", reading = "ねこ", meaning = "cat",
                senses = listOf(
                    SenseDisplay(
                        pos = listOf("Gauntlet"), definition = "cat",
                        misc = emptyList(), imported = true, scRowid = 7L, dictId = "d1",
                    ),
                ),
            ),
        )
        val html = SentenceAnkiHtmlBuilder.buildWordsHtmlWith(
            words, highlightedWords = emptySet(),
            styler = { cls, _ -> "class=\"$cls\"" },
            structuredGlossaries = mapOf(
                7L to """[{"type":"structured-content","content":{"tag":"span","content":"cat"}}]""",
            ),
            dictStyles = mapOf(
                "d1" to "span[data-sc-class=\"tag\"] { color: red }",
                "unused" to "div { color: blue }",
            ),
        )
        // The rendering dictionary's CSS, scoped, ahead of the table…
        assertTrue(html.contains("<style>.gl-sc[data-dictionary=\"d1\"] span[data-sc-class=\"tag\"]"))
        // …and only for dictionaries that actually rendered.
        assertTrue(!html.contains("color: blue"))
    }

    @Test
    fun `empty structured map keeps the flat rendering byte-for-byte`() {
        val words = listOf(
            SentenceAnkiHtmlBuilder.WordEntry(
                word = "猫", reading = "ねこ", meaning = "cat",
                senses = listOf(
                    SenseDisplay(
                        pos = listOf("Gauntlet"), definition = "line1\nline2",
                        misc = emptyList(), imported = true, scRowid = 7L, dictId = "d1",
                    ),
                ),
            ),
        )
        val html = SentenceAnkiHtmlBuilder.buildWordsHtmlWith(
            words, highlightedWords = emptySet(),
            styler = { cls, _ -> "class=\"$cls\"" },
        )
        assertTrue(html.contains("line1<br>line2"))
        assertTrue(!html.contains("gl-sc"))
    }
}
