package com.playtranslate.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * Pins the [EdgeIndicator] state table the sheet relies on: the rail's
 * sweep (and with it the line's lower resting strength) runs only while the
 * rail is shown AND a pass is in flight AND the system animates at all; the
 * ring's bloom breathes when shown and holds still with animations off; a
 * pulse plays when shown, waits for the next show otherwise, and is dropped
 * with animations off; the holder fans [EdgeIndicator.working] and
 * [EdgeIndicator.isVisible] out to the right views; and the ring's corner
 * radii come from the window's own insets, per corner, in ring order.
 *
 * "Shown" is driven through [View.onVisibilityAggregated], the platform's
 * own hook for exactly this (attach, detach, visibility and window-visibility
 * changes all funnel into it), because Robolectric's legacy window never
 * attaches a content view. Structural rather than pixel-level on purpose:
 * Robolectric's native graphics can't construct a View in a sandbox created
 * after a legacy one in the same JVM (RenderNode natives fail to bind), which
 * any full-suite run of this project produces, so a rasterizing test here
 * would be order-flaky.
 */
@RunWith(RobolectricTestRunner::class)
class EdgeIndicatorTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private fun indicator() = EdgeIndicator(ctx, Color.RED, sheetCornerRadiusPx = 21f)

    private fun setAnimatorScale(scale: Float) {
        ReflectionHelpers.callStaticMethod<Unit>(
            ValueAnimator::class.java, "setDurationScale",
            ClassParameter.from(Float::class.javaPrimitiveType, scale),
        )
    }

    @Test
    fun `rail sweeps only while shown and working`() {
        val rail = indicator().rail
        // Working but not yet on screen: nothing runs.
        rail.working = true
        assertFalse(rail.isSweeping)
        rail.onVisibilityAggregated(true)
        assertTrue(rail.isSweeping)
        // The pass ends: the sweep stops. A new pass restarts it.
        rail.working = false
        assertFalse(rail.isSweeping)
        rail.working = true
        assertTrue(rail.isSweeping)
        // Hidden (the setting turned off mid-episode, or the sheet lingering
        // as an invisible key sink): stops; shown again: resumes.
        rail.onVisibilityAggregated(false)
        assertFalse(rail.isSweeping)
        rail.onVisibilityAggregated(true)
        assertTrue(rail.isSweeping)
    }

    @Test
    fun `ring breathes when shown`() {
        val ring = indicator().ring
        assertEquals(1f, ring.bloomAlpha, 0.001f)
        ring.onVisibilityAggregated(true)
        // The breathe starts at its low point; anything but a still 1.0 or
        // the animations-off hold proves the animator took the bloom over.
        assertEquals(0.58f, ring.bloomAlpha, 0.001f)
        assertFalse(ring.isPulsing)
    }

    @Test
    fun `pulse plays when shown and waits for the next show otherwise`() {
        val ring = indicator().ring
        // Asked before the window is on screen (the sheet's show): deferred.
        ring.pulse()
        assertFalse(ring.isPulsing)
        ring.onVisibilityAggregated(true)
        assertTrue(ring.isPulsing)
        // Hidden mid-flash: the flash is dropped, not resumed later.
        ring.onVisibilityAggregated(false)
        assertFalse(ring.isPulsing)
        ring.onVisibilityAggregated(true)
        assertFalse(ring.isPulsing)
        // Asked while shown: flashes at once.
        ring.pulse()
        assertTrue(ring.isPulsing)
    }

    @Test
    fun `animations off holds the bloom still, never sweeps, drops the pulse`() {
        setAnimatorScale(0f)
        try {
            val ind = indicator()
            ind.working = true
            ind.pulse()
            ind.ring.onVisibilityAggregated(true)
            ind.rail.onVisibilityAggregated(true)
            assertFalse(ind.rail.isSweeping)
            assertFalse(ind.ring.isPulsing)
            assertEquals(0.8f, ind.ring.bloomAlpha, 0.001f)
        } finally {
            setAnimatorScale(1f)
        }
    }

    @Test
    fun `holder fans working and visibility out to both views`() {
        val ind = indicator()
        ind.rail.onVisibilityAggregated(true)
        ind.working = true
        assertTrue(ind.rail.isSweeping)
        ind.isVisible = false
        assertEquals(View.GONE, ind.ring.visibility)
        assertEquals(View.GONE, ind.rail.visibility)
        ind.isVisible = true
        assertEquals(View.VISIBLE, ind.ring.visibility)
        assertEquals(View.VISIBLE, ind.rail.visibility)
    }

    @Test
    fun `ring corner radii come from the window insets per corner`() {
        val ring = indicator().ring
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f), ring.cornerRadiiForTest(), 0f)
        val rounded = WindowInsets.Builder()
            .setRoundedCorner(
                RoundedCorner.POSITION_TOP_LEFT,
                RoundedCorner(RoundedCorner.POSITION_TOP_LEFT, 40, 40, 40),
            )
            .setRoundedCorner(
                RoundedCorner.POSITION_BOTTOM_RIGHT,
                RoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT, 12, 288, 188),
            )
            .build()
        ring.dispatchApplyWindowInsets(rounded)
        // Ring order: top-left, top-right, bottom-right, bottom-left.
        assertArrayEquals(floatArrayOf(40f, 0f, 12f, 0f), ring.cornerRadiiForTest(), 0f)
        // A window that reports no corners goes back to square. Cleared
        // explicitly: this framework's Builder writes its corners into a
        // shared NO_ROUNDED_CORNERS instance, so a fresh empty Builder would
        // still carry the two set above.
        val square = WindowInsets.Builder()
            .setRoundedCorner(RoundedCorner.POSITION_TOP_LEFT, null)
            .setRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT, null)
            .setRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT, null)
            .setRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT, null)
            .build()
        ring.dispatchApplyWindowInsets(square)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f), ring.cornerRadiiForTest(), 0f)
    }
}
