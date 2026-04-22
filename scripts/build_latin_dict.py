#!/usr/bin/env python3
"""
Build a Latin-script language pack for PlayTranslate from a kaikki.org
Wiktionary JSON-Lines extract (English Wiktionary's <LANG> entries).

One script, parameterised by `--lang`. Supports any Latin-script source
language that kaikki.org publishes AND that has a `word_frequency` locale
in the `wordfreq` package. Verified list:

    ca cs da de en es fi fr hu id it nb nl no pl pt ro sv tr vi

(Norwegian: pass `--lang no` — the kaikki file is named "Norwegian"
but its lang_code is `no`. The wordfreq locale `nb` is substituted
automatically.)

Pipeline
--------
1. Stream the kaikki JSON-Lines file (one JSON object per line).
2. Filter to content-word parts of speech (noun/verb/adj/adv/...).
3. Drop entries where `lang_code` doesn't match `--lang`.
4. Drop rare words (`wordfreq.word_frequency` below MIN_FREQUENCY).
5. Write a SQLite file with the JMdict schema shared by DictionaryManager /
   LatinDictionaryManager (`kanjidic` stays empty for Latin).
6. Write `manifest.json` and produce `<lang>.zip`.

Usage
-----
    python scripts/build_latin_dict.py \\
        --lang fr \\
        --input  /path/to/kaikki-French.jsonl \\
        --output /tmp/fr_pack/

The kaikki.org Wiktionary extracts are at:
    https://kaikki.org/dictionary/<LanguageName>/
Download the per-language JSON-Lines file (typically
`kaikki.org-dictionary-<LanguageName>.jsonl`).

After running:
1. `sha256sum /tmp/<lang>_pack/<lang>.zip` — note the hex digest.
2. Create a release tagged `<lang>-v1` on
   `github.com/dominostars/playtranslate-langpacks` and upload the zip.
3. Edit `app/src/main/assets/langpack_catalog.json` — add the `<lang>`
   entry with the release URL and the computed sha256.

Schema notes
------------
- `kanji.text`    -> headword. Column is misnamed for legacy JMdict
                    reasons; a later cleanup will rename.
- `reading.text`  -> UNUSED for Latin (no pronunciation data).
- `sense.glosses` -> TAB-separated list of English definitions (matches
                    JMdict's sense format).
- `sense.pos`     -> Wiktionary's `pos` field, lowercased.
- `sense.misc`    -> Empty for now; reserved for usage notes later.
- `entry.is_common` -> 1 if `word_frequency >= COMMON_FREQUENCY`.
- `entry.freq_score` -> 0..100 scaled log frequency, used for result ordering.

Content filters
---------------
- Only POS in CONTENT_POS. Proper nouns (`name`) are excluded — they
  add noise without translation value for game text.
- Multi-word headwords > 3 words are dropped.
- Entries with zero non-blank glosses are dropped.
- Caps per-entry to 8 senses to keep pack size down.
"""

from __future__ import annotations

import argparse
import json
import math
import sqlite3
import sys
import zipfile
from pathlib import Path
from typing import Iterable, Optional

try:
    from wordfreq import word_frequency
except ImportError:
    print(
        "error: wordfreq not installed. Run `pip install wordfreq` first.",
        file=sys.stderr,
    )
    sys.exit(1)

try:
    from snowballstemmer import stemmer as _snowball_stemmer
except ImportError:
    print(
        "error: snowballstemmer not installed. Run `pip install snowballstemmer` first.",
        file=sys.stderr,
    )
    sys.exit(1)

# Shared constants + redirect predicates — kept in scripts/wiktionary_filters.py
# so build_latin_dict.py and build_target_pack.py can't drift on filter rules.
from wiktionary_filters import (
    CONTENT_POS,
    MAX_HEADWORD_WORDS,
    MAX_SENSES_PER_ENTRY,
    WIKT_REDIRECT_KEYS,
    is_redirect_entry,
    is_redirect_sense,
)

# Frequency threshold (Zipf scale). Words with frequency below this are
# dropped. 1e-6 = "at least one occurrence per million words" — yields
# ~30-50k entries for well-covered languages.
MIN_FREQUENCY = 1e-6

# Higher threshold for the is_common flag. Roughly top 3000 common words.
COMMON_FREQUENCY = 1e-4

# Map our 2-letter code → snowballstemmer algorithm name. Must match what
# LatinEngine uses at runtime (Lucene's org.tartarus.snowball.ext.*Stemmer)
# so stem-indexed rows are reachable by the runtime stem query.
# Languages with no Snowball stemmer (Vietnamese, Indonesian) map to None
# and skip stem indexing — their runtime stemmer is null too, and
# LatinDictionaryManager's stem-fallback path short-circuits when
# stem == surface, so the asymmetry is harmless.
SNOWBALL_ALGO_FOR_LANG: dict[str, Optional[str]] = {
    "en": "english",
    "es": "spanish",
    "fr": "french",
    "de": "german",
    "it": "italian",
    "pt": "portuguese",
    "nl": "dutch",
    "tr": "turkish",
    "sv": "swedish",
    "da": "danish",
    "no": "norwegian",
    "fi": "finnish",
    "hu": "hungarian",
    "ro": "romanian",
    "ca": "catalan",
    "vi": None,
    "id": None,
}

# Norwegian: our runtime and ML Kit use `no` for Norwegian, but kaikki
# entries are `lang_code: "nb"` (Bokmål) and wordfreq's locale is also
# `nb`. Pass `--lang no` and let these aliases translate both sides.
KAIKKI_LANG_ALIASES = {
    "no": "nb",
}
WORDFREQ_LOCALE_ALIASES = {
    "no": "nb",
}


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


def build_sqlite(input_path: Path, db_path: Path, lang: str) -> None:
    if db_path.exists():
        db_path.unlink()

    conn = sqlite3.connect(db_path)
    create_schema(conn)
    cur = conn.cursor()

    wordfreq_locale = WORDFREQ_LOCALE_ALIASES.get(lang, lang)
    kaikki_lang = KAIKKI_LANG_ALIASES.get(lang, lang)

    # Snowball stemmer for the language (or None for isolating languages
    # with no inflection). When present, every entry gets a second
    # `kanji.text` row pointing the stem at the same entry_id — this is
    # what makes LatinDictionaryManager's stem fallback reach the lemma
    # after the redirect entry (`volontari → "plural of volontario"`) has
    # been filtered out of the pack.
    if lang not in SNOWBALL_ALGO_FOR_LANG:
        raise ValueError(
            f"No SNOWBALL_ALGO_FOR_LANG mapping for '{lang}'. "
            "Add it (or None) to build_latin_dict.py."
        )
    algo = SNOWBALL_ALGO_FOR_LANG[lang]
    stemmer = _snowball_stemmer(algo) if algo else None

    entry_id = 0
    kept = 0
    dropped_redirect = 0
    stem_rows = 0
    seen_headwords: set[str] = set()

    scanned = 0
    for obj in iter_kaikki(input_path):
        scanned += 1
        if scanned % 100000 == 0:
            print(
                f"  {scanned:,} scanned, {kept:,} kept, "
                f"{dropped_redirect:,} redirects dropped…"
            )

        word = obj.get("word")
        pos_raw = (obj.get("pos") or "").lower()
        lang_code = obj.get("lang_code")

        if not word or not pos_raw:
            continue
        if lang_code and lang_code != kaikki_lang:
            continue
        if pos_raw not in CONTENT_POS:
            continue
        if len(word.split()) > MAX_HEADWORD_WORDS:
            continue

        # Drop entire entries whose senses are all "form_of" / "alt_of"
        # redirects. Keeping them shadows the real lemma on direct lookup
        # (e.g. tap `volontari` → "masculine plural of volontario" instead
        # of the real gloss). The stem fallback inserted below lets users
        # still reach the lemma via inflected forms.
        if is_redirect_entry(obj):
            dropped_redirect += 1
            continue

        # Frequency cut — drops rare archaic words, misspellings, and
        # obscure technical terms that bloat the pack.
        word_lower = word.lower()
        freq = word_frequency(word_lower, wordfreq_locale)
        if freq < MIN_FREQUENCY:
            continue

        glosses_list: list[str] = []
        for sense in (obj.get("senses") or [])[:MAX_SENSES_PER_ENTRY]:
            # Skip individual redirect senses even on entries we're keeping
            # (the `is_redirect_entry` check above only drops entries where
            # ALL senses are redirects).
            if is_redirect_sense(sense):
                continue
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
        # Stem row — second kanji.text pointing at the same entry_id.
        # LatinDictionaryManager tries surface first, then the Lucene
        # Snowball stem of the queried word; the stem row is what that
        # fallback actually hits. Skip when the stem equals the surface
        # (would just duplicate the row) or when the language has no
        # stemmer.
        if stemmer is not None:
            stem = stemmer.stemWord(word_lower)
            if stem and stem != word_lower:
                cur.execute(
                    "INSERT INTO kanji VALUES (?, ?, ?)",
                    (entry_id, 1, stem),
                )
                stem_rows += 1
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
    print(
        f"Built {db_path} with {kept:,} entries "
        f"({dropped_redirect:,} redirects filtered, "
        f"{stem_rows:,} stem rows indexed)."
    )


def build_manifest(db_path: Path, manifest_path: Path, lang: str, pack_version: int) -> None:
    size = db_path.stat().st_size
    manifest = {
        "langId": lang,
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


# ── Smoke test ──────────────────────────────────────────────────────────

# Per-language fixtures: map an inflected surface we expect the user to
# encounter (usually a plural or conjugated form that OCR would return)
# to a substring the lemma's first gloss should contain.
#
# These are intentionally conservative — pick well-known lemmas whose
# gloss text is stable across Wiktionary revisions. Expand per-language
# as regressions surface. Empty dict means "no smoke test for this
# language" (build still succeeds).
SMOKE_FIXTURES: dict[str, dict[str, str]] = {
    "it": {
        "cani": "dog",          # cani → cane → "dog"
        "volontari": "volunteer",  # volontari → volontario → "volunteer"
        "gatti": "cat",         # gatti → gatto → "cat"
    },
}


def run_smoke_test(db_path: Path, lang: str) -> None:
    """Replay LatinDictionaryManager.lookup against a small fixture set
    to catch regressions where plurals/conjugations no longer resolve to
    their lemma gloss. Raises on failure; no-op when the language has no
    fixtures."""
    fixtures = SMOKE_FIXTURES.get(lang, {})
    if not fixtures:
        return
    algo = SNOWBALL_ALGO_FOR_LANG.get(lang)
    stemmer = _snowball_stemmer(algo) if algo else None
    conn = sqlite3.connect(db_path)
    failures: list[str] = []
    try:
        for surface, expected_substr in fixtures.items():
            surface_l = surface.lower()
            # Try surface, then stem — matches LatinDictionaryManager.lookup.
            rows = conn.execute(
                "SELECT s.glosses FROM kanji k JOIN sense s ON s.entry_id=k.entry_id "
                "WHERE k.text = ? ORDER BY k.entry_id",
                (surface_l,),
            ).fetchall()
            if not rows and stemmer is not None:
                stem = stemmer.stemWord(surface_l)
                if stem and stem != surface_l:
                    rows = conn.execute(
                        "SELECT s.glosses FROM kanji k JOIN sense s ON s.entry_id=k.entry_id "
                        "WHERE k.text = ? ORDER BY k.entry_id",
                        (stem,),
                    ).fetchall()
            if not rows:
                failures.append(f"  {surface!r}: no rows via surface or stem lookup")
                continue
            joined = "\t".join(r[0] for r in rows).lower()
            if expected_substr.lower() not in joined:
                failures.append(
                    f"  {surface!r}: expected gloss containing {expected_substr!r}, "
                    f"got {joined[:120]!r}"
                )
    finally:
        conn.close()
    if failures:
        print("SMOKE TEST FAILED:", file=sys.stderr)
        for f in failures:
            print(f, file=sys.stderr)
        raise SystemExit(2)
    print(f"Smoke test OK — {len(fixtures)} fixture(s) passed for '{lang}'.")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Build a Latin-script language pack for PlayTranslate",
    )
    parser.add_argument(
        "--lang",
        required=True,
        help="2-letter language code (e.g. fr, de, es, en, it, pt, nl, sv)",
    )
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
    zip_path = args.output / f"{args.lang}.zip"

    if not args.input.exists():
        print(f"error: input not found: {args.input}", file=sys.stderr)
        return 1

    build_sqlite(args.input, db_path, args.lang)
    run_smoke_test(db_path, args.lang)
    build_manifest(db_path, manifest_path, args.lang, args.pack_version)
    build_zip(db_path, manifest_path, zip_path)

    print()
    print(f"Next steps:")
    print(f"  1. sha256sum {zip_path}")
    print(f"  2. Upload {zip_path} to a release on")
    print(f"     github.com/dominostars/playtranslate-langpacks with tag {args.lang}-v{args.pack_version}")
    print(f"  3. Edit app/src/main/assets/langpack_catalog.json — add the")
    print(f"     {args.lang} entry with the URL and the computed sha256.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
