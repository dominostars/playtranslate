package com.playtranslate.ui

import com.playtranslate.language.DefinitionResult
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.ImportedSenseGroup
import com.playtranslate.model.unambiguousFallbackPos

/**
 * Builds the list of rendered [SenseDisplay]s for a resolved lookup, applied
 * uniformly by every surface that shows definitions (the magnifying lens
 * popup and the translation-result word list). Centralises the per-tier
 * branching — Native target glosses, machine-translated definitions, English
 * fallback — so the two call sites can't drift.
 *
 * [entries] are the dictionary entries behind [defResult] (their senses are
 * flattened the same way the lens does); [targetLang] is the user's target
 * language code, which selects the target-driven gloss path. Only call this
 * when [entries] is non-empty (i.e. there is a real entry to render).
 */
fun buildSenseDisplays(
    defResult: DefinitionResult,
    entries: List<DictionaryEntry>,
    targetLang: String,
): List<SenseDisplay> {
    // Imported Yomitan term-dictionary groups lead, in the user's section
    // order, ahead of the pack's senses. Final text — never enters the MT
    // tiers below.
    val imported = importedSenseDisplays(entries.firstOrNull()?.importedSenses.orEmpty())
    // Wiktionary packs split POS into separate entries, JMdict doesn't;
    // flattening across every entry merges them safely for both.
    val flatSenses = entries.flatMap { it.senses }
    return imported + when {
        defResult is DefinitionResult.Native -> {
            val targetSensesSorted = defResult.targetSenses.sortedBy { it.senseOrd }
            val isTargetDriven = targetLang != "en" && targetSensesSorted.isNotEmpty()
            if (isTargetDriven) {
                // Blank-pos target rows (PanLex) inherit the source-entry POS
                // only when entries agree; multi-POS source yields an empty
                // fallback so we don't mislabel verb/intj cells as NOUN.
                val fallbackPos = unambiguousFallbackPos(entries)
                targetSensesSorted.map { target ->
                    val pos = target.pos.filter { it.isNotBlank() }.ifEmpty { fallbackPos }
                    SenseDisplay(pos = pos, definition = target.glosses.joinToString("; "), misc = target.misc)
                }
            } else {
                // Reached only when target == "en" (Native is not returned for
                // English targets) or the empty-target-senses defensive case.
                val targetByOrd = targetSensesSorted.associateBy { it.senseOrd }
                flatSenses.mapIndexed { i, sense ->
                    val target = targetByOrd[i]
                    if (target != null) {
                        SenseDisplay(
                            pos = target.pos,
                            definition = target.glosses.joinToString("; "),
                            misc = target.misc,
                        )
                    } else {
                        SenseDisplay(
                            pos = sense.partsOfSpeech,
                            definition = sense.targetDefinitions.joinToString("; "),
                            misc = sense.misc,
                        )
                    }
                }
            }
        }
        defResult is DefinitionResult.MachineTranslated -> {
            val defs = defResult.translatedDefinitions
            if (defs != null) {
                flatSenses.mapIndexed { i, sense ->
                    SenseDisplay(
                        pos = sense.partsOfSpeech,
                        definition = defs.getOrElse(i) { sense.targetDefinitions.joinToString("; ") },
                        misc = sense.misc,
                    )
                }
            } else {
                buildList {
                    add(SenseDisplay(pos = emptyList(), definition = defResult.translatedHeadword, misc = emptyList()))
                    flatSenses.forEach { sense ->
                        add(
                            SenseDisplay(
                                pos = sense.partsOfSpeech,
                                definition = sense.targetDefinitions.joinToString("; "),
                                misc = sense.misc,
                            )
                        )
                    }
                }
            }
        }
        defResult is DefinitionResult.EnglishFallback && defResult.translatedDefinitions != null -> {
            val defs = defResult.translatedDefinitions
            flatSenses.mapIndexed { i, sense ->
                SenseDisplay(
                    pos = sense.partsOfSpeech,
                    definition = defs.getOrElse(i) { sense.targetDefinitions.joinToString("; ") },
                    misc = sense.misc,
                )
            }
        }
        else -> {
            flatSenses.map { sense ->
                SenseDisplay(
                    pos = sense.partsOfSpeech,
                    definition = sense.targetDefinitions.joinToString("; "),
                    misc = sense.misc,
                )
            }
        }
    }
}

/** Imported groups as renderable rows: the dictionary name (plus the
 *  entry's part-of-speech tags when the dictionary carries them) rides the
 *  pos slot, so consecutive rows with the same header share one via the
 *  existing pos-change rendering. */
fun importedSenseDisplays(groups: List<ImportedSenseGroup>): List<SenseDisplay> =
    groups.flatMap { group ->
        group.senses.map { sense ->
            SenseDisplay(
                // One display header (source · tags), rendered verbatim — never
                // localized — so it rides a single-element pos list.
                pos = listOf(importedHeader(group.source, sense.pos)),
                definition = sense.definition,
                misc = emptyList(),
                imported = true,
                accentColor = group.accentColor,
            )
        }
    }

/** "Jitendex · n, v5r" when the entry carries POS tags, bare source name
 *  otherwise. */
fun importedHeader(source: String, pos: String): String =
    if (pos.isBlank()) source else "$source · $pos"

/**
 * Imported groups as raw lines for the flat (Anki) definition string, ONE
 * line per definition with the source in trailing parens — every flat
 * builder uses this same shape so cards stay consistent, and downstream
 * line-based splitters keep working (embedded newlines from list-style
 * definitions are collapsed). Callers prepend these to the pack's lines
 * under continuous numbering.
 */
fun importedFlatLines(groups: List<ImportedSenseGroup>): List<String> =
    groups.flatMap { group ->
        group.senses.map { "${it.definition.replace('\n', ' ')} (${group.source})" }
    }

/**
 * The flat, newline-joined definition string derived from rendered
 * [SenseDisplay] rows — THE single derivation of a word's flat meaning
 * wherever structured senses exist (LastSentenceCache.lookupWords, the
 * enrichment-carrying transports). Imported rows re-attach their source
 * name in trailing parens (the [importedFlatLines] convention — the
 * source rides `pos[0]` as "source" or "source · tags"); numbering
 * matches [flatCardDefinition]: continuous, only when more than one
 * line survives. Blank-definition rows are dropped from the flat text
 * (they stay in the structured list).
 */
fun flatMeaningOf(senses: List<SenseDisplay>): String {
    val lines = senses.map { s ->
        val text = s.definition.replace('\n', ' ')
        if (!s.imported) text
        else {
            val source = s.pos.firstOrNull()?.substringBefore(" · ")?.trim().orEmpty()
            if (source.isEmpty() || text.isBlank()) text else "$text ($source)"
        }
    }.filter { it.isNotBlank() }
    return (if (lines.size > 1) lines.mapIndexed { i, l -> "${i + 1}. $l" } else lines)
        .joinToString("\n")
}

/**
 * The meaning-slot value for the two transports that carry
 * [WordEnrichment] alongside the flat meanings (the review sheet's
 * args, the review activity's intent): "" when the word's senses cross
 * in the enrichment — the reader re-derives the flat text via
 * [meaningFromTransport] — and the real flat string only for
 * sense-less words. Definition text crosses the binder once, not
 * twice.
 */
fun meaningForTransport(meaning: String, enrichment: WordEnrichment?): String =
    if (enrichment?.senses?.isNotEmpty() == true) "" else meaning

/** Rebuilds a meaning slot a writer blanked via [meaningForTransport].
 *  A non-blank slot passes through (sense-less words, and every
 *  transport that doesn't carry enrichment at all). */
fun meaningFromTransport(marshaled: String, enrichment: WordEnrichment?): String =
    marshaled.ifEmpty { enrichment?.senses?.let(::flatMeaningOf).orEmpty() }

/**
 * The word card's flat definition string built from a bare resolved entry:
 * imported term-dictionary lines lead (one per line, source in parens),
 * the pack's non-empty senses follow, numbered continuously when more than
 * one line. Every Anki path that builds a definition straight from a
 * [DictionaryEntry] — without tier-translated definitions — must use this,
 * so cards can't silently drift from what the popup displayed.
 */
fun flatCardDefinition(entry: DictionaryEntry): String {
    val rawLines = importedFlatLines(entry.importedSenses) +
        entry.senses
            .filter { it.targetDefinitions.isNotEmpty() }
            .map { it.targetDefinitions.joinToString("; ") }
    return (if (rawLines.size > 1) rawLines.mapIndexed { i, l -> "${i + 1}. $l" } else rawLines)
        .joinToString("\n")
}
