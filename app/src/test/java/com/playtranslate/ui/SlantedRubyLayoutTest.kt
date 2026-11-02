package com.playtranslate.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.cos
import kotlin.math.sin

/**
 * A slanted reading ([TranslationOverlayView]'s slanted ruby) whose text is
 * taller than its band, as a CJK line is at the size ratio: the view wraps
 * the text instead of scrolling it (the outlined stroke pass draws no
 * scrolled text, and its debug check throws on one), and grows away from
 * the base, its bottom edge on the band's. Robolectric's font metrics make
 * every reading taller than its band, so the case is the default here.
 */
@RunWith(RobolectricTestRunner::class)
class SlantedRubyLayoutTest {

    private val w = 1080
    private val h = 1920

    @Test fun `a slanted reading taller than its band grows away from its base and never scrolls`() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        ctx.setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        val host = FrameLayout(ctx)
        val overlay = TranslationOverlayView(ctx, oneShot = true)
        host.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        fun traverse() {
            host.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            host.layout(0, 0, w, h)
        }
        traverse()

        // A 120 by 15 band at 20 degrees about (300, 400); its bounds are the
        // turned band's box.
        val angle = 20f
        val band = 15f
        val rad = Math.toRadians(angle.toDouble())
        val halfW = (60 * cos(rad) + 7.5 * sin(rad)).toFloat()
        val halfH = (60 * sin(rad) + 7.5 * cos(rad)).toFloat()
        val bounds = Rect((300 - halfW).toInt(), (400 - halfH).toInt(), (300 + halfW).toInt(), (400 + halfH).toInt())
        val box = TextBox(
            "かな", bounds, isFurigana = true, angleDeg = angle, orientedWidth = 120f, orientedHeight = band,
        )
        overlay.setBoxes(listOf(box), 0, 0, w, h)
        traverse()

        val reading = (0 until overlay.childCount).map { overlay.getChildAt(it) }
            .filterIsInstance<OutlinedTextView>().single()
        assertEquals("no scroll", 0, reading.scrollY)
        assertTrue("the view holds its whole text", reading.height >= reading.layout.height)
        assertTrue("taller than its band here", reading.height > band)

        // The bottom edge's centre, the view turned about its own centre,
        // stays on the band's.
        val cx = reading.translationX + reading.width / 2f
        val cy = reading.translationY + reading.height / 2f
        val bottomX = cx - reading.height / 2f * sin(rad).toFloat()
        val bottomY = cy + reading.height / 2f * cos(rad).toFloat()
        val bandBottomX = bounds.exactCenterX() - band / 2f * sin(rad).toFloat()
        val bandBottomY = bounds.exactCenterY() + band / 2f * cos(rad).toFloat()
        assertEquals(bandBottomX, bottomX, 1.5f)
        assertEquals(bandBottomY, bottomY, 1.5f)

        // And it draws, the stroke pass's contract checks included.
        overlay.draw(Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)))
    }
}
