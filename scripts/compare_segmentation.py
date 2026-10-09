#!/usr/bin/env python3
"""Diff two segmentation dumps: the device p5_500_segmentation.json
(SegmentationBatchTest) or the JVM corpus.json / survey.json (JaPackDumpTest).

Only each line's "tokens" are compared: surface, lookupForm, reading and
inflections. A token without an "inflections" key (dumps from before
JaPackDumpTest) counts as having none, so old and new dumps still compare.

Classifies each changed sentence:
  MERGED              fewer tokens, same concatenated surface text (new glob)
  SPLIT               more tokens, same concatenated surface text
  LOOKUPFORM_CHANGED  same surfaces in order, some lookupForm differs
                      (or, with every lookupForm and inflection list equal,
                      only a reading differs)
  INFLECTIONS_CHANGED same surfaces and lookupForms in order, some token's
                      inflection list differs; printed with the lists
  OTHER               anything else (surface coverage changed)

Review tool, not a gate: always exits 0.

Usage: compare_segmentation.py baseline.json candidate.json [--limit N]
"""

import argparse
import json
import sys
from collections import Counter

KINDS = ("MERGED", "SPLIT", "LOOKUPFORM_CHANGED", "INFLECTIONS_CHANGED", "OTHER")


def load(path):
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    return {entry["ja"]: [normalize(t) for t in entry["tokens"]] for entry in data}


def normalize(token):
    return {
        "surface": token["surface"],
        "lookupForm": token["lookupForm"],
        "reading": token.get("reading"),
        "inflections": token.get("inflections", []),
    }


def fmt(tokens):
    return " | ".join(
        "{}->{}".format(t["surface"], t["lookupForm"]) for t in tokens
    )


def fmt_inflections(tokens):
    return " | ".join(
        "{}->{} [{}]".format(t["surface"], t["lookupForm"], ", ".join(t["inflections"]))
        for t in tokens
    )


def classify(base, cand):
    base_surfaces = [t["surface"] for t in base]
    cand_surfaces = [t["surface"] for t in cand]
    if base_surfaces == cand_surfaces:
        same_forms = [t["lookupForm"] for t in base] == [t["lookupForm"] for t in cand]
        if same_forms and [t["inflections"] for t in base] != [t["inflections"] for t in cand]:
            return "INFLECTIONS_CHANGED"
        return "LOOKUPFORM_CHANGED"
    if "".join(base_surfaces) == "".join(cand_surfaces):
        if len(cand) < len(base):
            return "MERGED"
        if len(cand) > len(base):
            return "SPLIT"
    return "OTHER"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("baseline")
    ap.add_argument("candidate")
    ap.add_argument("--limit", type=int, default=None,
                    help="max changed sentences to print per type")
    args = ap.parse_args()

    base = load(args.baseline)
    cand = load(args.candidate)

    missing = sorted(set(base) - set(cand))
    added = sorted(set(cand) - set(base))
    if missing or added:
        print("WARNING: sentence sets differ "
              "({} only in baseline, {} only in candidate)".format(
                  len(missing), len(added)))

    changes = {}  # type -> list of (sentence, base_tokens, cand_tokens)
    for ja in base:
        if ja not in cand or base[ja] == cand[ja]:
            continue
        kind = classify(base[ja], cand[ja])
        changes.setdefault(kind, []).append((ja, base[ja], cand[ja]))

    counts = Counter({k: len(v) for k, v in changes.items()})
    total = sum(counts.values())
    print("{} of {} sentences changed".format(total, len(base)))
    for kind in KINDS:
        if kind in counts:
            print("  {}: {}".format(kind, counts[kind]))

    for kind in KINDS:
        entries = changes.get(kind, [])
        if not entries:
            continue
        line = fmt_inflections if kind == "INFLECTIONS_CHANGED" else fmt
        print("\n=== {} ({}) ===".format(kind, len(entries)))
        shown = entries if args.limit is None else entries[: args.limit]
        for ja, b, c in shown:
            print("\n  {}".format(ja))
            print("    - {}".format(line(b)))
            print("    + {}".format(line(c)))
        if len(entries) > len(shown):
            print("\n  ... {} more (use --limit to adjust)".format(
                len(entries) - len(shown)))

    return 0


if __name__ == "__main__":
    sys.exit(main())
