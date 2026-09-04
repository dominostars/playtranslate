package com.playtranslate.language

import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.DictionaryResponse
import com.playtranslate.model.Headword
import com.playtranslate.model.ImportedSense
import com.playtranslate.model.ImportedSenseGroup
import com.playtranslate.model.Sense
import com.playtranslate.model.kanaOnlyFrom
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

    @Test
    fun `single-dictionary suppression keeps the pack entry's kana-only verdict`() {
        // それとも on the Thor: the lens pill stayed 其れとも because the
        // kana-only check used to be derived from the senses this strip
        // empties. The verdict is carried on the entry and must survive.
        val ukSense = Sense(
            targetDefinitions = listOf("or"), partsOfSpeech = emptyList(), tags = emptyList(),
            restrictions = emptyList(), info = emptyList(), misc = listOf("Kana only"),
        )
        val soretomo = DictionaryEntry(
            slug = "其れとも", isCommon = null, tags = emptyList(), jlpt = emptyList(),
            headwords = listOf(hw("其れとも", "それとも")),
            senses = listOf(ukSense),
            isKanaOnly = kanaOnlyFrom(listOf(ukSense)),
        )
        val merged = YomitanEnrichment.mergeImportedTerms(
            DictionaryResponse(listOf(soretomo)), "それとも",
            lookup(groups = listOf(group("dictA")), suppressesPackSenses = true), null,
        )!!
        assertTrue(merged.entries.first().senses.isEmpty())
        assertTrue(merged.entries.first().isKanaOnly)
    }

    // ── packWrittenForms: the kana-keyed retry's candidates ─────────────

    @Test
    fun `packWrittenForms offers the kanji spelling for a kana-keyed lookup`() {
        // いつも: Jitendex stores it under 何時も only; the pack resolved
        // 何時も/いつも, so the retry runs on that spelling, narrowed to いつも.
        val pack = DictionaryResponse(listOf(packEntry(hw("何時も", "いつも"))))
        assertEquals(
            listOf("何時も" to setOf("いつも")),
            YomitanEnrichment.packWrittenForms(pack, "いつも", "いつも"),
        )
    }

    @Test
    fun `packWrittenForms is empty when the word already is a pack spelling`() {
        // The common kanji-keyed lookup must add no query.
        val pack = DictionaryResponse(listOf(packEntry(hw("何時も", "いつも"))))
        assertTrue(YomitanEnrichment.packWrittenForms(pack, "何時も", "いつも").isEmpty())
        assertTrue(YomitanEnrichment.packWrittenForms(null, "いつも", "いつも").isEmpty())
    }

    @Test
    fun `packWrittenForms offers nothing for a spelling whose entry has other spellings`() {
        // Codex find: 端 looked up with no reading hint misses the imported
        // dictionaries; the entry also lists 辺/ほとり, positionally paired.
        // Offering 辺 would attach ほとり's definitions to the 端 anchor.
        val pack = DictionaryResponse(listOf(packEntry(hw("端", "はし"), hw("辺", "ほとり"))))
        assertTrue(YomitanEnrichment.packWrittenForms(pack, "端", null).isEmpty())
    }

    @Test
    fun `packWrittenForms with no hint narrows to the reading the kana word names`() {
        // ほとり names its own reading, which belongs to the SECOND spelling;
        // the primary-spelling fallback would wrongly offer 端.
        val pack = DictionaryResponse(listOf(packEntry(hw("端", "はし"), hw("辺", "ほとり"))))
        assertEquals(listOf("辺" to setOf("ほとり")), YomitanEnrichment.packWrittenForms(pack, "ほとり", null))
    }

    @Test
    fun `packWrittenForms narrows to the headwords carrying the reading`() {
        // Positional pairing: 端/はし and 辺/ほとり share an entry. A lookup by
        // はし must retry with 端 only, never 辺.
        val pack = DictionaryResponse(listOf(packEntry(hw("端", "はし"), hw("辺", "ほとり"))))
        assertEquals(listOf("端" to setOf("はし")), YomitanEnrichment.packWrittenForms(pack, "はし", "はし"))
    }

    @Test
    fun `packWrittenForms with an unmatched hint uses the reading the word names`() {
        // Drag path shape: kana lemma こだわる with the surface reading こだわっ,
        // which names no headword. The word itself does, and both spellings
        // carry it, so both are offered in headword order.
        val pack = DictionaryResponse(listOf(packEntry(hw("拘る", "こだわる"), hw("拘わる", "こだわる"))))
        assertEquals(
            listOf("拘る" to setOf("こだわる"), "拘わる" to setOf("こだわる")),
            YomitanEnrichment.packWrittenForms(pack, "こだわる", "こだわっ"),
        )
    }

    @Test
    fun `packWrittenForms falls back to the primary spelling only when nothing names a reading`() {
        // Neither the hint nor the word is a reading of the entry: offer the
        // entry's anchor spelling, never the whole positional set.
        val pack = DictionaryResponse(listOf(packEntry(hw("端", "はし"), hw("辺", "ほとり"))))
        assertEquals(listOf("端" to setOf("はし")), YomitanEnrichment.packWrittenForms(pack, "はしっ", "はしっ"))
    }

    @Test
    fun `packWrittenForms uses the first pack entry only`() {
        // ここ ranks 此処 above 個々; 個々's spelling would pull another
        // word's definitions onto the 此処 anchor.
        val pack = DictionaryResponse(listOf(packEntry(hw("此処", "ここ")), packEntry(hw("個々", "ここ"))))
        assertEquals(listOf("此処" to setOf("ここ")), YomitanEnrichment.packWrittenForms(pack, "ここ", "ここ"))
    }

    @Test
    fun `packWrittenForms narrows a multi-reading spelling to the reading the word names`() {
        // 明日 after single-kanji expansion: one headword per reading. A kana
        // key あした names one of them, so the retry is narrowed to it —
        // the imported 明日【あす】 rows are another occurrence's.
        val pack = DictionaryResponse(listOf(packEntry(hw("明日", "あした"), hw("明日", "あす"))))
        assertEquals(listOf("明日" to setOf("あした")), YomitanEnrichment.packWrittenForms(pack, "あした", null))
    }

    @Test
    fun `packWrittenForms merges the primary spelling's readings when nothing names one`() {
        // Same entry, a key that is no reading of it: the primary spelling is
        // offered once, carrying every reading the pack pairs with it.
        val pack = DictionaryResponse(listOf(packEntry(hw("明日", "あした"), hw("明日", "あす"))))
        assertEquals(
            listOf("明日" to setOf("あした", "あす")),
            YomitanEnrichment.packWrittenForms(pack, "あしたは", null),
        )
    }

    @Test
    fun `packWrittenForms skips search-only spellings`() {
        // 其れから is a search-only form: yomitan-import stores it only as a
        // redirect stub, which must not be fetched as a definition group.
        val pack = DictionaryResponse(listOf(packEntry(
            Headword("其れから", "それから", isSearchOnly = true), hw("其から", "それから"),
        )))
        assertEquals(listOf("其から" to setOf("それから")), YomitanEnrichment.packWrittenForms(pack, "それから", "それから"))
    }

    @Test
    fun `packWrittenForms anchors the no-reading fallback on the displayable spelling`() {
        // First-listed form is search-only and neither the hint nor the word
        // names a reading: the fallback must still offer the everyday
        // spelling, not anchor on the stub and then skip it.
        val pack = DictionaryResponse(listOf(packEntry(
            Headword("其れから", "それから", isSearchOnly = true), hw("其から", "それから"),
        )))
        assertEquals(
            listOf("其から" to setOf("それから")),
            YomitanEnrichment.packWrittenForms(pack, "それからね", "それからね"),
        )
    }
}
