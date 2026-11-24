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

    // ── The Built-in dictionary row ─────────────────────────────────────

    private val BUILT_IN = YomitanRegistry.BUILT_IN_TERMS_ID

    private fun terms(vararg ids: String) = ids.map { dict(it, listOf(YomitanCategory.TERMS)) }

    @Test fun `the built-in row is last when the stored order does not hold it`() {
        val registry = YomitanRegistry(
            dictionaries = terms("aaa", "bbb"),
            sectionOrder = mapOf(TERMS to listOf("bbb", "aaa")),
        )
        assertEquals(listOf("bbb", "aaa", BUILT_IN), registry.termsRowIds())
        assertEquals(listOf(BUILT_IN), YomitanRegistry().termsRowIds())
    }

    @Test fun `the built-in row sits where the stored order puts it`() {
        fun rows(vararg stored: String) = YomitanRegistry(
            dictionaries = terms("aaa", "bbb"),
            sectionOrder = mapOf(TERMS to stored.toList()),
        ).termsRowIds()
        assertEquals(listOf(BUILT_IN, "aaa", "bbb"), rows(BUILT_IN, "aaa", "bbb"))
        assertEquals(listOf("aaa", BUILT_IN, "bbb"), rows("aaa", BUILT_IN, "bbb"))
        assertEquals(listOf("aaa", "bbb", BUILT_IN), rows("aaa", "bbb", BUILT_IN))
    }

    @Test fun `a dictionary the stored order lacks goes after the built-in row's place, and a stale id counts for nothing`() {
        val registry = YomitanRegistry(
            dictionaries = terms("aaa", "bbb", "ccc"),
            // "gone" names no dictionary; "ccc" was never listed.
            sectionOrder = mapOf(TERMS to listOf("gone", "aaa", BUILT_IN, "bbb")),
        )
        assertEquals(listOf("aaa", BUILT_IN, "bbb", "ccc"), registry.termsRowIds())
    }

    @Test fun `the built-in row is in no dictionary list`() {
        val registry = YomitanRegistry(
            dictionaries = terms("aaa"),
            sectionOrder = mapOf(TERMS to listOf(BUILT_IN, "aaa")),
        )
        assertEquals(listOf("aaa"), registry.orderedFor(YomitanCategory.TERMS).map { it.id })
    }

    @Test fun `a fresh import lands above a built-in row that is the list's last`() {
        val registry = YomitanRegistry(
            dictionaries = terms("aaa"),
            sectionOrder = mapOf(TERMS to listOf("aaa", BUILT_IN)),
        )
        val out = YomitanDictionaryStore.buildRegistryAfterInstall(
            registry, dict("bbb", listOf(YomitanCategory.TERMS, YomitanCategory.KANJI)), replacing = null,
        )
        assertEquals(listOf("aaa", "bbb", BUILT_IN), out.sectionOrder[TERMS])
        assertEquals(listOf("aaa", "bbb", BUILT_IN), out.termsRowIds())
        // Only the TERMS list has the row.
        assertEquals(listOf("bbb"), out.sectionOrder[KANJI])
    }

    @Test fun `a fresh import lands at the bottom when the built-in row was moved up`() {
        val registry = YomitanRegistry(
            dictionaries = terms("aaa"),
            sectionOrder = mapOf(TERMS to listOf(BUILT_IN, "aaa")),
        )
        val out = YomitanDictionaryStore.buildRegistryAfterInstall(
            registry, dict("bbb", listOf(YomitanCategory.TERMS)), replacing = null,
        )
        assertEquals(listOf(BUILT_IN, "aaa", "bbb"), out.termsRowIds())
    }

    @Test fun `the first import after the last dictionary was deleted finds the row as it was left`() {
        // Every term dictionary deleted: the stored order is the row alone,
        // and the switch is still off.
        val registry = YomitanRegistry(
            sectionOrder = mapOf(TERMS to listOf(BUILT_IN)),
            builtInTermsEnabled = false,
        )
        val out = YomitanDictionaryStore.buildRegistryAfterInstall(
            registry, dict("bbb", listOf(YomitanCategory.TERMS)), replacing = null,
        )
        assertEquals(listOf("bbb", BUILT_IN), out.termsRowIds())
        assertEquals(false, out.builtInTermsEnabled)
    }

    @Test fun `an update keeps the built-in row's place and switch`() {
        val old = dict("old", listOf(YomitanCategory.TERMS))
        val registry = YomitanRegistry(
            dictionaries = listOf(dict("x", listOf(YomitanCategory.TERMS)), old),
            sectionOrder = mapOf(TERMS to listOf("x", BUILT_IN, "old")),
            builtInTermsEnabled = false,
        )
        val out = YomitanDictionaryStore.buildRegistryAfterInstall(
            registry, dict("new", listOf(YomitanCategory.TERMS)), replacing = old,
        )
        assertEquals(listOf("x", BUILT_IN, "new"), out.sectionOrder[TERMS])
        assertEquals(false, out.builtInTermsEnabled)
    }

    @Test fun `a reorder keeps the built-in row where the list put it, in the terms section alone`() {
        val inCategory = listOf("aaa", "bbb")
        assertEquals(
            listOf("bbb", BUILT_IN, "aaa"),
            YomitanDictionaryStore.reorderedSection(listOf("bbb", BUILT_IN, "aaa"), inCategory, keepBuiltIn = true),
        )
        assertEquals(
            listOf("bbb", "aaa"),
            YomitanDictionaryStore.reorderedSection(listOf("bbb", BUILT_IN, "aaa"), inCategory, keepBuiltIn = false),
        )
    }

    @Test fun `a reorder from a stale list drops a deleted id and re-adds a missed one above a bottom built-in row`() {
        assertEquals(
            listOf("aaa", "ccc", BUILT_IN),
            YomitanDictionaryStore.reorderedSection(
                orderedIds = listOf("gone", "aaa", "aaa", BUILT_IN),
                inCategory = listOf("aaa", "ccc"),
                keepBuiltIn = true,
            ),
        )
    }

    @Test fun `the data store counts one language's dictionaries above the built-in row`() {
        val rows = listOf("ja1", "zh1", BUILT_IN, "ja2")
        assertEquals(1, YomitanDataStore.builtInTermsSlot(rows, listOf("ja1", "ja2")))
        assertEquals(1, YomitanDataStore.builtInTermsSlot(rows, listOf("zh1")))
        assertEquals(0, YomitanDataStore.builtInTermsSlot(rows, emptyList()))
        // The row's default place: below all of them.
        assertEquals(2, YomitanDataStore.builtInTermsSlot(listOf("ja1", "ja2", BUILT_IN), listOf("ja1", "ja2")))
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
