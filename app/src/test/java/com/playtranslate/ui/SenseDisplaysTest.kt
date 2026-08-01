package com.playtranslate.ui

import com.playtranslate.language.DefinitionResult
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.DictionaryResponse
import com.playtranslate.model.Headword
import com.playtranslate.model.Sense
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [flatMeaningOf] — the single derivation of a word's flat meaning
 * from its [SenseDisplay] rows — and the [meaningForTransport]/
 * [meaningFromTransport] pair that lets the enrichment-carrying
 * transports ship definition text once instead of twice.
 */
class SenseDisplaysTest {

    private fun sense(def: String, imported: Boolean = false, pos: List<String> = emptyList()) =
        SenseDisplay(pos = pos, definition = def, misc = emptyList(), imported = imported)

    // ── flatMeaningOf ────────────────────────────────────────────────────

    @Test fun `single sense is one unnumbered line`() {
        assertEquals("to hear", flatMeaningOf(listOf(sense("to hear"))))
    }

    @Test fun `multiple senses number continuously`() {
        assertEquals(
            "1. to hear\n2. to ask",
            flatMeaningOf(listOf(sense("to hear"), sense("to ask"))),
        )
    }

    @Test fun `imported sense re-attaches its source in parens`() {
        // importedHeader packs "source" or "source · tags" into pos[0].
        assertEquals(
            "1. 封をすること (Jitendex)\n2. seal",
            flatMeaningOf(listOf(
                sense("封をすること", imported = true, pos = listOf("Jitendex · n")),
                sense("seal"),
            )),
        )
    }

    @Test fun `imported newlines collapse to spaces`() {
        assertEquals(
            "line one line two (Dict)",
            flatMeaningOf(listOf(
                sense("line one\nline two", imported = true, pos = listOf("Dict")),
            )),
        )
    }

    @Test fun `blank definitions drop from the flat text`() {
        assertEquals(
            "to hear",
            flatMeaningOf(listOf(sense(""), sense("to hear"))),
        )
    }

    @Test fun `empty senses derive an empty string`() {
        assertEquals("", flatMeaningOf(emptyList()))
    }

    // ── MT-without-definitions tier (regression) ─────────────────────────
    // buildSenseDisplays leads this tier with the translated headword as a
    // sense row — the only line in the user's target language — and the
    // lens, the lookup popup, and the sentence card's word cells all render
    // it as numbered row 1. The flat derivation deliberately matches
    // (before the flatMeaningOf consolidation, the cache's flat string
    // silently DROPPED the headword translation).

    private fun mtNoDefsResult(): Pair<DefinitionResult, List<DictionaryEntry>> {
        val entry = DictionaryEntry(
            slug = "猫",
            isCommon = null,
            tags = emptyList(),
            jlpt = emptyList(),
            headwords = listOf(Headword("猫", "ねこ")),
            senses = listOf(
                Sense(
                    targetDefinitions = listOf("cat"),
                    partsOfSpeech = listOf("noun"),
                    tags = emptyList(), restrictions = emptyList(), info = emptyList(),
                ),
                Sense(
                    targetDefinitions = listOf("shamisen"),
                    partsOfSpeech = listOf("noun"),
                    tags = emptyList(), restrictions = emptyList(), info = emptyList(),
                ),
            ),
            freqScore = 0,
        )
        val response = DictionaryResponse(listOf(entry))
        return DefinitionResult.MachineTranslated(
            response = response,
            translatedHeadword = "gato",
            translatedDefinitions = null,
        ) to listOf(entry)
    }

    @Test fun `MT-without-defs tier leads with the translated headword row`() {
        val (defResult, entries) = mtNoDefsResult()
        val senses = buildSenseDisplays(defResult, entries, targetLang = "es")
        assertEquals(3, senses.size)
        assertEquals("gato", senses[0].definition)
        assertEquals(emptyList<String>(), senses[0].pos)
        assertEquals("cat", senses[1].definition)
        assertEquals("shamisen", senses[2].definition)
    }

    @Test fun `MT-without-defs flat meaning numbers the headword like the rendered rows`() {
        val (defResult, entries) = mtNoDefsResult()
        val senses = buildSenseDisplays(defResult, entries, targetLang = "es")
        assertEquals("1. gato\n2. cat\n3. shamisen", flatMeaningOf(senses))
    }

    // ── transport round-trip ─────────────────────────────────────────────

    @Test fun `sense-bearing word crosses blank and re-derives`() {
        val enrichment = WordEnrichment(
            senses = listOf(sense("to hear"), sense("to ask")),
        )
        val marshaled = meaningForTransport("1. to hear\n2. to ask", enrichment)
        assertEquals("", marshaled)
        assertEquals("1. to hear\n2. to ask", meaningFromTransport(marshaled, enrichment))
    }

    @Test fun `sense-less word keeps its flat text through transport`() {
        val enrichment = WordEnrichment()
        val marshaled = meaningForTransport("manual definition", enrichment)
        assertEquals("manual definition", marshaled)
        assertEquals("manual definition", meaningFromTransport(marshaled, enrichment))
    }

    @Test fun `missing enrichment passes meaning through unchanged`() {
        assertEquals("text", meaningForTransport("text", null))
        assertEquals("text", meaningFromTransport("text", null))
        assertEquals("", meaningFromTransport("", null))
    }
}
