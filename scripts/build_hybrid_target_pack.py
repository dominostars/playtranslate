#!/usr/bin/env python3
"""
Build a HYBRID target gloss pack: existing kaikki/JMdict/CFDICT pack + PanLex.

Starts from a pack already produced by `build_target_pack.py` (or whatever
process generated it) and layers PanLex rows on top. PanLex rows share the
schema and use `source='panlex'`, so the reader doesn't change.

Sense ordinal collisions
------------------------
The PK is (source_lang, written, reading, sense_ord). PanLex emits all rows
with reading='', and its local sense_ord starts at 0 per (source_lang,
written). Kaikki/JMdict already populated some of those keys (with their own
sense numbering). To avoid PK collisions and to preserve the kaikki sense
structure ahead of PanLex's flatter aggregation, we shift each PanLex row's
sense_ord up by (max_kaikki_sense_ord_for_this_key + 1). Where kaikki has
nothing for the key, PanLex starts at 0.

Run:
    python scripts/build_hybrid_target_pack.py \\
        --target de \\
        --kaikki-pack local/target-build/de/out/glosses.sqlite \\
        --output local/target-build-hybrid
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import shutil
import sqlite3
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

# ── Garbage filter for PanLex source headwords ────────────────────────
# PanLex aggregates from many upstream dictionaries, some of which are
# spreadsheet conversions that leak Excel error markers and stray
# punctuation into the headword column. These are unambiguous noise.
_EXCEL_ERROR = re.compile(r"^#[A-Z][A-Z0-9/]*[!?]?$")
_PUNCT_ONLY = re.compile(r"^\W+$", re.UNICODE)

def is_garbage_written(text: str) -> bool:
    s = text.strip()
    if not s:
        return True
    if _EXCEL_ERROR.match(s):
        return True
    if _PUNCT_ONLY.match(s):
        return True
    return False

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ROOT = Path(__file__).resolve().parent.parent
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

SOFT_FLOOR_SHARED = 50  # skip (src, tgt) pairs with fewer shared meanings


def load_meaning_to_writtens(path: Path) -> dict[int, set[str]]:
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


def load_meaning_to_glosses(path: Path) -> dict[int, list[str]]:
    out: dict[int, list[str]] = defaultdict(list)
    with open(path, encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            try:
                m = int(row["meaning"])
            except (TypeError, ValueError):
                continue
            t = (row.get("txt") or "").strip()
            if t:
                out[m].append(t)
    return out


def app_lang_for(panlex_code: str) -> str:
    return PANLEX_TO_APP.get(panlex_code, panlex_code)


def sha256(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def build_hybrid(target_app: str, kaikki_pack: Path, output_dir: Path,
                 pack_version: int = 1) -> dict:
    if target_app not in APP_TO_PANLEX:
        raise SystemExit(f"No PanLex code for app target '{target_app}'")
    plx_target = APP_TO_PANLEX[target_app]
    target_tsv = PANLEX_DIR / f"{plx_target}.tsv"
    if not target_tsv.exists():
        raise SystemExit(f"Missing PanLex target TSV: {target_tsv}")
    if not kaikki_pack.exists():
        raise SystemExit(f"Missing kaikki pack: {kaikki_pack}")

    out_subdir = output_dir / target_app
    out_subdir.mkdir(parents=True, exist_ok=True)
    out_db = out_subdir / "glosses.sqlite"
    if out_db.exists():
        out_db.unlink()

    # ── 1. Copy kaikki pack as starting point ─────────────────────────
    print(f"Copying kaikki pack {kaikki_pack} → {out_db}", flush=True)
    shutil.copyfile(str(kaikki_pack), str(out_db))

    conn = sqlite3.connect(str(out_db))
    conn.execute("PRAGMA journal_mode=OFF")
    conn.execute("PRAGMA synchronous=OFF")

    # Pre-existing source breakdown
    pre_rows = conn.execute("SELECT COUNT(*) FROM glosses").fetchone()[0]
    pre_per_src = {r[0]: r[1] for r in conn.execute(
        "SELECT source, COUNT(*) FROM glosses GROUP BY source"
    )}
    print(f"  kaikki rows: {pre_rows:,}  per-source: {pre_per_src}", flush=True)

    # ── 2. Index which (source_lang, written) keys kaikki covers ─────
    # PanLex rows are dropped wholesale for any key kaikki touches; we
    # only need a presence check, not the gloss strings themselves.
    print("Indexing kaikki keys...", flush=True)
    kaikki_glosses_per_key: set[tuple[str, str]] = set()
    for sl, w, g in conn.execute("SELECT source_lang, written, glosses FROM glosses"):
        if g and g.strip():
            kaikki_glosses_per_key.add((sl, w))
    print(f"  {len(kaikki_glosses_per_key):,} keys with kaikki coverage",
          flush=True)

    # ── 3. Load target meaning index from PanLex ──────────────────────
    print(f"Loading PanLex target {plx_target}.tsv...", flush=True)
    target_idx = load_meaning_to_glosses(target_tsv)
    target_meanings = set(target_idx)
    print(f"  {len(target_meanings):,} meanings", flush=True)

    # ── 4. Stream PanLex sources, INSERT shifted rows ─────────────────
    source_files = sorted(p for p in PANLEX_DIR.glob("*.tsv")
                          if p.stat().st_size > 100_000)
    print(f"Streaming {len(source_files)} PanLex sources...", flush=True)

    panlex_added = 0
    panlex_per_src: dict[str, int] = defaultdict(int)
    rows_dropped_garbage = 0
    rows_dropped_redundant = 0
    row_buffer: list = []
    BUFFER_FLUSH = 500_000
    # PanLex rows ship empty examples/misc — its CC0 dump is gloss-only
    # (no example sentences, no editorial tags). The kaikki pack we copy
    # from already populates these columns where the data exists.
    SQL = (
        "INSERT OR IGNORE INTO glosses "
        "(source_lang, written, reading, sense_ord, pos, glosses, source, "
        " examples, example_trans, misc) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, '', '', '')"
    )

    for i, sf in enumerate(source_files):
        plx_code = sf.stem
        if plx_code == plx_target:
            continue  # don't pair lang with itself
        try:
            src_idx = load_meaning_to_writtens(sf)
        except Exception as e:
            print(f"  [{i+1}/{len(source_files)}] {plx_code}: load failed: {e}")
            continue

        shared = set(src_idx) & target_meanings
        if len(shared) < SOFT_FLOOR_SHARED:
            continue

        src_app = app_lang_for(plx_code)
        # Group source headwords by their meanings
        written_to_meanings: dict[str, list[int]] = defaultdict(list)
        for m in shared:
            for w in src_idx[m]:
                written_to_meanings[w].append(m)

        # For each (src_app, written): collapse all PanLex glosses across
        # every contributing meaning_id into a single deduplicated set,
        # then drop the entire row if kaikki already covers the key.
        for written, meanings in written_to_meanings.items():
            if is_garbage_written(written):
                rows_dropped_garbage += 1
                continue

            panlex_glosses: set[str] = set()
            for m in meanings:
                for g in target_idx[m]:
                    s = g.strip()
                    if s:
                        panlex_glosses.add(s)
            if not panlex_glosses:
                continue

            # PanLex aggregates many small upstream dictionaries of variable
            # quality. When kaikki/Wiktionary already provides ANY gloss for
            # this key, we drop the PanLex row entirely — kaikki tends to be
            # cleaner and the runtime renders senses target-driven, so PanLex
            # extras would only show up as a noisier additional sense block.
            # PanLex still wins for keys kaikki doesn't cover.
            if (src_app, written) in kaikki_glosses_per_key:
                rows_dropped_redundant += 1
                continue

            # Cap at 8 to match what build_target_pack.py + build_panlex_target_pack.py
            # already enforce. Sort for deterministic output.
            final = sorted(panlex_glosses)[:8]
            gloss_str = "\t".join(final)
            row_buffer.append((
                src_app, written, "", 0, "",
                gloss_str, "panlex",
            ))
            panlex_per_src[src_app] += 1
            panlex_added += 1

        if (i + 1) % 50 == 0 or (i + 1) == len(source_files):
            print(f"  [{i+1}/{len(source_files)}] {plx_code} "
                  f"(panlex rows so far: {panlex_added:,})", flush=True)

        if len(row_buffer) >= BUFFER_FLUSH:
            conn.executemany(SQL, row_buffer)
            row_buffer = []

    if row_buffer:
        conn.executemany(SQL, row_buffer)
    conn.commit()

    # ── 5. Re-stat, vacuum, manifest, zip ─────────────────────────────
    final_rows = conn.execute("SELECT COUNT(*) FROM glosses").fetchone()[0]
    final_per_src = {r[0]: r[1] for r in conn.execute(
        "SELECT source, COUNT(*) FROM glosses GROUP BY source"
    )}
    covers = sorted({r[0] for r in conn.execute(
        "SELECT DISTINCT source_lang FROM glosses"
    )})
    conn.close()

    print(f"\nVacuuming...", flush=True)
    c2 = sqlite3.connect(str(out_db))
    c2.execute("VACUUM")
    c2.close()

    db_size = out_db.stat().st_size
    db_sha = sha256(out_db)

    manifest = {
        "langId": f"target-{target_app}",
        "schemaVersion": 1,
        "packVersion": pack_version,
        "appMinVersion": 0,
        "files": [{"path": "glosses.sqlite", "size": db_size, "sha256": db_sha}],
        "totalSize": db_size,
        # Combined license set; caller can override if they want exact attribution
        "licenses": [
            {"component": "JMdict", "license": "CC-BY-SA-4.0",
             "attribution": "© EDRDG, https://www.edrdg.org/jmdict/edict_doc.html"},
            {"component": "Wiktionary", "license": "CC-BY-SA-3.0",
             "attribution": "© Wiktionary contributors"},
            {"component": "PanLex", "license": "CC0-1.0",
             "attribution": "(c) PanLex (CC0 1.0 Universal), https://panlex.org/"},
        ],
        "target": target_app,
        "covers": covers,
        "sourceCounts": final_per_src,
        "totalRows": final_rows,
    }
    manifest_path = out_subdir / "manifest.json"
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)

    zip_path = out_subdir / f"{target_app}.zip"
    if zip_path.exists():
        zip_path.unlink()
    with zipfile.ZipFile(str(zip_path), "w", zipfile.ZIP_DEFLATED) as zf:
        zf.write(str(out_db), "glosses.sqlite")
        zf.write(str(manifest_path), "manifest.json")
    zip_size = zip_path.stat().st_size
    zip_sha = sha256(zip_path)

    summary = {
        "target": target_app,
        "kaikkiInputPath": str(kaikki_pack),
        "kaikkiRows": pre_rows,
        "kaikkiPerSource": pre_per_src,
        "panlexRowsAdded": panlex_added,
        "panlexPerSource": dict(panlex_per_src),
        "panlexRowsDroppedGarbage": rows_dropped_garbage,
        "panlexRowsDroppedRedundant": rows_dropped_redundant,
        "totalRows": final_rows,
        "totalPerSource": final_per_src,
        "covers": len(covers),
        "dbSize": db_size,
        "dbSha256": db_sha,
        "zipPath": str(zip_path),
        "zipSize": zip_size,
        "zipSha256": zip_sha,
    }
    print(f"\n=== HYBRID target-{target_app} BUILT ===")
    print(f"  kaikki rows:           {pre_rows:>10,}  ({pre_per_src})")
    print(f"  panlex rows added:     {panlex_added:>10,}")
    print(f"  panlex dropped garbage:{rows_dropped_garbage:>10,}")
    print(f"  panlex dropped (kaikki already covers key): {rows_dropped_redundant:>10,}")
    print(f"  total rows:            {final_rows:>10,}  ({final_per_src})")
    print(f"  covers:                {len(covers):>10,} src codes")
    print(f"  db on-disk:            {db_size:>10,} bytes ({db_size/1e6:.1f} MB)")
    print(f"  zip download:          {zip_size:>10,} bytes ({zip_size/1e6:.1f} MB)")
    return summary


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--target", required=True, help="app code (e.g. de, ru, pl)")
    ap.add_argument("--kaikki-pack", required=True,
                    help="path to existing kaikki-built glosses.sqlite")
    ap.add_argument("--output", required=True, help="output directory")
    ap.add_argument("--pack-version", type=int, default=1)
    args = ap.parse_args()

    summary = build_hybrid(args.target, Path(args.kaikki_pack),
                           Path(args.output), args.pack_version)

    out = Path(args.output) / f"{args.target}_HYBRID_SUMMARY.json"
    with open(out, "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2, ensure_ascii=False)
    print(f"\nSummary: {out}")


if __name__ == "__main__":
    main()
