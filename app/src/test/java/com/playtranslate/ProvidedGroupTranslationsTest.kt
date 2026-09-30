package com.playtranslate

import com.playtranslate.ocr.ProvidedTranslations
import com.playtranslate.ocr.providedGroupTranslations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a reader's provided translations land on the OCR groups
 * ([providedGroupTranslations]) and when they may be used at all
 * ([ProvidedTranslations.Ok.isFor]). The groups are the capture's own, in
 * reading order; the reader keys its translations by group id, which is the
 * group's index, so the mapping must go by index and never by the map's own
 * order, and a translation made under another language pair must never be
 * shown or cached under the current one.
 */
class ProvidedGroupTranslationsTest {

    @Test fun `every group named maps by index, not by the map's order`() {
        // Inserted out of order: a mapping that walked the map's values would
        // come back b, a, c.
        val byGroup = linkedMapOf(1 to "b", 0 to "a", 2 to "c")
        assertEquals(listOf("a", "b", "c"), providedGroupTranslations(3, byGroup))
    }

    @Test fun `a group the reader did not name comes back blank`() {
        assertEquals(listOf("a", "", "c"), providedGroupTranslations(3, mapOf(0 to "a", 2 to "c")))
    }

    @Test fun `ids outside the groups are ignored`() {
        val byGroup = mapOf(-1 to "before", 0 to "a", 1 to "b", 2 to "after")
        assertEquals(listOf("a", "b"), providedGroupTranslations(2, byGroup))
    }

    @Test fun `no groups gives no translations`() {
        assertEquals(emptyList<String>(), providedGroupTranslations(0, mapOf(0 to "a")))
    }

    @Test fun `translations are for exactly the pair the reader sent`() {
        val ok = ProvidedTranslations.Ok(mapOf(0 to "a"), source = "ja", target = "en", providerName = "P")
        assertTrue(ok.isFor("ja", "en"))
        // The pair-mismatch cells: the target changed during the read, or the
        // source did. Either way the consumer translates as if none were provided.
        assertFalse(ok.isFor("ja", "de"))
        assertFalse(ok.isFor("zh", "en"))
    }
}
