package com.playtranslate

import android.graphics.Rect
import com.playtranslate.language.TextAlignment
import com.playtranslate.language.TextOrientation
import com.playtranslate.ocr.ProvidedTranslations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [providedForRegions]: which of a reconciler cycle's regions take the
 * reader's translation of their group. Only a region carrying one of this
 * read's groups, by identity, whose text is that whole group's: the TRANSLATION
 * flavor dispatches a typewriter's sentence-complete prefix with its group
 * attached, and a prefix showing the whole group's translation would put text
 * on screen the game has not typed yet.
 *
 * Robolectric for [Rect] only.
 */
@RunWith(RobolectricTestRunner::class)
class ProvidedForRegionsTest {

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

    private fun ok(byGroup: Map<Int, String>) =
        ProvidedTranslations.Ok(byGroup, source = "ja", target = "en", providerName = "P")

    @Test fun `whole-group regions take their group's translation, by position`() {
        val g0 = group("一", top = 0)
        val g1 = group("二", top = 40)
        val g2 = group("三", top = 80)
        val provided = ok(mapOf(0 to "one", 1 to "two", 2 to "three"))

        val byPosition = providedForRegions(listOf(region(g2), region(g0)), listOf(g0, g1, g2), provided)!!

        assertEquals(mapOf(0 to "three", 1 to "one"), byPosition.byGroup)
        assertEquals("ja", byPosition.source)
        assertEquals("en", byPosition.target)
        assertEquals("P", byPosition.providerName)
    }

    @Test fun `groups are matched by identity, not by value`() {
        // Two groups equal in value (same text, same bounds), distinct reads.
        val first = group("同じ")
        val second = group("同じ")
        assertEquals(first, second)
        assertNotSame(first, second)
        val provided = ok(mapOf(0 to "first", 1 to "second"))

        val byPosition = providedForRegions(listOf(region(second)), listOf(first, second), provided)!!

        assertEquals(mapOf(0 to "second"), byPosition.byGroup)
    }

    @Test fun `a typewriter prefix never takes the whole group's translation`() {
        val typing = group("こんにちは。元気ですか")
        val label = group("メニュー", top = 40)
        val provided = ok(mapOf(0 to "Hello. How are you", 1 to "Menu"))
        val prefix = region(typing, text = "こんにちは。")

        val byPosition = providedForRegions(listOf(prefix, region(label)), listOf(typing, label), provided)!!

        assertEquals(mapOf(1 to "Menu"), byPosition.byGroup)
        assertNull(providedForRegions(listOf(prefix), listOf(typing, label), provided))
    }

    @Test fun `a region without one of this read's groups, or whose group was not translated, takes nothing`() {
        val g0 = group("一")
        val g1 = group("二", top = 40)
        val otherRead = group("一")
        val provided = ok(mapOf(0 to "one"))
        val groups = listOf(g0, g1)

        // No group attached (hand-built), a group from another read, a group
        // the reader left untranslated.
        val work = listOf(region(null), region(otherRead), region(g1))
        assertNull(providedForRegions(work, groups, provided))
        assertEquals(
            mapOf(3 to "one"),
            providedForRegions(work + region(g0), groups, provided)!!.byGroup,
        )
    }

    @Test fun `nothing provided gives nothing`() {
        val g0 = group("一")
        assertNull(providedForRegions(listOf(region(g0)), listOf(g0), provided = null))
    }
}
