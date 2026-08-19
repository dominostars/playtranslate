#!/usr/bin/env python3
"""Arabic morphology augmentation — surface→lemma alias rows (position 2).

Heavy module: imported by build_latin_dict.py ONLY for `--lang ar`. Its deps
(camel-tools morphology, wordfreq, arramooz) live in scripts/requirements-ar.txt,
kept out of the base requirements so non-Arabic pack builds stay light.

Two complementary sources, each gated to lemmas already in the pack
(`kept_lemma_ids`), emitted as INSERT OR IGNORE position-2 alias rows so neither
can introduce an orphan row or a duplicate:

  emit_camel    — CAMeL Tools + the camel_morph CC-BY-4.0 MSA database. Analyzes
                  the most frequent Arabic surfaces (wordfreq) and maps each to
                  its lemma(s). Covers broken/sound plurals, verb conjugations,
                  AND clitic stacks / enclitic pronouns (كتابه → كِتَاب)
                  productively — the agglutination long tail.
  emit_arramooz — Arramooz (GPLv3) noun table: broken_plural → singular lemma.
                  Lexicon-complete over ~30k nouns, so it also catches broken
                  plurals of nouns too rare to surface in the frequency-driven
                  camel pass.

Both bridge orthography with arabic_normalize: camel lemmas and arramooz forms
are diacritized, while the pack's position-0 keys are bare (undiacritized).

camel_morph note: the official PyPI camel-tools (>=1.5.7) loads
camel_morph_msa_v1.0.db directly — despite the camel_morph README mentioning a
fork, that requirement is stale. We use ONLY the morphology subpackage, so
torch/transformers are not needed (see requirements-ar.txt).
"""

from __future__ import annotations

import os
import re
import sqlite3

from arabic_text import arabic_normalize
from pack_aliases import _insert_aliases, _load_kept_lemma_ids

# Floor on the camel bridge: the fraction of analyzed surfaces that map to >=1
# in-pack lemma. A healthy run lands well above this; ~0 means the camel DB
# failed to load or the lemma-orthography bridge broke — fail the build loudly
# rather than ship a silently-degraded pack. (Distinct from the deferred
# coverage-diff gate; this only catches a broken integration.)
CAMEL_MIN_SURFACE_MATCH_RATE = 0.05

# Default breadth of the frequency-driven camel pass (~2.5 ms/word).
DEFAULT_WORDFREQ_TOP_N = 60000

# camel_morph lemmas are bare diacritized forms, but strip a trailing sense
# index (e.g. "kitaAb_1") defensively in case a future DB build adds them.
_LEMMA_INDEX_RE = re.compile(r"_\d+$")


def emit_camel(
    cur: sqlite3.Cursor,
    kept_lemma_ids: dict[str, list[int]],
    camel_db_path: str,
    top_n: int = DEFAULT_WORDFREQ_TOP_N,
) -> dict:
    """Analyze the top-[top_n] frequency-ranked Arabic surfaces and emit a
    position-2 alias for each (surface → in-pack lemma) pair the analyzer finds."""
    from camel_tools.morphology.analyzer import Analyzer
    from camel_tools.morphology.database import MorphologyDB
    from wordfreq import top_n_list

    analyzer = Analyzer(MorphologyDB(camel_db_path, flags="a"))
    surfaces = top_n_list("ar", top_n)

    alias_pairs: set[tuple[int, str]] = set()
    surfaces_with_inpack_lemma = 0
    for surface in surfaces:
        surf_key = arabic_normalize(surface)
        if not surf_key:
            continue
        lemma_keys: set[str] = set()
        for analysis in analyzer.analyze(surface):
            lex = analysis.get("lex")
            if not lex:
                continue
            lk = arabic_normalize(_LEMMA_INDEX_RE.sub("", lex))
            if lk in kept_lemma_ids:
                lemma_keys.add(lk)
        if lemma_keys:
            surfaces_with_inpack_lemma += 1
        for lk in lemma_keys:
            if surf_key == lk:
                continue  # the surface IS the lemma — not an alias
            for entry_id in kept_lemma_ids[lk]:
                alias_pairs.add((entry_id, surf_key))

    inserted = _insert_aliases(cur, alias_pairs)
    match_rate = surfaces_with_inpack_lemma / max(1, len(surfaces))
    return {
        "surfaces": len(surfaces),
        "surfaces_with_inpack_lemma": surfaces_with_inpack_lemma,
        "match_rate": match_rate,
        "alias_rows": inserted,
    }


def emit_arramooz(cur: sqlite3.Cursor, kept_lemma_ids: dict[str, list[int]]) -> dict:
    """Emit broken_plural → singular-lemma aliases from the Arramooz noun table."""
    import arramooz

    db_path = os.path.join(
        os.path.dirname(arramooz.__file__), "data", "arabicdictionary.sqlite"
    )
    src = sqlite3.connect(db_path)
    alias_pairs: set[tuple[int, str]] = set()
    try:
        for unvoc, broken in src.execute(
            "SELECT unvocalized, broken_plural FROM nouns WHERE broken_plural != ''"
        ):
            lemma_key = arabic_normalize(unvoc or "")
            if lemma_key not in kept_lemma_ids:
                continue
            # broken_plural lists 1+ forms separated by ';' or whitespace, and may
            # carry a '+ات' sound-plural NOTATION token — keep only literal forms.
            for token in re.split(r"[;\s،]+", broken):
                token = token.strip()
                if not token or "+" in token:
                    continue
                bp_key = arabic_normalize(token)
                if not bp_key or bp_key == lemma_key:
                    continue
                for entry_id in kept_lemma_ids[lemma_key]:
                    alias_pairs.add((entry_id, bp_key))
    finally:
        src.close()
    return {"alias_rows": _insert_aliases(cur, alias_pairs)}


def augment_arabic(
    conn: sqlite3.Connection,
    *,
    camel_db_path: str | None = None,
    use_camel: bool = True,
    use_arramooz: bool = True,
    wordfreq_top_n: int = DEFAULT_WORDFREQ_TOP_N,
) -> dict:
    """Insert position-2 surface→lemma alias rows from the enabled sources.
    Operates on the already-open [conn]; the caller commits. Per-source toggles
    let the heavy camel pass be dropped without unwinding the rest."""
    cur = conn.cursor()
    kept = _load_kept_lemma_ids(conn)
    stats: dict = {"lemmas": len(kept)}

    if use_arramooz:
        stats["arramooz"] = emit_arramooz(cur, kept)
        print(f"  Arramooz: {stats['arramooz']['alias_rows']} broken-plural alias rows.")

    if use_camel:
        if not camel_db_path or not os.path.exists(camel_db_path):
            raise SystemExit(
                "error: camel_morph DB not found. Set CAMEL_MORPH_DB to the path of "
                "camel_morph_msa_v1.0.db (see scripts/requirements-ar.txt)."
            )
        cm = emit_camel(cur, kept, camel_db_path, wordfreq_top_n)
        stats["camel"] = cm
        print(
            f"  camel_morph: {cm['alias_rows']} alias rows from {cm['surfaces']} "
            f"surfaces; {cm['match_rate'] * 100:.1f}% mapped to an in-pack lemma."
        )
        if cm["match_rate"] < CAMEL_MIN_SURFACE_MATCH_RATE:
            raise SystemExit(
                f"error: camel_morph surface match rate {cm['match_rate'] * 100:.2f}% "
                f"below floor {CAMEL_MIN_SURFACE_MATCH_RATE * 100:.0f}% — the DB failed "
                f"to load or the lemma-orthography bridge broke. Refusing to ship a "
                f"silently-degraded pack."
            )
    return stats
