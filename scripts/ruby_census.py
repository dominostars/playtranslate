#!/usr/bin/env python3
"""Host-side census of RubyFilter's furigana rule over a grouping-run JSONL.
MEASUREMENT ONLY: this changes nothing in the app. It is the source of record
for the constants in app/src/main/java/com/playtranslate/ocr/core/RubyFilter.kt:
  SIZE_RATIO_MIN 1.5, PITCH_RATIO_MIN 1.4, PITCH_MIN_CHARS 2, REACH_EM 1.0,
  GAP_MIN_EM -0.5, OVERLAP_MIN 0.5, MAX_HAN_IN_READING 1.
The constants are nominal typesetting values with slack (ruby is half-size, so
both size ratios are nominally 2.0), not fitted numbers; the corpus is used to
look for surprises, not to set them. Calibrated on
ocr-grouping/runs/results-1786212247819.jsonl (57 ja seeds x 4 engines,
`flowgraph` column, 2022 regions: 131 demoted, all real ruby on inspection, 0
body lines) and one Thor capture on 2026-09-09.

PITCH CAVEAT: the app measures pitch from character POSITIONS (median advance
between consecutive char boxes, RubyFilter.charPitch), which the harness JSONL
does not carry, so this script uses reading extent over character count. On
reads the recognizer merged across a hole (two ruby runs read as one region)
the script is therefore MORE conservative than the app: it refuses some
readings the app demotes. Every other term is identical.

Input: a harness results JSONL (region records carry box, vert, text per
seed x engine x grouping variant). One grouping variant is read (default
`flowgraph`) so each (seed, engine) frame is counted once; the region SET is
the same across variants, only group membership differs.

Rule under test, per frame (case x engine):
  R is ruby iff
    reading:   R.text has >= 1 syllabic kana, at most MAX_HAN Han (one misread),
               and more kana than Han
    attached:  R's NEAREST line on its ruby side (below if that line is
               horizontal, left if vertical), overlapping R along the reading
               axis by >= OVERLAP * R's extent and starting within
               [GAP_MIN, REACH] of that line's em, contains Han
    half-size: two measurements, pitch(B)/pitch(R) >= PITCH (pitch = start-
               to-start character distance; here reading extent / chars, see
               the caveat; needs >= PITCH_CHARS on BOTH lines) and line height
               extent(B)/extent(R) >= K. On glyph-measuring engines (base has
               `cq`: ML Kit, Meiki) BOTH must pass; on PaddleOCR (no `cq`),
               whose measurements only err toward refusing, EITHER suffices;
               with no pitch (a one-character read or base) height decides.
Prints every demoted region (judge them by eye: a real reading of a kanji in
the base text, or a body line?), the cross-ratio histogram of every
reading+attached passer, and the passers inside --band so the cliffs can be
re-read.

Re-run after any corpus growth or engine change:
  python3 scripts/ruby_census.py ocr-grouping/runs/results-<runId>.jsonl
"""
import json, sys, collections, argparse

HAN = lambda c: 0x4E00 <= ord(c) <= 0x9FFF or 0x3400 <= ord(c) <= 0x4DBF or 0xF900 <= ord(c) <= 0xFAFF or ord(c) == 0x3005
# syllabic kana only, matching RubyFilter.isSyllabicKana (no prolonged mark / dots / iteration marks)
KANA = lambda c: 0x3041 <= ord(c) <= 0x3096 or 0x30A1 <= ord(c) <= 0x30FA or (0xFF66 <= ord(c) <= 0xFF9F and ord(c) != 0xFF70)

MAX_HAN = 1   # RubyFilter.MAX_HAN_IN_READING

def script_ok(t):
    """RubyFilter.isRubyText: >= 1 syllabic kana; at most MAX_HAN Han; more kana than Han."""
    kana = sum(1 for c in t if KANA(c))
    han = sum(1 for c in t if HAN(c))
    return kana > 0 and han <= MAX_HAN and kana > han

def nchars(t):
    return len("".join(t.split()))

def pitch(text, box, vert):
    l, t, r, b = box
    n = nchars(text) or 1
    return ((b - t) if vert else (r - l)) / n

def other_chars(t):
    return "".join(c for c in t if not KANA(c) and not c.isspace())

def ext(box, vert):
    l, t, r, b = box
    return (r - l) if vert else (b - t)

def base_rel(R, B, vert):
    """(gap_px, overlap_px, r_reading_extent) of R relative to base B (B's orientation)."""
    rl, rt, rr, rb = R; bl, bt, br, bb = B
    if not vert:
        gap = bt - rb                      # ruby above base
        ov = min(rr, br) - max(rl, bl)
        return gap, ov, rr - rl
    else:
        gap = rl - br                      # ruby right of base column
        ov = min(rb, bb) - max(rt, bt)
        return gap, ov, rb - rt

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("jsonl")
    ap.add_argument("--k", type=float, default=1.5)
    ap.add_argument("--reach", type=float, default=1.0, help="neighbour search bound, in the neighbour's ems")
    ap.add_argument("--gap-min", type=float, default=-0.5)
    ap.add_argument("--pitch", type=float, default=1.4)
    ap.add_argument("--pitch-chars", type=int, default=2)
    ap.add_argument("--band", default="1.1,1.8")
    ap.add_argument("--variant", default="flowgraph")
    ap.add_argument("--lang", default="ja")
    a = ap.parse_args()

    lang = {}
    frames = collections.defaultdict(list)
    with open(a.jsonl) as fh:
        for line in fh:
            d = json.loads(line)
            if d["type"] == "case":
                lang[d["case"]] = d["lang"]
            elif d["type"] == "region":
                eng, var = d["cfg"].split("/")
                if var != a.variant or d.get("rep", 0) != 0:
                    continue
                frames[(d["case"], eng)].append(d)

    demoted = []
    ratios_pass_pos = []   # ratio of every script+position passer (any K)
    n_frames = n_regions = 0
    for (case, eng), regs in sorted(frames.items()):
        if lang.get(case) != a.lang:
            continue
        n_frames += 1
        n_regions += len(regs)
        for R in regs:
            if not script_ok(R["text"]):
                continue
            # attached: the nearest line on R's ruby side, any script
            nearest = None
            for C in regs:
                if C is R:
                    continue
                vert = C["vert"]
                eC, eR = ext(C["box"], vert), ext(R["box"], vert)
                if eC <= 0 or eR <= 0:
                    continue
                gap, ov, rext = base_rel(R["box"], C["box"], vert)
                if ov < 0.5 * rext:
                    continue
                if gap < a.gap_min * eC or gap > a.reach * eC:
                    continue
                if nearest is None or gap < nearest[0]:
                    nearest = (gap, C, eC, eR, vert)
            if nearest is None:
                continue
            gap, B, eB, eR, vert = nearest
            if not any(HAN(c) for c in B["text"]):
                continue
            ratio = eB / eR
            pr = pitch(B["text"], B["box"], vert) / pitch(R["text"], R["box"], vert)
            gap_em = gap / eB
            ratios_pass_pos.append((ratio, R["text"], case, eng, gap_em, B["text"], R["box"], B["box"], "V" if vert else "H"))
            glyph_tight = B.get("cq") is not None
            pitch_available = nchars(R["text"]) >= a.pitch_chars and nchars(B["text"]) >= a.pitch_chars
            h_ok = ratio >= a.k
            p_ok = pitch_available and pr >= a.pitch
            ok = (h_ok if not pitch_available else (h_ok and p_ok) if glyph_tight else (h_ok or p_ok))
            if not ok:
                continue
            demoted.append((case, eng, R["text"], ratio, gap_em, B["text"], "V" if vert else "H",
                            other_chars(R["text"]), pr))

    print(f"frames={n_frames} regions={n_regions} variant={a.variant} K={a.k} reach=[{a.gap_min},{a.reach}] pitch>={a.pitch}@{a.pitch_chars}ch han<={MAX_HAN}")
    print(f"\n== DEMOTED ({len(demoted)}) ==")
    print("case                 eng          ratio  gap/em  pitch  or  text                 | base                       | non-kana")
    for case, eng, t, ratio, gap_em, bt, o, oc, pr in sorted(demoted, key=lambda x: (x[1], x[0])):
        print(f"{case[:20]:20} {eng:12} {ratio:5.2f}  {gap_em:5.2f}  {pr:5.2f}  {o}  {t[:20]:20} | {bt[:26]:26} | {oc}")

    print("\n== cross-ratio histogram of reading+attached passers (all K) ==")
    hist = collections.Counter()
    for ratio, *_ in ratios_pass_pos:
        hist[round(ratio * 10) / 10] += 1
    for k in sorted(hist):
        print(f"  {k:4.1f}: {'#' * hist[k]} ({hist[k]})")
    lo, hi = map(float, a.band.split(","))
    print(f"\n== reading+attached passers in the {lo}-{hi} cross-ratio band ==")
    for ratio, t, case, eng, gap_em, bt, rb, bb, o in sorted(ratios_pass_pos):
        if lo <= ratio < hi:
            print(f"  {ratio:4.2f} gap={gap_em:5.2f} {o} {eng:12} {case[:20]:20} {t[:14]:14} | {bt[:18]:18} R={rb} B={bb}")

if __name__ == "__main__":
    main()
