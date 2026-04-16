package com.playtranslate.language

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.TextRecognizerOptionsInterface
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Abstracts over an on-device OCR backend. Phase 1 only has [MlKitRecognizer]
 * for Japanese. Phases 3-5 will add the Latin/Chinese/Arabic backends —
 * each phase adds both the ML Kit (or Tesseract) dependency and a matching
 * `when` branch in [ScreenTextRecognizerFactory.create]. Phase 5 will also
 * replace the return type (currently ML Kit's [Text]) with an internal
 * `RecognizedText` model when Tesseract forces the redesign.
 */
interface ScreenTextRecognizer {
    suspend fun recognize(bitmap: Bitmap): Text
    fun close()
}

/**
 * Wraps an ML Kit [TextRecognizer] client. Thread-safe (ML Kit guarantees
 * internal thread-safety). Long-lived — one instance per backend, cached by
 * the factory's caller.
 */
class MlKitRecognizer(options: TextRecognizerOptionsInterface) : ScreenTextRecognizer {
    private val client: TextRecognizer = TextRecognition.getClient(options)

    override suspend fun recognize(bitmap: Bitmap): Text =
        suspendCancellableCoroutine { cont ->
            client.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

    override fun close() {
        client.close()
    }
}

/**
 * Builds a [ScreenTextRecognizer] for a given [OcrBackend]. Callers typically
 * cache the result keyed on the backend so one recognizer is reused across
 * captures.
 *
 * Phase 1 scope: only [OcrBackend.MLKitJapanese] is implemented. Every other
 * variant throws — the corresponding dependency is not yet on the classpath,
 * so adding real branches would be dead imports. Each later phase adds both
 * the dependency (e.g. `play-services-mlkit-text-recognition-chinese`) and
 * a real `when` branch below.
 */
object ScreenTextRecognizerFactory {
    fun create(backend: OcrBackend): ScreenTextRecognizer = when (backend) {
        OcrBackend.MLKitJapanese   -> MlKitRecognizer(JapaneseTextRecognizerOptions.Builder().build())
        OcrBackend.MLKitLatin      -> MlKitRecognizer(TextRecognizerOptions.DEFAULT_OPTIONS)
        OcrBackend.MLKitChinese    -> MlKitRecognizer(ChineseTextRecognizerOptions.Builder().build())
        OcrBackend.MLKitKorean     -> error("MLKitKorean not yet available (add play-services-mlkit-text-recognition-korean dependency)")
        OcrBackend.MLKitDevanagari -> error("MLKitDevanagari not yet available (add play-services-mlkit-text-recognition-devanagari dependency)")
        is OcrBackend.Tesseract    -> error("Tesseract OCR backend not yet implemented (Phase 5)")
    }
}
