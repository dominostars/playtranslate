package com.playtranslate

import android.graphics.Rect
import com.playtranslate.language.TextAlignment
import com.playtranslate.language.TextOrientation
import com.playtranslate.ocr.ReaderTranslations
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [readerForRegions]: which of a reconciler cycle's regions take the reader's
 * translation of their group. Only a region carrying one of this read's
 * groups, by identity, whose text is that whole group's: the TRANSLATION
 * flavor dispatches a typewriter's sentence-complete prefix with its group
 * attached, and a prefix showing the whole group's translation would put text
 * on screen the game has not typed yet. A cycle none of whose regions can
 * take one holds no reader, so it never waits for the server before its
 * waterfall.
 *
 * The reader here is settled with every group translated, so each view's
 * [ReaderTranslations.now] shows exactly which group each position took.
 * Robolectric for [Rect] only.
 */
@RunWith(RobolectricTestRunner::class)
class ReaderForRegionsTest {

    private fun group(text: String, top: Int = 0) =
        OcrManager.OcrGroup(text = text, bounds = Rect(0, top, 100, top + 20))

    private fun region(g: OcrManager.OcrGroup?, text: String = g?.text ?: "x") =
        ScanlineReconciler.Region(
            text = text,
            bounds = g?.bounds ?: Rect(0, 0, 10, 10),
            lineCount = 1,
            orientation = TextOrientation.HORIZONTAL,
            alignment = TextAlignment.LEFT,
            group = g,
        )

    /** A reader for [byGroup.size] groups that settled with [byGroup]. */
    private fun settled(byGroup: Map<Int, String>) =
        ReaderTranslations("ja", "en", "P", byGroup.size, emptyMap(), Job()).also { it.settle(byGroup) }

    @Test fun `whole-group regions take their group's translation, by position`() {
        val g0 = group("一", top = 0)
        val g1 = group("二", top = 40)
        val g2 = group("三", top = 80)
        val reader = settled(mapOf(0 to "one", 1 to "two", 2 to "three"))

        val view = readerForRegions(listOf(region(g2), region(g0)), listOf(g0, g1, g2), reader)!!

        assertEquals(mapOf(0 to "three", 1 to "one"), view.now.byPosition)
        assertTrue(view.isFor("ja", "en"))
        assertEquals("P", view.providerName)
    }

    @Test fun `groups are matched by identity, not by value`() {
        // Two groups equal in value (same text, same bounds), distinct reads.
        val first = group("同じ")
        val second = group("同じ")
        assertEquals(first, second)
        assertNotSame(first, second)
        val reader = settled(mapOf(0 to "first", 1 to "second"))

        val view = readerForRegions(listOf(region(second)), listOf(first, second), reader)!!

        assertEquals(mapOf(0 to "second"), view.now.byPosition)
    }

    @Test fun `a typewriter prefix never takes the whole group's translation`() {
        val typing = group("こんにちは。元気ですか")
        val label = group("メニュー", top = 40)
        val reader = settled(mapOf(0 to "Hello. How are you", 1 to "Menu"))
        val prefix = region(typing, text = "こんにちは。")

        val view = readerForRegions(listOf(prefix, region(label)), listOf(typing, label), reader)!!

        assertEquals(mapOf(1 to "Menu"), view.now.byPosition)
    }

    @Test fun `regions that are only typewriter prefixes hold no reader, so nothing waits`() {
        val typing = group("こんにちは。元気ですか")
        val label = group("メニュー", top = 40)
        // Not settled: a reader for these regions would be pending.
        val reader = ReaderTranslations("ja", "en", "P", 2, emptyMap(), Job())

        assertNull(readerForRegions(listOf(region(typing, text = "こんにちは。")), listOf(typing, label), reader))
    }

    @Test fun `a region without one of this read's groups takes nothing`() {
        val g0 = group("一")
        val g1 = group("二", top = 40)
        val otherRead = group("一")
        val reader = settled(mapOf(0 to "one", 1 to "two"))
        val groups = listOf(g0, g1)

        // No group attached (hand-built), a group from another read.
        assertNull(readerForRegions(listOf(region(null), region(otherRead)), groups, reader))
        assertEquals(
            mapOf(2 to "two"),
            readerForRegions(listOf(region(null), region(otherRead), region(g1)), groups, reader)!!.now.byPosition,
        )
    }

    @Test fun `a region whose group the reader left untranslated takes it, and gets nothing`() {
        val g0 = group("一")
        val g1 = group("二", top = 40)
        val reader = ReaderTranslations("ja", "en", "P", 2, mapOf(0 to "one"), Job())

        val view = readerForRegions(listOf(region(g1)), listOf(g0, g1), reader)!!

        // Mapped before the reader is done: its later translations reach it.
        assertTrue(view.pending)
        reader.settle()
        assertFalse(view.pending)
        assertEquals(emptyMap<Int, String>(), view.now.byPosition)
    }

    @Test fun `no reader gives nothing`() {
        val g0 = group("一")
        assertNull(readerForRegions(listOf(region(g0)), listOf(g0), reader = null))
    }
}
