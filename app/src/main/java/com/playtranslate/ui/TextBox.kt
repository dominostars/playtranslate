package com.playtranslate.ui

import android.graphics.Color
import android.graphics.Rect
import com.playtranslate.language.TextAlignment
import com.playtranslate.language.TextOrientation

/**
 * A single translated (or furigana) text box positioned over the game screen.
 *
 * Extracted from `TranslationOverlayView` to a top-level type so the live-mode
 * classes, classification, and the overlay window registry can reference it
 * without depending on a particular overlay View implementation.
 */
data class TextBox(
    val translatedText: String,
    /** Bounding box in original bitmap pixel coordinates. */
    val bounds: Rect,
    /** Average color of the game content behind this box (ARGB). */
    val bgColor: Int = Color.argb(200, 0, 0, 0),
    /** Text color — estimated from game text or chosen for contrast. */
    val textColor: Int = Color.WHITE,
    /** Number of OCR lines in this group (for skeleton placeholders). */
    val lineCount: Int = 1,
    /** True for furigana readings (smaller text, pill background). */
    val isFurigana: Boolean = false,
    /** Marked when pinhole detection finds a minor change under this overlay. */
    val dirty: Boolean = false,
    /** Original OCR source text this overlay translates. Used for content-based matching. */
    val sourceText: String = "",
    /** Display name of the translation backend that produced [translatedText]
     *  (cache hits carry the cached producer). Null for placeholders, furigana,
     *  and the same-language OCR bypass. Feeds the panel's "Translated by …"
     *  attribution and the history rows' backend column. */
    val backendDisplayName: String? = null,
    /** Text orientation — vertical boxes render with 90° CW rotated text. */
    val orientation: TextOrientation = TextOrientation.HORIZONTAL,
    /** Block alignment for horizontal boxes — drives skeleton bar placement
     *  and translated-text gravity. Ignored for vertical boxes. */
    val alignment: TextAlignment = TextAlignment.LEFT,
    /** Source slant in degrees, clockwise-positive (`View.rotation` semantics);
     *  0 = axis-aligned. Non-zero routes the box to [RenderMode.SOURCE_ANGLE]:
     *  the chip lays out at the oriented dims and rotates about the [bounds]
     *  center — [bounds] is exactly the slanted rect's AABB, so the rotated
     *  chip lands on the source footprint. */
    val angleDeg: Float = 0f,
    /** True (unrotated) dims of the slanted rect in the same space as [bounds];
     *  0 when [angleDeg] == 0. Ride with the angle — not re-derivable from
     *  bounds+angle (singular at 45°). */
    val orientedWidth: Float = 0f,
    val orientedHeight: Float = 0f,
    /** Consecutive upright re-reads survived by a held [angleDeg] (the
     *  reconciler's slant hysteresis). Meaningful only while [angleDeg] != 0;
     *  reset whenever a fresh measured angle arrives. Bounds the hold: the
     *  acceptance flap the hold exists for alternates angled/upright and
     *  never builds a streak, while a genuine upright transition releases
     *  after a few reads instead of sticking for the box's lifetime. */
    val slantUprightStreak: Int = 0,
    /** Minimum on-screen width (px) for a legible horizontal line of
     *  [translatedText] — the longest whitespace token measured at the
     *  legibility floor. Drives the vertical-box render routing in
     *  [OverlayLayout.resolveScreenRects] (HORIZONTAL_IN_PLACE when the box is
     *  already this wide; the grow target otherwise). Computed at layout time
     *  from the display density (see [TranslationOverlayView.rebuildChildren]);
     *  the value carried on stored/placeholder boxes is 0 — it is populated only
     *  on the transient copies handed to the resolver, and injected directly in
     *  unit tests. */
    val minWidthPx: Int = 0,
    /** OCR confidence of the BEST single read observed for [sourceText]:
     *  min over that read's lines, −1 when unknown. Stamped at placeholder
     *  build, then RATCHETED upward by every identical re-read
     *  ([ratchetSourceConf]) — evidence accumulates, it is never the
     *  accident of whichever read minted the text (a low-confidence birth
     *  followed by high-confidence identical re-reads must not stay
     *  beatable by a medium-confidence garble; adversarial-review
     *  finding). Read by ReadingArbiter when a fuzz-same read differs. A
     *  prefix-substituted dispatch carries its full read's scores —
     *  accepted slop. Deliberately `var`: the ratchet mutates in place so
     *  box IDENTITY survives (presenter maps, cached lists, the
     *  view's short-circuit all key on it); safe because no structural-
     *  keyed container of TextBox exists (IdentityHashMaps only) and the
     *  fields never affect rendering. */
    var sourceConfMin: Float = -1f,
    /** Mean over the same best read's lines, −1 when unknown — the min's
     *  tie-break (one suspect line beats two). */
    var sourceConfMean: Float = -1f,
) {

    /** Refresh the stored score with a fresh identical-text read: replace
     *  the pair iff the fresh read is (min, then mean) lexicographically
     *  better — the stored score stays "the best single read observed",
     *  never a chimera of two reads' components. Monotone and idempotent,
     *  so re-application (the dual-hypothesis double reconcile) is
     *  harmless; an unknown fresh read (−1) never erases a known score. */
    fun ratchetSourceConf(freshMin: Float, freshMean: Float) {
        if (freshMin > sourceConfMin ||
            (freshMin == sourceConfMin && freshMean > sourceConfMean)
        ) {
            sourceConfMin = freshMin
            sourceConfMean = freshMean
        }
    }
}
