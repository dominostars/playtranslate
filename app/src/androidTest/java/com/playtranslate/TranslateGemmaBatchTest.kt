package com.playtranslate

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.playtranslate.translation.translategemma.LlamaTranslator
import com.playtranslate.translation.translategemma.TranslateGemmaModel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase F validation harness — mirrors MlKitBatchTranslateTest but runs the
 * TranslateGemma backend (via [LlamaTranslator] directly to bypass the
 * `prefs.translateGemmaEnabled` gate).
 *
 * Pre-requisite: the model file must already be on the device at
 * `TranslateGemmaModel.file(appCtx)`. The on-device download flow can place
 * it there, or it can be sideloaded via:
 *
 *   adb push <gguf> /sdcard/Download/translategemma-4b-it.Q4_0.gguf
 *   adb shell run-as com.playtranslate sh -c \
 *     'mkdir -p /data/data/com.playtranslate/no_backup/models && \
 *      cp /sdcard/Download/translategemma-4b-it.Q4_0.gguf \
 *         /data/data/com.playtranslate/no_backup/models/translategemma-4b-it.Q4_0.gguf'
 *
 * Output:
 *   <appCtx.getExternalFilesDir>/p5_500_translategemma.json
 *   <appCtx.getExternalFilesDir>/p5_500_translategemma_summary.json
 *
 * Pull with:
 *   adb pull /sdcard/Android/data/com.playtranslate/files/p5_500_translategemma.json
 *   adb pull /sdcard/Android/data/com.playtranslate/files/p5_500_translategemma_summary.json
 */
@RunWith(AndroidJUnit4::class)
class TranslateGemmaBatchTest {

    @Test
    fun translateP5Batch() = runBlocking {
        val instr = InstrumentationRegistry.getInstrumentation()
        val testCtx = instr.context
        val appCtx = instr.targetContext

        // Relaxed from `isInstalled` (which strict-checks size against the
        // catalog) so we can drop in experimental quants (Q4_0, Q5_K_M, etc.)
        // by just sideloading to the same path.
        val modelFile = TranslateGemmaModel.file(appCtx)
        check(modelFile.exists() && modelFile.length() > 1_000_000) {
            "Model not present at ${modelFile.absolutePath}. " +
                "Sideload before running this test (see KDoc)."
        }
        val modelPath = modelFile.absolutePath
        println("TG_MODEL_PATH: $modelPath  size=${modelFile.length()}")

        val inputJson = testCtx.assets.open("p5_500_ja.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONArray(inputJson)
        val sentences = (0 until arr.length()).map { arr.getString(it) }

        val translator = LlamaTranslator(appCtx)

        // Warmup: first call pays the model-load cost. Translate one short sentence
        // and discard timing. The model stays loaded for the timed run.
        println("TG_WARMUP_START")
        val warmStart = System.currentTimeMillis()
        translator.translate("こんにちは", "ja", "en", modelPath)
        val warmMs = System.currentTimeMillis() - warmStart
        println("TG_WARMUP_DONE: ${warmMs}ms")

        // Timed run.
        val out = JSONArray()
        val t0 = System.currentTimeMillis()
        for ((i, s) in sentences.withIndex()) {
            val tr0 = System.currentTimeMillis()
            val translated = try {
                translator.translate(s, "ja", "en", modelPath)
            } catch (e: Exception) {
                "[ERROR: ${e.javaClass.simpleName}: ${e.message}]"
            }
            val tr1 = System.currentTimeMillis()
            val obj = JSONObject()
            obj.put("ja", s)
            obj.put("translategemma", translated)
            obj.put("ms", tr1 - tr0)
            out.put(obj)
            if ((i + 1) % 25 == 0) {
                val elapsed = (System.currentTimeMillis() - t0) / 1000.0
                val rate = (i + 1) / elapsed.coerceAtLeast(0.001)
                val eta = (sentences.size - (i + 1)) / rate.coerceAtLeast(0.001)
                println("TG_PROGRESS: ${i + 1}/${sentences.size} elapsed=${"%.1f".format(elapsed)}s rate=${"%.2f".format(rate)}/s eta=${"%.0f".format(eta)}s")
            }
        }
        val totalMs = System.currentTimeMillis() - t0

        translator.close()

        val outDir = appCtx.getExternalFilesDir(null) ?: appCtx.filesDir
        val outFile = File(outDir, "p5_500_translategemma.json")
        outFile.writeText(out.toString(2), Charsets.UTF_8)

        // Compute latency stats.
        val mss = (0 until out.length()).map { out.getJSONObject(it).optLong("ms", 0L) }
            .filter { it > 0 }
            .sorted()
        val avg = if (mss.isNotEmpty()) mss.sum().toDouble() / mss.size else 0.0
        val median = mss.getOrNull(mss.size / 2) ?: 0L
        val p90 = mss.getOrNull((mss.size * 0.9).toInt().coerceAtMost(mss.size - 1)) ?: 0L
        val max = mss.lastOrNull() ?: 0L

        val summary = JSONObject().apply {
            put("count", sentences.size)
            put("warmup_ms", warmMs)
            put("total_ms", totalMs)
            put("avg_ms", avg)
            put("median_ms", median)
            put("p90_ms", p90)
            put("max_ms", max)
            put("output_path", outFile.absolutePath)
        }
        File(outDir, "p5_500_translategemma_summary.json").writeText(summary.toString(2), Charsets.UTF_8)
        println("TG_BATCH_DONE: $summary")
    }
}
