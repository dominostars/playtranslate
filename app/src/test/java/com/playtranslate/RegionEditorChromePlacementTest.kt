package com.playtranslate

import android.app.Activity
import android.content.Context
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
import com.playtranslate.ui.RegionDragView
import com.playtranslate.ui.RegionEditorChrome
import org.junit.After
import org.junit.Assert.assertEquals
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
 * the box as it now stands. The floating-icon editor's windows are read from
 * the window manager's own copy of their params, which only addView and
 * updateViewLayout write, so a params change that never reached the window
 * fails here. The camera editor's chrome sits inside its controls host's inset
 * padding, which counts toward what is under it.
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

    @After fun tearDown() {
        regions?.hideAll()
        service?.destroy()
        // Let the churn gate finish every removal inside this test.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // ── Floating-icon editor ─────────────────────────────────────────────

    private fun openOverlayEditor(existing: RegionEntry?): RegionOverlayController {
        val built = Robolectric.buildService(CaptureService::class.java).create()
        service = built
        existing?.let { built.get().configureOverride(Display.DEFAULT_DISPLAY, it) }
        val controller = RegionOverlayController(
            ctx, OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY),
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

    private fun RegionOverlayController.field(name: String): View =
        RegionOverlayController::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(this) as View

    /** The vertical gravity the window manager has for [view]'s window. */
    private fun windowEdge(view: View): Int {
        val root = View::class.java.getDeclaredMethod("getViewRootImpl").invoke(view)
        val attrs = Class.forName("android.view.ViewRootImpl").getDeclaredField("mWindowAttributes")
            .apply { isAccessible = true }.get(root) as WindowManager.LayoutParams
        return attrs.gravity and Gravity.VERTICAL_GRAVITY_MASK
    }

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
