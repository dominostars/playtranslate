package com.playtranslate

import com.playtranslate.ui.TextBox

/**
 * The GSM-style typewriter hold over [ScanlineReconciler] CHANGED verdicts —
 * the one piece of cross-cycle state layered on top of Level 0, scoped so
 * narrowly it cannot reintroduce the rejected two-read-gate failure modes:
 *
 *  - **NEW text never waits.** Only entries carrying
 *    [ScanlineReconciler.Region.replacesBox] (a paired box being retranslated)
 *    are considered; first sightings pass straight through.
 *  - **Only the typewriter signature holds.** A paired region holds only when
 *    its new text is a growing-prefix EXTENSION of what the box shows
 *    ([OverlayToolkit.isEvolvingText]). A dialogue advance (different or
 *    shorter text) translates immediately; a blank-translation retry passes
 *    through.
 *  - **It cannot starve.** Release fires on [STABLE_READS] consecutive
 *    agreeing reads or when the cap expires — and the cap's clock is anchored
 *    at the CAPTURE time of the read that opened the hold, evaluated against
 *    wall-clock now. That anchoring makes the hold self-disabling on
 *    slow-OCR devices: when a single OCR pass takes ≥ [HOLD_MAX_MS], the cap
 *    has already expired by the time the opening reconcile runs, so the hold
 *    opens and releases in the same evaluation — pure translate-on-sight,
 *    zero added latency. On fast devices several reads fit inside the cap and
 *    the hold absorbs partial-line churn as designed. The clock never
 *    re-anchors as pending text grows (a marquee cannot defer forever).
 *
 * While a region is held, its box (the old, still-displayed one) is returned
 * in [Outcome.heldBoxes] and rendered verbatim — the display stays stable
 * through the reveal instead of churning per-partial translations.
 *
 * A hold must be re-affirmed by a CHANGED verdict every cycle it stays open:
 * any other fate for its box — KEEP (the "change" evaporated: OCR garble),
 * REMOVE (region gone), or absence — clears it. [Outcome.nextDeadlineMs]
 * exposes the earliest open cap so the mode can schedule a wake instead of
 * parking on the delivery gate — a typewriter that finishes into a static
 * screen must still get its releasing read.
 *
 * Pure Kotlin against (verdicts, captureAtMs, nowMs) — no clocks, no Android
 * services — so it unit-tests on the JVM like the reconciler. Holds are keyed
 * by box IDENTITY: a held box is re-shown verbatim (never repositioned —
 * reposition only touches KEEP verdicts), so the same instance returns in the
 * next cycle's displayed list.
 */
class StabilityHold {

    private class Hold(
        var pending: String,
        var stableReads: Int,
        /** Capture time (uptime ms) of the read that OPENED the hold. Never
         *  re-anchored — the cap bounds total hold time for the region. */
        val openCaptureMs: Long,
    )

    private val holds = java.util.IdentityHashMap<TextBox, Hold>()

    /** [filter]'s outcome: the entries to actually translate this cycle, the
     *  boxes whose retranslation is deferred (render them verbatim alongside
     *  the kept boxes), and the earliest open hold's cap deadline (uptime ms;
     *  null when no holds are open). */
    data class Outcome(
        val toTranslate: List<ScanlineReconciler.Region>,
        val heldBoxes: List<TextBox>,
        val nextDeadlineMs: Long?,
    )

    /**
     * Partition [verdicts]' toTranslate into translate-now vs held. [captureAtMs]
     * is when this cycle's frame was captured (uptime); [nowMs] is the
     * evaluation time (uptime) the cap is checked against.
     */
    fun filter(
        verdicts: ScanlineReconciler.Verdicts,
        captureAtMs: Long,
        nowMs: Long,
    ): Outcome {
        if (!STABILITY_HOLD) {
            holds.clear()
            return Outcome(verdicts.toTranslate, emptyList(), null)
        }
        val translate = ArrayList<ScanlineReconciler.Region>(verdicts.toTranslate.size)
        val heldBoxes = ArrayList<TextBox>()
        val affirmed = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<TextBox, Boolean>())

        for (entry in verdicts.toTranslate) {
            val box = entry.replacesBox
            if (box == null) {
                translate.add(entry) // NEW — never held
                continue
            }
            if (!OverlayToolkit.isSignificantChange(entry.text, box.sourceText)) {
                translate.add(entry) // blank-translation retry — text is stable
                continue
            }
            val hold = holds[box]
            if (hold == null) {
                if (!OverlayToolkit.isEvolvingText(box.sourceText, entry.text)) {
                    translate.add(entry) // real content change — Level 0 first response
                    continue
                }
                if (nowMs - captureAtMs >= HOLD_MAX_MS) {
                    // Slow-OCR self-disable: the cap expired while this very
                    // read was in the pipeline. Open-and-release.
                    translate.add(entry)
                    continue
                }
                holds[box] = Hold(pending = entry.text, stableReads = 1, openCaptureMs = captureAtMs)
                affirmed.add(box)
                heldBoxes.add(box)
                continue
            }
            // Existing hold, re-affirmed by another CHANGED read of this box.
            val capExpired = nowMs - hold.openCaptureMs >= HOLD_MAX_MS
            when {
                !OverlayToolkit.isSignificantChange(entry.text, hold.pending) -> {
                    hold.stableReads++
                    if (hold.stableReads >= STABLE_READS || capExpired) {
                        holds.remove(box)
                        translate.add(entry) // settled — translate the fresh read
                    } else {
                        affirmed.add(box); heldBoxes.add(box)
                    }
                }
                OverlayToolkit.isEvolvingText(hold.pending, entry.text) -> {
                    hold.pending = entry.text
                    hold.stableReads = 1
                    if (capExpired) {
                        holds.remove(box)
                        translate.add(entry) // still typing at the cap — flush newest
                    } else {
                        affirmed.add(box); heldBoxes.add(box)
                    }
                }
                else -> {
                    holds.remove(box)
                    translate.add(entry) // real change mid-reveal (advance) — now
                }
            }
        }

        // Any hold NOT re-affirmed this cycle is dead: its box was kept
        // (change evaporated), removed, or vanished from the displayed set.
        holds.keys.retainAll(affirmed)

        val deadline = holds.values.minOfOrNull { it.openCaptureMs + HOLD_MAX_MS }
        return Outcome(translate, heldBoxes, deadline)
    }

    fun clear() = holds.clear()

    companion object {
        /** Phase-4 A/B lever: false = pure Level 0 (translate on sight). */
        const val STABILITY_HOLD = true

        /** Consecutive agreeing reads that release a hold (GSM parity). */
        const val STABLE_READS = 2

        /** Wall-clock cap on any hold, anchored at the opening read's capture
         *  time — see the class doc for why that anchoring self-disables the
         *  hold on slow-OCR devices. */
        const val HOLD_MAX_MS = 2_000L
    }
}
