package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.playtranslate.ui.TextBox
import com.playtranslate.ui.TranslationOverlayView

/**
 * The OCR-input blackout: paint each non-dirty box's rendered footprint with
 * its background color so the next OCR pass can never read PlayTranslate's own
 * overlay as screen text. Extracted from [PinholeOverlayMode] so the geometry
 * is unit-testable.
 *
 * Rotated chips fill from the DRAWN-footprint channel
 * ([TranslationOverlayView.getChildFootprints]) — the laid-out dims (deskewed-
 * frame padding and same-angle carving included) rotated about the rendered
 * AABB's center — never the AABB itself, whose corner triangles are live game
 * pixels the user still sees (painting those would blind OCR to real on-screen
 * text), and never the STORED oriented dims, which stopped matching the drawn
 * chip when padding moved into the deskewed frame (Codex adversarial finding:
 * the padding fringe was left visible to OCR, and a carved chip was
 * over-filled past its carved edge). The two sibling mechanisms
 * (checkPinholes' sample skip, updateCleanRef's masked restore) key on the
 * overlay bitmap's alpha and follow the drawn truth automatically — this fill
 * runs on the OCR input copy, where no overlay alpha exists, so it must
 * reconstruct the footprint from the channel instead.
 */
internal object PinholeFill {

    /** Anti-aliasing buffer beyond the rendered overlay's edge, so ML Kit
     *  doesn't read AA fringe pixels as glyph fragments. Kept tiny so
     *  adjacent text lines outside the rendered overlay aren't obscured. */
    private const val AA_BUFFER = 3

    fun fillOverlayRegions(
        bitmap: Bitmap,
        boxes: List<TextBox>,
        bitmapRects: List<Rect>,
        footprints: List<TranslationOverlayView.ChildFootprint>,
    ) {
        val paint = Paint()
        val canvas = Canvas(bitmap)
        var rectIdx = 0
        for (box in boxes) {
            if (box.dirty) continue
            val rect = bitmapRects.getOrNull(rectIdx) ?: break
            val f = footprints.getOrNull(rectIdx)
            rectIdx++
            paint.color = box.bgColor or 0xFF000000.toInt()
            when {
                f != null && f.angleDeg != 0f -> {
                    // Drawn dims ride the same view→bitmap scale as the rect
                    // (identity in this mode; kept exact regardless). The
                    // canvas clips to the bitmap.
                    val sx = if (f.rect.width() > 0) rect.width().toFloat() / f.rect.width() else 1f
                    val sy = if (f.rect.height() > 0) rect.height().toFloat() / f.rect.height() else 1f
                    val cx = (rect.left + rect.right) / 2f
                    val cy = (rect.top + rect.bottom) / 2f
                    val hw = f.drawnW * sx / 2f + AA_BUFFER
                    val hh = f.drawnH * sy / 2f + AA_BUFFER
                    canvas.save()
                    canvas.rotate(f.angleDeg, cx, cy)
                    canvas.drawRect(cx - hw, cy - hh, cx + hw, cy + hh, paint)
                    canvas.restore()
                }
                f == null && box.angleDeg != 0f && box.orientedWidth > 0f && box.orientedHeight > 0f -> {
                    // Defensive: no footprint payload for this child — fall
                    // back to the stored oriented dims (the pre-channel fill,
                    // still footprint-shaped rather than AABB).
                    val cx = (rect.left + rect.right) / 2f
                    val cy = (rect.top + rect.bottom) / 2f
                    val hw = box.orientedWidth / 2f + AA_BUFFER
                    val hh = box.orientedHeight / 2f + AA_BUFFER
                    canvas.save()
                    canvas.rotate(box.angleDeg, cx, cy)
                    canvas.drawRect(cx - hw, cy - hh, cx + hw, cy + hh, paint)
                    canvas.restore()
                }
                else -> {
                    val l = (rect.left - AA_BUFFER).coerceAtLeast(0)
                    val t = (rect.top - AA_BUFFER).coerceAtLeast(0)
                    val r = (rect.right + AA_BUFFER).coerceAtMost(bitmap.width)
                    val b = (rect.bottom + AA_BUFFER).coerceAtMost(bitmap.height)
                    canvas.drawRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), paint)
                }
            }
        }
    }
}
