package com.playtranslate.dictionary

import com.playtranslate.language.AnnotatedSpan
import com.playtranslate.language.EntryRef
import com.playtranslate.language.ReadingPart
import com.playtranslate.language.SentenceAnnotation
import com.playtranslate.language.SourceLangId

/**
 * The JA sentence annotator's pure core: zips the raw Sudachi stream with the
 * re-glob's span output into ONE tiled [SentenceAnnotation], applies the
 * display-reading policy, and computes each span's ruby parts. All dictionary
 * knowledge arrives pre-resolved in the resolutions map, so this object is
 * unit-testable without a pack.
 *
 * ## The zip (the refactor's one genuinely new component)
 *
 * Re-glob output does NOT tile the text: non-content tokens are skipped,
 * `isLookupWorthy` drops more, and glue folding can OVERLAP a later phrase
 * match (言われる folds かも; かもしれない then matches at か — both spans are
 * load-bearing for the words list). The zip produces a disjoint display
 * cover, phrase-priority:
 *
 *  1. phrase spans claim their token ranges first (emission order makes
 *     phrase–phrase overlap impossible, and a phrase can only ever overlap a
 *     fallback's GLUE, never its stem);
 *  2. fallback spans claim their stem plus glue up to the first claimed
 *     token (言われるかも trims to 言われる — the phrase keeps かも);
 *  3. every unclaimed token becomes its own span (per-token furigana, the
 *     legacy display behavior);
 *  4. text the tokens don't cover (whitespace, normalization drops) becomes
 *     plain spans, so the tiling invariant holds against ANY analyzer gap.
 *
 * ## Display-reading policy (ported from the tactical override, 1fa59758)
 *
 * A span shows its resolved dictionary reading instead of its per-token
 * readings only when: it spans ≥2 tokens (sandhi needs two morphemes; single
 * tokens keep Sudachi's context-picked reading), its surface is all-kanji
 * (a whole-span bracket over okurigana would smear ruby across kana),
 * uninflected (surface == lookup form), and the resolved reading is pure
 * kana. Because resolution is keyed by (lookupForm, occurrence hint), a
 * homograph the tokenizer read two ways resolves per occurrence — each
 * occurrence gets ITS OWN entry's reading, which strictly improves on the
 * old builder's all-or-nothing occurrence veto (that veto compensated for
 * one deduped reading serving every occurrence; per-occurrence resolution
 * dissolves it).
 */
internal object SentenceAnnotator {

    /** Resolution request key: the span's lookup form plus its occurrence
     *  hint (the re-glob's lookup-hint reading — stem for singles, member
     *  concat for exact phrases, null for lemma variants). */
    data class ResolutionKey(val lookupForm: String, val hint: String?)

    /** One resolved word: which store/entry it landed on and the
     *  occurrence-validated display reading (selectHeadword semantics),
     *  null on a double miss. */
    data class WordResolution(val entryRef: EntryRef?, val reading: String?)

    /** The unique resolution requests a FULL-depth annotation needs. */
    fun resolutionKeys(
        reglob: List<DictionaryManager.Companion.ReglobSpan>,
    ): Set<ResolutionKey> =
        reglob.mapTo(linkedSetOf()) { ResolutionKey(it.lookupForm, it.reading) }

    fun annotate(
        text: String,
        lang: SourceLangId,
        tokens: List<JaToken>,
        /** Null = TOKENS depth (no re-glob, per-token spans only). */
        reglob: List<DictionaryManager.Companion.ReglobSpan>?,
        resolutions: Map<ResolutionKey, WordResolution>,
        importGeneration: Int,
    ): SentenceAnnotation {
        if (text.isEmpty()) return SentenceAnnotation(text, lang, importGeneration, emptyList())
        if (tokens.isEmpty()) return SentenceAnnotation.plain(text, lang)

        // ── Cover: which re-glob span (index) owns each token ────────────
        val owner = IntArray(tokens.size) { -1 }
        val coverCount = IntArray(reglob?.size ?: 0)
        if (reglob != null) {
            for ((idx, s) in reglob.withIndex()) {
                if (!s.isPhrase) continue
                for (t in s.tokenStart until s.tokenStart + s.tokenCount) {
                    owner[t] = idx
                }
                coverCount[idx] = s.tokenCount
            }
            for ((idx, s) in reglob.withIndex()) {
                if (s.isPhrase) continue
                if (owner[s.tokenStart] != -1) continue // stem claimed: impossible today, fail safe
                owner[s.tokenStart] = idx
                var claimed = 1
                for (t in s.tokenStart + 1 until s.tokenStart + s.tokenCount) {
                    if (owner[t] != -1) break
                    owner[t] = idx
                    claimed++
                }
                coverCount[idx] = claimed
            }
        }

        // ── Assemble tiled spans ─────────────────────────────────────────
        val spans = mutableListOf<AnnotatedSpan>()
        var charPos = 0
        var ti = 0
        while (ti < tokens.size) {
            val first = tokens[ti]
            val ownerIdx = owner[ti]
            val count = if (ownerIdx >= 0) coverCount[ownerIdx] else 1
            val last = tokens[ti + count - 1]
            // Gap before this unit (whitespace / analyzer drops) → plain span.
            if (first.begin > charPos) {
                spans.add(plainSpan(text, charPos, first.begin))
            }
            val members = tokens.subList(ti, ti + count)
            val src = if (ownerIdx >= 0) reglob!![ownerIdx] else null
            spans.add(buildSpan(text, first.begin, last.end, members, src, resolutions))
            charPos = last.end
            ti += count
        }
        if (charPos < text.length) spans.add(plainSpan(text, charPos, text.length))
        return SentenceAnnotation(text, lang, importGeneration, spans)
    }

    private fun plainSpan(text: String, start: Int, end: Int): AnnotatedSpan {
        val surface = text.substring(start, end)
        return AnnotatedSpan(
            start = start, end = end, surface = surface,
            furigana = listOf(ReadingPart(surface, null)),
        )
    }

    private fun buildSpan(
        text: String,
        start: Int,
        end: Int,
        members: List<JaToken>,
        src: DictionaryManager.Companion.ReglobSpan?,
        resolutions: Map<ResolutionKey, WordResolution>,
    ): AnnotatedSpan {
        val surface = text.substring(start, end)
        val memberReadings = members.map { it.reading?.let(Deinflector::katakanaToHiragana) }
        val tokenReading =
            if (memberReadings.any { it.isNullOrEmpty() }) null
            else memberReadings.joinToString("")
        val lookupForm = src?.lookupForm
            ?: members.singleOrNull()?.takeIf { it.category.isContent }?.dictionaryForm
        val res = src?.let { resolutions[ResolutionKey(it.lookupForm, it.reading)] }
        val resReading = res?.reading
        val overrideApplied = resReading != null &&
            members.size >= 2 &&
            src != null && surface == src.lookupForm &&
            surface.all(Deinflector::isKanji) &&
            resReading.all(Deinflector::isKana)
        val reading = if (overrideApplied) resReading else tokenReading
        val parts =
            if (overrideApplied && resReading != null) splitParts(surface, resReading)
            else perTokenParts(surface, members, memberReadings)
        return AnnotatedSpan(
            start = start, end = end, surface = surface,
            lookupForm = lookupForm,
            word = if (res?.entryRef != null) src?.lookupForm else null,
            entryRef = res?.entryRef,
            reading = reading,
            furigana = parts,
            tokenReading = tokenReading,
            inflections = src?.inflections.orEmpty(),
        )
    }

    /** Per-member ruby parts: each kanji-bearing token gets its own
     *  kana-anchored splitFurigana segmentation (the legacy per-token
     *  display, byte-preserved for every span the policy leaves alone);
     *  reading-less or kana-only tokens write through plain. */
    private fun perTokenParts(
        surface: String,
        members: List<JaToken>,
        memberReadings: List<String?>,
    ): List<ReadingPart> {
        val parts = mutableListOf<ReadingPart>()
        var covered = 0
        for ((i, m) in members.withIndex()) {
            val r = memberReadings[i]
            // reading == surface guard: byte-parity with the legacy furigana
            // path, which never floated a reading identical to its base.
            if (r != null && r != m.surface && m.surface.any(Deinflector::isKanji)) {
                parts += splitParts(m.surface, r)
            } else {
                parts += ReadingPart(m.surface, null)
            }
            covered += m.surface.length
        }
        // Glue that the surface carries beyond the member tokens (a trimmed
        // fallback never has this; an untrimmed one's glue IS in members —
        // this guards analyzer-normalization length drift): degrade to a
        // whole-span plain part rather than emit misaligned ruby.
        if (covered != surface.length) {
            return listOf(ReadingPart(surface, null))
        }
        return parts
    }

    private fun splitParts(surface: String, reading: String): List<ReadingPart> =
        Deinflector.splitFurigana(surface, reading).map { ReadingPart(it.text, it.reading) }
}
