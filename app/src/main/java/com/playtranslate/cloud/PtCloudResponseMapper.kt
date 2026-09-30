package com.playtranslate.cloud

import android.graphics.Rect
import com.playtranslate.OcrManager
import com.playtranslate.language.TextAlignment
import com.playtranslate.language.TextOrientation
import com.playtranslate.model.TextSegments

/**
 * Maps part 1 onto the app's OCR result, the same shape
 * [OcrManager.recognise] returns. The server runs a copy of the app's own
 * grouping, so this is a straight projection, never a regrouping.
 */
object PtCloudResponseMapper {

    /**
     * [part]'s groups as an [OcrManager.OcrResult] in the captured bitmap's
     * coordinates, or null when it holds no text (no groups, or only blank
     * ones), the same "no text" contract as [OcrManager.recognise].
     *
     * [scale] is uploaded pixels per bitmap pixel ([EncodedFrame.scale]).
     * Every box is divided by it uniformly and truncated to whole pixels the
     * way the device path maps its engine-input boxes back; a uniform scale
     * leaves the slant unchanged, so angles pass through and only the
     * oriented dims are divided. `fullText` and `segments` are assembled
     * exactly as the device path does, from the groups' texts.
     *
     * Throws the bad-response [PtCloudReadException] unless [part] is a
     * part 1 in schema [PT_CLOUD_SCHEMA_VERSION].
     */
    fun toOcrResult(part: OcrPart, scale: Float): OcrManager.OcrResult? {
        PtCloudErrors.requireOcrPart(part)
        if (part.groups.isEmpty()) return null
        val groups = part.groups.map { g ->
            OcrManager.OcrGroup(
                text = g.text,
                bounds = g.bounds.toRect(scale),
                orientation = orientationOf(g.orientation),
                alignment = if (g.alignment == "center") TextAlignment.CENTER else TextAlignment.LEFT,
                lines = g.lines.map { line -> line.toLineBox(groupIndex = g.id, scale = scale) },
                angleDeg = g.angle.toFloat(),
                orientedWidth = scaleDim(g.ow, scale),
                orientedHeight = scaleDim(g.oh, scale),
                drawBounds = (g.drawBounds ?: g.bounds).toRect(scale),
            )
        }
        val fullText = groups.joinToString(" ") { it.text }.trim()
        if (fullText.isBlank()) return null
        return OcrManager.OcrResult(
            fullText = fullText,
            segments = TextSegments.ofGroupTexts(groups.map { it.text }),
            groups = groups,
            engineBackend = null,
            mangaOcrUsed = false,
        )
    }

    private fun WireLine.toLineBox(groupIndex: Int, scale: Float) = OcrManager.LineBox(
        text = text,
        bounds = bounds.toRect(scale),
        groupIndex = groupIndex,
        elements = elements.map { OcrManager.ElementBox(it.text, it.bounds.toRect(scale)) },
        symbols = chars.map { OcrManager.SymbolBox(it.ch, it.bounds.toRect(scale), charOffset = it.offset) },
        orientation = orientationOf(orientation),
        confidence = conf.toFloat(),
        angleDeg = angle.toFloat(),
        orientedWidth = scaleDim(ow, scale),
        orientedHeight = scaleDim(oh, scale),
    )

    private fun orientationOf(wire: String) =
        if (wire == "v") TextOrientation.VERTICAL else TextOrientation.HORIZONTAL

    /** Uploaded-image box to bitmap box: divide by [scale] and truncate,
     *  as [OcrManager]'s own back-mapping does. */
    private fun WireBox.toRect(scale: Float): Rect =
        if (scale == 1f) Rect(left, top, right, bottom)
        else Rect((left / scale).toInt(), (top / scale).toInt(), (right / scale).toInt(), (bottom / scale).toInt())

    private fun scaleDim(v: Double, scale: Float): Float = if (scale == 1f) v.toFloat() else (v / scale).toFloat()
}
