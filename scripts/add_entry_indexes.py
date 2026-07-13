#!/usr/bin/env python3
"""Add the entry_id indexes to an already-built dictionary pack, in place.

Commit d2f628cf added idx_headword_entry / idx_reading_entry / idx_sense_entry to
the three pack builders, but the packs hosted at that point were built the day
before — so every shipped pack still does a full table scan per entry in
buildEntry (29% of app CPU, simpleperf 2026-07-05). Pack DBs are opened
OPEN_READONLY with no migration path, so the index can only arrive inside a
rebuilt pack.

For the 22 Wiktionary source packs and zh, d2f628cf is the ONLY commit that has
touched their builder (or any of its inputs) since they were built. Their
builders never ANALYZE/VACUUM and the packs carry no sqlite_stat1. So creating
the three indexes on the shipped database yields a pack logically identical to a
fresh build — same tables, same rows, same indexes — without re-downloading and
re-parsing 16 GB of kaikki extracts, and without pulling in unreviewed upstream
content drift. ja is NOT eligible: build_jmdict.py also gained the curated-misc
filter (de4e007c) after ja-v3 was built, so ja gets a real rebuild.

The zero-content-change claim is asserted, not assumed: row counts and the full
sqlite_master schema are compared before and after, and the only permitted
difference is the three new index rows.

  python3 scripts/add_entry_indexes.py --zip local/source-v2/fr/fr.zip \
      --lang fr --pack-version 3 --output local/packs-v3
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sqlite3
import sys
import tempfile
import zipfile
from pathlib import Path

# Byte-for-byte the DDL the builders emit (build_latin_dict.py, build_zh_dict.py,
# build_jmdict.py). Keep in sync — a divergent index here would silently produce
# a pack a fresh build could never reproduce.
INDEX_DDL = [
    ("idx_headword_entry", "CREATE INDEX idx_headword_entry ON headword(entry_id, position)"),
    ("idx_reading_entry", "CREATE INDEX idx_reading_entry ON reading(entry_id, position)"),
    ("idx_sense_entry", "CREATE INDEX idx_sense_entry ON sense(entry_id, position)"),
]
COUNTED_TABLES = ["entry", "headword", "reading", "sense"]


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def schema_of(conn: sqlite3.Connection) -> set[tuple]:
    return set(conn.execute(
        "SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY name"
    ).fetchall())


def counts_of(conn: sqlite3.Connection) -> dict[str, int]:
    return {t: conn.execute(f"SELECT count(*) FROM {t}").fetchone()[0] for t in COUNTED_TABLES}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--zip", type=Path, required=True, help="the already-built pack zip")
    ap.add_argument("--lang", required=True)
    ap.add_argument("--pack-version", type=int, required=True)
    ap.add_argument("--output", type=Path, required=True, help="<output>/<lang>/<lang>.zip")
    args = ap.parse_args()

    src: Path = args.zip
    if not src.is_file():
        print(f"ERROR: {src} not found", file=sys.stderr)
        return 1
    print(f"[{args.lang}] source {src} ({src.stat().st_size:,} B, sha {sha256(src)[:12]}…)")

    with tempfile.TemporaryDirectory() as td:
        work = Path(td)
        with zipfile.ZipFile(src) as z:
            names = z.namelist()          # re-zipped verbatim: ko's flat tokenizer/,
            z.extractall(work)            # zh's nested tokenizer/data/**, th's words.txt
        db = work / "dict.sqlite"
        man_path = work / "manifest.json"
        for required in (db, man_path):
            if not required.is_file():
                print(f"ERROR: {src} has no {required.name}", file=sys.stderr)
                return 1

        manifest = json.loads(man_path.read_text(encoding="utf-8"))
        if manifest.get("langId") != args.lang:
            print(f"ERROR: langId={manifest.get('langId')!r}, expected {args.lang!r}", file=sys.stderr)
            return 1
        if manifest.get("packVersion") >= args.pack_version:
            print(f"ERROR: pack is already packVersion {manifest.get('packVersion')}; "
                  f"refusing to relabel it {args.pack_version}", file=sys.stderr)
            return 1

        # isolation_level=None: autocommit, so VACUUM isn't trapped inside an
        # implicit transaction.
        conn = sqlite3.connect(db, isolation_level=None)
        before_schema = schema_of(conn)
        before_counts = counts_of(conn)
        before_indexes = {n for t, n, _, _ in before_schema if t == "index"}

        present = [name for name, _ in INDEX_DDL if name in before_indexes]
        if present:
            print(f"ERROR: {args.lang} already has {present} — nothing to migrate", file=sys.stderr)
            return 1

        for name, ddl in INDEX_DDL:
            conn.execute(ddl)
        conn.execute("VACUUM")

        integrity = conn.execute("PRAGMA integrity_check").fetchone()[0]
        if integrity != "ok":
            print(f"ERROR: integrity_check = {integrity!r}", file=sys.stderr)
            return 1

        after_schema = schema_of(conn)
        after_counts = counts_of(conn)
        conn.close()

        # The whole safety argument of this script: content is untouched, and the
        # ONLY schema delta is the three indexes we asked for.
        if after_counts != before_counts:
            print(f"ERROR: row counts changed! {before_counts} -> {after_counts}", file=sys.stderr)
            return 1
        added = after_schema - before_schema
        removed = before_schema - after_schema
        expected = {name for name, _ in INDEX_DDL}
        if removed or {n for _, n, _, _ in added} != expected:
            print(f"ERROR: unexpected schema delta. added={sorted(n for _, n, _, _ in added)} "
                  f"removed={sorted(n for _, n, _, _ in removed)}", file=sys.stderr)
            return 1

        # Manifest: only packVersion, dict.sqlite's size, and the derived total move.
        new_size = db.stat().st_size
        hit = False
        for f in manifest["files"]:
            if f["path"] == "dict.sqlite":
                f["size"] = new_size
                hit = True
        if not hit:
            print("ERROR: manifest.files has no dict.sqlite entry", file=sys.stderr)
            return 1
        manifest["packVersion"] = args.pack_version
        manifest["totalSize"] = sum(int(f["size"]) for f in manifest["files"])
        man_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
                            encoding="utf-8")

        outdir: Path = args.output / args.lang
        outdir.mkdir(parents=True, exist_ok=True)
        out_zip = outdir / f"{args.lang}.zip"
        if out_zip.exists():
            out_zip.unlink()
        with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED) as z:
            for name in names:
                z.write(work / name, arcname=name)

    print(f"[{args.lang}] rows unchanged {before_counts}")
    print(f"[{args.lang}] +3 indexes, integrity ok, dict.sqlite {new_size:,} B")
    print(f"[{args.lang}] wrote {out_zip} ({out_zip.stat().st_size:,} B, "
          f"packVersion {args.pack_version}, sha {sha256(out_zip)[:12]}…)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
