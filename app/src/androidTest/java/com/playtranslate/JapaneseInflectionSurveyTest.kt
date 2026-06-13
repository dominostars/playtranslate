package com.playtranslate

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.playtranslate.dictionary.SudachiJapaneseTokenizer
import com.playtranslate.language.LanguagePackStore
import com.playtranslate.language.SourceLangId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 0 survey for the inflection-labeling feature ("B"): dumps the raw
 * Sudachi morpheme stream for a conjugation battery so the
 * JapaneseInflectionAnalyzer mapping table can be authored against ground
 * truth instead of assumptions. This is the ONE device-only dependency in the
 * plan — it answers what the repo can't:
 *   - does 勉強させる split 勉強+さ+せる or 勉強+させる (suru-verb causative)?
 *   - exact dictionaryForm of せる/させる/られる/た/ない/ん/ます as mode-A emits them
 *   - does ませんでした double-count politeness (ます + です/でし)?
 *   - is volitional a う/よう AUX, or only a stem 意志推量形 inflectionForm?
 *   - confirms て+いる PROGRESSIVE is unreachable (いる emitted as its own VERB)
 *
 * Mirrors [SudachiProdValidationTest]: assumeTrue-gated on the installed JA
 * pack (Thor has it), with the same adb-pushed system_small.dic fallback, so an
 * unfiltered connectedDebugAndroidTest on a fresh/CI device SKIPS (stays green).
 *
 * Run (on Thor):
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.playtranslate.JapaneseInflectionSurveyTest
 * Pull the dump:
 *   adb exec-out run-as com.playtranslate cat files/ja_inflection_survey.json
 * Or read the readable per-word breakdown lines in logcat (tag "JaInflSurvey").
 *
 * Temporary diagnostic — safe to delete once the mapping table is finalized,
 * but kept (skip-by-default) as a regression aid like the sibling batch tests.
 */
@RunWith(AndroidJUnit4::class)
class JapaneseInflectionSurveyTest {

    private val TAG = "JaInflSurvey"

    /** (group label -> words). Grouped so the dump is navigable by paradigm. */
    private val battery: List<Pair<String, List<String>>> = listOf(
        "godan_iu (ワア行 — the 言わせて motivating case)" to listOf(
            "言う", "言わない", "言わなかった", "言います", "言いました",
            "言いません", "言いませんでした", "言った", "言って", "言える",
            "言われる", "言わせる", "言わせて", "言わせない", "言わせられる",
            "言わされる", "言おう", "言え", "言えば", "言いたい", "言いたかった",
        ),
        "godan_onbin (te/ta euphonic variants)" to listOf(
            "書く", "書いて", "書いた", "泳ぐ", "泳いで", "話す", "話して",
            "待つ", "待って", "死ぬ", "死んで", "飛ぶ", "飛んで", "読む", "読んで",
            "行く", "行って", "行った",
        ),
        "ichidan_taberu" to listOf(
            "食べる", "食べない", "食べた", "食べて", "食べられる", "食べさせる",
            "食べさせられる", "食べさせられませんでした", "食べろ", "食べよう",
            "食べれば", "食べたい", "食べている", "食べてる", "食べてた",
            "食べません", "食べませんでした", "食べなくて",
        ),
        "suru (incl. 勉強する causative split — key unknown)" to listOf(
            "する", "しない", "した", "して", "される", "させる", "しろ", "せよ",
            "しよう", "すれば", "したい", "勉強する", "勉強した", "勉強させる",
            "勉強される", "勉強しない", "読ませてください",
        ),
        "kuru" to listOf(
            "来る", "来ない", "来た", "来て", "来られる", "来させる", "来い",
            "来よう", "来れば",
        ),
        "i_adjective_takai" to listOf(
            "高い", "高くない", "高かった", "高くなかった", "高くて", "高ければ", "高く",
        ),
        "na_adjective_copula" to listOf(
            "静かだ", "静かではない", "静かだった", "静かで", "綺麗です", "綺麗でした",
        ),
    )

    @Test
    fun surveyConjugationMorphemes() {
        val appCtx = InstrumentationRegistry.getInstrumentation().targetContext

        assumeTrue(
            "Skipped: JA pack not installed — stage it before running the inflection survey.",
            LanguagePackStore.isInstalled(appCtx, SourceLangId.JA),
        )

        // Prefer the pack's own tokenizer dict; fall back to the adb-pushed
        // system_small.dic so the survey also runs on pre-ja-v3 installs.
        val tokDir = LanguagePackStore.dirFor(appCtx, SourceLangId.JA).resolve("tokenizer")
        if (tokDir.listFiles { f -> f.extension == "dic" }.isNullOrEmpty()) {
            val src = File(appCtx.getExternalFilesDir(null), "sudachi/system_small.dic")
            if (src.isFile) {
                tokDir.mkdirs()
                src.copyTo(File(tokDir, "system_small.dic"), overwrite = true)
            }
        }
        assumeTrue(
            "Skipped: no Sudachi dict in ${tokDir.absolutePath} and no adb-pushed system_small.dic.",
            tokDir.listFiles { f -> f.extension == "dic" }?.isNotEmpty() == true,
        )

        SudachiJapaneseTokenizer.Provider.initPackDir(tokDir)
        SudachiJapaneseTokenizer.Provider.preload()

        val out = JSONObject()
        var totalWords = 0
        for ((group, words) in battery) {
            val groupObj = JSONObject()
            Log.i(TAG, "── $group ──")
            for (w in words) {
                val tokens = SudachiJapaneseTokenizer.Provider.analyze(w)
                val arr = JSONArray()
                for (t in tokens) {
                    arr.put(JSONObject().apply {
                        put("s", t.surface)
                        put("d", t.dictionaryForm)
                        put("n", t.normalizedForm)
                        put("c", t.category.name)
                        put("i", t.inflectionForm ?: JSONObject.NULL)
                        put("o", t.isOov)
                    })
                }
                groupObj.put(w, arr)
                // Readable one-liner for logcat: 言わせて = 言わ/言う/VERB/未然形-一般 + せ/せる/AUX/連用形-一般 + て/て/PARTICLE/null
                val readable = tokens.joinToString("  +  ") {
                    "${it.surface}/${it.dictionaryForm}/${it.category.name}/${it.inflectionForm ?: "·"}"
                }
                Log.i(TAG, "$w = $readable")
                totalWords++
            }
            out.put(group, groupObj)
        }

        val outFile = File(appCtx.filesDir, "ja_inflection_survey.json")
        outFile.writeText(out.toString(2), Charsets.UTF_8)
        val marker = "JA_INFLECTION_SURVEY_DONE: $totalWords words -> ${outFile.absolutePath}"
        Log.i(TAG, marker)
        println(marker)

        assertTrue("survey produced no morphemes", totalWords > 0)
    }
}
