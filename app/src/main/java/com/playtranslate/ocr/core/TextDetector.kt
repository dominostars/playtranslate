package com.playtranslate.ocr.core

import java.io.Closeable

/**
 * Finds text regions in an image without recognizing their content. One half of
 * a [com.playtranslate.ocr.composites.DetectThenRecognize] composite (the other
 * is [TextRecognizer]). Examples: PaddleOCR DBNet, Meiki / comic-text-detector.
 *
 * Returns [DetectedRegion]s in ORIGINAL input-bitmap coordinates, optionally
 * carrying a deskew [DetectedRegion.quad] the recognizer can warp-crop along.
 */
interface TextDetector : Closeable {
    val capabilities: OcrCapabilities

    suspend fun detect(image: OcrImage): List<DetectedRegion>
}
