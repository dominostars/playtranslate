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
 * [SPLATTER_THRESHOLD] and [PINHOLE_CHANGE_PCT] as well. See the
 * "Detection thresholds" section below.
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
 * If you change the default [MASK_ALPHA], `checkPinholes` will produce
 * wrong predictions and the thresholds below will stop meaning what they
 * currently mean. The fix would be either (a) re-derive the blend
 * prediction with the new alpha (`predicted = lerp(ref, overlay,
 * MASK_ALPHA/255f)`) or (b) keep [MASK_ALPHA] at 0x80 and tune other
 * aspects of the pinhole appearance.
 *
 * ### Backend-aware compensation (live + MediaProjection)
 *
 * The MediaProjection live cell is the one configuration where the overlay
 * window does NOT composite at α=1.0: to bypass the QTI BSP visual clamp
 * and the AOSP untrusted-touch rule, the window is rendered at
 * approximately the system obscuring cap (default ≈ 0.8). To keep the
 * *effective* pinhole α at exactly 0.5 in that case,
 * `OverlayUiController` constructs the live overlay's `TranslationOverlayView`
 * with a compensated `maskAlpha` (≈ `0x60` at α = 0.8) that satisfies
 * `(1 − maskAlpha/255) × windowAlpha = 0.5`. The detection thresholds below
 * stay valid because the sampled pinhole positions still see a 50/50 blend
 * of game + overlay; the constant here is the default that applies in the
 * other three matrix cells (accessibility live, MP one-shot, accessibility
 * one-shot — all at α=1.0).
 *
 * ## Detection thresholds
 *
 *  - [SPLATTER_THRESHOLD] — per-channel delta above which a pinhole is
 *    counted as "changed". Calibrated against the 50/50 blend assumption
 *    above: an honest match sees max channel delta ~20–30 due to JPEG/
 *    texture noise, so 60 leaves comfortable headroom. Increase if
 *    stable-text cycles over-flag as REMOVE; decrease if real changes
 *    are being missed.
 *  - [PINHOLE_CHANGE_PCT] — fraction of pinholes in a box's region that
 *    must exceed [SPLATTER_THRESHOLD] for the box to be removed and
 *    re-OCR'd on the next cycle. Set to the value that used to be the
 *    soft DIRTY threshold (0.03) — with the dirty companion overlay
 *    retired (see [docs/dirty-overlay-archived-design.md]), there's no
 *    smooth recovery state, so any pinhole change above this fraction
 *    means the box gets removed immediately. The user-visible delta:
 *    text transitions show a brief no-overlay gap (~1 OCR cycle) where
 *    they previously stayed visible until OCR confirmed replacement.
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
     * Default alpha byte of the mask at pinhole positions (out of 255).
     * 0x80 == 128 → 50% blend at window α=1.0, which is what
     * [PinholeOverlayMode.checkPinholes] assumes in its
     * `predicted = (ref + overlay) / 2` math.
     *
     * Applied unmodified for accessibility live mode and both one-shot
     * cells. On the MediaProjection backend in live (pinhole) mode,
     * `OverlayUiController` passes a compensated value to
     * [com.playtranslate.ui.TranslationOverlayView] so the effective
     * pinhole α is still 0.5 once the reduced window α multiplies in.
     */
    const val MASK_ALPHA = 0x80

    /** Grid spacing in view pixels between adjacent pinhole positions. */
    const val PINHOLE_SPACING = 3

    /** Per-channel delta threshold for classifying a pinhole as "changed". */
    const val SPLATTER_THRESHOLD = 60

    /** Fraction of pinholes in a box that must change to mark it REMOVE.
     *  Sits between the old soft-DIRTY threshold (0.03) and the old
     *  confident-REMOVE threshold (0.10), leaning toward the sensitive
     *  end so single-character / counter-style edits get caught. The
     *  dirty companion window that used to buffer 0.03–0.10 changes is
     *  gone (see [docs/dirty-overlay-archived-design.md]); with no
     *  smooth recovery state, any pinhole change above this fraction
     *  removes the box immediately and the next OCR cycle re-detects.
     *  Trade-off in each direction:
     *    - Too low (≈0.03): transient noise (dialog-advance cursor blinks,
     *      animated portraits, particle FX under a stable text box) trips
     *      removal → visible flicker on stable translations.
     *    - Too high (≈0.10): small but real text edits (single-character
     *      swaps, score/timer increments under the box) don't trigger
     *      → stale translations linger past their underlying text.
     *  Tune empirically per device / game family. */
    const val PINHOLE_CHANGE_PCT = 0.05f
}
