package com.playtranslate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Guards the Chinese-variant staleness fix. LastSentenceCache stores ALREADY-
 * LOCALIZED translation + gloss output keyed only by sentence text, so a target
 * or script-variant change (which keeps targetLang == "zh", firing no language-
 * code invalidation) must drop the cache or it serves the stale script when the
 * panel/sheet reopens for the same sentence. The target picker now calls clear()
 * on selection; this verifies clear() actually resets the stale-bearing fields.
 */
class LastSentenceCacheTest {

    @Before fun reset() = LastSentenceCache.clear()

    @Test fun `clear drops localized translation and word results`() {
        LastSentenceCache.setFromTranslationResult(
            original = "请用鼠标",
            translation = "請用滑鼠",                       // Traditional (TW) — already localized
            translationSource = "ml-kit",
            wordResults = mapOf("鼠标" to Triple("", "滑鼠", 0)),
            surfaceForms = emptyMap(),
            wordEnrichment = emptyMap(),
        )
        assertEquals("請用滑鼠", LastSentenceCache.translation)
        assertNotNull(LastSentenceCache.wordResults)

        // What the picker now does on a target/variant change.
        LastSentenceCache.clear()

        assertNull("stale translation must not survive a target/variant change", LastSentenceCache.translation)
        assertNull(LastSentenceCache.wordResults)
        assertNull(LastSentenceCache.surfaceForms)
        assertNull(LastSentenceCache.original)
    }

    // ── snapshotFor: the locked all-or-nothing read ──────────────────────

    @Test fun `snapshotFor returns the word maps only for the matching sentence`() {
        LastSentenceCache.setFromTranslationResult(
            original = "猫が好き",
            translation = "I like cats",
            translationSource = "test",
            wordResults = mapOf("猫" to Triple("ねこ", "cat", 3)),
            surfaceForms = mapOf("猫" to "猫"),
            wordEnrichment = mapOf("猫" to WordEnrichment(pitch = listOf(1))),
        )
        val snap = LastSentenceCache.snapshotFor("猫が好き")
        assertNotNull(snap)
        assertEquals("cat", snap!!.results.getValue("猫").second)
        assertEquals(listOf(1), snap.enrichment.getValue("猫").pitch)
        assertEquals("猫", snap.surfaces["猫"])

        assertNull("another sentence's words must not leak",
            LastSentenceCache.snapshotFor("犬が好き"))
    }

    @Test fun `snapshotFor is null when the sentence has no words yet`() {
        LastSentenceCache.setFromTranslationResult(
            original = "猫が好き",
            translation = "I like cats",
            translationSource = "test",
            wordResults = null,
            surfaceForms = null,
            wordEnrichment = null,
        )
        assertNull(LastSentenceCache.snapshotFor("猫が好き"))
    }
}
