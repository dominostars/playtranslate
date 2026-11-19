package com.playtranslate

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CaptureLifecycle
import com.playtranslate.capture.CapturedFrame
import com.playtranslate.capture.DeliverySignal
import com.playtranslate.capture.LiveCaptureSource
import com.playtranslate.model.TranslationResult
import com.playtranslate.ocr.FrameReader
import com.playtranslate.ocr.FrameReaders
import com.playtranslate.ocr.FrameReading
import com.playtranslate.ocr.ReadRequest
import com.playtranslate.ocr.ReaderAttribution
import com.playtranslate.overlay.OverlayPlacement
import com.playtranslate.overlay.OwnActivities
import com.playtranslate.overlay.OwnWindows
import com.playtranslate.overlay.WindowChurnGate
import com.playtranslate.testutil.testEnvironment
import com.playtranslate.translation.FakeOnlineBackend
import com.playtranslate.translation.TranslationBackend
import com.playtranslate.translation.TranslationBackendRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/**
 * The frames the results-only loop ([ReconcilerLiveMode] with the
 * [PanelPresenter]) takes from a source whose frames hold this app's own
 * windows (whole-screen recording, accessibility screenshots), by where its
 * results show ([CaptureService.liveSurface]):
 *
 *  - the detached window's page: raw frames only, so the window is never
 *    blanked for a read; the window's rect is painted black in what the OCR
 *    is given; and no frame is taken while the icon stands in for the
 *    window, while something of ours is open over the game, or while a
 *    window of ours just removed may still be in the frames;
 *  - anywhere else: the clean capture it always took, and a page left from
 *    a window holds nothing.
 *
 * The loop is the real one on the real controller and service, cycle by
 * cycle on the main looper (the harness of [LiveNoTextMemoTest]): the frames
 * come from a fake source through the mode's test seam, which counts the
 * clean and the raw grabs apart, and the read is a reader that keeps what it
 * was handed. The display is a 1280 x 800 tablet, where the window is the
 * page.
 */
@RunWith(RobolectricTestRunner::class)
// The blackout is a canvas fill and the test reads its pixels back: the
// legacy shadows rasterize neither (OverlayToolkitBlackoutTest).
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WindowLiveFramesTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private val d0 = Display.DEFAULT_DISPLAY
    private var service: ServiceController<CaptureService>? = null
    private lateinit var svc: CaptureService
    private lateinit var a11y: PlayTranslateAccessibilityService
    private lateinit var controller: OverlayUiController
    private val source = Frames()
    private val reader = KeepingReader()
    private var mode: LiveMode? = null
    private val activities = mutableListOf<ActivityController<Activity>>()

    private val w = 1280
    private val h = 800

    /** A capture source whose frames are the whole screen's: system UI and
     *  our own windows in them, unless the grab was a clean one. */
    private inner class Frames : LiveCaptureSource {
        var cleanGrabs = 0
        var rawGrabs = 0
        val grabs: Int get() = cleanGrabs + rawGrabs
        override val minCaptureIntervalMs = 500L
        override val framesIncludeSystemUi = true
        override val hasAnyLoop = false
        /** Set by a test whose stream stands still ([StillStream]). */
        var still: StillStream? = null
        override val deliverySignal: DeliverySignal? get() = still
        private fun frame(ownOverlays: Boolean): CapturedFrame {
            still?.served()
            return CapturedFrame(checker(), includesSystemUi = true, includesOwnOverlays = ownOverlays)
        }
        override suspend fun requestClean(displayId: Int, maskOwnWindows: Boolean): CapturedFrame {
            cleanGrabs++
            return frame(ownOverlays = false)
        }
        override suspend fun requestRaw(
            displayId: Int,
            onCaptured: (() -> Unit)?,
            maskOwnWindows: Boolean,
        ): CapturedFrame {
            rawGrabs++
            return frame(ownOverlays = true)
        }
        override fun saveToCache(bitmap: Bitmap, displayId: Int): String? = null
        override fun destroy() {}
        override fun startLoop(
            displayId: Int,
            scope: CoroutineScope,
            onCleanFrame: (CapturedFrame) -> Unit,
            onRawFrame: (CapturedFrame) -> Unit,
        ) {}
        override fun requestCleanCapture(displayId: Int) {}
        override fun requestCleanCaptureAll() {}
        override fun stopLoop(displayId: Int) {}
        override fun stopAllLoops() {}
        override fun isLoopRunning(displayId: Int) = false
    }

    /** Finds [text] in every frame (none unless a test sets it), and keeps
     *  the last frame it was handed to read. */
    private class KeepingReader : FrameReader {
        var given: Bitmap? = null
        var text: String? = null
        private val me = ReaderAttribution("Keeping", "keeping")
        override suspend fun read(bitmap: Bitmap, request: ReadRequest): FrameReading {
            given = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            val t = text ?: return FrameReading(null, me, translations = null)
            val bounds = Rect(40, 150, 200, 170)
            val group = OcrManager.OcrGroup(
                text = t, bounds = bounds,
                lines = listOf(OcrManager.LineBox(text = t, bounds = bounds, groupIndex = 0)),
            )
            return FrameReading(
                OcrManager.OcrResult(fullText = t, segments = emptyList(), groups = listOf(group)),
                me, translations = null,
            )
        }
    }

    /** A stream that delivered one frame and delivers no other, as a game
     *  standing still on a line of dialogue is: once that frame is served
     *  the loop parks until something forces a cycle. */
    private class StillStream : DeliverySignal {
        override var lastServedSeq = 0L
        override fun seqNow() = 1L
        override suspend fun awaitSeqAfter(seq: Long) {
            if (seq >= 1L) awaitCancellation()
        }
        fun served() { lastServedSeq = 1L }
    }

    /** A translation that does not come back until the test lets it. */
    private class WaitingBackend : TranslationBackend {
        override val id = "waiting"
        override val displayName = "Waiting"
        override val priority = 10
        override val requiresInternet = true
        override val isDegradedFallback = false
        private val gate = CompletableDeferred<Unit>()
        @Volatile var asked = false
        @Volatile var answered = false
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        override fun isUsable(source: String, target: String) = true
        override suspend fun translate(text: String, source: String, target: String): String {
            calls.incrementAndGet()
            asked = true
            gate.await()
            answered = true
            return "translated"
        }
        fun letGo() { gate.complete(Unit) }
    }

    /** A game-like frame with no black in it: a two-tone grey checker. */
    private fun checker(): Bitmap {
        val dark = Color.rgb(40, 40, 40)
        val light = Color.rgb(200, 200, 200)
        val px = IntArray(w * h) { i -> if (((i % w) / 8 + (i / w) / 8) % 2 == 0) dark else light }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, w, 0, 0, w, h) }
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Capture intervals enough for several cycles. */
    private fun cycles() = idle(4_000)

    @Before fun setUp() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        android.provider.Settings.Global.putInt(ctx.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 57)
        CaptureLifecycle.setFloatingIconSuppressed(ctx, false)
        prefs.captureDisplayIds = setOf(d0)
        OwnActivities.displayIdProbe = { d0 }
        TranslationCache.shared.resetForTest()
        TranslationBackendRegistry.init(listOf(FakeOnlineBackend(id = "fake", priority = 10)))
        a11y = Robolectric.buildService(PlayTranslateAccessibilityService::class.java).create().get()
        PlayTranslateAccessibilityService.instance = a11y
        a11y.overlayHost.environmentReader = { testEnvironment(w, h, belowBars = false) }
        controller = a11y.overlayUiController
        val built = Robolectric.buildService(CaptureService::class.java).create()
        service = built
        svc = built.get()
        svc.configureSaved(d0)
        FrameReaders.overrideForTest = reader
    }

    @After fun tearDown() {
        ShadowChoreographer.setPaused(false)
        mode?.stop()
        mode = null
        FrameReaders.overrideForTest = null
        activities.forEach { runCatching { it.pause().stop().destroy() } }
        MainActivity.isInForeground = false
        service?.destroy()
        service = null
        controller.hideAll()
        PlayTranslateAccessibilityService.instance = null
        CaptureLifecycle.setFloatingIconSuppressed(ctx, true)
        idle(5_000)
        TranslationBackendRegistry.close()
        TranslationCache.shared.resetForTest()
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** The display's controls, in the form [form] was saved as. */
    private fun controlsUp(form: ControlsForm) {
        prefs.setControlsForm(d0, form)
        controller.reconcileFloatingIcons(freshAppearance = false)
        idle(200)
    }

    private fun startLoop() {
        mode = ReconcilerLiveMode(svc, d0, PanelPresenter(svc, d0), streamSource = { source }).also { it.start() }
    }

    private fun window() = controller.detachedWindowForTest(d0)

    // ── Which frame ──────────────────────────────────────────────────────

    @Test fun `with its results in the window's page the loop takes raw frames, and never a clean one`() {
        controlsUp(ControlsForm.WINDOW)
        assertNotNull(window())
        assertEquals(LiveSurface.WINDOW_PAGE, svc.liveSurface())

        startLoop()
        cycles()

        assertTrue("it reads: ${source.rawGrabs} raw", source.rawGrabs >= 2)
        assertEquals("a clean capture would blank the window the user is reading", 0, source.cleanGrabs)
    }

    @Test fun `with its results anywhere else the loop takes the clean frame it always took`() {
        controlsUp(ControlsForm.ICON)
        assertNull(window())
        assertEquals(LiveSurface.OVERLAYS, svc.liveSurface())

        startLoop()
        cycles()

        assertTrue("it reads, though no window is up: ${source.cleanGrabs} clean", source.cleanGrabs >= 2)
        assertEquals(0, source.rawGrabs)
    }

    // ── The window is not read ───────────────────────────────────────────

    /** The smallest rect that holds every pure black pixel of [b]. */
    private fun blackBox(b: Bitmap): Rect? {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var bt = -1
        for (i in px.indices) {
            if (px[i] != Color.BLACK) continue
            val x = i % b.width; val y = i / b.width
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > bt) bt = y
        }
        return if (r < 0) null else Rect(l, t, r + 1, bt + 1)
    }

    @Test fun `the window's rect is black in what the OCR is given, and nothing else is`() {
        controlsUp(ControlsForm.WINDOW)
        val at = window()!!.screenRect()!!
        assertTrue("the window is on the display: $at", Rect(0, 0, w, h).contains(at))

        startLoop()
        cycles()

        val given = reader.given!!
        // The crop keeps the frame's width and takes rows off its top only
        // (the whole-screen region, less the status bar where there is one).
        val cropTop = h - given.height
        assertEquals(w, given.width)
        val expected = Rect(at).apply { offset(0, -cropTop) }
        assertEquals("the window, and only the window", expected, blackBox(given))
        val inside = given.getPixel(expected.centerX(), expected.centerY())
        assertEquals("solid through its middle", Color.BLACK, inside)
    }

    // ── The loop waits ───────────────────────────────────────────────────

    /** Reads between now and after several intervals. */
    private fun readsOver(block: () -> Unit = {}): Int {
        val before = source.grabs
        block()
        cycles()
        return source.grabs - before
    }

    private fun ownActivity(): ActivityController<Activity> =
        Robolectric.buildActivity(Activity::class.java).also { activities += it }

    @Test fun `no frame is taken while the icon stands in for the window, and reading goes on when it is back`() {
        controlsUp(ControlsForm.WINDOW)
        startLoop()
        cycles()

        val screen = ownActivity().create().start()
        idle(200)
        assertNull("the icon stands in", window())
        assertEquals("the session is still the window's", LiveSurface.WINDOW_PAGE, svc.liveSurface())
        assertTrue(controller.windowBusy(d0))
        assertEquals("our own screen is not read as the game", 0, readsOver())

        screen.stop()
        idle(200)
        assertNotNull(window())
        assertTrue("reading goes on", readsOver() >= 2)
        assertEquals(0, source.cleanGrabs)
    }

    /** Something of ours opened over the game, as the surfaces the user
     *  works in are: placed as content. */
    private fun openOverGame(): View {
        val view = View(ctx)
        val wm = OwnWindows.manager(ctx)!!
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            a11y.overlayHost.windowType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        )
        assertTrue(a11y.overlayHost.addOverlayWindow(view, wm, params, d0, OverlayPlacement.Content))
        return view
    }

    @Test fun `no frame is taken while something of ours is open over the game, nor until its removal has left the frames`() {
        controlsUp(ControlsForm.WINDOW)
        startLoop()
        cycles()
        assertFalse(controller.windowBusy(d0))

        val opened = openOverGame()
        assertTrue(a11y.overlayHost.hasContentWindowOn(d0))
        assertTrue(controller.windowBusy(d0))
        assertEquals("its text is not the game's", 0, readsOver())

        // Frames come slower than the gate's timeout from here, so the
        // removal is not seen to composite: the wait is the gate's own.
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(WindowChurnGate.COMPOSITE_TIMEOUT_MS))
        a11y.overlayHost.removeOverlayWindow(opened)
        assertFalse("nothing is open any more", controller.windowBusy(d0))
        assertTrue("but it may still be in a frame", WindowChurnGate.hasUncompositedGhostOn(d0))
        val before = source.grabs
        idle(WindowChurnGate.COMPOSITE_TIMEOUT_MS / 2)
        assertTrue(WindowChurnGate.hasUncompositedGhostOn(d0))
        assertEquals("so no frame is taken yet", before, source.grabs)

        ShadowChoreographer.setPaused(false)
        assertTrue("and reading goes on once it has gone", readsOver() >= 2)
        assertEquals(0, source.cleanGrabs)
    }

    // ── A result that comes back while the window is busy ────────────────

    /** Turns of the main looper with real time between them, for work that
     *  runs off it (a translation), until [done] or about two seconds. */
    private fun turnsUntil(done: () -> Boolean): Boolean {
        repeat(200) {
            idle(50)
            if (done()) return true
            Thread.sleep(10)
        }
        return done()
    }

    private fun delivered(): TranslationResult? = (svc.panelState.value as? PanelState.Result)?.result

    @Test fun `a result translated while something is open from the window waits for it to close, and then shows`() {
        controlsUp(ControlsForm.WINDOW)
        val backend = WaitingBackend()
        TranslationBackendRegistry.init(listOf(backend))
        reader.text = "こんにちは"

        startLoop()
        assertTrue("the loop read the line and is translating it", turnsUntil { backend.asked })
        assertNull(delivered())

        // The user taps a word, or opens the menu: the translation is still
        // out, and comes back under what they opened.
        val opened = openOverGame()
        backend.letGo()
        assertTrue(turnsUntil { backend.answered })
        repeat(20) { idle(50); Thread.sleep(10) }
        assertNull("the page is not rewritten under what was opened from it", delivered())

        a11y.overlayHost.removeOverlayWindow(opened)

        assertTrue("with the window free the result held back is delivered", turnsUntil { delivered() != null })
        assertEquals("こんにちは", delivered()!!.originalText)
        assertEquals("translated once, not again for the delivery", 1, backend.calls.get())
    }

    @Test fun `on a screen that stands still a result held back still shows once the window is free`() {
        controlsUp(ControlsForm.WINDOW)
        val backend = WaitingBackend()
        TranslationBackendRegistry.init(listOf(backend))
        reader.text = "こんにちは"
        // No new frame will wake the loop: what is owed has to be asked for.
        source.still = StillStream()

        startLoop()
        assertTrue(turnsUntil { backend.asked })
        val opened = openOverGame()
        backend.letGo()
        assertTrue(turnsUntil { backend.answered })
        repeat(20) { idle(50); Thread.sleep(10) }
        assertNull(delivered())

        a11y.overlayHost.removeOverlayWindow(opened)

        assertTrue("delivered without waiting for the game to move", turnsUntil { delivered() != null })
        assertEquals("こんにちは", delivered()!!.originalText)
    }

    @Test fun `a result translated with the window free shows at once`() {
        controlsUp(ControlsForm.WINDOW)
        val backend = WaitingBackend()
        TranslationBackendRegistry.init(listOf(backend))
        reader.text = "こんにちは"

        startLoop()
        assertTrue(turnsUntil { backend.asked })
        backend.letGo()

        assertTrue(turnsUntil { delivered() != null })
        assertEquals("こんにちは", delivered()!!.originalText)
    }

    @Test fun `a page left from a window holds nothing where the results show elsewhere`() {
        // The window's form is saved, and the app is in front on a second
        // screen: the icon stands in, and the results are not the window's.
        controlsUp(ControlsForm.WINDOW)
        org.robolectric.shadows.ShadowDisplayManager.addDisplay("w640dp-h480dp")
        MainActivity.isInForeground = true
        controller.reconcileFloatingIcons(freshAppearance = false)
        idle(200)
        assertNull(window())
        assertTrue("the window is not there", controller.windowBusy(d0))
        assertEquals(LiveSurface.OVERLAYS, svc.liveSurface())

        startLoop()

        assertTrue("the loop reads all the same", readsOver() >= 2)
        assertEquals("with the clean frame of that surface", 0, source.rawGrabs)
    }
}
