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

    override suspend fun tokenize(text: String): List<TokenSpan> = withContext(Dispatchers.Default) {
        val terms = HanLP.segment(text)
        // Context-resolved per-surface reading corrections for heteronyms
        // (东西 dōngxī/dōngxi, standalone 还 hái/huán), carried on
        // TokenSpan.reading as a hint that lookup() honors. Setting it HERE is
        // the single source of truth: every reading writer funnels through
        // tokenize → resolver.lookup → lookup (the result-screen ViewModel, the
        // Anki word cache, drag/one-tap), so all of them get the corrected
        // reading without per-call-site patching. Absent for unambiguous or
        // conflicting surfaces → freq-default, exactly as before.
        val corrections = contextualReadings(terms, text)
        terms.filter { isLookupWorthy(it.word) }
            .map { TokenSpan(surface = it.word, lookupForm = it.word, reading = corrections[it.word]) }
    }

    override suspend fun lookup(word: String, reading: String?): DictionaryResponse? =
        dict.lookup(word, profile.preferTraditional)?.preferReading(reading)

    /**
     * CC-CEDICT contains most common hanzi as single-character entries with
     * pinyin + definitions, so we reuse the word-level dict path rather than
     * maintaining a separate per-character table. The highest-frequency entry
     * wins when a character has multiple senses under different readings.
     */
    override suspend fun lookupCharacter(literal: Char, targetLang: String): CharacterDetail? {
        val response = dict.lookup(literal.toString(), profile.preferTraditional) ?: return null
        val entry = response.entries.firstOrNull() ?: return null
        val meanings = entry.senses.flatMap { it.targetDefinitions }
        if (meanings.isEmpty()) return null
        // Headword.reading comes through buildEntry already tone-marked; run
        // it through PinyinFormatter anyway (idempotent) so the hanzi row is
        // guaranteed to match the definition's tone-mark format even if the
        // upstream contract ever changes.
        val pinyin = entry.headwords.firstOrNull()?.reading
            ?.takeIf { it.isNotBlank() }
            ?.let { PinyinFormatter.numberedToToneMarks(it) }
        return HanziDetail(
            literal = literal,
            meanings = meanings,
            pinyin = pinyin,
            isCommon = entry.isCommon == true,
            freqScore = entry.freqScore,
        )
    }

    override suspend fun annotateForHintText(text: String): List<HintTextAnnotation> =
        withContext(Dispatchers.Default) {
            val pinyinList = HanLP.convertToPinyinList(text)
            val annotations = mutableListOf<HintTextAnnotation>()
            for (i in text.indices) {
                val pinyin = pinyinList.getOrNull(i) ?: continue
                if (pinyin == Pinyin.none5) continue
                val pinyinStr = pinyin.pinyinWithToneMark ?: continue
                annotations.add(HintTextAnnotation(baseStart = i, baseEnd = i + 1, hintText = pinyinStr))
            }
            annotations
        }

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
            // CC-CEDICT lookup is surface-keyed, so resolve candidates once per surface.
            val candidates = candidatesBySurface.getOrPut(word) {
                // Cancellation propagates; a dictionary hiccup (e.g. the DB
                // closed by a live-mode sentence swap mid-send) degrades to no
                // correction for this surface rather than aborting the send.
                runCatchingNonCancellable {
                    dict.lookup(word, profile.preferTraditional)
                        ?.entries
                        ?.mapNotNull { it.headwords.firstOrNull()?.reading?.takeIf(String::isNotBlank) }
                        ?.distinct()
                        .orEmpty()
                } ?: emptyList()
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
