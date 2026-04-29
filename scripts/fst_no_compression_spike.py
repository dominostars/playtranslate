#!/usr/bin/env python3
"""
SPIKE — measure FST + flat data file size WITHOUT page-level zstd
compression. Just plain varint-encoded rows in a flat file, indexed
by a MARISA trie.

Two variants:
  A) FST + raw text glosses     — the simplest swap from current SQLite
  B) FST + interned glosses     — adds string interning but no compression

Compare to current pack (425 MB) and earlier prototypes.
"""
from __future__ import annotations

import sqlite3
import sys
from collections import defaultdict
from pathlib import Path

import marisa_trie

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ROOT = Path(__file__).resolve().parent.parent
HYBRID_DB = ROOT / "local" / "target-build-hybrid" / "de" / "glosses.sqlite"
INTERNED_DB = ROOT / "local" / "target-build-hybrid" / "de" / "glosses_interned.sqlite"
OUT_DIR = ROOT / "local" / "fst-spike"
OUT_DIR.mkdir(parents=True, exist_ok=True)


def vw(buf: bytearray, n: int) -> None:
    while n >= 0x80:
        buf.append((n & 0x7F) | 0x80)
        n >>= 7
    buf.append(n & 0x7F)


def encode_str(buf: bytearray, s: str) -> None:
    data = s.encode("utf-8")
    vw(buf, len(data))
    buf.extend(data)


def build_variant_a():
    """FST + raw text glosses (no interning, no compression)."""
    print("\n=== Variant A: FST + flat data with TEXT glosses (no interning) ===")
    db = sqlite3.connect(str(HYBRID_DB))
    db.execute("PRAGMA query_only = ON")
    print("Reading rows...", flush=True)
    rows = list(db.execute(
        "SELECT source_lang, written, reading, sense_ord, pos, glosses, source "
        "FROM glosses ORDER BY source_lang, written, reading, sense_ord"
    ))
    db.close()
    print(f"  {len(rows):,} rows", flush=True)

    # Group rows by (source_lang, written)
    groups: dict[tuple[str, str], list[tuple]] = defaultdict(list)
    for r in rows:
        groups[(r[0], r[1])].append(r)
    print(f"  {len(groups):,} distinct (source_lang, written) groups", flush=True)

    # Serialize: for each group, encode all its rows into a block. Track offsets.
    print("Serializing flat data file...", flush=True)
    data_buf = bytearray()
    keys_to_offsets: dict[str, int] = {}
    for (sl, w), grouped in groups.items():
        offset = len(data_buf)
        # block format: row_count varint, then for each row: reading, sense_ord, pos, glosses, source
        # source_lang and written are implicit (in the FST key).
        vw(data_buf, len(grouped))
        for _, _, r, ord_, pos, glosses, source in grouped:
            encode_str(data_buf, r or "")
            vw(data_buf, ord_)
            encode_str(data_buf, pos or "")
            encode_str(data_buf, glosses or "")
            encode_str(data_buf, source or "")
        key = f"{sl}\x00{w}"
        keys_to_offsets[key] = offset

    data_path = OUT_DIR / "variant_a_data.bin"
    data_path.write_bytes(bytes(data_buf))
    data_size = data_path.stat().st_size
    print(f"  flat data file:    {data_size:>12,} bytes  ({data_size/1e6:>6.1f} MB)")

    # Build MARISA mapping key → offset
    fmt_pack = "<I"  # 4-byte little-endian unsigned int — supports offsets up to 4 GB
    keys_iter = ((k, (off,)) for k, off in keys_to_offsets.items())
    trie = marisa_trie.RecordTrie(fmt_pack, keys_iter)
    trie_path = OUT_DIR / "variant_a_index.marisa"
    trie.save(str(trie_path))
    trie_size = trie_path.stat().st_size
    print(f"  MARISA index:      {trie_size:>12,} bytes  ({trie_size/1e6:>6.1f} MB)")

    total = data_size + trie_size
    print(f"  TOTAL:             {total:>12,} bytes  ({total/1e6:>6.1f} MB)")
    return total


def build_variant_b():
    """FST + flat data with INTERNED glosses (varint IDs)."""
    print("\n=== Variant B: FST + flat data with interned glosses ===")
    db = sqlite3.connect(str(INTERNED_DB))
    db.execute("PRAGMA query_only = ON")
    print("Reading interned rows...", flush=True)
    rows = list(db.execute(
        "SELECT source_lang, written, reading, sense_ord, pos, glosses, source "
        "FROM glosses ORDER BY source_lang, written, reading, sense_ord"
    ))
    print(f"  {len(rows):,} rows", flush=True)

    # Copy strings table size
    strings_size = db.execute(
        "SELECT SUM(LENGTH(text)) + COUNT(*)*4 FROM strings"
    ).fetchone()[0]
    print(f"  strings table:     {strings_size:>12,} bytes  ({strings_size/1e6:>6.1f} MB)  (estimated)")
    db.close()

    groups: dict[tuple[str, str], list[tuple]] = defaultdict(list)
    for r in rows:
        groups[(r[0], r[1])].append(r)
    print(f"  {len(groups):,} distinct (source_lang, written) groups", flush=True)

    print("Serializing flat data file...", flush=True)
    data_buf = bytearray()
    keys_to_offsets: dict[str, int] = {}
    for (sl, w), grouped in groups.items():
        offset = len(data_buf)
        vw(data_buf, len(grouped))
        for _, _, r, ord_, pos_id, gloss_blob, source_id in grouped:
            encode_str(data_buf, r or "")
            vw(data_buf, ord_)
            vw(data_buf, pos_id)
            vw(data_buf, source_id)
            # gloss_blob is already a packed varint sequence; just length-prefix and inline
            vw(data_buf, len(gloss_blob))
            data_buf.extend(gloss_blob)
        key = f"{sl}\x00{w}"
        keys_to_offsets[key] = offset

    data_path = OUT_DIR / "variant_b_data.bin"
    data_path.write_bytes(bytes(data_buf))
    data_size = data_path.stat().st_size
    print(f"  flat data file:    {data_size:>12,} bytes  ({data_size/1e6:>6.1f} MB)")

    fmt_pack = "<I"
    keys_iter = ((k, (off,)) for k, off in keys_to_offsets.items())
    trie = marisa_trie.RecordTrie(fmt_pack, keys_iter)
    trie_path = OUT_DIR / "variant_b_index.marisa"
    trie.save(str(trie_path))
    trie_size = trie_path.stat().st_size
    print(f"  MARISA index:      {trie_size:>12,} bytes  ({trie_size/1e6:>6.1f} MB)")

    total = data_size + trie_size + strings_size
    print(f"  TOTAL (incl strings table): {total:>12,} bytes  ({total/1e6:>6.1f} MB)")
    return total


def main():
    a = build_variant_a()
    b = build_variant_b()

    today = HYBRID_DB.stat().st_size
    interned = INTERNED_DB.stat().st_size

    print(f"\n=== Comparison ===\n")
    print(f"  Today (SQLite, no compression):           {today/1e6:>7.1f} MB")
    print(f"  Interning only (SQLite):                  {interned/1e6:>7.1f} MB")
    print(f"  FST + flat data (TEXT glosses):           {a/1e6:>7.1f} MB  "
          f"({(today-a)*100/today:+.1f}% vs today)")
    print(f"  FST + flat data (interned glosses):       {b/1e6:>7.1f} MB  "
          f"({(today-b)*100/today:+.1f}% vs today)")
    print()
    print(f"  Earlier measured:")
    print(f"    Interning + 200-row paging (SQLite):    240.6 MB  ({(today-240.6e6)*100/today:+.1f}% vs today)")
    print(f"    Interning + 1000-row paging (SQLite):   177.0 MB  ({(today-177.0e6)*100/today:+.1f}% vs today)")


if __name__ == "__main__":
    main()
