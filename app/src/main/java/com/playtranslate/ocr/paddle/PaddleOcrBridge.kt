package com.playtranslate.ocr.paddle

import android.os.Process
import android.util.Log
import com.playtranslate.ocr.composites.DetectThenRecognize
import com.playtranslate.ocr.core.OcrEngine
import java.io.File

/**
 * Debug-only provider that lets the OCR pipeline run the experimental PaddleOCR
 * PP-OCRv5 mobile backend instead of ML Kit, behind the `Prefs.debugUsePaddleOcr`
 * toggle. NOT a shipped feature — see docs/paddleocr-spike-report.md (verdict:
 * NO-GO for production).
 *
 * Exposes [engineOrNull]: when active (toggle on, arm64, JA source, models
 * present) it returns a [DetectThenRecognize] engine ([PaddleDetector] +
 * [PaddleRecognizer]) over a shared [PaddleOcrSession]. That engine flows
 * through the SAME OcrPipeline + shared LayoutAnalyzer as any other engine — so
 * PaddleOCR now gets real paragraph grouping (no bespoke result assembly), the
 * architectural payoff of the refactor. [OcrEngineRegistry] consults this before
 * falling back to ML Kit; if anything is missing it returns null (never a crash).
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

    /** Mirrors Prefs.debugUsePaddleOcr; pushed at startup + on the Settings
     *  toggle, same idiom as OcrManager.debugLogGroupingEnabled. */
    @Volatile var enabled: Boolean = false

    /** When true, use the SERVER recognizer (rec_server.mnn) instead of mobile,
     *  keeping the mobile DETECTOR fixed — isolates "does the bigger recognizer
     *  read small kana better?" from detection differences. Debug A/B toggle;
     *  flipping it rebuilds the session + engine. See docs/paddleocr-kana-research.md. */
    @Volatile var useServerRec: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                // Force a rebuild on next use with the new recognizer tier.
                synchronized(this) { session?.close(); session = null; engine = null; triedInit = false }
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

    private val arm64: Boolean by lazy { Process.is64Bit() }

    @Volatile private var session: PaddleOcrSession? = null
    @Volatile private var engine: OcrEngine? = null
    @Volatile private var triedInit = false

    /** True only when the toggle is on, the device is arm64, and the JA source
     *  is selected. Cheap pre-check before touching the (lazy) session. */
    private fun activeFor(sourceLang: String): Boolean =
        enabled && arm64 && sourceLang.equals("ja", ignoreCase = true)

    /**
     * The PaddleOCR engine for [sourceLang], or null to fall back to ML Kit.
     * Returns a [DetectThenRecognize] over the shared session when the debug
     * path is active and models are present; cached alongside the session.
     */
    fun engineOrNull(sourceLang: String): OcrEngine? {
        if (!activeFor(sourceLang)) return null
        val s = sessionOrNull() ?: return null
        engine?.let { return it }
        return synchronized(this) {
            engine ?: DetectThenRecognize(PaddleDetector(s), PaddleRecognizer(s)).also { engine = it }
        }
    }

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
}
