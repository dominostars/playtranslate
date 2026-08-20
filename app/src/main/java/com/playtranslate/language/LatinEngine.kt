package com.playtranslate.language

import android.content.Context
import android.icu.text.BreakIterator
import com.playtranslate.model.DictionaryResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tartarus.snowball.SnowballProgram
import org.tartarus.snowball.ext.ArabicStemmer
import org.tartarus.snowball.ext.CatalanStemmer
import org.tartarus.snowball.ext.DanishStemmer
import org.tartarus.snowball.ext.DutchStemmer
import org.tartarus.snowball.ext.EnglishStemmer
import org.tartarus.snowball.ext.FinnishStemmer
import org.tartarus.snowball.ext.FrenchStemmer
import org.tartarus.snowball.ext.GermanStemmer
import org.tartarus.snowball.ext.HungarianStemmer
import org.tartarus.snowball.ext.ItalianStemmer
import org.tartarus.snowball.ext.NorwegianStemmer
import org.tartarus.snowball.ext.PortugueseStemmer
import org.tartarus.snowball.ext.RomanianStemmer
import org.tartarus.snowball.ext.RussianStemmer
import org.tartarus.snowball.ext.SpanishStemmer
import org.tartarus.snowball.ext.SwedishStemmer
import org.tartarus.snowball.ext.TurkishStemmer
import java.text.Normalizer
import java.util.Locale

/**
 * Source-language engine for whitespace-segmented languages with a Snowball
 * stemmer and a Wiktionary pack — the Latin set plus Cyrillic (Russian). The
 * machinery keys off [langId], not script. Combines three off-the-shelf parts:
 *
 *  - **Tokenization**: ICU [BreakIterator] with the language's [Locale].
 *  - **Stemming**: Lucene's Snowball stemmer for the language. Nullable —
 *    isolating languages (Vietnamese, Indonesian) have no Snowball stemmer
 *    because there is no inflection to strip; [stemOf] falls back to the
 *    lowercased surface for them.
 *  - **Dictionary**: [WiktionaryDictionaryManager] queries the downloaded
 *    pack with surface-first and stem-fallback semantics.
 *
 * [tokenize] runs the space-delimited phrase re-glob (the analog of the JA
 * n-gram re-glob in [com.playtranslate.dictionary.DictionaryManager]): the
 * packs index six-figure counts of space-joined headwords ("a great deal",
 * "il y a", "máy tính") that one-token-per-word output could never query, so
 * every dictionary-known n-gram fuses into a single span — one words-panel
 * row, one tap target covering the whole expression. The packs also list
 * marginal function-word bigrams ("on the", "of a") that are
 * data-indistinguishable from gold entries ("have to", "at all"); those fuse
 * too, by decision — the phrase detail page's member-word drill-down keeps
 * each individual word one tap away, which is the escape hatch that makes
 * aggressive fusing acceptable.
 *
 * Tokenizer and stemmer are both stateful and not thread-safe, so both
 * operations are guarded by per-instance `synchronized` blocks.
 */
class LatinEngine(
    private val appContext: Context,
    private val langId: SourceLangId = SourceLangId.EN,
) : SourceLanguageEngine {

    override val profile: SourceLanguageProfile = SourceLanguageProfiles[langId]

    private val dict: WiktionaryDictionaryManager = WiktionaryDictionaryManager.get(appContext, langId)
    private val yomitan = YomitanEnrichment(appContext, langId.yomitanConsumingLang())
    private val locale: Locale = langId.locale
    private val breakIterator: BreakIterator = BreakIterator.getWordInstance(locale)
    private val stemmer: SnowballProgram? = stemmerFor(langId)
    private val stemmerLock = Any()
    private val iteratorLock = Any()

    override suspend fun preload(): PreloadResult {
        if (!LanguagePackStore.isInstalled(appContext, langId)) {
            return PreloadResult.PackMissing
        }
        if (dict.preload() == null) {
            return PreloadResult.PackCorrupt("${langId.code} dict.sqlite failed to open")
        }
        return PreloadResult.Success
    }

    /** Same LRU the JA/ZH engines keep in front of their dictionary-backed
     *  annotation: now that the phrase re-glob gates candidates against the
     *  pack + imported dicts, repeated-text calls (the drag flow re-tokenizes
     *  the same OCR line every dwell tick; result surfaces re-tokenize on
     *  re-render) must be map hits, not re-queries. Import mutations
     *  invalidate via [AnnotationGenerations]; pack swaps clear via [close]. */
    private val annotationCache = AnnotationCache()

    override suspend fun tokenize(text: String): List<TokenSpan> =
        lexicalAnnotation(text).spans.map {
            TokenSpan(
                surface = it.surface, lookupForm = it.lookupForm ?: it.surface,
                reading = it.reading, inflections = it.inflections,
            )
        }

    override suspend fun annotate(text: String, depth: AnnotationDepth): SentenceAnnotation {
        val lexical = lexicalAnnotation(text)
        // Interface contract kept from the default implementation: one plain
        // span when tokenization yields nothing.
        return if (lexical.spans.isEmpty()) SentenceAnnotation.plain(text, profile.id) else lexical
    }

    /**
     * The cached LEXICAL-tier analysis both [tokenize] and [annotate] serve
     * from — offsetless spans, possibly empty (unlike [annotate]'s plain
     * fallback, [tokenize] of lookup-unworthy text must stay `[]`).
     *
     * The space-delimited re-glob: collect the raw word stream (offsets +
     * whitespace-adjacency) under the iterator lock, gate every n-gram
     * candidate against the pack + imported dicts (suspends — outside the
     * lock), then fuse with longest-first claiming. A fused span's surface
     * is the VERBATIM source slice (word-tap surfaces re-find surfaces by
     * indexOf, so fabricated whitespace would break span mapping) while its
     * lookupForm is the single-space join the packs store. Words not
     * consumed by a phrase emit exactly as before.
     */
    private suspend fun lexicalAnnotation(text: String): SentenceAnnotation {
        if (text.isEmpty()) return SentenceAnnotation(text, profile.id, 0, emptyList())
        annotationCache.get(text)?.let { return it }
        // Generation read BEFORE the dictionary gates: an import that commits
        // mid-annotate must invalidate this entry, never ride in it.
        val generation = AnnotationGenerations.current()
        val spans = withContext(Dispatchers.Default) {
            val stream = synchronized(iteratorLock) { wordStream(text, breakIterator) }
            val candidates = phraseCandidates(text, stream, MAX_PHRASE_WINDOW_WORDS)
            fuseSpans(text, stream, candidates, knownPhraseForms(candidates))
        }.map {
            AnnotatedSpan(
                start = -1, end = -1, surface = it.surface,
                lookupForm = it.lookupForm, reading = it.reading,
                inflections = it.inflections,
            )
        }
        return SentenceAnnotation(text, profile.id, generation, spans)
            .also { annotationCache.put(it) }
    }

    /** The raw phrase-candidate [forms][PhraseCandidate.form] that exist in
     *  the pack or an imported Yomitan dictionary. Both gates run on the
     *  engine-normalized key ([normalizeForLookup] — AR undiacritization /
     *  HI NFC, identity elsewhere): the pack gate lowercases internally,
     *  the imported gate is offered the as-written + locale-lowercased pair
     *  (the same forms [lookup]'s imported chain tries). Pack candidates are
     *  capped at the build's headword limit; the oracle sees them all. */
    private suspend fun knownPhraseForms(allCandidates: List<PhraseCandidate>): Set<String> {
        if (allCandidates.isEmpty()) return emptySet()
        // Fuse-exclusion list first ([PhraseNofuse]): sequences that are
        // usually compositional in ordinary text never fuse, regardless of
        // which gate (pack or imported) knows them. Keyed like the gates:
        // engine-normalized, locale-lowercased.
        val nofuse = PhraseNofuse.forLang(appContext, langId)
        val candidates = if (nofuse.isEmpty()) allCandidates else allCandidates.filter {
            normalizeForLookup(it.form).lowercase(locale) !in nofuse
        }
        if (candidates.isEmpty()) return emptySet()
        val normalized = candidates.associate { it.form to normalizeForLookup(it.form) }
        val packKnown = dict.phrasesExist(
            candidates.mapNotNullTo(mutableSetOf()) { c ->
                normalized.getValue(c.form).takeIf { c.wordCount <= MAX_PACK_PHRASE_WORDS }
            },
        )
        val anyUnresolved = candidates.any { c ->
            c.wordCount > MAX_PACK_PHRASE_WORDS || normalized.getValue(c.form) !in packKnown
        }
        val oracleKnown = if (anyUnresolved) {
            yomitan.phraseOracle()?.invoke(
                buildSet {
                    for (c in candidates) {
                        val n = normalized.getValue(c.form)
                        add(n)
                        add(n.lowercase(locale))
                    }
                },
            ).orEmpty()
        } else {
            emptySet()
        }
        return candidates.filterTo(mutableListOf()) { c ->
            val n = normalized.getValue(c.form)
            (c.wordCount <= MAX_PACK_PHRASE_WORDS && n in packKnown) ||
                n in oracleKnown || n.lowercase(locale) in oracleKnown
        }.mapTo(mutableSetOf()) { it.form }
    }

    override suspend fun searchPrefix(query: String, limit: Int): List<TokenSpan> =
        dict.searchPrefix(normalizeForLookup(query), limit)
            .map { TokenSpan(surface = it, lookupForm = it, reading = null) }

    override suspend fun lookup(word: String, reading: String?): DictionaryResponse? {
        val w = normalizeForLookup(word)
        val lower = w.lowercase(locale)
        // Phrase keys skip real stemming: Snowball is single-word machinery,
        // and a stemmed join could equal an UNRELATED entry's stem row —
        // surfacing a wrong entry mislabeled [stem]. Inflected phrase surfaces
        // resolve through the pack's position-2 alias rows ("gave up") instead.
        val stem = if (w.any(Char::isWhitespace)) lower else stemOf(w)
        // Arabic gets a folded lookup key (casual/variant spellings) as a
        // fallback the dictionary tries after surface and before stem.
        val folded = if (langId == SourceLangId.AR) ArabicFold.fold(w) else null
        return yomitan.applyTo(
            dict.lookup(surface = w, stemmed = stem, folded = folded),
            w, reading = null,
            fallbackForms = importedTermFallbacks(w, lower, stem, folded),
        )
    }

    override fun close() {
        annotationCache.clear()
        dict.close()
    }

    /** Returns the stem for [word], or the lowercased surface when the
     *  language has no Snowball stemmer. Lowercasing runs under the
     *  language's [locale] so Turkish `IŞIK` → `ışık` (not `işik`).
     *  Callers of [WiktionaryDictionaryManager.lookup] already short-circuit
     *  when `stemmed == surface`, so no extra guard is needed downstream. */
    private fun stemOf(word: String): String {
        val lower = word.lowercase(locale)
        val s = stemmer ?: return lower
        return synchronized(stemmerLock) {
            s.setCurrent(lower)
            s.stem()
            s.current
        }
    }

    /** Arabic source text is matched against undiacritized headwords — NFKC +
     *  tashkeel/tatweel stripped, but letter identities PRESERVED (NO alef/ya/taa
     *  fold; see [ArabicNormalize] — the normalized form doubles as the displayed
     *  lemma). The pack is built with the identical normalization. Casual
     *  letter-variant spellings are handled separately by the folded fallback in
     *  [lookup] ([ArabicFold]). No-op for other languages. */
    private fun normalizeForLookup(word: String): String = when (langId) {
        SourceLangId.AR -> ArabicNormalize.normalize(word)
        // Devanagari: canonical NFC composes nukta sequences (ड़ etc.) so OCR and
        // pack forms match. Divergence-free (the pack applies the same NFC); NOT
        // IndicNormalizer folding (deferred with the Lucene stemmer).
        SourceLangId.HI -> Normalizer.normalize(word, Normalizer.Form.NFC)
        else -> word
    }

    companion object {
        internal fun isLookupWorthy(token: String): Boolean {
            if (token.isBlank()) return false
            if (!token.any { it.isLetter() }) return false
            if (token.length < 2) return false
            return true
        }

        /** Longest phrase candidate [tokenize]'s re-glob offers the
         *  imported-dictionary oracle. Imported Yomitan dictionaries carry
         *  expressions past the pack's cap ("as far as I know"); beyond ~5
         *  words, English-class headwords are proverbs a fuse shouldn't
         *  swallow. */
        internal const val MAX_PHRASE_WINDOW_WORDS = 5

        /** Mirror of the pack build's MAX_HEADWORD_WORDS
         *  (scripts/wiktionary_filters.py) — longer candidates skip the pack
         *  gate because the build guarantees they cannot exist there. */
        internal const val MAX_PACK_PHRASE_WORDS = 3

        /** [wordStream]'s result: every letter-containing segment of the
         *  text in order, plus which consecutive pairs are separated by
         *  whitespace ONLY ([adjacent] size = words.size - 1, or empty). */
        internal class WordStream(
            val words: List<IntRange>,
            val adjacent: BooleanArray,
        )

        /** One n-gram phrase candidate over the word stream: [wordCount]
         *  words starting at word index [start], with [form] the single-space
         *  join of their surfaces (original case — the caller normalizes for
         *  gating; a run of source whitespace collapses to the packs'
         *  space-joined headword shape). */
        internal data class PhraseCandidate(
            val start: Int,
            val wordCount: Int,
            val form: String,
        )

        /**
         * Collects the raw word stream for [tokenize]'s phrase re-glob:
         * every letter-containing segment (unlike isLookupWorthy this keeps
         * single-letter words — phrases start with and contain them: "a
         * great deal", "il y a") plus whitespace-adjacency between
         * neighbors. A comma, hyphen, or digit run between two words marks
         * them non-adjacent, because pack phrases are space-joined
         * ("great, deal" must never gate "great deal").
         *
         * Caller must hold the lock guarding [iterator] (stateful, not
         * thread-safe). Internal + iterator-injected so tests can drive it
         * with a fresh instance.
         */
        internal fun wordStream(text: String, iterator: BreakIterator): WordStream {
            val words = mutableListOf<IntRange>()
            iterator.setText(text)
            var start = iterator.first()
            var end = iterator.next()
            while (end != BreakIterator.DONE) {
                val range = start until end
                if (range.any { text[it].isLetter() }) words += range
                start = end
                end = iterator.next()
            }
            val adjacent = BooleanArray(maxOf(0, words.size - 1)) { i ->
                val gap = text.substring(words[i].last + 1, words[i + 1].first)
                gap.isNotEmpty() && gap.all(Char::isWhitespace)
            }
            return WordStream(words, adjacent)
        }

        /** Every 2..[maxWords]-gram of whitespace-adjacent words in the
         *  stream. Pure — the caller gates the forms for membership. */
        internal fun phraseCandidates(
            text: String,
            stream: WordStream,
            maxWords: Int,
        ): List<PhraseCandidate> {
            val out = mutableListOf<PhraseCandidate>()
            for (i in stream.words.indices) {
                var n = 2
                while (n <= maxWords && i + n <= stream.words.size && stream.adjacent[i + n - 2]) {
                    out += PhraseCandidate(
                        start = i, wordCount = n,
                        form = stream.words.subList(i, i + n).joinToString(" ") { text.substring(it) },
                    )
                    n++
                }
            }
            return out
        }

        /**
         * Longest-first claiming fuse over the word stream: every [known]
         * candidate is offered its words in word-count order (longest first,
         * ties leftmost) and claims them only while none are already taken.
         * A fused span's surface is the VERBATIM source slice, its
         * lookupForm the space-joined [PhraseCandidate.form]; unclaimed
         * words emit alone under the [isLookupWorthy] filter, exactly as the
         * pre-phrase tokenizer did. Single-word lookupForm stays the surface
         * (not the stem): [WiktionaryDictionaryManager] handles
         * surface-first + stem-fallback internally, and emitting the stem
         * would double-stem and miss dictionary entries.
         *
         * NOT left-to-right greed (the JA re-glob's shape): a marginal early
         * bigram would consume the head of a longer expression — "of a"
         * swallowing the "a" of "a great deal" — and the packs' function-word
         * bigrams make that collision common in ordinary text. JA keeps
         * left-first greed because JMdict's rank guard keeps marginal
         * phrases out of its known set; Wiktionary has no such guard, so
         * overlap resolution must do the work here. Equal-length overlaps
         * have no data signal either way (the marginal bigrams carry HIGHER
         * inherited freq than gold ones), so leftmost is the deterministic
         * tie-break.
         */
        internal fun fuseSpans(
            text: String,
            stream: WordStream,
            candidates: List<PhraseCandidate>,
            known: Set<String>,
        ): List<TokenSpan> {
            val claimed = BooleanArray(stream.words.size)
            val matchAt = arrayOfNulls<PhraseCandidate>(stream.words.size)
            val winners = candidates
                .filter { it.form in known }
                .sortedWith(compareByDescending<PhraseCandidate> { it.wordCount }.thenBy { it.start })
            for (c in winners) {
                val span = c.start until c.start + c.wordCount
                if (span.any { claimed[it] }) continue
                for (w in span) claimed[w] = true
                matchAt[c.start] = c
            }
            val result = mutableListOf<TokenSpan>()
            var i = 0
            while (i < stream.words.size) {
                val match = matchAt[i]
                if (match != null) {
                    val surface = text.substring(
                        stream.words[i].first,
                        stream.words[i + match.wordCount - 1].last + 1,
                    )
                    result += TokenSpan(surface = surface, lookupForm = match.form, reading = null)
                    i += match.wordCount
                    continue
                }
                val word = text.substring(stream.words[i])
                if (isLookupWorthy(word)) {
                    result += TokenSpan(surface = word, lookupForm = word, reading = null)
                }
                i++
            }
            return result
        }

        /** Imported-term Yomitan lookup keys to try after the direct
         *  (original-case) surface [w] — which already matches dictionaries
         *  that store capitalized headwords (e.g. German nouns). In order,
         *  deduped against [w] and one another:
         *   - [lower]: the locale-lowercased surface. [WiktionaryDictionaryManager]
         *     queries this FIRST, so a sentence-initial capital still resolves a
         *     lowercase lemma (English-style dicts store lowercase) the built-in
         *     pack would find — without it, enrichment silently misses common
         *     capitalized words.
         *   - [stem]: the Snowball stem (when distinct from [w] and [lower]).
         *   - [folded]: the Arabic casual/variant-spelling fold.
         *  Pure + internal so the ordering is unit-testable without a pack. */
        internal fun importedTermFallbacks(
            w: String,
            lower: String,
            stem: String,
            folded: String?,
        ): List<String> = listOfNotNull(
            lower.takeIf { it != w },
            stem.takeIf { it != w && it != lower },
            folded,
        )

        /** Returns a fresh Snowball stemmer instance, or null for isolating
         *  languages with no useful stemming rules. English is the default
         *  catch-all only for unknown IDs — callers should route through
         *  the explicit branches. */
        private fun stemmerFor(id: SourceLangId): SnowballProgram? = when (id) {
            SourceLangId.EN -> EnglishStemmer()
            SourceLangId.ES -> SpanishStemmer()
            SourceLangId.FR -> FrenchStemmer()
            SourceLangId.DE -> GermanStemmer()
            SourceLangId.IT -> ItalianStemmer()
            SourceLangId.PT -> PortugueseStemmer()
            SourceLangId.NL -> DutchStemmer()
            SourceLangId.TR -> TurkishStemmer()
            SourceLangId.SV -> SwedishStemmer()
            SourceLangId.DA -> DanishStemmer()
            SourceLangId.NO -> NorwegianStemmer()
            SourceLangId.FI -> FinnishStemmer()
            SourceLangId.HU -> HungarianStemmer()
            SourceLangId.RO -> RomanianStemmer()
            SourceLangId.CA -> CatalanStemmer()
            SourceLangId.RU -> RussianStemmer()
            // Arabic: Snowball light stemmer (clitic/affix stripping + internal
            // normalization). Heavy morphology (broken plurals, weak verbs) ships
            // as position-2 alias rows in the pack, not handled here.
            SourceLangId.AR -> ArabicStemmer()
            // Vietnamese and Indonesian have no Snowball stemmer. Vietnamese
            // is fully isolating (no inflection to strip). Indonesian has
            // prefix morphology (ber-, me-, di-, ter-) that Snowball doesn't
            // model; surface-only lookup is an acceptable first pass.
            // Hindi defers the Lucene HindiStemmer (v1 = surface + form_of aliases).
            // Polish has NO Snowball stemmer at all; its inflection ships as
            // position-2 PoliMorf alias rows in the pack (build-time), so runtime
            // stemming is intentionally null. See docs/polish-source-language-plan.md.
            SourceLangId.VI, SourceLangId.ID, SourceLangId.HI, SourceLangId.PL -> null
            // Should never happen — CJK ids and Thai never reach LatinEngine
            // (each has a dedicated engine). Listed to satisfy the exhaustive when.
            SourceLangId.JA, SourceLangId.ZH, SourceLangId.ZH_HANT, SourceLangId.KO,
            SourceLangId.TH -> null
        }
    }
}
