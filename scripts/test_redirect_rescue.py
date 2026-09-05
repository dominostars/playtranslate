"""End-to-end regression tests for the redirect rescue and the inflection-class
form filter, driven through build_sqlite over a small synthetic kaikki file.

Motivating bugs:

  - A redirect page whose chain ends at a target the frequency cut dropped used
    to vanish entirely. Pass 1 drops every redirect page; pass 2 aliases only
    onto lemmas the pack kept. Hindi made it visible: nuqta is routinely omitted
    in running text, so the nuqta-LESS spelling is the common one and redirects
    to a rarer nuqta-bearing lemma that MIN_FREQUENCY had already dropped — 34
    everyday words disappeared, and 279 across the fleet.

  - wiktextract's inflection-CLASS rows ("38/nainen" = Kotus type 38, model word
    nainen) are not word forms, but they reached forms[] for Finnish and wordfreq
    tokenizes them, handing every Finnish lemma its declension model's frequency.

Run: python3 scripts/test_redirect_rescue.py   (or via pytest)
"""

import json
import sqlite3
import tempfile
from pathlib import Path

from build_latin_dict import (
    _scripts_of,
    build_sqlite,
    eligible_form_surfaces,
)


def entry(word, pos="noun", glosses=("a thing",), form_of=None, forms=None,
          lang_code="en"):
    senses = [{"glosses": list(glosses)}]
    if form_of:
        senses[0]["form_of"] = [{"word": form_of}]
    o = {"word": word, "pos": pos, "lang_code": lang_code, "senses": senses}
    if forms:
        o["forms"] = forms
    return o


def build(objs, lang="en"):
    """Run the real builder over [objs] and return (lemmas, aliases, glosses)."""
    tmp = Path(tempfile.mkdtemp())
    src, db = tmp / "in.jsonl", tmp / "out.sqlite"
    with open(src, "w", encoding="utf-8") as f:
        for o in objs:
            f.write(json.dumps(o, ensure_ascii=False) + "\n")
    build_sqlite(src, db, lang)
    c = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    lemmas = {r[0] for r in c.execute("SELECT text FROM headword WHERE position=0")}
    aliases = {r[0] for r in c.execute("SELECT text FROM headword WHERE position=2")}
    glosses = {}
    for text, g in c.execute(
        "SELECT h.text, s.glosses FROM headword h JOIN sense s ON s.entry_id=h.entry_id "
        "WHERE h.position=0"
    ):
        glosses.setdefault(text, []).append(g)
    c.close()
    return lemmas, aliases, glosses


# ── Change 2: the redirect rescue ────────────────────────────────────────

def test_resolvable_redirect_becomes_an_alias_and_not_a_lemma():
    lemmas, aliases, _ = build([
        entry("walk", "verb", ["to move on foot"]),
        entry("walked", "verb", ["past tense of walk"], form_of="walk"),
    ])
    assert "walk" in lemmas
    assert "walked" in aliases, aliases
    assert "walked" not in lemmas, "a resolvable redirect must not shadow its lemma"


def test_unresolvable_redirect_is_kept_as_a_lemma_with_its_own_gloss():
    # The target is absent from the file, so nothing can be aliased onto — the
    # Hindi nuqta shape. The page must survive carrying its redirect gloss.
    lemmas, aliases, glosses = build([
        entry("walk", "verb", ["to move on foot"]),
        entry("newspaper", "noun", ["nuqtaless form of newspaper-x"],
              form_of="newspaper-x"),
    ])
    assert "newspaper" in lemmas, "an unresolvable redirect must not vanish"
    assert "newspaper" not in aliases
    assert any("nuqtaless form of" in g for g in glosses["newspaper"]), glosses["newspaper"]


def test_chain_through_a_non_kept_intermediate_still_aliases_to_the_end():
    # criticised -> criticise (itself only a redirect) -> criticize (kept).
    lemmas, aliases, _ = build([
        entry("criticize", "verb", ["to find fault with something"]),
        entry("criticise", "verb", ["alternative form of criticize"], form_of="criticize"),
        entry("criticised", "verb", ["past tense of criticise"], form_of="criticise"),
    ])
    assert "criticize" in lemmas
    assert "criticised" in aliases, aliases
    assert "criticised" not in lemmas, "a chain that resolves must not be rescued"
    assert "criticise" not in lemmas, "the intermediate resolves too, so it aliases"


def test_a_page_naming_no_followable_target_is_kept():
    # en `oneself`: the redirect marker sits at ENTRY level and names prose, so
    # pass 2 never finds a target. It used to be dropped with nothing to alias.
    o = entry("oneself", "pron", ["a person's self"])
    o["form_of"] = [{"word": "the indefinite personal pronoun one"}]
    lemmas, _, glosses = build([entry("walk", "verb", ["to move on foot"]), o])
    assert "oneself" in lemmas, "an unfollowable redirect must not vanish"
    assert any("person's self" in g for g in glosses["oneself"])


def test_a_stressed_redirect_target_resolves_to_its_unstressed_lemma():
    # Russian Wiktionary writes redirect targets with the pronunciation stress
    # on ("кни́га"), but a lemma's own `word` never carries it, so the target
    # never matched a kept lemma and the page looked unresolvable. 31,960 ru
    # glosses name a stressed target; 86.2% ARE kept lemmas once it comes off.
    KNIGA, KNIGI = "книга", "книги"
    STRESSED = "кни́га"
    lemmas, aliases, _ = build([
        entry(KNIGA, "noun", ["a book"], lang_code="ru"),
        entry(KNIGI, "noun", [f"genitive singular of {STRESSED}"],
              form_of=STRESSED, lang_code="ru"),
    ], lang="ru")
    assert KNIGA in lemmas, lemmas
    assert KNIGI in aliases, "the stressed target must resolve to its bare lemma"
    assert KNIGI not in lemmas, "it should alias, not be rescued as a stub"


# ── The rescue fixpoint ──────────────────────────────────────────────────

def test_chain_into_a_rescued_terminal_aliases_instead_of_stubbing():
    # The shape of en "house lights -> house light -> houselight": the terminal
    # only becomes a lemma via the rescue, so resolving once against
    # kept_lemma_ids left BOTH as stubs. Fixture words must clear MIN_FREQUENCY
    # to be rescuable at all, so this uses the same shape with frequent words.
    lemmas, aliases, _ = build([
        entry("walk", "verb", ["to move on foot"]),
        entry("pot man", "noun", ["alternative form of potmanx"],
              form_of="potmanx"),                       # terminal: target absent
        entry("pot men", "noun", ["plural of pot man"], form_of="pot man"),
    ])
    assert "pot man" in lemmas, "the terminal page must be rescued as a lemma"
    assert "pot men" in aliases, aliases
    assert "pot men" not in lemmas, "a page that can alias must not also stub"


def test_two_hop_chain_into_a_rescued_terminal():
    lemmas, aliases, _ = build([
        entry("walk", "verb", ["to move on foot"]),
        entry("color", "noun", ["alternative form of colorx"], form_of="colorx"),
        entry("colour", "noun", ["alternative form of color"], form_of="color"),
        entry("colours", "noun", ["plural of colour"], form_of="colour"),
    ])
    assert "color" in lemmas, "the terminal page must be rescued as a lemma"
    for surf in ("colour", "colours"):
        assert surf in aliases, (surf, aliases)
        assert surf not in lemmas, f"{surf} must alias, not stub"


def test_a_page_resolving_to_a_real_lemma_is_untouched_by_the_loop():
    lemmas, aliases, _ = build([
        entry("walk", "verb", ["to move on foot"]),
        entry("walked", "verb", ["past tense of walk"], form_of="walk"),
    ])
    assert "walked" in aliases and "walked" not in lemmas
    assert "walk" in lemmas


def test_case_fold_self_loop_is_not_emitted_twice():
    # en `nazi` is glossed "Alternative form of Nazi": the target lowercases back
    # onto the source, so the page is targetless, but the real Nazi entry is
    # already kept under that same surface. One entry, not two.
    lemmas, _, glosses = build([
        entry("Nazi", "noun", ["a member of the National Socialist party"]),
        entry("nazi", "noun", ["alternative form of Nazi"], form_of="Nazi"),
    ])
    assert "nazi" in lemmas
    real = [g for g in glosses["nazi"] if "National Socialist" in g]
    assert real, glosses["nazi"]
    assert len(glosses["nazi"]) == 1, f"one entry expected, got {glosses['nazi']}"


def test_letterless_redirect_surfaces_alias_instead_of_stubbing():
    # "+1" -> "plus one" was an alias in v3 and became stub lemmas once the
    # cross-script gate started reading an empty script set as foreign.
    lemmas, aliases, _ = build([
        entry("plus one", "noun", ["a guest accompanying an invitee"]),
        entry("+1", "noun", ["alternative form of plus one"], form_of="plus one"),
        entry("360", "noun", ["a complete rotation"]),
        entry("three-sixty", "noun", ["alternative form of 360"], form_of="360"),
    ])
    assert "+1" in aliases, aliases
    assert "+1" not in lemmas, "letterless source must alias, not stub"
    assert "three-sixty" in aliases, "letterless target must be reachable"
    assert "three-sixty" not in lemmas


def test_a_redirect_page_never_shadows_a_real_entry_at_the_same_surface():
    # kaikki emits `nazi` ("Alternative form of Nazi") BEFORE `Nazi`, and both
    # lowercase to one surface. While redirect pages shared the real entries'
    # (word, pos) dedupe namespace, the stub claimed the slot and all of Nazi's
    # real senses were dropped from the pack.
    lemmas, aliases, glosses = build([
        entry("nazi", "noun", ["alternative form of Nazi"], form_of="Nazi"),
        entry("Nazi", "noun", ["a member of the National Socialist party"]),
    ])
    assert "nazi" in lemmas
    assert any("National Socialist" in g for g in glosses["nazi"]), glosses["nazi"]
    assert not any("alternative form" in g.lower() for g in glosses["nazi"]), glosses["nazi"]


def test_vietnamese_diacritics_are_latin_not_a_foreign_script():
    # Nearly every accented Vietnamese vowel is Latin Extended Additional
    # (U+1E00-1EFF). While that block fell outside the "latn" range, `bác sỹ`
    # scored {latn, other} against `bác sĩ`'s {latn} and the alias was rejected
    # as a cross-script transliteration.
    assert _scripts_of("bác sỹ") == _scripts_of("bác sĩ") == frozenset({"latn"})
    lemmas, aliases, _ = build([
        entry("bác sĩ", "noun", ["a medical doctor; a physician"], lang_code="vi"),
        entry("bác sỹ", "noun", ["alternative spelling of bác sĩ"],
              form_of="bác sĩ", lang_code="vi"),
    ], lang="vi")
    assert "bác sỹ" in aliases, aliases
    assert "bác sỹ" not in lemmas, "a same-script alias must not become a stub"


def test_a_target_naming_its_hanja_inline_still_resolves():
    # Korean `alt_of` gives "고등학생(高等學生)" while the pack keys the lemma on
    # "고등학생". Hangul -> Hangul, so the cross-script gate is not involved:
    # a Latin ROMANIZATION like "hoxy" is a different case the gate correctly
    # still rejects.
    lemmas, aliases, _ = build([
        entry("고등학생", "noun", ["a high school student"], lang_code="ko"),
        entry("고딩", "noun", ["abbreviation of 고등학생"],
              form_of="고등학생(高等學生)", lang_code="ko"),
    ], lang="ko")
    assert "고등학생" in lemmas
    assert "고딩" in aliases, aliases
    assert "고딩" not in lemmas


# ── Change 1: inflection-class rows are not word forms ───────────────────

def test_inflection_class_rows_are_excluded_but_real_slash_forms_are_not():
    obj = {"forms": [
        {"form": "38/nainen", "tags": ["class"], "source": "declension"},
        {"form": "52*d/sanoa", "tags": ["class"], "source": "conjugation"},
        {"form": "naisen", "tags": ["genitive", "singular"]},
        {"form": "1/sgt", "tags": ["abbreviation"]},
    ]}
    got = eligible_form_surfaces(obj, "fi", "nainen", _scripts_of("nainen"))
    assert "38/nainen" not in got and "52*d/sanoa" not in got, got
    assert got == {"naisen", "1/sgt"}, got


def test_class_row_no_longer_lifts_a_rare_lemma_over_the_cut():
    # The scaffolding string carries the MODEL word's frequency; without the
    # filter this rare compound would be kept on the strength of "nainen".
    rare = "tyhjakayntisaadin"
    objs = [entry("walk", "verb", ["to move on foot"]),
            entry(rare, "noun", ["a rare compound"],
                  forms=[{"form": "38/woman", "tags": ["class"], "source": "declension"}])]
    lemmas, _, _ = build(objs)
    assert rare not in lemmas, "an inflection-class row must not carry the cut"


if __name__ == "__main__":
    for _name, _fn in sorted(globals().items()):
        if _name.startswith("test_") and callable(_fn):
            _fn()
            print(f"ok  {_name}")
    print("all redirect-rescue regressions passed")
