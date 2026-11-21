package com.playtranslate.camera

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.CaptureSession
import com.playtranslate.CaptureState
import com.playtranslate.OcrManager
import com.playtranslate.OcrTokenScope
import com.playtranslate.OneShotOverlayData
import com.playtranslate.OverlayMode
import com.playtranslate.R
import com.playtranslate.camera.render.FrozenLookupScene
import com.playtranslate.language.TokenSpan
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TranslationResult
import com.playtranslate.ui.CaptureResultGeometry
import com.playtranslate.ui.CaptureResultOverlay
import com.playtranslate.ui.WordBoxesView
import com.playtranslate.ui.WordLookupSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Definitions in a frozen review, as the camera's snapshot and the import
 * tool's page run it through [FrozenReviewPanel]: the capture sheet's own
 * word boxes over the tool's frame, placed through the frame's fit and its
 * zoom; the tool's own rasters in its other modes, never both at once; a
 * tap on the frame going to a word first; a card that closes when its word
 * moves or its read is replaced.
 */
@RunWith(RobolectricTestRunner::class)
class FrozenReviewDefinitionsTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    /** The tool's memory of its mode and posture. */
    private class ToolPrefs(var mode: OverlayMode) : CaptureResultOverlay.PresentationPrefs {
        var posture = CaptureResultGeometry.NO_POSTURE
        override var overlayMode: OverlayMode
            get() = mode
            set(value) { mode = value }
        override val offeredOverlayModes: List<OverlayMode> = listOf(
            OverlayMode.TRANSLATION, OverlayMode.FURIGANA, OverlayMode.DEFINITIONS, OverlayMode.NONE,
        )
        override var startPosture: Float
            get() = posture
            set(value) { posture = value }
    }

    /** The tool's painter of its own modes: what it was asked, in order. */
    private class ToolPresenter : CaptureResultOverlay.BoxPresenter {
        val calls = mutableListOf<String>()
        override fun show(data: OneShotOverlayData): Boolean { calls += "show"; return true }
        override fun update(data: OneShotOverlayData) { calls += "update" }
        override fun hide() { calls += "hide" }
    }

    /** The tool's pipeline: each run hands back a session the test drives,
     *  and the scene is whatever read "owns the display". */
    private class Backend : FrozenReviewBackend {
        var scene: FrozenLookupScene? = null
        val runs = mutableListOf<MutableStateFlow<CaptureState>>()
        override fun runReview(
            bitmap: Bitmap, regionAu: Rect?, preOcrDelayMs: Long, deviceOnly: Boolean,
        ): CaptureSession {
            val flow = MutableStateFlow<CaptureState>(CaptureState.InProgress("Reading"))
            runs += flow
            return CaptureSession(flow, Job())
        }
        override fun lookupScene(): FrozenLookupScene? = scene
        override suspend fun translateForPanel(text: String): CaptureResultOverlay.PanelTranslation? = null
    }

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private lateinit var activity: Activity
    private lateinit var overlayHost: FrameLayout
    private lateinit var panelHost: FrameLayout
    private val backend = Backend()
    private val presenter = ToolPresenter()
    private var panel: FrozenReviewPanel? = null

    /** The frame under review, twice the host's size each way so the fit
     *  halves it and the zoom has room. Sized once the host is laid out. */
    private lateinit var frame: Bitmap

    private fun sp() = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    @Before fun setUp() {
        sp().edit().clear().commit()
        activity = Robolectric.buildActivity(Host::class.java).setup().get()
        val root = FrameLayout(activity)
        overlayHost = FrameLayout(activity)
        panelHost = FrameLayout(activity)
        root.addView(overlayHost, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(panelHost, FrameLayout.LayoutParams(MATCH, MATCH))
        activity.setContentView(root)
        idle()
        assertTrue("precondition: the host is laid out", panelHost.width > 0 && panelHost.height > 0)
        frame = Bitmap.createBitmap(panelHost.width * 2, panelHost.height * 2, Bitmap.Config.ARGB_8888)
    }

    @After fun tearDown() {
        panel?.release()
        idle()
        sp().edit().clear().commit()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    private fun <T> Any.field(name: String): T {
        @Suppress("UNCHECKED_CAST")
        return javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T
    }

    private fun words(): ReviewWordBoxes = panel!!.field("words")
    private fun session(): WordLookupSession? = words().field("session")
    private fun selected(): Int? = session()?.field("selected")
    private fun boxesView(): WordBoxesView? =
        (0 until overlayHost.childCount).map { overlayHost.getChildAt(it) }.filterIsInstance<WordBoxesView>().firstOrNull()

    /** A line of [text] in the frame's own pixels, 40 px a character from
     *  ([left], [top]), 60 px tall. */
    private fun auLine(text: String, left: Int, top: Int) = OcrManager.OcrLine(
        text = text,
        bounds = Rect(left, top, left + text.length * 40, top + 60),
        symbols = text.mapIndexedNotNull { i, ch ->
            if (ch == ' ') null
            else OcrManager.SymbolBox(ch.toString(), Rect(left + i * 40, top, left + (i + 1) * 40, top + 60), i)
        },
    )

    private fun scene(vararg lines: OcrManager.OcrLine, path: String? = null) =
        FrozenLookupScene(lines.toList(), frame.width, frame.height, path)

    /** The frame file a card opened now would cite (its Anki screenshot). */
    private fun citedFrame(): String? =
        session()!!.field<Any>("lookup").field("screenshotPath")

    /** The tools' data: nothing of the sheet's own to paint. */
    private fun translating() = CaptureState.Translating(
        "hello world", listOf(TextSegment("hello world")), null,
        OneShotOverlayData(emptyList(), 0, 0, frame.width, frame.height, frame.width, frame.height, emptyList()),
    )

    private fun done() = CaptureState.Done(
        TranslationResult(
            originalText = "hello world",
            segments = listOf(TextSegment("hello world")),
            translatedText = "hi",
            timestamp = "",
            langContext = com.playtranslate.Prefs(ctx).langContext(),
        ),
        OneShotOverlayData(emptyList(), 0, 0, frame.width, frame.height, frame.width, frame.height, emptyList()),
    )

    /** A review of [frame] in [mode], the read not landed yet; [openWord]
     *  when it is the snapshot a tap on a live word took. */
    private fun open(mode: OverlayMode, openWord: ReviewWordRequest? = null): ToolPrefs {
        val toolPrefs = ToolPrefs(mode)
        val p = FrozenReviewPanel(
            activity = activity,
            panelHost = panelHost,
            regionUi = CameraRegionUi(activity, overlayHost, panelHost),
            backend = backend,
            bitmap = { frame },
            boxPresenter = presenter,
            presentationPrefs = toolPrefs,
            tokenScope = OcrTokenScope.IMPORT,
            fitMode = CameraCoordinates.FitMode.FIT,
            zoomPolicy = ReviewZoom.CeilingPolicy.IMPORT,
            setImageTransform = {},
            setOverlayTransform = {},
            setRenderScaleBoost = {},
            repaintOverlays = {},
            onControlsChanged = {},
            onDownloadLaunched = {},
            onDismissed = {},
        )
        panel = p
        words().tokenizeLine = { text, _ -> text.split(' ').filter { it.isNotEmpty() }.map { TokenSpan(it, it) } }
        p.startReview(frame, openWord)
        idle()
        return toolPrefs
    }

    /** The read lands: its lines own the display and the sheet is told. */
    private fun land(vararg lines: OcrManager.OcrLine) {
        backend.scene = scene(*lines)
        backend.runs.last().value = translating()
        settle()
    }

    /** Wait out the tokenization on the background dispatcher. */
    private fun settle() {
        val deadline = System.currentTimeMillis() + 3000
        idle()
        while (session()?.pending == true && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(5)
        }
        idle()
    }

    private val hello = "hello world"

    @Test fun `in Definitions every word is boxed where the frame shows it, and the tool's own rasters go once the boxes are up`() {
        open(OverlayMode.DEFINITIONS)
        land(auLine(hello, left = 200, top = 600))

        val view = boxesView()
        assertNotNull("the word boxes are over the frame, under the sheet", view)
        assertEquals(listOf("hello", "world"), view!!.shownBoxes.map { it.surface })
        // The frame is twice the host: the fit halves it. "hello" is 200 px
        // wide from x = 200 in the frame, so 100 px from x = 100 on screen,
        // grown by the box's pad.
        val pad = WordLookupSession.BOX_PAD_DP * activity.resources.displayMetrics.density
        val box = view.shownBoxes[0].rect
        assertEquals(100f - pad, box.left, 0.5f)
        assertEquals(300f - pad, box.top, 0.5f)
        assertEquals(330f + pad, box.bottom, 0.5f)
        assertFalse("the tool was never asked to paint its own", "show" in presenter.calls || "update" in presenter.calls)
        assertEquals("and whatever it had over the frame is taken down", "hide", presenter.calls.last())
    }

    @Test fun `in the tool's own modes its presenter paints and no word is boxed`() {
        open(OverlayMode.TRANSLATION)
        land(auLine(hello, left = 200, top = 600))
        assertEquals(listOf("show"), presenter.calls)
        assertNull(boxesView())
    }

    @Test fun `a pick moves between the word boxes and the tool's rasters, never both, and None takes both down`() {
        val toolPrefs = open(OverlayMode.TRANSLATION)
        land(auLine(hello, left = 200, top = 600))
        presenter.calls.clear()

        panel!!.selectOverlayMode(OverlayMode.DEFINITIONS)
        settle()
        assertEquals(OverlayMode.DEFINITIONS, toolPrefs.mode)
        assertNotNull(boxesView())
        assertEquals("the rasters go as the words come up", listOf("hide"), presenter.calls)

        presenter.calls.clear()
        panel!!.selectOverlayMode(OverlayMode.FURIGANA)
        settle()
        assertNull("the words go", boxesView())
        assertEquals("and the tool repaints in its own mode", listOf("update"), presenter.calls)

        panel!!.selectOverlayMode(OverlayMode.DEFINITIONS)
        settle()
        presenter.calls.clear()
        panel!!.selectOverlayMode(OverlayMode.NONE)
        settle()
        assertNull(boxesView())
        assertEquals(listOf("hide"), presenter.calls)
    }

    @Test fun `a tap on the frame is a word's first, and the drag lens coming up closes its card`() {
        open(OverlayMode.DEFINITIONS)
        land(auLine(hello, left = 200, top = 600))
        val lookup: CameraWordLookup = panel!!.field("wordLookup")

        assertTrue("on the first word", lookup.onTap!!.invoke(150f, 315f))
        assertEquals(0, selected())
        assertTrue("on the second word: the card moves", lookup.onTap!!.invoke(270f, 315f))
        assertEquals(1, selected())

        lookup.onLensRevealed!!.invoke()
        assertNull(selected())

        assertFalse("off every word with no card up it is the lookup's own", lookup.onTap!!.invoke(150f, 60f))
    }

    @Test fun `without the word boxes a tap on the frame is the lookup's own`() {
        open(OverlayMode.TRANSLATION)
        land(auLine(hello, left = 200, top = 600))
        val lookup: CameraWordLookup = panel!!.field("wordLookup")
        assertFalse(lookup.onTap!!.invoke(150f, 315f))
    }

    @Test fun `the same read shown again keeps the boxes and an open card`() {
        open(OverlayMode.DEFINITIONS)
        land(auLine(hello, left = 200, top = 600))
        words().onTap(150f, 315f)
        assertEquals(0, selected())
        val view = boxesView()

        backend.runs.last().value = done()
        settle()

        assertTrue("the same view, not a rebuild", view === boxesView())
        assertEquals(0, selected())
    }

    @Test fun `a re-run closes the card at once, keeps the boxes until its read lands, then boxes the new lines`() {
        open(OverlayMode.DEFINITIONS)
        land(auLine(hello, left = 200, top = 600))
        words().onTap(150f, 315f)

        panel!!.reRunReview()
        idle()
        assertNull("the card belonged to the read being replaced", selected())
        assertEquals(listOf("hello", "world"), boxesView()!!.shownBoxes.map { it.surface })

        land(auLine("good night", left = 200, top = 900))
        assertEquals(listOf("good", "night"), boxesView()!!.shownBoxes.map { it.surface })
    }

    @Test fun `a re-read that comes back with the same lines keeps the boxes and gives the next card its own frame file`() {
        open(OverlayMode.DEFINITIONS)
        backend.scene = scene(auLine(hello, left = 200, top = 600), path = "first-read.jpg")
        backend.runs.last().value = translating()
        settle()
        assertEquals("first-read.jpg", citedFrame())
        val view = boxesView()

        // A crop that still holds every line: a new read, a new frame file
        // (the first one is deleted), the very same lines.
        panel!!.reRunReview()
        backend.scene = scene(auLine(hello, left = 200, top = 600), path = "second-read.jpg")
        backend.runs.last().value = translating()
        settle()

        assertTrue("the boxes were not rebuilt", view === boxesView())
        assertEquals("second-read.jpg", citedFrame())
    }

    @Test fun `a zoom while the boxes are still being built keeps the one build, which lands on the zoomed picture`() {
        open(OverlayMode.DEFINITIONS)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var tokenized = 0
        words().tokenizeLine = { text, _ ->
            tokenized++
            gate.await()
            text.split(' ').filter { it.isNotEmpty() }.map { TokenSpan(it, it) }
        }
        backend.scene = scene(auLine(hello, left = 200, top = 600))
        backend.runs.last().value = translating()
        idle()
        assertTrue("precondition: the words are being tokenized", session()!!.pending)

        val zoom: ReviewZoom = panel!!.field("zoom")
        val onZoomChanged = FrozenReviewPanel::class.java.getDeclaredMethod("onZoomChanged").apply { isAccessible = true }
        repeat(3) {
            zoom.scaleBy(1.25f, 0f, 0f)
            onZoomChanged.invoke(panel)
            idle()
        }
        assertEquals("one line, tokenized once, however many frames the pinch ran", 1, tokenized)

        gate.complete(Unit)
        settle()

        assertEquals(1, tokenized)
        val pad = WordLookupSession.BOX_PAD_DP * activity.resources.displayMetrics.density
        val box = boxesView()!!.shownBoxes[0].rect
        // At fit "hello" starts at x = 100 on screen; zoomed about the
        // origin it starts at 100 times the zoom.
        assertEquals(100f * zoom.zoom - pad, box.left, 1f)
    }

    @Test fun `the boxes follow a zoom, and a card closes as its word moves`() {
        open(OverlayMode.DEFINITIONS)
        land(auLine(hello, left = 200, top = 600))
        words().onTap(150f, 315f)
        val before = android.graphics.RectF(boxesView()!!.shownBoxes[0].rect)

        val zoom: ReviewZoom = panel!!.field("zoom")
        assertTrue("precondition: this frame can be zoomed", zoom.zoomEnabled)
        zoom.scaleBy(2f, 0f, 0f)
        FrozenReviewPanel::class.java.getDeclaredMethod("onZoomChanged").apply { isAccessible = true }.invoke(panel)
        idle()

        val after = boxesView()!!.shownBoxes[0].rect
        assertTrue("twice as wide (${before.width()} -> ${after.width()})", after.width() > before.width() * 1.8f)
        assertNull(selected())
    }

    @Test fun `closing the review takes the boxes with it`() {
        open(OverlayMode.DEFINITIONS)
        land(auLine(hello, left = 200, top = 600))
        panel!!.dismissReview()
        idle()
        assertNull(boxesView())
        assertNull(session())
    }

    // ── the snapshot a tap on a live word took ───────────────────────────

    private fun sheet(): CaptureResultOverlay = panel!!.field("overlay")
    private fun parked(): Boolean = sheet().field("sliverMode")

    @Test fun `a live word's snapshot opens in Definitions with the sheet parked and that word's card up, writing nothing`() {
        // The review remembers None, its boxes off, and an open sheet.
        val toolPrefs = open(OverlayMode.NONE, openWord = ReviewWordRequest("world", 270f, 315f))
        assertEquals("the row shows what this review paints", OverlayMode.DEFINITIONS, panel!!.overlayMode)
        assertTrue("parked from the start, so the word is not under the sheet", parked())

        land(auLine(hello, left = 200, top = 600))

        assertEquals(listOf("hello", "world"), boxesView()!!.shownBoxes.map { it.surface })
        assertEquals("the tapped word's card", 1, selected())
        assertTrue(parked())
        assertEquals("the remembered mode is untouched", OverlayMode.NONE, toolPrefs.mode)

        panel!!.dismissReview()
        idle()
        assertEquals(OverlayMode.NONE, toolPrefs.mode)
        assertEquals("nor is the posture: the park was the tap's, not the user's", CaptureResultGeometry.NO_POSTURE, toolPrefs.posture)
    }

    @Test fun `the word is found by its text near the tap, the frame being one from just before it`() {
        // Tapped where the live preview drew it; in this frame the word sits
        // lower and to the right, and the point is nearer to "hello" (which
        // a tap there would pick) than to "world".
        open(OverlayMode.DEFINITIONS, openWord = ReviewWordRequest("world", 190f, 285f))
        land(auLine(hello, left = 200, top = 600))
        assertEquals("precondition: a plain tap there is the other word's", 0,
            com.playtranslate.ui.TapLookupGeometry.hitTest(boxesView()!!.shownBoxes, 190f, 285f, 24f * activity.resources.displayMetrics.density))
        assertEquals(1, selected())
    }

    @Test fun `a snapshot whose own read lacks the word shows its boxes and no card`() {
        open(OverlayMode.DEFINITIONS, openWord = ReviewWordRequest("world", 270f, 315f))
        land(auLine("good night", left = 200, top = 1500))
        assertEquals(listOf("good", "night"), boxesView()!!.shownBoxes.map { it.surface })
        assertNull(selected())
    }

    @Test fun `the word is asked for once, so a later read of the same snapshot opens nothing by itself`() {
        open(OverlayMode.DEFINITIONS, openWord = ReviewWordRequest("world", 270f, 315f))
        land(auLine(hello, left = 200, top = 600))
        words().closeCard()

        panel!!.reRunReview()
        land(auLine("hello world again", left = 200, top = 600))
        assertNull(selected())
    }

    @Test fun `a pick on the tool's row ends the override and is remembered`() {
        val toolPrefs = open(OverlayMode.NONE, openWord = ReviewWordRequest("world", 270f, 315f))
        land(auLine(hello, left = 200, top = 600))

        panel!!.selectOverlayMode(OverlayMode.TRANSLATION)
        settle()

        assertEquals(OverlayMode.TRANSLATION, toolPrefs.mode)
        assertEquals(OverlayMode.TRANSLATION, panel!!.overlayMode)
        assertNull(boxesView())
    }

    @Test fun `the next review, the shutter's, is back to what is remembered`() {
        val toolPrefs = open(OverlayMode.NONE, openWord = ReviewWordRequest("world", 270f, 315f))
        land(auLine(hello, left = 200, top = 600))
        panel!!.dismissReview()
        idle()

        panel!!.startReview(frame)
        idle()
        assertEquals(OverlayMode.NONE, panel!!.overlayMode)
        land(auLine(hello, left = 200, top = 600))
        assertNull(boxesView())
        assertEquals(OverlayMode.NONE, toolPrefs.mode)
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
