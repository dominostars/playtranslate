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
 * pack (ids and scores as of the 2026-07 build). Pure JVM: no database.
 */
class ChineseEntryGroupsTest {

    private fun line(
        id: Long, reading: String, simplified: String, traditional: String? = null,
        glosses: List<String>, freq: Int = 71, common: Boolean = true,
    ) = CedictLine(
        id = id, freqScore = freq, isCommon = common, reading = reading,
        simplified = simplified, traditional = traditional, pos = "", glosses = glosses, misc = emptyList(),
    )

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

    @Test fun `frequency orders groups of the same case and the pack's line order breaks a tie`() {
        val dongxi = listOf(
            line(41964, "dong1 xi1", "东西", "東西", listOf("east and west"), freq = 60),
            line(41965, "dong1 xi5", "东西", "東西", listOf("thing", "stuff"), freq = 70),
        )
        assertEquals(listOf("dōng xi", "dōng xī"), ChineseEntryGroups.groups(dongxi, "东西", false).map { it.reading })
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
        val dongxi = listOf(
            line(41964, "dong1 xi1", "东西", "東西", listOf("east and west"), freq = 60),
            line(41965, "dong1 xi5", "东西", "東西", listOf("thing"), freq = 70),
        )
        val groups = ChineseEntryGroups.groups(dongxi, "东西", false)
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
        val dongxi = ChineseEntryGroups.groups(
            listOf(
                line(41964, "dong1 xi1", "东西", "東西", listOf("east and west"), freq = 60),
                line(41965, "dong1 xi5", "东西", "東西", listOf("thing"), freq = 70),
            ),
            "东西", false,
        )
        assertEquals(setOf("dōng xī"), ChineseEntryGroups.otherReadings(dongxi, 0))
        assertEquals(setOf("dōng xi"), ChineseEntryGroups.otherReadings(dongxi, 1))
        val single = ChineseEntryGroups.groups(listOf(line(1, "fang4", "放", glosses = listOf("to put"))), "放", false)
        assertEquals(emptySet<String>(), ChineseEntryGroups.otherReadings(single, 0))
    }
}
