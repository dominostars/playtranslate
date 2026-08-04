package com.playtranslate

import android.graphics.Rect
import com.playtranslate.language.TextAlignment
import com.playtranslate.language.TextOrientation
import com.playtranslate.ocr.core.LayoutAnalyzer
import com.playtranslate.ui.TextBox
import kotlin.math.abs

/**
 * Text-space single-cycle reconciler — "Level 0". Ported from the `scanlines`
 * branch (built there for swept clean composites); on this branch it drives
 * [ReconcilerLiveMode] — every live flavor's shared sensing loop; overlay
 * presenters run it on API 34+ single-app streams whose frames are clean by
 * construction, the panel presenter on any stream.
 *
 * The mode answers "what changed" in TEXT space, never in pixel space: every
 * cycle it OCRs a clean frame and hands the fresh groups plus the
 * currently-displayed boxes here. This class decides, per region, whether an
 * overlay is still valid, has changed, or has vanished, and which regions to
 * translate — all from a SINGLE read.
 *
 * ## Level 0: read the screen, translate the screen, update the screen
 * There is no confirmation gate, no pending state, no grace counters, and no
 * cross-cycle memory of any kind inside this class — it is a pure function of
 * (groups, boxes) and holds NOTHING between calls. The only cross-cycle memory
 * in the whole mode is the mode's currently-displayed box list, which is handed
 * back in as [boxes]. Every artifact self-heals within one cycle by
 * construction; that is accepted and intended (see the ledger below). (The
 * mode may additionally run a bounded typewriter hold over CHANGED verdicts —
 * see [TypewriterGate] — which is deliberately OUTSIDE this class so the
 * reconciler stays a pure single-read function.)
 *
 * ## Verdicts (see [reconcile])
 *  - **KEEP** — a box pairs with a group whose text is not a significant change
 *    ([OverlayToolkit.isSignificantChange]) and the box already carries a
 *    translation. It passes through verbatim. The dominant steady-state verdict.
 *  - **RETRANSLATE** — a paired box is (re)translated at the group's region.
 *    Two paths lead here: the text differs (the region's content changed, old
 *    box dropped), or the text matches but the box's translation is empty (an
 *    earlier translation failed or returned blank — a plain retry). Either way
 *    the region is emitted in [Verdicts.toTranslate] on THIS read.
 *  - **REMOVE** — a box pairs with no group. It is removed immediately, on the
 *    first empty read. One empty read suffices; there is no grace counter.
 *  - **NEW** — a group pairs with no box. It is translated immediately, on the
 *    first sighting, emitted in [Verdicts.toTranslate].
 *
 * ## Accepted artifact ledger
 * Translating on the first read means transient reads reach the translator:
 *  - A typewriter reveal translates each partial string it is caught mid-frame
 *    ("H", "He", "Hel", …). Each partial self-corrects on the next cycle once
 *    the region's text differs again, and lands on the final translation once
 *    the text stops changing.
 *  - A one-cycle OCR miss (ML Kit hiccup) that drops a region for a single
 *    read removes its box; the box re-appears (re-translated) the moment the
 *    region is read again. The user sees a one-cycle blink.
 *  - A garbage read at a live region translates the garbage once, then
 *    self-heals on the next clean read (text differs → RETRANSLATE).
 * These are the deliberate cost of zero cross-cycle state: the machine can
 * never wedge, stall, or carry stale confirmation — it always reflects the last
 * read, and the last read is at most one cycle old.
 *
 * ## Two deliberate fuzziness layers
 * Neither the region geometry nor the OCR text is ever exact, so both matching
 * layers are intentionally tolerant:
 *  - **Region fuzz** — box↔group pairing is by [LayoutAnalyzer.wouldGroup] in
 *    [LayoutAnalyzer.GroupingMode.CROSS_FRAME_SAME_REGION] mode, NOT by rect
 *    equality. OCR bounds jitter a few pixels every frame (glyph mix,
 *    anti-aliasing); a same-region re-read must still pair with its box or the
 *    whole page would churn REMOVE+NEW on pixel noise.
 *  - **Character fuzz** — a paired box counts as unchanged when
 *    [OverlayToolkit.isSignificantChange] is false: a bag-of-characters
 *    tolerance that absorbs a swapped or dropped glyph or two, so a single OCR
 *    misread does not force a needless retranslation of otherwise-stable text.
 *    The fuzz answers IDENTITY only ("same text, modulo noise") — it must not
 *    also decide WHICH reading is canonical, or a garbled first read within
 *    tolerance would be locked in forever. When a fuzz-same pair's raw strings
 *    differ, [ReadingArbiter] ranks the two readings (confidence, junk score)
 *    and a winning fresh read retranslates as an upgrade
 *    ([Verdicts.upgraded]); ties keep the incumbent, today's behavior.
 * Pairing is by GEOMETRY only — text is judged AFTER pairing, so a same-region
 * re-OCR whose text genuinely changed still pairs (→ RETRANSLATE) instead of
 * falling through to REMOVE + NEW.
 *
 * Pure Kotlin: the only platform type it touches is [android.graphics.Rect]
 * (via [LayoutAnalyzer.wouldGroup] geometry). It injects nothing, holds no
 * state, and is fully deterministic — data in, verdicts out — so it unit-tests
 * on the JVM like [Classification]. Stateless since Level 0, hence an `object`.
 * ONE scoped impurity: an identical-text KEEP ratchets the kept box's stored
 * read score in place ([TextBox.ratchetSourceConf]) — monotone, idempotent,
 * verdict-neutral within the call. It exists because the score is cross-cycle
 * EVIDENCE, and evidence that is stamped once and never refreshed recreates
 * the first-read-wins trap the arbiter was built to remove, one level up.
 */
object ScanlineReconciler {

    /** An UNCHANGED box is only re-emitted onto its group's fresh bounds when
     *  some edge has moved more than this many px. Below this is OCR jitter on
     *  static text — repositioning that would make boxes shiver every cycle. */
    private const val REPOSITION_HYSTERESIS_PX = 5

    /** A slanted box whose fresh read is also slanted, at an angle this many
     *  degrees away, is refreshed like a bounds drift. Bounds alone can miss
     *  pure angle drift — the AABB moves only ~w·cosθ px per degree, under
     *  [REPOSITION_HYSTERESIS_PX] for short lines. 0↔non-zero flips are
     *  deliberately EXCLUDED ([slantDrifted]): a real transition to/from
     *  upright moves the AABB far past the bounds hysteresis and is caught
     *  there, while an equal-bounds flip is the producer's 10° snap threshold
     *  hovering under detector jitter — refreshing on that would rebuild the
     *  overlay every flip (the flap failure), so the born state stays sticky. */
    private const val SLANT_REFRESH_HYSTERESIS_DEG = 3f

    /** See [SLANT_REFRESH_HYSTERESIS_DEG]. */
    private fun slantDrifted(box: TextBox, g: OcrManager.OcrGroup): Boolean =
        box.angleDeg != 0f && g.angleDeg != 0f &&
            kotlin.math.abs(box.angleDeg - g.angleDeg) > SLANT_REFRESH_HYSTERESIS_DEG

    /** The data the mode needs to (re)build one box: an OCR group reduced to
     *  the fields [OverlayToolkit.buildPlaceholderBoxes] and
     *  [OverlayToolkit.translatePlaceholders] consume. */
    data class Region(
        val text: String,
        val bounds: Rect,
        val lineCount: Int,
        val orientation: TextOrientation,
        val alignment: TextAlignment,
        /** The displayed box this region replaces: the paired box for a
         *  RETRANSLATE (changed text, or blank-translation retry), null for a
         *  NEW sighting. Lets [TypewriterGate] scope its typewriter policy to
         *  CHANGED verdicts only — NEW text must never wait. */
        val replacesBox: TextBox? = null,
        /** The OCR group this region was reduced from — presenters needing
         *  line-level data read it. Null only in hand-built test fixtures. */
        val group: OcrManager.OcrGroup? = null,
        /** Slant carried from the group (see [OcrManager.OcrGroup.angleDeg]);
         *  oriented dims ride with it, 0 when upright. */
        val angleDeg: Float = 0f,
        val orientedWidth: Float = 0f,
        val orientedHeight: Float = 0f,
    )

    /**
     * One cycle's outcome. The mode renders `keptBoxes + toTranslate` (kept
     * boxes verbatim, toTranslate as a placeholder skeleton then translated in
     * place) and hides the overlay when both are empty. [removals] and the four
     * counts are informational (stats line / panel sync). Invariants:
     * `keptBoxes.size == unchanged`, `removals.size == missing`,
     * `toTranslate.size == changed + added`, and `repositioned <= unchanged`.
     */
    data class Verdicts(
        /** KEEP boxes. Verbatim when static; carrying the group's fresh bounds
         *  when the region drifted (see [repositioned]) — the translation is
         *  never touched either way. */
        val keptBoxes: List<TextBox>,
        /** Regions to translate this cycle — RETRANSLATE (changed/retry) and
         *  NEW alike. Translated and rendered now. Changed entries precede
         *  added ones. */
        val toTranslate: List<Region>,
        /** Boxes removed this cycle (paired with no group). */
        val removals: List<TextBox>,
        val unchanged: Int,
        val changed: Int,
        val missing: Int,
        val added: Int,
        /** How many of [keptBoxes] were re-emitted onto their group's fresh
         *  bounds because the region drifted beyond [REPOSITION_HYSTERESIS_PX].
         *  A subset of [unchanged] (not a separate verdict); informational. */
        val repositioned: Int,
        /** How many RETRANSLATEs were reading UPGRADES: the fresh read was
         *  within the identity fuzz of the displayed text (so it would have
         *  been a KEEP) but [ReadingArbiter] judged it a better reading of
         *  the same content. A subset of [changed]; informational. */
        val upgraded: Int = 0,
        /** Same-text confidence jitter observed this cycle, null when no
         *  identical re-read carried known scores. The calibration feed
         *  for [ReadingArbiter.CONF_MARGIN]: identical re-reads are pure
         *  same-text jitter samples, and the extreme deltas WITH the level
         *  they occurred at chart the jitter band as a function of
         *  confidence — the empirical curve the margin's size (and shape:
         *  absolute vs level-dependent) must sit above. Deltas are
         *  measured against the PRE-ratchet stored min, so after the
         *  stored score converges to the running max the lows chart the
         *  band's downward width and the highs mark ratchet moments. */
        val sameTextJitter: SameTextJitter? = null,
    )

    /** One cycle's same-text jitter extremes: [count] identical re-reads
     *  compared, the most negative and most positive (freshMin −
     *  storedMin) deltas, each with the stored level it was observed at. */
    data class SameTextJitter(
        val count: Int,
        val loDelta: Float,
        val loAtMin: Float,
        val hiDelta: Float,
        val hiAtMin: Float,
    )

    /**
     * Reconcile [groups] (fresh OCR) against [boxes] (currently displayed).
     * [groups] bounds and [boxes] bounds must share one coordinate space (the
     * mode keeps both in OCR-crop space — placeholders are built from group
     * bounds). See the class doc for the verdict semantics. Holds no state: the
     * whole outcome is a function of these arguments. The evidence contract is
     * FULL-FRAME: OCR must have seen the whole crop, because an unpaired box is
     * removed — absence of evidence is treated as evidence of absence, which is
     * only sound when the read covered everything.
     */
    fun reconcile(
        groups: List<OcrManager.OcrGroup>,
        boxes: List<TextBox>,
        sourceLang: String? = null,
        debugSink: ((String) -> Unit)? = null,
    ): Verdicts {
        val groupClaimed = BooleanArray(groups.size)
        val boxGroup = arrayOfNulls<Int>(boxes.size)

        // Greedy pairing: each box claims its best-overlapping, still-unclaimed
        // group. Pair by GEOMETRY only (wouldGroup) — text is judged afterward
        // so a same-region re-OCR whose text changed still pairs (→ RETRANSLATE)
        // instead of falling through to REMOVE + NEW.
        for ((bi, box) in boxes.withIndex()) {
            var bestG = -1
            var bestOverlap = -1L
            var bestDist = Long.MAX_VALUE
            for ((gi, g) in groups.withIndex()) {
                if (groupClaimed[gi]) continue
                if (!pairs(box.bounds, g.bounds, box.orientation, box.lineCount, g.lines.size)) continue
                val ov = overlapArea(box.bounds, g.bounds)
                val dist = centerDist2(box.bounds, g.bounds)
                if (ov > bestOverlap || (ov == bestOverlap && dist < bestDist)) {
                    bestOverlap = ov; bestDist = dist; bestG = gi
                }
            }
            if (bestG >= 0) {
                boxGroup[bi] = bestG
                groupClaimed[bestG] = true
            }
        }

        val kept = ArrayList<TextBox>()
        val toTranslate = ArrayList<Region>()
        val removals = ArrayList<TextBox>()
        var uCount = 0; var cCount = 0; var mCount = 0; var nCount = 0; var rCount = 0
        var upCount = 0
        var jitCount = 0
        var jitLo = 0f; var jitLoAt = 0f; var jitHi = 0f; var jitHiAt = 0f

        fun regionOf(g: OcrManager.OcrGroup, replaces: TextBox? = null) = Region(
            text = g.text,
            bounds = g.bounds,
            lineCount = g.lines.size.coerceAtLeast(1),
            orientation = g.orientation,
            alignment = g.alignment,
            replacesBox = replaces,
            group = g,
            angleDeg = g.angleDeg,
            orientedWidth = g.orientedWidth,
            orientedHeight = g.orientedHeight,
        )

        // Displayed boxes → KEEP / RETRANSLATE / REMOVE.
        for ((bi, box) in boxes.withIndex()) {
            val gi = boxGroup[bi]
            if (gi == null) {
                // REMOVE — the region is gone. One empty read suffices.
                removals.add(box); mCount++
            } else {
                val g = groups[gi]
                if (OverlayToolkit.isSignificantChange(g.text, box.sourceText)) {
                    // Text differs → retranslate the new text; old box dropped.
                    toTranslate.add(regionOf(g, replaces = box)); cCount++
                } else if (box.translatedText.isEmpty()) {
                    // Same text but the prior translation is blank (failed) —
                    // retry it. Bucketed with the changed retranslations.
                    toTranslate.add(regionOf(g, replaces = box)); cCount++
                } else {
                    // Same text within the identity fuzz. An IDENTICAL
                    // re-read is evidence for the displayed reading —
                    // ratchet the stored score (in place, identity kept)
                    // so it always reflects the best read observed, not
                    // the accident of the minting read. Without this, a
                    // low-confidence clean birth stays permanently
                    // beatable by a medium-confidence garble no matter
                    // how many high-confidence identical re-reads follow
                    // (adversarial-review finding). This is the class's
                    // ONE impurity: a monotone metadata refresh on input
                    // boxes that never alters any verdict in this call.
                    if (g.text == box.sourceText) {
                        val (fMin, fMean) = ReadingArbiter.scoreOf(g)
                        // Jitter sample BEFORE the ratchet: the delta is
                        // measured against what the margin would have seen.
                        if (fMin >= 0f && box.sourceConfMin >= 0f) {
                            val delta = fMin - box.sourceConfMin
                            if (jitCount == 0 || delta < jitLo) {
                                jitLo = delta; jitLoAt = box.sourceConfMin
                            }
                            if (jitCount == 0 || delta > jitHi) {
                                jitHi = delta; jitHiAt = box.sourceConfMin
                            }
                            jitCount++
                        }
                        box.ratchetSourceConf(fMin, fMean)
                    }
                    // If the raw strings differ instead, the pair is a
                    // QUALITY contest the fuzz alone cannot settle — one
                    // swapped glyph is below its tolerance yet materially
                    // wrongs the translation, and "first read wins
                    // forever" was a positional accident, not a policy.
                    // ReadingArbiter ranks the two readings; a winning
                    // challenger retranslates as an UPGRADE (the gate
                    // passes fuzz-same retries through, so upgrades never
                    // wait in a hold). TIE keeps today's behavior.
                    val upgrade = g.text != box.sourceText && run {
                        val (chMin, chMean) = ReadingArbiter.scoreOf(g)
                        val pref = ReadingArbiter.prefer(
                            ReadingArbiter.Reading(
                                box.sourceText, box.sourceConfMin, box.sourceConfMean,
                            ),
                            ReadingArbiter.Reading(g.text, chMin, chMean),
                            sourceLang,
                        )
                        debugSink?.invoke(
                            "arb inc=«${ReadingArbiter.snip(box.sourceText)}»" +
                                "(${box.sourceConfMin}/${box.sourceConfMean}) " +
                                "ch=«${ReadingArbiter.snip(g.text)}»($chMin/$chMean) → $pref"
                        )
                        pref == ReadingArbiter.Preference.CHALLENGER
                    }
                    if (upgrade) {
                        toTranslate.add(regionOf(g, replaces = box)); cCount++; upCount++
                    } else if (boundsDrifted(box.bounds, g.bounds) || slantDrifted(box, g)) {
                        // Region DRIFTED beyond OCR jitter (a scroll/pan), or a
                        // slanted box's angle drifted past its own hysteresis
                        // (a rotation bounds can miss on short lines): carry
                        // the box's existing translation onto the group's
                        // fresh bounds so the overlay tracks the moving text
                        // — no re-translate. Below both hystereses it passes
                        // through verbatim so static text does not shiver.
                        // Either way it counts as unchanged; drift is tallied
                        // in [Verdicts.repositioned].
                        // The slant moves with the bounds — it belongs to the
                        // fresh read's geometry, not the stale box's.
                        kept.add(box.copy(
                            bounds = g.bounds,
                            angleDeg = g.angleDeg,
                            orientedWidth = g.orientedWidth,
                            orientedHeight = g.orientedHeight,
                        )); rCount++; uCount++
                    } else {
                        kept.add(box); uCount++
                    }
                }
            }
        }

        // Unclaimed groups → NEW → translate immediately.
        for ((gi, g) in groups.withIndex()) {
            if (groupClaimed[gi]) continue
            toTranslate.add(regionOf(g)); nCount++
        }

        return Verdicts(
            keptBoxes = kept,
            toTranslate = toTranslate,
            removals = removals,
            unchanged = uCount,
            changed = cCount,
            missing = mCount,
            added = nCount,
            repositioned = rCount,
            upgraded = upCount,
            sameTextJitter = if (jitCount == 0) null else
                SameTextJitter(jitCount, jitLo, jitLoAt, jitHi, jitHiAt),
        )
    }

    // ── Geometry helpers ─────────────────────────────────────────────────

    /** True when any edge of [a] and [b] differs by more than
     *  [REPOSITION_HYSTERESIS_PX] — i.e. the paired region moved far enough to
     *  be real drift rather than per-frame OCR jitter. */
    private fun boundsDrifted(a: Rect, b: Rect): Boolean =
        abs(a.left - b.left) > REPOSITION_HYSTERESIS_PX ||
            abs(a.top - b.top) > REPOSITION_HYSTERESIS_PX ||
            abs(a.right - b.right) > REPOSITION_HYSTERESIS_PX ||
            abs(a.bottom - b.bottom) > REPOSITION_HYSTERESIS_PX

    /** Do these two rects represent the same on-screen region? Same predicate
     *  [Classification] uses for cross-frame overlay matching. */
    private fun pairs(a: Rect, b: Rect, orientation: TextOrientation, aLn: Int, bLn: Int): Boolean =
        LayoutAnalyzer.wouldGroup(
            a, b, orientation,
            mode = LayoutAnalyzer.GroupingMode.CROSS_FRAME_SAME_REGION,
            aLineCount = aLn.coerceAtLeast(1),
            bLineCount = bLn.coerceAtLeast(1),
        )

    private fun overlapArea(a: Rect, b: Rect): Long {
        val ix = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val iy = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (ix <= 0 || iy <= 0) return 0L
        return ix.toLong() * iy.toLong()
    }

    private fun centerDist2(a: Rect, b: Rect): Long {
        val dx = (a.centerX() - b.centerX()).toLong()
        val dy = (a.centerY() - b.centerY()).toLong()
        return dx * dx + dy * dy
    }
}
