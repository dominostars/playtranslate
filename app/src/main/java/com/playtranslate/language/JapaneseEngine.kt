package com.playtranslate.language

import android.content.Context
import com.playtranslate.dictionary.Deinflector
import com.playtranslate.dictionary.DictionaryManager
import com.playtranslate.model.CharacterDetail
import com.playtranslate.model.DictionaryResponse

/**
 * Japanese source-language engine. Thin forwarder over the existing
 * [DictionaryManager] singleton and [Deinflector] object — there's no new
 * runtime state here, just an interface-matching façade that Phase 1+ can
 * route calls through without touching the underlying implementation.
 *
 * Note: [close] is a no-op. [DictionaryManager] is a process-scoped singleton
 * that survives engine lifecycle changes; closing the dict here would break
 * any other caller that still reaches into [DictionaryManager.get] directly.
 */
class JapaneseEngine(private val appContext: Context) : SourceLanguageEngine {

    override val profile: SourceLanguageProfile = SourceLanguageProfiles[SourceLangId.JA]

    private val dict: DictionaryManager = DictionaryManager.get(appContext)

    init {
        // Point Kuromoji at the pack's tokenizer/ directory BEFORE any
        // engine method runs. The Deinflector's tokenizer is process-
        // scoped and lazy; doing this here (at engine construction,
        // which SourceLanguageEngines.get guarantees happens before any
        // tokenize/annotateForHintText call) closes the cold-start race
        // where a UI caller on the main dispatcher could fire before
        // MainActivity's IO-dispatched preload() set the pack dir.
        // If the pack predates the tokenizer-migration (no tokenizer/
        // subdir exists), Deinflector's lazy builder falls back to
        // classpath-backed Kuromoji — which only works if the APK
        // hasn't been resource-stripped yet. Ctor is fine for this:
        // it's just path computation, no disk I/O.
        Deinflector.initPackDir(
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
        val warmup = runCatching { Deinflector.preload() }
        if (warmup.isFailure) {
            // Kuromoji init threw. Very likely a pack issue (we just
            // pointed at pack dir), but could also be OOM mid-dict
            // deserialization (33 MB of .bin files) or other runtime
            // pressure. Don't auto-delete; let the caller log + retry.
            return PreloadResult.TokenizerInitFailed(
                "Kuromoji warm-up failed: ${warmup.exceptionOrNull()?.message ?: "unknown"}"
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

    override suspend fun lookupCharacter(literal: Char): CharacterDetail? =
        dict.lookupKanji(literal)

    override fun annotateForHintText(text: String): List<HintTextAnnotation> =
        dict.tokenizeForFurigana(text).map {
            HintTextAnnotation(
                baseStart = it.startOffset,
                baseEnd = it.endOffset,
                hintText = it.reading,
            )
        }

    override fun close() {
        // Intentionally empty — see class doc.
    }
}
