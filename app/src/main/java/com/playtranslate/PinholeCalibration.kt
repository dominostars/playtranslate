package com.playtranslate

/**
 * Pinhole pattern + detection calibration constants.
 *
 * The pinhole overlay is a tightly coupled system: the mask is drawn at a
 * specific alpha and spacing, and the detector samples the same grid and
 * compares raw on-screen pixels against a blend prediction derived from
 * exactly those mask parameters. Tuning any one value silently invalidates
 * the others, so they all live here together with the derivation that ties
 * them to each other.
 *
 * If you're reading this because you want to change how visible the
 * pinhole texture is on screen, you almost certainly need to re-tune
 * [SPLATTER_THRESHOLD], [PINHOLE_DIRTY_PCT], and [PINHOLE_CHANGE_PCT] as
 * well. See the "Detection thresholds" section below.
 *
 * ## Mask parameters
 *
 *  - [MASK_ALPHA] — alpha byte written at each pinhole position in the
 *    full-view mask bitmap. Currently `0x80` (128/255 ≈ 50%).
 *  - [PINHOLE_SPACING] — grid spacing in view pixels between adjacent
 *    pinhole positions. Currently 3.
 *
 * [com.playtranslate.ui.TranslationOverlayView.createPinholeMask] uses
 * both of these: the bitmap is filled with transparent pixels everywhere
 * except the grid positions, which get ARGB `MASK_ALPHA << 24` (alpha
 * only, RGB=0). The mask is composited with DST_OUT in `dispatchDraw`, so
 * pixels under a pinhole position are multiplied by `1 - MASK_ALPHA/255`
 * ≈ 0.5 of the overlay's original opacity, letting the game underneath
 * show through at a matching 0.5 fraction.
 *
 * ## Blend math encoded in checkPinholes
 *
 * At a pinhole position the on-screen pixel is a blend of the game
 * underneath (captured in `cleanRef`) and the rendered overlay:
 *
 *     raw = (1 - MASK_ALPHA/255) * game + (MASK_ALPHA/255) * overlay
 *
 * For [MASK_ALPHA] = 0x80 this simplifies to the 50/50 blend
 * `(game + overlay) / 2`, which is exactly what
 * [PinholeOverlayMode.checkPinholes] encodes as:
 *
 *     predicted = (cleanRef + overlay) / 2
 *     delta     = |raw - predicted|  (per channel)
 *
 * If you change [MASK_ALPHA], `checkPinholes` will produce wrong
 * predictions and the thresholds below will stop meaning what they
 * currently mean. The fix would be either (a) re-derive the blend
 * prediction with the new alpha (`predicted = lerp(ref, overlay,
 * MASK_ALPHA/255f)`) or (b) keep [MASK_ALPHA] at 0x80 and tune other
 * aspects of the pinhole appearance.
 *
 * ## Detection thresholds
 *
 *  - [SPLATTER_THRESHOLD] — per-channel delta above which a pinhole is
 *    counted as "changed". Calibrated against the 50/50 blend assumption
 *    above: an honest match sees max channel delta ~20–30 due to JPEG/
 *    texture noise, so 60 leaves comfortable headroom. Increase if
 *    stable-text cycles over-flag as DIRTY; decrease if real changes are
 *    being missed.
 *  - [PINHOLE_DIRTY_PCT] — fraction of pinholes in a box's region that
 *    must exceed [SPLATTER_THRESHOLD] for the box to be classified DIRTY
 *    (minor change: text edit, cursor advance, portrait blink).
 *  - [PINHOLE_CHANGE_PCT] — fraction required to classify REMOVE (major
 *    change: scene swap, menu transition, full text rebuild).
 *
 * DIRTY < CHANGE by construction, so REMOVE implies DIRTY.
 *
 * ## Scale assumption
 *
 * Everything here assumes identity scale (view dims == screenshot bitmap
 * dims). Under downsampling the sparse per-view-pixel pinhole pattern
 * smears across multiple bitmap pixels, the averaged alpha stops being
 * the per-pixel alpha, and the 50/50 blend math collapses. See
 * [FrameCoordinates] KDoc for the full explanation and
 * [PinholeOverlayMode.runCycle] for the fail-closed guard that prevents
 * `checkPinholes` from being called at non-identity scale.
 */
object PinholeCalibration {

    /**
     * Alpha byte of the mask at pinhole positions (out of 255).
     * 0x80 == 128 → 50% blend, which is what [PinholeOverlayMode.checkPinholes]
     * assumes in its `predicted = (ref + overlay) / 2` math.
     */
    const val MASK_ALPHA = 0x80

    /**
     * ARGB pixel color written at each pinhole position in the mask
     * bitmap: alpha = [MASK_ALPHA], RGB = 0. Pre-computed so
     * [com.playtranslate.ui.TranslationOverlayView.createPinholeMask] can
     * just index it into an IntArray instead of bit-shifting per pixel.
     */
    const val MASK_PIXEL: Int = MASK_ALPHA shl 24

    /** Grid spacing in view pixels between adjacent pinhole positions. */
    const val PINHOLE_SPACING = 3

    /** Per-channel delta threshold for classifying a pinhole as "changed". */
    const val SPLATTER_THRESHOLD = 60

    /** Fraction of pinholes in a box that must change to mark it DIRTY. */
    const val PINHOLE_DIRTY_PCT = 0.03f

    /** Fraction of pinholes in a box that must change to mark it REMOVE. */
    const val PINHOLE_CHANGE_PCT = 0.10f
}
