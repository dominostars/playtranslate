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

        /** Build + warm now via the STRICT path, PROPAGATING any failure so
         *  JapaneseEngine.preload can map it to PreloadResult.TokenizerInitFailed.
         *  (Unlike the public [analyze], this does not swallow failures.) */
        fun preload() {
            tokenizeStrict("テスト")
        }

        /** Release the mmap'd dict (pack swap / shutdown), after any in-flight
         *  [analyze] completes (write lock). */
        fun close() = rw.write {
            current?.close()
            current = null
        }

        /**
         * Public, FAIL-SOFT entry point: never throws. annotateForHintText runs
         * this synchronously on the main thread, and the SourceLanguageEngine
         * contract requires non-preload callers to degrade to empty on tokenizer
         * failure rather than crash the UI. The close-during-tokenize race is
         * prevented by the read lock in [tokenizeStrict] (NOT by this catch), so
         * this only absorbs genuine build / runtime-tokenize failures — logged
         * WITH the stack (not silently dropped), so real bugs stay visible.
         */
        override fun analyze(text: String): List<JaToken> = try {
            tokenizeStrict(text)
        } catch (noDict: NoDictException) {
            // Expected: the installed pack has no system_*.dic yet (pre-ja-v3).
            // Degrade quietly to no tokens — preload() surfaces this as
            // TokenizerInitFailed and the launch-time orchestrator upgrades.
            Log.i(TAG, "JA tokenizer dict not present; no tokens (${noDict.message})")
            emptyList()
        } catch (t: Throwable) {
            // Unexpected: a built dict threw at tokenize time (corrupt dict, OOM,
            // Sudachi-internal error). Contract: analyze is TOTAL / never-throws —
            // annotateForHintText runs it synchronously on the main thread, so it
            // must degrade rather than crash the UI. We catch Throwable (incl.
            // Error) deliberately, but log LOUDLY (ERROR + stack) so the failure
            // is visible, never silently dropped. preload() does NOT take this
            // path, so init failures still surface.
            Log.e(TAG, "Sudachi analyze failed unexpectedly; degrading to empty tokens", t)
            emptyList()
        }

        /** Strict tokenize: builds if needed, then tokenizes under the read lock,
         *  PROPAGATING build/tokenize failures. Shared by [preload] (which surfaces
         *  them) and [analyze] (which absorbs them into empty tokens). */
        private fun tokenizeStrict(text: String): List<JaToken> {
            tokenizerOverrideForTest?.let { return it.analyze(text) }
            ensureBuilt()
            return rw.read {
                // The read lock blocks close()/initPackDir(), so the Dictionary
                // stays open for the whole tokenize. `current` is null only if a
                // close() landed between ensureBuilt and here -> empty (not a fault).
                current?.analyze(text) ?: emptyList()
            }
        }

        /** Build [current] if absent, PROPAGATING build failure (no `system_*.dic`,
         *  I/O, etc.). The caller decides whether to surface it ([preload]) or
         *  absorb it into empty tokens ([analyze]). */
        private fun ensureBuilt() {
            if (current != null) return
            rw.write { if (current == null) current = build() }
        }

        /** Test-only seam: when non-null, [tokenizeStrict] delegates here instead
         *  of the real [Dictionary], so unit tests can exercise the contract
         *  (analyze degrades to empty, preload propagates) without a packaged
         *  `.dic`. Never set in production. */
        @Volatile internal var tokenizerOverrideForTest: JapaneseTokenizer? = null

        /** Thrown by [build] when the installed pack has no system_*.dic yet
         *  (pre-ja-v3). Lets [analyze] treat "no dict" as expected (quiet empty)
         *  while any OTHER failure is logged loudly; [preload] propagates either
         *  so JapaneseEngine reports TokenizerInitFailed. */
        private class NoDictException(message: String) : Exception(message)

        private fun build(): SudachiJapaneseTokenizer {
            val dir = packDir ?: throw NoDictException("pack dir not set")
            val dic = dir.listFiles { f -> f.isFile && f.name.startsWith("system") && f.name.endsWith(".dic") }
                ?.minByOrNull { it.name }
                ?: throw NoDictException("no system_*.dic in ${dir.absolutePath}")
            return create(dic)
        }
    }
}
