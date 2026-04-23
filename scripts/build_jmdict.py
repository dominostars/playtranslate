#!/usr/bin/env python3
"""
Build the JMdict + KANJIDIC2 language pack for PlayTranslate (Japanese).

Produces the same `dict.sqlite` + `manifest.json` + `<code>.zip` bundle
layout that LanguagePackStore.install expects, mirroring build_zh_dict.py.
Upload the resulting ja.zip to the playtranslate-langpacks GH release and
update app/src/main/assets/langpack_catalog.json with the sha256 + size.

Usage (run from project root):
    python scripts/build_jmdict.py --output /tmp/ja_pack/

Requirements: Python 3.8+, no third-party libraries needed.

JMdict is © The Electronic Dictionary Research and Development Group,
licensed under Creative Commons Attribution-ShareAlike 3.0.
See https://www.edrdg.org/edrdg/licence.html
"""

import argparse
import gzip
import json
import re
import sqlite3
import ssl
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

JMDICT_URL = "https://ftp.edrdg.org/pub/Nihongo/JMdict_e.gz"
SCRIPT_DIR = Path(__file__).parent

# Shorten verbose JMdict POS entity values for display
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
    "numeric": "Numeric",
    "expression (phrase, clause, etc.)": "Expression",
    "Ichidan verb": "Ichidan verb",
    "Ichidan verb - kureru special class": "Ichidan verb (kureru)",
    "Ichidan verb - zuru verb (alternative form of -jiru verbs)": "Ichidan verb (zuru)",
    "Godan verb with `u' ending": "Godan verb (u)",
    "Godan verb with `u' ending (special class)": "Godan verb (u, irr.)",
    "Godan verb with `ku' ending": "Godan verb (ku)",
    "Godan verb with `gu' ending": "Godan verb (gu)",
    "Godan verb with `su' ending": "Godan verb (su)",
    "Godan verb with `tsu' ending": "Godan verb (tsu)",
    "Godan verb with `nu' ending": "Godan verb (nu)",
    "Godan verb with `bu' ending": "Godan verb (bu)",
    "Godan verb with `mu' ending": "Godan verb (mu)",
    "Godan verb with `ru' ending": "Godan verb (ru)",
    "Godan verb with `ru' ending (irregular verb)": "Godan verb (ru, irr.)",
    "Godan verb - aru special class": "Godan verb (aru)",
    "Godan verb - Iku/Yuku special class": "Godan verb (iku)",
    "Kuru verb - special class": "Kuru verb",
    "suru verb - included": "Suru verb",
    "suru verb - special class": "Suru verb (special)",
    "suru verb - precursor to the above": "Suru precursor",
    "noun or verb acting prenominally": "Prenominal",
    "auxiliary": "Auxiliary",
    "auxiliary verb": "Aux. verb",
    "auxiliary adjective": "Aux. adjective",
    "unclassified": "Unclassified",
}

COMMON_PRIORITIES = {"ichi1", "ichi2", "spec1", "spec2", "news1", "news2"}

KANJIDIC2_URL = "https://www.edrdg.org/kanjidic/kanjidic2.xml.gz"

# Shorten verbose JMdict misc/field/dialect tags for display
MISC_ABBREV = {
    "usually written using kana alone": "Kana only",
    "word usually written using kana alone": "Kana only",
    "usually written using kanji alone": "Kanji only",
    "colloquial": "Colloquial",
    "honorific or respectful (sonkeigo) language": "Honorific",
    "humble (kenjogo) language": "Humble",
    "polite (teineigo) language": "Polite",
    "archaism": "Archaic",
    "idiomatic expression": "Idiomatic",
    "abbreviation": "Abbreviation",
    "slang": "Slang",
    "internet slang": "Internet slang",
    "manga slang": "Manga slang",
    "obsolete term": "Obsolete",
    "rare term": "Rare",
    "derogatory": "Derogatory",
    "vulgar expression or word": "Vulgar",
    "rude or X-rated term (not displayed in educational software)": "Adult",
    "onomatopoeic or mimetic word": "Onomatopoeia",
    "proverb": "Proverb",
    "poetical term": "Poetic",
    "familiar language": "Familiar",
    "female term or language": "Female speech",
    "male term or language": "Male speech",
    "children's language": "Children's",
    "sensitive": "Sensitive",
    "historical term": "Historical",
    "jocular, humorous term": "Humorous",
    "euphemism": "Euphemism",
    "yojijukugo": "4-char compound",
}


def compute_freq_score(priorities: set) -> int:
    """Map JMdict priority tags to a 0–5 star frequency score."""
    score = 0
    for p in priorities:
        if len(p) == 4 and p[:2] == "nf" and p[2:].isdigit():
            nf = int(p[2:])
            if   nf <=  6: score = max(score, 5)
            elif nf <= 12: score = max(score, 4)
            elif nf <= 20: score = max(score, 3)
            elif nf <= 30: score = max(score, 2)
            else:          score = max(score, 1)
        elif p in ("ichi1", "news1", "spec1"):
            score = max(score, 3)
        elif p in ("ichi2", "news2", "spec2", "gai1"):
            score = max(score, 2)
        elif p == "gai2":
            score = max(score, 1)
    return score


def download_jmdict() -> bytes:
    print(f"Downloading {JMDICT_URL} ...")
    # ftp.edrdg.org has a cert hostname mismatch; disable verification for this build script
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    with urllib.request.urlopen(JMDICT_URL, context=ctx) as resp:
        compressed = resp.read()
    print(f"  Downloaded {len(compressed) // 1024 // 1024} MB compressed")
    data = gzip.decompress(compressed)
    print(f"  Decompressed to {len(data) // 1024 // 1024} MB")
    return data


def preprocess_xml(xml_bytes: bytes) -> bytes:
    """
    ElementTree can't resolve DOCTYPE entities from inline DTDs.
    Extract them with regex, strip the DOCTYPE block, then substitute.
    """
    text = xml_bytes.decode("utf-8")

    # Extract entity definitions from DOCTYPE  <!ENTITY name "value">
    entities: dict[str, str] = {}
    for m in re.finditer(r'<!ENTITY\s+(\S+)\s+"([^"]*)">', text):
        entities[m.group(1)] = m.group(2)
    print(f"  Found {len(entities)} entity definitions")

    # Remove the entire DOCTYPE block (between <!DOCTYPE and the closing ]>)
    doctype_start = text.find("<!DOCTYPE")
    if doctype_start >= 0:
        doctype_end = text.find("]>", doctype_start)
        if doctype_end >= 0:
            text = text[:doctype_start] + text[doctype_end + 2 :]

    # Substitute &entity; references with resolved text
    for name, value in entities.items():
        # Escape any XML special chars in the replacement value
        safe = (
            value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace('"', "&quot;")
        )
        text = text.replace(f"&{name};", safe)

    return text.encode("utf-8")


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


def shorten_pos(raw: str) -> str:
    return POS_ABBREV.get(raw, raw)


def parse_and_insert(xml_bytes: bytes, conn: sqlite3.Connection) -> None:
    print("Parsing XML and inserting entries...")
    cur = conn.cursor()
    root = ET.fromstring(xml_bytes)

    count = 0
    for entry in root.iter("entry"):
        entry_id = int(entry.findtext("ent_seq"))

        # Collect all priority tags across all kanji and reading elements
        all_priorities: set = set()
        for ele in list(entry.findall("k_ele")) + list(entry.findall("r_ele")):
            for tag in ("ke_pri", "re_pri"):
                for pri in ele.findall(tag):
                    if pri.text:
                        all_priorities.add(pri.text)

        is_common = 1 if all_priorities & COMMON_PRIORITIES else 0
        freq_score = compute_freq_score(all_priorities)

        cur.execute("INSERT OR IGNORE INTO entry VALUES (?,?,?)", (entry_id, is_common, freq_score))

        # Kanji forms (headword table — the name is schema-shared with
        # other source packs; for Japanese the values are genuine kanji.)
        for pos, k_ele in enumerate(entry.findall("k_ele")):
            keb = k_ele.findtext("keb")
            if keb:
                cur.execute(
                    "INSERT INTO headword VALUES (?,?,?)", (entry_id, pos, keb)
                )

        # Reading forms
        for pos, r_ele in enumerate(entry.findall("r_ele")):
            reb = r_ele.findtext("reb")
            no_kanji = 1 if r_ele.find("re_nokanji") is not None else 0
            if reb:
                cur.execute(
                    "INSERT INTO reading VALUES (?,?,?,?)",
                    (entry_id, pos, reb, no_kanji),
                )

        # Senses — POS carries forward until explicitly changed (JMdict convention)
        current_pos: list[str] = []
        for pos_idx, sense in enumerate(entry.findall("sense")):
            new_pos = [shorten_pos(p.text) for p in sense.findall("pos") if p.text]
            if new_pos:
                current_pos = new_pos

            glosses = [
                g.text
                for g in sense.findall("gloss")
                if g.text
                and g.get("{http://www.w3.org/XML/1998/namespace}lang", "eng") == "eng"
            ]
            # Fallback: gloss elements with no lang attribute
            if not glosses:
                glosses = [g.text for g in sense.findall("gloss") if g.text]

            # Collect misc/field/dialect/s_inf tags
            misc_parts = []
            for m in sense.findall("misc"):
                if m.text:
                    misc_parts.append(MISC_ABBREV.get(m.text, m.text))
            for f in sense.findall("field"):
                if f.text:
                    misc_parts.append(f.text)
            for d in sense.findall("dial"):
                if d.text:
                    misc_parts.append(d.text)
            for s in sense.findall("s_inf"):
                if s.text:
                    misc_parts.append(s.text)
            misc_str = "\t".join(misc_parts)

            if glosses:
                cur.execute(
                    "INSERT INTO sense VALUES (?,?,?,?,?)",
                    (
                        entry_id,
                        pos_idx,
                        ",".join(current_pos),
                        "\t".join(glosses),
                        misc_str,
                    ),
                )

        count += 1
        if count % 20_000 == 0:
            conn.commit()
            print(f"  {count:,} entries...")

    conn.commit()
    print(f"  Total: {count:,} entries inserted")


def download_kanjidic() -> bytes:
    print(f"Downloading {KANJIDIC2_URL} ...")
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    with urllib.request.urlopen(KANJIDIC2_URL, context=ctx) as resp:
        compressed = resp.read()
    data = gzip.decompress(compressed)
    print(f"  Decompressed to {len(data) // 1024} KB")
    return data


def parse_and_insert_kanjidic(xml_bytes: bytes, conn: sqlite3.Connection) -> None:
    print("Parsing KANJIDIC2 and inserting kanji entries...")
    # Strip DOCTYPE block — may contain internal subset [...] before closing >
    text = xml_bytes.decode("utf-8")
    doctype_start = text.find("<!DOCTYPE")
    if doctype_start >= 0:
        # Look for internal subset end "]>" first, then bare ">"
        bracket_end = text.find("]>", doctype_start)
        if bracket_end >= 0:
            doctype_end = bracket_end + 2  # skip past "]>"
        else:
            gt = text.find(">", doctype_start)
            doctype_end = gt + 1 if gt >= 0 else len(text)
        text = text[:doctype_start] + text[doctype_end:]
    root = ET.fromstring(text.encode("utf-8"))

    cur = conn.cursor()
    count = 0
    for char in root.iter("character"):
        literal_el = char.find("literal")
        if literal_el is None or not literal_el.text:
            continue
        literal = literal_el.text.strip()

        misc_el = char.find("misc")
        grade = 0
        stroke_count = 0
        jlpt_raw = 0
        if misc_el is not None:
            grade_el = misc_el.find("grade")
            if grade_el is not None and grade_el.text:
                try:
                    grade = int(grade_el.text)
                except ValueError:
                    pass
            sc_el = misc_el.find("stroke_count")
            if sc_el is not None and sc_el.text:
                try:
                    stroke_count = int(sc_el.text)
                except ValueError:
                    pass
            jlpt_el = misc_el.find("jlpt")
            if jlpt_el is not None and jlpt_el.text:
                try:
                    jlpt_raw = int(jlpt_el.text)
                except ValueError:
                    pass

        # Convert old JLPT (1-4, 4=easiest) to new N-level (1-4 → N5,N4,N3,N2)
        # Store as 5=N5, 4=N4, 3=N3, 2=N2, 0=not in JLPT
        jlpt_n = {4: 5, 3: 4, 2: 3, 1: 2}.get(jlpt_raw, 0)

        on_readings = []
        kun_readings = []
        meanings = []
        rm = char.find("reading_meaning")
        if rm is not None:
            for rmg in rm.findall("rmgroup"):
                for r in rmg.findall("reading"):
                    r_type = r.get("r_type", "")
                    if r.text:
                        if r_type == "ja_on":
                            on_readings.append(r.text)
                        elif r_type == "ja_kun":
                            kun_readings.append(r.text)
                for m in rmg.findall("meaning"):
                    lang = m.get("{http://www.w3.org/XML/1998/namespace}lang", "en")
                    if lang == "en" and m.text:
                        meanings.append(m.text)
                    elif not m.get("{http://www.w3.org/XML/1998/namespace}lang") and m.text:
                        meanings.append(m.text)

        cur.execute(
            "INSERT OR IGNORE INTO kanjidic VALUES (?,?,?,?,?,?,?)",
            (
                literal,
                "\t".join(meanings),
                ",".join(on_readings),
                ",".join(kun_readings),
                jlpt_n,
                grade,
                stroke_count,
            ),
        )
        count += 1

    conn.commit()
    print(f"  Total: {count:,} kanji inserted")


def build_sqlite(db_path: Path) -> None:
    if db_path.exists():
        db_path.unlink()
        print(f"Removed existing {db_path}")

    xml_bytes = download_jmdict()
    print("Pre-processing XML entities...")
    clean_xml = preprocess_xml(xml_bytes)
    del xml_bytes  # free memory

    print(f"Building {db_path} ...")
    conn = sqlite3.connect(db_path)
    conn.execute("PRAGMA journal_mode=OFF")   # faster bulk insert (no WAL during build)
    conn.execute("PRAGMA synchronous=OFF")
    conn.execute("PRAGMA cache_size=65536")

    create_schema(conn)
    parse_and_insert(clean_xml, conn)

    kanjidic_xml = download_kanjidic()
    parse_and_insert_kanjidic(kanjidic_xml, conn)
    del kanjidic_xml

    print("Optimizing (ANALYZE + VACUUM)...")
    conn.execute("ANALYZE")
    conn.execute("VACUUM")
    conn.close()


# The 8 IPADIC binary files Kuromoji loads from its JAR classpath. Extracted
# from kuromoji-ipadic-*.jar into the pack's tokenizer/ subdir so the APK
# can strip them via packagingOptions.resources.excludes. Names must match
# exactly what SimpleResourceResolver asks for (bare filenames, no prefix).
KUROMOJI_IPADIC_BINS = (
    "characterDefinitions.bin",
    "connectionCosts.bin",
    "doubleArrayTrie.bin",
    "tokenInfoDictionary.bin",
    "tokenInfoFeaturesMap.bin",
    "tokenInfoPartOfSpeechMap.bin",
    "tokenInfoTargetMap.bin",
    "unknownDictionary.bin",
)

# Resource path prefix inside the JAR. `SimpleResourceResolver` calls
# `Tokenizer.class.getResourceAsStream(basename)`, which resolves to
# "/com/atilika/kuromoji/ipadic/<basename>" at the JAR root.
KUROMOJI_IPADIC_JAR_PREFIX = "com/atilika/kuromoji/ipadic/"


def _sha256_of(path: Path) -> str:
    import hashlib
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def extract_kuromoji_bins(jar_path: Path, tokenizer_dir: Path) -> list[dict]:
    """Extract the IPADIC bin files from `kuromoji-ipadic-*.jar` into
    [tokenizer_dir]. Returns a list of manifest-shape dicts (path relative
    to the pack root, size, sha256) for appending to manifest.files."""
    tokenizer_dir.mkdir(parents=True, exist_ok=True)
    entries: list[dict] = []
    with zipfile.ZipFile(jar_path, "r") as jar:
        names = set(jar.namelist())
        for basename in KUROMOJI_IPADIC_BINS:
            jar_entry = KUROMOJI_IPADIC_JAR_PREFIX + basename
            if jar_entry not in names:
                raise RuntimeError(
                    f"Kuromoji JAR at {jar_path} is missing entry {jar_entry}. "
                    "Pass --kuromoji-jar pointing at kuromoji-ipadic-0.9.0.jar "
                    "(typically under ~/.gradle/caches/modules-2/files-2.1/"
                    "com.atilika.kuromoji/kuromoji-ipadic/0.9.0/).")
            out_path = tokenizer_dir / basename
            with jar.open(jar_entry) as src, out_path.open("wb") as dst:
                while True:
                    chunk = src.read(1 << 20)
                    if not chunk:
                        break
                    dst.write(chunk)
            entries.append({
                "path": f"tokenizer/{basename}",
                "size": out_path.stat().st_size,
                "sha256": _sha256_of(out_path),
            })
    print(f"Extracted {len(entries)} Kuromoji files to {tokenizer_dir}")
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
    if tokenizer_entries:
        files.extend(tokenizer_entries)
        total += sum(int(e["size"]) for e in tokenizer_entries)
    manifest = {
        "langId": "ja",
        "schemaVersion": 1,
        "packVersion": pack_version,
        "appMinVersion": 0,
        "files": files,
        "totalSize": total,
        "licenses": [
            {
                "component": "JMdict",
                "license": "CC-BY-SA-4.0",
                "attribution": "© EDRDG, https://www.edrdg.org/jmdict/edict_doc.html",
            },
            {
                "component": "KANJIDIC2",
                "license": "CC-BY-SA-4.0",
                "attribution": "© EDRDG",
            },
            {
                "component": "Kuromoji IPADIC",
                "license": "Apache-2.0",
                "attribution": "© Atilika Inc. (IPADIC: IPA Dictionary, © ICOT)",
            },
        ],
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
            for p in sorted(tokenizer_dir.iterdir()):
                if p.is_file():
                    z.write(p, arcname=f"tokenizer/{p.name}")
    print(f"Wrote {zip_path} ({zip_path.stat().st_size:,} bytes)")


def main() -> int:
    parser = argparse.ArgumentParser(description="Build the Japanese language pack")
    parser.add_argument("--output", type=Path, required=True, help="Output directory")
    parser.add_argument(
        "--kuromoji-jar",
        type=Path,
        required=False,
        help="Path to kuromoji-ipadic-*.jar. When provided, its 8 IPADIC "
             "bin files are extracted into tokenizer/ in the pack so the "
             "APK can strip them. Omit to produce a tokenizer-less pack "
             "(dict.sqlite only, classpath-Kuromoji dependency).",
    )
    parser.add_argument(
        "--rebuild-sqlite",
        action="store_true",
        help="Force regeneration of dict.sqlite from JMdict/KANJIDIC sources. "
             "When omitted, an existing dict.sqlite in the output dir is reused.",
    )
    parser.add_argument("--pack-version", type=int, default=1)
    args = parser.parse_args()

    args.output.mkdir(parents=True, exist_ok=True)
    db_path = args.output / "dict.sqlite"
    manifest_path = args.output / "manifest.json"
    zip_path = args.output / "ja.zip"
    tokenizer_dir = args.output / "tokenizer"

    # Skip the (slow) JMdict rebuild if dict.sqlite already exists. Useful
    # when iterating on the tokenizer extraction step without re-downloading
    # JMdict_e.gz + KANJIDIC2 on every run. Pass --rebuild-sqlite to force.
    if db_path.is_file() and not args.rebuild_sqlite:
        print(f"Reusing existing {db_path} ({db_path.stat().st_size:,} bytes) — "
              f"pass --rebuild-sqlite to regenerate from JMdict source")
    else:
        build_sqlite(db_path)
    tokenizer_entries = None
    if args.kuromoji_jar is not None:
        if not args.kuromoji_jar.is_file():
            print(f"error: --kuromoji-jar not a file: {args.kuromoji_jar}", file=sys.stderr)
            return 1
        tokenizer_entries = extract_kuromoji_bins(args.kuromoji_jar, tokenizer_dir)
    build_manifest(db_path, manifest_path, args.pack_version, tokenizer_entries)
    build_zip(
        db_path,
        manifest_path,
        zip_path,
        tokenizer_dir if tokenizer_entries else None,
    )

    print()
    print("Next steps:")
    print(f"  1. sha256sum {zip_path}")
    print(f"  2. Upload {zip_path} to dominostars/playtranslate-langpacks tag ja-v1")
    print(f"  3. Update assets/langpack_catalog.json with URL + sha256 + new size")
    return 0


if __name__ == "__main__":
    sys.exit(main())
