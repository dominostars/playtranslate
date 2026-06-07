package com.playtranslate

import android.graphics.Rect
import com.playtranslate.language.TextAlignment
import com.playtranslate.language.TextOrientation
import com.playtranslate.ocr.core.CharBox
import com.playtranslate.ocr.core.ElementBox
import com.playtranslate.ocr.core.LayoutAnalyzer
import com.playtranslate.ocr.core.OcrBox
import com.playtranslate.ocr.core.RecognizedLine
import com.playtranslate.ocr.core.RecognizedRegion
import com.playtranslate.ocr.core.RecognizedTextNormalizer
import com.playtranslate.ocr.core.RegionOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [RecognizedTextNormalizer] (the shared text-cleaning stage) and the
 * alignment compute that moved into [LayoutAnalyzer]. Together these close the four
 * passes that used to live only in the ML Kit adapter, so Meiki/Paddle/manga-ocr get
 * them too. Robolectric for real `android.graphics.Rect` geometry (mirrors
 * `LineAssemblyTest` / `OcrGroupingTest`).
 */
@RunWith(RobolectricTestRunner::class)
class RecognizedTextNormalizerTest {

    private val CHAR_W = 20
    private val H = 24

    /** Per-character boxes laid left-to-right, one cell per char, offset == index. */
    private fun charBoxes(text: String, left: Int, top: Int): List<CharBox> =
        text.mapIndexed { i, c ->
            CharBox(
                text = c.toString(),
                box = OcrBox.upright(Rect(left + i * CHAR_W, top, left + (i + 1) * CHAR_W, top + H)),
                charOffset = i,
            )
        }

    private fun region(
        text: String,
        left: Int = 0,
        top: Int = 0,
        confidence: Float = 0.9f,
        withChars: Boolean = true,
        elements: List<ElementBox> = emptyList(),
        languageUndetermined: Boolean = false,
    ): RecognizedRegion {
        val box = OcrBox.upright(Rect(left, top, left + text.length * CHAR_W, top + H))
        val line = RecognizedLine(
            text = text,
            box = box,
            orientation = TextOrientation.HORIZONTAL,
            elements = elements,
            chars = if (withChars) charBoxes(text, left, top) else emptyList(),
        )
        return RecognizedRegion(
            text = text,
            box = box,
            orientation = TextOrientation.HORIZONTAL,
            confidence = confidence,
            lines = listOf(line),
            origin = RegionOrigin.LINE,
            languageUndetermined = languageUndetermined,
        )
    }

    private fun normalize(vararg regions: RecognizedRegion) =
        RecognizedTextNormalizer.normalize(regions.toList(), "ja")

    // ── region-rebuild invariant + offset-safe trim ──────────────────────────

    @Test fun rebuildsRegionTextAndReoffsetsCharsAfterPipeTrim() {
        val out = normalize(region("|テキスト"))
        assertEquals(1, out.size)
        assertEquals("テキスト", out[0].text)
        assertEquals("region.text must equal lines[0].text", out[0].text, out[0].lines[0].text)
        val chars = out[0].lines[0].chars
        assertEquals(4, chars.size)               // leading '|' char dropped
        assertEquals("テ", chars[0].text)
        assertEquals(0, chars[0].charOffset)      // re-based to the trimmed string
        assertEquals(3, chars[3].charOffset)
    }

    @Test fun clearsElementTierWhenTrimmed() {
        val box = OcrBox.upright(Rect(0, 0, 100, H))
        val line = RecognizedLine(
            text = "|text",
            box = box,
            orientation = TextOrientation.HORIZONTAL,
            elements = listOf(ElementBox("|text", box)),
            chars = charBoxes("|text", 0, 0),
        )
        val r = RecognizedRegion("|text", box, TextOrientation.HORIZONTAL, -1f, listOf(line), RegionOrigin.LINE)
        val out = RecognizedTextNormalizer.normalize(listOf(r), "ja")
        assertEquals("text", out[0].text)
        assertTrue("element tier cleared on trim", out[0].lines[0].elements.isEmpty())
    }

    // ── UI-decoration tiers ───────────────────────────────────────────────────

    @Test fun dropsLoneCursorArrow() {
        assertTrue(normalize(region("▼")).isEmpty())
    }

    @Test fun stripsLeadingCursorArrow() {
        assertEquals("こんにちは", normalize(region("▼こんにちは"))[0].text)
    }

    @Test fun dropsLoneAngleBracketRegion() {
        assertTrue(normalize(region("《")).isEmpty())
        assertTrue(normalize(region("《》")).isEmpty())
    }

    @Test fun keepsRealCjkAngleQuotePunctuation() {
        // 《》 are real Chinese title/quote marks — must NOT be stripped or dropped.
        val out = normalize(region("《書名》です"))
        assertEquals(1, out.size)
        assertEquals("《書名》です", out[0].text)
    }

    @Test fun dropsLoneAsciiBrackets() {
        // Stray bracket chrome with no content → noise.
        assertTrue(normalize(region("<")).isEmpty())
        assertTrue(normalize(region("<>")).isEmpty())
        assertTrue(normalize(region(">")).isEmpty())
    }

    @Test fun keepsBracketWrappedContent() {
        // Brackets framing real text are content, not chrome — keep verbatim, do NOT
        // strip to "こんにちは". Deliberately counter to the Codex P2 suggestion: angle
        // brackets are whole-line-droppable but never edge-stripped.
        assertEquals("<こんにちは>", normalize(region("<こんにちは>"))[0].text)
    }

    // ── noise / garble filter ─────────────────────────────────────────────────

    @Test fun keepsConfidentSingleSourceChar() {
        assertEquals(1, normalize(region("あ")).size)
    }

    // Confidence — not script membership — is the single-char keep/drop signal.

    @Test fun dropsLowConfidenceSingleGlyph() {
        // Native-review case: a stray single kana from an undetermined block (low
        // confidence) was previously kept; it must drop. Also a low-confidence symbol.
        assertTrue(normalize(region("の", confidence = 0.2f)).isEmpty())
        assertTrue(normalize(region("A", confidence = 0.2f)).isEmpty())
    }

    @Test fun keepsConfidentExpressivePunctuation() {
        // Adversarial-review case: a confident "！"/"…" bubble is content, not junk.
        assertEquals("！", normalize(region("！", confidence = 0.9f))[0].text)
        assertEquals("…", normalize(region("…", confidence = 0.9f))[0].text)
    }

    @Test fun keepsSingleGlyphWhenConfidenceUnknown() {
        assertEquals(1, normalize(region("！", confidence = -1f)).size)
    }

    @Test fun dropsUndeterminedSingleGlyphWhenConfidenceUnavailable() {
        // Pre-API-31 ML Kit: confidence -1 + block language undetermined → a lone
        // non-kanji glyph (stray kana, punctuation) is noise, as the old mapper had it.
        assertTrue(normalize(region("の", confidence = -1f, languageUndetermined = true)).isEmpty())
        assertTrue(normalize(region("！", confidence = -1f, languageUndetermined = true)).isEmpty())
    }

    @Test fun keepsUndeterminedSingleKanji() {
        // A lone kanji is often a real one-character word/sign — ML Kit always kept it.
        assertEquals(1, normalize(region("水", confidence = -1f, languageUndetermined = true)).size)
    }

    @Test fun confidentGlyphSurvivesUndeterminedLanguage() {
        // Confidence overrides the no-context guard: a confident "！" bubble stays.
        assertEquals("！", normalize(region("！", confidence = 0.9f, languageUndetermined = true))[0].text)
    }

    @Test fun dropsGarbledLowConfidence() {
        assertTrue(normalize(region("abc", confidence = 0.2f)).isEmpty())
    }

    @Test fun keepsGarbledWhenHighConfidence() {
        assertEquals(1, normalize(region("abc", confidence = 0.9f)).size)
    }

    @Test fun skipsRatioRuleWhenConfidenceUnknown() {
        assertEquals(1, normalize(region("abc", confidence = -1f)).size)
    }

    @Test fun passesThroughCleanSourceText() {
        val out = normalize(region("こんにちは"))
        assertEquals("こんにちは", out[0].text)
        assertEquals(out[0].text, out[0].lines[0].text)
    }

    // ── alignment compute moved into LayoutAnalyzer (gaps #1 / #1b) ────────────

    @Test fun effectiveAlignLeftShiftsPastHangingPunct() {
        // "「" occupies [100,120]; the body anchor is its right edge.
        val line = RecognizedLine(
            text = "「こんにちは",
            box = OcrBox.upright(Rect(100, 0, 100 + 6 * CHAR_W, H)),
            orientation = TextOrientation.HORIZONTAL,
            chars = charBoxes("「こんにちは", 100, 0),
        )
        assertEquals(120, LayoutAnalyzer.effectiveAlignLeft(line))
    }

    @Test fun effectiveAlignLeftRawWhenNoHangingPunct() {
        val line = RecognizedLine(
            text = "こんにちは",
            box = OcrBox.upright(Rect(100, 0, 100 + 5 * CHAR_W, H)),
            orientation = TextOrientation.HORIZONTAL,
            chars = charBoxes("こんにちは", 100, 0),
        )
        assertEquals(100, LayoutAnalyzer.effectiveAlignLeft(line))
    }

    @Test fun effectiveAlignLeftFallsBackToHeightWithoutChars() {
        // No char tier (Paddle/manga): approximate the leading glyph width by line height.
        val line = RecognizedLine(
            text = "「こんにちは",
            box = OcrBox.upright(Rect(100, 0, 220, H)),
            orientation = TextOrientation.HORIZONTAL,
        )
        assertEquals(100 + H, LayoutAnalyzer.effectiveAlignLeft(line))
    }

    @Test fun effectiveAlignLeftFallsBackWhenLeadingPunctBoxMissing() {
        // Sparse char tier: no symbol box was emitted for the opening 「 (offset 0), only
        // for body glyphs. chars.first() would be こ (offset 1) and its right edge (140)
        // over-shoots past the body — match by offset, and fall back to the height
        // approximation (124) when the punctuation's own box is absent.
        val box = OcrBox.upright(Rect(100, 0, 100 + 6 * CHAR_W, H))
        val bodyChars = listOf(
            CharBox("こ", OcrBox.upright(Rect(120, 0, 140, H)), charOffset = 1),
            CharBox("ん", OcrBox.upright(Rect(140, 0, 160, H)), charOffset = 2),
        )
        val line = RecognizedLine("「こんにちは", box, TextOrientation.HORIZONTAL, chars = bodyChars)
        assertEquals(100 + H, LayoutAnalyzer.effectiveAlignLeft(line))
    }

    @Test fun classifiesCenteredGroupNowThatFieldIsGone() {
        // Two center-aligned lines, no precomputed hint anywhere. Previously
        // unreachable: the effectiveAlignLeft-null guard forced LEFT for non-ML-Kit.
        val l1 = RecognizedLine("あいうえお", OcrBox.upright(Rect(100, 0, 200, H)), TextOrientation.HORIZONTAL)   // center 150
        val l2 = RecognizedLine("かきく", OcrBox.upright(Rect(120, 30, 180, 30 + H)), TextOrientation.HORIZONTAL) // center 150
        assertEquals(TextAlignment.CENTER, LayoutAnalyzer.classifyGroupAlignment(listOf(l1, l2)))
    }

    @Test fun classifiesLeftAlignedGroup() {
        val l1 = RecognizedLine("あいうえお", OcrBox.upright(Rect(100, 0, 200, H)), TextOrientation.HORIZONTAL)
        val l2 = RecognizedLine("かきく", OcrBox.upright(Rect(100, 30, 160, 30 + H)), TextOrientation.HORIZONTAL)
        assertEquals(TextAlignment.LEFT, LayoutAnalyzer.classifyGroupAlignment(listOf(l1, l2)))
    }
}
