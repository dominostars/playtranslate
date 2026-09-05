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
import re
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


# ── stub-lemma gate (Wiktionary source packs) ──────────────────────────────
# A redirect page that pass 2 cannot alias onto is kept as a lemma carrying its
# own "form of X" gloss, so the surface stays reachable. That is a FALLBACK. When
# the target is in fact present, the stub is a defect: the user taps the word and
# gets "plural of X" instead of X's definition, and the stub sits at position 0
# where it feeds searchPrefix.
#
# Two shapes, counted separately because they mean different things:
#   RESOLVABLE — the target is a kept lemma with a real gloss, so the alias was
#                simply missed (the junk gate rejecting a letterless surface,
#                "+1" -> "plus one").
#   CHAINED    — the target is itself a stub, so resolution stopped one link
#                short (house lights -> house light -> houselight).
# Both were found by opening a shipped pack; this gate is so they fail the build.
STUB_RESOLVABLE_MAX = 50
STUB_CHAINED_MAX = 100

_STUB_GLOSS_RE = re.compile(
    r"^\s*(?:\([^)]*\)\s*)*"
    r"(?!(?:an?|the)\s)"
    r"(?:[^\W\d_][\w'’-]*\s+){0,3}"
    r"(?:form|spelling|version|contraction|abbreviation|clipping|misspelling|"
    r"plural|participle|tense|singular|case)\s+of\s+"
    r"(?P<target>\S.*)$",
    re.IGNORECASE,
)
_STUB_TARGET_END_RE = re.compile(r"[(:,;.]")


def stub_target(gloss: str) -> str | None:
    """The lemma a single-sense form-of gloss points at, or None."""
    m = _STUB_GLOSS_RE.match(gloss or "")
    if not m:
        return None
    t = _STUB_TARGET_END_RE.split(m.group("target"), 1)[0].strip()
    return t.lower() or None


def stub_audit(conn: sqlite3.Connection) -> dict:
    """Classify every single-sense position-0 lemma whose only gloss is a form-of
    pointer. Returns counts plus samples; pure so a fixture DB can drive it."""
    glosses: dict[str, list[str]] = {}
    for text, g in conn.execute(
        "SELECT h.text, s.glosses FROM headword h JOIN sense s ON s.entry_id = h.entry_id "
        "WHERE h.position = 0"
    ):
        glosses.setdefault(text, []).append(g or "")
    # A lemma is a stub only when EVERY sense on it is a form-of pointer;
    # a word with a real sense elsewhere is a real entry.
    stubs: dict[str, str] = {}
    for text, gs in glosses.items():
        if len(gs) != 1:
            continue
        t = stub_target(gs[0])
        if t:
            stubs[text] = t
    resolvable, chained, dangling, selfref = [], [], [], []
    for text, target in stubs.items():
        if target == text:
            # The gloss names the page itself once case is folded away: en `nazi`
            # -> "Alternative form of Nazi", de `aids` -> "form of AIDS". There is
            # nothing else to point at, so this is never fixable by resolution and
            # must not count toward the chained budget.
            selfref.append((text, target))
        elif target not in glosses:
            dangling.append((text, target))
        elif target in stubs:
            chained.append((text, target))
        else:
            resolvable.append((text, target))
    return {
        "stubs": len(stubs),
        "resolvable": sorted(resolvable),
        "chained": sorted(chained),
        "dangling": sorted(dangling),
        "selfref": sorted(selfref),
    }


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
    ap.add_argument(
        "--stub-resolvable-max", type=int, default=STUB_RESOLVABLE_MAX,
        help="per-language override: how many stub lemmas whose target is a real "
             "kept lemma are tolerated before the pack fails",
    )
    ap.add_argument(
        "--stub-chained-max", type=int, default=STUB_CHAINED_MAX,
        help="per-language override: how many stub lemmas whose target is itself "
             "a stub are tolerated before the pack fails",
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

            # ja-v5: the ke_inf pass. Deliberately NOT a JmdictSchemaProbe probe
            # (the app guards the column so v4 installs stay ADDITIVE), which is
            # exactly why a v5+ build from a pre-ke_inf build_jmdict.py would
            # ship silently with v4 behaviour. This is the gate for that.
            if args.pack_version >= 5:
                hcols = {r[1] for r in conn.execute("PRAGMA table_info(headword)")}
                rcols = {r[1] for r in conn.execute("PRAGMA table_info(reading)")}
                if "ke_inf" not in hcols:
                    fail("headword.ke_inf missing — built with a pre-ke_inf build_jmdict.py")
                else:
                    n_sk = sum(
                        1 for (inf,) in conn.execute("SELECT ke_inf FROM headword WHERE ke_inf<>''")
                        if "sK" in inf.split(",")
                    )
                    if n_sk < 10_000:
                        fail(f"only {n_sk:,} search-only kanji forms (JMdict carries ~15k) — ke_inf not populated")
                    else:
                        ok(f"headword.ke_inf populated ({n_sk:,} sK forms)")
                if "uk_applicable" not in rcols:
                    fail("reading.uk_applicable missing — the per-reading uk scope needs it")
                else:
                    ok("reading.uk_applicable present")

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

        # ── stub-lemma gate (every Wiktionary source pack) ────────────────
        if args.lang not in ("ja", "zh"):
            a = stub_audit(conn)
            nr, nc = len(a["resolvable"]), len(a["chained"])
            print(f"  stub lemmas: {a['stubs']:,} total — {nr:,} resolvable, "
                  f"{nc:,} chained, {len(a['dangling']):,} dangling (target absent), "
                  f"{len(a['selfref']):,} self-referential")
            for label, rows, cap in (("resolvable", a["resolvable"], args.stub_resolvable_max),
                                     ("chained", a["chained"], args.stub_chained_max)):
                if rows:
                    print(f"    {label} samples: "
                          + ", ".join(f"{t}->{x}" for t, x in rows[:10]))
                if len(rows) > cap:
                    fail(f"{len(rows):,} {label} stub lemmas (max {cap}) — the "
                         f"redirect pass left reachable targets unaliased")
            if nr <= args.stub_resolvable_max and nc <= args.stub_chained_max:
                ok(f"stub gate: {nr} resolvable <= {args.stub_resolvable_max}, "
                   f"{nc} chained <= {args.stub_chained_max}")

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
