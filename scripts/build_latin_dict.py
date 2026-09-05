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

    ca cs da de en es fi fr hi hu id it ko nb nl no pl pt ro sv tr vi

(Norwegian: pass `--lang no` — the kaikki file is named "Norwegian"
but its lang_code is `no`. The wordfreq locale `nb` is substituted
automatically.)

Pipeline
--------
1. Stream the kaikki JSON-Lines file (one JSON object per line).
2. Filter to content-word parts of speech (noun/verb/adj/adv/det/...).
3. Drop entries where `lang_code` doesn't match `--lang`.
4. Drop rare LEXEMES: the frequency cut tests the SUM of
   `wordfreq.word_frequency` over the citation form and its `forms[]`
   surfaces against MIN_FREQUENCY (see the comment there for why the
   bare citation form alone is the wrong keep signal).
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
import os
import sqlite3
import sys
import unicodedata
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
    redirect_target_from_gloss,
    CONTENT_POS,
    MAX_HEADWORD_WORDS,
    MAX_SENSES_PER_ENTRY,
    WIKT_REDIRECT_KEYS,
    filter_misc,
    is_redirect_entry,
    is_redirect_sense,
)

# Arabic normalization + fold specs — kept in scripts/arabic_text.py (dep-free)
# so this script and the heavy arabic_morphology.py share one definition. MUST
# match ArabicNormalize.kt / ArabicFold.kt character-for-character (pinned by
# the _assert_* golden fixtures, run from the smoke test).
from arabic_text import (
    _assert_arabic_fold,
    _assert_arabic_normalize,
    arabic_fold,
    arabic_normalize,
)

# Turkish case mapping: default Python `str.lower()` uses Unicode folding
# (`I` → `i`, `İ` → `i` + combining dot), not Turkish rules (`I` → `ı`,
# `İ` → `i`). The runtime Android side lowercases queries with
# `SourceLangId.TR.locale`, so pack headwords have to match that output
# or uppercase OCR of words like `TIR`/`LGBTI` silently misses.
_TR_UPPER_MAP = str.maketrans({"I": "ı", "İ": "i"})


# Max usage examples kept per sense. Kaikki frequently ships 5-10 for
# well-covered entries; three is enough to illustrate without ballooning
# pack size.
MAX_EXAMPLES_PER_SENSE = 3

# Hard cap on example text length. Wiktionary (especially English) mixes
# short usage examples with multi-paragraph literary quotations; anything
# beyond ~200 characters reads as a wall of text in the word-detail popup
# and bloats pack size without helping learners. We also sort examples
# ascending by length so senses with mixed-length examples still surface
# their shortest ones instead of getting skipped.
MAX_EXAMPLE_CHARS = 200


# kaikki `forms[]` rows that are not real word forms: table scaffolding, the
# template name, and wiktextract's unparsed-cell marker.
#
# "class" is wiktextract's tag for the inflection-CLASS label a conjugation or
# declension table carries — never a word form. Every language that has
# inflection classes emits them: fi "38/nainen" and "52/sanoa" (Kotus type +
# model word), ru "velar-stem" / "accent-a", ko "consonant-stem", es "o-ue
# alternation". Most were already filtered incidentally — es's carries a space,
# ru's and ko's are Latin against a Cyrillic/Hangul lemma so the cross-script
# gate caught them — but Finnish's are Latin on a Latin lemma and slipped
# through, with consequences well beyond one junk row: wordfreq TOKENIZES
# "38/nainen" as ["38", "nainen"] and returns the model word's frequency, so
# every Finnish lemma inherited the corpus mass of its declension model and
# cleared MIN_FREQUENCY regardless of its own rarity. That is 139,305 of fi's
# 139,717 spurious lexemes, and it moved is_common from 966 to 5,934 — a rare
# mushroom name rendered as Common. Filtering by TAG rather than by shape is
# deliberate: a `/` rule would eat real English forms like "3/4 sister" and
# "1/sgt".
FORM_TAG_BLOCKLIST = frozenset({
    "table-tags", "inflection-template", "error-unrecognized-form",
    "no-table-tags", "romanization", "class",
})
_FORM_JUNK_LITERALS = frozenset({"-", "—", "–", "?"})


def _scripts_of(s: str) -> frozenset:
    """Coarse Unicode-script tags of the LETTERS in [s] (marks/digits ignored).
    Lets the alias passes keep a surface only when its script matches the lemma's:
    kaikki lists cross-script transliterations (Urdu forms on a Devanagari lemma)
    and grammar-class labels ("ā-stem") that a single-script OCR never produces.

    LIMITATION — the "other" bucket collapses every non-enumerated alphabetic
    script (Hangul, Hanja/CJK, Greek, Hebrew, …) into ONE tag, so the gate cannot
    distinguish among them. It is therefore PERMISSIVE for a language whose script
    is not enumerated below: it still rejects an enumerated FOREIGN script (e.g. a
    Latin romanization of a Korean word) but treats any two "other" surfaces as
    same-script. For the one currently-affected build language, Korean, that is
    the DESIRED behavior — Korean is genuinely multi-script (Hangul + Hanja, both
    read by its OCR), so a Hanja alias on a Hangul lemma is useful, not junk. If a
    future language instead needs cross-script filtering WITHIN "other" (e.g. Greek
    dropping Coptic, or Korean treated as Hangul-only), give its script its own tag
    here rather than relying on "other"."""
    out = set()
    for ch in s:
        o = ord(ch)
        if 0x0900 <= o <= 0x097F:
            out.add("deva")
        elif 0x0600 <= o <= 0x06FF or 0x0750 <= o <= 0x077F or 0xFB50 <= o <= 0xFDFF or 0xFE70 <= o <= 0xFEFF:
            out.add("arab")
        elif 0x0041 <= o <= 0x024F:
            out.add("latn")
        elif 0x0400 <= o <= 0x04FF:
            out.add("cyrl")
        elif 0x0E00 <= o <= 0x0E7F:
            out.add("thai")
        elif ch.isalpha():
            out.add("other")  # Hangul, Hanja/CJK, Greek, … all collapse here — see docstring
    return frozenset(out)


def _alias_surface_is_junk(surface: str, lemma_scripts: frozenset) -> bool:
    """True when [surface] must NOT become a position-2 alias, for reasons shared
    by BOTH the forms[] pass and the redirect-alias pass — kept here as one
    predicate so the two passes can't drift:

      - hyphen-boundary template scaffolding: a linking-vowel / bound-morpheme
        notation that starts or ends with a hyphen ("-a-", "मनो-") — never a
        tappable word (word_frequency even mis-scores "-a-" as the bare article).
      - a script the lemma doesn't use: cross-script transliterations (Urdu forms
        on a Devanagari lemma) and Latin grammar-class labels ("ā-stem") a
        single-script OCR pack can never produce, plus bare punctuation/digits
        (no letters at all → empty script set).

    The forms[]-only multi-word / literal filters stay at that call site: the
    redirect pass legitimately aliases multi-word expressions, so it must NOT
    apply them."""
    if surface.startswith("-") or surface.endswith("-"):
        return True
    surface_scripts = _scripts_of(surface)
    return not surface_scripts or bool(surface_scripts - lemma_scripts)


def _etym_num(obj: dict) -> int:
    """kaikki's `etymology_number`, coerced. Most languages emit an int, but
    Italian uses sub-etymologies like "1.1". Only the Korean freq_score branch
    reads this (where it is always a plain int), and it only needs the leading
    number, so coerce defensively instead of failing the build.
    """
    raw = obj.get("etymology_number") or 1
    try:
        return int(str(raw).split(".")[0])
    except (TypeError, ValueError):
        return 1


def eligible_form_surfaces(obj: dict, lang: str, word_lower: str,
                           lemma_scripts: frozenset) -> set[str]:
    """Single-word inflection surfaces from the entry's own kaikki `forms[]`
    table, junk-filtered and build-normalized — the ONE definition shared by
    the lexeme-aggregate frequency probe and the position-2 forms[] alias
    pass, so the two can't drift on what counts as a real form.

    Filters (all forms[]-specific except the shared junk gate):
      - FORM_TAG_BLOCKLIST rows (table scaffolding, template names,
        wiktextract's unparsed-cell marker).
      - Empty, lemma-identical, multi-word, and bare-punctuation "forms"
        (inflection tables shouldn't carry them; the REDIRECT pass
        legitimately aliases multi-word expressions, so that pass must not
        route through here).
      - _alias_surface_is_junk: hyphen scaffolding + cross-script
        transliterations (~31% of Hindi forms[])."""
    out: set[str] = set()
    for form_obj in obj.get("forms") or ():
        if set(form_obj.get("tags") or ()) & FORM_TAG_BLOCKLIST:
            continue
        form_text = lower_for_lang((form_obj.get("form") or "").strip(), lang)
        if not form_text or form_text == word_lower:
            continue
        if " " in form_text or form_text in _FORM_JUNK_LITERALS:
            continue
        if _alias_surface_is_junk(form_text, lemma_scripts):
            continue
        out.add(form_text)
    return out


# Chained-redirect resolution depth for pass 2 (see resolve_redirect_chain).
# 2 hops covers the motivating population — inflection page → alternative-
# spelling page → lemma ("criticised" → "criticise" → "criticize"); one
# spare hop absorbs a form-of chain stacked on that. Deeper chains are
# Wiktionary anomalies not worth chasing.
MAX_REDIRECT_HOPS = 3

# Languages whose forms[] alias rows are limited to surfaces wordfreq has
# actually seen. Only defensible for a `large_` list: Finnish's is 600k words,
# so "wordfreq has never seen this form" is real evidence the form is not worth
# a row, and ~150 generated rows per lemma is otherwise a 150-300 MB pack. It is
# NOT evidence for a small list — only 3% of Turkish's alias surfaces appear in
# its 63k-word list, and unattested there does not mean unused — so this stays
# scoped rather than becoming a general rule. The Snowball Finnish stem row
# (position 1) remains the backstop for every unattested inflection.
ALIAS_ATTESTED_ONLY_LANGS: frozenset[str] = frozenset({"fi"})


# Combining stress marks. Russian/Ukrainian Wiktionary writes redirect targets
# with the pronunciation stress on ("о́ко", "пара́граф"), but a lemma's own `word`
# never carries it, so a target string never matched a kept lemma and the page
# was treated as unresolvable: 31,960 ru redirect glosses name a stressed target
# and 86.2% of them ARE kept lemmas once the mark comes off. Latin á/é/í are
# PRECOMPOSED in NFC, so a bare combining acute only survives on scripts that
# have no precomposed form — Cyrillic stress being the case this exists for.
_STRESS_MARKS = dict.fromkeys(map(ord, "́̀́̀"))


def destress(word: str) -> str:
    """[word] without combining stress marks. Used ONLY to widen redirect target
    matching, never to rewrite a stored surface."""
    return word.translate(_STRESS_MARKS)


def resolve_redirect_chain(
    source: str,
    redirect_targets: dict[str, set[str]],
    kept_surfaces,
    max_hops: int = MAX_REDIRECT_HOPS,
) -> set[str]:
    """Kept-lemma surfaces reachable from redirect surface [source] by
    following [redirect_targets] transitively — breadth-first, cycle-guarded,
    capped at [max_hops]. A branch TERMINATES at the first kept lemma it
    reaches (a kept lemma is the answer, never a waypoint: "criticise" is
    both an altspell_of redirect page and — once aliased — a resolvable
    surface, but a chain that already landed on a kept lemma must not keep
    walking through its outgoing redirects).

    Single-hop resolution ("target must be a kept lemma") silently dropped
    every inflection of every alternative-spelling lemma: "criticised" is
    form_of "criticise", which is only altspell_of "criticize" — one hop
    reached a non-kept surface and gave up, so the en pack had NO row for
    "criticised" (or "recognising", "characterised", …).

    [kept_surfaces] only needs membership (`in`) — pass kept_lemma_ids.
    Pure + import-safe so scripts/test_alias_chains.py can drive it."""
    resolved: set[str] = set()
    frontier = redirect_targets.get(source, set())
    visited = {source}
    for _ in range(max_hops):
        nxt: set[str] = set()
        for target in frontier:
            if target in visited:
                continue
            visited.add(target)
            if target in kept_surfaces:
                resolved.add(target)
            else:
                nxt |= redirect_targets.get(target, set())
        if not nxt:
            break
        frontier = nxt
    return resolved


def extract_examples(sense: dict) -> list[tuple[str, str]]:
    """Pull up to MAX_EXAMPLES_PER_SENSE usage examples out of a kaikki
    sense dict. Each example becomes a (text, translation) tuple where
    translation is "" when the example is monolingual.

    Filters:
      - `type != "example"`: dropped. Kaikki tags editor-written usage
        examples as `type="example"` and historical citations as
        `type="quotation"`; untyped entries are a messy long-tail that
        leans heavily toward old literary extracts on English
        Wiktionary. For a learner app we only want the usage flavor.
      - Empty `text`: dropped (unusable).
      - Text longer than MAX_EXAMPLE_CHARS: dropped (defensive — usage
        examples are typically short, but a few outliers slip through).
      - `english` that's Wiktionary's "please add" placeholder: coerced
        to "" so the monolingual fallback path kicks in at render time
        instead of showing the placeholder verbatim.
      - Duplicate texts within the same sense: the first occurrence wins
        (kaikki occasionally ships repeats).

    Kaikki examples are returned in editorial order; sorting by length
    lets a sense surface its shortest example first when several qualify.
    """
    candidates: list[tuple[str, str]] = []
    seen: set[str] = set()
    for ex in sense.get("examples") or []:
        if ex.get("type") != "example":
            continue
        text = (ex.get("text") or "").strip()
        if not text or text in seen:
            continue
        if len(text) > MAX_EXAMPLE_CHARS:
            continue
        seen.add(text)
        translation = (ex.get("english") or "").strip()
        if "please add" in translation.lower():
            translation = ""
        candidates.append((text, translation))
    candidates.sort(key=lambda p: len(p[0]))
    return candidates[:MAX_EXAMPLES_PER_SENSE]


def lower_for_lang(word: str, lang: str) -> str:
    """Locale-aware lowercase for pack headword keys. Turkish needs the I/İ
    remap before default folding; Arabic is run through [arabic_normalize]
    (no case; diacritics/tatweel STRIPPED, but letter identities PRESERVED —
    NO alef/ya/taa folding, so the position-0 key doubles as the displayed
    lemma); other languages fall back to plain `str.lower()`."""
    if lang == "tr":
        word = word.translate(_TR_UPPER_MAP)
    elif lang == "ar":
        word = arabic_normalize(word)
    elif lang == "hi":
        # Devanagari: canonical NFC composes nukta sequences (e.g. ड़ as
        # U+0921+U+093C -> U+095C) so OCR and kaikki forms match regardless of
        # source. NFC is divergence-free (identical to java.text.Normalizer NFC
        # at runtime), so no shared spec is needed. This is NOT IndicNormalizer
        # folding (deferred with the stemmer).
        word = unicodedata.normalize("NFC", word)
    return word.lower()


# Frequency threshold (Zipf scale). LEXEMES with aggregate frequency below
# this are dropped. 1e-6 = "at least one occurrence per million words" —
# yields ~30-50k entries for well-covered languages.
#
# The cut is applied to the LEXEME AGGREGATE — the citation form's
# frequency plus every (junk-filtered, deduped) `forms[]` surface's
# frequency — not the bare citation form. wordfreq counts each surface
# separately, and inflection-heavy lexemes concentrate their usage in
# non-citation forms: en "confiscated" is 3.0e-06 while "confiscate" is
# 8.5e-07. Cutting on the citation form alone dropped such lexemes whole,
# and with the lemma went EVERY resolution path for its inflections (no
# position-0 row, no position-1 stem row, and the form_of redirect pages
# couldn't alias because their target wasn't kept) — so surfaces users
# actually meet ("confiscated", "besieged", "larvae", fr "décédé")
# resolved to nothing. SUM, not max: total surface mass IS the lexeme's
# corpus frequency, and max still dropped lexemes whose most common single
# form sits just under the bar ("deafening" 9.6e-07, "riveting" 9.8e-07 —
# family sums 1.1e-06 / 2.6e-06).
# Accepted skew: a rare lexeme whose form collides with an unrelated common
# surface inherits that surface's frequency for keep/score. Bounded — the
# entry is only reachable through its own surfaces, so the inflated score
# matters only when those surfaces are queried, where the entry is a
# legitimate candidate anyway.
#
# TODO (not implemented): make the aggregate REDIRECT-AWARE — add a redirect
# surface's frequency to the lexeme it points at. Hindi shows why: nuqta is
# routinely omitted in running text, so the nuqta-LESS spelling (अखबार) carries
# the corpus mass while the nuqta-bearing lemma it redirects to (अख़बार) can sit
# below this cut and be dropped, taking the common spelling's only resolution
# path with it. Crediting the redirect's frequency to its target would keep the
# target and let the common spelling alias onto a properly-glossed lemma —
# strictly better than the fallback that now exists (keeping the unresolvable
# redirect page itself as a lemma showing "nuqtaless form of …"). Needs the
# redirect graph before the cut runs, i.e. a frequency pre-pass.
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
    "ru": "russian",
    "ar": "arabic",
    "vi": None,
    "id": None,
    "ko": None,
    "th": None,  # isolating; runtime tokenization is the newmm dictionary matcher
    "hi": None,  # no Snowball Hindi; v1 is surface + form_of aliases (no stem rows)
    "pl": None,  # no Snowball Polish; inflection ships as position-2 PoliMorf
                 # alias rows (scripts/polish_morphology.py), not stem rows
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
        -- Reverse lookups by entry_id: buildEntry fetches each matched entry's
        -- headword / reading / sense rows by entry_id (+ ORDER BY position).
        -- Without these, those reverse lookups are full table scans per entry.
        CREATE INDEX idx_headword_entry ON headword(entry_id, position);
        CREATE INDEX idx_reading_entry  ON reading(entry_id, position);
        CREATE INDEX idx_sense_entry    ON sense(entry_id, position);
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
    # Inflection-table aliases mined from each kept lemma's own `forms[]`
    # (pass 1); merged into the pass-2 alias set before the single position-2
    # insert. See the block below and docs/polish-source-language-plan.md §3.3.
    forms_alias_pairs: set[tuple[int, str]] = set()
    # surface -> the redirect pages under it, held for the post-pass-2 rescue.
    redirect_pages: dict[str, list[tuple]] = {}

    def emit_lemma(word_lower, pos_raw, senses_data, glosses_list, freq,
                   form_surfaces, etym_num=1):
        """Insert one lexeme: entry row, position-0 headword, stem row, senses
        and examples, plus its forms[] alias contributions. Shared by pass 1 and
        by the post-pass-2 rescue of redirect pages that resolved to nothing, so
        a rescued page is indistinguishable from any other lemma."""
        nonlocal entry_id, kept, stem_rows, example_rows
        if lang == "ko":
            if freq is None:
                sense_count = len(glosses_list)
                freq_score = max(10, min(50, 25 + sense_count * 3 - (etym_num - 1) * 5))
                is_common = 0
            else:
                base = max(0, min(100, int((math.log10(freq) + 7) * 20)))
                freq_score = max(10, min(95, base - (etym_num - 1) * 2))
                is_common = 1 if freq >= COMMON_FREQUENCY else 0
        elif lang == "th":
            # No frequency signal (wordfreq lacks Thai). Rank by sense count —
            # more senses ≈ more central vocabulary — mirroring Korean's
            # freq-less path. is_common stays 0 (no frequency to threshold).
            sense_count = len(glosses_list)
            freq_score = max(10, min(60, 20 + sense_count * 5))
            is_common = 0
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

        # Inflection-table aliases. kaikki lemma entries carry their full
        # declension/conjugation table inline in `forms[]`, which pass 2's
        # redirect-entry scan never sees — a form only gets its own entry when
        # someone created a wiki page for it. Measured on Polish: 5.1 pairs per
        # lemma here vs 0.26 from redirect entries. Emitted at position 2, the
        # same tier as form_of aliases, so they surface with the [inflected]
        # marker and stay out of searchPrefix (which reads position 0 only).
        # The surface set (junk-filtered in eligible_form_surfaces) is the
        # same one the frequency aggregate above probed.
        for form_text in form_surfaces:
            if lang in ALIAS_ATTESTED_ONLY_LANGS and not word_frequency(
                form_text, wordfreq_locale
            ):
                continue
            forms_alias_pairs.add((entry_id, form_text))

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
                    "\t".join(sense["misc"]),
                ),
            )
            for ex_pos, (text, translation) in enumerate(sense["examples"]):
                cur.execute(
                    "INSERT INTO example VALUES (?, ?, ?, ?, ?)",
                    (entry_id, sense_pos, ex_pos, text, translation),
                )
                example_rows += 1

        kept += 1
        return entry_id

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

        # Entries whose senses are ALL redirects do not become lemmas here:
        # keeping them shadows the real word on direct lookup (tap `volontari`
        # and get "masculine plural of volontario" instead of the real gloss),
        # and pass 2 turns their surfaces into alias rows instead.
        #
        # They are RECORDED rather than discarded, because pass 2 can only
        # alias onto a lemma the pack actually kept. When a redirect's chain
        # ends at a target that the frequency cut dropped, the page used to
        # vanish outright and take the surface with it — Hindi's nuqta-less
        # spellings are the common ones and redirect to rarer nuqta-bearing
        # lemmas, so 34 everyday words disappeared. Anything still unresolved
        # after pass 2 is emitted as a lemma carrying its own redirect gloss,
        # which is what these pages showed before redirect detection widened.
        is_redirect = is_redirect_entry(obj)
        if is_redirect:
            dropped_redirect += 1

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
        lemma_scripts = _scripts_of(word_lower)
        # Shared by the lexeme-aggregate frequency probe below and the
        # forms[] alias emission after the entry inserts.
        form_surfaces = eligible_form_surfaces(obj, lang, word_lower, lemma_scripts)
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
        elif lang == "th":
            # wordfreq has NO Thai at all (verified: wordfreq 3.1.1,
            # `word_frequency(w, "th")` raises LookupError — no Thai-script
            # tokenizer), and there is no clean, redistributable Thai frequency
            # list. So Thai carries no frequency (freq=None → sense-count
            # freq_score below) and takes NO frequency cut: Thai Wiktionary is
            # small, so every content entry is worth keeping.
            freq = None
        else:
            # Lexeme aggregate: the citation form's contribution (max of its
            # two case probes) PLUS every distinct forms[] surface — the
            # lexeme's total corpus mass (see the MIN_FREQUENCY comment for
            # why sum, not bare-lemma or max). form_surfaces is a set that
            # excludes the lemma-identical surface, so nothing double-counts.
            # The forms probe skips the Turkish dual-case probe: the corpus
            # case-fold inconsistency was observed on lemma acronyms, and
            # forms[] surfaces arrive already-lowercased from the tables.
            freq = max(
                word_frequency(word.lower(), wordfreq_locale),
                word_frequency(word_lower, wordfreq_locale),
            )
            for form_surface in form_surfaces:
                freq += word_frequency(form_surface, wordfreq_locale)
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
            # Skip individual redirect senses on entries we're keeping as
            # lemmas in their own right. On a page that is ENTIRELY redirects
            # every sense is one, and the redirect text is the only gloss it
            # has — dropping them would emit an entry with no senses at all.
            if not is_redirect and is_redirect_sense(sense):
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
            this_misc = filter_misc(sense.get("tags"), sense.get("raw_tags"))
            senses_data.append(
                {"glosses": this_glosses, "examples": this_examples, "misc": this_misc}
            )
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
        if is_redirect:
            # Held until pass 2 has resolved the graph. Recorded AFTER the
            # frequency cut, so the map only ever holds pages that could
            # legitimately become entries.
            if senses_data:
                redirect_pages.setdefault(word_lower, []).append(
                    (pos_raw, senses_data, glosses_list, freq, form_surfaces,
                     _etym_num(obj))
                )
            continue

        emit_lemma(word_lower, pos_raw, senses_data, glosses_list, freq,
                   form_surfaces, _etym_num(obj))

    # ── Pass 2: redirect-alias indexing ─────────────────────────────
    # Re-stream the kaikki file. For each entry we dropped as a redirect
    # in pass 1, extract its target lemma word from any sense's
    # `form_of` / `alt_of` / `altspell_of` / `abbreviation_of` /
    # `synonym_of` field, resolve it through the redirect graph to a KEPT
    # lemma (targets can themselves be redirect pages — "criticised" is
    # form_of "criticise", which is only altspell_of "criticize"; see
    # resolve_redirect_chain), and add a position-2 headword row so
    # `WiktionaryDictionaryManager` can resolve the inflected/alternate
    # surface to the lemma's entry_id.
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

    def _target_variants(target_word: str) -> tuple[str, ...]:
        """The target as written, plus its destressed form when that differs.
        Additive on purpose: a language that really does write the stress keeps
        resolving on the literal string, and nothing that resolved before stops."""
        bare = destress(target_word)
        return (target_word,) if bare == target_word else (target_word, bare)

    # Sub-pass 2a: collect the redirect GRAPH (surface → named targets)
    # rather than resolving inline — chain resolution needs the whole graph
    # before any source can be routed through an intermediate redirect page.
    redirect_targets: dict[str, set[str]] = {}
    scanned2 = 0
    for obj in iter_kaikki(input_path):
        scanned2 += 1
        if scanned2 % 200000 == 0:
            print(
                f"  [pass2] {scanned2:,} scanned, "
                f"{len(redirect_targets):,} redirect surfaces mapped…"
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
            named = False
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
                named = True
                for cand in _target_variants(target_word):
                    if source_surface == cand:
                        continue  # self-alias, defensive
                    redirect_targets.setdefault(source_surface, set()).add(cand)
            if named:
                continue
            # The sense named no target structurally. It can still be the reason
            # pass 1 dropped this page — is_redirect_sense also fires on a
            # form-of TAG and on a prose form-of gloss — so read the target back
            # out of the gloss, or the page is dropped with nothing to alias.
            glosses = sense.get("glosses") or ()
            gloss_target = redirect_target_from_gloss(
                glosses[0], MAX_HEADWORD_WORDS
            ) if glosses else None
            if not gloss_target:
                continue
            target_word = lower_for_lang(gloss_target, lang)
            if not target_word:
                continue
            for cand in _target_variants(target_word):
                if source_surface == cand:
                    continue
                redirect_targets.setdefault(source_surface, set()).add(cand)

    # Sub-pass 2b: route every redirect surface through the graph to its
    # kept lemma(s) and emit the alias rows.
    alias_pairs: set[tuple[int, str]] = set()
    resolved_surfaces: set[str] = set()
    for source_surface in redirect_targets:
        for target_word in resolve_redirect_chain(
            source_surface, redirect_targets, kept_lemma_ids,
        ):
            # Same shared junk gate the forms[] pass uses, against the FINAL
            # kept lemma's script (the entry this row attaches to): drops
            # hyphen-scaffolding redirect surfaces (bound-morpheme prefixes
            # like "मनो-") and cross-script transliterations. Multi-word
            # aliases survive — the space filter is forms[]-only.
            if _alias_surface_is_junk(source_surface, _scripts_of(target_word)):
                continue
            for target_id in kept_lemma_ids[target_word]:
                alias_pairs.add((target_id, source_surface))
            # Recorded only once an alias row actually exists, so a surface
            # whose every target died in the junk gate still counts as
            # unresolved and is rescued as a lemma below.
            resolved_surfaces.add(source_surface)

    # Rescue: a redirect page nothing could alias onto becomes a lemma carrying
    # its own redirect gloss. Runs BEFORE the alias insert below so a rescued
    # page's own forms[] surfaces join the same single position-2 insertion.
    #
    # Two populations land here. (1) The chain ended at a target the frequency
    # cut dropped — Hindi's अखबार -> अख़बार, where the nuqta-less spelling is the
    # common one. (2) The page named no followable target at all: an entry-level
    # form_of with prose in it (en `oneself`, whose target reads "the indefinite
    # personal pronoun one"), or a tag-only form-of sense. Both used to vanish.
    redirect_lemmas = 0
    for surface, pages in redirect_pages.items():
        if surface in resolved_surfaces:
            continue
        for pos_raw_, senses_data_, glosses_, freq_, forms_, etym_ in pages:
            emit_lemma(surface, pos_raw_, senses_data_, glosses_, freq_,
                       forms_, etym_)
            redirect_lemmas += 1

    # Fold the pass-1 forms[] aliases into the pass-2 redirect-alias set: dedup
    # is then structural (both are sets keyed on (entry_id, surface)) and there
    # stays exactly ONE position-2 insertion point below.
    forms_only = len(forms_alias_pairs - alias_pairs)
    alias_pairs |= forms_alias_pairs

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
        f"{redirect_lemmas:,} of them kept as lemmas (unresolvable), "
        f"{stem_rows:,} stem rows indexed, "
        f"{len(alias_pairs):,} alias rows covering {distinct_targets:,} lemmas "
        f"({forms_only:,} of them from inflection tables / forms[]), "
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


def build_thai_wordlist(db_path: Path, words_path: Path) -> dict:
    """Write the Thai segmenter wordlist (``words.txt``): the union of the
    pack's Wiktionary headwords (position 0) and the PyThaiNLP CC0 word list.

    This is the dictionary the newmm matcher in ThaiEngine segments against —
    intentionally SEPARATE from the lookup dict, since a word can aid
    segmentation (boundary finding) without having a full dictionary entry.
    Returns a manifest file entry."""
    try:
        from pythainlp.corpus.common import thai_words
    except ImportError as e:
        raise SystemExit(
            "Thai build needs PyThaiNLP for the segmenter wordlist "
            "(`pip install pythainlp`)."
        ) from e
    words: set[str] = set()
    conn = sqlite3.connect(db_path)
    try:
        for (w,) in conn.execute("SELECT text FROM headword WHERE position = 0"):
            w = (w or "").strip()
            if w:
                words.add(w)
    finally:
        conn.close()
    wiktionary_count = len(words)
    words.update(w.strip() for w in thai_words() if w and w.strip())
    words_path.write_text("\n".join(sorted(words)) + "\n", encoding="utf-8")
    size = words_path.stat().st_size
    print(
        f"Wrote {words_path}: {len(words)} words "
        f"({wiktionary_count} Wiktionary + {len(words) - wiktionary_count} new from PyThaiNLP)"
    )
    return {"path": "words.txt", "size": size, "sha256": _sha256_of(words_path)}


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
    if lang == "ar":
        # Morphology augmentation sources (position-2 alias rows). See
        # scripts/arabic_morphology.py.
        licenses.append({
            "component": "Camel Morph (MSA database)",
            "license": "CC-BY-4.0",
            "attribution": "© CAMeL Lab, NYU Abu Dhabi, https://github.com/CAMeL-Lab/camel_morph",
        })
        licenses.append({
            "component": "Arramooz",
            "license": "GPL-3.0",
            "attribution": "© Taha Zerrouki, https://github.com/linuxscout/arramooz",
        })
    if lang == "pl":
        # Morphology augmentation source (position-2 alias rows). See
        # scripts/polish_morphology.py.
        licenses.append({
            "component": "Morfologik / PoliMorf",
            "license": "BSD-2-Clause",
            "attribution": "© Marcin Miłkowski, https://github.com/morfologik/morfologik-stemming",
        })
    if lang == "th":
        # PyThaiNLP CC0 word list, merged into the segmenter wordlist (words.txt).
        licenses.append({
            "component": "PyThaiNLP word list",
            "license": "CC0-1.0",
            "attribution": "© PyThaiNLP, https://github.com/PyThaiNLP/pythainlp",
        })
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
    extra_root_files: list[Path] | None = None,
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
        for p in extra_root_files or []:  # e.g. Thai words.txt at the pack root
            z.write(p, arcname=p.name)
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
    "en": {
        # Lexeme-aggregate frequency keep: "confiscate" alone is 8.5e-07 —
        # below the 1e-6 cut — while "confiscated" is ~3.0e-06. The bare-
        # citation-form cut dropped the whole lexeme (no lemma row, no stem
        # row, no alias target), so "confiscated" resolved to NOTHING.
        # Resolves post-fix via the kept lemma's alias/stem rows.
        "confiscated": "possession",  # confiscate: "…separate a possession from its holder"
        # det POS keep: "every" is det-only (unlike each/the/those it has no
        # homograph under a previously-kept POS) — a zipf-5.8 word with NO
        # entry while `det` sat outside CONTENT_POS.
        "every": "without exception",
        # Chained redirect: criticised —form_of→ criticise —altspell_of→
        # criticize. Single-hop alias resolution reached the non-kept
        # intermediate and gave up; the chain resolver lands the lemma.
        "criticised": "fault",        # criticize: "To find fault (with something)"
        # Fix B (prose form-of gloss): "Dated spelling of today." is a redirect
        # expressed only in prose, so to-day was kept as its own adv+noun lemma
        # and shadowed today. It must now alias onto today.
        "to-day": "current day",      # today: "On the current day or date."
    },
    "fi": {
        # Fix C (ALIAS_ATTESTED_ONLY_LANGS): only wordfreq-attested forms get a
        # position-2 alias row, so these two pairs pin BOTH resolution paths.
        # Attested — resolves on the alias row:
        "kirjan": "book",        # kirja
        "kissasi": "cat",        # kissa
        # Not in wordfreq, so deliberately NO alias row — these must come back
        # through the position-1 Snowball stem, which is the whole reason the
        # attestation filter is safe to apply:
        "kirjallanne": "book",   # stems to "kirj"
        "kissastansa": "cat",    # stems to "kis"
    },
    "hu": {
        # Fix B (prose form-of gloss): "past participle of készül:" carries no
        # structured alias field and no form-of tag, so készült survived as its
        # own lemma. It must now also reach készül through a position-2 alias.
        # (Its unrelated adjective sense "knowledgeable, educated" is a real
        # definition and keeps készült a lemma in its own right.)
        "készült": "prepare",
    },
    "fr": {
        # det POS keep: French possessive determiners were entirely absent
        # from the pack while `det` sat outside CONTENT_POS (mon/ma/votre/
        # notre/cet — top-frequency words a learner taps constantly).
        "mon": "my",
        "votre": "your",
    },
    "es": {
        # det POS keep: same class as fr — possessives were absent.
        "su": "his",
        "tus": "your",
    },
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
    "ar": {
        # كتاب "book" — a common lemma; the second form (with harakat) also checks
        # that diacritics are stripped at lookup time so vocalized text still hits.
        "كتاب": "book",
        "كِتَاب": "book",
        # مدرسة "school" — a taa-marbuta lemma. Its resolving proves the position-0
        # headword kept ة (not folded to ه), i.e. the displayed spelling is intact.
        "مدرسة": "school",
        # مدرسه — the casual heh-spelling of مدرسة (taa-marbuta written as plain
        # heh). NOT a canonical headword, so it must resolve via the position-3
        # fold key — this fixture exercises the fold FALLBACK, not the surface
        # query (which is ceiling-limited to position<=2 in the runner above).
        "مدرسه": "school",
    },
    "pl": {
        # No Snowball Polish stemmer, so each of these can ONLY resolve via a
        # position-2 alias row. A surface-only regression fails all of them.
        "psy": "dog",           # nominative plural of pies
        "książki": "book",      # genitive singular / nominative plural of książka
        "mieczem": "sword",     # instrumental singular of miecz
        "robił": "do",          # masculine past of robić
        # Irregular / suppletive — unreachable by any algorithmic stemmer.
        "dzieci": "child",      # dziecko
        "ludzi": "person",      # człowiek
        "ręce": "hand",         # ręka
    },
    "hi": {
        # Devanagari inflected surfaces that resolve ONLY via a forms[] alias
        # row (Hindi has no stemmer). Verified against the built pack.
        "कुत्ते": "dog",         # oblique/plural of कुत्ता
        "कुत्तों": "dog",        # oblique plural of कुत्ता
        "विश्वों": "universe",   # plural of विश्व
    },
    # Other languages: fill in per-rebuild. Empty is OK — no fixtures
    # means "build still succeeds, just no regression guard for this lang."
}


# ── Arabic post-build augmentation ────────────────────────────────────────
#
# Runs ONLY for `--lang ar`, between build_sqlite and the smoke test:
#
#   1. unique index — FIRST: makes the no-duplicate-row invariant structural so
#                     every augmentation insert below can be INSERT OR IGNORE.
#   2. morphology   — Arramooz + camel_morph surface→lemma position-2 alias rows
#                     (heavy; scripts/arabic_morphology.py, imported lazily).
#   3. fold pass    — position-3 folded variants of the position-0 LEMMAS only
#                     (NOT the aliases — see _emit_fold_rows for the measured
#                     reason). It reads only position-0 rows, so it is
#                     independent of step 2; only the unique index must precede
#                     both. Order 2-before-3 is tidiness, not correctness.
#
# `headword.position` tiers: 0 lemma (display), 1 Snowball stem, 2 alias,
# 3 folded variant (casual/variant spelling — lookup-only, never displayed).


def _create_headword_unique_index(conn: sqlite3.Connection) -> None:
    """Make (entry_id, position, text) unique so the morphology + fold inserts
    can't create duplicate rows. Creating it also self-checks that the base
    pass-1/2 rows carry no exact dupes (it raises otherwise). Invisible to the
    runtime schema probe, which checks columns, not indexes."""
    conn.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_headword_unique "
        "ON headword(entry_id, position, text)"
    )


def _emit_fold_rows(conn: sqlite3.Connection) -> None:
    """For every position-0 LEMMA, insert a position-3 'folded variant' row when
    arabic_fold(text) differs from the stored text — the separate internal lookup
    key WiktionaryDictionaryManager.lookup tries as a fallback for casual/variant
    spellings of the looked-up word. Position-0 display rows stay un-folded.

    Only lemmas are folded, NOT the position-2 morphology aliases: measured on
    the real pack, folding the (large) alias set drove the fold-key collision
    rate to ~61% (vs ~9% for lemmas alone) and ~13x the fold rows, for the narrow
    benefit of casual-spelling a non-lemma inflected form — which the canonical
    (position<=2) query already resolves directly.

    Accepted limitation: the fold fallback therefore covers casual/variant
    spellings of LEMMAS only. A casual misspelling of an inflected/plural alias
    (e.g. a broken plural ending ى typed with ي) is intentionally NOT
    fold-reachable; the correctly-spelled alias still resolves via the canonical
    query. Prints the collision rate so the lossy-fold tradeoff stays visible."""
    rows = conn.execute(
        "SELECT entry_id, text FROM headword WHERE position = 0"
    ).fetchall()
    fold_rows: list[tuple[int, str]] = []
    key_to_entries: dict[str, set[int]] = {}
    for entry_id, text in rows:
        folded = arabic_fold(text)
        if folded != text:
            fold_rows.append((entry_id, folded))
            key_to_entries.setdefault(folded, set()).add(entry_id)
    conn.executemany(
        "INSERT OR IGNORE INTO headword (entry_id, position, text) VALUES (?, 3, ?)",
        fold_rows,
    )
    if key_to_entries:
        collisions = sum(1 for ents in key_to_entries.values() if len(ents) > 1)
        total = len(key_to_entries)
        print(
            f"Fold pass: {len(fold_rows)} position-3 candidate rows over {total} "
            f"distinct fold keys; {collisions} keys "
            f"({100.0 * collisions / total:.2f}%) collide across >1 entry."
        )
    else:
        print("Fold pass: no folded variants needed.")


def postprocess_arabic(db_path: Path) -> None:
    """Arabic-only post-build pass (unique index → morphology → fold). See the
    block comment above for the ordering rationale."""
    import arabic_morphology  # heavy (camel-tools); imported only for --lang ar

    camel_db = os.environ.get("CAMEL_MORPH_DB")
    conn = sqlite3.connect(db_path)
    try:
        _create_headword_unique_index(conn)
        stats = arabic_morphology.augment_arabic(conn, camel_db_path=camel_db)
        print(f"Morphology augmentation gated to {stats['lemmas']} pack lemmas.")
        _emit_fold_rows(conn)
        conn.commit()
    finally:
        conn.close()


def postprocess_polish(db_path: Path, polimorf_tsv: Path) -> None:
    """Polish-only post-build pass: unique index, then PoliMorf alias rows.

    Polish has no Snowball stemmer, so position-1 rows are never written and
    these position-2 rows are the ONLY path from an inflected surface to its
    lemma. See docs/polish-source-language-plan.md."""
    import polish_morphology

    conn = sqlite3.connect(db_path)
    try:
        _create_headword_unique_index(conn)
        stats = polish_morphology.augment_polish(conn, polimorf_tsv)
        print(
            f"PoliMorf augmentation: {stats['rows']:,} alias rows over "
            f"{stats['lemmas']:,} pack lemmas "
            f"({stats['match_rate']:.1%} of lemmas known to PoliMorf)."
        )
        conn.commit()
    finally:
        conn.close()


def run_smoke_test(db_path: Path, lang: str) -> None:
    """Replay WiktionaryDictionaryManager.lookup against a small fixture
    set to catch regressions where plurals/conjugations no longer
    resolve to their lemma gloss. Raises on failure; no-op when the
    language has no fixtures."""
    if lang == "ar":
        _assert_arabic_normalize()
        _assert_arabic_fold()
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
            # Mirror WiktionaryDictionaryManager.lookup's cascade exactly:
            #   surface (canonical tiers, position<=2)
            #   → folded (Arabic only; position=3 EXACTLY, matching queryEntryIds'
            #     foldedTier — so a broken/missing fold row can't be masked by a
            #     same-text canonical or alias row at position 0-2)
            #   → stem   (canonical tiers, position<=2).
            # The position<=2 ceiling on surface/stem is what forces a genuine
            # casual-spelling fixture through the fold step instead of letting a
            # position-3 row satisfy the plain surface query.
            rows = conn.execute(
                "SELECT s.glosses FROM headword h JOIN sense s ON s.entry_id=h.entry_id "
                "WHERE h.text = ? AND h.position <= 2 ORDER BY h.entry_id",
                (surface_l,),
            ).fetchall()
            if not rows and lang == "ar":
                folded = arabic_fold(surface)
                rows = conn.execute(
                    "SELECT s.glosses FROM headword h JOIN sense s ON s.entry_id=h.entry_id "
                    "WHERE h.text = ? AND h.position = 3 ORDER BY h.entry_id",
                    (folded,),
                ).fetchall()
            if not rows and stemmer is not None:
                stem = stemmer.stemWord(surface_l)
                if stem and stem != surface_l:
                    rows = conn.execute(
                        "SELECT s.glosses FROM headword h JOIN sense s ON s.entry_id=h.entry_id "
                        "WHERE h.text = ? AND h.position <= 2 ORDER BY h.entry_id",
                        (stem,),
                    ).fetchall()
            if not rows:
                failures.append(f"  {surface!r}: no rows via surface/fold/stem lookup")
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
    parser.add_argument(
        "--polimorf-tsv", type=Path, required=False,
        help="form\\tlemma TSV from scripts/polish-morphology/DumpPoliMorf.java. "
             "Required for --lang pl; ignored otherwise.",
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
    # Validate the Polish morphology TSV UP FRONT — build_sqlite streams the
    # 772 MB extract for tens of minutes, so discovering the missing flag after
    # that would waste the whole run.
    if args.lang == "pl" and (args.polimorf_tsv is None or not args.polimorf_tsv.is_file()):
        print("error: --lang pl requires --polimorf-tsv "
              "(see scripts/polish-morphology/DumpPoliMorf.java)", file=sys.stderr)
        return 1

    build_sqlite(args.input, db_path, args.lang)
    if args.lang == "ar":
        postprocess_arabic(db_path)
    elif args.lang == "pl":
        postprocess_polish(db_path, args.polimorf_tsv)
    run_smoke_test(db_path, args.lang)

    tokenizer_entries = None
    if args.lang == "ko" and args.komoran_jar is not None:
        if not args.komoran_jar.is_file():
            print(f"error: --komoran-jar not a file: {args.komoran_jar}", file=sys.stderr)
            return 1
        tokenizer_entries = extract_komoran_models(args.komoran_jar, tokenizer_dir)
    elif args.komoran_jar is not None and args.lang != "ko":
        print(f"warning: --komoran-jar ignored for lang={args.lang}", file=sys.stderr)

    extra_root_files: list[Path] = []
    if args.lang == "th":
        # ThaiEngine's newmm matcher segments against words.txt (Wiktionary
        # headwords ∪ PyThaiNLP CC0), shipped at the pack root.
        words_path = args.output / "words.txt"
        entry = build_thai_wordlist(db_path, words_path)
        tokenizer_entries = (tokenizer_entries or []) + [entry]
        extra_root_files.append(words_path)

    build_manifest(db_path, manifest_path, args.lang, args.pack_version, tokenizer_entries)
    build_zip(
        db_path, manifest_path, zip_path,
        tokenizer_dir if (args.lang == "ko" and tokenizer_entries) else None,
        extra_root_files,
    )

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
