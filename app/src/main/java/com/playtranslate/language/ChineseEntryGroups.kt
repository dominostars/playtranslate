package com.playtranslate.language

import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.HanziDetail
import com.playtranslate.model.HanziEntry
import com.playtranslate.model.Headword
import com.playtranslate.model.ImportedKanji
import com.playtranslate.model.PosVocabulary
import com.playtranslate.model.Sense
import com.playtranslate.model.expressionFrom
import java.util.Locale

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
 *  (e) frequency, which separates the words of two simplified forms that
 *      share a traditional one and never two readings of one written word,
 *      since the pack scores the written word;
 *  (f) the reading [ChineseReadingDefaults] names for the word, which is
 *      what tells 要 yào "to want" from yāo "to demand";
 *  (g) the pack's line order, CC-CEDICT's alphabetical pinyin.
 * The same order gives [candidateReadings] for the heteronym pass, so the
 * first candidate is the reading an unhinted lookup leads with. Pure, so the
 * rule is tested without a database.
 */
internal object ChineseEntryGroups {

    /** The groups of [lines] for [surface], in the order above with no hint.
     *  [leadReading] gives the reading a simplified headword leads with, as
     *  the pack stores it, or null ([ChineseReadingDefaults.leadReading]). */
    fun groups(
        lines: List<CedictLine>,
        surface: String,
        preferTraditional: Boolean,
        leadReading: (String) -> String? = { null },
    ): List<EntryGroup> {
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
                .thenBy { group -> if (group.lines.any { leadReading(it.simplified) == it.reading }) 0 else 1 }
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

    /** Whether the group [groups] leads with is the one [leadReading] names
     *  for its word, so the default rests on the table and not on the pack's
     *  line order alone. */
    fun leadIsNamed(groups: List<EntryGroup>, leadReading: (String) -> String?): Boolean =
        groups.firstOrNull()?.lines?.any { leadReading(it.simplified) == it.reading } == true

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

    /**
     * The entries a character's row shows on a word's page, out of the
     * character's [groups] as a lookup orders them. They are every reading
     * of the written character the lookup leads with: all of a Simplified
     * reader's 沙 (shā, shà and the surname Shā), and for a Traditional
     * reader's 里 the character 里 and not 裡 or 裏, which are other
     * characters in that script and stand among 里's groups only because
     * they share its simplified form (a variant's cross-reference line, 㑺
     * among 俊's, leaves the same way).
     *
     * The readings the word uses come first, in the order it uses them
     * ([usedReadings], tone-marked, matched as a lookup's reading hint is by
     * [hintedIndex]), so 银行 shows 行 háng before xíng. A proper noun is
     * never one of them: a compound's capital is the compound's own (中国
     * Zhōng guó), so the syllables are compared lowercased, and proper nouns
     * stay after every common word, where [groups] has them. A syllable no
     * group carries, a neutral tone among them (地方 dì fang), moves
     * nothing.
     */
    fun characterGroups(groups: List<EntryGroup>, usedReadings: List<String>): List<EntryGroup> {
        val form = groups.firstOrNull()?.displayedForm ?: return emptyList()
        val own = groups.filter { it.displayedForm == form }
        val used = usedReadings
            .mapNotNull { hintedIndex(own, it.lowercase(Locale.ROOT))?.let(own::get) }
            .distinct()
        return used + (own - used.toSet())
    }

    /**
     * The Hanzi row of [literal] from its [groups], already in the row's
     * order ([characterGroups]), and what an imported character dictionary
     * holds for it; null when neither gives a meaning. One block per group,
     * its glosses under its own pinyin. An imported dictionary's meanings
     * win when it has any (the user installed it and ordered it), carrying
     * its declared language, as a kanji's do in the Japanese engine; they
     * are not divided by reading and a Yomitan kanji entry's on and kun
     * readings are not pinyin, so they stand as one block under every
     * reading the pack lists. Common and the frequency score are the
     * character's: common in any of its entries, and the highest score
     * among them, which only a Traditional reader's character standing for
     * two simplified ones can differ on.
     */
    fun hanziDetail(literal: Char, groups: List<EntryGroup>, imported: ImportedKanji?): HanziDetail? {
        val packEntries = groups.map(::toEntry)
        val entries: List<HanziEntry>
        val meaningsLang: String
        if (imported != null && imported.meanings.isNotEmpty()) {
            entries = listOf(HanziEntry(groups.map { it.reading }.distinct(), imported.meanings))
            meaningsLang = imported.meaningsLang
        } else {
            entries = groups.zip(packEntries) { group, entry ->
                HanziEntry(listOf(group.reading), entry.senses.flatMap { it.targetDefinitions })
            }
            meaningsLang = "en"
        }
        if (entries.isEmpty()) return null
        return HanziDetail(
            literal = literal,
            entries = entries,
            isCommon = packEntries.any { it.isCommon == true },
            freqScore = packEntries.maxOfOrNull { it.freqScore } ?: 0,
            meaningsLang = meaningsLang,
        )
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
