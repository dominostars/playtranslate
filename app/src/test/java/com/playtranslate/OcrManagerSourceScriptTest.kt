package com.playtranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards `OcrManager.isSourceLangChar` — the helper the overlay dedup / no-text
 * key filters on (`OverlayToolkit`: `ocrResult.fullText.filter { isSourceLangChar }`,
 * and `InAppOnlyMode`). A region of only Arabic Supplement / Extended-A /
 * Presentation-Form glyphs (e.g. the ﷲ ligature the PP-OCRv5 charset can emit)
 * must NOT filter to empty, or `processActiveRegion` reports a false NoText and
 * drops valid Arabic OCR.
 *
 * The helper delegates to the single vendor-neutral definition in
 * `LayoutAnalyzer`; this pins the dedup-key path against re-introducing a
 * hardcoded base-block-only Arabic range.
 */
@RunWith(RobolectricTestRunner::class)
class OcrManagerSourceScriptTest {

    @Test
    fun arabicPresentationAndExtendedGlyphs_surviveDedupFilter() {
        assertTrue("base Arabic", OcrManager.isSourceLangChar('ب', "ar"))
        assertTrue("Arabic Supplement", OcrManager.isSourceLangChar('ݐ', "ar"))
        assertTrue("Arabic Extended-A", OcrManager.isSourceLangChar('ࢠ', "ar"))
        assertTrue("Presentation Forms-A (ﷲ)", OcrManager.isSourceLangChar('ﷲ', "ar"))
        // The actual failure mode: the dedup key (a filter on isSourceLangChar) must
        // not go empty for a region that is only a presentation-form ligature.
        assertEquals("ﷲ", "ﷲ".filter { OcrManager.isSourceLangChar(it, "ar") })
    }
}
