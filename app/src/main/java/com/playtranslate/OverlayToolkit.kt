package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.TextPaint
import android.util.Log
import com.playtranslate.language.HintTextAnnotation
import com.playtranslate.language.SourceLanguageEngine
import com.playtranslate.language.hintAnnotations
import com.playtranslate.language.withFrontierHeld
import com.playtranslate.language.TextAlignment
import com.playtranslate.language.TextOrientation
import com.playtranslate.model.TextSegments
import com.playtranslate.ui.TextBox
import androidx.core.graphics.get
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale

/**
 * Pure, stateless functions for building overlay boxes, sampling colors, and
 * dedup comparison. No Android Service dependency — all inputs as parameters.
 */
object OverlayToolkit {

    // ── Constants ─────────────────────────────────────────────────────────

    const val FILL_PADDING = 30
    const val LIVE_DEDUP_TOLERANCE = 3
    const val LIVE_DEDUP_PCT_THRESHOLD = 0.3f

    // ── Dedup ─────────────────────────────────────────────────────────────

    /** Is the text change significant enough to warrant re-processing? */
    fun isSignificantChange(a: String, b: String): Boolean {
        if (a == b) return false
        val freqA = a.groupingBy { it }.eachCount()
        val freqB = b.groupingBy { it }.eachCount()
        var diff = 0
        for (c in (freqA.keys + freqB.keys).toSet()) {
            diff += kotlin.math.abs((freqA[c] ?: 0) - (freqB[c] ?: 0))
            if (diff > LIVE_DEDUP_TOLERANCE) return true
        }
        val maxLen = maxOf(a.length, b.length)
        if (maxLen > 0 && diff.toFloat() / maxLen > LIVE_DEDUP_PCT_THRESHOLD) return true
        return false
    }

    /** Max bag-of-chars difference (as a fraction of the compared prefix) for
     *  [isEvolvingText]'s prefix match — ≈ the 85%-similarity gate GSM's
     *  evolving-text detector converged on. */
    private const val EVOLVING_PREFIX_MAX_DIFF = 0.15f

    /** Tag for [Prefs.debugLiveMode]-gated live furigana annotation timing. */
    private const val FURIGANA_TIMING_TAG = "LiveFurigana"

    /**
     * Is [new] a growing-prefix extension of [old] — the typewriter
     * signature? True when [new] is strictly longer and its prefix of
     * [old]'s length matches [old] within [EVOLVING_PREFIX_MAX_DIFF]
     * bag-of-chars difference, retried with up to `min(2, old.length / 4)`
     * trailing chars of [old] trimmed — the newest glyphs of a mid-render
     * line often rasterize/OCR wrong (GSM's tail tolerance). Used by
     * [TypewriterGate] to scope its typewriter policy: a CHANGED region
     * whose new text merely EXTENDS the displayed text is still being
     * revealed; anything else is a real content change.
     */
    fun isEvolvingText(old: String, new: String): Boolean {
        if (old.isEmpty() || new.length <= old.length) return false
        if (prefixSimilar(old, new)) return true
        val trim = minOf(2, old.length / 4)
        if (trim == 0) return false
        return prefixSimilar(old.substring(0, old.length - trim), new)
    }

    /** Bag-of-chars comparison of [old] against the aligned prefix of [new]. */
    private fun prefixSimilar(old: String, new: String): Boolean {
        val prefix = new.substring(0, old.length)
        val freqA = old.groupingBy { it }.eachCount()
        val freqB = prefix.groupingBy { it }.eachCount()
        var diff = 0
        for (c in freqA.keys + freqB.keys) {
            diff += kotlin.math.abs((freqA[c] ?: 0) - (freqB[c] ?: 0))
        }
        return diff.toFloat() / old.length <= EVOLVING_PREFIX_MAX_DIFF
    }

    /** Anchors in panel reading order: the current OCR's group order (the
     *  layout engine's reading order — correct for vertical/RTL scripts)
     *  when available, else geometric top-then-left. The live mode maintains
     *  anchors as kept-then-new, which scrambles panel text when a new line
     *  appears above an unchanged one (round-25 review finding). Stable
     *  sort: unmatched anchors keep their incoming order at the tail. */
    fun panelReadingOrder(
        anchors: List<TextBox>,
        ocrResult: OcrManager.OcrResult?,
    ): List<TextBox> {
        val groups = ocrResult?.groups
        if (groups.isNullOrEmpty()) {
            return anchors.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        }
        return anchors.sortedBy { a ->
            val i = groups.indexOfFirst { Rect.intersects(it.bounds, a.bounds) }
            if (i >= 0) i else Int.MAX_VALUE
        }
    }

    /** The panel-facing text triple built from displayed boxes — ONE shape
     *  for every live tier's panel sync ([CaptureService.emitPanelResult]).
     *  Extracted because three hand-kept copies of the filter/join rules had
     *  already diverged once (provenance). */
    class PanelTexts(
        val originalText: String,
        val translatedText: String,
        val segments: List<com.playtranslate.model.TextSegment>,
    )

    /** Build the panel texts from [ordered] boxes (the caller's chosen panel
     *  order — [panelReadingOrder] for the reconciler tier, cachedBoxes
     *  verbatim for the pinhole tier). */
    fun panelTexts(ordered: List<TextBox>): PanelTexts = PanelTexts(
        originalText = ordered.filter { it.sourceText.isNotEmpty() }
            .joinToString("\n") { it.sourceText },
        translatedText = ordered.filter { it.translatedText.isNotEmpty() }
            .joinToString("\n\n") { it.translatedText },
        segments = com.playtranslate.model.TextSegments.ofLines(ordered.map { it.sourceText }),
    )

    /** The single "Translated by …" attribution for a panel emission, or null
     *  to suppress the label. A live panel result aggregates boxes translated
     *  across cycles (kept + fresh, cache hits + MT), so one label is only
     *  honest when every translated box names the same backend — any
     *  disagreement, or a translated box with unknown provenance (the
     *  same-language OCR bypass), suppresses it. */
    fun panelBackendLabel(boxes: List<TextBox>): String? =
        boxes.filter { it.translatedText.isNotEmpty() }
            .map { it.backendDisplayName }
            .distinct()
            .singleOrNull()

    /** Does detected text have significant additions over existing? */
    fun hasSignificantAdditions(existing: String, detected: String): Boolean {
        val bag = existing.groupingBy { it }.eachCount().toMutableMap()
        var added = 0
        for (c in detected) {
            val count = bag[c] ?: 0
            if (count > 0) {
                bag[c] = count - 1
            } else {
                added++
                if (added > 1) return true
            }
        }
        return false
    }

    // ── Color sampling ────────────────────────────────────────────────────

    /**
     * Sample background and text colors for each group bound from a scaled-down
     * reference bitmap. Returns (bgColor, textColor) per group.
     */
    fun sampleGroupColors(
        colorRef: Bitmap,
        groupBounds: List<Rect>,
        cropLeft: Int, cropTop: Int,
        colorScale: Int
    ): List<Pair<Int, Int>> {
        val buffer = 10 / colorScale
        return groupBounds.map { bounds ->
            val sl = (bounds.left + cropLeft) / colorScale
            val st = (bounds.top + cropTop) / colorScale
            val sr = (bounds.right + cropLeft) / colorScale
            val sb = (bounds.bottom + cropTop) / colorScale
            val bgColor = averageColor(colorRef,
                sl - buffer, st - buffer, sr + buffer, sb + buffer,
                excludeInner = Rect(sl, st, sr, sb))
            val textColor = if (colorLuminance(bgColor) > 128)
                Color.BLACK else Color.WHITE
            Pair(bgColor, textColor)
        }
    }

    private fun colorLuminance(color: Int): Double {
        return 0.299 * Color.red(color) +
            0.587 * Color.green(color) +
            0.114 * Color.blue(color)
    }

    private fun averageColor(
        bitmap: Bitmap, l: Int, t: Int, r: Int, b: Int,
        excludeInner: Rect? = null
    ): Int {
        val left = l.coerceIn(0, bitmap.width - 1)
        val top = t.coerceIn(0, bitmap.height - 1)
        val right = r.coerceIn(left + 1, bitmap.width)
        val bottom = b.coerceIn(top + 1, bitmap.height)
        var rSum = 0L; var gSum = 0L; var bSum = 0L; var count = 0
        for (y in top until bottom step 4) {
            for (x in left until right step 4) {
                if (excludeInner != null && excludeInner.contains(x, y)) continue
                val pixel = bitmap[x, y]
                rSum += Color.red(pixel)
                gSum += Color.green(pixel)
                bSum += Color.blue(pixel)
                count++
            }
        }
        if (count == 0) return Color.argb(224, 0, 0, 0)
        return Color.argb(224,
            (rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt())
    }

    // ── Furigana box building ─────────────────────────────────────────────

    /** A group of furigana boxes with their source OCR group text and bounds. */
    data class FuriganaGroup(
        val groupText: String,
        val groupBounds: Rect,
        val boxes: List<TextBox>
    )

    /**
     * Build furigana boxes grouped by OCR group, for selective invalidation.
     * Each group carries its source text and bounds so FuriganaMode can track
     * which groups changed and remove only their furigana.
     */
    suspend fun buildFuriganaBoxesByGroup(
        ocrResult: OcrManager.OcrResult,
        engine: SourceLanguageEngine,
        furiganaPaint: TextPaint,
        debugTiming: Boolean = false,
    ): List<FuriganaGroup> {
        val groups = mutableListOf<FuriganaGroup>()
        for (group in ocrResult.groups) {
            val groupBoxes = buildFuriganaBoxesForGroup(group, engine, furiganaPaint, debugTiming)
            if (groupBoxes.isNotEmpty()) {
                groups += FuriganaGroup(
                    groupText = group.text,
                    groupBounds = group.bounds,
                    boxes = groupBoxes
                )
            }
        }
        return groups
    }

    /** Per-group variant of [buildFuriganaBoxesByGroup] — the annotation
     *  machinery for exactly one OCR group. [FuriganaMode]'s reuse-or-rebuild
     *  loop re-annotates only the groups whose text or bounds changed.
     *  [debugTiming] ([Prefs.debugLiveMode] at the live call sites) logs
     *  per-line annotation wall time — the live-cell measurement the
     *  refactor's §6 gate needs, produced by a normal debug-flagged run. */
    suspend fun buildFuriganaBoxesForGroup(
        group: OcrManager.OcrGroup,
        engine: SourceLanguageEngine,
        furiganaPaint: TextPaint,
        debugTiming: Boolean = false,
        /** Typewriter frontier-hold: this group's text is still being
         *  revealed, so the LAST line's final span withholds its ruby
         *  ([withFrontierHeld]) — the one word whose reading could revise
         *  as glyphs arrive. */
        holdFrontier: Boolean = false,
    ): List<TextBox> {
        val lines = group.lines
        if (lines.isEmpty()) return emptyList()

        var timedLines = 0
        var timedTotalMs = 0.0
        val groupBoxes = mutableListOf<TextBox>()
        for (line in lines) {
            val isVertical = line.orientation == com.playtranslate.language.TextOrientation.VERTICAL
            if (line.text.isEmpty()) continue
            // FULL-depth: live furigana shows the SAME dictionary-corrected
            // readings the result sheet displays and TTS speaks (一泊 →
            // いっぱく) — never the raw per-token readings. The engine's
            // annotation LRU makes repeated lines (live re-OCRs the same
            // text every cycle) near-free; Thor measurement of the cold-line
            // cost (typewriter sequences included) rides [debugTiming] — if
            // a budget problem appears, cap the re-glob candidate WINDOWS,
            // never skip the pass (refactor doc §6: the fallback must stay
            // reading-neutral).
            val annotateStartNs = if (debugTiming) System.nanoTime() else 0L
            val isLastLine = line === lines.last()
            val annotations = engine.annotate(line.text)
                .let { if (holdFrontier && isLastLine) it.withFrontierHeld() else it }
                .hintAnnotations()
            if (debugTiming) {
                val ms = (System.nanoTime() - annotateStartNs) / 1e6
                timedLines++
                timedTotalMs += ms
                // Warm LRU hits log ~0ms; cold lines carry the real cost —
                // the duration distribution separates them without engine
                // plumbing.
                Log.i(
                    FURIGANA_TIMING_TAG,
                    "annotate %.1fms len=%d ann=%d '%s'".format(
                        ms, line.text.length, annotations.size,
                        line.text.take(12),
                    ),
                )
            }
            // Slanted line: the rotated sibling ([FuriganaSlantPlacement]) does
            // its placement + merge in the deskewed frame; the upright
            // arithmetic below stays byte-identical.
            if (line.angleDeg != 0f) {
                groupBoxes += FuriganaSlantPlacement.build(line, annotations, furiganaPaint)
                continue
            }

            val lineBoxes = mutableListOf<TextBox>()

            if (line.symbols.isNotEmpty()) {
                for (ann in annotations) {
                    val matching = line.symbols.filter { it.charOffset in ann.baseStart until ann.baseEnd }
                    if (matching.isEmpty()) continue
                    val first = matching.first()
                    val last = matching.last()

                    val bounds = if (isVertical) {
                        // Vertical: furigana to the right of the column
                        val furiganaWidth = (first.bounds.width() * 0.75f).toInt().coerceAtLeast(1)
                        Rect(
                            last.bounds.right,
                            first.bounds.top,
                            last.bounds.right + furiganaWidth,
                            last.bounds.bottom
                        )
                    } else {
                        // Horizontal: furigana above the text
                        val furiganaHeight = (first.bounds.height() * 0.75f).toInt().coerceAtLeast(1)
                        Rect(
                            first.bounds.left,
                            first.bounds.top - furiganaHeight,
                            last.bounds.right,
                            first.bounds.top
                        )
                    }
                    lineBoxes += TextBox(
                        translatedText = ann.hintText,
                        bounds = bounds,
                        lineCount = 1,
                        isFurigana = true,
                        orientation = line.orientation
                    )
                }
            } else {
                // Fallback: TextPaint estimation (no symbols available)
                if (isVertical) {
                    // Vertical fallback: distribute characters along column height
                    val lineH = line.bounds.height().toFloat()
                    val lineTop = line.bounds.top
                    val charCount = line.text.length
                    val charHeight = if (charCount > 0) lineH / charCount else lineH

                    for (ann in annotations) {
                        val top = lineTop + (ann.baseStart * charHeight).toInt()
                        val bottom = lineTop + (ann.baseEnd * charHeight).toInt()
                        val furiganaWidth = (line.bounds.width() * 0.75f).toInt().coerceAtLeast(1)
                        lineBoxes += TextBox(
                            translatedText = ann.hintText,
                            bounds = Rect(
                                line.bounds.right,
                                top,
                                line.bounds.right + furiganaWidth,
                                bottom
                            ),
                            lineCount = 1,
                            isFurigana = true,
                            orientation = line.orientation
                        )
                    }
                } else {
                    // Horizontal fallback (existing logic)
                    val positionMapper: (Int, Int) -> Pair<Int, Int> = if (line.elements.isNotEmpty()) {
                        buildCharToElementMapper(line.elements, furiganaPaint)
                    } else {
                        val lineW = line.bounds.width().toFloat()
                        val lineLeft = line.bounds.left
                        val charWidths = FloatArray(line.text.length).also {
                            furiganaPaint.getTextWidths(line.text, it)
                        }
                        val totalWeight = charWidths.sum()
                        fun(s: Int, e: Int): Pair<Int, Int> {
                            if (totalWeight <= 0f) return lineLeft to lineLeft
                            val lWeight = (0 until s.coerceIn(0, charWidths.size))
                                .sumOf { charWidths[it].toDouble() }.toFloat()
                            val rWeight = (0 until e.coerceIn(0, charWidths.size))
                                .sumOf { charWidths[it].toDouble() }.toFloat()
                            val l = lineLeft + (lWeight / totalWeight * lineW).toInt()
                            val r = lineLeft + (rWeight / totalWeight * lineW).toInt()
                            return l to r
                        }
                    }

                    for (ann in annotations) {
                        val (left, right) = positionMapper(ann.baseStart, ann.baseEnd)
                        val furiganaHeight = (line.bounds.height() * 0.75f).toInt().coerceAtLeast(1)
                        val furiganaBounds = Rect(
                            left,
                            line.bounds.top - furiganaHeight,
                            right,
                            line.bounds.top
                        )
                        lineBoxes += TextBox(
                            translatedText = ann.hintText,
                            bounds = furiganaBounds,
                            lineCount = 1,
                            isFurigana = true
                        )
                    }
                }
            }

            groupBoxes += mergeOverlappingFurigana(lineBoxes, furiganaPaint, isVertical)
        }

        if (debugTiming && timedLines > 0) {
            Log.i(FURIGANA_TIMING_TAG, "group done: %d lines %.1fms total".format(timedLines, timedTotalMs))
        }
        return groupBoxes
    }

    /** Convenience: build flat list of furigana boxes (for callers that don't need group tracking). */
    suspend fun buildFuriganaBoxes(
        ocrResult: OcrManager.OcrResult,
        engine: SourceLanguageEngine,
        furiganaPaint: TextPaint
    ): List<TextBox> =
        buildFuriganaBoxesByGroup(ocrResult, engine, furiganaPaint).flatMap { it.boxes }

    /** Check if two OCR groups match (same text at same approximate location). */
    fun groupsMatch(
        oldText: String, oldBounds: Rect,
        newText: String, newBounds: Rect
    ): Boolean {
        if (isSignificantChange(oldText, newText)) return false
        val tolerance = maxOf(
            oldBounds.width(), oldBounds.height(),
            newBounds.width(), newBounds.height()
        ) / 3
        val dx = Math.abs((oldBounds.left + oldBounds.right) / 2 - (newBounds.left + newBounds.right) / 2)
        val dy = Math.abs((oldBounds.top + oldBounds.bottom) / 2 - (newBounds.top + newBounds.bottom) / 2)
        return dx < tolerance && dy < tolerance
    }

    /**
     * Merge adjacent furigana boxes whose rendered text would overlap.
     * The furigana reading is often wider than the kanji it sits above (e.g., "わたし"
     * over "私"), so we estimate the rendered text extent to detect visual collisions
     * rather than just checking OCR bounds.
     *
     * When [vertical], furigana is to the right of a vertical column — merge
     * along the Y axis (top-to-bottom) instead of the X axis.
     */
    private fun mergeOverlappingFurigana(
        boxes: List<TextBox>,
        furiganaPaint: TextPaint,
        vertical: Boolean = false
    ): List<TextBox> {
        if (boxes.size <= 1) return boxes

        if (vertical) {
            // Vertical: furigana boxes are stacked top-to-bottom to the right of the column.
            // Merge boxes that overlap on the Y axis.
            val sorted = boxes.sortedBy { it.bounds.top }
            val merged = mutableListOf<TextBox>()
            var current = sorted[0]
            var currentBottom = estimateFuriganaBottom(current, furiganaPaint)
            for (i in 1 until sorted.size) {
                val next = sorted[i]
                if (next.bounds.top < currentBottom) {
                    current = TextBox(
                        translatedText = current.translatedText + next.translatedText,
                        bounds = Rect(
                            minOf(current.bounds.left, next.bounds.left),
                            current.bounds.top,
                            maxOf(current.bounds.right, next.bounds.right),
                            maxOf(current.bounds.bottom, next.bounds.bottom)
                        ),
                        lineCount = 1,
                        isFurigana = true,
                        orientation = current.orientation
                    )
                    currentBottom = estimateFuriganaBottom(current, furiganaPaint)
                } else {
                    merged += current
                    current = next
                    currentBottom = estimateFuriganaBottom(current, furiganaPaint)
                }
            }
            merged += current
            return merged
        }

        // Horizontal: merge along the X axis (existing behavior)
        val sorted = boxes.sortedBy { it.bounds.left }
        val merged = mutableListOf<TextBox>()
        var current = sorted[0]
        var currentRight = estimateFuriganaRight(current, furiganaPaint)
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.bounds.left < currentRight) {
                current = TextBox(
                    translatedText = current.translatedText + next.translatedText,
                    bounds = Rect(
                        current.bounds.left,
                        minOf(current.bounds.top, next.bounds.top),
                        maxOf(current.bounds.right, next.bounds.right),
                        maxOf(current.bounds.bottom, next.bounds.bottom)
                    ),
                    lineCount = 1,
                    isFurigana = true
                )
                currentRight = estimateFuriganaRight(current, furiganaPaint)
            } else {
                merged += current
                current = next
                currentRight = estimateFuriganaRight(current, furiganaPaint)
            }
        }
        merged += current
        return merged
    }

    /**
     * Estimate the right edge of a furigana label as it would be rendered.
     * The text is rendered at 0.7× the box height, positioned from box.left.
     */
    private fun estimateFuriganaRight(box: TextBox, paint: TextPaint): Int {
        val textSizePx = (box.bounds.height() * 0.7f).coerceAtLeast(4f)
        val savedSize = paint.textSize
        paint.textSize = textSizePx
        val textWidth = paint.measureText(box.translatedText)
        paint.textSize = savedSize
        return maxOf(box.bounds.right, (box.bounds.left + textWidth).toInt())
    }

    /** Estimate the bottom edge of a vertical furigana label (text rendered top-to-bottom). */
    private fun estimateFuriganaBottom(box: TextBox, paint: TextPaint): Int {
        // Each character stacks vertically; estimate total height from char count × char width
        val textSizePx = (box.bounds.width() * 0.7f).coerceAtLeast(4f)
        val charHeight = textSizePx * 1.2f  // line spacing factor
        val totalHeight = box.translatedText.length * charHeight
        return maxOf(box.bounds.bottom, (box.bounds.top + totalHeight).toInt())
    }


    /**
     * Build a mapper from (startCharOffset, endCharOffset) → (left, right) pixel positions,
     * using TextPaint relative proportions scaled to actual OCR element widths.
     *
     * TextPaint provides correct relative ratios (kanji wider than punctuation).
     * ML Kit provides the actual rendered width of each element on screen.
     * Scaling one by the other gives the best of both.
     */
    private fun buildCharToElementMapper(
        elements: List<OcrManager.ElementBox>,
        furiganaPaint: TextPaint
    ): (Int, Int) -> Pair<Int, Int> {
        data class CharMapping(val elemIdx: Int, val offsetInElem: Int)
        val charMap = mutableListOf<CharMapping>()
        for ((ei, elem) in elements.withIndex()) {
            for (ci in elem.text.indices) {
                charMap += CharMapping(ei, ci)
            }
        }

        // Scale TextPaint relative widths to match each element's actual rendered width
        val elemWidths = elements.map { elem ->
            val paintWidths = FloatArray(elem.text.length).also { furiganaPaint.getTextWidths(elem.text, it) }
            val paintTotal = paintWidths.sum()
            val actualTotal = elem.bounds.width().toFloat()
            if (paintTotal > 0f) {
                FloatArray(paintWidths.size) { i -> paintWidths[i] / paintTotal * actualTotal }
            } else {
                FloatArray(elem.text.length) { actualTotal / elem.text.length.coerceAtLeast(1) }
            }
        }

        if (charMap.isEmpty()) return { _, _ -> 0 to 0 }

        return { startOffset: Int, endOffset: Int ->
            val safeStart = startOffset.coerceIn(0, charMap.size - 1)
            val safeEnd = (endOffset - 1).coerceIn(0, charMap.size - 1)

            val startMapping = charMap[safeStart]
            val endMapping = charMap[safeEnd]

            val startElem = elements[startMapping.elemIdx]
            val endElem = elements[endMapping.elemIdx]

            val startWeights = elemWidths[startMapping.elemIdx]
            val startTotalWeight = startWeights.sum()
            val startPrecedingWeight = (0 until startMapping.offsetInElem).sumOf { startWeights[it].toDouble() }.toFloat()
            val left = if (startTotalWeight > 0f)
                startElem.bounds.left + (startPrecedingWeight / startTotalWeight * startElem.bounds.width()).toInt()
            else startElem.bounds.left

            val endWeights = elemWidths[endMapping.elemIdx]
            val endTotalWeight = endWeights.sum()
            val endPrecedingWeight = (0..endMapping.offsetInElem).sumOf { endWeights[it].toDouble() }.toFloat()
            val right = if (endTotalWeight > 0f)
                endElem.bounds.left + (endPrecedingWeight / endTotalWeight * endElem.bounds.width()).toInt()
            else endElem.bounds.right

            left to right
        }
    }

    // ── OCR pipeline ──────────────────────────────────────────────────────

    data class OcrPipelineResult(
        val ocrResult: OcrManager.OcrResult,
        val dedupKey: String,
        val cropLeft: Int, val cropTop: Int,
        val screenshotW: Int, val screenshotH: Int
    )

    /**
     * Compute the OCR input crop bounds for a raw screenshot. Single source of
     * truth for the status-bar clamp — both [runOcrPipeline] (production) and
     * [PlayTranslateAccessibilityService.runDebugCapture] (debug overlay) call
     * this so they exclude the same pixel rows from ML Kit. Callers do their
     * own [createBitmap] afterward to keep recycle ownership local.
     */
    fun computeOcrCrop(
        rawWidth: Int,
        rawHeight: Int,
        activeRegion: RegionEntry,
        statusBarHeight: Int,
    ): Rect {
        val top    = maxOf((rawHeight * activeRegion.top).toInt(), statusBarHeight)
        val left   = (rawWidth  * activeRegion.left).toInt()
        val bottom = (rawHeight * activeRegion.bottom).toInt()
        val right  = (rawWidth  * activeRegion.right).toInt()
        return Rect(left, top, right, bottom)
    }

    /**
     * Crop to active region, run OCR, filter source-lang chars. Returns null
     * if no text detected. Does NOT do dedup, translation, or display.
     *
     * [blackoutIconRect], when non-null (screen coordinates), is filled
     * black in the OCR input after the crop — the floating icon's window
     * rect, resolved by the caller from the frame's stamped
     * [com.playtranslate.capture.CapturedFrame.includesOwnOverlays] fact.
     * Raw frames of contaminated sources contain the icon, and its compact
     * chevron OCRs as a ‹-class glyph (observed producing overlay boxes,
     * 2026-07-16). Frames that structurally cannot contain the icon (clean
     * captures, CLEAN task mirrors) must pass null: filling their rect
     * would eat real game text under the dock spot. Pixel fill rather than
     * result-space exclusion so the glyph can never merge into an adjacent
     * real text line and take it down with it.
     *
     * [seedWriter], when non-null, is invoked after [OcrManager.recognise]
     * with the bitmap that was actually fed to OCR and the result (possibly
     * null) — before the bitmap is recycled. Used to wire [OcrSeedWriter]
     * into the pinhole / live OCR path so we can capture the exact filled
     * bitmap a failing repro produced, including the "recognise returned
     * null" case that wouldn't otherwise be recoverable from a manual
     * capture (manual capture operates on the raw screen with no overlay
     * fills applied). The seed always carries the UNFILTERED result — the
     * engine's true output for that bitmap; [excludeRect] is applied after.
     *
     * [excludeRect], when non-null (screen coordinates), drops every
     * recognized group whose bounds intersect it — the live-start status
     * card may be inside the captured frame (whole-display mirrors,
     * accessibility screenshots), and the app must never OCR its own
     * chrome. Frames are display-sized on both capture paths, so screen →
     * cropped-bitmap mapping is the crop offset alone.
     */
    suspend fun runOcrPipeline(
        raw: Bitmap,
        activeRegion: RegionEntry,
        sourceLang: String,
        ocrManager: OcrManager,
        statusBarHeight: Int,
        seedWriter: ((Bitmap, OcrManager.OcrResult?) -> Unit)? = null,
        excludeRect: Rect? = null,
        blackoutIconRect: Rect? = null,
    ): OcrPipelineResult? {
        val crop = computeOcrCrop(raw.width, raw.height, activeRegion, statusBarHeight)
        val needsCrop = crop.top > 0 || crop.left > 0 || crop.bottom < raw.height || crop.right < raw.width
        var bitmap = if (needsCrop)
            Bitmap.createBitmap(raw, crop.left, crop.top, (crop.right - crop.left).coerceAtLeast(1), (crop.bottom - crop.top).coerceAtLeast(1))
        else raw
        if (blackoutIconRect != null) {
            // In-place only into the pipeline-owned crop copy. When the crop
            // was a no-op, `bitmap` IS the caller-owned frame — reused after
            // this call as the untouched screen image — so the fill must land
            // on a copy no matter how mutable the frame is.
            val blacked = blackoutFloatingIcon(
                bitmap, crop.left, crop.top, blackoutIconRect,
                allowInPlace = bitmap !== raw,
            )
            if (blacked !== bitmap) {
                // The helper copied. Retire the superseded crop copy; never
                // the caller-owned raw.
                if (bitmap !== raw) bitmap.recycle()
                bitmap = blacked
            }
        }

        var ocrResult: OcrManager.OcrResult?
        try {
            ocrResult = ocrManager.recognise(bitmap, sourceLang, screenshotWidth = raw.width)
            seedWriter?.invoke(bitmap, ocrResult)
        } finally {
            // Always clean up the crop (NOT raw — caller manages that)
            if (bitmap !== raw && !bitmap.isRecycled) bitmap.recycle()
        }

        if (excludeRect != null && ocrResult != null) {
            val exCropped = Rect(excludeRect).apply { offset(-crop.left, -crop.top) }
            ocrResult = dropGroupsIntersecting(ocrResult, exCropped)
        }
        if (ocrResult == null) return null

        val dedupKey = ocrResult.fullText.filter { c -> OcrManager.isSourceLangChar(c, sourceLang) }
        if (dedupKey.isEmpty()) return null

        return OcrPipelineResult(ocrResult, dedupKey, crop.left, crop.top, raw.width, raw.height)
    }

    /** Opaque fill for the floating icon's window rect in OCR input — a
     *  solid rect produces no recognizer output, unlike the chevron glyph. */
    private val iconBlackoutPaint = Paint().apply { color = Color.BLACK }

    /**
     * Black out [iconRect] (screen coordinates) in [bitmap], whose (0,0)
     * sits at ([cropLeft], [cropTop]) in screen space. Returns [bitmap]
     * untouched when the rect misses it entirely; otherwise a bitmap with
     * the region filled black.
     *
     * [allowInPlace] is the OWNERSHIP declaration, not an optimization
     * flag: pass true only for a bitmap the call site owns outright and
     * discards after OCR (a crop copy, a pre-fill copy). Pass false for
     * anything that outlives the OCR call — above all a captured frame,
     * which downstream code reuses as the untouched screen image (cache
     * saves, cleanRef baselines, pinhole checks; 2026-07-16 adversarial
     * finding: both capture backends serve MUTABLE frames, so a
     * mutability test alone draws straight into them on no-op crops).
     * With false — or an immutable bitmap — the fill lands on a mutable
     * copy. NEVER recycles the input, even when it copies: the caller
     * owns both bitmaps and reconciles (the original 7ad22ff5 helper
     * recycled inside, which entangled ownership with the crop logic).
     */
    fun blackoutFloatingIcon(
        bitmap: Bitmap,
        cropLeft: Int,
        cropTop: Int,
        iconRect: Rect,
        allowInPlace: Boolean,
    ): Bitmap {
        val left = (iconRect.left - cropLeft).coerceAtLeast(0)
        val top = (iconRect.top - cropTop).coerceAtLeast(0)
        val right = (iconRect.right - cropLeft).coerceAtMost(bitmap.width)
        val bottom = (iconRect.bottom - cropTop).coerceAtMost(bitmap.height)
        if (left >= right || top >= bottom) return bitmap
        val out = if (allowInPlace && bitmap.isMutable) bitmap
            else bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawRect(
            left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(),
            iconBlackoutPaint,
        )
        return out
    }

    /**
     * A copy of [result] without the groups whose TEXT intersects [exclude]
     * (cropped-bitmap coordinates), or null when nothing survives. The
     * intersection is judged per LINE, not on the group's bounding box: a
     * full-width dialogue group whose bbox merely passes under the excluded
     * rect must not be discarded when none of its actual lines touches it
     * (review finding — bbox-level exclusion over-drops real game text).
     * Groups without line detail fall back to the bbox test. The derived
     * projections are rebuilt the way [OcrManager] builds them —
     * [OcrManager.OcrResult.fullText] as the space-joined group texts,
     * segments via [TextSegments.ofGroupTexts] — and every surviving line's
     * baked-in groupIndex is re-pointed at its group's new position.
     * debugBoxes are left untouched: showing the dropped region's boxes in
     * the debug overlay is a feature, not a leak.
     */
    private fun dropGroupsIntersecting(
        result: OcrManager.OcrResult,
        exclude: Rect,
    ): OcrManager.OcrResult? {
        val kept = result.groups.filter { group ->
            val hit = if (group.lines.isNotEmpty()) {
                group.lines.any { Rect.intersects(it.bounds, exclude) }
            } else {
                Rect.intersects(group.bounds, exclude)
            }
            !hit
        }
        if (kept.size == result.groups.size) return result
        if (kept.isEmpty()) return null
        val reindexed = kept.mapIndexed { gi, group ->
            group.copy(lines = group.lines.map { it.copy(groupIndex = gi) })
        }
        return result.copy(
            fullText = reindexed.joinToString(" ") { it.text }.trim(),
            segments = TextSegments.ofGroupTexts(reindexed.map { it.text }),
            groups = reindexed,
        )
    }

    // ── Placeholder boxes + translation (shared by live overlay modes) ─────

    /**
     * Build placeholder [TextBox]es with empty [TextBox.translatedText]
     * (skeleton indicators). Instant, no network — background/text colors are
     * sampled from [raw] at each group's bounds (offset by [left]/[top] crop).
     *
     * Used by [ReconcilerLiveMode]; [PinholeOverlayMode] keeps its own
     * private copy so the legacy tier stays byte-identical. Service-free by
     * design (pure box building).
     */
    internal fun buildPlaceholderBoxes(
        texts: List<String>, bounds: List<Rect>, lineCounts: List<Int>,
        raw: Bitmap, left: Int, top: Int,
        orientations: List<TextOrientation> = emptyList(),
        alignments: List<TextAlignment> = emptyList(),
        confidences: List<Pair<Float, Float>> = emptyList(),
        /** Per-box (angleDeg, orientedWidth, orientedHeight); zeros when upright. */
        slants: List<Triple<Float, Float, Float>> = emptyList(),
    ): List<TextBox> {
        val colorScale = 4
        val colorRef = raw.scale(raw.width / colorScale, raw.height / colorScale, false)
        val colors: List<Pair<Int, Int>>
        try {
            colors = sampleGroupColors(colorRef, bounds, left, top, colorScale)
        } finally {
            colorRef.recycle()
        }
        return bounds.mapIndexed { idx, rect ->
            val (bg, tc) = colors.getOrElse(idx) { Pair(Color.argb(224, 0, 0, 0), Color.WHITE) }
            val orient = orientations.getOrElse(idx) { TextOrientation.HORIZONTAL }
            val align = alignments.getOrElse(idx) { TextAlignment.LEFT }
            val (cMin, cMean) = confidences.getOrElse(idx) { -1f to -1f }
            val (ang, ow, oh) = slants.getOrElse(idx) { Triple(0f, 0f, 0f) }
            TextBox("", rect, bg, tc, lineCounts.getOrElse(idx) { 1 },
                sourceText = texts.getOrElse(idx) { "" }, orientation = orient, alignment = align,
                angleDeg = ang, orientedWidth = ow, orientedHeight = oh,
                sourceConfMin = cMin, sourceConfMean = cMean)
        }
    }

    /**
     * Translate [texts] (cache-first) and return [placeholders] with filled
     * [TextBox.translatedText]. Only cache misses hit [service]'s translator.
     */
    internal suspend fun translatePlaceholders(
        service: CaptureService, placeholders: List<TextBox>, texts: List<String>,
    ): List<TextBox> {
        val uncachedIndices = mutableListOf<Int>()
        val uncachedTexts = mutableListOf<String>()
        val translations = arrayOfNulls<CaptureService.GroupTranslation>(texts.size)

        for ((idx, text) in texts.withIndex()) {
            val cached = service.getCachedTranslation(text)
            if (cached != null) {
                translations[idx] = cached
            } else {
                uncachedIndices.add(idx)
                uncachedTexts.add(text)
            }
        }

        if (uncachedTexts.isNotEmpty()) {
            val results = service.translateGroupsSeparately(uncachedTexts)
            for ((i, idx) in uncachedIndices.withIndex()) {
                translations[idx] = results.getOrNull(i)
            }
        }

        return placeholders.mapIndexed { idx, ph ->
            val t = translations.getOrNull(idx)
            ph.copy(
                translatedText = t?.text ?: "",
                backendDisplayName = t?.backendDisplayName,
            )
        }
    }

    // ── Factory ───────────────────────────────────────────────────────────

}
