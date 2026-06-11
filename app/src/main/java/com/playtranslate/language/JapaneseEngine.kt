package com.playtranslate.language

import android.content.Context
import com.playtranslate.dictionary.Deinflector
import com.playtranslate.dictionary.DictionaryManager
import com.playtranslate.dictionary.SudachiJapaneseTokenizer
import com.playtranslate.model.CharacterDetail
import com.playtranslate.model.DictionaryResponse
import com.playtranslate.model.KanjiDetail
import com.playtranslate.yomitan.YomitanDataStore
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

    override suspend fun searchPrefix(query: String, limit: Int): List<TokenSpan> =
        dict.searchPrefix(query, limit).map {
            TokenSpan(surface = it.surface, lookupForm = it.lookupForm, reading = it.reading)
        }

    override suspend fun lookup(word: String, reading: String?): DictionaryResponse? =
        dict.lookup(word, reading)?.let { enrichWithYomitan(it) }

    /** Attaches pitch-accent downsteps and per-dictionary frequency tags
     *  from imported Yomitan dictionaries to each headword — one batched
     *  query per data type per lookup, over the same (term, reading) pairs.
     *  The facade gates each on "any such dictionary installed", so this is
     *  a no-op map lookup for everyone else. */
    private suspend fun enrichWithYomitan(response: DictionaryResponse): DictionaryResponse {
        val pairs = response.entries
            .flatMap { it.headwords }
            .mapNotNull { hw ->
                val term = hw.written ?: hw.reading ?: return@mapNotNull null
                term to (hw.reading ?: term)
            }
            .distinct()
        val pitch = YomitanDataStore.pitchFor(appContext, pairs)
        val frequencies = YomitanDataStore.frequencyFor(appContext, pairs)
        if (pitch.isEmpty() && frequencies.isEmpty()) return response
        return response.copy(
            entries = response.entries.map { entry ->
                entry.copy(
                    headwords = entry.headwords.map { hw ->
                        val term = hw.written ?: hw.reading ?: return@map hw
                        val key = term to (hw.reading ?: term)
                        val downsteps = pitch[key]
                        val tags = frequencies[key]
                        if (downsteps.isNullOrEmpty() && tags.isNullOrEmpty()) hw
                        else hw.copy(
                            pitch = downsteps ?: hw.pitch,
                            frequencies = tags ?: hw.frequencies,
                        )
                    },
                )
            },
        )
    }

    /**
     * Per-character detail merged from two sources: the first imported
     * Yomitan kanji dictionary containing the character wins the lexical
     * content (meanings; readings when non-empty), while the built-in
     * KANJIDIC2 pack stays the floor — filling whatever the import lacks
     * and always supplying the numeric stats (imports carry theirs under
     * dictionary-specific keys we don't interpret). Gloss-less characters
     * return null exactly as before imports existed: a row with readings
     * but no meaning isn't worth a breakdown slot.
     */
    override suspend fun lookupCharacter(literal: Char, targetLang: String): CharacterDetail? {
        val base = dict.lookupKanji(literal, targetLang)
        val imported = YomitanDataStore.kanjiFor(appContext, listOf(literal))[literal]
        val meanings: List<String>
        val meaningsLang: String
        if (imported != null && imported.meanings.isNotEmpty()) {
            meanings = imported.meanings
            meaningsLang = imported.meaningsLang
        } else {
            meanings = base?.meanings.orEmpty()
            meaningsLang = base?.meaningsLang ?: "en"
        }
        if (meanings.isEmpty()) return null
        // A combined (unsplit) readings list replaces BOTH labelled lines —
        // mixing KANJIDIC2's on readings back in would duplicate readings
        // the combined list already carries.
        val combined = imported?.combinedReadings.orEmpty()
        return KanjiDetail(
            literal = literal,
            meanings = meanings,
            meaningsLang = meaningsLang,
            onReadings = if (combined.isNotEmpty()) emptyList()
                else imported?.onReadings?.ifEmpty { null } ?: base?.onReadings.orEmpty(),
            kunReadings = if (combined.isNotEmpty()) emptyList()
                else imported?.kunReadings?.ifEmpty { null } ?: base?.kunReadings.orEmpty(),
            jlpt = base?.jlpt ?: 0,
            grade = base?.grade ?: 0,
            strokeCount = base?.strokeCount ?: 0,
            frequencies = YomitanDataStore.kanjiFrequencyFor(appContext, listOf(literal))[literal].orEmpty(),
            combinedReadings = combined,
        )
    }

    override suspend fun annotateForHintText(text: String): List<HintTextAnnotation> =
        withContext(Dispatchers.Default) {
            val tokens = dict.tokenizeForFurigana(text)
            // Pitch only on whole-word, uninflected ruby: partial ruby can't
            // carry a word contour, and lemma pitch on inflected forms is
            // linguistically wrong (verb/adjective accent shifts).
            val eligible = tokens
                .filter { it.coversWholeSurface && it.surface == it.dictionaryForm }
                .map { it.surface to it.reading }
                .distinct()
            val pitch =
                if (eligible.isEmpty()) emptyMap()
                else YomitanDataStore.pitchFor(appContext, eligible)
            tokens.map {
                HintTextAnnotation(
                    baseStart = it.startOffset,
                    baseEnd = it.endOffset,
                    hintText = it.reading,
                    pitchDownstep =
                        if (it.coversWholeSurface && it.surface == it.dictionaryForm) {
                            pitch[it.surface to it.reading]?.firstOrNull()
                        } else {
                            null
                        },
                )
            }
        }

    override suspend fun spokenForm(text: String): String =
        withContext(Dispatchers.Default) {
            // Feed TTS the SAME per-token readings the furigana shows — Sudachi's
            // readingForm — so the spoken kana matches what's displayed (初夏 →
            // しょか, not the engine's own guess はつか). Mirrors tokenizeForFurigana's
            // reading source, so audio == display by construction. Provider.analyze
            // is fail-soft to empty, so a tokenizer failure speaks the surface
            // text rather than emitting silence.
            val tokens = SudachiJapaneseTokenizer.Provider.analyze(text)
            if (tokens.isEmpty()) {
                text
            } else buildString {
                for (t in tokens) {
                    val kana = t.reading?.let { Deinflector.katakanaToHiragana(it) }
                    append(kana ?: t.surface)
                }
            }
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
