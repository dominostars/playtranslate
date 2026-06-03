package com.playtranslate.ocr.paddle

import android.content.Context
import android.util.Log
import com.playtranslate.language.PackIntegrity
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

    /** Close + drop the cached engine/session for a SINGLE [recPackKey], for the
     *  interactive pack delete (OcrModelManager.deleteOcrPack). No-op if none is
     *  held; unlike [close] it leaves every other pack's live session intact. */
    @Synchronized
    fun close(recPackKey: String) {
        engines.remove(recPackKey)?.let { runCatching { it.close() } }
        sessions.remove(recPackKey)?.let { runCatching { it.close() } }
    }

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
     *  loads from a path, not an asset stream). Null if the asset is missing or the
     *  copy fails. Runs under the object monitor (callers are @Synchronized), so the
     *  copy is single-flight. Existence is trustworthy because [copyBundledDetector]
     *  commits atomically — the final path only ever appears as a COMPLETE copy. */
    private fun bundledDetector(ctx: Context): File? {
        detFile?.let { if (it.exists()) return it }
        val out = File(ctx.noBackupFilesDir, "ocr/paddle_det.mnn").apply { parentFile?.mkdirs() }
        if (!out.exists() && !copyBundledDetector(ctx, out)) return null
        detFile = out
        return out
    }

    /** Stream the bundled detector asset into a sibling `.tmp`, then atomic-rename
     *  onto [out] via [PackIntegrity.atomicReplace] — the same commit primitive
     *  every DOWNLOADED model file lands through. A process death / storage-full /
     *  mid-copy throw leaves only the `.tmp` (deleted here), so [out] never holds a
     *  truncated detector that the existence check would later trust and feed to a
     *  failing `PaddleOcrSession.create` — which would silently fall back to ML Kit
     *  with no repair path. Returns true iff [out] now holds the complete asset.
     *  No SHA/length check: it's a bundled asset (no transport corruption) and a
     *  non-throwing `copyTo` reads to EOF, so the atomic rename alone guarantees a
     *  complete file. */
    private fun copyBundledDetector(ctx: Context, out: File): Boolean {
        val tmp = File(out.parentFile, "paddle_det.mnn.tmp")
        return try {
            ctx.assets.open(BUNDLED_DET_ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            PackIntegrity.atomicReplace(tmp, out)
            true
        } catch (e: Throwable) {
            tmp.delete()
            Log.e(TAG, "bundled $BUNDLED_DET_ASSET copy failed — using ML Kit", e)
            false
        }
    }

    @Synchronized
    fun close() {
        engines.values.forEach { runCatching { it.close() } }; engines.clear()
        sessions.values.forEach { runCatching { it.close() } }; sessions.clear()
    }
}
