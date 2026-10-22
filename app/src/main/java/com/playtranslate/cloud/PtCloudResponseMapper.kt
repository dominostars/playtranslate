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
     * Each line's address in its group's text ([OcrManager.LineBox.textStart],
     * which the furigana builder slices the group's annotation with) is the
     * wire's `text_start`. A server that predates the field sends none, so
     * every line of a wrapped group reads 0, the first line's address; the
     * lines are then addressed here ([addressLines]) by scanning the group's
     * text for each line's text in order, which is the join's own result
     * where the join's trim was a no-op, as the normalizer's edge strip
     * makes it.
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
                lines = addressLines(g.text, g.lines.map { line -> line.toLineBox(groupIndex = g.id, scale = scale) }),
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

    /**
     * [lines] with their addresses in [groupText] filled in where the wire
     * gave none: a group of more than one line whose lines all read 0. Each
     * line's text is sought from where the previous one ended (a moving
     * cursor), so a repeated line gets its own address; a line not found
     * from there keeps 0 and leaves the cursor, which cannot happen with the
     * server's grouping, whose group text is the lines' join. A group whose
     * wire carried the addresses, or a single line (0 is right), is
     * returned as it is.
     */
    internal fun addressLines(groupText: String, lines: List<OcrManager.LineBox>): List<OcrManager.LineBox> {
        if (lines.size < 2 || lines.any { it.textStart != 0 }) return lines
        var cursor = 0
        return lines.map { line ->
            val at = if (line.text.isEmpty()) -1 else groupText.indexOf(line.text, cursor)
            if (at < 0) return@map line
            cursor = at + line.text.length
            if (at == 0) line else line.copy(textStart = at)
        }
    }

    private fun WireLine.toLineBox(groupIndex: Int, scale: Float) = OcrManager.LineBox(
        text = text,
        bounds = bounds.toRect(scale),
        groupIndex = groupIndex,
        textStart = textStart,
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
