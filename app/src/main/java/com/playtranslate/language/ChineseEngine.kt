package com.playtranslate.language

import android.content.Context
import com.hankcs.hanlp.HanLP
import com.hankcs.hanlp.corpus.io.IIOAdapter
import com.hankcs.hanlp.dictionary.py.Pinyin
import com.playtranslate.model.CharacterDetail
import com.playtranslate.model.DictionaryResponse
import com.playtranslate.model.HanziDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

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
        return PreloadResult.Success
    }

    override suspend fun tokenize(text: String): List<TokenSpan> = withContext(Dispatchers.Default) {
        val terms = HanLP.segment(text)
        terms.filter { isLookupWorthy(it.word) }
            .map { TokenSpan(surface = it.word, lookupForm = it.word, reading = null) }
    }

    override suspend fun lookup(word: String, reading: String?): DictionaryResponse? =
        dict.lookup(word, profile.preferTraditional)

    /**
     * CC-CEDICT contains most common hanzi as single-character entries with
     * pinyin + definitions, so we reuse the word-level dict path rather than
     * maintaining a separate per-character table. The highest-frequency entry
     * wins when a character has multiple senses under different readings.
     */
    override suspend fun lookupCharacter(literal: Char): CharacterDetail? {
        val response = dict.lookup(literal.toString(), profile.preferTraditional) ?: return null
        val entry = response.entries.firstOrNull() ?: return null
        val meanings = entry.senses.flatMap { it.targetDefinitions }
        if (meanings.isEmpty()) return null
        val pinyin = entry.headwords.firstOrNull()?.reading?.takeIf { it.isNotBlank() }
        return HanziDetail(
            literal = literal,
            meanings = meanings,
            pinyin = pinyin,
            isCommon = entry.isCommon == true,
            freqScore = entry.freqScore,
        )
    }

    override fun annotateForHintText(text: String): List<HintTextAnnotation> {
        val pinyinList = HanLP.convertToPinyinList(text)
        val annotations = mutableListOf<HintTextAnnotation>()
        for (i in text.indices) {
            val pinyin = pinyinList.getOrNull(i) ?: continue
            if (pinyin == Pinyin.none5) continue
            val pinyinStr = pinyin.pinyinWithToneMark ?: continue
            annotations.add(HintTextAnnotation(baseStart = i, baseEnd = i + 1, hintText = pinyinStr))
        }
        return annotations
    }

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
