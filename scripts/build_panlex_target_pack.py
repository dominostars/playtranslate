#!/usr/bin/env python3
"""
Build target gloss packs from the PanLex CC0 snapshot
(via the cointegrated/panlex-meanings HuggingFace mirror).

For each target language in --targets, joins every available source
language TSV against the target on `meaning` (PanLex's concept ID) and
emits one row per (source_lang, source_headword, sense_ord) into a
glosses.sqlite that matches the existing target-pack schema:

    PRIMARY KEY (source_lang, written, reading, sense_ord)

PanLex meanings are concept-level — there's no Wiktionary-style sense
number. Each (source_lang, written) pair gets sense_ord 0..N-1, one per
distinct meaning_id the headword maps to. All glosses for a single
meaning are tab-joined into one `glosses` cell.

Memory model: load 8 small target indices (~30 MB each), then stream
each source TSV one at a time, intersecting with all targets in one
pass so we touch each source file exactly once.

Soft floor: skip (source_lang, target) pairs with shared_meanings < 50
to drop near-empty placeholder languages.
"""
from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import json
import os
import sqlite3
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ROOT = Path(__file__).resolve().parent.parent
PANLEX_DIR = ROOT / "local" / "panlex-spike"

# 2-letter app code → PanLex ISO 639-3
# All app source-pack codes plus the 8 new target codes the user requested.
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
ROW_THRESHOLD = 5_000   # skip building a pack with fewer total rows

CREATE_SQL = """
CREATE TABLE glosses (
    source_lang    TEXT NOT NULL,
    written        TEXT NOT NULL,
    reading        TEXT,
    sense_ord      INTEGER NOT NULL,
    pos            TEXT NOT NULL DEFAULT '',
    glosses        TEXT NOT NULL,
    source         TEXT NOT NULL,
    -- PanLex doesn't ship example sentences or editorial misc tags,
    -- but the FST builder + reader expect every glosses-table row to
    -- carry these columns alongside the kaikki/JMdict fields. Default
    -- to empty strings so PanLex packs round-trip through the same
    -- BuildTargetPack pipeline as the Wiktionary-derived ones.
    examples       TEXT NOT NULL DEFAULT '',
    example_trans  TEXT NOT NULL DEFAULT '',
    misc           TEXT NOT NULL DEFAULT '',
    schema_version INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (source_lang, written, reading, sense_ord)
) WITHOUT ROWID;
"""


def load_meaning_to_glosses(path: Path) -> dict[int, list[str]]:
    """meaning_id -> list of expression text strings."""
    out: dict[int, list[str]] = defaultdict(list)
    with open(path, encoding="utf-8") as f:
        reader = csv.DictReader(f, delimiter="\t")
        for row in reader:
            try:
                m = int(row["meaning"])
            except (TypeError, ValueError):
                continue
            t = (row.get("txt") or "").strip()
            if t:
                out[m].append(t)
    return out


def app_lang_for(panlex_code: str) -> str:
    """Map 3-letter PanLex code to app's 2-letter code if known, else
    keep the 3-letter code (still a valid string for source_lang)."""
    return PANLEX_TO_APP.get(panlex_code, panlex_code)


def sha256(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def build(targets_app: list[str], output_dir: Path, pack_version: int = 1) -> dict:
    output_dir.mkdir(parents=True, exist_ok=True)

    # ── Load 8 target indices once ────────────────────────────────────
    print(f"Loading {len(targets_app)} target indices...", flush=True)
    target_indices: dict[str, dict[int, list[str]]] = {}
    for app_code in targets_app:
        plx = APP_TO_PANLEX.get(app_code)
        if not plx:
            print(f"  WARN: no PanLex code for app target '{app_code}', skipping")
            continue
        path = PANLEX_DIR / f"{plx}.tsv"
        if not path.exists():
            print(f"  WARN: missing {path}")
            continue
        idx = load_meaning_to_glosses(path)
        target_indices[app_code] = idx
        print(f"  {app_code} ({plx}): {len(idx):,} meanings, "
              f"{sum(len(v) for v in idx.values()):,} glosses",
              flush=True)

    # ── Open one SQLite per target ────────────────────────────────────
    db_paths: dict[str, Path] = {}
    conns: dict[str, sqlite3.Connection] = {}
    row_buffers: dict[str, list] = {t: [] for t in target_indices}

    for app_code in target_indices:
        db_path = output_dir / app_code / "glosses.sqlite"
        db_path.parent.mkdir(parents=True, exist_ok=True)
        if db_path.exists():
            db_path.unlink()
        c = sqlite3.connect(str(db_path))
        c.execute("PRAGMA journal_mode=OFF")
        c.execute("PRAGMA synchronous=OFF")
        c.execute(CREATE_SQL)
        conns[app_code] = c
        db_paths[app_code] = db_path

    # ── Stream every source TSV, build all packs simultaneously ──────
    source_files = sorted(p for p in PANLEX_DIR.glob("*.tsv")
                          if p.stat().st_size > 100_000)
    print(f"\nProcessing {len(source_files)} source files...", flush=True)

    pair_stats: dict[tuple[str, str], dict] = {}  # (src_app, tgt_app) -> stats

    for i, sf in enumerate(source_files):
        plx_code = sf.stem
        # Skip if this file is itself a target (don't pair lang with itself)
        # but still useful as a SOURCE for OTHER targets.
        src_app = app_lang_for(plx_code)
        try:
            src_idx = load_meaning_to_glosses(sf)
        except Exception as e:
            print(f"  [{i+1}/{len(source_files)}] {plx_code}: load failed: {e}")
            continue

        progress_emitted = False
        for tgt_app, tgt_idx in target_indices.items():
            # Don't pair a language with itself
            if APP_TO_PANLEX.get(tgt_app) == plx_code:
                continue

            shared_meanings = set(src_idx) & set(tgt_idx)
            if len(shared_meanings) < SOFT_FLOOR_SHARED:
                continue

            rows_emitted = 0
            # For each headword in the source language, group by which
            # meanings it appears in (for sense_ord assignment).
            written_to_meanings: dict[str, list[int]] = defaultdict(list)
            for m in shared_meanings:
                # Dedup headwords within a single meaning before processing
                for w in set(src_idx[m]):
                    written_to_meanings[w].append(m)

            for written, meanings in written_to_meanings.items():
                for sense_ord, m in enumerate(sorted(meanings)):
                    glosses = sorted(set(tgt_idx[m]))
                    if not glosses:
                        continue
                    gloss_str = "\t".join(glosses[:8])  # cap at 8 glosses/sense
                    row_buffers[tgt_app].append((
                        src_app, written, "", sense_ord, "",
                        gloss_str, "panlex",
                    ))
                    rows_emitted += 1

            pair_stats[(src_app, tgt_app)] = {
                "shared": len(shared_meanings),
                "rows": rows_emitted,
            }

            if not progress_emitted:
                print(f"  [{i+1}/{len(source_files)}] {plx_code}", flush=True)
                progress_emitted = True

            # Flush buffer per target every 500K rows to keep memory bounded
            if len(row_buffers[tgt_app]) >= 500_000:
                conns[tgt_app].executemany(
                    "INSERT OR IGNORE INTO glosses "
                    "(source_lang, written, reading, sense_ord, pos, glosses, source) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    row_buffers[tgt_app],
                )
                row_buffers[tgt_app] = []

    # Final flush
    for tgt_app, buf in row_buffers.items():
        if buf:
            conns[tgt_app].executemany(
                "INSERT OR IGNORE INTO glosses "
                "(source_lang, written, reading, sense_ord, pos, glosses, source) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                buf,
            )
        conns[tgt_app].commit()

    # ── Per-target stats, manifest, zip ───────────────────────────────
    summary = []
    for tgt_app, c in conns.items():
        cur = c.execute("SELECT COUNT(*) FROM glosses")
        total_rows = cur.fetchone()[0]
        cur = c.execute("SELECT DISTINCT source_lang FROM glosses")
        covers = sorted({r[0] for r in cur.fetchall()})
        c.close()

        if total_rows < ROW_THRESHOLD:
            print(f"\n{tgt_app}: SKIP ({total_rows:,} rows < {ROW_THRESHOLD:,})")
            summary.append({"code": tgt_app, "rows": total_rows, "skipped": True})
            continue

        db_path = db_paths[tgt_app]
        # Vacuum to compact
        c2 = sqlite3.connect(str(db_path))
        c2.execute("VACUUM")
        c2.close()

        # Per-source row counts
        c3 = sqlite3.connect(str(db_path))
        per_src = {r[0]: r[1] for r in c3.execute(
            "SELECT source_lang, COUNT(*) FROM glosses GROUP BY source_lang"
        )}
        c3.close()

        # Manifest
        db_size = db_path.stat().st_size
        db_sha = sha256(db_path)
        manifest = {
            "langId": f"target-{tgt_app}",
            "schemaVersion": 1,
            "packVersion": pack_version,
            "appMinVersion": 0,
            "files": [{"path": "glosses.sqlite",
                       "size": db_size, "sha256": db_sha}],
            "totalSize": db_size,
            "licenses": [],
            "target": tgt_app,
            "covers": covers,
            "sourceCounts": {"panlex": total_rows},
            "totalRows": total_rows,
            "perSourceRows": per_src,
        }
        manifest_path = db_path.parent / "manifest.json"
        with open(manifest_path, "w", encoding="utf-8") as f:
            json.dump(manifest, f, indent=2, ensure_ascii=False)

        # Zip
        zip_path = db_path.parent / f"{tgt_app}.zip"
        if zip_path.exists():
            zip_path.unlink()
        with zipfile.ZipFile(str(zip_path), "w", zipfile.ZIP_DEFLATED) as zf:
            zf.write(str(db_path), "glosses.sqlite")
            zf.write(str(manifest_path), "manifest.json")
        zip_size = zip_path.stat().st_size
        zip_sha = sha256(zip_path)

        summary.append({
            "code": tgt_app,
            "rows": total_rows,
            "covers_count": len(covers),
            "zipPath": str(zip_path),
            "size": zip_size,
            "sha256": zip_sha,
            "perSourceRows": per_src,
        })
        print(f"\n{tgt_app}: BUILT  rows={total_rows:>9,}  "
              f"covers={len(covers):>3}  zip={zip_size/1e6:.1f} MB", flush=True)

    return {"built": [s for s in summary if not s.get("skipped")],
            "skipped": [s for s in summary if s.get("skipped")],
            "pair_stats": {f"{s}->{t}": v for (s, t), v in pair_stats.items()}}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--targets", required=True,
                    help="comma-separated app codes (e.g. ar,hi,he,fa)")
    ap.add_argument("--output", required=True,
                    help="output directory; per-target subdirs created inside")
    ap.add_argument("--pack-version", type=int, default=1)
    args = ap.parse_args()

    targets = [t.strip() for t in args.targets.split(",") if t.strip()]
    result = build(targets, Path(args.output), args.pack_version)

    # Write summary
    out = Path(args.output) / "PANLEX_BUILD_SUMMARY.json"
    with open(out, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2, ensure_ascii=False)
    print(f"\nSummary: {out}")


if __name__ == "__main__":
    main()
