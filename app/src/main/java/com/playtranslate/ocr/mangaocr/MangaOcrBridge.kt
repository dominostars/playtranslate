package com.playtranslate.ocr.mangaocr

import android.util.Log
import com.playtranslate.ocr.core.TextRecognizer
import java.io.File

/**
 * Provider for the manga-ocr recognizer (bake-off artifact). Owns one lazily-built
 * [MangaOcrSession] (encoder + decoder + vocab) and exposes its [TextRecognizer].
 * [modelDir] is set by the caller; missing model files → null → ML Kit fallback
 * (never crashes).
 *
 * NOT wired into production: manga-ocr is out of scope (see the approved OCR-engine
 * plan), so nothing currently calls this. Kept for the documented vertical-text
 * bake-off; safe to delete once the bake-off doc no longer needs a live reference.
 *
 * Model files: `encoder.mnn`, `decoder.mnn`, `vocab.txt` under [modelDir].
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
