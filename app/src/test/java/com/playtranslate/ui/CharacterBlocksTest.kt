package com.playtranslate.ui

import com.playtranslate.model.HanziDetail
import com.playtranslate.model.HanziEntry
import com.playtranslate.model.KanjiDetail
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [characterBlocks]: a character row as the blocks it draws. A kanji is one
 * block with its on and kun lines, or one neutral line for an imported
 * dictionary that does not tell them apart; a hanzi is one block per
 * dictionary entry, each with the pinyin its meanings are read with. Pure
 * JVM.
 */
class CharacterBlocksTest {

    private fun kanji(on: List<String>, kun: List<String>, combined: List<String> = emptyList()) = KanjiDetail(
        literal = '生', meanings = listOf("life", "birth"), meaningsLang = "en",
        onReadings = on, kunReadings = kun, jlpt = 5, grade = 1, strokeCount = 5, combinedReadings = combined,
    )

    // Mutations: the kun line is dropped; the combined list is labelled ON.
    @Test fun `a kanji is one block with a line per kind of reading it has`() {
        assertEquals(
            listOf(CharacterBlock(listOf("life", "birth"), listOf("on" to "セイ, ショウ", "kun" to "い.きる, う.まれる"))),
            characterBlocks(kanji(listOf("セイ", "ショウ"), listOf("い.きる", "う.まれる"))),
        )
        assertEquals(listOf("kun" to "なま"), characterBlocks(kanji(emptyList(), listOf("なま"))).single().readingLines)
        assertEquals(emptyList<Pair<String, String>>(), characterBlocks(kanji(emptyList(), emptyList())).single().readingLines)
        // A dictionary that does not split on from kun: one neutral line,
        // whatever the on and kun lists hold.
        assertEquals(
            listOf("readings" to "セイ, い.きる"),
            characterBlocks(kanji(listOf("セイ"), listOf("い.きる"), combined = listOf("セイ", "い.きる"))).single().readingLines,
        )
    }

    // Mutations: the entries are one block; a block shows the first
    // entry's pinyin under every entry.
    @Test fun `a hanzi is one block per entry, each with its own pinyin`() {
        val sha = HanziDetail(
            literal = '沙',
            entries = listOf(
                HanziEntry(listOf("shā"), listOf("granule", "sand")),
                HanziEntry(listOf("shà"), listOf("(dialect) to sift; to sieve")),
                HanziEntry(listOf("Shā"), listOf("surname Sha")),
            ),
            isCommon = false, freqScore = 2,
        )
        assertEquals(
            listOf(
                CharacterBlock(listOf("granule", "sand"), listOf("pinyin" to "shā")),
                CharacterBlock(listOf("(dialect) to sift; to sieve"), listOf("pinyin" to "shà")),
                CharacterBlock(listOf("surname Sha"), listOf("pinyin" to "Shā")),
            ),
            characterBlocks(sha),
        )
        // An imported dictionary's meanings, undivided: every reading on
        // one line, and no line for a character the pack does not list.
        val imported = sha.copy(entries = listOf(HanziEntry(listOf("shā", "shà", "Shā"), listOf("Sand"))), meaningsLang = "de")
        assertEquals(listOf(CharacterBlock(listOf("Sand"), listOf("pinyin" to "shā, shà, Shā"))), characterBlocks(imported))
        val unlisted = sha.copy(entries = listOf(HanziEntry(emptyList(), listOf("Sand"))))
        assertEquals(listOf(CharacterBlock(listOf("Sand"), emptyList())), characterBlocks(unlisted))
    }
}
