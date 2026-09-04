"""
Shared kaikki.org / Wiktionary filter constants and predicates.

Lives here so every build script applies the same rules — prevents drift
between build_latin_dict.py (source packs) and build_target_pack.py
(target gloss packs). If a filter needs to differ per pipeline, the
individual script can override after import; the default should be the
most restrictive shared definition.
"""

from __future__ import annotations

import re

import json
import logging
import os
from typing import Any

_log = logging.getLogger("misc_filter")

# Wiktionary `pos` values that should never become dictionary entries.
# `name` = proper nouns; `character` = individual CJK characters which
# Latin packs don't need and target packs handle separately.
WIKT_EXCLUDED_POS: frozenset[str] = frozenset({
    "name",
    "character",
})

# Wiktionary "X of Y" redirect keys. When an entry or sense carries any
# of these, it's a pointer to another lemma rather than a definition —
# e.g. `volontari` as "masculine plural of volontario". Dropping these
# forces lookup to fall through to stem-based resolution (which should
# land on the real lemma).
# Longest prose form-of target we will treat as a headword. Mirrors
# build_latin_dict.MAX_HEADWORD_WORDS; kept here so the filter module stays
# importable on its own.
MAX_REDIRECT_TARGET_WORDS = 3

WIKT_REDIRECT_KEYS: frozenset[str] = frozenset({
    "form_of",
    "altspell_of",
    "alt_of",
    "compound_of",
    "abbreviation_of",
    "synonym_of",
})

# Content-word parts of speech kept in source packs. Matches the exact
# strings kaikki emits in its `pos` field (verified against the full
# English dataset on 2026-04-15). Excludes `name` (proper nouns — noise
# without translation value for game text).
#
# `det` / `article` / `postp` were added 2026-08 after a coverage audit of
# the shipped packs: excluding them silently deleted top-frequency function
# words with no homograph under a kept POS — en "every" (det-only, zipf
# 5.8) had NO entry; en "the"/"a" survived only via their adv/prep
# homographs (the article senses were gone); fr possessives mon/ma/votre/
# notre/cet and es lo/su/tus/aquellos were absent; hi postpositions को/से
# (among the most common words in the language) were absent. A tap on any
# of these resolved to nothing — or, worse, to a stem-fallback lookalike.
CONTENT_POS: frozenset[str] = frozenset({
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
    "det",
    "article",
    "postp",
})

# Caps shared across pipelines to keep pack size bounded.
MAX_SENSES_PER_ENTRY: int = 8
MAX_HEADWORD_WORDS: int = 3


# wiktextract sense TAGS that mark a form-of sense. A page can carry these
# without any of WIKT_REDIRECT_KEYS being populated — es `su` sense 1 is tagged
# nothing at all while sense 0 is tagged ('abbreviation', 'alt-of', 'apocopic').
WIKT_REDIRECT_TAGS: frozenset[str] = frozenset({"form-of", "alt-of"})

# Form-of glosses that carry their target only in prose. The keyword must sit
# directly before "of", after at most three qualifier words ("apocopic form of",
# "past participle of", "masculine plural of", "Dated spelling of").
#
# A leading article is a hard NO: Wiktionary's form-of glosses never begin with
# one, while ordinary noun definitions of the same shape do ("A form of address
# used in…", "A case of beer"), and without this guard those read as redirects.
# The target-length ceiling in redirect_target_from_gloss is the other half of
# that guard.
_FORM_OF_GLOSS_RE = re.compile(
    r"^\s*(?:\([^)]*\)\s*)*"                       # optional "(before the noun)" label
    r"(?!(?:an?|the)\s)"                            # never an ordinary definition
    r"(?:[^\W\d_][\w'’-]*\s+){0,3}"             # up to 3 qualifier words
    r"(?:form|spelling|version|contraction|abbreviation|clipping|misspelling|"
    r"plural|participle|tense|singular|case)\s+of\s+"
    r"(?P<target>\S.*)$",
    re.IGNORECASE,
)

# The target ends at the first punctuation that cannot be part of a headword —
# "past participle of készül:" and "Dated spelling of today." both carry one,
# and "Alternative form of run-in (adjective)" carries a trailing gloss label.
_GLOSS_TARGET_END_RE = re.compile(r"[(:,;.]")


def redirect_target_from_gloss(gloss: str, max_words: int) -> str | None:
    """The lemma a prose form-of gloss points at, or None when the gloss is not
    a form-of gloss (or names something that cannot be a headword).

    Returning None for an unusable target is deliberate and load-bearing: this
    same function decides whether the sense COUNTS as a redirect, so a page can
    never be dropped as a pointer we are then unable to follow. That hole is
    exactly what strands en `oneself`, whose entry-level form_of names the prose
    "the indefinite personal pronoun one"."""
    m = _FORM_OF_GLOSS_RE.match(gloss or "")
    if not m:
        return None
    target = _GLOSS_TARGET_END_RE.split(m.group("target"), 1)[0].strip()
    if not target or len(target.split()) > max_words:
        return None
    return target


def is_redirect_sense(sense: dict[str, Any]) -> bool:
    """A single sense is a redirect if it carries any WIKT_REDIRECT_KEYS, is
    TAGGED as a form-of sense, or its first gloss names its target in prose.

    The structured fields alone missed a class that matters now that det /
    article / postp are content POS: es `su` is glossed "apocopic form of suyo"
    on a sense with neither tags nor alias keys, so it survived as its own lemma
    and — under the position-first ranking — shadowed `suyo`, which is where the
    possessive glosses actually live."""
    if any(sense.get(k) for k in WIKT_REDIRECT_KEYS):
        return True
    if set(sense.get("tags") or ()) & WIKT_REDIRECT_TAGS:
        return True
    glosses = sense.get("glosses") or ()
    return bool(glosses) and redirect_target_from_gloss(
        glosses[0], MAX_REDIRECT_TARGET_WORDS
    ) is not None


def is_redirect_entry(entry: dict[str, Any]) -> bool:
    """An entry is entirely a redirect if:
    - Its top-level `form_of` / `alt_of` / etc. fields are set, OR
    - Every sense it lists is a redirect sense.

    Entries with at least one non-redirect sense are kept — we strip the
    redirect senses inside but preserve the real glosses.
    """
    if any(entry.get(k) for k in WIKT_REDIRECT_KEYS):
        return True
    senses = entry.get("senses") or []
    if not senses:
        return False
    return all(is_redirect_sense(s) for s in senses)


def is_multi_word_ok(word: str) -> bool:
    """True if [word] is at most MAX_HEADWORD_WORDS whitespace-separated tokens."""
    return len(word.split()) <= MAX_HEADWORD_WORDS


# TODO(next pack rebuild): drop multi-word entries whose EVERY sense is a
# Wiktionary `&lit` cross-reference stub — glosses starting with "Used other
# than figuratively or idiomatically" ("do you", "want to"; 179 in the en
# pack). Wiktionary itself marks these non-idiomatic and the entry defines
# nothing, yet it currently reaches both typed lookup and (but for the
# runtime gate) tap-time phrase matching. The runtime already filters them at
# phrase candidacy (WiktionaryDictionaryManager.phrasesExistQuery's
# LIT_STUB_PREFIX EXISTS clause) — dropping them here additionally cleans
# typed lookup and shrinks the packs; the runtime gate then filters nothing
# and remains as a safety. Keep entries where a stub sits ALONGSIDE real
# senses ("open the door") — only all-stub entries go.


# ── Misc register-tag filter ────────────────────────────────────────────
#
# The single curated misc vocabulary lives in
# app/src/main/assets/misc_vocabulary.json (loaded by the app's
# MiscVocabulary.kt too). [filter_misc] is the BUILD-side application: it
# keeps only register/dialect (normalized to the canonical English label),
# allowlisted domains, and gazetteer regions; everything else is dropped.
# The render layer (MiscLabels.renderMisc) re-applies the same rule, so
# cleanliness does not depend on a pack being rebuilt — this is purely a
# pack-size + clean-Anki-export optimization. See the plan doc.

_MISC_VOCAB_PATH = os.path.join(
    os.path.dirname(__file__),
    "..", "app", "src", "main", "resources", "misc_vocabulary.json",
)
_misc_vocab: dict[str, Any] | None = None


def _load_misc_vocab() -> dict[str, Any]:
    """Parse and index misc_vocabulary.json once. Returns lowercase lookup
    maps: alias→canonical-label, domain→display, region→display, plus the
    denylist and pos-owned drop sets."""
    global _misc_vocab
    if _misc_vocab is None:
        with open(_MISC_VOCAB_PATH, encoding="utf-8") as f:
            data = json.load(f)
        alias_to_label: dict[str, str] = {}
        for entry in data["register"]:
            label = entry["label"]
            for form in [*entry["aliases"], label]:
                alias_to_label[form.strip().lower()] = label
        _misc_vocab = {
            "alias": alias_to_label,
            "domain": {d.strip().lower(): d for d in data["domainAllowlist"]},
            "region": {r.strip().lower(): r for r in data["regionGazetteer"]},
            "deny": {d.strip().lower() for d in data["grammaticalDenylist"]},
            "pos_owned": {p.strip().lower() for p in data["posOwnedDrop"]},
        }
    return _misc_vocab


def _looks_like_tag(token: str) -> bool:
    """Tag-shaped (short) vs a freeform s_inf-style sentence. Only dropped
    tag-shaped tokens are worth logging — sentences are expected noise."""
    return len(token) <= 30 and len(token.split()) <= 3


def filter_misc(tags: list[str] | None, raw_tags: list[str] | None = None) -> list[str]:
    """Curate a sense's misc tags to the canonical register vocabulary.

    [tags]      kaikki `.tags` (English-normalized) OR JMdict's misc/field/
                dial stream — register/dialect/domain are matched here, and
                JMdict dialect names (`Kansai-ben`) are caught by the region
                gazetteer.
    [raw_tags]  kaikki `.raw_tags` (edition-language/freeform) — ONLY the
                region gazetteer is matched here; everything else is freeform
                and dropped silently.

    Register/dialect tokens are normalized to their canonical English label;
    domains and regions pass through as-is. Order is preserved, deduped.
    Dropped tag-shaped tokens not on the grammatical denylist are logged so
    gazetteer/register gaps surface.
    """
    v = _load_misc_vocab()
    out: list[str] = []
    seen: set[str] = set()

    def add(value: str) -> None:
        if value not in seen:
            seen.add(value)
            out.append(value)

    for raw in tags or []:
        n = raw.strip().lower()
        if not n or n in v["pos_owned"] or n in v["deny"]:
            continue
        if n in v["alias"]:
            add(v["alias"][n])
        elif n in v["domain"]:
            add(v["domain"][n])
        elif n in v["region"]:
            add(v["region"][n])
        elif _looks_like_tag(raw):
            _log.info("dropped unrecognized misc tag: %r", raw)

    for raw in raw_tags or []:
        n = raw.strip().lower()
        if n in v["region"]:
            add(v["region"][n])

    return out
