package com.playtranslate.language

import android.content.Context
import com.playtranslate.model.DictionaryResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.co.shineware.nlp.komoran.constant.DEFAULT_MODEL
import kr.co.shineware.nlp.komoran.core.Komoran
import kr.co.shineware.nlp.komoran.model.Token
import java.text.Normalizer

/**
 * Korean source-language engine. Uses Shineware's KOMORAN (Apache 2.0) for
 * morphological segmentation: cracks eojeol like `먹었습니다` into morphemes
 * (먹/VV, 었/EP, 습니다/EF), then reconstructs the dictionary citation form
 * for verbs/adjectives by appending `다` to VV/VA stems.
 *
 * Why KOMORAN over Lucene Nori: Nori would require a larger mecab-ko-dic
 * (~24 MB) but, more critically, its `AttributeFactory` transitively loads
 * `java.lang.ClassValue` during class init. ClassValue is advertised by the
 * Android SDK stubs but is NOT implemented by ART at runtime — constructing
 * any Lucene tokenizer on Android throws `NoClassDefFoundError`. KOMORAN is
 * pure Java with no Lucene dependency, so it dodges this entirely. Quality
 * delta is small for game text; KOMORAN's slightly coarser compound handling
 * is arguably a UX win for tap regions.
 *
 * Structurally a twin of [ChineseEngine]: a morphological tokenizer on top
 * of [WiktionaryDictionaryManager]. Thread-safety: KOMORAN's [Komoran] is
 * not thread-safe, so [tokenize] is guarded by a per-instance lock (same
 * pattern as [LatinEngine.iteratorLock]).
 */
class KoreanEngine(private val appContext: Context) : SourceLanguageEngine {

    override val profile: SourceLanguageProfile = SourceLanguageProfiles[SourceLangId.KO]

    private val dict: WiktionaryDictionaryManager =
        WiktionaryDictionaryManager.get(appContext, SourceLangId.KO)

    /**
     * KOMORAN instance with the LIGHT model. LIGHT is Shineware's
     * production-tuned smaller model (~5-10 MB) that trades some OOV
     * robustness for faster load + smaller memory footprint. FULL exists
     * for newspaper-grade text; game dialogue doesn't need it.
     *
     * Lazy: the Komoran constructor deserializes the model synchronously
     * (~200-500 ms on JVM). Some call sites reach [SourceLanguageEngines.get]
     * on the main dispatcher (e.g. DragLookupController, TranslationResultFragment's
     * word-lookup launch). [tokenize] is the only method that touches this
     * field, and it body-wraps in [Dispatchers.Default], so lazy construction
     * is guaranteed to land off the UI thread even when preload hasn't run yet.
     */
    private val komoran: Komoran by lazy { Komoran(DEFAULT_MODEL.LIGHT) }
    private val komoranLock = Any()

    override suspend fun preload(): PreloadResult {
        if (!LanguagePackStore.isInstalled(appContext, SourceLangId.KO)) {
            return PreloadResult.PackMissing
        }
        // First access to [komoran] triggers its lazy constructor, which
        // loads the LIGHT model synchronously. Do it on IO so the user's
        // first capture doesn't stall. Mirrors ChineseEngine.preload().
        val warmed = withContext(Dispatchers.IO) {
            runCatching { tokenize("예열") }
        }
        if (warmed.isFailure) {
            return PreloadResult.PackCorrupt(
                "KOMORAN warm-up failed: ${warmed.exceptionOrNull()?.message ?: "unknown"}"
            )
        }
        if (dict.preload() == null) {
            return PreloadResult.PackCorrupt("KO dict.sqlite failed to open")
        }
        return PreloadResult.Success
    }

    override suspend fun tokenize(text: String): List<TokenSpan> = withContext(Dispatchers.Default) {
        // KOMORAN and the Wiktionary pack both expect precomposed Hangul, so
        // we analyze an NFC-normalized copy. But the UI downstream locates
        // surface spans via `displayedText.indexOf(tok.surface)` on the
        // ORIGINAL (possibly decomposed) text — so emitted surfaces must be
        // substrings of the ORIGINAL, not the normalized form. The offset
        // map [nfcToOrig] translates KOMORAN's NFC indices back to the
        // caller's character positions.
        val (normalized, nfcToOrig) = normalizeWithOffsets(text)

        val result = mutableListOf<TokenSpan>()
        // Dedupe near-identical tokens (same span + same lookupForm). KOMORAN
        // usually doesn't emit overlaps, but the guard is cheap insurance.
        val seen = mutableSetOf<Triple<Int, Int, String>>()

        synchronized(komoranLock) {
            val tokens = komoran.analyze(normalized).tokenList
            for (i in tokens.indices) {
                val token = tokens[i]
                val pos = token.pos ?: continue
                if (!isLookupWorthyPos(pos)) continue

                val beginNfc = token.beginIndex
                val tokenEndNfc = token.endIndex
                if (beginNfc < 0 || tokenEndNfc > normalized.length || beginNfc >= tokenEndNfc) continue

                // Determine the lookup/citation form. For NNG/NNP directly
                // attached to 하/되 tagged as XSV (verb-deriving suffix), the
                // pair forms a compound verb (공부하다, 사용되다, etc.) that
                // must be queried under the compound lemma — not bare 공부
                // or bare 하다. Without this, every -하다 / -되다 verb fails
                // lookup, even though those entries exist in the pack.
                val stem = token.morph ?: continue
                val compoundSuffix = detectCompoundSuffix(pos, tokens.getOrNull(i + 1), tokenEndNfc)
                val lookupForm = if (compoundSuffix != null) {
                    citationForm(stem + compoundSuffix, "VV")
                } else {
                    citationForm(stem, pos)
                }

                // Extend the tap span to cover the rest of the eojeol so the
                // full inflected word is tappable. Morpheme-level offsets
                // alone leave only the stem syllables covered — e.g. 먹 out
                // of 먹었습니다, 사람 out of 사람은 — and for contracted
                // forms such as 공부했습니다 the 하/XSV and 였/EP morphemes
                // share a single input syllable, so a per-token walk would
                // miss the trailing EF 습니다. Jumping to the eojeol end and
                // capping at any subsequent content-POS token inside it is
                // robust to both overlapping contractions and KOMORAN
                // segmentation quirks.
                val eojeolEndNfc = findEojeolEndNfc(normalized, tokenEndNfc)
                var endNfc = eojeolEndNfc
                for (k in i + 1 until tokens.size) {
                    val nxt = tokens[k]
                    if (nxt.beginIndex >= eojeolEndNfc) break
                    val nxtPos = nxt.pos ?: continue
                    if (nxt.beginIndex < tokenEndNfc) continue  // overlapping contraction
                    // Cap at the next content word so compound nouns like
                    // 학생회 split into separately tappable 학생 + 회, and at
                    // S-family tokens (punctuation SF/SP, symbols SW, foreign
                    // SL/SH/SN) so adjacent punctuation stays tappable on its
                    // own (`먹었습니다.` → span is `먹었습니다`, not the period).
                    if (isLookupWorthyPos(nxtPos) || nxtPos.startsWith("S")) {
                        endNfc = nxt.beginIndex
                        break
                    }
                }

                // Map NFC offsets back to the caller's string before emitting
                // the span. Safe for both fast-path (identity map when input
                // is already NFC) and slow-path (Hangul jamo / Latin NFD).
                val beginOrig = nfcToOrig[beginNfc]
                val endOrig = nfcToOrig[endNfc]
                if (beginOrig < 0 || endOrig > text.length || beginOrig >= endOrig) continue
                val surface = text.substring(beginOrig, endOrig)
                if (surface.isBlank()) continue

                val key = Triple(beginOrig, endOrig, lookupForm)
                if (!seen.add(key)) continue

                result += TokenSpan(surface = surface, lookupForm = lookupForm, reading = null)
            }
        }

        result
    }

    override suspend fun lookup(word: String, reading: String?): DictionaryResponse? {
        // KOMORAN already produced the citation form upstream, so no stem
        // fallback is meaningful. WiktionaryDictionaryManager's stemmed=null
        // branch short-circuits cleanly.
        val normalized = Normalizer.normalize(word, Normalizer.Form.NFC)
        return dict.lookup(surface = normalized, stemmed = null)
    }

    override fun close() {
        // KOMORAN does not expose a close() — the Komoran instance holds
        // model resources until GC'd. Only the dict needs explicit cleanup.
        dict.close()
    }

    /**
     * True when a Sejong-tagset POS is a content word worth dictionary-
     * looking-up. KOMORAN uses the same Sejong tags as Nori/MeCab-ko.
     * The whitelist covers the categories with standalone entries in the
     * Korean Wiktionary pack; attachments (J/E/X), copulas (VC*), symbols
     * (S*), and foreign-script tokens (SL/SH/SN) are excluded because they
     * either aren't dictionary words (particles, endings) or aren't covered
     * by the KO pack (Hanja, Latin glosses, bare numerals).
     */
    private fun isLookupWorthyPos(pos: String): Boolean = when (pos) {
        "NNG", "NNP",           // general + proper noun
        "NNB",                  // dependent / bound noun (것, 수, 바, 때문) — common in real text
        "NP",                   // pronoun (나, 너, 우리, 이것)
        "NR",                   // numeral word (하나, 둘, 백)
        "VV", "VA",             // verb + adjective
        "VX",                   // auxiliary verb/adj (e.g. 하다 in 공부하다)
        "VCP", "VCN",           // copulas 이다 (positive) / 아니다 (negative)
        "MM",                   // determiner (이, 그, 저, 이런)
        "MAG", "MAJ",           // general + conjunctive adverb (그리고, 하지만)
        "IC" -> true            // interjection (common in game dialogue)
        else -> false
    }

    /**
     * Return the compound suffix ("하" or "되") when [pos] is a content
     * noun (NNG/NNP) directly attached (no whitespace gap) to a matching
     * verb- or adjective-deriving suffix. Returns null otherwise.
     *
     * Covers both productive patterns:
     *  - `공부하다` (verb) — `공부/NNG + 하/XSV + 다/EF`
     *  - `필요하다` (adjective) — `필요/NNG + 하/XSA + 다/EF`
     *  - `사용되다` (passive verb) — `사용/NNG + 되/XSV + 다/EF`
     *
     * Without this check the engine would try to look up bare `공부` /
     * `필요` even though the user tapped the whole compound. Restricted
     * to NNG/NNP because the pattern is lexically bound to content nouns
     * — pronouns, numerals, and dependent nouns don't participate
     * (나하다, 것하다 are not words). Morph filter stays `하`/`되` because
     * those are the suffixes with near-total Wiktionary entry coverage;
     * other XSA morphs like 답/롭/스럽 (남자답다, 자유롭다, 자랑스럽다)
     * are deliberately left falling back to the noun lookup since
     * dictionary coverage for those is patchier and a bare-noun result
     * beats an empty result.
     *
     * [nounEndNfc] is the NFC offset where the noun morpheme ends; used
     * to verify the suffix is attached with no intervening whitespace.
     */
    private fun detectCompoundSuffix(pos: String, next: Token?, nounEndNfc: Int): String? {
        if (pos != "NNG" && pos != "NNP") return null
        if (next == null) return null
        if (next.beginIndex != nounEndNfc) return null
        val nextPos = next.pos ?: return null
        if (nextPos != "XSV" && nextPos != "XSA") return null
        val morph = next.morph ?: return null
        return if (morph == "하" || morph == "되") morph else null
    }

    /**
     * First whitespace position in [normalized] at or after [fromIndex], or
     * [normalized.length] when there is none. Defines the end of the
     * current eojeol — whitespace-delimited Korean word chunk — from the
     * tokenizer's perspective. [Char.isWhitespace] covers plain space,
     * tab, newline, and the Unicode whitespace category we encounter in
     * OCR output.
     */
    private fun findEojeolEndNfc(normalized: String, fromIndex: Int): Int {
        var k = fromIndex
        while (k < normalized.length && !normalized[k].isWhitespace()) k++
        return k
    }

    /**
     * Reconstruct the dictionary citation form from the morpheme stem.
     * Korean verbs, adjectives, and copulas are listed in Wiktionary with
     * a trailing `다` (`먹다`, `예쁘다`, `공부하다`, `이다`, `아니다`).
     * KOMORAN emits the bare stem (`먹`, `예쁘`, `이`, `아니`) as the
     * V*-family morpheme, always without the `다` suffix, so we re-append
     * it unconditionally for those POS — including synthesized VV for
     * 하다/되다 compounds and VCP/VCN for copulas.
     */
    private fun citationForm(stem: String, pos: String): String {
        val needsCitationSuffix = pos == "VV" || pos == "VA" || pos == "VX" ||
            pos == "VCP" || pos == "VCN"
        return if (needsCitationSuffix) stem + "다" else stem
    }

    companion object {
        /**
         * Returns an NFC-normalized copy of [text] paired with an offset map:
         * `nfcToOrig[i]` is the offset in [text] where the NFC character at
         * position `i` starts. The last entry is `text.length` (sentinel),
         * so a token with NFC range `[b, e)` maps to original range
         * `[nfcToOrig[b], nfcToOrig[e])`, and the resulting substring is
         * guaranteed to exist verbatim in the caller's string.
         *
         * Why this exists: the UI locates tap spans via
         * `displayedText.indexOf(TokenSpan.surface)` on the original string.
         * If we NFC-normalized before segmentation and emitted surfaces
         * from the normalized form, decomposed Hangul jamo input (or any
         * NFD combining input) would silently break span lookup even
         * though tokenization succeeded. Mapping back to original offsets
         * keeps the invariant that surface ⊆ displayedText.
         *
         * Fast path: identity map when the input is already NFC. Slow path:
         * walk the original, recording each NFC character's source start
         * position by watching when incremental NFC length advances.
         * Correct for Hangul L+V(+T) composition, Latin NFD (e.g. "café"
         * as `e + U+0301`), and any mixed-script input. Complexity is
         * O(n²) on the slow path, acceptable for per-line OCR output.
         */
        internal fun normalizeWithOffsets(text: String): Pair<String, IntArray> {
            val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
            if (normalized == text) {
                return normalized to IntArray(text.length + 1) { it }
            }

            val map = IntArray(normalized.length + 1)
            var prevNfcLen = 0
            for (origIdx in 1..text.length) {
                val curNfcLen =
                    Normalizer.normalize(text.substring(0, origIdx), Normalizer.Form.NFC).length
                while (prevNfcLen < curNfcLen) {
                    // A new NFC character just appeared; its source sequence
                    // began at the char we just processed (origIdx - 1).
                    map[prevNfcLen] = origIdx - 1
                    prevNfcLen++
                }
            }
            map[normalized.length] = text.length
            return normalized to map
        }
    }
}
