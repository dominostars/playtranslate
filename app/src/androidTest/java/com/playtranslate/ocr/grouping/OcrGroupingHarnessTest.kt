package com.playtranslate.ocr.grouping

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.playtranslate.OcrManager
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.language.TextOrientation
import com.playtranslate.ocr.OcrPipeline
import com.playtranslate.ocr.core.GlyphScale
import com.playtranslate.ocr.core.LayoutAnalyzer
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
 * OCR grouping harness — the dumb emitter half of the grouping suite.
 *
 * Runs each seed screenshot through every applicable OCR engine ONCE, then
 * groups the same recognized lines N ways — one [LayoutAnalyzer.analyze] pass
 * per [GroupingVariants] catalog entry (recipes and strategies) — and dumps
 * the per-line group assignments as JSONL. It computes NO verdicts and always passes: anchoring, split/merge
 * judgment, and every threshold live host-side in
 * `scripts/build_grouping_report.py`, where they can be retuned against a saved
 * JSONL without re-running the device pass. (Same architecture as
 * [com.playtranslate.ocr.ab.OcrAbHarnessTest] — deliberate, see its kdoc.)
 *
 * ## Seeds
 *
 * Two sources, same format (`<name>.png` + `<name>.groups.txt`):
 *  - CORPUS: `ocr-grouping/assets/ocr_grouping/`, mounted into this
 *    sourceSet's assets by app/build.gradle.kts (absent checkout = empty).
 *  - STAGED: `<external-files>/ocr_grouping/staging/`, pushed by run_suite.sh
 *    from the host's drafts/ triage dir — pre-promotion material that never
 *    touches the corpus until the user moves it there deliberately.
 * The harness reads only the `#` directives from the expectations file
 * (`# lang:` required, `# surface: screen|import` optional); the stanzas are
 * consumed host-side only. A directives-only file is a draft request — the
 * seed still runs, and the report's `--emit-drafts` writes a stanza template
 * to curate.
 *
 * ## Columns
 *
 * Every seed attempts the four canonical engine columns — `meiki`, `mlkit`,
 * `paddle`, `paddle-fast` — filtered by what the seed's language actually
 * offers and what is installed; each not-run column gets an explicit `skip`
 * record (reason distinguishes "not offered for lang" from "pack not
 * installed"). After resolution the harness verifies the resolved backend's
 * selection token equals the requested one and skips on mismatch —
 * `resolveSelectedBackend` silently substitutes the ML Kit floor otherwise,
 * which would mislabel a whole column.
 *
 * Recognition goes through the production seam
 * ([OcrPipeline.recognizeNormalized]): production recipe
 * ([selectOcrRecipe] + dark-background sampling), production normalization.
 * Meiki/Paddle are byte-deterministic on-device, so they run once per seed;
 * ML Kit is not, so its column runs [MLKIT_REPS] reps and the report judges by
 * majority. All emitted boxes are divided back to ORIGINAL-bitmap coords (the
 * space the seed stanzas use); `scaleFactor` rides along in the case record.
 *
 * ## Run (Thor, arm64)
 * ```
 * ./gradlew :app:installDebug :app:installDebugAndroidTest
 *   # NOT connectedAndroidTest — it uninstalls the app, wiping installed OCR packs.
 * adb shell am instrument -w -e class com.playtranslate.ocr.grouping.OcrGroupingHarnessTest \
 *   com.playtranslate.test/androidx.test.runner.AndroidJUnitRunner
 * adb pull /sdcard/Android/data/com.playtranslate/files/ocr_grouping/results-<runId>.jsonl \
 *   ocr-grouping/runs/
 * python3 ocr-grouping/build_grouping_report.py --jsonl ocr-grouping/runs/results-<runId>.jsonl \
 *   --seeds ocr-grouping/assets/ocr_grouping --out grouping_report.html
 * ```
 * The runId is echoed to logcat (`OcrGrouping`) at start. The JSONL file is
 * authoritative; every line is mirrored to logcat tag `OcrGrouping` (throttled;
 * fallback recovery: `adb logcat -d -s OcrGrouping:I > grouping.log` — the
 * report script reads either). Grouping-decision reason traces land in logcat
 * via `logDecisions = true`; pass a full `adb logcat -d` dump to the report's
 * `--logcat` to attach them to failures.
 */
@RunWith(AndroidJUnit4::class)
class OcrGroupingHarnessTest {

    private val instr get() = InstrumentationRegistry.getInstrumentation()
    private val appCtx: Context get() = instr.targetContext   // app uid: packs, external files
    private val testCtx: Context get() = instr.context        // test APK: ocr_grouping assets

    @Test
    fun runSuite() {
        val runId = System.currentTimeMillis().toString()
        Log.i(TAG, "===== OCR GROUPING runId=$runId =====")
        // Instrumentation normally boots the real Application (which sets this),
        // but a null appContext would silently resolve every column to the ML Kit
        // floor — set it defensively so the failure mode can't exist.
        if (OcrModelManager.appContext == null) {
            OcrModelManager.appContext = appCtx.applicationContext
        }
        // Angle census instrument: every measured angle lands in the run's
        // logcat (dumped by run_suite.sh), so the corpus's known-upright seeds
        // give a ground-truth upright-noise distribution — the input that
        // prices the threshold-drop gate. Costs a string per detected line,
        // fine at harness scale.
        com.playtranslate.ocr.core.OrientedBoxGeometry.probeSink = { Log.d("AngleProbe", it) }
        val registry = OcrEngineRegistry()
        val sink = ResultSink(appCtx, runId)
        try {
            val seeds = loadSeeds(sink)
            if (seeds.isEmpty()) sink.skip("all", "no seeds in assets/$SEED_DIR or device staging")
            for (seed in seeds) runSeed(sink, registry, seed)
        } finally {
            sink.close()
            registry.closeAll()
        }
        Log.i(TAG, "===== OCR GROUPING runId=$runId done: ${sink.resultFile.absolutePath} =====")
    }

    // ── Per-seed driver ──────────────────────────────────────────────────────

    private fun runSeed(sink: ResultSink, registry: OcrEngineRegistry, seed: Seed) {
        val profile = SourceLanguageProfiles.forCode(seed.lang)
        if (profile == null) {
            sink.skip(seed.id, "unknown lang '${seed.lang}' in ${seed.id}.groups.txt")
            return
        }
        val id = profile.id
        val offered = OcrModelManager.availableBackends(appCtx, id)
        val prodToken = OcrModelManager.selectedBackend(appCtx, id)?.selectionToken
        for (token in CANONICAL_TOKENS) {
            val backend = offered.firstOrNull { it.selectionToken == token }
            if (backend == null) {
                sink.skip(seed.id, "$token: not offered for '${seed.lang}'")
                continue
            }
            if (!backend.isDownloaded(appCtx)) {
                sink.skip(seed.id, "$token: pack not installed")
                continue
            }
            val reps = if (token == "mlkit") MLKIT_REPS else 1
            for (rep in 0 until reps) {
                runColumn(sink, registry, seed, token, rep, prodToken)
            }
        }
    }

    /** One recognition pass, then one [LayoutAnalyzer.analyze] per grouping
     *  variant over the SAME regions — a verdict difference between variants is
     *  attributable purely to the flag, never to OCR jitter. Emits lines from
     *  the analyzer's output groups (group index per line), boxes divided back
     *  to original-bitmap coords. */
    private fun runColumn(
        sink: ResultSink, registry: OcrEngineRegistry, seed: Seed, token: String, rep: Int, prodToken: String?,
    ) {
        var bmp: Bitmap? = null
        try {
            val bitmap = loadBitmap(seed).also { bmp = it }
            val t0 = System.nanoTime()
            runBlocking {
                // Scoped bracket: withRecognition owns the preprocessed bitmap
                // and recycles it when this block exits — nothing to clean up here.
                OcrPipeline.withRecognition(
                    engineProvider = { registry.engineFor(seed.lang, token) },
                    bitmap = bitmap,
                    sourceLang = seed.lang,
                    screenshotWidth = bitmap.width,
                    recipe = selectOcrRecipe(seed.lang),
                    darkBackgroundProvider = { OcrManager.instance.sampleIsDarkBackground(bitmap) },
                ) { rec ->
                    val recognizeMs = (System.nanoTime() - t0) / 1_000_000
                    val ranToken = rec.backend?.selectionToken
                    if (ranToken != token) {
                        sink.skip(seed.id, "$token: resolved '$ranToken' for requested '$token' (fallback?)")
                        return@withRecognition
                    }
                    for (variant in GroupingVariants.catalog) {
                        val cfg = "$token/${variant.name}"
                        // Per-variant catch: an error here belongs to THIS cfg
                        // only, and a throwing strategy must not kill the
                        // variants after it — foreign-port catalog entries are
                        // exactly the code most likely to throw. (Both
                        // 2026-07-20 reviews: error scope must equal record
                        // scope, or cells go missing/misattributed.)
                        try {
                            val groups = LayoutAnalyzer.analyze(
                                regions = rec.regions,
                                sourceLang = seed.lang,
                                screenshotWidthInRegionSpace = bitmap.width * rec.scaleFactor,
                                logDecisions = true,
                                strategy = variant.strategy,
                                angleToleranceDeg = variant.angleToleranceDeg,
                            )
                            var n = 0
                            groups.forEachIndexed { gi, g ->
                                for (line in g.lines) {
                                    sink.region(
                                        seed.id, cfg, rep, n++, gi,
                                        scaled(line.box.bounds, rec.scaleFactor).run { intArrayOf(left, top, right, bottom) },
                                        vert = g.orientation == TextOrientation.VERTICAL,
                                        text = line.text, conf = line.confidence,
                                        // Char-tier scale quantiles, for the glyph-scale question
                                        // (scripts/glyph_scale_report.py). Null on engines whose
                                        // char cells are sliced from the line box — the report
                                        // must see the absence, not a restated line height.
                                        charQuantiles = GlyphScale.quantiles(line),
                                        // Angle undivided (rotation survives scaling); the
                                        // oriented dims are lengths and divide like the box.
                                        angleDeg = line.box.angleDeg,
                                        orientedW = scaledDim(line.box.orientedWidth, rec.scaleFactor),
                                        orientedH = scaledDim(line.box.orientedHeight, rec.scaleFactor),
                                    )
                                }
                            }
                            sink.case(
                                seed.id, seed.lang, cfg, rep, "ok",
                                lines = n, groups = groups.size, totalMs = recognizeMs,
                                prodToken = prodToken, surface = seed.surface, scaleFactor = rec.scaleFactor,
                            )
                        } catch (t: Throwable) {
                            Log.w(TAG, "${seed.id}/$cfg/r$rep failed", t)
                            sink.case(
                                seed.id, seed.lang, cfg, rep, "error", 0, 0,
                                prodToken = prodToken, surface = seed.surface,
                                reason = "${t.javaClass.simpleName}: ${t.message}",
                            )
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            // Load/recognition failure: the whole column is dead, so EVERY
            // catalog entry gets its error record — otherwise the untouched
            // variants' cells read as "not attempted" instead of ERROR.
            Log.w(TAG, "${seed.id}/$token/r$rep failed", t)
            for (variant in GroupingVariants.catalog) {
                sink.case(
                    seed.id, seed.lang, "$token/${variant.name}", rep, "error", 0, 0,
                    prodToken = prodToken, surface = seed.surface,
                    reason = "${t.javaClass.simpleName}: ${t.message}",
                )
            }
        } finally {
            bmp?.recycle()
        }
    }

    /** [com.playtranslate.OcrManager]'s scaleRect: divide engine-input coords
     *  back to original-bitmap coords (same truncation, so boxes match the
     *  seed drafts OcrSeedWriter emits from OcrResult). */
    private fun scaled(r: Rect, sf: Float): Rect =
        if (sf == 1f) r
        else Rect((r.left / sf).toInt(), (r.top / sf).toInt(), (r.right / sf).toInt(), (r.bottom / sf).toInt())

    /** [scaled]'s twin for oriented dims: same divide + truncation, scalar in. */
    private fun scaledDim(d: Float, sf: Float): Int = (d / sf).toInt()

    // ── Seeds ────────────────────────────────────────────────────────────────

    /** One seed: bundled corpus asset ([assetPath]) XOR device-staged file
     *  ([file]) — staged seeds are pushed by run_suite.sh from the host's
     *  drafts/ triage dir and run identically; they exist so the corpus only
     *  ever receives deliberately promoted material. */
    private class Seed(
        val id: String, val lang: String, val surface: String,
        val assetPath: String? = null, val file: File? = null,
    )

    /** Corpus PNGs paired with their `.groups.txt`, then device-staged pairs
     *  from `<external-files>/ocr_grouping/staging/` (the AB harness's staged-
     *  corpus pattern). A staged name shadowing a corpus seed is skipped with
     *  a record — silent shadowing would corrupt attribution. */
    private fun loadSeeds(sink: ResultSink): List<Seed> {
        val files = (testCtx.assets.list(SEED_DIR) ?: emptyArray()).toSet()
        val corpus = files.filter { it.endsWith(".png", ignoreCase = true) }.sorted().mapNotNull { png ->
            val name = png.substringBeforeLast('.')
            val expectations = "$name.groups.txt"
            if (expectations !in files) {
                Log.w(TAG, "seed $name has no $expectations — skipping")
                return@mapNotNull null
            }
            val directives = parseDirectives(
                testCtx.assets.open("$SEED_DIR/$expectations").bufferedReader().use { it.readText() })
            seedFromDirectives(name, directives, assetPath = "$SEED_DIR/$png", file = null)
        }
        val corpusIds = corpus.map { it.id }.toSet()
        val stagingDir = File(appCtx.getExternalFilesDir(OUT_DIR), STAGING_DIR)
        val staged = stagingDir.listFiles { f -> f.isFile && f.name.endsWith(".png", ignoreCase = true) }
            .orEmpty().sortedBy { it.name }.mapNotNull { png ->
                val name = png.name.substringBeforeLast('.')
                val exp = File(stagingDir, "$name.groups.txt")
                when {
                    !exp.isFile -> {
                        Log.w(TAG, "staged $name has no groups.txt — skipping"); null
                    }
                    name in corpusIds -> {
                        sink.skip(name, "staged seed shadows a corpus seed of the same name — rename or remove")
                        null
                    }
                    else -> seedFromDirectives(name, parseDirectives(exp.readText()), assetPath = null, file = png)
                }
            }
        return corpus + staged
    }

    private fun seedFromDirectives(
        name: String, directives: Map<String, String>, assetPath: String?, file: File?,
    ): Seed? {
        val lang = directives["lang"]
        if (lang == null) {
            Log.w(TAG, "seed $name lacks a '# lang:' directive — skipping")
            return null
        }
        return Seed(
            id = name, lang = lang,
            surface = directives["surface"] ?: "screen",
            assetPath = assetPath, file = file,
        )
    }

    /** Leading `# key: value` lines of an expectations file; stanzas ignored. */
    private fun parseDirectives(content: String): Map<String, String> =
        content.lineSequence()
            .filter { it.startsWith("#") }
            .mapNotNull { line ->
                val body = line.removePrefix("#").trim()
                val colon = body.indexOf(':')
                if (colon <= 0) null
                else body.take(colon).trim().lowercase() to body.substring(colon + 1).trim()
            }
            .toMap()

    /** inScaled=false + ARGB_8888 is load-bearing: default decode would apply
     *  density scaling and change every pixel the engines see (OcrGoldenSetTest). */
    private fun loadBitmap(seed: Seed): Bitmap {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bmp = when {
            seed.assetPath != null ->
                testCtx.assets.open(seed.assetPath).use { BitmapFactory.decodeStream(it, null, opts) }
            seed.file != null -> BitmapFactory.decodeFile(seed.file.absolutePath, opts)
            else -> null
        }
        return bmp ?: error("failed to decode ${seed.id}")
    }

    // ── Result sink ──────────────────────────────────────────────────────────

    /**
     * JSONL writer (the OcrAbHarnessTest ResultSink pattern): one JSON object
     * per line, regions first, then the `case` line as that (cfg, rep)'s
     * completion marker. File is authoritative; each line is mirrored to logcat
     * tag [TAG] with the repo's 3ms anti-chatty throttle. flush() per line +
     * fd.sync() per case bounds data loss if a native crash kills the process.
     */
    private class ResultSink(appCtx: Context, private val runId: String) {
        val resultFile: File = File(
            checkNotNull(appCtx.getExternalFilesDir(OUT_DIR)) { "external files dir unavailable" },
            "results-$runId.jsonl")
        private val fos = FileOutputStream(resultFile)
        private val writer = fos.bufferedWriter()

        init {
            emit(JSONObject()
                .put("type", "run").put("run", runId)
                .put("ts", System.currentTimeMillis())
                .put("suite", "grouping")
                .put("abi", android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?")
                // Host identity: arm64 emulator and arm64 Thor share an abi,
                // but their OCR output is NOT interchangeable (cross-device
                // byte-identity unestablished) — the report labels runs by this.
                .put("model", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"))
            sync()
        }

        fun skip(scope: String, reason: String) {
            emit(JSONObject().put("type", "skip").put("run", runId).put("case", scope).put("reason", reason))
            sync()
        }

        fun case(
            caseId: String, lang: String, cfg: String, rep: Int, status: String,
            lines: Int, groups: Int, totalMs: Long? = null, prodToken: String? = null,
            surface: String? = null, scaleFactor: Float? = null, reason: String? = null,
        ) {
            val o = JSONObject()
                .put("type", "case").put("run", runId).put("case", caseId)
                .put("lang", lang).put("cfg", cfg).put("rep", rep)
                .put("status", status).put("lines", lines).put("groups", groups)
            totalMs?.let { o.put("totalMs", it) }
            prodToken?.let { o.put("prodToken", it) }
            surface?.let { o.put("surface", it) }
            scaleFactor?.let { o.put("scaleFactor", it.toDouble()) }
            reason?.let { o.put("reason", it) }
            emit(o)
            sync()
        }

        /** [charQuantiles] is [com.playtranslate.ocr.core.GlyphScale.quantiles] —
         *  the line's char-box cross-axis extents at 25/50/75%, ABSENT when the
         *  engine's char cells are sliced from the line box (Paddle CTC,
         *  manga-ocr synthesis) rather than measured. Unlike `box`, these stay in
         *  ENGINE-INPUT space: the report only takes ratios between them, which
         *  are scale-invariant, and dividing 20–60px values by `scaleFactor`
         *  (in the case record, if absolute values are ever wanted) would cost
         *  more truncation than the 0.30/0.50 decision points can spare. */
        fun region(
            caseId: String, cfg: String, rep: Int, idx: Int, group: Int, box: IntArray,
            vert: Boolean, text: String, conf: Float, charQuantiles: IntArray? = null,
            angleDeg: Float = 0f, orientedW: Int = 0, orientedH: Int = 0,
        ) {
            val o = JSONObject()
                .put("type", "region").put("run", runId).put("case", caseId)
                .put("cfg", cfg).put("rep", rep).put("idx", idx).put("group", group)
                .put("box", JSONArray().apply { box.forEach { put(it) } })
                .put("vert", vert)
                .put("text", text)
            if (conf.isFinite() && conf >= 0f) o.put("conf", conf.toDouble())
            charQuantiles?.let { q -> o.put("cq", JSONArray().apply { q.forEach { put(it) } }) }
            // Slanted lines carry the angle trio; absent keys mean upright
            // (0-when-upright, mirroring the seed-row grammar).
            if (angleDeg != 0f) {
                o.put("ang", angleDeg.toDouble()).put("ow", orientedW).put("oh", orientedH)
            }
            emit(o)
        }

        private fun emit(o: JSONObject) {
            val line = o.toString()
            writer.write(line); writer.write("\n"); writer.flush()
            Log.i(TAG, line)
            Thread.sleep(3)   // stay under logd's chatty rate limit (OcrGoldenSetTest pattern)
        }

        private fun sync() = runCatching { fos.fd.sync() }

        fun close() {
            runCatching { writer.flush(); fos.fd.sync(); writer.close() }
        }
    }

    private companion object {
        const val TAG = "OcrGrouping"
        const val SEED_DIR = "ocr_grouping"
        const val OUT_DIR = "ocr_grouping"
        const val STAGING_DIR = "staging"
        const val MLKIT_REPS = 3
        /** The four engine columns, in stable report order. */
        val CANONICAL_TOKENS = listOf("meiki", "mlkit", "paddle", "paddle-fast")
    }
}
