package com.playtranslate

import android.graphics.Bitmap
import android.text.TextPaint
import com.playtranslate.language.SourceLanguageEngine
import com.playtranslate.ui.TextBox
import androidx.core.graphics.scale

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
        showOverlay: (List<TextBox>) -> Unit
    ): List<TextBox>
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
        showOverlay: (List<TextBox>) -> Unit
    ): List<TextBox> {
        return OverlayToolkit.buildFuriganaBoxes(ocrResult, engine, furiganaPaint)
    }
}

/** Builds color-matched translation overlay boxes. Shows shimmer while translating. */
internal class TranslationOneShotProcessor(
    private val translateFn: suspend (List<String>) -> List<CaptureService.GroupTranslation>
) : OneShotProcessor {
    override suspend fun buildBoxes(
        ocrResult: OcrManager.OcrResult,
        raw: Bitmap,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int,
        showOverlay: (List<TextBox>) -> Unit
    ): List<TextBox> {
        // Color sample from scaled reference
        val colorScale = 4
        val colorRef = raw.scale(raw.width / colorScale, raw.height / colorScale, false)
        val colors: List<Pair<Int, Int>>
        try {
            colors = OverlayToolkit.sampleGroupColors(
                colorRef, ocrResult.groups.map { it.bounds }, cropLeft, cropTop, colorScale
            )
        } finally {
            colorRef.recycle()
        }

        // Show shimmer placeholders while translating
        val placeholders = ocrResult.groups.mapIndexed { idx, g ->
            val (bgColor, textColor) = colors.getOrElse(idx) {
                Pair(android.graphics.Color.argb(200, 0, 0, 0), android.graphics.Color.WHITE)
            }
            TextBox("", g.bounds, bgColor, textColor, g.lines.size, orientation = g.orientation, alignment = g.alignment)
        }
        showOverlay(placeholders)

        // Translate
        val perGroup = translateFn(ocrResult.groups.map { it.text })

        // Build final boxes with translated text
        return if (ocrResult.groups.size == perGroup.size) {
            perGroup.zip(placeholders).map { (tr, ph) ->
                ph.copy(translatedText = tr.text)
            }
        } else placeholders
    }
}
