package com.playtranslate.ocr.registry

import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.playtranslate.language.OcrBackend
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.ocr.core.OcrEngine
import com.playtranslate.ocr.engines.mlkit.MlKitOcr
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds and caches the [OcrEngine] for a source language — the named factory
 * that "recipe" collapses into. Each source language maps (via its
 * `SourceLanguageProfile.ocrBackend`) to a constructed engine; one engine per
 * backend, cached and reused. [closeAll] releases them (called from
 * `OcrManager.releaseAll` at TRIM_MEMORY_COMPLETE).
 *
 * Replaces the former `ScreenTextRecognizerFactory`. As detector+recognizer
 * engines land (PaddleOCR via DetectThenRecognize, Meiki, manga-ocr), their
 * composed [OcrEngine] trees are constructed here.
 */
class OcrEngineRegistry {

    private val engines = ConcurrentHashMap<OcrBackend, OcrEngine>()

    fun engineFor(sourceLang: String): OcrEngine {
        // Debug-only engine override (Settings "OCR engine" picker: PaddleOCR /
        // Meiki / Meiki→Manga / Paddle→Manga). OcrEngineSelection owns the chosen
        // composite's lifecycle, so it is NOT cached here. Falls through to the
        // production ML Kit backend when DEFAULT / unavailable.
        OcrEngineSelection.debugEngineOrNull(sourceLang)?.let { return it }
        val profile = SourceLanguageProfiles.forCode(sourceLang)
            ?: SourceLanguageProfiles[SourceLangId.JA]
        return engines.getOrPut(profile.ocrBackend) { create(profile.ocrBackend) }
    }

    /** Close + drop every cached engine. Caller must guarantee no in-flight OCR. */
    fun closeAll() {
        val snapshot = engines.keys.toList()
        for (backend in snapshot) {
            engines.remove(backend)?.close()
        }
        OcrEngineSelection.closeAll()
    }

    private fun create(backend: OcrBackend): OcrEngine = when (backend) {
        OcrBackend.MLKitJapanese -> MlKitOcr(JapaneseTextRecognizerOptions.Builder().build())
        OcrBackend.MLKitLatin -> MlKitOcr(TextRecognizerOptions.DEFAULT_OPTIONS)
        OcrBackend.MLKitChinese -> MlKitOcr(ChineseTextRecognizerOptions.Builder().build())
        OcrBackend.MLKitKorean -> MlKitOcr(KoreanTextRecognizerOptions.Builder().build())
        OcrBackend.MLKitDevanagari -> error("MLKitDevanagari not yet available (add play-services-mlkit-text-recognition-devanagari dependency)")
        is OcrBackend.Tesseract -> error("Tesseract OCR backend not yet implemented (Phase 5)")
    }
}
