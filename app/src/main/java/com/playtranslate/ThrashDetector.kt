package com.playtranslate

import android.graphics.Rect

/**
 * Language-independent misroute backstop for [CleanStreamOverlayMode]: the
 * flap-signature detector. If the mode is ever running against a stream that
 * is NOT actually clean (a wrong [com.playtranslate.capture.StreamKindProbe]
 * verdict), the failure always manifests as *oscillation* — a region's box
 * being re-placed with text the region already showed moments ago, cycle
 * after cycle, with no stable KEEP in between. The echo tripwire catches the
 * subset where OCR can read our rendered translations back; this detector
 * catches every mechanism, including the pairs where it can't (a
 * source-language recognizer that reads nothing at our target-script boxes
 * produces a REMOVE → rediscover-same-text → REMOVE loop that never echoes).
 *
 * The discriminator between flap and legitimate rapid content is twofold:
 *  - **Only REVISITS count.** A placement whose text matches something the
 *    region showed recently ([OverlayToolkit.isSignificantChange] fuzz) is a
 *    revisit; forward progress (dialogue A → B → C) never accumulates.
 *  - **Stability forgives.** A KEEP verdict at a region clears its revisit
 *    run ([recordStability]) — a player flipping between two menu tabs
 *    interleaves stable cycles between flips, while a true flap replaces the
 *    region every cycle and never earns one.
 * [CleanStreamOverlayMode] additionally skips recording placements during an
 * input burst, so touch-driven churn never counts. (Gamepad input is
 * invisible on the MediaProjection backend; the stability rule is the guard
 * there.)
 *
 * Regions are keyed by bucketed center ([BUCKET_PX]) — OCR bounds jitter,
 * so exact rects would never rendezvous. Pure Kotlin over [Rect]; JVM-tested.
 */
class ThrashDetector {

    private data class Key(val bx: Int, val by: Int)

    private class Region {
        val recentTexts = ArrayDeque<String>()
        val revisitsMs = ArrayDeque<Long>()
    }

    private val regions = HashMap<Key, Region>()

    private fun keyOf(bounds: Rect) =
        Key(bounds.centerX() / BUCKET_PX, bounds.centerY() / BUCKET_PX)

    /**
     * A region is being (re)translated with [newText] at [nowMs]. Returns
     * true when this placement crosses the thrash bar — [REVISITS_TO_FIRE]
     * revisits at one region inside [WINDOW_MS] with no intervening
     * stability — and the caller should demote the stream kind.
     */
    fun recordPlacement(bounds: Rect, newText: String, nowMs: Long): Boolean {
        val region = regions.getOrPut(keyOf(bounds)) { Region() }
        val revisit = region.recentTexts.any {
            !OverlayToolkit.isSignificantChange(it, newText)
        }
        region.recentTexts.addLast(newText)
        while (region.recentTexts.size > TEXT_MEMORY) region.recentTexts.removeFirst()
        if (!revisit) return false
        region.revisitsMs.addLast(nowMs)
        while (region.revisitsMs.isNotEmpty() &&
            nowMs - region.revisitsMs.first() > WINDOW_MS
        ) {
            region.revisitsMs.removeFirst()
        }
        return region.revisitsMs.size >= REVISITS_TO_FIRE
    }

    /** The region under [bounds] was KEEP this cycle — genuine stability;
     *  its revisit run is forgiven. */
    fun recordStability(bounds: Rect) {
        regions[keyOf(bounds)]?.revisitsMs?.clear()
    }

    fun clear() = regions.clear()

    companion object {
        /** Region key quantum — generous enough that per-cycle OCR bounds
         *  jitter keeps hitting the same bucket. */
        const val BUCKET_PX = 96

        /** Texts remembered per region for the revisit test. */
        const val TEXT_MEMORY = 4

        /** Revisit-counting window. */
        const val WINDOW_MS = 60_000L

        /** Revisits (not placements) at one region that mark thrash. */
        const val REVISITS_TO_FIRE = 3
    }
}
