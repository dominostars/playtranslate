#!/usr/bin/env python3
"""Eval + fixture generator for the Thai word segmenter (ThaiEngine).

Two jobs:

1. **Fidelity fixture** (default): generate a JVM test fixture that pins our
   Kotlin `MaximalMatchThaiSegmenter` (a port of PyThaiNLP `newmm`) to produce
   *byte-identical* output to real PyThaiNLP `newmm`. We emit, into
   `app/src/test/resources/thai/`:
     - `newmm_words.txt`  — the DAG-complete wordlist (every PyThaiNLP word that
       is a prefix at any position in the test sentences), so the JVM trie yields
       an identical match DAG.
     - `newmm_fixture.tsv` — `text <tab> tok1 <tab> tok2 ...` per sentence, tokens
       from PyThaiNLP newmm over the same wordlist.
   `ThaiNewmmFidelityTest` then asserts our port reproduces `tokens` exactly.

2. **Quality metrics** (optional): boundary-F1 of PyThaiNLP newmm vs a gold file
   (`--gold`, one space/`|`-free sentence per line with `|` between gold words),
   and dictionary-hit-rate of the golden tokens against a built `dict.sqlite`
   (`--dict`). Since the fixture proves our port == PyThaiNLP newmm, newmm's F1
   is our F1.

Run with the build venv:  ~/playtranslate/.venv-pt/bin/python scripts/eval_thai_segmenter.py
"""
from __future__ import annotations

import argparse
import sqlite3
from pathlib import Path

from pythainlp.corpus.common import thai_words
from pythainlp.tokenize.newmm import segment as newmm_segment
from pythainlp.util import Trie

# Realistic Thai sentences spanning simple words, compounds, particles, and
# embedded latin/number runs (the non-Thai path).
SENTENCES = [
    "สวัสดีครับผมชื่อสมชาย",
    "วันนี้อากาศดีมาก",
    "ฉันอยากกินข้าวผัด",
    "เขาเดินไปโรงเรียนทุกวัน",
    "ประเทศไทยมีประชากรหกสิบล้านคน",
    "ขอบคุณมากสำหรับความช่วยเหลือ",
    "ร้านอาหารนี้อร่อยและราคาไม่แพง",
    "เด็กกำลังเล่นอยู่ในสวน",
    "ผมเรียนภาษาไทยมาสองปีแล้ว",
    "แม่น้ำเจ้าพระยาไหลผ่านกรุงเทพ",
    "หนังสือเล่มนี้น่าสนใจมาก",
    "เราจะไปเที่ยวทะเลวันเสาร์นี้",
    "โทรศัพท์ของฉันแบตหมด",
    "เขาทำงานที่บริษัทใหญ่",
    "อาหารไทยมีรสชาติเผ็ด",
    "นักเรียนทุกคนต้องทำการบ้าน",
    "ความสุขคือการได้อยู่กับครอบครัว",
    "รถไฟฟ้าสะดวกและรวดเร็ว",
    "ผมไม่เข้าใจคำถามนี้",
    "เธอพูดภาษาอังกฤษได้ดีมาก",
    "ราคา250บาทเท่านั้น",
    "ร้านเปิดเวลา9โมงเช้า",
]


def boundary_set(tokens: list[str]) -> set[int]:
    """Internal word-boundary character offsets (excludes 0 and len)."""
    bset, pos = set(), 0
    for t in tokens[:-1]:
        pos += len(t)
        bset.add(pos)
    return bset


def boundary_f1(gold: list[str], pred: list[str]) -> tuple[float, float, float]:
    g, p = boundary_set(gold), boundary_set(pred)
    if not g and not p:
        return 1.0, 1.0, 1.0
    tp = len(g & p)
    prec = tp / len(p) if p else 0.0
    rec = tp / len(g) if g else 0.0
    f1 = 2 * prec * rec / (prec + rec) if (prec + rec) else 0.0
    return prec, rec, f1


def gen_fixture(out_dir: Path) -> None:
    trie = Trie(thai_words())
    needed: set[str] = set()
    rows = []
    for s in SENTENCES:
        tokens = newmm_segment(s, custom_dict=trie)
        rows.append({"text": s, "tokens": tokens})
        for i in range(len(s)):
            for w in trie.prefixes(s[i:]):
                if w:
                    needed.add(w)
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "newmm_words.txt").write_text(
        "\n".join(sorted(needed)) + "\n", encoding="utf-8"
    )
    # TSV: text \t tok1 \t tok2 ...  (tokens never contain a tab; dependency-free
    # to parse on the JVM side).
    with (out_dir / "newmm_fixture.tsv").open("w", encoding="utf-8") as f:
        for r in rows:
            f.write(r["text"] + "\t" + "\t".join(r["tokens"]) + "\n")
    avg = sum(len(r["tokens"]) for r in rows) / len(rows)
    print(f"Wrote fixture: {len(rows)} sentences, {len(needed)} DAG-complete words, "
          f"avg {avg:.1f} tokens/sentence -> {out_dir}")


def eval_gold(gold_path: Path) -> None:
    trie = Trie(thai_words())
    n, sp, sr, sf = 0, 0.0, 0.0, 0.0
    for line in gold_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        gold = [t for t in line.split("|") if t]
        text = "".join(gold)
        pred = newmm_segment(text, custom_dict=trie)
        p, r, f = boundary_f1(gold, pred)
        n, sp, sr, sf = n + 1, sp + p, sr + r, sf + f
    if n:
        print(f"PyThaiNLP newmm vs gold ({n} sentences): "
              f"P={sp / n:.3f} R={sr / n:.3f} F1={sf / n:.3f}")


def eval_hit_rate(dict_path: Path) -> None:
    trie = Trie(thai_words())
    conn = sqlite3.connect(dict_path)
    headwords = {r[0] for r in conn.execute(
        "SELECT text FROM headword WHERE position = 0")}
    conn.close()
    thai_tok, hit = 0, 0
    for s in SENTENCES:
        for t in newmm_segment(s, custom_dict=trie):
            if any("฀" <= c <= "๿" for c in t):
                thai_tok += 1
                if t in headwords:
                    hit += 1
    if thai_tok:
        print(f"Dictionary hit-rate (Thai tokens resolving in dict.sqlite): "
              f"{hit}/{thai_tok} = {hit / thai_tok:.1%}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    repo = Path(__file__).resolve().parents[1]
    ap.add_argument("--out", type=Path,
                    default=repo / "app/src/test/resources/thai",
                    help="JVM fixture output dir")
    ap.add_argument("--gold", type=Path, help="gold file (|-delimited) for F1")
    ap.add_argument("--dict", type=Path, help="built dict.sqlite for hit-rate")
    args = ap.parse_args()

    gen_fixture(args.out)
    if args.gold:
        eval_gold(args.gold)
    if args.dict:
        eval_hit_rate(args.dict)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
