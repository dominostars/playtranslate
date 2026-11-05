package com.playtranslate.language

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [PinyinDisambiguator]. Pure JVM — no HanLP. */
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

    @Test fun `picks the tone-matching candidate over the default`() {
        // 东西: default dōng xī (east-west) vs dōng xi (thing). Context = thing.
        val candidates = listOf("dōng xī", "dōng xi")
        assertEquals("dōng xi", PinyinDisambiguator.choose(candidates, listOf("dōng", "xi")))
    }

    @Test fun `keeps the default when context matches it`() {
        val candidates = listOf("dōng xī", "dōng xi")
        assertEquals("dōng xī", PinyinDisambiguator.choose(candidates, listOf("dōng", "xī")))
    }

    @Test fun `disambiguates a standalone heteronym character`() {
        // 还: default hái (also/still) vs huán (to return). Context = return.
        val candidates = listOf("hái", "huán")
        assertEquals("huán", PinyinDisambiguator.choose(candidates, listOf("huán")))
    }

    @Test fun `falls back to the default when nothing matches`() {
        val candidates = listOf("cháng", "zhǎng")
        assertEquals("cháng", PinyinDisambiguator.choose(candidates, listOf("xìng")))
    }

    @Test fun `ignores candidates whose syllable count cannot align`() {
        // The contextual reading has two syllables; a 1- or 3-syllable
        // candidate can't be scored per-position and must not win over the
        // default 2-syllable one when the latter actually matches.
        val candidates = listOf("dōng xī", "dōng xi ér")
        assertEquals("dōng xī", PinyinDisambiguator.choose(candidates, listOf("dōng", "xī")))
    }

    @Test fun `empty context returns the default`() {
        assertEquals("dōng xī", PinyinDisambiguator.choose(listOf("dōng xī", "dōng xi"), emptyList()))
    }

    // ── resolveOverrides: conflict / duplicate guard ─────────────────────

    private fun occ(surface: String, candidates: List<String>, context: List<String>?, standalone: List<String>? = null) =
        HeteronymOccurrence(surface, candidates, context, standalone)

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
        // worse than the default, so suppress — both stay default.
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

    @Test fun `no override when context agrees with the default`() {
        val overrides = PinyinDisambiguator.resolveOverrides(listOf(
            occ("东西", listOf("dōng xī", "dōng xi"), listOf("dōng", "xī")),
        ))
        assertEquals(emptyMap<String, String>(), overrides)
    }

    // ── resolveOverrides: HanLP's standing reading of a word is not context ──

    @Test fun `a reading the sentence did not change keeps the default`() {
        // 东西 leads with dōng xi "thing"; HanLP reads the word dōng xī in
        // every sentence, this one included, which says nothing about it.
        val overrides = PinyinDisambiguator.resolveOverrides(listOf(
            occ("东西", listOf("dōng xi", "dōng xī"), context = listOf("dōng", "xī"), standalone = listOf("dōng", "xī")),
        ))
        assertEquals(emptyMap<String, String>(), overrides)
    }

    @Test fun `a reading the sentence changed is a correction`() {
        // 还 before 钱: HanLP's phrase 还钱 reads it huán where the word alone is hái.
        val overrides = PinyinDisambiguator.resolveOverrides(listOf(
            occ("还", listOf("hái", "huán"), context = listOf("huán"), standalone = listOf("hái")),
        ))
        assertEquals(mapOf("还" to "huán"), overrides)
    }

    @Test fun `with no standalone reading every context counts`() {
        // 於 under the Simplified profile: the default is line order's wū
        // and HanLP's standing yú is the only opinion on it.
        val overrides = PinyinDisambiguator.resolveOverrides(listOf(
            occ("於", listOf("wū", "yú"), context = listOf("yú"), standalone = null),
        ))
        assertEquals(mapOf("於" to "yú"), overrides)
    }

    @Test fun `an occurrence the sentence changed and one it did not disagree`() {
        // 更易读、更灵敏: HanLP's phrase 更易 reads the first gēng; the second
        // is the standing gèng, which is the default. No correction for either.
        val overrides = PinyinDisambiguator.resolveOverrides(listOf(
            occ("更", listOf("gèng", "gēng"), context = listOf("gēng"), standalone = listOf("gèng")),
            occ("更", listOf("gèng", "gēng"), context = listOf("gèng"), standalone = listOf("gèng")),
        ))
        assertEquals(emptyMap<String, String>(), overrides)
    }

    @Test fun `unambiguous surfaces are never overridden`() {
        val overrides = PinyinDisambiguator.resolveOverrides(listOf(
            occ("银行", listOf("yín háng"), listOf("yín", "háng")),
        ))
        assertEquals(emptyMap<String, String>(), overrides)
    }

    @Test fun `disambiguates a ü-bearing heteronym`() {
        // 率: lǜ ('rate') vs shuài ('lead'). With ü now folded consistently by
        // PinyinFormatter, the candidate matches HanLP's clean context syllable.
        assertEquals("lǜ", PinyinDisambiguator.choose(listOf("lǜ", "shuài"), listOf("lǜ")))
    }
}
