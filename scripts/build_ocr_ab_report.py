#!/usr/bin/env python3
"""Build the OCR A/B diff-TRIAGE report from OcrAbHarnessTest JSONL output.

This is deliberately NOT a scorer. It emits no aggregate quality metric, no
ranking, no pass/fail — whole-screen metrics have misranked OCR configs in
this repo before (reading-order artifacts corrupted CER for every config;
spurious noise-region deltas can dominate any count). Instead it:

  1. matches regions between a baseline config and each variant config
     PER CASE by box IoU (greedy 1:1, deterministic) — text is only ever
     compared within a matched pair, so reading order cannot enter at all;
  2. sorts every difference into piles:
       identical      matched, same text (or detcap), IoU >= IOU_IDENTICAL
       jitter         matched, same text, box moved (IoU < IOU_IDENTICAL)
       text_differs   matched, text changed          (fp16 experiments only)
       merge_split    1 box <-> N boxes, concatenated text equal
                      (segmentation-neutral; fp16 only)
       only_base      box the baseline found and the variant lost
                      (for detcap: recall loss at the lower cap)
       only_var       box only the variant found (spurious/gained)
       noise          only_* boxes under NOISE_MIN_AREA px^2, collapsed
  3. renders an HTML report where each diff is the CROPPED PIXELS of that box
     with both configs' readings — so the human decides, in seconds per diff,
     whether a change matters.

Usage:
  build_ocr_ab_report.py --jsonl results-<runId>.jsonl \
      --golden app/src/androidTest/assets/ocr_golden \
      --corpus ./ocr_ab_out/cases --out ocr_ab_report.html
  build_ocr_ab_report.py --selftest

--jsonl also accepts an `adb logcat -d -s OcrAb:I` dump (the parser starts at
the first '{' per line and skips unparseable lines, including a final line
truncated by a mid-run crash).
"""

import argparse
import base64
import html
import json
import os
import statistics
import sys
import unicodedata
from collections import defaultdict

# ── Tunables (host-side by design: retune without re-running the device pass) ─
IOU_MATCH = 0.5        # min IoU to pair two regions 1:1
IOU_IDENTICAL = 0.9    # matched + same text at/above -> identical; below -> jitter
CONTAIN_FRAC = 0.8     # merge-split: fraction of a small box inside the big box
MERGE_UNION_IOU = 0.5  # merge-split: union-bbox IoU vs the single box
MERGE_MAX_N = 6        # merge-split: cap on the N side
NOISE_MIN_AREA = 64    # px^2: only_* boxes under this go to the collapsed noise bucket
CROP_PAD = 6           # px of visual context around each rendered crop

# Baseline config per experiment; anything else seen is a variant.
BASELINE = {"fp16-meiki": "p0", "fp16-paddle": "p0", "detcap-paddle": "det1920",
            "pack-meiki": "nopack"}

# Human meaning of each config label (rendered as a legend per experiment).
# pN = the MnnInterpreter precision flag; detN = PaddleOcrSession detLimitSide.
CONFIG_LEGEND = {
    "p0": "MNN precision 0 (Precision_Normal, fp32) — current production",
    "p2": "MNN precision 2 (Precision_Low, fp16 ARM NEON)",
    "p2d": "mixed: fp16 detector + fp32 recognizer",
    "det1920": "detector input long-side cap 1920px — current production",
    "det1280": "detector input long-side cap 1280px",
    "det960": "detector input long-side cap 960px (PaddleOCR upstream default)",
    "nopack": "one crop per rec canvas — current production",
    "pack": "multiple crops packed per rec canvas (32px gaps, shared char budget)",
}

PILES = ("identical", "jitter", "text_differs", "merge_split", "only_base", "only_var", "noise")


# ── Parsing ───────────────────────────────────────────────────────────────────

def parse_jsonl(path):
    """Parse JSONL or a logcat dump; returns (records, skipped_line_count)."""
    records, skipped = [], 0
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            brace = line.find("{")
            if brace < 0:
                continue
            try:
                obj = json.loads(line[brace:])
            except json.JSONDecodeError:
                skipped += 1
                continue
            if isinstance(obj, dict) and obj.get("type") in ("run", "skip", "case", "region"):
                records.append(obj)
    return records, skipped


def index_records(records):
    """Bucket records. Last record wins on duplicate keys (re-runs in one dump)."""
    idx = {
        "runs": [],
        "skips": [],
        # (exp, case, cfg) -> case record / list of region records
        "cases": {},
        "regions": defaultdict(list),
        "langs": {},           # (exp, case) -> lang
        "cfgs": defaultdict(list),   # exp -> ordered unique cfgs (first-seen order)
    }
    for r in records:
        t = r["type"]
        if t == "run":
            idx["runs"].append(r)
        elif t == "skip":
            idx["skips"].append(r)
        elif t == "case":
            key = (r["exp"], r["case"], r["cfg"])
            idx["cases"][key] = r
            idx["langs"][(r["exp"], r["case"])] = r.get("lang", "?")
            if r["cfg"] not in idx["cfgs"][r["exp"]]:
                idx["cfgs"][r["exp"]].append(r["cfg"])
        elif t == "region":
            idx["regions"][(r["exp"], r["case"], r["cfg"])].append(r)
    for regs in idx["regions"].values():
        regs.sort(key=lambda r: r.get("idx", 0))
    return idx


# ── Geometry / text ──────────────────────────────────────────────────────────

def area(b):
    return max(0, b[2] - b[0]) * max(0, b[3] - b[1])


def inter(a, b):
    l, t = max(a[0], b[0]), max(a[1], b[1])
    r, bt = min(a[2], b[2]), min(a[3], b[3])
    return max(0, r - l) * max(0, bt - t)


def iou(a, b):
    i = inter(a, b)
    u = area(a) + area(b) - i
    return i / u if u > 0 else 0.0


def union_box(boxes):
    return [min(b[0] for b in boxes), min(b[1] for b in boxes),
            max(b[2] for b in boxes), max(b[3] for b in boxes)]


def norm(text):
    """NFKC + strip ALL whitespace — the only text normalization applied."""
    return "".join(unicodedata.normalize("NFKC", text or "").split())


# ── Matching / piles ─────────────────────────────────────────────────────────

def match_regions(base, var):
    """Greedy 1:1 bipartite matching on box IoU >= IOU_MATCH.

    Deterministic: candidates sorted by (-iou, base_idx, var_idx).
    Returns (pairs [(i, j, iou)], unmatched_base_idx, unmatched_var_idx).
    """
    cands = []
    for i, a in enumerate(base):
        for j, b in enumerate(var):
            v = iou(a["box"], b["box"])
            if v >= IOU_MATCH:
                cands.append((v, i, j))
    cands.sort(key=lambda c: (-c[0], c[1], c[2]))
    taken_i, taken_j, pairs = set(), set(), []
    for v, i, j in cands:
        if i in taken_i or j in taken_j:
            continue
        taken_i.add(i)
        taken_j.add(j)
        pairs.append((i, j, v))
    un_i = [i for i in range(len(base)) if i not in taken_i]
    un_j = [j for j in range(len(var)) if j not in taken_j]
    return pairs, un_i, un_j


def reading_order(regions):
    """Order a merge-group's regions for text concatenation: columns
    right-to-left when the group is tall (tategaki), else rows top-to-bottom."""
    if len(regions) <= 1:
        return list(regions)
    boxes = [r["box"] for r in regions]
    ub = union_box(boxes)
    tall = (ub[3] - ub[1]) > (ub[2] - ub[0]) * 1.5
    if tall:
        med = statistics.median((b[2] - b[0]) for b in boxes) * 0.7 or 1.0
        return sorted(regions, key=lambda r: (-round(((r["box"][0] + r["box"][2]) / 2) / med), r["box"][1]))
    med = statistics.median((b[3] - b[1]) for b in boxes) * 0.7 or 1.0
    return sorted(regions, key=lambda r: (round(r["box"][1] / med), r["box"][0]))


def find_merge_splits(single_side, single_un, many_side, many_un):
    """Find 1<->N groupings: an unmatched box on one side whose text equals the
    reading-order concatenation of >=2 unmatched contained boxes on the other.
    Returns (groups [(single_idx, [many_idx,...])], used_singles, used_manys)."""
    groups, used_s, used_m = [], set(), set()
    for si in single_un:
        s = single_side[si]
        cand = [mi for mi in many_un
                if mi not in used_m
                and area(many_side[mi]["box"]) > 0
                and inter(many_side[mi]["box"], s["box"]) / area(many_side[mi]["box"]) >= CONTAIN_FRAC]
        if len(cand) < 2:
            continue
        cand = cand[:MERGE_MAX_N]
        members = [many_side[mi] for mi in cand]
        if iou(union_box([m["box"] for m in members]), s["box"]) < MERGE_UNION_IOU:
            continue
        concat = "".join(m.get("text", "") for m in reading_order(members))
        if norm(concat) != norm(s.get("text", "")):
            continue
        groups.append((si, cand))
        used_s.add(si)
        used_m.update(cand)
    return groups, used_s, used_m


def classify(base, var, have_text):
    """Sort one (baseline regions, variant regions) comparison into piles.
    Returns {pile: [entries]}; entries reference the raw region dicts."""
    piles = {p: [] for p in PILES}
    pairs, un_b, un_v = match_regions(base, var)

    for i, j, v in pairs:
        same = (not have_text) or norm(base[i].get("text")) == norm(var[j].get("text"))
        if same and v >= IOU_IDENTICAL:
            piles["identical"].append({"base": base[i], "var": var[j], "iou": v})
        elif same:
            piles["jitter"].append({"base": base[i], "var": var[j], "iou": v})
        else:
            piles["text_differs"].append({"base": base[i], "var": var[j], "iou": v})

    if have_text:
        # splits: 1 baseline box -> N variant boxes; merges: N baseline -> 1 variant
        splits, used_b, used_v = find_merge_splits(base, un_b, var, un_v)
        for si, manys in splits:
            piles["merge_split"].append({"single": base[si], "many": [var[j] for j in manys], "dir": "split"})
        un_b = [i for i in un_b if i not in used_b]
        un_v = [j for j in un_v if j not in used_v]
        merges, used_v2, used_b2 = find_merge_splits(var, un_v, base, un_b)
        for sj, manys in merges:
            piles["merge_split"].append({"single": var[sj], "many": [base[i] for i in manys], "dir": "merge"})
        un_b = [i for i in un_b if i not in used_b2]
        un_v = [j for j in un_v if j not in used_v2]

    for i in un_b:
        pile = "only_base" if area(base[i]["box"]) >= NOISE_MIN_AREA else "noise"
        piles[pile].append({"side": "base", "region": base[i]})
    for j in un_v:
        pile = "only_var" if area(var[j]["box"]) >= NOISE_MIN_AREA else "noise"
        piles[pile].append({"side": "var", "region": var[j]})
    return piles


# ── HTML rendering ───────────────────────────────────────────────────────────

CSS = """
body{font-family:system-ui,sans-serif;margin:24px;max-width:1200px}
table{border-collapse:collapse;margin:8px 0}
td,th{border:1px solid #aaa;padding:4px 10px;text-align:left;vertical-align:top}
.crop{display:block;image-rendering:pixelated}
.cropwrap{max-width:1000px;overflow:auto;border:1px solid #888;display:inline-block;background:#000}
.pile{margin:12px 0 20px 0}
.ocrtext{font-size:16px;white-space:pre-wrap}
.tag{color:#666;font-size:12px}
.warn{color:#a40000}
h4{margin:20px 0 4px 0;border-top:2px solid #ccc;padding-top:10px}
details{margin:6px 0}
"""


def load_case_image(case_id, golden_dir, corpus_dir):
    """Resolve a case id to its PNG path: golden/<base> or staged/<lang>/<base>."""
    if case_id.startswith("golden/"):
        cand = os.path.join(golden_dir or "", case_id[len("golden/"):] + ".png")
    elif case_id.startswith("staged/"):
        cand = os.path.join(corpus_dir or "", case_id[len("staged/"):] + ".png")
    else:
        return None
    return cand if os.path.isfile(cand) else None


def crop_div(cls, box):
    l, t, r, b = box
    l, t = max(0, l - CROP_PAD), max(0, t - CROP_PAD)
    w, h = (r + CROP_PAD) - l, (b + CROP_PAD) - t
    return (f'<span class="cropwrap"><span class="crop {cls}" '
            f'style="width:{w}px;height:{h}px;background-position:-{l}px -{t}px"></span></span>')


def region_label(r):
    tag = "[V] " if r.get("vert") else ""
    conf = r.get("conf")
    conf_s = f' <span class="tag">conf={conf:.2f}</span>' if isinstance(conf, (int, float)) else ""
    text = html.escape(r.get("text", "")) or '<span class="tag">(no text)</span>'
    return f'<span class="ocrtext">{tag}{text}</span>{conf_s} <span class="tag">{r["box"]}</span>'


def render_case(out, case_id, cfg_base, cfg_var, piles, img_cls, missing_img, base_case, var_case):
    meaningful = (piles["text_differs"] or piles["only_base"] or piles["only_var"]
                  or piles["merge_split"] or piles["noise"])
    status_notes = []
    for label, rec in (("baseline", base_case), ("variant", var_case)):
        if rec is None:
            status_notes.append(f'<span class="warn">{label} has no data (crashed mid-run?)</span>')
        elif rec.get("status") != "ok":
            status_notes.append(f'<span class="warn">{label} error: {html.escape(str(rec.get("reason")))}</span>')
    if not meaningful and not status_notes:
        return False

    out.append(f"<h4>{html.escape(case_id)} <span class='tag'>({html.escape(cfg_var)} vs {html.escape(cfg_base)})</span></h4>")
    for n in status_notes:
        out.append(f"<p>{n}</p>")
    if missing_img:
        out.append('<p class="warn">screenshot not found — diffs shown without crops</p>')

    def crop(box):
        return crop_div(img_cls, box) if img_cls else ""

    if piles["text_differs"]:
        out.append('<div class="pile"><b>text differs</b> (matched box, changed reading)<table>')
        out.append(f"<tr><th>crop</th><th>{html.escape(cfg_base)}</th><th>{html.escape(cfg_var)}</th></tr>")
        for e in piles["text_differs"]:
            out.append(f'<tr><td>{crop(e["base"]["box"])}</td>'
                       f'<td>{region_label(e["base"])}</td><td>{region_label(e["var"])}</td></tr>')
        out.append("</table></div>")

    for pile, title in (("only_base", f"only in {cfg_base} (variant lost this box)"),
                        ("only_var", f"only in {cfg_var} (variant gained this box)")):
        if piles[pile]:
            out.append(f'<div class="pile"><b>{html.escape(title)}</b><table>')
            for e in piles[pile]:
                out.append(f'<tr><td>{crop(e["region"]["box"])}</td><td>{region_label(e["region"])}</td></tr>')
            out.append("</table></div>")

    if piles["merge_split"]:
        out.append('<div class="pile"><b>merge/split</b> (re-segmentation, concatenated text equal — usually neutral)<table>')
        for e in piles["merge_split"]:
            parts = " + ".join(region_label(m) for m in e["many"])
            out.append(f'<tr><td>{crop(e["single"]["box"])}</td>'
                       f'<td>{e["dir"]}: {region_label(e["single"])}<br>&nbsp;&nbsp;&harr; {parts}</td></tr>')
        out.append("</table></div>")

    if piles["noise"]:
        out.append(f'<details><summary>{len(piles["noise"])} sub-{NOISE_MIN_AREA}px&sup2; only-in-one boxes (noise bucket)</summary><table>')
        for e in piles["noise"]:
            out.append(f'<tr><td>{e["side"]}</td><td>{region_label(e["region"])}</td></tr>')
        out.append("</table></details>")

    out.append(f'<p class="tag">unchanged: {len(piles["identical"])} identical, '
               f'{len(piles["jitter"])} box-jitter-only</p>')
    return True


def p90(values):
    s = sorted(values)
    return s[int(0.9 * (len(s) - 1))] if s else 0


def timing_rows(idx, exp):
    rows = []
    for cfg in idx["cfgs"][exp]:
        recs = [c for (e, _case, cf), c in idx["cases"].items() if e == exp and cf == cfg]
        ok = [c for c in recs if c.get("status") == "ok"]
        times = [c.get("totalMs", c.get("detMs")) for c in ok]
        times = [t for t in times if isinstance(t, (int, float))]
        med = round(statistics.median(times)) if times else "-"
        rows.append((cfg, len(ok), len(recs) - len(ok), med, round(p90(times)) if times else "-"))
    return rows


def build_report(idx, golden_dir, corpus_dir, out_path, source_name):
    out = [f"<!doctype html><html><head><meta charset='utf-8'>"
           f"<title>OCR A/B triage</title><style>{CSS}</style></head><body>"]
    out.append("<h1>OCR A/B triage report</h1>")
    runs = ", ".join(sorted({r.get("run", "?") for r in idx["runs"]})) or "?"
    out.append(f"<p class='tag'>source: {html.escape(source_name)} &middot; run(s): {html.escape(runs)}</p>")
    out.append("<p><b>Triage counts are not scores.</b> Every entry below is a difference to be "
               "eyeballed; the human verdict is the only verdict.</p>")
    for s in idx["skips"]:
        out.append(f"<p class='warn'>skipped {html.escape(s.get('exp', '?'))}: {html.escape(s.get('reason', ''))}</p>")

    img_cache = {}   # case_id -> (css_class or None)
    css_blocks = []

    for exp in sorted(idx["cfgs"]):
        cfgs = idx["cfgs"][exp]
        base_cfg = BASELINE.get(exp, cfgs[0] if cfgs else None)
        if base_cfg is None:
            continue
        variants = [c for c in cfgs if c != base_cfg]
        have_text = not exp.startswith("detcap")
        out.append(f"<h2>{html.escape(exp)}</h2>")
        legend = [f"<b>{html.escape(c)}</b> = {html.escape(CONFIG_LEGEND.get(c, 'unknown config'))}"
                  for c in cfgs]
        out.append(f"<p class='tag'>{' &middot; '.join(legend)}</p>")

        out.append("<table><tr><th>config</th><th>cases ok</th><th>errors</th>"
                   "<th>median ms</th><th>p90 ms</th></tr>")
        for cfg, n_ok, n_err, med, p9 in timing_rows(idx, exp):
            mark = " (baseline)" if cfg == base_cfg else ""
            out.append(f"<tr><td>{html.escape(cfg)}{mark}</td><td>{n_ok}</td><td>{n_err}</td>"
                       f"<td>{med}</td><td>{p9}</td></tr>")
        out.append("</table>")

        cases = sorted({c for (e, c, _cfg) in idx["cases"] if e == exp})
        for var_cfg in variants:
            out.append(f"<h3>{html.escape(var_cfg)} vs {html.escape(base_cfg)}</h3>")
            counts = {p: 0 for p in PILES}
            case_sections = []
            for case_id in cases:
                base_case = idx["cases"].get((exp, case_id, base_cfg))
                var_case = idx["cases"].get((exp, case_id, var_cfg))
                base_regs = idx["regions"].get((exp, case_id, base_cfg), [])
                var_regs = idx["regions"].get((exp, case_id, var_cfg), [])
                piles = classify(base_regs, var_regs, have_text)
                for p in PILES:
                    counts[p] += len(piles[p])

                if case_id not in img_cache:
                    path = load_case_image(case_id, golden_dir, corpus_dir)
                    if path:
                        cls = f"bg{len(img_cache)}"
                        with open(path, "rb") as f:
                            b64 = base64.b64encode(f.read()).decode("ascii")
                        css_blocks.append(f".{cls}{{background-image:url(data:image/png;base64,{b64})}}")
                        img_cache[case_id] = cls
                    else:
                        img_cache[case_id] = None
                img_cls = img_cache[case_id]

                section = []
                if render_case(section, case_id, base_cfg, var_cfg, piles, img_cls,
                               img_cls is None, base_case, var_case):
                    case_sections.extend(section)

            out.append("<table><tr>" + "".join(f"<th>{p}</th>" for p in PILES) + "</tr><tr>"
                       + "".join(f"<td>{counts[p]}</td>" for p in PILES) + "</tr></table>")
            if case_sections:
                out.extend(case_sections)
            else:
                out.append("<p>No differences beyond identical/jitter in any case.</p>")

    out.append("<style>" + "\n".join(css_blocks) + "</style></body></html>")
    with open(out_path, "w", encoding="utf-8") as f:
        f.write("\n".join(out))


# ── Selftest ─────────────────────────────────────────────────────────────────

def _r(box, text=None, vert=False, conf=None):
    r = {"box": list(box), "vert": vert}
    if text is not None:
        r["text"] = text
    if conf is not None:
        r["conf"] = conf
    return r


def selftest():
    failures = []

    def check(name, cond):
        if not cond:
            failures.append(name)

    # -- classify fixtures (fp16-style, have_text=True) --
    base = [
        _r((0, 0, 200, 40), "ab cd"),      # split into two in variant
        _r((0, 100, 100, 140), "hello"),   # identical
        _r((0, 200, 100, 240), "world"),   # jitter (variant shifted 10px)
        _r((0, 300, 100, 340), "same"),    # text_differs (variant reads "sane")
        _r((0, 400, 300, 460), "lost"),    # only_base
    ]
    var = [
        _r((0, 0, 95, 40), "ab"),
        _r((105, 0, 200, 40), "cd"),
        _r((0, 100, 100, 140), "hello"),
        _r((10, 200, 110, 240), "world"),
        _r((0, 300, 100, 340), "sane"),
        _r((500, 500, 504, 504), "x"),     # 16 px^2 -> noise
    ]
    piles = classify(base, var, have_text=True)
    check("identical", len(piles["identical"]) == 1 and piles["identical"][0]["base"]["text"] == "hello")
    check("jitter", len(piles["jitter"]) == 1 and piles["jitter"][0]["base"]["text"] == "world")
    check("text_differs", len(piles["text_differs"]) == 1 and piles["text_differs"][0]["var"]["text"] == "sane")
    check("merge_split", len(piles["merge_split"]) == 1 and piles["merge_split"][0]["dir"] == "split"
          and len(piles["merge_split"][0]["many"]) == 2)
    check("only_base", len(piles["only_base"]) == 1 and piles["only_base"][0]["region"]["text"] == "lost")
    check("only_var_empty", len(piles["only_var"]) == 0)
    check("noise", len(piles["noise"]) == 1 and piles["noise"][0]["side"] == "var")

    # -- merge direction (N base -> 1 var) --
    piles = classify(
        [_r((0, 0, 95, 40), "ab"), _r((105, 0, 200, 40), "cd")],
        [_r((0, 0, 200, 40), "AB CD")],   # text mismatch (NFKC does not case-fold)
        have_text=True)
    check("merge_none_when_text_mismatch", len(piles["merge_split"]) == 0)
    piles = classify(
        [_r((0, 0, 95, 40), "ab"), _r((105, 0, 200, 40), "cd")],
        [_r((0, 0, 200, 40), "ab cd")],
        have_text=True)
    check("merge", len(piles["merge_split"]) == 1 and piles["merge_split"][0]["dir"] == "merge")

    # -- deterministic IoU tie-break: two identical base boxes, one var box --
    pairs, un_b, un_v = match_regions(
        [_r((0, 0, 100, 40), "x"), _r((0, 0, 100, 40), "x")],
        [_r((0, 0, 100, 40), "x")])
    check("tiebreak", pairs == [(0, 0, 1.0)] and un_b == [1] and un_v == [])

    # -- detcap (have_text=False): text ignored, recall loss surfaces --
    piles = classify(
        [_r((0, 0, 100, 40)), _r((0, 100, 100, 140))],
        [_r((0, 0, 100, 40))],
        have_text=False)
    check("detcap_identical", len(piles["identical"]) == 1)
    check("detcap_recall_loss", len(piles["only_base"]) == 1)

    # -- vertical reading order for merge concat (columns right-to-left) --
    cols = [_r((0, 0, 30, 200), "second"), _r((40, 0, 70, 200), "first")]
    ordered = reading_order(cols)
    check("vertical_order", [r["text"] for r in ordered] == ["first", "second"])

    # -- parser: logcat prefixes, garbage, truncation --
    import tempfile
    with tempfile.NamedTemporaryFile("w", suffix=".jsonl", delete=False, encoding="utf-8") as f:
        f.write("random garbage line\n")
        f.write('07-16 12:00:00.000  1234  5678 I OcrAb   : {"type":"run","run":"1","ts":1,"exp":"all","abi":"arm64-v8a"}\n')
        f.write('{"type":"case","run":"1","exp":"fp16-meiki","case":"golden/x","lang":"ja","cfg":"p0","status":"ok","regions":0,"totalMs":5}\n')
        f.write('{"type":"region","run":"1","exp":"fp16-meiki","case":"golden/x","cfg":"p0","idx":0,"box":[1,2,3')  # truncated
        tmp = f.name
    records, skipped = parse_jsonl(tmp)
    os.unlink(tmp)
    check("parse", len(records) == 2 and skipped == 1)
    idx = index_records(records)
    check("index", ("fp16-meiki", "golden/x", "p0") in idx["cases"] and idx["cfgs"]["fp16-meiki"] == ["p0"])

    if failures:
        print("SELFTEST FAILED:", ", ".join(failures))
        return 1
    print("selftest OK")
    return 0


# ── Main ─────────────────────────────────────────────────────────────────────

def main():
    global IOU_MATCH, IOU_IDENTICAL
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--jsonl", help="results-<runId>.jsonl (or an OcrAb logcat dump)")
    ap.add_argument("--golden", help="dir with golden PNGs (app/src/androidTest/assets/ocr_golden)")
    ap.add_argument("--corpus", help="dir with staged case PNGs (<pulled ocr_ab>/cases)")
    ap.add_argument("--out", default="ocr_ab_report.html")
    ap.add_argument("--iou-match", type=float, help=f"override IOU_MATCH (default {IOU_MATCH})")
    ap.add_argument("--iou-identical", type=float, help=f"override IOU_IDENTICAL (default {IOU_IDENTICAL})")
    ap.add_argument("--selftest", action="store_true", help="run embedded fixtures and exit")
    args = ap.parse_args()

    if args.selftest:
        sys.exit(selftest())
    if not args.jsonl:
        ap.error("--jsonl is required (or use --selftest)")

    if args.iou_match is not None:
        IOU_MATCH = args.iou_match
    if args.iou_identical is not None:
        IOU_IDENTICAL = args.iou_identical

    records, skipped = parse_jsonl(args.jsonl)
    if not records:
        print(f"no parseable records in {args.jsonl}", file=sys.stderr)
        sys.exit(1)
    if skipped:
        print(f"note: skipped {skipped} unparseable line(s)")
    idx = index_records(records)
    build_report(idx, args.golden, args.corpus, args.out, os.path.basename(args.jsonl))
    n_cases = len({(e, c) for (e, c, _cfg) in idx["cases"]})
    print(f"wrote {args.out} ({len(idx['cfgs'])} experiment(s), {n_cases} case(s))")


if __name__ == "__main__":
    main()
