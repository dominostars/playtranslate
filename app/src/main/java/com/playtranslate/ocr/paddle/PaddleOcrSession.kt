package com.playtranslate.ocr.paddle

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.playtranslate.mnn.MnnInterpreter
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.imgcodecs.Imgcodecs
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

//
// PaddleOCR PP-OCRv5 pipeline driver for the spike harness (androidTest-only).
//
// Wraps two MnnInterpreter sessions: the DBNet detector and the CRNN/SVTR
// recognizer (.mnn files converted from official PaddlePaddle weights, staged
// in the app external files dir). All image pre/post-processing lives here in
// Kotlin; the native MNN shim only runs float tensors. OpenCV supplies the
// DBNet geometry (contours, min-area-rect, perspective-warp) in DbNet.kt.
//
// Two entry points, matching the spike config matrix (paddleocr-spike-scope):
//   - detectAndRecognize: full pipeline (PP detector + PP recognizer), configs
//     2/3. Loses ML Kit symbol boxes (region-level only).
//   - recognizeCrops: recognizer only, over caller-supplied boxes (ML Kit line
//     rects), configs 4/5 hybrid. Detection comes from ML Kit upstream.
//
// Not thread-safe (one MNN Session each); the harness runs configs serially.
//
// Preprocessing constants in Cfg are the canonical PP-OCRv5 inference values,
// verified against each model inference.yml on 2026-05-31:
//   det:  DetResizeForTest resize_long 960; NormalizeImage ImageNet mean/std,
//         scale 1/255; DBPostProcess thresh 0.3 / box_thresh 0.6 / unclip 1.5.
//   rec:  image_shape 3x48xW; CTCLabelDecode; charset index 0 is blank.
//
class PaddleOcrSession private constructor(
    private val det: MnnInterpreter,
    private val rec: MnnInterpreter,
    private val keys: List<String>,
) : Closeable {

    /** One recognized region: box in ORIGINAL-bitmap coords, text, confidence. */
    data class Region(val box: Rect, val text: String, val confidence: Float)

    /** Full-pipeline result. regions in reading order; fullText joined. */
    data class Result(
        val regions: List<Region>,
        val fullText: String,
        val detMs: Long,
        val recMs: Long,
    )

    /** Recognizer-only result for one supplied crop (hybrid configs). */
    data class CropResult(val text: String, val confidence: Float)

    // ── Config 2/3: full PaddleOCR pipeline ──────────────────────────────────

    fun detectAndRecognize(bitmap: Bitmap): Result {
        val dp = detPreprocess(bitmap)
        val detStart = System.nanoTime()
        val detOut = det.run(dp.data, intArrayOf(1, 3, dp.resizedH, dp.resizedW))
        val detMs = (System.nanoTime() - detStart) / 1_000_000

        val (probH, probW) = probMapHW(detOut.shape, dp.resizedH, dp.resizedW)
        val boxes = DbNet.boxesFromProbMap(
            prob = detOut.data, h = probH, w = probW,
            scaleX = dp.resizedW.toFloat() / bitmap.width,
            scaleY = dp.resizedH.toFloat() / bitmap.height,
            origW = bitmap.width, origH = bitmap.height,
        )

        val srcMat = Mat().also { Utils.bitmapToMat(bitmap, it) }  // RGBA
        val recStart = System.nanoTime()
        val regions = ArrayList<Region>(boxes.size)
        try {
            for ((idx, b) in boxes.withIndex()) {
                val crop = DbNet.warpCrop(srcMat, b.points, Cfg.REC_HEIGHT) ?: continue
                try {
                    val r = recognizeMat(crop)
                    if (r.text.isNotBlank()) regions += Region(b.aabb, r.text, r.confidence)
                    // Debug crop-dump: write the EXACT pre-recognition crop the
                    // model saw, tagged with what it read + the region's aspect
                    // (V = warpCrop rotated it as vertical, H = horizontal). Lets
                    // us eyeball whether our rotate/resample mangled small kana
                    // (our bug, fixable) vs the crop is legible but the model
                    // blanked it (recognition limit). No-op unless dumpDir set.
                    dumpDir?.let { dir -> dumpCrop(dir, crop, idx, r.text, b.aabb) }
                } finally {
                    crop.release()
                }
            }
        } finally {
            srcMat.release()
        }
        val recMs = (System.nanoTime() - recStart) / 1_000_000

        val ordered = orderForReading(regions)
        return Result(ordered, ordered.joinToString(" ") { it.text }, detMs, recMs)
    }

    // ── Config 4/5: recognizer only, over caller (ML Kit) boxes ──────────────

    /** Recognize each rect by cropping the bitmap and running only the
     *  recognizer. Returns one CropResult per input box, index-aligned, so the
     *  caller can pair PP text back onto the ML Kit line it came from. */
    fun recognizeCrops(bitmap: Bitmap, boxes: List<Rect>): List<CropResult> {
        val srcMat = Mat().also { Utils.bitmapToMat(bitmap, it) }
        val out = ArrayList<CropResult>(boxes.size)
        try {
            for (rect in boxes) {
                val clamped = Rect(
                    rect.left.coerceIn(0, bitmap.width - 1),
                    rect.top.coerceIn(0, bitmap.height - 1),
                    rect.right.coerceIn(1, bitmap.width),
                    rect.bottom.coerceIn(1, bitmap.height),
                )
                if (clamped.width() < 2 || clamped.height() < 2) {
                    out += CropResult("", 0f); continue
                }
                val sub = srcMat.submat(clamped.top, clamped.bottom, clamped.left, clamped.right)
                val resized = Mat()
                try {
                    val w = max(1, (Cfg.REC_HEIGHT * clamped.width().toDouble() / clamped.height()).roundToInt())
                    // Direction-aware interpolation (see DbNet.warpCrop): INTER_AREA
                    // when shrinking preserves fine dakuten/handakuten detail that
                    // default INTER_LINEAR aliases away; INTER_CUBIC when enlarging.
                    val interp = if (clamped.height() > Cfg.REC_HEIGHT) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC
                    Imgproc.resize(sub, resized, Size(w.toDouble(), Cfg.REC_HEIGHT.toDouble()), 0.0, 0.0, interp)
                    out += recognizeMat(resized)
                } finally {
                    sub.release(); resized.release()
                }
            }
        } finally {
            srcMat.release()
        }
        return out
    }

    // ── Recognizer: one already-cropped, height-48 Mat → text + confidence ───

    /** Soft unsharp-mask kernel, identical to the Vietnamese diacritics recipe
     *  (DiacriticsOcrPreprocessing): `v = (6·center − 4·neighbours) / 2`. Boosts
     *  thin-stroke edges (dakuten/handakuten) without halo overshoot. Applied
     *  per-channel via filter2D so COLOR is preserved (no grayscale) — PP's rec
     *  model wants RGB. No upscale/contrast/invert: just the sharpen, so region
     *  coordinates are untouched and nothing downstream needs rescaling. */
    private val sharpenKernel: Mat by lazy {
        Mat(3, 3, CvType.CV_32F).apply {
            put(0, 0,
                0.0, -0.5, 0.0,
                -0.5, 3.0, -0.5,
                0.0, -0.5, 0.0)
        }
    }

    private fun recognizeMat(crop: Mat): CropResult {
        // Sharpen in place (in-channel) before normalization to preserve fine
        // diacritic detail through the height-48 resample.
        Imgproc.filter2D(crop, crop, -1, sharpenKernel)
        val w = crop.cols()
        val input = recPreprocess(crop, w)
        val out = rec.run(input, intArrayOf(1, 3, Cfg.REC_HEIGHT, w))
        return ctcDecode(out.data, out.shape)
    }

    // ── Detector preprocessing ───────────────────────────────────────────────

    private class DetInput(val data: FloatArray, val resizedW: Int, val resizedH: Int)

    /** Resize so the longest side stays within Cfg.DET_LIMIT_SIDE, both dims
     *  rounded to a multiple of 32, then normalize (RGB, ImageNet mean/std,
     *  NCHW). */
    private fun detPreprocess(bitmap: Bitmap): DetInput {
        val ow = bitmap.width; val oh = bitmap.height
        var ratio = 1f
        val longest = max(ow, oh)
        if (longest > Cfg.DET_LIMIT_SIDE) ratio = Cfg.DET_LIMIT_SIDE.toFloat() / longest
        fun round32(v: Int): Int = max(32, (v / 32.0).roundToInt() * 32)
        val rw = round32((ow * ratio).roundToInt())
        val rh = round32((oh * ratio).roundToInt())

        val scaled = Bitmap.createScaledBitmap(bitmap, rw, rh, true)
        val px = IntArray(rw * rh)
        scaled.getPixels(px, 0, rw, 0, 0, rw, rh)
        if (scaled != bitmap) scaled.recycle()

        // NCHW, RGB, normalized as (v/255 - mean) / std
        val out = FloatArray(3 * rw * rh)
        val plane = rw * rh
        for (i in 0 until plane) {
            val p = px[i]
            val r = ((p ushr 16) and 0xff) / 255f
            val g = ((p ushr 8) and 0xff) / 255f
            val b = (p and 0xff) / 255f
            out[i] = (r - Cfg.DET_MEAN[0]) / Cfg.DET_STD[0]
            out[plane + i] = (g - Cfg.DET_MEAN[1]) / Cfg.DET_STD[1]
            out[2 * plane + i] = (b - Cfg.DET_MEAN[2]) / Cfg.DET_STD[2]
        }
        return DetInput(out, rw, rh)
    }

    /** Detector output is single-channel. Pick the two largest dims as H,W in
     *  order; fall back to the resized det dims if the shape is ambiguous. */
    private fun probMapHW(shape: IntArray, fallbackH: Int, fallbackW: Int): Pair<Int, Int> {
        val dims = shape.filter { it > 1 }
        return when {
            dims.size >= 2 -> dims[dims.size - 2] to dims[dims.size - 1]
            else -> fallbackH to fallbackW
        }
    }

    // ── Recognizer preprocessing ─────────────────────────────────────────────

    /** crop is an RGBA Mat already at height Cfg.REC_HEIGHT. Produce NCHW RGB
     *  float normalized to [-1,1], i.e. (v/255 - 0.5) / 0.5. This is correct
     *  for PP-OCRv5 rec: the inference.yml lists NO NormalizeImage op — the
     *  normalization is baked into PaddleOCR's RecResizeImg operator, whose
     *  `resize_norm_img` does exactly `img/255; img -= 0.5; img /= 0.5`. (A
     *  plain v/255 mapping was tried and reverted — it feeds the model a range
     *  it wasn't trained on. The det model is the one with explicit ImageNet
     *  mean/std; rec is [-1,1].) */
    private fun recPreprocess(crop: Mat, w: Int): FloatArray {
        val h = Cfg.REC_HEIGHT
        val ch = crop.channels()
        val buf = ByteArray(w * h * ch)
        crop.get(0, 0, buf)
        val out = FloatArray(3 * w * h)
        val plane = w * h
        for (i in 0 until plane) {
            val base = i * ch
            // Utils.bitmapToMat yields RGBA; extract R,G,B explicitly.
            val r = (buf[base].toInt() and 0xff) / 255f
            val g = (buf[base + 1].toInt() and 0xff) / 255f
            val b = (buf[base + 2].toInt() and 0xff) / 255f
            out[i] = (r - 0.5f) / 0.5f
            out[plane + i] = (g - 0.5f) / 0.5f
            out[2 * plane + i] = (b - 0.5f) / 0.5f
        }
        return out
    }

    // ── CTC greedy decode ────────────────────────────────────────────────────

    /** logits is shape 1,T,C (softmax already applied for mobile; argmax is
     *  invariant to that). Greedy: per-timestep argmax, collapse repeats, drop
     *  blank at index 0. Confidence is the mean max-prob over kept timesteps. */
    private fun ctcDecode(logits: FloatArray, shape: IntArray): CropResult {
        val dims = shape.filter { it >= 1 }
        val c = dims.last()
        val t = if (dims.size >= 2) dims[dims.size - 2] else logits.size / c
        val labels = labelsFor(c)

        val sb = StringBuilder()
        var prev = -1
        var probSum = 0f
        var probCount = 0
        for (ti in 0 until t) {
            val off = ti * c
            var best = 0; var bestV = logits[off]
            for (ci in 1 until c) {
                val v = logits[off + ci]
                if (v > bestV) { bestV = v; best = ci }
            }
            if (best != 0 && best != prev) {
                sb.append(labels[best])
                probSum += bestV; probCount++
            }
            prev = best
        }
        val conf = if (probCount > 0) probSum / probCount else 0f
        return CropResult(sb.toString(), conf)
    }

    /** Build the label table for recognizer output dimension c. PP-OCR puts
     *  blank at index 0; remaining indices map to the charset, optionally with a
     *  trailing space (use_space_char). Robust to off-by-one around keys.size. */
    private var cachedLabels: List<String>? = null
    private fun labelsFor(c: Int): List<String> {
        cachedLabels?.let { if (it.size == c) return it }
        val out = ArrayList<String>(c)
        out += ""                       // 0 = blank
        for (i in 1 until c) {
            out += keys.getOrElse(i - 1) {
                if (i - 1 == keys.size) " " else ""   // trailing space slot
            }
        }
        cachedLabels = out
        return out
    }

    // ── Reading order ────────────────────────────────────────────────────────

    /** Order regions into reading order. Horizontal (default): top-to-bottom,
     *  then left-to-right within a row band. Vertical/tategaki: columns read
     *  right-to-left (rightmost column first), top-to-bottom within a column —
     *  so we band by column X (rightmost first) then sort by top.
     *
     *  A capture is treated as vertical when most regions are tall-and-narrow
     *  (same box test the crop rotation uses), which is the tategaki case. */
    private fun orderForReading(regions: List<Region>): List<Region> {
        if (regions.isEmpty()) return regions
        val verticalCount = regions.count { it.box.height() > it.box.width() * 1.5 }
        val verticalDominant = verticalCount * 2 > regions.size

        if (verticalDominant) {
            // Band by column using average region width; rightmost band first.
            val avgW = regions.map { it.box.width() }.average().toFloat().coerceAtLeast(1f)
            return regions.sortedWith(
                // Negate the column band so larger X (rightmost) sorts first;
                // within a column, top-to-bottom.
                compareBy({ -((it.box.left + it.box.right) / 2 / (avgW * 0.7f)).toInt() }, { it.box.top })
            )
        }

        val avgH = regions.map { it.box.height() }.average().toFloat().coerceAtLeast(1f)
        return regions.sortedWith(compareBy({ (it.box.top / (avgH * 0.7f)).toInt() }, { it.box.left }))
    }

    override fun close() {
        det.close(); rec.close()
    }

    /** Write one pre-recognition crop to [dir] as PNG, named with the recognized
     *  text + orientation tag so the file itself documents what the model saw vs
     *  read. Filenames: `crop_<idx>_<V|H>_<text>.png`. Best-effort; never throws
     *  into the OCR path. [crop] is RGBA (from Utils.bitmapToMat); convert to BGR
     *  for imwrite so colors aren't swapped in the saved file. */
    private fun dumpCrop(dir: File, crop: Mat, idx: Int, text: String, box: Rect) {
        try {
            if (!dir.exists()) dir.mkdirs()
            // V if the source region was taller than wide (warpCrop rotated it).
            val tag = if (box.height() > box.width() * 1.5) "V" else "H"
            // sanitize text for a filename; keep it short.
            val safe = text.take(12).map { if (it.isLetterOrDigit() || it in '぀'..'ヿ' || it in '一'..'鿿') it else '_' }
                .joinToString("")
            val bgr = Mat()
            Imgproc.cvtColor(crop, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgcodecs.imwrite(File(dir, "crop_${idx}_${tag}_$safe.png").absolutePath, bgr)
            bgr.release()
        } catch (e: Throwable) {
            Log.w(TAG, "dumpCrop failed: ${e.message}")
        }
    }

    // Canonical PP-OCRv5 inference constants. Verified against inference.yml.
    private object Cfg {
        // Longest-side cap for the detector input. PP's default is 960, but our
        // captures are 1080p — capping at 960 downscaled 1920→960, HALVING every
        // character's pixels before detection and starving small-kana detail
        // (dakuten, small っ). Raised to 1920 so 1080p frames pass through at
        // native resolution: more pixels per character upstream of the crop,
        // where the detail still exists. See docs/paddleocr-kana-research.md
        // (option B). Cost: larger detector input → slower det (the det stage
        // was already the latency sink at 960; expect a further increase).
        // Rounded to a multiple of 32 in detPreprocess regardless.
        const val DET_LIMIT_SIDE = 1920
        // NormalizeImage (det): ImageNet mean/std, scale 1/255, RGB order.
        val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        // Recognizer fixed input height (rec image_shape 3 x 48 x W).
        const val REC_HEIGHT = 48
    }

    companion object {
        private const val TAG = "PaddleOcrSession"

        /** Debug crop-dump target. When non-null, every pre-recognition crop is
         *  written here as a PNG (see [dumpCrop]). Null in production → no-op.
         *  Set by PaddleOcrBridge from the debug toggle. */
        @Volatile var dumpDir: File? = null

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

        /**
         * @param precision MnnInterpreter precision flag: 0 Normal, 1 High, 2 Low-fp16.
         */
        fun create(
            detModelPath: String,
            recModelPath: String,
            keysPath: String,
            numThread: Int = 4,
            precision: Int = 0,
        ): PaddleOcrSession {
            ensureOpenCv()
            val keys = File(keysPath).readLines()
            val det = MnnInterpreter.fromFile(detModelPath, numThread, precision)
            val rec = MnnInterpreter.fromFile(recModelPath, numThread, precision)
            Log.i(TAG, "PaddleOcrSession: det=$detModelPath rec=$recModelPath keys=${keys.size}")
            return PaddleOcrSession(det, rec, keys)
        }
    }
}
