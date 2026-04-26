#!/usr/bin/env python3
"""
SPIKE — measure MARISA trie size + lookup speed against the current SQLite
B-tree for target-de's (source_lang, written) keys.

Goal: confirm/refute whether moving from SQLite to FST-backed storage gives
the projected ~10x compression on the index column. If yes, FST + paged
data file is worth the architectural switch. If no, stick with interning +
page batching.

Comparisons:
  1. raw bytes (sorted text, one key per line) — uncompressed baseline
  2. raw bytes + zstd-19 — what plain compression gets you
  3. SQLite-stored as (TEXT PRIMARY KEY) WITHOUT ROWID — apples to today
  4. MARISA trie — the FST candidate
"""
from __future__ import annotations

import os
import random
import sqlite3
import sys
import time
from pathlib import Path

import marisa_trie
import zstandard as zstd

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ROOT = Path(__file__).resolve().parent.parent
INPUT_DB = ROOT / "local" / "target-build-hybrid" / "de" / "glosses.sqlite"
OUT_DIR = ROOT / "local" / "fst-spike"
OUT_DIR.mkdir(parents=True, exist_ok=True)


def main():
    print(f"Loading distinct (source_lang, written) keys from {INPUT_DB}...",
          flush=True)
    db = sqlite3.connect(str(INPUT_DB))
    db.execute("PRAGMA query_only = ON")
    t0 = time.time()
    rows = db.execute(
        "SELECT DISTINCT source_lang, written FROM glosses "
        "ORDER BY source_lang, written"
    ).fetchall()
    db.close()
    print(f"  {len(rows):,} distinct keys in {time.time()-t0:.1f}s",
          flush=True)

    # Compose into single-string keys ("ja\x00本", etc.). NUL is the standard
    # column separator for FST keys — never appears in text.
    keys: list[str] = [f"{sl}\x00{w}" for sl, w in rows]
    raw_bytes = b"\n".join(k.encode("utf-8") for k in keys)
    n = len(keys)

    print(f"\n=== Size comparison: indexing {n:,} keys ===\n")

    # Baseline 1: raw text, one per line
    raw_path = OUT_DIR / "keys_raw.txt"
    raw_path.write_bytes(raw_bytes)
    raw_size = raw_path.stat().st_size
    print(f"  raw text:                 {raw_size:>12,} bytes  ({raw_size/1e6:>6.1f} MB)")

    # Baseline 2: raw text + zstd-19
    cctx = zstd.ZstdCompressor(level=19)
    zstd_path = OUT_DIR / "keys_raw.txt.zst"
    zstd_path.write_bytes(cctx.compress(raw_bytes))
    zstd_size = zstd_path.stat().st_size
    print(f"  raw + zstd-19:            {zstd_size:>12,} bytes  ({zstd_size/1e6:>6.1f} MB)  "
          f"({zstd_size*100/raw_size:.1f}% of raw)")

    # Baseline 3: SQLite WITHOUT ROWID PK index (closest to current storage)
    sqlite_path = OUT_DIR / "keys_sqlite.db"
    if sqlite_path.exists():
        sqlite_path.unlink()
    sdb = sqlite3.connect(str(sqlite_path))
    sdb.execute("PRAGMA journal_mode = OFF")
    sdb.execute("CREATE TABLE k (key TEXT PRIMARY KEY) WITHOUT ROWID")
    sdb.executemany("INSERT INTO k(key) VALUES (?)", ((k,) for k in keys))
    sdb.commit()
    sdb.execute("VACUUM")
    sdb.close()
    sqlite_size = sqlite_path.stat().st_size
    print(f"  SQLite WITHOUT ROWID:     {sqlite_size:>12,} bytes  ({sqlite_size/1e6:>6.1f} MB)  "
          f"({sqlite_size*100/raw_size:.1f}% of raw)")

    # Candidate: MARISA trie (FST family — succinct, mmap-friendly)
    print(f"\n  Building MARISA trie...", flush=True)
    t0 = time.time()
    trie = marisa_trie.Trie(keys)
    marisa_path = OUT_DIR / "keys.marisa"
    trie.save(str(marisa_path))
    build_ms = (time.time() - t0) * 1000
    marisa_size = marisa_path.stat().st_size
    print(f"  MARISA trie:              {marisa_size:>12,} bytes  ({marisa_size/1e6:>6.1f} MB)  "
          f"({marisa_size*100/raw_size:.1f}% of raw)")
    print(f"    built in {build_ms:.0f}ms")

    # Candidate: MARISA trie compressed with zstd (does it help further?)
    marisa_zstd_path = OUT_DIR / "keys.marisa.zst"
    marisa_zstd_path.write_bytes(cctx.compress(marisa_path.read_bytes()))
    marisa_zstd_size = marisa_zstd_path.stat().st_size
    print(f"  MARISA + zstd-19:         {marisa_zstd_size:>12,} bytes  ({marisa_zstd_size/1e6:>6.1f} MB)  "
          f"({marisa_zstd_size*100/raw_size:.1f}% of raw)")

    # Lookup latency
    print(f"\n=== Lookup latency ===\n")
    sample = random.sample(keys, 1000)

    # SQLite probe
    sdb = sqlite3.connect(str(sqlite_path))
    t0 = time.perf_counter()
    for k in sample:
        _ = sdb.execute("SELECT key FROM k WHERE key=?", (k,)).fetchone()
    sqlite_ms = (time.perf_counter() - t0) * 1000
    sdb.close()
    print(f"  SQLite WHERE key=? × 1000:  {sqlite_ms:>7.1f}ms total  "
          f"({sqlite_ms/1000*1000:.1f} us/lookup)")

    # MARISA lookup (load from disk first to mimic mmap-load cold start)
    trie2 = marisa_trie.Trie()
    t0 = time.perf_counter()
    trie2.load(str(marisa_path))
    load_ms = (time.perf_counter() - t0) * 1000
    print(f"  MARISA load from disk:      {load_ms:>7.1f}ms  ({marisa_size/1e6:.1f} MB)")
    t0 = time.perf_counter()
    for k in sample:
        _ = trie2.get(k)
    marisa_ms = (time.perf_counter() - t0) * 1000
    print(f"  MARISA get(key) × 1000:     {marisa_ms:>7.1f}ms total  "
          f"({marisa_ms/1000*1000:.1f} us/lookup)")

    # Summary
    print(f"\n=== Summary ===\n")
    print(f"  Indexing {n:,} distinct keys.")
    print(f"  SQLite WITHOUT ROWID:  {sqlite_size/1e6:.1f} MB  (today's storage)")
    print(f"  MARISA trie:           {marisa_size/1e6:.1f} MB  "
          f"({sqlite_size/marisa_size:.1f}x smaller than SQLite)")
    print()
    if marisa_size < sqlite_size:
        savings = sqlite_size - marisa_size
        pct = savings * 100 / sqlite_size
        print(f"  Switching the index alone would save {savings/1e6:.1f} MB ({pct:.1f}%)")

    # Realistic projection: what would the full target-de pack look like?
    # Today: 425 MB total
    # Index column (written + source_lang as PK index): unknown but probably
    # somewhere around the SQLite-PK number we just measured.
    print()
    print(f"  Naive projection: if we replaced the SQLite PK with a MARISA "
          f"trie\n  pointing into a flat data file:")
    print(f"    Index savings: ~{(sqlite_size - marisa_size)/1e6:.0f} MB on the index alone")
    print(f"    Plus we'd lose all SQLite B-tree page overhead on the data side.")


if __name__ == "__main__":
    main()
