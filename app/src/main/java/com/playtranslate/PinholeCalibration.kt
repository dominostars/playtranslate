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
 * A fifth cell arrived with delivery-gated live capture (2026-07): an
 * accessibility-hosted window (α=1.0, this default mask) CAPTURED through
 * the MediaProjection mirror — `CaptureBackendResolver.liveCaptureSourceFor`
 * routes live TRANSLATION capture to the mirrored stream even under the
 * accessibility backend. The blend math is the accessibility cell's (the
 * alpha cell follows the overlay host's window type, not the capture
 * source); the capture path is the mirror. Overlay + mask presence in the
 * mirror is verified qualitatively on Thor; when validating this cell,
 * watch pinhole `pct` on stable text for a mirror-side blend bias (the
 * SAW-at-α≈0.8 mirror cell historically showed one).
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

    /** Per-channel RAW delta threshold for classifying a pinhole as
     *  "changed" — the shipped calibration (honest-match noise peaks at
     *  ~20–30; 60 leaves headroom). The brief A3 experiment that measured
     *  fit-residuals here instead (with a recalibrated 45) is reverted:
     *  normalization on the removal path trades stale overlays for dim
     *  smoothness, the wrong direction under the 2026-07-08 speed-first
     *  rule. */
    const val SPLATTER_THRESHOLD = 60

    // ── A2 change-gate constants (OutsideChangeGate + the runCycle gate) ──

    /** Outside-diff sample grid stride in bitmap px. ~150 sampled rows /
     *  ~40k samples at 1080p; text smaller than this pitch can slip between
     *  samples, which is what [GATE_RECONCILE_EVERY_SKIPS] backstops. */
    const val OUTSIDE_STRIDE_PX = 7

    /** Per-sample |brightness-normalized luma residual| that counts as
     *  changed. Mirror-capture noise measured ≤ ~6 levels on Thor; real
     *  glyph swaps measured 50+ (stream-sensor 0b: swap p50=52 vs ambient
     *  p90=14 — recorded data, order-of-magnitude corroboration only). 25
     *  sits far above noise while catching text at half strength. */
    const val OUTSIDE_LUMA_THRESHOLD = 25

    /** Samples over threshold before the outside gate fires. 2 tolerates a
     *  freak sample (dither, dead pixel); a real text change at stride 7
     *  hits several grid points. */
    const val OUTSIDE_MIN_CHANGED_SAMPLES = 2

    /** Outside-diff exclusion inflation around each rendered box rect, in
     *  px — keeps anti-aliased box edges out of the sampled set (mirrors
     *  fillOverlayRegions' aaBuffer). */
    const val GATE_EXCLUDE_INFLATE_PX = 3

    /** Run a full reconciliation cycle after this many consecutive gate
     *  skips — the safety net for sub-grid changes while confidence builds.
     *  A reconcile that finds work logs a "gate MISS" (the audit-A1
     *  false-negative metric); a persistently zero miss rate is the
     *  evidence for eventually deleting the net. */
    const val GATE_RECONCILE_EVERY_SKIPS = 25

    // ── A7 glyph-anchored probes (GlyphAnchors + checkPinholes) ──────────

    /** Anchor points per approximated text line (start, 1/3, 2/3, end). */
    const val GLYPH_ANCHORS_PER_LINE = 4

    /** Chebyshev radius (px) around an anchor within which a changed sample
     *  counts as a hit on that anchor. At PINHOLE_SPACING=3 this covers the
     *  1–2 nearest actual holes. */
    const val GLYPH_PROBE_RADIUS_PX = 4

    /** (Telemetry reference only — the anchor rule is DISARMED as of
     *  2026-07-08.) Distinct anchors with changed samples that WOULD mark a
     *  box suspect regardless of area percentage. Anchors are placed
     *  geometrically, not on detected ink, so on translucent boxes they can
     *  sit over background animation; the per-outcome glyphAnchors= count
     *  is being collected to size the idea before it is ever allowed to
     *  drive removals. */
    const val GLYPH_PROBE_MIN_ANCHORS = 2

    /** Inset from the rendered rect (which includes ~14px of box padding)
     *  before laying anchor rows, biasing anchors toward actual ink. */
    const val GLYPH_ANCHOR_INSET_PX = 8

    /** Dying-box fragment deferral (see [com.playtranslate.abutsAnyInflated]):
     *  a fresh OCR group within this many px of a box being PINHOLE-removed
     *  the same cycle is deferred one floor-paced look instead of placed —
     *  it may be only the visible tail of the text the removal uncovers.
     *  Sized to reach text that borders the uncovered region (tails start
     *  at the rendered rect's edge; wrapped lines sit ≤ ~15px below; the
     *  campfire trace's directly-adjacent menu item sat at 6px) without
     *  reaching across a full menu row (~72px pitch at 1080p). */
    const val FRAGMENT_DEFER_ABUT_PX = 32

    // ── Content-match relocation tombstones (classifyOcrResults) ─────────

    /** Positional slop for tombstone matching AND for the same-position
     *  test that decides whether a content match is a relocation at all:
     *  two rects are "the same region" when every edge differs by at most
     *  this many px. Deliberately TIGHT — it must sit above static re-read
     *  jitter (a few px of anti-aliasing noise on identical text;
     *  ScanlineReconciler's reposition hysteresis uses 5) but well below
     *  the smallest per-cycle displacement a genuinely moving text can
     *  show while still producing a readable distinct OCR group: the
     *  vacated region's old occupant was blacked out to OCR under the
     *  rendered rect plus ~14px of box padding, so text that has moved
     *  less than the padding cannot have been read whole at its old spot.
     *  A generous overlap test here would make slow scrollers hit their
     *  own previous-cycle tombstones and leave a trail of spawned boxes —
     *  the exact accumulation bug content match exists to prevent. */
    const val TOMBSTONE_MATCH_SLOP_PX = 12

    /** How many full looks (cycles that reached classification) a
     *  tombstone survives. The oscillation it kills has a period of
     *  exactly two looks — relocate away on look N, re-read the vacated
     *  rect on N+1 — so 1 suffices for the crisp case; 2 covers a
     *  one-look delay (step-9b deferral of the spawn, a brief occlusion).
     *  Longer would start converting slow revisit-movers (a drifting
     *  text returning to an old position) into spurious duplicates. */
    const val TOMBSTONE_LIFESPAN_LOOKS = 2

    /** Game-input burst window (audit A4): after a tap/gamepad press, cycles
     *  pace at the backend floor for this long instead of the user interval.
     *  Input is the strongest change predictor available; the burst buys
     *  fast settle-confirmation and pickup for whatever the input caused,
     *  while A3's per-region detection ensures only regions that actually
     *  changed are lifted — static HUD boxes survive. */
    const val INPUT_BURST_MS = 2_500L

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
