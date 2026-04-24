#!/usr/bin/env python3
"""
Build a source-language pack for PlayTranslate from a kaikki.org
Wiktionary JSON-Lines extract (English Wiktionary's <LANG> entries).

Originally Latin-only (hence the filename); now also handles Korean,
which shares the same Wiktionary→SQLite pipeline because the pack
schema (entry/headword/sense) is script-agnostic. Korean skips Snowball
stemming entirely — runtime morphology is handled by Lucene's Nori
tokenizer in KoreanEngine, so the pack only stores citation-form lemmas.
TODO: rename to `build_wiktionary_dict.py` once nothing in-flight
references the current filename.

One script, parameterised by `--lang`. Supports any source language
that kaikki.org publishes AND that has a `word_frequency` locale in
the `wordfreq` package. Verified list:

    ca cs da de en es fi fr hu id it ko nb nl no pl pt ro sv tr vi

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
   WiktionaryDictionaryManager (`kanjidic` stays empty for non-JA packs).
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
- `headword.text`  -> lemma surface (position 0), Snowball stem (position 1),
                     or redirect alias (position 2). See the alias pass in
                     build_sqlite for what position 2 represents.
- `reading.text`   -> UNUSED for Latin (no pronunciation data).
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

# Turkish case mapping: default Python `str.lower()` uses Unicode folding
# (`I` → `i`, `İ` → `i` + combining dot), not Turkish rules (`I` → `ı`,
# `İ` → `i`). The runtime Android side lowercases queries with
# `SourceLangId.TR.locale`, so pack headwords have to match that output
# or uppercase OCR of words like `TIR`/`LGBTI` silently misses.
_TR_UPPER_MAP = str.maketrans({"I": "ı", "İ": "i"})


# Max usage examples kept per sense. Kaikki frequently ships 5-10 for
# well-covered entries; three is enough to illustrate without ballooning
# pack size (measured: French rises ~400 KB zipped at cap=3).
MAX_EXAMPLES_PER_SENSE = 3


def extract_examples(sense: dict) -> list[tuple[str, str]]:
    """Pull up to MAX_EXAMPLES_PER_SENSE usage examples out of a kaikki
    sense dict. Each example becomes a (text, translation) tuple where
    translation is "" when the example is monolingual.

    Filters:
      - Empty `text`: dropped (unusable).
      - `english` that's Wiktionary's "please add" placeholder: coerced
        to "" so the monolingual fallback path kicks in at render time
        instead of showing the placeholder verbatim.
      - Duplicate texts within the same sense: the first occurrence wins
        (kaikki occasionally ships repeats).
    """
    out: list[tuple[str, str]] = []
    seen: set[str] = set()
    for ex in sense.get("examples") or []:
        text = (ex.get("text") or "").strip()
        if not text or text in seen:
            continue
        seen.add(text)
        translation = (ex.get("english") or "").strip()
        if "please add" in translation.lower():
            translation = ""
        out.append((text, translation))
        if len(out) >= MAX_EXAMPLES_PER_SENSE:
            break
    return out


def lower_for_lang(word: str, lang: str) -> str:
    """Locale-aware lowercase for pack headword keys. Turkish needs the
    I/İ remap before default folding; other supported languages fall back
    to plain `str.lower()` because their case rules match Unicode default
    for the ASCII letters we care about."""
    if lang == "tr":
        word = word.translate(_TR_UPPER_MAP)
    return word.lower()


# Frequency threshold (Zipf scale). Words with frequency below this are
# dropped. 1e-6 = "at least one occurrence per million words" — yields
# ~30-50k entries for well-covered languages.
MIN_FREQUENCY = 1e-6

# Higher threshold for the is_common flag. Roughly top 3000 common words.
COMMON_FREQUENCY = 1e-4

# Map our 2-letter code → snowballstemmer algorithm name. Must match what
# LatinEngine uses at runtime (Lucene's org.tartarus.snowball.ext.*Stemmer)
# so stem-indexed rows are reachable by the runtime stem query.
# Languages with no Snowball stemmer map to None and skip stem indexing:
#   - Vietnamese, Indonesian: runtime stemmer is also null; the stem-fallback
#     path in WiktionaryDictionaryManager short-circuits when stem == surface,
#     so the asymmetry is harmless.
#   - Korean: agglutinative; morphology is decomposed at runtime by Lucene
#     Nori in KoreanEngine, which emits citation-form lemmas directly. The
#     pack only stores lemma surfaces — Nori does the "stemming" equivalent.
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
    "ko": None,
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
    readers (DictionaryManager / WiktionaryDictionaryManager) can share
    query code."""
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
        CREATE TABLE example (
            entry_id       INTEGER NOT NULL,
            sense_position INTEGER NOT NULL,
            position       INTEGER NOT NULL,
            text           TEXT    NOT NULL,
            translation    TEXT    NOT NULL DEFAULT ''
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
        CREATE INDEX idx_example_entry ON example(entry_id, sense_position);
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
    # with no inflection, and for Korean where KOMORAN does morphological
    # analysis at runtime). When present, every entry gets a second
    # `headword.text` row pointing the stem at the same entry_id — this
    # is what makes WiktionaryDictionaryManager's stem fallback reach the
    # lemma for REGULAR inflections (e.g. `cani` and `cane` both stem to
    # `can`). Irregular forms (e.g. `detto` / `dire` stem to `dett` /
    # `dir`) need the redirect-alias pass below.
    if lang not in SNOWBALL_ALGO_FOR_LANG:
        raise ValueError(
            f"No SNOWBALL_ALGO_FOR_LANG mapping for '{lang}'. "
            "Add it (or None) to build_latin_dict.py."
        )
    algo = SNOWBALL_ALGO_FOR_LANG[lang]
    stemmer = _snowball_stemmer(algo) if algo else None

    # Korean: load the raw wordfreq corpus dict once. Bypasses wordfreq's
    # `word_frequency()` path which tokenizes via MeCab (not available on
    # Windows Python). The dict is morpheme-level (built from MeCab-ko-
    # processed OpenSubtitles), so verb/adjective citation forms like
    # `먹다` return 0 — we strip the trailing `다` for those POS before
    # lookup to hit the real morpheme frequency (`먹`).
    ko_freq_dict: Optional[dict[str, float]] = None
    if lang == "ko":
        from wordfreq import get_frequency_dict
        ko_freq_dict = get_frequency_dict("ko")
        print(f"  loaded wordfreq ko corpus: {len(ko_freq_dict):,} morphemes")

    entry_id = 0
    kept = 0
    dropped_redirect = 0
    stem_rows = 0
    example_rows = 0
    seen_headwords: set[str] = set()

    # Pass 1 populates this map; pass 2 consults it to check whether a
    # redirect entry's target lemma was actually kept in the pack. A
    # surface like "volontario" appears TWICE in Italian Wiktionary — as
    # a noun ("volunteer") and as an adj ("voluntary") — each with its
    # own entry_id. Both meanings are valid for the inflected surface
    # `volontari`, so we track every entry_id per word and pass 2 emits
    # an alias row for each.
    kept_lemma_ids: dict[str, list[int]] = {}

    scanned = 0
    for obj in iter_kaikki(input_path):
        scanned += 1
        if scanned % 100000 == 0:
            print(
                f"  [pass1] {scanned:,} scanned, {kept:,} kept, "
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
        # of the real gloss). Pass 2 below converts their surfaces into
        # alias rows so users can still reach the lemma.
        if is_redirect_entry(obj):
            dropped_redirect += 1
            continue

        # Frequency cut — drops rare archaic words, misspellings, and
        # obscure technical terms that bloat the pack.
        #
        # wordfreq's Turkish corpus is inconsistent about case folding.
        # Some words are indexed under the Unicode-default fold (e.g.
        # `LGBTI` → `lgbti`, because `str.lower()` keeps the dotless
        # plural mapping `I → i`). Others are indexed under the
        # Turkish-aware fold (e.g. `İstanbul` → `istanbul`, because
        # Python's default decomposes `İ` → `i + ◌̇` and wordfreq
        # collapsed that during corpus build).
        # Probing both and taking the max is the only way to keep both
        # `LGBTI` AND `İngilizce`-style headwords in the pack.
        #
        # Korean: wordfreq's `word_frequency()` path tokenizes via MeCab
        # which isn't available on Windows Python, so we consult the raw
        # corpus dict directly (loaded once above). The corpus stores
        # morpheme-level keys — nouns resolve as-is; verbs and adjectives
        # need the citation `다` stripped before lookup (Wiktionary stores
        # `먹다`, corpus stores `먹`). Entries missed by the corpus
        # (archaic words, specialized terminology) are kept but flagged
        # with freq=None so the fallback scoring branch below handles
        # them with a Wiktionary-intrinsic proxy capped below the
        # real-frequency range.
        word_lower = lower_for_lang(word, lang)
        if lang == "ko":
            key = word_lower
            if pos_raw in ("verb", "adj") and len(key) > 1 and key.endswith("다"):
                key = key[:-1]
            ko_freq = ko_freq_dict.get(key, 0.0) if ko_freq_dict is not None else 0.0
            freq = ko_freq if ko_freq >= MIN_FREQUENCY else None
            # No frequency cut for Korean: we want every non-redirect
            # entry in the pack, even archaic ones (the corpus miss rate
            # for the long tail is high, and dropping those would hurt
            # Hanja aliases more than it helps pack size).
        else:
            freq = max(
                word_frequency(word.lower(), wordfreq_locale),
                word_frequency(word_lower, wordfreq_locale),
            )
            if freq < MIN_FREQUENCY:
                continue

        # Collect senses into structured records (glosses + examples) so we
        # can emit one sense row per kaikki sense and thread each sense's
        # examples through the new example table. `glosses_list` is kept as
        # a flat view for the Korean freq-score heuristic below, which only
        # needs gloss count.
        senses_data: list[dict] = []
        glosses_list: list[str] = []
        for sense in (obj.get("senses") or [])[:MAX_SENSES_PER_ENTRY]:
            # Skip individual redirect senses even on entries we're keeping
            # (the `is_redirect_entry` check above only drops entries where
            # ALL senses are redirects).
            if is_redirect_sense(sense):
                continue
            this_glosses: list[str] = []
            for g in sense.get("glosses") or []:
                g_clean = (g or "").strip()
                if g_clean:
                    this_glosses.append(g_clean)
                    glosses_list.append(g_clean)
            if not this_glosses:
                continue
            this_examples = extract_examples(sense)
            senses_data.append({"glosses": this_glosses, "examples": this_examples})
        if not senses_data:
            continue

        # De-duplicate (word, pos) — kaikki sometimes emits repeats.
        # For Korean, distinct etymologies at the same (word, pos) are
        # separate dictionary entries (눈 eye vs 눈 snow, 밤 night vs 밤
        # chestnut, 배 stomach/boat/pear — all NNG homographs), so the
        # dedupe key MUST preserve `etymology_number` or the post-first
        # senses are silently dropped before the ko scoring branch below
        # ever runs. Other languages fold homographs into multi-sense
        # entries under a single etymology, so the tighter key still
        # behaves as before for them.
        etym_num_raw = obj.get("etymology_number") or 1
        key = (
            f"{word_lower}\t{pos_raw}\t{etym_num_raw}"
            if lang == "ko"
            else f"{word_lower}\t{pos_raw}"
        )
        if key in seen_headwords:
            continue
        seen_headwords.add(key)

        # Scale frequency into an integer score for sort ordering.
        # log10(freq) ranges from ~-7 (rare) to ~-2 (very common). Shift
        # and clamp to 0..100.
        #
        # Korean adds two wrinkles to the generic formula:
        #  1. Corpus-miss fallback — entries not in wordfreq's Korean
        #     dict (archaic lemmas, rare Hanja, specialized terms) get a
        #     Wiktionary-intrinsic proxy (sense count + etymology order)
        #     capped below the real-frequency range so they don't
        #     outrank corpus-known words.
        #  2. Homograph tie-breaking — multiple Wiktionary entries with
        #     the same spelling share one morpheme frequency in the
        #     corpus, so a small etymology-order offset differentiates
        #     them. kaikki orders etymologies by Wiktionary page order
        #     (usually primary meaning first), which is the right thing
        #     for sort-by-freq-score-desc.
        if lang == "ko":
            etym_num = int(obj.get("etymology_number") or 1)
            if freq is None:
                sense_count = len(glosses_list)
                freq_score = max(10, min(50, 25 + sense_count * 3 - (etym_num - 1) * 5))
                is_common = 0
            else:
                base = max(0, min(100, int((math.log10(freq) + 7) * 20)))
                freq_score = max(10, min(95, base - (etym_num - 1) * 2))
                is_common = 1 if freq >= COMMON_FREQUENCY else 0
        else:
            freq_score = max(0, min(100, int((math.log10(freq) + 7) * 20)))
            is_common = 1 if freq >= COMMON_FREQUENCY else 0

        entry_id += 1
        cur.execute(
            "INSERT INTO entry VALUES (?, ?, ?)",
            (entry_id, is_common, freq_score),
        )
        cur.execute(
            "INSERT INTO headword VALUES (?, ?, ?)",
            (entry_id, 0, word_lower),
        )
        # Position 0 = lemma surface. Record in kept_lemma_ids so pass 2
        # can alias-index redirect surfaces targeting this lemma. A word
        # may appear under multiple POS (noun + adj etc.) with distinct
        # entry_ids — we keep them all so an alias like "volontari" fans
        # out to both the "volunteer" (noun) and "voluntary" (adj) entries.
        kept_lemma_ids.setdefault(word_lower, []).append(entry_id)

        # Stem row — position 1 headword pointing at the same entry_id.
        # WiktionaryDictionaryManager tries surface first, then the
        # Snowball stem of the queried word; the stem row is what that
        # fallback actually hits. Skip when the stem equals the surface
        # (would just duplicate the row) or when the language has no
        # stemmer.
        if stemmer is not None:
            stem = stemmer.stemWord(word_lower)
            if stem and stem != word_lower:
                cur.execute(
                    "INSERT INTO headword VALUES (?, ?, ?)",
                    (entry_id, 1, stem),
                )
                stem_rows += 1
        # No reading rows for Latin.

        for sense_pos, sense in enumerate(senses_data):
            cur.execute(
                "INSERT INTO sense VALUES (?, ?, ?, ?, ?)",
                (
                    entry_id,
                    sense_pos,
                    pos_raw,
                    "\t".join(sense["glosses"]),
                    "",
                ),
            )
            for ex_pos, (text, translation) in enumerate(sense["examples"]):
                cur.execute(
                    "INSERT INTO example VALUES (?, ?, ?, ?, ?)",
                    (entry_id, sense_pos, ex_pos, text, translation),
                )
                example_rows += 1

        kept += 1

    # ── Pass 2: redirect-alias indexing ─────────────────────────────
    # Re-stream the kaikki file. For each entry we dropped as a redirect
    # in pass 1, extract its target lemma word from any sense's
    # `form_of` / `alt_of` / `altspell_of` / `abbreviation_of` /
    # `synonym_of` field, and if that lemma is in the pack, add a
    # position-2 headword row so `WiktionaryDictionaryManager` can
    # resolve the inflected/alternate surface to the lemma's entry_id.
    #
    # `compound_of` is INTENTIONALLY EXCLUDED. Italian compounds like
    # `dacci = da' + ci` would route the user to `da'`'s gloss, which
    # is only a fragment of the compound's meaning. Romance languages
    # (IT, ES, PT) populate compound_of heavily with clitic-attached
    # imperatives; English has zero compound_of entries in a 200 MB
    # sample, so there's nothing to lose by skipping this key.
    ALIAS_KEYS = (
        "form_of",
        "alt_of",
        "altspell_of",
        "abbreviation_of",
        "synonym_of",
    )

    alias_pairs: set[tuple[int, str]] = set()
    scanned2 = 0
    for obj in iter_kaikki(input_path):
        scanned2 += 1
        if scanned2 % 200000 == 0:
            print(
                f"  [pass2] {scanned2:,} scanned, "
                f"{len(alias_pairs):,} alias pairs collected…"
            )

        word = obj.get("word")
        lang_code = obj.get("lang_code")
        if not word:
            continue
        if lang_code and lang_code != kaikki_lang:
            continue
        # We only care about entries we dropped in pass 1 — i.e. pure
        # redirect entries. Entries that survived pass 1 already have
        # their lemma row.
        if not is_redirect_entry(obj):
            continue

        source_surface = lower_for_lang(word, lang)
        senses = obj.get("senses") or []
        for sense in senses:
            for key in ALIAS_KEYS:
                target_list = sense.get(key)
                if not target_list:
                    continue
                target = target_list[0] if isinstance(target_list, list) else None
                if not isinstance(target, dict):
                    continue
                target_word = lower_for_lang(target.get("word") or "", lang)
                if not target_word:
                    continue
                if source_surface == target_word:
                    continue  # self-alias, defensive
                target_ids = kept_lemma_ids.get(target_word)
                if not target_ids:
                    continue
                for target_id in target_ids:
                    alias_pairs.add((target_id, source_surface))

    if alias_pairs:
        cur.executemany(
            "INSERT INTO headword VALUES (?, ?, ?)",
            [(tid, 2, surf) for (tid, surf) in alias_pairs],
        )

    conn.commit()
    conn.close()
    distinct_targets = len({tid for (tid, _) in alias_pairs})
    print(
        f"Built {db_path} with {kept:,} entries "
        f"({dropped_redirect:,} redirects filtered, "
        f"{stem_rows:,} stem rows indexed, "
        f"{len(alias_pairs):,} alias rows covering {distinct_targets:,} lemmas, "
        f"{example_rows:,} example rows)."
    )


# KOMORAN's LIGHT model files (~1.75 MB total). Extracted from
# KOMORAN-*.jar into the KO pack's tokenizer/ subdir so the APK can
# strip the bundled models via packagingOptions.resources.excludes and
# the runtime loads them via `new Komoran(String modelPath)`.
KOMORAN_LIGHT_FILES = (
    "pos.table",
    "irregular.model",
    "transition.model",
    "observation.model",
)
KOMORAN_JAR_PREFIX = "models_light/"


def _sha256_of(path: Path) -> str:
    import hashlib
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def extract_komoran_models(jar_path: Path, tokenizer_dir: Path) -> list[dict]:
    """Extract KOMORAN models_light/ files from [jar_path] into
    [tokenizer_dir]. Returns a list of manifest-shape dicts for
    appending to manifest.files."""
    tokenizer_dir.mkdir(parents=True, exist_ok=True)
    entries: list[dict] = []
    with zipfile.ZipFile(jar_path, "r") as jar:
        names = set(jar.namelist())
        for basename in KOMORAN_LIGHT_FILES:
            jar_entry = KOMORAN_JAR_PREFIX + basename
            if jar_entry not in names:
                raise RuntimeError(
                    f"KOMORAN JAR at {jar_path} is missing entry {jar_entry}. "
                    "Pass --komoran-jar pointing at KOMORAN-3.3.9.jar (typically under "
                    "~/.gradle/caches/modules-2/files-2.1/com.github.shin285/KOMORAN/)."
                )
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
    print(f"Extracted {len(entries)} KOMORAN files to {tokenizer_dir}")
    return entries


def build_manifest(
    db_path: Path,
    manifest_path: Path,
    lang: str,
    pack_version: int,
    tokenizer_entries: list[dict] | None = None,
) -> None:
    size = db_path.stat().st_size
    files: list[dict] = [{"path": "dict.sqlite", "size": size, "sha256": None}]
    total = size
    licenses: list[dict] = [
        {
            "component": "Wiktionary",
            "license": "CC-BY-SA-3.0",
            "attribution": "© Wiktionary contributors, https://en.wiktionary.org/",
        }
    ]
    if tokenizer_entries:
        files.extend(tokenizer_entries)
        total += sum(int(e["size"]) for e in tokenizer_entries)
        if lang == "ko":
            licenses.append({
                "component": "KOMORAN",
                "license": "Apache-2.0",
                "attribution": "© Shineware, https://github.com/shineware/KOMORAN",
            })
    manifest = {
        "langId": lang,
        "schemaVersion": 1,
        "packVersion": pack_version,
        # appMinVersion isn't known here — LanguagePackStore.writeManifestIfMissing
        # writes its own manifest with BuildConfig.VERSION_CODE when the pack is
        # bundled. Downloaded packs use whatever value the server-side manifest
        # provides; use a placeholder of 0 = "any version" here.
        "appMinVersion": 0,
        "files": files,
        "totalSize": total,
        "licenses": licenses,
    }
    manifest_path.write_text(json.dumps(manifest, indent=2))
    print(f"Wrote {manifest_path} ({size} bytes dict, {total} bytes total)")


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
        # Regular plurals resolve via the Snowball stem path.
        "cani": "dog",            # cani → cane → "dog"
        "volontari": "volunteer",  # volontari → volontario → "volunteer"
        "gatti": "cat",           # gatti → gatto → "cat"
        # Irregular participles resolve via the form_of alias path.
        # These stem to something unrelated to their lemma's stem, so
        # they only work when pass 2 indexed a position-2 alias row.
        "detto": "say",            # detto → dire (form_of) → "say"
        "fatto": "do",             # fatto → fare (form_of) → "do"
        "preso": "take",           # preso → prendere (form_of) → "take"
        "venuto": "come",          # venuto → venire (form_of) → "come"
    },
    "ko": {
        # Korean fixtures are CITATION-FORM ONLY. Conjugated surfaces
        # (e.g. "먹었습니다" / "예뻐요") are decomposed by Lucene Nori in
        # KoreanEngine at runtime, not by the pack — so there's nothing
        # at pack-build time that would resolve them. We pin the lemma
        # surfaces that the pack must contain; runtime lemmatization is
        # covered by KoreanEngineTokenizerTest on the JVM side.
        "사람": "person",
        "집": "house",
        "먹다": "eat",
    },
    "tr": {
        # Baseline: already-lowercase Turkish word.
        "ışık": "light",
        # Uppercase OCR path. `"IŞIK".lower()` under Unicode default is
        # `"işik"` — to hit `ışık` in the DB we need the Turkish-aware
        # lowercase (`I` → `ı`). Pins both build-time and runtime casing.
        "IŞIK": "light",
        # Acronym where Python's default `str.lower()` diverges from
        # Turkish casing. Wiktionary has two entries: uppercase `TIR`
        # (vehicle/tractor senses) and lowercase `tır` (heavy truck) —
        # both fold to `tır` under `lower_for_lang`. Whichever wins
        # the (word_lower, pos) dedupe, its gloss contains "truck".
        "TIR": "truck",
        # Dotted-İ entry that the default `word.lower()` probe would
        # drop: `"İngilizce".lower()` is `"i̇ngilizce"` (9 codepoints
        # with combining dot), which returns freq 0.0 in wordfreq.
        # The max-of-both-forms probe recovers it via the Turkish
        # fold (`ingilizce`, freq 1.45e-4).
        "İngilizce": "English",
    },
    # Other languages: fill in per-rebuild. Empty is OK — no fixtures
    # means "build still succeeds, just no regression guard for this lang."
}


def run_smoke_test(db_path: Path, lang: str) -> None:
    """Replay WiktionaryDictionaryManager.lookup against a small fixture
    set to catch regressions where plurals/conjugations no longer
    resolve to their lemma gloss. Raises on failure; no-op when the
    language has no fixtures."""
    fixtures = SMOKE_FIXTURES.get(lang, {})
    if not fixtures:
        return
    algo = SNOWBALL_ALGO_FOR_LANG.get(lang)
    stemmer = _snowball_stemmer(algo) if algo else None
    conn = sqlite3.connect(db_path)
    failures: list[str] = []
    try:
        for surface, expected_substr in fixtures.items():
            surface_l = lower_for_lang(surface, lang)
            # Try surface, then stem — matches WiktionaryDictionaryManager.lookup.
            rows = conn.execute(
                "SELECT s.glosses FROM headword h JOIN sense s ON s.entry_id=h.entry_id "
                "WHERE h.text = ? ORDER BY h.entry_id",
                (surface_l,),
            ).fetchall()
            if not rows and stemmer is not None:
                stem = stemmer.stemWord(surface_l)
                if stem and stem != surface_l:
                    rows = conn.execute(
                        "SELECT s.glosses FROM headword h JOIN sense s ON s.entry_id=h.entry_id "
                        "WHERE h.text = ? ORDER BY h.entry_id",
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
    parser.add_argument(
        "--komoran-jar",
        type=Path,
        required=False,
        help="Path to KOMORAN-*.jar. Only meaningful when --lang ko; extracts "
             "the models_light/ files into tokenizer/ inside the pack so the "
             "APK can strip the bundled models. Ignored for non-Korean packs.",
    )
    args = parser.parse_args()

    args.output.mkdir(parents=True, exist_ok=True)
    db_path = args.output / "dict.sqlite"
    manifest_path = args.output / "manifest.json"
    zip_path = args.output / f"{args.lang}.zip"
    tokenizer_dir = args.output / "tokenizer"

    if not args.input.exists():
        print(f"error: input not found: {args.input}", file=sys.stderr)
        return 1

    build_sqlite(args.input, db_path, args.lang)
    run_smoke_test(db_path, args.lang)

    tokenizer_entries = None
    if args.lang == "ko" and args.komoran_jar is not None:
        if not args.komoran_jar.is_file():
            print(f"error: --komoran-jar not a file: {args.komoran_jar}", file=sys.stderr)
            return 1
        tokenizer_entries = extract_komoran_models(args.komoran_jar, tokenizer_dir)
    elif args.komoran_jar is not None and args.lang != "ko":
        print(f"warning: --komoran-jar ignored for lang={args.lang}", file=sys.stderr)

    build_manifest(db_path, manifest_path, args.lang, args.pack_version, tokenizer_entries)
    build_zip(db_path, manifest_path, zip_path, tokenizer_dir if tokenizer_entries else None)

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
