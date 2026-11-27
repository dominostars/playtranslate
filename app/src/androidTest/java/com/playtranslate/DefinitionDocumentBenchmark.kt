package com.playtranslate

import android.app.ActivityManager
import android.app.UiAutomation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Choreographer
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.playtranslate.language.LanguagePackStore
import com.playtranslate.language.PreloadResult
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.ui.DefinitionsDocument
import com.playtranslate.ui.RowState
import com.playtranslate.ui.WordDefinitionData
import com.playtranslate.ui.WordLookupContext
import com.playtranslate.ui.DefRow
import com.playtranslate.ui.DefRowHtml
import com.playtranslate.ui.DefinitionShell
import com.playtranslate.ui.fetchYomitanStyledData
import com.playtranslate.ui.resolveWordRows
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Measures the one-document definition model on THIS device: a whole word
 * list as ONE definitions page (a [DefinitionShell], the page every
 * definitions document loads, holding the rows [DefRowHtml] builds) inside
 * a host ScrollView (the view never scrolls itself), in a
 * hardware-accelerated overlay window like the capture sheet's.
 *
 * It drives the shell directly, not a whole document: a document reads
 * structured content from the app's own dictionary store, and this can be
 * handed a dictionary's entries from a file (`glossFile`) on a device with
 * nothing imported. The page, its script and stylesheet and the rows'
 * markup are the app's; what a document adds (feeding by chunks) is
 * measured here as the page's own set and append.
 *
 * What it answers:
 *  - what a kept-alive WebView and its renderer process cost in memory;
 *  - what a second shell costs while one is alive, against one built after
 *    every WebView was destroyed (a first definition after the surfaces
 *    closed);
 *  - how long a row set takes for one row, for N rows at once, and for one
 *    row appended to a loaded page (the on-demand increment);
 *  - whether a tall document keeps up with host scrolling: the document's
 *    rows are opaque and the view sits on a magenta holder, so any magenta
 *    in a screenshot is area the WebView had not rastered at that instant;
 *  - what the page believes its viewport is under host scroll.
 *
 * The rows are real: a fixed paragraph tokenized and resolved through
 * [resolveWordRows] against the device's own packs and imported
 * dictionaries, with the styled payload fetched for every row.
 *
 * Run (never connectedAndroidTest, which uninstalls the app afterwards):
 *   ./gradlew :app:installDebug :app:installDebugAndroidTest
 *   adb shell am instrument -w -e class com.playtranslate.DefinitionDocumentBenchmark \
 *     [-e rows 40] [-e lang ja] [-e holdSec 0] com.playtranslate.test/androidx.test.runner.AndroidJUnitRunner
 * Output: Log tag DefDocBench and
 *   /sdcard/Android/data/com.playtranslate/files/defdoc/results.txt (+ frames)
 */
@RunWith(AndroidJUnit4::class)
class DefinitionDocumentBenchmark {

    private val tag = "DefDocBench"
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = instr.targetContext
    // Keep the app's own accessibility service bound while this runs.
    private val ui: UiAutomation =
        instr.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val density = ctx.resources.displayMetrics.density
    private lateinit var outDir: File
    private lateinit var outFile: File

    private lateinit var scroll: ScrollView
    private lateinit var holder: FrameLayout
    private var probeRect = Rect()
    private var markerX = 0
    private var markerY = 0
    @Volatile private var scrollYNow = 0

    private class Sample(val phase: String, val t0: Long, val t1: Long, val magenta: Float, val marker: Int, val y: Int)
    private val samples = ArrayList<Sample>()
    @Volatile private var samplerOn = false
    @Volatile private var samplerRun = true
    @Volatile private var phase = "idle"
    @Volatile private var phaseWorst = 0f

    private val markerColors = intArrayOf(
        0xFF00FFFF.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(),
        0xFFFF8000.toInt(), 0xFF0080FF.toInt(), 0xFFFFFFFF.toInt(),
    )
    private var markerIndex = 0

    private fun out(line: String) {
        Log.i(tag, line)
        outFile.appendText(line + "\n")
    }

    private fun shell(cmd: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(cmd))
            .bufferedReader().use { it.readText() }

    private fun now() = SystemClock.uptimeMillis()

    // ---- memory ---------------------------------------------------------

    private fun sandboxed(): Map<Int, String> =
        shell("ps -A -o PID,NAME").lineSequence().mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"), limit = 2)
            if (p.size == 2 && p[1].contains("sandboxed")) p[0].toIntOrNull()?.let { it to p[1] } else null
        }.toMap()

    private fun meminfo(pid: Int): String {
        val o = shell("dumpsys meminfo $pid")
        fun f(re: String) = Regex(re).find(o)?.groupValues?.get(1) ?: "?"
        val parts = Regex("(?m)^\\s*(Java Heap|Native Heap|Code|Stack|Graphics|Private Other|System):\\s+(\\d+)")
            .findAll(o).joinToString(" ") { it.groupValues[1].replace(" ", "") + "=" + it.groupValues[2] }
        return "pss=${f("TOTAL PSS:\\s+(\\d+)")}kB rss=${f("TOTAL RSS:\\s+(\\d+)")}kB [$parts]"
    }

    /** Waits until this process's own PSS stops moving (two readings two
     *  seconds apart within 2 MB), so a reading is not taken mid-startup. */
    private fun settle() {
        var last = -1L
        repeat(20) {
            val pss = android.os.Debug.getPss()
            if (last >= 0 && kotlin.math.abs(pss - last) < 2000) return
            last = pss
            Thread.sleep(2000)
        }
    }

    private var baselineSandboxed: Set<Int> = emptySet()

    private fun mem(label: String) {
        val was = samplerOn
        samplerOn = false
        Thread.sleep(1500)
        settle()
        out("MEM [$label] app(${android.os.Process.myPid()}): ${meminfo(android.os.Process.myPid())}")
        val mine = sandboxed().filterKeys { it !in baselineSandboxed }
        if (mine.isEmpty()) out("MEM [$label] renderer: none")
        for ((pid, name) in mine) out("MEM [$label] renderer($pid ${name.takeLast(40)}): ${meminfo(pid)}")
        if (mine.isNotEmpty()) {
            val oom = shell("dumpsys activity oom").lineSequence()
            val keep = mine.keys.map { " $it:" } + " ${android.os.Process.myPid()}:"
            for (line in oom) if (keep.any { line.contains(it) }) out("OOM [$label] ${line.trim().take(150)}")
        }
        samplerOn = was
    }

    // ---- screenshots ----------------------------------------------------

    private fun magentaFraction(b: Bitmap, r: Rect): Float {
        val left = max(0, r.left); val top = max(0, r.top)
        val right = min(b.width, r.right); val bottom = min(b.height, r.bottom)
        val w = right - left
        if (w <= 0 || bottom <= top) return 0f
        val row = IntArray(w)
        var hit = 0; var total = 0
        var y = top
        while (y < bottom) {
            b.getPixels(row, 0, w, left, y, w, 1)
            var x = 0
            while (x < w) {
                val c = row[x]
                if (Color.red(c) > 200 && Color.green(c) < 70 && Color.blue(c) > 200) hit++
                total++
                x += 3
            }
            y += 3
        }
        return if (total == 0) 0f else hit.toFloat() / total
    }

    private fun save(b: Bitmap, name: String) {
        try {
            FileOutputStream(File(outDir, name)).use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        } catch (e: Exception) {
            Log.w(tag, "save $name failed: ${e.javaClass.simpleName}")
        }
    }

    private val sampler = Thread({
        while (samplerRun) {
            if (!samplerOn) { Thread.sleep(5); continue }
            val t0 = now()
            val y = scrollYNow
            val ph = phase
            val bmp = try { ui.takeScreenshot() } catch (e: Exception) { null }
            val t1 = now()
            if (bmp == null) { Thread.sleep(20); continue }
            val frac = magentaFraction(bmp, probeRect)
            val mk = if (markerX in 0 until bmp.width && markerY in 0 until bmp.height) bmp.getPixel(markerX, markerY) else 0
            synchronized(samples) { samples += Sample(ph, t0, t1, frac, mk, y) }
            if (frac > 0.02f && frac > phaseWorst && ph == phase) {
                phaseWorst = frac
                save(bmp, "worst_$ph.jpg")
            }
            bmp.recycle()
        }
    }, "defdoc-sampler")

    private fun snap(name: String) {
        val bmp = try { ui.takeScreenshot() } catch (e: Exception) { null } ?: return
        save(bmp, name)
        bmp.recycle()
    }

    private fun beginPhase(name: String) {
        phase = name
        phaseWorst = 0f
    }

    private fun near(a: Int, b: Int) =
        kotlin.math.abs(Color.red(a) - Color.red(b)) < 40 &&
            kotlin.math.abs(Color.green(a) - Color.green(b)) < 40 &&
            kotlin.math.abs(Color.blue(a) - Color.blue(b)) < 40

    /** The first screenshot taken at or after [since] that shows [color] at
     *  the marker point, as (start, end) of that screenshot's capture. */
    private fun awaitMarker(color: Int, since: Long, timeoutMs: Long): Pair<Long, Long>? {
        val deadline = now() + timeoutMs
        while (now() < deadline) {
            val hit = synchronized(samples) { samples.firstOrNull { it.t0 >= since && near(it.marker, color) } }
            if (hit != null) return hit.t0 to hit.t1
            Thread.sleep(10)
        }
        return null
    }

    // ---- content --------------------------------------------------------

    private val themed by lazy { android.view.ContextThemeWrapper(ctx, R.style.Theme_PlayTranslate) }
    private var nextDom = 0

    /** [row] as the app draws it: the word row as a list shows it (links
     *  inert) or, [open], as the lens does (links live, the Anki button).
     *  Both draw every dictionary in full under its title: a list's row was
     *  capped to a preview until 2026-11-11, which is what "clamped" in the
     *  step names below meant, and figures taken before then are of capped
     *  rows. */
    private fun rowHtml(row: RowState, structured: Map<Long, String>, lang: String, open: Boolean): String {
        val data = WordDefinitionData(
            word = row.displayWord,
            reading = row.reading.ifEmpty { null },
            senses = row.senses,
            freqScore = row.freqScore,
            isCommon = row.isCommon,
            importedGroups = row.importedGroups,
        )
        val defRow = DefRow.Word(
            "r", data, speak = DefRow.Speak.IDLE,
            trailing = if (open) DefRow.Trailing.ANKI else DefRow.Trailing.HIDE,
            full = open,
        )
        return DefRowHtml.html(themed, defRow, "k${nextDom++}", structured, lang)
    }

    private fun nextMarker(): Int = markerColors[markerIndex++ % markerColors.size]

    private fun markerHtml(color: Int) =
        "<div style=\"height:12px;background:#%06X\"></div>".format(color and 0xFFFFFF)

    // ---- view plumbing --------------------------------------------------

    private fun onMain(block: () -> Unit) = instr.runOnMainSync(block)

    private fun tokens() = DefinitionsDocument.Tokens(
        text = 0xFFEFEFEF.toInt(),
        textMuted = 0xFFA0A0A0.toInt(),
        textHint = 0xFF606060.toInt(),
        accent = 0xFF00BCD4.toInt(),
        panel = 0xFF242424.toInt(),
        baseFontSizePx = 16.5f,
    )

    private fun theme() = DefinitionsDocument.DocTheme(tokens(), divider = 0xFF555555.toInt(), band = 0xFF1A1A1A.toInt())

    /** One shell showing in a frame, sized by the page's layout reports:
     *  what a [com.playtranslate.ui.DefinitionDocument] is to its host,
     *  without its row list. Main thread. */
    private inner class Page(val shell: DefinitionShell) {
        val frame = FrameLayout(ctx)
        var onHeight: ((Int) -> Unit)? = null
        var gen = 0
            private set
        private val appliedCss = HashSet<String>()

        init {
            frame.addView(shell.webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            shell.adopt(themed)
            shell.listener = object : DefinitionShell.Listener {
                override fun onLayout(gen: Int, heightCssPx: Int, targetsJson: String, headsJson: String, redrawnJson: String, labelsJson: String) {
                    if (gen == this@Page.gen) onHeight?.invoke((heightCssPx * density).roundToInt())
                }
                override fun onAction(domId: String, act: String) = Unit
                override fun onRendererGone() = Unit
            }
            shell.exec(DefinitionsDocument.themeJs(theme(), rtl = false))
            // The app's rows are transparent over their card. Here the rows'
            // root gets an opaque fill (the page itself stays transparent),
            // gaps between rows included, so the magenta holder shows only
            // where the engine has not rastered.
            shell.exec(
                "(function(){var s=document.createElement('style');" +
                    "s.textContent='#root{background:#242424}';document.head.appendChild(s);})()",
            )
        }

        /** A new row set: the dictionaries' CSS not yet applied, then the rows. */
        fun set(html: String, css: Map<String, String>, lang: String) {
            shell.mediaLanguage = lang
            for ((dict, sheet) in css) {
                if (appliedCss.add(dict)) shell.exec("ptApplyDictCss(${JSONObject.quote(dict)},${JSONObject.quote(sheet)})")
            }
            gen++
            shell.exec("ptSet(${JSONObject.quote(html)},$gen)")
        }

        fun destroy() = shell.destroy()
    }

    private fun setHeight(v: Page, px: Int) {
        v.frame.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, max(1, px))
    }

    private class SwapStats(val callMs: Long, val heightMs: Long, val heightPx: Int, val pixelsMs: Pair<Long, Long>?)

    /** One row set ([Page.set]) of [rowsHtml] under a fresh marker bar: the
     *  main-thread cost of the call, the time to the first layout report,
     *  and the time to the marker's pixels on screen. */
    private fun swap(v: Page, rowsHtml: String, css: Map<String, String>, lang: String, label: String): SwapStats {
        val color = nextMarker()
        val html = markerHtml(color) + rowsHtml
        val firstH = AtomicInteger(0)
        val tHeight = AtomicLong(0)
        val latch = CountDownLatch(1)
        var tSet = 0L
        var callMs = 0L
        beginPhase(label)
        samplerOn = true
        onMain {
            scroll.scrollTo(0, 0)
            scrollYNow = 0
            v.onHeight = { h ->
                if (h > 1) {
                    setHeight(v, h)
                    if (firstH.compareAndSet(0, h)) {
                        tHeight.set(now())
                        latch.countDown()
                    }
                }
            }
            tSet = now()
            v.set(html, css, lang)
            callMs = now() - tSet
        }
        val got = latch.await(30, TimeUnit.SECONDS)
        val pix = awaitMarker(color, tSet, 10_000)
        val heightMs = if (got) tHeight.get() - tSet else -1
        val s = SwapStats(callMs, heightMs, firstH.get(), pix?.let { (it.first - tSet) to (it.second - tSet) })
        out(
            "SWAP [$label] html=${html.length}ch call=${s.callMs}ms height=${s.heightMs}ms (${s.heightPx}px) " +
                "pixels=${s.pixelsMs?.let { "${it.first}..${it.second}ms" } ?: "not seen in 10s"}",
        )
        return s
    }

    // ---- scrolling ------------------------------------------------------

    /** Drives the host ScrollView from [from] to [to] at a constant
     *  [pxPerSec], one scrollTo per frame, and reports the frame cadence and
     *  what the screenshots saw. [sampled] false leaves the screenshot
     *  thread idle so the frame timing is not disturbed by it. */
    private fun scrollPass(label: String, from: Int, to: Int, pxPerSec: Int, sampled: Boolean) {
        samplerOn = false
        onMain { scroll.scrollTo(0, from); scrollYNow = from }
        Thread.sleep(600)
        shell("dumpsys gfxinfo com.playtranslate reset")
        beginPhase(label)
        val firstSample = synchronized(samples) { samples.size }
        samplerOn = sampled
        val latch = CountDownLatch(1)
        val deltas = ArrayList<Long>()
        onMain {
            val ch = Choreographer.getInstance()
            val dir = if (to >= from) 1 else -1
            var t0 = 0L
            var last = 0L
            ch.postFrameCallback(object : Choreographer.FrameCallback {
                override fun doFrame(frameNs: Long) {
                    if (t0 == 0L) t0 = frameNs else deltas += frameNs - last
                    last = frameNs
                    val y = from + dir * ((frameNs - t0) / 1e9 * pxPerSec).toInt()
                    val done = if (dir > 0) y >= to else y <= to
                    val at = if (done) to else y
                    scroll.scrollTo(0, at)
                    scrollYNow = at
                    if (done) latch.countDown() else ch.postFrameCallback(this)
                }
            })
        }
        latch.await(90, TimeUnit.SECONDS)
        Thread.sleep(120)
        samplerOn = false
        val sorted = deltas.sorted()
        val period = if (sorted.isEmpty()) 0L else sorted[sorted.size / 2]
        val late = deltas.count { it > period * 3 / 2 }
        val worst = (sorted.lastOrNull() ?: 0L) / 1e6
        val gfx = shell("dumpsys gfxinfo com.playtranslate").lineSequence()
            .map { it.trim() }
            .filter {
                it.startsWith("Total frames rendered") || it.startsWith("Janky frames") ||
                    it.startsWith("50th percentile") || it.startsWith("90th percentile") ||
                    it.startsWith("95th percentile") || it.startsWith("99th percentile")
            }
            .take(6).joinToString(" | ")
        val seen = synchronized(samples) { samples.subList(firstSample, samples.size).filter { it.phase == label } }
        val check = if (!sampled) "not sampled" else
            "shots=${seen.size} unrastered max=${"%.3f".format(seen.maxOfOrNull { it.magenta } ?: 0f)} " +
                "mean=${"%.4f".format(if (seen.isEmpty()) 0.0 else seen.map { it.magenta.toDouble() }.average())} " +
                "over2pct=${seen.count { it.magenta > 0.02f }}"
        out(
            "SCROLL [$label] $from->$to at ${pxPerSec}px/s frames=${deltas.size} period=${"%.2f".format(period / 1e6)}ms " +
                "late=$late worst=${"%.1f".format(worst)}ms | $check",
        )
        out("GFX [$label] $gfx")
    }

    private fun scrollSuite(name: String) {
        var maxY = 0
        onMain { maxY = max(0, scroll.getChildAt(0).height - scroll.height) }
        out("DOC [$name] contentHeight=${maxY + scroll.height}px viewport=${scroll.height}px maxScroll=${maxY}px")
        for (speed in intArrayOf(2_000, 8_000, 20_000)) {
            val to = min(maxY, speed * 3)
            scrollPass("${name}_down_$speed", 0, to, speed, sampled = true)
            scrollPass("${name}_up_$speed", to, 0, speed, sampled = true)
            scrollPass("${name}_down_${speed}_clean", 0, to, speed, sampled = false)
        }
    }

    // ---- the run --------------------------------------------------------

    private val paragraphs = mapOf(
        SourceLangId.JA to
            "昨日、友達に聞いたんだけど、あの店のラーメンは本当に美味しいらしい。" +
            "王国の歴史は千年前の大戦から始まり、伝説の勇者が魔王を倒したと言われている。" +
            "研究所の地下には古い機械が隠されていて、夜になると不思議な音が聞こえる。" +
            "商人は町の広場で珍しい薬草や武器を売っているが、値段が高くて誰も買わない。" +
            "彼女は静かに首を振り、遠くの山を見つめながら小さな声で約束の言葉を呟いた。" +
            "明日の朝、北の塔へ向かう前に十分な準備をして、仲間と一緒に出発しよう。" +
            "湖の底に沈んだ都の話を信じる者は少ないが、漁師たちは今でも鐘の音を聞くという。",
        SourceLangId.ZH to
            "昨天我听朋友说，那家店的拉面真的很好吃。王国的历史从一千年前的大战开始，传说中的勇者打败了魔王。" +
            "研究所的地下藏着古老的机器，到了晚上就能听到奇怪的声音。商人在城镇的广场上卖珍贵的草药和武器，" +
            "可是价格太高，没有人买。她静静地摇了摇头，望着远处的山，小声说出了约定的话。" +
            "明天早上去北边的塔之前，我们要做好充分的准备，和伙伴们一起出发。",
    )

    @Test
    fun measure() {
        outDir = File(ctx.getExternalFilesDir(null), "defdoc").apply { deleteRecursively(); mkdirs() }
        outFile = File(outDir, "results.txt")
        val args = InstrumentationRegistry.getArguments()
        val wantRows = args.getString("rows")?.toIntOrNull() ?: 40
        val holdSec = args.getString("holdSec")?.toIntOrNull() ?: 0
        val memOnly = args.getString("memOnly") == "true"
        val stopAfterClamped = args.getString("stopAfterClamped") == "true"
        val lingerSec = args.getString("lingerSec")?.toIntOrNull() ?: 0
        val prefs = Prefs(ctx)
        val langId = SourceLangId.fromCode(args.getString("lang"))
            ?: SourceLangId.JA.takeIf { LanguagePackStore.isInstalled(ctx, it) }
            ?: prefs.sourceLangId
        assumeTrue("pack for ${langId.code} not installed", LanguagePackStore.isInstalled(ctx, langId))

        val am = ctx.getSystemService(ActivityManager::class.java)
        val wm = ctx.getSystemService(WindowManager::class.java)
        @Suppress("DEPRECATION") val display = wm.defaultDisplay
        out(
            "DEVICE ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} density=$density refresh=${display.refreshRate}Hz " +
                "lowRam=${am.isLowRamDevice} webview=${WebView.getCurrentWebViewPackage()?.let { it.packageName + " " + it.versionName }} " +
                "lang=${langId.code}",
        )
        if (!Settings.canDrawOverlays(ctx)) {
            shell("appops set com.playtranslate SYSTEM_ALERT_WINDOW allow")
            Thread.sleep(500)
            out("NOTE overlay appop was off; set to allow for this run")
        }

        // ---- real rows, off the main thread ----
        var rows: List<RowState> = emptyList()
        var structured: Map<Long, String> = emptyMap()
        var css: Map<String, String> = emptyMap()
        val lang = langId.yomitanConsumingLang()
        runBlocking {
            val engine = SourceLanguageEngines.get(ctx, langId)
            assumeTrue("engine preload failed", engine.preload() == PreloadResult.Success)
            val text = paragraphs[langId] ?: paragraphs.getValue(SourceLangId.JA)
            val t0 = now()
            val toks = engine.tokenize(text)
            // Target "en": the pack's own glosses, never a machine translation.
            val data = resolveWordRows(ctx, WordLookupContext(engine, "en", prefs.targetChineseVariant), toks)
            val t1 = now()
            rows = data.rows
            val styled = fetchYomitanStyledData(ctx, lang, rows.flatMap { it.importedGroups })
            val t2 = now()
            structured = styled?.structured.orEmpty()
            css = styled?.dictStyles.orEmpty()
            out(
                "ROWS tokens=${toks.size} rows=${rows.size} lookup=${t1 - t0}ms payloadFetch=${t2 - t1}ms " +
                    "structuredGlossaries=${structured.size} payloadChars=${structured.values.sumOf { it.length }} " +
                    "dicts=${rows.flatMap { r -> r.importedGroups.map { it.source } }.distinct()} " +
                    "css=${css.entries.joinToString { "${it.key}:${it.value.length}ch" }}",
            )
        }
        assumeTrue("no rows resolved", rows.isNotEmpty())
        File(outDir, "rows.tsv").writeText(rows.joinToString("\n") { it.displayWord + "\t" + it.reading })
        if (args.getString("listOnly") == "true") {
            out("LIST ONLY: ${rows.size} rows written to rows.tsv")
            return
        }
        // Dictionary entries handed in from outside the app's own store, for a
        // device with nothing imported: {"css": styles.css, "terms": {"word\treading":
        // [glossary JSON per term-bank entry]}}. Nothing is imported or written.
        args.getString("glossFile")?.let { path ->
            // Read through the shell, so the file can sit in /data/local/tmp.
            val json = JSONObject(shell("cat $path"))
            val terms = json.getJSONObject("terms")
            val fed = HashMap<Long, String>()
            var nextId = 1L
            var matched = 0
            rows = rows.map { row ->
                val arr = terms.optJSONArray(row.displayWord + "\t" + row.reading) ?: terms.optJSONArray(row.displayWord + "\t")
                    ?: return@map row
                matched++
                val senses = List(arr.length()) { i ->
                    val id = nextId++
                    fed[id] = arr.getString(i)
                    com.playtranslate.model.ImportedSense("", scRowid = id)
                }
                row.copy(importedGroups = listOf(com.playtranslate.model.ImportedSenseGroup("Jitendex", senses, dictId = "jitendex")))
            }
            structured = fed
            css = mapOf("jitendex" to json.getString("css"))
            out(
                "FED $path: rows with entries=$matched/${rows.size} glossaries=${fed.size} " +
                    "payloadChars=${fed.values.sumOf { it.length }} css=${css.getValue("jitendex").length}ch",
            )
        }
        if (structured.isEmpty()) out("NOTE no structured content on this device: the rows render the pack's flat senses only")
        fun pick(n: Int) = List(n) { rows[it % rows.size] }

        fun build(n: Int, open: Boolean): List<String> {
            val t0 = now()
            val list = pick(n).map { rowHtml(it, structured, lang, open) }
            out("BUILD rows=$n look=${if (open) "open (the lens's full row)" else "a list's row (named clamped: capped until 2026-11-11, in full since)"} html=${list.sumOf { it.length }}ch kotlin=${now() - t0}ms")
            return list
        }
        val clamped = build(wantRows, open = false)
        val open by lazy { build(wantRows, open = true) }
        val huge by lazy { build(wantRows * 5, open = true) }

        baselineSandboxed = sandboxed().keys
        out("BASELINE other apps' sandboxed renderers: ${baselineSandboxed.size}")
        mem("baseline, no WebView")

        // ---- the window: ScrollView > column > [header, magenta holder] ----
        var root: FrameLayout? = null
        onMain {
            val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            column.addView(
                TextView(ctx).apply {
                    text = "DefinitionDocumentBenchmark"
                    setTextColor(Color.WHITE)
                    setPadding(32, 24, 32, 24)
                },
            )
            holder = FrameLayout(ctx).apply { setBackgroundColor(Color.MAGENTA) }
            column.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            scroll = ScrollView(ctx).apply { addView(column) }
            root = FrameLayout(ctx).apply {
                setBackgroundColor(Color.BLACK)
                addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            wm.addView(
                root,
                WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.OPAQUE,
                ),
            )
        }
        var v1: Page? = null
        try {
            Thread.sleep(700)
            onMain {
                val loc = IntArray(2)
                scroll.getLocationOnScreen(loc)
                probeRect = Rect(loc[0], loc[1], loc[0] + scroll.width, loc[1] + scroll.height)
                holder.getLocationOnScreen(loc)
                markerX = loc[0] + holder.width / 2
                markerY = loc[1] + (6 * density).roundToInt()
            }
            out("WINDOW probe=$probeRect marker=($markerX,$markerY)")
            sampler.start()
            samplerOn = true
            // ---- 0. the same scroll over plain TextViews, before any WebView ----
            var nativeColumn: LinearLayout? = null
            onMain {
                val c = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(0xFF242424.toInt())
                }
                repeat(wantRows) { i ->
                    val r = rows[i % rows.size]
                    c.addView(
                        TextView(ctx).apply {
                            text = r.displayWord + "  " + r.reading + "\n" + r.meaning.take(400)
                            setTextColor(Color.WHITE)
                            textSize = 16f
                            setPadding(32, 28, 32, 28)
                            minHeight = (200 * density).roundToInt()
                        },
                    )
                }
                holder.addView(c, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                nativeColumn = c
            }
            Thread.sleep(600)
            var nativeMax = 0
            onMain { nativeMax = max(0, scroll.getChildAt(0).height - scroll.height) }
            out("DOC [native] contentHeight=${nativeMax + scroll.height}px viewport=${scroll.height}px")
            for (speed in intArrayOf(2_000, 8_000, 20_000)) {
                scrollPass("native_down_${speed}_clean", 0, min(nativeMax, speed * 3), speed, sampled = false)
            }
            onMain {
                holder.removeView(nativeColumn)
                scroll.scrollTo(0, 0)
                scrollYNow = 0
            }
            samplerOn = true
            Thread.sleep(1000)
            // The control: this window and the screenshot thread, no WebView yet.
            mem("window up, no WebView")

            // ---- 1. the first WebView of the process ----
            var ctorMs = 0L
            onMain {
                val t = now()
                val shell = DefinitionShell.create(ctx)
                ctorMs = now() - t
                if (shell != null) {
                    val v = Page(shell)
                    holder.addView(v.frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
                    v1 = v
                }
            }
            assumeTrue("no WebView provider", v1 != null)
            val first = v1!!
            out("COLD first WebView constructor=${ctorMs}ms (add the swap below for shell + first content)")
            swap(first, clamped[0], css, lang, "cold_first_view_one_row")
            mem("one view alive, one row")

            // ---- 2. a spare shell while one view is alive ----
            var s2: DefinitionShell? = null
            var t2 = 0L
            onMain {
                t2 = now()
                s2 = DefinitionShell.create(ctx)
            }
            val spareShell = s2!!
            val spareCtor = now() - t2
            var ready = -1L
            val deadline = now() + 10_000
            while (now() < deadline) {
                var r = false
                onMain { r = spareShell.isPageReady() }
                if (r) { ready = now() - t2; break }
                Thread.sleep(2)
            }
            out("SPARE second shell, never attached, while one view is alive: constructor=${spareCtor}ms pageReady=${ready}ms")
            mem("one view + one spare shell")
            var v2: Page? = null
            onMain {
                holder.removeView(first.frame)
                val v = Page(spareShell)
                holder.addView(v.frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
                v2 = v
            }
            val spare = v2!!
            swap(spare, clamped[1 % clamped.size], css, lang, "spare_taken_one_row")
            onMain {
                holder.removeView(spare.frame)
                spare.destroy()
                holder.addView(first.frame)
            }
            Thread.sleep(300)

            // ---- 3. warm swaps, one row ----
            for (i in 0 until 5) swap(first, clamped[(i + 2) % clamped.size], css, lang, "warm_one_row_$i")

            // ---- 4. the whole list at once, rows clamped like the words panel ----
            swap(first, clamped.joinToString(""), css, lang, "all_${wantRows}_rows_clamped")
            Thread.sleep(800)
            mem("$wantRows rows clamped")
            // The whole list replaced again, as each live capture would.
            for (i in 0 until 3) {
                val rotated = clamped.drop(i + 1) + clamped.take(i + 1)
                swap(first, rotated.joinToString(""), css, lang, "replace_all_${wantRows}_clamped_$i")
                Thread.sleep(400)
            }
            if (!memOnly) {
            scrollSuite("clamped$wantRows")
            if (!stopAfterClamped) {

            // ---- 5. the whole list at once, rows at full height ----
            swap(first, open.joinToString(""), css, lang, "all_${wantRows}_rows_open")
            Thread.sleep(800)
            mem("$wantRows rows open")
            scrollSuite("open$wantRows")

            // ---- 6. five times the list, full height: the height limit ----
            swap(first, huge.joinToString(""), css, lang, "all_${wantRows * 5}_rows_open")
            Thread.sleep(1200)
            mem("${wantRows * 5} rows open")
            var maxY = 0
            onMain { maxY = max(0, scroll.getChildAt(0).height - scroll.height) }
            out("DOC [huge] contentHeight=${maxY + probeRect.height()}px maxScroll=${maxY}px")
            scrollPass("huge_down_8000", 0, min(maxY, 40_000), 8_000, sampled = true)
            beginPhase("huge_bottom")
            onMain { scroll.scrollTo(0, maxY); scrollYNow = maxY }
            Thread.sleep(1500)
            snap("huge_bottom.jpg")
            onMain { scroll.scrollTo(0, maxY / 2); scrollYNow = maxY / 2 }
            Thread.sleep(1500)
            snap("huge_middle.jpg")

            // ---- 7. what the page thinks is visible ----
            val wv = first.shell.webView
            fun js(code: String): String {
                val l = CountDownLatch(1)
                var res = ""
                onMain { wv.evaluateJavascript(code) { r -> res = r ?: ""; l.countDown() } }
                l.await(10, TimeUnit.SECONDS)
                return res
            }
            onMain { scroll.scrollTo(0, 0); scrollYNow = 0 }
            Thread.sleep(500)
            out(
                "PROBE innerHeight|clientHeight|rootHeight|dpr|hasIO|ua = " + js(
                    "(function(){var r=document.getElementById('root');var k=r.children;var a=k[1],z=k[k.length-1];" +
                        "window.__io=[];if(window.IntersectionObserver){var o=new IntersectionObserver(function(es){es.forEach(function(e){" +
                        "window.__io.push((e.target===z?'last':'first')+':'+e.isIntersecting);});});o.observe(a);o.observe(z);}" +
                        "return [window.innerHeight,document.documentElement.clientHeight,Math.round(r.getBoundingClientRect().height)," +
                        "window.devicePixelRatio,!!window.IntersectionObserver,navigator.userAgent].join('|');})()",
                ),
            )
            Thread.sleep(800)
            out("PROBE IntersectionObserver, first row on screen and last row far below it: " + js("window.__io.join(';')"))

            // ---- 8. on demand: eight rows, then one more at a time ----
            swap(first, open.take(8).joinToString(""), css, lang, "prefix_8_rows_open")
            Thread.sleep(500)
            val appendMs = ArrayList<Long>()
            for (i in 8 until min(18, open.size)) {
                val t = now()
                // The page's own append (its layout report sizes the frame).
                val r = js(
                    "(function(){var t=performance.now();ptAppend(" + JSONObject.quote(open[i]) + "," + first.gen + ");" +
                        "var h=document.getElementById('root').getBoundingClientRect().height;" +
                        "return Math.ceil(h)+','+(performance.now()-t).toFixed(1);})()",
                ).trim('"')
                val round = now() - t
                appendMs += round
                val parts = r.split(",")
                out("APPEND row ${i + 1}: html=${open[i].length}ch roundTrip=${round}ms inPage=${parts.getOrNull(1)}ms")
                Thread.sleep(150)
            }
            out("APPEND median roundTrip=${appendMs.sorted().getOrNull(appendMs.size / 2)}ms of ${appendMs.size}")
            }
            }

            // ---- hold for a hand test ----
            if (holdSec > 0) {
                swap(first, open.joinToString(""), css, lang, "hold_${wantRows}_rows_open")
                samplerOn = false
                out("HOLD ${holdSec}s: scroll the document by hand now")
                Thread.sleep(holdSec * 1000L)
                samplerOn = true
            }

            // ---- 9. everything destroyed, then a shell from nothing ----
            samplerOn = false
            onMain {
                holder.removeView(first.frame)
                first.destroy()
            }
            v1 = null
            val tDestroyed = now()
            Thread.sleep(3000)
            mem("every WebView destroyed, 3s later")
            for (at in intArrayOf(15, 30, 60, 120)) {
                if (at > lingerSec) break
                val wait = at * 1000L - (now() - tDestroyed)
                if (wait > 0) Thread.sleep(wait)
                val mine = sandboxed().filterKeys { it !in baselineSandboxed }
                out("LINGER ${(now() - tDestroyed) / 1000}s after the last WebView was destroyed: renderer ${if (mine.isEmpty()) "gone" else "alive " + mine.keys}")
                if (mine.isNotEmpty()) mem("lingering renderer")
            }
            var s3: DefinitionShell? = null
            var t3 = 0L
            onMain {
                t3 = now()
                s3 = DefinitionShell.create(ctx)
            }
            val lone = s3!!
            var ready3 = -1L
            val deadline3 = now() + 10_000
            while (now() < deadline3) {
                var r = false
                onMain { r = lone.isPageReady() }
                if (r) { ready3 = now() - t3; break }
                Thread.sleep(2)
            }
            out("LONE shell built after every WebView was destroyed (a first definition after the surfaces closed): pageReady=${ready3}ms")
            onMain { lone.destroy() }
            out("DONE")
        } finally {
            samplerRun = false
            samplerOn = false
            onMain {
                v1?.destroy()
                root?.let { wm.removeView(it) }
            }
        }
    }
}
