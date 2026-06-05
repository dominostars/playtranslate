package com.playtranslate.ocr.engines.mlkit

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizerOptionsInterface
import com.google.mlkit.vision.text.TextRecognizer as MlKitClient
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.ocr.core.OcrCapabilities
import com.playtranslate.ocr.core.OcrEngine
import com.playtranslate.ocr.core.OcrImage
import com.playtranslate.ocr.core.OcrOrientationSupport
import com.playtranslate.ocr.core.RecognizedRegion
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * End-to-end ML Kit OCR engine — the Composite pattern's "leaf": a single ML Kit
 * model both detects and recognizes, so it implements [OcrEngine] directly
 * (no detector/recognizer split). Wraps one long-lived, thread-safe ML Kit
 * [TextRecognizer] client and maps its output via [MlKitTextMapper].
 *
 * [OcrCapabilities.selfPreprocesses] is false: the pipeline applies the
 * `OcrPreprocessingRecipe` and hands this engine the preprocessed bitmap. The
 * regions returned are therefore in that (possibly upscaled) bitmap's space; the
 * pipeline normalizes them back to original-bitmap coordinates for the final
 * `OcrResult`.
 */
class MlKitOcr(options: TextRecognizerOptionsInterface) : OcrEngine {

    private val client: MlKitClient = TextRecognition.getClient(options)

    override val capabilities = OcrCapabilities(
        orientation = OcrOrientationSupport.BOTH,
        emitsCharBoxes = true,
        emitsElementBoxes = true,
        wholeRegionInput = false,
        threadSafe = true,
        selfPreprocesses = false,
        emitsSubLineBoxes = false,
    )

    override suspend fun recognize(image: OcrImage): List<RecognizedRegion> {
        val visionText = suspendCancellableCoroutine { cont ->
            client.process(InputImage.fromBitmap(image.bitmap, 0))
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        val addWordSpaces =
            SourceLanguageProfiles.forCode(image.sourceLang)?.wordsSeparatedByWhitespace ?: false
        return MlKitTextMapper.map(visionText, image.sourceLang, addWordSpaces)
    }

    override fun close() {
        client.close()
    }
}
