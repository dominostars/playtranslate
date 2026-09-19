package com.playtranslate.ui

import android.content.Context
import android.graphics.Rect
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The capture sheet re-places its parked hint from an OnLayoutChangeListener
 * on the one-shot [TranslationOverlayView] ([CaptureResultOverlay]'s
 * placeSliverHint), on the contract that the listener runs after EVERY
 * rebuild of the chips — not only when the full-screen view's own bounds
 * change. That is the framework's rule (View.layout runs onLayout and the
 * listeners when the bounds changed OR a measure was forced, and addView
 * forces one), pinned here against the real rebuild paths: a box that moves
 * and a skeleton promoted to its translation both rebuild and notify with
 * the view's bounds untouched, while the fuzzy fast path (jitter within
 * tolerance, no rebuild) neither moves a child nor notifies — so the
 * placement is stale exactly never.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationOverlayLayoutListenerTest {

    private val w = 1080
    private val h = 1920

    private class Harness(ctx: Context) {
        val host = FrameLayout(ctx)
        val overlay = TranslationOverlayView(ctx, oneShot = true)
        var fired = 0
        /** Each firing's chip x offsets — the laid-out `left` of every child,
         *  which is what moves a chip (the one-shot path places chips by
         *  layout margins). Not [TranslationOverlayView.getChildScreenRects]:
         *  Robolectric's legacy getLocationOnScreen drops the view offsets,
         *  so every footprint reads at the origin here, though the real
         *  framework's adds them (the pinhole gate relies on it on device). */
        val childLefts = mutableListOf<List<Int>>()

        init {
            host.addView(
                overlay,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        /** One traversal, as ViewRootImpl would run it: exact measure, then
         *  layout at the same bounds every time. */
        fun traverse(w: Int, h: Int) {
            host.measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
            )
            host.layout(0, 0, w, h)
        }
    }

    private fun harness(): Harness {
        val ctx: Context = RuntimeEnvironment.getApplication()
        ctx.setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        return Harness(ctx).also {
            it.traverse(w, h)
            it.overlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                it.fired++
                it.childLefts += (0 until it.overlay.childCount).map { i -> it.overlay.getChildAt(i).left }
            }
        }
    }

    private fun box(text: String, bounds: Rect) = TextBox(translatedText = text, bounds = bounds, sourceText = "src")

    @Test fun `a box that moves rebuilds and notifies with the view's bounds unchanged`() {
        val hs = harness()
        hs.overlay.setBoxes(listOf(box("hello", Rect(100, 1800, 500, 1900))), 0, 0, w, h)
        hs.traverse(w, h)
        hs.overlay.setBoxes(listOf(box("hello", Rect(600, 1800, 1000, 1900))), 0, 0, w, h)
        hs.traverse(w, h)
        assertEquals(2, hs.fired)
        assertEquals(Rect(0, 0, w, h), Rect(hs.overlay.left, hs.overlay.top, hs.overlay.right, hs.overlay.bottom))
        assertEquals(1, hs.childLefts[0].size)
        assertEquals(1, hs.childLefts[1].size)
        // The chip really moved between the two notifications (500px right,
        // the same stroke padding on both).
        assertEquals(500, hs.childLefts[1][0] - hs.childLefts[0][0])
    }

    @Test fun `a skeleton promoted to its translation rebuilds and notifies`() {
        val hs = harness()
        val bounds = Rect(100, 1800, 500, 1900)
        hs.overlay.setBoxes(listOf(box("", bounds)), 0, 0, w, h)
        hs.traverse(w, h)
        hs.overlay.setBoxes(listOf(box("hello", bounds)), 0, 0, w, h)
        hs.traverse(w, h)
        assertEquals(2, hs.fired)
        assertEquals(1, hs.childLefts[1].size)
    }

    @Test fun `jitter within the fuzzy tolerance neither moves a child nor notifies`() {
        val hs = harness()
        hs.overlay.setBoxes(listOf(box("hello", Rect(100, 1800, 500, 1900))), 0, 0, w, h)
        hs.traverse(w, h)
        val before = hs.overlay.getChildAt(0).left
        hs.overlay.setBoxes(listOf(box("hello", Rect(104, 1803, 505, 1902))), 0, 0, w, h)
        hs.traverse(w, h)
        assertEquals(1, hs.fired)
        assertEquals(before, hs.overlay.getChildAt(0).left)
    }
}
