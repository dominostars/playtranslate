package com.playtranslate.ocr.engines.mlkit

import com.google.mlkit.vision.text.Text
import com.playtranslate.OcrManager
import com.playtranslate.ocr.core.CharBox
import com.playtranslate.ocr.core.ElementBox
import com.playtranslate.ocr.core.OcrBox
import com.playtranslate.ocr.core.RecognizedLine
import com.playtranslate.ocr.core.RecognizedRegion
import com.playtranslate.ocr.core.RegionOrigin

/**
 * Maps ML Kit's [Text] into the vendor-neutral model — ONE [RecognizedRegion]
 * per kept ML Kit line ([RegionOrigin.LINE]). All ML-Kit-specific quirk handling
 * lives here:
 *  - the line-level garbling / single-char filter (needs ML Kit's
 *    `recognizedLanguage` + `confidence`, which aren't vendor-neutral),
 *  - per-element pipe-trim + UI-decoration stripping (dialogue cursors/borders),
 *  - per-character symbol extraction (offset-aligned [CharBox]es),
 *  - orientation detection and the hanging-punctuation align hint.
 *
 * Coordinates are in the INPUT bitmap's space — i.e. the (possibly preprocessed/
 * upscaled) bitmap ML Kit was given. The `OcrPipeline` owns preprocessing and
 * normalizes the final `OcrResult` boxes back to original-bitmap coordinates, so
 * this mapper does NOT divide by any scaleFactor.
 *
 * This is a faithful extraction of the former `OcrManager.recognise` element
 * walk + the `groupLinesByProximity` line filter; behavior is verified
 * end-to-end by the group-structure snapshot diff. `detectOrientation`,
 * `effectiveAlignLeft`, and `isSourceLangChar` are reused from [OcrManager] for
 * now (they are ML-Kit-typed / shared language logic); a later cleanup can
 * relocate them once the keystone behavior is locked.
 */
object MlKitTextMapper {

    /** UI-only symbols that are never meaningful dialogue text on their own. */
    private val UI_DECORATION_CHARS = setOf(
        // Arrows / triangles used as dialogue-advance or selection cursors
        '▼', '▽', '▲', '△', '▸', '▾', '◂', '◀', '▶', '►', '◄',
        '↓', '↑', '←', '→', '↵', '↩',
        // Angle brackets used as decorative dialogue borders
        '<', '>', '＜', '＞', '〈', '〉', '《', '》', '«', '»'
    )

    /**
     * Convert [visionText] to per-line regions. [addWordSpaces] inserts spaces
     * between elements for whitespace-separated source languages (mirrors the
     * `wordsSeparatedByWhitespace` profile flag handled in the former walk).
     */
    fun map(visionText: Text, sourceLang: String, addWordSpaces: Boolean): List<RecognizedRegion> {
        val out = mutableListOf<RecognizedRegion>()
        for (block in visionText.textBlocks) {
            val blockLang = block.recognizedLanguage
            for (line in block.lines) {
                val bb = line.boundingBox ?: continue
                if (!keepLine(line, blockLang, sourceLang)) continue
                val walked = walkLine(line, addWordSpaces)
                if (walked.text.isBlank()) continue
                val orientation = OcrManager.detectOrientation(line)
                val box = OcrBox.upright(bb)
                val recLine = RecognizedLine(
                    text = walked.text,
                    box = box,
                    orientation = orientation,
                    elements = walked.elements,
                    chars = walked.chars,
                    effectiveAlignLeft = OcrManager.effectiveAlignLeft(line),
                )
                out += RecognizedRegion(
                    text = walked.text,
                    box = box,
                    orientation = orientation,
                    confidence = lineConfidence(line),
                    lines = listOf(recLine),
                    origin = RegionOrigin.LINE,
                )
            }
        }
        return out
    }

    /**
     * The line-noise filter formerly inside `groupLinesByProximity`: drop
     * single-character lines that aren't real words (unless kanji on an
     * undetermined-language block), and drop garbled multi-char lines that are
     * mostly non-source characters AND low confidence. Verbatim thresholds.
     */
    private fun keepLine(line: Text.Line, blockLang: String?, sourceLang: String): Boolean {
        if (line.text.trim().length <= 1) {
            if (blockLang == null || blockLang == "und") {
                val c = line.text.trim().firstOrNull() ?: return false
                val isKanji = c in '一'..'鿿' || c in '㐀'..'䶿'
                if (!isKanji) return false
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 31 && line.text.trim().length > 1) {
            val text = line.text.trim()
            val sourceCount = text.count { c -> OcrManager.isSourceLangChar(c, sourceLang) }
            val ratio = sourceCount.toFloat() / text.length
            if (ratio < 0.30f && line.confidence < 0.35f) return false
        }
        return true
    }

    private fun lineConfidence(line: Text.Line): Float =
        if (android.os.Build.VERSION.SDK_INT >= 31) line.confidence else -1f

    private class Walked(
        val text: String,
        val elements: List<ElementBox>,
        val chars: List<CharBox>,
    )

    /**
     * Walk a line's elements, applying the same pipe-trim + UI-decoration filter
     * + optional word-spacing the former `recognise` walk used, and collecting
     * per-element boxes (drives `segments`) and per-character symbols (drives
     * drag-lookup + furigana). Coordinates are kept in input-bitmap space.
     */
    private fun walkLine(line: Text.Line, addWordSpaces: Boolean): Walked {
        val lineBuilder = StringBuilder()
        val elements = mutableListOf<ElementBox>()
        val chars = mutableListOf<CharBox>()
        var lineCharCount = 0
        line.elements.forEachIndexed { ei, element ->
            if (!isUiDecoration(element.text)) {
                var text = element.text
                if (ei == 0) text = text.trimStart('|').trimStart()
                if (ei == line.elements.lastIndex) text = text.trimEnd('|').trimEnd()
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
        }
        return Walked(lineBuilder.toString(), elements, chars)
    }

    /**
     * True for OCR elements that are pure UI decoration (dialogue-advance arrows,
     * decorative angle brackets) rather than dialogue text. Only matches elements
     * whose ENTIRE content is decoration, so real text containing similar
     * characters is never dropped.
     */
    private fun isUiDecoration(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        return t.all { it in UI_DECORATION_CHARS }
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
