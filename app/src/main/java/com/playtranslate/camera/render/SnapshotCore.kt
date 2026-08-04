package com.playtranslate.camera.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import androidx.core.graphics.scale
import com.playtranslate.BuildConfig
import com.playtranslate.CaptureState
import com.playtranslate.OcrManager
import com.playtranslate.OneShotOverlayData
import com.playtranslate.OverlayToolkit
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.language.TextOrientation
import com.playtranslate.model.OcrProvenance
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TextSegments
import com.playtranslate.model.TranslationLangContext
import com.playtranslate.model.TranslationResult
import com.playtranslate.ocr.core.RegionPreFilter
import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.ocr.registry.ocrLabel
import com.playtranslate.camera.CameraTranslator
import com.playtranslate.ocr.registry.selectionToken
import com.playtranslate.ui.TextBox

/**
 * A frozen scene's word-lookup payload: the display cache's OCR lines
 * flattened to [OcrManager.OcrLine] (AU coordinates — the caller maps to
 * view space), plus the AU dims and the saved screenshot path (Anki card
 * attachments). Produced by whichever flow owns the frozen display —
 * camera snapshot or import review.
 */
data class FrozenLookupScene(
    val lines: List<OcrManager.OcrLine>,
    val auWidth: Int,
    val auHeight: Int,
    val screenshotPath: String?,
)

/**
 * The camera-frame snapshot pipeline's SEMANTICS, extracted so the camera
 * tool and the import-image tool share one implementation: recognition
 * gating (confidence + edge), region filtering, provenance, placeholder
 * boxes, color sampling, and result assembly. Pure with respect to session
 * state — every prefs-derived input arrives as a parameter, so callers bind
 * their own scope (camera vs import OCR tokens, flavors) at the call site.
 *
 * The cycle SEQUENCING and the display substrate (caches, locks, epochs)
 * deliberately stay per-flow: the camera's are entangled with its live
 * tracker path, the import tool's are single-writer. Policy changes land
 * here once; both cycles pick them up.
 */
object SnapshotCore {

    /** Groups whose known line confidences average below the engine's
     *  threshold are dropped before translation — garbage reads (rotated
     *  text, blur) translate into fluent-sounding nonsense otherwise.
     *  Engines that report no confidence (-1) are never gated. Thresholds
     *  are per-engine-family, calibrated from device logs (2026-07-07
     *  Moto G): ML Kit good reads sit 0.76-0.84; Meiki garbage sat
     *  0.32-0.45. Extend as kept/dropped logs accumulate. */
    const val MIN_GROUP_CONFIDENCE_DEFAULT = 0.5f
    const val MIN_GROUP_CONFIDENCE_MLKIT = 0.6f

    /** Post-OCR edge gate margin (AU px) — see [usableGroups]. */
    const val EDGE_MARGIN_PX = 12

    /** Pre-recognition edge margin as a fraction of the processed frame's
     *  dimensions (mirrors [EDGE_MARGIN_PX] post-OCR). */
    const val EDGE_MARGIN_FRAC = 0.012f

    /** Color-reference downscale for per-group color sampling. */
    const val COLOR_SCALE = 4

    /**
     * Camera-frame quality gate. OCR output is deliberately NOT trusted here
     * (camera frames — unlike screenshots — carry blur, rotation, and
     * frame-edge clipping the engines weren't tuned for):
     *  - drop groups whose known line confidences average below the engine
     *    family's threshold (garbage reads translate into fluent nonsense);
     *    engines reporting no confidence are not gated;
     *  - drop groups clipped at the frame edge on their reading axis
     *    (the line continues off-frame; the fragment reads as a non
     *    sequitur once translated).
     *
     * [translating] is the raw language-pair fact (source != target);
     * [skipEdgeGate] is the snapshot override — snapshots keep the
     * confidence gate but read the whole frame, clipped lines included.
     * [tag] keeps gate logs under the calling flow's log tag.
     */
    fun usableGroups(
        ocr: OcrManager.OcrResult,
        auWidth: Int,
        auHeight: Int,
        translating: Boolean,
        skipEdgeGate: Boolean = false,
        tag: String = TAG,
    ): List<OcrManager.OcrGroup> {
        // Edge-clipped fragments only hurt when TRANSLATED (a cut-off line
        // renders as a fluent non sequitur). In same-language OCR-only mode
        // a clipped line is still honest output — and on a full-frame
        // document the edge gate would otherwise discard most of the page
        // (28 of 36 groups observed).
        val edgeGate = translating && !skipEdgeGate
        val confThreshold =
            if (ocr.engineBackend?.toString()?.startsWith("MLKit") == true) MIN_GROUP_CONFIDENCE_MLKIT
            else MIN_GROUP_CONFIDENCE_DEFAULT
        return ocr.groups.filter { g ->
            if (g.text.isBlank()) return@filter false
            val known = g.lines.map { it.confidence }.filter { it >= 0f }
            if (known.isNotEmpty() && known.average() < confThreshold) {
                // Camera OCR content is PRIVATE (documents, screens) — raw
                // text never reaches production logs, only debug builds.
                if (BuildConfig.DEBUG) {
                    Log.d(tag, "gate: dropped low-confidence (%.2f) group \"%s\"".format(known.average(), g.text.take(40)))
                }
                return@filter false
            }
            if (known.isNotEmpty() && BuildConfig.DEBUG) {
                // Kept-group confidences calibrate the threshold: we need to
                // know where GOOD reads sit on this device, not just the bad.
                Log.d(tag, "gate: kept (%.2f) group \"%s\"".format(known.average(), g.text.take(40)))
            }
            if (edgeGate) {
                val clipped = when (g.orientation) {
                    TextOrientation.VERTICAL ->
                        g.bounds.top <= EDGE_MARGIN_PX || g.bounds.bottom >= auHeight - EDGE_MARGIN_PX
                    else ->
                        g.bounds.left <= EDGE_MARGIN_PX || g.bounds.right >= auWidth - EDGE_MARGIN_PX
                }
                if (clipped) {
                    if (BuildConfig.DEBUG) {
                        Log.d(tag, "gate: dropped edge-clipped group \"${g.text.take(40)}\"")
                    }
                    return@filter false
                }
            }
            true
        }
    }

    /**
     * Pre-recognition detection filter (region clip + edge gate + center-out
     * priority). [dropEdgeClipped] is the FINAL edge-gate decision — the
     * caller ANDs its flow policy with the language-pair fact. [clipTo] is a
     * snapshot region in the [clipFrameW]x[clipFrameH] frame; the filter
     * runs in the PROCESSED image's space — the OCR recipe scales common
     * camera frames — so the region is projected into the filter's space per
     * invocation (comparing spaces raw silently shrank the region's
     * effective coverage toward the top-left by the scale factor). A
     * recognition-cost saver ONLY — single-model engines (ML Kit) ignore the
     * detect/recognize seam entirely, so the correctness gate is the
     * group-level [regionCenterFilter].
     */
    fun regionPreFilter(
        dropEdgeClipped: Boolean,
        clipTo: Rect? = null,
        clipFrameW: Int = 0,
        clipFrameH: Int = 0,
        tag: String = TAG,
    ): RegionPreFilter = RegionPreFilter { regions, w, h ->
        val inRegion = if (clipTo == null) regions else {
            val sx = if (clipFrameW > 0) w.toFloat() / clipFrameW else 1f
            val sy = if (clipFrameH > 0) h.toFloat() / clipFrameH else 1f
            val clip = Rect(
                (clipTo.left * sx).toInt(),
                (clipTo.top * sy).toInt(),
                (clipTo.right * sx).toInt(),
                (clipTo.bottom * sy).toInt(),
            )
            regions.filter { r ->
                clip.contains(r.box.bounds.centerX(), r.box.bounds.centerY())
            }
        }
        val mx = (w * EDGE_MARGIN_FRAC).toInt()
        val my = (h * EDGE_MARGIN_FRAC).toInt()
        val kept = if (!dropEdgeClipped) inRegion else inRegion.filter { r ->
            val b = r.box.bounds
            val clipped = when (r.orientation) {
                TextOrientation.VERTICAL ->
                    b.top <= my || b.bottom >= h - my
                else -> b.left <= mx || b.right >= w - mx
            }
            !clipped
        }
        if (inRegion.size != regions.size) {
            Log.d(tag, "gate: skipped recognition for ${regions.size - inRegion.size} outside-region detections")
        }
        if (kept.size != inRegion.size) {
            Log.d(tag, "gate: skipped recognition for ${inRegion.size - kept.size} edge-clipped detections")
        }
        val cx = w / 2f
        val cy = h / 2f
        kept.sortedBy { r ->
            val b = r.box.bounds
            val dx = b.exactCenterX() - cx
            val dy = b.exactCenterY() - cy
            dx * dx + dy * dy
        }
    }

    /** The region gate proper, at GROUP level: single-model engines never
     *  see the detect/recognize seam, and a group is the translation/Anki
     *  unit — center-inside keeps whole paragraphs, never half-clipped
     *  ones. Null region = whole frame. */
    fun regionCenterFilter(
        groups: List<OcrManager.OcrGroup>,
        regionAu: Rect?,
    ): List<OcrManager.OcrGroup> =
        if (regionAu == null) groups else groups.filter {
            regionAu.contains(it.bounds.centerX(), it.bounds.centerY())
        }

    /** Mirror of the service's provenance builders: engine from the result
     *  when OCR ran, else the backend [tokenOverride] resolves — the no-text
     *  gear needs a token to key the picker. Full-frame region; frames are
     *  clean images (no system UI, no own overlays). */
    fun snapshotProvenance(
        ctx: Context,
        ocr: OcrManager.OcrResult?,
        srcId: SourceLangId,
        tokenOverride: String?,
    ): OcrProvenance? {
        val backend = ocr?.engineBackend
            ?: OcrModelManager.selectedBackend(ctx, srcId, tokenOverride)
            ?: return null
        val label =
            if (ocr?.mangaOcrUsed == true) "${backend.ocrLabel(ctx)} + MangaOCR"
            else backend.ocrLabel(ctx)
        return OcrProvenance(
            label, backend.selectionToken,
            android.view.Display.DEFAULT_DISPLAY, srcId,
            com.playtranslate.CaptureService.DEFAULT_REGION,
            frameIncludesSystemUi = false,
            frameIncludesOwnOverlays = false,
        )
    }

    /** Skeleton boxes for the groups awaiting their translations. */
    fun buildPlaceholderBoxes(
        groups: List<OcrManager.OcrGroup>,
        groupColors: List<Pair<Int, Int>>,
    ): List<TextBox> = groups.mapIndexed { idx, group ->
        val (bg, tc) = groupColors.getOrElse(idx) { Pair(Color.argb(224, 0, 0, 0), Color.WHITE) }
        TextBox(
            translatedText = "",
            bounds = group.bounds,
            bgColor = bg,
            textColor = tc,
            lineCount = group.lines.size,
            sourceText = group.text,
            orientation = group.orientation,
            alignment = group.alignment,
            angleDeg = group.angleDeg,
            orientedWidth = group.orientedWidth,
            orientedHeight = group.orientedHeight,
        )
    }

    /** Per-group (bg, text) colors sampled from a transient ×[COLOR_SCALE]
     *  reference of [frame] — sampled ONCE so no bitmap is retained. */
    fun sampleGroupColors(
        frame: Bitmap,
        bounds: List<Rect>,
    ): List<Pair<Int, Int>> {
        val colorRef = frame.scale(frame.width / COLOR_SCALE, frame.height / COLOR_SCALE, false)
        return try {
            OverlayToolkit.sampleGroupColors(colorRef, bounds, 0, 0, COLOR_SCALE)
        } finally {
            colorRef.recycle()
        }
    }

    /** Panel text must match what gets translated. When the gates dropped
     *  nothing (the common case) the recognizer's own fullText/segments are
     *  used verbatim (they carry the richer per-line segmentation);
     *  otherwise both are rebuilt from the gated groups so source and
     *  translation stay paragraph-aligned. */
    fun panelTextFor(
        ocr: OcrManager.OcrResult,
        gatedGroups: List<OcrManager.OcrGroup>,
    ): Pair<String, List<TextSegment>> =
        if (gatedGroups.size == ocr.groups.size) {
            ocr.fullText to ocr.segments
        } else {
            val text = gatedGroups.joinToString("\n\n") { it.text }
            text to TextSegments.ofText(text)
        }

    // ── Saved-frame files ───────────────────────────────────────────────
    // Both flows save their reviewed frame beside the capture flow's
    // screenshots under UNIQUE per-cycle names. A fixed per-tool name was a
    // bug class (Codex adversarial finding on the import tool): cycles
    // overlap (cooperative cancellation), and the import tool's share
    // target even allows two live activity instances whose sessions share
    // no generation counter — a fixed path let a stale or sibling cycle's
    // write alias the active review's Anki attachments and process-death
    // restore. Unique names make each write private to its cycle; the path
    // becomes shared state only at the caller's generation-guarded cache
    // publication, and unpublished/replaced files are deleted at those
    // guard points. [sweepFrameFiles] reclaims what crashes leave behind.

    /** Orphan cutoff for [sweepFrameFiles]: generous next to a real
     *  review's lifetime, tight enough that process-death leftovers don't
     *  pile up. A LIVE review older than this in a background activity
     *  instance loses only its Anki-attachment file. */
    private const val FRAME_ORPHAN_AGE_MS = 24 * 60 * 60 * 1000L

    /** Same-millisecond save disambiguator, process-wide across tools. */
    private val frameCounter = java.util.concurrent.atomic.AtomicInteger()

    private fun framesDir(ctx: Context) = java.io.File(ctx.cacheDir, "screenshots")

    /** Compress [frame] to a fresh uniquely-named JPEG under [prefix];
     *  null on failure (callers already treat the path as optional). */
    fun saveFrame(ctx: Context, frame: Bitmap, prefix: String, tag: String = TAG): String? = try {
        val dir = framesDir(ctx).apply { mkdirs() }
        val file = java.io.File(
            dir,
            "$prefix${System.currentTimeMillis()}-${frameCounter.incrementAndGet()}.jpg",
        )
        file.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        file.absolutePath
    } catch (e: Exception) {
        Log.w(tag, "frame save failed", e)
        null
    }

    /** Retire a frame file that lost its guard or was replaced/dismissed. */
    fun deleteFrame(path: String?) {
        if (path == null) return
        runCatching { java.io.File(path).delete() }
    }

    /** Collect [prefix] frames orphaned by a crash or process death (their
     *  cycle's delete never ran). Age-gated so a sibling activity
     *  instance's live frame survives; [keepPath] additionally protects a
     *  restore target regardless of age. Call from activity onCreate, off
     *  the main thread. */
    fun sweepFrameFiles(ctx: Context, prefix: String, keepPath: String?) {
        val cutoff = System.currentTimeMillis() - FRAME_ORPHAN_AGE_MS
        framesDir(ctx).listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith(prefix) &&
                f.absolutePath != keepPath && f.lastModified() < cutoff
            ) {
                runCatching { f.delete() }
            }
        }
    }

    /** Assemble the terminal Done state from the pipeline's outputs — the
     *  shape both flows' panels bind identically. */
    fun doneState(
        originalText: String,
        segments: List<TextSegment>,
        perGroup: List<CameraTranslator.Detailed>,
        screenshotPath: String?,
        provenance: OcrProvenance?,
        langContext: TranslationLangContext,
        auW: Int,
        auH: Int,
    ): CaptureState.Done {
        val translated = perGroup.joinToString("\n\n") { it.text }
        val note = perGroup.mapNotNull { it.note }.firstOrNull()
        val backendDisplayName = perGroup.mapNotNull { it.backendDisplayName }.firstOrNull()
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        return CaptureState.Done(
            TranslationResult(
                originalText = originalText,
                segments = segments,
                translatedText = translated,
                timestamp = timestamp,
                screenshotPath = screenshotPath,
                note = note,
                backendDisplayName = backendDisplayName,
                ocrProvenance = provenance,
                langContext = langContext,
            ),
            // Non-empty overlayData lights the panel's "Show on screen"
            // action; both flows' presenters ignore the boxes themselves and
            // paint through their own warp path.
            OneShotOverlayData(emptyList(), 0, 0, auW, auH),
        )
    }

    private const val TAG = "SnapshotCore"
}
