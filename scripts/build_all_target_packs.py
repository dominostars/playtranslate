#!/usr/bin/env python3
"""
End-to-end builder for ALL missing target-language packs.

Driven from the ML Kit TranslateLanguage list. For each target language not
already in the catalog, this:
  1. HEADs https://kaikki.org/{code}wiktionary/raw-wiktextract-data.jsonl.gz
  2. Downloads the dump (gz, streamed)
  3. Gunzips
  4. Runs build_target_pack.py (with JMdict for supported codes, CFDICT for fr)
  5. Reads totalRows from manifest.json
  6. If totalRows >= THRESHOLD: keeps the .zip; otherwise records a skip

Final report is written to local/target-build/SUMMARY.json with:
  - built: [{code, name, totalRows, zipPath, sha256, size}]
  - skipped_low_count: [{code, name, totalRows}]
  - skipped_no_dump: [{code, name, http_status}]

Run with: python scripts/build_all_target_packs.py [--threshold 5000]
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import shutil
import subprocess
import sys
import urllib.request
import urllib.error
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WORK = ROOT / "local" / "target-build"
WORK.mkdir(parents=True, exist_ok=True)
SUMMARY_PATH = WORK / "SUMMARY.json"

# ML Kit TranslateLanguage 17.0.3 — all 59 codes, with English readable names
ML_KIT_TARGETS: list[tuple[str, str]] = [
    ("af", "Afrikaans"), ("sq", "Albanian"), ("ar", "Arabic"),
    ("be", "Belarusian"), ("bg", "Bulgarian"), ("bn", "Bengali"),
    ("ca", "Catalan"), ("zh", "Chinese"), ("hr", "Croatian"),
    ("cs", "Czech"), ("da", "Danish"), ("nl", "Dutch"),
    ("en", "English"), ("eo", "Esperanto"), ("et", "Estonian"),
    ("fi", "Finnish"), ("fr", "French"), ("gl", "Galician"),
    ("ka", "Georgian"), ("de", "German"), ("el", "Greek"),
    ("gu", "Gujarati"), ("ht", "Haitian Creole"), ("he", "Hebrew"),
    ("hi", "Hindi"), ("hu", "Hungarian"), ("is", "Icelandic"),
    ("id", "Indonesian"), ("ga", "Irish"), ("it", "Italian"),
    ("ja", "Japanese"), ("kn", "Kannada"), ("ko", "Korean"),
    ("lt", "Lithuanian"), ("lv", "Latvian"), ("mk", "Macedonian"),
    ("mr", "Marathi"), ("ms", "Malay"), ("mt", "Maltese"),
    ("no", "Norwegian"), ("fa", "Persian"), ("pl", "Polish"),
    ("pt", "Portuguese"), ("ro", "Romanian"), ("ru", "Russian"),
    ("sk", "Slovak"), ("sl", "Slovenian"), ("es", "Spanish"),
    ("sv", "Swedish"), ("sw", "Swahili"), ("tl", "Tagalog"),
    ("ta", "Tamil"), ("te", "Telugu"), ("th", "Thai"),
    ("tr", "Turkish"), ("uk", "Ukrainian"), ("ur", "Urdu"),
    ("vi", "Vietnamese"), ("cy", "Welsh"),
]

# Already-built or no-pack-needed
SKIP_AS_DONE = {"fr", "es", "ja", "zh", "en"}

# JMdict 3-letter codes the upstream XML carries
# (see scripts/build_target_pack.py:JMDICT_LANG_MAP)
JMDICT_TARGETS = {"fr", "de", "es", "ru", "pt", "nl", "hu", "sv", "sl", "it"}

CATALOG_PATH = ROOT / "app" / "src" / "main" / "assets" / "langpack_catalog.json"


def kaikki_url(code: str) -> str:
    return f"https://kaikki.org/{code}wiktionary/raw-wiktextract-data.jsonl.gz"


def head(url: str) -> tuple[int, int]:
    """Return (http_status, content_length). 0 on failure."""
    req = urllib.request.Request(url, method="HEAD")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            length = int(resp.headers.get("Content-Length", "0"))
            return resp.status, length
    except urllib.error.HTTPError as e:
        return e.code, 0
    except Exception:
        return 0, 0


def stream_download(url: str, dest: Path) -> int:
    """Stream a URL to dest. Returns total bytes."""
    if dest.exists() and dest.stat().st_size > 0:
        return dest.stat().st_size
    tmp = dest.with_suffix(dest.suffix + ".part")
    if tmp.exists():
        tmp.unlink()
    total = 0
    with urllib.request.urlopen(url, timeout=120) as resp, open(tmp, "wb") as f:
        while True:
            chunk = resp.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
            total += len(chunk)
    tmp.rename(dest)
    return total


def gunzip(src_gz: Path, dest: Path) -> int:
    if dest.exists() and dest.stat().st_size > 0:
        return dest.stat().st_size
    with gzip.open(src_gz, "rb") as fin, open(dest, "wb") as fout:
        shutil.copyfileobj(fin, fout, length=1 << 22)
    return dest.stat().st_size


def run_build_pack(code: str, jsonl_dir: Path, out_dir: Path,
                   jmdict_path: Path | None, cfdict_path: Path | None) -> dict:
    """Invoke scripts/build_target_pack.py. Returns parsed manifest."""
    out_dir.mkdir(parents=True, exist_ok=True)
    cmd = [
        sys.executable, str(ROOT / "scripts" / "build_target_pack.py"),
        "--target", code,
        "--wiktionary", str(jsonl_dir),
        "--output", str(out_dir),
        "--pack-version", "1",
    ]
    if jmdict_path and jmdict_path.exists() and code in JMDICT_TARGETS:
        cmd += ["--jmdict", str(jmdict_path)]
    if cfdict_path and cfdict_path.exists() and code == "fr":
        cmd += ["--cfdict", str(cfdict_path)]
    print(f"  RUN: {' '.join(cmd)}", flush=True)
    subprocess.run(cmd, check=True)
    manifest = json.loads((out_dir / "manifest.json").read_text("utf-8"))
    return manifest


def sha256(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def existing_catalog_targets() -> set[str]:
    cat = json.loads(CATALOG_PATH.read_text("utf-8"))
    return {k.removeprefix("target-") for k in cat["packs"] if k.startswith("target-")}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--threshold", type=int, default=5000,
                    help="Skip packs with totalRows below this (default 5000)")
    ap.add_argument("--phase", choices=["probe", "download", "build", "all"],
                    default="all")
    ap.add_argument("--jmdict",
                    help="Path to JMdict_e.gz or JMdict_e_examp.xml")
    ap.add_argument("--cfdict", help="Path to cfdict.txt (for target-fr)")
    args = ap.parse_args()

    already = existing_catalog_targets() | SKIP_AS_DONE
    todo = [(c, n) for (c, n) in ML_KIT_TARGETS if c not in already]
    print(f"Will attempt {len(todo)} target packs; skipping {len(SKIP_AS_DONE | existing_catalog_targets())} already done.")

    summary = {
        "threshold": args.threshold,
        "probed": [],
        "built": [],
        "skipped_low_count": [],
        "skipped_no_dump": [],
        "errors": [],
    }
    if SUMMARY_PATH.exists():
        prev = json.loads(SUMMARY_PATH.read_text("utf-8"))
        summary["probed"] = prev.get("probed", [])

    # ── Phase 1: probe ────────────────────────────────────────────────
    if args.phase in ("probe", "all"):
        print("\n=== PROBE ===")
        for code, name in todo:
            url = kaikki_url(code)
            status, length = head(url)
            entry = {"code": code, "name": name, "url": url,
                     "status": status, "size": length}
            summary["probed"].append(entry)
            print(f"  {code:<3} {name:<20} status={status:<3} size={length:>12,}")
        SUMMARY_PATH.write_text(json.dumps(summary, indent=2, ensure_ascii=False))

    available = [p for p in summary["probed"] if p.get("status") == 200]
    no_dump = [p for p in summary["probed"] if p.get("status") != 200]
    summary["skipped_no_dump"] = [
        {"code": p["code"], "name": p["name"], "http_status": p["status"]}
        for p in no_dump
    ]
    total_dl = sum(p["size"] for p in available)
    print(f"\nAvailable kaikki dumps: {len(available)} "
          f"(total {total_dl/1e9:.1f} GB compressed); "
          f"missing/4xx: {len(no_dump)}")

    if args.phase == "probe":
        SUMMARY_PATH.write_text(json.dumps(summary, indent=2, ensure_ascii=False))
        return

    # ── Phase 2: download + build ──────────────────────────────────────
    if args.phase in ("download", "build", "all"):
        for entry in available:
            code, name = entry["code"], entry["name"]
            print(f"\n=== {code} ({name}) ===", flush=True)
            ldir = WORK / code
            ldir.mkdir(parents=True, exist_ok=True)
            jsonl_dir = ldir / "wikt"
            jsonl_dir.mkdir(exist_ok=True)
            gz = ldir / "raw.jsonl.gz"
            jl = jsonl_dir / "raw.jsonl"
            try:
                if not gz.exists() or gz.stat().st_size == 0:
                    print(f"  Downloading {entry['size']:,} bytes...", flush=True)
                    stream_download(entry["url"], gz)
                if not jl.exists() or jl.stat().st_size == 0:
                    print(f"  Gunzipping...", flush=True)
                    gunzip(gz, jl)

                if args.phase == "download":
                    continue

                manifest = run_build_pack(
                    code, jsonl_dir, ldir / "out",
                    Path(args.jmdict) if args.jmdict else None,
                    Path(args.cfdict) if args.cfdict else None,
                )
                rows = manifest.get("totalRows", 0)
                print(f"  totalRows={rows:,}", flush=True)
                if rows < args.threshold:
                    summary["skipped_low_count"].append(
                        {"code": code, "name": name, "totalRows": rows}
                    )
                    print(f"  SKIP (< {args.threshold})")
                    continue
                zip_path = (ldir / "out" / f"{code}.zip")
                summary["built"].append({
                    "code": code, "name": name,
                    "totalRows": rows,
                    "zipPath": str(zip_path),
                    "size": zip_path.stat().st_size,
                    "sha256": sha256(zip_path),
                    "covers": manifest.get("covers", []),
                    "sourceCounts": manifest.get("sourceCounts", {}),
                })
            except subprocess.CalledProcessError as e:
                summary["errors"].append({"code": code, "name": name,
                                          "stage": "build",
                                          "error": str(e)})
                print(f"  BUILD ERROR: {e}", flush=True)
            except Exception as e:
                summary["errors"].append({"code": code, "name": name,
                                          "stage": "download",
                                          "error": str(e)})
                print(f"  ERROR: {e}", flush=True)
            finally:
                SUMMARY_PATH.write_text(
                    json.dumps(summary, indent=2, ensure_ascii=False)
                )

    print("\nDone. Summary:", SUMMARY_PATH)


if __name__ == "__main__":
    main()
