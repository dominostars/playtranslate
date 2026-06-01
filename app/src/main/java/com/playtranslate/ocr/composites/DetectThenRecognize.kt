package com.playtranslate.ocr.composites

import com.playtranslate.ocr.core.DetectedRegion
import com.playtranslate.ocr.core.OcrCapabilities
import com.playtranslate.ocr.core.OcrEngine
import com.playtranslate.ocr.core.OcrImage
import com.playtranslate.ocr.core.RecognizedRegion
import com.playtranslate.ocr.core.TextDetector
import com.playtranslate.ocr.core.TextRecognizer
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/**
 * Composite [OcrEngine] = a [TextDetector] feeding a [TextRecognizer]. The
 * "separated" engine that implements the SAME interface as a single-model leaf
 * (e.g. MlKitOcr) — so the pipeline treats both uniformly. "PaddleOCR" =
 * `DetectThenRecognize(PaddleDetector, PaddleRecognizer)`.
 *
 * Capabilities compose from the children PER TYPE (not a blanket AND): the
 * recognizer drives orientation / char-box / element-box / whole-region;
 * [OcrCapabilities.threadSafe] is the AND (a non-thread-safe child — e.g. an MNN
 * session — makes the whole subtree non-thread-safe); [OcrCapabilities.selfPreprocesses]
 * is the detector's (it sees the pipeline image first). A non-thread-safe subtree
 * is serialized with a [Mutex] (also guards close() against an in-flight run).
 *
 * Cancellation is checked after detection and before each per-region recognize,
 * so a superseded frame stops issuing recognizer calls promptly (the underlying
 * native calls are themselves non-interruptible).
 */
class DetectThenRecognize(
    private val detector: TextDetector,
    private val recognizer: TextRecognizer,
) : OcrEngine {

    override val capabilities = OcrCapabilities(
        orientation = recognizer.capabilities.orientation,
        emitsCharBoxes = recognizer.capabilities.emitsCharBoxes,
        emitsElementBoxes = recognizer.capabilities.emitsElementBoxes,
        wholeRegionInput = recognizer.capabilities.wholeRegionInput,
        threadSafe = detector.capabilities.threadSafe && recognizer.capabilities.threadSafe,
        selfPreprocesses = detector.capabilities.selfPreprocesses,
    )

    private val mutex: Mutex? = if (capabilities.threadSafe) null else Mutex()

    override suspend fun recognize(image: OcrImage): List<RecognizedRegion> {
        val m = mutex
        return if (m != null) m.withLock { runStages(image) } else runStages(image)
    }

    private suspend fun runStages(image: OcrImage): List<RecognizedRegion> {
        val detected: List<DetectedRegion> = detector.detect(image)
        coroutineContext.ensureActive()
        // A whole-region recognizer (capabilities.wholeRegionInput) would cluster
        // detector boxes into bubble crops here via the shared LayoutAnalyzer; no
        // in-scope engine needs that yet, so detector regions feed 1:1.
        val out = ArrayList<RecognizedRegion>(detected.size)
        for (region in detected) {
            coroutineContext.ensureActive()
            recognizer.recognize(image, region)?.let { out += it }
        }
        return out
    }

    override fun close() {
        // Serialize teardown against any in-flight run for a non-thread-safe
        // subtree (closing an MNN session mid-run is a native use-after-free).
        // close() isn't suspend, so the caller must already guarantee no
        // concurrent run (OcrManager.releaseAll only runs at TRIM_MEMORY_COMPLETE);
        // children additionally guard their own native handles.
        detector.close()
        recognizer.close()
    }
}
