package com.playtranslate.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [buildHeadwords] — the kanji×reading pairing that, for single-kanji
 * entries, surfaces every reading (明日 → あした / あす / みょうにち) instead of
 * dropping all but the first, while leaving multi-kanji entries and pre-column
 * packs on the prior positional pairing. Pure JUnit; no SQLite.
 */
class BuildHeadwordsTest {

    private fun k(text: String, priority: Boolean = false) = JmKanjiForm(text, priority)
    private fun r(text: String, noKanji: Boolean = false, rankScore: Int = 0) =
        JmReadingForm(text, noKanji, rankScore)

    @Test fun `single kanji with several readings expands to one headword each`() {
        val hw = buildHeadwords(
            listOf(k("明日", priority = true)),
            listOf(r("あした"), r("あす"), r("みょうにち")),
            hasNoKanjiColumn = true,
        )
        assertEquals(listOf("あした", "あす", "みょうにち"), hw.map { it.reading })
        assertTrue("all share the written form", hw.all { it.written == "明日" })
        assertTrue("priority rides along to every reading", hw.all { it.hasPriority })
        assertEquals("primary reading stays first", "あした", hw.first().reading)
    }

    @Test fun `re_nokanji readings become kana-only headwords, not kanji-paired`() {
        val hw = buildHeadwords(
            listOf(k("彼")),
            listOf(r("かれ"), r("あれ", noKanji = true)),
            hasNoKanjiColumn = true,
        )
        // かれ pairs with the kanji; あれ (re_nokanji) stays a kana-only headword
        // so a lookup of あれ still resolves to あれ, not 彼/かれ.
        assertEquals(
            listOf("彼" to "かれ", null to "あれ"),
            hw.map { it.written to it.reading },
        )
    }

    @Test fun `all-re_nokanji single-kanji entry keeps its readings rather than vanish`() {
        val hw = buildHeadwords(
            listOf(k("〆")),
            listOf(r("しめ", noKanji = true)),
            hasNoKanjiColumn = true,
        )
        // No kanji-compatible reading → fall back to pairing with the kanji so
        // the entry survives the caller's headwords.isEmpty() check.
        assertEquals(listOf("〆" to "しめ"), hw.map { it.written to it.reading })
        assertTrue(hw.isNotEmpty())
    }

    @Test fun `multiple kanji forms keep the positional pairing`() {
        val hw = buildHeadwords(
            listOf(k("決まる"), k("極まる")),
            listOf(r("きまる"), r("きわまる")),
            hasNoKanjiColumn = true,
        )
        assertEquals(
            listOf("決まる" to "きまる", "極まる" to "きわまる"),
            hw.map { it.written to it.reading },
        )
    }

    @Test fun `single kanji without the no_kanji column stays positional`() {
        // Older pack lacking the column → exactly the prior behaviour (extras dropped).
        val hw = buildHeadwords(
            listOf(k("明日")),
            listOf(r("あした"), r("あす"), r("みょうにち")),
            hasNoKanjiColumn = false,
        )
        assertEquals(listOf("あした"), hw.map { it.reading })
    }

    @Test fun `kana-only entry maps readings to writtenless headwords`() {
        val hw = buildHeadwords(
            emptyList(),
            listOf(r("ありがとう")),
            hasNoKanjiColumn = true,
        )
        assertEquals(1, hw.size)
        assertNull(hw.first().written)
        assertEquals("ありがとう", hw.first().reading)
    }

    @Test fun `single kanji with a single reading yields one headword`() {
        val hw = buildHeadwords(
            listOf(k("食べる")),
            listOf(r("たべる")),
            hasNoKanjiColumn = true,
        )
        assertEquals(listOf("食べる" to "たべる"), hw.map { it.written to it.reading })
    }

    @Test fun `carries rankScore onto headwords without reordering them`() {
        // Even though あす ranks highest, headwords stay in INPUT (position) order
        // — firstOrNull()/primary is unchanged; only the detail rows reorder.
        val hw = buildHeadwords(
            listOf(k("明日")),
            listOf(r("あした", rankScore = 10), r("あす", rankScore = 50), r("みょうにち", rankScore = 5)),
            hasNoKanjiColumn = true,
        )
        assertEquals(listOf("あした", "あす", "みょうにち"), hw.map { it.reading })
        assertEquals(listOf(10, 50, 5), hw.map { it.rankScore })
    }
}
