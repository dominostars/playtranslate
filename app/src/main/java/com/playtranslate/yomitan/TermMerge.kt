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
     *  arrive in rowid (bank) order. [scRowid] is the row's own rowid when
     *  a `term_sc` structured-glossary row exists for it (null otherwise) —
     *  carried through to [ImportedSense.scRowid] so render surfaces know
     *  styled content is fetchable without a second probe. */
    data class Row(
        val dictId: String,
        val reading: String,
        val score: Double,
        val defs: List<String>,
        val pos: String = "",
        val scRowid: Long? = null,
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
     *
     * [singleDictionary] (the user's TERMS-section toggle) keeps only the
     * first dictionary's group — groups exist only for dicts whose rows
     * survived narrowing, so "first" already IS the highest-priority dict
     * with results, and falling through to lower-priority dicts on a miss
     * needs no extra logic. The built-in pack is the implicit LAST source
     * in that priority order: when a group wins, the returned lookup also
     * flags the pack's senses for exclusion.
     */
    fun merge(
        rows: List<Row>,
        dictOrder: List<Pair<String, String>>,
        normalizedReading: String?,
        normalizedTerm: String,
        singleDictionary: Boolean = false,
        /** Per-dictionary accent override (ARGB) keyed by dict id; tints each
         *  imported group's title. Defaulted so existing callers are unaffected. */
        dictColors: Map<String, Int?> = emptyMap(),
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
                        scRowid = row.scRowid,
                    )
                }
                .takeIf { it.isNotEmpty() }
                ?.let { ImportedSenseGroup(label, it, dictColors[dictId], dictId = dictId) }
        }.let { if (singleDictionary) it.take(1) else it }
        val resolvedReading = normalizedReading
            ?: dictOrder.firstNotNullOfOrNull { (dictId, _) ->
                narrowed.firstOrNull { it.dictId == dictId }?.reading
            }
        return YomitanDataStore.TermLookup(
            groups,
            resolvedReading,
            suppressesPackSenses = singleDictionary && groups.isNotEmpty(),
        )
    }
}
