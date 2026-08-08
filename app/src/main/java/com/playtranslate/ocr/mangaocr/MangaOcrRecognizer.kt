package com.playtranslate.ocr.mangaocr

import android.graphics.Bitmap
import com.playtranslate.language.TextOrientation
import com.playtranslate.ocr.core.DetectedRegion
import com.playtranslate.ocr.core.OcrCapabilities
import com.playtranslate.ocr.core.OcrImage
import com.playtranslate.ocr.core.OcrOrientationSupport
import com.playtranslate.ocr.core.RecognizedLine
import com.playtranslate.ocr.core.RecognizedRegion
import com.playtranslate.ocr.core.RegionOrigin
import com.playtranslate.ocr.core.TextRecognizer
import com.playtranslate.ocr.core.synthesizeEvenCharBoxes
import com.playtranslate.ocr.core.OcrBox
import kotlinx.coroutines.Job
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.coroutines.coroutineContext
import kotlin.math.cos
import kotlin.math.sin

/**
 * [TextRecognizer] over [MangaOcrSession]. Crops the detected region from the
 * frame and recognizes it. manga-ocr emits a bare string, so the per-character
 * tier is **synthesized** ([synthesizeEvenCharBoxes]) — evenly spread across the
 * region so drag-lookup + furigana resolve (proportional, not pixel-exact); no
 * element tier. Driven by [com.playtranslate.ocr.MangaOcrRefiner] over whole
 * [com.playtranslate.ocr.core.LayoutGroup] blocks — the model's native input
 * shape — which discards the synthesized tier in favor of aligner-derived boxes
 * (and is composable with any detector via
 * [com.playtranslate.ocr.composites.DetectThenRecognize]). Caches the bitmap→BGR
 * Mat across a frame's regions. `threadSafe = false` (shared MNN session,
 * serialized by the caller's mutex).
 */
class MangaOcrRecognizer(private val session: MangaOcrSession) : TextRecognizer {

    override val capabilities = OcrCapabilities(
        orientation = OcrOrientationSupport.BOTH,
        // Char tier is synthesized (even spread) — proportional, not pixel-exact,
        // but enough for drag-lookup hit-testing + furigana placement.
        emitsCharBoxes = true,
        emitsElementBoxes = false,
        // A vision encoder-decoder reading a whole block in one pass: multi-line
        // blocks read substantially better than the same content as line crops.
        wholeRegionInput = true,
        threadSafe = false,
        selfPreprocesses = true,
        emitsSubLineBoxes = false,
    )

    private var cachedBitmap: Bitmap? = null
    private var cachedBgr: Mat? = null

    override suspend fun recognize(image: OcrImage, region: DetectedRegion): RecognizedRegion? =
        recognize(image, region, MangaOcrSession.MAX_LEN)

    /**
     * [recognize] with a decode budget: [maxTokens] caps the session's decode steps
     * (see [MangaOcrSession.recognize] — a runaway no-EOS decode is quadratic-
     * expensive and discarded anyway, so callers that know the expected text length
     * pass it). The decode also polls this coroutine's [Job] between steps, so a
     * superseded frame aborts mid-block instead of finishing a doomed decode.
     */
    suspend fun recognize(image: OcrImage, region: DetectedRegion, maxTokens: Int): RecognizedRegion? {
        val r = region.box.bounds
        val bw = image.bitmap.width; val bh = image.bitmap.height
        val x1 = r.left.coerceIn(0, bw - 1)
        val y1 = r.top.coerceIn(0, bh - 1)
        val x2 = r.right.coerceIn(x1 + 1, bw)
        val y2 = r.bottom.coerceIn(y1 + 1, bh)
        if (x2 - x1 < 2 || y2 - y1 < 2) return null

        // Rotated region: warp the oriented rect upright into an ow×oh strip
        // ([deskewAffine]) instead of submat-ing the inflated AABB — the AABB
        // crop drags in off-axis content that poisons the whole-block read.
        // Scale-1 rotation, so INTER_LINEAR is right here (DbNet's anti-LINEAR
        // note concerns downscaling; the encoder's 224² squash does the scaling
        // later); BORDER_REPLICATE extends edge pixels where the rect pokes
        // past the bitmap. Never for tategaki: rotated regions are HORIZONTAL
        // by the producer invariant, and keyed on the box, not a quad.
        val sub: Mat
        if (region.box.isRotated) {
            val ow = Math.round(region.box.orientedWidth)
            val oh = Math.round(region.box.orientedHeight)
            if (ow < 2 || oh < 2) return null
            val m = deskewAffine(region.box)
            val mat = Mat(2, 3, CvType.CV_32F)
            mat.put(0, 0, floatArrayOf(m[0], m[1], m[2], m[3], m[4], m[5]))
            sub = Mat()
            try {
                Imgproc.warpAffine(
                    bgrFor(image.bitmap), sub, mat, Size(ow.toDouble(), oh.toDouble()),
                    Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE,
                )
            } finally {
                mat.release()
            }
        } else {
            sub = bgrFor(image.bitmap).submat(y1, y2, x1, x2)
        }
        val job = coroutineContext[Job]
        val reading = try {
            session.recognize(sub, maxTokens) { job?.isActive != false }
        } finally {
            sub.release()
        }
        // Decline a truncated / runaway / aborted reading (stopped without EOS): adopting
        // a partial string would drop the tail of the base engine's complete text.
        if (reading.text.isBlank() || reading.hitCap) return null
        val text = reading.text

        // manga-ocr has no spatial output; synthesize an even per-char tier across
        // the region so line.chars is populated (drag-lookup + furigana). charOffset
        // aligns 1:1 with text (session strips spaces).
        val chars = synthesizeEvenCharBoxes(
            text = text,
            box = region.box,
            vertical = region.orientation == TextOrientation.VERTICAL,
        )
        val line = RecognizedLine(
            text = text,
            box = region.box,
            orientation = region.orientation,
            chars = chars,
        )
        return RecognizedRegion(
            text = text,
            box = region.box,
            orientation = region.orientation,
            confidence = -1f,
            lines = listOf(line),
            origin = RegionOrigin.LINE,
        )
    }

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
}

/**
 * The 2×3 affine (row-major `[a, b, tx, c, d, ty]`) that warps [box]'s oriented
 * rect upright into an `ow×oh` destination: rotation by −angleDeg about the AABB
 * center (clockwise-positive y-down convention — the rotation part equals
 * OpenCV's `getRotationMatrix2D(center, +angleDeg, 1.0)`; a negated angle
 * DOUBLES the skew instead of removing it), then a translation putting the
 * rect's center at the destination center. Pure math — unit-tested for the sign
 * without OpenCV.
 */
internal fun deskewAffine(box: OcrBox): FloatArray {
    val rad = Math.toRadians(box.angleDeg.toDouble())
    val c = cos(rad).toFloat()
    val s = sin(rad).toFloat()
    val cx = box.bounds.exactCenterX()
    val cy = box.bounds.exactCenterY()
    val ow = box.orientedWidth
    val oh = box.orientedHeight
    return floatArrayOf(
        c, s, ow / 2f - c * cx - s * cy,
        -s, c, oh / 2f + s * cx - c * cy,
    )
}
