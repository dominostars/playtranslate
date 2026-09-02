package com.playtranslate.ocr.meiki

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.playtranslate.BuildConfig
import com.playtranslate.language.TextOrientation
import com.playtranslate.ocr.core.CharBox
import com.playtranslate.ocr.core.DetectedRegion
import com.playtranslate.ocr.core.OcrBox
import com.playtranslate.ocr.core.OcrCapabilities
import com.playtranslate.ocr.core.OcrImage
import com.playtranslate.ocr.core.OcrOrientationSupport
import com.playtranslate.ocr.core.OrientedBoxGeometry
import com.playtranslate.ocr.core.RecognizedLine
import com.playtranslate.ocr.core.RecognizedRegion
import com.playtranslate.ocr.core.RegionOrigin
import com.playtranslate.ocr.core.TextRecognizer
import com.playtranslate.ocr.mangaocr.deskewAffine
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * [TextRecognizer] over Meiki's D-FINE character-detection recognizers (vertical
 * 32×480 / horizontal 960×32, chosen by [DetectedRegion.orientation]). Crops the
 * region's upright box from the frame, recognizes it, and — the payoff over
 * PaddleOCR — **emits per-character boxes** ([CharBox]), mapped from crop-local
 * back to ORIGINAL-bitmap coords, so drag-lookup + furigana place precisely
 * instead of falling back to proportional. Caches the bitmap→BGR Mat across a
 * frame's regions (the composite feeds them sequentially). `threadSafe = false`
 * (shared MNN session, serialized by the composite mutex).
 *
 * ## Slanted text: the confidence-gated deskew retry — DISABLED
 *
 * Switched off by [SKEW_RETRY_ENABLED] on 2026-09-01 after a Thor session on
 * P3R status/skill screens: per-line slant estimates on axis-aligned crops
 * are contaminated by neighbour ink by a frame-varying amount (a three-line
 * paragraph at one angle measured −10.7 / +5.5 / −8.0 and rendered two lines
 * rotated, one upright), a detector union over a multi-element HUD widget
 * measures layout rather than a baseline and passes the guard because the
 * deskewed strip is a different crop, and the guard's own margin sits inside
 * recognizer noise (12 of 82 accepts that day won by ≤ 0.05; verdicts flipped
 * between passes on 7 of 20 boxes). A frame-level consensus pass built on top
 * did not fix the reporting screen and rotated an upright HUD label; it was
 * reverted (docs/meiki-frame-pass-2026-09-01.patch). Any revival needs
 * detector-native geometry (DBNet quads / ML Kit corner points), not pixels
 * inside an AABB. The mechanism below is kept, inert, for that record.
 *
 * Meiki's detector emits AABBs only and its recognizer reads a slanted crop as
 * garbage (the upright crop clips a long line's ends AND the aspect-fit into
 * the 32 px canvas divides glyph resolution by the slant-inflation factor —
 * half the characters of a 20-char line are lost at 3°), yet the same
 * recognizer reads a DESKEWED crop perfectly out to 30°. Since no Meiki tensor
 * carries an angle, the angle comes from the crop pixels instead
 * ([MeikiSkewEstimator]), and the whole path is gated so upright frames — the
 * overwhelming mass — pay nothing:
 *
 *  1. measure every horizontal line wide/long enough for the fit
 *     ([SKEW_MIN_WIDTH_PX], [SKEW_MIN_CHARS] — the census's icon/decoration
 *     false flags were all smaller). NOT confidence-gated: confidently-garbled
 *     slant reads score ≥ 0.8 (see [skewRetryCandidate]), and the estimator
 *     pass is one O(pixels) scan — the gates in rung 3 throttle the re-read;
 *  2. estimate on the already-cropped band, then refine on the deskewed strip
 *     up to [SKEW_RESIDUAL_PASSES] times — the residual passes recover the
 *     det-band clipping bias (~35% under-read from the band alone);
 *  3. route the measurement through [OrientedBoxGeometry.boxFor] via a
 *     synthesized quad, so the noise gate, excursion floor, and the debug
 *     rollback toggle ([OcrImage.angleNoiseGateDeg]) govern this producer
 *     exactly as they do ML Kit and Paddle — sub-gate estimates change
 *     nothing;
 *  4. re-recognize the deskewed strip and keep it ONLY if its confidence beats
 *     the upright read's ([accept-if-better]) — the terminal guard that held
 *     against every enemy class in the full-corpus census (italic fonts,
 *     icons, low-contrast banners): a wrong estimate costs one wasted forward,
 *     never a worse read.
 *
 * Validated envelope (offline, real-slant corpus): recovers |θ| ≈ 3–13° on
 * detected, unmerged lines (CER 0.49 → 0.26 band-wide); above that the det box
 * itself has clipped or merged the line and no recognizer-side fix applies
 * ([SKEW_MAX_SLANT_DEG]). Never for tategaki: rotated regions are HORIZONTAL
 * by the producer invariant.
 */
class MeikiRecognizer(private val session: MeikiSession) : TextRecognizer {

    override val capabilities = OcrCapabilities(
        orientation = OcrOrientationSupport.BOTH,
        emitsCharBoxes = true,
        emitsElementBoxes = false,
        wholeRegionInput = false,
        threadSafe = false,
        selfPreprocesses = true,
        emitsSubLineBoxes = false,
    )

    private var cachedBitmap: Bitmap? = null
    private var cachedBgr: Mat? = null

    override suspend fun recognize(image: OcrImage, region: DetectedRegion): RecognizedRegion? {
        val r = region.box.bounds
        val bw = image.bitmap.width; val bh = image.bitmap.height
        val x1 = r.left.coerceIn(0, bw - 1)
        val y1 = r.top.coerceIn(0, bh - 1)
        val x2 = r.right.coerceIn(x1 + 1, bw)
        val y2 = r.bottom.coerceIn(y1 + 1, bh)
        if (x2 - x1 < 2 || y2 - y1 < 2) return null

        val sub = bgrFor(image.bitmap).submat(y1, y2, x1, x2)
        val res: MeikiSession.RecResult
        var skewGray: ByteArray? = null
        try {
            res = session.recognize(sub, region.orientation == TextOrientation.VERTICAL)
            // Grab the grayscale for the skew estimate BEFORE the submat is
            // released; only when this read is even a retry candidate.
            if (skewRetryCandidate(region, res, x2 - x1)) skewGray = grayBytes(sub)
        } finally {
            sub.release()
        }
        if (BuildConfig.DEBUG && region.orientation == TextOrientation.HORIZONTAL &&
            !region.box.isRotated && x2 - x1 >= SKEW_MIN_WIDTH_PX &&
            (res.text.isBlank() || res.text.length < SKEW_MIN_CHARS)
        ) {
            // A wide horizontal det box whose read came back blank or near-blank
            // is the vanished-slanted-line signature (P3R skill card,
            // 耐性を無視して): the char floor keeps it out of the retry and the
            // garble filter drops the remnant downstream, so without this line
            // the disappearance is untraceable. If a line vanishes and NEITHER
            // this nor a retry line logs for it, the detector never boxed it.
            Log.d(
                TAG,
                "skew not attempted (blank/short read '${res.text}'): " +
                    "${x2 - x1}x${y2 - y1}@($x1,$y1)",
            )
        }
        if (res.text.isBlank()) return null

        skewGray?.let { gray ->
            skewRetry(image, gray, x1, y1, x2, y2, res)?.let { return it }
        }

        // Offset crop-local char boxes into original-bitmap coords.
        val chars = res.chars.map { c ->
            CharBox(
                text = c.text,
                box = OcrBox.upright(Rect(x1 + c.rect.left, y1 + c.rect.top, x1 + c.rect.right, y1 + c.rect.bottom)),
                charOffset = c.offset,
            )
        }
        val line = RecognizedLine(
            text = res.text, box = region.box, orientation = region.orientation, chars = chars,
            confidence = res.confidence,
        )
        return RecognizedRegion(
            text = res.text,
            box = region.box,
            orientation = region.orientation,
            confidence = res.confidence,
            lines = listOf(line),
            origin = RegionOrigin.LINE,
        )
    }

    /** Rung 1 of the retry: is this read worth measuring at all? Horizontal,
     *  upright-boxed, wide enough for the estimator's fit, and long enough to
     *  rule out the icon/short-label false-flag classes. Deliberately NO
     *  confidence condition: mean char confidence stays high on confidently-
     *  GARBLED slant reads (Thor, P3R activity screen: 日常生活 → 日学生活,
     *  タルタロス到達階 → ダルダロス到達態, both ≥ 0.8), so a confidence
     *  ceiling here silently exempts exactly the reads the retry exists to
     *  fix. The estimator is one O(pixels) pass — cheap enough to run on
     *  every candidate — and the angle/excursion gates below still throttle
     *  the expensive re-read; confidence guards ACCEPTANCE instead. */
    private fun skewRetryCandidate(
        region: DetectedRegion,
        res: MeikiSession.RecResult,
        cropWidth: Int,
    ): Boolean =
        SKEW_RETRY_ENABLED &&
            region.orientation == TextOrientation.HORIZONTAL &&
            !region.box.isRotated &&
            cropWidth >= SKEW_MIN_WIDTH_PX &&
            res.text.length >= SKEW_MIN_CHARS

    /**
     * Rungs 2–4: estimate (twice — the second pass on the deskewed strip
     * recovers the det-band clipping bias), gate through [OrientedBoxGeometry.boxFor],
     * re-recognize the deskewed strip, accept only on a confidence win.
     * Null = keep the upright result (every decline path lands here).
     */
    private fun skewRetry(
        image: OcrImage,
        gray: ByteArray,
        x1: Int, y1: Int, x2: Int, y2: Int,
        base: MeikiSession.RecResult,
    ): RecognizedRegion? {
        val w = x2 - x1
        val h = y2 - y1
        val est1 = MeikiSkewEstimator.estimate(gray, w, h)
        // Below this there is nothing a second pass can amplify past the noise
        // gate: the estimator's observed under-read never exceeds ~50%.
        if (abs(est1) < SKEW_ITERATE_MIN_DEG) return null
        val cx = (x1 + x2) / 2f
        val cy = (y1 + y2) / 2f

        // Residual passes: each deskew-and-re-measure recovers magnitude the
        // det-band clipping hid from the previous pass (the estimate converges
        // from below, never overshoots past noise). Two passes, corpus-tuned:
        // mean |angle error| 4.6°→3.5° and loop CER 0.36→0.31 over the P5
        // real-slant set vs one pass; a third pass bought nothing further.
        // Each pass costs one warpAffine + one O(pixels) scan — no extra
        // recognizer forward.
        var estTotal = est1
        for (pass in 0 until SKEW_RESIDUAL_PASSES) {
            val residual = estimateOnStrip(image.bitmap, cx, cy, w, h, estTotal)
            if (abs(residual) < SKEW_RESIDUAL_CONVERGED_DEG) break
            estTotal += residual
        }

        val quad = rotatedRectQuad(cx, cy, w.toFloat(), h.toFloat(), estTotal)
        val box = OrientedBoxGeometry.boxFor(
            quadAabb(quad), quad, TextOrientation.HORIZONTAL,
            maxSlantDeg = SKEW_MAX_SLANT_DEG,
            minSlantDeg = image.angleNoiseGateDeg,
        )
        if (!box.isRotated) {
            if (BuildConfig.DEBUG) {
                // Which rung declined is boxFor's business; log the inputs it
                // judged (total + width ⇒ the reader can compute the excursion).
                Log.d(
                    TAG,
                    "skew declined: est=${"%.1f".format(estTotal)} w=$w " +
                        "(gates: min=${image.angleNoiseGateDeg}°, " +
                        "excursion ${OcrBox.ANGLE_MIN_EXCURSION_PX}px, max=${SKEW_MAX_SLANT_DEG}°)",
                )
            }
            return null
        }

        val strip = warpStrip(image.bitmap, box) ?: return null
        val retry = try {
            session.recognize(strip, vertical = false)
        } finally {
            strip.release()
        }
        val accepted = skewRetryAccepted(base, retry)
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "skew retry: est=${"%.1f".format(estTotal)} " +
                    "conf ${"%.2f".format(base.confidence)} -> ${"%.2f".format(retry.confidence)} " +
                    (if (accepted) "ACCEPTED" else "kept upright"),
            )
        }
        if (!accepted) return null

        // Strip-local char boxes map back through the rotation to upright
        // per-glyph AABBs in frame coords — the same char-tier shape the other
        // rotated producer (ML Kit) emits for slanted lines.
        val chars = retry.chars.map { c ->
            CharBox(
                text = c.text,
                box = OcrBox.upright(stripRectToFrameAabb(box, c.rect)),
                charOffset = c.offset,
            )
        }
        val line = RecognizedLine(
            text = retry.text, box = box, orientation = TextOrientation.HORIZONTAL,
            chars = chars, confidence = retry.confidence,
        )
        return RecognizedRegion(
            text = retry.text,
            box = box,
            orientation = TextOrientation.HORIZONTAL,
            confidence = retry.confidence,
            lines = listOf(line),
            origin = RegionOrigin.LINE,
        )
    }

    /** Residual slant of the [est1]-deskewed strip: warps the oriented rect
     *  upright and re-measures. On the deskewed strip the ink is no longer
     *  clipped by the det band, so this second read recovers the magnitude the
     *  first one under-measured. */
    private fun estimateOnStrip(bitmap: Bitmap, cx: Float, cy: Float, w: Int, h: Int, est1: Float): Float {
        val half = rotatedRectQuad(cx, cy, w.toFloat(), h.toFloat(), est1)
        val probe = OcrBox(quadAabb(half), w.toFloat(), h.toFloat(), est1)
        val strip = warpStrip(bitmap, probe) ?: return 0f
        return try {
            val grayMat = Mat()
            Imgproc.cvtColor(strip, grayMat, Imgproc.COLOR_BGR2GRAY)
            val buf = ByteArray(grayMat.cols() * grayMat.rows())
            grayMat.get(0, 0, buf)
            val est = MeikiSkewEstimator.estimate(buf, grayMat.cols(), grayMat.rows())
            grayMat.release()
            est
        } finally {
            strip.release()
        }
    }

    /** Warp [box]'s oriented rect upright into an ow×oh strip — the same
     *  scale-1 rotation as MangaOcrRecognizer's rotated branch (INTER_LINEAR;
     *  BORDER_REPLICATE where the rect pokes past the bitmap). Reads from the
     *  full frame, so the strip recovers line ends the axis-aligned det band
     *  had clipped away. */
    private fun warpStrip(bitmap: Bitmap, box: OcrBox): Mat? {
        val ow = box.orientedWidth.roundToInt()
        val oh = box.orientedHeight.roundToInt()
        if (ow < 2 || oh < 2) return null
        val m = deskewAffine(box)
        val mat = Mat(2, 3, CvType.CV_32F)
        mat.put(0, 0, floatArrayOf(m[0], m[1], m[2], m[3], m[4], m[5]))
        val out = Mat()
        try {
            Imgproc.warpAffine(
                bgrFor(bitmap), out, mat, Size(ow.toDouble(), oh.toDouble()),
                Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE,
            )
        } finally {
            mat.release()
        }
        return out
    }

    /** Grayscale bytes of [sub] for the estimator (cvtColor's output is
     *  continuous even when the submat isn't). */
    private fun grayBytes(sub: Mat): ByteArray {
        val grayMat = Mat()
        Imgproc.cvtColor(sub, grayMat, Imgproc.COLOR_BGR2GRAY)
        val buf = ByteArray(grayMat.cols() * grayMat.rows())
        grayMat.get(0, 0, buf)
        grayMat.release()
        return buf
    }

    /** RGBA→BGR Mat for [bitmap], reused while the same bitmap's regions process. */
    private fun bgrFor(bitmap: Bitmap): Mat {
        cachedBgr?.let { if (bitmap === cachedBitmap) return it }
        cachedBgr?.release()
        val rgba = Mat().also { Utils.bitmapToMat(bitmap, it) }
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        cachedBitmap = bitmap; cachedBgr = bgr
        return bgr
    }

    override fun close() {
        cachedBgr?.release(); cachedBgr = null; cachedBitmap = null
    }

    companion object {
        private const val TAG = "MeikiRecognizer"

        /** Master switch for the deskew retry (class kdoc, "DISABLED"). Off:
         *  no estimator pass, no re-read, Meiki emits upright boxes only —
         *  the pre-b77069e4 behaviour. ML Kit and Paddle angles, which come
         *  from detector geometry, are unaffected. */
        internal const val SKEW_RETRY_ENABLED = false

        /**
         * Acceptance for the deskewed re-read: a confidence win AND length
         * coverage of the base read. Mean per-char confidence carries no
         * length term, and the deskew warp is center-pinned — glyphs near the
         * pivot stay legible under a WRONG angle estimate — so a bad estimate
         * can yield a few clean center glyphs whose mean confidence beats a
         * fuller garbled base; without the coverage floor that fragment would
         * replace the fuller text (adversarial-review finding, 2026-08-12).
         * The floor is a ratio, not `>=`: legitimate recoveries can be
         * SHORTER than a hallucination-padded base garble (device: 'Fromn' →
         * 'From'), and every legitimate accept observed on corpus + device
         * retained well above this fraction.
         */
        internal fun skewRetryAccepted(
            base: MeikiSession.RecResult,
            retry: MeikiSession.RecResult,
        ): Boolean =
            retry.text.isNotBlank() &&
                retry.confidence > base.confidence &&
                // ceil, not floor: flooring the threshold inverted the floor's
                // purpose at the margin (a 4-char base × 0.7 floored to 2 —
                // 50% coverage where 70% is promised; short bases are exactly
                // where retry candidates are common).
                retry.text.length >= ceil(base.text.length * SKEW_ACCEPT_MIN_LENGTH_RATIO).toInt()

        /** See [skewRetryAccepted]. */
        internal const val SKEW_ACCEPT_MIN_LENGTH_RATIO = 0.7f

        /** Estimator floor: the full-corpus census's icon/decoration false
         *  flags were all ≤ 48 px wide (junk up to 114 px); real recoverable
         *  slants are lines, not chips. */
        internal const val SKEW_MIN_WIDTH_PX = 120

        /** Short-run floor: italic glyph shear masquerades as slant on 2–3
         *  char runs (FF7R HP digits class), and det-clipped short stylized
         *  labels are beyond this branch's reach anyway. */
        internal const val SKEW_MIN_CHARS = 4

        /** No first-pass signal below this: iteration amplifies a measurement,
         *  it cannot conjure one. */
        internal const val SKEW_ITERATE_MIN_DEG = 1f

        /** Residual deskew-and-re-measure passes after the first estimate (see
         *  the loop in [skewRetry] for the corpus numbers behind 2). */
        internal const val SKEW_RESIDUAL_PASSES = 2

        /** A residual below this is converged — further passes measure noise. */
        internal const val SKEW_RESIDUAL_CONVERGED_DEG = 0.3f

        /** Validated recovery ceiling: past ~14° Meiki's detector has clipped
         *  the line to a fraction of its length (or merged/lost it) and a
         *  deskewed re-read of the det rect cannot reach the missing text. */
        internal const val SKEW_MAX_SLANT_DEG = 15f
    }
}
