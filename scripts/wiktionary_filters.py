"""
Shared kaikki.org / Wiktionary filter constants and predicates.

Lives here so every build script applies the same rules — prevents drift
between build_latin_dict.py (source packs) and build_target_pack.py
(target gloss packs). If a filter needs to differ per pipeline, the
individual script can override after import; the default should be the
most restrictive shared definition.
"""

from __future__ import annotations

from typing import Any

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
})

# Caps shared across pipelines to keep pack size bounded.
MAX_SENSES_PER_ENTRY: int = 8
MAX_HEADWORD_WORDS: int = 3


def is_redirect_sense(sense: dict[str, Any]) -> bool:
    """A single sense is a redirect if it carries any WIKT_REDIRECT_KEYS."""
    return any(sense.get(k) for k in WIKT_REDIRECT_KEYS)


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
