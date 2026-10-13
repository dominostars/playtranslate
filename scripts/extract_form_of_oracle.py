#!/usr/bin/env python3
"""Extract kaikki's "form of" pages as an oracle for the inflection tables.

Wiktionary has a page for most individual inflected forms, separate from the
lemma's conjugation table, saying what the form is: `pon` is "second-person
singular imperative of poner" (kaikki: a sense with `form_of: [{word: poner}]`
and tags like `form-of imperative second-person singular`). Those pages are
written independently of the tables, and kaikki carries them for every
language, so they are an answer key the inflection feature can be checked
against at population scale and without knowing the grammar: run the app's
lookup on each surface and require the named lemma with a reading whose tags
cover the page's grammatical tags (app/src/test/.../PackInflectionOracleTest).

Output: a UTF-8 TSV, one line per form-of sense:
    surface \\t lemma \\t pos \\t tags (space-joined, sorted, `form-of` removed) \\t gloss

  python3 scripts/extract_form_of_oracle.py --lang es \\
      --input local/kaikki-es/kaikki.org-dictionary-Spanish.jsonl \\
      --output local/inflection-dev/es/form_of_oracle.tsv
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from build_latin_dict import KAIKKI_LANG_ALIASES, iter_kaikki  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--lang", required=True)
    ap.add_argument("--input", type=Path, required=True)
    ap.add_argument("--output", type=Path, required=True)
    args = ap.parse_args()
    kaikki_lang = KAIKKI_LANG_ALIASES.get(args.lang, args.lang)
    pages = senses = 0
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as out:
        for obj in iter_kaikki(args.input):
            if obj.get("lang_code") != kaikki_lang:
                continue
            word = (obj.get("word") or "").strip()
            pos = (obj.get("pos") or "").lower()
            if not word or not pos:
                continue
            hit = False
            for sense in obj.get("senses") or ():
                targets = [t.get("word", "").strip() for t in (sense.get("form_of") or ()) if t.get("word")]
                if not targets:
                    continue
                tags = sorted(t for t in (sense.get("tags") or ()) if t != "form-of")
                gloss = " / ".join(g.strip() for g in (sense.get("glosses") or ()) if g)
                for target in targets:
                    if any(ch in target for ch in "\t\n") or any(ch in word for ch in "\t\n"):
                        continue
                    out.write(f"{word}\t{target}\t{pos}\t{' '.join(tags)}\t{gloss.replace(chr(9), ' ')}\n")
                    senses += 1
                    hit = True
            if hit:
                pages += 1
    print(f"[{args.lang}] {pages:,} form-of pages, {senses:,} form-of senses -> {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
