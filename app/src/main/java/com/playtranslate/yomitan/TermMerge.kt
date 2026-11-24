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
     * order. A non-empty [normalizedReadings] is a HARD disambiguator: only
     * rows with one of those readings survive — widening to other readings
     * of the same spelling would attach a homograph's definitions (端/はじ)
     * under the resolved word (端/はし). Rows whose stored reading is just
     * the term itself ([normalizedTerm]) didn't disambiguate at all (the
     * format's blank-reading sentinel, common in sloppier conversions) and
     * match any supplied reading. With no reading supplied, every row for
     * the term applies, unless [resolveToFirstReading]: then the term's
     * first row, in section order (the reading the result reports as
     * resolved), picks the reading and only its rows survive, so a lookup
     * that could not narrow still yields ONE word's senses under the
     * reading it reports, instead of every homograph's senses under the
     * first one's reading. In memory, over the rows already fetched. Set by
     * the imported lookup's pack-miss retry alone; a null-reading lookup
     * from a caller whose readings are not Yomitan keys (ChineseEngine,
     * pinyin) keeps every row, as its heteronyms need.
     *
     * A SET rather than one reading because the caller's disambiguator is
     * not always a single reading: when the tap's reading narrows nothing,
     * [com.playtranslate.language.YomitanEnrichment] retries with every
     * reading the built-in pack resolved for the spelling, so the imported
     * groups are narrowed by exactly the identity the pack landed on.
     *
     * Every dictionary with surviving rows gets its group: which of them
     * are SHOWN, and whether the built-in dictionary's senses are, is
     * [shown]'s, decided by the caller that knows whether the built-in
     * dictionary has the word. [singleDictionary] and [builtInEnabled] (the
     * user's two TERMS switches) ride the returned lookup to it untouched.
     * [builtInSlot] is the Built-in dictionary row's place in [dictOrder],
     * the number of its dictionaries the user's list puts above that row:
     * a group of a dictionary at or past it is marked
     * [ImportedSenseGroup.belowBuiltIn]. The default is the row's own
     * default place, below every dictionary.
     */
    fun merge(
        rows: List<Row>,
        dictOrder: List<Pair<String, String>>,
        normalizedReadings: Set<String>?,
        normalizedTerm: String,
        singleDictionary: Boolean = false,
        builtInSlot: Int = dictOrder.size,
        builtInEnabled: Boolean = true,
        /** Per-dictionary accent override (ARGB) keyed by dict id; tints each
         *  imported group's title. Defaulted so existing callers are unaffected. */
        dictColors: Map<String, Int?> = emptyMap(),
        resolveToFirstReading: Boolean = false,
        /** The comparison key for a reading on BOTH sides of the narrowing
         *  filter, identity by default. Chinese readings are pinyin, which
         *  dictionaries write in several formats, so its callers pass
         *  [com.playtranslate.language.PinyinFormatter.canonicalPinyin] and an
         *  imported row written "dong1 xi1" matches a pack reading written
         *  "dōng xī". [resolvedReading] stays the caller's own string. */
        readingKey: (String) -> String = { it },
        /** Readings whose rows belong to ANOTHER entry of the same spelling
         *  and are dropped before any narrowing, compared through
         *  [readingKey]; empty by default. The caller that hands out one
         *  entry per reading without narrowing its default one (ChineseEngine:
         *  东西 read dōng xi leads, dōng xī is its own entry) passes the other
         *  entries' readings, so "east and west" does not sit under "thing".
         *  A row matching none of them, one whose pinyin follows another
         *  convention or that carries no reading, stays: exclusion can move
         *  a row to its own entry, never hide one. */
        excludedReadings: Set<String> = emptySet(),
    ): YomitanDataStore.TermLookup {
        val candidates = if (excludedReadings.isEmpty()) {
            rows
        } else {
            val excluded = excludedReadings.mapTo(HashSet(), readingKey)
            rows.filterNot { readingKey(it.reading) in excluded }
        }
        val narrowed = when {
            !normalizedReadings.isNullOrEmpty() -> {
                val keys = normalizedReadings.mapTo(HashSet(), readingKey)
                candidates.filter { readingKey(it.reading) in keys || it.reading == normalizedTerm }
            }
            resolveToFirstReading -> {
                // The reading [resolvedReading] reports below: the first row,
                // in section order, of the first dictionary with rows.
                val first = dictOrder.firstNotNullOfOrNull { (dictId, _) ->
                    candidates.firstOrNull { it.dictId == dictId }?.reading
                }
                if (first == null) candidates else candidates.filter { it.reading == first }
            }
            else -> candidates
        }
        // Section order across dicts; score (desc) within a dict —
        // sortedByDescending is stable, so equal scores keep bank order.
        // One sense per bank ENTRY: an entry's multiple glossary items are
        // parallel glosses of one sense (JMdict ships one entry per sense),
        // joined the same way the pack joins its glosses; distinct senses
        // arrive as distinct entries and stay distinct rows.
        val groups = dictOrder.withIndex().mapNotNull { (position, dict) ->
            val (dictId, label) = dict
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
                ?.let {
                    ImportedSenseGroup(
                        label, it, dictColors[dictId], dictId = dictId,
                        belowBuiltIn = position >= builtInSlot,
                    )
                }
        }
        // One supplied reading IS the resolved reading; a SET disambiguates
        // without naming one, so the surviving rows answer instead — the
        // same source a null disambiguator has always used.
        val resolvedReading = normalizedReadings?.singleOrNull()
            ?: dictOrder.firstNotNullOfOrNull { (dictId, _) ->
                narrowed.firstOrNull { it.dictId == dictId }?.reading
            }
        return YomitanDataStore.TermLookup(
            groups,
            resolvedReading,
            singleDictionary = singleDictionary,
            builtInEnabled = builtInEnabled,
        )
    }

    /**
     * THE rule for which dictionaries' definitions a word shows, over the
     * imported [groups] that have it (in the user's order, each marked with
     * its side of the Built-in dictionary row), the user's two TERMS
     * switches, and whether the built-in dictionary has the word
     * ([builtInHasWord]):
     *
     *  - No imported dictionary has the word: the built-in one is shown
     *    whatever its switch says. It is the only dictionary with a result,
     *    which is also what gives a language with no imported dictionary
     *    its definitions while the switch is off.
     *  - Its row switched off ([builtInEnabled] false): withheld. Off, it
     *    never counts as a dictionary with a result while another has one.
     *  - [singleDictionary]: the first row in the user's order that has the
     *    word wins and nothing else is shown. The Built-in row is one of
     *    those rows, at its own place: it wins when it has the word and
     *    every imported group is below it. Otherwise the first group wins,
     *    which is the highest-priority imported dictionary with a result
     *    (groups exist only for dictionaries whose rows survived the
     *    narrowing), and the built-in one is withheld.
     *  - Otherwise every group and the built-in dictionary are shown, the
     *    built-in one where the groups' marks put it.
     */
    fun shown(
        groups: List<ImportedSenseGroup>,
        singleDictionary: Boolean,
        builtInEnabled: Boolean,
        builtInHasWord: Boolean,
    ): YomitanDataStore.ShownTerms = when {
        groups.isEmpty() -> YomitanDataStore.ShownTerms(groups, builtInWithheld = false)
        !singleDictionary -> YomitanDataStore.ShownTerms(groups, builtInWithheld = !builtInEnabled)
        builtInEnabled && builtInHasWord && groups.first().belowBuiltIn ->
            YomitanDataStore.ShownTerms(emptyList(), builtInWithheld = false)
        else -> YomitanDataStore.ShownTerms(groups.take(1), builtInWithheld = true)
    }
}
