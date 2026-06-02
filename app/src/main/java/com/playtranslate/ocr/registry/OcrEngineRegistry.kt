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
        // Production: the user's chosen Meiki/Paddle engine if its pack is
        // installed — built + owned by the bridge (NOT cached here, so a selection
        // switch never closes a live session out from under a capture). Falls
        // through to the ML Kit floor (cached; thread-safe, no native teardown).
        OcrModelManager.engineForSelected(sourceLang)?.let { return it }
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
        OcrModelManager.closeAll()
    }

    private fun create(backend: OcrBackend): OcrEngine = when (backend) {
        OcrBackend.MLKitJapanese -> MlKitOcr(JapaneseTextRecognizerOptions.Builder().build())
        OcrBackend.MLKitLatin -> MlKitOcr(TextRecognizerOptions.DEFAULT_OPTIONS)
        OcrBackend.MLKitChinese -> MlKitOcr(ChineseTextRecognizerOptions.Builder().build())
        OcrBackend.MLKitKorean -> MlKitOcr(KoreanTextRecognizerOptions.Builder().build())
        OcrBackend.MLKitDevanagari -> error("MLKitDevanagari not yet available (add play-services-mlkit-text-recognition-devanagari dependency)")
        is OcrBackend.Tesseract -> error("Tesseract OCR backend not yet implemented (Phase 5)")
        // Meiki/Paddle are detector+recognizer composites built via their bridges
        // in engineFor (Phase 2 resolution), never through this ML-Kit factory.
        is OcrBackend.Meiki -> error("Meiki built via MeikiBridge in engineFor, not create()")
        is OcrBackend.Paddle -> error("Paddle built via PaddleOcrBridge in engineFor, not create()")
    }
}
