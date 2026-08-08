package com.playtranslate.ocr

import android.graphics.Bitmap
import com.playtranslate.OcrPreprocessingRecipe
import com.playtranslate.language.OcrBackend
import com.playtranslate.ocr.core.LayoutAnalyzer
import com.playtranslate.ocr.core.LayoutGroup
import com.playtranslate.ocr.core.OcrImage
import com.playtranslate.ocr.core.RecognizedTextNormalizer
import com.playtranslate.ocr.core.ResolvedOcr
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
     *  factor to divide box coordinates by to reach original-bitmap space and the
     *  [backend] that actually ran (null = the no-OCR empty engine), so the caller
     *  can attribute the result to a specific OCR tool. [mangaOcrUsed] is true when
     *  the manga-ocr refiner decoded at least one block of this result — the caller
     *  extends the attribution to "… + MangaOCR". */
    data class Output(
        val groups: List<LayoutGroup>,
        val scaleFactor: Float,
        val backend: OcrBackend?,
        val mangaOcrUsed: Boolean = false,
    )

    /** Pre-layout recognition result handed to [withRecognition]'s block:
     *  normalized engine output in the engine's input space (divide box
     *  coords by [scaleFactor] for original-bitmap space). [processed] is the
     *  engine-input bitmap and is valid ONLY inside the block —
     *  [withRecognition] recycles it on exit. */
    data class Recognition(
        val regions: List<com.playtranslate.ocr.core.RecognizedRegion>,
        val scaleFactor: Float,
        val processed: Bitmap,
        val backend: OcrBackend?,
    )

    /** Pre-layout half of [run] as a scoped bracket: engine resolution,
     *  preprocessing, recognition, shared normalization, then [block] with the
     *  [Recognition]. Deliberately NOT a value-returning seam: the
     *  preprocessed bitmap never escapes this frame, so allocation and the
     *  recycling finally coexist and cancellation at any suspend point —
     *  including the prompt-cancellation resume boundary, which discards a
     *  returned value — cannot strand it (2026-07-20 adversarial-review
     *  finding on the previous returning shape). */
    internal suspend fun <T> withRecognition(
        engineProvider: () -> ResolvedOcr,
        bitmap: Bitmap,
        sourceLang: String,
        screenshotWidth: Int,
        recipe: OcrPreprocessingRecipe,
        darkBackgroundProvider: () -> Boolean,
        regionPreFilter: com.playtranslate.ocr.core.RegionPreFilter? = null,
        angleNoiseGateDeg: Float = com.playtranslate.ocr.core.OcrBox.ANGLE_NOISE_GATE_DEG,
        block: suspend (Recognition) -> T,
    ): T = withContext(Dispatchers.Default) {
        // Engine + dark-background inputs are resolved HERE, not as eager
        // call-site arguments: building a first-use Meiki/Paddle engine does a
        // native MNN load + OpenCV init, and the dark-bg sample reads bitmap
        // pixels — both would otherwise run on the Main capture coroutine.
        val resolved = engineProvider()
        val engine = resolved.engine
        val isDarkBackground = darkBackgroundProvider()
        val selfPreprocesses = engine.capabilities.selfPreprocesses
        val processed = if (selfPreprocesses) bitmap else recipe.apply(bitmap, isDarkBackground)
        val scaleFactor =
            if (processed === bitmap) 1f else processed.width.toFloat() / bitmap.width
        try {
            val recognized = engine.recognize(
                OcrImage(processed, sourceLang, screenshotWidth, regionPreFilter, angleNoiseGateDeg),
            )
            // Shared text normalization (pipe-trim / UI-decoration / noise) for EVERY
            // engine — folds in passes that used to live only in the ML Kit adapter, so
            // Meiki/Paddle/manga-ocr get them too. LayoutAnalyzer.analyze (whose sole
            // production caller is [run]) assumes its input is already normalized.
            val regions = RecognizedTextNormalizer.normalize(recognized, sourceLang)
            block(Recognition(regions, scaleFactor, processed, resolved.backend))
        } finally {
            if (processed !== bitmap) processed.recycle()
        }
    }

    suspend fun run(
        engineProvider: () -> ResolvedOcr,
        bitmap: Bitmap,
        sourceLang: String,
        screenshotWidth: Int,
        recipe: OcrPreprocessingRecipe,
        darkBackgroundProvider: () -> Boolean,
        logGrouping: Boolean,
        refineWithMangaOcr: Boolean = false,
        regionPreFilter: com.playtranslate.ocr.core.RegionPreFilter? = null,
        documentLayoutBias: Boolean = false,
        angleNoiseGateDeg: Float = com.playtranslate.ocr.core.OcrBox.ANGLE_NOISE_GATE_DEG,
    ): Output? =
        // The whole pass runs OFF the main thread (withRecognition dispatches
        // to Default): preprocessing, the engine's inference, and layout are
        // all CPU-bound. The capture coroutine is dispatched on Main, and
        // synchronous MNN engines (Paddle/Meiki/manga-ocr) would otherwise
        // block it. ML Kit suspends around its async client so it never
        // blocked Main — which masked this until a heavy engine (manga-ocr on
        // a large page) blocked Main long enough to ANR.
        withRecognition(
            engineProvider, bitmap, sourceLang, screenshotWidth, recipe,
            darkBackgroundProvider, regionPreFilter, angleNoiseGateDeg,
        ) { rec ->
            if (rec.regions.isEmpty()) return@withRecognition null
            val groups = LayoutAnalyzer.analyze(
                regions = rec.regions,
                sourceLang = sourceLang,
                screenshotWidthInRegionSpace = screenshotWidth * rec.scaleFactor,
                logDecisions = logGrouping,
                documentPitchPrior = documentLayoutBias,
            )
            if (groups.isEmpty()) return@withRecognition null
            // Optional manga-ocr refinement, post-layout so both entry points share it.
            // Crops come from `processed` (engine-input space, same as the group boxes),
            // so this runs inside the bracket, before withRecognition recycles it.
            // No-op if the model isn't loaded.
            val refined =
                if (refineWithMangaOcr) {
                    MangaOcrRefiner.refine(groups, rec.processed, sourceLang, logText = logGrouping)
                } else {
                    null
                }
            Output(
                groups = refined?.groups ?: groups,
                scaleFactor = rec.scaleFactor,
                backend = rec.backend,
                mangaOcrUsed = (refined?.decodedBlocks ?: 0) > 0,
            )
        }
}
