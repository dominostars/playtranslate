#!/usr/bin/env python3
"""Gate a built dictionary pack before it is uploaded.

Every check here corresponds to something that fails SILENTLY or IRREVERSIBLY
downstream:

  - missing entry_id indexes .... the whole point of the rebuild; nothing in the
                                  app or the build scripts would notice
  - manifest size mismatch ...... LanguagePackStore.validateManifest rejects a
                                  pack on a byte-exact size mismatch
  - per-file sha mismatch ....... same, when the manifest declares one
  - appMinVersion > VERSION_CODE  pack installs nowhere
  - ja without a tokenizer ...... SHA-verifies, installs, passes JmdictSchemaProbe,
                                  then dies at the first Japanese lookup
  - ja with zero examples ....... a mis-staged Tatoeba dir degrades to no examples
                                  while still stamping the Tatoeba license
  - ja with uncurated misc ...... the filter silently didn't run

  python3 scripts/verify_pack.py --zip local/packs-v3/ja/ja.zip --lang ja --pack-version 4
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
import sys
import tempfile
import zipfile
from pathlib import Path

ENTRY_INDEXES = {"idx_headword_entry", "idx_reading_entry", "idx_sense_entry"}
APP_VERSION_CODE = 14  # app/build.gradle.kts

# Row-count regression gate (--compare-against): a rebuild pulls a fresh upstream
# extract, so some churn is normal — but a large DROP means the new pack is worse
# than what users already have, which no other check here would catch. Allow 5%
# churn per table before failing.
REGRESSION_TOLERANCE = 0.05

FAILURES: list[str] = []

# JmdictSchemaProbe.kt:31-52 — all five must answer or the pack is force-wiped.
JA_PROBES = [
    "SELECT freq_score FROM entry LIMIT 1",
    "SELECT text FROM headword LIMIT 1",
    "SELECT misc FROM sense LIMIT 1",
    "SELECT literal FROM kanjidic LIMIT 1",
    "SELECT literal, lang, meanings FROM kanji_meaning LIMIT 1",
]


def fail(msg: str) -> None:
    print(f"  FAIL  {msg}")
    FAILURES.append(msg)


def ok(msg: str) -> None:
    print(f"  ok    {msg}")


def compare_against_published(old_zip: Path, new_conn: sqlite3.Connection, work: Path) -> None:
    """Fail on a material row-count DROP vs the currently-published pack.

    `additiveFromVersion` describes the install MECHANISM, not the data: a
    rebuild fully replaces the old pack, and nothing else in this toolchain
    verifies the new one didn't silently lose entries/senses/examples to
    upstream kaikki churn or a wordfreq shift. This is that gate. The `headword`
    table legitimately GROWS once the forms[] pass lands, so the gate only ever
    fires on drops. Only a fleet rebuild has a prior pack to compare against —
    a brand-new language (e.g. Polish) has none; download the old pack from the
    catalog `url` and pass it here for every language in a fleet campaign."""
    if not old_zip.is_file():
        fail(f"--compare-against {old_zip}: no such file")
        return
    old_dir = work / "_old"
    old_dir.mkdir(exist_ok=True)
    try:
        with zipfile.ZipFile(old_zip) as z:
            z.extract("dict.sqlite", old_dir)
    except Exception as e:
        fail(f"--compare-against {old_zip}: cannot read dict.sqlite ({e!r})")
        return
    old = sqlite3.connect(f"file:{old_dir / 'dict.sqlite'}?mode=ro", uri=True)
    try:
        for table in ("entry", "headword", "sense", "example"):
            old_n = old.execute(f"SELECT count(*) FROM {table}").fetchone()[0]
            new_n = new_conn.execute(f"SELECT count(*) FROM {table}").fetchone()[0]
            delta = (new_n - old_n) / old_n if old_n else 0.0
            if old_n and new_n < old_n * (1 - REGRESSION_TOLERANCE):
                fail(f"{table}: {old_n:,} -> {new_n:,} ({delta:+.1%}) — rebuild LOSES data")
            else:
                ok(f"{table}: {old_n:,} -> {new_n:,} ({delta:+.1%})")
    finally:
        old.close()


def misc_vocabulary(root: Path) -> tuple[set[str], set[str]]:
    v = json.loads((root / "app/src/main/resources/misc_vocabulary.json").read_text())
    alias = set()
    for e in v.get("register", []):
        for a in e.get("aliases", []) + [e["label"]]:
            alias.add(a.strip().lower())
    passthrough = {x.strip().lower() for x in v.get("domainAllowlist", []) + v.get("regionGazetteer", [])}
    return alias, passthrough


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--zip", type=Path, required=True)
    ap.add_argument("--lang", required=True)
    ap.add_argument("--pack-version", type=int, required=True)
    ap.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parent.parent)
    ap.add_argument(
        "--compare-against", type=Path, default=None,
        help="Previously-published pack .zip for this language (downloaded from "
             "the catalog `url`). Fails on a material row-count drop vs it. Run "
             "for every language in a fleet rebuild before uploading.",
    )
    args = ap.parse_args()

    print(f"verify {args.lang} — {args.zip}")
    if not args.zip.is_file():
        print(f"  FAIL  no such zip")
        return 1

    with tempfile.TemporaryDirectory() as td:
        work = Path(td)
        with zipfile.ZipFile(args.zip) as z:
            names = set(z.namelist())
            z.extractall(work)

        # ── manifest ──────────────────────────────────────────────────────
        man_file = work / "manifest.json"
        if not man_file.is_file():
            fail("no manifest.json")
            return 1
        m = json.loads(man_file.read_text())
        if m.get("langId") != args.lang:
            fail(f"langId={m.get('langId')!r} != {args.lang!r}")
        if m.get("packVersion") != args.pack_version:
            fail(f"packVersion={m.get('packVersion')} != {args.pack_version}")
        else:
            ok(f"manifest langId={args.lang} packVersion={args.pack_version}")
        if m.get("schemaVersion") != 1:
            fail(f"schemaVersion={m.get('schemaVersion')} (app supports 1)")
        amv = m.get("appMinVersion")
        if amv is None or amv > APP_VERSION_CODE:
            fail(f"appMinVersion={amv} > versionCode {APP_VERSION_CODE} — uninstallable")
        else:
            ok(f"appMinVersion={amv}")

        # LanguagePackStore.validateManifest: every listed file present, byte-exact
        # size, and sha verified when declared.
        total = 0
        for f in m.get("files", []):
            p = work / f["path"]
            if f["path"] not in names or not p.is_file():
                fail(f"manifest lists {f['path']} but the zip has no such file")
                continue
            actual = p.stat().st_size
            if actual != f["size"]:
                fail(f"{f['path']}: manifest size {f['size']} != actual {actual}")
            if f.get("sha256"):
                h = hashlib.sha256(p.read_bytes()).hexdigest()
                if h.lower() != f["sha256"].lower():
                    fail(f"{f['path']}: manifest sha256 mismatch")
            total += int(f["size"])
        if total != m.get("totalSize"):
            fail(f"totalSize={m.get('totalSize')} != sum(files)={total}")
        else:
            ok(f"{len(m['files'])} file(s), sizes+shas match, totalSize consistent")

        # ── the indexes: the reason this rebuild exists ───────────────────
        db = work / "dict.sqlite"
        if not db.is_file():
            fail("no dict.sqlite")
            return 1 if FAILURES else 0
        conn = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
        idx = {r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='index'")}
        missing = ENTRY_INDEXES - idx
        if missing:
            fail(f"MISSING entry_id indexes: {sorted(missing)}")
        else:
            ok("entry_id indexes 3/3")
        integ = conn.execute("PRAGMA integrity_check").fetchone()[0]
        if integ != "ok":
            fail(f"integrity_check={integ!r}")
        else:
            ok("integrity_check ok")

        # ── per-language traps ────────────────────────────────────────────
        if args.lang == "ja":
            probes_ok = 0
            for q in JA_PROBES:
                try:
                    conn.execute(q).fetchone()
                    probes_ok += 1
                except sqlite3.Error as e:
                    fail(f"JmdictSchemaProbe query failed ({q!r}): {e}")
            if probes_ok == len(JA_PROBES):
                ok(f"JmdictSchemaProbe: {probes_ok}/{len(JA_PROBES)} probes answer")

            tok = [n for n in names if n.startswith("tokenizer/") and n.endswith(".dic")]
            if not tok:
                fail("no tokenizer/system_*.dic — pack would die at the first JA lookup")
            else:
                sz = (work / tok[0]).stat().st_size
                if sz < 100_000_000:
                    fail(f"{tok[0]} is only {sz:,} B — expected the ~207 MB core dict")
                else:
                    ok(f"{tok[0]} ({sz:,} B)")

            n_ex = conn.execute("SELECT count(*) FROM example").fetchone()[0]
            if n_ex == 0:
                fail("0 example rows — Tatoeba was mis-staged and silently skipped")
            else:
                ok(f"{n_ex:,} example rows (Tatoeba joined)")

            # The curated-misc filter is the other half of ja's rebuild. Every
            # stored token must resolve in the shared vocabulary; a raw freeform
            # s_inf sentence surviving means filter_misc never ran.
            alias, passthrough = misc_vocabulary(args.repo_root)
            bad = 0
            for (misc,) in conn.execute("SELECT misc FROM sense WHERE misc<>''"):
                for tok_ in misc.split("\t"):
                    t = tok_.strip().lower()
                    if t and t not in alias and t not in passthrough:
                        bad += 1
                        break
            if bad:
                fail(f"{bad:,} senses carry misc tokens outside misc_vocabulary.json "
                     f"— the curated filter did not run")
            else:
                ok("sense.misc fully curated (every token resolves in misc_vocabulary.json)")

        if args.lang == "zh":
            n = len([x for x in names if x.startswith("tokenizer/")])
            if n != 24:
                fail(f"tokenizer/ has {n} files, expected 24 (HanLP data)")
            else:
                ok("HanLP tokenizer 24/24 files")

        if args.lang == "ko":
            n = len([x for x in names if x.startswith("tokenizer/")])
            if n != 4:
                fail(f"tokenizer/ has {n} files, expected 4 (KOMORAN models)")
            else:
                ok("KOMORAN tokenizer 4/4 files")

        if args.lang == "th":
            if "words.txt" not in names:
                fail("no words.txt — Thai segmenter wordlist missing")
            else:
                ok("words.txt present")

        # ── regression gate vs the previously-published pack (§3.6) ────────
        # Optional: only a fleet rebuild has a prior pack to compare against.
        if args.compare_against is not None:
            compare_against_published(args.compare_against, conn, work)

        conn.close()

    if FAILURES:
        print(f"  ==> {args.lang} FAILED ({len(FAILURES)} problem(s))")
        return 1
    print(f"  ==> {args.lang} PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
