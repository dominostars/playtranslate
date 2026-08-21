"""
Shared kaikki.org / Wiktionary filter constants and predicates.

Lives here so every build script applies the same rules — prevents drift
between build_latin_dict.py (source packs) and build_target_pack.py
(target gloss packs). If a filter needs to differ per pipeline, the
individual script can override after import; the default should be the
most restrictive shared definition.
"""

from __future__ import annotations

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
