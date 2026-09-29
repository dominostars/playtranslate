package com.playtranslate.ocr.cloud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.playtranslate.OcrManager
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.ocr.OcrPipeline
import com.playtranslate.ocr.core.LayoutAnalyzer
import com.playtranslate.ocr.core.LayoutGroup
import com.playtranslate.ocr.core.OcrBox
import com.playtranslate.ocr.core.RecognizedLine
import com.playtranslate.ocr.core.RecognizedRegion
import com.playtranslate.ocr.registry.OcrEngineRegistry
import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.ocr.registry.isDownloaded
import com.playtranslate.ocr.registry.selectionToken
import com.playtranslate.selectOcrRecipe
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Cloud parity dump: the on-device Paddle (accurate tier) recognition output and the production
 * grouping of it, per seed, as JSONL for the cloud prototype's parity checks
 * (playtranslate-cloud, Phase 2 line comparison and Phase 3 grouping replay).
 *
 * Seeds: the golden set (the ocr_golden PNGs, Japanese) and the grouping corpus
 * (ocr_grouping PNGs with a groups.txt carrying a lang directive). One column only: `paddle`.
 * A seed whose language has no installed Paddle pack gets a `skip` record.
 *
 * Records:
 *  - `input`: the normalized [RecognizedRegion]s exactly as [LayoutAnalyzer.analyze] receives them
 *    (region box, orientation, confidence, origin, each line with its box and char boxes), in
 *    engine-input coordinates with `scaleFactor` (1 for Paddle) so they replay verbatim.
 *  - `groups`: the production [LayoutAnalyzer.analyze] result over those regions (defaults: no
 *    strategy override, default angle tolerance, no document pitch prior), each group's lines
 *    identified by text and box.
 *  - `case`: completion marker with the recognition time.
 *
 * Run (Thor): installDebug + installDebugAndroidTest, then
 *   adb shell am instrument -w -e class com.playtranslate.ocr.cloud.CloudParityDumpTest \
 *     [-e langs ja,en] [-e cases name1,name2] com.playtranslate.test/androidx.test.runner.AndroidJUnitRunner
 *   adb pull /sdcard/Android/data/com.playtranslate/files/cloud_parity/dump-<runId>.jsonl
 * Never connectedAndroidTest (it uninstalls the app and its packs).
 */
@RunWith(AndroidJUnit4::class)
class CloudParityDumpTest {

    private val instr get() = InstrumentationRegistry.getInstrumentation()
    private val appCtx: Context get() = instr.targetContext
    private val testCtx: Context get() = instr.context

    private class Seed(val id: String, val lang: String, val assetPath: String, val source: String)

    @Test
    fun dump() {
        val runId = System.currentTimeMillis().toString()
        val args = InstrumentationRegistry.getArguments()
        val langFilter = args.getString("langs")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        val caseFilter = args.getString("cases")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        Log.i(TAG, "===== CLOUD PARITY DUMP runId=$runId =====")
        if (OcrModelManager.appContext == null) OcrModelManager.appContext = appCtx.applicationContext
        val registry = OcrEngineRegistry()
        val outDir = checkNotNull(appCtx.getExternalFilesDir(OUT_DIR))
        val file = File(outDir, "dump-$runId.jsonl")
        val fos = FileOutputStream(file)
        val w = fos.bufferedWriter()
        fun emit(o: JSONObject) { w.write(o.toString()); w.write("\n"); w.flush() }
        try {
            emit(JSONObject().put("type", "run").put("run", runId).put("ts", System.currentTimeMillis())
                .put("suite", "cloud_parity").put("model", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"))
            val seeds = loadSeeds().filter { (langFilter == null || it.lang in langFilter) && (caseFilter == null || it.id in caseFilter) }
            Log.i(TAG, "${seeds.size} seeds")
            for (seed in seeds) {
                val profile = SourceLanguageProfiles.forCode(seed.lang)
                if (profile == null) { emit(skip(runId, seed, "unknown lang")); continue }
                val backend = OcrModelManager.availableBackends(appCtx, profile.id).firstOrNull { it.selectionToken == TOKEN }
                if (backend == null) { emit(skip(runId, seed, "paddle not offered for ${seed.lang}")); continue }
                if (!backend.isDownloaded(appCtx)) { emit(skip(runId, seed, "paddle pack not installed for ${seed.lang}")); continue }
                var bmp: Bitmap? = null
                try {
                    val bitmap = loadBitmap(seed).also { bmp = it }
                    val t0 = System.nanoTime()
                    runBlocking {
                        OcrPipeline.withRecognition(
                            engineProvider = { registry.engineFor(seed.lang, TOKEN) },
                            bitmap = bitmap,
                            sourceLang = seed.lang,
                            screenshotWidth = bitmap.width,
                            recipe = selectOcrRecipe(seed.lang),
                            darkBackgroundProvider = { OcrManager.instance.sampleIsDarkBackground(bitmap) },
                        ) { rec ->
                            val recognizeMs = (System.nanoTime() - t0) / 1_000_000
                            val ranToken = rec.backend?.selectionToken
                            if (ranToken != TOKEN) {
                                emit(skip(runId, seed, "resolved '$ranToken' for requested '$TOKEN'"))
                                return@withRecognition
                            }
                            emit(JSONObject().put("type", "input").put("run", runId).put("case", seed.id)
                                .put("lang", seed.lang).put("source", seed.source)
                                .put("w", bitmap.width).put("h", bitmap.height)
                                .put("scaleFactor", rec.scaleFactor.toDouble())
                                .put("regions", JSONArray().apply { rec.regions.forEach { put(region(it)) } }))
                            val groups = LayoutAnalyzer.analyze(
                                regions = rec.regions,
                                sourceLang = seed.lang,
                                screenshotWidthInRegionSpace = bitmap.width * rec.scaleFactor,
                                logDecisions = false,
                            )
                            emit(JSONObject().put("type", "groups").put("run", runId).put("case", seed.id)
                                .put("lang", seed.lang)
                                .put("groups", JSONArray().apply { groups.forEach { put(group(it)) } }))
                            emit(JSONObject().put("type", "case").put("run", runId).put("case", seed.id)
                                .put("lang", seed.lang).put("status", "ok").put("regions", rec.regions.size)
                                .put("groups", groups.size).put("recognizeMs", recognizeMs))
                            Log.i(TAG, "${seed.id} ${seed.lang}: ${rec.regions.size} regions, ${groups.size} groups, ${recognizeMs}ms")
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "${seed.id} failed", t)
                    emit(JSONObject().put("type", "case").put("run", runId).put("case", seed.id).put("lang", seed.lang)
                        .put("status", "error").put("reason", "${t.javaClass.simpleName}: ${t.message}"))
                } finally {
                    bmp?.recycle()
                }
                runCatching { fos.fd.sync() }
            }
        } finally {
            runCatching { w.flush(); fos.fd.sync(); w.close() }
            registry.closeAll()
        }
        Log.i(TAG, "===== CLOUD PARITY DUMP runId=$runId done: ${file.absolutePath} =====")
    }

    private fun skip(runId: String, seed: Seed, reason: String) =
        JSONObject().put("type", "skip").put("run", runId).put("case", seed.id).put("lang", seed.lang).put("reason", reason)

    private fun box(b: OcrBox): JSONObject = JSONObject()
        .put("b", JSONArray().put(b.bounds.left).put(b.bounds.top).put(b.bounds.right).put(b.bounds.bottom))
        .put("ow", b.orientedWidth.toDouble()).put("oh", b.orientedHeight.toDouble())
        .put("ang", b.angleDeg.toDouble()).put("unm", b.angleUnmeasured)

    private fun line(l: RecognizedLine): JSONObject = JSONObject()
        .put("text", l.text).put("box", box(l.box)).put("orientation", l.orientation.name)
        .put("conf", l.confidence.toDouble())
        .put("elements", JSONArray().apply { l.elements.forEach { put(JSONObject().put("text", it.text).put("box", box(it.box))) } })
        .put("chars", JSONArray().apply { l.chars.forEach { put(JSONObject().put("ch", it.text).put("box", box(it.box)).put("off", it.charOffset)) } })

    private fun region(r: RecognizedRegion): JSONObject = JSONObject()
        .put("text", r.text).put("box", box(r.box)).put("orientation", r.orientation.name)
        .put("conf", r.confidence.toDouble()).put("origin", r.origin.name)
        .put("languageUndetermined", r.languageUndetermined)
        .put("lines", JSONArray().apply { r.lines.forEach { put(line(it)) } })

    private fun group(g: LayoutGroup): JSONObject = JSONObject()
        .put("text", g.text)
        .put("bounds", JSONArray().put(g.bounds.left).put(g.bounds.top).put(g.bounds.right).put(g.bounds.bottom))
        .put("drawBounds", JSONArray().put(g.drawBounds.left).put(g.drawBounds.top).put(g.drawBounds.right).put(g.drawBounds.bottom))
        .put("orientation", g.orientation.name).put("alignment", g.alignment.name)
        .put("ang", g.angleDeg.toDouble()).put("ow", g.orientedWidth.toDouble()).put("oh", g.orientedHeight.toDouble())
        .put("lines", JSONArray().apply { g.lines.forEach { put(line(it)) } })

    private fun loadSeeds(): List<Seed> {
        val out = ArrayList<Seed>()
        val golden = (testCtx.assets.list(GOLDEN_DIR) ?: emptyArray()).filter { it.endsWith(".png", true) }.sorted()
        for (png in golden) out += Seed(png.substringBeforeLast('.'), "ja", "$GOLDEN_DIR/$png", "golden")
        val files = (testCtx.assets.list(SEED_DIR) ?: emptyArray()).toSet()
        for (png in files.filter { it.endsWith(".png", true) }.sorted()) {
            val name = png.substringBeforeLast('.')
            val exp = "$name.groups.txt"
            if (exp !in files) continue
            val directives = testCtx.assets.open("$SEED_DIR/$exp").bufferedReader().use { it.readText() }
                .lineSequence().filter { it.startsWith("#") }.mapNotNull { line ->
                    val body = line.removePrefix("#").trim(); val colon = body.indexOf(':')
                    if (colon <= 0) null else body.take(colon).trim().lowercase() to body.substring(colon + 1).trim()
                }.toMap()
            val lang = directives["lang"] ?: continue
            out += Seed(name, lang, "$SEED_DIR/$png", "corpus")
        }
        return out
    }

    private fun loadBitmap(seed: Seed): Bitmap {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false }
        return testCtx.assets.open(seed.assetPath).use { BitmapFactory.decodeStream(it, null, opts) } ?: error("failed to decode ${seed.id}")
    }

    private companion object {
        const val TAG = "CloudParity"
        const val TOKEN = "paddle"
        const val GOLDEN_DIR = "ocr_golden"
        const val SEED_DIR = "ocr_grouping"
        const val OUT_DIR = "cloud_parity"
    }
}
