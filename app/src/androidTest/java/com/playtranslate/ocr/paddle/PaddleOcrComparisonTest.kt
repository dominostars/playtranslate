package com.playtranslate.ocr.paddle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.playtranslate.ocr.OcrMetrics
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The PaddleOCR spike's 5-config comparison harness (docs/paddleocr-spike-scope.md).
 *
 * For every golden case it runs:
 *   1. baseline      — ML Kit detect + ML Kit recognize
 *   2. pp_full_mobile — PP detector + PP recognizer (mobile tier)
 *   3. pp_full_server — PP detector + PP recognizer (server tier)
 *   4. hybrid_mobile  — ML Kit detect → PP recognizer (mobile) on ML Kit line crops
 *   5. hybrid_server  — ML Kit detect → PP recognizer (server) on ML Kit line crops
 *
 * Emits per-(case,config) text + latency + CER/meaningful-op metrics, and for
 * the hybrid arms the per-line symbol-box outcome (A: PP==MLKit, B: differs/same
 * char count → boxes attach 1:1, C: differs/diff count → realignment needed),
 * all to logcat under the `PaddleSpike` tag (survives connectedAndroidTest's
 * post-run uninstall, same pattern as OcrGoldenSetTest). The host script
 * `scripts/build_paddle_spike_report.py` joins this with the golden PNGs into a
 * side-by-side HTML for the subjective pass.
 *
 * ## Models are NOT bundled — push them first
 *
 * The .mnn files (≈21 MB mobile, ≈172 MB server) are read from the app's
 * external files dir, not the test APK. Before running:
 * ```
 * adb shell mkdir -p /sdcard/Android/data/com.playtranslate/files/paddle_models
 * adb push mnn-spike/paddleocr-convert/mnn/{det_mobile,rec_mobile,det_server,rec_server}.mnn \
 *          mnn-spike/paddleocr-convert/mnn/keys.txt \
 *          /sdcard/Android/data/com.playtranslate/files/paddle_models/
 * ```
 * If the models are absent the test logs a clear skip and passes (no-op) so it
 * never hard-fails a CI/connected run that hasn't staged them.
 *
 * Run: `./gradlew connectedAndroidTest` with Thor connected. Recover the report:
 * `adb logcat -d -s PaddleSpike:I > paddle_spike.log`, then
 * `python scripts/build_paddle_spike_report.py paddle_spike.log`.
 */
@RunWith(AndroidJUnit4::class)
class PaddleOcrComparisonTest {

    private val instr get() = InstrumentationRegistry.getInstrumentation()
    private val testCtx: Context get() = instr.context           // androidTest APK (golden assets)
    private val appCtx: Context get() = instr.targetContext      // app context (external files)

    private val mlKit by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    @Test
    fun compareAllConfigs() {
        val cases = loadGoldenCases()
        if (cases.isEmpty()) { Log.w(REPORT, "No golden cases; skipping."); return }

        val modelsDir = File(appCtx.getExternalFilesDir(null), "paddle_models")
        val keys = File(modelsDir, "keys.txt")
        val haveModels = keys.exists() &&
            listOf("det_mobile", "rec_mobile", "det_server", "rec_server")
                .all { File(modelsDir, "$it.mnn").exists() }
        if (!haveModels) {
            Log.w(REPORT, "PaddleOCR models not found in ${modelsDir.absolutePath} — " +
                "push them per PaddleOcrComparisonTest kdoc. Running ML Kit baseline only.")
        }

        val mobile = if (haveModels) PaddleOcrSession.create(
            File(modelsDir, "det_mobile.mnn").absolutePath,
            File(modelsDir, "rec_mobile.mnn").absolutePath,
            keys.absolutePath,
        ) else null
        val server = if (haveModels) PaddleOcrSession.create(
            File(modelsDir, "det_server.mnn").absolutePath,
            File(modelsDir, "rec_server.mnn").absolutePath,
            keys.absolutePath,
        ) else null

        Log.i(REPORT, "===== BEGIN PADDLE SPIKE REPORT cases=${cases.size} models=$haveModels =====")
        try {
            for (case in cases) {
                val bmp = loadBitmap("$ASSET_DIR/${case.imageAsset}")
                try {
                    runMlKitBaseline(case, bmp)
                    if (mobile != null) {
                        runFull(case, bmp, "pp_full_mobile", mobile)
                        runHybrid(case, bmp, "hybrid_mobile", mobile)
                    }
                    if (server != null) {
                        runFull(case, bmp, "pp_full_server", server)
                        runHybrid(case, bmp, "hybrid_server", server)
                    }
                } catch (e: Exception) {
                    Log.e(REPORT, "case ${case.id}: ${e.javaClass.simpleName}: ${e.message}", e)
                } finally {
                    bmp.recycle()
                }
            }
        } finally {
            mobile?.close(); server?.close()
        }
        Log.i(REPORT, "===== END PADDLE SPIKE REPORT =====")
    }

    // ── config runners ───────────────────────────────────────────────────────

    private fun runMlKitBaseline(case: GoldenCase, bmp: Bitmap) {
        val t0 = System.nanoTime()
        val vt = mlKitProcess(bmp)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val lines = mlKitLines(vt)
        val text = lines.joinToString(" ") { it.text }
        emitRow(case, "baseline", text, detMs = ms, recMs = 0)
    }

    private fun runFull(case: GoldenCase, bmp: Bitmap, config: String, session: PaddleOcrSession) {
        val r = session.detectAndRecognize(bmp)
        emitRow(case, config, r.fullText, detMs = r.detMs, recMs = r.recMs)
    }

    private fun runHybrid(case: GoldenCase, bmp: Bitmap, config: String, session: PaddleOcrSession) {
        val vt = mlKitProcess(bmp)
        val lines = mlKitLines(vt)
        val boxes = lines.map { it.boundingBox ?: Rect() }
        val t0 = System.nanoTime()
        val crops = session.recognizeCrops(bmp, boxes)
        val recMs = (System.nanoTime() - t0) / 1_000_000

        // splice PP text onto ML Kit lines (reading order = ML Kit order)
        val ppText = crops.joinToString(" ") { it.text }
        emitRow(case, config, ppText, detMs = 0, recMs = recMs)

        // symbol-box preservation outcome per line
        var a = 0; var b = 0; var c = 0
        for (i in lines.indices) {
            val ml = OcrMetrics.normalize(lines[i].text).replace(" ", "")
            val pp = OcrMetrics.normalize(crops.getOrNull(i)?.text ?: "").replace(" ", "")
            when {
                pp == ml -> a++
                pp.length == ml.length -> b++
                else -> c++
            }
            // per-line pair for the subjective report (host pairs these)
            Log.i(REPORT, "LINE\t${case.id}\t$config\t$i\t${cls(ml, pp)}\t$ml\t$pp")
        }
        Log.i(REPORT, "BOX\t${case.id}\t$config\tA=$a\tB=$b\tC=$c\tlines=${lines.size}")
    }

    private fun cls(ml: String, pp: String): String =
        when { pp == ml -> "A"; pp.length == ml.length -> "B"; else -> "C" }

    // ── emit one metrics row ─────────────────────────────────────────────────

    private fun emitRow(case: GoldenCase, config: String, actual: String, detMs: Long, recMs: Long) {
        val cer = OcrMetrics.cer(actual, case.expected)
        val mo = OcrMetrics.meaningfulOpCounts(actual, case.expected)
        val total = detMs + recMs
        // ROW is tab-separated for trivial host parsing; text fields last.
        Log.i(REPORT, "ROW\t${case.id}\t$config\t" +
            "cer=${"%.4f".format(cer)}\trealSub=${mo.sub}\trealDel=${mo.del}\trealIns=${mo.ins}\t" +
            "detMs=$detMs\trecMs=$recMs\ttotalMs=$total\t" +
            "actual=${oneLine(actual)}\texpected=${oneLine(case.expected)}")
    }

    private fun oneLine(s: String) = s.replace("\t", " ").replace("\n", " ")

    // ── ML Kit helpers ───────────────────────────────────────────────────────

    private fun mlKitProcess(bmp: Bitmap): Text =
        Tasks.await(mlKit.process(InputImage.fromBitmap(bmp, 0)))

    /** All lines in reading order (top-to-bottom by box top). */
    private fun mlKitLines(vt: Text): List<Text.Line> =
        vt.textBlocks.flatMap { it.lines }
            .filter { it.boundingBox != null }
            .sortedBy { it.boundingBox!!.top }

    // ── golden loaders (mirror OcrGoldenSetTest) ─────────────────────────────

    data class GoldenCase(val id: String, val imageAsset: String, val expected: String)

    private fun loadGoldenCases(): List<GoldenCase> {
        val files = testCtx.assets.list(ASSET_DIR).orEmpty().toSet()
        return files.filter { it.endsWith(".png", true) }.sorted().mapNotNull { png ->
            val base = png.dropLast(4)
            val txt = "$base.txt"
            if (txt !in files) return@mapNotNull null
            val expected = testCtx.assets.open("$ASSET_DIR/$txt").bufferedReader().use { it.readText() }
            GoldenCase(base, png, expected)
        }
    }

    private fun loadBitmap(path: String): Bitmap {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        return testCtx.assets.open(path).use {
            BitmapFactory.decodeStream(it, null, opts) ?: error("decode failed: $path")
        }
    }

    companion object {
        private const val REPORT = "PaddleSpike"
        private const val ASSET_DIR = "ocr_golden"
    }
}
