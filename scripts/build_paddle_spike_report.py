#!/usr/bin/env python3
"""Build the PaddleOCR spike's side-by-side HTML + metrics CSV from a logcat dump.

The on-device harness (PaddleOcrComparisonTest) emits tab-separated ROW / BOX /
LINE records under the `PaddleSpike` logcat tag. Recover them with:

    adb logcat -d -s PaddleSpike:I > paddle_spike.log

then:

    python scripts/build_paddle_spike_report.py paddle_spike.log

Outputs (next to the log):
  * paddleocr-spike-report.html  — per-case golden image beside all 5 configs'
    raw outputs + CER/latency; the artifact for the SUBJECTIVE pass.
  * paddleocr-spike-metrics.csv  — one row per (case, config) for spreadsheeting.
  * console summary — median latency per config, mean CER, A/B/C box outcomes.

No third-party deps (stdlib only). Golden PNGs are read from
app/src/androidTest/assets/ocr_golden and embedded as base64 so the HTML is
self-contained.
"""
import sys, os, re, csv, base64, html, statistics
from collections import defaultdict

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GOLDEN_DIR = os.path.join(REPO, "app", "src", "androidTest", "assets", "ocr_golden")
CONFIGS = ["baseline", "pp_full_mobile", "hybrid_mobile", "pp_full_server", "hybrid_server"]

TAG_RE = re.compile(r"PaddleSpike\s*:\s*")

def strip_prefix(line):
    """Drop everything up to and including 'PaddleSpike: '."""
    m = TAG_RE.search(line)
    return line[m.end():].rstrip("\n") if m else None

def parse(logpath):
    rows = defaultdict(dict)     # case -> config -> dict
    boxes = defaultdict(dict)    # case -> config -> {A,B,C,lines}
    lines = defaultdict(list)    # (case,config) -> [(i,cls,ml,pp)]
    with open(logpath, encoding="utf-8", errors="replace") as f:
        for raw in f:
            payload = strip_prefix(raw)
            if not payload:
                continue
            parts = payload.split("\t")
            kind = parts[0]
            if kind == "ROW" and len(parts) >= 4:
                case, config = parts[1], parts[2]
                d = {}
                for p in parts[3:]:
                    if "=" in p:
                        k, v = p.split("=", 1); d[k] = v
                rows[case][config] = d
            elif kind == "BOX" and len(parts) >= 6:
                case, config = parts[1], parts[2]
                d = {}
                for p in parts[3:]:
                    if "=" in p:
                        k, v = p.split("=", 1); d[k] = v
                boxes[case][config] = d
            elif kind == "LINE" and len(parts) >= 7:
                case, config, idx, cls, ml, pp = parts[1], parts[2], parts[3], parts[4], parts[5], parts[6]
                lines[(case, config)].append((idx, cls, ml, pp))
    return rows, boxes, lines

def fnum(d, key, default=None):
    try: return float(d[key])
    except (KeyError, ValueError, TypeError): return default

def img_data_uri(case):
    for ext in (".png", ".PNG"):
        p = os.path.join(GOLDEN_DIR, case + ext)
        if os.path.exists(p):
            with open(p, "rb") as fh:
                return "data:image/png;base64," + base64.b64encode(fh.read()).decode()
    return None

def write_csv(rows, out):
    cols = ["case", "config", "cer", "realSub", "realDel", "realIns",
            "detMs", "recMs", "totalMs", "actual", "expected"]
    with open(out, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f); w.writerow(cols)
        for case in sorted(rows):
            for config in CONFIGS:
                d = rows[case].get(config)
                if not d: continue
                w.writerow([case, config, d.get("cer",""), d.get("realSub",""),
                            d.get("realDel",""), d.get("realIns",""), d.get("detMs",""),
                            d.get("recMs",""), d.get("totalMs",""),
                            d.get("actual",""), d.get("expected","")])

def summarize(rows, boxes):
    print("\n=== latency (median ms) & CER (mean) per config ===")
    print(f"{'config':16} {'n':>3} {'med detMs':>10} {'med recMs':>10} {'med totalMs':>12} {'mean CER':>9} {'sum realDrop':>12}")
    for config in CONFIGS:
        cers, det, rec, tot, drops = [], [], [], [], 0
        for case in rows:
            d = rows[case].get(config)
            if not d: continue
            c = fnum(d, "cer");
            if c is not None: cers.append(c)
            for lst, k in ((det,"detMs"),(rec,"recMs"),(tot,"totalMs")):
                v = fnum(d, k)
                if v is not None: lst.append(v)
            drops += int(fnum(d, "realDel", 0) or 0) + int(fnum(d, "realSub", 0) or 0)
        if not cers and not tot: continue
        med = lambda x: statistics.median(x) if x else float("nan")
        print(f"{config:16} {len(cers):>3} {med(det):>10.0f} {med(rec):>10.0f} "
              f"{med(tot):>12.0f} {(statistics.mean(cers) if cers else float('nan')):>9.3f} {drops:>12}")
    # box outcomes
    print("\n=== symbol-box outcome (hybrid arms): A=identical B=diff/same-len C=diff/realign ===")
    for config in ("hybrid_mobile", "hybrid_server"):
        A=B=C=L=0
        for case in boxes:
            d = boxes[case].get(config)
            if not d: continue
            A += int(d.get("A","0")); B += int(d.get("B","0"))
            C += int(d.get("C","0")); L += int(d.get("lines","0"))
        if L: print(f"{config:16} lines={L:>4}  A={A:>4} ({100*A/L:4.1f}%)  "
                    f"B={B:>4} ({100*B/L:4.1f}%)  C={C:>4} ({100*C/L:4.1f}%)")

def write_html(rows, boxes, lines, out):
    def esc(s): return html.escape(s or "")
    parts = ["<!doctype html><meta charset=utf-8><title>PaddleOCR Spike</title>",
             "<style>",
             "body{font:14px/1.5 -apple-system,sans-serif;margin:24px;max-width:1400px}",
             "h2{margin-top:48px;border-bottom:2px solid #ccc}",
             "img{max-width:680px;border:1px solid #ddd;display:block;margin:8px 0}",
             "table{border-collapse:collapse;width:100%;margin:8px 0}",
             "td,th{border:1px solid #ddd;padding:6px 8px;vertical-align:top;text-align:left}",
             "th{background:#f5f5f5}.cfg{font-weight:600;white-space:nowrap}",
             ".jp{font-size:16px}.m{color:#666;font-size:12px;white-space:nowrap}",
             ".A{color:#888}.B{color:#0a0}.C{color:#c00}",
             ".exp{background:#fffbe6}",
             "</style>",
             "<h1>PaddleOCR spike — subjective comparison</h1>",
             "<p>Per case: the golden image, then each engine's raw output. Judge which "
             "is actually correct and why. CER is vs ML-Kit-seeded ground truth and "
             "is a screen, not a verdict (see scope doc).</p>"]
    for case in sorted(rows):
        parts.append(f"<h2>{esc(case)}</h2>")
        uri = img_data_uri(case)
        if uri: parts.append(f'<img src="{uri}">')
        # expected
        exp = ""
        for config in CONFIGS:
            d = rows[case].get(config)
            if d and d.get("expected"): exp = d["expected"]; break
        parts.append('<table><tr><th>config</th><th>output</th><th>metrics</th></tr>')
        parts.append(f'<tr class=exp><td class=cfg>ground&nbsp;truth</td>'
                     f'<td class=jp>{esc(exp)}</td><td class=m>—</td></tr>')
        for config in CONFIGS:
            d = rows[case].get(config)
            if not d:
                parts.append(f'<tr><td class=cfg>{config}</td><td class=m>(no data)</td><td></td></tr>')
                continue
            metrics = (f"CER {d.get('cer','?')}<br>realSub {d.get('realSub','?')} "
                       f"realDel {d.get('realDel','?')} realIns {d.get('realIns','?')}<br>"
                       f"det {d.get('detMs','?')}ms rec {d.get('recMs','?')}ms")
            bx = boxes.get(case, {}).get(config)
            if bx:
                metrics += f"<br>box A={bx.get('A')} B={bx.get('B')} C={bx.get('C')}"
            parts.append(f'<tr><td class=cfg>{config}</td>'
                         f'<td class=jp>{esc(d.get("actual",""))}</td>'
                         f'<td class=m>{metrics}</td></tr>')
        parts.append('</table>')
        # per-line hybrid pairs (collapsible)
        for config in ("hybrid_mobile", "hybrid_server"):
            ls = lines.get((case, config))
            if not ls: continue
            parts.append(f'<details><summary>{config}: per-line ML Kit vs PP ({len(ls)})</summary><table>'
                         '<tr><th>#</th><th>cls</th><th>ML Kit</th><th>PaddleOCR</th></tr>')
            for idx, cls, ml, pp in ls:
                parts.append(f'<tr><td>{esc(idx)}</td><td class={esc(cls)}>{esc(cls)}</td>'
                             f'<td class=jp>{esc(ml)}</td><td class=jp>{esc(pp)}</td></tr>')
            parts.append('</table></details>')
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(parts))

def main():
    if len(sys.argv) < 2:
        print(__doc__); sys.exit(1)
    logpath = sys.argv[1]
    rows, boxes, lines = parse(logpath)
    if not rows:
        print(f"No ROW records found in {logpath}. Did the harness run? "
              f"(adb logcat -d -s PaddleSpike:I > {logpath})")
        sys.exit(2)
    base = os.path.dirname(os.path.abspath(logpath))
    html_out = os.path.join(base, "paddleocr-spike-report.html")
    csv_out = os.path.join(base, "paddleocr-spike-metrics.csv")
    write_csv(rows, csv_out)
    write_html(rows, boxes, lines, html_out)
    summarize(rows, boxes)
    print(f"\nHTML: {html_out}\nCSV : {csv_out}")
    print(f"cases={len(rows)}")

if __name__ == "__main__":
    main()
