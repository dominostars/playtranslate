package com.playtranslate.language

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
}
