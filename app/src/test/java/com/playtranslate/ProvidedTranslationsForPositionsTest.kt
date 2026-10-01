package com.playtranslate

import com.playtranslate.ocr.ProvidedTranslations
import com.playtranslate.ocr.forPositions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [forPositions]: a reader's translations, keyed by group index, re-keyed by
 * position in a consumer's own list ([OverlayToolkit.translatePlaceholders]
 * hands the re-keyed set to [CaptureService.translateGroupsSeparately], which
 * reads it by position). Position i must take the translation of the group
 * `indices[i]` names, never the map's i-th value, and a set that names no
 * position is no set at all.
 */
class ProvidedTranslationsForPositionsTest {

    private fun ok(byGroup: Map<Int, String>) =
        ProvidedTranslations.Ok(byGroup, source = "ja", target = "en", providerName = "P")

    @Test fun `each position takes its group's translation, not the map's order`() {
        // Inserted out of order: a mapping that walked the map's values would
        // give position 0 "c" and position 1 "a".
        val provided = ok(linkedMapOf(2 to "c", 0 to "a", 1 to "b"))
        val byPosition = provided.forPositions(listOf(1, 2))!!
        assertEquals(mapOf(0 to "b", 1 to "c"), byPosition.byGroup)
    }

    @Test fun `the pair and the provider are kept`() {
        val byPosition = ok(mapOf(3 to "d")).forPositions(listOf(3))!!
        assertEquals(ProvidedTranslations.Ok(mapOf(0 to "d"), "ja", "en", "P"), byPosition)
    }

    @Test fun `a position whose group is not named stays unnamed`() {
        val byPosition = ok(mapOf(0 to "a", 2 to "c")).forPositions(listOf(0, 1, 2))!!
        assertEquals(mapOf(0 to "a", 2 to "c"), byPosition.byGroup)
    }

    @Test fun `no position named gives no set`() {
        assertNull(ok(mapOf(0 to "a")).forPositions(listOf(1, 2)))
        assertNull(ok(mapOf(0 to "a")).forPositions(emptyList()))
    }
}
