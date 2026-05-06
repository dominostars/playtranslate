package com.playtranslate.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.playtranslate.OcrManager
import com.playtranslate.OcrPreprocessingRecipe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.Normalizer

/**
 * Evaluates OCR preprocessing recipes against a hand-curated golden set of
 * game screenshots bundled in `androidTest/assets/ocr_golden/`.
 *
 * ## Why this exists
 *
 * We discovered ML Kit could confidently drop a kanji (`相` from `との相性`)
 * after our linear ×2.0 contrast preprocessing — same line, same bounding
 * box, but missing one character. ML Kit's confidence scores are uncalibrated
 * (per external review), so we can't rank preprocessing variants by the
 * confidence they report. This harness scores variants by Levenshtein-based
 * Character Error Rate against hand-transcribed ground truth. Future
 * preprocessing changes get evaluated against measurable accuracy, not vibes.
 *
 * ## Two tests
 *
 *  * [runGoldenSet] — reporting tool. Runs every recipe in [Variants.all]
 *    against every golden case, writes CSV + Markdown to
 *    `<targetContext.getExternalFilesDir(null)>/ocr_golden/report-<ts>.{csv,md}`,
 *    and **always passes**. Use it to sweep variants and compare.
 *  * [productionRecipeNoRegression] — regression gate. Runs only the
 *    [OcrPreprocessingRecipe.Default] recipe and fails if any Japanese-script
 *    character in the expected text is missing from the OCR output. Hard
 *    fail — the `相`-style drop bug triggers it directly.
 *
 * ## How to run
 *
 * `./gradlew connectedAndroidTest` against any connected device (USB or WiFi
 * adb). Takes ~1 minute for the current 16-case × 8-recipe sweep.
 *
 * ## How to recover the report (important — read this)
 *
 * `connectedAndroidTest` **uninstalls the app + test APK after the run**, so
 * the `.csv`/`.md` files written to the app's external files dir are wiped
 * before you can `adb pull` them. Recovery path: every report line is also
 * emitted to logcat under the `OcrReport` tag (with a 3ms throttle so it
 * survives logd's chatty filter). Pull after the run with:
 *
 * ```
 * adb logcat -d -s OcrReport:I | sed -nE 's/^[^:]+:[^:]+:[^ ]+ +[0-9]+ +[0-9]+ I OcrReport *: //p' > report.txt
 * ```
 *
 * ## Adding a golden case
 *
 * The fast path uses the seed-capture toggle (debug builds only):
 *
 *   1. Settings → Debug → "Save OCR captures as seeds" → on.
 *   2. Use the app to translate the screen. Each capture writes
 *      `<timestamp>.png` + `<timestamp>.txt` (the OCR's own draft) under
 *      `/sdcard/Android/data/com.playtranslate/files/ocr_seeds/`.
 *   3. `adb pull` the seeds locally.
 *   4. Edit the `.txt` to fix any OCR mistakes — the draft is a starting
 *      point, not ground truth.
 *   5. Rename the pair to a descriptive basename and drop both files
 *      directly into `app/src/androidTest/assets/ocr_golden/`. (Editing
 *      anywhere else won't be picked up — the test reads only this dir.)
 *   6. Re-run `./gradlew connectedAndroidTest`.
 *
 * Expected `.txt` format: one OCR line per row, reading order, plain text.
 * No manifest. Filenames are descriptive (no numeric prefixes).
 *
 * ## What gets scored
 *
 * For each (screen, recipe) the report has two parallel ops counts:
 *
 *  * **Raw** (`subs/dels/ins`): every character-level substitution, deletion,
 *    insertion in the OCR vs expected alignment. Includes punctuation, digits,
 *    Latin letters, ellipses — i.e. noise that doesn't change translation
 *    quality.
 *  * **Meaningful** (`real-sub/real-del/real-ins`): only ops that involve a
 *    Japanese-script character (hiragana, katakana, kanji). This is the
 *    metric that maps to translation quality. SUB counts if either side is
 *    Japanese (a real word changed); DEL needs the expected char to be
 *    Japanese (real character drop — the original `相` failure mode); INS
 *    needs the actual char to be Japanese (hallucinated Japanese text, not
 *    just gauge digits).
 *
 * For ranking variants on real-world impact, **prefer real-sub + real-del**
 * over CER. CER is dominated by hallucinations (HP/MP gauge bleed, trailing
 * periods) that don't matter; real-sub+del isolates translation-affecting
 * failures.
 *
 * ## Caveats
 *
 *  * **ML Kit is not perfectly deterministic.** Same bitmap, same recipe,
 *    different runs can produce slightly different output. Small per-case
 *    margins shift between runs. Re-run a few times if a result looks
 *    pivotal.
 *  * **PNG-decode path differs from runtime path.** The test loads bitmaps
 *    via `BitmapFactory.decodeStream` from PNG assets; production runtime
 *    builds them from MediaProjection captures directly. Pixel values can
 *    differ subtly enough to flip CNN classification on edge cases. If a bug
 *    reproduces in the live app but not in the harness, this is the likely
 *    reason — capture a fresh PNG from the same screen and try again.
 *  * **`runGoldenSet` always passes.** It's a reporting harness. Read the
 *    report; don't rely on the green checkmark. Only
 *    `productionRecipeNoRegression` is a real gate.
 *  * **Variants.kt** owns the recipe catalog; edit there to add/remove
 *    sweep entries without touching the test class.
 */
@RunWith(AndroidJUnit4::class)
class OcrGoldenSetTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val testCtx: Context get() = instrumentation.context           // androidTest APK (assets)
    private val appCtx: Context get() = instrumentation.targetContext      // production APK (external files dir)

    @After
    fun tearDown() {
        // Drop cached recognizers so each test run sees a fresh ML Kit client.
        OcrManager.instance.releaseAll()
    }

    @Test
    fun runGoldenSet() {
        val cases = loadGoldenCases(testCtx)
        if (cases.isEmpty()) {
            Log.w(TAG, "No golden cases found in androidTest/assets/ocr_golden/. Skipping sweep.")
            return
        }
        Log.i(TAG, "Sweep: ${cases.size} cases × ${Variants.all.size} recipes = ${cases.size * Variants.all.size} OCR runs")

        val rows = mutableListOf<ReportRow>()
        for (case in cases) {
            val bitmap = loadBitmap(testCtx, "$ASSET_DIR/${case.imageAsset}")
            try {
                for ((name, recipe) in Variants.all) {
                    val started = System.currentTimeMillis()
                    val result = runBlocking {
                        OcrManager.instance.recognise(
                            bitmap = bitmap,
                            sourceLang = "ja",
                            screenshotWidth = bitmap.width,
                            recipe = recipe
                        )
                    }
                    val durationMs = System.currentTimeMillis() - started
                    val actual = result?.fullText.orEmpty()
                    val cerVal = cer(actual, case.expected)
                    val diff = charDiff(actual, case.expected)
                    val ops = opCounts(actual, case.expected)
                    val realOps = meaningfulOpCounts(actual, case.expected)
                    rows += ReportRow(
                        caseId = case.id,
                        recipe = name,
                        cer = cerVal,
                        missingChars = diff.missing,
                        extraCount = diff.extra.size,
                        subs = ops.sub,
                        dels = ops.del,
                        ins = ops.ins,
                        realSubs = realOps.sub,
                        realDels = realOps.del,
                        realIns = realOps.ins,
                        inlineDiff = inlineDiff(actual, case.expected),
                        meaningfulDiff = meaningfulDiff(actual, case.expected),
                        actualPrefix = actual.take(80),
                        expectedPrefix = case.expected.take(80),
                        durationMs = durationMs,
                    )
                    Log.i(TAG, "${case.id} / $name: cer=${"%.3f".format(cerVal)} real=${realOps.sub}/${realOps.del}/${realOps.ins} (${durationMs}ms)")
                }
            } finally {
                bitmap.recycle()
            }
        }
        writeReport(appCtx, rows)
    }

    @Test
    fun productionRecipeNoRegression() {
        val cases = loadGoldenCases(testCtx)
        if (cases.isEmpty()) {
            Log.w(TAG, "No golden cases — regression gate is a no-op.")
            return
        }
        val failures = mutableListOf<String>()
        for (case in cases) {
            val bitmap = loadBitmap(testCtx, "$ASSET_DIR/${case.imageAsset}")
            try {
                val result = runBlocking {
                    OcrManager.instance.recognise(
                        bitmap = bitmap,
                        sourceLang = "ja",
                        screenshotWidth = bitmap.width,
                        recipe = OcrPreprocessingRecipe.Default,
                    )
                }
                val actual = result?.fullText.orEmpty()
                val diff = charDiff(actual, case.expected)
                if (diff.missing.isNotEmpty()) {
                    failures += "${case.id}: missing '${diff.missing.joinToString("")}'"
                }
            } finally {
                bitmap.recycle()
            }
        }
        if (failures.isNotEmpty()) {
            org.junit.Assert.fail(
                "Production recipe dropped expected characters on ${failures.size}/${cases.size} screen(s):\n" +
                    failures.joinToString("\n  ", prefix = "  ")
            )
        }
    }

    // ── Loaders ──────────────────────────────────────────────────────────

    private data class GoldenCase(val id: String, val imageAsset: String, val expected: String)

    private fun loadGoldenCases(context: Context): List<GoldenCase> {
        val files = context.assets.list(ASSET_DIR).orEmpty().toSet()
        val pngs = files.filter { it.endsWith(".png", ignoreCase = true) }.sorted()
        return pngs.mapNotNull { png ->
            val basename = png.dropLast(4) // strip .png/.PNG
            val txt = "$basename.txt"
            if (txt !in files) {
                Log.w(TAG, "Skipping $png — no matching $txt")
                return@mapNotNull null
            }
            val expected = context.assets.open("$ASSET_DIR/$txt").bufferedReader().use { it.readText() }
            GoldenCase(basename, png, expected)
        }
    }

    private fun loadBitmap(context: Context, assetPath: String): Bitmap {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false      // critical: skip density-based downscale
        }
        return context.assets.open(assetPath).use { input ->
            BitmapFactory.decodeStream(input, null, opts)
                ?: error("Failed to decode bitmap from $assetPath")
        }
    }

    // ── Metrics ──────────────────────────────────────────────────────────

    private data class CharDiff(val missing: List<Char>, val extra: List<Char>)

    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC)
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun cer(actual: String, expected: String): Double {
        val a = normalize(actual)
        val e = normalize(expected)
        if (e.isEmpty()) return if (a.isEmpty()) 0.0 else 1.0
        return levenshtein(a, e).toDouble() / e.length
    }

    private fun charDiff(actual: String, expected: String): CharDiff {
        val a = normalize(actual)
        val e = normalize(expected)
        val aFreq = mutableMapOf<Char, Int>()
        val eFreq = mutableMapOf<Char, Int>()
        for (c in a) if (!c.isWhitespace()) aFreq.merge(c, 1, Int::plus)
        for (c in e) if (!c.isWhitespace()) eFreq.merge(c, 1, Int::plus)
        val missing = mutableListOf<Char>()
        val extra = mutableListOf<Char>()
        for ((c, n) in eFreq) repeat(n - (aFreq[c] ?: 0)) { missing += c }
        for ((c, n) in aFreq) repeat(n - (eFreq[c] ?: 0)) { extra += c }
        return CharDiff(missing, extra)
    }

    /** Row-only DP Levenshtein on UTF-16 code units. */
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val m = a.length
        val n = b.length
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)
        for (i in 1..m) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[n]
    }

    private enum class Op { MATCH, SUB, DEL, INS }

    private data class CharOp(val kind: Op, val expectedCh: Char?, val actualCh: Char?)

    /**
     * Aligns [actual] against [expected] using full Levenshtein DP + traceback.
     * DEL means a character was in [expected] but missing from [actual] (the OCR
     * dropped it — `相` from `との相性`). INS means a character is in [actual] but
     * not in [expected] (hallucinated — `でしょよ` for `でしょ`). SUB means a
     * one-for-one swap (`相` → `性`).
     *
     * On ties, prefers MATCH > SUB > DEL > INS so the report stays readable
     * — substitutions surface as one op rather than del+ins pairs.
     */
    private fun alignChars(actual: String, expected: String): List<CharOp> {
        val a = actual
        val e = expected
        val m = a.length
        val n = e.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) for (j in 1..n) {
            val cost = if (a[i - 1] == e[j - 1]) 0 else 1
            dp[i][j] = minOf(
                dp[i - 1][j] + 1,
                dp[i][j - 1] + 1,
                dp[i - 1][j - 1] + cost,
            )
        }
        val ops = mutableListOf<CharOp>()
        var i = m; var j = n
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && a[i - 1] == e[j - 1] && dp[i][j] == dp[i - 1][j - 1] -> {
                    ops += CharOp(Op.MATCH, e[j - 1], a[i - 1]); i--; j--
                }
                i > 0 && j > 0 && dp[i][j] == dp[i - 1][j - 1] + 1 -> {
                    ops += CharOp(Op.SUB, e[j - 1], a[i - 1]); i--; j--
                }
                j > 0 && dp[i][j] == dp[i][j - 1] + 1 -> {
                    ops += CharOp(Op.DEL, e[j - 1], null); j--
                }
                else -> {
                    ops += CharOp(Op.INS, null, a[i - 1]); i--
                }
            }
        }
        return ops.reversed()
    }

    /**
     * Renders the aligned diff as a single annotated string. Matched characters
     * appear bare; non-matches use bracket notation so substitutions/missing
     * characters are visible in their original phrase context:
     *   - `[相→性]` — substitution
     *   - `[相→_]` — deleted (missing from actual)
     *   - `[_→ょ]` — inserted (hallucinated in actual)
     *
     * Returns "(exact match)" when there are no diffs.
     */
    private fun inlineDiff(actual: String, expected: String): String {
        val ops = alignChars(normalize(actual), normalize(expected))
        if (ops.all { it.kind == Op.MATCH }) return "(exact match)"
        val sb = StringBuilder()
        for (op in ops) {
            when (op.kind) {
                Op.MATCH -> sb.append(op.expectedCh)
                Op.SUB -> sb.append("[${op.expectedCh}→${op.actualCh}]")
                Op.DEL -> sb.append("[${op.expectedCh}→_]")
                Op.INS -> sb.append("[_→${op.actualCh}]")
            }
        }
        return sb.toString()
    }

    private data class OpCounts(val sub: Int, val del: Int, val ins: Int)

    private fun opCounts(actual: String, expected: String): OpCounts {
        val ops = alignChars(normalize(actual), normalize(expected))
        var sub = 0; var del = 0; var ins = 0
        for (op in ops) when (op.kind) {
            Op.SUB -> sub++
            Op.DEL -> del++
            Op.INS -> ins++
            Op.MATCH -> {}
        }
        return OpCounts(sub, del, ins)
    }

    /** Hiragana, katakana, kanji (incl. CJK Extension A and half-width kana). */
    private fun isJapanese(c: Char?): Boolean {
        if (c == null) return false
        return c in '぀'..'ゟ' ||  // Hiragana
            c in '゠'..'ヿ' ||     // Katakana
            c in '一'..'鿿' ||     // CJK Unified Ideographs
            c in '㐀'..'䶿' ||     // CJK Extension A
            c in '･'..'ﾟ'         // Half-width Katakana
    }

    /** Whether [op] involves a Japanese-script character on either side, i.e.
     *  the diff actually changes a translation-relevant character. SUB counts
     *  if either side is Japanese; DEL needs the expected char to be Japanese
     *  (real drop); INS needs the actual char to be Japanese (real
     *  hallucination of Japanese text, not just digits/punctuation/whitespace). */
    private fun isMeaningfulOp(op: CharOp): Boolean = when (op.kind) {
        Op.MATCH -> false
        Op.SUB -> isJapanese(op.expectedCh) || isJapanese(op.actualCh)
        Op.DEL -> isJapanese(op.expectedCh)
        Op.INS -> isJapanese(op.actualCh)
    }

    /** Like [opCounts] but ignores noise ops — punctuation, digits, ASCII
     *  letters, whitespace, ellipses, etc. */
    private fun meaningfulOpCounts(actual: String, expected: String): OpCounts {
        val ops = alignChars(normalize(actual), normalize(expected))
        var sub = 0; var del = 0; var ins = 0
        for (op in ops) {
            if (!isMeaningfulOp(op)) continue
            when (op.kind) {
                Op.SUB -> sub++
                Op.DEL -> del++
                Op.INS -> ins++
                Op.MATCH -> {}
            }
        }
        return OpCounts(sub, del, ins)
    }

    /** Inline diff that omits noise ops. Matched characters are preserved as
     *  surrounding context; non-match ops only show in brackets when they
     *  involve Japanese script. */
    private fun meaningfulDiff(actual: String, expected: String): String {
        val ops = alignChars(normalize(actual), normalize(expected))
        if (ops.none { it.kind != Op.MATCH && isMeaningfulOp(it) }) {
            return "(no meaningful diffs)"
        }
        val sb = StringBuilder()
        for (op in ops) {
            when (op.kind) {
                Op.MATCH -> sb.append(op.expectedCh)
                else -> if (isMeaningfulOp(op)) {
                    when (op.kind) {
                        Op.SUB -> sb.append("[${op.expectedCh}→${op.actualCh}]")
                        Op.DEL -> sb.append("[${op.expectedCh}→_]")
                        Op.INS -> sb.append("[_→${op.actualCh}]")
                        else -> {}
                    }
                }
            }
        }
        return sb.toString()
    }

    // ── Reporting ────────────────────────────────────────────────────────

    private data class ReportRow(
        val caseId: String,
        val recipe: String,
        val cer: Double,
        val missingChars: List<Char>,
        val extraCount: Int,
        val subs: Int,
        val dels: Int,
        val ins: Int,
        val realSubs: Int,
        val realDels: Int,
        val realIns: Int,
        val inlineDiff: String,
        val meaningfulDiff: String,
        val actualPrefix: String,
        val expectedPrefix: String,
        val durationMs: Long,
    )

    private fun writeReport(context: Context, rows: List<ReportRow>) {
        val dir = File(context.getExternalFilesDir(null), "ocr_golden").apply { mkdirs() }
        val ts = System.currentTimeMillis()

        val csvLines = mutableListOf<String>().apply {
            add("case_id,recipe,cer,subs,dels,ins,real_subs,real_dels,real_ins,missing_count,missing_chars,extra_count,inline_diff,meaningful_diff,actual_first_80,expected_first_80,duration_ms")
            for (r in rows) {
                val cells = listOf(
                    r.caseId, r.recipe,
                    "%.4f".format(r.cer),
                    r.subs.toString(),
                    r.dels.toString(),
                    r.ins.toString(),
                    r.realSubs.toString(),
                    r.realDels.toString(),
                    r.realIns.toString(),
                    r.missingChars.size.toString(),
                    r.missingChars.joinToString(""),
                    r.extraCount.toString(),
                    r.inlineDiff,
                    r.meaningfulDiff,
                    r.actualPrefix, r.expectedPrefix,
                    r.durationMs.toString(),
                )
                add(cells.joinToString(",") { csvEscape(it) })
            }
        }

        val mdLines = buildMarkdownReport(ts, rows)

        File(dir, "report-$ts.csv").bufferedWriter().use { w ->
            csvLines.forEach { w.appendLine(it) }
        }
        File(dir, "report-$ts.md").bufferedWriter().use { w ->
            mdLines.forEach { w.appendLine(it) }
        }

        // Also emit to logcat under a dedicated tag so the report survives
        // connectedAndroidTest's post-run uninstall. Recover with
        //   adb logcat -d -s OcrReport:I > report.txt
        // Throttle each Log.i call by ~3ms — Android's logd applies a
        // per-process rate limit (chatty filter) when bursting > ~50 lines/sec
        // and silently drops some, which previously cost us 4 of 128 detail
        // rows. The 3ms gap is below the user-perceptible budget but well
        // above the rate-limit threshold.
        Log.i(REPORT_TAG, "===== BEGIN OCR GOLDEN-SET REPORT ts=$ts =====")
        Log.i(REPORT_TAG, "----- markdown -----")
        for (line in mdLines) { Log.i(REPORT_TAG, line); Thread.sleep(3) }
        Log.i(REPORT_TAG, "----- csv -----")
        for (line in csvLines) { Log.i(REPORT_TAG, line); Thread.sleep(3) }
        Log.i(REPORT_TAG, "===== END OCR GOLDEN-SET REPORT ts=$ts =====")

        Log.i(TAG, "Report written to ${dir.absolutePath}")
    }

    private fun buildMarkdownReport(ts: Long, rows: List<ReportRow>): List<String> {
        val out = mutableListOf<String>()
        out += "# OCR Golden-Set Report — ts=$ts"
        out += ""
        val cases = rows.map { it.caseId }.distinct().sorted()
        val recipes = rows.map { it.recipe }.distinct()
        out += "| case |" + recipes.joinToString("") { " $it |" }
        out += "|---|" + recipes.joinToString("") { "---|" }
        for (case in cases) {
            val cells = recipes.joinToString("") { rcp ->
                val row = rows.firstOrNull { it.caseId == case && it.recipe == rcp }
                " ${if (row == null) "-" else "%.3f".format(row.cer)} |"
            }
            out += "| $case |$cells"
        }
        out += ""
        out += "## Per-row detail (meaningful diffs only — Japanese-script ops)"
        out += ""
        for (r in rows) {
            out += "- **${r.caseId}** / `${r.recipe}` cer=${"%.3f".format(r.cer)}" +
                " real-sub=${r.realSubs} real-del=${r.realDels} real-ins=${r.realIns}" +
                " (raw sub/del/ins=${r.subs}/${r.dels}/${r.ins}, ${r.durationMs}ms)"
            out += "    diff: `${r.meaningfulDiff}`"
        }
        return out
    }

    private fun csvEscape(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' })
            "\"${s.replace("\"", "\"\"")}\""
        else s

    companion object {
        private const val TAG = "OcrGoldenSetTest"
        private const val REPORT_TAG = "OcrReport"
        private const val ASSET_DIR = "ocr_golden"
    }
}
