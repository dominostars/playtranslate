package com.playtranslate.ocr.paddle

import android.graphics.Rect
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * DBNet detector postprocessing + crop rectification via OpenCV — the geometry
 * half of [PaddleOcrSession]'s pipeline. Pure functions; no MNN, no model state.
 * Production: backs the PaddleOCR detector/recognizer path (Russian + opt-in CJK),
 * not test-only.
 *
 * Turns the detector's probability map into oriented text-region quadrilaterals
 * (in original-bitmap coordinates) and warps each into a horizontal strip the
 * recognizer can read. Mirrors PaddleOCR's `DBPostProcess` + `get_rotate_crop_image`.
 *
 * The unclip step uses a min-area-rect grow (distance = area·ratio/perimeter)
 * rather than a Vatti/pyclipper polygon offset — a standard approximation for
 * rectangular text boxes when pyclipper isn't on the classpath. Adequate for
 * the spike; flagged so it isn't mistaken for an exact port.
 *
 * Thresholds are the canonical PP-OCRv5 values — VERIFY against the det
 * model's inference.yml `PostProcess` block (see [PaddleOcrSession] kdoc).
 */
internal object DbNet {

    private const val BIN_THRESH = 0.3       // DBPostProcess.thresh
    private const val BOX_THRESH = 0.6       // DBPostProcess.box_thresh
    // Box dilation. PP default 1.5 expands all four sides symmetrically; on tall
    // vertical (tategaki) boxes that pushes the long sides into the ADJACENT
    // column, so warped crops pick up neighboring text and the recognizer reads
    // garbage. Lowered to 1.1 to keep crops tight to their own column. (distance
    // scales with this ratio, so 1.5→1.1 cuts the sideways bleed ~3.6×.)
    private const val UNCLIP_RATIO = 1.1     // DBPostProcess.unclip_ratio (PP default 1.5)
    private const val MIN_SIZE = 3.0         // reject boxes whose short side < this (det-map px)
    private const val MAX_CANDIDATES = 1000
    // Aspect past which a deskewed quad is a vertical (tategaki) column, which
    // [warpCrop] rotates 90° CCW into a horizontal strip. SINGLE SOURCE OF TRUTH:
    // the recognizer keys its char-box axis off [isVerticalQuad] (this same test on
    // the QUAD), so synthesized char geometry can never silently transpose against
    // the rotation warpCrop actually applied. (The detector's orientation *label*
    // is a separate AABB-based test — it tags the line, but must NOT drive geometry.)
    private const val VERTICAL_ASPECT = 1.5

    /** A detected text region: [points] are the 4 corners in ORIGINAL-bitmap
     *  coordinates; [aabb] is their axis-aligned bounding box. */
    class Box(val points: Array<Point>, val aabb: Rect)

    /**
     * @param prob   detector output, row-major [h*w], values 0..1
     * @param h,w    probability-map dims (== det-resized dims)
     * @param scaleX,scaleY  det-resized / original ratio (prob_coord / scale = original_coord)
     */
    fun boxesFromProbMap(
        prob: FloatArray, h: Int, w: Int,
        scaleX: Float, scaleY: Float,
        origW: Int, origH: Int,
    ): List<Box> {
        if (h * w > prob.size) return emptyList()
        val probMat = Mat(h, w, CvType.CV_32F)
        probMat.put(0, 0, prob)

        // binarize → CV_8U {0,255}
        val bin = Mat()
        Imgproc.threshold(probMat, bin, BIN_THRESH, 255.0, Imgproc.THRESH_BINARY)
        bin.convertTo(bin, CvType.CV_8UC1)

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(bin, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        val boxes = ArrayList<Box>()
        var count = 0
        for (contour in contours) {
            if (count++ >= MAX_CANDIDATES) break
            val c2f = MatOfPoint2f(*contour.toArray())
            val rect = Imgproc.minAreaRect(c2f)
            val shortSide = min(rect.size.width, rect.size.height)
            if (shortSide < MIN_SIZE) { c2f.release(); continue }

            // score: mean probability inside the contour (PaddleOCR box_score_fast)
            val score = boxScoreFast(probMat, contour, w, h)
            if (score < BOX_THRESH) { c2f.release(); continue }

            // unclip (grow the rect), reject if it collapses
            val grown = unclip(rect) ?: run { c2f.release(); null } ?: continue
            if (min(grown.size.width, grown.size.height) < MIN_SIZE + 2) { c2f.release(); continue }

            val pts = arrayOf(Point(), Point(), Point(), Point())
            grown.points(pts)
            // map det-map coords → original coords
            val orig = Array(4) { Point(pts[it].x / scaleX, pts[it].y / scaleY) }
            boxes += Box(orig, aabbOf(orig, origW, origH))
            c2f.release()
        }
        probMat.release(); bin.release()
        return boxes
    }

    /** Warp the quad [pts] (original-image coords) out of [srcMat] into a
     *  horizontal strip of height [targetH]; rotates tall (vertical) boxes 90°.
     *  Returns null if degenerate. Caller releases the returned Mat. */
    fun warpCrop(srcMat: Mat, pts: Array<Point>, targetH: Int): Mat? {
        val o = orderPoints(pts)
        val widthTop = dist(o[0], o[1]); val widthBot = dist(o[3], o[2])
        val heightL = dist(o[0], o[3]); val heightR = dist(o[1], o[2])
        val boxW = max(widthTop, widthBot)
        val boxH = max(heightL, heightR)
        if (boxW < 1 || boxH < 1) return null

        val src = MatOfPoint2f(o[0], o[1], o[2], o[3])
        val dst = MatOfPoint2f(
            Point(0.0, 0.0), Point(boxW, 0.0), Point(boxW, boxH), Point(0.0, boxH)
        )
        val m = Imgproc.getPerspectiveTransform(src, dst)
        val warped = Mat()
        Imgproc.warpPerspective(srcMat, warped, m, Size(boxW, boxH))
        src.release(); dst.release(); m.release()

        var cur = warped
        // Tall box → vertical (tategaki) text, which reads top-to-bottom.
        // Rotate COUNTER-clockwise so the top character maps to the LEFT of the
        // strip: the horizontal recognizer reads the column in true top→bottom
        // order. (A CW experiment was tried to test a small-kana positional
        // hypothesis — it COLLAPSED most vertical columns to empty output, i.e.
        // CW is the wrong orientation for the recognizer. Reverted. Direction is
        // load-bearing for recognition working at all, not a small-kana lever.)
        if (isVerticalWH(boxW, boxH)) {
            val rot = Mat()
            Core.rotate(cur, rot, Core.ROTATE_90_COUNTERCLOCKWISE)
            cur.release(); cur = rot
        }
        val finalW = max(1, (targetH * cur.cols().toDouble() / cur.rows()).roundToInt())
        val out = Mat()
        // Direction-aware interpolation to preserve fine high-frequency detail
        // (dakuten/handakuten marks — the dominant kana error). Default
        // INTER_LINEAR aliases those 2-px marks away when shrinking and blurs
        // edges when enlarging. INTER_AREA is the correct anti-aliased filter
        // for downscale (the common case: game dialogue taller than 48px);
        // INTER_CUBIC keeps edges crisp when upscaling small UI text.
        val interp = if (cur.rows() > targetH) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC
        Imgproc.resize(cur, out, Size(finalW.toDouble(), targetH.toDouble()), 0.0, 0.0, interp)
        cur.release()
        return out
    }

    /** Whether [warpCrop] rotates a quad of these deskewed dims as a vertical column. */
    private fun isVerticalWH(boxW: Double, boxH: Double): Boolean = boxH > boxW * VERTICAL_ASPECT

    /** Whether [warpCrop] will rotate [pts] as a vertical (tategaki) column — i.e. the
     *  recognition strip reads top→bottom, so timestep 0 maps to the column TOP. Mirrors
     *  the dimension computation in [warpCrop], keyed off the QUAD (not an AABB) so it
     *  matches the strip the recognizer actually sees. Used to set the char-box axis. */
    internal fun isVerticalQuad(pts: Array<Point>): Boolean {
        val o = orderPoints(pts)
        val boxW = max(dist(o[0], o[1]), dist(o[3], o[2]))
        val boxH = max(dist(o[0], o[3]), dist(o[1], o[2]))
        return isVerticalWH(boxW, boxH)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun boxScoreFast(prob: Mat, contour: MatOfPoint, w: Int, h: Int): Double {
        val arr = contour.toArray()
        var xmin = Int.MAX_VALUE; var xmax = Int.MIN_VALUE
        var ymin = Int.MAX_VALUE; var ymax = Int.MIN_VALUE
        for (p in arr) {
            xmin = min(xmin, floor(p.x).toInt()); xmax = max(xmax, ceil(p.x).toInt())
            ymin = min(ymin, floor(p.y).toInt()); ymax = max(ymax, ceil(p.y).toInt())
        }
        xmin = xmin.coerceIn(0, w - 1); xmax = xmax.coerceIn(0, w - 1)
        ymin = ymin.coerceIn(0, h - 1); ymax = ymax.coerceIn(0, h - 1)
        if (xmax < xmin || ymax < ymin) return 0.0

        val rw = xmax - xmin + 1; val rh = ymax - ymin + 1
        val mask = Mat.zeros(rh, rw, CvType.CV_8UC1)
        val shifted = ArrayList<Point>(arr.size)
        for (p in arr) shifted += Point(p.x - xmin, p.y - ymin)
        val poly = MatOfPoint(*shifted.toTypedArray())
        Imgproc.fillPoly(mask, listOf(poly), Scalar(1.0))
        val sub = prob.submat(ymin, ymax + 1, xmin, xmax + 1)
        val mean = Core.mean(sub, mask).`val`[0]
        mask.release(); poly.release(); sub.release()
        return mean
    }

    private fun unclip(rect: org.opencv.core.RotatedRect): org.opencv.core.RotatedRect? {
        val area = rect.size.width * rect.size.height
        val perim = 2.0 * (rect.size.width + rect.size.height)
        if (perim < 1e-3) return null
        val distance = area * UNCLIP_RATIO / perim
        return org.opencv.core.RotatedRect(
            rect.center,
            Size(rect.size.width + 2 * distance, rect.size.height + 2 * distance),
            rect.angle,
        )
    }

    /** Order 4 points as tl, tr, br, bl using the sum/diff heuristic. */
    private fun orderPoints(p: Array<Point>): Array<Point> {
        val bySum = p.sortedBy { it.x + it.y }
        val tl = bySum.first(); val br = bySum.last()
        val byDiff = p.sortedBy { it.y - it.x }
        val tr = byDiff.first(); val bl = byDiff.last()
        return arrayOf(tl, tr, br, bl)
    }

    private fun dist(a: Point, b: Point): Double = hypot(a.x - b.x, a.y - b.y)

    private fun aabbOf(pts: Array<Point>, maxW: Int, maxH: Int): Rect {
        var l = Double.MAX_VALUE; var t = Double.MAX_VALUE
        var r = -Double.MAX_VALUE; var b = -Double.MAX_VALUE
        for (p in pts) { l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y) }
        return Rect(
            l.toInt().coerceIn(0, maxW - 1),
            t.toInt().coerceIn(0, maxH - 1),
            r.roundToInt().coerceIn(1, maxW),
            b.roundToInt().coerceIn(1, maxH),
        )
    }
}
