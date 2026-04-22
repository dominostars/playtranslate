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


def build_manifest(db_path: Path, manifest_path: Path, pack_version: int) -> None:
    size = db_path.stat().st_size
    manifest = {
        "langId": "zh",
        "schemaVersion": 1,
        "packVersion": pack_version,
        "appMinVersion": 0,
        "files": [{"path": "dict.sqlite", "size": size, "sha256": None}],
        "totalSize": size,
        "licenses": [
            {
                "component": "CC-CEDICT",
                "license": "CC-BY-SA-4.0",
                "attribution": "© MDBG, https://cc-cedict.org/",
            }
        ],
    }
    manifest_path.write_text(json.dumps(manifest, indent=2))
    print(f"Wrote {manifest_path} ({size:,} bytes dict)")


def build_zip(db_path: Path, manifest_path: Path, zip_path: Path) -> None:
    if zip_path.exists():
        zip_path.unlink()
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(db_path, arcname="dict.sqlite")
        z.write(manifest_path, arcname="manifest.json")
    print(f"Wrote {zip_path} ({zip_path.stat().st_size:,} bytes)")


def main() -> int:
    parser = argparse.ArgumentParser(description="Build the Chinese language pack")
    parser.add_argument("--input", type=Path, required=True, help="CC-CEDICT text file")
    parser.add_argument("--output", type=Path, required=True, help="Output directory")
    parser.add_argument("--pack-version", type=int, default=1)
    args = parser.parse_args()

    args.output.mkdir(parents=True, exist_ok=True)
    db_path = args.output / "dict.sqlite"
    manifest_path = args.output / "manifest.json"
    zip_path = args.output / "zh.zip"

    if not args.input.exists():
        print(f"error: input not found: {args.input}", file=sys.stderr)
        return 1

    build_sqlite(args.input, db_path)
    build_manifest(db_path, manifest_path, args.pack_version)
    build_zip(db_path, manifest_path, zip_path)

    print()
    print("Next steps:")
    print(f"  1. sha256sum {zip_path}")
    print(f"  2. Upload {zip_path} to dominostars/playtranslate-langpacks tag zh-v1")
    print(f"  3. Update assets/langpack_catalog.json with URL + sha256")
    return 0


if __name__ == "__main__":
    sys.exit(main())
