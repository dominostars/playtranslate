#!/usr/bin/env python3
"""
Build the Chinese language pack for PlayTranslate from CC-CEDICT.

CC-CEDICT is a community-maintained Chinese-English dictionary released
under CC-BY-SA-4.0. Each line has the format:

    Traditional Simplified [pinyin] /definition1/definition2/.../

This script parses that format into the JMdict-compatible SQLite schema
so ChineseDictionaryManager can read it without a schema branch.

Both simplified and traditional forms are stored in the `headword` table
(position 0 = simplified, position 1 = traditional if different). A
single `WHERE text = ?` query matches either variant.

Usage:
    python scripts/build_zh_dict.py \\
        --input cedict_1_0_ts_utf-8_mdbg.txt \\
        --output /tmp/zh_pack/

CC-CEDICT source:
    https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sqlite3
import sys
import zipfile
from pathlib import Path

try:
    from wordfreq import word_frequency
except ImportError:
    print(
        "error: wordfreq not installed. Run `pip install wordfreq` first.",
        file=sys.stderr,
    )
    sys.exit(1)

# Frequency threshold. Chinese has more unique characters, so the threshold
# is lower than English's 1e-6.
MIN_FREQUENCY = 1e-7
COMMON_FREQUENCY = 1e-4

# Regex for CC-CEDICT line format:
#   Traditional Simplified [pinyin] /def1/def2/.../
LINE_RE = re.compile(
    r"^(\S+)\s+(\S+)\s+\[([^\]]+)\]\s+/(.+)/$"
)


def create_schema(conn: sqlite3.Connection) -> None:
    conn.executescript(
        """
        CREATE TABLE entry (
            id         INTEGER PRIMARY KEY,
            is_common  INTEGER NOT NULL DEFAULT 0,
            freq_score INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE headword (
            entry_id   INTEGER NOT NULL,
            position   INTEGER NOT NULL,
            text       TEXT    NOT NULL
        );
        CREATE TABLE reading (
            entry_id   INTEGER NOT NULL,
            position   INTEGER NOT NULL,
            text       TEXT    NOT NULL,
            no_kanji   INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE sense (
            entry_id   INTEGER NOT NULL,
            position   INTEGER NOT NULL,
            pos        TEXT    NOT NULL,
            glosses    TEXT    NOT NULL,
            misc       TEXT    NOT NULL DEFAULT ''
        );
        CREATE TABLE kanjidic (
            literal      TEXT    PRIMARY KEY,
            meanings     TEXT    NOT NULL DEFAULT '',
            on_readings  TEXT    NOT NULL DEFAULT '',
            kun_readings TEXT    NOT NULL DEFAULT '',
            jlpt         INTEGER NOT NULL DEFAULT 0,
            grade        INTEGER NOT NULL DEFAULT 0,
            stroke_count INTEGER NOT NULL DEFAULT 0
        );
        CREATE INDEX idx_headword_text ON headword(text);
        CREATE INDEX idx_reading_text  ON reading(text);
        """
    )


def build_sqlite(input_path: Path, db_path: Path) -> None:
    if db_path.exists():
        db_path.unlink()

    conn = sqlite3.connect(db_path)
    create_schema(conn)
    cur = conn.cursor()

    entry_id = 0
    kept = 0
    skipped = 0

    with input_path.open("r", encoding="utf-8") as f:
        for line_no, raw_line in enumerate(f, 1):
            line = raw_line.strip()
            if not line or line.startswith("#"):
                continue

            m = LINE_RE.match(line)
            if not m:
                skipped += 1
                continue

            traditional = m.group(1)
            simplified = m.group(2)
            pinyin = m.group(3)
            raw_defs = m.group(4)

            # Split definitions on /
            definitions = [d.strip() for d in raw_defs.split("/") if d.strip()]
            if not definitions:
                skipped += 1
                continue

            # Frequency filter on the simplified form
            freq = word_frequency(simplified, "zh")
            if freq < MIN_FREQUENCY:
                skipped += 1
                continue

            freq_score = max(0, min(100, int((math.log10(freq) + 8) * 14)))
            is_common = 1 if freq >= COMMON_FREQUENCY else 0

            entry_id += 1
            cur.execute(
                "INSERT INTO entry VALUES (?, ?, ?)",
                (entry_id, is_common, freq_score),
            )

            # Simplified as primary headword (position 0)
            cur.execute(
                "INSERT INTO headword VALUES (?, ?, ?)",
                (entry_id, 0, simplified),
            )

            # Traditional as secondary headword (position 1) if different
            if traditional != simplified:
                cur.execute(
                    "INSERT INTO headword VALUES (?, ?, ?)",
                    (entry_id, 1, traditional),
                )

            # Pinyin in the reading table
            cur.execute(
                "INSERT INTO reading VALUES (?, ?, ?, ?)",
                (entry_id, 0, pinyin, 0),
            )

            # All definitions in one sense row, tab-separated
            cur.execute(
                "INSERT INTO sense VALUES (?, ?, ?, ?, ?)",
                (entry_id, 0, "", "\t".join(definitions[:8]), ""),
            )

            kept += 1
            if kept % 5000 == 0:
                print(f"  {kept:,} entries kept ({skipped:,} skipped)…")

    conn.commit()
    conn.close()
    print(f"Built {db_path} with {kept:,} entries ({skipped:,} skipped).")


# HanLP portable-1.8.4 ships all its runtime dictionaries under data/
# inside the JAR. The ChineseEngine uses HanLP.segment() +
# HanLP.convertToPinyinList(), which between them pull core dict + ngram
# + pinyin + char tables + (lazily) NER models. Ship the whole tree
# rather than try to minimize — full tree is ~23 MB uncompressed,
# compresses well in zip, and avoids a canary pass that could miss a
# runtime-loaded file.
HANLP_DATA_PREFIX = "data/"


def _sha256_of(path: Path) -> str:
    import hashlib
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def extract_hanlp_data(jar_path: Path, tokenizer_dir: Path) -> list[dict]:
    """Extract HanLP's entire data/ tree from [jar_path] into
    [tokenizer_dir]. The resulting layout is tokenizer/data/... so that
    setting HanLP.Config.DATA_ROOT to '<tokenizer_dir>/data' at runtime
    lets HanLP's internal path resolution work unchanged.

    Returns a list of manifest-shape dicts for appending to
    manifest.files."""
    tokenizer_dir.mkdir(parents=True, exist_ok=True)
    entries: list[dict] = []
    with zipfile.ZipFile(jar_path, "r") as jar:
        for info in jar.infolist():
            name = info.filename
            if not name.startswith(HANLP_DATA_PREFIX):
                continue
            if name.endswith("/"):
                continue  # directory entry
            rel_in_tokenizer = name  # e.g. "data/dictionary/CoreNature..."
            out_path = tokenizer_dir / rel_in_tokenizer
            out_path.parent.mkdir(parents=True, exist_ok=True)
            with jar.open(info) as src, out_path.open("wb") as dst:
                while True:
                    chunk = src.read(1 << 20)
                    if not chunk:
                        break
                    dst.write(chunk)
            entries.append({
                "path": f"tokenizer/{rel_in_tokenizer}",
                "size": out_path.stat().st_size,
                "sha256": _sha256_of(out_path),
            })
    print(f"Extracted {len(entries)} HanLP files to {tokenizer_dir}")
    return entries


def build_manifest(
    db_path: Path,
    manifest_path: Path,
    pack_version: int,
    tokenizer_entries: list[dict] | None = None,
) -> None:
    size = db_path.stat().st_size
    files: list[dict] = [{"path": "dict.sqlite", "size": size, "sha256": None}]
    total = size
    licenses: list[dict] = [
        {
            "component": "CC-CEDICT",
            "license": "CC-BY-SA-4.0",
            "attribution": "© MDBG, https://cc-cedict.org/",
        }
    ]
    if tokenizer_entries:
        files.extend(tokenizer_entries)
        total += sum(int(e["size"]) for e in tokenizer_entries)
        licenses.append({
            "component": "HanLP",
            "license": "Apache-2.0",
            "attribution": "© hankcs, https://github.com/hankcs/HanLP",
        })
    manifest = {
        "langId": "zh",
        "schemaVersion": 1,
        "packVersion": pack_version,
        "appMinVersion": 0,
        "files": files,
        "totalSize": total,
        "licenses": licenses,
    }
    manifest_path.write_text(json.dumps(manifest, indent=2))
    print(f"Wrote {manifest_path} ({size:,} bytes dict, {total:,} bytes total)")


def build_zip(
    db_path: Path,
    manifest_path: Path,
    zip_path: Path,
    tokenizer_dir: Path | None = None,
) -> None:
    if zip_path.exists():
        zip_path.unlink()
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(db_path, arcname="dict.sqlite")
        z.write(manifest_path, arcname="manifest.json")
        if tokenizer_dir is not None and tokenizer_dir.is_dir():
            for p in sorted(tokenizer_dir.rglob("*")):
                if p.is_file():
                    rel = p.relative_to(tokenizer_dir)
                    z.write(p, arcname=f"tokenizer/{rel.as_posix()}")
    print(f"Wrote {zip_path} ({zip_path.stat().st_size:,} bytes)")


def main() -> int:
    parser = argparse.ArgumentParser(description="Build the Chinese language pack")
    parser.add_argument("--input", type=Path, required=True, help="CC-CEDICT text file")
    parser.add_argument("--output", type=Path, required=True, help="Output directory")
    parser.add_argument(
        "--hanlp-jar",
        type=Path,
        required=False,
        help="Path to hanlp-portable-*.jar. When provided, the data/ tree is "
             "extracted into tokenizer/ in the pack so the APK can strip the "
             "bundled HanLP data. ChineseEngine sets HanLP.Config.DATA_ROOT "
             "to the pack's tokenizer/data dir at runtime.",
    )
    parser.add_argument("--pack-version", type=int, default=1)
    parser.add_argument(
        "--rebuild-sqlite",
        action="store_true",
        help="Force dict.sqlite regeneration. When omitted, an existing "
             "dict.sqlite in the output dir is reused.",
    )
    args = parser.parse_args()

    args.output.mkdir(parents=True, exist_ok=True)
    db_path = args.output / "dict.sqlite"
    manifest_path = args.output / "manifest.json"
    zip_path = args.output / "zh.zip"
    tokenizer_dir = args.output / "tokenizer"

    if not args.input.exists():
        print(f"error: input not found: {args.input}", file=sys.stderr)
        return 1

    if db_path.is_file() and not args.rebuild_sqlite:
        print(f"Reusing existing {db_path} ({db_path.stat().st_size:,} bytes) — "
              f"pass --rebuild-sqlite to regenerate from CC-CEDICT")
    else:
        build_sqlite(args.input, db_path)

    tokenizer_entries = None
    if args.hanlp_jar is not None:
        if not args.hanlp_jar.is_file():
            print(f"error: --hanlp-jar not a file: {args.hanlp_jar}", file=sys.stderr)
            return 1
        tokenizer_entries = extract_hanlp_data(args.hanlp_jar, tokenizer_dir)

    build_manifest(db_path, manifest_path, args.pack_version, tokenizer_entries)
    build_zip(db_path, manifest_path, zip_path, tokenizer_dir if tokenizer_entries else None)

    print()
    print("Next steps:")
    print(f"  1. sha256sum {zip_path}")
    print(f"  2. Upload {zip_path} to dominostars/playtranslate-langpacks tag zh-v1")
    print(f"  3. Update assets/langpack_catalog.json with URL + sha256 + new size")
    return 0


if __name__ == "__main__":
    sys.exit(main())
