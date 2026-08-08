package com.playtranslate.ocr.ab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.playtranslate.OcrManager
import com.playtranslate.Prefs
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.language.TextOrientation
import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.ocr.registry.isDownloaded
import com.playtranslate.ocr.registry.selectionToken
import com.playtranslate.ocr.composites.DetectThenRecognize
import com.playtranslate.ocr.core.OcrEngine
import com.playtranslate.ocr.core.OcrImage
import com.playtranslate.ocr.meiki.MeikiDetector
import com.playtranslate.ocr.meiki.MeikiRecognizer
import com.playtranslate.ocr.meiki.MeikiSession
import com.playtranslate.ocr.paddle.PaddleDetector
import com.playtranslate.ocr.paddle.PaddleOcrSession
import com.playtranslate.ocr.paddle.PaddleRecognizer
import com.playtranslate.ocr.registry.OcrPackModelHelper
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * OCR engine-config A/B harness — the diff-TRIAGE instrument, not a scorer.
 *
 * Runs the same screenshots through pairs/sweeps of engine configurations and
 * dumps RAW per-region output (box + text) as JSONL. It computes NO metrics,
 * asserts NOTHING about OCR content, and always passes: all matching, pile
 * sorting, and thresholds live host-side in `scripts/build_ocr_ab_report.py`,
 * where they can be retuned without re-running the device pass, and where the
 * output is an HTML report of cropped diffs for a HUMAN verdict. (Deliberate:
 * aggregate metrics over full screens have misranked configs before — reading
 * -order artifacts, noise-region deltas. See the report script header.)
 *
 * Experiments (select with `-e experiment <name>`; omit to run all):
 *  - `fp16-meiki`    — Meiki (JA): p0 (fp32, baseline) vs p2 (fp16) vs p2d
 *    (mixed: fp16 det + fp32 rec).
 *  - `fp16-paddle`   — PaddleOCR, same three configs, per installed rec pack.
 *  - `detcap-paddle` — PaddleOCR DETECTOR ONLY at detLimitSide 1920 (baseline,
 *    current prod) / 1280 / 960 — the det-recall sweep for the DET_LIMIT_SIDE
 *    revert decision. Language-agnostic (the det model is shared), so every
 *    staged case runs regardless of language.
 *  - `pack-meiki`    — Meiki rec-canvas utilization: `nopack` (baseline, the
 *    production composite — one crop per fixed canvas) vs `pack`
 *    ([MeikiSession.recognizePacked] — multiple crops per canvas). Both fp32;
 *    det is deterministic, so both configs emit identical box sets and every
 *    diff is a pure packing effect.
 *  - `mlkit-vs-fast` — the floor vs the Fast tier, measured PIPELINE-level
 *    through the production [OcrManager] by flipping the language's selection
 *    token (restored afterwards): the ML Kit arm carries its true in-app cost
 *    (preprocess recipe + upscale + grouping), the fast arm runs Paddle
 *    fp16+det960 exactly as the `paddle-fast` token resolves. Each case
 *    verifies via [OcrManager.OcrResult.engineBackend] that the intended
 *    engine actually ran. Regions here are post-grouping paragraphs, so the
 *    report's diff piles are coarse; the timing table is the deliverable.
 *    Skipped for languages lacking an ML Kit floor or an installed pack.
 *
 * Cases = the bundled golden JA PNGs (`androidTest/assets/ocr_golden`; the
 * `.txt` ground truth is deliberately NOT read) + optional staged corpora — PNGs
 * under `<external-files>/ocr_ab/cases/<lang>/` (subdir name = source-language
 * code; collect via the debug "Save OCR captures as seeds" toggle, curate, push).
 *
 * Models come from the INSTALLED packs (instrumentation runs in the app uid):
 * meiki-ja from its models dir (skips with a `skip` record if absent);
 * paddle-rec-unified is APK-bundled and materialized on demand; other Paddle
 * rec packs are included iff installed. Configs run strictly sequentially with
 * one live MNN session at a time (sessions are not thread-safe, and fp32+fp16
 * must not be co-resident for memory).
 *
 * ## Run (Thor, arm64; charge first — battery throttling skews timings)
 * ```
 * ./gradlew :app:installDebug :app:installDebugAndroidTest
 *   # NOT connectedAndroidTest — it uninstalls the app, wiping installed OCR
 *   # packs, staged cases, and results.
 * adb shell am instrument -w -e experiment fp16-meiki \
 *   -e class com.playtranslate.ocr.ab.OcrAbHarnessTest \
 *   com.playtranslate.test/androidx.test.runner.AndroidJUnitRunner
 * adb pull /sdcard/Android/data/com.playtranslate/files/ocr_ab ./ocr_ab_out
 * python3 scripts/build_ocr_ab_report.py --jsonl ./ocr_ab_out/results-<runId>.jsonl \
 *   --golden app/src/androidTest/assets/ocr_golden --corpus ./ocr_ab_out/cases \
 *   --out ocr_ab_report.html
 * ```
 * The runId is echoed to logcat (`OcrAb`) at start. Results are written to
 * `<external-files>/ocr_ab/results-<runId>.jsonl` (authoritative) and mirrored
 * to logcat tag `OcrAb` (throttled; fallback recovery:
 * `adb logcat -d -s OcrAb:I > ocr_ab.log` — the report script reads either).
 */
@RunWith(AndroidJUnit4::class)
class OcrAbHarnessTest {

    private val instr get() = InstrumentationRegistry.getInstrumentation()
    private val appCtx: Context get() = instr.targetContext   // app uid: packs, external files
    private val testCtx: Context get() = instr.context        // test APK: ocr_golden assets

    @Test
    fun runExperiments() {
        val experiment = InstrumentationRegistry.getArguments().getString("experiment")
        val runId = System.currentTimeMillis().toString()
        Log.i(TAG, "===== OCR AB runId=$runId experiment=${experiment ?: "all"} =====")
        val sink = ResultSink(appCtx, runId, experiment ?: "all")
        try {
            when (experiment) {
                "fp16-meiki" -> runFp16Meiki(sink)
                "fp16-paddle" -> runFp16Paddle(sink)
                "detcap-paddle" -> runDetcapPaddle(sink)
                "pack-meiki" -> runPackMeiki(sink)
                "mlkit-vs-fast" -> runMlkitVsFast(sink)
                null -> {
                    runFp16Meiki(sink); runFp16Paddle(sink); runDetcapPaddle(sink)
                    runPackMeiki(sink); runMlkitVsFast(sink)
                }
                else -> {
                    Log.w(TAG, "unknown experiment '$experiment'")
                    sink.skip("all", "unknown experiment '$experiment'")
                }
            }
        } finally {
            sink.close()
        }
        Log.i(TAG, "===== OCR AB runId=$runId done: ${sink.resultFile.absolutePath} =====")
    }

    // ── Experiments ──────────────────────────────────────────────────────────

    private fun runFp16Meiki(sink: ResultSink) {
        val exp = "fp16-meiki"
        val dir = OcrPackModelHelper(MEIKI_PACK).file(appCtx)
        val det = File(dir, "det.mnn")
        val recH = File(dir, "rec_horizontal.mnn")
        val recV = File(dir, "rec_vertical.mnn")
        if (!det.exists() || !recH.exists() || !recV.exists()) {
            sink.skip(exp, "$MEIKI_PACK pack not installed in ${dir.absolutePath}")
            return
        }
        val cases = goldenJaCases() + stagedCases().filter { it.lang == "ja" }
        for (cfg in FP16_CONFIGS) {                    // baseline p0 first
            val session = MeikiSession.create(
                det.absolutePath, recH.absolutePath, recV.absolutePath,
                precision = cfg.det, recPrecision = cfg.rec)
            val engine = DetectThenRecognize(MeikiDetector(session), MeikiRecognizer(session))
            try {
                for (c in cases) runComposite(sink, exp, cfg.label, c, engine)
            } finally {
                engine.close(); session.close()
            }
        }
    }

    private fun runFp16Paddle(sink: ResultSink) {
        val exp = "fp16-paddle"
        val det = bundledDetFile() ?: run { sink.skip(exp, "bundled paddle det asset unavailable"); return }
        val byPack = (goldenJaCases() + stagedCases()).groupBy { recPackForLang(it.lang) }.toSortedMap()
        for ((packKey, cases) in byPack) {
            val paths = recPackFiles(packKey)
            if (paths == null) { sink.skip(exp, "$packKey not installed"); continue }
            for (cfg in FP16_CONFIGS) {                // baseline p0 first
                val session = PaddleOcrSession.create(
                    det.absolutePath, paths.first.absolutePath, paths.second.absolutePath,
                    precision = cfg.det, recPrecision = cfg.rec)
                val engine = DetectThenRecognize(PaddleDetector(session), PaddleRecognizer(session))
                try {
                    for (c in cases) runComposite(sink, exp, cfg.label, c, engine)
                } finally {
                    engine.close(); session.close()
                }
            }
        }
    }

    private fun runPackMeiki(sink: ResultSink) {
        val exp = "pack-meiki"
        val dir = OcrPackModelHelper(MEIKI_PACK).file(appCtx)
        val det = File(dir, "det.mnn")
        val recH = File(dir, "rec_horizontal.mnn")
        val recV = File(dir, "rec_vertical.mnn")
        if (!det.exists() || !recH.exists() || !recV.exists()) {
            sink.skip(exp, "$MEIKI_PACK pack not installed in ${dir.absolutePath}")
            return
        }
        val cases = goldenJaCases() + stagedCases().filter { it.lang == "ja" }
        // nopack baseline: the untouched production composite (fp32).
        run {
            val session = MeikiSession.create(det.absolutePath, recH.absolutePath, recV.absolutePath)
            val engine = DetectThenRecognize(MeikiDetector(session), MeikiRecognizer(session))
            try {
                for (c in cases) runComposite(sink, exp, "nopack", c, engine)
            } finally {
                engine.close(); session.close()
            }
        }
        // pack: same det + crop geometry, rec via recognizePacked.
        run {
            val session = MeikiSession.create(det.absolutePath, recH.absolutePath, recV.absolutePath)
            try {
                for (c in cases) runPacked(sink, exp, "pack", c, session)
            } finally {
                session.close()
            }
        }
    }

    /** The pack config's driver: replicates the composite's per-region crop
     *  geometry (MeikiDetector's orientation split + MeikiRecognizer's clamp and
     *  RGBA→BGR conversion) but recognizes each orientation group through
     *  [MeikiSession.recognizePacked]. Emits regions in det order with the det
     *  rect as the box — byte-comparable to the nopack baseline. */
    private fun runPacked(sink: ResultSink, exp: String, cfg: String, c: CaseRef, session: MeikiSession) {
        var bmp: Bitmap? = null
        var bgr: Mat? = null
        val subs = ArrayList<Mat>()
        try {
            bmp = loadBitmap(c)
            val t0 = System.nanoTime()
            val dets = session.detect(bmp)
            val rgba = Mat().also { Utils.bitmapToMat(bmp, it) }
            bgr = Mat().also { Imgproc.cvtColor(rgba, it, Imgproc.COLOR_RGBA2BGR) }
            rgba.release()

            class Item(val rect: android.graphics.Rect, val vertical: Boolean, val sub: Mat?)
            val bw = bmp.width; val bh = bmp.height
            val items = dets.map { db ->
                val x1 = db.rect.left.coerceIn(0, bw - 1)
                val y1 = db.rect.top.coerceIn(0, bh - 1)
                val x2 = db.rect.right.coerceIn(x1 + 1, bw)
                val y2 = db.rect.bottom.coerceIn(y1 + 1, bh)
                val sub = if (x2 - x1 < 2 || y2 - y1 < 2) null
                else bgr!!.submat(y1, y2, x1, x2).also { subs += it }
                // Same aspect test MeikiDetector uses for the orientation label.
                Item(db.rect, db.rect.height() > db.rect.width() * 1.3, sub)
            }
            val results = arrayOfNulls<MeikiSession.RecResult>(items.size)
            for (vertical in booleanArrayOf(false, true)) {
                val idxs = items.indices.filter { items[it].vertical == vertical && items[it].sub != null }
                if (idxs.isEmpty()) continue
                val recs = session.recognizePacked(idxs.map { items[it].sub!! }, vertical)
                idxs.forEachIndexed { k, i -> results[i] = recs[k] }
            }
            val totalMs = (System.nanoTime() - t0) / 1_000_000
            var n = 0
            items.forEachIndexed { i, item ->
                val res = results[i] ?: return@forEachIndexed
                if (res.text.isBlank()) return@forEachIndexed
                sink.region(exp, c.id, cfg, n++,
                    item.rect.run { intArrayOf(left, top, right, bottom) },
                    vert = item.vertical, text = res.text, conf = res.confidence)
            }
            sink.case(exp, c.id, c.lang, cfg, "ok", n, totalMs = totalMs)
        } catch (t: Throwable) {
            Log.w(TAG, "$exp/$cfg/${c.id} failed", t)
            sink.case(exp, c.id, c.lang, cfg, "error", 0,
                reason = "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            subs.forEach { runCatching { it.release() } }
            bgr?.release()
            bmp?.recycle()
        }
    }

    private fun runDetcapPaddle(sink: ResultSink) {
        val exp = "detcap-paddle"
        val det = bundledDetFile() ?: run { sink.skip(exp, "bundled paddle det asset unavailable"); return }
        // The rec model is loaded but never run (create() loads both) — simpler
        // than a det-only seam; unified is bundled so it's always present.
        val paths = recPackFiles(UNIFIED_PACK) ?: run { sink.skip(exp, "$UNIFIED_PACK unavailable"); return }
        val cases = goldenJaCases() + stagedCases()
        for (cap in DETCAP_SWEEP) {                    // baseline det1920 first
            val session = PaddleOcrSession.create(
                det.absolutePath, paths.first.absolutePath, paths.second.absolutePath,
                precision = 0, detLimitSide = cap)
            val detector = PaddleDetector(session)
            try {
                for (c in cases) runDetect(sink, exp, "det$cap", c, detector)
            } finally {
                session.close()
            }
        }
    }

    // ── Per-case drivers ─────────────────────────────────────────────────────

    /** Full det→rec pipeline through the production composite; emits recognized
     *  regions (text + box) then the case line as a completion marker. */
    private fun runComposite(sink: ResultSink, exp: String, cfg: String, c: CaseRef, engine: OcrEngine) {
        var bmp: Bitmap? = null
        try {
            bmp = loadBitmap(c)
            val t0 = System.nanoTime()
            val regions = runBlocking { engine.recognize(OcrImage(bmp, c.lang, bmp.width)) }
            val totalMs = (System.nanoTime() - t0) / 1_000_000
            regions.forEachIndexed { i, r ->
                sink.region(exp, c.id, cfg, i, r.box.bounds.run { intArrayOf(left, top, right, bottom) },
                    vert = r.orientation == TextOrientation.VERTICAL, text = r.text, conf = r.confidence)
            }
            sink.case(exp, c.id, c.lang, cfg, "ok", regions.size, totalMs = totalMs)
        } catch (t: Throwable) {
            Log.w(TAG, "$exp/$cfg/${c.id} failed", t)
            sink.case(exp, c.id, c.lang, cfg, "error", 0,
                reason = "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            bmp?.recycle()
        }
    }

    /** Detector-only run (detcap sweep); emits detected boxes with quads, no text. */
    private fun runDetect(sink: ResultSink, exp: String, cfg: String, c: CaseRef, detector: PaddleDetector) {
        var bmp: Bitmap? = null
        try {
            bmp = loadBitmap(c)
            val t0 = System.nanoTime()
            val regions = runBlocking { detector.detect(OcrImage(bmp, c.lang, bmp.width)) }
            val detMs = (System.nanoTime() - t0) / 1_000_000
            regions.forEachIndexed { i, r ->
                sink.region(exp, c.id, cfg, i, r.box.bounds.run { intArrayOf(left, top, right, bottom) },
                    vert = r.orientation == TextOrientation.VERTICAL,
                    quad = r.quad?.map { intArrayOf(it.x.roundToInt(), it.y.roundToInt()) })
            }
            sink.case(exp, c.id, c.lang, cfg, "ok", regions.size, detMs = detMs)
        } catch (t: Throwable) {
            Log.w(TAG, "$exp/$cfg/${c.id} failed", t)
            sink.case(exp, c.id, c.lang, cfg, "error", 0,
                reason = "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            bmp?.recycle()
        }
    }

    private fun runMlkitVsFast(sink: ResultSink) {
        val exp = "mlkit-vs-fast"
        val prefs = Prefs(appCtx)
        val byLang = (goldenJaCases() + stagedCases()).groupBy { it.lang }.toSortedMap()
        for ((lang, cases) in byLang) {
            val profile = SourceLanguageProfiles.forCode(lang)
            if (profile == null) { sink.skip(exp, "unknown staged lang '$lang'"); continue }
            val id = profile.id
            val backends = OcrModelManager.availableBackends(appCtx, id)
            val fastBackend = backends.firstOrNull { it.selectionToken == "paddle-fast" }
            if (backends.none { it.selectionToken == "mlkit" } ||
                fastBackend == null || !fastBackend.isDownloaded(appCtx)) {
                sink.skip(exp, "$lang: needs an ML Kit floor AND an installed Paddle pack")
                continue
            }
            val original = prefs.ocrBackendToken(id)
            try {
                for (token in listOf("mlkit", "paddle-fast")) {   // baseline mlkit first
                    prefs.setOcrBackendToken(id, token)
                    val cfg = if (token == "mlkit") "mlkit" else "fast"
                    for (c in cases) runManaged(sink, exp, cfg, token, c)
                }
            } finally {
                if (original == null) prefs.clearOcrBackendToken(id)
                else prefs.setOcrBackendToken(id, original)
            }
        }
        // Drop the engines/sessions this experiment resolved through the
        // production caches (registry + bridges); quiescent here by construction.
        OcrManager.instance.releaseAll()
    }

    /** One case through the PRODUCTION pipeline (OcrManager.recognise with the
     *  language's default recipe). Emits post-grouping paragraphs as regions and
     *  hard-fails the case if the resolved engine isn't the one the flipped
     *  token was meant to select (a silent fallback would corrupt the timing). */
    private fun runManaged(sink: ResultSink, exp: String, cfg: String, expectedToken: String, c: CaseRef) {
        var bmp: Bitmap? = null
        try {
            bmp = loadBitmap(c)
            val t0 = System.nanoTime()
            val result = runBlocking {
                OcrManager.instance.recognise(bitmap = bmp, sourceLang = c.lang, screenshotWidth = bmp.width)
            }
            val totalMs = (System.nanoTime() - t0) / 1_000_000
            val ranToken = result?.engineBackend?.selectionToken
            if (result == null || ranToken != expectedToken) {
                sink.case(exp, c.id, c.lang, cfg, "error", 0,
                    reason = "resolved engine '$ranToken' != expected '$expectedToken' (fallback?)")
                return
            }
            result.groups.forEachIndexed { i, g ->
                sink.region(exp, c.id, cfg, i, g.bounds.run { intArrayOf(left, top, right, bottom) },
                    vert = g.orientation == TextOrientation.VERTICAL, text = g.text)
            }
            sink.case(exp, c.id, c.lang, cfg, "ok", result.groups.size, totalMs = totalMs)
        } catch (t: Throwable) {
            Log.w(TAG, "$exp/$cfg/${c.id} failed", t)
            sink.case(exp, c.id, c.lang, cfg, "error", 0,
                reason = "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            bmp?.recycle()
        }
    }

    // ── Model resolution ─────────────────────────────────────────────────────

    /** rec.mnn + keys.txt for [packKey], materializing a bundled pack from APK
     *  assets on demand; null if the pack isn't installed/complete. */
    private fun recPackFiles(packKey: String): Pair<File, File>? {
        val helper = OcrPackModelHelper(packKey)
        if (!helper.isInstalled(appCtx)) return null
        helper.ensureBundledMaterialized(appCtx)   // no-op for downloaded packs
        val dir = helper.file(appCtx)
        val rec = File(dir, "rec.mnn")
        val keys = File(dir, "keys.txt")
        return if (rec.exists() && keys.exists()) rec to keys else null
    }

    /** The APK-bundled shared Paddle detector, copied to cache once per run
     *  (PaddleOcrBridge's materializer is private; MNN loads from a real path). */
    private fun bundledDetFile(): File? = try {
        val out = File(appCtx.cacheDir, "ocr_ab/paddle_det.mnn").apply { parentFile?.mkdirs() }
        if (!out.exists() || out.length() == 0L) {
            appCtx.assets.open("ocr/paddle_det.mnn").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        out
    } catch (t: Throwable) {
        Log.w(TAG, "bundled det copy failed", t); null
    }

    /** Which Paddle recognizer pack serves a staged-corpus language code. */
    private fun recPackForLang(lang: String): String = when {
        lang == "ko" -> "paddle-rec-korean"
        lang == "th" -> "paddle-rec-thai"
        lang in setOf("ru", "uk", "bg", "sr", "mk") -> "paddle-rec-cyrillic"
        lang in setOf("ar", "fa", "ur") -> "paddle-rec-arabic"
        else -> UNIFIED_PACK   // ja / zh / zh-Hant / Latin scripts
    }

    // ── Cases ────────────────────────────────────────────────────────────────

    /** One input screenshot: bundled golden asset (assetPath) XOR staged file. */
    private class CaseRef(val id: String, val lang: String, val assetPath: String? = null, val file: File? = null)

    private fun goldenJaCases(): List<CaseRef> =
        (testCtx.assets.list(GOLDEN_DIR) ?: emptyArray())
            .filter { it.endsWith(".png", ignoreCase = true) }
            .sorted()
            .map { CaseRef(id = "golden/${it.substringBeforeLast('.')}", lang = "ja", assetPath = "$GOLDEN_DIR/$it") }

    private fun stagedCases(): List<CaseRef> {
        val root = File(appCtx.getExternalFilesDir(AB_DIR) ?: return emptyList(), "cases")
        if (!root.isDirectory) return emptyList()
        return root.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }.flatMap { langDir ->
            langDir.listFiles { f -> f.isFile && f.name.endsWith(".png", ignoreCase = true) }.orEmpty()
                .sortedBy { it.name }
                .map { CaseRef(id = "staged/${langDir.name}/${it.name.substringBeforeLast('.')}", lang = langDir.name, file = it) }
        }
    }

    /** inScaled=false + ARGB_8888 is load-bearing: default decode would apply
     *  density scaling and change every pixel the engines see (OcrGoldenSetTest). */
    private fun loadBitmap(c: CaseRef): Bitmap {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bmp = when {
            c.assetPath != null -> testCtx.assets.open(c.assetPath).use { BitmapFactory.decodeStream(it, null, opts) }
            c.file != null -> BitmapFactory.decodeFile(c.file.absolutePath, opts)
            else -> null
        }
        return checkNotNull(bmp) { "failed to decode ${c.id}" }
    }

    // ── Result sink ──────────────────────────────────────────────────────────

    /**
     * JSONL writer: one JSON object per line (regions first, then the `case`
     * line as that case's completion marker). File is authoritative; each line
     * is mirrored to logcat tag [TAG] with the repo's 3ms anti-chatty throttle
     * as a fallback. flush() per line + fd.sync() per case bounds data loss if
     * a native crash kills the process mid-run. org.json escaping keeps every
     * physical line single-line (embedded newlines in OCR text become `\n`).
     */
    private class ResultSink(appCtx: Context, private val runId: String, experiment: String) {
        val resultFile: File = File(
            checkNotNull(appCtx.getExternalFilesDir(AB_DIR)) { "external files dir unavailable" },
            "results-$runId.jsonl")
        private val fos = FileOutputStream(resultFile)
        private val writer = fos.bufferedWriter()

        init {
            emit(JSONObject()
                .put("type", "run").put("run", runId)
                .put("ts", System.currentTimeMillis())
                .put("exp", experiment)
                .put("abi", android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?"))
            sync()
        }

        fun skip(exp: String, reason: String) {
            emit(JSONObject().put("type", "skip").put("run", runId).put("exp", exp).put("reason", reason))
            sync()
        }

        fun case(
            exp: String, caseId: String, lang: String, cfg: String, status: String, regions: Int,
            totalMs: Long? = null, detMs: Long? = null, reason: String? = null,
        ) {
            val o = JSONObject()
                .put("type", "case").put("run", runId).put("exp", exp).put("case", caseId)
                .put("lang", lang).put("cfg", cfg).put("status", status).put("regions", regions)
            totalMs?.let { o.put("totalMs", it) }
            detMs?.let { o.put("detMs", it) }
            reason?.let { o.put("reason", it) }
            emit(o)
            sync()
        }

        fun region(
            exp: String, caseId: String, cfg: String, idx: Int, box: IntArray,
            vert: Boolean, text: String? = null, conf: Float? = null, quad: List<IntArray>? = null,
            angleDeg: Float = 0f, orientedW: Float = 0f, orientedH: Float = 0f,
        ) {
            val o = JSONObject()
                .put("type", "region").put("run", runId).put("exp", exp).put("case", caseId)
                .put("cfg", cfg).put("idx", idx)
                .put("box", JSONArray().apply { box.forEach { put(it) } })
                .put("vert", vert)
            text?.let { o.put("text", it) }
            conf?.takeIf { it.isFinite() }?.let { o.put("conf", it.toDouble()) }
            quad?.let { q -> o.put("quad", JSONArray().apply { q.forEach { p -> put(JSONArray().apply { p.forEach(::put) }) } }) }
            // Slanted regions carry the angle trio (0-when-upright convention:
            // absent keys mean upright, mirroring the grouping harness records).
            if (angleDeg != 0f) {
                o.put("ang", angleDeg.toDouble())
                o.put("ow", orientedW.toDouble())
                o.put("oh", orientedH.toDouble())
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

    /** One fp16-experiment configuration: MNN precision per stage. */
    private class PrecisionCfg(val label: String, val det: Int, val rec: Int)

    private companion object {
        const val TAG = "OcrAb"
        const val GOLDEN_DIR = "ocr_golden"
        const val AB_DIR = "ocr_ab"
        const val MEIKI_PACK = "meiki-ja"
        const val UNIFIED_PACK = "paddle-rec-unified"
        // p0 = fp32 baseline, p2 = full fp16, p2d = mixed (fp16 det + fp32 rec —
        // the speedup on the stage that carries no reading-fidelity risk).
        val FP16_CONFIGS = listOf(
            PrecisionCfg("p0", det = 0, rec = 0),
            PrecisionCfg("p2", det = 2, rec = 2),
            PrecisionCfg("p2d", det = 2, rec = 0),
        )
        val DETCAP_SWEEP = listOf(1920, 1280, 960)  // det1920 = current-prod baseline
    }
}
