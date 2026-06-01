package com.playtranslate.ocr.paddle

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Process
import android.util.Log
import com.playtranslate.OcrManager
import com.playtranslate.language.TextAlignment
import com.playtranslate.language.TextOrientation
import com.playtranslate.model.TextSegment
import java.io.File

/**
 * Debug-only bridge that lets the live OCR pipeline run the experimental
 * PaddleOCR PP-OCRv5 mobile backend instead of ML Kit, behind the
 * `Prefs.debugUsePaddleOcr` toggle. NOT a shipped feature — see
 * docs/paddleocr-spike-report.md (verdict: NO-GO for production).
 *
 * All PaddleOCR glue is isolated here so [OcrManager] only needs a one-line
 * early-return at the top of each entry point. Deleting this file + those two
 * call sites fully removes the integration.
 *
 * ## Scope (quick manual-test build)
 * - Japanese only (the general rec covers JA; gated in [OcrManager]).
 * - PaddleOCR does its OWN detection + region→reading-order assembly
 *   ([PaddleOcrSession.detectAndRecognize]); we do NOT route it through the
 *   ML Kit grouping. One OCR "group"/"line" per PP region.
 * - No per-symbol boxes: emitted [OcrManager.OcrLine]s carry `symbols = []`,
 *   so drag-to-lookup falls back to proportional char-width splitting
 *   (already implemented in DragLookupController.findClosestToken).
 *
 * ## Lifecycle
 * Process-wide singleton mirroring [OcrManager]'s Context-free design. The
 * model directory is pushed in once at startup ([modelDir]) from
 * PlayTranslateApplication, which has a Context; the [PaddleOcrSession] is then
 * built lazily on first use and reused. If the toggle is off, the device isn't
 * arm64, or the model files are absent, [maybeRecognise]/[maybeRecogniseLines]
 * return null and the caller proceeds with ML Kit — never a crash.
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
     *  flipping it rebuilds the session. See docs/paddleocr-kana-research.md. */
    @Volatile var useServerRec: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                // Force a rebuild on next use with the new recognizer tier.
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

    private val arm64: Boolean by lazy { Process.is64Bit() }

    @Volatile private var session: PaddleOcrSession? = null
    @Volatile private var triedInit = false

    /** True only when the toggle is on, the device is arm64, and the JA source
     *  is selected. Cheap pre-check before touching the (lazy) session. */
    private fun activeFor(sourceLang: String): Boolean =
        enabled && arm64 && sourceLang.equals("ja", ignoreCase = true)

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

    /**
     * If the PaddleOCR path is active and available, recognize [bitmap] and
     * build a minimal [OcrManager.OcrResult]; otherwise return null so the
     * caller runs ML Kit. [bitmap] is the ORIGINAL capture (PaddleOcrSession
     * does its own preprocessing) — ownership stays with the caller; we do not
     * recycle it.
     */
    fun maybeRecognise(bitmap: Bitmap, sourceLang: String): OcrManager.OcrResult? {
        if (!activeFor(sourceLang)) return null
        val s = sessionOrNull() ?: return null
        return try {
            val r = s.detectAndRecognize(bitmap)
            if (r.regions.isEmpty()) {
                Log.d(TAG, "PaddleOCR found no text")
                return null
            }
            Log.i(TAG, "PaddleOCR: ${r.regions.size} regions, det=${r.detMs}ms rec=${r.recMs}ms")
            buildResult(r.regions)
        } catch (e: Throwable) {
            Log.e(TAG, "PaddleOCR recognise failed — using ML Kit", e)
            null
        }
    }

    /** Drag-to-lookup variant: minimal [OcrManager.OcrLine] list, one per PP
     *  region, `symbols` empty (proportional fallback handles hit-testing). */
    fun maybeRecogniseLines(bitmap: Bitmap, sourceLang: String): List<OcrManager.OcrLine>? {
        if (!activeFor(sourceLang)) return null
        val s = sessionOrNull() ?: return null
        return try {
            val r = s.detectAndRecognize(bitmap)
            if (r.regions.isEmpty()) return null
            r.regions.mapIndexed { i, region ->
                OcrManager.OcrLine(
                    text = region.text,
                    bounds = region.box,
                    groupIndex = i,
                    groupText = region.text,
                    symbols = emptyList(),
                    orientation = orientationOf(region.box),
                )
            }.ifEmpty { null }
        } catch (e: Throwable) {
            Log.e(TAG, "PaddleOCR recogniseWithPositions failed — using ML Kit", e)
            null
        }
    }

    // ── result assembly ──────────────────────────────────────────────────────

    /** One OCR group per PP region — the simplest faithful mapping for the
     *  quick build (no cross-region paragraph grouping). */
    private fun buildResult(regions: List<PaddleOcrSession.Region>): OcrManager.OcrResult {
        val segments = mutableListOf<TextSegment>()
        val groupTexts = mutableListOf<String>()
        val groupBounds = mutableListOf<Rect>()
        val groupLineCounts = mutableListOf<Int>()
        val groupOrientations = mutableListOf<TextOrientation>()
        val groupAlignments = mutableListOf<TextAlignment>()
        val lineBoxes = mutableListOf<OcrManager.LineBox>()
        val full = StringBuilder()

        regions.forEachIndexed { i, region ->
            if (i > 0) {
                full.append(" ")
                segments += TextSegment("\n\n", isSeparator = true)
            }
            full.append(region.text)
            segments += TextSegment(region.text)
            groupTexts += region.text
            groupBounds += region.box
            groupLineCounts += 1
            groupOrientations += orientationOf(region.box)
            groupAlignments += TextAlignment.LEFT
            lineBoxes += OcrManager.LineBox(
                text = region.text,
                bounds = region.box,
                groupIndex = i,
                symbols = emptyList(),
                orientation = orientationOf(region.box),
            )
        }

        return OcrManager.OcrResult(
            fullText = full.toString().trim(),
            segments = segments,
            groupTexts = groupTexts,
            groupBounds = groupBounds,
            groupLineCounts = groupLineCounts,
            lineBoxes = lineBoxes,
            debugBoxes = null,
            groupOrientations = groupOrientations,
            groupAlignments = groupAlignments,
        )
    }

    /** Tall-and-narrow region → vertical (tategaki); else horizontal. PP's
     *  warpCrop already rotates vertical crops, so this only drives downstream
     *  orientation handling (furigana side, reading hints). */
    private fun orientationOf(box: Rect): TextOrientation =
        if (box.height() > box.width() * 1.5) TextOrientation.VERTICAL
        else TextOrientation.HORIZONTAL
}
