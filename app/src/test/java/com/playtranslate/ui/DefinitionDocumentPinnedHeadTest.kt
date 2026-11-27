package com.playtranslate.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.playtranslate.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * A word's head pins at the top of what is visible while its row's
 * definitions scroll under it, and the page reports every place unpinned.
 * [DefinitionDocument] turns that into what a controller and a tap need: a
 * pinned head's buttons where the head is drawn (as CSS sticky places it:
 * at the visible top, never above its own place, never past its row's end),
 * a target under a pinned head revealed below it, and a tap that changes a
 * row whose head is pinned bringing the row's top to the top of what is
 * visible once the page has laid the change out.
 *
 * The page is played by [DefinitionDocument.layoutForTest]: three rows of
 * 300 px, each head 40 px at its row's top with its buttons in it; the
 * document stands 100 px down a 400 px ScrollView (a JVM test's density is
 * 1, so CSS px are px).
 */
@RunWith(RobolectricTestRunner::class)
class DefinitionDocumentPinnedHeadTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private lateinit var activity: Activity
    private lateinit var scroll: ScrollView
    private lateinit var doc: DefinitionDocument
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val actions = mutableListOf<Pair<String, String>>()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun row(id: String, answer: Boolean = false) = DefRow.Word(
        id,
        WordDefinitionData(
            word = id, reading = null, senses = listOf(SenseDisplay(emptyList(), "meaning of $id", emptyList())),
            freqScore = 0, isCommon = false,
        ),
        speak = DefRow.Speak.IDLE,
        trailing = DefRow.Trailing.HIDE,
        explain = DefRow.Explain(answer = if (answer) DefRow.Answer("In this sentence", "an answer") else null),
    )

    @Before fun setUp() {
        DefinitionShells.reset()
        activity = Robolectric.buildActivity(Host::class.java).setup().get()
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(View(activity), LinearLayout.LayoutParams(MATCH, 100))
        val slot = FrameLayout(activity)
        column.addView(slot, LinearLayout.LayoutParams(MATCH, WRAP))
        column.addView(View(activity), LinearLayout.LayoutParams(MATCH, 1000))
        scroll = ScrollView(activity)
        scroll.addView(column, ViewGroup.LayoutParams(MATCH, WRAP))
        val root = FrameLayout(activity)
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, 400))
        activity.setContentView(root)
        doc = DefinitionDocument(
            activity, slot, scope,
            theme = {
                DefinitionsDocument.DocTheme(
                    DefinitionsDocument.Tokens(-1, -2, -3, -4, -5, 16.5f), divider = -6, band = -7,
                )
            },
            onAction = { row, act -> actions += row.id to act },
            buildOn = Dispatchers.Main.immediate,
        )
        doc.setRows(listOf(row("w0"), row("w1", answer = true), row("w2")), "ja")
        idle()
        doc.layoutForTest(300)
        idle()
    }

    @After fun tearDown() {
        doc.release()
        scope.cancel()
        DefinitionShells.reset()
    }

    private val viewportTop: Int get() = IntArray(2).also { scroll.getLocationOnScreen(it) }[1]

    /** Scrolls the host so that [visibleTop] px of the document are above
     *  what is visible. */
    private fun scrollDocumentTo(visibleTop: Int) {
        scroll.scrollTo(0, 100 + visibleTop)
        idle()
    }

    private val frame: DefinitionDocumentView get() = doc.hostView as DefinitionDocumentView

    /** The action the document's cursor is on. */
    private val cursorAct: String?
        get() = (DefinitionDocument::class.java.getDeclaredField("cursor").apply { isAccessible = true }
            .get(doc) as DocTarget?)?.act

    private fun target(rowId: String, act: String): DocTarget =
        doc.targets.first { it.rowId == rowId && it.act == act }

    private fun drawn(rowId: String, act: String): Rect =
        Rect().also { assertTrue(DocNavTarget(doc, rowId, act).rectOnScreen(it)) }

    private fun revealed(rowId: String, act: String): Rect =
        Rect().also { assertTrue(DocNavTarget(doc, rowId, act).revealRectOnScreen(it)) }

    private fun ringed(rowId: String, act: String): Rect =
        Rect().also { assertTrue(DocNavTarget(doc, rowId, act).ringRectOnScreen(it)) }

    // Mutations: the ring is the label's box, not the row's; the readings
    // join it only while the head is in its place; a pinned head moves it.
    @Test fun `a row's ring is around its word and the readings under it while its head is in its place`() {
        doc.layoutForTest(300, readingsLines = setOf("w1"))
        idle()
        scrollDocumentTo(250)
        val row = drawn("w1", DefAction.OPEN)
        // The word 16..112 x 9..31 down the row, the readings 16..136 x 42..62.
        assertEquals(Rect(row.left + 16, row.top + 9, row.left + 136, row.top + 62), ringed("w1", DefAction.OPEN))
        assertEquals("moving on still goes by the whole row", 300, row.height())
        assertEquals("a button is ringed whole", drawn("w1", DefAction.SPEAK), ringed("w1", DefAction.SPEAK))
    }

    @Test fun `on a pinned head the ring is the word alone, where the head is drawn`() {
        doc.layoutForTest(300, readingsLines = setOf("w1"))
        idle()
        scrollDocumentTo(450)
        val ring = ringed("w1", DefAction.OPEN)
        assertEquals("9 px down the head pinned at the visible top", viewportTop + 9, ring.top)
        assertEquals("the readings went under the head", 22, ring.height())
        assertEquals("the row itself is where the page put it", viewportTop - 150, drawn("w1", DefAction.OPEN).top)
    }

    @Test fun `the page's heads are kept by row`() {
        assertEquals(DocHead("w1", 300, 40), doc.heads["w1"])
        assertEquals(setOf("w0", "w1", "w2"), doc.heads.keys)
    }

    @Test fun `a pinned head's buttons are where the head is pinned, at the top of what is visible`() {
        scrollDocumentTo(450)
        for (act in listOf(DefAction.EXPLAIN, DefAction.SPEAK, DefAction.TRAILING)) {
            assertEquals(act, viewportTop, drawn("w1", act).top)
        }
    }

    @Test fun `a head the next row pushes is drawn at its row's end`() {
        // Row w1 is 300..600, its head 40 tall: it can go no lower than 560.
        scrollDocumentTo(590)
        assertEquals(viewportTop - 30, drawn("w1", DefAction.SPEAK).top)
    }

    @Test fun `a head whose place is on screen is where the page reported it`() {
        scrollDocumentTo(250)
        assertEquals(viewportTop + 50, drawn("w1", DefAction.SPEAK).top)
    }

    @Test fun `a row's own target and what is under its head are never moved`() {
        scrollDocumentTo(450)
        assertEquals("the row", viewportTop - 150, drawn("w1", DefAction.OPEN).top)
        assertEquals("the answer's X", viewportTop - 110, drawn("w1", DefAction.EXPLAIN_CLOSE).top)
    }

    @Test fun `a target under a head is revealed with room above it for the head`() {
        scrollDocumentTo(450)
        val x = drawn("w1", DefAction.EXPLAIN_CLOSE)
        val reveal = revealed("w1", DefAction.EXPLAIN_CLOSE)
        assertEquals(x.top - 40, reveal.top)
        assertEquals(x.bottom, reveal.bottom)
        assertEquals("a head's own button needs only itself", drawn("w1", DefAction.SPEAK), revealed("w1", DefAction.SPEAK))
        assertEquals("a row's own target, its word on the pinned head, needs only its ring", ringed("w1", DefAction.OPEN), revealed("w1", DefAction.OPEN))
    }

    // Mutations: the whole row whatever shows (a move back onto the word
    // scrolls the row under it); the ring even when the word is off screen
    // (a move down shows only the word's line, not the row).
    @Test fun `a row's own target needs only its ring while its word shows, and the whole row once it does not`() {
        scrollDocumentTo(250)
        assertEquals("the head in its place, its word on screen", ringed("w1", DefAction.OPEN), revealed("w1", DefAction.OPEN))
        scrollDocumentTo(450)
        assertEquals("the word on the pinned head", ringed("w1", DefAction.OPEN), revealed("w1", DefAction.OPEN))
        scrollDocumentTo(0)
        // Row w2 is 600..900 down the document; the 400 px viewport shows 0..400 of it.
        assertEquals("a word below what shows brings its row", drawn("w2", DefAction.OPEN), revealed("w2", DefAction.OPEN))
    }

    @Test fun `the page's report lets the view line the page up with what is visible`() {
        scrollDocumentTo(450)
        val root = activity.window.decorView
        root.draw(Canvas(Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)))
        assertEquals(450, frame.pageView!!.scrollY)
    }

    @Test fun `the D-pad's focus rect is where the cursor's button is drawn on a pinned head`() {
        scrollDocumentTo(450)
        doc.setCursor(target("w1", DefAction.SPEAK))
        val focus = Rect()
        frame.getFocusedRect(focus)
        assertEquals("the head pinned at the visible top, 450 px down the document", 450, focus.top)
    }

    @Test fun `a D-pad move onto a target under a pinned head scrolls it into view below the head`() {
        scrollDocumentTo(450)
        doc.setCursor(target("w1", DefAction.TRAILING))
        frame.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals("precondition: the cursor is on the answer's X", DefAction.EXPLAIN_CLOSE, cursorAct)
        // The X is 340 px down the document, under the head's 40: the row's
        // top comes to the top, its head in its own place over the X.
        assertEquals(100 + 300, scroll.scrollY)
    }

    /** What the host does with a press of [act] on [rowId]: the rows set
     *  again with that row changed, as a patch. */
    private fun hostChanges(rowId: String, change: (DefRow.Word) -> DefRow.Word) {
        doc.setRows(doc.shownRows.map { if (it.id == rowId) change(it as DefRow.Word) else it }, "ja")
        idle()
    }

    /** The change a press of [act] makes to its row. */
    private fun changeFor(act: String): (DefRow.Word) -> DefRow.Word = when (act) {
        DefAction.TRAILING -> { r -> r.copy(trailing = DefRow.Trailing.SHOW) }
        else -> { r -> r.copy(explain = DefRow.Explain(busy = true)) }
    }

    private val initialRows = listOf(row("w0"), row("w1", answer = true), row("w2"))

    @Test fun `a tap that changes a row whose head is pinned brings the row to the top once the page has redrawn it`() {
        for (act in listOf(DefAction.EXPLAIN, DefAction.EXPLAIN_CLOSE, DefAction.EXPLAIN_RETRY, DefAction.TRAILING)) {
            doc.setRows(initialRows, "ja")
            idle()
            doc.layoutForTest(300)
            scrollDocumentTo(450)
            doc.activate("w1", act)
            assertEquals("$act: the host heard it", "w1" to act, actions.last())
            doc.layoutForTest(300)
            assertEquals("$act: a layout before the change reaches the row moves nothing", 550, scroll.scrollY)
            hostChanges("w1", changeFor(act))
            doc.layoutForTest(300, redrawn = setOf("w0"))
            assertEquals("$act: nor one that redrew another row", 550, scroll.scrollY)
            doc.layoutForTest(300, redrawn = setOf("w1"))
            assertEquals("$act: its head in its own place at the top", 100 + 300, scroll.scrollY)
            scrollDocumentTo(450)
            doc.layoutForTest(300, redrawn = setOf("w1"))
            assertEquals("$act: once", 550, scroll.scrollY)
        }
    }

    @Test fun `a tap that changed nothing never moves the list, whatever redraws the row later`() {
        scrollDocumentTo(450)
        // No model to ask: the host shows its alert and the row stays as it was.
        doc.activate("w1", DefAction.EXPLAIN)
        doc.layoutForTest(300)
        hostChanges("w1") { it.copy(speak = DefRow.Speak.LOADING) }
        doc.layoutForTest(300, redrawn = setOf("w1"))
        assertEquals(550, scroll.scrollY)
    }

    @Test fun `a scroll before the redraw leaves the list where the user put it`() {
        scrollDocumentTo(450)
        doc.activate("w1", DefAction.EXPLAIN)
        hostChanges("w1", changeFor(DefAction.EXPLAIN))
        scrollDocumentTo(500)
        doc.layoutForTest(300, redrawn = setOf("w1"))
        assertEquals(600, scroll.scrollY)
    }

    @Test fun `another press ends the one before, and speak or a head in its own place brings nothing up`() {
        scrollDocumentTo(450)
        doc.activate("w1", DefAction.EXPLAIN)
        doc.activate("w1", DefAction.SPEAK)
        hostChanges("w1", changeFor(DefAction.EXPLAIN))
        doc.layoutForTest(300, redrawn = setOf("w1"))
        assertEquals("speak changes nothing above the definitions, and it ended the explain", 550, scroll.scrollY)

        doc.setRows(initialRows, "ja")
        idle()
        doc.layoutForTest(300)
        scrollDocumentTo(250)
        doc.activate("w1", DefAction.EXPLAIN)
        hostChanges("w1", changeFor(DefAction.EXPLAIN))
        doc.layoutForTest(300, redrawn = setOf("w1"))
        assertEquals("a head in its own place opens its answer under it, on screen", 350, scroll.scrollY)
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
