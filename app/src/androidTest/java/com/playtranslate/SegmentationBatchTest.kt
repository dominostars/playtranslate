package com.playtranslate

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.playtranslate.language.LanguagePackStore
import com.playtranslate.language.PreloadResult
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngines
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Segmentation regression harness: runs the production JA tokenize path
 * (engine.tokenize -> tokenizeWithSurfaces, including the n-gram re-glob)
 * over the 500-sentence Persona 5 corpus and dumps the full token stream
 * for offline diffing across builds (scripts/compare_segmentation.py).
 *
 * Needs a Sudachi dict: uses the one shipped in the installed JA pack's
 * tokenizer/ dir, or stages the adb-pushed system_small.dic like
 * [SudachiProdValidationTest] when the pack lacks one. Skips otherwise.
 *
 * Run: ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.playtranslate.SegmentationBatchTest
 * Output (app-internal): files/p5_500_segmentation.json — pull with
 *   adb exec-out run-as com.playtranslate cat files/p5_500_segmentation.json
 */
@RunWith(AndroidJUnit4::class)
class SegmentationBatchTest {

    @Test
    fun segmentP5Batch() = runBlocking {
        val instr = InstrumentationRegistry.getInstrumentation()
        val testCtx = instr.context
        val appCtx = instr.targetContext

        // Skip (never fail, never network-install) when the JA pack is absent:
        // an unfiltered connectedDebugAndroidTest on a fresh/offline device must
        // stay green. Operators stage the pack beforehand (Thor has it; on an
        // emulator, sideload dict.sqlite + manifest.json + tokenizer/ via run-as).
        assumeTrue(
            "Skipped: JA pack not installed — stage it before running the segmentation harness.",
            LanguagePackStore.isInstalled(appCtx, SourceLangId.JA),
        )

        // Prefer the pack's own tokenizer dict; fall back to the adb-pushed
        // system_small.dic so the harness also runs on pre-ja-v3 installs.
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

        SourceLanguageEngines.release(SourceLangId.JA)
        val engine = SourceLanguageEngines.get(appCtx, SourceLangId.JA)
        assertEquals(PreloadResult.Success, engine.preload())

        val inputJson = testCtx.assets.open("p5_500_ja.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONArray(inputJson)
        val sentences = (0 until arr.length()).map { arr.getString(it) }

        val out = JSONArray()
        var totalTokens = 0
        val t0 = System.currentTimeMillis()
        for (s in sentences) {
            val spans = engine.tokenize(s)
            totalTokens += spans.size
            val tokens = JSONArray()
            for (sp in spans) {
                tokens.put(JSONObject().apply {
                    put("surface", sp.surface)
                    put("lookupForm", sp.lookupForm)
                    put("reading", sp.reading ?: JSONObject.NULL)
                })
            }
            out.put(JSONObject().apply {
                put("ja", s)
                put("tokens", tokens)
            })
        }
        val totalMs = System.currentTimeMillis() - t0

        // Internal filesDir (not external): on API 30+ neither adb shell nor
        // run-as can read the app's external dir, but run-as always reads
        // filesDir on a debuggable build. Pull with:
        //   adb exec-out run-as com.playtranslate cat files/p5_500_segmentation.json
        val outDir = appCtx.filesDir
        val outFile = File(outDir, "p5_500_segmentation.json")
        outFile.writeText(out.toString(2), Charsets.UTF_8)

        val summary = JSONObject().apply {
            put("count", sentences.size)
            put("total_tokens", totalTokens)
            put("total_ms", totalMs)
            put("output_path", outFile.absolutePath)
        }
        File(outDir, "p5_500_segmentation_summary.json")
            .writeText(summary.toString(2), Charsets.UTF_8)
        println("SEGMENTATION_BATCH_DONE: $summary")
    }
}
