package com.playtranslate.yomitan

import org.junit.Assert.assertEquals
import org.junit.Test

class KanjiDataTest {

    @Test
    fun `empty reading string yields empty list`() {
        assertEquals(emptyList<String>(), KanjiData.splitReadings(""))
        assertEquals(emptyList<String>(), KanjiData.splitReadings("  "))
    }

    @Test
    fun `space-separated readings split cleanly`() {
        assertEquals(listOf("おし.える", "おそ.わる"), KanjiData.splitReadings("おし.える おそ.わる"))
        assertEquals(listOf("キョウ"), KanjiData.splitReadings("キョウ"))
        // Ideographic space tolerated.
        assertEquals(listOf("あめ", "あま-"), KanjiData.splitReadings("あめ　あま-"))
    }

    @Test
    fun `meanings round-trip through awkward characters`() {
        val meanings = listOf("teach, instruct", "say \"hello\"", "a · b", "")
        assertEquals(meanings, KanjiData.decodeMeanings(KanjiData.encodeMeanings(meanings)))
    }

    @Test
    fun `corrupt encoded meanings decode to empty`() {
        assertEquals(emptyList<String>(), KanjiData.decodeMeanings("not json"))
        assertEquals(emptyList<String>(), KanjiData.decodeMeanings(""))
    }
}
