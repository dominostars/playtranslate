package com.playtranslate.language

import android.content.Context
import com.hankcs.hanlp.HanLP
import com.hankcs.hanlp.corpus.io.IIOAdapter
import com.hankcs.hanlp.dictionary.py.Pinyin
import com.hankcs.hanlp.seg.common.Term
import com.playtranslate.model.CharacterDetail
import com.playtranslate.model.DictionaryResponse
import com.playtranslate.model.HanziDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Like [runCatching] but cancellation-safe: rethrows [CancellationException]
 * so coroutine cancellation still propagates, and degrades any OTHER failure
 * to null. Bare `runCatching` in a coroutine swallows cancellation, which can
 * let an abandoned send fall through and still create a card. Also lets
 * [Error] (OOM, etc.) propagate — only [Exception] degrades.
 */
internal inline fun <T> runCatchingNonCancellable(block: () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

/**
 * Floats the entry whose headword reading matches [reading] to the front, so
 * `entries.first()` / `headwordDisplay` reflect a context-resolved heteronym
 * choice. A null/blank hint, or no matching entry, leaves the response in its
 * original frequency order. Extracted and `internal` so the selection is
 * JVM-unit-testable without HanLP.
 */
internal fun DictionaryResponse.preferReading(reading: String?): DictionaryResponse {
    if (reading.isNullOrEmpty()) return this
    val idx = entries.indexOfFirst { e -> e.headwords.any { it.reading == reading } }
    if (idx <= 0) return this
    return copy(entries = listOf(entries[idx]) + entries.filterIndexed { i, _ -> i != idx })
}

/**
 * Chinese source-language engine. Uses HanLP's CRF/perceptron segmenter for
 * word-level tokenization (handles both Simplified and Traditional, resolves
 * ambiguity using context, and supports custom dictionaries for game-specific
 * terms via `CustomDictionary.add`). Dictionary lookups go through
 * [ChineseDictionaryManager] against a CC-CEDICT-derived pack.
 *
 * HanLP's first `segment()` call deserializes the CRF model (~1-2s).
 * [preload] triggers this on the background IO thread so the user's first
 * capture doesn't stall.
 */
class ChineseEngine(
    private val appContext: Context,
    private val langId: SourceLangId = SourceLangId.ZH,
) : SourceLanguageEngine {

    override val profile: SourceLanguageProfile = SourceLanguageProfiles[langId]

    private val dict: ChineseDictionaryManager = ChineseDictionaryManager.get(appContext)

    private val yomitan = YomitanEnrichment(appContext, langId.yomitanConsumingLang())

    init {
        // Redirect HanLP's file reads to our pack's tokenizer/ dir BEFORE
        // any HanLP.segment() / convertToPinyinList() call, closing the
        // cold-start race where a UI caller on the main dispatcher could
        // fire HanLP.segment before MainActivity's IO-dispatched preload
        // runs. Matches the JA Deinflector.initPackDir pattern.
        //
        // HanLP.Config.IOAdapter is a public-static slot for a custom
        // IIOAdapter. When null, HanLP reads via classpath/filesystem
        // using its Config.*Path fields (which default to relative paths
        // like "data/dictionary/CoreNatureDictionary.txt"). We install a
        // pack-aware adapter that routes those relative paths to the
        // installed pack's tokenizer/ dir.
        //
        // Fallback: if the pack has no tokenizer/ subdir (pre-migration
        // pack), our adapter falls through to classpath — harmless while
        // the APK still has the resources, broken once the APK strip
        // lands. Same transition risk profile as JA/KO.
        //
        // ZH_HANT shares the ZH pack via SourceLangId.packId, so
        // SourceLanguageEngines only caches one ChineseEngine instance
        // per process. Setting the JVM-global Config.IOAdapter once in
        // init is safe.
        val packTokenizerDir = LanguagePackStore.dirFor(appContext, langId).resolve("tokenizer")
        if (packTokenizerDir.isDirectory) {
            HanLP.Config.IOAdapter = PackAwareHanlpAdapter(packTokenizerDir)
        }
    }

    override suspend fun preload(): PreloadResult {
        if (!LanguagePackStore.isInstalled(appContext, langId)) {
            return PreloadResult.PackMissing
        }
        val warmed = withContext(Dispatchers.IO) {
            runCatching { HanLP.segment("预热") }
        }
        if (warmed.isFailure) {
            // HanLP first-segment init failed. Pre-ZH-migration, model
            // data is in the APK classpath — failure is almost certainly
            // OOM or JVM-level, not a pack integrity issue. Don't delete
            // the pack; let the caller log and the next action retry.
            return PreloadResult.TokenizerInitFailed(
                "HanLP warm-up failed: ${warmed.exceptionOrNull()?.message ?: "unknown"}"
            )
        }
        if (dict.preload() == null) {
            return PreloadResult.PackCorrupt("ZH dict.sqlite failed to open")
        }
        // HanLP's portable mini CoreDictionary is missing many CC-CEDICT
        // compounds (赋能, 用户体验, etc.) so it splits them into single
        // characters even on whitespace-clean input. Lookups against
        // dict.sqlite never fire for those splits because tokenize stops
        // at the broken boundary. Inject every CC-CEDICT headword into
        // HanLP's runtime BinTrie via CustomDictionary.add — ViterbiSegment
        // checks both the static .bin DAT (HanLP's curated entries) and
        // the runtime BinTrie, so this augments without displacing.
        // Single-char entries are skipped so we don't disrupt HanLP's
        // tuned single-hanzi frequencies.
        dict.injectCustomDictEntriesOnce()
        return PreloadResult.Success
    }

    /** tokenize() is a projection of annotate(WORDS): term spans with the
     *  heteronym-corrected reading as the lookup hint (the TokenSpan.reading
     *  contract every reading writer already funnels through). */
    override suspend fun tokenize(text: String): List<TokenSpan> =
        annotate(text, AnnotationDepth.WORDS).spans
            .filter { it.lookupForm != null }
            .map { TokenSpan(surface = it.surface, lookupForm = it.lookupForm!!, reading = it.lookupHint) }

    override suspend fun searchPrefix(query: String, limit: Int): List<TokenSpan> =
        dict.searchPrefix(query, limit, profile.preferTraditional)
            .map { TokenSpan(surface = it, lookupForm = it, reading = null) }

    override suspend fun lookup(word: String, reading: String?): DictionaryResponse? =
        yomitan.applyTo(
            // Verbatim former body — the ?.preferReading(reading) heteronym
            // reorder must survive the wrap. CC-CEDICT reading is pinyin, not
            // a Yomitan reading key, so the imported term lookup keys on null.
            dict.lookup(word, profile.preferTraditional)?.preferReading(reading),
            word, reading = null, fallbackForms = emptyList(),
        )

    /**
     * CC-CEDICT contains most common hanzi as single-character entries with
     * pinyin + definitions, so we reuse the word-level dict path rather than
     * maintaining a separate per-character table. The highest-frequency entry
     * wins when a character has multiple senses under different readings.
     */
    override suspend fun lookupCharacter(literal: Char, targetLang: String): CharacterDetail? {
        val entry = dict.lookup(literal.toString(), profile.preferTraditional)?.entries?.firstOrNull()
        val imported = yomitan.importedKanji(literal)
        // Imported zh-source kanji meanings win when present (the user installed
        // the dict and ordered it), carrying their declared language; else the
        // CC-CEDICT single-char glosses. Mirrors JapaneseEngine.lookupCharacter.
        val meanings: List<String>
        val meaningsLang: String
        if (imported != null && imported.meanings.isNotEmpty()) {
            meanings = imported.meanings
            meaningsLang = imported.meaningsLang
        } else {
            meanings = entry?.senses?.flatMap { it.targetDefinitions }.orEmpty()
            meaningsLang = "en"
        }
        if (meanings.isEmpty()) return null
        // Pinyin/readings stay CC-CEDICT — Yomitan kanji on/kun readings don't
        // map to pinyin. Headword.reading is already tone-marked; reformat
        // (idempotent) so the hanzi row matches the definition's tone format.
        val pinyin = entry?.headwords?.firstOrNull()?.reading
            ?.takeIf { it.isNotBlank() }
            ?.let { PinyinFormatter.numberedToToneMarks(it) }
        return HanziDetail(
            literal = literal,
            meanings = meanings,
            pinyin = pinyin,
            isCommon = entry?.isCommon == true,
            freqScore = entry?.freqScore ?: 0,
            meaningsLang = meaningsLang,
            frequencies = yomitan.kanjiFrequencies(literal),
        )
    }

    /**
     * ZH annotation: HanLP terms anchored to the source text (greedy from a
     * running cursor, the same alignment [contextualReadings] uses), each
     * span carrying per-CHARACTER pinyin parts — the in-app display
     * granularity — plus the context-resolved word reading on
     * [AnnotatedSpan.reading] for heteronym-corrected surfaces (null
     * otherwise; hydration's lookup then uses the frequency default,
     * exactly like the TokenSpan.reading contract). A term whose normalized
     * surface isn't in the source text degrades to an OFFSETLESS span: it
     * still feeds the words projection, and the display renderer simply has
     * nothing to place — today's behavior for those terms. Text between and
     * around terms becomes per-char spans, so every hanzi keeps its pinyin
     * (the legacy hint path annotated all characters, not just term
     * members).
     */
    /** FULL-depth annotations for live overlay lines; generation-checked
     *  against Yomitan imports, cleared on [close]. */
    private val annotationCache = AnnotationCache()

    override suspend fun annotate(text: String, depth: AnnotationDepth): SentenceAnnotation {
        if (depth == AnnotationDepth.FULL) {
            annotationCache.get(text)?.let { return it }
        }
        val result = annotateUncached(text, depth)
        if (depth == AnnotationDepth.FULL) annotationCache.put(result)
        return result
    }

    private suspend fun annotateUncached(text: String, depth: AnnotationDepth): SentenceAnnotation =
        withContext(Dispatchers.Default) {
            if (text.isEmpty()) {
                return@withContext SentenceAnnotation(text, profile.id, 0, emptyList())
            }
            val terms = runCatchingNonCancellable { HanLP.segment(text) }
                ?: return@withContext SentenceAnnotation.plain(text, profile.id)
            val charPinyin = runCatchingNonCancellable { HanLP.convertToPinyinList(text) }
            // Corrections at WORDS and FULL — tokenize() has always carried
            // them (its callers' lookups honor the hint); only the TOKENS
            // display path, which never consulted them, skips the pass.
            val corrections =
                if (depth != AnnotationDepth.TOKENS) contextualReadings(terms, text) else emptyMap()
            val spans = mutableListOf<AnnotatedSpan>()
            var cursor = 0
            var emitted = 0
            fun emitGapUpTo(pos: Int) {
                if (pos > emitted) spans.add(charSpan(text, emitted, pos, charPinyin, null, null))
            }
            for (term in terms) {
                val word = term.word ?: continue
                if (word.isEmpty()) continue
                val found = text.indexOf(word, cursor)
                if (found < 0) {
                    if (isLookupWorthy(word)) {
                        spans.add(AnnotatedSpan(
                            start = -1, end = -1, surface = word,
                            lookupForm = word, reading = corrections[word],
                            lookupHint = corrections[word],
                        ))
                    }
                    continue
                }
                cursor = found + word.length
                emitGapUpTo(found)
                spans.add(charSpan(
                    text, found, found + word.length, charPinyin,
                    lookupForm = word.takeIf { isLookupWorthy(it) },
                    reading = corrections[word],
                    lookupHint = corrections[word],
                ))
                emitted = found + word.length
            }
            emitGapUpTo(text.length)
            SentenceAnnotation(text, profile.id, AnnotationGenerations.current(), spans)
        }

    /** One anchored span with per-character pinyin parts. */
    private fun charSpan(
        text: String,
        start: Int,
        end: Int,
        charPinyin: List<Pinyin>?,
        lookupForm: String?,
        reading: String?,
        lookupHint: String? = null,
    ): AnnotatedSpan {
        val surface = text.substring(start, end)
        val parts = surface.mapIndexed { k, c ->
            val p = charPinyin?.getOrNull(start + k)
            val py = if (p != null && p != Pinyin.none5) p.pinyinWithToneMark else null
            ReadingPart(c.toString(), py)
        }
        return AnnotatedSpan(
            start = start, end = end, surface = surface,
            lookupForm = lookupForm, reading = reading, lookupHint = lookupHint,
            furigana = parts,
        )
    }

    /** Legacy hint API, now a projection of [annotate] — per-character
     *  pinyin annotations, byte-parity with the old convertToPinyinList
     *  walk. TOKENS depth skips the heteronym pass the display never used. */
    override suspend fun annotateForHintText(text: String): List<HintTextAnnotation> =
        annotate(text, AnnotationDepth.TOKENS).hintAnnotations()

    /**
     * Per-surface, context-resolved pinyin OVERRIDES for the heteronyms in
     * [text] — a `{surface → reading}` map containing ONLY surfaces whose
     * contextual reading differs from CC-CEDICT's frequency default. The
     * caller applies it over the existing surface-keyed word readings before
     * building the card, so the existing (robust, surface-keyed) annotation
     * path renders the contextually-correct pinyin with no second code path.
     *
     * Heteronym fix: CC-CEDICT lists readings per entry and the display
     * defaults to the most frequent, so homographs (东西 dōngxī/dōngxi, 大夫
     * dàfū/dàifu) and standalone heteronyms (地 dì/de, 还 hái/huán) could show
     * the wrong pinyin. Here we reuse the caller's HanLP segmentation, take
     * HanLP's phrase-aware per-hanzi pinyin for the whole sentence (the
     * convertToPinyinList signal the live overlay already uses), and for each
     * AMBIGUOUS surface pick the CC-CEDICT candidate whose syllables best match
     * that context.
     *
     * Per-surface, first-occurrence-wins: a surface that genuinely takes two
     * different readings in one sentence resolves to the first occurrence's —
     * a rare, bounded limitation that is the price of keeping this to a single
     * annotation path. Anything not in the map keeps its frequency-default
     * reading, so coverage and robustness stay exactly the existing path's.
     */
    private suspend fun contextualReadings(terms: List<Term>, text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        // HanLP's phrase-aware per-hanzi pinyin is the context signal — computed
        // lazily so a sentence with no ambiguous surface skips the extra pass.
        // The caller's already-segmented `terms` are reused, so we never
        // re-segment (tokenize already paid for HanLP.segment).
        val charPinyin: List<Pinyin>? by lazy { runCatchingNonCancellable { HanLP.convertToPinyinList(text) } }
        val occurrences = mutableListOf<HeteronymOccurrence>()
        val candidatesBySurface = HashMap<String, List<String>>()
        var cursor = 0
        for (term in terms) {
            val word = term.word ?: continue
            if (word.isEmpty()) continue
            // Align the term to the source text so we can slice its per-char
            // pinyin; greedy from the running cursor is robust to whitespace
            // HanLP may drop between terms.
            val found = text.indexOf(word, cursor)
            val begin = if (found >= 0) found else cursor
            cursor = begin + word.length
            if (!word.any(::isHanziChar)) continue
            if (found < 0) continue              // normalized surface, not in source text
            // CC-CEDICT lookup is surface-keyed, so resolve candidates once per
            // surface. Reading-only query (no sense/gloss build) — we only need
            // the candidate readings to detect a heteronym and disambiguate.
            // Cancellation propagates; a dict hiccup degrades to no correction
            // for this surface rather than aborting the send.
            val candidates = candidatesBySurface.getOrPut(word) {
                runCatchingNonCancellable { dict.candidateReadings(word) } ?: emptyList()
            }
            if (candidates.size < 2) continue    // unambiguous → frequency default is correct
            // Record EVERY ambiguous occurrence with its positional context;
            // resolveOverrides decides per surface and suppresses any surface
            // whose occurrences disagree, so a repeat is never made worse.
            occurrences += HeteronymOccurrence(word, candidates, contextSyllables(word, begin, charPinyin))
        }
        return PinyinDisambiguator.resolveOverrides(occurrences)
    }

    /**
     * HanLP's per-hanzi tone-marked pinyin for [word] at [begin] in the
     * analyzed text. Returns null (→ caller uses frequency-first) when the
     * word isn't pure hanzi or any character lacks a pinyin, so the syllable
     * list always aligns 1:1 with the hanzi a candidate reading splits into.
     */
    private fun contextSyllables(word: String, begin: Int, charPinyin: List<Pinyin>?): List<String>? {
        if (charPinyin == null || !word.all(::isHanziChar)) return null
        val syllables = ArrayList<String>(word.length)
        for (k in word.indices) {
            val p = charPinyin.getOrNull(begin + k) ?: return null
            if (p == Pinyin.none5) return null
            syllables.add(p.pinyinWithToneMark ?: return null)
        }
        return syllables
    }

    private fun isHanziChar(c: Char): Boolean =
        c.code in 0x4e00..0x9fff || c.code in 0x3400..0x4dbf

    override fun close() {
        annotationCache.clear()
        dict.close()
    }

    private fun isLookupWorthy(token: String): Boolean {
        if (token.isBlank()) return false
        if (token.all { it.code <= 0x7F }) return false
        return true
    }
}

/**
 * HanLP [IIOAdapter] that routes file reads through the installed ZH
 * source pack's `tokenizer/` directory instead of the APK classpath.
 *
 * HanLP's `Config.*Path` fields default to relative paths rooted at
 * `data/` (e.g. `"data/dictionary/CoreNatureDictionary.txt"`). When this
 * adapter is installed, [open] maps those relative paths onto the pack
 * directory: `data/dictionary/X` → `<packTokenizerDir>/data/dictionary/X`.
 *
 * Classpath fallback: if the requested file isn't in the pack (pre-
 * migration pack with only dict.sqlite, or a HanLP feature we didn't
 * anticipate), we fall back to [Class.getResourceAsStream] with a
 * leading slash so it's interpreted as an absolute classpath path. This
 * keeps pre-APK-strip builds working and stays resilient to HanLP
 * loading auxiliary files we didn't explicitly ship.
 *
 * [create] is not exercised in the read-only tokenization + pinyin paths
 * this app uses, but the IIOAdapter contract requires an implementation.
 * We route create to the pack dir (even though it's under noBackupFilesDir
 * it's still writable) so corrupt-file regeneration by HanLP wouldn't
 * silently fail.
 */
private class PackAwareHanlpAdapter(private val packTokenizerDir: File) : IIOAdapter {
    override fun open(path: String): InputStream {
        // HanLP passes paths like "data/dictionary/...", "data/model/..."
        // — we strip any leading slashes and resolve under packTokenizerDir.
        val relative = path.removePrefix("/").removePrefix("\\")
        val packFile = File(packTokenizerDir, relative)
        if (packFile.isFile) {
            return FileInputStream(packFile)
        }
        // Fallback to classpath. HanLP's relative-path convention matches
        // the JAR resource layout verbatim, so prefix with "/" for absolute
        // classpath lookup.
        val cp = HanLP::class.java.getResourceAsStream("/$relative")
            ?: throw java.io.IOException(
                "HanLP resource not found in pack (${packFile.absolutePath}) or classpath: $path"
            )
        return cp
    }

    override fun create(path: String): OutputStream {
        val relative = path.removePrefix("/").removePrefix("\\")
        val packFile = File(packTokenizerDir, relative)
        packFile.parentFile?.mkdirs()
        return FileOutputStream(packFile)
    }
}
