package com.playtranslate.ocr

import android.graphics.Bitmap
import android.util.Log
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.ocr.core.BlockTextAligner
import com.playtranslate.ocr.core.DeskewGeometry
import com.playtranslate.ocr.core.DetectedRegion
import com.playtranslate.ocr.core.LayoutGroup
import com.playtranslate.ocr.core.OcrBox
import com.playtranslate.ocr.core.OcrImage
import com.playtranslate.ocr.core.RecognizedRegion
import com.playtranslate.ocr.core.RecognizedTextNormalizer
import com.playtranslate.ocr.mangaocr.MangaOcrBridge
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Optional post-layout refinement: re-read the selected engine's [LayoutGroup]s with
 * the manga-ocr specialist and splice its (better) reading back onto the base lines.
 * Invoked by [OcrPipeline] AFTER [com.playtranslate.ocr.core.LayoutAnalyzer.analyze],
 * so it runs once and both `recognise` (translate) and `recogniseWithPositions`
 * (drag-lookup) inherit the result. Coordinates stay in engine-input space (same as
 * [LayoutGroup] and the `processed` bitmap), so the caller's existing scaleFactor
 * projection is untouched.
 *
 * **Block-level, both orientations.** The whole group crop is manga-ocr's native
 * input shape: multi-line blocks read substantially better than the same content fed
 * line-by-line, and one decode covers the group. What bounds eligibility is squash
 * resolution, not orientation — every crop is aspect-distorted into the encoder's
 * 224² input, so a group whose longest line packs too many glyphs per patch
 * ([MAX_LINE_CHARS]), stacks too many lines ([MAX_LINES]), mixes inconsistent
 * slants (see [isEligible]), or is too long to decode within budget
 * ([MAX_BLOCK_CHARS]) is skipped and the base result stands (the base engines are
 * fine on exactly those shapes). Consistently-slanted groups read through the
 * deskew rotate-crop ([com.playtranslate.ocr.mangaocr.deskewAffine]).
 *
 * **Positions come from the base pass, not the model.** manga-ocr emits a bare
 * string; [BlockTextAligner] aligns it against the base lines so matched/substituted
 * chars inherit the base engine's REAL char boxes and only insertions are
 * interpolated — strictly better than the former even-spread synthesis. The
 * aligner's gap/match-rate guards double as the adoption policy every group
 * (single-line included) now passes: a reading that doesn't align — a hallucination
 * or a mis-scoped crop — is rejected instead of adopted on a bare text-differs check.
 *
 * **Decode budget + abort.** The aligner rejects any reading longer than ~1.5× the
 * base text, so decoding past that is guaranteed-rejected work on a no-KV-cache
 * decoder whose runaways are quadratic-expensive: each block decode is capped at
 * [budgetFor] the base length, and the session polls the caller's cancellation
 * between steps, so a superseded frame aborts mid-block.
 *
 * **Best-effort.** A decode fault, a blank/truncated reading, or a rejected
 * alignment keeps the base group; cancellation still propagates. Candidates pass
 * through [RecognizedTextNormalizer] before alignment so this opt-in path can't
 * reinject junk (e.g. a ▼ advance-cursor) after the pipeline's one cleanup stage.
 *
 * **Concurrency.** The session, its serializing lock, and its teardown are all owned
 * by [MangaOcrBridge]; this refiner never touches a raw session. It runs each frame
 * inside [MangaOcrBridge.withRecognizer], which holds the bridge lock for the whole
 * scope — so overlapping frames (a live capture vs a drag-lookup) serialize, and an
 * interactive close can't tear the session down mid-decode. The lifecycle race is
 * structural, not discipline: there is no way to obtain the session outside that
 * lock. The eligibility gate runs BEFORE the bridge is touched, so a frame with
 * nothing to refine never triggers the lazy session build (~71MB native).
 */
object MangaOcrRefiner {

    private const val TAG = "MangaOcrRefiner"

    // Eligibility gates. All three are provisional pending on-device calibration —
    // sourced from the manga-ocr findings report, not the golden suite.

    /** Longest-line cap (chars along the line's own reading axis): the 224² squash
     *  leaves too little per-glyph detail past this — the bake-off's ~40-char lines
     *  garbled while ~16-char columns read fine (224px / 14 patches ≈ 1 patch/char
     *  at 16). */
    private const val MAX_LINE_CHARS = 20

    /** Line-count cap: the cross-axis analogue of [MAX_LINE_CHARS] — a tall stack
     *  squashes each line's height below patch resolution. */
    private const val MAX_LINES = 8

    /** Total-chars cap: bounds the worst-case per-block decode (~1–2s at 96 chars on
     *  the Thor, via [budgetFor]) so one huge paragraph can't stall a live frame. */
    private const val MAX_BLOCK_CHARS = 96

    /**
     * Decode seam: one block crop → one reading. Production ([refine]) binds
     * [com.playtranslate.ocr.mangaocr.MangaOcrRecognizer]'s budgeted overload inside
     * the bridge lock; tests inject fakes through [refineWith].
     */
    internal fun interface BlockReader {
        suspend fun read(image: OcrImage, region: DetectedRegion, maxTokens: Int): RecognizedRegion?
    }

    /**
     * One refinement pass's outcome: the (possibly refined) [groups], plus how many
     * of them manga-ocr actually READ — a non-null reading that survived
     * normalization and reached the aligner. An alignment-rejected reading counts
     * (it was decoded; the guards just kept the base), but a no-output attempt —
     * blank decode, hit-cap runaway, aborted, degenerate crop, normalized-to-junk —
     * does not: attribution must never credit MangaOCR for a capture where every
     * attempt produced nothing usable. [decodedBlocks] == 0 also covers the model
     * never running (nothing eligible, model not loadable, pass failed). The caller
     * surfaces `decodedBlocks > 0` as the "Scanned by … + MangaOCR" attribution.
     */
    data class Refined(val groups: List<LayoutGroup>, val decodedBlocks: Int)

    /** One group's refinement outcome: the kept-or-adopted [group], plus whether a
     *  usable reading was obtained for it (see [Refined.decodedBlocks]). */
    private data class GroupOutcome(val group: LayoutGroup, val decoded: Boolean)

    /** Decode-step budget for a block whose base reading has [baseLen] chars.
     *  [BlockTextAligner]'s gap-rate guard auto-rejects any reading past ~1.5× the
     *  base length, so steps beyond that are guaranteed-rejected work; +4 slack
     *  absorbs token/char drift (skipped [UNK]s, rare multi-char vocab entries).
     *  The session clamps to its own MAX_LEN ceiling. */
    internal fun budgetFor(baseLen: Int): Int = baseLen + baseLen / 2 + 4

    /** True when the specialist can plausibly beat the base engine on [group] —
     *  see the gate constants for why each bound exists. A slanted group is
     *  eligible when its lines' angles are CONSISTENT with the group's (within
     *  the cluster cap — exactly what the grouping shell built, so this admits
     *  every cluster and reduces to a no-op for upright groups, whose lines are
     *  all angle-0 post-clustering); the rotate-crop then reads the deskewed
     *  strip. Residual per-line slant ≤ the cap rides inside the crop —
     *  manga-ocr's home turf is hand-drawn text (accepted). A line whose angle
     *  disagrees with its group past the cap means the crop would misframe it.
     *  UNMEASURED lines ([OcrBox.angleUnmeasured]) are exempt from the check:
     *  their 0 is a withheld measurement, not a disagreement, the shell admits
     *  them into slanted groups by position, and the producer's excursion gate
     *  bounds their true-vs-0 discrepancy under the crop's accepted residual. */
    internal fun isEligible(group: LayoutGroup): Boolean {
        val lines = group.lines
        if (lines.isEmpty() || lines.size > MAX_LINES) return false
        if (lines.any {
                !it.box.angleUnmeasured &&
                    abs(it.box.angleDeg - group.angleDeg) > DeskewGeometry.DEFAULT_CLUSTER_CAP_DEG
            }
        ) {
            return false
        }
        val longest = lines.maxOf { it.text.length }
        if (longest == 0 || longest > MAX_LINE_CHARS) return false
        return lines.sumOf { it.text.length } <= MAX_BLOCK_CHARS
    }

    /**
     * Returns [Refined]: [groups] with eligible groups re-read by manga-ocr where the
     * aligned reading differs from the base, plus the decoded-block count for the
     * attribution. A no-op (input groups, 0 decodes) when nothing is eligible —
     * checked BEFORE the bridge so the session is never built for a frame it can't
     * help — or when the model isn't loadable ([MangaOcrBridge.withRecognizer]
     * yields null and the base result stands). The caller gates on enablement /
     * source language / ABI before invoking.
     */
    suspend fun refine(
        groups: List<LayoutGroup>,
        processed: Bitmap,
        sourceLang: String,
        logText: Boolean = false,
    ): Refined {
        if (groups.none(::isEligible)) return Refined(groups, 0)
        return MangaOcrBridge.withRecognizer { recognizer ->
            runRefinement(
                { image, region, maxTokens -> recognizer.recognize(image, region, maxTokens) },
                groups, processed, sourceLang, logText,
            )
        } ?: Refined(groups, 0) // model not loadable — the bridge already logged why (once); base stands
    }

    /** Test seam: drive the transform with an injected [BlockReader]. Production
     *  [refine] goes through [MangaOcrBridge.withRecognizer], which owns the session
     *  lock. */
    internal suspend fun refineWith(
        reader: BlockReader,
        groups: List<LayoutGroup>,
        processed: Bitmap,
        sourceLang: String,
        logText: Boolean = false,
    ): Refined = runRefinement(reader, groups, processed, sourceLang, logText)

    /** The transform. Production runs it inside [MangaOcrBridge.withRecognizer]'s lock;
     *  the [refineWith] test seam runs it directly. Best-effort: a decode fault returns
     *  the base [groups]; cancellation still propagates. */
    private suspend fun runRefinement(
        reader: BlockReader,
        groups: List<LayoutGroup>,
        processed: Bitmap,
        sourceLang: String,
        logText: Boolean,
    ): Refined {
        val t0 = System.nanoTime()
        val lineJoin =
            if (SourceLanguageProfiles.forCode(sourceLang)?.wordsSeparatedByWhitespace == true) " " else ""
        val image = OcrImage(processed, sourceLang)
        var attempted = 0
        var decoded = 0
        return try {
            val out = groups.map { group ->
                if (!isEligible(group)) {
                    group
                } else {
                    attempted++
                    val outcome = refineGroup(group, reader, image, lineJoin, logText)
                    if (outcome.decoded) decoded++
                    outcome.group
                }
            }
            if (logText) {
                val changed = out.indices.count { out[it] !== groups[it] }
                val ms = (System.nanoTime() - t0) / 1_000_000
                val perBlock = if (attempted > 0) " (~${ms / attempted}ms/block)" else ""
                Log.i(
                    TAG,
                    "ran in ${ms}ms$perBlock: $attempted/${groups.size} block(s) attempted, " +
                        "$decoded read, $changed adopted" +
                        if (attempted == 0) " (no eligible group)" else "",
                )
            }
            Refined(out, decoded)
        } catch (c: CancellationException) {
            throw c // a superseded frame's cancellation must propagate, not be swallowed
        } catch (t: Throwable) {
            // Best-effort: an OPTIONAL refinement must never sink a successful base OCR
            // pass. Keep the base groups — and report 0 decodes, since nothing manga-ocr
            // read survived into the result. Don't close the session here — a transient
            // native error is better retried next capture than escalated, and closing
            // mid-frame is exactly the race MangaOcrBridge's locked teardown avoids.
            Log.w(TAG, "refinement failed — keeping base OCR result", t)
            Refined(groups, 0)
        }
    }

    private suspend fun refineGroup(
        group: LayoutGroup,
        reader: BlockReader,
        image: OcrImage,
        lineJoin: String,
        logText: Boolean,
    ): GroupOutcome {
        coroutineContext.ensureActive() // stop a superseded frame promptly
        val baseLen = group.lines.sumOf { it.text.length }
        // A slanted group hands the recognizer its true oriented box — the
        // rotate-crop keys on it; upright groups keep the plain AABB.
        val region = DetectedRegion(
            box = if (group.angleDeg != 0f) {
                OcrBox(group.bounds, group.orientedWidth, group.orientedHeight, group.angleDeg)
            } else {
                OcrBox.upright(group.bounds)
            },
            orientation = group.orientation,
        )
        // Run the candidate through the SAME normalizer the base lines already passed
        // (edge pipe/cursor strip, decoration-only drop) so this opt-in path can't
        // reinject junk after the pipeline's one cleanup stage. Drops → keep base.
        val blockText = reader.read(image, region, budgetFor(baseLen))
            ?.let { RecognizedTextNormalizer.normalize(listOf(it), image.sourceLang).firstOrNull() }
            ?.lines?.firstOrNull()?.text
        // An aborted decode (superseded frame) comes back as a null reading, not a
        // throw. Current callers are already protected — OcrPipeline's withContext
        // rethrows on a cancelled job — but re-check here so cancellation surfaces
        // from THIS boundary too, keeping the refiner self-contained for any future
        // caller that doesn't wrap it.
        coroutineContext.ensureActive()
        // No usable reading (blank/hit-cap/degenerate decode, or normalized to junk):
        // keep the base AND report decoded=false — this attempt must not feed the
        // "+ MangaOCR" attribution.
        if (blockText.isNullOrBlank()) return GroupOutcome(group, decoded = false)
        return when (val result = BlockTextAligner.align(blockText, group.lines)) {
            is BlockTextAligner.Result.Rejected -> {
                if (logText) Log.d(TAG, "  rejected \"${group.text.take(24)}\": ${result.reason}")
                GroupOutcome(group, decoded = true) // read fine — the guards kept the base
            }
            is BlockTextAligner.Result.Aligned -> {
                val lines = result.lines
                if (lines.indices.all { lines[it] === group.lines[it] }) {
                    return GroupOutcome(group, decoded = true) // confirming read
                }
                // Rebuild group text with the same join the layout used, from the same
                // line order (aligner output preserves size + order).
                val text = lines.joinToString(lineJoin) { it.text }.trim()
                if (logText) Log.d(TAG, "  base=\"${group.text}\" -> manga=\"$text\"")
                GroupOutcome(group.copy(text = text, lines = lines), decoded = true)
            }
        }
    }
}
