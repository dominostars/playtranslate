package com.playtranslate.dictionary

import android.util.Log
import com.worksap.nlp.sudachi.Config
import com.worksap.nlp.sudachi.Dictionary
import com.worksap.nlp.sudachi.DictionaryFactory
import com.worksap.nlp.sudachi.Tokenizer
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Sudachi-backed [JapaneseTokenizer]. Owns a long-lived Sudachi [Dictionary]
 * (an mmap'd `system_*.dic`), exposed process-scoped via [Provider] and closed
 * on pack swap so the mmap is released before the `.dic` is replaced/deleted.
 *
 * A fresh Sudachi `Tokenizer` is created per [analyze] call from the
 * thread-safe [Dictionary] — Sudachi `Tokenizer`s are cheap (the heavy dict is
 * shared/mmap'd) and not thread-safe, so per-call construction sidesteps both
 * locking and stale-instance hazards across pack swaps.
 *
 * SplitMode A (UniDic short unit) per the Phase 0 measurement: best JMdict
 * hit-rate, and it composes with DictionaryManager's n-gram phrase re-glob
 * (which rebuilds compounds) while avoiding mode C's punctuation over-merge.
 */
class SudachiJapaneseTokenizer private constructor(
    private val dictionary: Dictionary,
    private val mode: Tokenizer.SplitMode,
) : JapaneseTokenizer, AutoCloseable {

    override fun analyze(text: String): List<JaToken> =
        dictionary.create().tokenize(mode, text).mapNotNull { m ->
            val surface = m.surface()
            // Skip zero-width morphemes (input-rewrite artifacts, e.g. around …).
            if (surface.isEmpty()) return@mapNotNull null
            JaToken(
                surface = surface,
                begin = m.begin(),
                end = m.end(),
                category = JaCategory.fromUniDic(m.partOfSpeech().getOrElse(0) { "" }),
                dictionaryForm = m.dictionaryForm().ifEmpty { surface },
                normalizedForm = m.normalizedForm().ifEmpty { surface },
                reading = m.readingForm().takeIf { it.isNotEmpty() },
                isOov = m.isOOV(),
            )
        }

    override fun close() = dictionary.close()

    companion object {
        private const val TAG = "SudachiTokenizer"
        private val DEFAULT_MODE = Tokenizer.SplitMode.A

        /** Build from a system dictionary file. Throws on init failure (the
         *  caller maps that to [PreloadResult.TokenizerInitFailed]). */
        fun create(
            systemDic: File,
            mode: Tokenizer.SplitMode = DEFAULT_MODE,
        ): SudachiJapaneseTokenizer {
            require(systemDic.isFile) { "Sudachi system dictionary not found: ${systemDic.absolutePath}" }
            val config = Config.defaultConfig().systemDictionary(systemDic.toPath())
            val dictionary = DictionaryFactory().create(config)
            Log.i(TAG, "Sudachi initialized from ${systemDic.absolutePath} (mode=$mode)")
            return SudachiJapaneseTokenizer(dictionary, mode)
        }
    }

    /**
     * Process-scoped provider. Mirrors the old `Deinflector.initPackDir` /
     * KOMORAN lazy pack-aware pattern, but additionally closes the previous
     * [Dictionary] on [initPackDir] / [close] because Sudachi's dict is an
     * mmap'd file handle that must be released before the pack's `.dic` is
     * swapped — `PackUpgradeOrchestrator` calls [close] at its teardown points.
     *
     * Lifetime safety: [analyze] holds the READ lock for the whole tokenize and
     * [close]/[initPackDir] take the WRITE lock, so the [Dictionary] is never
     * closed while a tokenization is in flight (kuromoji never `close()`d, so the
     * swap introduced this hazard; an in-flight `tokenize` on an unmapped dict
     * would fault or, via a catch-all, silently return empty). This mirrors
     * `DictionaryManager.withRefcount` (SQLite acquire/releaseReference).
     */
    object Provider : JapaneseTokenizer {
        @Volatile private var packDir: File? = null
        @Volatile private var current: SudachiJapaneseTokenizer? = null
        private val rw = ReentrantReadWriteLock()

        /** Point at the pack's `tokenizer/` dir; closes any previously-open dict
         *  so the next [analyze] lazily loads whatever is on disk now (handles
         *  install's safeSwap reusing the same directory path). Waits for any
         *  in-flight [analyze] before closing. Any thread. */
        fun initPackDir(dir: File?) = rw.write {
            packDir = dir
            current?.close()
            current = null
        }

        /** Build + warm now. Throws if the pack has no `system_*.dic` (caller
         *  maps that to PreloadResult.TokenizerInitFailed). */
        fun preload() {
            rw.write { if (current == null) current = build() }
            analyze("テスト")
        }

        /** Release the mmap'd dict (pack swap / shutdown), after any in-flight
         *  [analyze] completes (write lock). */
        fun close() = rw.write {
            current?.close()
            current = null
        }

        override fun analyze(text: String): List<JaToken> {
            // Build outside the read lock (rare; can't upgrade read -> write). A
            // missing / un-buildable dict degrades to empty tokens — no furigana
            // or lookups — rather than crashing; see [ensureBuilt].
            if (ensureBuilt() == null) return emptyList()
            return rw.read {
                // The read lock blocks close()/initPackDir(), so the Dictionary
                // stays open for the whole tokenize. `current` can still be null
                // if a close() landed between ensureBuilt and here -> empty. We do
                // NOT catch tokenize exceptions: with the close race gone, those
                // are real bugs and should surface, not become "successful empty".
                (current ?: return@read emptyList()).analyze(text)
            }
        }

        /** Ensure [current] is built; returns it, or null when there is no
         *  `system_*.dic` / the build failed (graceful — only pre-ja-v3 when JA
         *  is gated, so retry cost is bounded). Build failure is logged, not
         *  swallowed into a successful-looking empty tokenization. */
        private fun ensureBuilt(): SudachiJapaneseTokenizer? {
            current?.let { return it }
            return rw.write {
                current ?: runCatching { build() }
                    .onFailure { Log.w(TAG, "Sudachi build failed (no system_*.dic / pre-ja-v3): ${it.message}") }
                    .getOrNull()
                    ?.also { current = it }
            }
        }

        private fun build(): SudachiJapaneseTokenizer {
            val dir = packDir ?: error("SudachiJapaneseTokenizer.Provider: pack dir not set")
            val dic = dir.listFiles { f -> f.isFile && f.name.startsWith("system") && f.name.endsWith(".dic") }
                ?.minByOrNull { it.name }
                ?: error("No Sudachi system dictionary (system_*.dic) in ${dir.absolutePath}")
            return create(dic)
        }
    }
}
