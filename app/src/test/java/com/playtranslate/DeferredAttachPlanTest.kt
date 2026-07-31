package com.playtranslate

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the deferred-completion attach dedupe ([deferredAttachPlan]): a capture
 * records duplicate group texts as ONE null-translation History row (the
 * token's seen-set), so the completion attaches once per distinct normalized
 * key — the store's attach is idempotent either way, but each row should be
 * offered its translation exactly once.
 */
@RunWith(RobolectricTestRunner::class)
class DeferredAttachPlanTest {

    private fun gt(text: String, backend: String? = null) =
        CaptureService.GroupTranslation(text, note = null, backendDisplayName = backend)

    @Test
    fun duplicateGroupTextsAttachOnce() {
        val plan = deferredAttachPlan(
            listOf("こんにちは、世界", "レベルアップ", "こんにちは、世界"),
            listOf(gt("Hello, world", "DeepL"), gt("Level up", "DeepL"), gt("Hello, world", "DeepL")),
            recordSrc = "ja",
        )
        assertEquals(2, plan.size)
        assertEquals("こんにちは、世界", plan[0].first)
        assertEquals("Hello, world", plan[0].second)
        assertEquals("レベルアップ", plan[1].first)
        assertEquals("Level up", plan[1].second)
    }

    @Test
    fun blankTranslationsAreSkipped() {
        // A group whose translation came back blank has nothing to attach —
        // its null row deliberately stays null (visible-path parity: the
        // non-deferred pipeline records no row for a blank translation).
        val plan = deferredAttachPlan(
            listOf("こんにちは、世界", "レベルアップ"),
            listOf(gt(""), gt("Level up", "Qwen")),
            recordSrc = "ja",
        )
        assertEquals(1, plan.size)
        assertEquals("レベルアップ", plan[0].first)
        assertEquals("Qwen", plan[0].third)
    }

    @Test
    fun widthJitterDuplicatesCollapseToOneAttach() {
        // The dedupe key is LogWriteGate.normalizedKey, not raw text — the
        // same fold the insert-side dedupe used, so the two sides agree on
        // what "the same group" means.
        val plan = deferredAttachPlan(
            listOf("レベルアップ", "レベル アップ"),
            listOf(gt("Level up"), gt("Level up")),
            recordSrc = "ja",
        )
        assertEquals(1, plan.size)
    }
}
