"""Regression tests for pass 2's chained-redirect resolution
(build_latin_dict.resolve_redirect_chain) and the shared forms[] surface
filter (eligible_form_surfaces).

Motivating bug: alias targets can themselves be redirect pages —
"criticised" is form_of "criticise", which is only altspell_of "criticize".
Single-hop resolution ("target must be a kept lemma") reached the non-kept
intermediate and gave up, so the en pack carried NO row for the inflections
of any alternative-spelling lemma.

NOTE: importing build_latin_dict needs the pack-build deps installed
(wordfreq, snowballstemmer) — run inside the same venv the builds use.

Run: python3 scripts/test_alias_chains.py   (or via pytest)
"""

from build_latin_dict import (
    _scripts_of,
    eligible_form_surfaces,
    resolve_redirect_chain,
)

KEPT = {"criticize": [10], "walk": [11], "colour": [12]}


def test_single_hop_still_resolves():
    graph = {"walked": {"walk"}}
    assert resolve_redirect_chain("walked", graph, KEPT) == {"walk"}


def test_two_hop_chain_resolves_through_non_kept_intermediate():
    graph = {
        "criticised": {"criticise"},
        "criticise": {"criticize"},
    }
    assert resolve_redirect_chain("criticised", graph, KEPT) == {"criticize"}


def test_kept_lemma_terminates_its_branch():
    # criticize is kept AND has an outgoing redirect edge (e.g. a synonym_of
    # sense) to another kept lemma. The chain must stop AT criticize — a kept
    # lemma is the answer, never a waypoint to more aliases.
    graph = {
        "criticised": {"criticise"},
        "criticise": {"criticize"},
        "criticize": {"colour"},
    }
    assert resolve_redirect_chain("criticised", graph, KEPT) == {"criticize"}


def test_fan_out_resolves_each_target_independently():
    # One target is directly kept, the sibling needs a hop — both land.
    graph = {
        "colorised": {"colour", "colorise"},
        "colorise": {"criticize"},
    }
    assert resolve_redirect_chain("colorised", graph, KEPT) == {"colour", "criticize"}


def test_cycle_terminates_empty():
    graph = {"a": {"b"}, "b": {"a"}}
    assert resolve_redirect_chain("a", graph, KEPT) == set()


def test_depth_cap_bounds_the_walk():
    graph = {
        "s": {"h1"},
        "h1": {"h2"},
        "h2": {"h3"},
        "h3": {"criticize"},
    }
    # 4 hops needed; the default cap (3) refuses, an explicit 4 resolves.
    assert resolve_redirect_chain("s", graph, KEPT) == set()
    assert resolve_redirect_chain("s", graph, KEPT, max_hops=4) == {"criticize"}


def test_unknown_source_resolves_empty():
    assert resolve_redirect_chain("ghost", {}, KEPT) == set()


def test_form_surface_filter_keeps_real_forms_and_drops_junk():
    obj = {
        "forms": [
            {"form": "confiscated"},
            {"form": "confiscating"},
            {"form": "confiscate"},                          # lemma-identical
            {"form": "-a-"},                                 # hyphen scaffolding
            {"form": "?"},                                   # junk literal
            {"form": "two words"},                           # multi-word
            {"form": "table", "tags": ["table-tags"]},       # scaffolding row
            {"form": ""},
        ]
    }
    got = eligible_form_surfaces(obj, "en", "confiscate", _scripts_of("confiscate"))
    assert got == {"confiscated", "confiscating"}, got


if __name__ == "__main__":
    for _name, _fn in sorted(globals().items()):
        if _name.startswith("test_") and callable(_fn):
            _fn()
            print(f"ok  {_name}")
    print("all alias-chain regressions passed")
