package com.playtranslate.language

import com.playtranslate.model.HanziEntry
import com.playtranslate.model.ImportedKanji
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChineseEntryGroups]: how CC-CEDICT's lines for one surface become one
 * entry per reading and displayed form, which group a lookup leads with,
 * and what the heteronym pass chooses between. Lines mirror the shipped
 * pack (ids and scores as of the 2026-07 build), where two readings of one
 * written word always score alike. Pure JVM: no database.
 */
class ChineseEntryGroupsTest {

    private fun line(
        id: Long, reading: String, simplified: String, traditional: String? = null,
        glosses: List<String>, freq: Int = 71, common: Boolean = true,
    ) = CedictLine(
        id = id, freqScore = freq, isCommon = common, reading = reading,
        simplified = simplified, traditional = traditional, pos = "", glosses = glosses, misc = emptyList(),
    )

    /** 东西 as the pack has it: two readings of one written word, which the
     *  pack scores alike, since its frequency is the written word's. */
    private val dongxi = listOf(
        line(41964, "dong1 xi1", "东西", "東西", listOf("east and west"), freq = 64),
        line(41965, "dong1 xi5", "东西", "東西", listOf("thing", "stuff"), freq = 64),
    )

    /** The lead-reading table's rows a test needs. */
    private fun lead(vararg rows: Pair<String, String>): (String) -> String? = mapOf(*rows)::get

    private val li = listOf(
        line(71264, "li3", "里", "裏", listOf("variant of 裡|里[li3]")),
        line(71416, "li3", "里", "裡", listOf("lining", "interior", "inside", "internal", "also written 裏|里[li3]")),
        line(80257, "Li3", "里", null, listOf("Li (surname)")),
        line(80258, "li3", "里", null, listOf("li, ancient measure of length, approx. 500 m", "neighborhood")),
    )

    @Test fun `a Simplified reader's 里 is one lǐ entry with a sense per defining line, the surname second`() {
        val groups = ChineseEntryGroups.groups(li, "里", preferTraditional = false)
        assertEquals(listOf("lǐ", "Lǐ"), groups.map { it.reading })
        val common = groups[0]
        assertEquals(listOf(71416L, 80258L), common.lines.map { it.id })
        assertEquals(71416L, common.packId)
        assertEquals(listOf("里", "裏", "裡"), common.forms)
        val entry = ChineseEntryGroups.toEntry(common)
        assertEquals(listOf("lining", "li, ancient measure of length, approx. 500 m"), entry.senses.map { it.targetDefinitions.first() })
        assertEquals("also written 裏|里[lǐ]", entry.senses[0].targetDefinitions.last())
        assertEquals(listOf("里", "裏", "裡"), entry.headwords.map { it.written })
        assertEquals("lǐ", entry.headwords.first().reading)
        assertEquals("里", entry.slug)
        assertEquals(71416L, entry.packId)
        assertEquals(3, entry.freqScore)
        assertEquals(true, entry.isCommon)
        assertFalse(entry.isExpression)
    }

    @Test fun `a Traditional reader keeps 裡, 裏 and 里 apart and the tapped form leads`() {
        val groups = ChineseEntryGroups.groups(li, "里", preferTraditional = true)
        assertEquals(listOf("里", "裡", "裏", "里"), groups.map { it.displayedForm })
        assertEquals(listOf("lǐ", "lǐ", "lǐ", "Lǐ"), groups.map { it.reading })
        assertEquals(80258L, groups[0].packId)
        assertEquals(listOf("裡", "里"), groups[1].forms)
        // The lone cross-reference line is its own group here and keeps its line.
        assertEquals(listOf(71264L), groups[2].lines.map { it.id })
    }

    @Test fun `a common word leads a proper noun whatever the line order`() {
        val bao = listOf(
            line(11937, "Bao1", "包", glosses = listOf("surname Bao"), freq = 55),
            line(11938, "bao1", "包", glosses = listOf("to cover", "to wrap"), freq = 55),
        )
        assertEquals(listOf("bāo", "Bāo"), ChineseEntryGroups.groups(bao, "包", false).map { it.reading })
    }

    @Test fun `frequency orders the words of two simplified forms and the pack's line order breaks a tie`() {
        // A Traditional reader's 著 is 着's particle (the commoner written
        // word) before 著's own "to make known".
        val zhu = listOf(
            line(69030, "zhe5", "着", "著", listOf("aspect particle indicating action in progress or ongoing state"), freq = 73),
            line(69032, "zhu4", "著", null, listOf("to make known", "to show"), freq = 56),
        )
        assertEquals(listOf("zhe", "zhù"), ChineseEntryGroups.groups(zhu.reversed(), "著", true).map { it.reading })
        // Two readings of one written word tie on frequency: line order.
        assertEquals(listOf("dōng xī", "dōng xi"), ChineseEntryGroups.groups(dongxi, "东西", false).map { it.reading })
        val tie = listOf(
            line(2, "a1", "x", glosses = listOf("second")),
            line(1, "b1", "x", glosses = listOf("first")),
        )
        assertEquals(listOf("b1", "a1").map(PinyinFormatter::numberedToToneMarks), ChineseEntryGroups.groups(tie, "x", false).map { it.reading })
    }

    @Test fun `a character that is also another character's traditional form leads with its own word`() {
        val qian = listOf(
            line(3057, "gan1", "干", "乹", listOf("old variant of 乾|干[gan1]"), freq = 63),
            line(3060, "Gan1", "干", "乾", listOf("surname Gan"), freq = 63),
            line(3061, "gan1", "干", "乾", listOf("dry", "dried food"), freq = 63),
            line(3062, "qian2", "乾", null, listOf("one of the Eight Trigrams 八卦[ba1 gua4]"), freq = 50),
        )
        val groups = ChineseEntryGroups.groups(qian, "乾", false)
        assertEquals(listOf("qián", "gān", "Gān"), groups.map { it.reading })
        assertEquals(listOf("乾", "干", "干"), groups.map { it.displayedForm })
        // The gān group drops its cross-reference line and keeps 乹 as a form.
        assertEquals(listOf(3061L), groups[1].lines.map { it.id })
        assertEquals(listOf("干", "乹", "乾"), groups[1].forms)
    }

    @Test fun `a cross-reference needs a bracketed citation, and a group of only cross-references keeps them`() {
        val lines = listOf(
            line(1, "zai4 jian4", "再见", "再見", listOf("see you again later")),
            line(2, "zai4 jian4", "再见", "再見", listOf("variant of 再見|再见[zai4 jian4]")),
        )
        val groups = ChineseEntryGroups.groups(lines, "再见", false)
        assertEquals(listOf(1L), groups.single().lines.map { it.id })
        val only = listOf(line(2, "zai4 jian4", "再见", "再見", listOf("variant of 再見|再见[zai4 jian4]")))
        assertEquals(listOf(2L), ChineseEntryGroups.groups(only, "再见", false).single().lines.map { it.id })
    }

    @Test fun `the table's reading leads two readings of one word, and line order where it has no row`() {
        val named = ChineseEntryGroups.groups(dongxi, "东西", false, lead("东西" to "dong1 xi5"))
        assertEquals(listOf("dōng xi", "dōng xī"), named.map { it.reading })
        assertEquals("thing", ChineseEntryGroups.toEntry(named.first()).senses.single().targetDefinitions.first())
        // A row for another word, and a row naming a reading the pack does
        // not list for this one, name nothing: line order.
        assertEquals(listOf("dōng xī", "dōng xi"), ChineseEntryGroups.groups(dongxi, "东西", false, lead("南北" to "nan2 bei3")).map { it.reading })
        assertEquals(listOf("dōng xī", "dōng xi"), ChineseEntryGroups.groups(dongxi, "东西", false, lead("东西" to "dong1 xi2")).map { it.reading })
        // 要: yāo "to demand" is listed first, yào "to want" is the word.
        val yao = listOf(
            line(71855, "yao1", "要", glosses = listOf("(bound form) to demand; to coerce"), freq = 76),
            line(71856, "yao4", "要", glosses = listOf("to want; to need; to ask for"), freq = 76),
        )
        assertEquals(listOf("yāo", "yào"), ChineseEntryGroups.groups(yao, "要", false).map { it.reading })
        assertEquals(listOf("yào", "yāo"), ChineseEntryGroups.groups(yao, "要", false, lead("要" to "yao4")).map { it.reading })
    }

    @Test fun `a Traditional reader's word is named through its simplified headword`() {
        val groups = ChineseEntryGroups.groups(dongxi, "東西", true, lead("东西" to "dong1 xi5"))
        assertEquals(listOf("dōng xi", "dōng xī"), groups.map { it.reading })
        assertEquals(listOf("東西", "東西"), groups.map { it.displayedForm })
    }

    @Test fun `the table breaks only the tie the tiers above it leave`() {
        // A proper noun never leads, named or not.
        val bao = listOf(
            line(11937, "Bao1", "包", glosses = listOf("surname Bao"), freq = 55),
            line(11938, "bao1", "包", glosses = listOf("to cover", "to wrap"), freq = 55),
        )
        assertEquals(listOf("bāo", "Bāo"), ChineseEntryGroups.groups(bao, "包", false, lead("包" to "Bao1")).map { it.reading })
        // A reading that only cites another headword never leads a defining one.
        val ei = listOf(
            line(73099, "ei2", "诶", "誒", listOf("variant of 欸[ei2]"), freq = 40),
            line(73102, "xi1", "诶", "誒", listOf("sigh (to express regret)"), freq = 40),
        )
        assertEquals(listOf("xī", "éi"), ChineseEntryGroups.groups(ei, "诶", false, lead("诶" to "ei2")).map { it.reading })
        // The tapped form's own word leads another character's word.
        val qian = listOf(
            line(3061, "gan1", "干", "乾", listOf("dry", "dried food"), freq = 63),
            line(3062, "qian2", "乾", null, listOf("one of the Eight Trigrams 八卦[ba1 gua4]"), freq = 63),
        )
        assertEquals(listOf("qián", "gān"), ChineseEntryGroups.groups(qian, "乾", false, lead("干" to "gan1")).map { it.reading })
        // The commoner written word leads a named reading of a rarer one.
        val zhu = listOf(
            line(69030, "zhe5", "着", "著", listOf("aspect particle indicating action in progress or ongoing state"), freq = 73),
            line(69032, "zhu4", "著", null, listOf("to make known", "to show"), freq = 56),
        )
        assertEquals(listOf("zhe", "zhù"), ChineseEntryGroups.groups(zhu, "著", true, lead("著" to "zhu4")).map { it.reading })
    }

    @Test fun `the lead is named only when the table names the group that leads`() {
        val named = lead("东西" to "dong1 xi5")
        assertTrue(ChineseEntryGroups.leadIsNamed(ChineseEntryGroups.groups(dongxi, "东西", false, named), named))
        // No row: the lead is line order's.
        val none = lead()
        assertFalse(ChineseEntryGroups.leadIsNamed(ChineseEntryGroups.groups(dongxi, "东西", false, none), none))
        // A row for a reading that cannot lead (it only cites another
        // headword) names nothing the lookup leads with.
        val ei = listOf(
            line(73099, "ei2", "诶", "誒", listOf("variant of 欸[ei2]"), freq = 40),
            line(73102, "xi1", "诶", "誒", listOf("sigh (to express regret)"), freq = 40),
        )
        val cited = lead("诶" to "ei2")
        assertFalse(ChineseEntryGroups.leadIsNamed(ChineseEntryGroups.groups(ei, "诶", false, cited), cited))
        assertFalse(ChineseEntryGroups.leadIsNamed(emptyList(), named))
    }

    @Test fun `the hint names a group exactly, then by numbered equivalence, else nothing`() {
        val groups = ChineseEntryGroups.groups(li, "里", false)
        assertEquals(1, ChineseEntryGroups.hintedIndex(groups, "Lǐ"))
        assertEquals(1, ChineseEntryGroups.hintedIndex(groups, "Li3"))
        assertEquals(0, ChineseEntryGroups.hintedIndex(groups, "lǐ"))
        assertNull(ChineseEntryGroups.hintedIndex(groups, "zzz"))
        assertNull(ChineseEntryGroups.hintedIndex(groups, null))
        assertNull(ChineseEntryGroups.hintedIndex(groups, ""))
    }

    @Test fun `the heteronym candidates are the unhinted order without proper nouns`() {
        val simplified = ChineseEntryGroups.groups(li, "里", false)
        assertEquals(listOf("lǐ"), ChineseEntryGroups.candidateReadings(simplified))
        val traditional = ChineseEntryGroups.groups(li, "里", true)
        assertEquals(listOf("lǐ"), ChineseEntryGroups.candidateReadings(traditional))
        val groups = ChineseEntryGroups.groups(dongxi, "东西", false, lead("东西" to "dong1 xi5"))
        assertEquals(listOf("dōng xi", "dōng xī"), ChineseEntryGroups.candidateReadings(groups))
        assertEquals(groups.first().reading, ChineseEntryGroups.candidateReadings(groups).first())
        assertTrue(ChineseEntryGroups.groups(li, "里", false)[1].isProperNoun)
    }

    @Test fun `the other readings are the other words' readings, never the landed group's own`() {
        val simplified = ChineseEntryGroups.groups(li, "里", false)
        assertEquals(setOf("Lǐ"), ChineseEntryGroups.otherReadings(simplified, 0))
        assertEquals(setOf("lǐ"), ChineseEntryGroups.otherReadings(simplified, 1))
        // A Traditional reader's 裡 and 裏 read lǐ as 里 does: they claim no
        // imported row from it. The surname does.
        val traditional = ChineseEntryGroups.groups(li, "里", true)
        assertEquals(setOf("Lǐ"), ChineseEntryGroups.otherReadings(traditional, 0))
        val thingFirst = ChineseEntryGroups.groups(dongxi, "东西", false, lead("东西" to "dong1 xi5"))
        assertEquals(setOf("dōng xī"), ChineseEntryGroups.otherReadings(thingFirst, 0))
        assertEquals(setOf("dōng xi"), ChineseEntryGroups.otherReadings(thingFirst, 1))
        val single = ChineseEntryGroups.groups(listOf(line(1, "fang4", "放", glosses = listOf("to put"))), "放", false)
        assertEquals(emptySet<String>(), ChineseEntryGroups.otherReadings(single, 0))
    }

    // ── A character's row on a word's page ──────────────────────────────

    /** 沙 as the pack has it: the surname's line first. */
    private val sha = listOf(
        line(46766, "Sha1", "沙", glosses = listOf("surname Sha"), freq = 47, common = false),
        line(46767, "sha1", "沙", glosses = listOf("granule", "sand", "CL:粒[li4]"), freq = 47, common = false),
        line(46768, "sha4", "沙", glosses = listOf("(dialect) to sift; to sieve"), freq = 47, common = false),
    )

    private val xing = listOf(
        line(90001, "hang2", "行", glosses = listOf("row", "line", "profession"), freq = 70),
        line(90002, "heng2", "行", glosses = listOf("used in 道行[dao4 heng2]"), freq = 70),
        line(90003, "xing2", "行", glosses = listOf("to walk", "to go"), freq = 70),
    )

    private fun row(lines: List<CedictLine>, surface: String, used: List<String>, traditional: Boolean = false, lead: (String) -> String? = { null }) =
        ChineseEntryGroups.characterGroups(ChineseEntryGroups.groups(lines, surface, traditional, lead), used)

    // Mutations: the row is the lead group alone; proper nouns are dropped.
    @Test fun `a character's row holds every entry of the character, the surname last`() {
        assertEquals(listOf("shā", "shà", "Shā"), row(sha, "沙", emptyList()).map { it.reading })
        // With no word reading the order is the lookup's own.
        val lookup = ChineseEntryGroups.groups(xing, "行", false, lead("行" to "xing2"))
        assertEquals(listOf("xíng", "háng", "héng"), lookup.map { it.reading })
        assertEquals(lookup, ChineseEntryGroups.characterGroups(lookup, emptyList()))
        assertEquals(emptyList<EntryGroup>(), ChineseEntryGroups.characterGroups(emptyList(), listOf("shā")))
    }

    // Mutations: the hint is ignored; the used readings follow the lookup's
    // order and not the word's; a matched group is listed twice.
    @Test fun `the readings the word uses lead, in the word's order, and the rest keep the lookup's`() {
        val named = lead("行" to "xing2")
        // 银行 yín háng.
        assertEquals(listOf("háng", "xíng", "héng"), row(xing, "行", listOf("háng"), lead = named).map { it.reading })
        // A word using two readings of one character: both lead, as used.
        assertEquals(listOf("héng", "háng", "xíng"), row(xing, "行", listOf("héng", "háng"), lead = named).map { it.reading })
        assertEquals(listOf("háng", "héng", "xíng"), row(xing, "行", listOf("háng", "héng"), lead = named).map { it.reading })
        // The lead named again changes nothing, and no group is listed twice.
        assertEquals(listOf("xíng", "háng", "héng"), row(xing, "行", listOf("xíng", "xíng"), lead = named).map { it.reading })
        // A reading written numbered names its group as a lookup's hint does.
        assertEquals(listOf("háng", "xíng", "héng"), row(xing, "行", listOf("hang2"), lead = named).map { it.reading })
    }

    // Mutations: the syllable is compared with its capital; a proper noun
    // the word names leads.
    @Test fun `a compound's capital names the common word, and a proper noun never leads one`() {
        val zhong = listOf(
            line(2901, "Zhong1", "中", glosses = listOf("China", "surname Zhong"), freq = 80),
            line(2902, "zhong1", "中", glosses = listOf("within", "among", "middle"), freq = 80),
            line(2903, "zhong4", "中", glosses = listOf("to hit (the mark)"), freq = 80),
        )
        // 中国 Zhōng guó: the capital is the compound's.
        assertEquals(listOf("zhōng", "zhòng", "Zhōng"), row(zhong, "中", listOf("Zhōng")).map { it.reading })
        assertEquals(listOf("zhòng", "zhōng", "Zhōng"), row(zhong, "中", listOf("Zhòng")).map { it.reading })
        // 沙特 Shā tè: the surname stays last.
        assertEquals(listOf("shā", "shà", "Shā"), row(sha, "沙", listOf("Shā")).map { it.reading })
    }

    @Test fun `a syllable no entry carries moves nothing, a neutral tone among them`() {
        // 萝卜 luó bo: the pack lists 卜 as bǔ and bó, and bo names neither.
        val bu = listOf(
            line(8001, "bo2", "卜", "蔔", listOf("used in 蘿蔔|萝卜[luo2 bo5]"), freq = 40),
            line(8002, "bu3", "卜", null, listOf("to divine", "to forecast"), freq = 40),
        )
        val lookup = ChineseEntryGroups.groups(bu, "卜", false, lead("卜" to "bu3"))
        assertEquals(listOf("bǔ", "bó"), lookup.map { it.reading })
        assertEquals(lookup, ChineseEntryGroups.characterGroups(lookup, listOf("bo")))
        assertEquals(lookup, ChineseEntryGroups.characterGroups(lookup, listOf("zzz", "")))
    }

    // Mutations: every group stands in the row whatever its form; the row
    // keeps the forms the lead does not have.
    @Test fun `a row is the written character the lookup leads with, not the characters that share its simplified form`() {
        // A Traditional reader's 里 is 里, and the surname written 里: 裡 and
        // 裏 are other characters there.
        val hant = row(li, "里", emptyList(), traditional = true)
        assertEquals(listOf("里" to "lǐ", "里" to "Lǐ"), hant.map { it.displayedForm to it.reading })
        assertEquals(listOf(80258L), hant[0].lines.map { it.id })
        // A Simplified reader's 里 is all of them, in one lǐ entry.
        val hans = row(li, "里", emptyList())
        assertEquals(listOf("lǐ", "Lǐ"), hans.map { it.reading })
        assertEquals(listOf(71416L, 80258L), hans[0].lines.map { it.id })
        // A Simplified reader's 乾 is the trigram: dry is written 干.
        val qian = listOf(
            line(3060, "Gan1", "干", "乾", listOf("surname Gan"), freq = 63),
            line(3061, "gan1", "干", "乾", listOf("dry", "dried food"), freq = 63),
            line(3062, "qian2", "乾", null, listOf("one of the Eight Trigrams 八卦[ba1 gua4]"), freq = 50),
        )
        assertEquals(listOf("qián"), row(qian, "乾", emptyList()).map { it.reading })
        // A Traditional reader's 乾 is both, dry first as the commoner word,
        // and the word's reading leads.
        assertEquals(listOf("gān", "qián", "Gān"), row(qian, "乾", emptyList(), traditional = true).map { it.reading })
        assertEquals(listOf("qián", "gān", "Gān"), row(qian, "乾", listOf("qián"), traditional = true).map { it.reading })
    }

    // Mutations: the row holds the first group's meanings alone; the
    // imported meanings are added beside the pack's; the imported block
    // names the first reading alone; the character is common only when its
    // first entry is.
    @Test fun `a hanzi's row is one block per entry, or an imported dictionary's meanings under every reading`() {
        val groups = row(sha, "沙", listOf("shà"))
        val pack = ChineseEntryGroups.hanziDetail('沙', groups, imported = null)!!
        assertEquals(
            listOf(
                HanziEntry(listOf("shà"), listOf("(dialect) to sift; to sieve")),
                HanziEntry(listOf("shā"), listOf("granule", "sand", "CL:粒[lì]")),
                HanziEntry(listOf("Shā"), listOf("surname Sha")),
            ),
            pack.entries,
        )
        assertEquals("en", pack.meaningsLang)
        assertEquals(2, pack.freqScore)
        assertFalse(pack.isCommon)
        assertEquals('沙', pack.literal)

        val imported = ImportedKanji(
            meanings = listOf("Sand", "Kies"), onReadings = listOf("サ"), kunReadings = emptyList(), meaningsLang = "de",
        )
        val merged = ChineseEntryGroups.hanziDetail('沙', groups, imported)!!
        assertEquals(listOf(HanziEntry(listOf("shà", "shā", "Shā"), listOf("Sand", "Kies"))), merged.entries)
        assertEquals("de", merged.meaningsLang)
        assertEquals(2, merged.freqScore)

        // An import with no meaning for the character leaves the pack's.
        val bare = imported.copy(meanings = emptyList())
        assertEquals(pack, ChineseEntryGroups.hanziDetail('沙', groups, bare))
        // A character only the import knows has its meanings and no pinyin.
        assertEquals(
            listOf(HanziEntry(emptyList(), listOf("Sand", "Kies"))),
            ChineseEntryGroups.hanziDetail('沙', emptyList(), imported)!!.entries,
        )
        // No meaning anywhere is no row.
        assertNull(ChineseEntryGroups.hanziDetail('沙', emptyList(), null))
        assertNull(ChineseEntryGroups.hanziDetail('沙', emptyList(), bare))

        // Common and the score are the character's: any entry's, the highest.
        val mixed = listOf(
            line(1, "a1", "啊", glosses = listOf("ah"), freq = 20, common = false),
            line(2, "a5", "啊", glosses = listOf("(particle)"), freq = 20, common = true),
        )
        assertTrue(ChineseEntryGroups.hanziDetail('啊', row(mixed, "啊", emptyList()), null)!!.isCommon)
        val twoWords = listOf(
            line(3061, "gan1", "干", "乾", listOf("dry"), freq = 63, common = false),
            line(3062, "qian2", "乾", null, listOf("one of the Eight Trigrams"), freq = 50, common = false),
        )
        assertEquals(3, ChineseEntryGroups.hanziDetail('乾', row(twoWords, "乾", listOf("qián"), traditional = true), null)!!.freqScore)
    }
}
