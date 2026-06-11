package com.playtranslate.ui

import com.playtranslate.language.DefinitionResult
import com.playtranslate.model.DictionaryEntry
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
    // Wiktionary packs split POS into separate entries, JMdict doesn't;
    // flattening across every entry merges them safely for both.
    val flatSenses = entries.flatMap { it.senses }
    return when {
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
