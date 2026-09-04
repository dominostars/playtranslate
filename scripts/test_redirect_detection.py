"""Regression tests for redirect detection beyond the structured alias fields
(wiktionary_filters.is_redirect_sense / redirect_target_from_gloss).

Motivating bug: pass 1 dropped a page as a redirect only when a sense carried
one of WIKT_REDIRECT_KEYS. Pages that say the same thing with a TAG, or in the
gloss prose, survived as their own lemmas — and once det/article/postp became
content POS that started shadowing real words. Spanish `su` is the worst case:
its sense 0 carries alt_of, but its sense 1 has no tags and no alias keys and is
glossed "apocopic form of suyo", so the ALL-senses rule kept the whole page, and
the position-first ranking then put `su` ahead of `suyo` — where the possessive
glosses actually live.

Run: python3 scripts/test_redirect_detection.py   (or via pytest)
"""

from wiktionary_filters import (
    MAX_REDIRECT_TARGET_WORDS as MAXW,
    is_redirect_entry,
    is_redirect_sense,
    redirect_target_from_gloss,
)


# ── detection ────────────────────────────────────────────────────────────

def test_gloss_only_redirect_is_detected():
    # es `su` sense 1, verbatim: no tags, no alias keys, prose target only.
    sense = {"glosses": ["apocopic form of suyo",
                         "used to express an approximate number: about, approximately"]}
    assert is_redirect_sense(sense)


def test_tag_only_redirect_is_detected():
    # A form-of sense whose target lives only in `tags` (en `oneself` shape).
    sense = {"tags": ["form-of", "reflexive"],
             "glosses": ["A person's self: general form of himself or herself."]}
    assert is_redirect_sense(sense)


def test_structured_field_still_detected():
    assert is_redirect_sense({"form_of": [{"word": "criticize"}]})


def test_entry_with_one_real_sense_is_not_a_redirect():
    # `would`: "past tense of will" beside senses that are real definitions.
    # The ALL-senses rule is what keeps it a lemma, and must survive the
    # widened per-sense test.
    entry = {"senses": [
        {"glosses": ["past tense of will"]},
        {"glosses": ["Used to express a polite request."]},
    ]}
    assert is_redirect_sense(entry["senses"][0])
    assert not is_redirect_sense(entry["senses"][1])
    assert not is_redirect_entry(entry)


def test_su_shaped_entry_is_a_redirect_entry():
    entry = {"senses": [
        {"tags": ["abbreviation", "alt-of", "apocopic"],
         "alt_of": [{"word": "suyo"}], "glosses": ["apocopic form of suyo"]},
        {"glosses": ["apocopic form of suyo",
                     "used to express an approximate number: about, approximately"]},
    ]}
    assert is_redirect_entry(entry)


def test_ordinary_definitions_of_the_same_shape_are_not_redirects():
    # The article guard plus the target-length ceiling. Without them a noun
    # definition reads as a pointer and the whole page disappears.
    for gloss in (
        "A form of address used in formal contexts",
        "A case of beer",
        "An abbreviation of the sort used by telegraph operators everywhere",
        "One thing (among a group of others); one member of a group.",
    ):
        assert not is_redirect_sense({"glosses": [gloss]}), gloss


def test_unfollowable_target_is_not_a_redirect():
    # Detection is tied to producing a usable target, so a page is never
    # dropped as a pointer we cannot then follow (the en `oneself` hole).
    assert redirect_target_from_gloss(
        "form of the indefinite personal pronoun one", MAXW) is None
    assert not is_redirect_sense(
        {"glosses": ["form of the indefinite personal pronoun one"]})


# ── target parsing ───────────────────────────────────────────────────────

def test_target_parsing():
    cases = {
        "apocopic form of suyo": "suyo",
        "past participle of készül:": "készül",
        "Dated spelling of today.": "today",
        "Alternative form of run-in (adjective)": "run-in",
        "masculine plural of volontario": "volontario",
        "genitive singular of hus": "hus",
        "(before the noun) apocopic form of suyo": "suyo",
    }
    for gloss, want in cases.items():
        got = redirect_target_from_gloss(gloss, MAXW)
        assert got == want, f"{gloss!r} -> {got!r}, expected {want!r}"


def test_target_longer_than_the_ceiling_is_rejected():
    assert redirect_target_from_gloss(
        "plural of one two three four", MAXW) is None
    assert redirect_target_from_gloss("plural of one two three", MAXW) == "one two three"


def test_non_form_of_gloss_yields_no_target():
    assert redirect_target_from_gloss("A large domesticated carnivore.", MAXW) is None
    assert redirect_target_from_gloss("", MAXW) is None


if __name__ == "__main__":
    for _name, _fn in sorted(globals().items()):
        if _name.startswith("test_") and callable(_fn):
            _fn()
            print(f"ok  {_name}")
    print("all redirect-detection regressions passed")
