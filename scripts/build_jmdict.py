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

from __future__ import annotations

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

# Cap how many Tatoeba example sentences are bundled per (entry, sense). One
# keeps the per-sense UI rows compact (matching the convention target packs
# use) and bounds the JA pack's example-table growth to a small fraction of
# the DB. Bump if you want more examples per sense at the cost of pack size.
MAX_EXAMPLES_PER_SENSE = 1

# Hard cap on JA example text length. Tatoeba contributors occasionally upload
# multi-clause monsters that bloat the pack and overwhelm the per-sense rows;
# 200 codepoints lops off the worst outliers without hurting normal sentences.
MAX_EXAMPLE_LEN = 200

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


# ── Per-headword rank score (for cross-entry kana ranking) ────────────────────
#
# Empirically validated against JMdict_e (2026-05-10): 11/12 spot-check passes,
# 0 archaic-winner flags, recommended bonus magnitude 1.5M. See
# project_kana_lookup_ranking.md and the corresponding plan file.

PRIORITY_TAGS = {"ichi1", "news1", "gai1", "spec1", "spec2"}
FREQ_TAGS = PRIORITY_TAGS | {f"nf{i:02d}" for i in range(1, 49)}

# JMdict <re_inf> entity NAMES we treat as archaic (irregular / out-dated /
# rarely-used kana). Comparing against entity names (short form) requires the
# value->name reverse map produced by preprocess_xml; the entity VALUES that
# the XML actually contains are descriptive sentences ("rarely used kana
# form" etc.) which we don't want to spread across the codebase.
ARCHAIC_RE_INF = {"ik", "ok", "rk"}

# JMdict <misc> entity NAME for "usually written using kana alone".
UK_ENTITY_NAME = "uk"


def compute_reading_rank_score(
    re_pri_tags: set, re_inf_tags: set, position: int
) -> int:
    """Per-reading rank score for cross-entry ranking on pure-kana queries.

    Mirrors yomitan-import's headword.Score (`+1M IsPriority + 1M IsFrequent`)
    plus a kana-archaic penalty derived from re_inf info tags. The validation
    report dropped the kanji-archaic penalty (it backfires because rK marks
    'usually-kana' words) — only re_inf archaic tags penalize here.
    """
    score = 0
    if PRIORITY_TAGS & re_pri_tags:
        score += 1_000_000
    if len(FREQ_TAGS & re_pri_tags) > 1:
        score += 1_000_000
    score -= 5_000_000 * len(ARCHAIC_RE_INF & re_inf_tags)
    score -= 10_000 * position
    return score


def compute_headword_rank_score(ke_pri_tags: set, position: int) -> int:
    """Per-headword (kanji form) rank score for kanji-disambiguated queries.

    No archaic penalty: ke_inf tags like rK/sK mark 'usually-kana' words and
    penalizing them would suppress exactly the entries we want to elevate
    via the pure-kana uk-bonus. Validation report Mode C confirmed this.
    """
    score = 0
    if PRIORITY_TAGS & ke_pri_tags:
        score += 1_000_000
    if len(FREQ_TAGS & ke_pri_tags) > 1:
        score += 1_000_000
    score -= 10_000 * position
    return score


def compute_uk_readings_for_entry(entry, value_to_name: dict[str, str]) -> set:
    """Return the set of reading texts (reb strings) in this <entry> that are
    covered by some <sense> with `<misc>uk</misc>`, respecting `<stagr>`
    restrictions (a uk sense may apply only to specific readings).

    Used to set reading.uk_applicable per row at build time so the runtime
    pure-kana ranking SQL can apply a +1.5M bonus on (position == 0 AND
    uk_applicable == 1) without joining against sense at query time.
    """
    all_reading_texts = [
        r_ele.findtext("reb")
        for r_ele in entry.findall("r_ele")
        if r_ele.findtext("reb")
    ]
    uk_readings: set = set()
    for sense in entry.findall("sense"):
        sense_has_uk = False
        for m in sense.findall("misc"):
            if m.text and value_to_name.get(m.text) == UK_ENTITY_NAME:
                sense_has_uk = True
                break
        if not sense_has_uk:
            continue
        stagrs = [s.text for s in sense.findall("stagr") if s.text]
        if not stagrs:
            uk_readings.update(all_reading_texts)
        else:
            uk_readings.update(stagrs)
    return uk_readings


def extract_re_inf_names(r_ele, value_to_name: dict[str, str]) -> set:
    """Extract the set of <re_inf> entity NAMES (short form: 'ik', 'ok',
    'rk') for one <r_ele>. Converts expanded entity values back via
    value_to_name. Empty set when no info tags."""
    out: set = set()
    for inf in r_ele.findall("re_inf"):
        if inf.text:
            name = value_to_name.get(inf.text)
            if name:
                out.add(name)
    return out


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


def preprocess_xml(xml_bytes: bytes) -> tuple[bytes, dict[str, str]]:
    """
    ElementTree can't resolve DOCTYPE entities from inline DTDs.
    Extract them with regex, strip the DOCTYPE block, then substitute.

    Returns (preprocessed_xml, value_to_name). The value->name map lets
    callers convert expanded entity values (e.g. "word usually written
    using kana alone") back to short entity names (e.g. "uk") for compact
    storage and stable comparison. Most JMdict priority entities have
    name == value so the map is identity for them; misc/info/dialect
    entities have descriptive values, so the map is load-bearing there.
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

    # Reverse map for converting expanded values back to entity names. If two
    # entities share the same value the last one wins; in JMdict practice
    # values are unique within categories so this is fine.
    value_to_name = {v: k for k, v in entities.items()}

    return text.encode("utf-8"), value_to_name


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
            text       TEXT    NOT NULL,
            -- JMdict <ke_pri> tags for THIS kanji form, comma-joined and
            -- sorted (e.g. "ichi1,news1,nf07"). Empty when no priority.
            ke_pri     TEXT    NOT NULL DEFAULT '',
            -- Per-headword rank score for cross-entry ranking on
            -- kanji-disambiguated queries. Computed at build time from
            -- ke_pri + position via compute_headword_rank_score. Scale is
            -- millions (positive priority signals + negative position
            -- penalty). Distinct from entry.freq_score (0-5, used for
            -- star display) — see project_kana_lookup_ranking.md.
            rank_score INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE reading (
            entry_id   INTEGER NOT NULL,
            position   INTEGER NOT NULL,
            text       TEXT    NOT NULL,
            no_kanji   INTEGER NOT NULL DEFAULT 0,
            -- JMdict's <re_pri> tags for THIS reading, comma-joined and
            -- sorted (e.g. "ichi1,nf03"). Most readings carry no priority
            -- and store ''. Distinct from entry.freq_score, which aggregates
            -- across every k_ele + r_ele in the entry — re_pri here is the
            -- per-reading subset only, which is the signal for ranking
            -- multi-entry kana-reading collisions (e.g. ここ → 此処 vs 九,
            -- where 九 carries high entry-level freq from きゅう/く but its
            -- ここ reading is unmarked). See project_kana_lookup_ranking.md.
            re_pri     TEXT    NOT NULL DEFAULT '',
            -- 0-5 score derived from re_pri via the same compute_freq_score
            -- function entry uses. Currently unused at runtime — superseded
            -- by rank_score (millions scale). Kept to avoid disrupting
            -- callers; cleanup is a separate concern.
            freq_score INTEGER NOT NULL DEFAULT 0,
            -- JMdict <re_inf> info tags for this reading as entity NAMES
            -- (short form: "ik", "ok", "rk"), comma-joined and sorted.
            -- Empty when no info tags. The compact name form lets the
            -- ranking SQL compare against {ik,ok,rk} cheaply.
            re_inf     TEXT    NOT NULL DEFAULT '',
            -- Per-reading rank score for pure-kana cross-entry ranking.
            -- Computed at build time from re_pri + re_inf + position via
            -- compute_reading_rank_score. Scale is millions; new in ja-v2.
            rank_score INTEGER NOT NULL DEFAULT 0,
            -- 1 if THIS reading is covered by some sense's <misc>uk</misc>
            -- (respecting <stagr> restrictions that scope a uk sense to
            -- specific readings). The pure-kana SQL adds a +1.5M bonus
            -- when uk_applicable=1 AND position=0. Validated empirically
            -- to flip 此処 above 個々 for ここ without raising 鴇 above
            -- 時 for とき.
            uk_applicable INTEGER NOT NULL DEFAULT 0
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
            on_readings  TEXT    NOT NULL DEFAULT '',
            kun_readings TEXT    NOT NULL DEFAULT '',
            jlpt         INTEGER NOT NULL DEFAULT 0,
            grade        INTEGER NOT NULL DEFAULT 0,
            stroke_count INTEGER NOT NULL DEFAULT 0
        );
        -- Per-language kanji glosses. KANJIDIC2 ships native meanings in
        -- multiple languages (en, fr, es, pt); this table stores each
        -- language's list of meanings as a \t-separated string so the
        -- runtime can serve the user's target language natively when the
        -- pack has coverage, and fall back to English otherwise.
        CREATE TABLE kanji_meaning (
            literal  TEXT NOT NULL,
            lang     TEXT NOT NULL,
            meanings TEXT NOT NULL,
            PRIMARY KEY (literal, lang)
        );
        CREATE INDEX idx_headword_text ON headword(text);
        CREATE INDEX idx_reading_text  ON reading(text);
        """
    )

    # The `example` table is owned by parse_and_insert_tatoeba and only
    # exists when --tatoeba-dir is supplied. Keeping it out of the base
    # schema means the JA reader's missing-table try/catch covers builds
    # where the contributor doesn't have Tatoeba data on disk.


def shorten_pos(raw: str) -> str:
    return POS_ABBREV.get(raw, raw)


def parse_and_insert(
    xml_bytes: bytes,
    conn: sqlite3.Connection,
    value_to_name: dict[str, str],
) -> None:
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

        # Pre-compute uk_applicable per reading. Scan senses for the &uk;
        # misc tag and respect <stagr> restrictions that scope a uk sense
        # to specific readings. Done before the reading inserts so each
        # row gets the right flag.
        uk_readings = compute_uk_readings_for_entry(entry, value_to_name)

        # Kanji forms (headword table — the name is schema-shared with
        # other source packs; for Japanese the values are genuine kanji.)
        for pos, k_ele in enumerate(entry.findall("k_ele")):
            keb = k_ele.findtext("keb")
            if not keb:
                continue
            ke_pri_tags = {p.text for p in k_ele.findall("ke_pri") if p.text}
            ke_pri_str = ",".join(sorted(ke_pri_tags))
            rank_score = compute_headword_rank_score(ke_pri_tags, pos)
            cur.execute(
                "INSERT INTO headword VALUES (?,?,?,?,?)",
                (entry_id, pos, keb, ke_pri_str, rank_score),
            )

        # Reading forms
        for pos, r_ele in enumerate(entry.findall("r_ele")):
            reb = r_ele.findtext("reb")
            if not reb:
                continue
            no_kanji = 1 if r_ele.find("re_nokanji") is not None else 0
            # Per-reading priority — JMdict's <re_pri> tags scoped to
            # this <r_ele>, NOT the entry-wide aggregate. Distinguishes
            # entries where the queried reading is dominant (carries
            # priority marks) from entries where it's a marginal
            # historical reading. Empty string when the reading has no
            # priority marks.
            re_pri_tags = {p.text for p in r_ele.findall("re_pri") if p.text}
            re_pri_str = ",".join(sorted(re_pri_tags))
            re_pri_freq_score = compute_freq_score(re_pri_tags)
            # Per-reading info tags. Stored as compact entity names; see
            # extract_re_inf_names for the value→name conversion rationale.
            re_inf_tags = extract_re_inf_names(r_ele, value_to_name)
            re_inf_str = ",".join(sorted(re_inf_tags))
            rank_score = compute_reading_rank_score(re_pri_tags, re_inf_tags, pos)
            uk_app = 1 if reb in uk_readings else 0
            cur.execute(
                "INSERT INTO reading VALUES (?,?,?,?,?,?,?,?,?)",
                (
                    entry_id,
                    pos,
                    reb,
                    no_kanji,
                    re_pri_str,
                    re_pri_freq_score,
                    re_inf_str,
                    rank_score,
                    uk_app,
                ),
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


# ── Tatoeba example sentences ────────────────────────────────────────────────
#
# Tatoeba publishes monthly dumps under https://downloads.tatoeba.org/exports/
# (CC-BY 2.0). For example-sentence indexing we need:
#
#   * `per_language/jpn/jpn_sentences.tsv.bz2`  — JA sentence id → text
#   * `per_language/eng/eng_sentences.tsv.bz2`  — EN sentence id → text
#   * `links.tar.bz2`                            — sentence_id ↔ translation_id
#   * `jpn_indices.tar.bz2`                      — JA sentences indexed against
#                                                  JMdict entries (the headword
#                                                  carries an ent_seq via
#                                                  embedded headword/sense info)
#
# `jpn_indices.csv` row format (TSV; one row per JA sentence):
#
#   <ja_sentence_id>\t<meaning_id>\t<indices>
#
# `<indices>` is a space-separated list of tokens, each like:
#
#   <headword>(<reading>)[sense_index_1based]{token_in_sentence}~
#
# Where the `(<reading>)`, `[sense]`, `{token}`, and trailing `~` are all
# optional. `~` flags Tatoeba's "good example" — hand-curated and high quality.
# The `[sense]` index is 1-based against JMdict's enumerated <sense> children
# (so sense 1 maps to entry.sense.position == 0).
#
# We resolve the headword token to a JMdict entry_id by looking it up in the
# `headword` (kanji) and `reading` (kana) tables we already built. This is
# robust whether Tatoeba ships ent_seq numerics or surface forms.


def _open_text(path: Path):
    """Open a file for line-by-line text reading. Auto-decompresses .bz2."""
    import bz2
    if path.suffix == ".bz2":
        return bz2.open(path, "rt", encoding="utf-8", errors="replace")
    return open(path, "r", encoding="utf-8", errors="replace")


def _parse_index_token(
    token: str,
) -> tuple[str, int | None, int | None, bool] | None:
    """Parse one token out of the `<indices>` column.

    Token format (anything after the headword is optional):
        <headword>(<reading_or_#entseq>)[<sense_1based>]{<surface>}~

    Returns (headword, ent_seq_or_None, sense_idx_1based_or_None, good_flag)
    or None when the token is malformed. The `(#NNNNNNN)` form carries the
    JMdict ent_seq for homograph disambiguation; we surface it so the
    indexer can look up the entry directly instead of guessing among
    candidates that share a surface.
    """
    if not token:
        return None
    good = token.endswith("~")
    if good:
        token = token[:-1]
    # Drop {...} surface form
    brace = token.find("{")
    if brace >= 0:
        end = token.find("}", brace)
        if end < 0:
            return None
        token = token[:brace] + token[end + 1 :]
    # Pull off [sense]
    sense: int | None = None
    bracket = token.find("[")
    if bracket >= 0:
        end = token.find("]", bracket)
        if end < 0:
            return None
        try:
            sense = int(token[bracket + 1 : end])
        except ValueError:
            sense = None
        token = token[:bracket] + token[end + 1 :]
    # Pull off (reading) — or (#ent_seq) for explicit homograph disambiguation
    ent_seq: int | None = None
    paren = token.find("(")
    if paren >= 0:
        end = token.find(")", paren)
        if end < 0:
            return None
        inner = token[paren + 1 : end]
        if inner.startswith("#"):
            try:
                ent_seq = int(inner[1:])
            except ValueError:
                ent_seq = None
        token = token[:paren] + token[end + 1 :]
    headword = token.strip()
    if not headword:
        return None
    return headword, ent_seq, sense, good


def _build_headword_index(
    conn: sqlite3.Connection,
) -> tuple[dict[str, list[int]], set[int]]:
    """Map every kanji surface and reading to its candidate entry_ids, plus
    the set of `is_common = 1` entry_ids for ambiguity tie-breaking.

    Multiple JMdict entries can share a surface (homographs). When the
    Tatoeba token ships an explicit `(#ent_seq)` disambiguator we look up
    the entry directly; when it doesn't, the indexer prefers a common
    candidate over a rare one to avoid attaching examples to obscure
    homographs.
    """
    index: dict[str, list[int]] = {}
    cur = conn.cursor()
    for table in ("headword", "reading"):
        for entry_id, text in cur.execute(f"SELECT entry_id, text FROM {table}"):
            index.setdefault(text, []).append(entry_id)
    # Dedup per key — a text can appear under both headword and reading
    # rows for the same entry.
    deduped = {k: list(dict.fromkeys(v)) for k, v in index.items()}
    common = {
        row[0]
        for row in cur.execute("SELECT id FROM entry WHERE is_common = 1")
    }
    return deduped, common


def parse_and_insert_tatoeba(
    tatoeba_dir: Path, conn: sqlite3.Connection
) -> int:
    """Index Tatoeba JA sentences against JMdict entries and insert into the
    `example` table. Returns the number of rows written.

    Skips silently when any of the four required files are missing — the
    pack still builds, just with no example sentences (the prior behavior).
    """
    jpn_sentences = tatoeba_dir / "jpn_sentences.tsv.bz2"
    eng_sentences = tatoeba_dir / "eng_sentences.tsv.bz2"
    links_csv = tatoeba_dir / "links.csv"
    jpn_indices = tatoeba_dir / "jpn_indices.csv"

    missing = [
        str(p)
        for p in (jpn_sentences, eng_sentences, links_csv, jpn_indices)
        if not p.is_file()
    ]
    if missing:
        print(f"Tatoeba: skipping example-sentence index — missing: {missing}")
        return 0

    print("Tatoeba: indexing example sentences...")

    # parse_and_insert_tatoeba owns the example table; recreate from scratch
    # so a re-run on a previously-built dict.sqlite (--rebuild-sqlite omitted)
    # doesn't accumulate stale rows.
    conn.executescript(
        """
        DROP TABLE IF EXISTS example;
        CREATE TABLE example (
            entry_id       INTEGER NOT NULL,
            sense_position INTEGER NOT NULL,
            position       INTEGER NOT NULL,
            text           TEXT    NOT NULL,
            translation    TEXT    NOT NULL DEFAULT ''
        );
        CREATE INDEX idx_example_entry ON example(entry_id, sense_position);
        """
    )

    print(f"  Loading JA sentences from {jpn_sentences.name}...")
    ja_text: dict[int, str] = {}
    with _open_text(jpn_sentences) as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            try:
                sid = int(parts[0])
            except ValueError:
                continue
            ja_text[sid] = parts[2]
    print(f"    {len(ja_text):,} JA sentences")

    print(f"  Loading EN sentences from {eng_sentences.name}...")
    en_text: dict[int, str] = {}
    with _open_text(eng_sentences) as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            try:
                sid = int(parts[0])
            except ValueError:
                continue
            en_text[sid] = parts[2]
    print(f"    {len(en_text):,} EN sentences")

    # Map each JA sentence to its first available EN translation. links.csv
    # is symmetric (id, translation_id), so a JA sentence can appear on
    # either side. We iterate once and bucket by JA-id.
    print(f"  Loading JA->EN links from {links_csv.name}...")
    ja_to_en: dict[int, int] = {}
    with _open_text(links_csv) as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 2:
                continue
            try:
                a = int(parts[0]); b = int(parts[1])
            except ValueError:
                continue
            # Pair must be JA on one side and EN on the other.
            if a in ja_text and b in en_text:
                ja_to_en.setdefault(a, b)
            elif b in ja_text and a in en_text:
                ja_to_en.setdefault(b, a)
    print(f"    {len(ja_to_en):,} JA sentences with at least one EN translation")

    print("  Building headword -> entry_id index from JMdict tables...")
    headword_index, common_ids = _build_headword_index(conn)
    sense_counts: dict[int, int] = {
        row[0]: row[1]
        for row in conn.execute(
            "SELECT entry_id, COUNT(*) FROM sense GROUP BY entry_id"
        )
    }
    print(
        f"    {len(headword_index):,} unique surface forms; "
        f"{len(common_ids):,} entries marked is_common; "
        f"{len(sense_counts):,} entries with senses"
    )

    # Bucket: (entry_id, sense_position_0based) → list of (sentence_id, good)
    # Tatoeba's [sense] is "specify when ambiguous, omit otherwise" — most
    # tokens carrying real demonstrations (~6× more than indexed ones) come
    # in without an explicit sense. We attach those to sense 0 when the
    # entry has exactly one sense (unambiguous), and skip otherwise so we
    # don't bias the first sense of multi-sense entries with mismatched
    # examples.
    print(f"  Parsing {jpn_indices.name}...")
    buckets: dict[tuple[int, int], list[tuple[int, bool]]] = {}
    rows = 0
    matched = 0
    matched_default_sense = 0
    sense_missing_multi = 0
    surface_unmatched = 0
    with _open_text(jpn_indices) as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            try:
                jid = int(parts[0])
            except ValueError:
                continue
            if jid not in ja_to_en:
                continue
            rows += 1
            for tok in parts[2].split(" "):
                parsed = _parse_index_token(tok)
                if parsed is None:
                    continue
                headword, ent_seq, sense_1b, good = parsed
                # Resolve to a single entry_id: explicit (#ent_seq)
                # disambiguator wins; otherwise prefer a common candidate;
                # otherwise pick the first (lowest ent_seq, which JMdict
                # tends to assign to higher-frequency variants).
                entry_id: int | None = None
                if ent_seq is not None:
                    entry_id = ent_seq
                else:
                    candidates = headword_index.get(headword)
                    if not candidates:
                        surface_unmatched += 1
                        continue
                    entry_id = next(
                        (c for c in candidates if c in common_ids),
                        candidates[0],
                    )
                if sense_1b is None:
                    # Sense-less token: only attach when the entry has a
                    # single sense. Multi-sense entries get skipped to
                    # avoid biasing sense 0.
                    if sense_counts.get(entry_id) != 1:
                        sense_missing_multi += 1
                        continue
                    sense_0b = 0
                    matched_default_sense += 1
                else:
                    sense_0b = sense_1b - 1
                matched += 1
                buckets.setdefault((entry_id, sense_0b), []).append((jid, good))
    print(
        f"    {rows:,} indexed JA sentences; "
        f"{matched:,} (entry,sense) attachments "
        f"({matched_default_sense:,} via single-sense default); "
        f"{sense_missing_multi:,} skipped (no sense index, multi-sense entry); "
        f"{surface_unmatched:,} skipped (surface not in JMdict)"
    )

    print(f"  Selecting up to {MAX_EXAMPLES_PER_SENSE} examples per sense...")
    cur = conn.cursor()
    inserted = 0
    for (entry_id, sense_pos), entries in buckets.items():
        # Prefer ~-flagged "good examples", then shorter sentences.
        scored: list[tuple[int, int, str, str]] = []
        for sid, good in entries:
            ja = ja_text.get(sid)
            if ja is None:
                continue
            if len(ja) > MAX_EXAMPLE_LEN:
                continue
            en = en_text.get(ja_to_en[sid], "")
            if len(en) > MAX_EXAMPLE_LEN * 2:
                # Allow the EN translation a bit more slack but still cap.
                continue
            # (good_first, length) — sort ascending picks ~-flagged shortest.
            scored.append((0 if good else 1, len(ja), ja, en))
        if not scored:
            continue
        scored.sort(key=lambda t: (t[0], t[1]))
        kept = scored[:MAX_EXAMPLES_PER_SENSE]
        for pos, (_, _, ja, en) in enumerate(kept):
            cur.execute(
                "INSERT INTO example VALUES (?, ?, ?, ?, ?)",
                (entry_id, sense_pos, pos, ja, en),
            )
            inserted += 1

    conn.commit()
    print(f"  Inserted {inserted:,} example rows across {len(buckets):,} (entry,sense) pairs")
    return inserted


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
        # Meanings keyed by BCP-47 language code. KANJIDIC2 ships several;
        # meanings without an xml:lang attribute default to English per the
        # KANJIDIC2 spec.
        meanings_by_lang: dict[str, list[str]] = {}
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
                    if not m.text:
                        continue
                    # KANJIDIC2 tags non-English meanings with a bare
                    # `m_lang` attribute (not xml:lang like JMdict). Absent
                    # attribute means English per the KANJIDIC2 spec.
                    lang = m.get("m_lang", "en")
                    meanings_by_lang.setdefault(lang, []).append(m.text)

        cur.execute(
            "INSERT OR IGNORE INTO kanjidic VALUES (?,?,?,?,?,?)",
            (
                literal,
                ",".join(on_readings),
                ",".join(kun_readings),
                jlpt_n,
                grade,
                stroke_count,
            ),
        )
        for lang, meanings in meanings_by_lang.items():
            if not meanings:
                continue
            cur.execute(
                "INSERT OR IGNORE INTO kanji_meaning VALUES (?,?,?)",
                (literal, lang, "\t".join(meanings)),
            )
        count += 1

    conn.commit()
    # Summarize per-language coverage so whoever runs the build can sanity-
    # check that native non-English meanings landed in the pack.
    lang_counts = cur.execute(
        "SELECT lang, COUNT(*) FROM kanji_meaning GROUP BY lang ORDER BY lang"
    ).fetchall()
    coverage = ", ".join(f"{lang}={n:,}" for lang, n in lang_counts)
    print(f"  Total: {count:,} kanji inserted ({coverage})")


def build_sqlite(db_path: Path) -> None:
    if db_path.exists():
        db_path.unlink()
        print(f"Removed existing {db_path}")

    xml_bytes = download_jmdict()
    print("Pre-processing XML entities...")
    clean_xml, value_to_name = preprocess_xml(xml_bytes)
    del xml_bytes  # free memory

    print(f"Building {db_path} ...")
    conn = sqlite3.connect(db_path)
    conn.execute("PRAGMA journal_mode=OFF")   # faster bulk insert (no WAL during build)
    conn.execute("PRAGMA synchronous=OFF")
    conn.execute("PRAGMA cache_size=65536")

    create_schema(conn)
    parse_and_insert(clean_xml, conn, value_to_name)

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
    include_tatoeba_license: bool = False,
) -> None:
    size = db_path.stat().st_size
    files: list[dict] = [{"path": "dict.sqlite", "size": size, "sha256": None}]
    total = size
    if tokenizer_entries:
        files.extend(tokenizer_entries)
        total += sum(int(e["size"]) for e in tokenizer_entries)
    licenses = [
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
    ]
    if include_tatoeba_license:
        licenses.append(
            {
                "component": "Tatoeba example sentences",
                "license": "CC-BY-2.0",
                "attribution": "© Tatoeba contributors, https://tatoeba.org",
            }
        )
    manifest = {
        "langId": "ja",
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
    parser.add_argument(
        "--tatoeba-dir",
        type=Path,
        required=False,
        help="Path to a directory containing Tatoeba CSV exports "
             "(jpn_sentences.tsv.bz2, eng_sentences.tsv.bz2, links.csv, "
             "jpn_indices.csv). When provided, the build indexes Tatoeba "
             "JA sentences against JMdict entries and inserts them into the "
             "`example` table. Omit to produce a pack without examples "
             "(prior behavior; the JA reader's missing-table guard handles "
             "it).",
    )
    parser.add_argument("--pack-version", type=int, default=2)
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

    # Tatoeba pass runs as its own step so it can either (a) extend a fresh
    # build_sqlite() output, or (b) be re-run against an existing dict.sqlite
    # without the slow JMdict rebuild. The function recreates the `example`
    # table from scratch, so a re-run is idempotent.
    if args.tatoeba_dir is not None:
        if not args.tatoeba_dir.is_dir():
            print(
                f"error: --tatoeba-dir not a directory: {args.tatoeba_dir}",
                file=sys.stderr,
            )
            return 1
        conn = sqlite3.connect(db_path)
        conn.execute("PRAGMA journal_mode=OFF")
        conn.execute("PRAGMA synchronous=OFF")
        conn.execute("PRAGMA cache_size=65536")
        try:
            inserted = parse_and_insert_tatoeba(args.tatoeba_dir, conn)
            if inserted > 0:
                print("Re-optimizing after example inserts (ANALYZE + VACUUM)...")
                conn.execute("ANALYZE")
                conn.execute("VACUUM")
        finally:
            conn.close()

    tokenizer_entries = None
    if args.kuromoji_jar is not None:
        if not args.kuromoji_jar.is_file():
            print(f"error: --kuromoji-jar not a file: {args.kuromoji_jar}", file=sys.stderr)
            return 1
        tokenizer_entries = extract_kuromoji_bins(args.kuromoji_jar, tokenizer_dir)
    build_manifest(
        db_path,
        manifest_path,
        args.pack_version,
        tokenizer_entries,
        include_tatoeba_license=args.tatoeba_dir is not None,
    )
    build_zip(
        db_path,
        manifest_path,
        zip_path,
        tokenizer_dir if tokenizer_entries else None,
    )

    print()
    print("Next steps:")
    print(f"  1. sha256sum {zip_path}")
    print(f"  2. Upload {zip_path} to dominostars/playtranslate-langpacks tag ja-v2")
    print(f"  3. Update assets/langpack_catalog.json with URL + sha256 + new size")
    return 0


if __name__ == "__main__":
    sys.exit(main())
