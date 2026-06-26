#!/usr/bin/env python3
"""Finalize langpack_catalog.json for rebuilt source packs (optional upgrade).

For each source language with a built <lang>/<lang>.zip under --build-dir, bump
its catalog entry to the new packVersion (default 2), KEEP additiveFromVersion
unchanged so the previous version takes the additive / optional ("Update Later")
upgrade path, point the URL at the <lang>-v<version> release tag, and refresh
sha256 + size from the actual zip.

Run AFTER uploading each <lang>.zip to its <lang>-v<version> release on
github.com/dominostars/playtranslate-langpacks, then commit the catalog.

  python3 scripts/finalize_source_catalog.py --build-dir <dir> [--lang hi th] [--pack-version 2]
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CATALOG = ROOT / "app" / "src" / "main" / "assets" / "langpack_catalog.json"
RELEASES = "https://github.com/dominostars/playtranslate-langpacks/releases/download"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--build-dir", type=Path, required=True,
                    help="directory containing <lang>/<lang>.zip subdirs")
    ap.add_argument("--lang", nargs="*", default=None,
                    help="limit to these langs (default: every built zip found)")
    ap.add_argument("--pack-version", type=int, default=2)
    args = ap.parse_args()

    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
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
        entry.setdefault("additiveFromVersion", 1)
        data = zip_path.read_bytes()
        entry["packVersion"] = args.pack_version
        entry["size"] = len(data)
        entry["url"] = f"{RELEASES}/{lang}-v{args.pack_version}/{lang}.zip"
        entry["sha256"] = hashlib.sha256(data).hexdigest()
        updated.append(lang)

    CATALOG.write_text(
        json.dumps(catalog, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    print(f"Updated {len(updated)} source pack(s) to v{args.pack_version} "
          f"(additive/optional): {', '.join(updated) or '(none)'}")
    if skipped:
        print("Skipped:", ", ".join(skipped))
    print(f"Confirm each <lang>.zip is uploaded to its <lang>-v{args.pack_version} "
          "release tag before committing the catalog.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
