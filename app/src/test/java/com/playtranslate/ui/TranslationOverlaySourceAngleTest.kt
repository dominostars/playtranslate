package com.playtranslate.ui

import android.content.Context
import android.graphics.Rect
import android.view.View.MeasureSpec
import com.playtranslate.language.TextOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Verifies the [RenderMode.SOURCE_ANGLE] child in [TranslationOverlayView]:
 * laid out at the screen-scaled oriented dims, rotated by the source angle
 * about its (default, center) pivot, and translation-pinned so its center
 * lands on the UNPADDED mapped bounds center — the geometry that puts the
 * rotated chip exactly on the slanted source footprint.
 *
 * Asserts view properties, not `getChildScreenRects()` — `View.getHitRect`
 * falls back to the layout rect when the view is unattached (Robolectric),
 * same caveat as the existing ROTATE path.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationOverlaySourceAngleTest {

    /** AABB of a 200×40 rect rotated 30° about its center. */
    private val slantedBox = TextBox(
        translatedText = "Hello",
        bounds = Rect(300, 300, 493, 435),
        orientation = TextOrientation.HORIZONTAL,
        angleDeg = 30f,
        orientedWidth = 200f,
        orientedHeight = 40f,
    )

    private fun laidOutOverlay(): TranslationOverlayView {
        val ctx: Context = RuntimeEnvironment.getApplication()
        ctx.setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        val v = TranslationOverlayView(ctx, verticalTextTarget = false)
        v.measure(
            MeasureSpec.makeMeasureSpec(1000, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(1000, MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, 1000, 1000)
        return v
    }

    @Test
    fun slantedBox_childRotatedAtOrientedDims_centerPinned() {
        val v = laidOutOverlay()
        // screenshot == display → identity scale; crop 0.
        v.setBoxes(listOf(slantedBox), 0, 0, 1000, 1000)

        assertEquals(1, v.childCount)
        val child = v.getChildAt(0)
        assertTrue("expected OutlinedTextView, got ${child.javaClass.simpleName}", child is OutlinedTextView)
        assertEquals(30f, child.rotation)
        // The chip carries the PADDED drawn dims (200 + 2·6, 40 + 2·6 at
        // density 1) — padding lives in the deskewed frame since the carve
        // stage, mirroring how upright boxes render into padded rects.
        assertEquals(212, child.layoutParams.width)
        assertEquals(52, child.layoutParams.height)
        // Pin: child center on the mapped bounds center (396.5, 367.5).
        assertEquals(396.5f - 106f, child.translationX, 0.01f)
        assertEquals(367.5f - 26f, child.translationY, 0.01f)
        // The fill is load-bearing for pinhole detection.
        assertNotNull("slanted child must have a background fill", child.background)
    }

    @Test
    fun slantedSkeleton_takesTheSameRotatedPlacement() {
        val v = laidOutOverlay()
        v.setBoxes(listOf(slantedBox.copy(translatedText = "")), 0, 0, 1000, 1000)

        assertEquals(1, v.childCount)
        val child = v.getChildAt(0)
        assertEquals("skeletons rotate with their source", 30f, child.rotation)
        assertEquals(212, child.layoutParams.width)
        assertEquals(52, child.layoutParams.height)
        assertEquals(396.5f - 106f, child.translationX, 0.01f)
        assertEquals(367.5f - 26f, child.translationY, 0.01f)
    }

    @Test
    fun uprightBox_staysUnrotatedWithMargins() {
        val v = laidOutOverlay()
        v.setBoxes(listOf(slantedBox.copy(angleDeg = 0f, orientedWidth = 0f, orientedHeight = 0f)), 0, 0, 1000, 1000)

        val child = v.getChildAt(0)
        assertEquals(0f, child.rotation)
        assertEquals(0f, child.translationX)
    }
}
