package com.playtranslate.ocr.meiki

import android.graphics.Bitmap
import androidx.core.graphics.scale
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
import kotlin.math.ceil
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
 * [MnnInterpreter.run] (List<NamedTensor>). All pre/post-processing lives here,
 * ported from the desktop spike (`/tmp/ocr_bakeoff/run_bakeoff.py` + the models'
 * shipped `inference*.py`) — EXCEPT detection preprocessing, which follows
 * upstream meikiocr's `ocr.py` (aspect-preserving letterbox) rather than the
 * v0.1 demo script's plain stretch the spike copied; see [detect]. BGR /255
 * input; D-FINE char outputs are **Unicode codepoints** (`Character.toChars`);
 * reading-axis overlap dedup + sort.
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
     *  ORIGINAL-bitmap coords. The bitmap is LETTERBOXED into DET_W×DET_H —
     *  aspect-preserving resize, content anchored top-left, black padding —
     *  which is upstream meikiocr's `ocr.py` preprocessing; boxes map back
     *  through the single uniform scale. The first port stretched to
     *  DET_W×DET_H like the HF repo's v0.1 demo script instead, which coupled
     *  the CROP's shape to the score distribution: a tight region arrived
     *  several times more vertically distorted than a full frame, sliding
     *  whole per-line score stacks across [DET_CONF] — one line emitted twice
     *  when two stack members cleared it, a line lost when none did
     *  (docs/meiki-detection-threshold-forensics.md). Survivors then pass
     *  [dedupByIou], the region-tier analog of the recognizers' overlap dedup. */
    fun detect(bitmap: Bitmap): List<DetBox> {
        val scale = min(DET_W.toFloat() / bitmap.width, DET_H.toFloat() / bitmap.height)
        val newW = min(DET_W, max(1, (bitmap.width * scale).roundToInt()))
        val newH = min(DET_H, max(1, (bitmap.height * scale).roundToInt()))
        val scaled = bitmap.scale(newW, newH)
        val px = IntArray(newW * newH)
        scaled.getPixels(px, 0, newW, 0, 0, newW, newH)
        if (scaled != bitmap) scaled.recycle()
        // NCHW, BGR, /255 (Meiki trained on cv2/BGR; no mean-std). Content
        // occupies the top-left newW×newH; the FloatArray's zero fill IS the
        // black letterbox padding.
        val input = FloatArray(3 * DET_W * DET_H)
        val plane = DET_W * DET_H
        for (y in 0 until newH) {
            val src = y * newW
            val dst = y * DET_W
            for (x in 0 until newW) {
                val p = px[src + x]
                val d = dst + x
                input[d] = (p and 0xff) / 255f                 // B
                input[plane + d] = ((p ushr 8) and 0xff) / 255f  // G
                input[2 * plane + d] = ((p ushr 16) and 0xff) / 255f // R
            }
        }
        val outs = det.run(listOf(
            NamedTensor("images", intArrayOf(1, 3, DET_H, DET_W), TensorData.Floats(input)),
            NamedTensor("orig_target_sizes", intArrayOf(1, 2), TensorData.Ints(intArrayOf(DET_W, DET_H))),
        ))
        val boxes = boxesOf(outs)
        val scores = scoresOf(outs)
        val inv = 1f / scale
        val out = ArrayList<DetBox>(scores.size)
        for (i in scores.indices) {
            if (scores[i] < DET_CONF) continue
            val x1 = (boxes[i * 4] * inv).roundToInt().coerceIn(0, bitmap.width - 1)
            val y1 = (boxes[i * 4 + 1] * inv).roundToInt().coerceIn(0, bitmap.height - 1)
            val x2 = (boxes[i * 4 + 2] * inv).roundToInt().coerceIn(1, bitmap.width)
            val y2 = (boxes[i * 4 + 3] * inv).roundToInt().coerceIn(1, bitmap.height)
            if (x2 - x1 >= 2 && y2 - y1 >= 2) out += DetBox(Rect(x1, y1, x2, y2), scores[i])
        }
        return dedupByIou(out)
    }

    /**
     * Score-descending greedy IoU suppression over the kept region boxes — the
     * region tier's analog of [dedupByAxisOverlap]. The D-FINE head proposes a
     * stack of near-copies for every text line (fixed 64 queries; no NMS in the
     * model, and none in upstream meikiocr, which filters by confidence alone),
     * so whenever two members of one stack clear [DET_CONF] the line is
     * recognized and emitted twice. [DET_DEDUP_IOU] separates the regimes:
     * measured same-line stack pairs sit at IoU ≥ 0.96, distinct upright lines
     * at ~0.0, and the worst plausible distinct-line overlap — adjacent long
     * 45°-slanted parallel lines at tight pitch, whose AABBs reach this tier
     * intact because deskew happens per-region at recognition — sketches to
     * ~0.76. Output preserves input (query) order.
     */
    private fun dedupByIou(boxes: List<DetBox>): List<DetBox> {
        if (boxes.size < 2) return boxes
        val suppressed = BooleanArray(boxes.size)
        val order = boxes.indices.sortedByDescending { boxes[it].score }
        for (oi in order.indices) {
            val i = order[oi]
            if (suppressed[i]) continue
            for (oj in oi + 1 until order.size) {
                val j = order[oj]
                if (!suppressed[j] && iou(boxes[i].rect, boxes[j].rect) > DET_DEDUP_IOU) {
                    suppressed[j] = true
                }
            }
        }
        return boxes.filterIndexed { i, _ -> !suppressed[i] }
    }

    /** Intersection-over-union of two pixel AABBs. */
    private fun iou(a: Rect, b: Rect): Float {
        val iw = min(a.right, b.right) - max(a.left, b.left)
        val ih = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (iw <= 0 || ih <= 0) return 0f
        val inter = iw.toFloat() * ih
        return inter / (a.width().toFloat() * a.height() + b.width().toFloat() * b.height() - inter)
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
        // Reading axis is Y for vertical, X for horizontal.
        val axLo = if (vertical) 1 else 0
        val kept = dedupByAxisOverlap(candidatesOf(outs, axLo))
        return buildResult(
            kept, effW = newW.toFloat(), effH = newH.toFloat(),
            cropW = cropW, cropH = cropH, shiftX = 0f, shiftY = 0f,
        )
    }

    /**
     * Recognize [crops] (all of the same [vertical] orientation) by PACKING
     * multiple aspect-resized crops onto shared fixed canvases — the utilization
     * experiment: a short crop wastes most of the fixed 960×32 / 32×480 canvas in
     * [recognize], so packing N short crops into one forward pass divides rec
     * compute by ~N, at the risk of cross-crop interference (which the A/B
     * harness measures). Canvas shape is UNCHANGED — no untested graph dims.
     *
     * Placement: content anchored at 0 on the across axis, slots separated by
     * [PACK_GAP] black px along the reading axis; greedy binning in input order,
     * flushed on canvas overflow or the [PACK_CHAR_BUDGET] estimate (the model
     * recognizes ~48 chars per canvas, a shared budget when packing). Candidates
     * are assigned to the slot whose content range they overlap most (gap-only
     * phantoms dropped) and deduped within their slot only.
     *
     * Returns one [RecResult] per input crop, index-aligned, with CROP-LOCAL
     * coordinates exactly like [recognize]. Not thread-safe (same sessions).
     */
    fun recognizePacked(crops: List<Mat>, vertical: Boolean): List<RecResult> {
        val iw = if (vertical) REC_V_W else REC_H_W
        val ih = if (vertical) REC_V_H else REC_H_H
        val canvasAlong = if (vertical) ih else iw
        val results = arrayOfNulls<RecResult>(crops.size)

        // Aspect-resize dims per crop — same formula as recognize().
        class Sized(val idx: Int, val newW: Int, val newH: Int, val cropW: Int, val cropH: Int) {
            val along get() = if (vertical) newH else newW
        }
        val sized = ArrayList<Sized>(crops.size)
        for ((i, crop) in crops.withIndex()) {
            val cropW = crop.cols(); val cropH = crop.rows()
            if (cropW < 2 || cropH < 2) { results[i] = RecResult("", emptyList(), 0f); continue }
            var newW: Int; var newH: Int
            if (vertical) {
                newW = iw; newH = max(1, (cropH * (iw.toDouble() / cropW)).roundToInt())
                if (newH > ih) { newH = ih; newW = max(1, (cropW * (ih.toDouble() / cropH)).roundToInt()) }
            } else {
                newH = ih; newW = max(1, (cropW * (ih.toDouble() / cropH)).roundToInt())
                if (newW > iw) { newW = iw; newH = max(1, (cropH * (iw.toDouble() / cropW)).roundToInt()) }
            }
            sized += Sized(i, newW, newH, cropW, cropH)
        }

        // Greedy binning in input order; an oversize single crop still gets its
        // own canvas (limits only trip when the bin is non-empty).
        val bins = ArrayList<ArrayList<Sized>>()
        var bin = ArrayList<Sized>(); var along = 0; var chars = 0
        for (s in sized) {
            val est = ceil(s.along / EST_CHAR_PITCH.toDouble()).toInt()
            if (bin.isNotEmpty() &&
                (along + PACK_GAP + s.along > canvasAlong || chars + est > PACK_CHAR_BUDGET)) {
                bins += bin; bin = ArrayList(); along = 0; chars = 0
            }
            if (bin.isNotEmpty()) along += PACK_GAP
            bin += s; along += s.along; chars += est
        }
        if (bin.isNotEmpty()) bins += bin

        for (b in bins) {
            var cursor = 0
            val slots = ArrayList<Pair<Sized, Int>>(b.size)   // (crop dims, axisStart)
            val padded = Mat.zeros(ih, iw, CvType.CV_8UC3)
            for (s in b) {
                val resized = Mat()
                val src = crops[s.idx]
                val interp = if (s.newH < s.cropH || s.newW < s.cropW) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC
                Imgproc.resize(src, resized, Size(s.newW.toDouble(), s.newH.toDouble()), 0.0, 0.0, interp)
                if (vertical) resized.copyTo(padded.submat(cursor, cursor + s.newH, 0, s.newW))
                else resized.copyTo(padded.submat(0, s.newH, cursor, cursor + s.newW))
                resized.release()
                slots += s to cursor
                cursor += s.along + PACK_GAP
            }
            val input = matToNchwBgr(padded, iw, ih)
            padded.release()
            val outs = recSession(vertical).run(listOf(
                NamedTensor("images", intArrayOf(1, 3, ih, iw), TensorData.Floats(input)),
                NamedTensor("orig_target_sizes", intArrayOf(1, 2), TensorData.Ints(intArrayOf(iw, ih))),
            ))
            val axLo = if (vertical) 1 else 0
            val all = candidatesOf(outs, axLo)
            // Assign each candidate to the slot whose CONTENT range it overlaps
            // most along the reading axis (ties -> earlier slot). Center-based
            // assignment let a glyph near a boundary migrate to the neighbor slot
            // (trailing 、 prepending itself to the next crop) and then perturb
            // that slot's overlap dedup. A candidate overlapping no content range
            // sits entirely in a black gap — a phantom, dropped.
            val slotCands = Array(slots.size) { ArrayList<Cand>() }
            for (c in all) {
                var best = -1
                var bestOv = 0f
                for ((k, sp) in slots.withIndex()) {
                    val (s, start) = sp
                    val ov = min(c.hi, (start + s.along).toFloat()) - max(c.lo, start.toFloat())
                    if (ov > bestOv) { bestOv = ov; best = k }
                }
                if (best >= 0) slotCands[best] += c
            }
            for ((k, sp) in slots.withIndex()) {
                val (s, start) = sp
                results[s.idx] = buildResult(
                    dedupByAxisOverlap(slotCands[k]),
                    effW = s.newW.toFloat(), effH = s.newH.toFloat(),
                    cropW = s.cropW, cropH = s.cropH,
                    shiftX = if (vertical) 0f else start.toFloat(),
                    shiftY = if (vertical) start.toFloat() else 0f,
                )
            }
        }
        return List(crops.size) { results[it] ?: RecResult("", emptyList(), 0f) }
    }

    private fun recSession(vertical: Boolean) = if (vertical) recV else recH

    // ── Shared recognizer post-processing (single-crop and packed paths) ─────

    /** One character candidate: [lo]/[hi] are its extent along the reading axis
     *  (canvas coords); [b] the full canvas-coord box. */
    private class Cand(val s: String, val sc: Float, val lo: Float, val hi: Float, val b: FloatArray)

    /** Confidence/codepoint-filter the raw D-FINE outputs into candidates. */
    private fun candidatesOf(outs: List<NamedTensor>, axLo: Int): ArrayList<Cand> {
        val codes = codesOf(outs)
        val boxes = boxesOf(outs)
        val scores = scoresOf(outs)
        val cands = ArrayList<Cand>(scores.size)
        for (i in scores.indices) {
            if (scores[i] < REC_CONF) continue
            val cp = codes.getOrElse(i) { 0 }
            if (cp <= 0) continue
            val s = try { String(Character.toChars(cp)) } catch (e: Exception) { continue }
            val b = floatArrayOf(boxes[i * 4], boxes[i * 4 + 1], boxes[i * 4 + 2], boxes[i * 4 + 3])
            cands += Cand(s, scores[i], b[axLo], b[axLo + 2], b)
        }
        return cands
    }

    /** Score-descending greedy dedup by reading-axis overlap, then reading order. */
    private fun dedupByAxisOverlap(cands: ArrayList<Cand>): List<Cand> {
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
        return kept
    }

    /** Assemble kept candidates into a [RecResult], mapping canvas-coord boxes to
     *  CROP-LOCAL rects: shifted by [shiftX]/[shiftY] (a packed slot's along-axis
     *  offset; 0 for the single-crop path), content [effW]×[effH] scaled to
     *  [cropW]×[cropH]. */
    private fun buildResult(
        kept: List<Cand>, effW: Float, effH: Float, cropW: Int, cropH: Int,
        shiftX: Float, shiftY: Float,
    ): RecResult {
        val sb = StringBuilder()
        val chars = ArrayList<CharHit>(kept.size)
        var confSum = 0f
        for (c in kept) {
            val offset = sb.length
            sb.append(c.s)
            confSum += c.sc
            fun mapX(v: Float) = ((v - shiftX).coerceIn(0f, effW) / effW * cropW).roundToInt()
            fun mapY(v: Float) = ((v - shiftY).coerceIn(0f, effH) / effH * cropH).roundToInt()
            val rect = Rect(mapX(c.b[0]), mapY(c.b[1]), mapX(c.b[2]), mapY(c.b[3]))
            chars += CharHit(c.s, rect, offset)
        }
        val conf = if (kept.isNotEmpty()) confSum / kept.size else 0f
        return RecResult(sb.toString(), chars, conf)
    }

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

        // Detector input (letterboxed), confidence gate.
        private const val DET_W = 960
        private const val DET_H = 544
        private const val DET_CONF = 0.4f

        /** Region-tier near-duplicate bar for [dedupByIou]: above the worst
         *  plausible distinct-line AABB overlap (~0.76, adjacent slanted
         *  lines), below every measured same-line stack pair (≥ 0.96). */
        private const val DET_DEDUP_IOU = 0.8f
        // Recognizer inputs.
        private const val REC_H_W = 960
        private const val REC_H_H = 32
        private const val REC_V_W = 32
        private const val REC_V_H = 480
        private const val REC_CONF = 0.1f
        private const val OVERLAP = 0.3f
        // recognizePacked knobs: black gap between packed slots along the reading
        // axis; conservative estimated glyph pitch (canvas px per char) and the
        // per-canvas char budget — the model recognizes ~48 chars per canvas
        // (upstream README), a budget that packing makes SHARED across crops.
        private const val PACK_GAP = 32
        private const val EST_CHAR_PITCH = 20
        private const val PACK_CHAR_BUDGET = 44

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

        /**
         * @param precision MnnInterpreter precision flag for the DETECTOR (and the
         *   recognizers' default): 0 Normal/fp32, 1 High, 2 Low/fp16.
         * @param recPrecision recognizer override. Mixed precision (det 2, rec 0)
         *   exists because fp16 left Meiki's det boxes byte-stable in the A/B but
         *   flipped marginal rec classifications (small っ → つ) — the recognizers
         *   carry the reading-fidelity risk, the detector doesn't.
         */
        fun create(
            detPath: String,
            recHorizontalPath: String,
            recVerticalPath: String,
            numThread: Int = 4,
            precision: Int = 0,
            recPrecision: Int = precision,
        ): MeikiSession {
            ensureOpenCv()
            val det = MnnInterpreter.fromFile(detPath, numThread, precision)
            val recH = MnnInterpreter.fromFile(recHorizontalPath, numThread, recPrecision)
            val recV = MnnInterpreter.fromFile(recVerticalPath, numThread, recPrecision)
            Log.i(TAG, "MeikiSession: det=$detPath recH=$recHorizontalPath recV=$recVerticalPath")
            return MeikiSession(det, recH, recV)
        }
    }
}
