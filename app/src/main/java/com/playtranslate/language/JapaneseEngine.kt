package com.playtranslate.language

import android.content.Context
import com.playtranslate.dictionary.DictionaryManager
import com.playtranslate.dictionary.SudachiJapaneseTokenizer
import com.playtranslate.model.CharacterDetail
import com.playtranslate.model.DictionaryResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Japanese source-language engine. Thin forwarder over the existing
 * [DictionaryManager] singleton and [Deinflector] object — there's no new
 * runtime state here, just an interface-matching façade that Phase 1+ can
 * route calls through without touching the underlying implementation.
 *
 * [close] releases JA's process-scoped native handles — the Sudachi Provider
 * mmap and the [DictionaryManager] SQLite handle. Pack uninstall evicts the
 * engine via [SourceLanguageEngines.releaseForPack] and then deletes the pack
 * dir, so close() is the contract point that has to drop those handles first.
 * Both reopen lazily, so closing is safe even if another reference survives.
 */
class JapaneseEngine(private val appContext: Context) : SourceLanguageEngine {

    override val profile: SourceLanguageProfile = SourceLanguageProfiles[SourceLangId.JA]

    private val dict: DictionaryManager = DictionaryManager.get(appContext)

    init {
        // Point the Sudachi tokenizer at the pack's tokenizer/ directory BEFORE
        // any engine method runs. The Provider is process-scoped and lazy; doing
        // this at engine construction (which SourceLanguageEngines.get guarantees
        // happens before any tokenize/annotateForHintText call) closes the
        // cold-start race where a UI caller on the main dispatcher could fire
        // before MainActivity's IO-dispatched preload() set the pack dir. If the
        // installed pack predates ja-v3 (no system_*.dic), the lazy build throws
        // and preload() reports TokenizerInitFailed. Ctor is just path
        // computation, no disk I/O.
        SudachiJapaneseTokenizer.Provider.initPackDir(
            LanguagePackStore.dirFor(appContext, SourceLangId.JA).resolve("tokenizer")
        )
    }

    override suspend fun preload(): PreloadResult {
        if (!LanguagePackStore.isInstalled(appContext, SourceLangId.JA)) {
            return PreloadResult.PackMissing
        }
        val db = dict.preload()
        if (db == null) {
            // SQLite open failed — dict.sqlite missing, truncated, or
            // schema-stale. Confirmed on-disk issue. Safe to uninstall.
            return PreloadResult.PackCorrupt("JA dict.sqlite failed to open")
        }
        val warmup = runCatching { SudachiJapaneseTokenizer.Provider.preload() }
        if (warmup.isFailure) {
            // Sudachi init/warm-up threw. Most likely the installed pack has no
            // system_*.dic yet (pre-ja-v3), but could also be OOM or other
            // runtime pressure. Don't auto-delete; let the caller log + retry
            // (the launch-time PackUpgradeOrchestrator drives the ja-v3 upgrade).
            return PreloadResult.TokenizerInitFailed(
                "Sudachi warm-up failed: ${warmup.exceptionOrNull()?.message ?: "unknown"}"
            )
        }
        return PreloadResult.Success
    }

    override suspend fun tokenize(text: String): List<TokenSpan> =
        dict.tokenizeWithSurfaces(text).map {
            TokenSpan(surface = it.surface, lookupForm = it.lookupForm, reading = it.reading)
        }

    override suspend fun lookup(word: String, reading: String?): DictionaryResponse? =
        dict.lookup(word, reading)

    override suspend fun lookupCharacter(literal: Char, targetLang: String): CharacterDetail? =
        dict.lookupKanji(literal, targetLang)

    override suspend fun annotateForHintText(text: String): List<HintTextAnnotation> =
        withContext(Dispatchers.Default) {
            dict.tokenizeForFurigana(text).map {
                HintTextAnnotation(
                    baseStart = it.startOffset,
                    baseEnd = it.endOffset,
                    hintText = it.reading,
                )
            }
        }

    override suspend fun spokenForm(text: String): String =
        withContext(Dispatchers.Default) {
            // A tokenizer failure falls back to the surface — same text the
            // engine got before this hook existed, never a crash.
            runCatching { Deinflector.spokenForm(text) }.getOrDefault(text)
        }

    override fun close() {
        // Release JA's process-scoped native handles so pack uninstall doesn't
        // leak them. The engine cache only evicts (SourceLanguageEngines.
        // releaseForPack, via LanguagePackStore.uninstall) when the pack is
        // going away, and uninstall() closes through here and THEN deletes the
        // pack dir — so without these closes the Sudachi mmap and the JMdict
        // SQLite handle stay bound to the unlinked files until process death,
        // and already-resolved engine references keep serving stale tokens /
        // lookups. Both reopen lazily (Provider on the next engine's
        // initPackDir, DictionaryManager on the next ensureOpen; refcounting
        // keeps any in-flight query valid), so closing is safe even if a
        // reference survives. PackUpgradeOrchestrator still closes both at its
        // teardown points — now idempotent belt-and-suspenders.
        SudachiJapaneseTokenizer.Provider.close()
        dict.close()
    }
}
