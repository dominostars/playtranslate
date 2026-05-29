package com.playtranslate

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.playtranslate.dictionary.SudachiJapaneseTokenizer
import com.playtranslate.language.InstallResult
import com.playtranslate.language.LanguagePackStore
import com.playtranslate.language.PreloadResult
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.SourceLangId
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 4 (sudachi-prod): validate the PRODUCTION JA pipeline end-to-end on
 * device with the real Sudachi dict — tokenize -> JMdict lookup -> furigana
 * through the post-swap DictionaryManager/JapaneseEngine (no kuromoji).
 *
 * Stages system_small.dic into the installed JA pack's tokenizer/ dir (this is
 * what ja-v3 will ship), copying from the adb-pushed external file:
 *   adb push system_small.dic /sdcard/Android/data/com.playtranslate/files/sudachi/system_small.dic
 *
 * Run: ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.playtranslate.SudachiProdValidationTest
 * Output: /sdcard/Android/data/com.playtranslate/files/sudachi_prod_validation.json
 */
@RunWith(AndroidJUnit4::class)
class SudachiProdValidationTest {

    private val TAG = "SudachiProd"

    @Test
    fun validateProductionPipeline() = runBlocking {
        val appCtx = InstrumentationRegistry.getInstrumentation().targetContext

        // 1. JA pack (dict.sqlite) must be installed.
        if (!LanguagePackStore.isInstalled(appCtx, SourceLangId.JA)) {
            val r = LanguagePackStore.install(appCtx, SourceLangId.JA) {}
            assertEquals("JA pack install failed: $r", InstallResult.Success, r)
        }

        // 2. Stage system_small.dic into the pack tokenizer/ dir (what ja-v3 ships),
        //    copying from the adb-pushed external file.
        val src = File(appCtx.getExternalFilesDir(null), "sudachi/system_small.dic")
        assertTrue("Push system_small.dic to ${src.absolutePath} first.", src.isFile)
        val tokDir = LanguagePackStore.dirFor(appCtx, SourceLangId.JA).resolve("tokenizer").apply { mkdirs() }
        val dest = File(tokDir, "system_small.dic")
        if (!dest.isFile || dest.length() != src.length()) src.copyTo(dest, overwrite = true)

        // 3. Fresh engine so init points the Sudachi Provider at the dict-bearing pack dir.
        SourceLanguageEngines.release(SourceLangId.JA)
        val engine = SourceLanguageEngines.get(appCtx, SourceLangId.JA)
        assertEquals("preload should succeed with system_small.dic present",
            PreloadResult.Success, engine.preload())

        val out = JSONObject()

        // 4. Furigana fidelity vs the SentenceAnkiHtmlBuilder unit-test canned tokens.
        val furi = JSONObject()
        for (s in listOf("聞いた", "取り出す", "友達に聞いた", "今度はC", "鴨志田に会った")) {
            val ann = engine.annotateForHintText(s)
            furi.put(s, ann.joinToString(" | ") {
                "${s.substring(it.baseStart, it.baseEnd)}[${it.hintText}]@${it.baseStart}"
            })
        }
        out.put("furigana", furi)

        // 5. Tokenize + JMdict lookup through the production path.
        val tokDump = JSONObject()
        var hits = 0; var total = 0
        for (s in listOf("使わないでください", "友達に聞いた", "鴨志田の聖域だ")) {
            val spans = engine.tokenize(s)
            assertTrue("tokenize($s) should be non-empty", spans.isNotEmpty())
            tokDump.put(s, spans.joinToString(" | ") { "${it.surface}->${it.lookupForm}[${it.reading ?: ""}]" })
            for (sp in spans) { total++; if (engine.lookup(sp.lookupForm, sp.reading) != null) hits++ }
        }
        out.put("tokens", tokDump)
        out.put("lookup_hits", "$hits/$total")
        out.put("dic", dest.name + " (" + dest.length() + " bytes)")

        File(appCtx.getExternalFilesDir(null) ?: appCtx.filesDir, "sudachi_prod_validation.json")
            .writeText(out.toString(2), Charsets.UTF_8)
        Log.i(TAG, "SUDACHI_PROD_DONE: $out")
        println("SUDACHI_PROD_DONE: $out")
        assertTrue("expected JMdict lookup hits through the production pipeline", hits > 0)
    }

    /**
     * Phase 4: concurrency stress for the Provider lifetime guard. Hammers
     * Provider.analyze from several threads while close()/initPackDir() churn —
     * the interleaving Codex flagged (Dictionary closed during an in-flight
     * tokenize). Pre-fix this faulted / threw (then got masked as empty); the
     * read/write lock must keep it crash-free and still working after the storm.
     */
    @Test
    fun concurrentAnalyzeVsClose() {
        val appCtx = InstrumentationRegistry.getInstrumentation().targetContext
        val src = File(appCtx.getExternalFilesDir(null), "sudachi/system_small.dic")
        assertTrue("Push system_small.dic to ${src.absolutePath} first.", src.isFile)
        val tokDir = LanguagePackStore.dirFor(appCtx, SourceLangId.JA).resolve("tokenizer").apply { mkdirs() }
        val dest = File(tokDir, "system_small.dic")
        if (!dest.isFile || dest.length() != src.length()) src.copyTo(dest, overwrite = true)

        SudachiJapaneseTokenizer.Provider.initPackDir(tokDir)
        SudachiJapaneseTokenizer.Provider.preload()

        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val samples = listOf("使わないでください", "友達に聞いた", "鴨志田の聖域だ", "取り出す")
        val readers = (1..6).map {
            Thread {
                try {
                    while (!stop.get()) for (s in samples) SudachiJapaneseTokenizer.Provider.analyze(s)
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }.apply { start() }
        }
        // Churn close + re-init (forces close-while-tokenizing + rebuilds) for ~2s.
        val end = System.nanoTime() + 2_000_000_000L
        var churns = 0
        try {
            while (System.nanoTime() < end) {
                SudachiJapaneseTokenizer.Provider.close()
                SudachiJapaneseTokenizer.Provider.initPackDir(tokDir)
                churns++
                Thread.sleep(3)
            }
        } catch (t: Throwable) {
            errors.add(t)
        }
        stop.set(true)
        readers.forEach { it.join(10_000) }

        SudachiJapaneseTokenizer.Provider.initPackDir(tokDir)
        val after = SudachiJapaneseTokenizer.Provider.analyze("友達に聞いた")

        Log.i(TAG, "concurrentAnalyzeVsClose: churns=$churns errors=${errors.size} afterTokens=${after.size}")
        assertTrue(
            "Concurrency errors during the analyze/close storm: ${errors.take(3).map { it.toString() }}",
            errors.isEmpty(),
        )
        assertTrue("analyze should still work after the storm", after.isNotEmpty())
    }
}
