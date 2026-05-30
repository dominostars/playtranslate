package com.playtranslate

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.playtranslate.translation.bergamot.BergamotModelFiles
import com.playtranslate.translation.bergamot.BergamotTranslator
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 500-sentence Persona 5 ja->en run through the Bergamot (slimt + gemmology)
 * engine on-device — the same corpus/shape as MlKitBatchTranslateTest and the
 * MNN-spike Qwen/TG runs, for an apples-to-apples latency + quality comparison.
 *
 * Setup: push the ja-en base-memory model files into the app's external files
 * dir before running, e.g.
 *   adb push <ja-en model dir>/. \
 *     /sdcard/Android/data/com.playtranslate/files/bergamot-ja-en/
 * Output: p5_500_bergamot.json + p5_500_bergamot_summary.json in that dir.
 */
@RunWith(AndroidJUnit4::class)
class BergamotBatchTest {

    @Test
    fun translateP5Batch() = runBlocking {
        val instr = InstrumentationRegistry.getInstrumentation()
        val testCtx = instr.context
        val appCtx = instr.targetContext

        val sentences = JSONArray(
            testCtx.assets.open("p5_500_ja.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        ).let { arr -> (0 until arr.length()).map { arr.getString(it) } }

        val modelDir = File(appCtx.getExternalFilesDir(null), "bergamot-ja-en")
        fun glob(re: Regex): File = modelDir.listFiles { f -> re.matches(f.name) }?.firstOrNull()
            ?: error("missing file matching $re in $modelDir (push the ja-en model first)")
        val files = BergamotModelFiles(
            direction = "ja-en",
            modelPath = glob(Regex("""model\..*\.intgemm\.alphas\.bin""")).absolutePath,
            vocabPath = glob(Regex("""vocab\..*\.spm""")).absolutePath,
            targetVocabPath = "", // ja-en is single-vocab
            shortlistPath = glob(Regex("""lex\..*\.bin""")).absolutePath,
            encoderLayers = 6,
            decoderLayers = 4,
            feedForwardDepth = 2,
            numHeads = 8,
        )

        val translator = BergamotTranslator.getInstance(appCtx)

        // Warm-up: first call pays the model-load cost.
        val warm0 = System.currentTimeMillis()
        translator.translateSingle(files, sentences.first())
        val warmupMs = System.currentTimeMillis() - warm0

        val out = JSONArray()
        val t0 = System.currentTimeMillis()
        for (s in sentences) {
            val tr0 = System.currentTimeMillis()
            val translated = translator.translateSingle(files, s)
            val tr1 = System.currentTimeMillis()
            out.put(JSONObject().apply {
                put("ja", s)
                put("bergamot", translated)
                put("ms", tr1 - tr0)
            })
        }
        val totalMs = System.currentTimeMillis() - t0
        translator.close()

        val outDir = appCtx.getExternalFilesDir(null) ?: appCtx.filesDir
        val outFile = File(outDir, "p5_500_bergamot.json")
        outFile.writeText(out.toString(2), Charsets.UTF_8)

        val lats = (0 until out.length()).map { out.getJSONObject(it).getLong("ms") }.sorted()
        val summary = JSONObject().apply {
            put("count", sentences.size)
            put("warmup_ms", warmupMs)
            put("total_ms", totalMs)
            put("avg_ms", totalMs.toDouble() / sentences.size)
            put("median_ms", lats[lats.size / 2])
            put("p90_ms", lats[minOf((lats.size * 0.9).toInt(), lats.size - 1)])
            put("max_ms", lats.last())
            put("output_path", outFile.absolutePath)
        }
        File(outDir, "p5_500_bergamot_summary.json").writeText(summary.toString(2), Charsets.UTF_8)
        println("BERGAMOT_BATCH_DONE: $summary")
    }
}
