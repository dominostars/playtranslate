package com.playtranslate.ocr.engines.mlkit

import android.graphics.PointF
import com.google.mlkit.vision.text.Text
import com.playtranslate.OcrManager
import com.playtranslate.ocr.core.CharBox
import com.playtranslate.ocr.core.ElementBox
import com.playtranslate.ocr.core.OcrBox
import com.playtranslate.ocr.core.OrientedBoxGeometry
import com.playtranslate.ocr.core.RecognizedLine
import com.playtranslate.ocr.core.RecognizedRegion
import com.playtranslate.ocr.core.RegionOrigin

/**
 * Maps ML Kit's [Text] into the vendor-neutral model — ONE [RecognizedRegion] per
 * ML Kit line ([RegionOrigin.LINE]). This now owns only what is irreducibly
 * ML-Kit-specific: building the element (word) + character tiers from ML Kit's
 * symbol soup ([extractElementSymbols], offset-aligned [CharBox]es), inserting word
 * spaces for whitespace-separated scripts, and orientation detection.
 *
 * Text cleaning that used to live here — pipe-trim, UI-decoration stripping, and the
 * line noise/garble filter — moved to the shared, vendor-neutral
 * [com.playtranslate.ocr.core.RecognizedTextNormalizer] so every engine gets it; the
 * hanging-punctuation align hint is computed on demand in
 * [com.playtranslate.ocr.core.LayoutAnalyzer.effectiveAlignLeft].
 *
 * Coordinates are in the INPUT bitmap's space — i.e. the (possibly preprocessed/
 * upscaled) bitmap ML Kit was given. `OcrPipeline` owns preprocessing and reports
 * the factor; `OcrManager.buildOcrResult` divides the final `OcrResult` boxes back
 * to original-bitmap coordinates, so this mapper does NOT divide by any scaleFactor.
 */
object MlKitTextMapper {

    /**
     * Convert [visionText] to per-line regions. [addWordSpaces] inserts spaces
     * between elements for whitespace-separated source languages (mirrors the
     * `wordsSeparatedByWhitespace` profile flag handled in the former walk).
     */
    fun map(visionText: Text, sourceLang: String, addWordSpaces: Boolean): List<RecognizedRegion> {
        val out = mutableListOf<RecognizedRegion>()
        for (block in visionText.textBlocks) {
            // ML Kit's one cleaning input the shared normalizer can't re-derive: a block
            // whose language ML Kit couldn't classify ("und") is where a lone
            // non-kanji glyph is most likely a stray OCR fragment. Carried per region.
            val blockLang = block.recognizedLanguage
            val languageUndetermined = blockLang == "und"
            for (line in block.lines) {
                val bb = line.boundingBox ?: continue
                val walked = walkLine(line, addWordSpaces)
                if (walked.text.isBlank()) continue
                val orientation = OcrManager.detectOrientation(line)
                // Slant from the rotated corner quad (angle + true dims in one
                // self-consistent source; `line.angle` alone can't give dims).
                // Same defensive shape as detectOrientation's angle read — the
                // GMS-delivered recognizer behind this API varies by device.
                val quad = try {
                    line.cornerPoints?.map { PointF(it.x.toFloat(), it.y.toFloat()) }
                } catch (_: Throwable) {
                    null
                }
                val box = OrientedBoxGeometry.boxFor(bb, quad, orientation)
                val recLine = RecognizedLine(
                    text = walked.text,
                    box = box,
                    orientation = orientation,
                    elements = walked.elements,
                    chars = walked.chars,
                    confidence = lineConfidence(line),
                )
                out += RecognizedRegion(
                    text = walked.text,
                    box = box,
                    orientation = orientation,
                    confidence = lineConfidence(line),
                    lines = listOf(recLine),
                    origin = RegionOrigin.LINE,
                    languageUndetermined = languageUndetermined,
                )
            }
        }
        return out
    }

    /** ML Kit populates line confidence only when the GMS-delivered
     *  recognizer is new enough (Play services 22.30+, mid-2022); the
     *  DOCUMENTED unavailable sentinel is 0, and platform version is not
     *  the axis (an Android 10 device with current GMS computes real
     *  confidences — the old SDK>=31 gate needlessly disabled the camera's
     *  quality gate exactly on the low-end devices with the blurriest
     *  frames). Map exactly-0 to the pipeline's own unknown sentinel: an
     *  ancient-GMS device degrades to gate-skipped instead of every line
     *  reading as confidence-0 and being dropped, and a genuine 0.0 read
     *  is practically nonexistent (garbage reads land ~0.1-0.4). */
    private fun lineConfidence(line: Text.Line): Float =
        line.confidence.takeIf { it > 0f } ?: -1f

    private class Walked(
        val text: String,
        val elements: List<ElementBox>,
        val chars: List<CharBox>,
    )

    /**
     * Walk a line's elements: insert word spaces for whitespace-separated scripts and
     * collect per-element boxes (drives `segments`) + per-character symbols (drives
     * drag-lookup + furigana). Text cleaning is no longer done here — the assembled
     * line is cleaned by the shared
     * [com.playtranslate.ocr.core.RecognizedTextNormalizer]. Coordinates are kept in
     * input-bitmap space.
     */
    private fun walkLine(line: Text.Line, addWordSpaces: Boolean): Walked {
        val lineBuilder = StringBuilder()
        val elements = mutableListOf<ElementBox>()
        val chars = mutableListOf<CharBox>()
        var lineCharCount = 0
        line.elements.forEach { element ->
            val text = element.text
            if (text.isNotEmpty()) {
                if (addWordSpaces && lineCharCount > 0) {
                    lineBuilder.append(' ')
                    lineCharCount++
                }
                val elementOffset = lineCharCount
                lineBuilder.append(text)
                lineCharCount += text.length
                element.boundingBox?.let { ebb ->
                    elements += ElementBox(text = text, box = OcrBox.upright(ebb))
                }
                chars += extractElementSymbols(element, text, elementOffset)
            }
        }
        return Walked(lineBuilder.toString(), elements, chars)
    }

    /**
     * Per-character [CharBox]es for each character in [processedText], aligned by
     * a match-and-advance over ML Kit's symbols (whose order isn't guaranteed
     * left-to-right on some inputs). [startOffset] is the element's offset within
     * the line text. Coordinates kept in input-bitmap space.
     */
    private fun extractElementSymbols(
        element: Text.Element,
        processedText: String,
        startOffset: Int,
    ): List<CharBox> {
        val out = mutableListOf<CharBox>()
        val rawSymbols = element.symbols
        var symIdx = 0
        for ((charIdx, ch) in processedText.withIndex()) {
            while (symIdx < rawSymbols.size) {
                val sym = rawSymbols[symIdx]
                symIdx++
                if (sym.text == ch.toString()) {
                    sym.boundingBox?.let { sbb ->
                        out += CharBox(
                            text = sym.text,
                            box = OcrBox.upright(sbb),
                            charOffset = startOffset + charIdx,
                        )
                    }
                    break
                }
            }
        }
        return out
    }
}
