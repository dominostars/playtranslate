"""Regression tests for the curated misc filter (wiktionary_filters.filter_misc).

Covers the two adversarial-review findings:
  F1: build_target_pack feeds RAW JMdict misc text (not the MISC_ABBREV-
      abbreviated form), so the raw descriptive forms must normalize.
  F2: target packs delimit misc with a TAB, so domain labels that contain
      commas (e.g. "food, cooking") survive instead of being split apart.

Run: python3 scripts/test_misc_filter.py   (or via pytest)
"""

import json
import logging

logging.disable(logging.CRITICAL)

import wiktionary_filters as wf

VOCAB = json.load(open(wf._MISC_VOCAB_PATH, encoding="utf-8"))


def test_raw_jmdict_kana_forms_normalize():
    assert wf.filter_misc(["usually written using kana alone"]) == ["Kana only"]
    assert wf.filter_misc(["word usually written using kana alone"]) == ["Kana only"]
    assert wf.filter_misc(["usually written using kanji alone"]) == ["Kanji only"]


def test_comma_domain_survives_tab_roundtrip():
    out = wf.filter_misc(["food, cooking", "computing"])
    assert out == ["food, cooking", "computing"], out
    # Target packs store the list tab-joined and the runtime splits on tab.
    assert "\t".join(out).split("\t") == ["food, cooking", "computing"]


def test_no_vocab_token_contains_the_tab_delimiter():
    tokens = []
    for entry in VOCAB["register"]:
        tokens.append(entry["label"])
        tokens.extend(entry["aliases"])
    tokens += VOCAB["domainAllowlist"] + VOCAB["regionGazetteer"]
    bad = [t for t in tokens if "\t" in t]
    assert not bad, f"misc tokens contain the tab delimiter: {bad}"


if __name__ == "__main__":
    for _name, _fn in sorted(globals().items()):
        if _name.startswith("test_") and callable(_fn):
            _fn()
            print(f"ok  {_name}")
    print("all misc filter regressions passed")
