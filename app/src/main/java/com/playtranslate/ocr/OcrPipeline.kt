package com.playtranslate.ocr

import android.graphics.Bitmap
import com.playtranslate.OcrPreprocessingRecipe
import com.playtranslate.ocr.core.LayoutAnalyzer
import com.playtranslate.ocr.core.LayoutGroup
import com.playtranslate.ocr.core.OcrEngine
import com.playtranslate.ocr.core.OcrImage
import com.playtranslate.ocr.core.RecognizedTextNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        engineProvider: () -> OcrEngine,
        bitmap: Bitmap,
        sourceLang: String,
        screenshotWidth: Int,
        recipe: OcrPreprocessingRecipe,
        darkBackgroundProvider: () -> Boolean,
        logGrouping: Boolean,
    ): Output? = withContext(Dispatchers.Default) {
        // Run the whole pass OFF the main thread: preprocessing, the engine's
        // inference, and layout are all CPU-bound. The capture coroutine is
        // dispatched on Main, and synchronous MNN engines (Paddle/Meiki/manga-ocr)
        // would otherwise block it. ML Kit suspends around its async client so it
        // never blocked Main — which masked this until a heavy engine (manga-ocr
        // on a large page) blocked Main long enough to ANR. Moving every engine
        // off Main here fixes the ANR and the (smaller) Paddle/Meiki UI jank.
        //
        // The engine + dark-background inputs are resolved HERE, not as eager
        // call-site arguments: building a first-use Meiki/Paddle engine does a
        // native MNN load + OpenCV init, and the dark-bg sample reads bitmap
        // pixels — both would otherwise run on the Main capture coroutine.
        val engine = engineProvider()
        val isDarkBackground = darkBackgroundProvider()
        val selfPreprocesses = engine.capabilities.selfPreprocesses
        val processed = if (selfPreprocesses) bitmap else recipe.apply(bitmap, isDarkBackground)
        val scaleFactor =
            if (processed === bitmap) 1f else processed.width.toFloat() / bitmap.width
        try {
            val recognized = engine.recognize(OcrImage(processed, sourceLang, screenshotWidth))
            // Shared text normalization (pipe-trim / UI-decoration / noise) for EVERY
            // engine — folds in passes that used to live only in the ML Kit adapter, so
            // Meiki/Paddle/manga-ocr get them too. LayoutAnalyzer.analyze (this is its
            // sole production caller) assumes its input is already normalized.
            val regions = RecognizedTextNormalizer.normalize(recognized, sourceLang)
            if (regions.isEmpty()) return@withContext null
            val groups = LayoutAnalyzer.analyze(
                regions = regions,
                sourceLang = sourceLang,
                screenshotWidthInRegionSpace = screenshotWidth * scaleFactor,
                logDecisions = logGrouping,
            )
            if (groups.isEmpty()) return@withContext null
            Output(groups, scaleFactor)
        } finally {
            if (processed !== bitmap) processed.recycle()
        }
    }
}
