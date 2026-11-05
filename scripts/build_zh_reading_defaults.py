#!/usr/bin/env python3
"""
Build the lead-reading table for Chinese words with several readings.

The ZH pack scores frequency per written word (wordfreq), so two readings of
one word always tie and the app would lead with whichever CC-CEDICT lists
first, which is alphabetical pinyin: 要 yao1 "to demand" before yao4 "to
want", 东西 dong1 xi1 "east and west" before dong1 xi5 "thing". This table
names the reading a word most often has when it stands alone in running
text. The app reads it from assets and uses it only to break that tie
(ChineseEntryGroups); a word with no row keeps the pack's line order.

Sources, in order:
  1. scripts/zh_reading_defaults_curated.tsv: hand decisions, every
     multi-character word in the table and the single characters whose
     standalone word differs from the character's customary reading
     (教 jiao1 "to teach" as a word, jiao4 inside 教育).
  2. Single characters: the Unicode Han Database, kMandarin (the most
     customary reading; with two values the first is the zh-Hans one) and
     then kHanyuPinlu (readings by corpus frequency). A reading whose every
     gloss only points at compounds ("used in 漂亮[piao4 liang5]") is passed
     over while another reading of the character defines something, so the
     lead never moves to a pointer.
  3. Single characters Unihan settles nothing for, whose first listed
     reading is such a pointer (伺 ci4 "used in 伺候"): the first reading
     that defines something (si4 "to watch; to wait").
No row ever names a reading the app cannot lead with: one whose lines only
cite another headword ("variant of 橘[ju2]") ranks after every defining
reading in the app (ChineseEntryGroups), so such a row would be dead. A
curated row that does is an error; a curated row with basis "none" keeps a
word out of the table.
Multi-character words have no mechanical source: a word not in the curated
file gets no row. The run lists them.

Usage:
    python scripts/build_zh_reading_defaults.py \\
        --dict <extracted zh pack>/dict.sqlite \\
        --unihan <Unihan.zip extracted>/Unihan_Readings.txt

Unihan source: https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip
(Unicode License v3; the notice is written into the table's header).
"""

from __future__ import annotations

import argparse
import re
import sqlite3
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DEFAULT_CURATED = REPO / "scripts" / "zh_reading_defaults_curated.tsv"
DEFAULT_OUT = REPO / "app" / "src" / "main" / "assets" / "zh_reading_defaults.tsv"

# A gloss that defines nothing by itself: it names the compounds a reading
# occurs in, another headword, or how the reading is pronounced elsewhere.
POINTER_PREFIXES = (
    "used in ", "(bound form used in ", "see ", "variant of ", "old variant of ",
    "archaic variant of ", "erhua variant of ", "also written ", "Taiwan pr. ", "also pr. ",
)

# The app's own cross-reference rule (ChineseEntryGroups.isCrossReference):
# one of these prefixes and a bracketed citation. A reading whose lines are
# all such glosses cannot lead while another reading of the word has any
# other line.
CITATION_PREFIXES = (
    "variant of ", "old variant of ", "archaic variant of ", "erhua variant of ",
    "also written ", "see ",
)

TONE_MARKS = {"̄": "1", "́": "2", "̌": "3", "̀": "4"}

UNICODE_NOTICE = """\
UNICODE LICENSE V3

COPYRIGHT AND PERMISSION NOTICE

Copyright © 1991-2026 Unicode, Inc.

NOTICE TO USER: Carefully read the following legal agreement. BY
DOWNLOADING, INSTALLING, COPYING OR OTHERWISE USING DATA FILES, AND/OR
SOFTWARE, YOU UNEQUIVOCALLY ACCEPT, AND AGREE TO BE BOUND BY, ALL OF THE
TERMS AND CONDITIONS OF THIS AGREEMENT. IF YOU DO NOT AGREE, DO NOT
DOWNLOAD, INSTALL, COPY, DISTRIBUTE OR USE THE DATA FILES OR SOFTWARE.

Permission is hereby granted, free of charge, to any person obtaining a
copy of data files and any associated documentation (the "Data Files") or
software and any associated documentation (the "Software") to deal in the
Data Files or Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, and/or sell
copies of the Data Files or Software, and to permit persons to whom the
Data Files or Software are furnished to do so, provided that either (a)
this copyright and permission notice appear with all copies of the Data
Files or Software, or (b) this copyright and permission notice appear in
associated Documentation.

THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY
KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF
THIRD PARTY RIGHTS.

IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS NOTICE
BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES,
OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION,
ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THE DATA
FILES OR SOFTWARE.

Except as contained in this notice, the name of a copyright holder shall
not be used in advertising or otherwise to promote the sale, use or other
dealings in these Data Files or Software without prior written
authorization of the copyright holder.
"""


def key_of_marked(reading: str) -> str:
    """Comparison key of a tone-marked reading ("dōng xi", "lǜ", "ǹg")."""
    out = []
    for syllable in reading.split():
        letters, tone = [], "5"
        for ch in unicodedata.normalize("NFD", syllable):
            if ch in TONE_MARKS:
                tone = TONE_MARKS[ch]
            elif ch == "̈":            # the diaeresis of ü
                if letters and letters[-1] in "uU":
                    letters[-1] = "v"
            else:
                letters.append(ch)
        out.append("".join(letters) + tone)
    return " ".join(out)


def key_of_numbered(reading: str) -> str:
    """Comparison key of a reading as the pack stores it ("dong1 xi5", "lu:4")."""
    out = []
    for syllable in reading.split():
        s = syllable.replace("u:", "v").replace("U:", "V")
        out.append(s if s[-1].isdigit() else s + "5")
    return " ".join(out)


def is_pointer_only(glosses: list[str]) -> bool:
    return bool(glosses) and all(g.startswith(POINTER_PREFIXES) for g in glosses)


def only_cites(glosses: list[str]) -> bool:
    return bool(glosses) and all("[" in g and g.startswith(CITATION_PREFIXES) for g in glosses)


def load_lines(dict_path: Path) -> dict[str, list[tuple[str, list[str]]]]:
    """simplified headword -> its lines in pack order as (reading, glosses)."""
    con = sqlite3.connect(f"file:{dict_path}?mode=ro", uri=True)
    lines: dict[str, list[tuple[str, list[str]]]] = defaultdict(list)
    for text, reading, glosses in con.execute(
        "SELECT h.text, r.text, s.glosses FROM entry e "
        "JOIN headword h ON h.entry_id = e.id AND h.position = 0 "
        "JOIN reading r ON r.entry_id = e.id AND r.position = 0 "
        "JOIN sense s ON s.entry_id = e.id AND s.position = 0 ORDER BY e.id"
    ):
        lines[text].append((reading, [g for g in glosses.split("\t") if g.strip()]))
    con.close()
    return lines


def common_readings(word_lines: list[tuple[str, list[str]]]) -> list[str]:
    """The distinct readings of a word in pack order, proper nouns (capital
    initial, CC-CEDICT's convention) left out: they never lead."""
    seen: list[str] = []
    for reading, _ in word_lines:
        if reading[:1].islower() and reading not in seen:
            seen.append(reading)
    return seen


def load_unihan(path: Path) -> tuple[dict[str, list[str]], str]:
    """character -> its readings in Unihan's order of preference
    (kMandarin's values, then kHanyuPinlu by descending count), tone-marked;
    and the Unicode version the file declares."""
    mandarin: dict[str, list[str]] = {}
    pinlu: dict[str, list[str]] = {}
    version = "unknown"
    with open(path, encoding="utf-8") as f:
        for row in f:
            if row.startswith("#"):
                m = re.match(r"# Unicode Version (\S+)", row)
                if m:
                    version = m.group(1)
                continue
            parts = row.rstrip("\n").split("\t")
            if len(parts) != 3:
                continue
            code, field, value = parts
            if field == "kMandarin":
                mandarin[chr(int(code[2:], 16))] = value.split()
            elif field == "kHanyuPinlu":
                counted = [(int(m.group(2)), m.group(1)) for m in re.finditer(r"(\S+?)\((\d+)\)", value)]
                pinlu[chr(int(code[2:], 16))] = [r for _, r in sorted(counted, key=lambda c: -c[0])]
    ranked: dict[str, list[str]] = {}
    for ch in set(mandarin) | set(pinlu):
        order: list[str] = []
        for reading in mandarin.get(ch, []) + pinlu.get(ch, []):
            if reading not in order:
                order.append(reading)
        ranked[ch] = order
    return ranked, version


def load_curated(path: Path) -> dict[str, str | None]:
    """word -> the reading it leads with, or None for a word kept out of
    the table (basis "none")."""
    curated: dict[str, str | None] = {}
    with open(path, encoding="utf-8") as f:
        for n, row in enumerate(f, 1):
            row = row.rstrip("\n")
            if not row or row.startswith("#"):
                continue
            parts = row.split("\t")
            if len(parts) < 3:
                sys.exit(f"{path}:{n}: expected surface, reading, basis[, gloss]")
            if parts[0] in curated:
                sys.exit(f"{path}:{n}: {parts[0]} is listed twice")
            curated[parts[0]] = None if parts[2] == "none" else parts[1]
    return curated


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dict", required=True, type=Path, help="the ZH pack's dict.sqlite")
    ap.add_argument("--unihan", required=True, type=Path, help="Unihan_Readings.txt")
    ap.add_argument("--curated", type=Path, default=DEFAULT_CURATED)
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = ap.parse_args()

    lines = load_lines(args.dict)
    unihan, unicode_version = load_unihan(args.unihan)
    curated = load_curated(args.curated)

    population = {w: common_readings(ls) for w, ls in lines.items()}
    population = {w: rs for w, rs in population.items() if len(rs) >= 2}

    def leadable(word: str) -> list[str]:
        """The word's common readings the app can lead with, in pack order."""
        glosses_of = defaultdict(list)
        for reading, glosses in lines[word]:
            glosses_of[reading] += glosses
        can = [r for r in population[word] if not only_cites(glosses_of[r])]
        return can or population[word]

    errors = []
    for w, r in curated.items():
        if w not in population:
            errors.append(f"{w}: not a word with several common readings in this pack")
        elif r is not None and r not in population[w]:
            errors.append(f"{w}: curated reading {r!r} is not a common reading of the word in this pack")
        elif r is not None and r not in leadable(w):
            errors.append(f"{w}: curated reading {r!r} only cites another headword and cannot lead; the row would be dead")
    if errors:
        sys.exit("curated file does not fit the pack:\n  " + "\n  ".join(errors))

    rows: dict[str, str] = {}
    by_source = {"curated": 0, "unihan": 0, "first defining": 0}
    passed_over: list[str] = []
    for word in population:
        if word in curated:
            if curated[word] is not None:
                rows[word] = curated[word]
                by_source["curated"] += 1
            continue
        if len(word) != 1:
            continue
        glosses_of = defaultdict(list)
        for reading, glosses in lines[word]:
            glosses_of[reading] += glosses
        can_lead = leadable(word)
        defining = [r for r in can_lead if not is_pointer_only(glosses_of[r])]
        by_key = {key_of_numbered(r): r for r in can_lead}
        listed = [by_key[k] for k in (key_of_marked(u) for u in unihan.get(word, [])) if k in by_key]
        eligible = [r for r in listed if r in defining] if defining else listed
        if eligible:
            rows[word] = eligible[0]
            by_source["unihan"] += 1
        elif defining and can_lead[0] not in defining:
            rows[word] = defining[0]
            by_source["first defining"] += 1
        if listed and rows.get(word) != listed[0]:
            passed_over.append(f"{word} {listed[0]} -> {rows.get(word, 'no row')}")

    changed = sum(1 for w, r in rows.items() if r != population[w][0])
    unrowed_multi = sorted(w for w in population if len(w) > 1 and w not in rows)
    unrowed_single = sorted(w for w in population if len(w) == 1 and w not in rows)

    header = [
        "Lead readings for Chinese words with several readings: surface<TAB>reading,",
        "the reading numbered as the ZH pack stores it. A word with no row keeps the",
        "pack's line order. Generated by scripts/build_zh_reading_defaults.py; do not",
        "edit by hand. Hand decisions live in scripts/zh_reading_defaults_curated.tsv.",
        "",
        f"Single-character rows not in the curated file are derived from the Unicode",
        f"Han Database (Unihan) {unicode_version}, fields kMandarin and kHanyuPinlu, used under",
        "the licence below.",
        "",
    ] + UNICODE_NOTICE.rstrip("\n").split("\n")
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        for line in header:
            f.write(("# " + line).rstrip() + "\n")
        for word in sorted(rows):
            f.write(f"{word}\t{rows[word]}\n")

    print(f"multi-reading words in the pack: {len(population)} "
          f"({sum(1 for w in population if len(w) == 1)} single characters)")
    print(f"rows written: {len(rows)} (curated {by_source['curated']}, Unihan {by_source['unihan']}, "
          f"first defining reading {by_source['first defining']}); "
          f"{changed} name a reading other than the first listed")
    print(f"Unihan's first leadable reading passed over as a pointer: {len(passed_over)}")
    for item in passed_over:
        print(f"  {item}")
    print(f"single characters with no row: {len(unrowed_single)} {' '.join(unrowed_single)}")
    print(f"multi-character words with no row (line order stands): {len(unrowed_multi)}")
    print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
