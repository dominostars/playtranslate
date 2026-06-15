package com.playtranslate.ocr.core

import android.graphics.Rect
import com.playtranslate.language.TextOrientation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the Arabic source-script filter. The PP-OCRv5 Arabic recognizer can
 * emit Arabic Supplement, Extended-A, and Presentation Forms (e.g. the ﷲ
 * ligature, U+FDF2). [LayoutAnalyzer.isSourceLangChar] must accept them — and
 * agree with the AR profile's isScriptChar — or [LayoutAnalyzer.analyze] drops a
 * region made only of those glyphs as non-source text before it can be
 * translated or looked up. (Adversarial review caught a hardcoded base-block-only
 * `"ar"` branch that did exactly that.)
 */
@RunWith(RobolectricTestRunner::class)
class LayoutAnalyzerSourceScriptTest {

    @Test
    fun isSourceLangChar_arabic_acceptsEveryRecognizerRange() {
        assertTrue("base Arabic", LayoutAnalyzer.isSourceLangChar('ب', "ar"))
        assertTrue("Arabic Supplement", LayoutAnalyzer.isSourceLangChar('ݐ', "ar"))
        assertTrue("Arabic Extended-A", LayoutAnalyzer.isSourceLangChar('ࢠ', "ar"))
        assertTrue("Presentation Forms-A (Allah ligature)", LayoutAnalyzer.isSourceLangChar('ﷲ', "ar"))
        assertTrue("Presentation Forms-B", LayoutAnalyzer.isSourceLangChar('ﹰ', "ar"))
        assertFalse("Latin is not Arabic", LayoutAnalyzer.isSourceLangChar('A', "ar"))
    }

    @Test
    fun analyze_arabic_keepsRegionOfOnlyPresentationFormGlyphs() {
        val box = OcrBox.upright(Rect(0, 0, 100, 40))
        val region = RecognizedRegion(
            text = "ﷲ",  // Allah ligature — a Presentation Forms-A glyph
            box = box,
            orientation = TextOrientation.HORIZONTAL,
            lines = listOf(RecognizedLine("ﷲ", box, TextOrientation.HORIZONTAL)),
            origin = RegionOrigin.LINE,
        )
        val groups = LayoutAnalyzer.analyze(
            listOf(region), sourceLang = "ar", screenshotWidthInRegionSpace = 0f,
        )
        assertTrue(
            "Arabic presentation-form region must survive the source-script filter",
            groups.isNotEmpty(),
        )
    }
}
