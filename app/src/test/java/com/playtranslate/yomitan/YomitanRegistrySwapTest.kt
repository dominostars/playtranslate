package com.playtranslate.yomitan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [YomitanDictionaryStore.buildRegistryAfterInstall] — the
 * sectionOrder bookkeeping that's the crux of the id-changing auto-update swap.
 * Two paths:
 *  - fresh import (replacing == null): append the new id at the END of each of
 *    its sections, deduping a stale occurrence (today's manual-import behavior;
 *    pinned so the refactor can't silently regress it);
 *  - update (replacing != null): swap old id → new id IN PLACE (priority slot
 *    preserved), drop from no-longer-matching sections, append to newly-gained
 *    ones, never duplicate.
 * Pure JVM.
 */
class YomitanRegistrySwapTest {

    private fun dict(id: String, cats: List<YomitanCategory>) = YomitanDictionary(
        id = id,
        title = "t-$id",
        format = 3,
        categories = cats,
        sizeBytes = 0,
        importedAtMs = 0,
    )

    private val TERMS = YomitanCategory.TERMS.name
    private val KANJI = YomitanCategory.KANJI.name

    @Test fun `fresh import appends the new id at the end of each of its sections`() {
        val registry = YomitanRegistry(
            dictionaries = listOf(dict("aaa", listOf(YomitanCategory.TERMS))),
            sectionOrder = mapOf(TERMS to listOf("aaa")),
        )
        val fresh = dict("bbb", listOf(YomitanCategory.TERMS, YomitanCategory.KANJI))
        val out = YomitanDictionaryStore.buildRegistryAfterInstall(registry, fresh, replacing = null)
        assertEquals(listOf("aaa", "bbb"), out.sectionOrder[TERMS])
        assertEquals(listOf("bbb"), out.sectionOrder[KANJI])
        assertEquals(listOf("aaa", "bbb"), out.dictionaries.map { it.id })
    }

    @Test fun `fresh import dedupes a stale occurrence of the new id (matches original import)`() {
        val registry = YomitanRegistry(sectionOrder = mapOf(TERMS to listOf("bbb", "aaa")))
        val fresh = dict("bbb", listOf(YomitanCategory.TERMS))
        val out = YomitanDictionaryStore.buildRegistryAfterInstall(registry, fresh, replacing = null)
        assertEquals(listOf("aaa", "bbb"), out.sectionOrder[TERMS])
    }

    @Test fun `update keeps the new id in the old id's priority slot`() {
        val old = dict("old", listOf(YomitanCategory.TERMS))
        val registry = YomitanRegistry(
            dictionaries = listOf(
                dict("x", listOf(YomitanCategory.TERMS)), old, dict("y", listOf(YomitanCategory.TERMS)),
            ),
            sectionOrder = mapOf(TERMS to listOf("x", "old", "y")),
        )
        val out = YomitanDictionaryStore.buildRegistryAfterInstall(
            registry, dict("new", listOf(YomitanCategory.TERMS)), replacing = old,
        )
        assertEquals(listOf("x", "new", "y"), out.sectionOrder[TERMS])
        assertEquals(listOf("x", "new", "y"), out.dictionaries.map { it.id })
    }

    @Test fun `update drops the old id from a section the new version no longer matches`() {
        val old = dict("old", listOf(YomitanCategory.TERMS, YomitanCategory.KANJI))
        val registry = YomitanRegistry(
            dictionaries = listOf(old),
            sectionOrder = mapOf(TERMS to listOf("old"), KANJI to listOf("old")),
        )
        val out = YomitanDictionaryStore.buildRegistryAfterInstall(
            registry, dict("new", listOf(YomitanCategory.TERMS)), replacing = old,
        )
        assertEquals(listOf("new"), out.sectionOrder[TERMS])
        assertEquals(emptyList<String>(), out.sectionOrder[KANJI])
    }

    @Test fun `update appends the new id to a newly-gained section`() {
        val old = dict("old", listOf(YomitanCategory.TERMS))
        val registry = YomitanRegistry(
            dictionaries = listOf(dict("x", listOf(YomitanCategory.KANJI)), old),
            sectionOrder = mapOf(TERMS to listOf("old"), KANJI to listOf("x")),
        )
        val out = YomitanDictionaryStore.buildRegistryAfterInstall(
            registry,
            dict("new", listOf(YomitanCategory.TERMS, YomitanCategory.KANJI)),
            replacing = old,
        )
        assertEquals(listOf("new"), out.sectionOrder[TERMS])
        assertEquals(listOf("x", "new"), out.sectionOrder[KANJI])
    }

    @Test fun `update never duplicates the new id when it is already stale-present`() {
        val old = dict("old", listOf(YomitanCategory.TERMS))
        val registry = YomitanRegistry(
            dictionaries = listOf(old),
            sectionOrder = mapOf(TERMS to listOf("new", "old")),
        )
        val out = YomitanDictionaryStore.buildRegistryAfterInstall(
            registry, dict("new", listOf(YomitanCategory.TERMS)), replacing = old,
        )
        assertEquals(1, out.sectionOrder[TERMS]!!.count { it == "new" })
    }

    @Test fun `update preserves termsSingleDictionary`() {
        val old = dict("old", listOf(YomitanCategory.TERMS))
        val registry = YomitanRegistry(
            dictionaries = listOf(old),
            sectionOrder = mapOf(TERMS to listOf("old")),
            termsSingleDictionary = true,
        )
        val out = YomitanDictionaryStore.buildRegistryAfterInstall(
            registry, dict("new", listOf(YomitanCategory.TERMS)), replacing = old,
        )
        assertTrue(out.termsSingleDictionary)
    }
}
