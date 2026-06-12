package com.playtranslate.yomitan

import com.playtranslate.model.ImportedSense
import com.playtranslate.model.ImportedSenseGroup

/**
 * Pure grouping/sorting/reading-narrowing for [YomitanDataStore.termSensesFor]
 * — the merge rules live here, SQLite-free, so they're unit-testable
 * ([FreqData]/[KanjiData] discipline).
 */
internal object TermMerge {

    /** One `term` table row. [reading] is normalized hiragana; [defs] are
     *  the entry's flattened definition strings; [pos] is the stored
     *  space-joined part-of-speech tag names ('' when untagged). Rows
     *  arrive in rowid (bank) order. */
    data class Row(
        val dictId: String,
        val reading: String,
        val score: Double,
        val defs: List<String>,
        val pos: String = "",
    )

    /**
     * [dictOrder] is the TERMS section's (dict id, group label) display
     * order. A non-null [normalizedReading] is a HARD disambiguator: only
     * rows with that reading survive — widening to other readings of the
     * same spelling would attach a homograph's definitions (端/はじ) under
     * the resolved word (端/はし). Rows whose stored reading is just the
     * term itself ([normalizedTerm]) didn't disambiguate at all (the
     * format's blank-reading sentinel, common in sloppier conversions) and
     * match any supplied reading. With no reading supplied, every row for
     * the term applies.
     */
    fun merge(
        rows: List<Row>,
        dictOrder: List<Pair<String, String>>,
        normalizedReading: String?,
        normalizedTerm: String,
    ): YomitanDataStore.TermLookup {
        val narrowed =
            if (normalizedReading == null) rows
            else rows.filter {
                it.reading == normalizedReading || it.reading == normalizedTerm
            }
        // Section order across dicts; score (desc) within a dict —
        // sortedByDescending is stable, so equal scores keep bank order.
        // One sense per bank ENTRY: an entry's multiple glossary items are
        // parallel glosses of one sense (JMdict ships one entry per sense),
        // joined the same way the pack joins its glosses; distinct senses
        // arrive as distinct entries and stay distinct rows.
        val groups = dictOrder.mapNotNull { (dictId, label) ->
            narrowed.filter { it.dictId == dictId }
                .sortedByDescending { it.score }
                .map { row ->
                    ImportedSense(
                        definition = row.defs.joinToString("; "),
                        pos = row.pos.split(' ').filter { it.isNotEmpty() }.joinToString(", "),
                    )
                }
                .takeIf { it.isNotEmpty() }
                ?.let { ImportedSenseGroup(label, it) }
        }
        val resolvedReading = normalizedReading
            ?: dictOrder.firstNotNullOfOrNull { (dictId, _) ->
                narrowed.firstOrNull { it.dictId == dictId }?.reading
            }
        return YomitanDataStore.TermLookup(groups, resolvedReading)
    }
}
