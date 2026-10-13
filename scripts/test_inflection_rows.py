"""Regression tests for the inflection tables: the row selector
(build_latin_dict.eligible_inflection_rows), the blob encoder/decoder
(InflectionBlobs / decode_inflection_blob), the end-to-end write through
build_sqlite, and the INFLECTION_SMOKE helper.

NOTE: importing build_latin_dict needs the pack-build deps installed
(wordfreq, snowballstemmer) — run inside the same venv the builds use.

Run: python3 scripts/test_inflection_rows.py   (or via pytest)
"""

import json
import sqlite3
import tempfile
from pathlib import Path

from build_latin_dict import (
    InflectionBlobs,
    _scripts_of,
    build_sqlite,
    decode_inflection_blob,
    eligible_inflection_rows,
    form_of_rows,
    inflection_smoke_failures,
    load_inflection_tagsets,
)


def form(text, tags=(), source="conjugation"):
    d = {"form": text, "tags": list(tags)}
    if source is not None:
        d["source"] = source
    return d


def rows(forms, lang="es", lemma="hablar"):
    obj = {"word": lemma, "pos": "verb", "forms": forms}
    return eligible_inflection_rows(obj, lang, lemma, _scripts_of(lemma))


# ── the row selector ─────────────────────────────────────────────────────

def test_table_rows_keep_sorted_tags_in_kaikki_order_including_the_lemma_row():
    got = rows([
        form("hablar", ["infinitive"]),
        form("hablamos", ["first-person", "plural", "present", "indicative"]),
        form("hablamos", ["indicative", "preterite", "plural", "first-person"]),
    ])
    assert got == [
        (("infinitive",), "hablar"),
        (("first-person", "indicative", "plural", "present"), "hablamos"),
        (("first-person", "indicative", "plural", "preterite"), "hablamos"),
    ], got


def test_combined_form_rows_are_kept_with_their_tags():
    got = rows([
        form("habla", ["imperative", "informal", "second-person", "singular"]),
        form("háblame", ["combined-form", "dative", "imperative", "informal",
                         "object-first-person", "object-singular", "second-person", "singular"]),
    ])
    assert [s for _, s in got] == ["habla", "háblame"], got
    assert "combined-form" in got[1][0]


def test_construction_placeholders_and_scaffolding_rows_are_dropped():
    got = rows([
        form("avoir + past participle", ["infinitive", "multiword-construction"]),
        form("Conjugation of hablar", ["table-tags"]),
        form("es-conj", ["inflection-template"]),
        form("e-ie alternation", ["class"]),
        form("?", ["gerund"]),
        form("-", ["participle"]),
        form("hablando", ["gerund"]),
    ])
    assert got == [(("gerund",), "hablando")], got


def test_compound_tense_fragments_are_dropped_by_their_tag():
    # kaikki's French compound tenses: most rows read "avoir + past participle"
    # and fall to the " + " rule; the fragments without a plus ("ayant", "past
    # historic of avoir") carry only the tag.
    got = rows([
        form("parlant", ["gerund", "participle", "present"]),
        form("ayant", ["gerund", "multiword-construction", "participle", "present"]),
        form("past historic of avoir", ["anterior", "indicative", "multiword-construction", "past"]),
    ], lang="fr")
    assert got == [(("gerund", "participle", "present"), "parlant")], got


def test_multi_word_forms_are_kept_unlike_the_alias_pass():
    got = rows([form("me arrepiento", ["first-person", "indicative", "present", "singular"])],
               lemma="arrepentirse")
    assert got == [(("first-person", "indicative", "present", "singular"), "me arrepiento")], got


def test_duplicate_rows_collapse_to_one():
    got = rows([
        form("ido", ["masculine", "participle", "past", "singular"]),
        form("ido", ["masculine", "participle", "past", "singular"]),
    ], lemma="ir")
    assert len(got) == 1, got


def test_surfaces_are_build_lowercased():
    got = rows([form("Hablamos", ["first-person", "indicative", "plural", "present"])])
    assert got[0][1] == "hablamos", got


def test_junk_gate_removes_ipa_rows():
    got = eligible_inflection_rows(
        {"word": "parler", "pos": "verb", "forms": [
            form("parle", ["first-person", "indicative", "present", "singular"]),
            form("paʁl", ["first-person", "indicative", "present", "singular"]),
        ]},
        "fr", "parler", _scripts_of("parler"),
    )
    assert [s for _, s in got] == ["parle"], got


def test_headline_rows_are_a_fallback_only_when_the_entry_has_no_table():
    # A verb with a table: its headline rows (hablo, hablé, hablado without a
    # source) must not be added beside the table's own lines.
    verb = rows([
        form("hablo", ["first-person", "present", "singular"], source=None),
        form("hablamos", ["first-person", "indicative", "plural", "present"]),
    ])
    assert [s for _, s in verb] == ["hablamos"], verb
    # A noun has only headline rows: the grammatical one is kept, the
    # variant-spelling ones are not.
    noun = eligible_inflection_rows(
        {"word": "casa", "pos": "noun", "forms": [
            form("casas", ["plural"], source=None),
            form("cassa", ["alternative", "obsolete"], source=None),
            form("casa", ["canonical"], source=None),
            form("kasa", [], source=None),
        ]},
        "es", "casa", _scripts_of("casa"),
    )
    assert noun == [(("plural",), "casas")], noun


def test_headline_fallback_excludes_register_tags_even_with_a_grammatical_one():
    got = eligible_inflection_rows(
        {"word": "blanco", "pos": "adj", "forms": [
            form("blanca", ["feminine", "singular"], source=None),
            form("blancas", ["feminine", "plural"], source=None),
            form("blancos", ["masculine", "plural"], source=None),
            form("blanquito", ["diminutive", "colloquial"], source=None),
        ]},
        "es", "blanco", _scripts_of("blanco"),
    )
    assert [s for _, s in got] == ["blanca", "blancas", "blancos"], got


def test_rows_of_a_second_table_are_tagged_with_its_ordinal():
    # ir's page: ir's table, ir's clitic combinations (all combined-form, so the
    # table contributes nothing), then irse's table whose single-word
    # imperatives carry ir's imperative tags verbatim.
    got = rows([
        form("irregular", ["table-tags"]),
        form("es-conj", ["inflection-template"]),
        form("ir", ["infinitive"]),
        form("ve", ["imperative", "informal", "second-person", "singular"]),
        form("idos", ["masculine", "participle", "past", "plural"]),
        form("irregular", ["table-tags"]),
        form("es-conj", ["inflection-template"]),
        form("vete", ["combined-form", "imperative", "informal", "second-person", "singular", "with-tú"]),
        form("irregular", ["table-tags"]),
        form("es-conj", ["inflection-template"]),
        form("me voy", ["first-person", "indicative", "present", "singular"]),
        form("vete", ["imperative", "informal", "second-person", "singular"]),
        form("idos", ["masculine", "participle", "past", "plural"]),
    ], lemma="ir")
    # The clitic table (combined forms only) belongs to ir, so irse's table is
    # the SECOND paradigm table; its finite forms are two words, so it is the
    # pronominal variant; the combined row carries no table tag.
    assert got == [
        (("infinitive",), "ir"),
        (("imperative", "informal", "second-person", "singular"), "ve"),
        (("masculine", "participle", "past", "plural"), "idos"),
        (("combined-form", "imperative", "informal", "second-person", "singular", "with-tú"), "vete"),
        (("first-person", "indicative", "present", "pronominal", "singular", "table-2"), "me voy"),
        (("imperative", "informal", "pronominal", "second-person", "singular", "table-2"), "vete"),
        (("masculine", "participle", "past", "plural", "pronominal", "table-2"), "idos"),
    ], got


def test_a_second_table_with_one_word_finite_forms_is_the_lemma_s_own_paradigm():
    # acostar: the stem-changing table, then the regular one; both are acostar.
    got = rows([
        form("es-conj", ["inflection-template"]),
        form("acuesta", ["indicative", "present", "singular", "third-person"]),
        form("es-conj", ["inflection-template"]),
        form("acosta", ["indicative", "present", "singular", "third-person"]),
    ], lemma="acostar")
    assert got == [
        (("indicative", "present", "singular", "third-person"), "acuesta"),
        (("indicative", "present", "singular", "table-2", "third-person"), "acosta"),
    ], got


def test_a_single_table_with_or_without_its_marker_is_table_one():
    with_marker = rows([form("es-conj", ["inflection-template"]), form("hablo", ["first-person", "indicative", "present", "singular"])])
    without = rows([form("hablo", ["first-person", "indicative", "present", "singular"])])
    assert with_marker == without == [(("first-person", "indicative", "present", "singular"), "hablo")]


def test_hungarian_tables_are_skipped():
    got = rows([form("beszélek", ["first-person", "indicative", "present", "singular"])],
               lang="hu", lemma="beszél")
    assert got == [], got


# ── the blob format ──────────────────────────────────────────────────────

def test_blob_interns_tag_sets_across_entries_and_round_trips():
    blobs = InflectionBlobs()
    a = blobs.encode([(("infinitive",), "hablar"),
                      (("first-person", "indicative", "plural", "present"), "hablamos")])
    b = blobs.encode([(("infinitive",), "comer"),
                      (("first-person", "indicative", "plural", "present"), "comemos"),
                      (("gerund",), "comiendo")])
    assert blobs.encode([]) is None
    assert a == "1\thablar\n2\thablamos", a
    assert b == "1\tcomer\n2\tcomemos\n3\tcomiendo", b
    assert blobs.tagset_rows() == [
        (1, "infinitive"), (2, "first-person indicative plural present"), (3, "gerund"),
    ]
    assert (blobs.entries, blobs.lines, blobs.multiword) == (2, 5, 0)
    tagsets = dict(blobs.tagset_rows())
    assert decode_inflection_blob(b, tagsets) == [
        (frozenset({"infinitive"}), "comer"),
        (frozenset({"first-person", "indicative", "plural", "present"}), "comemos"),
        (frozenset({"gerund"}), "comiendo"),
    ]


def test_multiword_lines_are_counted():
    blobs = InflectionBlobs()
    blobs.encode([(("first-person", "indicative", "present", "singular"), "me arrepiento")])
    assert blobs.multiword == 1


# ── end to end through build_sqlite ──────────────────────────────────────

def entry(word, pos, glosses, forms=None):
    o = {"word": word, "pos": pos, "lang_code": "es",
         "senses": [{"glosses": list(glosses)}]}
    if forms:
        o["forms"] = forms
    return o


def verb_fixture_forms():
    """The INFLECTION_SMOKE['es'] shapes, as kaikki emits them."""
    return {
        "hablar": [
            form("hablar", ["infinitive"]),
            form("hablamos", ["first-person", "indicative", "plural", "present"]),
            form("hablamos", ["first-person", "indicative", "plural", "preterite"]),
            form("háblame", ["combined-form", "imperative"]),
            form("hablo", ["first-person", "present", "singular"], source=None),
        ],
        "ser": [form("fue", ["indicative", "preterite", "singular", "third-person"])],
        "ir": [form("voy", ["first-person", "indicative", "present", "singular"]),
               form("me voy", ["first-person", "indicative", "present", "singular"])],
        "quejarse": [
            form("quejarse", ["infinitive"]),
            form("me quejo", ["first-person", "indicative", "present", "singular"]),
        ],
    }


def build(objs, lang="es"):
    tmp = Path(tempfile.mkdtemp())
    src, db = tmp / "in.jsonl", tmp / "out.sqlite"
    with open(src, "w", encoding="utf-8") as f:
        for o in objs:
            f.write(json.dumps(o, ensure_ascii=False) + "\n")
    build_sqlite(src, db, lang)
    return db


def table_of(conn, lemma):
    tagsets = load_inflection_tagsets(conn)
    blobs = conn.execute(
        "SELECT i.forms FROM headword h JOIN inflection i ON i.entry_id = h.entry_id "
        "WHERE h.text = ? AND h.position = 0", (lemma,)
    ).fetchall()
    assert len(blobs) == 1, (lemma, blobs)
    return decode_inflection_blob(blobs[0][0], tagsets)


def blob_of(conn, lemma, pos):
    """The decoded blob of the lemma's entry of [pos], or None when that
    entry has no inflection row."""
    rows = conn.execute(
        "SELECT DISTINCT i.forms FROM headword h JOIN sense s ON s.entry_id = h.entry_id "
        "JOIN inflection i ON i.entry_id = h.entry_id "
        "WHERE h.text = ? AND h.position = 0 AND s.pos = ?", (lemma, pos)
    ).fetchall()
    assert len(rows) <= 1, (lemma, pos, rows)
    return decode_inflection_blob(rows[0][0], load_inflection_tagsets(conn)) if rows else None


def test_build_writes_blobs_tagsets_and_passes_the_smoke_fixtures():
    forms = verb_fixture_forms()
    db = build([
        entry("hablar", "verb", ["to talk"], forms["hablar"]),
        entry("ser", "verb", ["to be"], forms["ser"]),
        entry("ir", "verb", ["to go"], forms["ir"]),
        entry("quejarse", "verb", ["to complain"], forms["quejarse"]),
        entry("casa", "noun", ["house"], [form("casas", ["plural"], source=None)]),
    ])
    c = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    try:
        hablar = table_of(c, "hablar")
        assert [s for _, s in hablar] == ["hablar", "hablamos", "hablamos", "háblame"], hablar
        assert "combined-form" in hablar[3][0]
        assert table_of(c, "casa") == [(frozenset({"plural"}), "casas")]
        assert table_of(c, "ir") == [
            (frozenset({"first-person", "indicative", "present", "singular"}), "voy"),
            (frozenset({"first-person", "indicative", "present", "singular"}), "me voy"),
        ]
        n_tagsets = c.execute("SELECT count(*) FROM inflection_tagset").fetchone()[0]
        # infinitive, 1pl present, 1pl preterite, combined imperative, 3sg preterite, 1sg present, plural
        assert n_tagsets == 7, n_tagsets
        assert inflection_smoke_failures(c, "es") == []
        # The alias pass is unchanged: the multi-word form is NOT an alias row,
        # the combined form still is.
        aliases = {r[0] for r in c.execute("SELECT text FROM headword WHERE position = 2")}
        assert "me arrepiento" not in aliases
        assert "háblame" in aliases
    finally:
        c.close()


def test_form_of_rows_keep_grammar_tags_and_skip_spelling_pages():
    page = {"word": "cintita", "pos": "noun", "senses": [
        {"glosses": ["diminutive of cinta"], "tags": ["diminutive", "feminine", "form-of"], "form_of": [{"word": "cinta"}]},
    ]}
    assert form_of_rows(page, "es") == [("cinta", ("diminutive", "feminine", "form-of"), "cintita")]
    spelling = {"word": "fablar", "pos": "verb", "senses": [
        {"glosses": ["obsolete form of hablar"], "tags": ["alt-of", "obsolete", "form-of"], "form_of": [{"word": "hablar"}]},
    ]}
    assert form_of_rows(spelling, "es") == []
    bare = {"word": "x", "pos": "noun", "senses": [{"tags": ["form-of"], "form_of": [{"word": "y"}]}]}
    assert form_of_rows(bare, "es") == []


def test_form_of_pages_fill_surfaces_the_table_lacks_and_never_double_table_rows():
    db = build([
        entry("cinta", "noun", ["tape"], [form("cintas", ["plural"], source=None)]),
        {"word": "cintita", "pos": "noun", "lang_code": "es", "senses": [
            {"glosses": ["diminutive of cinta"], "tags": ["diminutive", "feminine", "form-of"], "form_of": [{"word": "cinta"}]},
        ]},
        entry("hablar", "verb", ["to talk"], [
            form("hablamos", ["first-person", "indicative", "plural", "present"]),
        ]),
        # A noun homograph of the verb (ser "being" beside ser "to be" in the
        # real pack): the verb pages' lines must not land on it.
        entry("hablar", "noun", ["a talk"]),
        {"word": "hablé", "pos": "verb", "lang_code": "es", "senses": [
            {"glosses": ["first-person singular preterite indicative of hablar"],
             "tags": ["first-person", "form-of", "indicative", "preterite", "singular"], "form_of": [{"word": "hablar"}]},
        ]},
        # A page for a surface the table already has: the table's line wins, no duplicate.
        {"word": "hablamos", "pos": "verb", "lang_code": "es", "senses": [
            {"glosses": ["first-person plural present indicative of hablar"],
             "tags": ["first-person", "form-of", "indicative", "plural", "present"], "form_of": [{"word": "hablar"}]},
        ]},
        # A verb with no table (comer here; dietar in the real pack): its form
        # page is the only source.
        entry("comer", "verb", ["to eat"]),
        {"word": "comió", "pos": "verb", "lang_code": "es", "senses": [
            {"glosses": ["third-person singular preterite indicative of comer"],
             "tags": ["form-of", "indicative", "preterite", "singular", "third-person"], "form_of": [{"word": "comer"}]},
        ]},
    ])
    c = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    try:
        assert table_of(c, "cinta") == [
            (frozenset({"plural"}), "cintas"),
            (frozenset({"diminutive", "feminine", "form-of"}), "cintita"),
        ]
        assert [s for _, s in table_of(c, "hablar")] == ["hablamos", "hablé"]
        assert blob_of(c, "hablar", "verb") is not None
        assert blob_of(c, "hablar", "noun") is None
        assert table_of(c, "comer") == [
            (frozenset({"form-of", "indicative", "preterite", "singular", "third-person"}), "comió"),
        ]
    finally:
        c.close()


def test_a_bundled_pronominal_variant_does_not_block_a_form_of_page():
    # callar's page carries callarse's table (its finite forms two words, so
    # the builder tags it pronominal): the variant's one-word imperative
    # `cállese` sits in callar's blob under the variant's tags, which the app
    # keeps out of callar's readings. The form-of page reading cállese as
    # callar + se must still be appended; callar's own `calla` still blocks its
    # page.
    db = build([
        entry("callar", "verb", ["to be quiet"], [
            form("es-conj", ["inflection-template"]),
            form("callar", ["infinitive"]),
            form("calla", ["imperative", "informal", "second-person", "singular"]),
            form("es-conj", ["inflection-template"]),
            form("me callo", ["first-person", "indicative", "present", "singular"]),
            form("cállese", ["imperative", "singular", "third-person"]),
        ]),
        {"word": "cállese", "pos": "verb", "lang_code": "es", "senses": [
            {"glosses": ["third-person singular imperative of callar combined with se"],
             "tags": ["form-of", "imperative", "reflexive", "singular", "third-person"], "form_of": [{"word": "callar"}]},
        ]},
        {"word": "calla", "pos": "verb", "lang_code": "es", "senses": [
            {"glosses": ["second-person singular imperative of callar"],
             "tags": ["form-of", "imperative", "second-person", "singular"], "form_of": [{"word": "callar"}]},
        ]},
    ])
    c = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    try:
        assert table_of(c, "callar") == [
            (frozenset({"infinitive"}), "callar"),
            (frozenset({"imperative", "informal", "second-person", "singular"}), "calla"),
            (frozenset({"first-person", "indicative", "present", "singular", "pronominal", "table-2"}), "me callo"),
            (frozenset({"imperative", "singular", "third-person", "pronominal", "table-2"}), "cállese"),
            (frozenset({"form-of", "imperative", "reflexive", "singular", "third-person"}), "cállese"),
        ]
    finally:
        c.close()


def test_smoke_helper_reports_a_missing_form_and_a_missing_table():
    forms = verb_fixture_forms()
    forms["ir"] = [form("ido", ["masculine", "participle", "past", "singular"])]  # no voy
    db = build([
        entry("hablar", "verb", ["to talk"], forms["hablar"]),
        entry("ser", "verb", ["to be"], forms["ser"]),
        entry("ir", "verb", ["to go"], forms["ir"]),
        entry("quejarse", "verb", ["to complain"]),  # no forms at all
        # A noun whose headline forms include the verb's `fue`: the absent
        # fixture (ser, noun, fue) must report it.
        entry("ser", "noun", ["a being"], [form("fue", ["plural"], source=None)]),
    ])
    c = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    try:
        failures = inflection_smoke_failures(c, "es")
    finally:
        c.close()
    assert len(failures) == 3, failures
    assert any("'ser' (noun)" in f and "'fue'" in f for f in failures), failures
    assert any("'ir'" in f and "'voy'" in f for f in failures), failures
    assert any("'quejarse'" in f and "no inflection blob" in f for f in failures), failures


if __name__ == "__main__":
    for _name, _fn in sorted(globals().items()):
        if _name.startswith("test_") and callable(_fn):
            _fn()
            print(f"ok  {_name}")
    print("all inflection-row regressions passed")
