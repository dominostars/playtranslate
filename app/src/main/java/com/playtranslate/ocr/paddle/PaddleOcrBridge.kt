package com.playtranslate.ocr.paddle

import android.content.Context
import android.util.Log
import com.playtranslate.ocr.composites.DetectThenRecognize
import com.playtranslate.ocr.core.OcrEngine
import com.playtranslate.ocr.registry.OcrPackModelHelper
import java.io.File

/**
 * Owner of PaddleOCR's native MNN sessions (the lifecycle contract's "one
 * owner"). Builds a [DetectThenRecognize] (Paddle detector + recognizer) over a
 * session that pairs the **APK-bundled shared detector** with the selected
 * language's **recognizer pack**, caching engine+session by recognizer pack key.
 * Construction is lazy + read-only; sessions close ONLY at the quiescent teardown
 * via [close], never on a selection switch.
 *
 * Detector: bundled `assets/ocr/paddle_det.mnn`, copied once to
 * `noBackupFilesDir/ocr/`. Recognizer pack (`noBackupFilesDir/models/<recPackKey>/`):
 * `rec.mnn` + `keys.txt`. Missing files → null → ML Kit floor.
 */
object PaddleOcrBridge {

    private const val TAG = "PaddleOcrBridge"
    private const val BUNDLED_DET_ASSET = "ocr/paddle_det.mnn"

    private val sessions = HashMap<String, PaddleOcrSession>()
    private val engines = HashMap<String, OcrEngine>()
    @Volatile private var detFile: File? = null

    /** Cached engine for recognizer [recPackKey], or null if det/rec files absent. */
    @Synchronized
    fun engine(ctx: Context, recPackKey: String): OcrEngine? {
        engines[recPackKey]?.let { return it }
        val s = sessionFor(ctx, recPackKey) ?: return null
        return DetectThenRecognize(PaddleDetector(s), PaddleRecognizer(s)).also { engines[recPackKey] = it }
    }

    @Synchronized
    fun isLoaded(recPackKey: String): Boolean = sessions.containsKey(recPackKey)

    private fun sessionFor(ctx: Context, recPackKey: String): PaddleOcrSession? {
        sessions[recPackKey]?.let { return it }
        val det = bundledDetector(ctx) ?: return null
        val dir = OcrPackModelHelper(recPackKey).file(ctx)
        val rec = File(dir, "rec.mnn")
        val keys = File(dir, "keys.txt")
        if (!rec.exists() || !keys.exists()) {
            Log.w(TAG, "rec pack incomplete in ${dir.absolutePath} " +
                "(rec=${rec.exists()} keys=${keys.exists()}) — using ML Kit")
            return null
        }
        return try {
            PaddleOcrSession.create(det.absolutePath, rec.absolutePath, keys.absolutePath)
                .also { sessions[recPackKey] = it; Log.i(TAG, "Paddle session ready ($recPackKey)") }
        } catch (e: Throwable) {
            Log.e(TAG, "PaddleOcrSession.create failed ($recPackKey) — using ML Kit", e); null
        }
    }

    /** The bundled detector, copied from assets to a real file path once (MNN
     *  loads from a path, not an asset stream). Null if the asset is missing. */
    private fun bundledDetector(ctx: Context): File? {
        detFile?.let { if (it.exists()) return it }
        val out = File(ctx.noBackupFilesDir, "ocr/paddle_det.mnn").apply { parentFile?.mkdirs() }
        if (!out.exists()) {
            try {
                ctx.assets.open(BUNDLED_DET_ASSET).use { input -> out.outputStream().use { input.copyTo(it) } }
            } catch (e: Throwable) {
                Log.e(TAG, "bundled $BUNDLED_DET_ASSET missing — using ML Kit", e); return null
            }
        }
        detFile = out
        return out
    }

    @Synchronized
    fun close() {
        engines.values.forEach { runCatching { it.close() } }; engines.clear()
        sessions.values.forEach { runCatching { it.close() } }; sessions.clear()
    }
}
