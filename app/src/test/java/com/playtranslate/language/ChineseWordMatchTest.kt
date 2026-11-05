package com.playtranslate.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ChineseWordMatch]: the dictionary-driven split of a HanLP term the
 * dictionary does not list (the segmenter's narrowing) and of a looked-up
 * unit into its member words. Pure JVM: no HanLP, no database.
 */
class ChineseWordMatchTest {

    private val specimen = listOf(
        "有", ",", "这些", "常用", "的", "东西", "我", "都", "放在", "包里", "了", ",", "我", "给", "你", "拿",
    )
    private val listed = setOf("这些", "常用", "东西")

    @Test fun `an unlisted HanLP term splits into its dictionary words and listed terms stay whole`() {
        assertEquals(
            listOf("有", ",", "这些", "常用", "的", "东西", "我", "都", "放", "在", "包", "里", "了", ",", "我", "给", "你", "拿"),
            ChineseWordMatch.narrow(specimen, listed),
        )
    }

    @Test fun `the match runs from the right end`() {
        // Forward would take 新版 and leave 本; backward takes 版本.
        assertEquals(listOf("新", "版本"), ChineseWordMatch.pieces("新版本", setOf("新版", "版本")))
        assertEquals(listOf("受", "限制"), ChineseWordMatch.pieces("受限制", setOf("受限", "限制")))
    }

    @Test fun `the longest listed suffix wins`() {
        assertEquals(
            listOf("中华", "人民共和国"),
            ChineseWordMatch.pieces("中华人民共和国", setOf("中华", "人民共和国", "共和国", "和国")),
        )
    }

    @Test fun `a single hanzi is always a unit`() {
        assertEquals(listOf("放", "在"), ChineseWordMatch.pieces("放在", emptySet()))
    }

    @Test fun `a listed term, a short term and a term with ASCII or a surrogate pair are left whole`() {
        assertEquals(listOf("东西"), ChineseWordMatch.pieces("东西", listed))
        assertEquals(listOf("的"), ChineseWordMatch.pieces("的", listed))
        assertEquals(listOf("4S店"), ChineseWordMatch.pieces("4S店", listed))
        val extensionB = "𠀀字"
        assertEquals(listOf(extensionB), ChineseWordMatch.pieces(extensionB, listed))
    }

    @Test fun `the probe asks for every multi-character word and every multi-character substring`() {
        assertEquals(
            setOf("放在", "简体", "简体中", "简体中文", "体中", "体中文", "中文"),
            ChineseWordMatch.probeCandidates(listOf("放在", "简体中文", "的", "4S店")),
        )
    }

    @Test fun `members are the listed words inside a unit, the unit itself excluded`() {
        assertEquals(listOf("常", "用"), members("常用", setOf("常用"), null))
        assertEquals(listOf("翻译", "器"), members("翻译器", setOf("翻译器", "翻译"), null))
        assertEquals(listOf("简体", "中文"), members("简体中文", setOf("简体中文", "简体", "中文"), null))
        assertEquals(listOf("放", "在"), members("放在", emptySet(), null))
    }

    @Test fun `a reduplication yields its one member and punctuation is never a member`() {
        assertEquals(listOf("妈"), members("妈妈", setOf("妈妈"), null))
        assertEquals(
            listOf("一", "不", "做", "二", "休"),
            members("一不做，二不休", setOf("一不做，二不休"), "yī bù zuò , èr bù xiū"),
        )
    }

    @Test fun `a unit under two characters or holding a surrogate pair has no members`() {
        assertEquals(emptyList<String>(), members("里", emptySet(), null))
        assertEquals(emptyList<String>(), members("𠀀字", emptySet(), null))
    }

    @Test fun `each member carries its slice of the displayed reading, lowercased`() {
        val bank = ChineseWordMatch.memberSplit("银行", setOf("银行"), "yín háng")
        assertEquals(listOf("yín", "háng"), bank.map { it.reading })
        val china = ChineseWordMatch.memberSplit("中国", setOf("中国"), "Zhōng guó")
        assertEquals(listOf("zhōng", "guó"), china.map { it.reading })
        val script = ChineseWordMatch.memberSplit("简体中文", setOf("简体中文", "简体", "中文"), "jiǎn tǐ zhōng wén")
        assertEquals(listOf("jiǎn tǐ", "zhōng wén"), script.map { it.reading })
    }

    @Test fun `a reading whose token count differs from the character count gives no hint`() {
        for (span in ChineseWordMatch.memberSplit("银行", setOf("银行"), "yín")) assertNull(span.reading)
        for (span in ChineseWordMatch.memberSplit("银行", setOf("银行"), null)) assertNull(span.reading)
    }

    @Test fun `a member span looks itself up`() {
        val span = ChineseWordMatch.memberSplit("放在", emptySet(), "fàng zài").first()
        assertEquals("放", span.surface)
        assertEquals("放", span.lookupForm)
        assertEquals("fàng", span.reading)
    }

    private fun members(headword: String, listed: Set<String>, reading: String?): List<String> =
        ChineseWordMatch.memberSplit(headword, listed, reading).map { it.surface }
}
