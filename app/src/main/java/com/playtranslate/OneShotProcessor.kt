package com.playtranslate

import android.graphics.Bitmap
import android.text.TextPaint
import com.playtranslate.language.SourceLanguageEngine
import com.playtranslate.ui.TranslationOverlayView

/**
 * Builds overlay boxes from a single OCR result.
 * The variant logic in the one-shot pipeline — furigana vs translation
 * produce different box types via different processing.
 */
interface OneShotProcessor {
    suspend fun buildBoxes(
        ocrResult: OcrManager.OcrResult,
        raw: Bitmap,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int,
        showOverlay: (List<TranslationOverlayView.TextBox>) -> Unit
    ): List<TranslationOverlayView.TextBox>
}

/** Builds furigana/pinyin reading boxes. Instant — no network, no shimmer. */
class FuriganaOneShotProcessor(
    private val engine: SourceLanguageEngine,
    private val furiganaPaint: TextPaint
) : OneShotProcessor {
    override suspend fun buildBoxes(
        ocrResult: OcrManager.OcrResult,
        raw: Bitmap,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int,
        showOverlay: (List<TranslationOverlayView.TextBox>) -> Unit
    ): List<TranslationOverlayView.TextBox> {
        return OverlayToolkit.buildFuriganaBoxes(ocrResult, engine, furiganaPaint)
    }
}

/** Builds color-matched translation overlay boxes. Shows shimmer while translating. */
class TranslationOneShotProcessor(
    private val translateFn: suspend (List<String>) -> List<Pair<String, String?>>
) : OneShotProcessor {
    override suspend fun buildBoxes(
        ocrResult: OcrManager.OcrResult,
        raw: Bitmap,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int,
        showOverlay: (List<TranslationOverlayView.TextBox>) -> Unit
    ): List<TranslationOverlayView.TextBox> {
        // Color sample from scaled reference
        val colorScale = 4
        val colorRef = Bitmap.createScaledBitmap(
            raw, raw.width / colorScale, raw.height / colorScale, false
        )
        val colors: List<Pair<Int, Int>>
        try {
            colors = OverlayToolkit.sampleGroupColors(
                colorRef, ocrResult.groupBounds, cropLeft, cropTop, colorScale
            )
        } finally {
            colorRef.recycle()
        }

        // Show shimmer placeholders while translating
        val placeholders = ocrResult.groupBounds.mapIndexed { idx, bounds ->
            val (bgColor, textColor) = colors.getOrElse(idx) {
                Pair(android.graphics.Color.argb(200, 0, 0, 0), android.graphics.Color.WHITE)
            }
            val lineCount = ocrResult.groupLineCounts.getOrElse(idx) { 1 }
            TranslationOverlayView.TextBox("", bounds, bgColor, textColor, lineCount)
        }
        showOverlay(placeholders)

        // Translate
        val perGroup = translateFn(ocrResult.groupTexts)

        // Build final boxes with translated text
        return if (ocrResult.groupBounds.size == perGroup.size) {
            perGroup.zip(placeholders).map { (tr, ph) ->
                ph.copy(translatedText = tr.first)
            }
        } else placeholders
    }
}
