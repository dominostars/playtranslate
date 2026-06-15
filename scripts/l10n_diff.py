#!/usr/bin/env python3
"""
l10n_diff.py - detect drift between the English source strings.xml and the
translated locale files.

READ-ONLY. This script never edits any file. It reports, per locale, three sets:

  MISSING   translatable keys present in English but absent from the locale
            -> translate and insert (mirroring English order)
  ORPHAN    keys present in the locale that English no longer has (translatable)
            -> delete from the locale
  MODIFIED  keys whose English text changed since the baseline English file
            -> the existing translation is stale; re-check / re-translate
            (only computed when --english-base is given)

MISSING + ORPHAN are structural and need only the current English + locale files.
MODIFIED needs a baseline English file (the English strings.xml as of the last
localization sync), e.g. from git:

  git show l10n-sync:app/src/main/res/values/strings.xml > /tmp/en-base.xml

Exit status is non-zero if any MISSING or ORPHAN key is found (CI-friendly).
Full workflow: docs/l10n-updating-locales.md
"""

import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET

XLIFF_NS = "urn:oasis:names:tc:xliff:document:1.2"
ET.register_namespace("xliff", XLIFF_NS)

DEFAULT_ENGLISH = "app/src/main/res/values/strings.xml"
LOCALE_GLOB = "app/src/main/res/values-*/strings.xml"


def _inner(elem):
    """Canonical text of an element's inner content (text + child markup),
    serialized identically across runs so two parses can be compared.

    For <plurals>, each <item>'s quantity is included so a change to any
    category (or its text) is detected."""
    if elem.tag == "plurals":
        parts = []
        for item in elem:
            if item.tag is ET.Comment:
                continue
            parts.append("[%s]%s" % (item.get("quantity", ""), _inner(item)))
        return "".join(parts)
    text = elem.text or ""
    for child in elem:
        if child.tag is ET.Comment:
            continue
        text += ET.tostring(child, encoding="unicode")
    # ET re-declares the namespace on every serialized child; drop it so the
    # value reads cleanly and compares stably (the decl is always identical).
    return text.replace(' xmlns:xliff="%s"' % XLIFF_NS, "").strip()


def parse(path):
    """Parse one strings.xml. Returns (entries, order) where entries maps
    name -> {translatable, inner, comment} and order is the document-order
    list of names. The comment is the <!-- --> immediately preceding the
    entry (used to hand context to a translator)."""
    parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
    root = ET.parse(path, parser=parser).getroot()
    entries, order, pending = {}, [], ""
    for elem in root:
        if elem.tag is ET.Comment:
            pending = (elem.text or "").strip()
            continue
        if elem.tag in ("string", "plurals"):
            name = elem.get("name")
            if name is not None:
                entries[name] = {
                    "translatable": elem.get("translatable") != "false",
                    "inner": _inner(elem),
                    "comment": pending,
                }
                order.append(name)
        pending = ""
    return entries, order


def anchor_for(name, en_order, present):
    """Nearest preceding English key that will be present in the locale when this
    key is inserted, so a MISSING key can be spliced in to keep the file
    diffable. `present` is the locale's existing keys PLUS the missing keys
    already emitted earlier in this pass (MISSING is reported in English order),
    so a run of consecutive new keys chains: new_2 anchors to new_1 rather than
    to the same existing key as new_1."""
    idx = en_order.index(name)
    for prev in reversed(en_order[:idx]):
        if prev in present:
            return prev
    return None


def diff_locale(en, en_order, loc, base):
    en_tr = {n for n, e in en.items() if e["translatable"]}
    missing = [n for n in en_order if n in en_tr and n not in loc]
    orphan = sorted(set(loc) - en_tr)
    modified = []
    if base is not None:
        for n in en_order:
            if n in loc and n in en_tr and n in base:
                if en[n]["inner"] != base[n]["inner"]:
                    modified.append(n)
    return missing, orphan, modified


def _clip(s, n=140):
    s = " ".join(s.split())
    return s if len(s) <= n else s[: n - 1] + "…"


def report(locale_path, en, en_order, loc, base):
    missing, orphan, modified = diff_locale(en, en_order, loc, base)
    name = os.path.basename(os.path.dirname(locale_path))
    print("=" * 72)
    print("%s   missing=%d  orphan=%d  modified=%d"
          % (name, len(missing), len(orphan), len(modified)))
    print("=" * 72)

    if missing:
        print("\n-- MISSING (translate & insert) --")
        present = set(loc)  # grows as each missing key is "inserted", in order
        for n in missing:
            anchor = anchor_for(n, en_order, present)
            where = ("after %s" % anchor) if anchor else "at top of file"
            print("  %s   [insert %s]" % (n, where))
            if en[n]["comment"]:
                print("      ctx: %s" % _clip(en[n]["comment"]))
            print("      en:  %s" % _clip(en[n]["inner"]))
            present.add(n)  # this key now exists for subsequent anchors

    if orphan:
        print("\n-- ORPHAN (delete from locale) --")
        for n in orphan:
            print("  %s   was: %s" % (n, _clip(loc[n]["inner"])))

    if modified:
        print("\n-- MODIFIED (English changed; existing translation is stale) --")
        for n in modified:
            print("  %s" % n)
            print("      base: %s" % _clip(base[n]["inner"]))
            print("      now:  %s" % _clip(en[n]["inner"]))
            print("      loc:  %s" % _clip(loc[n]["inner"]))

    if not (missing or orphan or modified):
        print("\n  in sync.")
    print()
    return missing, orphan, modified


def main():
    ap = argparse.ArgumentParser(description="Detect English<->locale string drift (read-only).")
    ap.add_argument("--english", default=DEFAULT_ENGLISH, help="current English strings.xml")
    ap.add_argument("--english-base", help="baseline English strings.xml (enables MODIFIED detection)")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--locale", help="a single locale strings.xml to check")
    g.add_argument("--all", action="store_true", help="check every values-*/strings.xml")
    args = ap.parse_args()

    en, en_order = parse(args.english)
    base = parse(args.english_base)[0] if args.english_base else None

    if args.all:
        locales = sorted(p for p in glob.glob(LOCALE_GLOB)
                         if os.path.abspath(p) != os.path.abspath(args.english))
    else:
        locales = [args.locale]

    summary, dirty = [], False
    for path in locales:
        loc = parse(path)[0]
        m, o, mod = report(path, en, en_order, loc, base)
        summary.append((os.path.basename(os.path.dirname(path)), len(m), len(o), len(mod)))
        dirty = dirty or bool(m or o)

    if len(summary) > 1:
        print("=" * 72)
        print("SUMMARY               missing  orphan  modified")
        print("-" * 72)
        for name, m, o, mod in summary:
            print("  %-18s  %7d  %6d  %8s" % (name, m, o, mod if base is not None else "-"))
        print("=" * 72)

    sys.exit(1 if dirty else 0)


if __name__ == "__main__":
    main()
