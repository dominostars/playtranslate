package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.CaptureSession
import com.playtranslate.CaptureState
import com.playtranslate.OneShotOverlayData
import com.playtranslate.OverlayMode
import com.playtranslate.R
import com.playtranslate.model.TextSegment
import com.playtranslate.overlay.OverlayHost
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The capture sheet hosted the way the camera and import tools host it: in
 * an activity, the host painting the boxes through its presenter, with its
 * own presentation prefs and its own mode control (the gear menu's Overlays
 * row). The sheet's pill never comes up there, and the host's pick goes
 * through the sheet as the pill's would.
 */
@RunWith(RobolectricTestRunner::class)
class ToolHostedSheetModeTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    /** The host's memory of its mode and posture, as the tools keep theirs. */
    private class HostPrefs(
        var mode: OverlayMode,
        var posture: Float = CaptureResultGeometry.NO_POSTURE,
    ) : CaptureResultOverlay.PresentationPrefs {
        override var overlayMode: OverlayMode
            get() = mode
            set(value) { mode = value }
        override val offeredOverlayModes: List<OverlayMode> =
            listOf(OverlayMode.TRANSLATION, OverlayMode.FURIGANA, OverlayMode.NONE)
        override var startPosture: Float
            get() = posture
            set(value) { posture = value }
    }

    /** The host's painter: what the sheet asked of it, in order. */
    private class Presenter : CaptureResultOverlay.BoxPresenter {
        val calls = mutableListOf<String>()
        override fun show(data: OneShotOverlayData): Boolean { calls += "show"; return true }
        override fun update(data: OneShotOverlayData) { calls += "update" }
        override fun hide() { calls += "hide" }
    }

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val host = OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    private var sheet: CaptureResultOverlay? = null

    private fun sp() = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    @Before fun clearPrefs() { sp().edit().clear().commit() }

    @After fun tearDown() {
        sheet?.dismiss()
        host.removeAll()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        sp().edit().clear().commit()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(o: Any, name: String): T =
        o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o) as T

    private fun pillOf(o: CaptureResultOverlay): OverlayModePill = field(o, "modePill")
    private fun parked(o: CaptureResultOverlay): Boolean = field(o, "sliverMode")

    private fun open(
        hostPrefs: HostPrefs,
        presenter: Presenter,
        hostOwnsModeControl: Boolean = true,
    ): CaptureResultOverlay {
        val activity = Robolectric.buildActivity(Host::class.java).setup().get()
        val parent = FrameLayout(activity)
        activity.setContentView(parent)
        val o = CaptureResultOverlay(
            activity, activity.windowManager, Display.DEFAULT_DISPLAY, host,
            sheetHost = ActivitySheetHost(parent),
        )
        sheet = o
        o.boxPresenter = presenter
        o.presentationPrefs = hostPrefs
        o.hostOwnsModeControl = hostOwnsModeControl
        o.dismissOnGesture = false
        o.show(1280, 720)
        idle()
        return o
    }

    private fun session(): Pair<MutableStateFlow<CaptureState>, CaptureSession> {
        val flow = MutableStateFlow<CaptureState>(CaptureState.InProgress("Reading"))
        return flow to CaptureSession(flow, Job())
    }

    /** The tools' data: no boxes of the sheet's own, the presenter paints
     *  from the tool's session. */
    private fun translating() = CaptureState.Translating(
        "hello", listOf(TextSegment("hello")), null,
        OneShotOverlayData(emptyList(), 0, 0, 1280, 720, 1280, 720, emptyList()),
    )

    @Test fun `the sheet's pill never comes up where the host has the mode control`() {
        val o = open(HostPrefs(OverlayMode.TRANSLATION), Presenter())
        val (flow, s) = session()
        o.observe(s)
        idle()
        assertEquals("while the frame is read", View.GONE, pillOf(o).visibility)
        flow.value = translating()
        idle()
        assertEquals("with the text read and boxes up", View.GONE, pillOf(o).visibility)
        o.selectOverlayMode(OverlayMode.FURIGANA)
        idle()
        assertEquals("after a pick", View.GONE, pillOf(o).visibility)
    }

    @Test fun `the same sheet without a host control has its pill, so the test above sees the switch`() {
        val o = open(HostPrefs(OverlayMode.TRANSLATION), Presenter(), hostOwnsModeControl = false)
        val (_, s) = session()
        o.observe(s)
        idle()
        assertEquals(View.VISIBLE, pillOf(o).visibility)
    }

    @Test fun `the host's pick is remembered and painted through its presenter, as the pill's would be`() {
        val hostPrefs = HostPrefs(OverlayMode.TRANSLATION)
        val presenter = Presenter()
        val o = open(hostPrefs, presenter)
        val (flow, s) = session()
        o.observe(s)
        flow.value = translating()
        idle()
        assertEquals("the read's boxes go up once", listOf("show"), presenter.calls)

        o.selectOverlayMode(OverlayMode.FURIGANA)
        assertEquals(OverlayMode.FURIGANA, hostPrefs.mode)
        assertEquals("boxes that are up are repainted in place", listOf("show", "update"), presenter.calls)

        o.selectOverlayMode(OverlayMode.NONE)
        assertEquals(OverlayMode.NONE, hostPrefs.mode)
        assertEquals(listOf("show", "update", "hide"), presenter.calls)

        o.selectOverlayMode(OverlayMode.TRANSLATION)
        assertEquals(OverlayMode.TRANSLATION, hostPrefs.mode)
        assertEquals("a mode after None puts them back", listOf("show", "update", "hide", "show"), presenter.calls)
    }

    @Test fun `a pick while only a status is up is remembered and paints nothing`() {
        val hostPrefs = HostPrefs(OverlayMode.TRANSLATION)
        val presenter = Presenter()
        val o = open(hostPrefs, presenter)
        val (_, s) = session()
        o.observe(s)
        idle()
        o.selectOverlayMode(OverlayMode.FURIGANA)
        assertEquals(OverlayMode.FURIGANA, hostPrefs.mode)
        assertFalse("nothing is read yet, so nothing is painted", "show" in presenter.calls || "update" in presenter.calls)
    }

    @Test fun `None picked on a parked sheet brings the panel up, since nothing else is left on screen`() {
        val hostPrefs = HostPrefs(OverlayMode.TRANSLATION, posture = CaptureResultGeometry.COLLAPSED_POSTURE)
        val presenter = Presenter()
        val o = open(hostPrefs, presenter)
        val (flow, s) = session()
        o.observe(s)
        flow.value = translating()
        idle()
        assertTrue("precondition: parked on its boxes", parked(o))
        o.selectOverlayMode(OverlayMode.NONE)
        idle()
        assertFalse(parked(o))
    }
}
