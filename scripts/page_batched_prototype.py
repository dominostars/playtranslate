#!/usr/bin/env python3
"""
PROTOTYPE — layer page-level zstd batching on top of the interned glosses
DB. Measures disk size + cold/warm/drag-lookup latency at multiple page
sizes so we can pick a sweet spot.

Schema:
  CREATE TABLE strings (id INTEGER PRIMARY KEY, text TEXT NOT NULL UNIQUE);
  CREATE TABLE pages   (start_key TEXT PRIMARY KEY, blob BLOB NOT NULL);

Page binary format (uncompressed bytes inside the BLOB):
  magic         u8      = 1
  row_count     varint
  for each row:
    source_lang  varint-length-prefixed utf-8
    written      varint-length-prefixed utf-8
    reading      varint-length-prefixed utf-8 (may be empty)
    sense_ord    varint
    pos_id       varint
    source_id    varint
    gloss_count  varint
    gloss_ids    gloss_count varints

Whole page is then zstd-compressed.

Page boundaries: group rows so all rows sharing (source_lang, written)
land in the same page. Otherwise, each new page starts when row_count
crosses the target_page_rows threshold.
"""
from __future__ import annotations

import argparse
import io
import sqlite3
import sys
import time
from pathlib import Path

import zstandard as zstd

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


def vw(buf: bytearray, n: int) -> None:
    """Write unsigned LEB128 varint."""
    while n >= 0x80:
        buf.append((n & 0x7F) | 0x80)
        n >>= 7
    buf.append(n & 0x7F)


def vr(view: memoryview, off: int) -> tuple[int, int]:
    """Read varint, return (value, new_offset)."""
    n = 0
    shift = 0
    while True:
        b = view[off]
        off += 1
        n |= (b & 0x7F) << shift
        if not (b & 0x80):
            return n, off
        shift += 7


def encode_str(buf: bytearray, s: str) -> None:
    data = s.encode("utf-8")
    vw(buf, len(data))
    buf.extend(data)


def decode_str(view: memoryview, off: int) -> tuple[str, int]:
    length, off = vr(view, off)
    s = bytes(view[off:off + length]).decode("utf-8")
    return s, off + length


def encode_page(rows: list[tuple]) -> bytes:
    buf = bytearray()
    buf.append(1)  # magic / page version
    vw(buf, len(rows))
    for sl, w, r, ord_, pos_id, gloss_blob, source_id in rows:
        encode_str(buf, sl)
        encode_str(buf, w)
        encode_str(buf, r or "")
        vw(buf, ord_)
        vw(buf, pos_id)
        vw(buf, source_id)
        # Decode gloss BLOB into varints, count them, re-emit
        gids = []
        view = memoryview(gloss_blob)
        off = 0
        while off < len(view):
            v, off = vr(view, off)
            gids.append(v)
        vw(buf, len(gids))
        for g in gids:
            vw(buf, g)
    return bytes(buf)


def decode_page(raw: bytes) -> list[tuple]:
    view = memoryview(raw)
    if view[0] != 1:
        raise ValueError(f"unknown page version {view[0]}")
    off = 1
    n_rows, off = vr(view, off)
    rows = []
    for _ in range(n_rows):
        sl, off = decode_str(view, off)
        w, off = decode_str(view, off)
        r, off = decode_str(view, off)
        ord_, off = vr(view, off)
        pos_id, off = vr(view, off)
        source_id, off = vr(view, off)
        gn, off = vr(view, off)
        gids = []
        for _ in range(gn):
            g, off = vr(view, off)
            gids.append(g)
        rows.append((sl, w, r, ord_, pos_id, gids, source_id))
    return rows


def page_key(source_lang: str, written: str) -> str:
    return f"{source_lang}\t{written}"


def build_paged(interned_path: Path, output_path: Path,
                target_page_rows: int = 200) -> dict:
    print(f"\n=== Build paged ({target_page_rows} rows/page target) ===")
    if output_path.exists():
        output_path.unlink()

    in_db = sqlite3.connect(str(interned_path))
    in_db.execute("PRAGMA query_only = ON")

    # Read all rows in PK order
    print("Reading interned rows in sort order...", flush=True)
    rows = list(in_db.execute(
        "SELECT source_lang, written, reading, sense_ord, pos, glosses, source "
        "FROM glosses ORDER BY source_lang, written, reading, sense_ord"
    ))
    print(f"  {len(rows):,} rows", flush=True)

    # Copy strings table
    print("Copying strings table...", flush=True)
    out_db = sqlite3.connect(str(output_path))
    out_db.execute("PRAGMA journal_mode = OFF")
    out_db.execute("PRAGMA synchronous = OFF")
    out_db.execute("CREATE TABLE strings (id INTEGER PRIMARY KEY, text TEXT NOT NULL UNIQUE)")
    out_db.executemany("INSERT INTO strings(id, text) VALUES (?, ?)",
                       in_db.execute("SELECT id, text FROM strings"))
    out_db.execute("CREATE TABLE pages (start_key TEXT PRIMARY KEY, blob BLOB NOT NULL) WITHOUT ROWID")

    # Group into pages, breaking only between (source_lang, written) units
    cctx = zstd.ZstdCompressor(level=19)
    print("Encoding + compressing pages...", flush=True)
    t0 = time.time()
    page_rows: list = []
    page_inserts: list = []
    last_key = None
    total_compressed = 0
    total_uncompressed = 0
    n_pages = 0

    def flush():
        nonlocal page_rows, total_compressed, total_uncompressed, n_pages
        if not page_rows:
            return
        raw = encode_page(page_rows)
        comp = cctx.compress(raw)
        total_uncompressed += len(raw)
        total_compressed += len(comp)
        n_pages += 1
        sl, w = page_rows[0][0], page_rows[0][1]
        page_inserts.append((page_key(sl, w), comp))
        page_rows = []

    for row in rows:
        sl, w = row[0], row[1]
        cur_key = (sl, w)
        # Start a new page when we cross a (source_lang, written) boundary
        # AND the current page already has the target row count.
        if last_key is not None and cur_key != last_key and len(page_rows) >= target_page_rows:
            flush()
        page_rows.append(row)
        last_key = cur_key
    flush()

    out_db.executemany("INSERT INTO pages(start_key, blob) VALUES (?, ?)", page_inserts)
    out_db.commit()
    out_db.execute("VACUUM")
    out_db.close()
    in_db.close()

    file_size = output_path.stat().st_size
    enc_time = time.time() - t0
    print(f"  pages: {n_pages:,}", flush=True)
    print(f"  uncompressed total: {total_uncompressed/1e6:.1f} MB", flush=True)
    print(f"  compressed total:   {total_compressed/1e6:.1f} MB "
          f"(ratio {total_compressed/total_uncompressed:.2%})", flush=True)
    print(f"  file on disk:       {file_size/1e6:.1f} MB "
          f"(includes strings table + sqlite overhead)", flush=True)
    print(f"  encode + compress time: {enc_time:.1f}s", flush=True)

    return {
        "pageRows": target_page_rows,
        "pages": n_pages,
        "uncompressedBytes": total_uncompressed,
        "compressedBytes": total_compressed,
        "fileBytes": file_size,
    }


# ── Reader prototypes ─────────────────────────────────────────────────

class PagedReader:
    """Reader for the paged-DB schema. Optionally caches decompressed pages
    in a tiny LRU."""

    def __init__(self, db_path: str, cache_pages: int = 0):
        self.db = sqlite3.connect(db_path)
        self.dctx = zstd.ZstdDecompressor()
        self.cache_pages = cache_pages
        self._cache: dict[str, list[tuple]] = {}
        self._cache_order: list[str] = []
        self.bytes_decompressed = 0
        self.cache_hits = 0
        self.cache_misses = 0

    def _load_page(self, start_key: str) -> list[tuple]:
        if self.cache_pages > 0 and start_key in self._cache:
            self.cache_hits += 1
            # LRU touch
            self._cache_order.remove(start_key)
            self._cache_order.append(start_key)
            return self._cache[start_key]

        self.cache_misses += 1
        row = self.db.execute(
            "SELECT blob FROM pages WHERE start_key <= ? "
            "ORDER BY start_key DESC LIMIT 1",
            (start_key,)
        ).fetchone()
        if not row:
            return []
        comp = row[0]
        raw = self.dctx.decompress(comp)
        self.bytes_decompressed += len(raw)
        page = decode_page(raw)
        if self.cache_pages > 0:
            self._cache[start_key] = page
            self._cache_order.append(start_key)
            while len(self._cache_order) > self.cache_pages:
                evict = self._cache_order.pop(0)
                self._cache.pop(evict, None)
        return page

    def lookup(self, source_lang: str, written: str, reading: str | None = None
               ) -> list[tuple]:
        # Probe by (source_lang, written) — pages are keyed by the start of
        # that group. The actual page might be the largest start_key <= ours.
        probe = page_key(source_lang, written)
        page = self._load_page(probe)
        # Linear-scan the page for matching rows
        matches = []
        for sl, w, r, ord_, pos_id, gids, source_id in page:
            if sl != source_lang or w != written:
                continue
            if reading is not None and r != reading and r != "":
                continue
            matches.append((sl, w, r, ord_, pos_id, gids, source_id))
        if not matches:
            return []
        # Batched gloss lookup: collect every needed string id, fetch all in
        # one IN-clause query
        all_ids = set()
        for _, _, _, _, pos_id, gids, source_id in matches:
            all_ids.update(gids)
            if pos_id: all_ids.add(pos_id)
            if source_id: all_ids.add(source_id)
        ph = ",".join("?" * len(all_ids))
        id_to_text = dict(self.db.execute(
            f"SELECT id, text FROM strings WHERE id IN ({ph})", tuple(all_ids)
        ))
        out = []
        for sl, w, r, ord_, pos_id, gids, source_id in matches:
            out.append({
                "source_lang": sl, "written": w, "reading": r,
                "sense_ord": ord_,
                "pos": id_to_text.get(pos_id, ""),
                "source": id_to_text.get(source_id, ""),
                "glosses": [id_to_text[g] for g in gids],
            })
        return out


# ── Performance comparison ────────────────────────────────────────────

def measure(label: str, lookup_fn, sample_words: list[str], reps: int = 5):
    """Run cold + warm + drag scenarios."""
    # Cold tap
    cold_times = []
    for _ in range(reps):
        t0 = time.perf_counter()
        _ = lookup_fn("ja", "本")
        cold_times.append((time.perf_counter() - t0) * 1e6)

    # Drag — measures total time for many varied words
    t0 = time.perf_counter()
    for w in sample_words:
        _ = lookup_fn("ja", w)
    drag_total = (time.perf_counter() - t0) * 1000

    print(f"  {label:<40} cold tap: {min(cold_times):>5.0f}us median "
          f"{sorted(cold_times)[len(cold_times)//2]:>5.0f}us | "
          f"drag {len(sample_words)} words: {drag_total:>6.1f}ms "
          f"({drag_total*1000/len(sample_words):>5.0f}us/word)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--interned",
                    default="local/target-build-hybrid/de/glosses_interned.sqlite",
                    help="Interned SQLite (input)")
    ap.add_argument("--output-dir",
                    default="local/target-build-hybrid/de",
                    help="Where to write paged variants")
    args = ap.parse_args()

    interned = Path(args.interned)
    out_dir = Path(args.output_dir)

    # Build paged variants at multiple page sizes
    variants = []
    for page_rows in (100, 200, 500, 1000):
        out_path = out_dir / f"glosses_paged_{page_rows}.sqlite"
        v = build_paged(interned, out_path, target_page_rows=page_rows)
        variants.append(v)

    # ── Performance comparison ────────────────────────────────────────
    print("\n\n=== Performance comparison ===\n")
    print(f"{'Variant':<40} {'cold tap':>15} | {'drag 100 words':>30}")
    print("-" * 100)

    # Sample words for drag (consecutive in sort order = realistic page
    # locality, e.g. words that appear near each other in a sentence)
    db = sqlite3.connect(str(interned))
    sample_words = [r[0] for r in db.execute(
        "SELECT DISTINCT written FROM glosses WHERE source_lang='ja' "
        "ORDER BY written LIMIT 100"
    ).fetchall()]
    db.close()

    # Baseline: interned-only (the existing prototype reader)
    print("\n--- baseline: interned-only (no paging, no cache) ---")
    base_db = sqlite3.connect(str(interned))
    def base_lookup(sl, w):
        rows = base_db.execute(
            "SELECT pos, glosses, source FROM glosses "
            "WHERE source_lang=? AND written=? ORDER BY reading, sense_ord",
            (sl, w)
        ).fetchall()
        if not rows:
            return []
        all_ids = set()
        decoded = []
        for pos_id, gblob, source_id in rows:
            view = memoryview(gblob)
            off = 0
            gids = []
            while off < len(view):
                v, off = vr(view, off)
                gids.append(v)
            decoded.append((pos_id, gids, source_id))
            all_ids.update(gids)
            if pos_id: all_ids.add(pos_id)
            if source_id: all_ids.add(source_id)
        ph = ",".join("?" * len(all_ids))
        id_to_text = dict(base_db.execute(
            f"SELECT id, text FROM strings WHERE id IN ({ph})", tuple(all_ids)
        ))
        return [(id_to_text.get(p, ""), [id_to_text[g] for g in gs],
                 id_to_text.get(s, "")) for p, gs, s in decoded]
    measure("interned-only", base_lookup, sample_words)
    base_db.close()

    # Paged variants
    for v in variants:
        page_path = out_dir / f"glosses_paged_{v['pageRows']}.sqlite"
        for cache in (0, 4, 16):
            r = PagedReader(str(page_path), cache_pages=cache)
            label = f"paged{v['pageRows']:>5}rows  cache={cache:<3}"
            measure(label, r.lookup, sample_words)
            print(f"      ({r.cache_hits} cache hits, {r.cache_misses} misses, "
                  f"{r.bytes_decompressed/1e6:.1f} MB decompressed total)")

    # Final size summary
    print("\n=== File size summary ===")
    interned_size = interned.stat().st_size
    print(f"  interned (no paging):  {interned_size/1e6:>8.1f} MB")
    for v in variants:
        page_path = out_dir / f"glosses_paged_{v['pageRows']}.sqlite"
        sz = page_path.stat().st_size
        savings = (interned_size - sz) * 100 / interned_size
        print(f"  paged {v['pageRows']:>5}rows:        {sz/1e6:>8.1f} MB  "
              f"({savings:+.1f}% vs interned)")


if __name__ == "__main__":
    main()
