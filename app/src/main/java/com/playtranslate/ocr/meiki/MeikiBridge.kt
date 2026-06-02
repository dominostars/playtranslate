package com.playtranslate.ocr.meiki

import android.content.Context
import android.util.Log
import com.playtranslate.ocr.composites.DetectThenRecognize
import com.playtranslate.ocr.core.OcrEngine
import com.playtranslate.ocr.registry.OcrPackModelHelper
import java.io.File

/**
 * Owner of Meiki's native MNN sessions (the lifecycle contract's "one owner").
 * Builds a [DetectThenRecognize] (Meiki detector + char-detection recognizer) over
 * a session loaded from an installed OCR pack dir, caching the engine+session by
 * pack key. Construction is lazy + read-only (safe any thread/time); sessions are
 * closed ONLY at the quiescent teardown via [close] — never on a selection switch
 * — so a live capture is never torn out from under itself.
 *
 * Pack layout (`noBackupFilesDir/models/<packKey>/`): `det.mnn`,
 * `rec_horizontal.mnn`, `rec_vertical.mnn`. Missing files → null → ML Kit floor.
 */
object MeikiBridge {

    private const val TAG = "MeikiBridge"

    private val sessions = HashMap<String, MeikiSession>()
    private val engines = HashMap<String, OcrEngine>()

    /** Cached engine for [packKey], or null if its pack files are absent. */
    @Synchronized
    fun engine(ctx: Context, packKey: String): OcrEngine? {
        engines[packKey]?.let { return it }
        val s = sessionFor(ctx, packKey) ?: return null
        return DetectThenRecognize(MeikiDetector(s), MeikiRecognizer(s)).also { engines[packKey] = it }
    }

    /** True if a live session for [packKey] is held (sweep must not delete its files). */
    @Synchronized
    fun isLoaded(packKey: String): Boolean = sessions.containsKey(packKey)

    private fun sessionFor(ctx: Context, packKey: String): MeikiSession? {
        sessions[packKey]?.let { return it }
        val dir = OcrPackModelHelper(packKey).file(ctx)
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
                .also { sessions[packKey] = it; Log.i(TAG, "Meiki session ready ($packKey)") }
        } catch (e: Throwable) {
            Log.e(TAG, "MeikiSession.create failed ($packKey) — using ML Kit", e); null
        }
    }

    /** Quiescent teardown only (no in-flight capture): close cached engines
     *  (recognizer Mat caches) + native sessions. */
    @Synchronized
    fun close() {
        engines.values.forEach { runCatching { it.close() } }; engines.clear()
        sessions.values.forEach { runCatching { it.close() } }; sessions.clear()
    }
}
