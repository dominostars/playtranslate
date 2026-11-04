package com.playtranslate.ui

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.OverlayMode
import com.playtranslate.R
import com.playtranslate.overlayThemedContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The game screen's mode pill sizes its segments to its labels, measured in
 * the font they draw in (native graphics, so the measure is a real font's):
 * every label shows at its full size, never shrunk or cut, the longest
 * ("Translations", "Definitions") included. On a screen too narrow for that
 * the segments give way, so the X stays on screen and the labels shrink as
 * they always could.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayModePillWidthTest {

    private val ctx: Context = overlayThemedContext(ApplicationProvider.getApplicationContext())
    private val modes = listOf(OverlayMode.TRANSLATION, OverlayMode.FURIGANA, OverlayMode.DEFINITIONS, OverlayMode.NONE)

    private fun descendants(v: View): List<View> =
        if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else listOf(v)

    private fun segments(p: View): List<TextView> =
        descendants(p).filterIsInstance<TextView>().filter { it.isClickable }

    /** [v]'s left edge in [ancestor]'s coordinates (a detached view has no
     *  window position to ask for). */
    private fun leftIn(v: View, ancestor: View): Int {
        var x = 0
        var cur = v
        while (cur !== ancestor) {
            x += cur.left
            cur = cur.parent as View
        }
        return x
    }

    /** The expanded pill with [labels], laid out in a parent [width] wide. */
    private fun laidOut(labels: List<String>, width: Int): OverlayModePill {
        val parent = FrameLayout(ctx)
        val pill = OverlayModePill(ctx)
        parent.addView(pill, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        pill.render(labels.zip(modes), OverlayMode.TRANSLATION, collapsed = false)
        parent.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(400, MeasureSpec.EXACTLY),
        )
        parent.layout(0, 0, width, 400)
        return pill
    }

    private val shipped get() = listOf(
        ctx.getString(R.string.overlay_mode_option_translation),
        ctx.getString(R.string.overlay_mode_option_furigana),
        ctx.getString(R.string.overlay_mode_option_definitions),
        ctx.getString(R.string.overlay_mode_option_none),
    )

    @Test fun `every label shows at its full size`() {
        val full = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, ctx.resources.displayMetrics)
        for (labels in listOf(shipped, listOf("Translations", "Furigana", "Definitions", "None"))) {
            val pill = laidOut(labels, 4000)
            val segs = segments(pill)
            assertEquals(labels, segs.map { it.text.toString() })
            for (s in segs) {
                assertEquals("${s.text} keeps its size", full, s.textSize, 0.5f)
                assertEquals("${s.text} is not cut", 0, s.layout.getEllipsisCount(0))
            }
        }
    }

    @Test fun `on a screen too narrow for that the X stays on screen`() {
        val width = 240
        val pill = laidOut(listOf("Translations", "Furigana", "Definitions", "None"), width)
        val close = descendants(pill).filterIsInstance<ImageView>()
            .single { it.contentDescription == ctx.getString(R.string.capture_overlay_pill_collapse_cd) }
        assertTrue("the pill fits the screen", pill.width <= width)
        assertTrue("the X ends inside the pill", leftIn(close, pill) + close.width <= pill.width)
        assertTrue("the X has its width", close.width > 0)
    }
}
