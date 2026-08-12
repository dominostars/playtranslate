package com.playtranslate.ui

import android.app.Activity
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.R
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer

/**
 * Tap-to-place on [WaveformTrimView]: a tap on unselected waveform re-places
 * the whole selection there — snapped onto a detected voice line when the tap
 * lands on (or a finger's width from) one, else a [WaveformTrimView.tapSelectionMs]
 * window centered on the tapped moment. Plus what the edge gutters do with a
 * press: scroll while they hold a more-audio arrow, place like any other
 * margin when they don't.
 *
 * Coordinate math is exact rather than approximate because the main fixture
 * opens FULLY ZOOMED OUT: the clip (20 s) is shorter than the view's 30 s
 * opening window, so `revealSelection` clamps to the fit-the-file scale and
 * the view offset is pinned at 0. That leaves one linear map, ms =
 * (x − gutter) · duration / contentWidth, which [msAt]/[xFor] mirror. The
 * gutter is the view's own 14 dp arrow lane. [scrolledWaveform] is the other
 * fixture — long enough to hide audio off both edges, which is what puts the
 * arrows on screen; its scroll position is read back through where a placing
 * tap lands, the only public window the view offers.
 *
 * The negative pins carry as much weight as the positive ones: a press on a
 * handle is a GRAB (the handle-drag path owns it), a press on an arrow is a
 * SCROLL and never a placement, a drag that wanders back to its origin is
 * still a drag, a vertical swipe over the strip is a sheet scroll, and a
 * touch forwarded from the panel's padding (the host shifts those into our
 * coordinates, so they land off the strip) must not move a trim the user set.
 */
@RunWith(RobolectricTestRunner::class)
class WaveformTapSelectionTest {

    private val ctx = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext(),
        R.style.Theme_PlayTranslate,
    )

    private val density = ctx.resources.displayMetrics.density
    private val w = 1_000
    private val h = 80
    private val durationMs = 20_000L

    /** The view's edge gutter (14 dp) — content maps inside it. */
    private val gutter = 14f * density
    private val contentW = w - 2 * gutter
    private val msPerPx = durationMs.toDouble() / contentW

    private fun xFor(ms: Long): Float = (gutter + ms / msPerPx).toFloat()
    private fun msAt(x: Float): Long = ((x - gutter) * msPerPx).toLong()

    private var fired = 0
    private var lastFired: Pair<Long, Long>? = null

    private fun waveform(
        selStartMs: Long = 7_000L,
        selEndMs: Long = 12_000L,
        clipMs: Long = durationMs,
    ): WaveformTrimView {
        val v = WaveformTrimView(ctx)
        v.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, w, h)
        v.setData(
            FloatArray((clipMs / 50).toInt()) { 0.4f },
            bucketMs = 50L,
            durationMs = clipMs,
            initialStartMs = selStartMs,
            initialEndMs = selEndMs,
        )
        v.onSelectionChanged = { s, e -> fired++; lastFired = s to e }
        return v
    }

    /**
     * A 3 min clip opened on the view's 30 s window: BOTH more-audio arrows
     * are on screen, so both gutters are live scroll controls. The window
     * lands at 47 500 ms (`revealSelection` centers the 5 s selection in it),
     * which is what keeps probe x = 100 clear of the selection and its
     * handles across every scrolled state these tests reach.
     */
    private fun scrolledWaveform(): WaveformTrimView =
        waveform(selStartMs = 60_000L, selEndMs = 65_000L, clipMs = 180_000L)

    /** ARROW_STEP_FRACTION (0.15) of that fixture's 30 s window. */
    private val arrowStepMs = 4_500L
    private val leftGutterX = 7f
    private val rightGutterX = w - 7f

    /** Where a placing tap at [x] lands, as the placed clip's center — the
     *  only public read of the view's scroll position, and the one that
     *  matters: it is the audio the user can now reach. */
    private fun placedCenter(v: WaveformTrimView, x: Float = 100f): Long {
        tap(v, x)
        return (v.selStartMs + v.selEndMs) / 2
    }

    private fun assertNear(message: String, expected: Long, actual: Long) {
        if (kotlin.math.abs(expected - actual) > 2) {
            assertEquals(message, expected, actual)
        }
    }

    /** Attach to a real window: the held arrow's glide runs on animation
     *  frames, and `postOnAnimation` on a detached view goes to the run
     *  queue instead — where it would never fire and the hold would silently
     *  look like a single press. */
    private fun attach(v: WaveformTrimView) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val container = FrameLayout(activity)
        activity.setContentView(container)
        container.addView(v, FrameLayout.LayoutParams(w, h))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun send(v: WaveformTrimView, action: Int, x: Float, y: Float, downTime: Long) {
        val ev = MotionEvent.obtain(downTime, downTime, action, x, y, 0)
        v.onTouchEvent(ev)
        ev.recycle()
    }

    private fun tap(v: WaveformTrimView, x: Float, y: Float = h / 2f) {
        send(v, MotionEvent.ACTION_DOWN, x, y, 1_000L)
        send(v, MotionEvent.ACTION_UP, x, y, 1_000L)
    }

    private fun assertSelection(expectedStart: Long, expectedEnd: Long, v: WaveformTrimView) {
        assertEquals("selection start", expectedStart, v.selStartMs)
        assertEquals("selection end", expectedEnd, v.selEndMs)
    }

    @Test
    fun tapOnPlainWaveform_centersTheDefaultLengthOnTheTap() {
        val v = waveform()
        val x = xFor(3_000L)

        tap(v, x)

        val tapped = msAt(x)
        assertSelection(tapped - 2_500, tapped + 2_500, v)
        assertEquals("placed clip keeps the default length", 5_000L, v.selEndMs - v.selStartMs)
        assertEquals("a tap is a deliberate placement — it reports", 1, fired)
        assertEquals(v.selStartMs to v.selEndMs, lastFired)
    }

    @Test
    fun tapSelectionMs_setsThePlacedLength() {
        val v = waveform().apply { tapSelectionMs = 3_000L }
        val x = xFor(3_000L)

        tap(v, x)

        assertEquals(3_000L, v.selEndMs - v.selStartMs)
        assertEquals(msAt(x), (v.selStartMs + v.selEndMs) / 2)
    }

    @Test
    fun tapOnVoiceLine_takesTheWholeLine() {
        val v = waveform()
        v.setSpeechRegions(listOf(2_000L to 3_400L))

        tap(v, xFor(2_700L))

        assertSelection(2_000L, 3_400L, v)
        assertEquals(1, fired)
    }

    @Test
    fun tapJustBesideAThinVoiceLine_stillTakesIt() {
        // Tolerance is 12 dp of SCREEN distance (~247 ms at this zoom), so a
        // 600 ms line — 29 px wide here, a few px on a full 3 min ring — stays
        // reachable by finger.
        val v = waveform()
        v.setSpeechRegions(listOf(4_000L to 4_600L))

        tap(v, xFor(3_800L))

        assertSelection(4_000L, 4_600L, v)
    }

    @Test
    fun tapWellClearOfEveryVoiceLine_getsThePlainWindow() {
        // 1 s off the line — ~48 px here, far outside the finger tolerance.
        val v = waveform()
        v.setSpeechRegions(listOf(4_000L to 4_600L))
        val x = xFor(3_000L)

        tap(v, x)

        val tapped = msAt(x)
        assertSelection(tapped - 2_500, tapped + 2_500, v)
    }

    @Test
    fun tapInsideTheSelection_changesNothing() {
        val v = waveform()

        tap(v, xFor(9_500L))

        assertSelection(7_000L, 12_000L, v)
        assertEquals("no report for an inert tap", 0, fired)
    }

    @Test
    fun tapOnAHandle_changesNothing_itIsAGrab() {
        val v = waveform()

        tap(v, xFor(7_000L))
        tap(v, xFor(12_000L))

        assertSelection(7_000L, 12_000L, v)
        assertEquals(0, fired)
    }

    @Test
    fun tapAtEitherEnd_shiftsTheWindowInsteadOfShrinkingIt() {
        val head = waveform(selStartMs = 12_000L, selEndMs = 17_000L)
        tap(head, xFor(300L))
        assertSelection(0L, 5_000L, head)

        fired = 0
        val tail = waveform()
        tap(tail, xFor(19_800L))
        assertSelection(15_000L, 20_000L, tail)
    }

    @Test
    fun subSlopJitter_doesNotPanTheAudioOutFromUnderTheTap() {
        // A finger that drifts a few px and settles back as it lifts. The
        // drift stays under the slop, so this is still a tap — and the window
        // must not have moved beneath it. (Asymmetric on purpose: the finger
        // never reports the return trip, so a window that panned with the
        // drift would stay panned and the placement would land early.)
        val base = placedCenter(scrolledWaveform())
        fired = 0

        val v = scrolledWaveform()
        send(v, MotionEvent.ACTION_DOWN, 100f, h / 2f, 1_000L)
        send(v, MotionEvent.ACTION_MOVE, 104f, h / 2f, 1_000L)
        send(v, MotionEvent.ACTION_UP, 100f, h / 2f, 1_000L)

        assertEquals("a sub-slop drift is still a tap", 1, fired)
        assertNear("jitter must not shift the placement", base, (v.selStartMs + v.selEndMs) / 2)
    }

    @Test
    fun dragThatReturnsToItsOrigin_isNotATap() {
        val v = waveform()
        val x = xFor(3_000L)
        val y = h / 2f

        send(v, MotionEvent.ACTION_DOWN, x, y, 1_000L)
        send(v, MotionEvent.ACTION_MOVE, x + 200f, y, 1_000L)
        send(v, MotionEvent.ACTION_MOVE, x, y, 1_000L)
        send(v, MotionEvent.ACTION_UP, x, y, 1_000L)

        assertSelection(7_000L, 12_000L, v)
        assertEquals(0, fired)
    }

    @Test
    fun embedded_verticalSwipeOverTheStrip_isNotATap() {
        // The sheet normally intercepts and we get a CANCEL — but when it has
        // nowhere to scroll the whole swipe lands here, and it is not a tap.
        val v = waveform().apply { embedded = true }
        val x = xFor(3_000L)

        send(v, MotionEvent.ACTION_DOWN, x, 8f, 1_000L)
        send(v, MotionEvent.ACTION_MOVE, x, 70f, 1_000L)
        send(v, MotionEvent.ACTION_UP, x, 70f, 1_000L)

        assertSelection(7_000L, 12_000L, v)
        assertEquals(0, fired)
    }

    // ── The edge gutters ─────────────────────────────────────────────────

    @Test
    fun gutterWithNoArrowShown_placesAtTheEdgeUnderTheFinger() {
        // This fixture fits the whole clip, so nothing is hidden and neither
        // arrow is drawn: the lane is plain margin, and a touch target that
        // ends at the last bar would put a dead rim inside the visible strip.
        val head = waveform()
        tap(head, leftGutterX)
        assertSelection(0L, 5_000L, head)

        val tail = waveform()
        tap(tail, rightGutterX)
        assertSelection(15_000L, 20_000L, tail)
    }

    @Test
    fun pressingAShownArrow_scrollsInsteadOfPlacing() {
        val v = scrolledWaveform()

        tap(v, leftGutterX)
        tap(v, rightGutterX)

        assertSelection(60_000L, 65_000L, v)
        assertEquals("an arrow is a scroll control, not a placement target", 0, fired)
    }

    @Test
    fun pressingTheLeftArrow_scrollsTowardTheEarlierAudio() {
        val base = placedCenter(scrolledWaveform())

        val v = scrolledWaveform()
        tap(v, leftGutterX)

        assertNear("one press = one step back", base - arrowStepMs, placedCenter(v))
    }

    @Test
    fun pressingTheRightArrow_scrollsTowardTheLaterAudio() {
        val base = placedCenter(scrolledWaveform())

        val v = scrolledWaveform()
        tap(v, rightGutterX)

        assertNear("one press = one step forward", base + arrowStepMs, placedCenter(v))
    }

    @Test
    fun holdingAnArrow_keepsScrollingPastTheFirstStep() {
        val pressed = scrolledWaveform()
        tap(pressed, leftGutterX)
        val afterPress = placedCenter(pressed)

        // Real frame pacing: unpaused, Robolectric runs every animation
        // callback the moment it is posted, so a self-rescheduling glide
        // would drain to the clamp in one idle and the rate would go
        // unmeasured (it also leaves a hang as the failure signature if the
        // glide ever stops self-terminating).
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val held = scrolledWaveform()
        attach(held)
        send(held, MotionEvent.ACTION_DOWN, leftGutterX, h / 2f, 1_000L)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        send(held, MotionEvent.ACTION_UP, leftGutterX, h / 2f, 1_000L)
        val afterHold = placedCenter(held)

        // One paced frame is AUTO_PAN_FRACTION (1%) of the 30 s window; a
        // floor rather than an equality so a Robolectric change in frame
        // accounting can't fail a working glide.
        assertTrue(
            "a held arrow must glide past its press step: press=$afterPress hold=$afterHold",
            afterPress - afterHold >= 300,
        )
    }

    @Test
    fun aShownArrowOwnsItsGutter_evenWithAHandleParkedInIt() {
        val v = scrolledWaveform()
        // Pan until the left handle sits at x ≈ 7 — inside the gutter, with
        // its 24 dp grab zone right over the arrow. The first slop-beating
        // move only ARMS the pan (the window holds still until the gesture
        // is proven), so the travel that moves it is the one after that.
        send(v, MotionEvent.ACTION_DOWN, 500f, h / 2f, 1_000L)
        send(v, MotionEvent.ACTION_MOVE, 480f, h / 2f, 1_000L)
        send(v, MotionEvent.ACTION_MOVE, 68f, h / 2f, 1_000L)
        send(v, MotionEvent.ACTION_UP, 68f, h / 2f, 1_000L)
        fired = 0

        // Press there and drag deep into the strip: a handle grab would haul
        // the selection start along with the finger.
        send(v, MotionEvent.ACTION_DOWN, leftGutterX, h / 2f, 2_000L)
        send(v, MotionEvent.ACTION_MOVE, 200f, h / 2f, 2_000L)
        send(v, MotionEvent.ACTION_UP, 200f, h / 2f, 2_000L)

        assertSelection(60_000L, 65_000L, v)
        assertEquals(0, fired)
    }

    @Test
    fun touchForwardedFromThePanelPadding_isNotATap() {
        val v = waveform()

        // Below the strip (the zoom-hint row) and beside it (the 24 dp side
        // padding) — the host forwards both, coordinate-shifted.
        tap(v, xFor(3_000L), y = h + 30f)
        tap(v, -12f, y = h / 2f)

        assertSelection(7_000L, 12_000L, v)
        assertEquals(0, fired)
    }

    @Test
    fun touchForwardedFromThePanelPadding_doesNotWorkTheArrowsEither() {
        // Off the strip on the arrow's own side: the card margin beside the
        // gutter must not become an extra scroll rail.
        val base = placedCenter(scrolledWaveform())

        val v = scrolledWaveform()
        tap(v, -12f)
        tap(v, w + 12f)

        assertEquals("forwarded padding must not scroll", base, placedCenter(v))
    }
}
