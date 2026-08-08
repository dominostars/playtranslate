package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import com.playtranslate.language.OcrBackend
import com.playtranslate.language.TextAlignment
import com.playtranslate.language.TextOrientation
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TextSegments
import com.playtranslate.ocr.OcrPipeline
import com.playtranslate.ocr.core.LayoutGroup
import com.playtranslate.ocr.mangaocr.MangaOcrBridge
import com.playtranslate.ocr.registry.OcrEngineRegistry
import com.playtranslate.ocr.registry.OcrModelManager
import androidx.core.graphics.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Public facade for on-device OCR.
 *
 * Resolves the [OcrEngine] for a source language, runs the engine-agnostic
 * [OcrPipeline] (preprocess → engine → shared [com.playtranslate.ocr.core.LayoutAnalyzer]),
 * and projects the grouped result into the [OcrResult] / [OcrLine] shapes the
 * app consumes. All grouping/layout logic lives in `ocr.core`; the per-vendor
 * extraction lives in `ocr.engines`. This class keeps only: engine lifecycle,
 * the result projection, and a few ML-Kit-typed helpers the ML Kit adapter
 * reuses ([detectOrientation], [effectiveAlignLeft], [isSourceLangChar]).
 */
class OcrManager private constructor() {

    /** Debug-only: when true, the grouping kernel logs every candidate line's
     *  MERGE/SPLIT decision to logcat under tag "DetectionLog". Pushed from
     *  [PlayTranslateApplication] on start and from the SettingsRenderer toggle. */
    @Volatile var debugLogGroupingEnabled: Boolean = false

    /** Debug override for the producer angle noise gate (degrees); null = the
     *  compiled [com.playtranslate.ocr.core.OcrBox.ANGLE_NOISE_GATE_DEG].
     *  The threshold-drop program's per-stage device validation forces the
     *  target gate here, so the final drop only changes a default over
     *  already-exercised code. */
    @Volatile var debugAngleGateDeg: Float? = null

    /** Mirrors [debugLogGroupingEnabled]'s wiring (boot + settings toggle).
     *  The setter injects/clears the AngleProbe sink — `ocr.core` cannot read
     *  Prefs itself, so the app layer owns the gate (same injection pattern as
     *  `OcrModelManager.appContext`). */
    var debugAngleProbeEnabled: Boolean = false
        set(value) {
            field = value
            com.playtranslate.ocr.core.OrientedBoxGeometry.probeSink =
                if (value) { msg -> android.util.Log.d("AngleProbe", msg) } else null
        }

    /** Pushed from [com.playtranslate.PlayTranslateApplication] on start and from the
     *  "Use MangaOCR" settings toggle. Gates the optional manga-ocr refinement
     *  ([shouldRefineMangaOcr] adds the Japanese / arm64 / model-installed checks). */
    @Volatile var mangaOcrEnabled: Boolean = false

    /** Builds + caches the [com.playtranslate.ocr.core.OcrEngine] per source
     *  language; closed in [releaseAll]. */
    private val registry = OcrEngineRegistry()

    /**
     * Drop every cached engine and close its native resources.
     *
     * Wired only into [com.playtranslate.PlayTranslateApplication.onTrimMemory]
     * at [android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE] — the one
     * signal that guarantees no foreground service (and therefore no in-flight
     * OCR) is running. Calling this while [recognise] is mid-call would close an
     * engine out from under its worker, so do NOT hook it into any UI-driven path.
     */
    fun releaseAll() {
        registry.closeAll()
        // manga-ocr's session lives off the registry (it's a refiner, not a cached
        // engine); close it on the same quiescent TRIM_MEMORY_COMPLETE signal. This is
        // the bridge's lock-free teardown — sound only because this path guarantees no
        // in-flight OCR (the framework's documented quiescent point); interactive teardown
        // uses the locked MangaOcrBridge.close() instead.
        MangaOcrBridge.closeForTrim()
    }

    /**
     * Resolve — and thereby construct and cache — the OCR engine for
     * [sourceLang] ahead of the first recognition pass. Live mode calls this at
     * start so the multi-second Meiki/Paddle native session load overlaps
     * MediaProjection setup instead of landing inside cycle 1's [recognise].
     *
     * Returns when engine resolution has SETTLED, whatever it settled to — a
     * real engine, the ML Kit floor, or the no-OCR empty engine. Failures are
     * swallowed: the first real pass will surface them through its own path.
     * Safe against a concurrent [recognise]: the bridges' engine() accessors
     * are synchronized and idempotent, so both callers get the same cached
     * session.
     */
    suspend fun warmUpEngine(sourceLang: String) {
        withContext(Dispatchers.Default) {
            runCatching { registry.engineFor(sourceLang) }
        }
    }

    /** True when manga-ocr refinement should run for [sourceLang]: enabled by the user
     *  ([mangaOcrEnabled]), Japanese, arm64 (MNN), and the model pack installed
     *  ([MangaOcrBridge.modelDir] pushed non-null). Gating modelDir here is also what
     *  keeps the bridge's lazy init from firing — and latching `triedInit` — before
     *  the pack exists. */
    private fun shouldRefineMangaOcr(sourceLang: String): Boolean =
        mangaOcrEnabled &&
            sourceLang == "ja" &&
            OcrModelManager.isMnnAvailable() &&
            MangaOcrBridge.modelDir != null

    /** A bounding box with optional confidence for debug overlay. */
    data class DebugBox(
        val bounds: Rect,
        val confidence: Float = -1f,
        val text: String = "",
        val lang: String = "",
        /** Slant in degrees (clockwise-positive); 0 = axis-aligned. */
        val angleDeg: Float = 0f,
        /** True (unrotated) dims of a slanted box, same coordinate space as
         *  [bounds]; 0 when upright. The debug overlay draws the oriented
         *  footprint from these — rotating the AABB instead would outline a
         *  shape that exists nowhere in the pipeline (adversarial-review
         *  finding). */
        val orientedWidth: Float = 0f,
        val orientedHeight: Float = 0f,
    )

    /** Bounding boxes at each OCR hierarchy level, for debug overlay. */
    data class OcrDebugBoxes(
        val blockBoxes: List<DebugBox>,
        val lineBoxes: List<DebugBox>,
        val elementBoxes: List<DebugBox>,
        /** Combined group bounding boxes (union of merged lines). */
        val groupBoxes: List<DebugBox>,
        /** Scale factor applied during OCR; divide box coords by this to get original coords. */
        val scaleFactor: Float
    )

    /** A single OCR element's text and bounding box within a line. */
    data class ElementBox(
        val text: String,
        val bounds: Rect
    )

    /** A single character with its exact bounding box.
     *  [charOffset] is the character's position within the containing line's
     *  processed text string. Consumers filter symbols by offset range rather
     *  than assuming 1:1 positional alignment — spaces and missing symbols
     *  simply have no entry, which is correct. */
    data class SymbolBox(
        val text: String,
        val bounds: Rect,
        val charOffset: Int,
    )

    /** A per-line bounding box with its processed text and group association. */
    data class LineBox(
        /** Processed text of this line (decorations stripped, pipes trimmed). */
        val text: String,
        /** Bounding box in original (pre-scale) bitmap coordinates. */
        val bounds: Rect,
        /** Index of the group this line belongs to. */
        val groupIndex: Int,
        /** Per-element bounding boxes within this line (for precise character positioning). */
        val elements: List<ElementBox> = emptyList(),
        /** Per-character symbols with exact bounds. Empty if unavailable. */
        val symbols: List<SymbolBox> = emptyList(),
        /** Text orientation detected from ML Kit angle / bounding box geometry. */
        val orientation: TextOrientation = TextOrientation.HORIZONTAL,
        /** Recognition confidence 0..1 from the engine, or -1 when unknown.
         *  -1 means "no signal", never "low". */
        val confidence: Float = -1f,
        /** Slant in degrees (clockwise-positive, `View.rotation` semantics);
         *  0 = axis-aligned. Non-zero only for a genuinely slanted line. */
        val angleDeg: Float = 0f,
        /** True (unrotated) dims of the slanted rect; 0 when [angleDeg] == 0.
         *  Carried alongside — not re-derivable from bounds+angle (45° singular). */
        val orientedWidth: Float = 0f,
        val orientedHeight: Float = 0f,
    )

    /**
     * One grouped paragraph: combined [text], [bounds] (original-bitmap coords),
     * the voted [orientation] + classified [alignment], and its constituent
     * [lines]. Replaces the former parallel group-* lists with one cohesive type
     * — index desync is impossible.
     */
    data class OcrGroup(
        val text: String,
        val bounds: Rect,
        val orientation: TextOrientation = TextOrientation.HORIZONTAL,
        val alignment: TextAlignment = TextAlignment.LEFT,
        val lines: List<LineBox> = emptyList(),
        /** Slant in degrees (clockwise-positive); non-zero only for a standalone
         *  rotated group, whose [bounds] is then exactly the slanted rect's AABB. */
        val angleDeg: Float = 0f,
        /** True (unrotated) dims of the slanted rect, original-bitmap px; 0 when
         *  [angleDeg] == 0. Ride with the angle — not re-derivable downstream. */
        val orientedWidth: Float = 0f,
        val orientedHeight: Float = 0f,
    )

    data class OcrResult(
        /** Full text joined across groups, suitable for bulk translation. */
        val fullText: String,
        /** Flat list of segments (one per TextElement) for tappable display. */
        val segments: List<TextSegment>,
        /** The OCR groups (paragraphs) in reading order — the source of truth. */
        val groups: List<OcrGroup> = emptyList(),
        /** Debug bounding boxes at line/element/group level, or null if debug is off. */
        val debugBoxes: OcrDebugBoxes? = null,
        /** The OCR backend that actually produced this result, captured at the
         *  registry's resolution chokepoint. Null when no engine identity is
         *  available (the no-OCR empty engine). Carries the backend itself — not
         *  just a label — so callers can derive both its display name and its
         *  selection token for provenance. */
        val engineBackend: OcrBackend? = null,
        /** True when the manga-ocr refiner decoded at least one block of this result
         *  (see [com.playtranslate.ocr.MangaOcrRefiner.Refined]) — provenance extends
         *  the display label to "… + MangaOCR". Attribution-only: [engineBackend]
         *  stays the base engine, so the re-OCR picker key is unaffected. */
        val mangaOcrUsed: Boolean = false,
    )

    /**
     * Run OCR and return a grouped, translation-ready [OcrResult] in original
     * bitmap coordinates. Resolves the engine for [sourceLang], runs the shared
     * pipeline, and projects the result.
     */
    suspend fun recognise(
        bitmap: Bitmap,
        sourceLang: String = "ja",
        collectDebugBoxes: Boolean = false,
        screenshotWidth: Int = 0,
        recipe: OcrPreprocessingRecipe = selectOcrRecipe(sourceLang),
        regionPreFilter: com.playtranslate.ocr.core.RegionPreFilter? = null,
        /** Caller-scoped OCR selection token (the camera tool's per-flow engine
         *  choice) resolved in place of the stored global one; null = global. */
        engineTokenOverride: String? = null,
        /** Document layout bias: grouping may borrow the page's dominant line
         *  rhythm as bootstrap evidence in the ambiguous gap band (airy
         *  document leading otherwise starves there — see
         *  LayoutAnalyzer.documentPitch). Game/live surfaces leave this off:
         *  menus are rhythmic too, and the band's refusal protects them. */
        documentLayoutBias: Boolean = false,
    ): OcrResult? {
        val output = OcrPipeline.run(
            engineProvider = { registry.engineFor(sourceLang, engineTokenOverride) },
            bitmap = bitmap,
            sourceLang = sourceLang,
            screenshotWidth = screenshotWidth,
            recipe = recipe,
            darkBackgroundProvider = { sampleIsDarkBackground(bitmap) },
            logGrouping = debugLogGroupingEnabled,
            refineWithMangaOcr = shouldRefineMangaOcr(sourceLang),
            regionPreFilter = regionPreFilter,
            documentLayoutBias = documentLayoutBias,
            angleNoiseGateDeg = debugAngleGateDeg
                ?: com.playtranslate.ocr.core.OcrBox.ANGLE_NOISE_GATE_DEG,
        ) ?: return null

        val result = buildOcrResult(
            output.groups, output.scaleFactor, collectDebugBoxes, output.backend, output.mangaOcrUsed,
        )
        if (result.fullText.isBlank()) return null

        android.util.Log.d("DetectionLog", "OCR raw: ${result.groups.size} groups")
        // Recognized TEXT is user content — game screens, and now arbitrary
        // camera frames (documents, mail). Release logs must carry only
        // counts/backend/timing: logcat rides along in exported diagnostics,
        // and the DEBUG gate here is what makes the camera path's own
        // DEBUG-gated content logging actually hold.
        if (BuildConfig.DEBUG) {
            for ((i, g) in result.groups.withIndex()) {
                android.util.Log.d("DetectionLog", "  group[$i]: \"${g.text.take(50)}\"")
            }
        }
        return result
    }

    /**
     * Run OCR and return lines with bounding boxes in original bitmap coordinates.
     * Used by drag-to-lookup to hit-test finger position against text lines. Does
     * NOT split menu groups (screenshotWidth = 0), matching the prior behavior.
     */
    suspend fun recogniseWithPositions(
        bitmap: Bitmap,
        sourceLang: String = "ja",
        recipe: OcrPreprocessingRecipe = selectOcrRecipe(sourceLang)
    ): List<OcrLine>? {
        val output = OcrPipeline.run(
            engineProvider = { registry.engineFor(sourceLang) },
            bitmap = bitmap,
            sourceLang = sourceLang,
            screenshotWidth = 0,
            recipe = recipe,
            darkBackgroundProvider = { sampleIsDarkBackground(bitmap) },
            logGrouping = debugLogGroupingEnabled,
            refineWithMangaOcr = shouldRefineMangaOcr(sourceLang),
            angleNoiseGateDeg = debugAngleGateDeg
                ?: com.playtranslate.ocr.core.OcrBox.ANGLE_NOISE_GATE_DEG,
        ) ?: return null

        return buildOcrLines(output.groups, output.scaleFactor).ifEmpty { null }
    }

    // ── Projection: LayoutGroup (engine-input coords) → app result types ─────

    /** Divide a box by [sf] to map engine-input coords back to original-bitmap
     *  coords. angleDeg passes through UNdivided — scale-invariance holds only
     *  because [sf] is uniform (OcrPipeline derives one factor from the width
     *  ratio and applies it to both axes); an anisotropic preprocess would turn
     *  the slant into a shear this projection cannot express. */
    private fun scaleRect(r: Rect, sf: Float): Rect =
        if (sf == 1f) r
        else Rect((r.left / sf).toInt(), (r.top / sf).toInt(), (r.right / sf).toInt(), (r.bottom / sf).toInt())

    /** [scaleRect]'s scalar twin for the oriented dims. */
    private fun scaleDim(v: Float, sf: Float): Float = if (sf == 1f || v == 0f) v else v / sf

    private fun buildOcrResult(
        groups: List<LayoutGroup>,
        scaleFactor: Float,
        collectDebugBoxes: Boolean,
        engineBackend: OcrBackend? = null,
        mangaOcrUsed: Boolean = false,
    ): OcrResult {
        val ocrGroups = groups.mapIndexed { gi, group ->
            OcrGroup(
                text = group.text,
                bounds = scaleRect(group.bounds, scaleFactor),
                orientation = group.orientation,
                alignment = group.alignment,
                lines = group.lines.map { line ->
                    // Oriented dims are carried only WITH a slant: an upright
                    // OcrBox holds its AABB dims there (upright() convention),
                    // but the app-side carriers keep 0 so "angleDeg != 0" and
                    // "oriented dims set" stay one condition, matching the
                    // group tier.
                    val slanted = line.box.angleDeg != 0f
                    LineBox(
                        text = line.text,
                        bounds = scaleRect(line.box.bounds, scaleFactor),
                        groupIndex = gi,
                        elements = line.elements.map { ElementBox(it.text, scaleRect(it.box.bounds, scaleFactor)) },
                        symbols = line.chars.map { SymbolBox(it.text, scaleRect(it.box.bounds, scaleFactor), it.charOffset) },
                        orientation = line.orientation,
                        confidence = line.confidence,
                        angleDeg = line.box.angleDeg,
                        orientedWidth = if (slanted) scaleDim(line.box.orientedWidth, scaleFactor) else 0f,
                        orientedHeight = if (slanted) scaleDim(line.box.orientedHeight, scaleFactor) else 0f,
                    )
                },
                angleDeg = group.angleDeg,
                orientedWidth = scaleDim(group.orientedWidth, scaleFactor),
                orientedHeight = scaleDim(group.orientedHeight, scaleFactor),
            )
        }

        // `segments` is a display-only projection of the result: ClickableTextView
        // concatenates it to render the original text and tap-to-lookup re-tokenizes
        // by character offset (boundaries unused). Derive it through the shared
        // TextSegments helper. Render paragraph-grouped from each group's combined
        // `.text` (exactly the per-group string sent to the translator) so the
        // source mirrors the translation's group layout instead of exposing the
        // intra-group OCR line breaks. grp.text is engine-agnostic — it's the
        // translation input every engine produces, and already carries its
        // language-appropriate intra-group join (LayoutAnalyzer.buildLayoutGroup).
        val segments = TextSegments.ofGroupTexts(ocrGroups.map { it.text })

        val fullText = groups.joinToString(" ") { it.text }.trim()
        val debugBoxes = if (collectDebugBoxes) buildDebugBoxes(groups, scaleFactor) else null
        return OcrResult(
            fullText = fullText,
            segments = segments,
            groups = ocrGroups,
            debugBoxes = debugBoxes,
            engineBackend = engineBackend,
            mangaOcrUsed = mangaOcrUsed,
        )
    }

    private fun buildOcrLines(groups: List<LayoutGroup>, scaleFactor: Float): List<OcrLine> {
        val out = mutableListOf<OcrLine>()
        groups.forEachIndexed { gi, group ->
            for (line in group.lines) {
                out += OcrLine(
                    text = line.text,
                    bounds = scaleRect(line.box.bounds, scaleFactor),
                    groupIndex = gi,
                    groupText = group.text,
                    symbols = line.chars.map { SymbolBox(it.text, scaleRect(it.box.bounds, scaleFactor), it.charOffset) },
                    orientation = line.orientation,
                    // Angle survives scaling; the dims are lengths and divide
                    // like the box.
                    angleDeg = line.box.angleDeg,
                    orientedWidth = if (line.box.angleDeg != 0f) line.box.orientedWidth / scaleFactor else 0f,
                    orientedHeight = if (line.box.angleDeg != 0f) line.box.orientedHeight / scaleFactor else 0f,
                )
            }
        }
        return out
    }

    /**
     * Debug overlay boxes, projected from the grouped result. Boxes are in
     * engine-input (pre-scale) coordinates with [OcrDebugBoxes.scaleFactor] set,
     * matching the prior contract (consumers divide). The block tier is empty —
     * the vendor-neutral model carries line/element/group levels only.
     */
    private fun buildDebugBoxes(groups: List<LayoutGroup>, scaleFactor: Float): OcrDebugBoxes {
        val lineBoxes = mutableListOf<DebugBox>()
        val elementBoxes = mutableListOf<DebugBox>()
        val groupBoxes = mutableListOf<DebugBox>()
        for (group in groups) {
            groupBoxes += DebugBox(
                group.bounds, angleDeg = group.angleDeg,
                orientedWidth = group.orientedWidth, orientedHeight = group.orientedHeight,
            )
            for (line in group.lines) {
                val slanted = line.box.angleDeg != 0f
                lineBoxes += DebugBox(
                    line.box.bounds, text = line.text, angleDeg = line.box.angleDeg,
                    orientedWidth = if (slanted) line.box.orientedWidth else 0f,
                    orientedHeight = if (slanted) line.box.orientedHeight else 0f,
                )
                for (el in line.elements) elementBoxes += DebugBox(el.box.bounds, text = el.text)
            }
        }
        return OcrDebugBoxes(emptyList(), lineBoxes, elementBoxes, groupBoxes, scaleFactor)
    }

    /**
     * Samples corner pixels to estimate whether the image has a dark background
     * (suggesting light-on-dark text that should be inverted for OCR).
     *
     * Exposed [internal] so [OcrPreprocessingRecipe] implementations and the
     * instrumented golden-set tests can reuse the same auto-invert decision
     * production uses.
     */
    internal fun sampleIsDarkBackground(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val margin = (minOf(w, h) * 0.05f).toInt().coerceAtLeast(1)
        // Sample 8 points around the edges (corners + midpoints)
        val points = listOf(
            margin to margin,                   // top-left
            w - margin to margin,               // top-right
            margin to h - margin,               // bottom-left
            w - margin to h - margin,           // bottom-right
            w / 2 to margin,                    // top-center
            w / 2 to h - margin,                // bottom-center
            margin to h / 2,                    // left-center
            w - margin to h / 2                 // right-center
        )
        var brightnessSum = 0
        for ((x, y) in points) {
            val px = bitmap[x.coerceIn(0, w - 1), y.coerceIn(0, h - 1)]
            brightnessSum += (android.graphics.Color.red(px) +
                android.graphics.Color.green(px) +
                android.graphics.Color.blue(px)) / 3
        }
        return brightnessSum / points.size < 100
    }

    /** A line of OCR text with its bounding box in original (pre-scale) screen coordinates. */
    data class OcrLine(
        val text: String,
        val bounds: Rect,
        /** Index of the group this line belongs to (lines in the same group are combined text). */
        val groupIndex: Int = 0,
        /** Pre-built combined text of the entire group this line belongs to. */
        val groupText: String = text,
        /**
         * Per-character bounding boxes, aligned 1:1 with [text]. Empty if the
         * engine didn't emit symbols. When populated, drag-lookup uses these for
         * precise (non-monospaced) hit testing; empty triggers the legacy
         * charWidth fallback.
         */
        val symbols: List<SymbolBox> = emptyList(),
        /** Text orientation detected from ML Kit angle / bounding box geometry. */
        val orientation: TextOrientation = TextOrientation.HORIZONTAL,
        /** Slant in degrees (clockwise-positive, `View.rotation` semantics);
         *  0 = upright. Oriented dims ride with it (0-when-upright) — drag
         *  hit-testing un-rotates the finger into this frame. Any projection
         *  of [bounds] to another space must project these dims too. */
        val angleDeg: Float = 0f,
        val orientedWidth: Float = 0f,
        val orientedHeight: Float = 0f,
    )

    companion object {
        /** Process-scoped singleton. Engines live for the app's lifetime. */
        val instance: OcrManager by lazy { OcrManager() }

        /**
         * Detects whether a Text.Line is vertical (tategaki) or horizontal based
         * on ML Kit's reported angle and bounding box geometry. ~90° (or a tall
         * aspect ratio on multi-char lines) indicates vertical. Single-character
         * lines are ambiguous and default to horizontal.
         *
         * Reused by [com.playtranslate.ocr.engines.mlkit.MlKitTextMapper].
         */
        fun detectOrientation(line: Text.Line): TextOrientation {
            if (line.text.trim().length <= 1) return TextOrientation.HORIZONTAL
            try {
                val angle = line.angle.toDouble()
                if (angle in 60.0..120.0 || angle in -120.0..-60.0) {
                    return TextOrientation.VERTICAL
                }
            } catch (_: Throwable) {
                // getAngle() may not exist in all versions — fall through to geometry
            }
            val bb = line.boundingBox ?: return TextOrientation.HORIZONTAL
            val w = bb.width()
            val h = bb.height()
            if (w > 0 && h.toFloat() / w > 2.0f) return TextOrientation.VERTICAL
            return TextOrientation.HORIZONTAL
        }

        /**
         * Returns true if [c] belongs to a script native to [sourceLang]. Used by
         * the ML Kit adapter's line filter and the overlay dedup / no-text key
         * ([com.playtranslate.OverlayToolkit]). Delegates to the single
         * vendor-neutral definition in
         * [com.playtranslate.ocr.core.LayoutAnalyzer.isSourceLangChar] so every
         * path shares ONE script-coverage source — a hardcoded duplicate here had
         * drifted to a base-block-only Arabic range and silently dropped Arabic
         * Supplement / Extended-A / Presentation-Form glyphs (e.g. ﷲ) from the
         * dedup key, producing a false NoText.
         */
        fun isSourceLangChar(c: Char, sourceLang: String): Boolean =
            com.playtranslate.ocr.core.LayoutAnalyzer.isSourceLangChar(c, sourceLang)
    }
}
