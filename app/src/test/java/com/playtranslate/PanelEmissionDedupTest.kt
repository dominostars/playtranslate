package com.playtranslate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The delivery layer's per-display panel-emission key ([PanelEmissionDedup]
 * kdoc carries the rationale). The wiring — [CaptureService.emitPanelResult]
 * checks, [CaptureService.translateAndSendToPanel] commits, every
 * non-result transition clears, [CaptureService.emitResult] deliberately
 * records nothing — is pinned by comments at those sites.
 */
class PanelEmissionDedupTest {

    private fun texts(original: String, translated: String) =
        OverlayToolkit.PanelTexts(original, translated, emptyList())

    private val a = texts("扉は固く閉ざされている", "The door is firmly shut.")
    private val b = texts("扉は開いている", "The door is open.")

    @Test fun `first offer is new`() {
        assertTrue(PanelEmissionDedup().isNew(0, a, "DeepL"))
    }

    @Test fun `identical key on the same display is a duplicate`() {
        val d = PanelEmissionDedup()
        assertTrue(d.isNew(0, a, "DeepL"))
        // Equal content in a fresh instance: the key is the content.
        assertFalse(d.isNew(0, texts(a.originalText, a.translatedText), "DeepL"))
    }

    @Test fun `same text on another display has its own slot`() {
        val d = PanelEmissionDedup()
        d.isNew(0, a, "DeepL")
        assertTrue(d.isNew(1, a, "DeepL"))
        assertFalse("display 0 kept its own key", d.isNew(0, a, "DeepL"))
    }

    @Test fun `changed original or translation is new`() {
        val d = PanelEmissionDedup()
        d.isNew(0, a, "DeepL")
        assertTrue("original", d.isNew(0, texts("扉は開いている", a.translatedText), "DeepL"))
        assertTrue("translation", d.isNew(0, texts("扉は開いている", "The door is open."), "DeepL"))
    }

    @Test fun `label-only change is new, both ways across null`() {
        val d = PanelEmissionDedup()
        d.isNew(0, a, "DeepL")
        assertTrue("other backend", d.isNew(0, a, "Google"))
        assertTrue("label suppressed", d.isNew(0, a, null))
        assertFalse("null repeat", d.isNew(0, a, null))
        assertTrue("label back", d.isNew(0, a, "Google"))
    }

    @Test fun `a commit of other text makes the previous key new again`() {
        val d = PanelEmissionDedup()
        d.isNew(0, a, "DeepL")
        // A hold delivered different text: the panel no longer shows A.
        d.committed(0, b.originalText, b.translatedText, "DeepL")
        assertTrue(d.isNew(0, a, "DeepL"))
    }

    @Test fun `a commit records what it delivered`() {
        val d = PanelEmissionDedup()
        d.committed(0, a.originalText, a.translatedText, "DeepL")
        assertFalse(d.isNew(0, a, "DeepL"))
    }

    @Test fun `clear makes an identical key new on every display`() {
        val d = PanelEmissionDedup()
        d.isNew(0, a, "DeepL")
        d.isNew(1, b, "DeepL")
        d.clear()
        assertTrue(d.isNew(0, a, "DeepL"))
        assertTrue(d.isNew(1, b, "DeepL"))
    }

    @Test fun `only the last accepted offer is recorded`() {
        val d = PanelEmissionDedup()
        assertTrue("A", d.isNew(0, a, "DeepL"))
        assertTrue("B", d.isNew(0, b, "DeepL"))
        assertTrue("A after B", d.isNew(0, a, "DeepL"))
        assertFalse("A again", d.isNew(0, a, "DeepL"))
    }
}
