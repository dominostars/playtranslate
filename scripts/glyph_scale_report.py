#!/usr/bin/env python3
"""Does a char-tier scale statistic separate line pairs the glyph-tight line box
cannot? MEASUREMENT ONLY: this changes nothing in the app.

The scale gate asks "are these two lines the same font size?" and answers from
line-box cross-axis extent, which moves with the line's CONTENT (a Latin row
with a descender is far taller than one without, same font). GlyphScale answers
it from per-glyph boxes instead. This script puts the two statistics side by
side on real captures:

  lineDelta  (hi-lo)/lo on the line box's cross-axis extent   <- what the gate uses today
  charDelta  min over matched 25/50/75 quantiles of the same ratio on per-glyph
             boxes                                            <- the candidate

## Why this needs the expectation files

A disagreement between the two statistics is not by itself a win or a loss. A
heading/body pair or a ruby/base pair whose char quantiles happen to collapse
below the cap looks identical, in the numbers alone, to a same-font paragraph
pair the gate is wrongly splitting — and counting the first as evidence FOR the
change is how a bad rule gets shipped. So every pair is labelled from the
corpus's curated stanzas before it is counted:

  SHOULD-MERGE   both lines anchor into the SAME stanza
  SHOULD-SPLIT   both lines anchor into DIFFERENT stanzas
  UNLABELLED     either line is not covered by any stanza

Expectations are partial by design (omission is the corpus's indifference
operator), so UNLABELLED is normal and large; it is reported separately and
NOTHING is claimed about it. Without --seeds the labelled tables are omitted
entirely rather than guessed at.

## Both labelled numbers are UPPER BOUNDS

The scale gate is one check among several. A SHOULD-MERGE pair whose lineDelta
exceeds the cap may ALSO be failing gap or alignment, in which case fixing scale
would not merge it; a SHOULD-SPLIT pair may still be split by gap or alignment
even if scale stops rejecting it. This script does not re-run those checks, so
"payload" and "cost" are both ceilings. If the two come out close, the next step
is the DetectionLog trace (run_suite.sh dumps it) which names the check that
actually fired on each pair.

## Read the coverage table first

Only engines that measure per-glyph boxes have the statistic at all; Paddle CTC
cells and manga-ocr synthesis take their cross-axis extent from the line box, so
the harness omits the field rather than restating the line height. If the tier
is absent on the engine a language actually uses, the statistic cannot carry a
rule for that language no matter what the other tables say.

Usage:
  python3 scripts/glyph_scale_report.py --jsonl ocr-grouping/runs/results-<id>.jsonl \
      --seeds ocr-grouping/assets/ocr_grouping
  python3 scripts/glyph_scale_report.py --selftest
"""
import argparse
import collections
import json
import os
import sys

CAPS = (0.30, 0.50)
ANCHOR_MIN_IOU = 0.3


# ── inputs ───────────────────────────────────────────────────────────────────

def load(path):
    """Region rows keyed by (case, cfg, rep), plus the set of keys whose `case`
    completion marker says the pass finished. The harness flushes region rows
    BEFORE that marker, so a run that died mid-case leaves orphan rows on disk;
    counting them would let a failed run look like measured data."""
    regions = collections.defaultdict(list)
    status = {}
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if not line:
            continue
        d = json.loads(line)
        key = (d.get("case"), d.get("cfg"), d.get("rep"))
        if d.get("type") == "region":
            regions[key].append(d)
        elif d.get("type") == "case":
            status[key] = d.get("status")
    return regions, status


def parse_seed(path):
    """Expected groups: blank-line-separated stanzas of `text<TAB>l,t,r,b`."""
    stanzas, cur = [], []
    for line in open(path, encoding="utf-8"):
        s = line.rstrip("\n")
        if s.startswith("#"):
            continue
        if not s.strip():
            if cur:
                stanzas.append(cur)
                cur = []
            continue
        if "\t" not in s:
            continue
        _, box = s.split("\t", 1)
        try:
            # Rows are l,t,r,b or l,t,r,b,ang,ow,oh (slanted rows carry a float
            # angle + oriented dims); only the AABB matters here, so slice — a
            # full-tuple int() parse would ValueError on the angle and silently
            # drop the row's label coverage.
            cur.append(tuple(int(x) for x in box.split(",")[:4]))
        except ValueError:
            continue
    if cur:
        stanzas.append(cur)
    return stanzas


# ── geometry ─────────────────────────────────────────────────────────────────

def iou(a, b):
    ix = min(a[2], b[2]) - max(a[0], b[0])
    iy = min(a[3], b[3]) - max(a[1], b[1])
    if ix <= 0 or iy <= 0:
        return 0.0
    inter = ix * iy
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / union if union else 0.0


def cross_extent(box, vert):
    left, top, right, bottom = box
    return (right - left) if vert else (bottom - top)


def ratio(a, b):
    lo, hi = min(a, b), max(a, b)
    return None if lo <= 0 else (hi - lo) / lo


def char_delta(qa, qb):
    """min over MATCHED quantiles — see GlyphScale.scaleDelta."""
    best = None
    for x, y in zip(qa, qb):
        r = ratio(x, y)
        if r is not None and (best is None or r < best):
            best = r
    return best


def adjacent_pairs(regs):
    """Consecutive lines in reading order that overlap on the inline axis. A pair
    spanning two columns is not a scale question."""
    horiz = sorted((r for r in regs if not r["vert"]), key=lambda r: r["box"][1])
    vert = sorted((r for r in regs if r["vert"]), key=lambda r: -r["box"][2])
    for seq, is_vert in ((horiz, False), (vert, True)):
        for a, b in zip(seq, seq[1:]):
            ax0, ax1 = (a["box"][1], a["box"][3]) if is_vert else (a["box"][0], a["box"][2])
            bx0, bx1 = (b["box"][1], b["box"][3]) if is_vert else (b["box"][0], b["box"][2])
            if min(ax1, bx1) - max(ax0, bx0) <= 0.5 * min(ax1 - ax0, bx1 - bx0):
                continue
            yield a, b, is_vert


def anchor(region, stanzas):
    """Index of the stanza this region belongs to, or None when no curated row
    covers it (partial expectations — the common case)."""
    best_idx, best_iou = None, 0.0
    for si, rows in enumerate(stanzas):
        for box in rows:
            v = iou(tuple(region["box"]), box)
            if v > best_iou:
                best_idx, best_iou = si, v
    return best_idx if best_iou >= ANCHOR_MIN_IOU else None


# ── report ───────────────────────────────────────────────────────────────────

def analyse(regions, status, seeds_dir, rep, require_ok=True):
    cov = collections.Counter()
    skipped = collections.Counter()
    pair_stats = collections.defaultdict(collections.Counter)
    flips = []

    for key, regs in sorted(regions.items()):
        case, cfg, r = key
        if r != rep:
            continue
        if require_ok and status.get(key) != "ok":
            skipped[status.get(key) or "no completion marker"] += 1
            continue
        engine = cfg.split("/")[0]
        for reg in regs:
            cov[(engine, "cq" in reg)] += 1

        stanzas = []
        if seeds_dir:
            p = os.path.join(seeds_dir, case + ".groups.txt")
            if os.path.isfile(p):
                stanzas = parse_seed(p)
        anchors = {id(reg): (anchor(reg, stanzas) if stanzas else None) for reg in regs}

        for a, b, is_vert in adjacent_pairs(regs):
            ld = ratio(cross_extent(a["box"], is_vert), cross_extent(b["box"], is_vert))
            if ld is None:
                continue
            bucket = pair_stats[engine]
            bucket["pairs"] += 1
            if "cq" not in a or "cq" not in b:
                bucket["no_char_tier"] += 1
                continue
            cd = char_delta(a["cq"], b["cq"])
            if cd is None:
                bucket["no_char_tier"] += 1
                continue
            bucket["measured"] += 1

            sa, sb = anchors[id(a)], anchors[id(b)]
            if not stanzas or sa is None or sb is None:
                label = "unlabelled"
            elif sa == sb:
                label = "should_merge"
            else:
                label = "should_split"

            for cap in CAPS:
                if (ld > cap) > (cd > cap):
                    bucket[f"{label}/line_rejects_char_accepts@{cap:.2f}"] += 1
                    if label != "unlabelled":
                        flips.append((case, cfg, label, cap, ld, cd, a["text"], b["text"]))
                    break
                if (cd > cap) > (ld > cap):
                    bucket[f"{label}/char_rejects_line_accepts@{cap:.2f}"] += 1
                    if label != "unlabelled":
                        flips.append((case, cfg, label, cap, ld, cd, a["text"], b["text"]))
                    break
            else:
                bucket[f"{label}/agree"] += 1
    return cov, skipped, pair_stats, flips


def report(cov, skipped, pair_stats, flips, seeds_dir, verbose):
    print("== char-tier coverage (lines with a measured tier) ==")
    for e in sorted({e for e, _ in cov}):
        have, miss = cov[(e, True)], cov[(e, False)]
        total = have + miss
        pct = (100.0 * have / total) if total else 0.0
        print(f"  {e:14s} {have:5d}/{total:<5d} lines  ({pct:5.1f}%)")
    if not cov:
        print("  (no completed cases)")

    if skipped:
        print("\n== skipped, no ok completion marker ==")
        for reason, n in sorted(skipped.items()):
            print(f"  {reason:24s} {n} (case,cfg,rep) groups")

    print("\n== adjacent pairs ==")
    for e in sorted(pair_stats):
        c = pair_stats[e]
        print(f"  {e:14s} pairs={c['pairs']:4d} measured={c['measured']:4d} no-tier={c['no_char_tier']:4d}")

    if not seeds_dir:
        print("\n(no --seeds: pairs cannot be labelled, so no payload/cost tables.")
        print(" A disagreement is not a win or a loss without knowing whether the")
        print(" pair should have merged.)")
        return

    print("\n== labelled disagreements (UPPER BOUNDS -- see module docstring) ==")
    for e in sorted(pair_stats):
        c = pair_stats[e]
        rows = [(k, v) for k, v in sorted(c.items()) if "/" in k and v]
        if not rows:
            continue
        print(f"  {e}")
        for k, v in rows:
            label, rest = k.split("/", 1)
            note = ""
            if label == "should_merge" and rest.startswith("line_rejects_char_accepts"):
                note = "  <- payload ceiling"
            elif label == "should_split" and rest.startswith("line_rejects_char_accepts"):
                note = "  <- cost ceiling"
            print(f"    {label:13s} {rest:34s} {v:4d}{note}")

    if verbose and flips:
        print("\n== labelled flipping pairs ==")
        for case, cfg, label, cap, ld, cd, ta, tb in flips:
            print(f"  [{label} @{cap:.2f}] {case}/{cfg}  line={ld:.2f} char={cd:.2f}")
            print(f"        {ta[:44]!r}")
            print(f"        {tb[:44]!r}")


# ── selftest ─────────────────────────────────────────────────────────────────

def selftest():
    """Embedded fixtures: the plumbing that decides what gets counted."""
    ok = True

    def check(name, cond):
        nonlocal ok
        print(("  PASS " if cond else "  FAIL ") + name)
        ok = ok and cond

    regions = {
        ("s", "mlkit/x", 0): [
            {"box": [0, 0, 100, 40], "vert": False, "text": "a", "cq": [10, 10, 10], "group": 0},
            {"box": [0, 50, 100, 70], "vert": False, "text": "b", "cq": [10, 10, 10], "group": 0},
        ],
        ("dead", "mlkit/x", 0): [
            {"box": [0, 0, 100, 40], "vert": False, "text": "orphan", "cq": [10, 10, 10], "group": 0},
        ],
    }
    status = {("s", "mlkit/x", 0): "ok", ("dead", "mlkit/x", 0): "error"}

    cov, skipped, stats, _ = analyse(regions, status, None, rep=0)
    check("orphan rows from a failed case are excluded", cov[("mlkit", True)] == 2)
    check("the failed case is reported, not silently dropped", sum(skipped.values()) == 1)

    cov2, _, _, _ = analyse(regions, status, None, rep=0, require_ok=False)
    check("without the filter the orphan would have been counted", cov2[("mlkit", True)] == 3)

    # lineDelta 1.0 (40 vs 20), charDelta 0.0 -> a disagreement at both caps.
    c = stats["mlkit"]
    dis = [k for k in c if "line_rejects_char_accepts" in k]
    check("disagreement detected", len(dis) == 1)
    check("unlabelled without seeds", dis and dis[0].startswith("unlabelled/"))

    check("char_delta takes matched quantiles", char_delta([10, 20, 30], [10, 40, 60]) == 0.0)
    check("char_delta never crosses quantiles", char_delta([10, 20, 30], [20, 40, 60]) == 1.0)
    print("selftest:", "OK" if ok else "FAILED")
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jsonl")
    ap.add_argument("--seeds", help="corpus dir with <case>.groups.txt; without it no labelled tables")
    ap.add_argument("--rep", type=int, default=0, help="ML Kit runs several reps; default 0")
    ap.add_argument("--verbose", action="store_true")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return selftest()
    if not args.jsonl:
        ap.error("--jsonl is required (or --selftest)")

    regions, status = load(args.jsonl)
    cov, skipped, stats, flips = analyse(regions, status, args.seeds, args.rep)
    print(f"(rep {args.rep}; ML Kit emits several and this reads one)\n")
    report(cov, skipped, stats, flips, args.seeds, args.verbose)
    return 0


if __name__ == "__main__":
    sys.exit(main())
