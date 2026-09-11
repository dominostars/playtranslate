package com.playtranslate.ui

import android.content.Context
import android.graphics.Rect
import android.view.View.MeasureSpec
import com.playtranslate.language.TextOrientation
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The fuzzy fast path in [TranslationOverlayView.setBoxes] must not swallow a
 * change in the DRAWN extension ([TextBox.drawBounds] beyond [TextBox.bounds]):
 * a furigana band joining a chip already on screen, same text, same matched
 * rect, within the 20 px jitter tolerance, has to rebuild the child so the
 * chip covers the reading's pixels (Codex adversarial review, 2026-09-10).
 */
@RunWith(RobolectricTestRunner::class)
class TranslationOverlayDrawExtentTest {

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
    fun carriedDrawBounds_carriesTheExtension_notThePosition() {
        val oldB = Rect(100, 200, 600, 240); val oldD = Rect(90, 180, 600, 240)   // band 10 left, 20 top
        val newB = Rect(130, 260, 630, 300)                                       // text moved 30 right, 60 down
        val carried = carriedDrawBounds(oldB, oldD, newB, newB)
        assertEquals(Rect(120, 240, 630, 300), carried)
        // The fresh read's own band unions in.
        assertEquals(Rect(120, 230, 640, 300), carriedDrawBounds(oldB, oldD, newB, Rect(130, 230, 640, 300)))
        // A hand-built box whose drawn rect is smaller than its matched rect carries nothing.
        assertEquals(newB, carriedDrawBounds(oldB, Rect(110, 210, 590, 230), newB, newB))
    }

    @Test
    fun sameTextSameBounds_furiganaBandJoins_childGrows() {
        val v = laidOutOverlay()
        val plain = TextBox(
            translatedText = "Hello", bounds = Rect(100, 200, 600, 240),
            sourceText = "こんにちは", orientation = TextOrientation.HORIZONTAL,
        )
        // Identity scale (screenshot == view), zero crop, non-authoritative caller.
        v.setBoxes(listOf(plain), 0, 0, 1000, 1000)
        assertEquals(1, v.childCount)
        val before = v.getChildAt(0).layoutParams.height

        // The next cycle read the furigana: drawn rect grows 16 px upward,
        // matched rect and text unchanged — fuzzy-equal, so only the drawn
        // extension distinguishes the two lists.
        val banded = plain.copy(drawBounds = Rect(100, 184, 600, 240))
        v.setBoxes(listOf(banded), 0, 0, 1000, 1000)
        assertEquals(1, v.childCount)
        val after = v.getChildAt(0).layoutParams.height
        assertEquals("chip must grow by the band", 16, after - before)

        // And a pure jitter of the matched rect, extension unchanged, still
        // takes the fast path: same child height, no rebuild.
        val jittered = banded.copy(bounds = Rect(100, 203, 600, 243), drawBounds = Rect(100, 187, 600, 243))
        v.setBoxes(listOf(jittered), 0, 0, 1000, 1000)
        assertEquals(after, v.getChildAt(0).layoutParams.height)
    }
}
