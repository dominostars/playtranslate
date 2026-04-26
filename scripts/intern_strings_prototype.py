#!/usr/bin/env python3
"""
PROTOTYPE — convert an existing target-pack glosses.sqlite into a
string-interned form. Measures real on-disk savings on the actual data.

Schema changes vs the live target-pack format:
  - New `strings(id INTEGER PRIMARY KEY, text TEXT NOT NULL UNIQUE)` table.
  - `glosses` column TEXT → BLOB of LEB128-packed IDs into `strings`.
  - `source` column TEXT → INTEGER (ID into strings) — only ~4 distinct
    values across all packs, so this is essentially a 1-byte enum.
  - `pos` column TEXT → INTEGER (ID into strings).
  - `schema_version` column DROPPED — always 1, redundant with manifest.
  - PK / source_lang / written / reading / sense_ord unchanged so existing
    indexed lookups behave identically.

Reader-side (not touched here, prototype only):
  - On open, preload `strings` into an in-memory id→text Map.
  - On row read, decode glosses BLOB into IDs, map to text via the Map.
  - source/pos: same Map lookup.

Run:
  python scripts/intern_strings_prototype.py \\
      --input local/target-build-hybrid/de/glosses.sqlite \\
      --output local/target-build-hybrid/de/glosses_interned.sqlite
"""
from __future__ import annotations

import argparse
import sqlite3
import sys
import time
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


def varint_encode(n: int) -> bytes:
    """Unsigned LEB128 (varint). 1 byte for n<128, 2 for n<16384, 3 for n<2M."""
    if n < 0:
        raise ValueError("negative")
    out = bytearray()
    while n >= 0x80:
        out.append((n & 0x7F) | 0x80)
        n >>= 7
    out.append(n & 0x7F)
    return bytes(out)


def varints_decode(buf: bytes) -> list[int]:
    out = []
    n = 0
    shift = 0
    for b in buf:
        n |= (b & 0x7F) << shift
        if not (b & 0x80):
            out.append(n)
            n = 0
            shift = 0
        else:
            shift += 7
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--output", required=True)
    args = ap.parse_args()

    src = Path(args.input)
    dst = Path(args.output)
    if not src.exists():
        sys.exit(f"Missing input: {src}")
    if dst.exists():
        dst.unlink()

    src_size = src.stat().st_size
    print(f"Input:  {src} ({src_size:,} bytes / {src_size/1e6:.1f} MB)",
          flush=True)

    # ── Pass 1: collect all unique strings (glosses + pos + source) ───
    print("\nPass 1: collecting unique strings...", flush=True)
    t0 = time.time()
    in_db = sqlite3.connect(str(src))
    in_db.execute("PRAGMA query_only = ON")

    strings_set: set[str] = set()
    n_rows = 0
    for pos, source, glosses in in_db.execute(
        "SELECT pos, source, glosses FROM glosses"
    ):
        n_rows += 1
        if pos:
            strings_set.add(pos)
        if source:
            strings_set.add(source)
        if glosses:
            for g in glosses.split("\t"):
                if g:
                    strings_set.add(g)
    print(f"  {n_rows:,} rows scanned, {len(strings_set):,} unique strings, "
          f"{time.time()-t0:.1f}s", flush=True)

    # Sort by frequency? For varint-packing, smaller IDs encode in fewer bytes,
    # so frequent strings should get small IDs. But computing frequency needs
    # another pass. Approximation: sort by string length descending — shortest
    # last (often most frequent in natural language). Skip for prototype.
    strings_list = sorted(strings_set)
    string_to_id = {s: i + 1 for i, s in enumerate(strings_list)}  # IDs start at 1

    # ── Pass 2: write interned output ─────────────────────────────────
    print(f"\nPass 2: writing interned database...", flush=True)
    t0 = time.time()
    out_db = sqlite3.connect(str(dst))
    out_db.execute("PRAGMA journal_mode = OFF")
    out_db.execute("PRAGMA synchronous = OFF")
    out_db.execute("""
        CREATE TABLE strings (
            id   INTEGER PRIMARY KEY,
            text TEXT NOT NULL UNIQUE
        )
    """)
    out_db.execute("""
        CREATE TABLE glosses (
            source_lang  TEXT NOT NULL,
            written      TEXT NOT NULL,
            reading      TEXT,
            sense_ord    INTEGER NOT NULL,
            pos          INTEGER NOT NULL DEFAULT 0,
            glosses      BLOB NOT NULL,
            source       INTEGER NOT NULL,
            PRIMARY KEY (source_lang, written, reading, sense_ord)
        ) WITHOUT ROWID
    """)

    # Insert strings table
    out_db.executemany(
        "INSERT INTO strings(id, text) VALUES (?, ?)",
        ((sid, s) for s, sid in string_to_id.items())
    )

    # Stream rows, encode, insert
    INSERT = ("INSERT INTO glosses "
              "(source_lang, written, reading, sense_ord, pos, glosses, source) "
              "VALUES (?, ?, ?, ?, ?, ?, ?)")
    buf: list = []
    BUF_FLUSH = 100_000
    for sl, w, r, ord_, pos, glosses, source in in_db.execute(
        "SELECT source_lang, written, reading, sense_ord, pos, glosses, source "
        "FROM glosses"
    ):
        # Encode glosses as varint BLOB
        gids = []
        if glosses:
            for g in glosses.split("\t"):
                if g and g in string_to_id:
                    gids.append(string_to_id[g])
        if not gids:
            continue  # shouldn't happen in real packs
        gloss_blob = b"".join(varint_encode(i) for i in gids)
        pos_id = string_to_id.get(pos, 0) if pos else 0
        source_id = string_to_id[source] if source else 0
        buf.append((sl, w, r, ord_, pos_id, gloss_blob, source_id))
        if len(buf) >= BUF_FLUSH:
            out_db.executemany(INSERT, buf)
            buf = []
    if buf:
        out_db.executemany(INSERT, buf)
    out_db.commit()
    out_db.execute("VACUUM")
    out_db.close()
    in_db.close()

    dst_size = dst.stat().st_size
    print(f"  wrote {dst_size:,} bytes / {dst_size/1e6:.1f} MB, "
          f"{time.time()-t0:.1f}s", flush=True)

    # ── Smoke test ────────────────────────────────────────────────────
    print(f"\n=== Size comparison ===", flush=True)
    print(f"  before: {src_size:>12,} bytes ({src_size/1e6:.1f} MB)")
    print(f"  after:  {dst_size:>12,} bytes ({dst_size/1e6:.1f} MB)")
    print(f"  saved:  {src_size-dst_size:>12,} bytes "
          f"({(src_size-dst_size)*100/src_size:.1f}%)")

    print(f"\n=== Smoke test: ja:本 lookup latency ===", flush=True)
    out = sqlite3.connect(str(dst))
    # Preload strings into dict (this is what the app reader would do)
    t0 = time.time()
    id_to_text: dict[int, str] = {}
    for sid, txt in out.execute("SELECT id, text FROM strings"):
        id_to_text[sid] = txt
    preload_ms = (time.time() - t0) * 1000
    preload_mem = sum(len(t) for t in id_to_text.values()) + len(id_to_text) * 32
    print(f"  string table preload: {preload_ms:.1f}ms, "
          f"{len(id_to_text):,} entries, ~{preload_mem/1e6:.1f} MB resident",
          flush=True)

    # Tap-style lookup: query + materialize
    t0 = time.time()
    rows = out.execute(
        "SELECT source_lang, written, reading, sense_ord, pos, glosses, source "
        "FROM glosses WHERE source_lang='ja' AND written=? ORDER BY reading, sense_ord",
        ("本",)
    ).fetchall()
    materialized = []
    for sl, w, r, ord_, pos_id, gblob, source_id in rows:
        gids = varints_decode(gblob)
        gtexts = [id_to_text[i] for i in gids]
        pos_text = id_to_text.get(pos_id, "") if pos_id else ""
        source_text = id_to_text.get(source_id, "") if source_id else ""
        materialized.append((sl, w, r, ord_, pos_text, gtexts, source_text))
    tap_ms = (time.time() - t0) * 1000
    print(f"  ja:本 lookup ({len(materialized)} rows): {tap_ms:.2f}ms",
          flush=True)

    # Drag-style: many queries in a row
    t0 = time.time()
    sample_words = [r[0] for r in out.execute(
        "SELECT DISTINCT written FROM glosses WHERE source_lang='ja' LIMIT 100"
    ).fetchall()]
    for w in sample_words:
        rows = out.execute(
            "SELECT pos, glosses, source FROM glosses "
            "WHERE source_lang='ja' AND written=? ORDER BY reading, sense_ord",
            (w,)
        ).fetchall()
        for pos_id, gblob, source_id in rows:
            _ = [id_to_text[i] for i in varints_decode(gblob)]
    drag_ms = (time.time() - t0) * 1000
    print(f"  drag-lookup 100 ja words: {drag_ms:.1f}ms total, "
          f"{drag_ms/len(sample_words):.2f}ms/word avg", flush=True)

    # Check first 3 rows look right
    print(f"\n=== Sample rows ja:本 ===")
    for row in materialized[:3]:
        sl, w, r, ord_, pos_text, gtexts, source_text = row
        print(f"  [{source_text}] reading={r!r} sense_ord={ord_} "
              f"pos={pos_text!r} glosses={gtexts}")

    out.close()


if __name__ == "__main__":
    main()
