package com.playtranslate.language

import android.content.Context
import android.util.Log
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.DictionaryResponse
import com.playtranslate.model.FrequencyTag
import com.playtranslate.model.Headword
import com.playtranslate.model.ImportedKanji
import com.playtranslate.model.preferDisplayable
import com.playtranslate.yomitan.YomitanDataStore
import kotlinx.coroutines.CancellationException

private const val TAG = "YomitanEnrich"

/**
 * Shared Yomitan term/pitch/frequency enrichment for any source-language
 * engine. Holds the app context plus the normalized consuming language
 * ([SourceLangId.yomitanConsumingLang]) so the per-language capability cache
 * resolves correctly — every [YomitanDataStore] call routes its `sourceLang`
 * through here, which is the ONLY place an engine names that language string.
 *
 * Lifted verbatim out of [JapaneseEngine] (term lookup → merge → pitch/freq
 * attach) so all engines share one implementation. The merge operates purely
 * on [DictionaryResponse]/[DictionaryEntry]/[Headword] — nothing here is
 * Japanese-specific. Pitch is data-gated (only JA dicts carry it), so calling
 * [applyTo] for every language is safe and near-free when no dictionary for
 * the language is installed (the facade short-circuits on empty capability).
 */
class YomitanEnrichment(
    private val appContext: Context,
    private val sourceLang: String,
) {

    /** Runs [block], degrading any failure that isn't coroutine cancellation to
     *  [fallback] (logged). Yomitan enrichment is OPTIONAL — a corrupt/locked
     *  imported-dictionary DB, or any other [YomitanDataStore] error, must
     *  never sink the host engine's core lookup/tokenize/character result
     *  (which non-JA engines didn't even depend on Yomitan for before this).
     *  Cancellation propagates; [Error] (e.g. OOM) propagates. */
    private inline fun <T> failOpen(fallback: T, what: String, block: () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Yomitan $what failed for source '$sourceLang'; using base result", e)
            fallback
        }

    /** Imported Yomitan term dicts list expressions the built-in pack lacks —
     *  offer them as a second phrase gate for a tokenizer's n-gram re-glob.
     *  Null (not a no-op lambda) when no term dict is installed for this
     *  language, so the tokenizer skips the whole oracle pass. Only engines
     *  whose tokenizer accepts an oracle (JA's [com.playtranslate.dictionary.DictionaryManager])
     *  wire this in. */
    suspend fun phraseOracle(): (suspend (Set<String>) -> Set<String>)? =
        if (failOpen(false, "term-dictionary probe") {
                YomitanDataStore.hasTermDictionaries(appContext, sourceLang)
            }
        ) {
            { candidates ->
                failOpen(emptySet(), "phrase oracle") {
                    YomitanDataStore.batchTermsExist(appContext, sourceLang, candidates)
                }
            }
        } else {
            null
        }

    /**
     * Full enrichment for one lookup: resolves imported term definitions for
     * [word] (with [reading]) and the [fallbackForms] (each tried with a null
     * reading, mirroring the pack's candidate tiers), merges them into
     * [packResponse] (or synthesizes an entry on a pack miss), then attaches
     * pitch + frequency to every headword. Returns null only when [packResponse]
     * is null AND nothing imported matched.
     */
    suspend fun applyTo(
        packResponse: DictionaryResponse?,
        word: String,
        reading: String?,
        fallbackForms: List<String>,
    ): DictionaryResponse? {
        // Optional enrichment must never sink the pack result: a Yomitan
        // failure returns [packResponse] unchanged, and an enrich failure
        // returns the already-merged definitions without pitch/frequency.
        val imported = failOpen<ImportedTerms?>(null, "term lookup") {
            lookupImportedTerms(
                word, reading, packReadings(packResponse, word),
                packWrittenForms(packResponse, word, reading), fallbackForms,
            )
        } ?: return packResponse
        val merged = mergeImportedTerms(packResponse, word, imported.lookup, imported.resolvedTerm)
            ?: return null
        return failOpen(merged, "pitch/frequency enrichment") { enrich(merged) }
    }

    /** Batched pitch passthrough (held [sourceLang]) for callers that attach
     *  pitch outside [applyTo] — JA's furigana hint-text pass. */
    suspend fun pitchFor(
        pairs: Collection<Pair<String, String>>,
    ): Map<Pair<String, String>, List<Int>> =
        failOpen(emptyMap(), "pitch lookup") {
            YomitanDataStore.pitchFor(appContext, sourceLang, pairs)
        }

    /** The winning imported kanji dictionary's content for [literal], or null
     *  when no kanji dict for this language has it. CJK character enrichment. */
    suspend fun importedKanji(literal: Char): ImportedKanji? =
        failOpen(null, "kanji lookup") {
            YomitanDataStore.kanjiFor(appContext, sourceLang, listOf(literal))[literal]
        }

    /** Per-dictionary kanji-frequency chips for [literal], in section order. */
    suspend fun kanjiFrequencies(literal: Char): List<FrequencyTag> =
        failOpen(emptyList(), "kanji-frequency lookup") {
            YomitanDataStore.kanjiFrequencyFor(appContext, sourceLang, listOf(literal))[literal].orEmpty()
        }

    private class ImportedTerms(
        val lookup: YomitanDataStore.TermLookup,
        /** The candidate form that hit, when any did. */
        val resolvedTerm: String?,
    ) {
        val groups get() = lookup.groups
    }

    /**
     * Imported-term resolution mirrors the pack's candidate tiers
     * independently (Yomitan semantics — sources don't need to agree on a
     * lemma): the word as passed first, then — when [reading] disambiguated
     * everything away — the readings the pack itself resolved, then the
     * pack's own spellings of the word ([packForms]), then the engine's
     * fallback forms.
     *
     * The [packReadings] tier exists because `reading` has no single
     * meaning across callers. The tap surfaces pass the JMdict-RESOLVED
     * lemma reading ([com.playtranslate.ui.SourceWordLookup.computeSpans]
     * prefers `lookupToReading`), but the drag lookup passes the
     * TOKENIZER's surface reading — こだわっ for 拘って, whose lemma is 拘る.
     * [com.playtranslate.dictionary.DictionaryManager.lookup] absorbs that
     * mismatch by retrying without the reading filter, so the pack answers
     * correctly while the imported groups went silent: the same broken hint
     * reached two consumers and only one had a fallback. This is that
     * fallback, narrowed rather than dropped — the pack has already resolved
     * the spelling's readings by the time [applyTo] runs, so the imported
     * groups are narrowed by exactly the identity the pack landed on and the
     * two sources cannot disagree per-surface again.
     *
     * The [packForms] tier exists because the store matches imported rows on
     * the TERM column alone, while Yomitan itself queries its expression AND
     * reading indexes: a lookup keyed by kana never reaches an imported row
     * whose headword is the kanji spelling. Jitendex stores いつも under
     * 何時も only (its kana headwords exist just for all-rare kanji or a
     * priority tag the kanji lacks, and 何時も carries ichi1 like its
     * reading), so every kana-keyed lookup lost it: the lens always keyed on
     * the tokenizer's kana lemma, and the detail sheet, the lookup screen
     * and the Anki review sheet key on the DISPLAYED word, which the
     * kana-only rule turned from 何時も into いつも for 686 words on
     * 2026-09-02, taking Jitendex off those screens at once. The pack has
     * already resolved the spelling by the time this runs, so the retry uses
     * the FIRST pack entry's written forms, each narrowed to the readings
     * the pack pairs with it — the identity the imported groups attach to
     * ([mergeImportedTerms] rides the first entry), never a homograph's.
     * Cost: zero queries when the word already is a pack spelling (the
     * common kanji case filters to nothing), else one indexed point query
     * per distinct spelling, short-circuiting on the first hit, and only
     * after the two tiers above missed.
     *
     * Strictly additive: every tier runs only after a narrower lookup found
     * nothing, so no lookup that resolves today can change.
     */
    private suspend fun lookupImportedTerms(
        word: String,
        reading: String?,
        packReadings: Set<String>,
        packForms: List<Pair<String, Set<String>>>,
        fallbackForms: List<String>,
    ): ImportedTerms {
        if (!YomitanDataStore.hasTermDictionaries(appContext, sourceLang)) {
            return ImportedTerms(YomitanDataStore.TermLookup(emptyList(), reading), null)
        }
        val direct =
            YomitanDataStore.termSensesFor(appContext, sourceLang, word, reading?.let(::setOf))
        if (direct.groups.isNotEmpty()) return ImportedTerms(direct, word)
        // Only when the caller actually narrowed: an unnarrowed miss already
        // saw every row for the term, and no reading set can widen that.
        if (reading != null && packReadings.isNotEmpty()) {
            val byPack = YomitanDataStore.termSensesFor(appContext, sourceLang, word, packReadings)
            if (byPack.groups.isNotEmpty()) return ImportedTerms(byPack, word)
        }
        for ((form, formReadings) in packForms) {
            val bySpelling = YomitanDataStore.termSensesFor(appContext, sourceLang, form, formReadings)
            if (bySpelling.groups.isNotEmpty()) return ImportedTerms(bySpelling, form)
        }
        for (form in fallbackForms) {
            val hit = YomitanDataStore.termSensesFor(appContext, sourceLang, form, null)
            if (hit.groups.isNotEmpty()) return ImportedTerms(hit, form)
        }
        return ImportedTerms(direct, null)
    }

    companion object {
        /**
         * Every reading the built-in pack resolved for [word] — the
         * disambiguator [lookupImportedTerms] retries with when the caller's
         * own reading narrowed the imported rows away. Empty when the pack
         * missed or its headwords carry no readings, which keeps the retry
         * off.
         *
         * Every entry, not just the first: when the pack couldn't narrow by
         * reading it returns all of the spelling's homographs (拘る →
         * こだわる AND かかわる), and the imported groups should show the
         * same breadth the pack is showing rather than an arbitrary pick.
         *
         * Within an entry, though, only the headwords compatible with [word]
         * contribute. An entry lists several written↔reading pairs and
         * [com.playtranslate.dictionary.DictionaryManager.buildHeadwords]
         * pairs them POSITIONALLY once there are multiple kanji forms (the
         * `re_restr` the pack lacks), so an entry's reading set can carry a
         * reading that belongs to a different written form — retrying with it
         * would readmit exactly the homograph the reading filter exists to
         * keep out. Compatible means: the written form IS [word]; or there is
         * no written form (a `re_nokanji` kana reading, which belongs to the
         * whole entry); or [word] is itself that reading — the kana-lemma
         * case, without which every `uk` word (the population this retry
         * matters most for) would filter down to nothing and silently lose
         * the retry.
         *
         * An entry whose headwords match none of those falls back to its full
         * reading set: the pack matches on its reading table too, so such an
         * entry was reached by something no written form can be filtered
         * against, and its own readings are the only signal left.
         */
        internal fun packReadings(packResponse: DictionaryResponse?, word: String): Set<String> =
            packResponse?.entries
                ?.flatMapTo(mutableSetOf()) { entry ->
                    entry.headwords
                        .filter { it.written == null || it.written == word || it.reading == word }
                        .ifEmpty { entry.headwords }
                        .mapNotNull { it.reading?.takeIf(String::isNotBlank) }
                }
                .orEmpty()

        /**
         * The pack's own spellings of [word], for [lookupImportedTerms]'s
         * retry when a kana-keyed lookup misses imported rows stored under
         * the kanji: each written form of the FIRST pack entry that differs
         * from [word], paired with the readings the pack lists for it, in
         * headword order. Empty on a pack miss and when [word] already is a
         * pack spelling, so the common kanji-keyed lookup adds no query.
         *
         * First entry only, unlike [packReadings]: imported groups attach to
         * the first entry ([mergeImportedTerms]), and a kana key can return
         * homographs (ここ → 此処 above 個々), whose spellings would fetch
         * another word's definitions onto this one's anchor.
         *
         * A [word] that already IS one of the entry's spellings gets nothing:
         * a miss on it is a genuine miss, and under positional pairing the
         * entry's other spellings can be other words (端/はし beside 辺/ほとり),
         * so offering them would attach a neighbour's definitions to this
         * anchor — the bleed the tier exists to avoid (Codex find).
         *
         * Within the entry, only reading-compatible headwords contribute: the
         * ones carrying [reading] when it names a reading; else the ones whose
         * reading IS [word] (a kana key names its own reading); else — the
         * drag path's surface reading (こだわっ) on a key that names nothing —
         * the primary spelling alone, the same anchor the entry's slug uses,
         * never the whole positional set.
         */
        internal fun packWrittenForms(
            packResponse: DictionaryResponse?,
            word: String,
            reading: String?,
        ): List<Pair<String, Set<String>>> {
            val entry = packResponse?.entries?.firstOrNull() ?: return emptyList()
            if (entry.headwords.any { it.written == word }) return emptyList()
            // The anchor spelling is the first DISPLAYABLE headword, not the
            // first listed: 141 JMdict entries list a search-only form first,
            // and anchoring there would pick a spelling the loop below then
            // skips, leaving nothing to retry with (Codex adversarial find).
            val primary = entry.headwords.preferDisplayable()?.written
            val compatible = entry.headwords
                .filter { reading != null && it.reading == reading }
                .ifEmpty { entry.headwords.filter { it.reading == word } }
                .ifEmpty { entry.headwords.filter { it.written != null && it.written == primary } }
            val byForm = LinkedHashMap<String, MutableSet<String>>()
            for (hw in compatible) {
                // A search-only spelling is a lookup key, not a word: imported
                // dictionaries built by yomitan-import carry it only as a
                // redirect stub (⟶ the real headword), which is no definition.
                if (hw.isSearchOnly) continue
                val written = hw.written?.takeIf { it.isNotBlank() } ?: continue
                val readings = byForm.getOrPut(written) { mutableSetOf() }
                hw.reading?.takeIf(String::isNotBlank)?.let(readings::add)
            }
            return byForm.map { (form, readings) -> form to readings.toSet() }
        }

        /** Pure term-merge, extracted so it's unit-testable without a Context:
         *  attaches imported definition groups from [lookup] to [packResponse]
         *  (groups ride the first entry; single-dictionary mode also strips the
         *  pack's own senses), synthesizes an entry on a pack miss, or returns
         *  null when the pack missed and nothing imported matched. With no group
         *  to attach it returns [packResponse] untouched — so a pre-applied
         *  entry reorder (e.g. ChineseEngine's preferReading) is never
         *  disturbed by the wrap. */
        internal fun mergeImportedTerms(
            packResponse: DictionaryResponse?,
            word: String,
            lookup: YomitanDataStore.TermLookup,
            resolvedTerm: String?,
        ): DictionaryResponse? = when {
            // The pack entry anchors the word's identity (headword display, TTS
            // reading, badges, POS); imported groups attach to the first entry —
            // the one every surface renders.
            packResponse != null -> {
                if (lookup.groups.isEmpty()) {
                    packResponse
                } else {
                    // Single-dictionary mode: the pack is the lowest-priority
                    // source, so a winning imported group excludes its senses
                    // too. Stripped across ALL entries (surfaces flatMap senses
                    // over them); the entry keeps anchoring identity — the
                    // already-supported synthesized-entry shape.
                    val entries = if (lookup.suppressesPackSenses) {
                        packResponse.entries.map { it.copy(senses = emptyList()) }
                    } else {
                        packResponse.entries
                    }
                    packResponse.copy(
                        entries = listOf(
                            entries.first().copy(importedSenses = lookup.groups)
                        ) + entries.drop(1),
                    )
                }
            }
            // Pack miss but an imported dict has the word: synthesize an entry
            // so it resolves everywhere. No senses/POS/badges — the imported
            // groups are the content.
            lookup.groups.isNotEmpty() -> {
                val term = resolvedTerm ?: word
                DictionaryResponse(
                    entries = listOf(
                        DictionaryEntry(
                            slug = term,
                            isCommon = null,
                            tags = emptyList(),
                            jlpt = emptyList(),
                            headwords = listOf(
                                Headword(
                                    written = term,
                                    reading = lookup.resolvedReading?.takeIf { it != term },
                                )
                            ),
                            senses = emptyList(),
                            importedSenses = lookup.groups,
                        )
                    ),
                )
            }
            else -> null
        }
    }

    /** Attaches pitch-accent downsteps and per-dictionary frequency tags from
     *  imported Yomitan dictionaries to each headword — one batched query per
     *  data type per lookup, over the same (term, reading) pairs. The facade
     *  gates each on "any such dictionary installed for this language", so this
     *  is a no-op map lookup for everyone else (and for every non-JA language
     *  with no pitch dict, pitch returns empty immediately). */
    private suspend fun enrich(response: DictionaryResponse): DictionaryResponse {
        val pairs = response.entries
            .flatMap { it.headwords }
            .mapNotNull { hw ->
                val term = hw.written ?: hw.reading ?: return@mapNotNull null
                term to (hw.reading ?: term)
            }
            .distinct()
        val pitch = YomitanDataStore.pitchFor(appContext, sourceLang, pairs)
        val frequencies = YomitanDataStore.frequencyFor(appContext, sourceLang, pairs)
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
}
