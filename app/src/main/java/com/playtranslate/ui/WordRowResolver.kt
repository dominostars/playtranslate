package com.playtranslate.ui

import android.content.Context
import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.language.DefinitionGlossTranslators
import com.playtranslate.language.DefinitionResolver
import com.playtranslate.language.DefinitionResult
import com.playtranslate.language.SourceLanguageEngine
import com.playtranslate.language.TargetGlossDatabaseProvider
import com.playtranslate.language.TokenSpan
import com.playtranslate.language.TranslationManagerProvider
import com.playtranslate.language.WordTranslator
import com.playtranslate.model.headwordDisplay
import com.playtranslate.model.headwordFor
import com.playtranslate.translation.ChineseScriptConverter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Outcome of [resolveWordRows]: the rendered word [rows] plus the supporting
 * span/reading data the translation-result surface needs for furigana and
 * word-tap positioning.
 *
 *  - [tokenSpans]: the per-occurrence tokens the rows were built from (carries
 *    the tokenizer's surface info; the dictionary-search screen passes its
 *    candidate list here and simply doesn't use it for span mapping).
 *  - [lookupToReading]: maps both lookupForm and surface form to the resolved
 *    reading, so conjugated surfaces still get furigana.
 *  - [surfaces]: displayWord → surface, when the two differ.
 */
data class LookupData(
    val rows: List<RowState>,
    val tokenSpans: List<TokenSpan>,
    val lookupToReading: Map<String, String>,
    val surfaces: Map<String, String>,
)

/**
 * Immutable snapshot of the source/target settings one lookup runs against,
 * captured by the caller BEFORE tokenizing. Threading it through
 * [resolveWordRows] keeps tokenization and resolution pinned to the same
 * [engine] and target language even if the user changes preferences while the
 * lookup is in flight — otherwise an in-flight resolve could re-read globals
 * and resolve one language's tokens through another language's engine, or
 * render them against a different target. The caller MUST tokenize with this
 * same [engine].
 */
data class WordLookupContext(
    val engine: SourceLanguageEngine,
    val targetLang: String,
    val targetChineseVariant: ChineseScriptVariant,
)

/**
 * Resolve a list of already-tokenized [tokens] into renderable word [rows],
 * against the caller-owned [context] snapshot.
 *
 * This is the shared token → [RowState] pipeline used by both the translation
 * result surface ([TranslationResultViewModel], which feeds it
 * `context.engine.tokenize(text)`) and the standalone dictionary-search screen
 * ([DictionaryLookupViewModel], which feeds it either segmented tokens or
 * prefix-completion candidates). Row order follows the dedup order of
 * [tokens], so callers control ranking by ordering the tokens they pass in.
 *
 * Per-token dictionary lookups fan out in parallel on IO; per-row failures
 * drop to null and are filtered out. Honors coroutine cancellation so a newer
 * query can cancel an in-flight resolve.
 */
suspend fun resolveWordRows(
    appCtx: Context,
    context: WordLookupContext,
    tokens: List<TokenSpan>,
): LookupData {
    val engine = context.engine
    val targetLang = context.targetLang
    val targetGlossDb = TargetGlossDatabaseProvider.get(appCtx, targetLang)
    val mlKit = TranslationManagerProvider.get(engine.profile.translationCode, targetLang)
    val resolver = DefinitionResolver(
        engine, targetGlossDb,
        mlKit?.let { WordTranslator(it::translate) }, targetLang,
        DefinitionGlossTranslators.forTarget(targetLang),
        ChineseScriptConverter.forTarget(targetLang, context.targetChineseVariant),
    )

    val allTokens = tokens

    val seen = mutableSetOf<String>()
    val uniqueTokens = allTokens.filter { seen.add(it.lookupForm) }
    val resolveTokens = uniqueTokens.map { it.lookupForm }

    if (resolveTokens.isEmpty()) {
        return LookupData(
            rows = emptyList(),
            tokenSpans = allTokens,
            lookupToReading = emptyMap(),
            surfaces = emptyMap(),
        )
    }

    val surfaceByToken = uniqueTokens.associate { it.lookupForm to it.surface }
    val readingByToken = uniqueTokens.associate { it.lookupForm to it.reading }

    // Fan out per-token lookups in parallel on IO. Per-row failures produce
    // nulls that we filter out below.
    data class Row(
        val rowState: RowState,
        val surfaceMapping: Pair<String, String>?,  // displayWord → surface, when they differ
    )

    val results: List<Row?> = withContext(Dispatchers.IO) {
        coroutineScope {
            resolveTokens.map { word ->
                async {
                    try {
                        val defResult = resolver.lookup(word, readingByToken[word])
                        val response = defResult?.response
                        if (response == null || response.entries.isEmpty()) return@async null
                        val entry = response.entries.first()
                        val flatSenses = response.entries.flatMap { it.senses }
                        val primary = entry.headwordFor(surfaceByToken[word])
                            ?: entry.headwordFor(word)
                            ?: entry.headwords.firstOrNull()
                        // headwordDisplay swaps the kanji for the kana on JMdict
                        // uk-tagged entries (e.g. なぜ over 何故), suppressing the
                        // reading column since it would just duplicate the
                        // headword — UNLESS the source text actually showed the
                        // kanji (surfaceByToken[word] matches a written form), in
                        // which case we honor what the user saw.
                        val display = entry.headwordDisplay(
                            primary,
                            surfaceByToken[word],
                        )
                        val displayWord = display.written
                        val reading = display.reading ?: ""
                        val freqScore = entry.freqScore

                        val meaning = when (defResult) {
                            is DefinitionResult.Native -> {
                                val targetSensesSorted = defResult.targetSenses.sortedBy { it.senseOrd }
                                val isTargetDriven = targetLang != "en" && targetSensesSorted.isNotEmpty()
                                if (isTargetDriven) {
                                    targetSensesSorted.mapIndexed { i, target ->
                                        val glosses = target.glosses.joinToString("; ")
                                        if (targetSensesSorted.size > 1) "${i + 1}. $glosses" else glosses
                                    }.joinToString("\n")
                                } else {
                                    val targetByOrd = targetSensesSorted.associateBy { it.senseOrd }
                                    flatSenses.mapIndexed { i, sense ->
                                        val target = targetByOrd[i]
                                        val glosses = target?.glosses?.joinToString("; ")
                                            ?: sense.targetDefinitions.joinToString("; ")
                                        if (flatSenses.size > 1) "${i + 1}. $glosses" else glosses
                                    }.joinToString("\n")
                                }
                            }
                            is DefinitionResult.MachineTranslated -> {
                                val defs = defResult.translatedDefinitions
                                if (defs != null) {
                                    flatSenses.mapIndexed { i, sense ->
                                        val glosses = defs.getOrElse(i) { sense.targetDefinitions.joinToString("; ") }
                                        if (flatSenses.size > 1) "${i + 1}. $glosses" else glosses
                                    }.joinToString("\n")
                                } else {
                                    val translatedLine = defResult.translatedHeadword
                                    val englishLines = flatSenses.mapIndexed { i, sense ->
                                        val glosses = sense.targetDefinitions.joinToString("; ")
                                        if (flatSenses.size > 1) "${i + 1}. $glosses" else glosses
                                    }.joinToString("\n")
                                    "$translatedLine\n$englishLines"
                                }
                            }
                            is DefinitionResult.EnglishFallback -> {
                                val defs = defResult.translatedDefinitions
                                flatSenses.mapIndexed { i, sense ->
                                    val glosses = defs?.getOrElse(i) { sense.targetDefinitions.joinToString("; ") }
                                        ?: sense.targetDefinitions.joinToString("; ")
                                    if (flatSenses.size > 1) "${i + 1}. $glosses" else glosses
                                }.joinToString("\n")
                            }
                        }
                        if (meaning.isEmpty()) return@async null
                        // Structured senses for the cell's numbered, POS-grouped
                        // definitions, built once via the shared tier logic the
                        // lens popup also uses.
                        val senses = buildSenseDisplays(defResult, response.entries, targetLang)
                        val ankiPos = entry.senses.firstOrNull()?.partsOfSpeech
                            ?.filter { it.isNotBlank() }?.joinToString(" · ") ?: ""
                        val surface = surfaceByToken[word] ?: word
                        Row(
                            rowState = RowState(
                                displayWord = displayWord,
                                reading = reading,
                                meaning = meaning,
                                senses = senses,
                                freqScore = freqScore,
                                isCommon = entry.isCommon == true,
                                surface = surface,
                                ankiPos = ankiPos,
                            ),
                            surfaceMapping = if (surface != displayWord) {
                                displayWord to surface
                            } else null,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll()
        }
    }

    val resolvedRows = results.filterNotNull().map { it.rowState }
    val surfaces = results.filterNotNull()
        .mapNotNull { it.surfaceMapping }
        .toMap()

    val lookupToReading = mutableMapOf<String, String>()
    results.forEachIndexed { idx, row ->
        if (row != null && row.rowState.reading.isNotEmpty()) {
            lookupToReading[resolveTokens[idx]] = row.rowState.reading
            val surface = surfaceByToken[resolveTokens[idx]]
            if (surface != null && surface != resolveTokens[idx]) {
                lookupToReading[surface] = row.rowState.reading
            }
        }
    }

    return LookupData(
        rows = resolvedRows,
        tokenSpans = allTokens,
        lookupToReading = lookupToReading,
        surfaces = surfaces,
    )
}
