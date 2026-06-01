package com.playtranslate.ocr

import android.graphics.Bitmap
import com.playtranslate.OcrPreprocessingRecipe
import com.playtranslate.ocr.core.LayoutAnalyzer
import com.playtranslate.ocr.core.LayoutGroup
import com.playtranslate.ocr.core.OcrEngine
import com.playtranslate.ocr.core.OcrImage

/**
 * Orchestrates one OCR pass: preprocess → engine → shared layout. Engine- and
 * recipe-agnostic. Returns grouped paragraphs in the engine's INPUT coordinate
 * space, plus the [Output.scaleFactor] the caller uses to normalize boxes back
 * to original-bitmap coordinates. The caller ([com.playtranslate.OcrManager])
 * projects the groups into its `OcrResult` / `OcrLine` shapes — so both entry
 * points share one acquisition+layout path (no duplication).
 *
 * Preprocessing lives here, not in the engine, because the recipe is an
 * app-level, per-call concern (the golden sweep overrides it) and `ocr.core`
 * must not depend on `OcrPreprocessingRecipe`. Engines that self-preprocess
 * ([com.playtranslate.ocr.core.OcrCapabilities.selfPreprocesses] = true, e.g.
 * PaddleOCR) receive the original bitmap and report original coords
 * (scaleFactor = 1); the grouping kernel is ratio-based, so grouping is
 * identical regardless of which space it runs in.
 */
object OcrPipeline {

    /** Grouped paragraphs in the engine's input-coordinate space, plus the
     *  factor to divide box coordinates by to reach original-bitmap space. */
    data class Output(val groups: List<LayoutGroup>, val scaleFactor: Float)

    suspend fun run(
        engine: OcrEngine,
        bitmap: Bitmap,
        sourceLang: String,
        screenshotWidth: Int,
        recipe: OcrPreprocessingRecipe,
        isDarkBackground: Boolean,
        logGrouping: Boolean,
    ): Output? {
        val selfPreprocesses = engine.capabilities.selfPreprocesses
        val processed = if (selfPreprocesses) bitmap else recipe.apply(bitmap, isDarkBackground)
        val scaleFactor =
            if (processed === bitmap) 1f else processed.width.toFloat() / bitmap.width
        try {
            val regions = engine.recognize(OcrImage(processed, sourceLang, screenshotWidth))
            if (regions.isEmpty()) return null
            val groups = LayoutAnalyzer.analyze(
                regions = regions,
                sourceLang = sourceLang,
                screenshotWidthInRegionSpace = screenshotWidth * scaleFactor,
                logDecisions = logGrouping,
            )
            if (groups.isEmpty()) return null
            return Output(groups, scaleFactor)
        } finally {
            if (processed !== bitmap) processed.recycle()
        }
    }
}
