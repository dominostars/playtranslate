package com.playtranslate.ui

import com.playtranslate.language.DefinitionResult
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.DictionaryResponse
import com.playtranslate.model.Headword
import com.playtranslate.model.Sense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // ── transportPayloadFor: the launch-payload size gate ────────────────

    private fun payloadOf(vararg words: Pair<String, WordEnrichment>): Pair<
        Map<String, Triple<String, String, Int>>, Map<String, WordEnrichment>> {
        val results = words.associate { (w, e) ->
            w to Triple("", flatMeaningOf(e.senses).ifEmpty { "flat $w" }, 0)
        }
        return results to words.toMap()
    }

    @Test fun `under budget senses cross and meaning slots blank`() {
        val (results, enrichment) = payloadOf(
            "猫" to WordEnrichment(pitch = listOf(1), senses = listOf(sense("cat"))),
            "犬" to WordEnrichment(),  // sense-less: keeps its flat text
        )
        val t = transportPayloadFor(arrayOf("猫", "犬"), results, enrichment)
        assertEquals("", t.meanings[0])
        assertEquals("flat 犬", t.meanings[1])
        assertEquals(listOf(sense("cat")), t.enrichment.getValue("猫").senses)
    }

    @Test fun `over budget strips senses and ships real meanings`() {
        // ~90K chars of definition text × 3 bytes/char blows the 200KB gate.
        val monster = sense("あ".repeat(30_000), imported = true, pos = listOf("Dict"))
        val (results, enrichment) = payloadOf(
            "一" to WordEnrichment(pitch = listOf(0), senses = listOf(monster)),
            "二" to WordEnrichment(senses = listOf(monster)),
            "三" to WordEnrichment(senses = listOf(monster)),
        )
        val t = transportPayloadFor(arrayOf("一", "二", "三"), results, enrichment)
        // Meanings ship non-blank (capped), senses stripped, small fields kept.
        t.meanings.forEach { m ->
            assertTrue("meaning must ship in degraded mode", m.isNotEmpty())
            assertTrue("meaning capped: ${m.length}", m.length <= 8_001)
        }
        t.enrichment.values.forEach { e ->
            assertEquals(emptyList<SenseDisplay>(), e.senses)
        }
        assertEquals(listOf(0), t.enrichment.getValue("一").pitch)
        // Read side passes the degraded meanings straight through.
        assertEquals(t.meanings[0],
            meaningFromTransport(t.meanings[0], t.enrichment.getValue("一")))
    }

    @Test fun `degraded payload stays bounded in aggregate across many words`() {
        // 32 saturated words: a fixed per-word cap alone would ship
        // 32 × 8K = 256K chars (~768KB serialized) — over the binder budget.
        val monster = sense("あ".repeat(10_000))
        val words = (1..32).map { i ->
            "word$i" to WordEnrichment(senses = listOf(monster))
        }.toTypedArray()
        val (results, enrichment) = payloadOf(*words)
        val keys = words.map { it.first }.toTypedArray()
        val t = transportPayloadFor(keys, results, enrichment)
        val total = t.meanings.sumOf { it.length }
        assertTrue("aggregate meanings bounded, got $total chars", total <= 65_000)
        t.meanings.forEach { m ->
            assertTrue("budget/wordCount excerpt per word: ${m.length}", m.length >= 1_000)
        }
    }

    @Test fun `aggregate bound holds unconditionally on absurdly wide captures`() {
        // 200 saturated words — wider than any screen- or document-OCR
        // block. The per-word cap has no floor, so the 64K-char aggregate
        // ceiling holds for ANY word count.
        val monster = sense("あ".repeat(2_000))
        val words = (1..200).map { i ->
            "word$i" to WordEnrichment(senses = listOf(monster))
        }.toTypedArray()
        val (results, enrichment) = payloadOf(*words)
        val keys = words.map { it.first }.toTypedArray()
        val t = transportPayloadFor(keys, results, enrichment)
        val total = t.meanings.sumOf { it.length }
        assertTrue("aggregate bounded for 200 words, got $total chars", total <= 65_000)
        // Excerpts shrink rather than the bound breaking.
        assertTrue(t.meanings.all { it.isNotEmpty() })
    }

    @Test fun `degraded short meanings are not truncated`() {
        val monster = sense("あ".repeat(80_000))
        val short = sense("cat")
        val (results, enrichment) = payloadOf(
            "一" to WordEnrichment(senses = listOf(monster)),
            "猫" to WordEnrichment(senses = listOf(short)),
        )
        val t = transportPayloadFor(arrayOf("一", "猫"), results, enrichment)
        assertEquals("cat", t.meanings[1])
        assertTrue(t.meanings[0].endsWith("…"))
    }
}
