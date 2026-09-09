package com.playtranslate.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins [alignMemberReadings] — the reading hints for a fused headword's
 * member words, chosen so the members read as the headword does. The
 * specimen is the one the feature was built for: inside 瞬く間に the
 * tokenizer reads 瞬く as しばたたく and 間 as あいだ; aligned against
 * またたくまに the members must read またたく and ま, or they resolve to
 * the wrong entries downstream.
 */
class MemberReadingAlignmentTest {

    private fun u(surface: String, lookupForm: String = surface, vararg readings: String) =
        AlignUnit(surface, lookupForm, readings.toSet())

    private fun wildcard(surface: String) = AlignUnit(surface, surface, null)

    @Test fun `瞬く間に picks またたく and ま over the tokenizer's guesses`() {
        val units = listOf(
            u("瞬く", readings = arrayOf("またたく", "まばたく", "まだたく", "めたたく", "しばたたく")),
            u("間", readings = arrayOf("あいだ", "ま", "かん", "けん")),
            u("に", readings = arrayOf("に")),
        )
        assertEquals(listOf("またたく", "ま", "に"), alignMemberReadings(units, "またたくまに"))
    }

    @Test fun `an inflected kanji member reads through its lemma's reading`() {
        // 秘密を漏らした: 漏らし is 漏らす's surface; the hint is the lemma's
        // reading, which is what narrows the lookup. Kana units (を, た) are
        // given as their own surface reading, the way the engine builds them.
        val units = listOf(
            u("秘密", readings = arrayOf("ひみつ")),
            u("を", readings = arrayOf("を")),
            u("漏らした", "漏らす", "もらす", "もれ"),
        )
        assertEquals(listOf("ひみつ", "を", "もらす"), alignMemberReadings(units, "ひみつをもらした"))
    }

    @Test fun `rendaku on a member's first kana is tolerated and the hint stays unvoiced`() {
        val units = listOf(
            u("一人", readings = arrayOf("ひとり", "いちにん")),
            u("暮らし", readings = arrayOf("くらし")),
        )
        assertEquals(listOf("ひとり", "くらし"), alignMemberReadings(units, "ひとりぐらし"))
    }

    @Test fun `sokuon on a member's last kana is tolerated`() {
        val units = listOf(
            u("一", readings = arrayOf("いち", "ひと")),
            u("泊", readings = arrayOf("はく")),
        )
        assertEquals(listOf("いち", "はく"), alignMemberReadings(units, "いっぱく"))
    }

    @Test fun `a member with no entry is a wildcard and the rest still align`() {
        val units = listOf(wildcard("手当たり"), u("次第", readings = arrayOf("しだい")))
        assertEquals(listOf(null, "しだい"), alignMemberReadings(units, "てあたりしだい"))
    }

    @Test fun `a wildcard cannot swallow a neighbour that fits`() {
        val units = listOf(wildcard("手当たり"), u("次第", readings = arrayOf("しだい")))
        // Shortest run first: てあたり, not てあたりし + だい.
        assertEquals(listOf(null, "しだい"), alignMemberReadings(units, "てあたりしだい"))
        assertNull(alignMemberReadings(listOf(wildcard("手当たり"), u("次第", readings = arrayOf("じだい"))), "てあたりしだい"))
    }

    @Test fun `a reading that does not decompose aligns nothing`() {
        val units = listOf(
            u("瞬く", readings = arrayOf("しばたたく")),
            u("間", readings = arrayOf("あいだ")),
            u("に", readings = arrayOf("に")),
        )
        assertNull(alignMemberReadings(units, "またたくまに"))
        assertNull(alignMemberReadings(emptyList(), "またたくまに"))
        assertNull(alignMemberReadings(units, ""))
    }

    @Test fun `kana units read as themselves whatever their lemma`() {
        // The copula に lemmatizes to だ and します to する; neither is an
        // okurigana swap. 別に = べつに, お願いします = おねがいします.
        assertEquals(
            listOf("べつ", "に"),
            alignMemberReadings(listOf(u("別", readings = arrayOf("べつ", "わけ")), u("に", "だ", "に")), "べつに"),
        )
        assertEquals(
            listOf("おねがい", "します"),
            alignMemberReadings(listOf(u("お願い", readings = arrayOf("おねがい")), u("します", "する", "します")), "おねがいします"),
        )
        assertEquals(listOf("に" to "に"), surfaceReadingCandidates("に", "だ", setOf("に")))
    }

    @Test fun `surface reading candidates swap the lemma's tail for the surface's`() {
        assertEquals(listOf("もらし" to "もらす"), surfaceReadingCandidates("漏らし", "漏らす", setOf("もらす")))
        assertEquals(listOf("なった" to "なる"), surfaceReadingCandidates("成った", "成る", setOf("なる")))
        assertEquals(listOf("またたく" to "またたく"), surfaceReadingCandidates("瞬く", "瞬く", setOf("またたく")))
        // A reading not ending in the lemma's tail offers nothing.
        assertEquals(emptyList<Pair<String, String>>(), surfaceReadingCandidates("漏らし", "漏らす", setOf("もれ")))
    }
}
