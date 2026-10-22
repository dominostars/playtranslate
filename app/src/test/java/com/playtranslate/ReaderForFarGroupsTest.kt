package com.playtranslate

import android.graphics.Rect
import com.playtranslate.ocr.ReaderTranslations
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [readerForFarGroups]: which of a pinhole look's far groups take the reader's
 * translation of their group. Every far group that is one whole group of the
 * read ([FarGroup.sourceGroupIndex]), whatever the cache holds for it (the
 * translate call, not this mapping, decides whether to wait); a coalesced
 * merge is no single group's text and takes none. A look none of whose boxes
 * can take one holds no reader, so it never waits for the server before its
 * waterfall.
 *
 * The reader here is settled with every group translated, so each view's
 * [ReaderTranslations.now] shows exactly which group each position took.
 * Robolectric for [Rect] only.
 */
@RunWith(RobolectricTestRunner::class)
class ReaderForFarGroupsTest {

    private fun far(text: String, sourceGroupIndex: Int?, top: Int = 0) =
        FarGroup(text, Rect(0, top, 100, top + 20), lineCount = 1, sourceGroupIndex = sourceGroupIndex)

    /** A reader for [byGroup.size] groups that settled with [byGroup]. */
    private fun settled(byGroup: Map<Int, String>) =
        ReaderTranslations("ja", "en", "P", byGroup.size, emptyMap(), Job()).also { it.settle(byGroup) }

    private val four = settled(mapOf(0 to "one", 1 to "two", 2 to "three", 3 to "four"))

    @Test fun `every box of one whole group takes that group's translation, by position`() {
        // Far position 0 is group 2 of the read, position 1 is group 0: a
        // mapping by position would give position 0 "one".
        val work = listOf(far("三", sourceGroupIndex = 2), far("一", sourceGroupIndex = 0, top = 40))

        val view = readerForFarGroups(work, four)!!

        assertEquals(mapOf(0 to "three", 1 to "one"), view.now.byPosition)
        assertTrue(view.isFor("ja", "en"))
        assertEquals("P", view.providerName)
    }

    @Test fun `a coalesced merge takes nothing`() {
        val work = listOf(far("一 二", sourceGroupIndex = null), far("三", sourceGroupIndex = 2, top = 40))
        assertEquals(mapOf(1 to "three"), readerForFarGroups(work, four)!!.now.byPosition)
    }

    @Test fun `a group the reader left untranslated is taken, and gives nothing`() {
        val work = listOf(far("一", sourceGroupIndex = 0), far("二", sourceGroupIndex = 1, top = 40))
        // Settled for two groups with only group 1 translated.
        val reader = ReaderTranslations("ja", "en", "P", 2, emptyMap(), Job()).also { it.settle(mapOf(1 to "two")) }
        assertEquals(mapOf(1 to "two"), readerForFarGroups(work, reader)!!.now.byPosition)
    }

    // ── when it holds no reader, and so waits for nothing ────────────────

    @Test fun `boxes that are all coalesced merges hold no reader`() {
        val work = listOf(far("一 二", sourceGroupIndex = null), far("三 四", sourceGroupIndex = null, top = 40))
        assertNull(readerForFarGroups(work, four))
    }

    @Test fun `no reader gives nothing`() {
        assertNull(readerForFarGroups(listOf(far("一", sourceGroupIndex = 0)), reader = null))
    }
}
