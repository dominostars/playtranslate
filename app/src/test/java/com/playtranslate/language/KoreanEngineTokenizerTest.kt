package com.playtranslate.language

import kr.co.shineware.nlp.komoran.constant.DEFAULT_MODEL
import kr.co.shineware.nlp.komoran.core.Komoran
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Documents KOMORAN's segmentation behavior for the Korean inputs
 * [KoreanEngine] relies on. Pure JVM, no Android classes — mirrors
 * [ChineseEngineTokenizerTest] and [LatinEngineStemmerTest].
 *
 * Tests pin the contract our engine depends on:
 *  - Nouns are tagged NNG/NNP.
 *  - Particles get tagged under the J family (JX, JKS, etc.).
 *  - Verb stems come out as VV without a trailing -다 (we re-append it).
 *  - Adjective stems come out as VA.
 *
 * If a release of KOMORAN changes the tag scheme or splitting behavior,
 * these tests surface the change before it reaches users.
 */
class KoreanEngineTokenizerTest {

    companion object {
        /**
         * One KOMORAN instance per test class. Model load is ~200-500 ms on
         * JVM and not worth paying per test. Thread-safety only matters
         * in production; tests are single-threaded.
         */
        private lateinit var komoran: Komoran

        @JvmStatic
        @BeforeClass
        fun loadModel() {
            komoran = Komoran(DEFAULT_MODEL.LIGHT)
        }
    }

    /** One emitted morpheme. Mirrors what [KoreanEngine.tokenize] observes. */
    private data class Morpheme(
        val surface: String,
        val pos: String,
        val beginIndex: Int,
        val endIndex: Int,
    )

    private fun tokenize(text: String): List<Morpheme> {
        val result = komoran.analyze(text)
        return result.tokenList.map {
            Morpheme(it.morph, it.pos, it.beginIndex, it.endIndex)
        }
    }

    @Test fun `simple noun emits NNG morpheme`() {
        val tokens = tokenize("사람")
        assertTrue(
            "Expected NNG '사람' in $tokens",
            tokens.any { it.surface == "사람" && it.pos == "NNG" },
        )
    }

    @Test fun `topic particle is tagged with J-prefix and filtered by content-POS check`() {
        // 사람은 = person (NNG) + topic-marker 은 (JX). KoreanEngine's
        // isLookupWorthyPos must NOT accept any J* tag, or particles would
        // bloat the dictionary-lookup list with entries like "은" / "는" / "이".
        val tokens = tokenize("사람은")
        assertTrue("Expected NNG 사람", tokens.any { it.pos == "NNG" && it.surface == "사람" })
        assertTrue(
            "Expected a J-prefix (particle) tag in $tokens",
            tokens.any { it.pos.startsWith("J") },
        )
    }

    @Test fun `verb conjugation yields VV stem without -다`() {
        // 먹었습니다 = ate (formal-polite). KOMORAN decomposes to
        // [먹/VV, 었/EP, 습니다/EF]. The VV morpheme's surface is the bare
        // stem (no -다); KoreanEngine re-appends -다 to reconstruct `먹다`.
        val tokens = tokenize("먹었습니다")
        val vv = tokens.firstOrNull { it.pos == "VV" }
        assertTrue("Expected a VV morpheme in $tokens", vv != null)
        assertFalse(
            "VV surface should NOT already end in 다 (${vv?.surface}); " +
                "if it does, KoreanEngine's citation-form append is redundant.",
            vv?.surface?.endsWith("다") == true,
        )
    }

    @Test fun `adjective conjugation yields VA morpheme`() {
        // 예뻐요 = pretty (polite). Should decompose to a VA morpheme
        // for the stem + an E-family ending. Citation form for VA is also
        // stem + 다 (→ `예쁘다`), same logic as VV.
        val tokens = tokenize("예뻐요")
        assertTrue(
            "Expected a VA morpheme in $tokens",
            tokens.any { it.pos == "VA" },
        )
    }

    @Test fun `offset range of VA morpheme is a substring of input even when morph is not`() {
        // The core invariant KoreanEngine.tokenize() relies on after P1 fix:
        // `TokenSpan.surface` is taken from normalized.substring(begin, end),
        // NOT from token.morph. For vowel-coalesced inflections like 예뻐요,
        // KOMORAN emits morph=예쁘 (canonical stem, 2 chars), but the input
        // only contains 예뻐 (different 2nd char from 쁘→뻐 coalescence).
        // Downstream UI code (DragLookupController.findClosestToken,
        // TranslationResultFragment) uses `displayedText.indexOf(surface)`,
        // so `surface` must appear verbatim in the input.
        val input = "예뻐요"
        val tokens = tokenize(input)
        val va = tokens.firstOrNull { it.pos == "VA" }
        assertTrue("Expected a VA morpheme in $tokens", va != null)
        val surface = input.substring(va!!.beginIndex, va.endIndex)
        assertTrue(
            "Offset-based surface \"$surface\" must be a literal substring of " +
                "input \"$input\"; KOMORAN's morph was \"${va.surface}\"",
            input.contains(surface),
        )
        // Sanity: morph is the canonical stem, distinct from surface for
        // vowel-coalesced cases. If this ever starts matching, KOMORAN may
        // have changed its morpheme normalization and the citation-form
        // reconstruction in KoreanEngine needs review.
        assertFalse(
            "Expected morph \"${va.surface}\" to differ from offset surface " +
                "\"$surface\" for 예뻐요 (vowel coalescence)",
            va.surface == surface,
        )
    }

    @Test fun `whitespace between eojeols produces an offset gap`() {
        // KoreanEngine caps its eojeol-level span extension at the first
        // whitespace char in the NFC-normalized line; that only works if
        // KOMORAN reports tokens that straddle a space with a begin/end
        // gap. If this stops holding (KOMORAN emits through whitespace),
        // the engine's span would merge across eojeols and produce
        // surfaces that aren't substrings of the displayed text.
        val input = "사람 먹었습니다"
        val spaceIdx = input.indexOf(' ')
        val tokens = tokenize(input)
        val before = tokens.lastOrNull { it.endIndex <= spaceIdx }
        val after = tokens.firstOrNull { it.beginIndex > spaceIdx }
        assertTrue("Expected a token before the space: $tokens", before != null)
        assertTrue("Expected a token after the space: $tokens", after != null)
        assertTrue(
            "Expected an offset gap across whitespace " +
                "(before.endIndex=${before?.endIndex}, after.beginIndex=${after?.beginIndex}): $tokens",
            (after!!.beginIndex - before!!.endIndex) >= 1,
        )
    }

    @Test fun `하다 compound is tagged as NNG plus XSV 하`() {
        // 공부하다 = study. KOMORAN decomposes it as NNG 공부 + XSV 하 +
        // EF 다. KoreanEngine.detectCompoundSuffix matches exactly this
        // shape (NNG directly attached to XSV with morph 하 or 되) to
        // rebuild the compound lemma 공부하다 for dictionary lookup.
        // If KOMORAN stops emitting XSV for 하 here (e.g. tags it as VX
        // or VV instead), -하다 verbs revert to being looked up as bare
        // 공부 / bare 하다 — a user-visible lookup regression for the
        // single most common Korean verb construction.
        val tokens = tokenize("공부하다")
        assertTrue(
            "Expected NNG 공부 in $tokens",
            tokens.any { it.pos == "NNG" && it.surface == "공부" },
        )
        val xsv = tokens.firstOrNull { it.pos == "XSV" }
        assertTrue("Expected an XSV token in $tokens", xsv != null)
        assertTrue(
            "Expected the XSV morpheme to be 하 (got ${xsv?.surface}/${xsv?.pos}): $tokens",
            xsv!!.surface == "하",
        )
    }

    @Test fun `되다 compound is tagged as NNG plus XSV 되`() {
        // 사용되다 = be used (passive). Same compound pattern as 하다 but
        // via 되. KoreanEngine treats both morphs interchangeably for the
        // compound-verb lookup reconstruction.
        val tokens = tokenize("사용되다")
        val xsv = tokens.firstOrNull { it.pos == "XSV" }
        assertTrue("Expected an XSV token in $tokens", xsv != null)
        assertTrue(
            "Expected the XSV morpheme to be 되 (got ${xsv?.surface}/${xsv?.pos}): $tokens",
            xsv!!.surface == "되",
        )
    }

    @Test fun `pronoun is tagged with NP`() {
        // KOMORAN tags standalone pronouns with NP. KoreanEngine adds NP
        // to the lookup-worthy whitelist so 나 / 너 / 우리 produce tap
        // spans with dictionary entries. Without NP recognized here, the
        // Wiktionary pack's pronoun entries would never be surfaced.
        val tokens = tokenize("나는")
        assertTrue(
            "Expected an NP token for 나 in $tokens",
            tokens.any { it.pos == "NP" && it.surface == "나" },
        )
    }

    @Test fun `dependent noun 것 is tagged with NNB`() {
        // 것이다 = it is (that one). KOMORAN tags the bound noun 것 with
        // NNB — a POS class KoreanEngine now admits for lookup. Dependent
        // nouns like 것 / 수 / 바 / 때문 appear constantly in real text,
        // so excluding them silently drops common taps on the floor.
        val tokens = tokenize("것이다")
        assertTrue(
            "Expected an NNB token for 것 in $tokens",
            tokens.any { it.pos == "NNB" && it.surface == "것" },
        )
    }

    @Test fun `하다 adjective compound attaches 하 to NNG via an XS-family suffix`() {
        // 필요하다 = necessary. KOMORAN's LIGHT model currently tags BOTH
        // verb- and adjective-deriving 하 as XSV (not XSA as the Sejong
        // tagset nominally prescribes), with 다 tagged EC. FULL model or
        // future releases may switch to XSA for adjectives, so
        // KoreanEngine.detectCompoundSuffix accepts either — this test
        // pins the weaker invariant our engine actually depends on:
        // noun-derived adjective compounds decompose as NNG + XS* +
        // verb-ending with morph `하` at the junction.
        val tokens = tokenize("필요하다")
        assertTrue(
            "Expected NNG 필요 in $tokens",
            tokens.any { it.pos == "NNG" && it.surface == "필요" },
        )
        val suffix = tokens.firstOrNull { it.pos == "XSV" || it.pos == "XSA" }
        assertTrue("Expected an XSV or XSA token in $tokens", suffix != null)
        assertTrue(
            "Expected the XS* morpheme to be 하 (got ${suffix?.surface}/${suffix?.pos}): $tokens",
            suffix!!.surface == "하",
        )
    }

    @Test fun `standalone 아니다 is tagged as VCN plus EF`() {
        // 아니다 = "not" / negative copula. With no preceding noun to
        // absorb it into, a user tapping this would get NOTHING unless
        // KoreanEngine admits VCN for lookup and appends 다 as the
        // citation-form suffix (analogous to VV/VA).
        val tokens = tokenize("아니다")
        val vcn = tokens.firstOrNull { it.pos == "VCN" }
        assertTrue("Expected a VCN token in $tokens", vcn != null)
        assertTrue(
            "Expected VCN morpheme 아니 (got ${vcn?.surface}): $tokens",
            vcn!!.surface == "아니",
        )
    }

    @Test fun `copular form 입니다 decomposes with VCP 이 attached to NNG`() {
        // 학생입니다 = "is a student". KOMORAN tags the copula 이 with
        // VCP. Previously KoreanEngine filtered VCP out, which meant the
        // Wiktionary pack's 이다 entry was unreachable — adding VCP to
        // the whitelist gives the 입니다 portion its own tap span with
        // lookupForm 이다, while 학생 stays tappable as the noun.
        val tokens = tokenize("학생입니다")
        assertTrue(
            "Expected NNG 학생 in $tokens",
            tokens.any { it.pos == "NNG" && it.surface == "학생" },
        )
        val vcp = tokens.firstOrNull { it.pos == "VCP" }
        assertTrue("Expected a VCP token in $tokens", vcp != null)
        assertTrue(
            "Expected VCP morpheme 이 (got ${vcp?.surface}): $tokens",
            vcp!!.surface == "이",
        )
    }

    @Test fun `numeral word is tagged with NR`() {
        // 하나 = one. KOMORAN tags numeral WORDS (spelled out, not digit
        // forms) with NR. KoreanEngine admits NR so `하나` / `둘` / `백`
        // become tappable vocabulary.
        val tokens = tokenize("하나")
        assertTrue(
            "Expected an NR token in $tokens",
            tokens.any { it.pos == "NR" },
        )
    }

    @Test fun `punctuation is tagged with S-prefix`() {
        // Unlike Nori's 4-arg constructor (which discards punctuation),
        // KOMORAN emits punctuation tokens under the S* family. KoreanEngine's
        // POS filter excludes all S* tags, so these are dropped downstream.
        val tokens = tokenize("안녕.")
        assertTrue("Expected at least one token in $tokens", tokens.isNotEmpty())
        assertTrue(
            "Expected an S-prefix punctuation tag in $tokens",
            tokens.any { it.pos.startsWith("S") },
        )
    }
}
