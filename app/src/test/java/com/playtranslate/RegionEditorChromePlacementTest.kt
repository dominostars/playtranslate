package com.playtranslate

import android.app.Activity
import android.content.Context
import android.graphics.Point
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.camera.CameraRegionUi
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.Sides
import com.playtranslate.testutil.testEnvironment
import com.playtranslate.ui.RegionDragView
import com.playtranslate.ui.RegionEditorChrome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import java.time.Duration

/**
 * Both region editors place their pill and bar by [RegionEditorChrome.edgeFor]
 * from the region they open on (none: top), and again at each drag's end from
 * the box as it now stands. The floating-icon editor's two windows are laid
 * out by the host from the display as it is ([RegionEditorChrome.place]: inside
 * what covers the edge, centred in the clear width), at their add and at every
 * refresh of the display: a bar the game shows, a rotation, a drag's end. They
 * are read from the window manager's own copy of their params, which only
 * addView and updateViewLayout write, so a params change that never reached
 * the window fails here. The camera editor's chrome sits inside its controls
 * host's inset padding, which counts toward what is under it.
 */
@RunWith(RobolectricTestRunner::class)
class RegionEditorChromePlacementTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun settle() = shadowOf(Looper.getMainLooper()).idle()
    private var service: ServiceController<CaptureService>? = null
    private var regions: RegionOverlayController? = null
    private var host: OverlayHost? = null

    /** The display the host reads; a case shows a bar or turns it. */
    private var screen = Point(720, 1604)
    private var shown = Sides.NONE
    private var cutout = Sides.NONE

    @After fun tearDown() {
        regions?.hideAll()
        service?.destroy()
        // Let the churn gate finish every removal inside this test.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // ── Floating-icon editor ─────────────────────────────────────────────

    private fun openOverlayEditor(
        existing: RegionEntry?,
        windowType: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        android11: Boolean = false,
    ): RegionOverlayController {
        val built = Robolectric.buildService(CaptureService::class.java).create()
        service = built
        existing?.let { built.get().configureOverride(Display.DEFAULT_DISPLAY, it) }
        val overlayHost = OverlayHost(ctx, windowType).also {
            it.environmentReader = { id ->
                if (id != Display.DEFAULT_DISPLAY) null
                else testEnvironment(
                    screen.x, screen.y,
                    belowBars = windowType == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    hearsBars = windowType == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY || android11,
                    shownBars = shown, cutout = cutout,
                )
            }
        }
        host = overlayHost
        val controller = RegionOverlayController(
            ctx, overlayHost,
            anyIconInDragMode = { false },
            bringIconToFront = {},
            hideTranslationOverlay = {},
            onRegionSelected = { _, _ -> },
            onRegionCleared = {},
        )
        regions = controller
        val display = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        controller.showRegionEditor(display)
        settle()
        return controller
    }

    /** The display changed under the editor (a bar shown, a rotation): the
     *  host lays it out again. */
    private fun displayChanged() {
        host!!.onDisplayChanged(Display.DEFAULT_DISPLAY)
        settle()
    }

    private fun RegionOverlayController.field(name: String): View =
        RegionOverlayController::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(this) as View

    /** The params the window manager has for [view]'s window. */
    private fun windowAttrs(view: View): WindowManager.LayoutParams {
        val root = View::class.java.getDeclaredMethod("getViewRootImpl").invoke(view)
        return Class.forName("android.view.ViewRootImpl").getDeclaredField("mWindowAttributes")
            .apply { isAccessible = true }.get(root) as WindowManager.LayoutParams
    }

    /** The vertical gravity the window manager has for [view]'s window. */
    private fun windowEdge(view: View): Int = windowAttrs(view).gravity and Gravity.VERTICAL_GRAVITY_MASK

    /** The offset from its edge the window manager has for [view]'s window. */
    private fun windowY(view: View): Int = windowAttrs(view).y

    private fun RegionOverlayController.assertChromeOn(edge: Int) {
        assertEquals("bar", edge, windowEdge(field("regionEditorBar")))
        assertEquals("pill", edge, windowEdge(field("regionEditorLabel")))
    }

    private fun RegionOverlayController.drag(top: Float, bottom: Float) {
        val drag = field("dragView") as RegionDragView
        drag.onDragStart!!.invoke()
        assertEquals(View.INVISIBLE, field("regionEditorBar").visibility)
        drag.setRegion(top, bottom, 0.1f, 0.9f)
        drag.onDragEnd!!.invoke()
        settle()
        assertEquals(View.VISIBLE, field("regionEditorBar").visibility)
        assertEquals(View.VISIBLE, field("regionEditorLabel").visibility)
    }

    @Test fun `the floating-icon editor opens on the region's edge and re-decides at each drag's end`() {
        val editor = openOverlayEditor(RegionEntry("", 0f, 0.4f, 0.1f, 0.9f))
        editor.assertChromeOn(Gravity.BOTTOM)

        editor.drag(top = 0.65f, bottom = 1f)
        editor.assertChromeOn(Gravity.TOP)

        editor.drag(top = 0f, bottom = 1f)
        editor.assertChromeOn(Gravity.TOP)

        editor.drag(top = 0f, bottom = 0.4f)
        editor.assertChromeOn(Gravity.BOTTOM)
    }

    @Test fun `the floating-icon editor opens on top with no region, then follows the box`() {
        val editor = openOverlayEditor(existing = null)
        editor.assertChromeOn(Gravity.TOP)

        editor.drag(top = 0f, bottom = 0.4f)
        editor.assertChromeOn(Gravity.BOTTOM)
    }

    // ── What covers the chrome's edge ───────────────────────────────────

    @Test fun `the bottom chrome moves up by a bar that appears while the editor is open, and back`() {
        val editor = openOverlayEditor(RegionEntry("", 0f, 0.4f, 0.1f, 0.9f))
        editor.assertChromeOn(Gravity.BOTTOM)
        val barView = editor.field("regionEditorBar")
        val pill = editor.field("regionEditorLabel")
        val barY = windowY(barView)
        val pillY = windowY(pill)

        shown = Sides(0, 0, 0, 84)
        displayChanged()
        assertEquals(barY + 84, windowY(barView))
        assertEquals(pillY + 84, windowY(pill))
        editor.assertChromeOn(Gravity.BOTTOM)

        shown = Sides.NONE
        displayChanged()
        assertEquals(barY, windowY(barView))
        assertEquals(pillY, windowY(pill))
    }

    @Test fun `another display's change leaves the chrome alone`() {
        val editor = openOverlayEditor(RegionEntry("", 0f, 0.4f, 0.1f, 0.9f))
        val barView = editor.field("regionEditorBar")
        val barY = windowY(barView)

        shown = Sides(0, 0, 0, 84)
        host!!.onDisplayChanged(Display.DEFAULT_DISPLAY + 7)
        settle()
        assertEquals(barY, windowY(barView))
    }

    @Test fun `the top chrome moves below a shown status bar and ignores a bottom bar`() {
        val editor = openOverlayEditor(existing = null)
        editor.assertChromeOn(Gravity.TOP)
        val barView = editor.field("regionEditorBar")
        val barY = windowY(barView)

        shown = Sides(0, 0, 0, 84)
        displayChanged()
        assertEquals("a bottom bar is not the top edge's", barY, windowY(barView))

        shown = Sides(0, 60, 0, 84)
        displayChanged()
        assertEquals(barY + 60, windowY(barView))
        editor.assertChromeOn(Gravity.TOP)
    }

    @Test fun `above the bars the chrome clears the cutout only`() {
        val editor = openOverlayEditor(existing = null, windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        val barView = editor.field("regionEditorBar")
        val barY = windowY(barView)

        shown = Sides(0, 60, 0, 84)
        displayChanged()
        assertEquals("the bars lie under an accessibility overlay", barY, windowY(barView))

        cutout = Sides(0, 90, 0, 0)
        displayChanged()
        assertEquals(barY + 90, windowY(barView))
    }

    @Test fun `on Android 11 the accessibility backend's bottom chrome stays above a shown bar, as in v3,3,1`() {
        // Android 11 dispatches the bars to every window, so v3.3.1's fit
        // kept this chrome off the navigation buttons there.
        val editor = openOverlayEditor(
            RegionEntry("", 0f, 0.4f, 0.1f, 0.9f),
            windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android11 = true,
        )
        editor.assertChromeOn(Gravity.BOTTOM)
        val barView = editor.field("regionEditorBar")
        val barY = windowY(barView)

        shown = Sides(0, 0, 0, 84)
        displayChanged()
        assertEquals(barY + 84, windowY(barView))
    }

    @Test fun `the chrome is centred in the width a side bar leaves`() {
        val editor = openOverlayEditor(existing = null)
        val barView = editor.field("regionEditorBar")
        assertEquals(0, windowAttrs(barView).x)

        shown = Sides(0, 0, 84, 0)
        displayChanged()
        assertEquals("half the bar toward the clear side", -42, windowAttrs(barView).x)
        assertEquals(-42, windowAttrs(editor.field("regionEditorLabel")).x)
    }

    @Test fun `the edge is decided with the covered inset, in fractions of the whole display`() {
        // A box whose top lies below the bare top stack but above it once a
        // 100 px status bar is counted.
        val editor = openOverlayEditor(existing = null)
        val top = (editor.stackPx() + 50f) / screen.y
        editor.drag(top = top, bottom = top + 0.1f)
        editor.assertChromeOn(Gravity.TOP)

        shown = Sides(0, 100, 0, 0)
        displayChanged()
        editor.assertChromeOn(Gravity.BOTTOM)
    }

    // ── A rotation with the editor open ─────────────────────────────────

    /** The chrome stack's height, as the editor computes it from the chrome
     *  it built. */
    private fun RegionOverlayController.stackPx(): Int {
        val pill = field("regionEditorLabel")
        val bar = field("regionEditorBar")
        val dp = ctx.resources.displayMetrics.density
        return RegionEditorChrome.stackDepthPx(pill.measuredHeight, bar.measuredHeight, dp)
    }

    @Test fun `a rotation re-decides the chrome's edge against the new height and re-fits the label`() {
        // A region clear of the top stack on the tall screen and under it on
        // a short one: between the two shares.
        val editor = openOverlayEditor(RegionEntry("", 0.36f, 0.5f, 0.1f, 0.9f))
        val stack = editor.stackPx()
        assertTrue("the fixture sits above the tall screen's top stack", stack.toFloat() / screen.y < 0.36f)
        editor.assertChromeOn(Gravity.TOP)

        // A screen short enough that the top stack reaches past the region's
        // top (0.36) and the bottom stack stays clear of its bottom (0.5):
        // the stack as 43 percent of the height.
        screen = Point(1604, (stack / 0.43f).toInt())
        displayChanged()
        editor.assertChromeOn(Gravity.BOTTOM)

        // Narrower than the instruction's text: the label is measured again
        // against the screen it is on, not the one it opened on.
        screen = Point(200, 1604)
        displayChanged()
        val pill = editor.field("regionEditorLabel")
        val dp = ctx.resources.displayMetrics.density
        assertTrue(
            "the label fits the screen it is on",
            windowAttrs(pill).width <= 200 - (32 * dp).toInt(),
        )
    }

    // ── Camera / Import file editor ──────────────────────────────────────

    @Test fun `the camera editor counts the inset padding and re-decides at each drag's end`() {
        val activity = Robolectric.buildActivity(Host::class.java).setup().get()
        val root = FrameLayout(activity)
        val fullBleed = FrameLayout(activity)
        val controls = FrameLayout(activity).apply { setPadding(0, 100, 0, 60) }
        root.addView(fullBleed, FrameLayout.LayoutParams(-1, -1))
        root.addView(controls, FrameLayout.LayoutParams(-1, -1))
        fun layOut() {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 1000, 2000)
        }
        layOut()
        val ui = CameraRegionUi(activity, fullBleed, controls)

        // The box's top at 200px: clear of the stack alone (well under 200px
        // at this density) but under it once the 100px top inset is added.
        ui.showEditor(RectF(0.1f, 0.1f, 0.9f, 0.5f), onCancel = {}, onClear = {}, onConfirm = {})
        val bar = controls.getChildAt(0)
        val pill = controls.getChildAt(1)
        val dp = activity.resources.displayMetrics.density
        val barOffset = RegionEditorChrome.barOffsetPx(pill.measuredHeight, dp)
        val pillOffset = RegionEditorChrome.pillOffsetPx(dp)
        fun assertOn(edge: Int) {
            for ((view, offset) in listOf(bar to barOffset, pill to pillOffset)) {
                val lp = view.layoutParams as FrameLayout.LayoutParams
                assertEquals(edge, lp.gravity and Gravity.VERTICAL_GRAVITY_MASK)
                assertEquals(if (edge == Gravity.TOP) offset else 0, lp.topMargin)
                assertEquals(if (edge == Gravity.BOTTOM) offset else 0, lp.bottomMargin)
            }
        }
        assertOn(Gravity.BOTTOM)

        val drag = fullBleed.getChildAt(fullBleed.childCount - 1) as RegionDragView
        fun dragTo(top: Float, bottom: Float) {
            drag.onDragStart!!.invoke()
            assertEquals(View.INVISIBLE, bar.visibility)
            drag.setRegion(top, bottom, 0.1f, 0.9f)
            drag.onDragEnd!!.invoke()
            assertEquals(View.VISIBLE, bar.visibility)
            assertEquals(View.VISIBLE, pill.visibility)
            layOut()
        }
        dragTo(top = 0.7f, bottom = 1f)
        assertOn(Gravity.TOP)
        dragTo(top = 0f, bottom = 0.4f)
        assertOn(Gravity.BOTTOM)
    }
}
