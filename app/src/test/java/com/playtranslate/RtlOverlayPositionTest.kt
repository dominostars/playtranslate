package com.playtranslate

import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.ui.TextBox
import com.playtranslate.ui.TranslationOverlayView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression cover for the Arabic-locale overlay mispositioning.
 *
 * Overlay windows place their children in physical screen pixels — capture
 * coordinates, display metrics, touch positions — a space that must never
 * mirror. But a root added through WindowManager has layout direction INHERIT,
 * so `ViewRootImpl#performTraversals` stamps the window configuration's
 * direction onto it; under an Arabic system locale that is RTL. FrameLayout's
 * DEFAULT_CHILD_GRAVITY is TOP|START, which resolves to RIGHT there and
 * computes childLeft as `parentRight - width - rightMargin`, discarding
 * leftMargin — while topMargin, carrying no relative bit, still applies.
 *
 * These tests pin both halves of the repair: the framework trap itself, the
 * absolute-gravity spelling that is immune to it (the fix used in
 * `FloatingIconMenu` and `MagnifierLens`), the LTR container pin (the fix used
 * in `TranslationOverlayView`), and the guarantee that pinning placement does
 * not force Arabic text to render left-to-right.
 */
@RunWith(RobolectricTestRunner::class)
class RtlOverlayPositionTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun layoutAt(v: View, w: Int, h: Int) {
        val ws = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY)
        val hs = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        v.measure(ws, hs)
        v.layout(0, 0, w, h)
    }

    /** Places one child in a 1000×500 FrameLayout at the given layout
     *  direction, with [gravity] (-1 meaning "leave unset"). */
    private fun childAt(dir: Int, gravity: Int): Pair<Int, Int> {
        val parent = FrameLayout(ctx)
        parent.layoutDirection = dir
        val child = View(ctx)
        val lp = FrameLayout.LayoutParams(50, 20)
        if (gravity != -1) lp.gravity = gravity
        lp.leftMargin = 100
        lp.topMargin = 300
        parent.addView(child, lp)
        layoutAt(parent, 1000, 500)
        return child.left to child.top
    }

    /** The trap: with gravity left unset, RTL discards leftMargin and pins the
     *  child to the right edge — while topMargin survives untouched. This is
     *  why the symptom was "horizontally wrong, vertically correct". */
    @Test
    fun frameLayout_discardsLeftMargin_underRtl_butKeepsTopMargin() {
        val ltr = childAt(View.LAYOUT_DIRECTION_LTR, -1)
        val rtl = childAt(View.LAYOUT_DIRECTION_RTL, -1)

        assertEquals("LTR honours leftMargin", 100 to 300, ltr)
        assertEquals("RTL pins to the right edge, discarding leftMargin", (1000 - 50) to 300, rtl)
    }

    /** The fix used where a placement container also hosts localized chrome
     *  (FloatingIconMenu, MagnifierLens): absolute LEFT carries no relative
     *  bit, so the screen-pixel arithmetic survives either direction. */
    @Test
    fun frameLayout_absoluteLeftGravity_isDirectionProof() {
        val g = Gravity.TOP or Gravity.LEFT
        assertEquals(
            "absolute LEFT must place identically in both directions",
            childAt(View.LAYOUT_DIRECTION_LTR, g),
            childAt(View.LAYOUT_DIRECTION_RTL, g),
        )
        assertEquals("and at the coordinate asked for", 100 to 300, childAt(View.LAYOUT_DIRECTION_RTL, g))
    }

    /** Replicates what `ViewRootImpl` does to a WindowManager root: it stamps
     *  the window configuration's layout direction onto the host — but ONLY
     *  when the host's own RAW direction is still INHERIT. Both the first
     *  traversal and the runtime locale-change path guard on the raw value
     *  snapshotted at `setView`, so a view that pinned itself in `init` is
     *  left alone. That guard is what makes the pin durable. */
    private fun stampWindowDirectionLikeViewRoot(root: View, configDir: Int) {
        if (rawLayoutDirectionOf(root) == View.LAYOUT_DIRECTION_INHERIT) {
            root.layoutDirection = configDir
        }
    }

    /** `View.getRawLayoutDirection()` is public in the framework but omitted
     *  from the SDK stub, so reach it reflectively — Robolectric loads the real
     *  class. Reading the RAW (unresolved) value is the whole point: the
     *  resolved getter reports LTR both for "pinned to LTR" and for "still
     *  INHERIT, nothing resolved yet", which is exactly the distinction
     *  ViewRootImpl's guard turns on. */
    private fun rawLayoutDirectionOf(v: View): Int =
        View::class.java.getMethod("getRawLayoutDirection").invoke(v) as Int

    /** Keeps [stampWindowDirectionLikeViewRoot] honest: an unpinned root DOES
     *  get stamped, so the overlay test below is not passing vacuously. */
    @Test
    fun viewRootStamp_appliesToAnUnpinnedRoot() {
        val bare = FrameLayout(ctx)
        stampWindowDirectionLikeViewRoot(bare, View.LAYOUT_DIRECTION_RTL)
        assertEquals(View.LAYOUT_DIRECTION_RTL, bare.layoutDirection)
    }

    private fun overlayPlacements(configure: (TranslationOverlayView) -> Unit): List<Pair<Int, Int>> {
        val boxes = listOf(
            TextBox(translatedText = "hello", bounds = Rect(100, 200, 300, 260), sourceText = "src1"),
            TextBox(translatedText = "world", bounds = Rect(500, 600, 700, 660), sourceText = "src2"),
        )
        val v = TranslationOverlayView(ctx)
        configure(v)
        layoutAt(v, 1000, 800)
        v.setBoxes(boxes, 0, 0, 1000, 800)
        layoutAt(v, 1000, 800)
        return (0 until v.childCount).map { i ->
            val c = v.getChildAt(i)
            (c.left + c.translationX).toInt() to (c.top + c.translationY).toInt()
        }
    }

    /** The fix on the pure-geometry surface, in its WINDOW hosting mode (live +
     *  one-shot overlays): TranslationOverlayView pins itself to LTR in `init`,
     *  so ViewRootImpl's stamp is skipped and every chip lands on its capture
     *  coordinate whatever the system locale. Before the pin, an RTL config
     *  collapsed both chips onto x=788 (`overlayWidth - chipWidth`) at correct
     *  heights — the reported "wrong horizontally, right vertically". */
    @Test
    fun translationOverlay_asWindowRoot_ignoresAnRtlWindowConfiguration() {
        val ltr = overlayPlacements { stampWindowDirectionLikeViewRoot(it, View.LAYOUT_DIRECTION_LTR) }
        val rtl = overlayPlacements { stampWindowDirectionLikeViewRoot(it, View.LAYOUT_DIRECTION_RTL) }

        assertEquals("two chips built", 2, ltr.size)
        assertEquals("chips must not move when the system locale is RTL", ltr, rtl)
        // Pin the absolute values too, so a change that moves BOTH directions
        // together still trips this test.
        assertEquals("chips land on their capture coordinates", listOf(94 to 194, 494 to 594), ltr)
    }

    /** The same fix in its NESTED hosting mode — the in-app sliver, where
     *  CaptureResultOverlay adds this view into a `CaptureResultRoot` that has
     *  itself gone RTL. An explicit direction beats INHERIT from the parent, so
     *  one pin covers both hosting modes. */
    @Test
    fun translationOverlay_nestedInRtlParent_stillPlacesOnCaptureCoordinates() {
        val placements = overlayPlacements { overlay ->
            FrameLayout(ctx).apply {
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                addView(overlay, FrameLayout.LayoutParams(1000, 800))
            }
        }
        assertEquals("chips land on their capture coordinates", listOf(94 to 194, 494 to 594), placements)
    }

    /** Guards the container pin: it must not force Arabic to render
     *  left-to-right. Text direction resolves independently of layout
     *  direction — ViewRootImpl#getTextDirection returns
     *  TEXT_DIRECTION_FIRST_STRONG, which is content-driven — so Arabic still
     *  gets an RTL paragraph and ALIGN_NORMAL still right-aligns it. */
    @Test
    fun ltrPinnedContainer_stillRendersArabicTextRightToLeft() {
        val parent = FrameLayout(ctx)
        parent.layoutDirection = View.LAYOUT_DIRECTION_LTR
        val tv = android.widget.TextView(ctx).apply { text = "مرحبا بالعالم" }
        parent.addView(tv, FrameLayout.LayoutParams(400, 80))
        layoutAt(parent, 1000, 500)

        val layout = requireNotNull(tv.layout) { "TextView must have laid out" }
        assertEquals(
            "Arabic must lay out as an RTL paragraph even inside an LTR-pinned parent",
            -1, layout.getParagraphDirection(0),
        )
        assertEquals(
            "ALIGN_NORMAL keeps Arabic right-aligned in its box",
            android.text.Layout.Alignment.ALIGN_NORMAL, layout.alignment,
        )
    }
}
