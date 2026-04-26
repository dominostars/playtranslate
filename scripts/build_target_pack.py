#!/usr/bin/env python3
"""
Build a target-language gloss pack for PlayTranslate.

A target pack contains definitions in the user's target language (e.g. French)
for ALL supported source languages (JA, ZH, EN, ...) in a single SQLite file.
Users download one target pack; switching source languages doesn't need a new
download.

Pipeline
--------
1. Parse JMdict XML for target-language glosses (JA→target).
2. Parse optional specialized dicts (CFDICT for ZH→FR, HanDeDict for ZH→DE).
3. Parse kaikki.org Wiktionary JSON-Lines for all remaining source→target pairs.
4. Apply source-level exclusivity: if a primary source covers a headword,
   Wiktionary is not used for that headword.
5. Write glosses.sqlite (WITHOUT ROWID per-sense schema).
6. Write manifest.json + zip.

Usage
-----
    python scripts/build_target_pack.py \\
        --target fr \\
        --jmdict /path/to/JMdict_e_examp.xml \\
        --wiktionary /path/to/kaikki.org/fr/ \\
        --cfdict /path/to/cfdict.txt \\
        --output /tmp/target-fr/

Requirements: Python 3.8+, no third-party libraries.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sqlite3
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

# ── JMdict ISO-639-2 language codes → our 2-letter codes ───────────────

JMDICT_LANG_MAP = {
    "fre": "fr", "ger": "de", "spa": "es", "rus": "ru",
    "por": "pt", "dut": "nl", "hun": "hu", "swe": "sv",
    "slv": "sl", "ita": "it",
}

# ── POS abbreviation map (same as build_jmdict.py) ─────────────────────

POS_ABBREV = {
    "noun (common) (futsuumeishi)": "Noun",
    "adverbial noun (fukushitekimeishi)": "Adv. noun",
    "noun, used as a suffix": "Noun suffix",
    "noun, used as a prefix": "Noun prefix",
    "noun (temporal) (jisoumeishi)": "Temporal noun",
    "pronoun": "Pronoun",
    "adjective (keiyoushi)": "I-adjective",
    "adjective (keiyoushi) - yoi/ii class": "I-adjective (ii)",
    "adjectival nouns or quasi-adjectives (keiyodoshi)": "Na-adjective",
    "pre-noun adjectival (rentaishi)": "Pre-noun adj.",
    "adverb (fukushi)": "Adverb",
    "adverb taking the `to' particle": "Adverb (to)",
    "conjunction": "Conjunction",
    "interjection (kandoushi)": "Interjection",
    "particle": "Particle",
    "prefix": "Prefix",
    "suffix": "Suffix",
    "counter": "Counter",
    "expression (phrase, clause, etc.)": "Expression",
}

# Shared constants live in scripts/wiktionary_filters.py so this script
# and build_latin_dict.py can't drift on what "redirect" / "content POS"
# means — a bug hidden by that drift was what prompted this refactor.
from wiktionary_filters import WIKT_EXCLUDED_POS, WIKT_REDIRECT_KEYS


def shorten_pos(pos_text: str) -> str:
    return POS_ABBREV.get(pos_text, pos_text)


# ── Schema ──────────────────────────────────────────────────────────────

CREATE_SQL = """
CREATE TABLE glosses (
    source_lang     TEXT NOT NULL,
    written         TEXT NOT NULL,
    reading         TEXT,
    sense_ord       INTEGER NOT NULL,
    pos             TEXT NOT NULL DEFAULT '',
    glosses         TEXT NOT NULL,
    source          TEXT NOT NULL,
    examples        TEXT NOT NULL DEFAULT '',  -- tab-separated example texts
    example_trans   TEXT NOT NULL DEFAULT '',  -- tab-separated translations (parallel to examples)
    misc            TEXT NOT NULL DEFAULT '',  -- comma-separated misc/tag flags
    schema_version  INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (source_lang, written, reading, sense_ord)
) WITHOUT ROWID;
"""

# DE Wiktionary doesn't tag examples with type="example" / type="quotation"
# the way EN Wiktionary does, so we can't filter by type here — just length-
# cap and cap-per-sense. Target packs cap at one example per sense: enough
# to illustrate usage in the word panel, keeps pack size + UI clutter down.
MAX_EXAMPLES_PER_SENSE = 1
MAX_EXAMPLE_CHARS = 200


def extract_kaikki_examples(sense: dict) -> Tuple[str, str]:
    """Returns (examples_tab_joined, translations_tab_joined). Translations are
    positionally aligned with examples — empty string when an example is
    monolingual. Caps per sense and per-example length to keep packs lean.
    """
    texts: List[str] = []
    trans: List[str] = []
    seen: Set[str] = set()
    for ex in sense.get("examples") or []:
        text = (ex.get("text") or "").strip()
        if not text or text in seen:
            continue
        if len(text) > MAX_EXAMPLE_CHARS:
            continue
        seen.add(text)
        texts.append(text)
        translation = (ex.get("translation") or ex.get("english") or "").strip()
        # Wiktionary "[please add a translation]"-style placeholders show up
        # as raw english strings; coerce to "" so the runtime knows there's
        # no translation and the source-language line stands alone.
        if translation.startswith("[please") or translation.startswith("(please"):
            translation = ""
        trans.append(translation)
        if len(texts) >= MAX_EXAMPLES_PER_SENSE:
            break
    return "\t".join(texts), "\t".join(trans)


def extract_kaikki_misc(sense: dict) -> str:
    """Returns comma-joined misc flags from kaikki tags + raw_tags. Dedup
    preserves first-occurrence order. Tags (machine-normalized) come before
    raw_tags (German editorial labels) so the more useful identifiers
    surface first when the runtime renders them inline.
    """
    out: List[str] = []
    seen: Set[str] = set()
    for src in (sense.get("tags") or [], sense.get("raw_tags") or []):
        for t in src:
            if not isinstance(t, str):
                continue
            s = t.strip()
            if not s or s in seen:
                continue
            seen.add(s)
            out.append(s)
    return ",".join(out)


# ── JMdict parsing ─────────────────────────────────────────────────────

def parse_jmdict(
    jmdict_path: str, target_lang_3: str
) -> Tuple[List[tuple], Set[str]]:
    """
    Parse JMdict XML and extract senses with target-language glosses.
    Returns (rows, covered_headwords) where covered_headwords is the set
    of (source_lang='ja', written) pairs that have at least one target gloss.
    """
    rows = []
    covered = set()

    print(f"Parsing JMdict XML for lang={target_lang_3}...")
    tree = ET.parse(jmdict_path)
    root = tree.getroot()

    for entry in root.iter("entry"):
        # Collect kanji (written) and reading forms
        keb_list = [k.text for k in entry.iter("keb") if k.text]
        reb_list = [r.text for r in entry.iter("reb") if r.text]

        if not keb_list and not reb_list:
            continue

        # JMdict puts <misc> tags on the English-bearing sense, not on the
        # per-language sense blocks. We can ONLY safely attach English misc
        # to the target-language rows when there's an unambiguous 1:1
        # mapping — i.e. exactly 1 English sense and exactly 1 target
        # sense. Multi-sense entries have unknown alignment between English
        # and target blocks (proven for 取る: 2/17 positions matched), so
        # attaching English misc to a target block could attribute the
        # flag to the wrong meaning. In those cases we drop misc entirely.
        en_sense_count = 0
        target_sense_count = 0
        for sense in entry.iter("sense"):
            has_eng = any(
                g.get("{http://www.w3.org/XML/1998/namespace}lang", "eng") == "eng"
                for g in sense.iter("gloss") if g.text
            )
            has_target = any(
                g.get("{http://www.w3.org/XML/1998/namespace}lang", "eng") == target_lang_3
                for g in sense.iter("gloss") if g.text
            )
            if has_eng:
                en_sense_count += 1
            if has_target:
                target_sense_count += 1
        misc_str = ""
        if en_sense_count == 1 and target_sense_count == 1:
            seen_misc: Set[str] = set()
            entry_misc: List[str] = []
            for sense in entry.iter("sense"):
                for m in sense.iter("misc"):
                    if m.text and m.text not in seen_misc:
                        seen_misc.add(m.text)
                        entry_misc.append(m.text)
            misc_str = ",".join(entry_misc)

        # For each sense, check if it has glosses in the target language
        for sense_ord, sense in enumerate(entry.iter("sense")):
            target_glosses = []
            for g in sense.iter("gloss"):
                lang = g.get("{http://www.w3.org/XML/1998/namespace}lang", "eng")
                if lang == target_lang_3 and g.text:
                    target_glosses.append(g.text)

            if not target_glosses:
                continue

            pos_tags = [shorten_pos(p.text) for p in sense.iter("pos") if p.text]
            pos_str = ",".join(pos_tags)
            gloss_str = "\t".join(target_glosses)
            # misc_str is collected entry-wide above (JMdict puts <misc> on
            # the English sense, not the per-language blocks). Examples stay
            # empty for JA — JMdict's <example> element is unpopulated across
            # all 216k entries (verified).

            # Emit one row per (written, reading) combination.
            # If no keb, use reb as written with reading=NULL.
            if keb_list:
                for keb in keb_list:
                    for reb in reb_list:
                        rows.append((
                            "ja", keb, reb, sense_ord, pos_str,
                            gloss_str, "jmdict", "", "", misc_str,
                        ))
                        covered.add(keb)
                    # Also emit empty-reading row for fallback queries
                    rows.append((
                        "ja", keb, "", sense_ord, pos_str,
                        gloss_str, "jmdict", "", "", misc_str,
                    ))
                    covered.add(keb)
            else:
                for reb in reb_list:
                    rows.append((
                        "ja", reb, "", sense_ord, pos_str,
                        gloss_str, "jmdict", "", "", misc_str,
                    ))
                    covered.add(reb)

    print(f"  JMdict: {len(rows)} rows, {len(covered)} headwords covered")
    return rows, covered


# ── CFDICT / HanDeDict parsing ──────────────────────────────────────────

def parse_cfdict(cfdict_path: str, source_label: str) -> Tuple[List[tuple], Set[str]]:
    """
    Parse a CEDICT-format file (CFDICT for ZH→FR, HanDeDict for ZH→DE).
    Format: traditional simplified [pinyin] /def1/def2/.../
    """
    rows = []
    covered = set()
    print(f"Parsing {source_label} from {cfdict_path}...")

    with open(cfdict_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            # Parse CEDICT format
            parts = line.split(" ", 2)
            if len(parts) < 3:
                continue
            simplified = parts[1]
            rest = parts[2]
            # Extract definitions between / /
            defs_start = rest.find("/")
            defs_end = rest.rfind("/")
            if defs_start < 0 or defs_start == defs_end:
                continue
            defs_raw = rest[defs_start + 1:defs_end]
            definitions = [d.strip() for d in defs_raw.split("/") if d.strip()]
            if not definitions:
                continue

            # Extract pinyin from [brackets]
            pinyin = None
            bracket_start = rest.find("[")
            bracket_end = rest.find("]")
            if bracket_start >= 0 and bracket_end > bracket_start:
                pinyin = rest[bracket_start + 1:bracket_end]

            # Emit as single sense (CEDICT doesn't have per-sense structure).
            # CFDICT/HanDeDict don't ship example sentences or misc tags.
            gloss_str = "\t".join(definitions[:8])
            rows.append((
                "zh", simplified, pinyin, 0, "",
                gloss_str, source_label, "", "", "",
            ))
            # Also emit without reading for fallback
            if pinyin:
                rows.append((
                    "zh", simplified, "", 0, "",
                    gloss_str, source_label, "", "", "",
                ))
            covered.add(simplified)

    print(f"  {source_label}: {len(rows)} rows, {len(covered)} headwords covered")
    return rows, covered


# ── Wiktionary kaikki.org parsing ───────────────────────────────────────

def parse_wiktionary_dir(
    wikt_dir: str,
    target_lang: str,
    excluded_headwords: Dict[str, Set[str]],
) -> List[tuple]:
    """
    Parse all kaikki.org JSON-Lines files in a directory. Each file should
    be named like `kaikki.org-dictionary-French-by-language-Japanese.jsonl`
    or similar — we detect the source language from the `lang_code` field
    in each JSON line.

    excluded_headwords maps source_lang → set of headwords already covered
    by a primary source. These are skipped (source-level exclusivity).
    """
    rows = []
    wikt_path = Path(wikt_dir)

    jsonl_files = sorted(wikt_path.glob("*.jsonl"))
    if not jsonl_files:
        # Try a single file if dir contains just one
        jsonl_files = sorted(wikt_path.glob("*.json"))
    if not jsonl_files:
        print(f"  No JSONL files found in {wikt_dir}")
        return rows

    for jsonl_file in jsonl_files:
        file_rows = 0
        print(f"  Processing {jsonl_file.name}...")
        with open(jsonl_file, "r", encoding="utf-8") as f:
            for line_no, line in enumerate(f, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    entry = json.loads(line)
                except json.JSONDecodeError:
                    continue

                word = entry.get("word", "").strip()
                if not word:
                    continue

                pos = entry.get("pos", "")
                if pos in WIKT_EXCLUDED_POS:
                    continue

                # Filter "X of Y" redirects
                is_redirect = False
                for redirect_key in WIKT_REDIRECT_KEYS:
                    if entry.get(redirect_key):
                        is_redirect = True
                        break
                if is_redirect:
                    continue

                # Determine source language from the entry
                source_lang = entry.get("lang_code", "").lower()
                if not source_lang or len(source_lang) > 3:
                    continue

                # Skip if already covered by primary source
                excluded = excluded_headwords.get(source_lang, set())
                if word in excluded:
                    continue

                # Extract senses
                senses_data = entry.get("senses", [])
                if not senses_data:
                    continue

                for sense_ord, sense in enumerate(senses_data):
                    # Get glosses from the sense
                    glosses = []
                    for g in sense.get("glosses", []):
                        if isinstance(g, str) and g.strip():
                            glosses.append(g.strip())
                    if not glosses:
                        continue

                    # Skip redirect senses
                    sense_is_redirect = False
                    for redirect_key in WIKT_REDIRECT_KEYS:
                        if sense.get(redirect_key):
                            sense_is_redirect = True
                            break
                    if sense_is_redirect:
                        continue

                    pos_str = pos if pos else ""
                    gloss_str = "\t".join(glosses[:8])
                    examples_str, example_trans_str = extract_kaikki_examples(sense)
                    misc_str = extract_kaikki_misc(sense)

                    rows.append((
                        source_lang, word, "", sense_ord, pos_str,
                        gloss_str, "wiktionary",
                        examples_str, example_trans_str, misc_str,
                    ))
                    file_rows += 1

                    # Cap senses per word
                    if sense_ord >= 7:
                        break

        print(f"    {file_rows} rows from {jsonl_file.name}")

    print(f"  Wiktionary total: {len(rows)} rows")
    return rows


# ── Main ────────────────────────────────────────────────────────────────

def build_target_pack(args: argparse.Namespace) -> None:
    target = args.target
    pack_version = args.pack_version
    output_dir = Path(args.output)
    output_dir.mkdir(parents=True, exist_ok=True)

    db_path = output_dir / "glosses.sqlite"
    if db_path.exists():
        db_path.unlink()

    conn = sqlite3.connect(str(db_path))
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute(CREATE_SQL)

    all_sources: List[str] = []
    source_counts: Dict[str, int] = {}
    excluded_headwords: Dict[str, Set[str]] = {}

    # ── Tier 1: JMdict (JA→target) ──────────────────────────────────
    if args.jmdict:
        # Map 2-letter target to JMdict 3-letter code
        target_3 = {v: k for k, v in JMDICT_LANG_MAP.items()}.get(target)
        if target_3:
            jmdict_rows, jmdict_covered = parse_jmdict(args.jmdict, target_3)
            if jmdict_rows:
                conn.executemany(
                    "INSERT OR IGNORE INTO glosses "
                    "(source_lang, written, reading, sense_ord, pos, glosses, source, "
                    " examples, example_trans, misc) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    jmdict_rows,
                )
                excluded_headwords["ja"] = jmdict_covered
                source_counts["jmdict_ja"] = len(jmdict_rows)
                if "ja" not in all_sources:
                    all_sources.append("ja")
        else:
            print(f"  JMdict: no mapping for target lang '{target}', skipping")

    # ── Tier 2: Specialized dicts (ZH→target) ────────────────────────
    if args.cfdict:
        label = "cfdict" if target == "fr" else "handedict"
        cfdict_rows, cfdict_covered = parse_cfdict(args.cfdict, label)
        if cfdict_rows:
            conn.executemany(
                "INSERT OR IGNORE INTO glosses "
                "(source_lang, written, reading, sense_ord, pos, glosses, source, "
                " examples, example_trans, misc) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                cfdict_rows,
            )
            excluded_headwords.setdefault("zh", set()).update(cfdict_covered)
            source_counts[f"{label}_zh"] = len(cfdict_rows)
            if "zh" not in all_sources:
                all_sources.append("zh")

    # ── Tier 3: Wiktionary (all remaining pairs) ─────────────────────
    if args.wiktionary:
        wikt_rows = parse_wiktionary_dir(args.wiktionary, target, excluded_headwords)
        if wikt_rows:
            conn.executemany(
                "INSERT OR IGNORE INTO glosses "
                "(source_lang, written, reading, sense_ord, pos, glosses, source, "
                " examples, example_trans, misc) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                wikt_rows,
            )
            # Collect unique source languages from wiktionary
            wikt_sources = set()
            for row in wikt_rows:
                wikt_sources.add(row[0])
            for src in sorted(wikt_sources):
                if src not in all_sources:
                    all_sources.append(src)
            source_counts["wiktionary"] = len(wikt_rows)

    conn.commit()

    # Report total
    cursor = conn.execute("SELECT COUNT(*) FROM glosses")
    total_rows = cursor.fetchone()[0]
    print(f"\nTotal rows in glosses.sqlite: {total_rows}")

    conn.close()

    # ── Manifest ─────────────────────────────────────────────────────
    db_size = db_path.stat().st_size
    db_sha = hashlib.sha256(db_path.read_bytes()).hexdigest()
    manifest = {
        "langId": f"target-{target}",
        "schemaVersion": 1,
        "packVersion": pack_version,
        "appMinVersion": 0,
        "files": [{"path": "glosses.sqlite", "size": db_size, "sha256": db_sha}],
        "totalSize": db_size,
        "licenses": [],
        "target": target,
        "covers": all_sources,
        "sourceCounts": source_counts,
        "totalRows": total_rows,
    }
    manifest_path = output_dir / "manifest.json"
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
    print(f"Manifest written: covers={all_sources}")

    # ── Zip ──────────────────────────────────────────────────────────
    zip_name = f"{target}.zip"
    zip_path = output_dir / zip_name
    with zipfile.ZipFile(str(zip_path), "w", zipfile.ZIP_DEFLATED) as zf:
        zf.write(str(db_path), "glosses.sqlite")
        zf.write(str(manifest_path), "manifest.json")

    zip_size = zip_path.stat().st_size
    sha256 = hashlib.sha256(zip_path.read_bytes()).hexdigest()

    print(f"\nOutput: {zip_path}")
    print(f"  Size: {zip_size:,} bytes ({zip_size / 1024 / 1024:.1f} MB)")
    print(f"  SHA-256: {sha256}")
    print(f"\nCatalog entry:")
    print(f'  "target-{target}": {{')
    print(f'    "display": "{target.upper()} definitions",')
    print(f'    "type": "target",')
    print(f'    "bundled": false,')
    print(f'    "packVersion": {pack_version},')
    print(f'    "size": {zip_size},')
    print(f'    "url": "https://github.com/dominostars/playtranslate-langpacks/releases/download/target-{target}-v{pack_version}/{zip_name}",')
    print(f'    "sha256": "{sha256}"')
    print(f"  }}")


def main():
    parser = argparse.ArgumentParser(
        description="Build a target-language gloss pack for PlayTranslate",
    )
    parser.add_argument(
        "--target", required=True,
        help="Target language 2-letter code (e.g. fr, de, es)",
    )
    parser.add_argument(
        "--jmdict",
        help="Path to JMdict XML file (for JA→target glosses)",
    )
    parser.add_argument(
        "--wiktionary",
        help="Directory with kaikki.org JSONL files for the target language",
    )
    parser.add_argument(
        "--cfdict",
        help="Path to CFDICT/HanDeDict text file (CEDICT format, for ZH→target)",
    )
    parser.add_argument(
        "--output", required=True,
        help="Output directory for glosses.sqlite, manifest.json, and .zip",
    )
    parser.add_argument(
        "--pack-version", type=int, default=1,
        help="Pack version number for manifest (default: 1)",
    )

    args = parser.parse_args()
    build_target_pack(args)


if __name__ == "__main__":
    main()
