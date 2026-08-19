#!/usr/bin/env python3
"""Shared position-2 alias helpers for the pack morphology augmenters.

scripts/arabic_morphology.py and scripts/polish_morphology.py both gate their
surface->lemma sources on the pack's already-kept lemmas (`kept_lemma_ids`) and
insert position-2 rows the same way. These two helpers are that shared
mechanism, lifted here so the two language modules can't drift on it.

Position tiers in `headword`: 0 lemma (display), 1 Snowball stem, 2 alias
(surface->lemma), 3 folded variant. Everything here is position 2.
"""

from __future__ import annotations

import sqlite3


def _load_kept_lemma_ids(conn: sqlite3.Connection) -> dict[str, list[int]]:
    """Map each position-0 lemma key -> its entry_id(s). Aliases are emitted only
    for lemmas already in the pack, so no source can introduce an orphan row."""
    out: dict[str, list[int]] = {}
    for entry_id, text in conn.execute(
        "SELECT entry_id, text FROM headword WHERE position = 0"
    ):
        out.setdefault(text, []).append(entry_id)
    return out


def _insert_aliases(cur: sqlite3.Cursor, pairs: set[tuple[int, str]]) -> int:
    """INSERT OR IGNORE the (entry_id, position-2, surface) rows. The unique
    index on headword(entry_id, position, text) makes dedup structural — exact
    overlaps with Wiktionary form_of rows or the other source are dropped."""
    rows = list(pairs)
    cur.executemany(
        "INSERT OR IGNORE INTO headword (entry_id, position, text) VALUES (?, 2, ?)",
        rows,
    )
    return len(rows)
