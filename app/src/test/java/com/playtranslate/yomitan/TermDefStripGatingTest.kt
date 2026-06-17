package com.playtranslate.yomitan

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [YomitanDataStore.resolveTermDefs] — the per-entry definition
 * gate that ties the JA-only headword-echo strip to source language (schema 7).
 * Japanese-source dicts strip monolingual headword echoes; every other language
 * preserves the glossary text (the 【】 heuristic mis-fires on non-JA scripts)
 * and drops only blanks. Pure JVM — [com.playtranslate.yomitan.TermGlossary.stripHeadwordEcho]
 * is pure. Echo fixtures match TermGlossaryTest's verified cases.
 */
class TermDefStripGatingTest {

    @Test
    fun `japanese source strips a headword echo`() {
        assertEquals(
            listOf("空から降る水滴。"),
            YomitanDataStore.resolveTermDefs(
                listOf("あめ【雨】\n空から降る水滴。"), "雨", "あめ", applyHeadwordEchoStrip = true,
            ),
        )
    }

    @Test
    fun `non-japanese source preserves the would-be-stripped text`() {
        val defs = listOf("あめ【雨】\n空から降る水滴。")
        assertEquals(
            defs,
            YomitanDataStore.resolveTermDefs(defs, "雨", "あめ", applyHeadwordEchoStrip = false),
        )
    }

    @Test
    fun `japanese source drops an echo-only definition`() {
        assertEquals(
            emptyList<String>(),
            YomitanDataStore.resolveTermDefs(
                listOf("ねこ【猫】"), "猫", "ねこ", applyHeadwordEchoStrip = true,
            ),
        )
    }

    @Test
    fun `non-japanese source keeps an echo-only definition`() {
        assertEquals(
            listOf("ねこ【猫】"),
            YomitanDataStore.resolveTermDefs(
                listOf("ねこ【猫】"), "猫", "ねこ", applyHeadwordEchoStrip = false,
            ),
        )
    }

    @Test
    fun `non-japanese source still drops blank definitions`() {
        assertEquals(
            listOf("a", "b"),
            YomitanDataStore.resolveTermDefs(
                listOf("a", "  ", "b"), "t", "r", applyHeadwordEchoStrip = false,
            ),
        )
    }

    @Test
    fun `japanese source leaves a plain definition untouched`() {
        assertEquals(
            listOf("cat; feline"),
            YomitanDataStore.resolveTermDefs(
                listOf("cat; feline"), "猫", "ねこ", applyHeadwordEchoStrip = true,
            ),
        )
    }
}
