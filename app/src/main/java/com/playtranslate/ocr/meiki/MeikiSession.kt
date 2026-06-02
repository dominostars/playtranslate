package com.playtranslate.ocr.meiki

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.playtranslate.mnn.MnnInterpreter
import com.playtranslate.mnn.MnnInterpreter.NamedTensor
import com.playtranslate.mnn.MnnInterpreter.TensorData
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Meiki OCR pipeline driver (D-FINE detector + D-FINE character-detection
 * recognizers), the Meiki counterpart to [com.playtranslate.ocr.paddle.PaddleOcrSession].
 *
 * Holds three [MnnInterpreter] sessions — one detector and two recognizers
 * (horizontal 960×32, vertical 32×480) — converted from the official ONNX to MNN.
 * Unlike PaddleOCR these are **multi-IO** models (`images` + int32
 * `orig_target_sizes` → `char_codes`/`boxes`/`scores`), so they run through
 * [MnnInterpreter.run] (List<NamedTensor>). All pre/post-processing lives here and
 * is a faithful port of the desktop spike (`/tmp/ocr_bakeoff/run_bakeoff.py` +
 * the models' shipped `inference*.py`): BGR /255 input; D-FINE char outputs are
 * **Unicode codepoints** (`Character.toChars`); reading-axis overlap dedup + sort.
 *
 * CRITICAL: `orig_target_sizes` MUST be int32 `[W, H]` — int64 (or the wrong axis)
 * zeroes the box height scaling (verified in the spike). Coordinates returned are
 * crop-local for [recognize] and original-bitmap for [detect]; the engine adapters
 * ([MeikiRecognizer]) offset crop-local boxes into original space.
 *
 * Not thread-safe (one Session each); serialize via the composite Mutex.
 */
class MeikiSession private constructor(
    private val det: MnnInterpreter,
    private val recH: MnnInterpreter,
    private val recV: MnnInterpreter,
) : Closeable {

    /** A detected text region: AABB in ORIGINAL-bitmap coords + detector score. */
    data class DetBox(val rect: Rect, val score: Float)

    /** One recognized character: [rect] in CROP-LOCAL coords, [offset] into [RecResult.text]. */
    data class CharHit(val text: String, val rect: Rect, val offset: Int)

    /** Recognizer output for one crop. [chars] boxes are CROP-LOCAL. */
    data class RecResult(val text: String, val chars: List<CharHit>, val confidence: Float)

    // ── Detection ────────────────────────────────────────────────────────────

    /** Run the D-FINE detector on [bitmap]; return text-region AABBs in
     *  ORIGINAL-bitmap coords (boxes are produced in the stretched DET_W×DET_H
     *  space and mapped back by the independent x/y ratios, like the spike). */
    fun detect(bitmap: Bitmap): List<DetBox> {
        val scaled = Bitmap.createScaledBitmap(bitmap, DET_W, DET_H, true)
        val px = IntArray(DET_W * DET_H)
        scaled.getPixels(px, 0, DET_W, 0, 0, DET_W, DET_H)
        if (scaled != bitmap) scaled.recycle()
        // NCHW, BGR, /255 (Meiki trained on cv2/BGR; no mean-std).
        val input = FloatArray(3 * DET_W * DET_H)
        val plane = DET_W * DET_H
        for (i in 0 until plane) {
            val p = px[i]
            input[i] = (p and 0xff) / 255f                 // B
            input[plane + i] = ((p ushr 8) and 0xff) / 255f  // G
            input[2 * plane + i] = ((p ushr 16) and 0xff) / 255f // R
        }
        val outs = det.run(listOf(
            NamedTensor("images", intArrayOf(1, 3, DET_H, DET_W), TensorData.Floats(input)),
            NamedTensor("orig_target_sizes", intArrayOf(1, 2), TensorData.Ints(intArrayOf(DET_W, DET_H))),
        ))
        val boxes = boxesOf(outs)
        val scores = scoresOf(outs)
        val sx = bitmap.width.toFloat() / DET_W
        val sy = bitmap.height.toFloat() / DET_H
        val out = ArrayList<DetBox>(scores.size)
        for (i in scores.indices) {
            if (scores[i] < DET_CONF) continue
            val x1 = (boxes[i * 4] * sx).roundToInt().coerceIn(0, bitmap.width - 1)
            val y1 = (boxes[i * 4 + 1] * sy).roundToInt().coerceIn(0, bitmap.height - 1)
            val x2 = (boxes[i * 4 + 2] * sx).roundToInt().coerceIn(1, bitmap.width)
            val y2 = (boxes[i * 4 + 3] * sy).roundToInt().coerceIn(1, bitmap.height)
            if (x2 - x1 >= 2 && y2 - y1 >= 2) out += DetBox(Rect(x1, y1, x2, y2), scores[i])
        }
        return out
    }

    // ── Recognition ──────────────────────────────────────────────────────────

    /** Recognize one BGR crop [cropBgr] with the [vertical] (32×480) or horizontal
     *  (960×32) model. Returns text + per-char boxes in CROP-LOCAL coords. */
    fun recognize(cropBgr: Mat, vertical: Boolean): RecResult {
        val cropW = cropBgr.cols(); val cropH = cropBgr.rows()
        if (cropW < 2 || cropH < 2) return RecResult("", emptyList(), 0f)
        val iw = if (vertical) REC_V_W else REC_H_W
        val ih = if (vertical) REC_V_H else REC_H_H

        // Aspect-preserving resize into iw×ih, content anchored top-left, rest 0.
        var newW: Int; var newH: Int
        if (vertical) {
            newW = iw; newH = max(1, (cropH * (iw.toDouble() / cropW)).roundToInt())
            if (newH > ih) { newH = ih; newW = max(1, (cropW * (ih.toDouble() / cropH)).roundToInt()) }
        } else {
            newH = ih; newW = max(1, (cropW * (ih.toDouble() / cropH)).roundToInt())
            if (newW > iw) { newW = iw; newH = max(1, (cropH * (iw.toDouble() / cropW)).roundToInt()) }
        }
        val resized = Mat()
        val interp = if (newH < cropH || newW < cropW) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC
        Imgproc.resize(cropBgr, resized, Size(newW.toDouble(), newH.toDouble()), 0.0, 0.0, interp)
        val padded = Mat.zeros(ih, iw, CvType.CV_8UC3)
        resized.copyTo(padded.submat(0, newH, 0, newW))
        resized.release()
        val input = matToNchwBgr(padded, iw, ih)
        padded.release()

        val outs = recSession(vertical).run(listOf(
            NamedTensor("images", intArrayOf(1, 3, ih, iw), TensorData.Floats(input)),
            NamedTensor("orig_target_sizes", intArrayOf(1, 2), TensorData.Ints(intArrayOf(iw, ih))),
        ))
        val codes = codesOf(outs)
        val boxes = boxesOf(outs)
        val scores = scoresOf(outs)

        // Candidate chars; reading axis is Y for vertical, X for horizontal.
        val axLo = if (vertical) 1 else 0
        data class Cand(val s: String, val sc: Float, val lo: Float, val hi: Float, val b: FloatArray)
        val cands = ArrayList<Cand>(scores.size)
        for (i in scores.indices) {
            if (scores[i] < REC_CONF) continue
            val cp = codes.getOrElse(i) { 0 }
            if (cp <= 0) continue
            val s = try { String(Character.toChars(cp)) } catch (e: Exception) { continue }
            val b = floatArrayOf(boxes[i * 4], boxes[i * 4 + 1], boxes[i * 4 + 2], boxes[i * 4 + 3])
            cands += Cand(s, scores[i], b[axLo], b[axLo + 2], b)
        }
        cands.sortByDescending { it.sc }
        val kept = ArrayList<Cand>(cands.size)
        for (c in cands) {
            val wdt = (c.hi - c.lo) + 1e-6f
            val overlapped = kept.any {
                val ov = max(0f, min(c.hi, it.hi) - max(c.lo, it.lo)); ov / wdt > OVERLAP
            }
            if (!overlapped) kept += c
        }
        kept.sortBy { it.lo }

        val sb = StringBuilder()
        val chars = ArrayList<CharHit>(kept.size)
        val effW = newW.toFloat(); val effH = newH.toFloat()
        var confSum = 0f
        for (c in kept) {
            val offset = sb.length
            sb.append(c.s)
            confSum += c.sc
            fun mapX(v: Float) = (v.coerceIn(0f, effW) / effW * cropW).roundToInt()
            fun mapY(v: Float) = (v.coerceIn(0f, effH) / effH * cropH).roundToInt()
            val rect = Rect(mapX(c.b[0]), mapY(c.b[1]), mapX(c.b[2]), mapY(c.b[3]))
            chars += CharHit(c.s, rect, offset)
        }
        val conf = if (kept.isNotEmpty()) confSum / kept.size else 0f
        return RecResult(sb.toString(), chars, conf)
    }

    private fun recSession(vertical: Boolean) = if (vertical) recV else recH

    /** CV_8UC3 BGR Mat (h×w) → NCHW float /255, channels in stored (B,G,R) order. */
    private fun matToNchwBgr(mat: Mat, w: Int, h: Int): FloatArray {
        val buf = ByteArray(w * h * 3)
        mat.get(0, 0, buf)
        val out = FloatArray(3 * w * h)
        val plane = w * h
        for (i in 0 until plane) {
            val base = i * 3
            out[i] = (buf[base].toInt() and 0xff) / 255f
            out[plane + i] = (buf[base + 1].toInt() and 0xff) / 255f
            out[2 * plane + i] = (buf[base + 2].toInt() and 0xff) / 255f
        }
        return out
    }

    override fun close() {
        det.close(); recH.close(); recV.close()
    }

    companion object {
        private const val TAG = "MeikiSession"

        // Detector input (stretched), confidence gate.
        private const val DET_W = 960
        private const val DET_H = 544
        private const val DET_CONF = 0.4f
        // Recognizer inputs.
        private const val REC_H_W = 960
        private const val REC_H_H = 32
        private const val REC_V_W = 32
        private const val REC_V_H = 480
        private const val REC_CONF = 0.1f
        private const val OVERLAP = 0.3f

        @Volatile private var cvLoaded = false
        private fun ensureOpenCv() {
            if (!cvLoaded) synchronized(this) {
                if (!cvLoaded) {
                    check(OpenCVLoader.initLocal()) { "OpenCV initLocal() failed" }
                    cvLoaded = true
                    Log.i(TAG, "OpenCV initialized: ${Core.VERSION}")
                }
            }
        }

        /** D-FINE outputs identified structurally (robust to MNN tensor-name
         *  changes): char_codes = the int tensor; boxes = float tensor with a
         *  3-D shape (…,N,4); scores = the other (2-D) float tensor. */
        private fun codesOf(outs: List<NamedTensor>): IntArray =
            (outs.first { it.data is TensorData.Ints }.data as TensorData.Ints).data
        private fun boxesOf(outs: List<NamedTensor>): FloatArray =
            (outs.first { it.data is TensorData.Floats && it.shape.size >= 3 }.data as TensorData.Floats).data
        private fun scoresOf(outs: List<NamedTensor>): FloatArray =
            (outs.first { it.data is TensorData.Floats && it.shape.size < 3 }.data as TensorData.Floats).data

        fun create(
            detPath: String,
            recHorizontalPath: String,
            recVerticalPath: String,
            numThread: Int = 4,
            precision: Int = 0,
        ): MeikiSession {
            ensureOpenCv()
            val det = MnnInterpreter.fromFile(detPath, numThread, precision)
            val recH = MnnInterpreter.fromFile(recHorizontalPath, numThread, precision)
            val recV = MnnInterpreter.fromFile(recVerticalPath, numThread, precision)
            Log.i(TAG, "MeikiSession: det=$detPath recH=$recHorizontalPath recV=$recVerticalPath")
            return MeikiSession(det, recH, recV)
        }
    }
}
