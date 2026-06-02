package com.playtranslate.ocr.paddle

import android.util.Log
import com.playtranslate.ocr.composites.DetectThenRecognize
import com.playtranslate.ocr.core.OcrEngine
import com.playtranslate.ocr.core.TextDetector
import java.io.File

/**
 * Debug-only provider for the experimental PaddleOCR PP-OCRv5 backend, selected
 * via the Settings "OCR engine" picker. NOT a shipped feature — see
 * docs/paddleocr-spike-report.md (verdict: NO-GO for production).
 *
 * Exposes building blocks for [com.playtranslate.ocr.registry.OcrEngineSelection]:
 * [engineOrNull] (full [DetectThenRecognize] = [PaddleDetector] + [PaddleRecognizer]
 * over a shared [PaddleOcrSession]) and [detectorOrNull] (the detector alone, for
 * the `Paddle→Manga` combination). arm64 / JA / which-engine-is-selected gating
 * lives in [com.playtranslate.ocr.registry.OcrEngineSelection]; here we only build
 * from the session and return null when models are absent (never a crash).
 *
 * ## Lifecycle
 * Process-wide singleton mirroring [com.playtranslate.OcrManager]'s Context-free
 * design. [modelDir] is pushed once at startup from PlayTranslateApplication
 * (which has a Context); the session + engine are built lazily on first use and
 * reused (rebuilt when [useServerRec] flips). Deleting this file + the
 * [engineOrNull] call in OcrEngineRegistry fully removes the integration.
 */
object PaddleOcrBridge {

    private const val TAG = "PaddleOcrBridge"

    /** Pushed from PlayTranslateApplication (which has a Context). When null,
     *  the bridge is inert. Re-derives the crop-dump dir so startup order
     *  (modelDir vs dumpCrops) doesn't matter. */
    @Volatile var modelDir: File? = null
        set(value) {
            field = value
            if (dumpCrops) PaddleOcrSession.dumpDir = value?.parentFile?.let { File(it, "paddle_crops") }
        }

    /** When true, use the SERVER recognizer (rec_server.mnn) instead of mobile,
     *  keeping the mobile DETECTOR fixed — isolates "does the bigger recognizer
     *  read small kana better?" from detection differences. Debug A/B toggle;
     *  flipping it rebuilds the session + engine. See docs/paddleocr-kana-research.md. */
    @Volatile var useServerRec: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                // Force a rebuild on next use with the new recognizer tier.
                // (OcrEngineSelection.invalidate() drops its cached composite.)
                synchronized(this) { session?.close(); session = null; triedInit = false }
            }
        }

    /** When true, dump every pre-recognition crop to `<modelDir>/../paddle_crops/`
     *  (see PaddleOcrSession.dumpCrop). Diagnostic for the vertical-rotation
     *  hypothesis; pulled via adb. Pushed from the debug toggle + startup. */
    @Volatile var dumpCrops: Boolean = false
        set(value) {
            field = value
            PaddleOcrSession.dumpDir =
                if (value) modelDir?.parentFile?.let { File(it, "paddle_crops") } else null
        }

    @Volatile private var session: PaddleOcrSession? = null
    @Volatile private var triedInit = false

    /** PaddleOCR detector building block (for the `Paddle→Manga` combination), or
     *  null when models are absent. Gating lives in [OcrEngineSelection]. */
    fun detectorOrNull(): TextDetector? = sessionOrNull()?.let { PaddleDetector(it) }

    /**
     * The full PaddleOCR engine ([DetectThenRecognize] over the shared session),
     * or null when models are absent. Gating (arm64/JA/selected engine) lives in
     * [OcrEngineSelection], which also caches the result.
     */
    fun engineOrNull(): OcrEngine? =
        sessionOrNull()?.let { DetectThenRecognize(PaddleDetector(it), PaddleRecognizer(it)) }

    /** Lazily build (once) the PP session from the pushed model dir. Returns
     *  null and logs if the dir/models are missing — caller falls back. */
    @Synchronized
    private fun sessionOrNull(): PaddleOcrSession? {
        session?.let { return it }
        if (triedInit) return null   // already failed; don't retry every capture
        triedInit = true
        val dir = modelDir ?: run { Log.w(TAG, "no modelDir pushed"); return null }
        // Detector stays mobile to isolate the recognizer variable; only the
        // recognizer swaps to the server tier when useServerRec is set.
        val det = File(dir, "det_mobile.mnn")
        val recName = if (useServerRec) "rec_server.mnn" else "rec_mobile.mnn"
        val rec = File(dir, recName)
        val keys = File(dir, "keys.txt")
        if (!det.exists() || !rec.exists() || !keys.exists()) {
            Log.w(TAG, "models missing in ${dir.absolutePath} " +
                "(det=${det.exists()} rec[$recName]=${rec.exists()} keys=${keys.exists()}) — using ML Kit")
            return null
        }
        return try {
            PaddleOcrSession.create(det.absolutePath, rec.absolutePath, keys.absolutePath)
                .also { session = it; Log.i(TAG, "PaddleOCR session ready (det=mobile rec=${if (useServerRec) "server" else "mobile"})") }
        } catch (e: Throwable) {
            Log.e(TAG, "PaddleOcrSession.create failed — using ML Kit", e)
            null
        }
    }

    @Synchronized
    fun close() {
        session?.close(); session = null; triedInit = false
    }
}
