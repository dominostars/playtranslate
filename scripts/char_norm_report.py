#!/usr/bin/env python3
"""Does character-class normalization rescue the scale gate's wrong blocks?
MEASUREMENT ONLY: this changes nothing in the app.

The scale gate compares glyph-tight line-box cross extents, which move with
each line's CONTENT: a Latin row with a descender measures ~1.3x one without,
same font. This script tests the candidate fix: predict each line's vertical
coverage from its TEXT via a global character -> height-class map ("p" reaches
below the baseline, "b" above x-height, "o" neither), divide the measured
extent by the predicted coverage, and compare the normalized values.

The rule under test is PERMISSIVE-ONLY: a pair passes if the raw delta passes
the cap OR the normalized delta does. Normalization can rescue a blocked pair
but never block a passing one -- so a stylized font that defeats the
prediction (pixel fonts box everything out) degrades to today's behavior, and
no pair outside the gate's current firings can change state. The report
asserts that invariant over every pair rather than trusting the construction.

The map is ONE global codepoint table (typographic facts: which glyphs ascend,
descend, hang, or fill the em box), not per-language tuning. Scripts not yet
mapped (Arabic, Thai, Devanagari...) make their lines ineligible, which just
means fallback to the raw statistic. A line is eligible only if every
non-space character is mapped and at least one is a baseline-anchored core
glyph; both lines must be eligible for the pair to use the normalized path.

Labels come from the corpus stanzas exactly as in glyph_scale_report.py
(anchor by IoU >= 0.3; omission is the corpus's indifference operator), and
both headline numbers are UPPER BOUNDS for the same reason as there: the
scale gate is one check among several, so a rescued SHOULD-MERGE pair may
still be blocked by gap or alignment, and a wrongly rescued SHOULD-SPLIT pair
may still be split by them. Pairing, production-cell restriction (prodToken x
surface-declared doc-pitch variant, rep 0), and the ratio statistic all match
the sibling script so the tables are comparable.

Usage:
  python3 scripts/char_norm_report.py --jsonl ocr-grouping/runs/results-<id>.jsonl \
      --seeds ocr-grouping/assets/ocr_grouping [--verbose]
  python3 scripts/char_norm_report.py --selftest
"""
import argparse
import collections
import json
import os
import sys
import unicodedata

CAPS = (0.30, 0.50)
ANCHOR_MIN_IOU = 0.3

# ── character -> vertical coverage class ─────────────────────────────────────
# (top, bottom) in em units above/below the baseline, plus a kind:
#   core -- baseline-anchored ink that can carry a line's coverage prediction
#   thin -- centered/hanging ink (dashes, dots, quotes): mapped, but a line of
#           ONLY these has no baseline span and is ineligible
# Values are MEASURED medians over 9 system fonts (2026-07-25 sweep; span
# relative to b..p = 1.0): x-height 0.575 (identical Latin/Cyrillic), caps
# 0.755, ascenders 0.80, descenders 0.22. Residual font-to-font spread ~8%,
# against the ~31% content error the raw statistic carries. The app ships a
# HALF-BLENDED variant of this statistic under a raw<=0.50 ceiling; this
# script measures the full-strength statistic.

X_HEIGHT = 0.575
ASCENDER = 0.80
CAP_TOP = 0.755
T_TOP = 0.73          # t and dotted i/j reach short of full ascenders
DESCENDER = 0.22
SMALL_TAIL = 0.125     # Cyrillic д/ц/щ feet, Q's tail
COMMA_BOT = 0.18
CJK_TOP, CJK_BOT = 0.88, 0.12
MARK_ABOVE = 0.22     # first combining mark above; extra stacked marks add less
MARK_STACK = 0.12
MARK_BELOW = 0.18

LATIN_X = set("acemnorsuvwxz")
LATIN_ASC = set("bdfhkl")
LATIN_DESC = set("gpqy")
CYR_LOWER_X = set("авгежзиклмнопстхчшьыэюя")
CYR_DESC_FULL = set("ру")
CYR_SMALL_TAIL = set("дцщ")
SMALL_KANA = set("ぁぃぅぇぉっゃゅょゎゕゖァィゥェォッャュョヮヵヶ")
CJK_LOW_PUNCT = set("、。，．")
CJK_BRACKETS = set("「」『』【】〔〕（）")

PUNCT = {
    ".": (0.12, 0.0, "thin"), "·": (0.55, 0.0, "thin"), "…": (0.12, 0.0, "thin"),
    ",": (0.12, COMMA_BOT, "thin"),
    ":": (X_HEIGHT, 0.0, "thin"), ";": (X_HEIGHT, COMMA_BOT, "thin"),
    "!": (CAP_TOP, 0.0, "core"), "?": (CAP_TOP, 0.0, "core"),
    "'": (CAP_TOP, 0.0, "thin"), "’": (CAP_TOP, 0.0, "thin"),
    "‘": (CAP_TOP, 0.0, "thin"), '"': (CAP_TOP, 0.0, "thin"),
    "“": (CAP_TOP, 0.0, "thin"), "”": (CAP_TOP, 0.0, "thin"),
    "-": (0.35, 0.0, "thin"), "–": (0.35, 0.0, "thin"),
    "—": (0.35, 0.0, "thin"), "~": (0.45, 0.0, "thin"),
    "(": (ASCENDER, COMMA_BOT, "core"), ")": (ASCENDER, COMMA_BOT, "core"),
    "[": (ASCENDER, COMMA_BOT, "core"), "]": (ASCENDER, COMMA_BOT, "core"),
    "{": (ASCENDER, COMMA_BOT, "core"), "}": (ASCENDER, COMMA_BOT, "core"),
    "/": (ASCENDER, SMALL_TAIL, "core"), "\\": (ASCENDER, SMALL_TAIL, "core"),
    "&": (CAP_TOP, 0.0, "core"), "%": (CAP_TOP, 0.0, "core"),
    "*": (CAP_TOP, 0.0, "thin"), "#": (CAP_TOP, 0.0, "core"),
    "$": (ASCENDER, SMALL_TAIL, "core"), "@": (CAP_TOP, COMMA_BOT, "core"),
    "+": (0.55, 0.0, "thin"), "<": (0.55, 0.0, "thin"),
    ">": (0.55, 0.0, "thin"), "=": (0.45, 0.0, "thin"),
    "ー": (0.55, 0.0, "thin"),   # prolonged sound mark: centered bar
    "・": (0.55, 0.0, "thin"), "〜": (0.55, 0.0, "thin"),
    "～": (0.55, 0.0, "thin"),
}


def _is_cjk_full(cp):
    return (0x3400 <= cp <= 0x4DBF or 0x4E00 <= cp <= 0x9FFF
            or 0xF900 <= cp <= 0xFAFF or 0x20000 <= cp <= 0x2FA1F
            or 0xAC00 <= cp <= 0xD7A3 or 0x1100 <= cp <= 0x11FF
            or 0x3130 <= cp <= 0x318F)


def _is_kana(cp):
    return 0x3040 <= cp <= 0x309F or 0x30A0 <= cp <= 0x30FF


def base_metrics(ch):
    """(top, bottom, kind) for a base (non-combining) character, or None."""
    if ch in PUNCT:
        return PUNCT[ch]
    if ch in CJK_LOW_PUNCT:
        return (0.25, 0.0, "thin")
    if ch in CJK_BRACKETS:
        return (CJK_TOP, CJK_BOT, "core")
    if ch in SMALL_KANA:
        return (0.62, 0.08, "core")
    cp = ord(ch)
    if _is_kana(cp) or _is_cjk_full(cp):
        return (CJK_TOP, CJK_BOT, "core")
    if 0xFF01 <= cp <= 0xFF5E:                      # fullwidth ASCII forms
        return base_metrics(chr(cp - 0xFF00 + 0x20))
    if 0xFF66 <= cp <= 0xFF9D:                      # halfwidth katakana
        return (CJK_TOP, CJK_BOT, "core")
    if ch.isdigit():
        return (CAP_TOP, 0.0, "core")
    if "a" <= ch <= "z":
        if ch in LATIN_X:
            return (X_HEIGHT, 0.0, "core")
        if ch in LATIN_ASC:
            return (ASCENDER, 0.0, "core")
        if ch in LATIN_DESC:
            return (X_HEIGHT, DESCENDER, "core")
        if ch in "ti":
            return (T_TOP, 0.0, "core")
        if ch == "j":
            return (T_TOP, DESCENDER, "core")
    if "A" <= ch <= "Z":
        return (CAP_TOP, SMALL_TAIL if ch == "Q" else 0.0, "core")
    if ch == "đ":                                   # Vietnamese d-stroke
        return (ASCENDER, 0.0, "core")
    if ch == "Đ":
        return (CAP_TOP, 0.0, "core")
    if "а" <= ch <= "я" or ch == "ё":   # Cyrillic lowercase + ё
        if ch == "б":                             # б
            return (ASCENDER, 0.0, "core")
        if ch == "ф":                             # ф
            return (ASCENDER, DESCENDER, "core")
        if ch == "й":                             # й
            return (X_HEIGHT + MARK_STACK, 0.0, "core")
        if ch == "ё":                             # ё
            return (X_HEIGHT + MARK_ABOVE, 0.0, "core")
        if ch in CYR_DESC_FULL:
            return (X_HEIGHT, DESCENDER, "core")
        if ch in CYR_SMALL_TAIL:
            return (X_HEIGHT, SMALL_TAIL, "core")
        return (X_HEIGHT, 0.0, "core")
    if "А" <= ch <= "Я" or ch == "Ё":   # Cyrillic caps + Ё
        if ch in "ЦЩД":                 # Ц Щ Д tails/feet
            return (CAP_TOP, SMALL_TAIL, "core")
        if ch == "Й":                             # Й
            return (CAP_TOP + MARK_STACK, 0.0, "core")
        if ch == "Ё":                             # Ё
            return (CAP_TOP + MARK_STACK, 0.0, "core")
        return (CAP_TOP, 0.0, "core")
    return None


def line_coverage(text):
    """Predicted coverage span (em units) for a horizontal line, or None if
    any non-space character is unmapped. Combining marks (NFD) raise the
    base's top / drop its bottom, so accented Latin and Vietnamese stacks
    price in."""
    top = bot = 0.0
    n_core = 0
    last = None    # (top, bot, kind, n_marks) of the last base char
    for ch in unicodedata.normalize("NFD", text):
        if ch.isspace():
            continue
        cc = unicodedata.combining(ch)
        if cc:
            if last is None:
                return None
            t, b, kind, n_marks = last
            # Only marks that render above (class 230) or below (220/202) the
            # base extend its coverage. Everything else -- kana voicing marks
            # (class 8: NFD splits が into か+゙, but the dakuten lives INSIDE
            # the em box), attached marks like the Vietnamese horn (216) --
            # stays within the base's span and must not stretch it: treating
            # dakuten as an accent manufactured rescues for JA name-tag pairs.
            if cc in (220, 202):
                b = max(b, MARK_BELOW)
            elif cc == 230:
                t += MARK_ABOVE if n_marks == 0 else MARK_STACK
                n_marks += 1          # only above-marks stack
            last = (t, b, kind, n_marks)
            continue
        if last is not None:
            t, b, kind, _ = last
            top, bot = max(top, t), max(bot, b)
            if kind == "core":
                n_core += 1
        m = base_metrics(ch)
        if m is None:
            return None
        last = (m[0], m[1], m[2], 0)
    if last is not None:
        t, b, kind, _ = last
        top, bot = max(top, t), max(bot, b)
        if kind == "core":
            n_core += 1
    if n_core == 0:
        return None
    return top + bot


def column_coverage(text):
    """Predicted ink WIDTH for a vertical column (cross axis = width): the
    height-class model does not apply sideways. Full-width CJK fills the em,
    small kana are narrower, centered thin marks carry no width claim. Any
    non-CJK content makes the column ineligible -- rotated/upright Latin in
    vertical text is not modelled."""
    width = 0.0
    n_core = 0
    for ch in text:
        if ch.isspace():
            continue
        cp = ord(ch)
        if ch in PUNCT or ch in CJK_LOW_PUNCT:
            continue                       # thin/low marks: no width claim
        if ch in SMALL_KANA:
            width = max(width, 0.80)
            n_core += 1
        elif ch in CJK_BRACKETS or _is_kana(cp) or _is_cjk_full(cp):
            width = max(width, 1.0)
            n_core += 1
        else:
            return None
    return width if n_core else None


# ── inputs / geometry (mirrors glyph_scale_report.py) ────────────────────────

def load(path):
    regions = collections.defaultdict(list)
    status, meta = {}, {}
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
            m = meta.setdefault(d["case"], {})
            for k in ("lang", "surface", "prodToken"):
                if d.get(k) is not None:
                    m[k] = d[k]
    return regions, status, meta


def parse_seed(path):
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


def iou(a, b):
    ix = min(a[2], b[2]) - max(a[0], b[0])
    iy = min(a[3], b[3]) - max(a[1], b[1])
    if ix <= 0 or iy <= 0:
        return 0.0
    inter = ix * iy
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / union if union else 0.0


def cross_extent(box, vert):
    return (box[2] - box[0]) if vert else (box[3] - box[1])


def ratio(a, b):
    lo, hi = min(a, b), max(a, b)
    return None if lo <= 0 else (hi - lo) / lo


def adjacent_pairs(regs):
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
    best_idx, best_iou = None, 0.0
    for si, rows in enumerate(stanzas):
        for box in rows:
            v = iou(tuple(region["box"]), box)
            if v > best_iou:
                best_idx, best_iou = si, v
    return best_idx if best_iou >= ANCHOR_MIN_IOU else None


# ── analysis ─────────────────────────────────────────────────────────────────

def norm_delta(a, b, is_vert):
    """Normalized delta for a pair, or None when either line is ineligible."""
    cov = column_coverage if is_vert else line_coverage
    ca, cb = cov(a["text"] or ""), cov(b["text"] or "")
    if not ca or not cb:
        return None
    ea = cross_extent(a["box"], is_vert) / ca
    eb = cross_extent(b["box"], is_vert) / cb
    return ratio(ea, eb)


def analyse(regions, status, meta, seeds_dir, rep=0):
    stats = collections.defaultdict(collections.Counter)
    events = []          # (kind, cap, case, lang, ld, nd, a, b, vert)
    invariant_broken = 0
    for key, regs in sorted(regions.items()):
        case, cfg, r = key
        if r != rep or status.get(key) != "ok":
            continue
        m = meta.get(case, {})
        tok = m.get("prodToken")
        variant = "docpitch-on" if m.get("surface") == "import" else "docpitch-off"
        if cfg != f"{tok}/{variant}":
            continue
        lang = m.get("lang", "?")
        p = os.path.join(seeds_dir, case + ".groups.txt") if seeds_dir else None
        stanzas = parse_seed(p) if p and os.path.isfile(p) else []
        anchors = {id(reg): (anchor(reg, stanzas) if stanzas else None) for reg in regs}

        for a, b, is_vert in adjacent_pairs(regs):
            ld = ratio(cross_extent(a["box"], is_vert), cross_extent(b["box"], is_vert))
            if ld is None:
                continue
            sa, sb = anchors[id(a)], anchors[id(b)]
            label = ("unlabelled" if sa is None or sb is None
                     else "should_merge" if sa == sb else "should_split")
            k = (lang, cfg.split("/")[0])
            c = stats[k]
            c["pairs"] += 1
            c[label] += 1
            nd = norm_delta(a, b, is_vert)
            if nd is None:
                c["ineligible"] += 1

            # The permissive rule as an effective statistic: a pair's delta is
            # the raw one unless the pair is eligible, in which case the more
            # forgiving of the two applies. new_fires is derived from THIS
            # formulation, and the invariant below checks it against the raw
            # outcome instead of trusting the by-construction argument.
            eff = ld if nd is None else min(ld, nd)
            for cap in CAPS:
                sfx = f"@{cap:.2f}"
                raw_fires = ld > cap
                new_fires = eff > cap
                if not raw_fires and new_fires:
                    invariant_broken += 1   # permissive-only must forbid this
                if raw_fires:
                    c["fires" + sfx] += 1
                    if label == "should_merge":
                        c["wrong_block" + sfx] += 1
                if raw_fires and not new_fires:
                    c[f"{label}/rescued{sfx}"] += 1
                    events.append((label, cap, case, lang, ld, nd,
                                   a["text"], b["text"], is_vert))
    return stats, events, invariant_broken


def report(stats, events, invariant_broken, verbose):
    tot = collections.Counter()
    hdr = (f"{'lang':6s} {'engine':12s} {'pairs':>5s} {'merge':>5s} {'split':>5s} "
           f"{'inel':>5s}")
    for cap in CAPS:
        hdr += f" | {'fires':>5s} {'wrong':>5s} {'resc':>5s} {'badresc':>7s} @{cap:.2f}"
    print("== per production cell (resc = rescued wrong blocks; badresc = "
          "rescued SHOULD-SPLIT blocks) ==")
    print(hdr)
    for k in sorted(stats):
        c = stats[k]
        row = (f"{k[0]:6s} {k[1]:12s} {c['pairs']:5d} {c['should_merge']:5d} "
               f"{c['should_split']:5d} {c['ineligible']:5d}")
        for cap in CAPS:
            sfx = f"@{cap:.2f}"
            row += (f" | {c['fires' + sfx]:5d} {c['wrong_block' + sfx]:5d} "
                    f"{c[f'should_merge/rescued{sfx}']:5d} "
                    f"{c[f'should_split/rescued{sfx}']:7d}      ")
        print(row)
        for f in list(c):
            tot[f] += c[f]
    row = f"{'TOTAL':19s} {tot['pairs']:5d} {tot['should_merge']:5d} {tot['should_split']:5d} {tot['ineligible']:5d}"
    for cap in CAPS:
        sfx = f"@{cap:.2f}"
        row += (f" | {tot['fires' + sfx]:5d} {tot['wrong_block' + sfx]:5d} "
                f"{tot[f'should_merge/rescued{sfx}']:5d} "
                f"{tot[f'should_split/rescued{sfx}']:7d}      ")
    print(row)
    print(f"\npermissive invariant (no pair outside the firings changes): "
          f"{'BROKEN x' + str(invariant_broken) if invariant_broken else 'holds'}")
    for cap in CAPS:
        sfx = f"@{cap:.2f}"
        n_un = tot[f"unlabelled/rescued{sfx}"]
        print(f"unlabelled rescues @{cap:.2f}: {n_un} (reported, nothing claimed)")

    if verbose and events:
        print("\n== rescued pairs (rescue is an UPPER BOUND: other gates may "
              "still block the merge) ==")
        for label, cap, case, lang, ld, nd, ta, tb, vert in events:
            v = " vert" if vert else ""
            print(f"  [{label} @{cap:.2f}]{v} [{lang}] {case}  raw={ld:.2f} norm={nd:.2f}")
            print(f"        {ta[:48]!r}")
            print(f"        {tb[:48]!r}")


# ── selftest ─────────────────────────────────────────────────────────────────

def selftest():
    ok = True

    def check(name, cond):
        nonlocal ok
        print(("  PASS " if cond else "  FAIL ") + name)
        ok = ok and cond

    # coverage predictions
    check("descender line spans full", abs(line_coverage("sample") - 1.02) < 1e-9)
    check("ascender-only line", abs(line_coverage("for translation") - 0.80) < 1e-9)
    check("x-height-only line", abs(line_coverage("nano") - 0.575) < 1e-9)
    check("caps-only line", abs(line_coverage("DECK") - 0.755) < 1e-9)
    check("accent raises top", line_coverage("é") > line_coverage("e"))
    check("dakuten stays inside the em box", abs(line_coverage("だけど") - 1.0) < 1e-9)
    check("vietnamese horn stays inside", abs(line_coverage("ư") - X_HEIGHT) < 1e-9)
    check("vietnamese d-stroke maps", abs(line_coverage("đường") - 1.02) < 1e-9)
    check("cyrillic descender", abs(line_coverage("скорее") - 0.795) < 1e-9)
    check("cjk em box", abs(line_coverage("鳴潮") - 1.0) < 1e-9)
    check("unmapped char -> ineligible", line_coverage("a★b") is None)
    check("thin-only line -> ineligible", line_coverage("ーーー") is None)
    check("empty -> ineligible", line_coverage("") is None)
    check("vertical full-width column", column_coverage("今日の移動は") == 1.0)
    check("vertical latin -> ineligible", column_coverage("abc") is None)

    def reg(box, text, vert=False):
        return {"box": list(box), "text": text, "vert": vert}

    # the canonical rescue: same font, descender vs no-descender (raw 0.31)
    a = reg([0, 0, 400, 157], "This is a text sample")
    b = reg([0, 200, 300, 320], "for translation")
    nd = norm_delta(a, b, False)
    check("text_sample pair rescued", nd is not None and nd < 0.30)

    # pixel-font enemy: equal boxes, skewed prediction -> norm fires but the
    # permissive rule never consults it when raw passes
    a2 = reg([0, 0, 100, 40], "pb")
    b2 = reg([0, 50, 100, 90], "ao")
    nd2 = norm_delta(a2, b2, False)
    check("pixel-font prediction skewed as expected", nd2 is not None and nd2 > 0.30)

    # a real 1.45x heading with equal content classes is NOT rescued
    a3 = reg([0, 0, 400, 145], "Item Name Here")
    b3 = reg([0, 200, 400, 300], "item body here")
    nd3 = norm_delta(a3, b3, False)
    check("real scale difference survives", nd3 is not None and nd3 > 0.30)

    # end-to-end: invariant + rescue accounting on a tiny synthetic run.
    # Two cases so each contributes exactly its own adjacent pair: s1 is the
    # rescued wrap, s2 is the pixel-font enemy (raw passes, prediction skewed).
    regions = {
        ("s1", "mlkit/docpitch-off", 0): [a, b],
        ("s2", "mlkit/docpitch-off", 0): [a2, b2],
    }
    status = {k: "ok" for k in regions}
    meta = {c_: {"lang": "en", "prodToken": "mlkit", "surface": "screen"}
            for c_ in ("s1", "s2")}
    stats, events, broken = analyse(regions, status, meta, None)
    c = stats[("en", "mlkit")]
    check("invariant holds", broken == 0)
    check("one rescue counted at bare cap", c["unlabelled/rescued@0.30"] == 1)
    check("rescued pair still counted as a raw firing", c["fires@0.30"] == 1)
    check("pixel-font pair kept its raw acceptance", c["fires@0.30"] == 1 and c["pairs"] == 2)

    print("selftest:", "OK" if ok else "FAILED")
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jsonl")
    ap.add_argument("--seeds", help="corpus dir with <case>.groups.txt; without it no labels")
    ap.add_argument("--rep", type=int, default=0)
    ap.add_argument("--verbose", action="store_true")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return selftest()
    if not args.jsonl:
        ap.error("--jsonl is required (or --selftest)")

    regions, status, meta = load(args.jsonl)
    stats, events, broken = analyse(regions, status, meta, args.seeds, args.rep)
    report(stats, events, broken, args.verbose)
    return 0


if __name__ == "__main__":
    sys.exit(main())
