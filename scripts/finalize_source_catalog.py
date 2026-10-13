#!/usr/bin/env python3
"""Finalize langpack_catalog.json for rebuilt source packs (optional upgrade).

For each source language with a built <lang>/<lang>.zip under --build-dir, bump
its catalog entry to the new packVersion (default 2), KEEP additiveFromVersion
unchanged so the previous version takes the additive / optional ("Update Later")
upgrade path, point the URL at the <lang>-v<version> release tag (on the packs host, see
scripts/pack_urls.py), and refresh sha256 + size from the actual zip.

Run AFTER uploading each <lang>.zip to its <lang>-v<version> release on
github.com/dominostars/playtranslate-langpacks, then commit the catalog.

  python3 scripts/finalize_source_catalog.py --build-dir <dir> [--lang hi th] [--pack-version 2]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path

import pack_urls

ROOT = Path(__file__).resolve().parent.parent
CATALOG = ROOT / "app" / "src" / "main" / "assets" / "langpack_catalog.json"

def rewrite_entry(text: str, lang: str, fields: dict[str, object]) -> str:
    """Rewrites the scalar [fields] of one pack entry in the catalog's TEXT,
    touching nothing else, so the commit diffs as that entry's lines alone.
    The file mixes literal non-ASCII text with \\uXXXX escapes (the Hy-MT2
    attributions), which no json.dumps setting reproduces, so re-serializing
    the whole catalog rewrote lines outside the entry being finalized (the
    es-v5 run of 2026-10-13). A field the entry lacks (additiveFromVersion on
    an entry that never had it) is inserted after packVersion."""
    entry = re.search(r'^    "%s": \{\n.*?^    \}' % re.escape(lang), text, re.S | re.M)
    if entry is None:
        raise SystemExit(f"{lang}: no entry at indent 4 in the catalog text")
    block = entry.group(0)
    for key, value in fields.items():
        rendered = json.dumps(value, ensure_ascii=False)
        line = re.compile(r'^(      "%s": )(.*?)(,?)$' % re.escape(key), re.M)
        if line.search(block) is None:
            anchor = re.search(r'^      "packVersion": .*\n', block, re.M)
            if anchor is None:
                raise SystemExit(f"{lang}: neither {key} nor packVersion in the entry text")
            block = block[:anchor.end()] + f'      "{key}": {rendered},\n' + block[anchor.end():]
        else:
            block, n = line.subn(lambda m, r=rendered: m.group(1) + r + m.group(3), block, count=1)
            if n != 1:
                raise SystemExit(f"{lang}: {key} not rewritten")
    return text[:entry.start()] + block + text[entry.end():]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--build-dir", type=Path, required=True,
                    help="directory containing <lang>/<lang>.zip subdirs")
    ap.add_argument("--lang", nargs="*", default=None,
                    help="limit to these langs (default: every built zip found)")
    ap.add_argument("--pack-version", type=int, default=2)
    args = ap.parse_args()

    original = CATALOG.read_text(encoding="utf-8")
    text = original
    catalog = json.loads(original)
    packs = catalog["packs"]
    updated: list[str] = []
    skipped: list[str] = []
    for sub in sorted(args.build_dir.iterdir()):
        lang = sub.name
        if args.lang and lang not in args.lang:
            continue
        zip_path = sub / f"{lang}.zip"
        if not zip_path.is_file():
            continue
        entry = packs.get(lang)
        if entry is None:
            skipped.append(f"{lang} (not in catalog)")
            continue
        # Keep additiveFromVersion as-is so the prior version stays an optional
        # upgrade; only fall back to 1 if the entry never had it.
        data = zip_path.read_bytes()
        fields: dict[str, object] = {
            "additiveFromVersion": entry.get("additiveFromVersion", 1),
            "packVersion": args.pack_version,
            "size": len(data),
            "url": pack_urls.langpack_url(f"{lang}-v{args.pack_version}", f"{lang}.zip"),
            "sha256": hashlib.sha256(data).hexdigest(),
        }
        entry.update(fields)
        text = rewrite_entry(text, lang, fields)
        updated.append(lang)

    # The textual edit must mean exactly what the dict edit means.
    if json.loads(text) != catalog:
        raise SystemExit("the catalog text after the edit does not parse to the edited catalog; nothing written")
    CATALOG.write_text(text, encoding="utf-8")
    print(f"Updated {len(updated)} source pack(s) to v{args.pack_version} "
          f"(additive/optional): {', '.join(updated) or '(none)'}")
    if skipped:
        print("Skipped:", ", ".join(skipped))
    print(f"Confirm each <lang>.zip is uploaded to its <lang>-v{args.pack_version} "
          "release tag before committing the catalog.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
