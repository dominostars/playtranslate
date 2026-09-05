"""Pins verify_pack.py's stub-lemma gate against a fixture pack.

A redirect page the alias pass cannot resolve is kept as a lemma carrying its own
"form of X" gloss so the surface stays reachable. That is a fallback, and when the
target IS present the stub is a defect: the user taps the word and gets "plural of
X" instead of X's definition, and the stub sits at position 0 feeding searchPrefix.
Both shipped shapes of this bug (a missed alias, and a chain stopping one link
short) were found by opening a published pack. This gate exists so they fail the
build instead.

Run: python3 scripts/test_verify_pack_stub_gate.py   (or via pytest)
"""

import hashlib
import json
import sqlite3
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

from build_latin_dict import create_schema
from verify_pack import stub_audit, stub_target

HERE = Path(__file__).resolve().parent


def make_pack(rows, tmp: Path) -> Path:
    """rows: (entry_id, position, text, gloss_or_None). A gloss of None means the
    row is a headword only (an alias), so no sense is written for it."""
    db = tmp / "dict.sqlite"
    conn = sqlite3.connect(db)
    create_schema(conn)
    cur = conn.cursor()
    seen_entries = set()
    for eid, pos, text, gloss in rows:
        if eid not in seen_entries:
            cur.execute("INSERT INTO entry VALUES (?, ?, ?)", (eid, 0, 50))
            seen_entries.add(eid)
        cur.execute("INSERT INTO headword VALUES (?, ?, ?)", (eid, pos, text))
        if gloss is not None:
            cur.execute("INSERT INTO sense VALUES (?, ?, ?, ?, ?)",
                        (eid, 0, "noun", gloss, ""))
    conn.commit()
    conn.close()
    size = db.stat().st_size
    man = {
        "langId": "en", "packVersion": 4, "schemaVersion": 1, "appMinVersion": 0,
        "files": [{"path": "dict.sqlite", "size": size,
                   "sha256": hashlib.sha256(db.read_bytes()).hexdigest()}],
        "totalSize": size,
    }
    (tmp / "manifest.json").write_text(json.dumps(man), encoding="utf-8")
    zp = tmp / "en.zip"
    with zipfile.ZipFile(zp, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(db, "dict.sqlite")
        z.write(tmp / "manifest.json", "manifest.json")
    return zp


def run_verify(zp: Path, *extra: str) -> tuple[int, str]:
    r = subprocess.run(
        [sys.executable, str(HERE / "verify_pack.py"), "--zip", str(zp),
         "--lang", "en", "--pack-version", "4", *extra],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    return r.returncode, r.stdout + r.stderr


# ── classification ───────────────────────────────────────────────────────

def test_stub_audit_classifies_the_three_shapes():
    tmp = Path(tempfile.mkdtemp())
    db = tmp / "d.sqlite"
    conn = sqlite3.connect(db)
    create_schema(conn)
    c = conn.cursor()
    rows = [
        (1, 0, "plus one", "a guest accompanying an invitee"),  # real lemma
        (2, 0, "+1", "alternative form of plus one"),           # RESOLVABLE stub
        (3, 0, "house light", "alternative form of houselight"),  # CHAINED stub
        (4, 0, "houselight", "alternative form of houselamp"),    # DANGLING stub
    ]
    for eid, pos, text, gloss in rows:
        c.execute("INSERT INTO entry VALUES (?, ?, ?)", (eid, 0, 50))
        c.execute("INSERT INTO headword VALUES (?, ?, ?)", (eid, pos, text))
        c.execute("INSERT INTO sense VALUES (?, ?, ?, ?, ?)", (eid, 0, "noun", gloss, ""))
    conn.commit()
    a = stub_audit(conn)
    conn.close()
    assert a["stubs"] == 3, a
    assert [t for t, _ in a["resolvable"]] == ["+1"], a["resolvable"]
    assert [t for t, _ in a["chained"]] == ["house light"], a["chained"]
    assert [t for t, _ in a["dangling"]] == ["houselight"], a["dangling"]


def test_self_referential_stub_is_its_own_bucket():
    # de `aids` -> "form of AIDS" folds onto itself; nothing to resolve to, so it
    # must not be charged to the chained budget.
    tmp = Path(tempfile.mkdtemp())
    conn = sqlite3.connect(tmp / "d.sqlite")
    create_schema(conn)
    c = conn.cursor()
    c.execute("INSERT INTO entry VALUES (1, 0, 50)")
    c.execute("INSERT INTO headword VALUES (1, 0, 'aids')")
    c.execute("INSERT INTO sense VALUES (1, 0, 'noun', 'alternative form of aids', '')")
    conn.commit()
    a = stub_audit(conn)
    conn.close()
    assert [t for t, _ in a["selfref"]] == ["aids"], a
    assert a["chained"] == [] and a["dangling"] == [] and a["resolvable"] == []


def test_a_word_with_a_real_sense_is_not_a_stub():
    # "would" is glossed "past tense of will" AND has real senses. Two senses on
    # the lemma, so it must not count.
    tmp = Path(tempfile.mkdtemp())
    conn = sqlite3.connect(tmp / "d.sqlite")
    create_schema(conn)
    c = conn.cursor()
    c.execute("INSERT INTO entry VALUES (1, 0, 50)")
    c.execute("INSERT INTO headword VALUES (1, 0, 'would')")
    c.execute("INSERT INTO sense VALUES (1, 0, 'verb', 'past tense of will', '')")
    c.execute("INSERT INTO sense VALUES (1, 1, 'verb', 'used to express a polite request', '')")
    conn.commit()
    a = stub_audit(conn)
    conn.close()
    assert a["stubs"] == 0, a


def test_target_parsing_ignores_ordinary_definitions():
    assert stub_target("plural of pot man") == "pot man"
    assert stub_target("A form of address used widely") is None
    assert stub_target("a plural of dogs") is None


# ── the gate as verify_pack runs it ──────────────────────────────────────

def test_correctly_aliased_stub_passes():
    # The redirect surface is a position-2 ALIAS on the real lemma, which is what
    # the resolver is supposed to produce. No stub lemma exists.
    tmp = Path(tempfile.mkdtemp())
    zp = make_pack([
        (1, 0, "plus one", "a guest accompanying an invitee"),
        (1, 2, "+1", None),
    ], tmp)
    rc, out = run_verify(zp)
    assert "stub lemmas: 0 total" in out, out
    assert rc == 0, out


def test_stub_pointing_at_a_real_lemma_fails_the_gate():
    tmp = Path(tempfile.mkdtemp())
    zp = make_pack([
        (1, 0, "plus one", "a guest accompanying an invitee"),
        (2, 0, "+1", "alternative form of plus one"),
    ], tmp)
    rc, out = run_verify(zp, "--stub-resolvable-max", "0")
    assert "1 resolvable stub lemmas (max 0)" in out, out
    assert "+1->plus one" in out, out
    assert rc == 1, out
    # ...and the same pack passes under the shipped threshold, so the gate is a
    # budget rather than a tripwire on a single row.
    rc2, out2 = run_verify(zp)
    assert rc2 == 0, out2


def test_chained_stub_fails_under_its_own_threshold():
    tmp = Path(tempfile.mkdtemp())
    zp = make_pack([
        (1, 0, "houselight", "alternative form of houselamp"),
        (2, 0, "house light", "alternative form of houselight"),
    ], tmp)
    rc, out = run_verify(zp, "--stub-chained-max", "0")
    assert "1 chained stub lemmas (max 0)" in out, out
    assert rc == 1, out


if __name__ == "__main__":
    for _name, _fn in sorted(globals().items()):
        if _name.startswith("test_") and callable(_fn):
            _fn()
            print(f"ok  {_name}")
    print("all verify_pack stub-gate regressions passed")
