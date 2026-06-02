#!/usr/bin/env python3
"""C1 filter: drop rows whose source_lang is not a 2-letter ISO 639-1 code.

After iso_codes normalization at build time, any row with source_lang of length
!= 2 represents a language without an ISO 639-1 code — the runtime cannot query
it (the runtime always uses 2-letter codes from source-pack codes). These rows
are dead weight regardless of future plans.

This script runs against the v2 SQLite intermediates AFTER they're built and
BEFORE FST conversion. Idempotent — safe to re-run.

Filter rule:
    DELETE FROM glosses WHERE LENGTH(source_lang) != 2

Reports row counts before/after and disk-size savings per pack.
"""
from __future__ import annotations
import sqlite3, sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")
ROOT = Path(__file__).resolve().parent.parent

# Default scan paths. The script picks up every glosses.sqlite found under each
# of these and applies the filter idempotently. Add new build directories here
# rather than passing CLI args.
INTERMEDIATE_PATHS = [
    ROOT / "local/target-build-v2",
    ROOT / "local/target-build-panlex-v2",
    ROOT / "local/target-build-hybrid-v2",
]

def filter_one(sqlite_path: Path) -> dict:
    pre_size = sqlite_path.stat().st_size
    c = sqlite3.connect(str(sqlite_path))
    pre_rows = c.execute("SELECT COUNT(*) FROM glosses").fetchone()[0]
    # Sample what we're about to drop (top dropped codes)
    dropped_codes_top = list(c.execute(
        "SELECT source_lang, COUNT(*) FROM glosses WHERE LENGTH(source_lang) != 2 "
        "GROUP BY source_lang ORDER BY 2 DESC LIMIT 5"
    ))
    # Apply the filter
    c.execute("DELETE FROM glosses WHERE LENGTH(source_lang) != 2")
    deleted = c.total_changes
    c.commit()
    # Reclaim space
    c.execute("VACUUM")
    c.close()
    post_size = sqlite_path.stat().st_size
    c = sqlite3.connect(str(sqlite_path))
    post_rows = c.execute("SELECT COUNT(*) FROM glosses").fetchone()[0]
    c.close()
    return {
        "pre_rows": pre_rows,
        "post_rows": post_rows,
        "deleted": deleted,
        "pre_size_bytes": pre_size,
        "post_size_bytes": post_size,
        "size_saved_bytes": pre_size - post_size,
        "top_dropped_codes": dropped_codes_top,
    }

def main():
    all_sqlites: list[tuple[str, str, Path]] = []
    for base in INTERMEDIATE_PATHS:
        if not base.exists(): continue
        kind = base.name.removeprefix("target-build-").removeprefix("v2") or "kaikki"
        for d in sorted(base.iterdir()):
            if not d.is_dir(): continue
            sp = d / "glosses.sqlite"
            if sp.exists():
                all_sqlites.append((d.name, kind, sp))

    print(f"Found {len(all_sqlites)} v2 SQLite intermediates to filter")
    print()
    print(f"{'pack':<8} {'kind':<10} {'pre rows':>11} {'kept':>11} {'dropped':>11} {'pre MB':>8} {'post MB':>8} {'saved MB':>8}")
    print('-' * 95)

    total_pre = total_post = total_size_pre = total_size_post = 0
    for code, kind, p in all_sqlites:
        r = filter_one(p)
        total_pre += r["pre_rows"]
        total_post += r["post_rows"]
        total_size_pre += r["pre_size_bytes"]
        total_size_post += r["post_size_bytes"]
        print(f"{code:<8} {kind:<10} {r['pre_rows']:>11,} {r['post_rows']:>11,} {r['deleted']:>11,} "
              f"{r['pre_size_bytes']/1024/1024:>7.1f}M {r['post_size_bytes']/1024/1024:>7.1f}M "
              f"{r['size_saved_bytes']/1024/1024:>7.1f}M")

    print('-' * 95)
    print(f"{'TOTAL':<8} {'':<10} {total_pre:>11,} {total_post:>11,} {total_pre-total_post:>11,} "
          f"{total_size_pre/1024/1024:>7.0f}M {total_size_post/1024/1024:>7.0f}M "
          f"{(total_size_pre-total_size_post)/1024/1024:>7.0f}M")
    print()
    pct_rows = (1 - total_post/total_pre) * 100 if total_pre else 0
    pct_size = (1 - total_size_post/total_size_pre) * 100 if total_size_pre else 0
    print(f"  Rows dropped: {total_pre-total_post:,} ({pct_rows:.1f}%)")
    print(f"  SQLite size saved: {(total_size_pre-total_size_post)/1024/1024:.0f} MB ({pct_size:.1f}%)")
    print("  Note: zip-size savings are smaller (~30-50%) due to FST + zip compression already amortizing strings.bin")

if __name__ == "__main__":
    main()
