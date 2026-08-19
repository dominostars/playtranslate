#!/usr/bin/env python3
"""Polish morphology augmentation — surface->lemma alias rows (position 2).

Imported LAZILY by build_latin_dict.py only for `--lang pl`. Unlike Arabic,
Polish needs NO extra Python deps: the morphology is produced JVM-side by
scripts/polish-morphology/DumpPoliMorf.java (Morfologik / PoliMorf,
BSD-2-Clause) as a `form\\tlemma` TSV, and this module only bridges that TSV
into position-2 alias rows gated on the pack's kept lemmas.

Polish has NO Snowball stemmer, so no position-1 stem rows are ever written for
pl — these position-2 rows are the ONLY path from an inflected surface to its
lemma. A surface-only regression is therefore total, which is why the smoke
fixtures are all inflected forms. See docs/polish-source-language-plan.md.
"""

from __future__ import annotations

import sqlite3

from pack_aliases import _insert_aliases, _load_kept_lemma_ids

# Floor on the PoliMorf bridge: the fraction of pack lemmas PoliMorf knows.
# Measured 93.6% on a real sample; a value near zero means the TSV failed to
# load or the lowercasing bridge broke. Fail the build loudly rather than ship
# a silently surface-only pack. (Not a coverage gate — only a broken-integration
# detector, exactly like CAMEL_MIN_SURFACE_MATCH_RATE.)
POLIMORF_MIN_LEMMA_MATCH_RATE = 0.50


def augment_polish(conn: sqlite3.Connection, polimorf_tsv_path) -> dict:
    """Insert position-2 surface->lemma alias rows from the PoliMorf TSV.

    Gated on kept_lemma_ids (position-0 rows) so no row can be an orphan, and
    INSERT OR IGNORE so overlaps with the forms[] pass dedup structurally.
    BOTH sides of every TSV pair are re-normalized through lower_for_lang(...,
    "pl") — the same function that produced the position-0 keys — so the pack
    key never diverges from the Java dumper's Locale.ROOT lowercasing (the
    match-rate floor below is what catches it if it ever does).

    Operates on the already-open [conn]; the caller commits. Returns a stats
    dict (lemmas, rows, match_rate) the caller prints, matching the Arabic
    call site."""
    from build_latin_dict import lower_for_lang

    cur = conn.cursor()
    kept = _load_kept_lemma_ids(conn)

    alias_pairs: set[tuple[int, str]] = set()
    matched_lemmas: set[str] = set()
    with open(polimorf_tsv_path, encoding="utf-8") as f:
        for line in f:
            form, sep, lemma = line.rstrip("\n").partition("\t")
            if not sep:
                continue
            lemma_key = lower_for_lang(lemma, "pl")
            ids = kept.get(lemma_key)
            if not ids:
                continue  # lemma not in pack -> nothing to alias TO
            matched_lemmas.add(lemma_key)
            form_key = lower_for_lang(form, "pl")
            if not form_key or form_key == lemma_key:
                continue
            for entry_id in ids:
                alias_pairs.add((entry_id, form_key))

    inserted = _insert_aliases(cur, alias_pairs)
    # Denominator = single-token pack lemmas only. kaikki keeps up to 3-word
    # headwords, but PoliMorf is single-token by construction, so a multi-word
    # pack lemma can NEVER match — counting it dilutes the rate far below the
    # ~90% a healthy single-token bridge achieves (measured 88% single-token vs
    # 66% with multi-word entries in the denominator). The floor detects a BROKEN
    # bridge (rate near 0); an honest denominator is what keeps it meaningful.
    single_token_lemmas = sum(1 for k in kept if " " not in k)
    match_rate = len(matched_lemmas) / max(1, single_token_lemmas)
    if match_rate < POLIMORF_MIN_LEMMA_MATCH_RATE:
        raise SystemExit(
            f"error: PoliMorf lemma match rate {match_rate * 100:.2f}% below floor "
            f"{POLIMORF_MIN_LEMMA_MATCH_RATE * 100:.0f}% — the TSV failed to load or the "
            f"lowercasing bridge broke. Refusing to ship a silently surface-only pack."
        )
    return {"lemmas": len(matched_lemmas), "rows": inserted, "match_rate": match_rate}
