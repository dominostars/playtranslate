package com.playtranslate.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.OverlayMode
import com.playtranslate.R
import com.playtranslate.overlayThemedContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [OverlayModePill]: the segments and the X while expanded, the mode's glyph
 * while collapsed, the two callbacks, and an idempotent render.
 */
@RunWith(RobolectricTestRunner::class)
class OverlayModePillTest {

    private val ctx: Context = overlayThemedContext(ApplicationProvider.getApplicationContext())
    private val options = listOf(
        "Translation" to OverlayMode.TRANSLATION,
        "Furigana" to OverlayMode.FURIGANA,
        "None" to OverlayMode.NONE,
    )
    private val picked = mutableListOf<OverlayMode>()
    private val collapses = mutableListOf<Boolean>()

    private fun pill() = OverlayModePill(ctx).also {
        it.onSelect = { m -> picked += m }
        it.onCollapsedChanged = { c -> collapses += c }
    }

    private fun descendants(v: View): List<View> =
        if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else listOf(v)

    private fun segments(p: OverlayModePill): List<TextView> =
        descendants(p).filterIsInstance<TextView>().filter { it.isClickable }

    private fun closeButton(p: OverlayModePill): ImageView? =
        descendants(p).filterIsInstance<ImageView>()
            .firstOrNull { it.contentDescription == ctx.getString(R.string.capture_overlay_pill_collapse_cd) }

    @Test fun `expanded shows one segment per option and the X`() {
        val p = pill()
        p.render(options, OverlayMode.TRANSLATION, collapsed = false)
        assertEquals(View.VISIBLE, p.visibility)
        assertEquals(listOf("Translation", "Furigana", "None"), segments(p).map { it.text.toString() })
        assertTrue(closeButton(p) != null)
    }

    @Test fun `a segment tap reports the mode and keeps the control expanded`() {
        val p = pill()
        p.render(options, OverlayMode.TRANSLATION, collapsed = false)
        segments(p)[2].performClick()
        assertEquals(listOf(OverlayMode.NONE), picked)
        assertFalse(p.collapsed)
        assertEquals(3, segments(p).size)
        assertTrue(collapses.isEmpty())
    }

    @Test fun `the X collapses to the selected mode's glyph and says so`() {
        val p = pill()
        p.render(options, OverlayMode.FURIGANA, collapsed = false)
        closeButton(p)!!.performClick()
        assertTrue(p.collapsed)
        assertEquals(listOf(true), collapses)
        assertTrue(segments(p).isEmpty())
        val disc = p.getChildAt(0) as ImageView
        assertEquals(
            ctx.getString(R.string.capture_overlay_pill_collapsed_cd, "Furigana"),
            disc.contentDescription,
        )
    }

    @Test fun `a tap on the disc expands again`() {
        val p = pill()
        p.render(options, OverlayMode.NONE, collapsed = true)
        assertTrue(p.collapsed)
        (p.getChildAt(0) as ImageView).performClick()
        assertFalse(p.collapsed)
        assertEquals(listOf(false), collapses)
        assertEquals(3, segments(p).size)
    }

    @Test fun `the same render keeps the view, a change rebuilds it`() {
        val p = pill()
        p.render(options, OverlayMode.TRANSLATION, collapsed = false)
        val first = p.getChildAt(0)
        p.render(options, OverlayMode.TRANSLATION, collapsed = false)
        assertSame(first, p.getChildAt(0))
        p.render(options, OverlayMode.NONE, collapsed = false)
        assertNotSame(first, p.getChildAt(0))
    }

    @Test fun `no options hides the control`() {
        val p = pill()
        p.render(options, OverlayMode.TRANSLATION, collapsed = false)
        p.render(emptyList(), OverlayMode.TRANSLATION, collapsed = false)
        assertEquals(View.GONE, p.visibility)
        assertEquals(0, p.childCount)
    }

    @Test fun `every mode has a glyph`() {
        for (mode in OverlayMode.entries) {
            assertTrue(mode.name, OverlayModePill.glyphOf(mode) != 0)
        }
    }
}
