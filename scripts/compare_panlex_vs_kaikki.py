#!/usr/bin/env python3
"""
For each kaikki-built target pack, measure what a PanLex-built pack
would contain — without actually building it. One pass through the
PanLex source files, intersecting against ALL targets at once.

Memory-light: target indices store only meaning_id sets (not glosses),
since the comparison only needs to know "does PanLex have a target gloss
at this meaning?" — not the gloss text itself.

Output: D:/translate_app/local/panlex_vs_kaikki.json
"""
from __future__ import annotations

import csv
import json
import sqlite3
import sys
from collections import defaultdict
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ROOT = Path(__file__).resolve().parent.parent
KAIKKI_BUILD = ROOT / "local" / "target-build"
PANLEX_DIR = ROOT / "local" / "panlex-spike"

APP_TO_PANLEX = {
    "ja": "jpn", "zh": "cmn", "ko": "kor", "en": "eng",
    "es": "spa", "fr": "fra", "de": "deu", "it": "ita",
    "pt": "por", "nl": "nld", "tr": "tur", "vi": "vie",
    "id": "ind", "sv": "swe", "da": "dan", "no": "nob",
    "fi": "fin", "hu": "hun", "ro": "ron", "ca": "cat",
    "ar": "arb", "hi": "hin", "he": "heb", "fa": "pes",
    "uk": "ukr", "th": "tha", "pl": "pol", "cs": "ces",
    "el": "ell", "ru": "rus", "ms": "zsm",
}
PANLEX_TO_APP = {v: k for k, v in APP_TO_PANLEX.items()}

KAIKKI_TARGETS = ["cs", "de", "el", "id", "it", "ko", "ms",
                  "nl", "pl", "pt", "ru", "th", "tr", "vi"]


def load_meaning_set(path: Path) -> set[int]:
    """Just the set of meaning IDs that have at least one non-empty gloss."""
    out = set()
    with open(path, encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            try:
                m = int(row["meaning"])
            except (TypeError, ValueError):
                continue
            t = (row.get("txt") or "").strip()
            if t:
                out.add(m)
    return out


def load_meaning_to_writtens(path: Path) -> dict[int, set[str]]:
    """For source files: meaning_id -> set of written forms (deduped)."""
    out: dict[int, set[str]] = defaultdict(set)
    with open(path, encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            try:
                m = int(row["meaning"])
            except (TypeError, ValueError):
                continue
            t = (row.get("txt") or "").strip()
            if t:
                out[m].add(t)
    return out


def kaikki_keys(db: Path) -> tuple[set, int, dict]:
    c = sqlite3.connect(str(db))
    keys = set()
    per_src = defaultdict(int)
    total = 0
    for sl, w in c.execute("SELECT source_lang, written FROM glosses"):
        keys.add((sl, w))
        per_src[sl] += 1
        total += 1
    c.close()
    return keys, total, dict(per_src)


def app_lang_for(panlex_code: str) -> str:
    return PANLEX_TO_APP.get(panlex_code, panlex_code)


def main():
    print(f"Loading {len(KAIKKI_TARGETS)} target meaning sets + kaikki keysets...",
          flush=True)
    target_meanings: dict[str, set[int]] = {}
    kaikki: dict[str, dict] = {}
    for code in KAIKKI_TARGETS:
        plx = APP_TO_PANLEX[code]
        path = PANLEX_DIR / f"{plx}.tsv"
        if not path.exists():
            print(f"  WARN: missing {path}")
            continue
        target_meanings[code] = load_meaning_set(path)
        db = KAIKKI_BUILD / code / "out" / "glosses.sqlite"
        if not db.exists():
            print(f"  WARN: missing kaikki DB at {db}")
            continue
        kk_keys, kk_rows, kk_src = kaikki_keys(db)
        kaikki[code] = {
            "keys": kk_keys, "rows": kk_rows, "src_codes": kk_src,
        }
        print(f"  {code}: {len(target_meanings[code]):,} meanings, "
              f"kaikki {kk_rows:,} rows, {len(kk_keys):,} keys",
              flush=True)

    panlex_keys: dict[str, set] = {c: set() for c in target_meanings}
    panlex_rows: dict[str, int] = {c: 0 for c in target_meanings}
    panlex_per_src: dict[str, dict] = {c: defaultdict(int) for c in target_meanings}

    source_files = sorted(p for p in PANLEX_DIR.glob("*.tsv")
                          if p.stat().st_size > 100_000)
    print(f"\nStreaming {len(source_files)} PanLex source files (one pass)...",
          flush=True)

    for i, sf in enumerate(source_files):
        plx_code = sf.stem
        try:
            src_idx = load_meaning_to_writtens(sf)
        except Exception as e:
            print(f"  [{i+1}/{len(source_files)}] {plx_code}: load failed: {e}")
            continue
        src_meanings = set(src_idx)
        src_app = app_lang_for(plx_code)

        emitted_for = []
        for tgt_code, tgt_meanings in target_meanings.items():
            if APP_TO_PANLEX[tgt_code] == plx_code:
                continue
            shared = src_meanings & tgt_meanings
            if len(shared) < 50:
                continue
            row_n = 0
            for m in shared:
                for w in src_idx[m]:
                    panlex_keys[tgt_code].add((src_app, w))
                    row_n += 1
            panlex_rows[tgt_code] += row_n
            panlex_per_src[tgt_code][src_app] += row_n
            emitted_for.append(tgt_code)

        if (i + 1) % 50 == 0 or (i + 1) == len(source_files):
            print(f"  [{i+1}/{len(source_files)}] {plx_code}  emitted->{emitted_for}",
                  flush=True)

    summary = []
    print("\n\n=== PER-TARGET COMPARISON ===")
    for code in KAIKKI_TARGETS:
        if code not in kaikki or code not in panlex_keys:
            continue
        kk = kaikki[code]["keys"]
        pl = panlex_keys[code]
        overlap = kk & pl
        only_kk = kk - pl
        only_pl = pl - kk
        union = kk | pl
        add_pct = 100 * len(only_pl) / max(len(union), 1)
        top_adds = sorted(panlex_per_src[code].items(),
                          key=lambda x: -x[1])[:5]
        print(f"\n{code} → PanLex {APP_TO_PANLEX[code]}")
        print(f"  kaikki:        {kaikki[code]['rows']:>9,} rows, "
              f"{len(kk):>9,} keys, "
              f"{len(kaikki[code]['src_codes']):>3} src codes")
        print(f"  panlex (calc): {panlex_rows[code]:>9,} rows, "
              f"{len(pl):>9,} keys, "
              f"{len(panlex_per_src[code]):>3} src codes")
        print(f"  overlap:       {len(overlap):>9,} keys")
        print(f"  kaikki-only:   {len(only_kk):>9,} keys")
        print(f"  panlex-only:   {len(only_pl):>9,} keys (NEW from PanLex)")
        print(f"  union:         {len(union):>9,} keys")
        print(f"  PanLex add %:  {add_pct:.1f}%  (of union)")
        print(f"  top-5 panlex sources: " +
              ", ".join(f"{s}({n:,})" for s, n in top_adds))
        summary.append({
            "target": code,
            "kaikki_rows": kaikki[code]["rows"],
            "kaikki_keys": len(kk),
            "kaikki_src_codes": len(kaikki[code]["src_codes"]),
            "panlex_rows": panlex_rows[code],
            "panlex_keys": len(pl),
            "panlex_src_codes": len(panlex_per_src[code]),
            "overlap": len(overlap),
            "kaikki_only": len(only_kk),
            "panlex_only": len(only_pl),
            "union": len(union),
            "panlex_add_pct": add_pct,
            "top_panlex_sources": [[s, n] for s, n in top_adds],
        })

    out = ROOT / "local" / "panlex_vs_kaikki.json"
    with open(out, "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2, ensure_ascii=False)
    print(f"\n\nWrote: {out}")


if __name__ == "__main__":
    main()
