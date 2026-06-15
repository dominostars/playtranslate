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

    private fun registry(vararg titles: String) =
        YomitanRegistry(dictionaries = titles.map { dict(it) })

    private val jmnedict = RecommendedYomitanDictionaries.all.first { it.displayTitle == "JMnedict" }
    private val jiten = RecommendedYomitanDictionaries.all.first { it.displayTitle == "Jiten" }

    @Test
    fun `jmnedict matches its dated and bare titles`() {
        // yomidevs ships the title with a build-date suffix that changes daily.
        assertTrue(jmnedict.matchesInstalled("JMnedict [2026-06-14]"))
        assertTrue(jmnedict.matchesInstalled("JMnedict"))
    }

    @Test
    fun `jmnedict does not match unrelated dictionaries`() {
        assertFalse(jmnedict.matchesInstalled("Jitendex"))
        assertFalse(jmnedict.matchesInstalled("Jiten"))
        assertFalse(jmnedict.matchesInstalled("JMdict"))
    }

    @Test
    fun `jiten matches exactly and not the popular Jitendex`() {
        assertTrue(jiten.matchesInstalled("Jiten"))
        // A prefix match would wrongly swallow Jitendex — the guard that forces exact match.
        assertFalse(jiten.matchesInstalled("Jitendex"))
        assertFalse(jiten.matchesInstalled("JMnedict [2026-06-14]"))
    }

    @Test
    fun `notInstalled hides only installed recommendations`() {
        // Nothing installed → both recommended, in declared order.
        assertEquals(
            listOf("JMnedict", "Jiten"),
            RecommendedYomitanDictionaries.notInstalled(registry()).map { it.displayTitle },
        )
        // Dated JMnedict installed → only Jiten remains.
        assertEquals(
            listOf("Jiten"),
            RecommendedYomitanDictionaries.notInstalled(registry("JMnedict [2026-06-14]"))
                .map { it.displayTitle },
        )
        // Jitendex installed (a near-miss) → both still recommended.
        assertEquals(
            listOf("JMnedict", "Jiten"),
            RecommendedYomitanDictionaries.notInstalled(registry("Jitendex")).map { it.displayTitle },
        )
        // Both installed → none.
        assertTrue(
            RecommendedYomitanDictionaries
                .notInstalled(registry("JMnedict [2026-06-14]", "Jiten"))
                .isEmpty(),
        )
    }
}
