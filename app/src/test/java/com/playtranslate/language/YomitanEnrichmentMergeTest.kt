package com.playtranslate.language

import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.DictionaryResponse
import com.playtranslate.model.Headword
import com.playtranslate.model.ImportedSense
import com.playtranslate.model.ImportedSenseGroup
import com.playtranslate.model.Sense
import com.playtranslate.yomitan.YomitanDataStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [YomitanEnrichment.mergeImportedTerms] — the pure term-merge
 * lifted verbatim out of JapaneseEngine when Yomitan enrichment was generalized
 * to every source language. Guards the three shapes the move must preserve
 * (pack-hit attach, pack-miss synthesize, single-dictionary suppression) AND
 * that wrapping a pack response in the merge never disturbs its entry order —
 * the invariant ChineseEngine.preferReading's heteronym reorder relies on.
 * Pure JVM — no Context, no dict, no HanLP.
 */
class YomitanEnrichmentMergeTest {

    private fun entry(reading: String, senses: List<Sense> = emptyList()) = DictionaryEntry(
        slug = reading,
        isCommon = null,
        tags = emptyList(),
        jlpt = emptyList(),
        headwords = listOf(Headword(written = "x", reading = reading)),
        senses = senses,
    )

    private fun hw(written: String?, reading: String?) = Headword(written, reading)

    /** An entry with explicit headwords — [packReadings] reads the written↔reading
     *  pairing, which [entry]'s single placeholder headword can't express. */
    private fun packEntry(vararg headwords: Headword) = DictionaryEntry(
        slug = "s",
        isCommon = null,
        tags = emptyList(),
        jlpt = emptyList(),
        headwords = headwords.toList(),
        senses = emptyList(),
    )

    private fun group(label: String) =
        ImportedSenseGroup(source = label, senses = listOf(ImportedSense("def of $label")))

    private fun lookup(
        groups: List<ImportedSenseGroup> = emptyList(),
        resolvedReading: String? = null,
        suppressesPackSenses: Boolean = false,
    ) = YomitanDataStore.TermLookup(groups, resolvedReading, suppressesPackSenses)

    @Test
    fun `packReadings collects every matched entry's readings`() {
        // 拘る's shape: the pack's reading filter found nothing and it fell
        // back to an unfiltered match, returning both homographs. Both list
        // 拘る among their written forms, so the imported lookup retries with
        // BOTH — an arbitrary first-entry pick would drop half the word.
        val pack = DictionaryResponse(
            listOf(
                packEntry(hw("拘る", "こだわる"), hw("拘わる", "こだわる")),
                packEntry(hw("関わる", "かかわる"), hw("拘る", "かかわる")),
            ),
        )
        assertEquals(setOf("こだわる", "かかわる"), YomitanEnrichment.packReadings(pack, "拘る"))
    }

    @Test
    fun `packReadings drops readings belonging to another written form`() {
        // buildHeadwords pairs multiple kanji forms POSITIONALLY (the re_restr
        // the pack lacks), so an entry can carry a reading that isn't the
        // looked-up spelling's. Retrying with it would readmit the homograph
        // the reading filter exists to exclude.
        val pack = DictionaryResponse(listOf(packEntry(hw("端", "はし"), hw("辺", "ほとり"))))
        assertEquals(setOf("はし"), YomitanEnrichment.packReadings(pack, "端"))
    }

    @Test
    fun `packReadings keeps kana-only headwords and kana lemmas`() {
        // written = null is a re_nokanji reading: it belongs to the entry as a
        // whole, not to one kanji form.
        val pack = DictionaryResponse(
            listOf(packEntry(hw("猫", "ねこ"), hw(null, "ネコ"))),
        )
        assertEquals(setOf("ねこ", "ネコ"), YomitanEnrichment.packReadings(pack, "猫"))
        // The kana-lemma case: looking a uk word up BY its reading must not
        // filter the entry down to nothing and switch the retry off.
        assertEquals(setOf("ねこ", "ネコ"), YomitanEnrichment.packReadings(pack, "ねこ"))
    }

    @Test
    fun `packReadings falls back to the whole entry when no headword matches`() {
        // The pack matches on its reading table too, so an entry can arrive
        // with nothing to filter a written form against.
        val pack = DictionaryResponse(listOf(packEntry(hw("端", "はし"))))
        assertEquals(setOf("はし"), YomitanEnrichment.packReadings(pack, "unrelated"))
    }

    @Test
    fun `packReadings is empty for a pack miss or reading-less headwords`() {
        // Empty keeps the retry off: nothing to narrow by, and the
        // deinflection fallbacks are the next tier either way.
        assertEquals(emptySet<String>(), YomitanEnrichment.packReadings(null, "くれる"))
        // The synthesized-entry shape (mergeImportedTerms' pack-miss branch):
        // the term in `written`, no reading. Pack-built kana-only headwords
        // are the other way round — see buildHeadwords.
        val synthesized = packEntry(hw("くれる", null))
        assertEquals(
            emptySet<String>(),
            YomitanEnrichment.packReadings(DictionaryResponse(listOf(synthesized)), "くれる"),
        )
    }

    @Test
    fun `pack hit with no imported groups returns the pack response untouched`() {
        // Entries arrive in a deliberate non-frequency order (as if preferReading
        // already floated "B" to the front); the merge must NOT reorder them.
        val pack = DictionaryResponse(listOf(entry("B"), entry("A")))
        val merged = YomitanEnrichment.mergeImportedTerms(pack, "w", lookup(), resolvedTerm = "w")
        assertSame(pack, merged)
        assertEquals(listOf("B", "A"), merged!!.entries.map { it.headwords.first().reading })
    }

    @Test
    fun `imported groups attach to the first entry only`() {
        val pack = DictionaryResponse(listOf(entry("first"), entry("second")))
        val merged = YomitanEnrichment.mergeImportedTerms(
            pack, "w", lookup(groups = listOf(group("dictA"))), resolvedTerm = "w",
        )!!
        assertEquals(listOf("dictA"), merged.entries[0].importedSenses.map { it.source })
        assertTrue(merged.entries[1].importedSenses.isEmpty())
        // Entry order otherwise untouched.
        assertEquals(listOf("first", "second"), merged.entries.map { it.headwords.first().reading })
    }

    @Test
    fun `single-dictionary suppression strips pack senses across all entries`() {
        val sense = Sense(listOf("packdef"), emptyList(), emptyList(), emptyList(), emptyList())
        val pack = DictionaryResponse(
            listOf(entry("first", listOf(sense)), entry("second", listOf(sense)))
        )
        val merged = YomitanEnrichment.mergeImportedTerms(
            pack, "w",
            lookup(groups = listOf(group("dictA")), suppressesPackSenses = true),
            resolvedTerm = "w",
        )!!
        assertTrue(merged.entries.all { it.senses.isEmpty() })
        assertEquals(listOf("dictA"), merged.entries[0].importedSenses.map { it.source })
    }

    @Test
    fun `pack miss with imported groups synthesizes an entry`() {
        val merged = YomitanEnrichment.mergeImportedTerms(
            packResponse = null,
            word = "fallthrough",
            lookup = lookup(groups = listOf(group("dictA")), resolvedReading = "よみ"),
            resolvedTerm = "猫",
        )!!
        assertEquals(1, merged.entries.size)
        val e = merged.entries.first()
        assertEquals("猫", e.slug)
        assertEquals("猫", e.headwords.first().written)
        assertEquals("よみ", e.headwords.first().reading)
        assertTrue(e.senses.isEmpty())
        assertEquals(listOf("dictA"), e.importedSenses.map { it.source })
    }

    @Test
    fun `synthesized entry falls back to the word when no candidate resolved`() {
        val merged = YomitanEnrichment.mergeImportedTerms(
            packResponse = null,
            word = "word",
            lookup = lookup(groups = listOf(group("dictA"))),
            resolvedTerm = null,
        )!!
        assertEquals("word", merged.entries.first().slug)
    }

    @Test
    fun `synthesized reading is nulled when it equals the term`() {
        val merged = YomitanEnrichment.mergeImportedTerms(
            packResponse = null,
            word = "猫",
            lookup = lookup(groups = listOf(group("dictA")), resolvedReading = "猫"),
            resolvedTerm = "猫",
        )!!
        assertNull(merged.entries.first().headwords.first().reading)
    }

    @Test
    fun `pack miss with no imported groups returns null`() {
        assertNull(YomitanEnrichment.mergeImportedTerms(null, "w", lookup(), resolvedTerm = null))
    }
}
