package com.playtranslate

import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.playtranslate.language.LanguagePackStore
import com.playtranslate.language.PreloadResult
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.ui.DefAction
import com.playtranslate.ui.DefRow
import com.playtranslate.ui.DefinitionDocument
import com.playtranslate.ui.DefinitionDocumentView
import com.playtranslate.ui.DefinitionsDocument
import com.playtranslate.ui.RowState
import com.playtranslate.ui.WordDefinitionData
import com.playtranslate.ui.WordLookupContext
import com.playtranslate.ui.resolveWordRows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * A [DefinitionDocument] in a real WebView: the page's own script and
 * stylesheet, which no JVM test can run. Real rows from the device's pack
 * go into a document in a hardware overlay window; the test waits for the
 * page's layout reports and checks that every row and button came back as
 * a target, that rows past the first chunk were fed, that a tap in the page
 * (a click dispatched on the element, then real touches through the
 * window) reaches the host as that row's action, that a drag starting on
 * the document scrolls the host and is no tap, and that a patch redraws
 * one row.
 *
 * Run (never connectedAndroidTest, which uninstalls the app afterwards):
 *   ./gradlew :app:installDebug :app:installDebugAndroidTest
 *   adb shell am instrument -w -e class com.playtranslate.DefinitionDocumentSmokeTest \
 *     com.playtranslate.test/androidx.test.runner.AndroidJUnitRunner
 * Output: Log tag DefDocSmoke and a screenshot at
 *   /sdcard/Android/data/com.playtranslate/files/defdoc/smoke.jpg
 */
@RunWith(AndroidJUnit4::class)
class DefinitionDocumentSmokeTest {

    private val tag = "DefDocSmoke"
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ui: UiAutomation =
        instr.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private fun onMain(block: () -> Unit) = instr.runOnMainSync(block)

    private fun await(what: String, timeoutMs: Long = 20_000, done: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var ok = false
            onMain { ok = done() }
            if (ok) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    private fun pointer(action: Int, down: Long, x: Float, y: Float) {
        val ev = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        ev.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        instr.sendPointerSync(ev)
        ev.recycle()
    }

    private fun touch(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        pointer(android.view.MotionEvent.ACTION_DOWN, down, x, y)
        Thread.sleep(40)
        pointer(android.view.MotionEvent.ACTION_UP, down, x, y)
    }

    /** A finger held still at ([x], [y]) for [ms], then lifted. */
    private fun hold(x: Float, y: Float, ms: Long) {
        val down = SystemClock.uptimeMillis()
        pointer(android.view.MotionEvent.ACTION_DOWN, down, x, y)
        Thread.sleep(ms)
        pointer(android.view.MotionEvent.ACTION_UP, down, x, y)
    }

    private fun drag(x: Float, fromY: Float, toY: Float) {
        val down = SystemClock.uptimeMillis()
        pointer(android.view.MotionEvent.ACTION_DOWN, down, x, fromY)
        val steps = 20
        for (i in 1..steps) {
            Thread.sleep(12)
            pointer(android.view.MotionEvent.ACTION_MOVE, down, x, fromY + (toY - fromY) * i / steps)
        }
        Thread.sleep(60)
        pointer(android.view.MotionEvent.ACTION_UP, down, x, toY)
    }

    @Test
    fun rowsRenderReportTargetsTakeTapsAndPatch() {
        val app = instr.targetContext
        assumeTrue("JA pack not installed", LanguagePackStore.isInstalled(app, SourceLangId.JA))
        if (!Settings.canDrawOverlays(app)) {
            ui.executeShellCommand("appops set com.playtranslate SYSTEM_ALERT_WINDOW allow").close()
            Thread.sleep(500)
        }
        var looked: List<RowState> = emptyList()
        runBlocking {
            val engine = SourceLanguageEngines.get(app, SourceLangId.JA)
            assumeTrue("engine preload failed", engine.preload() == PreloadResult.Success)
            val text = "昨日、友達に聞いたんだけど、あの店のラーメンは本当に美味しいらしい。王国の歴史は千年前の大戦から始まった。"
            // Target "en": the pack's own glosses, never a machine translation.
            looked = resolveWordRows(app, WordLookupContext(engine, "en", Prefs(app).targetChineseVariant), engine.tokenize(text)).rows
        }
        assumeTrue("too few rows resolved: ${looked.size}", looked.size >= 12)
        val states = looked.take(12)

        fun rowsOf(stub: Set<Int>, speaking: Set<Int>) = states.mapIndexed { i, s ->
            DefRow.Word(
                id = "row$i",
                data = WordDefinitionData(
                    word = s.displayWord, reading = s.reading.ifEmpty { null }, senses = s.senses,
                    freqScore = s.freqScore, isCommon = s.isCommon, pitch = s.pitch,
                    frequencies = s.frequencies, readingRows = s.readingRows,
                    importedGroups = s.importedGroups, inflectedForms = s.inflectedForms,
                ),
                speak = if (i in speaking) DefRow.Speak.LOADING else DefRow.Speak.IDLE,
                trailing = if (i in stub) DefRow.Trailing.SHOW else DefRow.Trailing.HIDE,
            )
        }

        val themed = ContextThemeWrapper(app, R.style.Theme_PlayTranslate)
        val wm = app.getSystemService(WindowManager::class.java)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val actions = ArrayList<Pair<String, String>>()
        var layouts = 0
        var root: FrameLayout? = null
        var slot: LinearLayout? = null
        var doc: DefinitionDocument? = null
        try {
            onMain {
                val column = LinearLayout(themed).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(themed.themeColor(R.attr.ptCard))
                }
                slot = column
                val scroll = ScrollView(themed).apply { addView(column) }
                root = FrameLayout(themed).apply {
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
                doc = DefinitionDocument(
                    themed, column, scope,
                    theme = {
                        DefinitionsDocument.DocTheme(
                            DefinitionsDocument.Tokens(
                                text = themed.themeColor(R.attr.ptText),
                                textMuted = themed.themeColor(R.attr.ptTextMuted),
                                textHint = themed.themeColor(R.attr.ptTextHint),
                                accent = themed.themeColor(R.attr.ptAccent),
                                panel = themed.themeColor(R.attr.ptCard),
                                baseFontSizePx = 16.5f,
                            ),
                            divider = themed.themeColor(R.attr.ptDivider),
                            band = com.playtranslate.ui.hiddenWordBandColor(themed),
                        )
                    },
                    onAction = { row, act -> actions += row.id to act },
                    onLayout = { layouts++ },
                )
            }
            val d = doc!!
            val t0 = SystemClock.uptimeMillis()
            onMain { d.setRows(rowsOf(stub = setOf(2), speaking = setOf(1)), "ja") }
            await("the first layout") { layouts > 0 }
            Log.i(tag, "first layout ${SystemClock.uptimeMillis() - t0} ms after setRows (shell from nothing included)")
            await("all twelve rows as targets") { d.targets.map { it.rowId }.distinct().size == 12 }
            Log.i(tag, "all 12 rows laid out ${SystemClock.uptimeMillis() - t0} ms after setRows, $layouts layout reports")

            var targets = emptyList<com.playtranslate.ui.DocTarget>()
            onMain { targets = d.targets }
            fun acts(row: String) = targets.filter { it.rowId == row }.map { it.act }.toSet()
            assertEquals(setOf(DefAction.OPEN, DefAction.SPEAK, DefAction.TRAILING), acts("row0"))
            assertEquals("a stub has no speak button", setOf(DefAction.OPEN, DefAction.TRAILING), acts("row2"))
            assertEquals("rows past the first chunk were fed", setOf(DefAction.OPEN, DefAction.SPEAK, DefAction.TRAILING), acts("row11"))
            val open0 = targets.first { it.rowId == "row0" && it.act == DefAction.OPEN }
            val open1 = targets.first { it.rowId == "row1" && it.act == DefAction.OPEN }
            assertTrue("rows stack: ${open0.top} then ${open1.top}", open1.top >= open0.top + open0.height - 1)
            assertTrue("a row spans the page: ${open0.width}", open0.width > 200)
            val speak0 = targets.first { it.rowId == "row0" && it.act == DefAction.SPEAK }
            assertEquals("a button is the native slot's size", 36 to 40, speak0.width to speak0.height)

            // A tap in the page: the speak button of the first row, then the row itself.
            var web: WebView? = null
            onMain { web = (slot!!.getChildAt(0) as DefinitionDocumentView).pageView as WebView }
            onMain { web!!.evaluateJavascript("document.querySelector('[data-row] [data-act=\"speak\"]').click()", null) }
            await("the speak tap") { actions.isNotEmpty() }
            assertEquals(listOf("row0" to DefAction.SPEAK), actions.toList())
            onMain { web!!.evaluateJavascript("document.querySelector('[data-row] .body, [data-row] .title').click()", null) }
            await("the row tap") { actions.size == 2 }
            assertEquals("row0" to DefAction.OPEN, actions[1])

            // Real touches, through the window: a tap on a row's text is that
            // row's tap, a tap on its eye is the eye's, and a drag that starts
            // on the document scrolls the HOST and is no tap at all.
            val r = android.graphics.Rect()
            fun screenRect(row: String, act: String): android.graphics.Rect {
                var ok = false
                onMain { ok = com.playtranslate.ui.DocNavTarget(d, row, act).rectOnScreen(r) }
                assertTrue("no rect for $row/$act", ok)
                return android.graphics.Rect(r)
            }
            val row1 = screenRect("row1", DefAction.OPEN)
            touch(row1.left + row1.width() / 4f, row1.bottom - row1.height() / 5f)
            await("the touch tap on row1") { actions.size == 3 }
            assertEquals("row1" to DefAction.OPEN, actions[2])
            val eye = screenRect("row3", DefAction.TRAILING)
            touch(eye.exactCenterX(), eye.exactCenterY())
            await("the touch tap on row3's eye") { actions.size == 4 }
            assertEquals("row3" to DefAction.TRAILING, actions[3])

            var scroll: ScrollView? = null
            var screenH = 0
            var screenW = 0
            onMain {
                scroll = slot!!.parent as ScrollView
                screenH = root!!.height
                screenW = root.width
            }
            drag(screenW / 2f, screenH * 0.75f, screenH * 0.25f)
            Thread.sleep(400)
            var scrolled = 0
            onMain { scrolled = scroll!!.scrollY }
            Log.i(tag, "a drag over the document scrolled the host by $scrolled px")
            assertTrue("a drag on the document scrolls the host: $scrolled", scrolled > screenH / 4)
            assertEquals("a drag is no tap", 4, actions.size)
            onMain { scroll!!.scrollTo(0, 0) }
            Thread.sleep(200)

            // A patch: the first row becomes a stub, and only its targets change.
            val before = layouts
            val t1 = SystemClock.uptimeMillis()
            onMain { d.setRows(rowsOf(stub = setOf(0, 2), speaking = setOf(1)), "ja") }
            await("the patched row's layout") { d.targets.none { it.rowId == "row0" && it.act == DefAction.SPEAK } }
            Log.i(tag, "patch laid out ${SystemClock.uptimeMillis() - t1} ms after setRows, ${layouts - before} reports")
            onMain { targets = d.targets }
            assertFalse(targets.any { it.rowId == "row0" && it.act == DefAction.SPEAK })
            assertEquals(12, targets.map { it.rowId }.distinct().size)

            Thread.sleep(600)
            val dir = File(app.getExternalFilesDir(null), "defdoc").apply { mkdirs() }
            ui.takeScreenshot()?.let { bmp ->
                FileOutputStream(File(dir, "smoke.jpg")).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                bmp.recycle()
            }
        } finally {
            onMain {
                doc?.release()
                root?.let { wm.removeView(it) }
            }
            scope.cancel()
        }
    }
    /**
     * The lens's rows in a real WebView, in a card of the lens's width: the
     * app's word row with everything shown, for the tapped unit and a
     * related one, a word with no entry and its placeholder, and the "Load
     * more" row. Each row and each of its buttons comes back as a target
     * for its own action; a tap on the "Load more" row reaches the host as
     * that row's; and a finger held on a row's Anki button is that
     * button's hold and not also its tap, where a quick touch is the tap.
     */
    @Test
    fun lensRowsRenderAndReportTheirActions() {
        val app = instr.targetContext
        assumeTrue("JA pack not installed", LanguagePackStore.isInstalled(app, SourceLangId.JA))
        var looked: List<RowState> = emptyList()
        runBlocking {
            val engine = SourceLanguageEngines.get(app, SourceLangId.JA)
            assumeTrue("engine preload failed", engine.preload() == PreloadResult.Success)
            looked = resolveWordRows(
                app, WordLookupContext(engine, "en", Prefs(app).targetChineseVariant),
                engine.tokenize("友達に聞いた"),
            ).rows
        }
        assumeTrue("too few rows resolved: ${looked.size}", looked.size >= 2)
        fun dataOf(s: RowState) = WordDefinitionData(
            word = s.displayWord, reading = s.reading.ifEmpty { null }, senses = s.senses,
            freqScore = s.freqScore, isCommon = s.isCommon, pitch = s.pitch, frequencies = s.frequencies,
            importedGroups = s.importedGroups, inflectedForms = s.inflectedForms,
        )
        fun lensRow(id: String, data: WordDefinitionData, opens: Boolean, anki: Boolean, label: String? = null) = DefRow.Word(
            id, data, opens = opens, speak = DefRow.Speak.IDLE,
            trailing = if (anki) DefRow.Trailing.ANKI else null, trailingHolds = anki,
            full = true, label = label, emptyText = "No definitions found.",
        )
        val rows = listOf(
            lensRow("p", dataOf(looked[0]), opens = true, anki = true),
            lensRow("s0", dataOf(looked[1]), opens = true, anki = true, label = "Machine translated"),
            lensRow(
                "s1", WordDefinitionData("星と深淵を目指せ", "ほしとしんえんをめざせ", emptyList(), freqScore = 0, isCommon = false),
                opens = false, anki = false,
            ),
            DefRow.More("more", "Load more (2)"),
        )

        val themed = ContextThemeWrapper(app, R.style.Theme_PlayTranslate)
        val wm = app.getSystemService(WindowManager::class.java)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val actions = ArrayList<Pair<String, String>>()
        var root: FrameLayout? = null
        var slot: FrameLayout? = null
        var doc: DefinitionDocument? = null
        try {
            onMain {
                val density = themed.resources.displayMetrics.density
                val card = FrameLayout(themed).apply { setBackgroundColor(themed.themeColor(R.attr.ptSurface)) }
                slot = card
                root = FrameLayout(themed).apply {
                    setBackgroundColor(Color.BLACK)
                    addView(
                        card,
                        FrameLayout.LayoutParams((300 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = (60 * density).toInt()
                            leftMargin = (20 * density).toInt()
                        },
                    )
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
                doc = DefinitionDocument(
                    themed, card, scope,
                    theme = {
                        DefinitionsDocument.DocTheme(
                            DefinitionsDocument.Tokens(
                                text = themed.themeColor(R.attr.ptText),
                                textMuted = themed.themeColor(R.attr.ptTextMuted),
                                textHint = themed.themeColor(R.attr.ptTextHint),
                                accent = themed.themeColor(R.attr.ptAccent),
                                panel = themed.themeColor(R.attr.ptSurface),
                                baseFontSizePx = 16.5f * 0.8f,
                            ),
                            divider = themed.themeColor(R.attr.ptDivider),
                            band = 0,
                            link = themed.themeColor(R.attr.ptTextLink),
                        )
                    },
                    onAction = { row, act -> actions += row.id to act },
                )
            }
            val d = doc!!
            onMain { d.setRows(rows, "ja") }
            await("the lens rows as targets") { d.targets.map { it.rowId }.distinct().size == 4 }
            var targets = emptyList<com.playtranslate.ui.DocTarget>()
            onMain { targets = d.targets }
            fun acts(row: String) = targets.filter { it.rowId == row && it.act.isNotEmpty() }.map { it.act }.toSet()
            assertEquals(setOf(DefAction.OPEN, DefAction.SPEAK, DefAction.TRAILING), acts("p"))
            assertEquals(setOf(DefAction.OPEN, DefAction.SPEAK, DefAction.TRAILING), acts("s0"))
            assertEquals("a row that does not open still speaks", setOf(DefAction.SPEAK), acts("s1"))
            assertEquals(setOf(DefAction.MORE), acts("more"))

            fun rect(row: String, act: String): android.graphics.Rect {
                val r = android.graphics.Rect()
                var ok = false
                onMain { ok = com.playtranslate.ui.DocNavTarget(d, row, act).rectOnScreen(r) }
                assertTrue("no rect for $row/$act", ok)
                return r
            }
            // The controller's cursor on a row's button, then a real touch on "Load more".
            onMain { d.setCursor(targets.first { it.rowId == "s0" && it.act == DefAction.SPEAK }) }
            val more = rect("more", DefAction.MORE)
            touch(more.exactCenterX(), more.exactCenterY())
            await("the tap on Load more") { actions.isNotEmpty() }
            assertEquals(listOf("more" to DefAction.MORE), actions.toList())

            // A quick touch on a row's Anki button is its tap; a finger held
            // there is its hold, once, and lifting is not also a tap.
            val anki = rect("p", DefAction.TRAILING)
            touch(anki.exactCenterX(), anki.exactCenterY())
            await("the tap on Anki") { actions.size == 2 }
            assertEquals("p" to DefAction.TRAILING, actions.last())
            hold(anki.exactCenterX(), anki.exactCenterY(), 900)
            await("the hold on Anki") { actions.size == 3 }
            assertEquals("p" to DefAction.hold(DefAction.TRAILING), actions.last())
            Thread.sleep(400)
            assertEquals("lifting after a hold reported a tap as well", 3, actions.size)
            // A row's Speak button is its own action, never the row's open.
            val speak = rect("p", DefAction.SPEAK)
            touch(speak.exactCenterX(), speak.exactCenterY())
            await("the tap on Speak") { actions.size == 4 }
            assertEquals("p" to DefAction.SPEAK, actions.last())

            Thread.sleep(600)
            val dir = File(app.getExternalFilesDir(null), "defdoc").apply { mkdirs() }
            ui.takeScreenshot()?.let { bmp ->
                FileOutputStream(File(dir, "smoke_lens.jpg")).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                bmp.recycle()
            }
        } finally {
            onMain {
                doc?.release()
                root?.let { wm.removeView(it) }
            }
            scope.cancel()
        }
    }

    /**
     * The word row's head in a real engine, the one thing about it no JVM
     * test can run: where its buttons stand. Five rows in a card 300dp wide
     * at the Words card's text size, each with the explain button, Speak, a
     * second button and the chevron, which leaves the headword about 92px
     * beside the buttons and about 256px on a line of its own:
     *  - a short headword keeps its buttons on its line;
     *  - a headword that fits a line but not beside the buttons keeps the
     *    line whole, and the buttons stand under it, at the line's end:
     *    one Latin word, a phrase with spaces (which could break at them),
     *    and kana drawn mora by mora under an accent line (which could break
     *    between any two);
     *  - a headword wider than the whole line wraps inside its own box and
     *    never runs past the head, its buttons under it;
     *  - an open explain answer stands under the head, with its ✕ a target.
     * Synthetic rows, so it needs no pack. Leaves a screenshot beside the
     * other smoke shots (smoke_head.jpg).
     */
    @Test
    fun aHeadwordTooLongForItsButtonsKeepsItsLineAndTheButtonsWrapUnder() {
        val app = instr.targetContext
        fun data(word: String, reading: String? = null, pitch: List<Int> = emptyList()) = WordDefinitionData(
            word, reading,
            listOf(com.playtranslate.ui.SenseDisplay(listOf("noun"), "a definition", emptyList())),
            freqScore = 0, isCommon = false, pitch = pitch,
        )
        fun row(id: String, data: WordDefinitionData, explain: DefRow.Explain = DefRow.Explain()) = DefRow.Word(
            id, data, opens = true, speak = DefRow.Speak.IDLE, trailing = DefRow.Trailing.HIDE, explain = explain,
        )
        val rows = listOf(
            row("short", data("猫", "ねこ")),
            row("latin", data("Entschuldigung")),
            row("phrase", data("in spite of all")),
            row("kana", data("いらっしゃいませ", pitch = listOf(6))),
            row("huge", data("Geschwindigkeitsbegrenzungen")),
            row(
                "asked", data("犬", "いぬ"),
                DefRow.Explain(answer = DefRow.Answer("In this sentence", "It names the animal.", footer = "Answered by a test")),
            ),
        )
        val order = rows.map { it.id }

        val themed = ContextThemeWrapper(app, R.style.Theme_PlayTranslate)
        val wm = app.getSystemService(WindowManager::class.java)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var root: FrameLayout? = null
        var doc: DefinitionDocument? = null
        try {
            onMain {
                val density = themed.resources.displayMetrics.density
                val card = FrameLayout(themed).apply { setBackgroundColor(themed.themeColor(R.attr.ptSurface)) }
                root = FrameLayout(themed).apply {
                    setBackgroundColor(Color.BLACK)
                    addView(
                        card,
                        FrameLayout.LayoutParams((300 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = (60 * density).toInt()
                            leftMargin = (20 * density).toInt()
                        },
                    )
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
                doc = DefinitionDocument(
                    themed, card, scope,
                    theme = {
                        DefinitionsDocument.DocTheme(
                            DefinitionsDocument.Tokens(
                                text = themed.themeColor(R.attr.ptText),
                                textMuted = themed.themeColor(R.attr.ptTextMuted),
                                textHint = themed.themeColor(R.attr.ptTextHint),
                                accent = themed.themeColor(R.attr.ptAccent),
                                panel = themed.themeColor(R.attr.ptSurface),
                                baseFontSizePx = 16.5f,
                            ),
                            divider = themed.themeColor(R.attr.ptDivider),
                            band = 0,
                            link = themed.themeColor(R.attr.ptTextLink),
                        )
                    },
                    onAction = { _, _ -> },
                )
            }
            val d = doc!!
            onMain { d.setRows(rows, "ja") }
            await("the rows as targets") { d.targets.map { it.rowId }.distinct().size == rows.size }
            var targets = emptyList<com.playtranslate.ui.DocTarget>()
            onMain { targets = d.targets }
            fun t(row: String, act: String) = targets.first { it.rowId == row && it.act == act }
            fun box(row: String) = targets.first { it.rowId == row }

            // What only the page knows: each headword's own box, against its
            // head's. One line of a headword is 1.636 x 16.5 x 1.4 = 38px.
            var measured: String? = null
            onMain {
                val web = (d.hostView as DefinitionDocumentView).pageView as WebView
                web.evaluateJavascript(
                    """(function(){var out=[];var rows=document.querySelectorAll('[data-row]');
                    for(var i=0;i<rows.length;i++){var h=rows[i].querySelector('.head'),w=h.querySelector('.w');
                    var hb=h.getBoundingClientRect(),wb=w.getBoundingClientRect();
                    out.push(Math.round(wb.height)+','+Math.round(wb.right-hb.right)+','+Math.round(wb.bottom-hb.top));}
                    return out.join(';');})()""",
                ) { measured = it }
            }
            await("the page's own measure of the headwords") { measured != null }
            val words = measured!!.trim('"').split(';').map { m -> m.split(',').map { it.toInt() } }
            fun headwordHeight(row: String) = words[order.indexOf(row)][0]
            fun headwordPastHead(row: String) = words[order.indexOf(row)][1]
            fun headwordBottom(row: String) = words[order.indexOf(row)][2]
            Log.i("DefDocSmoke", "head: targets=$targets words=$measured")

            val acts = listOf(DefAction.EXPLAIN, DefAction.SPEAK, DefAction.TRAILING)
            // Every row: the three buttons level with one another, in order.
            for (row in order) {
                val tops = acts.map { t(row, it).top }
                assertEquals("$row: the buttons are level", 1, tops.distinct().size)
                val lefts = acts.map { t(row, it).left }
                assertEquals("$row: explain, speak, then the second button", lefts.sorted(), lefts)
                assertTrue("$row: the headword stays inside its head", headwordPastHead(row) <= 1)
            }
            // A short headword: the buttons on its line, at the row's top.
            assertTrue(
                "short: the buttons stand beside the headword (${t("short", DefAction.EXPLAIN)} in ${box("short")})",
                t("short", DefAction.EXPLAIN).top - box("short").top <= 14,
            )
            assertTrue("short: one line", headwordHeight("short") <= 45)
            // Fits a line, not beside the buttons: the line is kept whole and
            // the buttons stand under it, ending where the short row's end.
            for (row in listOf("latin", "phrase", "kana")) {
                assertTrue("$row: the headword is on one line (${headwordHeight(row)}px)", headwordHeight(row) <= 50)
                assertTrue(
                    "$row: the buttons stand under the headword",
                    t(row, DefAction.EXPLAIN).top - box(row).top >= headwordBottom(row) - 2,
                )
                assertEquals(
                    "$row: at the line's end, where a row's buttons always end",
                    t("short", DefAction.TRAILING).left, t(row, DefAction.TRAILING).left,
                )
            }
            // Wider than the whole line: it wraps in its own box, the buttons under it.
            assertTrue("huge: the headword wraps (${headwordHeight("huge")}px)", headwordHeight("huge") >= 60)
            assertTrue(
                "huge: the buttons stand under all of it",
                t("huge", DefAction.EXPLAIN).top - box("huge").top >= headwordBottom("huge") - 2,
            )
            // An open answer: its X is a target, under the head's buttons.
            val close = t("asked", DefAction.EXPLAIN_CLOSE)
            assertTrue("asked: the answer stands under the head", close.top >= t("asked", DefAction.EXPLAIN).top + 36)
            assertEquals(
                setOf(DefAction.OPEN, DefAction.EXPLAIN, DefAction.SPEAK, DefAction.TRAILING, DefAction.EXPLAIN_CLOSE),
                targets.filter { it.rowId == "asked" && it.act.isNotEmpty() }.map { it.act }.toSet(),
            )

            Thread.sleep(600)
            val dir = File(app.getExternalFilesDir(null), "defdoc").apply { mkdirs() }
            ui.takeScreenshot()?.let { bmp ->
                FileOutputStream(File(dir, "smoke_head.jpg")).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                bmp.recycle()
            }
        } finally {
            onMain {
                doc?.release()
                root?.let { wm.removeView(it) }
            }
            scope.cancel()
        }
    }

    /**
     * The rows of a word's own page in a real engine: plain-text imported
     * definitions as page senses under their dictionary's name, then
     * numbered senses with the eyebrow, the note and examples. None is a
     * tap target, and a late example translation redraws its sense.
     * Synthetic rows, so it needs no pack.
     */
    @Test
    fun pageSenseRowsRenderAndTakeALateTranslation() {
        val app = instr.targetContext
        val imported = DefRow.Imported(
            "imported",
            listOf(
                com.playtranslate.model.ImportedSenseGroup(
                    "大辞林",
                    listOf(
                        com.playtranslate.model.ImportedSense("食物を口に入れ、かんで飲み込む。", "動詞"),
                        com.playtranslate.model.ImportedSense("生計を立てる。暮らす。"),
                    ),
                    accentColor = 0xFFE0A030.toInt(), dictId = "daijirin",
                ),
                com.playtranslate.model.ImportedSenseGroup(
                    "Plain EN", listOf(com.playtranslate.model.ImportedSense("to eat; to consume")), dictId = "plain",
                ),
            ),
        )
        fun senses(late: String) = listOf(
            DefRow.Sense(
                "s0", 1, "ICHIDAN VERB, TRANSITIVE VERB", "to eat",
                examples = listOf(
                    DefRow.Example("朝ごはんを食べましたか。", "Did you eat breakfast?", italic = true),
                    DefRow.Example("彼は毎日同じ店で昼ごはんを食べるので、店の人はもう注文を聞かない。", late, italic = true),
                ),
            ),
            DefRow.Sense(
                "s1", 2, "ICHIDAN VERB", "to live on (e.g. a salary); to live off; to subsist on",
                misc = "usually written using kana alone",
            ),
            DefRow.Sense("s2", 3, "", "to take in; to absorb"),
        )
        val themed = ContextThemeWrapper(app, R.style.Theme_PlayTranslate)
        val wm = app.getSystemService(WindowManager::class.java)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var root: FrameLayout? = null
        var doc: DefinitionDocument? = null
        try {
            onMain {
                val density = themed.resources.displayMetrics.density
                val card = FrameLayout(themed).apply { setBackgroundColor(themed.themeColor(R.attr.ptCard)) }
                root = FrameLayout(themed).apply {
                    setBackgroundColor(themed.themeColor(R.attr.ptBg))
                    addView(
                        card,
                        FrameLayout.LayoutParams((360 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = (60 * density).toInt()
                            leftMargin = (16 * density).toInt()
                        },
                    )
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
                doc = DefinitionDocument(
                    themed, card, scope,
                    theme = { com.playtranslate.ui.cardDocTheme(themed, 16.5f) },
                    onAction = { _, _ -> },
                )
            }
            val d = doc!!
            onMain { d.setRows(listOf(imported) + senses(late = ""), "ja") }
            await("the page rows laid out") { d.targets.map { it.rowId }.distinct().size == 4 }
            var targets = emptyList<com.playtranslate.ui.DocTarget>()
            var before = 0
            onMain { targets = d.targets; before = d.heightPx }
            assertTrue("no row of a page's definitions is a tap target", targets.all { it.act.isEmpty() })
            var view: DefinitionDocumentView? = null
            onMain { view = d.hostView as DefinitionDocumentView }
            assertFalse("a block of plain senses is no focus stop", view!!.isFocusable)

            // The second example's translation arrives: its sense grows by a line.
            val t0 = SystemClock.uptimeMillis()
            onMain { d.setRows(listOf(imported) + senses(late = "He eats lunch at the same place every day."), "ja") }
            await("the late translation laid out") { d.heightPx > before }
            Log.i(tag, "late translation: ${SystemClock.uptimeMillis() - t0} ms, height $before -> ${d.heightPx}")

            Thread.sleep(600)
            val dir = File(app.getExternalFilesDir(null), "defdoc").apply { mkdirs() }
            ui.takeScreenshot()?.let { bmp ->
                FileOutputStream(File(dir, "smoke_page.jpg")).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                bmp.recycle()
            }
        } finally {
            onMain {
                doc?.release()
                root?.let { wm.removeView(it) }
            }
            scope.cancel()
        }
    }

    /**
     * The Anki word sheet's card in a real engine: every remove button comes
     * back as a target of its own action, and a real touch on each kind (an
     * imported definition's, an example's, a sense's) reaches the host as
     * that row's action with the index it was drawn with. Synthetic rows.
     */
    @Test
    fun sheetRowsReportTheirRemoveButtons() {
        val app = instr.targetContext
        val rows = listOf(
            DefRow.Imported(
                "imported",
                listOf(
                    com.playtranslate.model.ImportedSenseGroup(
                        "大辞林",
                        listOf(
                            com.playtranslate.model.ImportedSense("removed already"),
                            com.playtranslate.model.ImportedSense("食物を口に入れ、かんで飲み込む。", "動詞"),
                            com.playtranslate.model.ImportedSense("生計を立てる。暮らす。"),
                        ),
                        dictId = "daijirin",
                    ),
                ),
                DefRow.SenseLook.SHEET, DefRow.Curation(removed = setOf(0), removable = true),
            ),
            DefRow.Sense(
                "s0", 1, "ICHIDAN VERB, TRANSITIVE VERB", "to eat",
                examples = listOf(
                    DefRow.Example("朝ごはんを食べましたか。", "Did you eat breakfast?", italic = false, removeIndex = 0),
                    DefRow.Example(
                        "彼は毎日同じ店で昼ごはんを食べるので、店の人はもう注文を聞かない。",
                        "He eats lunch at the same place every day.", italic = false, removeIndex = 2,
                    ),
                ),
                look = DefRow.SenseLook.SHEET, removable = true,
            ),
            DefRow.Sense(
                "s3", 2, "ICHIDAN VERB", "to live on (e.g. a salary); to live off; to subsist on",
                misc = "usually written using kana alone", look = DefRow.SenseLook.SHEET, removable = true,
            ),
        )
        val themed = ContextThemeWrapper(app, R.style.Theme_PlayTranslate)
        val wm = app.getSystemService(WindowManager::class.java)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val actions = java.util.Collections.synchronizedList(ArrayList<Pair<String, String>>())
        var root: FrameLayout? = null
        var doc: DefinitionDocument? = null
        try {
            onMain {
                val density = themed.resources.displayMetrics.density
                val card = FrameLayout(themed).apply { setBackgroundColor(themed.themeColor(R.attr.ptCard)) }
                root = FrameLayout(themed).apply {
                    setBackgroundColor(themed.themeColor(R.attr.ptBg))
                    addView(
                        card,
                        FrameLayout.LayoutParams((328 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = (60 * density).toInt()
                            leftMargin = (16 * density).toInt()
                        },
                    )
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
                doc = DefinitionDocument(
                    themed, card, scope,
                    theme = { com.playtranslate.ui.cardDocTheme(themed, 15f) },
                    onAction = { row, act -> actions += row.id to act },
                )
            }
            val d = doc!!
            onMain { d.setRows(rows, "ja") }
            await("the sheet rows laid out") { d.targets.map { it.rowId }.distinct().size == 3 }
            var targets = emptyList<com.playtranslate.ui.DocTarget>()
            onMain { targets = d.targets }
            fun acts(row: String) = targets.filter { it.rowId == row && it.act.isNotEmpty() }.map { it.act }.toSet()
            assertEquals(setOf(DefAction.removeItem(1), DefAction.removeItem(2)), acts("imported"))
            assertEquals(setOf(DefAction.removeItem(0), DefAction.removeItem(2), DefAction.REMOVE), acts("s0"))
            assertEquals(setOf(DefAction.REMOVE), acts("s3"))
            var view: DefinitionDocumentView? = null
            onMain { view = d.hostView as DefinitionDocumentView }
            assertTrue("a card with something to remove is a focus stop", view!!.isFocusable)

            // The controller's cursor on a button, then one real touch on each kind.
            onMain { d.setCursor(targets.first { it.rowId == "s3" && it.act == DefAction.REMOVE }) }
            val taps = listOf(
                "imported" to DefAction.removeItem(2),
                "s0" to DefAction.removeItem(2),
                "s0" to DefAction.REMOVE,
            )
            for ((n, tap) in taps.withIndex()) {
                val r = android.graphics.Rect()
                var ok = false
                onMain { ok = com.playtranslate.ui.DocNavTarget(d, tap.first, tap.second).rectOnScreen(r) }
                assertTrue("no rect for $tap", ok)
                touch(r.exactCenterX(), r.exactCenterY())
                await("the tap on $tap") { actions.size == n + 1 }
            }
            assertEquals(taps, actions.toList())

            Thread.sleep(600)
            val dir = File(app.getExternalFilesDir(null), "defdoc").apply { mkdirs() }
            ui.takeScreenshot()?.let { bmp ->
                FileOutputStream(File(dir, "smoke_sheet.jpg")).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                bmp.recycle()
            }
        } finally {
            onMain {
                doc?.release()
                root?.let { wm.removeView(it) }
            }
            scope.cancel()
        }
    }

    /**
     * The Anki sentence sheet's word rows in a real engine: a target with
     * its audio row, a plain word, a stub. Every control comes back as a
     * target of its own action, and real touches pin the rules only the
     * page's hit testing decides: a tap on the audio row is the switch, never
     * the target; the eye's tap area is a 48px square around its glyph; and
     * what that square overlaps of the audio row stays the audio row's.
     * Synthetic rows.
     */
    @Test
    fun sheetWordRowsTakeTheirTaps() {
        val app = instr.targetContext
        fun word(id: String, word: String, reading: String, meaning: String, target: Boolean = false, hidden: Boolean = false) =
            DefRow.SheetWord(
                id, word, reading, pitch = emptyList(), stars = "\u2605\u2605\u2605", target = target, hidden = hidden,
                meaning = meaning, importedGroups = emptyList(),
                audio = if (target) DefRow.Audio(on = true, play = DefRow.Play.IDLE, source = "Auto") else null,
            )
        val rows = listOf(
            word("neko", "猫", "ねこ", "cat", target = true),
            word("taberu", "食べる", "たべる", "to eat; to live on (e.g. a salary); to live off; to subsist on"),
            word("sakana", "魚", "さかな", "fish", hidden = true),
        )
        val themed = ContextThemeWrapper(app, R.style.Theme_PlayTranslate)
        val wm = app.getSystemService(WindowManager::class.java)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val actions = java.util.Collections.synchronizedList(ArrayList<Pair<String, String>>())
        var root: FrameLayout? = null
        var doc: DefinitionDocument? = null
        var density = 1f
        try {
            onMain {
                density = themed.resources.displayMetrics.density
                val card = FrameLayout(themed).apply { setBackgroundColor(themed.themeColor(R.attr.ptCard)) }
                root = FrameLayout(themed).apply {
                    setBackgroundColor(themed.themeColor(R.attr.ptBg))
                    addView(
                        card,
                        FrameLayout.LayoutParams((328 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = (60 * density).toInt()
                            leftMargin = (16 * density).toInt()
                        },
                    )
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
                doc = DefinitionDocument(
                    themed, card, scope,
                    theme = { com.playtranslate.ui.cardDocTheme(themed, 13f) },
                    onAction = { row, act -> actions += row.id to act },
                )
            }
            val d = doc!!
            onMain { d.setRows(rows, "ja") }
            await("the sheet words laid out") { d.targets.map { it.rowId }.distinct().size == 3 }
            var targets = emptyList<com.playtranslate.ui.DocTarget>()
            onMain { targets = d.targets }
            fun acts(row: String) = targets.filter { it.rowId == row && it.act.isNotEmpty() }.map { it.act }.toSet()
            assertEquals(
                setOf(DefAction.TARGET, DefAction.TRAILING, DefAction.AUDIO, DefAction.SPEAK, DefAction.SOURCE),
                acts("neko"),
            )
            assertEquals(setOf(DefAction.TARGET, DefAction.TRAILING), acts("taberu"))
            assertEquals("a stub is inert but for its eye", setOf(DefAction.TRAILING), acts("sakana"))

            fun rect(row: String, act: String): android.graphics.Rect {
                val r = android.graphics.Rect()
                var ok = false
                onMain { ok = com.playtranslate.ui.DocNavTarget(d, row, act).rectOnScreen(r) }
                assertTrue("no rect for $row/$act", ok)
                return r
            }
            fun expectTap(what: String, x: Float, y: Float, expected: Pair<String, String>) {
                val before = actions.size
                touch(x, y)
                await("the tap: $what") { actions.size == before + 1 }
                assertEquals(what, expected, actions.last())
            }
            val title = rect("neko", DefAction.SOURCE)   // any point of the audio row's own line
            val audio = rect("neko", DefAction.AUDIO)
            val eye = rect("neko", DefAction.TRAILING)
            val plainEye = rect("taberu", DefAction.TRAILING)
            val plainRow = rect("taberu", DefAction.TARGET)

            // The audio row's own area (clear of its two controls) is the switch.
            expectTap("the audio row", audio.right - 40 * density, audio.exactCenterY(), "neko" to DefAction.AUDIO)
            expectTap("the preview", rect("neko", DefAction.SPEAK).exactCenterX(), audio.exactCenterY(), "neko" to DefAction.SPEAK)
            expectTap("the source pill", title.exactCenterX(), title.exactCenterY(), "neko" to DefAction.SOURCE)
            // The row's body is the target toggle.
            expectTap("the row", plainRow.left + 40 * density, plainRow.bottom - 10 * density, "taberu" to DefAction.TARGET)
            // 18px under the eye's centre: outside the 20px glyph, inside its 48px square.
            expectTap("the eye's square", plainEye.exactCenterX(), plainEye.exactCenterY() + 18 * density, "taberu" to DefAction.TRAILING)
            // Under the target's eye the square reaches the audio row, which keeps what it covers.
            assertTrue("the square overlaps the audio row", audio.top + 4 * density < eye.exactCenterY() + 24 * density)
            expectTap("the eye's square over the audio row", eye.exactCenterX(), audio.top + 4 * density, "neko" to DefAction.AUDIO)
            // A stub's body does nothing; its eye shows the word.
            val stubEye = rect("sakana", DefAction.TRAILING)
            touch(stubEye.left - 120 * density, stubEye.exactCenterY())
            expectTap("the stub's eye", stubEye.exactCenterX(), stubEye.exactCenterY(), "sakana" to DefAction.TRAILING)
            assertEquals("the stub's body reported nothing", 7, actions.size)

            // A preview that is loading, then playing with the switch off.
            onMain {
                d.setRows(
                    listOf(
                        rows[0].copy(audio = DefRow.Audio(on = false, play = DefRow.Play.PLAYING, source = "Wikimedia Commons")),
                        rows[1], rows[2],
                    ),
                    "ja",
                )
            }
            Thread.sleep(600)
            val dir = File(app.getExternalFilesDir(null), "defdoc").apply { mkdirs() }
            ui.takeScreenshot()?.let { bmp ->
                FileOutputStream(File(dir, "smoke_words_off.jpg")).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                bmp.recycle()
            }
            onMain { d.setRows(rows, "ja") }
            Thread.sleep(600)
            ui.takeScreenshot()?.let { bmp ->
                FileOutputStream(File(dir, "smoke_words.jpg")).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                bmp.recycle()
            }
        } finally {
            onMain {
                doc?.release()
                root?.let { wm.removeView(it) }
            }
            scope.cancel()
        }
    }

    /**
     * Renderer death, on a real render process: the document is redrawn
     * once on a new page, a second death on the same rows leaves the notice
     * where the definitions were (no crash loop), and the next rows the
     * host sets draw again. The process is ended through the platform's own
     * call, which is what a low-memory kill looks like to the app.
     */
    @Test
    fun rendererDeathIsRecoveredOnceThenTheNoticeStandsUntilNewRows() {
        assumeTrue("needs WebViewRenderProcess.terminate", android.os.Build.VERSION.SDK_INT >= 29)
        val app = instr.targetContext
        fun rows(tag: String) = List(3) { i ->
            DefRow.Sense("$tag$i", i + 1, "NOUN", "definition $i of $tag")
        }
        val themed = ContextThemeWrapper(app, R.style.Theme_PlayTranslate)
        val wm = app.getSystemService(WindowManager::class.java)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var root: FrameLayout? = null
        var doc: DefinitionDocument? = null
        try {
            onMain {
                val density = themed.resources.displayMetrics.density
                val card = FrameLayout(themed).apply { setBackgroundColor(themed.themeColor(R.attr.ptCard)) }
                root = FrameLayout(themed).apply {
                    setBackgroundColor(themed.themeColor(R.attr.ptBg))
                    addView(
                        card,
                        FrameLayout.LayoutParams((328 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = (60 * density).toInt()
                            leftMargin = (16 * density).toInt()
                        },
                    )
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
                doc = DefinitionDocument(
                    themed, card, scope,
                    theme = { com.playtranslate.ui.cardDocTheme(themed, 16.5f) },
                    onAction = { _, _ -> },
                )
            }
            val d = doc!!
            // Read on the main thread: inside an await, or through onMain.
            fun laidOut(n: Int): Boolean = !d.showsNotice && d.targets.map { it.rowId }.distinct().size == n
            // The page the document shows now. Main thread.
            fun page(): WebView? = (d.hostView as? DefinitionDocumentView)?.pageView as? WebView

            /** Ends the render process and returns the page that died. */
            fun killRenderer(): WebView {
                var killed = false
                var dead: WebView? = null
                onMain {
                    dead = page()
                    killed = dead?.webViewRenderProcess?.terminate() == true
                }
                assertTrue("the render process could not be ended", killed)
                return dead!!
            }
            fun notice(): Boolean {
                var shown = false
                onMain { shown = d.showsNotice }
                return shown
            }
            fun noticeNow(): Boolean = d.showsNotice

            onMain { d.setRows(rows("a"), "ja") }
            await("the rows laid out") { laidOut(3) }
            // Let the spare shell load: it shares the render process, and the
            // death below must not leave it for the document to recover on.
            Thread.sleep(1500)

            // First death: the same rows, laid out by a NEW page, and it
            // stays up (the spare's own death notice, arriving late, is not a
            // second death of this document).
            val first = killRenderer()
            await("the rows redrawn on a new page after the first death") {
                val now = page()
                now != null && now !== first && laidOut(3)
            }
            Thread.sleep(1500)
            assertFalse("one death must not show the notice", notice())

            // Second death on the same rows: the notice, and it stays.
            killRenderer()
            await("the notice after the second death") { noticeNow() }
            Thread.sleep(1500)
            assertTrue("the document tried again on rows that already killed a page", notice())

            // New rows from the host draw again.
            onMain { d.setRows(rows("b"), "ja") }
            await("new rows drawn after the notice") { laidOut(3) }
            assertFalse(notice())
        } finally {
            onMain {
                doc?.release()
                root?.let { wm.removeView(it) }
            }
            scope.cancel()
        }
    }
}
