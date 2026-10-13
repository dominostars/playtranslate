#!/usr/bin/env python3
"""Dump a pack's inflection tagsets as the fixture of its golden label test.

The app labels a looked-up form from the kaikki tags the pack stores for it
(app/src/main/.../language/inflection/<Language>Paradigm.kt). The tagsets a
pack carries are few (939 for es-v5) and enumerable, so the labeller is gated
exhaustively rather than sampled: this script writes every tagset of a pack
with its line count and an example surface, and the per-language golden test
(SpanishTagsetGoldenTest) runs each through the labeller and compares with a
reviewed golden file. A convention the labeller does not handle then shows up
as an unlabelled or changed line with a count, before any human reads labels
on a device.

A clitic tagset (object-*, dative, accusative, reflexive, combined-form) gets
one line per attached pronoun its surfaces carry, because kaikki gives
háblele, háblelo and háblese one tagset and the label reads the pronoun off
the surface. The pronoun hangs off the verb, the first word of a phrasal
surface that is not itself a bare pronoun (démonos a luz, lo pasado); a verb
ending in none of the pronouns is grouped under "-".

Output: a UTF-8 TSV sorted by tags, one line per group:
    lines \\t example surface \\t tags (space-joined, as the pack stores them)

  python3 scripts/dump_inflection_tagsets.py \\
      --db local/inflection-dev/es/dict.sqlite \\
      --output app/src/test/resources/inflection/es/tagsets.tsv
"""
from __future__ import annotations

import argparse
import sqlite3
from collections import Counter
from pathlib import Path

CLITIC_TAGS = {"combined-form", "dative", "accusative", "reflexive"}
# Longest first, so "los" wins over "os" and "les" over "es".
PRONOUNS = ["nos", "los", "las", "les", "os", "me", "te", "se", "lo", "la", "le"]


BARE_PRONOUNS = {"me", "te", "se", "nos", "os", "lo", "la", "los", "las", "le", "les"}


def attached(surface: str) -> str:
    # The verb is the first word that is not itself a bare pronoun (lo pasado
    # under pasarlo), as SpanishParadigm.verbWord reads it.
    verb = next((w for w in surface.split(" ") if w not in BARE_PRONOUNS), surface)
    for p in PRONOUNS:
        if verb.endswith(p) and len(verb) > len(p):
            return p
    return "-"


def is_clitic(tags: set[str]) -> bool:
    return bool(tags & CLITIC_TAGS) or any(t.startswith("object-") for t in tags)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", type=Path, required=True, help="the pack's dict.sqlite")
    ap.add_argument("--output", type=Path, required=True)
    args = ap.parse_args()

    conn = sqlite3.connect(f"file:{args.db}?mode=ro", uri=True)
    tagsets = {tid: tags for tid, tags in conn.execute("SELECT id, tags FROM inflection_tagset")}
    counts: Counter[tuple[str, str]] = Counter()
    examples: dict[tuple[str, str], str] = {}
    for (blob,) in conn.execute("SELECT forms FROM inflection ORDER BY entry_id"):
        for line in blob.split("\n"):
            tid, surface = line.split("\t", 1)
            tags = tagsets[int(tid)]
            key = (tags, attached(surface) if is_clitic(set(tags.split())) else "")
            counts[key] += 1
            examples.setdefault(key, surface)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as out:
        for key in sorted(counts):
            out.write(f"{counts[key]}\t{examples[key]}\t{key[0]}\n")
    print(f"{len(tagsets)} tagsets, {len(counts)} lines, {sum(counts.values())} forms -> {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
