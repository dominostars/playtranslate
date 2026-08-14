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
                scRowid = sense.scRowid,
                dictId = group.dictId.takeIf { it.isNotEmpty() },
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
 *  A non-blank slot passes through (sense-less words, degraded oversized
 *  payloads from [transportPayloadFor], and every transport that doesn't
 *  carry enrichment at all). */
fun meaningFromTransport(marshaled: String, enrichment: WordEnrichment?): String =
    marshaled.ifEmpty { enrichment?.senses?.let(::flatMeaningOf).orEmpty() }

/** The (meanings, enrichment) pair [transportPayloadFor] sizes for one
 *  launch payload — meanings parallel to the keys it was given. */
internal class TransportPayload(
    val meanings: Array<String>,
    val enrichment: HashMap<String, WordEnrichment>,
)

/** Estimated serialized weight of the structured senses: definition/pos/
 *  misc text at 3 bytes/char (CJK in modified UTF-8) plus a per-object
 *  constant for headers and list plumbing. Deliberately pessimistic —
 *  the gate below trips early rather than late. */
private fun estimateSensesBytes(enrichment: Map<String, WordEnrichment>): Int {
    var bytes = 0
    for (e in enrichment.values) {
        for (s in e.senses) {
            bytes += 64
            bytes += (s.definition.length +
                s.pos.sumOf { it.length } + s.misc.sumOf { it.length }) * 3
        }
    }
    return bytes
}

/** Estimated senses payload above which a launch Bundle risks the shared
 *  1MB Binder buffer (TransactionTooLargeException crashes reliably from
 *  ~200-500KB depending on concurrent traffic; Intents AND saved-state
 *  Fragment args share the same budget). */
private const val TRANSPORT_SENSES_BUDGET_BYTES = 200_000

/** Per-word ceiling on a DEGRADED payload's flat meaning. Only reachable
 *  when a single word carries several paragraph-length monolingual
 *  imported senses — inputs on which the pre-gate code (and the pre-v002
 *  code, whose MEANINGS array shipped the same text) crashed outright. */
private const val TRANSPORT_MEANING_CHAR_CAP = 8_000

/** Aggregate ceiling on a DEGRADED payload's flat-meaning text (chars;
 *  ×3 bytes serialized ≈ 192KB). UNCONDITIONAL: the per-word cap is the
 *  budget divided by the word count, so the sum stays bounded no matter
 *  how wide the capture was — a fixed per-word cap alone re-creates the
 *  crash at ~20+ saturated words. Excerpts shrink as captures widen
 *  (still ~200 chars/word at a fantasy 300-word block). */
private const val TRANSPORT_MEANINGS_CHAR_BUDGET = 64_000

/**
 * Sizes one launch payload's word data for transport. Two shapes:
 *
 *  - Under [TRANSPORT_SENSES_BUDGET_BYTES] (every realistic sentence —
 *    typical JMdict payloads run ~10KB serialized): senses cross in the
 *    enrichment and sense-bearing meaning slots are blanked
 *    ([meaningForTransport]) — definition text crosses the binder once.
 *  - Over it (long OCR paragraphs where most words carry paragraph-length
 *    monolingual imported senses): senses are STRIPPED from the shipped
 *    enrichment (pitch/frequencies/isCommon stay — they're tiny) and the
 *    flat meanings ship non-blank, capped per word. The read side
 *    degrades by construction: [meaningFromTransport] passes non-blank
 *    meanings through, and empty senses select the flat-lines cell
 *    rendering. If the receiver's global-cache read still hits the same
 *    sentence, it recovers full structured senses in-process for free.
 *
 * Callers: the ONLY two transports that carry enrichment beside meanings
 * (AnkiReviewBottomSheet args, CaptureResultOverlay's review intent).
 */
internal fun transportPayloadFor(
    keys: Array<String>,
    results: Map<String, Triple<String, String, Int>>,
    enrichment: Map<String, WordEnrichment>,
): TransportPayload {
    if (estimateSensesBytes(enrichment) <= TRANSPORT_SENSES_BUDGET_BYTES) {
        return TransportPayload(
            meanings = keys.map { k ->
                meaningForTransport(results.getValue(k).second, enrichment[k])
            }.toTypedArray(),
            enrichment = HashMap(enrichment),
        )
    }
    val stripped = HashMap<String, WordEnrichment>(enrichment.size * 2)
    enrichment.forEach { (w, e) -> stripped[w] = e.copy(senses = emptyList()) }
    val perWordCap = (TRANSPORT_MEANINGS_CHAR_BUDGET / keys.size.coerceAtLeast(1))
        .coerceAtMost(TRANSPORT_MEANING_CHAR_CAP)
    return TransportPayload(
        meanings = keys.map { k ->
            val m = results.getValue(k).second
            if (m.length > perWordCap) m.take(perWordCap) + "…" else m
        }.toTypedArray(),
        enrichment = stripped,
    )
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
