package com.playtranslate.ocr.meiki

import android.util.Log
import com.playtranslate.ocr.core.TextDetector
import com.playtranslate.ocr.core.TextRecognizer
import java.io.File

/**
 * Debug-only provider for the Meiki engine, mirroring
 * [com.playtranslate.ocr.paddle.PaddleOcrBridge]. Owns one lazily-built
 * [MeikiSession] (det + horizontal rec + vertical rec) and exposes its
 * [TextDetector] / [TextRecognizer] building blocks so [com.playtranslate.ocr.registry.OcrEngineSelection]
 * can compose `Meiki` (Meiki det + Meiki rec) and `Meiki→Manga` (Meiki det +
 * manga-ocr rec). [modelDir] is pushed at startup from PlayTranslateApplication;
 * missing model files → null → ML Kit fallback (never crashes).
 *
 * Model files (pushed to `<externalFilesDir>/meiki_models/`): `det.mnn`,
 * `rec_horizontal.mnn`, `rec_vertical.mnn`.
 */
object MeikiBridge {

    private const val TAG = "MeikiBridge"

    @Volatile var modelDir: File? = null

    @Volatile private var session: MeikiSession? = null
    @Volatile private var triedInit = false

    fun detectorOrNull(): TextDetector? = sessionOrNull()?.let { MeikiDetector(it) }
    fun recognizerOrNull(): TextRecognizer? = sessionOrNull()?.let { MeikiRecognizer(it) }

    @Synchronized
    private fun sessionOrNull(): MeikiSession? {
        session?.let { return it }
        if (triedInit) return null
        triedInit = true
        val dir = modelDir ?: run { Log.w(TAG, "no modelDir pushed"); return null }
        val det = File(dir, "det.mnn")
        val recH = File(dir, "rec_horizontal.mnn")
        val recV = File(dir, "rec_vertical.mnn")
        if (!det.exists() || !recH.exists() || !recV.exists()) {
            Log.w(TAG, "models missing in ${dir.absolutePath} " +
                "(det=${det.exists()} recH=${recH.exists()} recV=${recV.exists()}) — using ML Kit")
            return null
        }
        return try {
            MeikiSession.create(det.absolutePath, recH.absolutePath, recV.absolutePath)
                .also { session = it; Log.i(TAG, "Meiki session ready") }
        } catch (e: Throwable) {
            Log.e(TAG, "MeikiSession.create failed — using ML Kit", e)
            null
        }
    }

    @Synchronized
    fun close() {
        session?.close(); session = null; triedInit = false
    }
}
