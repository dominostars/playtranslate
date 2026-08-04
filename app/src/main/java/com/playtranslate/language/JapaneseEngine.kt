package com.playtranslate.language

import android.content.Context
import com.playtranslate.dictionary.Deinflector
import com.playtranslate.dictionary.DictionaryManager
import com.playtranslate.dictionary.SentenceAnnotator
import com.playtranslate.dictionary.SudachiJapaneseTokenizer
import com.playtranslate.model.selectHeadword
import com.playtranslate.model.CharacterDetail
import com.playtranslate.model.DictionaryResponse
import com.playtranslate.model.KanjiDetail
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

    private val yomitan = YomitanEnrichment(appContext, SourceLangId.JA.yomitanConsumingLang())

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

    // tokenize() is a projection of annotate(WORDS) — see the override below
    // next to annotate(). The imported-dict phrase oracle rides inside
    // annotate's re-glob call.

    override suspend fun searchPrefix(query: String, limit: Int): List<TokenSpan> =
        dict.searchPrefix(query, limit).map {
            TokenSpan(surface = it.surface, lookupForm = it.lookupForm, reading = it.reading)
        }

    /** Pack lookup, then shared Yomitan enrichment: imported term groups merge
     *  in (deinflection candidates are the fallback forms), with pitch +
     *  frequency attached. Behaviour is identical to the pre-extraction inline
     *  path — [YomitanEnrichment] is a verbatim move of it. */
    override suspend fun lookup(word: String, reading: String?): DictionaryResponse? =
        yomitan.applyTo(
            dict.lookup(word, reading),
            word, reading,
            fallbackForms = Deinflector.candidates(word).map { it.text },
        )

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
        val imported = yomitan.importedKanji(literal)
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
            frequencies = yomitan.kanjiFrequencies(literal),
            combinedReadings = combined,
        )
    }

    override suspend fun annotate(text: String, depth: AnnotationDepth): SentenceAnnotation =
        withContext(Dispatchers.Default) {
            val tokens = SudachiJapaneseTokenizer.Provider.analyze(text)
            if (tokens.isEmpty()) return@withContext SentenceAnnotation.plain(text, profile.id)
            val reglob =
                if (depth != AnnotationDepth.TOKENS) {
                    dict.reglobSpansForTokens(tokens, yomitan.phraseOracle())
                } else null
            val resolutions =
                if (reglob == null || depth != AnnotationDepth.FULL) emptyMap()
                else SentenceAnnotator.resolutionKeys(reglob).associateWith { resolveWord(it) }
            val annotation = SentenceAnnotator.annotate(
                text, profile.id, tokens, reglob, resolutions, importGeneration = 0,
            )
            // Pitch rides display depths; WORDS is the tokenize projection's
            // depth and never renders ruby.
            if (depth == AnnotationDepth.WORDS) annotation else applyPitch(annotation)
        }

    override suspend fun tokenize(text: String): List<TokenSpan> =
        annotate(text, AnnotationDepth.WORDS).spans
            .filter { it.lookupForm != null }
            .map {
                TokenSpan(
                    surface = it.surface, lookupForm = it.lookupForm!!,
                    reading = it.lookupHint, inflections = it.inflections,
                )
            }

    /** Two-store resolution for one (lookupForm, occurrence-hint) pair: the
     *  full lookup (pack + Yomitan merge/synthesis) chooses the entry with
     *  the hint as soft narrowing, and selectHeadword picks the occurrence-
     *  validated reading. Readings-only fast resolution is a phase-4 concern
     *  (refactor doc §6) — this path's cost equals today's words-list pass. */
    private suspend fun resolveWord(
        key: SentenceAnnotator.ResolutionKey,
    ): SentenceAnnotator.WordResolution {
        val entry = lookup(key.lookupForm, key.hint)?.entries?.firstOrNull()
            ?: return SentenceAnnotator.WordResolution(null, null)
        val ref = entry.packId?.let { EntryRef.Pack(it) }
            ?: EntryRef.Imported(key.lookupForm, entry.headwords.firstOrNull()?.reading)
        val hw = entry.selectHeadword(key.lookupForm, key.lookupForm, key.hint)
        return SentenceAnnotator.WordResolution(ref, hw?.reading)
    }

    /** Pitch on whole-word uninflected spans — the legacy hint path's
     *  eligibility rule (coversWholeSurface && surface == dictionaryForm) at
     *  span granularity: a single ruby part covering the whole surface of an
     *  uninflected span. */
    private suspend fun applyPitch(annotation: SentenceAnnotation): SentenceAnnotation {
        val eligible = annotation.spans
            .filter {
                it.lookupForm != null && it.surface == it.lookupForm &&
                    it.reading != null &&
                    it.furigana.size == 1 && it.furigana[0].text == it.surface &&
                    it.furigana[0].reading != null
            }
            .map { it.surface to it.reading!! }
            .distinct()
        if (eligible.isEmpty()) return annotation
        val pitch = yomitan.pitchFor(eligible)
        if (pitch.isEmpty()) return annotation
        return annotation.copy(spans = annotation.spans.map { s ->
            val p = if (s.reading != null) pitch[s.surface to s.reading] else null
            if (p.isNullOrEmpty()) s else s.copy(pitch = p)
        })
    }

    /** Legacy hint API, now a projection of [annotate] at TOKENS depth —
     *  byte-parity with the old per-token path. The live overlay consumes
     *  this until its measurement gate flips it to FULL (refactor doc §6);
     *  the in-app binder calls [annotate] FULL directly. */
    override suspend fun annotateForHintText(text: String): List<HintTextAnnotation> =
        annotate(text, AnnotationDepth.TOKENS).hintAnnotations()

    override suspend fun spokenForm(text: String): String =
        withContext(Dispatchers.Default) {
            // TTS speaks the SAME readings the display shows — audio ==
            // display by construction. Both project from ONE FULL-depth
            // annotation now, so dictionary-corrected compound readings
            // (一泊 → いっぱく) are spoken, not just displayed. annotate is
            // fail-soft: no tokenizer → one plain span → surface text.
            val spans = annotate(text, AnnotationDepth.FULL).spans
            if (spans.isEmpty()) text
            else buildString { for (s in spans) append(s.reading ?: s.surface) }
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
