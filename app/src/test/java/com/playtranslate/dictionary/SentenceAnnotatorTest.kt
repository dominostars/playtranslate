package com.playtranslate.dictionary

import com.playtranslate.dictionary.DictionaryManager.Companion.phraseCandidatesFor
import com.playtranslate.dictionary.DictionaryManager.Companion.reglobSpans
import com.playtranslate.dictionary.SentenceAnnotator.ResolutionKey
import com.playtranslate.dictionary.SentenceAnnotator.WordResolution
import com.playtranslate.language.EntryRef
import com.playtranslate.language.SourceLangId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [SentenceAnnotator]: the raw-stream/re-glob zip, the
 * display-reading policy (ported from the tactical builder override — same
 * semantics, new home), and tiling invariants. Re-glob output comes from the
 * REAL [reglobSpans] over canned tokens; resolutions are injected.
 */
class SentenceAnnotatorTest {

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

    private fun annotate(
        text: String,
        tokens: List<JaToken>,
        knownPhrases: Set<String> = emptySet(),
        knownForms: Set<String> = emptySet(),
        resolutions: Map<ResolutionKey, WordResolution> = emptyMap(),
        full: Boolean = true,
    ) = SentenceAnnotator.annotate(
        text, SourceLangId.JA, tokens,
        reglob = if (full) reglobSpans(tokens, phraseCandidatesFor(tokens), knownPhrases, knownForms) else null,
        resolutions = resolutions,
        importGeneration = 0,
    ).also { ann ->
        // Tiling invariant: spans cover the text exactly, in order.
        var at = 0
        for (s in ann.spans) {
            assertEquals("span start at $at", at, s.start)
            assertEquals(s.surface, text.substring(s.start, s.end))
            at = s.end
        }
        assertEquals(text.length, at)
    }

    private fun pack(id: Long, reading: String) = WordResolution(EntryRef.Pack(id), reading)

    @Test fun `sandhi compound takes the resolved dictionary reading as one part`() {
        pos = 0
        val tokens = listOf(
            tok("一", JaCategory.NOUN, "イチ"), tok("泊", JaCategory.NOUN, "ハク"),
            tok("し", JaCategory.VERB, "シ", dict = "する"), tok("た", JaCategory.AUX, "タ"),
        )
        val ann = annotate(
            "一泊した", tokens, knownPhrases = setOf("一泊"),
            resolutions = mapOf(ResolutionKey("一泊", "いちはく") to pack(1165700, "いっぱく")),
        )
        val span = ann.spans.first { it.surface == "一泊" }
        assertEquals("いっぱく", span.reading)
        assertEquals("いちはく", span.tokenReading)
        assertEquals("一泊", span.word)
        assertEquals(EntryRef.Pack(1165700), span.entryRef)
        assertEquals(listOf("一泊" to "いっぱく"), span.furigana.map { it.text to it.reading })
    }

    @Test fun `single-token span keeps the tokenizer's context reading`() {
        pos = 0
        val tokens = listOf(tok("明日", JaCategory.NOUN, "アシタ"), tok("行く", JaCategory.VERB, "イク"))
        val ann = annotate(
            "明日行く", tokens, knownForms = setOf("明日", "行く"),
            resolutions = mapOf(
                ResolutionKey("明日", "あした") to pack(1, "あす"),
                ResolutionKey("行く", "いく") to pack(2, "いく"),
            ),
        )
        val span = ann.spans.first { it.surface == "明日" }
        assertEquals("あした", span.reading)      // policy: single tokens never override
        assertEquals("明日", span.word)            // ...but the words projection still resolves
    }

    @Test fun `inflected span reads stem plus glue for TTS and splits per token for ruby`() {
        pos = 0
        val tokens = listOf(tok("聞い", JaCategory.VERB, "キイ", dict = "聞く"), tok("た", JaCategory.AUX, "タ"))
        val ann = annotate("聞いた", tokens, knownForms = setOf("聞く"))
        assertEquals(1, ann.spans.size)
        val span = ann.spans[0]
        assertEquals("聞いた", span.surface)
        assertEquals("聞く", span.lookupForm)
        assertEquals("きいた", span.reading)       // glue-inclusive: TTS speaks the whole span
        assertEquals(
            listOf("聞" to "き", "い" to null, "た" to null),
            span.furigana.map { it.text to it.reading },
        )
    }

    @Test fun `discriminated homograph resolves per occurrence`() {
        pos = 0
        val tokens = listOf(
            tok("大", JaCategory.NOUN, "ダイ"), tok("人気", JaCategory.NOUN, "ニンキ"),
            tok("と", JaCategory.PARTICLE, "ト"),
            tok("大人", JaCategory.NOUN, "オトナ"), tok("気", JaCategory.NOUN, "ゲ"),
        )
        val ann = annotate(
            "大人気と大人気", tokens, knownPhrases = setOf("大人気"),
            resolutions = mapOf(
                ResolutionKey("大人気", "だいにんき") to pack(10, "だいにんき"),
                ResolutionKey("大人気", "おとなげ") to pack(11, "おとなげ"),
            ),
        )
        val readings = ann.spans.filter { it.surface == "大人気" }.map { it.reading }
        // Each occurrence carries ITS OWN entry's reading — strictly better
        // than the old all-or-nothing veto.
        assertEquals(listOf("だいにんき", "おとなげ"), readings)
    }

    @Test fun `phrase keeps its glue-overlapped tokens and the fallback span trims`() {
        pos = 0
        val tokens = listOf(
            tok("言わ", JaCategory.VERB, "イワ", dict = "言う"),
            tok("れる", JaCategory.AUX, "レル"),
            tok("か", JaCategory.PARTICLE, "カ"),
            tok("も", JaCategory.PARTICLE, "モ"),
            tok("しれ", JaCategory.VERB, "シレ", dict = "しれる"),
            tok("ない", JaCategory.AUX, "ナイ"),
        )
        val ann = annotate(
            "言われるかもしれない", tokens,
            knownPhrases = setOf("かもしれない"), knownForms = setOf("言う"),
        )
        val surfaces = ann.spans.map { it.surface }
        assertEquals(listOf("言われる", "かもしれない"), surfaces)
        assertEquals("言う", ann.spans[0].lookupForm)
        assertEquals("かもしれない", ann.spans[1].lookupForm)
    }

    @Test fun `text the tokens do not cover becomes plain spans`() {
        pos = 0
        val t1 = tok("今日", JaCategory.NOUN, "キョウ")
        pos += 1 // analyzer skipped the space
        val t2 = tok("は", JaCategory.PARTICLE, "ハ")
        val ann = annotate("今日 は", listOf(t1, t2), knownForms = setOf("今日"))
        assertEquals(listOf("今日", " ", "は"), ann.spans.map { it.surface })
        assertNull(ann.spans[1].reading)
    }

    @Test fun `TOKENS depth yields per-token spans with tokenizer readings`() {
        pos = 0
        val tokens = listOf(
            tok("一", JaCategory.NOUN, "イチ"), tok("泊", JaCategory.NOUN, "ハク"),
        )
        val ann = annotate("一泊", tokens, full = false)
        assertEquals(listOf("一", "泊"), ann.spans.map { it.surface })
        assertEquals(listOf("いち", "はく"), ann.spans.map { it.reading })
        assertTrue(ann.spans.all { it.word == null })
    }

    @Test fun `mixed kanji-kana span never takes the whole-span reading`() {
        pos = 0
        val tokens = listOf(
            tok("泊まり", JaCategory.VERB, "トマリ", dict = "泊まる"),
            tok("込み", JaCategory.NOUN, "コミ", dict = "込む"),
        )
        val ann = annotate(
            "泊まり込み", tokens, knownPhrases = setOf("泊まり込み"),
            resolutions = mapOf(
                ResolutionKey("泊まり込み", "とまりこみ") to pack(20, "とまりこみ"),
            ),
        )
        val span = ann.spans.single()
        assertEquals("とまりこみ", span.tokenReading)
        assertEquals(
            listOf("泊" to "と", "ま" to null, "り" to null, "込" to "こ", "み" to null),
            span.furigana.map { it.text to it.reading },
        )
    }
}
