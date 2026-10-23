package com.playtranslate.language

import com.playtranslate.language.YomitanEnrichment.Companion.ImportedQuery
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [YomitanEnrichment.importedLookupTiers]: the order the imported-term
 * lookup queries the store in, and what the retry after a narrowed miss
 * narrows to: the pack's readings on a pack hit, nothing on a pack miss.
 * Pure JVM.
 */
class ImportedLookupTiersTest {

    private fun tiers(
        word: String = "拘る",
        reading: String?,
        packReadings: Set<String> = emptySet(),
        packForms: List<Pair<String, Set<String>>> = emptyList(),
        fallbackForms: List<String> = emptyList(),
    ) = YomitanEnrichment.importedLookupTiers(word, reading, packReadings, packForms, fallbackForms)

    @Test
    fun `an unnarrowed lookup runs once and never retries the word`() {
        // The first query already saw every row of the term.
        assertEquals(
            listOf(ImportedQuery("拘る", null), ImportedQuery("拘わる", null)),
            tiers(reading = null, fallbackForms = listOf("拘わる")),
        )
    }

    @Test
    fun `a pack hit retries with the pack's readings, never unnarrowed`() {
        // 拘る dragged as こだわっ (the tokenizer's surface reading) while the
        // pack resolved こだわる and かかわる: the retry stays inside the
        // identity the pack landed on, so a homograph's imported senses
        // cannot attach to this entry.
        assertEquals(
            listOf(
                ImportedQuery("拘る", setOf("こだわっ")),
                ImportedQuery("拘る", setOf("こだわる", "かかわる")),
            ),
            tiers(reading = "こだわっ", packReadings = setOf("こだわる", "かかわる")),
        )
    }

    @Test
    fun `a pack hit whose only reading is the caller's does not repeat the query`() {
        assertEquals(
            listOf(ImportedQuery("拘る", setOf("こだわる"))),
            tiers(reading = "こだわる", packReadings = setOf("こだわる")),
        )
    }

    @Test
    fun `a pack miss retries the word resolved to the dictionary's first reading`() {
        // 星と深淵を目指せ: the imported-dictionary oracle fused it by text,
        // the pack has no entry, and the dictionary's reading need not be
        // the tokenizer's concatenation. The term's own rows answer, under
        // the one reading the dictionary lists first.
        assertEquals(
            listOf(
                ImportedQuery("星と深淵を目指せ", setOf("ほしとしんえんをめざせ")),
                ImportedQuery("星と深淵を目指せ", null, resolveToFirstReading = true),
            ),
            tiers(word = "星と深淵を目指せ", reading = "ほしとしんえんをめざせ"),
        )
    }

    @Test
    fun `pack spellings and fallback forms follow the retry in order`() {
        assertEquals(
            listOf(
                ImportedQuery("いつも", setOf("いつも")),
                ImportedQuery("何時も", setOf("いつも")),
                ImportedQuery("いつ", null),
            ),
            tiers(
                word = "いつも", reading = "いつも", packReadings = setOf("いつも"),
                packForms = listOf("何時も" to setOf("いつも")), fallbackForms = listOf("いつ"),
            ),
        )
    }
}
