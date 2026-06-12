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
                val fallbackPos = unambiguousFallbackPos(entries).joinToString(", ")
                targetSensesSorted.map { target ->
                    val pos = target.pos.filter { it.isNotBlank() }
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString(", ")
                        ?: fallbackPos
                    SenseDisplay(pos = pos, definition = target.glosses.joinToString("; "))
                }
            } else {
                // Reached only when target == "en" (Native is not returned for
                // English targets) or the empty-target-senses defensive case.
                val targetByOrd = targetSensesSorted.associateBy { it.senseOrd }
                flatSenses.mapIndexed { i, sense ->
                    val target = targetByOrd[i]
                    if (target != null) {
                        SenseDisplay(
                            pos = target.pos.joinToString(", "),
                            definition = target.glosses.joinToString("; "),
                        )
                    } else {
                        SenseDisplay(
                            pos = sense.partsOfSpeech.joinToString(", "),
                            definition = sense.targetDefinitions.joinToString("; "),
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
                        pos = sense.partsOfSpeech.joinToString(", "),
                        definition = defs.getOrElse(i) { sense.targetDefinitions.joinToString("; ") },
                    )
                }
            } else {
                buildList {
                    add(SenseDisplay(pos = "", definition = defResult.translatedHeadword))
                    flatSenses.forEach { sense ->
                        add(
                            SenseDisplay(
                                pos = sense.partsOfSpeech.joinToString(", "),
                                definition = sense.targetDefinitions.joinToString("; "),
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
                    pos = sense.partsOfSpeech.joinToString(", "),
                    definition = defs.getOrElse(i) { sense.targetDefinitions.joinToString("; ") },
                )
            }
        }
        else -> {
            flatSenses.map { sense ->
                SenseDisplay(
                    pos = sense.partsOfSpeech.joinToString(", "),
                    definition = sense.targetDefinitions.joinToString("; "),
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
                pos = importedHeader(group.source, sense.pos),
                definition = sense.definition,
                imported = true,
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
