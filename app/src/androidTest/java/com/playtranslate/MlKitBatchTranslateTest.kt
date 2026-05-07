package com.playtranslate

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MlKitBatchTranslateTest {

    @Test
    fun translateP5Batch() = runBlocking {
        val instr = InstrumentationRegistry.getInstrumentation()
        val testCtx = instr.context
        val appCtx = instr.targetContext

        val inputJson = testCtx.assets.open("p5_500_ja.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONArray(inputJson)
        val sentences = (0 until arr.length()).map { arr.getString(it) }

        val tm = TranslationManager("ja", "en")
        tm.ensureModelReady(requireWifi = false)

        val out = JSONArray()
        val t0 = System.currentTimeMillis()
        for (s in sentences) {
            val tr0 = System.currentTimeMillis()
            val translated = tm.translate(s)
            val tr1 = System.currentTimeMillis()
            val obj = JSONObject()
            obj.put("ja", s)
            obj.put("ml_kit", translated)
            obj.put("ms", tr1 - tr0)
            out.put(obj)
        }
        val totalMs = System.currentTimeMillis() - t0

        tm.close()

        val outDir = appCtx.getExternalFilesDir(null) ?: appCtx.filesDir
        val outFile = File(outDir, "p5_500_mlkit.json")
        outFile.writeText(out.toString(2), Charsets.UTF_8)

        val summary = JSONObject().apply {
            put("count", sentences.size)
            put("total_ms", totalMs)
            put("avg_ms", totalMs.toDouble() / sentences.size)
            put("output_path", outFile.absolutePath)
        }
        File(outDir, "p5_500_mlkit_summary.json").writeText(summary.toString(2), Charsets.UTF_8)

        // Echo to logcat for sanity
        println("MLKIT_BATCH_DONE: $summary")
    }
}
