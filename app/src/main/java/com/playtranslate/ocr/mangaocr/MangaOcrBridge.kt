package com.playtranslate.ocr.mangaocr

import android.util.Log
import com.playtranslate.ocr.core.TextRecognizer
import java.io.File

/**
 * Debug-only provider for the manga-ocr recognizer, mirroring
 * [com.playtranslate.ocr.paddle.PaddleOcrBridge]. Owns one lazily-built
 * [MangaOcrSession] (encoder + decoder + vocab) and exposes its [TextRecognizer]
 * so [com.playtranslate.ocr.registry.OcrEngineSelection] can compose it with a
 * detector (`Meiki→Manga` or `Paddle→Manga`). [modelDir] is pushed at startup;
 * missing model files → null → ML Kit fallback (never crashes).
 *
 * Model files (pushed to `<externalFilesDir>/mangaocr_models/`): `encoder.mnn`,
 * `decoder.mnn`, `vocab.txt`.
 */
object MangaOcrBridge {

    private const val TAG = "MangaOcrBridge"

    @Volatile var modelDir: File? = null

    @Volatile private var session: MangaOcrSession? = null
    @Volatile private var triedInit = false

    fun recognizerOrNull(): TextRecognizer? = sessionOrNull()?.let { MangaOcrRecognizer(it) }

    @Synchronized
    private fun sessionOrNull(): MangaOcrSession? {
        session?.let { return it }
        if (triedInit) return null
        triedInit = true
        val dir = modelDir ?: run { Log.w(TAG, "no modelDir pushed"); return null }
        val enc = File(dir, "encoder.mnn")
        val dec = File(dir, "decoder.mnn")
        val vocab = File(dir, "vocab.txt")
        if (!enc.exists() || !dec.exists() || !vocab.exists()) {
            Log.w(TAG, "models missing in ${dir.absolutePath} " +
                "(enc=${enc.exists()} dec=${dec.exists()} vocab=${vocab.exists()}) — using ML Kit")
            return null
        }
        return try {
            MangaOcrSession.create(enc.absolutePath, dec.absolutePath, vocab.absolutePath)
                .also { session = it; Log.i(TAG, "manga-ocr session ready") }
        } catch (e: Throwable) {
            Log.e(TAG, "MangaOcrSession.create failed — using ML Kit", e)
            null
        }
    }

    @Synchronized
    fun close() {
        session?.close(); session = null; triedInit = false
    }
}
