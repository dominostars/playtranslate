#!/usr/bin/env python3
"""Rebuild every Wiktionary source-language pack at packVersion 4.

For each language: use a local kaikki extract when present, otherwise stream the
per-language extract from kaikki.org, then run build_latin_dict.py to produce a
<code>.zip whose `sense.misc` column is populated by the curated filter. Records
sha256 + size to local/source-v4c/SUMMARY.json. Resumable (skips a built zip);
downloaded extracts are deleted after a successful build to reclaim disk.

RUN WITH THE ARABIC BUILD VENV so camel-tools / arramooz are importable for `ar`
(it also has wordfreq / snowballstemmer / pythainlp, so every language builds):

  ~/playtranslate/.venv-pt/bin/python scripts/build_source_packs.py

Per-language specifics this handles automatically:
  - no : "Norwegian Bokmål" — kaikki keeps the space in the dir but strips it in
         the filename, which is why a naive URL 404s.
  - ko : passes --komoran-jar (KOMORAN-3.3.9.jar from the gradle cache).
  - ar : downloads camel_morph_msa_v1.0.db (~85 MB) and sets $CAMEL_MORPH_DB.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path
from urllib.parse import quote

ROOT = Path(__file__).resolve().parent.parent
HOME = ROOT.parent
WORK = ROOT / "local" / "source-v4c"
WORK.mkdir(parents=True, exist_ok=True)
SUMMARY = WORK / "SUMMARY.json"
# The packVersion each language's manifest is stamped with. MUST equal that
# language's catalog packVersion — a lower value on disk makes the app read the
# installed pack as permanently stale (an upgrade prompt that re-fires on every
# launch and can never be satisfied).
#
# PACK_VERSION is the fleet baseline; PACK_VERSION_OVERRIDES carries the languages
# deliberately bumped AHEAD of the fleet. Do NOT raise the baseline to cover one
# language — that stamps the new version onto every other pack whose catalog entry
# is still at the baseline, inverting the exact staleness bug this guards against.
#   - hi -> 5: hi shipped its forms[] inflection-alias upgrade ahead of the
#              fleet (catalog hi.packVersion == 4), so the coverage rebuild that
#              takes the fleet 3 -> 4 has to take hi 4 -> 5 or its manifest would
#              read as a downgrade against the catalog it is being finalized into.
PACK_VERSION = 4
PACK_VERSION_OVERRIDES: dict[str, int] = {"hi": 5}


def pack_version_for(code: str) -> int:
    """The packVersion for [code]'s manifest — its override, else the fleet
    baseline. Keep in lockstep with langpack_catalog.json's per-entry packVersion."""
    return PACK_VERSION_OVERRIDES.get(code, PACK_VERSION)

# code -> kaikki language name (per-language source extract).
LANGS: dict[str, str] = {
    "ca": "Catalan", "da": "Danish", "nl": "Dutch", "fr": "French", "de": "German",
    "hu": "Hungarian", "id": "Indonesian", "it": "Italian", "no": "Norwegian Bokmål",
    "pl": "Polish", "pt": "Portuguese", "ro": "Romanian", "es": "Spanish", "sv": "Swedish",
    "tr": "Turkish", "vi": "Vietnamese", "hi": "Hindi", "th": "Thai", "ko": "Korean",
    "ru": "Russian", "ar": "Arabic", "fi": "Finnish", "en": "English",
}
LOCAL_INPUT: dict[str, Path] = {
    "hi": HOME / "hibuild" / "kaikki-hi.jsonl",
    "th": HOME / "thbuild" / "kaikki-th.jsonl",
}
CAMEL_DB_URL = (
    "https://raw.githubusercontent.com/CAMeL-Lab/camel_morph/main/official_releases/"
    "lrec-coling2024_release/databases/camel-morph-msa/camel_morph_msa_v1.0.db"
)
CAMEL_DB = WORK / "camel_morph_msa_v1.0.db"
# PoliMorf (pl morphology): three Morfologik jars from Maven Central + the
# form\tlemma TSV DumpPoliMorf.java writes from them (built once, then reused).
POLIMORF_TSV = WORK / "polimorf.tsv"
MORFOLOGIK_JARS = [
    "https://repo1.maven.org/maven2/org/carrot2/morfologik-polish/2.1.9/morfologik-polish-2.1.9.jar",
    "https://repo1.maven.org/maven2/org/carrot2/morfologik-stemming/2.1.9/morfologik-stemming-2.1.9.jar",
    "https://repo1.maven.org/maven2/org/carrot2/morfologik-fsa/2.1.9/morfologik-fsa-2.1.9.jar",
]
# KOMORAN (ko tokenizer) is a JitPack dep declared in app/build.gradle.kts; locate
# its jar in the gradle cache by glob so this is not tied to one machine's hash path.
KOMORAN_GLOB = "caches/modules-2/files-2.1/com.github.shin285/KOMORAN/*/*/KOMORAN-*.jar"
KOMORAN_JITPACK = "https://jitpack.io/com/github/shin285/KOMORAN/3.3.9/KOMORAN-3.3.9.jar"
# Build the multi-GB extracts last so smaller packs land (and validate) first.
ORDER = [c for c in LANGS if c not in ("ru", "de", "es", "fi", "en")] + ["ru", "de", "es", "fi", "en"]


def kaikki_url(name: str) -> str:
    # The kaikki dir keeps the space; the filename strips it (Norwegian Bokmål).
    return (f"https://kaikki.org/dictionary/{quote(name)}/"
            f"kaikki.org-dictionary-{quote(name.replace(' ', ''))}.jsonl")


def download(url: str, dest: Path) -> None:
    if dest.is_file() and dest.stat().st_size > 0:
        return
    tmp = dest.with_suffix(".part")
    with urllib.request.urlopen(url, timeout=120) as r, open(tmp, "wb") as f:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
    tmp.rename(dest)


def validate_existing(zip_path: Path, code: str) -> str | None:
    """Return None if [zip_path] is a usable pack for [code] at its expected
    packVersion (per pack_version_for), else a human-readable reason. Guards
    against a stale / partial zip being skipped by path and later blessed by
    finalize_source_catalog.py."""
    want = pack_version_for(code)
    try:
        with zipfile.ZipFile(zip_path) as zf:
            man = json.loads(zf.read("manifest.json"))
    except Exception as e:
        return f"unreadable manifest ({e!r})"
    if man.get("langId") != code:
        return f"langId={man.get('langId')!r}, expected {code!r}"
    if man.get("packVersion") != want:
        return f"packVersion={man.get('packVersion')!r}, expected {want}"
    return None


def find_komoran_jar() -> str:
    """Locate the KOMORAN jar in the gradle cache (machine-agnostic). A one-time
    gradle sync populates it from JitPack; on a clean machine run a gradle build
    first, or download KOMORAN_JITPACK by hand."""
    hits = sorted((Path.home() / ".gradle").glob(KOMORAN_GLOB))
    if hits:
        return str(hits[-1])
    # A normal exception (not SystemExit) so the per-language handler records ko
    # as failed and the batch continues instead of aborting.
    raise FileNotFoundError(
        "KOMORAN jar not found under ~/.gradle — run a gradle sync once (it is "
        f"declared in app/build.gradle.kts), or download {KOMORAN_JITPACK}"
    )


def _java_bin() -> str:
    """Prefer $JAVA_HOME/bin/java (the SDK JDK this repo pins) over a bare `java`
    that may not be on PATH."""
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        cand = Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if cand.is_file():
            return str(cand)
    return "java"


def build_polimorf_tsv() -> None:
    """Download the three Morfologik jars and run DumpPoliMorf ONCE to produce
    POLIMORF_TSV (the form\\tlemma table polish_morphology.py bridges into
    position-2 alias rows). Idempotent: skips whichever artifact already exists,
    so a re-run after a failed pl build reuses the ~4.8M-row dump."""
    jars: list[Path] = []
    for url in MORFOLOGIK_JARS:
        dest = WORK / url.rsplit("/", 1)[-1]
        if not (dest.is_file() and dest.stat().st_size > 0):
            print(f"[dl]    {dest.name}...", flush=True)
            download(url, dest)
        jars.append(dest)
    if POLIMORF_TSV.is_file() and POLIMORF_TSV.stat().st_size > 0:
        return
    print("[build] DumpPoliMorf -> polimorf.tsv (~4.8M rows)...", flush=True)
    cp = os.pathsep.join(str(j) for j in jars)
    src = ROOT / "scripts" / "polish-morphology" / "DumpPoliMorf.java"
    tmp = POLIMORF_TSV.with_suffix(".part")
    with open(tmp, "wb") as out:
        subprocess.run([_java_bin(), "-cp", cp, str(src)], check=True, stdout=out)
    tmp.rename(POLIMORF_TSV)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", nargs="*", default=None)
    ap.add_argument("--allow-failures", action="store_true",
                    help="exit 0 even if some languages failed (default: exit non-zero)")
    args = ap.parse_args()
    todo = [c for c in ORDER if not args.only or c in args.only]
    summary = json.loads(SUMMARY.read_text()) if SUMMARY.is_file() else {"built": {}, "failed": {}}

    for code in todo:
        name = LANGS[code]
        outdir = WORK / code
        zip_path = outdir / f"{code}.zip"
        if zip_path.is_file():
            reason = validate_existing(zip_path, code)
            if reason is None:
                data = zip_path.read_bytes()
                summary["built"][code] = {"sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}
                summary["failed"].pop(code, None)
                print(f"[skip]  {code}: already built (v{pack_version_for(code)} verified)", flush=True)
            else:
                summary["built"].pop(code, None)
                summary["failed"][code] = f"stale existing zip: {reason}"
                print(f"[FAIL]  {code}: stale zip ({reason}); delete {zip_path} and re-run", flush=True)
            SUMMARY.write_text(json.dumps(summary, indent=2))
            continue
        downloaded: Path | None = None
        try:
            # Use a local extract only when it actually exists; otherwise fall
            # back to the kaikki download (clean-machine path, as documented).
            src = LOCAL_INPUT.get(code)
            if src is not None and src.is_file():
                print(f"[local] {code}: {src}", flush=True)
            else:
                src = WORK / f"kaikki-{code}.jsonl"
                print(f"[dl]    {code} ({name})...", flush=True)
                download(kaikki_url(name), src)
                downloaded = src
            env = os.environ.copy()
            if code == "ar":
                if not CAMEL_DB.is_file():
                    print("[dl]    camel_morph_msa_v1.0.db (~85 MB)...", flush=True)
                    download(CAMEL_DB_URL, CAMEL_DB)
                env["CAMEL_MORPH_DB"] = str(CAMEL_DB)
            extra: list[str] = []
            if code == "ko":
                extra = ["--komoran-jar", find_komoran_jar()]
            elif code == "pl":
                build_polimorf_tsv()
                extra = ["--polimorf-tsv", str(POLIMORF_TSV)]
            print(f"[build] {code} ({src.stat().st_size // 1_000_000} MB)...", flush=True)
            subprocess.run(
                [sys.executable, str(ROOT / "scripts" / "build_latin_dict.py"),
                 "--lang", code, "--input", str(src), "--output", str(outdir),
                 "--pack-version", str(pack_version_for(code)), *extra],
                check=True, env=env,
            )
            data = zip_path.read_bytes()
            summary["built"][code] = {"sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}
            summary["failed"].pop(code, None)
            print(f"[ok]    {code}: {len(data):,} bytes", flush=True)
            # Reclaim disk: drop the downloaded extract and the loose dict.sqlite
            # (it is already inside <lang>.zip).
            (outdir / "dict.sqlite").unlink(missing_ok=True)
            if downloaded is not None:
                downloaded.unlink(missing_ok=True)
        except Exception as e:
            summary["failed"][code] = repr(e)
            print(f"[FAIL]  {code}: {e!r}", flush=True)
        SUMMARY.write_text(json.dumps(summary, indent=2))

    failed = sorted(summary["failed"])
    print(f"\nDONE. built={sorted(summary['built'])}\nfailed={failed}", flush=True)
    if failed and not args.allow_failures:
        print(f"ERROR: {len(failed)} language(s) failed: {', '.join(failed)}. "
              f"Do NOT finalize the catalog until resolved (or pass --allow-failures).", flush=True)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
