package com.playtranslate.ui

import android.app.Activity
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.playtranslate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * [RegionEditor] is the one view-tree region editor: the camera / Import
 * crop and the Anki screenshot crop host it. What the hosts vary is pinned
 * here: the pill's text, whether the bar has a 🗑, and the frame the box
 * lives in (a fitted image inside the host), whose fractions are what the
 * editor reports and whose position in the host is what decides the
 * chrome's edge. The camera editor's own placement case lives in
 * RegionEditorChromePlacementTest.
 */
@RunWith(RobolectricTestRunner::class)
class RegionEditorTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private class Fixture {
        val activity: Activity = Robolectric.buildActivity(Host::class.java).setup().get()
        val root = FrameLayout(activity).also { layOut(it) }
        val editor = RegionEditor(activity, root)

        /** Children after the drag view: the bar, then the pill. */
        val bar: ViewGroup get() = root.getChildAt(1) as ViewGroup
        val pill: TextView get() = root.getChildAt(2) as TextView
        val drag: RegionDragView get() = root.getChildAt(0) as RegionDragView

        fun layOut(v: View = root) {
            v.measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
            )
            v.layout(0, 0, 1000, 2000)
        }

        fun edge(): Int = (bar.layoutParams as FrameLayout.LayoutParams).gravity and Gravity.VERTICAL_GRAVITY_MASK
    }

    @Test fun `without a clear action the bar has two buttons, with one three`() {
        val two = Fixture()
        two.editor.show(init = null, instruction = R.string.region_overlay_drag_instruction,
            onCancel = {}, onClear = null, onConfirm = {})
        assertEquals(2, two.bar.childCount)
        val three = Fixture()
        three.editor.show(init = null, instruction = R.string.region_overlay_drag_instruction,
            onCancel = {}, onClear = {}, onConfirm = {})
        assertEquals(3, three.bar.childCount)
    }

    @Test fun `the pill shows the instruction it was given`() {
        val f = Fixture()
        f.editor.show(init = null, instruction = R.string.btn_clear,
            onCancel = {}, onClear = null, onConfirm = {})
        assertEquals(f.activity.getString(R.string.btn_clear), f.pill.text.toString())
    }

    @Test fun `a frame confines the box and the confirm reports fractions of it`() {
        val f = Fixture()
        var confirmed: RectF? = null
        // A 2400 x 1080 image fitted into the 1000 x 2000 host: 1000 x 450 at y 775.
        f.editor.show(
            init = RectF(0.1f, 0.2f, 0.9f, 0.8f), bounds = RectF(0f, 775f, 1000f, 1225f),
            instruction = R.string.region_overlay_drag_instruction,
            onCancel = {}, onClear = null, onConfirm = { confirmed = it },
        )
        val box = RectF().also(f.drag::boxInView)
        assertEquals(100f, box.left, 1e-3f)
        assertEquals(775f + 0.2f * 450f, box.top, 1e-3f)
        assertEquals(900f, box.right, 1e-3f)
        assertEquals(775f + 0.8f * 450f, box.bottom, 1e-3f)
        f.bar.getChildAt(1).performClick()
        assertEquals(RectF(0.1f, 0.2f, 0.9f, 0.8f), confirmed)
    }

    @Test fun `the chrome's edge is decided from the box's place in the host, not in its frame`() {
        // A box at 0.1 of a short frame at the host's top: under the top stack.
        val low = Fixture()
        low.editor.show(
            init = RectF(0.1f, 0.1f, 0.9f, 0.8f), bounds = RectF(0f, 0f, 1000f, 100f),
            instruction = R.string.region_overlay_drag_instruction,
            onCancel = {}, onClear = null, onConfirm = {},
        )
        assertEquals(Gravity.BOTTOM, low.edge())
        // The same fractions in a frame mid-host: clear of it.
        val mid = Fixture()
        mid.editor.show(
            init = RectF(0.1f, 0.1f, 0.9f, 0.8f), bounds = RectF(0f, 775f, 1000f, 1225f),
            instruction = R.string.region_overlay_drag_instruction,
            onCancel = {}, onClear = null, onConfirm = {},
        )
        assertEquals(Gravity.TOP, mid.edge())
    }

    @Test fun `a new frame moves the box, keeps its fractions and re-places the chrome`() {
        val f = Fixture()
        f.editor.show(
            init = RectF(0.1f, 0.1f, 0.9f, 0.8f), bounds = RectF(0f, 775f, 1000f, 1225f),
            instruction = R.string.region_overlay_drag_instruction,
            onCancel = {}, onClear = null, onConfirm = {},
        )
        assertEquals(Gravity.TOP, f.edge())
        f.editor.setContentBounds(RectF(0f, 0f, 1000f, 100f))
        assertEquals(0.1f, f.drag.topFraction, 0f)
        val box = RectF().also(f.drag::boxInView)
        assertEquals(10f, box.top, 1e-3f)
        assertEquals(Gravity.BOTTOM, f.edge())
    }

    @Test fun `hide removes every child and a new frame is then a no-op`() {
        val f = Fixture()
        f.editor.show(init = null, instruction = R.string.region_overlay_drag_instruction,
            onCancel = {}, onClear = {}, onConfirm = {})
        assertEquals(3, f.root.childCount)
        f.editor.hide()
        assertEquals(0, f.root.childCount)
        assertEquals(false, f.editor.isShowing)
        f.editor.setContentBounds(RectF(0f, 0f, 10f, 10f))
        assertNull(f.root.getChildAt(0))
    }
}
