"""
Tests for the JMdict pack-builder scoring formula.

Run from project root:
    python3 -m unittest tests.test_build_jmdict

These tests pin the validated formula (per project_kana_lookup_ranking.md and
the JMdict_e 2026-05-10 validation report) so future formula tweaks can't
silently break the 11/12 spot-check result.
"""

import sys
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

# Add scripts/ to import path. The build_jmdict module isn't packaged.
PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

import build_jmdict  # noqa: E402


class ReadingRankScoreTests(unittest.TestCase):
    """compute_reading_rank_score(re_pri_set, re_inf_set, position) -> int."""

    def test_empty_inputs(self):
        self.assertEqual(
            build_jmdict.compute_reading_rank_score(set(), set(), 0), 0
        )

    def test_priority_only_one_tag(self):
        # +1M for IsPriority, no IsFrequent (only one priority tag), no inf, pos 0.
        self.assertEqual(
            build_jmdict.compute_reading_rank_score({"ichi1"}, set(), 0),
            1_000_000,
        )

    def test_priority_and_frequent_two_priority_tags(self):
        # +1M IsPriority +1M IsFrequent (intersection size > 1), pos 0.
        self.assertEqual(
            build_jmdict.compute_reading_rank_score(
                {"ichi1", "news1"}, set(), 0
            ),
            2_000_000,
        )

    def test_nf_tag_alone_does_not_grant_priority(self):
        # nf07 is in FREQ_TAGS but not PRIORITY_TAGS, so IsPriority is false
        # and IsFrequent requires |FREQ_TAGS ∩ re_pri| > 1 — single nf07
        # alone fails both. Validation report explicitly noted "nf07 adds
        # nothing" — codify it.
        self.assertEqual(
            build_jmdict.compute_reading_rank_score({"nf07"}, set(), 0), 0
        )

    def test_priority_plus_nf_grants_frequent(self):
        # ichi1 + nf07 → IsPriority (+1M) + IsFrequent (intersection of size
        # 2 in FREQ_TAGS) (+1M).
        self.assertEqual(
            build_jmdict.compute_reading_rank_score(
                {"ichi1", "nf07"}, set(), 0
            ),
            2_000_000,
        )

    def test_three_priority_tags_still_caps_at_2M(self):
        # The validation report's 個々 case: ichi1+news1+nf07 → IsPriority +
        # IsFrequent = +2M (NOT +3M — there's no per-tag scaling).
        self.assertEqual(
            build_jmdict.compute_reading_rank_score(
                {"ichi1", "news1", "nf07"}, set(), 0
            ),
            2_000_000,
        )

    def test_position_penalty(self):
        # Position 4 → -40K from base.
        self.assertEqual(
            build_jmdict.compute_reading_rank_score({"ichi1"}, set(), 4),
            1_000_000 - 40_000,
        )

    def test_no_priority_position_penalty_only(self):
        # The 九 case: no re_pri, position 4 → -40K total. Validation report
        # baseline.
        self.assertEqual(
            build_jmdict.compute_reading_rank_score(set(), set(), 4), -40_000
        )

    def test_archaic_kana_penalty_single_tag(self):
        # ik on a priority reading: +1M base − 5M = −4M. Confirms the
        # penalty dominates priority for a single archaic tag.
        self.assertEqual(
            build_jmdict.compute_reading_rank_score(
                {"ichi1"}, {"ik"}, 0
            ),
            1_000_000 - 5_000_000,
        )

    def test_archaic_kana_penalty_no_priority(self):
        # ok with no priority, position 0 → -5M.
        self.assertEqual(
            build_jmdict.compute_reading_rank_score(set(), {"ok"}, 0),
            -5_000_000,
        )

    def test_archaic_kana_penalty_stacks(self):
        # Two archaic tags compound: -10M.
        self.assertEqual(
            build_jmdict.compute_reading_rank_score(set(), {"ik", "ok"}, 0),
            -10_000_000,
        )

    def test_non_archaic_inf_tag_ignored(self):
        # Some other re_inf value like "ng" (not in our set) should be
        # ignored. (Hypothetical — JMdict's actual re_inf values are ik/ok/rk.)
        self.assertEqual(
            build_jmdict.compute_reading_rank_score(
                {"ichi1"}, {"ng"}, 0
            ),
            1_000_000,
        )


class HeadwordRankScoreTests(unittest.TestCase):
    """compute_headword_rank_score(ke_pri_set, position) -> int. NO archaic
    penalty — validation report Mode C confirmed dropping it."""

    def test_empty_inputs(self):
        self.assertEqual(
            build_jmdict.compute_headword_rank_score(set(), 0), 0
        )

    def test_priority_only(self):
        self.assertEqual(
            build_jmdict.compute_headword_rank_score({"ichi1"}, 0), 1_000_000
        )

    def test_priority_and_frequent(self):
        self.assertEqual(
            build_jmdict.compute_headword_rank_score({"ichi1", "news1"}, 0),
            2_000_000,
        )

    def test_position_penalty(self):
        self.assertEqual(
            build_jmdict.compute_headword_rank_score({"ichi1"}, 3),
            1_000_000 - 30_000,
        )


class KnownEntryScoreTests(unittest.TestCase):
    """The three entries from the validation report (此処/個々/九 ここ
    readings) that motivated this work. Asserting their exact scores locks
    in the formula intent."""

    def test_koko_at_kokyu(self):
        # 此処 entry 1288810: ここ at reading position 0 with re_pri = ichi1.
        # Expected: +1M.
        self.assertEqual(
            build_jmdict.compute_reading_rank_score({"ichi1"}, set(), 0),
            1_000_000,
        )

    def test_koko_at_koko_individual(self):
        # 個々 entry 1593190: ここ at reading position 0 with re_pri =
        # ichi1, news1, nf07. Expected: +2M.
        self.assertEqual(
            build_jmdict.compute_reading_rank_score(
                {"ichi1", "news1", "nf07"}, set(), 0
            ),
            2_000_000,
        )

    def test_koko_at_nine(self):
        # 九 entry 1578150: ここ at reading position 4 with no re_pri.
        # Expected: -40K. This is the "wrong answer" we're explicitly
        # demoting.
        self.assertEqual(
            build_jmdict.compute_reading_rank_score(set(), set(), 4),
            -40_000,
        )

    def test_koko_ranking_holds(self):
        # The ranking-fix endpoint: 此処 (with uk-bonus +1.5M) > 個々 > 九.
        score_kotokoro = build_jmdict.compute_reading_rank_score(
            {"ichi1"}, set(), 0
        ) + 1_500_000  # uk-bonus applied because uk_applicable=1
        score_kojikoji = build_jmdict.compute_reading_rank_score(
            {"ichi1", "news1", "nf07"}, set(), 0
        )  # no uk-bonus
        score_kyu = build_jmdict.compute_reading_rank_score(
            set(), set(), 4
        )
        self.assertGreater(score_kotokoro, score_kojikoji)
        self.assertGreater(score_kojikoji, score_kyu)


class UkReadingsTests(unittest.TestCase):
    """compute_uk_readings_for_entry: identifies which readings in an entry
    are covered by a sense's uk tag, respecting <stagr> restrictions."""

    # The value_to_name map yields the "uk" entity name when the misc text
    # equals one of the JMdict-expanded forms. We use the canonical value
    # JMdict ships ("word usually written using kana alone").
    UK_VALUE = "word usually written using kana alone"
    VALUE_TO_NAME = {UK_VALUE: "uk"}

    def _entry(self, xml_str: str):
        return ET.fromstring(xml_str)

    def test_uk_sense_no_stagr_covers_all_readings(self):
        entry = self._entry(
            f"""
            <entry>
              <ent_seq>1</ent_seq>
              <r_ele><reb>ここ</reb></r_ele>
              <r_ele><reb>こご</reb></r_ele>
              <sense>
                <misc>{self.UK_VALUE}</misc>
                <gloss>here</gloss>
              </sense>
            </entry>
            """
        )
        result = build_jmdict.compute_uk_readings_for_entry(
            entry, self.VALUE_TO_NAME
        )
        self.assertEqual(result, {"ここ", "こご"})

    def test_uk_sense_with_stagr_restricts_to_listed_readings(self):
        entry = self._entry(
            f"""
            <entry>
              <ent_seq>1</ent_seq>
              <r_ele><reb>ここ</reb></r_ele>
              <r_ele><reb>こご</reb></r_ele>
              <sense>
                <stagr>ここ</stagr>
                <misc>{self.UK_VALUE}</misc>
                <gloss>here (only this reading)</gloss>
              </sense>
            </entry>
            """
        )
        result = build_jmdict.compute_uk_readings_for_entry(
            entry, self.VALUE_TO_NAME
        )
        self.assertEqual(result, {"ここ"})

    def test_no_uk_sense_returns_empty(self):
        entry = self._entry(
            """
            <entry>
              <ent_seq>1</ent_seq>
              <r_ele><reb>ここ</reb></r_ele>
              <sense><gloss>nothing uk-tagged</gloss></sense>
            </entry>
            """
        )
        result = build_jmdict.compute_uk_readings_for_entry(
            entry, self.VALUE_TO_NAME
        )
        self.assertEqual(result, set())

    def test_multiple_senses_one_uk(self):
        entry = self._entry(
            f"""
            <entry>
              <ent_seq>1</ent_seq>
              <r_ele><reb>ここ</reb></r_ele>
              <sense><gloss>first sense, no uk</gloss></sense>
              <sense>
                <misc>{self.UK_VALUE}</misc>
                <gloss>second sense, uk</gloss>
              </sense>
            </entry>
            """
        )
        result = build_jmdict.compute_uk_readings_for_entry(
            entry, self.VALUE_TO_NAME
        )
        self.assertEqual(result, {"ここ"})

    def test_unknown_misc_value_ignored(self):
        # A misc value not in value_to_name (e.g. an entity we don't track)
        # shouldn't trigger the uk path.
        entry = self._entry(
            """
            <entry>
              <ent_seq>1</ent_seq>
              <r_ele><reb>ここ</reb></r_ele>
              <sense>
                <misc>some other tag we don't care about</misc>
                <gloss>nope</gloss>
              </sense>
            </entry>
            """
        )
        result = build_jmdict.compute_uk_readings_for_entry(
            entry, self.VALUE_TO_NAME
        )
        self.assertEqual(result, set())


class ReInfExtractionTests(unittest.TestCase):
    """extract_re_inf_names converts expanded re_inf entity values back to
    short entity names via value_to_name."""

    def _r_ele(self, xml_str: str):
        return ET.fromstring(xml_str)

    def test_extract_single_archaic_tag(self):
        value_to_name = {"rarely used kana form": "rk"}
        r_ele = self._r_ele(
            "<r_ele><reb>x</reb><re_inf>rarely used kana form</re_inf></r_ele>"
        )
        self.assertEqual(
            build_jmdict.extract_re_inf_names(r_ele, value_to_name),
            {"rk"},
        )

    def test_extract_multiple_tags(self):
        value_to_name = {
            "rarely used kana form": "rk",
            "out-dated or obsolete kana usage": "ok",
        }
        r_ele = self._r_ele(
            "<r_ele><reb>x</reb>"
            "<re_inf>rarely used kana form</re_inf>"
            "<re_inf>out-dated or obsolete kana usage</re_inf>"
            "</r_ele>"
        )
        self.assertEqual(
            build_jmdict.extract_re_inf_names(r_ele, value_to_name),
            {"rk", "ok"},
        )

    def test_unknown_value_dropped(self):
        # If the expanded value isn't in value_to_name (e.g. JMdict added a
        # new info tag we haven't catalogued), the tag is silently dropped.
        value_to_name = {"rarely used kana form": "rk"}
        r_ele = self._r_ele(
            "<r_ele><reb>x</reb>"
            "<re_inf>some brand-new info tag</re_inf>"
            "</r_ele>"
        )
        self.assertEqual(
            build_jmdict.extract_re_inf_names(r_ele, value_to_name),
            set(),
        )

    def test_no_re_inf_returns_empty(self):
        r_ele = self._r_ele("<r_ele><reb>x</reb></r_ele>")
        self.assertEqual(
            build_jmdict.extract_re_inf_names(r_ele, {}),
            set(),
        )


if __name__ == "__main__":
    unittest.main()
