package com.playtranslate.yomitan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendedYomitanDictionariesTest {

    private fun dict(title: String) = YomitanDictionary(
        id = title,
        title = title,
        revision = null,
        description = null,
        author = null,
        format = 3,
        categories = listOf(YomitanCategory.TERMS),
        sizeBytes = 0,
        importedAtMs = 0,
    )

    /** Display titles offered with [installed] imported, to a user translating
     *  [source] into [target] (Japanese into English unless stated). */
    private fun offered(
        vararg installed: String,
        source: String = "ja",
        target: String = "en",
    ): List<String> = RecommendedYomitanDictionaries.offered(
        YomitanRegistry(dictionaries = installed.map { dict(it) }),
        sourceLanguage = source,
        targetLanguage = target,
    ).map { it.displayTitle }

    private fun rec(displayTitle: String) =
        RecommendedYomitanDictionaries.all.first { it.displayTitle == displayTitle }

    private val jitendex = rec("Jitendex.org")
    private val jmnedict = rec("JMnedict")
    private val jiten = rec("Jiten")

    @Test
    fun `jitendex matches its dated and bare titles`() {
        // Jitendex bakes its release date into the title.
        assertTrue(jitendex.matchesInstalled("Jitendex.org [2026-10-03]"))
        assertTrue(jitendex.matchesInstalled("Jitendex.org"))
    }

    @Test
    fun `jitendex does not match unrelated dictionaries`() {
        assertFalse(jitendex.matchesInstalled("Jiten"))
        assertFalse(jitendex.matchesInstalled("JMnedict [2026-10-14]"))
        assertFalse(jitendex.matchesInstalled("JMdict [2026-10-14]"))
    }

    @Test
    fun `jmnedict matches its dated and bare titles`() {
        // yomidevs ships the title with a build-date suffix that changes daily.
        assertTrue(jmnedict.matchesInstalled("JMnedict [2026-06-14]"))
        assertTrue(jmnedict.matchesInstalled("JMnedict"))
    }

    @Test
    fun `jmnedict does not match unrelated dictionaries`() {
        assertFalse(jmnedict.matchesInstalled("Jitendex.org [2026-10-03]"))
        assertFalse(jmnedict.matchesInstalled("Jiten"))
        assertFalse(jmnedict.matchesInstalled("JMdict"))
    }

    @Test
    fun `jiten matches exactly and not Jitendex`() {
        assertTrue(jiten.matchesInstalled("Jiten"))
        assertFalse(jiten.matchesInstalled("Jitendex.org [2026-10-03]"))
        assertFalse(jiten.matchesInstalled("JMnedict [2026-06-14]"))
    }

    @Test
    fun `an English target is offered all three with Jitendex first`() {
        assertEquals(listOf("Jitendex.org", "JMnedict", "Jiten"), offered())
    }

    @Test
    fun `any other target is offered everything but Jitendex`() {
        // Jitendex's definitions are English and show untranslated above the
        // target-language ones; JMnedict and Jiten name no target.
        assertEquals(listOf("JMnedict", "Jiten"), offered(target = "es"))
        assertEquals(listOf("JMnedict", "Jiten"), offered(target = "zh"))
    }

    @Test
    fun `a non-Japanese source is offered nothing`() {
        assertEquals(emptyList<String>(), offered(source = "zh-Hant"))
        assertEquals(emptyList<String>(), offered(source = "en"))
    }

    @Test
    fun `installed dictionaries drop out`() {
        assertEquals(listOf("JMnedict", "Jiten"), offered("Jitendex.org [2026-10-03]"))
        assertEquals(listOf("Jitendex.org", "Jiten"), offered("JMnedict [2026-06-14]"))
        // A near-miss title hides nothing.
        assertEquals(listOf("Jitendex.org", "JMnedict", "Jiten"), offered("JMdict [2026-10-14]"))
        assertEquals(
            emptyList<String>(),
            offered("Jitendex.org [2026-10-03]", "JMnedict [2026-06-14]", "Jiten"),
        )
    }
}
