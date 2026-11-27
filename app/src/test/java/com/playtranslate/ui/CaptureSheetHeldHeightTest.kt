package com.playtranslate.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Insets
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.CaptureSession
import com.playtranslate.CaptureState
import com.playtranslate.OcrManager
import com.playtranslate.OneShotOverlayData
import com.playtranslate.OverlayMode
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TranslationResult
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.OwnWindows
import com.playtranslate.themeColor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * Where the capture sheet rests is its state: parked, or open at its reading
 * height (the content's fit, or where the user let it go), raised to what a
 * status on show measures. One mover takes the height there, and it moves
 * nothing while the user holds the sheet: from a finger's DOWN on its window
 * to its UP or CANCEL, and while the stick resizes it. A fit asked for
 * meanwhile (the page reporting a new layout, a text growing furigana, a
 * section shown or hidden) fits the text to the height the sheet has; a park
 * or a status the capture writes changes where the sheet will rest; the
 * gesture under the finger keeps the rules it began under. When they let go
 * the sheet settles once, from the state their own release wrote last. That
 * is what keeps a collapse drag from springing back up as the Words page
 * loads. A slide under way when the hold begins stops under the finger and
 * finishes afterwards, and a drag stops where the content's need is without
 * ever pulling the sheet down.
 *
 * Also here: the Words list's page sits on the card as it is seen, the
 * card's translucent fill over the sheet's own colour, which is the band a
 * pinned word head stands on.
 *
 * The display is set to the sheet's size before it is shown; mdpi, so a dp
 * is a px. Native graphics, so the texts measure in a real font.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CaptureSheetHeldHeightTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val host = OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    private val wm = OwnWindows.manager(ctx)!!
    private val prefs = Prefs(ctx)
    private var sheet: CaptureResultOverlay? = null
    private val dismissals = mutableListOf<CaptureResultOverlay.DismissReason>()

    private fun sp() = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    @Before fun setUp() {
        sp().edit().clear().commit()
    }

    @After fun tearDown() {
        sheet?.dismiss()
        host.removeAll()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        sp().edit().clear().commit()
    }

    // ── harness ──────────────────────────────────────────────────────────

    /** A tall phone: the sheet stacks its texts and has room to move. */
    private val screenW = 500
    private val screenH = 900

    private fun <T> Any.field(name: String): T {
        @Suppress("UNCHECKED_CAST")
        return javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T
    }

    private fun CaptureResultOverlay.call(name: String, vararg args: Int) {
        val types = args.map { Int::class.javaPrimitiveType!! }.toTypedArray()
        CaptureResultOverlay::class.java.getDeclaredMethod(name, *types)
            .apply { isAccessible = true }
            .invoke(this, *args.toTypedArray())
    }

    private fun CaptureResultOverlay.callInt(name: String): Int =
        CaptureResultOverlay::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(this) as Int

    private fun CaptureResultOverlay.setField(name: String, value: Int) {
        CaptureResultOverlay::class.java.getDeclaredField(name).apply { isAccessible = true }.setInt(this, value)
    }

    private fun constInt(name: String): Int =
        CaptureResultOverlay::class.java.getDeclaredField(name).apply { isAccessible = true }.getInt(null)

    private fun constFloat(name: String): Float =
        CaptureResultOverlay::class.java.getDeclaredField(name).apply { isAccessible = true }.getFloat(null)

    private val CaptureResultOverlay.panel: View get() = field("panel")
    private val CaptureResultOverlay.body: View get() = field("body")
    private val CaptureResultOverlay.root: ViewGroup get() = field("root")
    private val CaptureResultOverlay.binder: TranslationSectionBinder get() = field("binder")
    private val CaptureResultOverlay.panelHeight: Int get() = field("panelHeightPx")
    private val CaptureResultOverlay.need: Int get() = field("maxNeededHeightPx")
    private val CaptureResultOverlay.parked: Boolean get() = field("sliverMode")
    private val CaptureResultOverlay.heightAnimator: ValueAnimator? get() = field("heightAnimator")
    private val CaptureResultOverlay.translationTextSize: Float
        get() = panel.findViewById<TextView>(R.id.tvTranslation).textSize

    private val floor get() = CaptureResultGeometry.minPanelHeight(screenH)
    private val autoMax get() = CaptureResultGeometry.autoMaxHeight(screenH)

    /** A pangram sentence repeated [sentences] times. */
    private fun text(sentences: Int) =
        "The quick brown fox jumps over the lazy dog, again and again. ".repeat(sentences).trim()

    private fun result(original: String, translation: String) = TranslationResult(
        originalText = original,
        segments = listOf(TextSegment(original)),
        translatedText = translation,
        timestamp = "",
        backendDisplayName = "Test MT",
        langContext = prefs.langContext(),
    )

    /** Run the sheet's posted fits, its animations and the layout they
     *  cause until nothing is left pending: time passes. */
    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

    /** A sheet showing [original] and [translation], its Ask and Words
     *  sections hidden, settled at its fit. */
    private fun open(original: String, translation: String): CaptureResultOverlay {
        RuntimeEnvironment.setQualifiers("w${screenW}dp-h${screenH}dp-mdpi")
        prefs.hideOriginalSection = false
        prefs.hideTranslationSection = false
        prefs.hideWordsSection = true
        prefs.hideAskSection = true
        prefs.capturePanelPosture = CaptureResultGeometry.NO_POSTURE
        dismissals.clear()
        val o = CaptureResultOverlay(ctx, wm, Display.DEFAULT_DISPLAY, host)
        sheet = o
        o.onDismiss = { dismissals += it }
        o.showWithResult(screenW, screenH, result(original, translation))
        settle()
        return o
    }

    /** A sheet that fits its texts below its auto ceiling. */
    private fun openFitting(): CaptureResultOverlay = open(text(3), text(2)).also {
        assertTrue("precondition: room to drag it down (${it.panelHeight})", it.panelHeight - 150 > floor)
    }

    private fun panelTop(o: CaptureResultOverlay): Float = o.panel.top + o.panel.translationY

    /** On the grabber: a drag there resizes the sheet. */
    private fun grabberY(o: CaptureResultOverlay): Float = panelTop(o) + constInt("HANDLE_HEIGHT_DP") / 2f

    /** The middle of the body, below the band that grabs the sheet: a drag
     *  there is the body's. */
    private fun bodyY(o: CaptureResultOverlay): Float {
        val y = panelTop(o) + o.body.top + o.body.height / 2f
        val grabBottom = panelTop(o) + constInt("HANDLE_HEIGHT_DP") + constInt("EXTRA_GRAB_INTO_SHEET_DP")
        assertTrue("the body point is below the grab band", y > grabBottom)
        return y
    }

    /** A finger on the sheet's window, as the window delivers one. */
    private inner class Finger(private val o: CaptureResultOverlay, private val x: Float, private var y: Float) {
        private val downTime = SystemClock.uptimeMillis()
        private var t = downTime

        private fun send(action: Int) {
            val ev = MotionEvent.obtain(downTime, t, action, x, y, 0)
            o.root.dispatchTouchEvent(ev)
            ev.recycle()
        }

        fun down() = send(MotionEvent.ACTION_DOWN)

        fun moveBy(dy: Float, steps: Int = 6) {
            val from = y
            for (i in 1..steps) {
                t += 16
                y = from + dy * i / steps
                send(MotionEvent.ACTION_MOVE)
            }
        }

        fun up() {
            t += 16
            send(MotionEvent.ACTION_UP)
        }

        /** The gesture ends with no release: no click. */
        fun cancel() {
            t += 16
            send(MotionEvent.ACTION_CANCEL)
        }
    }

    /** What a text growing its furigana asks of the sheet: a fit, now. */
    private fun furiganaRefit(o: CaptureResultOverlay) {
        o.binder.onSourceTextHeightChanged!!.invoke()
    }

    // ── The hold ────────────────────────────────────────────────────────

    @Test fun `a fit asked for during a grabber drag leaves the height to the finger, and the release keeps it`() {
        val o = openFitting()
        val fitted = o.panelHeight
        val finger = Finger(o, screenW / 2f, grabberY(o))
        finger.down()
        finger.moveBy(100f)
        val dragged = o.panelHeight
        assertEquals("precondition: the drag moved the sheet", fitted - 100, dragged)
        furiganaRefit(o)
        settle()
        assertEquals("time passed with the finger down: the height is the finger's", dragged, o.panelHeight)
        finger.up()
        settle()
        assertEquals("released where the finger left it", dragged, o.panelHeight)
        assertFalse(o.parked)
    }

    @Test fun `a fit asked for during a body swipe waits for its release, fitting the text meanwhile, then runs once`() {
        // A long Original and a short Translation: together they need more
        // than the sheet's ceiling, so the texts are fitted below their
        // largest size; the Translation alone needs less.
        val o = open(text(7), text(2))
        val fitted = o.panelHeight
        assertEquals("precondition: at the ceiling", autoMax, fitted)
        val finger = Finger(o, screenW / 4f, bodyY(o))
        finger.down()
        finger.moveBy(constFloat("DISMISS_DISTANCE_DP") / 3f)
        assertTrue("precondition: the swipe follows the finger", o.panel.translationY > 0f)
        val sizeBefore = o.translationTextSize
        o.binder.toggleOriginalHidden()
        settle()
        assertEquals("the height waits for the finger", fitted, o.panelHeight)
        assertNotEquals("the text fits the height the sheet has, at once", sizeBefore, o.translationTextSize, 0.01f)
        finger.up()
        settle()
        assertEquals("the swipe sprang back", 0f, o.panel.translationY)
        assertTrue("the held fit ran: the sheet took the Translation's need (${o.panelHeight})", o.panelHeight < fitted)
        assertEquals(minOf(o.need, autoMax), o.panelHeight)
        assertTrue(dismissals.isEmpty())
    }

    @Test fun `a fit asked for during a stick resize waits for its commit, then runs once`() {
        val o = openFitting()
        val fitted = o.panelHeight
        o.call("stickResizeBy", -100)
        val held = o.panelHeight
        assertEquals("precondition: the stick moved the sheet", fitted - 100, held)
        furiganaRefit(o)
        settle()
        assertEquals("the height is the stick's", held, o.panelHeight)
        // A change the fit acts on: without the Original the content needs less.
        o.binder.toggleOriginalHidden()
        settle()
        assertEquals("still the stick's", held, o.panelHeight)
        o.call("commitStickResize")
        settle()
        assertTrue("the held fit ran at the commit (${o.panelHeight} < $held)", o.panelHeight < held)
        assertEquals(minOf(o.need, held), o.panelHeight)
    }

    @Test fun `a fit held through a drag into the collapse band leaves the sheet parked`() {
        val o = openFitting()
        val finger = Finger(o, screenW / 2f, grabberY(o))
        finger.down()
        finger.moveBy((o.panelHeight - floor + 40).toFloat())
        furiganaRefit(o)
        settle()
        finger.up()
        settle()
        assertTrue("parked", o.parked)
        assertTrue("down in the sliver (${o.panelHeight})", o.panelHeight < floor)
    }

    @Test fun `a fit held through a swipe that throws the sheet away does not run`() {
        val o = open(text(7), text(2))
        val finger = Finger(o, screenW / 4f, bodyY(o))
        finger.down()
        finger.moveBy(constFloat("DISMISS_DISTANCE_DP") / 3f)
        // A change the fit would act on: the content needs less.
        o.binder.toggleOriginalHidden()
        settle()
        finger.moveBy(constFloat("DISMISS_DISTANCE_DP"))
        finger.up()
        assertFalse("no height animation under the exit", o.heightAnimator?.isStarted == true)
        settle()
        assertEquals("thrown away", listOf(CaptureResultOverlay.DismissReason.USER), dismissals)
    }

    @Test fun `with nothing held a fit moves the height at once, as before`() {
        val o = openFitting()
        val fitted = o.panelHeight
        o.binder.toggleOriginalHidden()
        settle()
        assertTrue("the fit ran (${o.panelHeight} < $fitted)", o.panelHeight < fitted)
        assertEquals(minOf(o.need, autoMax), o.panelHeight)
    }

    @Test fun `content that shrinks during a drag leaves the sheet where the finger holds it, and the release fits it`() {
        val o = openFitting()
        val fitted = o.panelHeight
        val finger = Finger(o, screenW / 2f, grabberY(o))
        finger.down()
        finger.moveBy(60f)
        assertEquals("precondition: the drag moved the sheet", fitted - 60, o.panelHeight)
        o.binder.toggleOriginalHidden()
        settle()
        assertTrue("precondition: the content now needs less than the finger holds (${o.need})", o.need < fitted - 60)
        assertEquals("the sheet stays where the finger holds it", fitted - 60, o.panelHeight)
        finger.moveBy(-40f)
        assertEquals("the drag climbs no higher than where it is, the content needing less", fitted - 60, o.panelHeight)
        finger.up()
        settle()
        assertEquals("released, the fit brings it to what the content needs", minOf(o.need, autoMax), o.panelHeight)
    }

    // A JVM test's looper runs a whole slide in one idle, so a slide that is
    // "under way" here is one just started, with no idle since.

    @Test fun `a fit already moving the sheet when a finger lands stops under it and finishes after`() {
        val o = openFitting()
        val fitted = o.panelHeight
        // A fit with somewhere to go: a lower ceiling than the sheet's height.
        o.setField("autoMaxPx", fitted - 100)
        o.call("autoSizeAndFit")
        assertTrue("precondition: the fit's slide is under way", o.heightAnimator?.isRunning == true)
        val finger = Finger(o, screenW / 4f, bodyY(o))
        finger.down()
        assertFalse("stopped under the finger", o.heightAnimator?.isRunning == true)
        settle()
        assertEquals("the sheet stays under the finger", fitted, o.panelHeight)
        finger.cancel()
        settle()
        assertEquals("the fit finished after", fitted - 100, o.panelHeight)
    }

    // ── The capture's own parks and expands ─────────────────────────────

    private val boxRect = Rect(100, 100, 300, 140)

    private fun boxes(text: String) = OneShotOverlayData(
        boxes = listOf(TextBox(text, boxRect)),
        cropLeft = 0, cropTop = 0, screenshotW = screenW, screenshotH = screenH,
        cropWidth = screenW, cropHeight = screenH,
        text = listOf(boxRect),
        groups = listOf(OcrManager.OcrGroup(text, boxRect, lines = listOf(OcrManager.LineBox(text, boxRect, groupIndex = 0)))),
    )

    /** OCR has landed with the long text [source], its boxes on screen. */
    private fun translating(source: String, boxText: String) =
        CaptureState.Translating(source, listOf(TextSegment(source)), null, boxes(boxText))

    /** The capture runs again on this sheet (a camera's new region, a
     *  settings change): its loading status, then OCR landing with boxes,
     *  which is where the collapsed posture asks for the park again. */
    private fun rerun(flow: MutableStateFlow<CaptureState>, boxText: String) {
        flow.value = CaptureState.InProgress("Recognizing text")
        shadowOf(Looper.getMainLooper()).idle()
        flow.value = translating(text(7), boxText)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** A sheet whose capture parks it on its boxes (a collapsed posture,
     *  translations painted over the game), with a text long enough to
     *  leave a drag room. */
    private fun openParked(source: String = text(7)): Pair<CaptureResultOverlay, MutableStateFlow<CaptureState>> {
        RuntimeEnvironment.setQualifiers("w${screenW}dp-h${screenH}dp-mdpi")
        prefs.hideOriginalSection = false
        prefs.hideTranslationSection = false
        prefs.hideWordsSection = true
        prefs.hideAskSection = true
        prefs.captureOverlayMode = OverlayMode.TRANSLATION
        prefs.capturePanelPosture = CaptureResultGeometry.COLLAPSED_POSTURE
        val o = CaptureResultOverlay(ctx, wm, Display.DEFAULT_DISPLAY, host)
        sheet = o
        o.onDismiss = { dismissals += it }
        o.show(screenW, screenH)
        val flow = MutableStateFlow<CaptureState>(CaptureState.InProgress("Capturing"))
        o.observe(CaptureSession(flow, Job()))
        flow.value = CaptureState.Translating(source, listOf(TextSegment(source)), null, boxes("hello"))
        settle()
        assertTrue("precondition: parked on the boxes", o.parked)
        return o to flow
    }

    /** [openParked], then brought up by the sheet itself (its rescue, which
     *  states no posture). */
    private fun openParkedThenUp(): Pair<CaptureResultOverlay, MutableStateFlow<CaptureState>> {
        val (o, flow) = openParked()
        o.call("expandFromSliver")
        settle()
        assertFalse("precondition: brought up", o.parked)
        return o to flow
    }

    /** A sheet over a capture of its own on a [w] by [h] display: its
     *  loading status, then [result]. */
    private fun openCapture(w: Int, h: Int, result: TranslationResult): Pair<CaptureResultOverlay, MutableStateFlow<CaptureState>> {
        val (o, flow) = showLoading(w, h)
        flow.value = CaptureState.Done(result)
        settle()
        return o to flow
    }

    /** A sheet whose capture is still recognizing text, on a [w] by [h]
     *  display. */
    private fun showLoading(w: Int, h: Int): Pair<CaptureResultOverlay, MutableStateFlow<CaptureState>> {
        RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-mdpi")
        prefs.hideOriginalSection = false
        prefs.hideTranslationSection = false
        prefs.hideWordsSection = true
        prefs.hideAskSection = true
        prefs.capturePanelPosture = CaptureResultGeometry.NO_POSTURE
        val o = CaptureResultOverlay(ctx, wm, Display.DEFAULT_DISPLAY, host)
        sheet = o
        o.onDismiss = { dismissals += it }
        o.show(w, h)
        val flow = MutableStateFlow<CaptureState>(CaptureState.InProgress("Recognizing text"))
        o.observe(CaptureSession(flow, Job()))
        settle()
        return o to flow
    }

    @Test fun `a park the capture writes during a drag moves nothing, the drag goes on, and its release keeps the sheet up`() {
        val (o, flow) = openParkedThenUp()
        val finger = Finger(o, screenW / 2f, grabberY(o))
        finger.down()
        finger.moveBy(-60f)
        val held = o.panelHeight
        rerun(flow, "again")
        assertTrue("precondition: the capture parked the sheet", o.parked)
        settle()
        assertEquals("nothing moved under the finger", held, o.panelHeight)
        finger.moveBy(30f)
        assertEquals("the drag keeps the rules it began under", held - 30, o.panelHeight)
        finger.up()
        settle()
        assertFalse("the drag stated where the sheet lives", o.parked)
        assertEquals("where the drag left it", held - 30, o.panelHeight)
    }

    @Test fun `a park the capture writes during a touch the system cancels parks the sheet once it ends`() {
        val (o, flow) = openParkedThenUp()
        val held = o.panelHeight
        val finger = Finger(o, screenW / 4f, bodyY(o))
        finger.down()
        rerun(flow, "again")
        settle()
        assertEquals("nothing moved under the finger", held, o.panelHeight)
        finger.cancel()
        settle()
        assertTrue("parked once the finger lifted", o.parked)
        assertEquals(o.callInt("sliverHeightPx"), o.panelHeight)
    }

    @Test fun `a park the capture writes during a tap on the open sheet does not land, the user was using it`() {
        val (o, flow) = openParkedThenUp()
        val held = o.panelHeight
        val finger = Finger(o, screenW / 4f, bodyY(o))
        finger.down()
        rerun(flow, "again")
        assertTrue("precondition: the capture parked the sheet", o.parked)
        finger.up()
        settle()
        assertFalse("still open", o.parked)
        assertEquals(held, o.panelHeight)
    }

    @Test fun `a park the capture writes during a tap on the grabber lands when the finger lifts`() {
        val (o, flow) = openParkedThenUp()
        val finger = Finger(o, screenW / 2f, grabberY(o))
        finger.down()
        rerun(flow, "again")
        finger.up()
        settle()
        assertTrue("a grab that went nowhere states nothing", o.parked)
        assertEquals(o.callInt("sliverHeightPx"), o.panelHeight)
    }

    @Test fun `a tap nothing on an in-app sheet takes holds nothing, so the result still fits it`() {
        RuntimeEnvironment.setQualifiers("w${screenW}dp-h${screenH}dp-mdpi")
        prefs.hideOriginalSection = false
        prefs.hideTranslationSection = false
        prefs.hideWordsSection = true
        prefs.hideAskSection = true
        prefs.capturePanelPosture = CaptureResultGeometry.NO_POSTURE
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val parent = FrameLayout(activity)
        activity.setContentView(parent)
        val o = CaptureResultOverlay(
            activity, activity.windowManager, Display.DEFAULT_DISPLAY, host,
            sheetHost = ActivitySheetHost(parent),
        )
        sheet = o
        o.show(screenW, screenH)
        val flow = MutableStateFlow<CaptureState>(CaptureState.InProgress("Recognizing text"))
        o.observe(CaptureSession(flow, Job()))
        settle()
        val loading = o.panelHeight
        // On the status, where nothing takes a touch: the activity's layout
        // sends this gesture's end to no one in the sheet.
        val y = panelTop(o) + o.body.top + o.body.height / 2f
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, screenW / 4f, y, 0)
        assertFalse("precondition: nothing on the sheet takes it", parent.dispatchTouchEvent(down))
        down.recycle()
        val up = MotionEvent.obtain(t, t + 16, MotionEvent.ACTION_UP, screenW / 4f, y, 0)
        parent.dispatchTouchEvent(up)
        up.recycle()
        flow.value = CaptureState.Done(result(text(3), text(2)))
        settle()
        assertTrue("the result's fit moved the sheet (${o.panelHeight} > $loading)", o.panelHeight > loading)
        assertEquals(minOf(o.need, autoMax), o.panelHeight)
    }

    @Test fun `a navigation bar that changes while parked is in the reading height the sheet comes back to`() {
        val (o, _) = openParked(source = text(1))
        val reading: Int = o.field("readingHeightPx")
        assertTrue("precondition: the content fits below the auto ceiling ($reading)", reading in (floor + 1) until autoMax - 48)
        o.root.dispatchApplyWindowInsets(navigationBar(o, 48))
        settle()
        assertEquals("the fit took the bar in while parked", reading + 48, o.field<Int>("readingHeightPx"))
        o.call("expandFromSliver")
        settle()
        assertEquals(reading + 48, o.panelHeight)
    }

    @Test fun `the capture bringing the sheet up during a sliver drag the user lets fall back leaves it parked`() {
        val (o, flow) = openParked()
        val sliver = o.panelHeight
        val finger = Finger(o, screenW / 2f, panelTop(o) + 4f)
        finger.down()
        finger.moveBy(-40f)
        val pulled = o.panelHeight
        assertTrue("precondition: pulled up, short of the floor ($pulled)", pulled in (sliver + 1) until floor)
        // Nothing came back to paint: the capture brings the sheet up.
        flow.value = CaptureState.Done(result(text(7), text(2)))
        settle()
        assertFalse("precondition: the capture brought it up", o.parked)
        assertEquals("nothing moved under the finger", pulled, o.panelHeight)
        finger.up()
        settle()
        assertTrue("let fall back, the user kept it parked", o.parked)
        assertEquals(sliver, o.panelHeight)
    }

    @Test fun `out of the park the sheet slides once, straight to its reading height, its text already sized for it`() {
        val (o, _) = openParked()
        val reading: Int = o.field("readingHeightPx")
        assertTrue("precondition: the fit kept the reading height while parked ($reading)", reading > floor)
        o.call("expandFromSliver")
        assertEquals("one slide, to the reading height", reading, o.field<Int>("settleTargetPx"))
        val size = o.translationTextSize
        settle()
        assertEquals(reading, o.panelHeight)
        assertEquals("the text was at the size it landed at", size, o.translationTextSize, 0.01f)
    }

    @Test fun `a navigation bar coming and going moves a parked sheet's strip at once, and waits for a finger to lift`() {
        val (o, _) = openParked()
        val bare = o.callInt("sliverHeightPx")
        o.root.dispatchApplyWindowInsets(navigationBar(o, 48))
        val withBar = o.callInt("sliverHeightPx")
        assertEquals("precondition: the strip rides the bar", bare + 48, withBar)
        assertEquals("at once", withBar, o.panelHeight)
        val finger = Finger(o, screenW / 2f, screenH - 10f)
        finger.down()
        o.root.dispatchApplyWindowInsets(navigationBar(o, 0))
        settle()
        assertEquals("not under the finger", withBar, o.panelHeight)
        finger.cancel()
        settle()
        assertEquals("with the finger gone", bare, o.panelHeight)
        assertTrue(o.parked)
    }

    /** The sheet's window insets with a bottom navigation bar [height] tall,
     *  shown, or gone for 0. */
    private fun navigationBar(o: CaptureResultOverlay, height: Int): WindowInsets =
        WindowInsets.Builder(o.root.rootWindowInsets)
            .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, height))
            .setVisible(WindowInsets.Type.navigationBars(), height > 0)
            .build()

    @Test fun `an expand already sliding when a finger lands stops under it and finishes after`() {
        val (o, _) = openParkedThenUp()
        o.call("collapseToSliver")
        settle()
        assertTrue("precondition: parked", o.parked)
        o.call("expandFromSliver")
        assertTrue("precondition: the expand is under way", o.heightAnimator?.isRunning == true)
        // On the strip the sheet stands at, below the band that grabs it:
        // a touch, not a resize.
        val y = panelTop(o) + constInt("HANDLE_HEIGHT_DP") + constInt("EXTRA_GRAB_INTO_SHEET_DP") + 2f
        assertTrue("precondition: on the sheet", y < o.panel.bottom + o.panel.translationY)
        val finger = Finger(o, screenW / 4f, y)
        finger.down()
        assertFalse("stopped under the finger", o.heightAnimator?.isRunning == true)
        val held = o.panelHeight
        settle()
        assertEquals("the sheet stays under the finger", held, o.panelHeight)
        finger.cancel()
        settle()
        assertTrue("the expand finished after (${o.panelHeight})", o.panelHeight >= floor)
        assertFalse(o.parked)
    }

    @Test fun `a drag takes over from an expand that was sliding, which does not come back`() {
        val (o, _) = openParkedThenUp()
        o.call("collapseToSliver")
        settle()
        o.call("expandFromSliver")
        assertTrue("precondition: the expand is under way", o.heightAnimator?.isRunning == true)
        val start = o.panelHeight
        val finger = Finger(o, screenW / 2f, grabberY(o))
        finger.down()
        finger.moveBy(-200f)
        assertEquals("precondition: the drag moved the sheet out of the collapse band", start + 200, o.panelHeight)
        assertTrue(o.panelHeight > floor)
        finger.up()
        assertFalse("nothing slides on after the drag", o.heightAnimator?.isRunning == true)
        settle()
        assertEquals("where the drag left it", start + 200, o.panelHeight)
        assertFalse(o.parked)
    }

    @Test fun `a drag begun before the content was measured stops at what it needs once it is`() {
        val (o, flow) = showLoading(screenW, screenH)
        val finger = Finger(o, screenW / 2f, grabberY(o))
        finger.down()
        flow.value = CaptureState.Done(result(text(3), text(2)))
        settle()
        val before = o.panelHeight
        assertTrue(
            "precondition: measured under the finger, past the sheet's height (${o.need}, $before)",
            o.need in (before + 1) until CaptureResultGeometry.maxPanelHeight(screenH),
        )
        finger.moveBy(-(screenH).toFloat())
        assertEquals("stopped at what the content needs", o.need, o.panelHeight)
        finger.up()
        settle()
        assertEquals(o.need, o.panelHeight)
    }

    @Test fun `a status that replaces the result during a hold rests the sheet at what the status needs, never the result's fit`() {
        // A short display, where a status block needs more than the 20% floor.
        val w = 900
        val h = 300
        val (o, flow) = openCapture(w, h, result("Hi", "Hello"))
        val min = CaptureResultGeometry.minPanelHeight(h)
        // The user wants the sheet as low as it goes.
        val grab = Finger(o, w / 2f, grabberY(o))
        grab.down()
        grab.moveBy((o.panelHeight - min).toFloat())
        grab.up()
        settle()
        assertEquals("precondition: let go at the floor", min, o.panelHeight)
        val finger = Finger(o, w / 4f, bodyY(o))
        finger.down()
        furiganaRefit(o)
        flow.value = CaptureState.Failed("Translation failed")
        settle()
        assertEquals("nothing moved under the finger", min, o.panelHeight)
        val status = o.callInt("statusFloorPx")
        assertTrue("precondition: the status needs more than the floor ($status)", status > min)
        finger.cancel()
        settle()
        assertEquals("at what the status needs", status, o.panelHeight)
    }

    @Test fun `a fit asked for while a status is on show moves nothing`() {
        val (o, flow) = openCapture(screenW, screenH, result(text(3), text(2)))
        val fitted = o.panelHeight
        flow.value = CaptureState.Failed("Translation failed")
        settle()
        assertEquals("precondition: the status keeps the sheet's height", fitted, o.panelHeight)
        val reading: Int = o.field("readingHeightPx")
        // The hidden result's content changes: its fit would shrink the sheet.
        o.binder.toggleOriginalHidden()
        settle()
        furiganaRefit(o)
        settle()
        assertEquals("the result the status replaced does not size the sheet", fitted, o.panelHeight)
        assertEquals(reading, o.field<Int>("readingHeightPx"))
    }

    @Test fun `a drag pulls the sheet no higher than its content needs`() {
        val o = openFitting()
        val finger = Finger(o, screenW / 2f, grabberY(o))
        finger.down()
        finger.moveBy(-200f)
        assertEquals("stopped at what the content needs", o.need, o.panelHeight)
        finger.up()
        settle()
        assertEquals(o.need, o.panelHeight)
    }

    @Test fun `a tap on the sheet while a park slides brings it up, and the park does not come back`() {
        val (o, _) = openParkedThenUp()
        o.call("collapseToSliver")
        assertTrue("precondition: the park is under way", o.heightAnimator?.isRunning == true)
        // Parking, the sheet takes the sliver's rules: a tap on it expands.
        val finger = Finger(o, screenW / 2f, panelTop(o) + 4f)
        finger.down()
        finger.up()
        settle()
        assertFalse("brought up", o.parked)
        assertTrue("at a height to read (${o.panelHeight})", o.panelHeight >= floor)
    }

    @Test fun `a status that lands during a drag waits for the release before it floors the height`() {
        val (o, flow) = openParkedThenUp()
        val finger = Finger(o, screenW / 2f, grabberY(o))
        finger.down()
        finger.moveBy((o.panelHeight - 50).toFloat())
        assertEquals("precondition: the drag holds the sheet low", 50, o.panelHeight)
        flow.value = CaptureState.InProgress("Recognizing text")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("precondition: the status needs more than the finger holds", o.callInt("statusFloorPx") > 50)
        assertEquals("the height is the finger's", 50, o.panelHeight)
        finger.up()
        settle()
        assertTrue("released in the collapse band, parked", o.parked)
    }

    // ── None's hold: a height the user sets ends it ─────────────────────

    /** [openParked], then None picked: the sheet up with nothing drawn. */
    private fun openParkedThenNone(): CaptureResultOverlay {
        val (o, _) = openParked()
        o.selectOverlayMode(OverlayMode.NONE)
        settle()
        assertFalse("precondition: None brought it up", o.parked)
        return o
    }

    @Test fun `a drag that moved the sheet while None held it up keeps it up through the next pick`() {
        val o = openParkedThenNone()
        val finger = Finger(o, screenW / 2f, grabberY(o))
        finger.down()
        finger.moveBy(60f)
        finger.up()
        settle()
        val dragged = o.panelHeight
        o.selectOverlayMode(OverlayMode.TRANSLATION)
        settle()
        assertFalse("the user set its height", o.parked)
        assertEquals(dragged, o.panelHeight)
    }

    @Test fun `a grab that went nowhere while None held the sheet up leaves the next pick to park it`() {
        val o = openParkedThenNone()
        val finger = Finger(o, screenW / 2f, grabberY(o))
        finger.down()
        finger.up()
        settle()
        o.selectOverlayMode(OverlayMode.TRANSLATION)
        settle()
        assertTrue("a grab that went nowhere sets no height", o.parked)
    }

    @Test fun `a stick resize while None held the sheet up keeps it up through the next pick`() {
        val o = openParkedThenNone()
        o.call("stickResizeBy", -100)
        o.call("commitStickResize")
        settle()
        o.selectOverlayMode(OverlayMode.TRANSLATION)
        settle()
        assertFalse("the user set its height", o.parked)
    }

    // ── The band's colour ───────────────────────────────────────────────

    @Test fun `the Words list's page sits on the card as it is seen, opaque`() {
        val o = openFitting()
        val words: WordRowsBinder = o.field("wordRows")
        val document: DefinitionDocument = words.field<WordRowList<*>>("list").field("document")
        val theme = document.field<() -> DefinitionsDocument.DocTheme>("theme").invoke()
        val sheetCtx: Context = o.field("ctx")
        val expected = ColorUtils.compositeColors(
            sectionCardFill(sheetCtx, constFloat("CARD_FILL_ALPHA")),
            sheetCtx.themeColor(R.attr.ptBg),
        )
        assertEquals(Integer.toHexString(expected), Integer.toHexString(theme.tokens.panel))
        assertEquals("opaque", 0xFF, theme.tokens.panel ushr 24)
        assertNotEquals("not the theme's card colour", sheetCtx.themeColor(R.attr.ptCard), theme.tokens.panel)
    }
}
