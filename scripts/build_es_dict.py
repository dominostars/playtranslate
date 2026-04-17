#!/usr/bin/env python3
"""
Build the Spanish language pack for PlayTranslate from a kaikki.org
Wiktionary JSON-Lines extract (English Wiktionary's Spanish entries).

This is a first-draft script — matches the JMdict schema that
`DictionaryManager` and `LatinDictionaryManager` read, but has NOT yet
been iterated against real kaikki output. Expect to adjust the JSON
field names and filter heuristics on first run.

Pipeline
--------
1. Stream the kaikki English JSON-Lines file (one JSON object per line).
2. Filter to content-word parts of speech (noun/verb/adj/adv).
3. Collapse multi-sense entries into a single row in `entry`, with
   individual rows in `sense` per Wiktionary sense.
4. Write a SQLite file with the exact JMdict schema (empty `kanjidic`).
5. Write a bundled `manifest.json`.
6. Produce `en.zip` containing `dict.sqlite` + `manifest.json`.

Usage
-----
    python scripts/build_en_dict.py \\
        --input  /path/to/kaikki-en.jsonl \\
        --output /tmp/en_pack/

The kaikki.org English Wiktionary extract is available at:
    https://kaikki.org/dictionary/English/
Download the "all words" JSON-Lines file (`raw-wiktextract-data.jsonl`
or equivalent — kaikki rotates names periodically).

After running:
1. `sha256sum /tmp/en_pack/en.zip` — note the hex digest.
2. Create a release tagged `en-v1` on
   `github.com/dominostars/playtranslate-langpacks` and upload en.zip.
3. Edit `app/src/main/assets/langpack_catalog.json` — add the `en`
   entry with the release URL and the computed sha256.

Schema notes
------------
- `kanji.text`    -> English headword ("run", "walk", …). The column is
                    misnamed for legacy JMdict reasons; a later cleanup
                    will rename.
- `reading.text`  -> UNUSED for Latin (no pronunciation data).
- `sense.glosses` -> TAB-separated list of English definitions (one
                    tab-separator per sense entry in JMdict's format).
- `sense.pos`     -> comma-separated short POS tokens (Wiktionary's
                    `pos` field, lowercased).
- `sense.misc`    -> TAB-separated; Phase 3 leaves empty. Future work
                    could carry Wiktionary usage notes.
- `entry.is_common` -> 1 if the headword is in a hand-curated top-5000
                      list (separate file, optional); 0 otherwise.
- `entry.freq_score` -> 0 for now. Phase 3.5 can add a frequency column
                       by cross-referencing a word-frequency dataset.

Content filters
---------------
- Only POS in {noun, verb, adj, adv, proper_noun=drop}. Proper nouns
  are dropped because they add noise without translation benefit.
- Multi-word headwords > 3 words are dropped.
- Entries with zero non-blank glosses are dropped.
- Caps per-entry to 8 senses to keep pack size down.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sqlite3
import sys
import zipfile
from pathlib import Path
from typing import Iterable

try:
    from wordfreq import word_frequency
except ImportError:
    print(
        "error: wordfreq not installed. Run `pip install wordfreq` first.",
        file=sys.stderr,
    )
    sys.exit(1)

# Frequency threshold (Zipf scale). Words with frequency below this are
# dropped. 1e-6 = "at least one occurrence per million words" — a
# reasonable "common English word" cutoff that yields ~30-50k entries.
MIN_FREQUENCY = 1e-6

# Higher threshold for the is_common flag. ~top 3000 English words.
COMMON_FREQUENCY = 1e-4

# Parts of speech we keep. Allowlist matches the exact strings kaikki
# emits in its `pos` field (verified against the full English dataset on
# 2026-04-15). Proper nouns (`name`) are explicitly excluded — they add
# noise without translation value for game text.
CONTENT_POS = {
    "noun",
    "verb",
    "adj",
    "adv",
    "phrase",
    "prep_phrase",
    "proverb",
    "intj",
    "pron",
    "conj",
    "prep",
    "num",
    "contraction",
    "abbrev",
}

MAX_SENSES_PER_ENTRY = 8
MAX_HEADWORD_WORDS = 3


def iter_kaikki(path: Path) -> Iterable[dict]:
    """Stream a kaikki JSON-Lines file one object at a time."""
    with path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                yield json.loads(line)
            except json.JSONDecodeError as e:
                print(f"  skip malformed line: {e}", file=sys.stderr)


def create_schema(conn: sqlite3.Connection) -> None:
    """Matches scripts/build_jmdict.py's schema exactly so the app-side
    readers (DictionaryManager / LatinDictionaryManager) can share query
    code."""
    conn.executescript(
        """
        CREATE TABLE entry (
            id         INTEGER PRIMARY KEY,
            is_common  INTEGER NOT NULL DEFAULT 0,
            freq_score INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE kanji (
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
        CREATE INDEX idx_kanji_text   ON kanji(text);
        CREATE INDEX idx_reading_text ON reading(text);
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
    seen_headwords: set[str] = set()

    scanned = 0
    for obj in iter_kaikki(input_path):
        scanned += 1
        if scanned % 100000 == 0:
            print(f"  {scanned:,} lines scanned, {kept:,} kept…")

        word = obj.get("word")
        pos_raw = (obj.get("pos") or "").lower()
        lang_code = obj.get("lang_code")

        if not word or not pos_raw:
            continue
        if lang_code and lang_code != "es":
            continue
        if pos_raw not in CONTENT_POS:
            continue
        if len(word.split()) > MAX_HEADWORD_WORDS:
            continue

        # Frequency cut — drops rare archaic words, misspellings, and
        # obscure technical terms that bloat the pack.
        word_lower = word.lower()
        freq = word_frequency(word_lower, "es")
        if freq < MIN_FREQUENCY:
            continue

        glosses_list: list[str] = []
        for sense in (obj.get("senses") or [])[:MAX_SENSES_PER_ENTRY]:
            glosses = sense.get("glosses") or []
            for g in glosses:
                g_clean = (g or "").strip()
                if g_clean:
                    glosses_list.append(g_clean)
        if not glosses_list:
            continue

        # De-duplicate (word, pos) — kaikki sometimes emits repeats.
        key = f"{word_lower}\t{pos_raw}"
        if key in seen_headwords:
            continue
        seen_headwords.add(key)

        # Scale frequency into an integer score for sort ordering.
        # log10(freq) ranges from ~-7 (rare) to ~-2 (very common). Shift
        # and clamp to 0..100.
        freq_score = max(0, min(100, int((math.log10(freq) + 7) * 20)))
        is_common = 1 if freq >= COMMON_FREQUENCY else 0

        entry_id += 1
        cur.execute(
            "INSERT INTO entry VALUES (?, ?, ?)",
            (entry_id, is_common, freq_score),
        )
        cur.execute(
            "INSERT INTO kanji VALUES (?, ?, ?)",
            (entry_id, 0, word_lower),
        )
        # No reading rows for Latin.

        cur.execute(
            "INSERT INTO sense VALUES (?, ?, ?, ?, ?)",
            (
                entry_id,
                0,
                pos_raw,
                "\t".join(glosses_list),
                "",
            ),
        )

        kept += 1

    conn.commit()
    conn.close()
    print(f"Built {db_path} with {kept} entries.")


def build_manifest(db_path: Path, manifest_path: Path, pack_version: int) -> None:
    size = db_path.stat().st_size
    manifest = {
        "langId": "es",
        "schemaVersion": 1,
        "packVersion": pack_version,
        # appMinVersion isn't known here — LanguagePackStore.writeManifestIfMissing
        # writes its own manifest with BuildConfig.VERSION_CODE when the pack is
        # bundled. Downloaded packs use whatever value the server-side manifest
        # provides; use a placeholder of 0 = "any version" here.
        "appMinVersion": 0,
        "files": [
            {"path": "dict.sqlite", "size": size, "sha256": None}
        ],
        "totalSize": size,
        "licenses": [
            {
                "component": "Wiktionary",
                "license": "CC-BY-SA-3.0",
                "attribution": "© Wiktionary contributors, https://en.wiktionary.org/",
            }
        ],
    }
    manifest_path.write_text(json.dumps(manifest, indent=2))
    print(f"Wrote {manifest_path} ({size} bytes dict)")


def build_zip(db_path: Path, manifest_path: Path, zip_path: Path) -> None:
    if zip_path.exists():
        zip_path.unlink()
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(db_path, arcname="dict.sqlite")
        z.write(manifest_path, arcname="manifest.json")
    print(f"Wrote {zip_path} ({zip_path.stat().st_size} bytes)")


def main() -> int:
    parser = argparse.ArgumentParser(description="Build the Spanish language pack")
    parser.add_argument(
        "--input", type=Path, required=True, help="kaikki JSON-Lines file"
    )
    parser.add_argument(
        "--output", type=Path, required=True, help="Output directory"
    )
    parser.add_argument(
        "--pack-version",
        type=int,
        default=1,
        help="packVersion to write into the manifest (default: 1)",
    )
    args = parser.parse_args()

    args.output.mkdir(parents=True, exist_ok=True)
    db_path = args.output / "dict.sqlite"
    manifest_path = args.output / "manifest.json"
    zip_path = args.output / "es.zip"

    if not args.input.exists():
        print(f"error: input not found: {args.input}", file=sys.stderr)
        return 1

    build_sqlite(args.input, db_path)
    build_manifest(db_path, manifest_path, args.pack_version)
    build_zip(db_path, manifest_path, zip_path)

    print()
    print(f"Next steps:")
    print(f"  1. sha256sum {zip_path}")
    print(f"  2. Upload {zip_path} to a release on")
    print(f"     github.com/dominostars/playtranslate-langpacks with tag en-v1")
    print(f"  3. Edit app/src/main/assets/langpack_catalog.json — add the")
    print(f"     en entry with the URL and the computed sha256.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
