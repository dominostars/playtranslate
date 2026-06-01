package com.playtranslate.ocr.core

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import com.playtranslate.language.TextOrientation
import kotlin.math.abs

/**
 * Vendor-neutral, pre-layout OCR model.
 *
 * Every engine ([OcrEngine]) maps its native output into these types; the
 * shared [LayoutAnalyzer] then groups [RecognizedRegion]s into the final
 * `OcrResult`. This file has NO ML Kit / MNN / OpenCV imports by design — it is
 * the contract that lets detectors, recognizers, and end-to-end engines compose.
 *
 * ## Coordinate invariant
 * Every [OcrBox] is in **original input-bitmap pixel coordinates** — the space
 * of the [Bitmap] handed to [OcrEngine.recognize]. Each engine adapter undoes
 * its own preprocessing/upscale (ML Kit's scaleFactor divide, Paddle's native
 * mapping) *before* returning, so nothing downstream — grouping, classification,
 * overlay placement — ever sees scaled coordinates.
 */

/**
 * An oriented text box. The axis-aligned [bounds] is the **primary** form (the
 * grouping kernel reads it directly as a field — no per-call compute), and
 * covers the overwhelmingly common upright case. [angleDeg] + [orientedWidth]/
 * [orientedHeight] describe the true (unrotated) rectangle for genuinely slanted
 * text (banners, stamps, SFX), used by overlay rendering and hit-testing.
 *
 * Tategaki is NOT rotation: a vertical column is an axis-aligned tall rectangle
 * with `angleDeg == 0` and [TextOrientation.VERTICAL]. Only [angleDeg] != 0 is a
 * genuine slant, and [isRotated] regions are treated as standalone by layout
 * (see [LayoutAnalyzer]).
 */
data class OcrBox(
    /** Axis-aligned bounding box in ORIGINAL bitmap coords. Read by the kernel. */
    val bounds: Rect,
    /** True (unrotated) width of the text rectangle. == bounds.width() when [angleDeg] == 0. */
    val orientedWidth: Float,
    /** True (unrotated) height of the text rectangle. == bounds.height() when [angleDeg] == 0. */
    val orientedHeight: Float,
    /** Rotation in degrees; 0 = axis-aligned (the common case). */
    val angleDeg: Float = 0f,
) {
    /** True when the box is slanted enough that upright grouping shouldn't apply. */
    val isRotated: Boolean get() = abs(angleDeg) > ROTATION_STANDALONE_DEG

    companion object {
        /** Slant threshold (degrees) past which a region is grouped standalone. */
        const val ROTATION_STANDALONE_DEG = 10f

        /** Axis-aligned box (the common case): oriented dims == AABB dims, angle 0. */
        fun upright(bounds: Rect): OcrBox =
            OcrBox(bounds, bounds.width().toFloat(), bounds.height().toFloat(), 0f)
    }
}

/** Input to any [OcrEngine] / [TextDetector] / [TextRecognizer]. */
data class OcrImage(
    /** Source bitmap. Output boxes are in this bitmap's coordinate space. */
    val bitmap: Bitmap,
    /** BCP-47-ish source language code (e.g. "ja") for script-aware filtering. */
    val sourceLang: String,
    /**
     * Full screenshot width (pre-crop), in original bitmap pixels. Drives the
     * menu-split layout heuristic's 1/3-screen test. 0 = unknown (skip the split).
     */
    val screenshotWidth: Int = 0,
)

/**
 * A detector's output for one region, before recognition. No text yet.
 * [quad] is the detector's deskew polygon (e.g. PaddleOCR DBNet's 4-point
 * min-area-rect) used to perspective-warp a tight crop for the recognizer;
 * null when the detector only produces axis-aligned boxes.
 */
data class DetectedRegion(
    val box: OcrBox,
    val quad: List<PointF>? = null,
    val orientation: TextOrientation = TextOrientation.HORIZONTAL,
    /** Detector confidence in [0,1]; -1 = unknown. */
    val confidence: Float = -1f,
)

/**
 * One recognized "element" (≈ a word / ML Kit `Text.Element`). The element tier
 * is what `OcrResult.segments` (tappable dictionary lookup) is built from, so
 * engines that provide it (ML Kit) preserve today's segment granularity;
 * engines that don't (Paddle, manga-ocr) yield line-level segments.
 */
data class ElementBox(
    val text: String,
    val box: OcrBox,
)

/**
 * One recognized character with its exact box and offset into the containing
 * line's text. Replaces `OcrManager.SymbolBox`. Empty char lists trigger the
 * proportional fallback in drag-lookup and furigana placement.
 */
data class CharBox(
    val text: String,
    val box: OcrBox,
    /** Character's position within the line's processed text string. */
    val charOffset: Int,
)

/** One recognized line of text with optional element + character tiers. */
data class RecognizedLine(
    val text: String,
    val box: OcrBox,
    val orientation: TextOrientation,
    /** Element (word) tier; drives `OcrResult.segments`. Empty if unavailable. */
    val elements: List<ElementBox> = emptyList(),
    /** Character tier; drives precise furigana + drag hit-testing. Empty if unavailable. */
    val chars: List<CharBox> = emptyList(),
    /**
     * Effective left edge for hanging-punctuation alignment compensation,
     * precomputed by the engine adapter when it has glyph data (e.g. ML Kit's
     * first-symbol box). Null = [LayoutAnalyzer] derives it (or skips it when
     * there is no text, as in pre-recognition clustering).
     */
    val effectiveAlignLeft: Int? = null,
)

/**
 * An engine's output unit, before paragraph grouping. For a line-level engine
 * (ML Kit, PaddleOCR) this is ONE line ([lines].size == 1, [origin] == LINE).
 * For a whole-region recognizer (manga-ocr) this is ONE bubble already
 * containing its lines ([origin] == WHOLE_REGION), which [LayoutAnalyzer] must
 * not re-split.
 */
data class RecognizedRegion(
    val text: String,
    val box: OcrBox,
    val orientation: TextOrientation,
    /** Recognition confidence in [0,1]; -1 = unknown (e.g. pre-API-31 ML Kit lines). */
    val confidence: Float = -1f,
    val lines: List<RecognizedLine> = emptyList(),
    val origin: RegionOrigin = RegionOrigin.LINE,
)

/**
 * Whether a [RecognizedRegion] is a line to be clustered into a paragraph
 * ([LINE]) or an already-complete unit that must not be re-grouped internally
 * ([WHOLE_REGION]). The double-grouping guard. [WHOLE_REGION] is unused by the
 * in-scope engines (ML Kit, PaddleOCR are both line-level); it exists for the
 * future whole-region recognizers (manga-ocr).
 */
enum class RegionOrigin { LINE, WHOLE_REGION }
