package com.playtranslate.language

import android.content.Context
import com.hankcs.hanlp.HanLP
import com.playtranslate.dictionary.JaCategory
import com.playtranslate.dictionary.SudachiJapaneseTokenizer
import com.worksap.nlp.sudachi.Tokenizer

/**
 * Per-language [ContentTokenCounter]s for the short-text classifier's unspaced
 * scripts. RAW segmentation only — never `tokenize()`/`annotate()`, which drag
 * in dictionary re-glob / pinyin / SQLite resolution the count doesn't need.
 * Every counter returns null (= no signal → NOT short) when its segmenter is
 * unavailable, so a missing pack degrades to today's all-online behavior.
 *
 * NAME VETO: a text containing a proper noun or an out-of-vocabulary token
 * also counts as null. Names are the class small offline NMT mangles worst
 * (kanji personal names translated semantically, invented katakana names
 * garbled), and speaker nameplates OCR as standalone short groups — so name-
 * bearing shorts go online, where at minimum transliteration heuristics are
 * better and an LLM tier handles them well. The residual (names that are
 * ordinary lexicon words: ひかり, 蛍) translates as its common meaning, which
 * a context-free online call does too.
 */
internal object ShortTextTokenCounters {

    fun forLanguage(ctx: Context, id: SourceLangId): ContentTokenCounter = when (id) {
        // Constructing the engine guarantees Provider.initPackDir has run —
        // its ctor is path computation only, but WITHOUT it a cold service
        // start (live mode from the tile, MainActivity never opened) leaves
        // packDir unset and analyze returns empty forever.
        SourceLangId.JA -> {
            SourceLanguageEngines.get(ctx, id)
            japanese()
        }
        SourceLangId.ZH, SourceLangId.ZH_HANT -> chinese(ctx, id)
        SourceLangId.TH -> thai(ctx)
        // Every other profile is wordsSeparatedByWhitespace — the classifier
        // takes the whitespace branch and never consults the counter.
        else -> ContentTokenCounter { null }
    }

    /** Sudachi mode C (word-like long units — 難易度 is one unit, not 難易+度,
     *  so the ≤2-content-word rule means the same thing it does for spaced
     *  languages). Mode-C over-merge can only UNDERcount, which the char
     *  ceiling bounds. Empty tokens for non-empty text = dict missing → null.
     *
     *  Name veto, three signals (each → null):
     *  - 固有名詞: lexicon names (火村, 美鶴, 真田 — all PROPER in UniDic);
     *  - content-bearing OOV: invented names UniDic has never seen;
     *  - a non-punctuation OTHER token: names UniDic launders through
     *    ordinary morphology — 無達 (the P3 monk "Mutatsu") parses as
     *    無(ADJ_I)+達(denylisted suffix→OTHER), no PROPER, no OOV, and
     *    Bergamot renders it "Untred". A prefix/denylisted-suffix/unknown in
     *    a ≤2-word candidate means the lattice had no clean lexical reading —
     *    exactly the fictional-name signature. Bounded false-veto cost:
     *    prefix-bearing menu items (全回復: 全=OTHER) go online — quota,
     *    never quality. Punctuation-flagged OTHER (！…) is exempt, so はい！
     *    still routes offline; おまかせ fuses to one NOUN under mode C.
     *  Residual (accepted): a fictional name spelled as clean common nouns
     *  (ひかり, 蛍) still routes offline and translates as its common
     *  meaning — parity with a context-free online call. */
    internal fun japanese(): ContentTokenCounter = ContentTokenCounter { text ->
        val tokens = SudachiJapaneseTokenizer.Provider.analyze(text, Tokenizer.SplitMode.C)
        when {
            tokens.isEmpty() -> null
            tokens.any {
                it.isProperNoun ||
                    (it.isOov && it.category.isContent) ||
                    (it.category == JaCategory.OTHER && !it.isPunctuation)
            } -> null
            else -> tokens.count { it.category.isContent && !it.isPunctuation }
        }
    }

    /** HanLP segmentation. The engine MUST be constructed first — its init
     *  installs `HanLP.Config.IOAdapter`; a bare `HanLP.segment` on a process
     *  that never built the engine would read HanLP's default relative paths.
     *  Name veto: nature `nr*` (HanLP's person-name recognizer — nr/nrf/nrj).
     *  Safe against the injected CC-CEDICT custom entries, which are all added
     *  with nature "n" (ChineseDictionaryManager.injectCustomDictEntriesOnce),
     *  so dictionary vocabulary never false-vetoes; place/org names (ns/nt)
     *  are left alone — they translate acceptably. */
    internal fun chinese(ctx: Context, id: SourceLangId): ContentTokenCounter {
        SourceLanguageEngines.get(ctx, id)
        val profile = SourceLanguageProfiles[id]
        return ContentTokenCounter { text ->
            runCatchingNonCancellable { HanLP.segment(text) }
                ?.takeIf { it.isNotEmpty() }
                ?.let { terms ->
                    if (terms.any { it.nature?.toString()?.startsWith("nr") == true }) null
                    else terms.count { term ->
                        term.word?.any { !it.isDigit() && profile.isScriptChar(it) } == true
                    }
                }
        }
    }

    /** Thai newmm trie segmenter. Gated on the TH pack: without it the trie is
     *  empty and the segmenter emits whole non-dictionary runs as ONE segment —
     *  a systematic false "short" (bounded by the char ceiling, but the pack
     *  gate removes the common case). No name signal exists for Thai (the
     *  segmenter has no POS); accepted gap. */
    internal fun thai(ctx: Context): ContentTokenCounter {
        if (!LanguagePackStore.isInstalled(ctx, SourceLangId.TH)) {
            return ContentTokenCounter { null }
        }
        val engine = SourceLanguageEngines.get(ctx, SourceLangId.TH) as? ThaiEngine
            ?: return ContentTokenCounter { null }
        val profile = SourceLanguageProfiles[SourceLangId.TH]
        return ContentTokenCounter { text ->
            runCatchingNonCancellable {
                engine.shortTextSegmenter.segment(text)
                    .count { seg -> seg.any { !it.isDigit() && profile.isScriptChar(it) } }
            }
        }
    }
}
