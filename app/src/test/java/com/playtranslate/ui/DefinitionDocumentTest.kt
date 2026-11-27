package com.playtranslate.ui

import android.graphics.Rect
import android.app.Activity
import android.content.ContextWrapper
import android.content.res.Resources
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import android.webkit.WebView
import android.widget.FrameLayout
import com.playtranslate.R
import com.playtranslate.model.ImportedSense
import com.playtranslate.model.ImportedSenseGroup
import com.playtranslate.yomitan.YomitanDataStore
import com.playtranslate.yomitan.YomitanDictionaryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * [DefinitionDocument]'s rules, driven as the page drives them: the test
 * plays the page (it finishes the shell's load, sends layout reports through
 * the bridge, kills the renderer) and reads what the document sent it.
 *
 * Rows are fed eight at a time and more only while the end is near; a
 * rebind with the same ids patches the rows that changed and nothing else;
 * a rebind with other ids is a new row set, and reports from the old one
 * are ignored; the view is 1 px only before its first layout; renderer
 * death is recovered once per content and the notice shows the second
 * time; a document with nothing to draw takes no shell; the view is a
 * focus stop only while a row offers something to act on; a dictionary
 * store that fails costs a chunk its styling, never its rows; and a row that
 * gains dictionary content under its id is fed again, not patched.
 */
@RunWith(RobolectricTestRunner::class)
class DefinitionDocumentTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private lateinit var activity: Activity
    private lateinit var slot: FrameLayout
    private lateinit var doc: DefinitionDocument
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val actions = mutableListOf<Pair<String, String>>()
    /** The taps on a row's text the host was told of, each with its row. */
    private val texts = mutableListOf<Pair<String, DefinitionDocument.TextTap>>()
    /** What the host was told of its marks: the tap, and the box or null. */
    private val marks = mutableListOf<Pair<Int, DefinitionDocument.MarkBox?>>()
    /** The host's `onLayout` calls. */
    private var layouts = 0

    @Before fun setUp() {
        DefinitionShells.reset()
        activity = Robolectric.buildActivity(Host::class.java).setup().get()
        slot = FrameLayout(activity)
        activity.setContentView(slot)
        doc = DefinitionDocument(
            activity, slot, scope,
            theme = {
                DefinitionsDocument.DocTheme(
                    DefinitionsDocument.Tokens(-1, -2, -3, -4, -5, 16.5f), divider = -6, band = -7,
                )
            },
            onAction = { row, act -> actions += row.id to act },
            onLayout = { layouts++ },
            // Inline, so a step's sends have happened when it returns.
            buildOn = Dispatchers.Main.immediate,
            onTextTap = { row, tap -> texts += row.id to tap },
        )
        doc.onMark = { tapId, box -> marks += tapId to box }
    }

    @After fun tearDown() {
        doc.release()
        scope.cancel()
        DefinitionShells.reset()
    }

    private fun row(id: String, meaning: String = "meaning of $id") = DefRow.Word(
        id,
        WordDefinitionData(
            word = id, reading = null, senses = listOf(SenseDisplay(emptyList(), meaning, emptyList())),
            freqScore = 0, isCommon = false,
        ),
        speak = DefRow.Speak.IDLE, trailing = DefRow.Trailing.HIDE,
    )

    private fun rows(n: Int) = (0 until n).map { row("w$it") }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val view: DefinitionDocumentView get() = slot.getChildAt(0) as DefinitionDocumentView
    private val web: WebView get() = view.pageView as WebView

    /** The shell's page finished loading: what was queued for it runs. */
    private fun pageReady() {
        shadowOf(web).webViewClient.onPageFinished(web, "")
        idle()
    }

    /** The last script the document ran in the page. */
    private fun sent(): String = shadowOf(web).lastEvaluatedJavascript.orEmpty()

    /** Waits for the page to be sent a script that [arrived] accepts. A row
     *  holding dictionary content asks the store on the store's own thread,
     *  so its chunk is not sent within the step that set it. */
    private fun awaitSent(arrived: (String) -> Boolean) {
        for (i in 0 until 300) {
            idle()
            if (arrived(sent())) return
            Thread.sleep(10)
        }
    }

    /** The page reports a layout of row set [gen], with the word heads that
     *  pin [heads] (rowId, top, height for each, as the page sends them),
     *  the rows it replaced since its last report [redrawn] and the word
     *  rows' labels [labels] (rowId, then the word's box and the readings'
     *  box, four numbers each). */
    private fun layout(
        gen: Int,
        heightCssPx: Int,
        targets: String = "[]",
        heads: String = "[]",
        redrawn: String = "[]",
        labels: String = "[]",
    ) {
        val bridge = shadowOf(web).getJavascriptInterface("PTBridge")
        val s = String::class.java
        bridge.javaClass.getMethod("onLayout", Int::class.java, Int::class.java, s, s, s, s)
            .invoke(bridge, gen, heightCssPx, targets, heads, redrawn, labels)
        idle()
    }

    private fun tap(domId: String, act: String) {
        val bridge = shadowOf(web).getJavascriptInterface("PTBridge")
        bridge.javaClass.getMethod("onAction", String::class.java, String::class.java).invoke(bridge, domId, act)
        idle()
    }

    /** The page reports a tap on a row's text. */
    private fun text(gen: Int, domId: String, tapId: Int, lang: String, dictId: String, text: String, offset: Int) {
        val bridge = shadowOf(web).getJavascriptInterface("PTBridge")
        val s = String::class.java
        val i = Int::class.java
        bridge.javaClass.getMethod("onText", i, s, i, s, s, s, i)
            .invoke(bridge, gen, domId, tapId, lang, dictId, text, offset)
        idle()
    }

    /** The page answers a mark. */
    private fun marked(tapId: Int, left: Int, top: Int, width: Int, height: Int) {
        val bridge = shadowOf(web).getJavascriptInterface("PTBridge")
        val i = Int::class.java
        bridge.javaClass.getMethod("onMark", i, i, i, i, i).invoke(bridge, tapId, left, top, width, height)
        idle()
    }

    private fun wordSense(id: String) = DefRow.Sense(
        id, number = null, pos = "", gloss = "gloss of $id",
        examples = listOf(DefRow.Example("例文です", "", italic = false)), words = true,
    )

    // Mutations: a tap from a row set since replaced is taken; a row is
    // named by its element's id; a tap outside its own text is taken.
    @Test fun `a tap on a row's text reaches the host with its row, and one of another row set or of no row does not`() {
        doc.setRows(listOf(wordSense("s0"), wordSense("s1")), "ja")
        pageReady()

        text(1, "k1", 7, "ja", "jt", "例文です", 2)

        val (row, tap) = texts.single()
        assertEquals("s1", row)
        assertEquals(listOf<Any>(7, "例文です", 2, "ja", "jt"), listOf(tap.id, tap.text, tap.offset, tap.lang, tap.dictId))
        // Another row set's tap, a row the document does not hold, and an
        // offset that is in no text.
        text(0, "k1", 8, "ja", "", "例文です", 2)
        text(1, "k9", 9, "ja", "", "例文です", 2)
        text(1, "k1", 10, "", "", "abc", 3)
        text(1, "k1", 11, "", "", "abc", -1)
        assertEquals(1, texts.size)
    }

    // Mutations: the page is told to paint when only asked to measure; a
    // mark's loss is told as a box; the clear is not sent.
    @Test fun `the page is asked for a mark and to clear it, and its answer or the mark's loss reaches the host`() {
        doc.setRows(listOf(wordSense("s0")), "ja")
        pageReady()

        doc.mark(7, 2, 4, draw = false)
        assertEquals("ptMark(7,2,4,false)", sent())
        doc.mark(7, 2, 4, draw = true)
        assertEquals("ptMark(7,2,4,true)", sent())
        marked(7, 10, 20, 30, 16)
        marked(7, 0, 0, 0, 0)
        doc.unmark()
        assertEquals("ptUnmark()", sent())

        assertEquals(listOf(7, 7), marks.map { it.first })
        val box = marks[0].second!!
        assertEquals(listOf(10, 20, 30, 16), listOf(box.left, box.top, box.width, box.height))
        assertNull("a box of no width is the mark gone", marks[1].second)
    }

    // Mutations: the box is placed by the screen alone; one out of sight is
    // placed all the same.
    @Test fun `a mark is on screen where the page drew it, and nowhere once none of it shows`() {
        doc.setRows(rows(2), "ja")
        pageReady()
        layout(1, 400)
        idle()

        val out = Rect()
        assertTrue(doc.markRectOnScreen(DefinitionDocument.MarkBox(10, 20, 30, 16), out))
        val at = IntArray(2).also { view.getLocationOnScreen(it) }
        // A JVM test's density is 1: CSS px are px.
        assertEquals(Rect(at[0] + 10, at[1] + 20, at[0] + 40, at[1] + 36), out)
        assertFalse(doc.markRectOnScreen(DefinitionDocument.MarkBox(10, 100_000, 30, 16), out))
    }

    private fun killRenderer() {
        shadowOf(web).webViewClient.onRenderProcessGone(web, null)
        idle()
    }

    @Test fun `nothing to draw and nothing drawn takes no shell`() {
        doc.setRows(emptyList(), "ja")
        assertEquals(0, slot.childCount)
    }

    @Test fun `the first eight rows go at once, and more only while the end is near`() {
        doc.setRows(rows(20), "ja")
        pageReady()
        assertTrue(sent(), sent().startsWith("ptSet("))
        assertTrue(sent().contains("id=\\\"k7\\\"") && !sent().contains("id=\\\"k8\\\""))
        assertTrue("the row set's generation rides with it", sent().endsWith(",1)"))

        // A short document: its end is near, so the next rows follow.
        layout(1, 400)
        assertTrue(sent(), sent().startsWith("ptAppend("))
        assertTrue(sent().contains("id=\\\"k8\\\"") && sent().contains("id=\\\"k15\\\"") && !sent().contains("id=\\\"k16\\\""))

        // Now far taller than two screens: nothing more until a scroll brings the end near.
        layout(1, 100_000)
        assertTrue("no further append", sent().contains("id=\\\"k15\\\""))
        assertEquals(20, doc.shownRows.size)
    }

    @Test fun `the view is one pixel only before its first layout`() {
        doc.setRows(rows(2), "ja")
        pageReady()
        assertEquals(1, view.layoutParams.height)
        layout(1, 100)
        val first = view.layoutParams.height
        assertTrue(first > 1)

        // A new row set keeps the height on screen until its own layout lands.
        doc.setRows(listOf(row("other")), "ja")
        assertEquals(first, view.layoutParams.height)
        layout(2, 40)
        assertTrue(view.layoutParams.height in 2 until first)
    }

    @Test fun `a rebind with the same ids patches only the row that changed`() {
        val first = rows(3)
        doc.setRows(first, "ja")
        pageReady()
        val set = sent()

        doc.setRows(first, "ja")
        assertEquals("an identical rebind sends nothing", set, sent())

        doc.setRows(listOf(first[0], row("w1", "a new meaning"), first[2]), "ja")
        assertTrue(sent(), sent().startsWith("ptReplace(\"k1\","))
        assertTrue(sent().contains("a new meaning") && sent().endsWith(",1)"))
    }

    @Test fun `a row patched before its chunk went is sent as it is now`() {
        doc.setRows(rows(12), "ja")
        pageReady()
        // Row 10 is not in the page yet: the list changes, the page does not.
        val before = sent()
        doc.setRows(rows(12).mapIndexed { i, r -> if (i == 10) row("w10", "patched early") else r }, "ja")
        assertEquals(before, sent())
        layout(1, 400)
        assertTrue(sent(), sent().startsWith("ptAppend(") && sent().contains("patched early"))
    }

    @Test fun `other ids are a new row set, and the old one's reports are ignored`() {
        doc.setRows(rows(2), "ja")
        pageReady()
        layout(1, 100)
        val height = view.layoutParams.height

        doc.setRows(listOf(row("x"), row("y")), "ja")
        assertTrue(sent(), sent().startsWith("ptSet(") && sent().endsWith(",2)"))
        layout(1, 9_000)
        assertEquals("a late report from the first set changes nothing", height, view.layoutParams.height)
    }

    @Test fun `a tap reaches the host with its row, and targets name rows by their own ids`() {
        doc.setRows(rows(2), "ja")
        pageReady()
        layout(1, 100, """["k1","open",0,50,300,50,"k1","speak",200,55,36,40]""")
        assertEquals(listOf(DocTarget("w1", "open", 0, 50, 300, 50), DocTarget("w1", "speak", 200, 55, 36, 40)), doc.targets)
        tap("k1", DefAction.SPEAK)
        assertEquals(listOf("w1" to DefAction.SPEAK), actions)
        tap("k99", DefAction.OPEN)
        assertEquals("a row the document does not hold is no tap", 1, actions.size)
    }

    // Mutations: the label's box is the ring's; a row with no label, or a
    // button, rings its own rect; the readings box joins the word's.
    @Test fun `a row's ring is around its label, a button's and an unlabelled row's around themselves`() {
        doc.setRows(rows(3), "ja")
        pageReady()
        layout(
            1, 300,
            """["k0","open",0,0,300,100,"k0","speak",200,5,40,40,"k1","open",0,100,300,100,"k2","open",0,200,300,100]""",
            labels = """["k0",16,9,96,22,0,0,0,0,"k1",16,109,80,22,16,142,120,20,"k9",1,1,1,1,0,0,0,0]""",
        )
        assertEquals(
            "the readings box of none is no box; a row the document does not hold has no label",
            mapOf(
                "w0" to DocLabel("w0", DocBox(16, 9, 96, 22), null),
                "w1" to DocLabel("w1", DocBox(16, 109, 80, 22), DocBox(16, 142, 120, 20)),
            ),
            doc.labels,
        )
        val at = IntArray(2).also { view.getLocationOnScreen(it) }
        fun ring(rowId: String, act: String) = Rect().also { assertTrue(doc.ringRectOnScreen(rowId, act, it)) }
        fun rect(rowId: String, act: String) = Rect().also { assertTrue(doc.rectOnScreen(rowId, act, it)) }
        // A JVM test's density is 1: CSS px are px.
        assertEquals(Rect(at[0] + 16, at[1] + 9, at[0] + 112, at[1] + 31), ring("w0", DefAction.OPEN))
        assertEquals("the word and the readings under it", Rect(at[0] + 16, at[1] + 109, at[0] + 136, at[1] + 162), ring("w1", DefAction.OPEN))
        assertEquals("a button is ringed whole", rect("w0", DefAction.SPEAK), ring("w0", DefAction.SPEAK))
        assertEquals("a row with no label is ringed whole", rect("w2", DefAction.OPEN), ring("w2", DefAction.OPEN))
        assertEquals("moving on still goes by the whole row", Rect(at[0], at[1], at[0] + 300, at[1] + 100), rect("w0", DefAction.OPEN))

        doc.setRows(listOf(row("other")), "ja")
        assertEquals("a new row set starts with none", emptyMap<String, DocLabel>(), doc.labels)
    }

    @Test fun `the heads a report names are kept by their rows' own ids, and a new row set starts with none`() {
        doc.setRows(rows(2), "ja")
        pageReady()
        layout(1, 200, """["k0","open",0,0,300,100,"k1","open",0,100,300,100]""", """["k0",10,40,"k1",110,44,"k9",0,40]""")
        assertEquals(mapOf("w0" to DocHead("w0", 10, 40), "w1" to DocHead("w1", 110, 44)), doc.heads)
        doc.setRows(listOf(row("other")), "ja")
        assertEquals(emptyMap<String, DocHead>(), doc.heads)
    }

    @Test fun `a cursor on an answer's X moves to the row's explain button when the X goes, and the page is told`() {
        doc.setRows(rows(2), "ja")
        pageReady()
        val head = """"k0","open",0,0,300,100,"k0","explain",120,5,36,40,"k0","speak",180,5,36,40"""
        val other = """"k1","open",0,100,300,100"""
        layout(1, 200, "[$head,\"k0\",\"explain:close\",250,45,36,32,$other]")
        doc.setCursor(doc.targets.first { it.act == DefAction.EXPLAIN_CLOSE })
        val key = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)

        // The X was pressed and the page lays the row out without the answer.
        view.onKeyDown(KeyEvent.KEYCODE_BUTTON_A, key)
        assertEquals(listOf("w0" to DefAction.EXPLAIN_CLOSE), actions)
        layout(1, 200, "[$head,$other]")

        assertTrue("the page draws the highlight from its own copy: ${sent()}", sent().contains("ptCursor(\"k0\",\"explain\")"))
        view.onKeyDown(KeyEvent.KEYCODE_BUTTON_A, key)
        assertEquals("the next press is the button's", "w0" to DefAction.EXPLAIN, actions.last())
    }

    @Test fun `a cursor on any other target that goes is dropped, and the next press does nothing`() {
        doc.setRows(rows(2), "ja")
        pageReady()
        layout(1, 200, """["k0","",0,0,300,100,"k0","remove:0",250,5,36,32,"k0","remove:1",250,45,36,32,"k0","remove",250,85,36,32]""")
        doc.setCursor(doc.targets.first { it.act == DefAction.removeItem(1) })
        val told = sent()
        val key = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)
        view.onKeyDown(KeyEvent.KEYCODE_BUTTON_A, key)
        assertEquals(listOf("w0" to DefAction.removeItem(1)), actions)

        // The item is gone; the others' X are where they were.
        layout(1, 160, """["k0","",0,0,300,100,"k0","remove:0",250,5,36,32,"k0","remove",250,45,36,32]""")

        assertEquals("the page was told nothing new", told, sent())
        assertFalse("no cursor: the key is not this document's", view.onKeyDown(KeyEvent.KEYCODE_BUTTON_A, key))
        assertEquals("and nothing else was removed", 1, actions.size)
    }

    @Test fun `the row at a height is the one it falls in, and in two columns the first of its line`() {
        doc.setRows(rows(4), "ja")
        pageReady()
        val density = activity.resources.displayMetrics.density
        fun at(cssY: Int) = doc.rowAt((cssY * density).toInt())

        // One column: four rows, each 100 tall.
        layout(1, 400, """["k0","open",0,0,300,100,"k1","open",0,100,300,100,"k2","open",0,200,300,100,"k3","open",0,300,300,100]""")
        assertEquals("w0", at(0))
        assertEquals("w0", at(99))
        assertEquals("w1", at(100))
        assertEquals("w3", at(350))
        assertEquals("past the end is the last row", "w3", at(900))

        // Two columns: w0 and w1 side by side, then w2 and w3.
        layout(1, 200, """["k0","open",0,0,146,100,"k1","open",154,0,146,100,"k2","open",0,100,146,100,"k3","open",154,100,146,100]""")
        assertEquals("the line's first row, not the one beside it", "w0", at(50))
        assertEquals("w2", at(100))
        assertEquals("w2", at(199))
        assertEquals("the top of w2 is its line's", doc.rowTopPx("w2"), doc.rowTopPx("w3"))
    }

    @Test fun `a document of plain senses is no focus stop, and a late translation patches its one row`() {
        fun sense(id: String, translation: String = "") = DefRow.Sense(
            id, number = null, pos = "", gloss = "gloss of $id",
            examples = listOf(DefRow.Example("example of $id", translation, italic = false)),
        )
        doc.setRows(listOf(sense("s0"), sense("s1")), "ja")
        pageReady()
        assertFalse(view.isFocusable)
        doc.setRows(listOf(sense("s0"), sense("s1", "translated")), "ja")
        idle()
        assertTrue(sent(), sent().startsWith("ptReplace(") && sent().contains("translated"))
        assertFalse(sent(), sent().contains("gloss of s0"))
        // Rows with something to tap make it one again.
        doc.setRows(rows(2), "ja")
        idle()
        assertTrue(view.isFocusable)
    }

    @Test fun `a row with nothing to act on but a dictionary's title is a focus stop`() {
        val groups = listOf(ImportedSenseGroup("Dict", listOf(ImportedSense("one")), dictId = "d"))
        val data = WordDefinitionData(
            word = "w", reading = null, senses = emptyList(), freqScore = 0, isCommon = false, importedGroups = groups,
        )
        doc.setRows(listOf(DefRow.Word("w", data, opens = false)), "ja")
        assertTrue(view.isFocusable)
        doc.setRows(listOf(DefRow.Word("u", data.copy(importedGroups = emptyList()), opens = false)), "ja")
        assertFalse(view.isFocusable)
    }

    // Mutations: the block is a stop whatever it holds; it is one only by
    // its curation; a block not told to follow links is a stop for one.
    @Test fun `a page's imported block is a focus stop once the page lays out a link in it, and the link's tap reaches the host`() {
        val groups = listOf(ImportedSenseGroup("Dict", listOf(ImportedSense("one")), dictId = "d"))
        val ref = DefAction.reference(0, "猫", "ねこ")
        doc.setRows(listOf(DefRow.Imported("imp", groups, links = true)), "ja")
        pageReady()
        assertFalse("nothing is known of its content yet", view.isFocusable)
        // Its definitions hold no link: the block alone, which takes no tap.
        layout(1, 100, """["k0","",0,0,300,100]""")
        assertFalse(view.isFocusable)
        layout(1, 100, """["k0","",0,0,300,100,"k0","$ref",40,30,60,20]""")
        assertTrue(view.isFocusable)
        assertEquals(listOf<NavTarget?>(DocNavTarget(doc, "imp", ref)), doc.navActions().map { it.target })
        tap("k0", ref)
        assertEquals(listOf("imp" to ref), actions)
    }

    @Test fun `emptying keeps the shell at one pixel for the rows that follow`() {
        doc.setRows(rows(2), "ja")
        pageReady()
        layout(1, 100)
        val shell = web
        doc.setRows(emptyList(), "ja")
        assertEquals("ptSet('',2)", sent())
        assertEquals(1, view.layoutParams.height)
        doc.setRows(rows(1), "ja")
        assertTrue(shell === web)
    }

    @Test fun `renderer death is recovered once per content, then the notice shows`() {
        doc.setRows(rows(2), "ja")
        pageReady()
        val first = web
        killRenderer()
        assertFalse(doc.showsNotice)
        assertNotSame("a new shell took the dead one's place", first, web)
        pageReady()
        assertTrue("the rows are sent again", sent().startsWith("ptSet(") && sent().contains("meaning of w0"))

        killRenderer()
        assertTrue("a second death on the same rows", doc.showsNotice)

        // New rows are a new attempt.
        doc.setRows(listOf(row("fresh")), "ja")
        assertFalse(doc.showsNotice)
        pageReady()
        assertTrue(sent().contains("meaning of fresh"))
    }

    @Test fun `the notice after a second renderer death is announced, since no layout will be`() {
        doc.setRows(rows(2), "ja")
        pageReady()
        layout(1, 100)
        assertEquals(1, layouts)
        assertTrue(doc.heightPx > 1)
        killRenderer()
        assertEquals("a recovery is not announced: its layout will be", 1, layouts)
        assertTrue("the view keeps its height for the new shell", doc.heightPx > 1)
        killRenderer()
        assertTrue(doc.showsNotice)
        assertEquals(2, layouts)
        assertEquals("no view, no height", 1, doc.heightPx)
    }

    @Test fun `a renderer death takes the spare with it, so one death is one death`() {
        doc.setRows(rows(2), "ja")
        pageReady()
        // A second surface holding definitions keeps a spare loaded.
        DefinitionShells.retain(activity)
        val spare = DefinitionShells.spareForTest ?: error("no spare")
        val first = web
        // The platform tells the document's page first; the spare, which
        // shared the render process, has not heard yet.
        shadowOf(first).webViewClient.onRenderProcessGone(first, null)
        idle()
        assertTrue("the spare died with the render process", spare.isDead)
        assertNotSame("the document did not recover onto the dead spare", spare.webView, web)
        assertNotSame(first, web)
        assertFalse(doc.showsNotice)
    }

    @Test fun `a dictionary store that fails costs the styling, never the rows`() {
        // The store's database cannot be opened: a directory sits where its
        // file goes. The row asks the store, since its imported sense kept a
        // structured glossary.
        val root = YomitanDictionaryStore.rootDir(activity)
        runBlocking { YomitanDataStore.resetForTest() }
        root.deleteRecursively()
        File(root, "yomitan.sqlite").mkdirs()
        try {
            assertTrue(
                "the store really fails here",
                runCatching { runBlocking { YomitanDataStore.stylingFor(activity, "ja") } }.isFailure,
            )
            val imported = ImportedSenseGroup("Dict", listOf(ImportedSense("its flat gloss", scRowid = 7L)), dictId = "d1")
            val word = DefRow.Word(
                "w",
                WordDefinitionData(
                    word = "w", reading = null, senses = emptyList(), freqScore = 0, isCommon = false,
                    importedGroups = listOf(imported),
                ),
            )
            doc.setRows(listOf(word), "ja")
            pageReady()
            awaitSent { it.startsWith("ptSet(") }
            assertTrue("the row is sent: ${sent().take(80)}", sent().startsWith("ptSet("))
            assertTrue("with the sense's flat text", sent().contains("its flat gloss"))
        } finally {
            runBlocking { YomitanDataStore.resetForTest() }
            root.deleteRecursively()
        }
    }

    @Test fun `a first chunk that cannot be built is announced, since no layout will be, and new rows are a new attempt`() {
        // A context whose resources fail on demand: a row's markup reads them.
        var failing = false
        val ctx = object : ContextWrapper(activity) {
            override fun getResources(): Resources {
                if (failing) throw IllegalStateException("no resources")
                return super.getResources()
            }
        }
        val own = FrameLayout(activity).also { slot.addView(it) }
        var told = 0
        val failingDoc = DefinitionDocument(
            ctx, own, scope,
            theme = {
                DefinitionsDocument.DocTheme(
                    DefinitionsDocument.Tokens(-1, -2, -3, -4, -5, 16.5f), divider = -6, band = -7,
                )
            },
            onAction = { _, _ -> },
            onLayout = { told++ },
            buildOn = Dispatchers.Main.immediate,
        )
        try {
            failingDoc.setRows(rows(2), "ja")
            assertFalse(failingDoc.buildFailed)
            assertEquals(0, told)

            failing = true
            failingDoc.setRows(listOf(row("other")), "ja")
            failing = false
            assertTrue("nothing of these rows will be drawn", failingDoc.buildFailed)
            assertEquals("the host is told: no layout is coming", 1, told)
            assertEquals(1, failingDoc.heightPx)

            // The same chunk asked for again (a D-pad step past the rows the
            // page holds) and built this time: no longer failed.
            val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
            val docView = own.getChildAt(0) as DefinitionDocumentView
            docView.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, down)
            assertFalse("the chunk went through", failingDoc.buildFailed)

            failing = true
            failingDoc.setRows(listOf(row("again")), "ja")
            failing = false
            assertTrue(failingDoc.buildFailed)
            assertEquals(2, told)
            failingDoc.setRows(listOf(row("third")), "ja")
            assertFalse("new rows are a new attempt", failingDoc.buildFailed)
            assertEquals(2, told)

            // A later chunk that fails is not this: the rows before it are
            // the page's, and their layout is what a host waits for.
            failingDoc.setRows(rows(20), "ja")
            failing = true
            docView.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, down)
            failing = false
            assertFalse("the first rows were built", failingDoc.buildFailed)
            assertEquals(2, told)

            // A release forgets the rows, and their failure with them.
            failing = true
            failingDoc.setRows(listOf(row("last")), "ja")
            failing = false
            assertTrue(failingDoc.buildFailed)
            failingDoc.release()
            assertFalse(failingDoc.buildFailed)
        } finally {
            failingDoc.release()
        }
    }

    @Test fun `a row that gains dictionary content under its id is a new row set`() {
        // Each sense kept a structured glossary under its rowid, which only
        // feeding fetches.
        fun word(vararg rowids: Long, speak: DefRow.Speak = DefRow.Speak.IDLE) = DefRow.Word(
            "w",
            WordDefinitionData(
                word = "w", reading = null, senses = emptyList(), freqScore = 0, isCommon = false,
                importedGroups = listOf(
                    ImportedSenseGroup("Dict", rowids.map { ImportedSense("gloss $it", scRowid = it) }, dictId = "d1"),
                ),
            ),
            speak = speak,
        )
        doc.setRows(listOf(word(1)), "ja")
        pageReady()
        awaitSent { it.startsWith("ptSet(") }
        assertTrue(sent(), sent().startsWith("ptSet(") && sent().endsWith(",1)"))

        // The same content with a spinner: a patch, as ever.
        doc.setRows(listOf(word(1, speak = DefRow.Speak.LOADING)), "ja")
        awaitSent { it.startsWith("ptReplace(") }
        assertTrue(sent(), sent().startsWith("ptReplace("))

        // A second glossary under the same id: nothing has fetched it, so
        // the rows are fed again.
        doc.setRows(listOf(word(1, 2)), "ja")
        awaitSent { it.startsWith("ptSet(") }
        assertTrue("a new row set: ${sent().take(60)}", sent().startsWith("ptSet(") && sent().endsWith(",2)"))
        assertTrue(sent().contains("gloss 2"))

        // Losing one needs no fetch: a patch.
        doc.setRows(listOf(word(2)), "ja")
        awaitSent { it.startsWith("ptReplace(") }
        assertTrue(sent(), sent().startsWith("ptReplace(") && sent().endsWith(",2)"))
    }

    @Test fun `release drops the view and a later rebind starts over`() {
        doc.setRows(rows(2), "ja")
        pageReady()
        layout(1, 100)
        assertTrue(doc.heightPx > 1)
        doc.release()
        assertEquals(0, slot.childCount)
        assertTrue(doc.shownRows.isEmpty())
        assertEquals("the height went with the view", 1, doc.heightPx)
        doc.setRows(rows(1), "ja")
        assertEquals(1, slot.childCount)
        assertEquals("the new view has drawn nothing", 1, doc.heightPx)
        assertEquals(1, view.layoutParams.height)
    }
}
