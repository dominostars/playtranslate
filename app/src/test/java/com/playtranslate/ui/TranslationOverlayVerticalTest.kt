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
 * Verifies the [TranslationOverlayView] render-path selection for vertical
 * boxes: CJK targets stack upright (a non-rotated [VerticalTextView] with a
 * background fill — the fill is load-bearing for pinhole detection), while
 * other targets keep the 90°-rotated [OutlinedTextView].
 *
 * Drives measure/layout before [TranslationOverlayView.setBoxes] so the rebuild
 * runs synchronously (no looper idling needed).
 */
@RunWith(RobolectricTestRunner::class)
class TranslationOverlayVerticalTest {

    private val verticalBox = TextBox(
        translatedText = "日本語ですよ",
        bounds = Rect(100, 100, 170, 600),
        orientation = TextOrientation.VERTICAL,
    )

    private fun laidOutOverlay(verticalTextTarget: Boolean): TranslationOverlayView {
        val ctx: Context = RuntimeEnvironment.getApplication()
        ctx.setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        val v = TranslationOverlayView(ctx, verticalTextTarget = verticalTextTarget)
        v.measure(
            MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(1920, MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, 1080, 1920)
        return v
    }

    @Test
    fun cjkTarget_verticalBox_stacksUprightWithFill() {
        val v = laidOutOverlay(verticalTextTarget = true)
        v.setBoxes(listOf(verticalBox), 0, 0, 1080, 1920)

        assertEquals(1, v.childCount)
        val child = v.getChildAt(0)
        assertTrue("expected VerticalTextView, got ${child.javaClass.simpleName}", child is VerticalTextView)
        assertEquals("stacked text must not be rotated", 0f, child.rotation)
        // Regression guard for the silent pinhole defect: the vertical child
        // must carry a background fill (renderToOffscreen captures it).
        assertNotNull("vertical child must have a background fill", child.background)
    }

    @Test
    fun latinTarget_verticalBox_keepsRotation() {
        val v = laidOutOverlay(verticalTextTarget = false)
        v.setBoxes(listOf(verticalBox), 0, 0, 1080, 1920)

        assertEquals(1, v.childCount)
        val child = v.getChildAt(0)
        assertTrue("expected OutlinedTextView, got ${child.javaClass.simpleName}", child is OutlinedTextView)
        assertEquals("Latin vertical box keeps the 90° rotation", 90f, child.rotation)
    }
}
