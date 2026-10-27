package com.playtranslate.dictionary

import com.playtranslate.language.AnnotatedSpan
import com.playtranslate.language.EntryRef
import com.playtranslate.language.ReadingOverride
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
 *     fallback's folded tail, never its stem);
 *  2. fallback spans claim their stem plus folded tail up to the first
 *     claimed token (言われるかも trims to 言われる: the phrase keeps かも),
 *     and a trimmed span's tags are recomputed from the tokens it kept;
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

    /** One resolved word: which store/entry it landed on, the
     *  occurrence-validated display reading, and the canonical written
     *  form (selectHeadword semantics — the words-table/drag-label
     *  display key). Null fields on a double miss. */
    data class WordResolution(
        val entryRef: EntryRef?,
        val reading: String?,
        val written: String? = null,
    )

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
                // A stem is never claimed here: spans come out in start order
                // and the matcher moves past a phrase before emitting anything
                // else, so no phrase covers a fallback's stem, and reglobSpans
                // emits no fallback at a token an earlier fallback folded (its
                // folded-auxiliary guard). Kept so a broken invariant drops the
                // span instead of double-tiling.
                if (owner[s.tokenStart] != -1) continue
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
        // Per-token spans keep contentOnly semantics: a content, lookup-
        // worthy token exposes its lemma (this is what the DB-not-ready
        // fallback and TOKENS depth surface to the tokenize projection);
        // when the re-glob ran, every such token is already a re-glob span,
        // so this branch only fires for tokens the re-glob dropped.
        val lookupForm = src?.lookupForm
            ?: members.singleOrNull()
                ?.takeIf { it.category.isContent && DictionaryManager.isLookupWorthy(it.dictionaryForm) }
                ?.dictionaryForm
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
            word = if (res?.entryRef != null) (res.written ?: src?.lookupForm) else null,
            entryRef = res?.entryRef,
            // Re-glob spans carry the hint resolution used (null for lemma
            // variants BY DESIGN — an inflected concat can never match an
            // entry reading); per-token fallback spans hint their own token
            // reading, the contentOnly-era behavior hydration expects.
            lookupHint = if (src != null) src.reading
                else tokenReading?.takeIf { lookupForm != null },
            reading = reading,
            furigana = parts,
            tokenReading = tokenReading,
            inflections = when {
                src == null -> emptyList()
                // A phrase took this fallback's tail: the tags describe only
                // the tokens the span still shows (言うじゃないの trimmed to 言う
                // by じゃない reads nothing, not negative).
                members.size < src.tokenCount ->
                    JapaneseInflectionAnalyzer.analyze(members.first(), members.drop(1), src.lookupForm)
                else -> src.inflections
            },
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

    /**
     * [annotation] as a sentence card reads it under [override]: the one
     * anchored span whose surface is [ReadingOverride.surface] becomes the
     * card's word, read [ReadingOverride.reading], with its ruby parts split
     * the way [buildSpan] splits a span it reads whole (a kana surface reading
     * itself stays plain). Its entry ref and pitch described the word the
     * analysis chose and are dropped; the fields left alone (lookup form,
     * hint, tags) feed the interactive lookups, which never see an override.
     * A surface the sentence holds more than once is left as analyzed: the
     * override names no occurrence, and a repeat may be another word read
     * another way (上手な方と下手な方 reads かた, then ほう), so re-reading every
     * one could put a reading on the card the sentence does not have. With no
     * span on the surface at all, the word may sit inside a larger one
     * ([withMemberSplit]). The same instance when neither applies;
     * [annotation] itself, which the engine and the sentence cache share, is
     * never modified. Accepted limit: a card for a word inside a unit whose
     * text also stands alone elsewhere in the sentence re-reads that
     * standalone span, since nothing here tells the occurrences apart; it
     * changes a reading only where the two are read differently, and
     * refusing whenever the text recurs inside other words would also refuse
     * 地方の方に聞いた (方 read かた), the case this exists for.
     */
    fun withReading(annotation: SentenceAnnotation, override: ReadingOverride): SentenceAnnotation {
        fun matches(span: AnnotatedSpan) = span.start >= 0 && span.surface == override.surface
        when (annotation.spans.count(::matches)) {
            0 -> return withMemberSplit(annotation, override) ?: annotation
            1 -> Unit
            else -> return annotation
        }
        return annotation.copy(
            spans = annotation.spans.map { span ->
                if (!matches(span)) span
                else span.copy(
                    word = override.word,
                    entryRef = null,
                    reading = override.reading,
                    furigana =
                        if (override.reading == span.surface) listOf(ReadingPart(span.surface, null))
                        else splitParts(span.surface, override.reading),
                    pitch = emptyList(),
                )
            },
        )
    }

    /**
     * [override]'s word split out of the one larger span that holds its text:
     * the card is for a member of a unit the analysis read whole (気 in
     * 気になる, from the detail page's Words section), and the span becomes
     * the pieces before, of and after the word, so the card can bold and wrap
     * the word alone. The pieces keep the analysis's readings, so the
     * sentence reads and sounds as it did: inside a unit read whole, a
     * member's own reading need not hold (間 reads ま in 瞬く間に). The word's
     * piece is the card's word; the others resolve to nothing, since the
     * unit no longer spans the text. Null, leaving the analysis alone, unless
     * exactly one anchored span holds the text, and holds it once (天気が気になる
     * puts 気 in two spans: no telling which), both cuts fall between ruby
     * parts or inside kana (a kanji's reading belongs to its whole part: 大
     * cannot leave 大人), and the span's reading is its parts' readings joined.
     */
    private fun withMemberSplit(annotation: SentenceAnnotation, override: ReadingOverride): SentenceAnnotation? {
        val text = override.surface
        if (text.isEmpty()) return null
        val holder = annotation.spans.singleOrNull { it.start >= 0 && it.surface.contains(text) } ?: return null
        val from = holder.surface.indexOf(text)
        if (holder.surface.indexOf(text, from + 1) >= 0) return null
        val to = from + text.length
        val (before, inside, after) = cutParts(holder.furigana, holder.surface.length, from, to) ?: return null
        fun readingOf(parts: List<ReadingPart>) =
            parts.joinToString("") { it.reading ?: Deinflector.katakanaToHiragana(it.text) }
        val reading = holder.reading
        if (reading != null && Deinflector.katakanaToHiragana(reading) != readingOf(before + inside + after)) {
            return null
        }
        fun piece(at: Int, parts: List<ReadingPart>, isWord: Boolean): AnnotatedSpan {
            val surface = parts.joinToString("") { it.text }
            val pieceReading = reading?.let { readingOf(parts) }
            return AnnotatedSpan(
                start = holder.start + at, end = holder.start + at + surface.length, surface = surface,
                lookupForm = if (isWord) override.word else null,
                word = if (isWord) override.word else null,
                reading = pieceReading,
                furigana = parts,
                tokenReading = pieceReading,
            )
        }
        val pieces = buildList {
            if (before.isNotEmpty()) add(piece(0, before, isWord = false))
            add(piece(from, inside, isWord = true))
            if (after.isNotEmpty()) add(piece(to, after, isWord = false))
        }
        return annotation.copy(spans = annotation.spans.flatMap { if (it === holder) pieces else listOf(it) })
    }

    /** [parts], one span's ruby tiling its [length] chars, cut at [from] and
     *  [to]: the parts before, between and after, a kana part divided where a
     *  cut falls inside it. Null when a cut falls inside a part that carries
     *  a reading, the parts do not tile the span, or nothing lies between. */
    private fun cutParts(
        parts: List<ReadingPart>,
        length: Int,
        from: Int,
        to: Int,
    ): Triple<List<ReadingPart>, List<ReadingPart>, List<ReadingPart>>? {
        val before = mutableListOf<ReadingPart>()
        val inside = mutableListOf<ReadingPart>()
        val after = mutableListOf<ReadingPart>()
        var start = 0
        for (part in parts) {
            val end = start + part.text.length
            if (part.reading != null && (from in (start + 1) until end || to in (start + 1) until end)) return null
            fun take(a: Int, b: Int, into: MutableList<ReadingPart>) {
                if (b <= a) return
                into += if (a == start && b == end) part else ReadingPart(part.text.substring(a - start, b - start), null)
            }
            take(start, minOf(end, from), before)
            take(maxOf(start, from), minOf(end, to), inside)
            take(maxOf(start, to), end, after)
            start = end
        }
        return if (start != length || inside.isEmpty()) null else Triple(before, inside, after)
    }
}
