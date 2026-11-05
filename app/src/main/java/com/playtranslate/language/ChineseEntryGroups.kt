package com.playtranslate.language

import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.Headword
import com.playtranslate.model.PosVocabulary
import com.playtranslate.model.Sense
import com.playtranslate.model.expressionFrom

/** One CC-CEDICT line as the ZH pack stores it: one entry row, its
 *  simplified form (headword position 0), its traditional form (position 1,
 *  null when the same), its one numbered reading and its one sense. */
internal data class CedictLine(
    val id: Long,
    val freqScore: Int,
    val isCommon: Boolean,
    /** Numbered, as stored: "li3", "Bao1", "dong1 xi5". A capital initial
     *  is CC-CEDICT's proper-noun convention. */
    val reading: String,
    val simplified: String,
    val traditional: String?,
    val pos: String,
    val glosses: List<String>,
    val misc: List<String>,
)

/**
 * The lines of one surface that are one word: the same reading and the same
 * displayed form. [lines] are the surviving lines in order, [forms] every
 * distinct written form of the group with [displayedForm] first.
 */
internal data class EntryGroup(
    val displayedForm: String,
    /** Tone-marked, capital kept: "lǐ", "Lǐ", "dōng xi". */
    val reading: String,
    val lines: List<CedictLine>,
    val forms: List<String>,
) {
    val packId: Long get() = lines.first().id
    val isProperNoun: Boolean get() = reading.firstOrNull()?.isUpperCase() == true
}

/**
 * How the lines CC-CEDICT holds for one surface become dictionary entries.
 * CC-CEDICT has no senses: one line is one headword, one reading and a flat
 * gloss list, and a surface gets several lines only by reading (东西 dōngxī
 * "east and west" beside dōngxi "thing"), by proper-noun capitalisation (包
 * Bāo beside bāo) or by traditional characters that merged into one
 * simplified character (里 is 裏 "lining", 裡 "inside" and 里 "li,
 * neighbourhood", all lǐ). The pack used to hand every line out as its own
 * entry, and every surface flattened them as one word under the first
 * line's pinyin, with ties on `freq_score` leaving that first line to
 * chance, so the surname could lead.
 *
 * One group per (reading, displayed form). A Simplified reader's 里 is one
 * lǐ entry with one sense per line; a Traditional reader keeps 裡, 裏 and
 * 里 apart, which is right in that script. Inside a group a line that only
 * cross-references another ("variant of 裡|里[li3]", "also written …",
 * "see …", each citing a headword in brackets) is dropped when another
 * line survives, and kept when it is all the group has.
 *
 * Group order, which is also the default entry a lookup leads with:
 *  (a) the group whose reading matches the caller's hint, exactly or by
 *      [PinyinFormatter.readingsEqual];
 *  (b) a common word before a proper noun (lowercase initial first);
 *  (c) the group whose displayed form is the looked-up surface (nothing
 *      for Simplified readers; for a Traditional reader 里 leads 裡 and
 *      裏, and a character that is both a simplified character and another
 *      character's traditional form, 乾, leads with its own word);
 *  (d) a group with a defining line before one that only cross-references;
 *  (e) frequency; (f) the pack's line order.
 * The same order gives [candidateReadings] for the heteronym pass, so the
 * first candidate is the reading an unhinted lookup leads with. Pure, so the
 * rule is tested without a database.
 */
internal object ChineseEntryGroups {

    /** The groups of [lines] for [surface], in the order above with no hint. */
    fun groups(lines: List<CedictLine>, surface: String, preferTraditional: Boolean): List<EntryGroup> {
        val byKey = LinkedHashMap<Pair<String, String>, MutableList<CedictLine>>()
        for (line in lines) {
            val displayed = if (preferTraditional) line.traditional ?: line.simplified else line.simplified
            byKey.getOrPut(line.reading to displayed) { mutableListOf() }.add(line)
        }
        return byKey.map { (key, members) ->
            val (reading, displayed) = key
            val ordered = members.sortedWith(compareByDescending<CedictLine> { it.freqScore }.thenBy { it.id })
            val defining = ordered.filterNot { isCrossReferenceOnly(it) }
            val forms = LinkedHashSet<String>().apply {
                add(displayed)
                for (line in ordered) {
                    add(line.simplified)
                    line.traditional?.let { add(it) }
                }
            }
            EntryGroup(
                displayedForm = displayed,
                reading = PinyinFormatter.numberedToToneMarks(reading),
                lines = defining.ifEmpty { ordered },
                forms = forms.toList(),
            )
        }.sortedWith(
            compareBy<EntryGroup> { if (it.isProperNoun) 1 else 0 }
                .thenBy { if (it.displayedForm == surface) 0 else 1 }
                .thenBy { group -> if (group.lines.all(::isCrossReferenceOnly)) 1 else 0 }
                .thenByDescending { group -> group.lines.maxOf { it.freqScore } }
                .thenBy { group -> group.lines.minOf { it.id } },
        )
    }

    /** The index in [groups] of the group [readingHint] names, or null when
     *  no hint or no group matches: exact tone-marked equality first, then
     *  [PinyinFormatter.readingsEqual]. */
    fun hintedIndex(groups: List<EntryGroup>, readingHint: String?): Int? {
        if (readingHint.isNullOrEmpty()) return null
        groups.indexOfFirst { it.reading == readingHint }.takeIf { it >= 0 }?.let { return it }
        return groups.indexOfFirst { PinyinFormatter.readingsEqual(it.reading, readingHint) }.takeIf { it >= 0 }
    }

    /** The readings the heteronym pass chooses between: the groups' readings
     *  in order, proper nouns left out (a surname is never a word's contextual
     *  reading), distinct. Its first element is the unhinted lookup's. */
    fun candidateReadings(groups: List<EntryGroup>): List<String> =
        groups.filterNot { it.isProperNoun }.map { it.reading }.distinct()

    /**
     * The readings among [groups] that name a different word than the group
     * at [index]: every other group's reading whose pinyin differs from that
     * group's own. A Traditional reader's 裡 and 裏 read lǐ as 里 itself
     * does and claim nothing from it; the surname Lǐ does. An imported row
     * carrying one of these belongs to that other entry, so the lookup that
     * leads with [index] leaves it out
     * ([com.playtranslate.yomitan.TermMerge.merge]'s excluded readings).
     */
    fun otherReadings(groups: List<EntryGroup>, index: Int): Set<String> {
        val own = PinyinFormatter.canonicalPinyin(groups[index].reading)
        return groups.filterIndexed { i, _ -> i != index }
            .map { it.reading }
            .filterTo(LinkedHashSet()) { PinyinFormatter.canonicalPinyin(it) != own }
    }

    /** The group as the one entry a lookup returns. */
    fun toEntry(group: EntryGroup): DictionaryEntry {
        val senses = group.lines.map { line ->
            Sense(
                targetDefinitions = line.glosses.map { PinyinFormatter.convertPinyinInBrackets(it) },
                partsOfSpeech = PosVocabulary.parse(line.pos),
                tags = emptyList(),
                restrictions = emptyList(),
                info = emptyList(),
                misc = line.misc,
            )
        }
        return DictionaryEntry(
            slug = group.displayedForm,
            packId = group.packId,
            isCommon = group.lines.any { it.isCommon },
            tags = emptyList(),
            jlpt = emptyList(),
            headwords = group.forms.map { Headword(written = it, reading = group.reading) },
            senses = senses,
            isExpression = expressionFrom(senses),
            freqScore = (group.lines.maxOf { it.freqScore } * 5 / 100).coerceIn(0, 5),
        )
    }

    private val CROSS_REFERENCE_PREFIXES = listOf(
        "variant of ", "old variant of ", "archaic variant of ", "erhua variant of ",
        "also written ", "see ",
    )

    /** A gloss that only points at another headword: a cross-reference
     *  prefix and a bracketed pinyin citation, which every CC-CEDICT
     *  cross-reference carries and a definition that happens to start with
     *  "see" does not. */
    private fun isCrossReference(gloss: String): Boolean =
        '[' in gloss && CROSS_REFERENCE_PREFIXES.any { gloss.startsWith(it) }

    private fun isCrossReferenceOnly(line: CedictLine): Boolean =
        line.glosses.isNotEmpty() && line.glosses.all(::isCrossReference)
}
