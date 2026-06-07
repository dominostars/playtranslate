package com.playtranslate.language

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [PinyinDisambiguator.choose]. Pure JVM — no HanLP. */
class PinyinDisambiguatorTest {

    @Test fun `single candidate is returned as-is`() {
        assertEquals(
            "yín háng",
            PinyinDisambiguator.choose(listOf("yín háng"), listOf("yín", "háng")),
        )
    }

    @Test fun `empty candidates yields empty string`() {
        assertEquals("", PinyinDisambiguator.choose(emptyList(), listOf("dōng")))
    }

    @Test fun `picks the tone-matching candidate over frequency-first`() {
        // 东西: freq-first dōng xī (east-west) vs dōng xi (thing). Context = thing.
        val candidates = listOf("dōng xī", "dōng xi")
        assertEquals("dōng xi", PinyinDisambiguator.choose(candidates, listOf("dōng", "xi")))
    }

    @Test fun `keeps frequency-first when context matches it`() {
        val candidates = listOf("dōng xī", "dōng xi")
        assertEquals("dōng xī", PinyinDisambiguator.choose(candidates, listOf("dōng", "xī")))
    }

    @Test fun `disambiguates a standalone heteronym character`() {
        // 还: freq-first hái (also/still) vs huán (to return). Context = return.
        val candidates = listOf("hái", "huán")
        assertEquals("huán", PinyinDisambiguator.choose(candidates, listOf("huán")))
    }

    @Test fun `falls back to frequency-first when nothing matches`() {
        val candidates = listOf("cháng", "zhǎng")
        assertEquals("cháng", PinyinDisambiguator.choose(candidates, listOf("xìng")))
    }

    @Test fun `ignores candidates whose syllable count cannot align`() {
        // The contextual reading has two syllables; a 1- or 3-syllable
        // candidate can't be scored per-position and must not win over the
        // freq-first 2-syllable one when the latter actually matches.
        val candidates = listOf("dōng xī", "dōng xi ér")
        assertEquals("dōng xī", PinyinDisambiguator.choose(candidates, listOf("dōng", "xī")))
    }

    @Test fun `empty context returns frequency-first`() {
        assertEquals("dōng xī", PinyinDisambiguator.choose(listOf("dōng xī", "dōng xi"), emptyList()))
    }

    // ── resolveOverrides: conflict / duplicate guard ─────────────────────

    private fun occ(surface: String, candidates: List<String>, context: List<String>?) =
        HeteronymOccurrence(surface, candidates, context)

    @Test fun `single ambiguous occurrence is corrected`() {
        val overrides = PinyinDisambiguator.resolveOverrides(listOf(
            occ("还", listOf("hái", "huán"), listOf("huán")),
        ))
        assertEquals(mapOf("还" to "huán"), overrides)
    }

    @Test fun `agreeing repeated occurrences are corrected`() {
        // 东西 twice, both "thing" → both dōng xi → safe to override.
        val overrides = PinyinDisambiguator.resolveOverrides(listOf(
            occ("东西", listOf("dōng xī", "dōng xi"), listOf("dōng", "xi")),
            occ("东西", listOf("dōng xī", "dōng xi"), listOf("dōng", "xi")),
        ))
        assertEquals(mapOf("东西" to "dōng xi"), overrides)
    }

    @Test fun `conflicting repeated occurrences suppress the override entirely`() {
        // 还 huán then 还 hái: overriding either would make one occurrence
        // worse than the frequency default, so suppress — both stay default.
        val overrides = PinyinDisambiguator.resolveOverrides(listOf(
            occ("还", listOf("hái", "huán"), listOf("huán")),
            occ("还", listOf("hái", "huán"), listOf("hái")),
        ))
        assertEquals(emptyMap<String, String>(), overrides)
    }

    @Test fun `an unresolvable later occurrence suppresses the surface`() {
        // Can't confirm the second occurrence agrees → fall back to default.
        val overrides = PinyinDisambiguator.resolveOverrides(listOf(
            occ("还", listOf("hái", "huán"), listOf("huán")),
            occ("还", listOf("hái", "huán"), null),
        ))
        assertEquals(emptyMap<String, String>(), overrides)
    }

    @Test fun `no override when context agrees with the frequency default`() {
        val overrides = PinyinDisambiguator.resolveOverrides(listOf(
            occ("东西", listOf("dōng xī", "dōng xi"), listOf("dōng", "xī")),
        ))
        assertEquals(emptyMap<String, String>(), overrides)
    }

    @Test fun `unambiguous surfaces are never overridden`() {
        val overrides = PinyinDisambiguator.resolveOverrides(listOf(
            occ("银行", listOf("yín háng"), listOf("yín", "háng")),
        ))
        assertEquals(emptyMap<String, String>(), overrides)
    }
}
