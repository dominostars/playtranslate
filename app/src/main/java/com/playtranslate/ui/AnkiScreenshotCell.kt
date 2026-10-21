package com.playtranslate.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.playtranslate.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The Screenshot group as [addAnkiScreenshotGroup] built it: [view] is the
 *  header + card, the caller's anchor for the groups it inserts above;
 *  [release] drops the preview bitmap at the host's teardown. */
internal class AnkiScreenshotGroup(val view: LinearLayout, private val image: ImageView) {
    fun release() {
        image.setImageBitmap(null)
    }
}

/**
 * The Screenshot group of a card editor: the header and card around the
 * frame, the crop overlay ([RegionIndicatorView]: the frame dimmed outside
 * the crop with the dashed accent outline), the ✕ that removes the
 * screenshot and the crop button that opens the editor ([onCrop]). Both
 * tabs of the word editor and the sentence editor build it over ONE
 * [AnkiScreenshotState], and each group follows that state for as long as
 * it lives: a crop set in one tab shows in the other, a removal in one
 * takes both out of their containers. Null when the state holds nothing.
 */
internal fun addAnkiScreenshotGroup(
    parent: LinearLayout,
    ctx: Context,
    scope: CoroutineScope,
    state: AnkiScreenshotState,
    onCrop: () -> Unit,
): AnkiScreenshotGroup? {
    if (state.attachment == null) return null
    val density = ctx.resources.displayMetrics.density
    val group = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }
    ankiGroupHeader(group, ctx.getString(R.string.anki_group_screenshot))
    val card = ankiGroupCard(group)
    val frame = FrameLayout(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }
    val img = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = true
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
    }
    frame.addView(img)

    // The crop overlay. With adjustViewBounds the ImageView's bounds are the
    // image's, so the crop's fractions map onto the overlay (which matches
    // them) directly. INVISIBLE, not GONE, while there is no crop: it keeps
    // its size, so the first crop lands without waiting for a layout.
    val overlay = RegionIndicatorView(ctx, Rect()).apply { visibility = View.INVISIBLE }
    frame.addView(
        overlay,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ),
    )
    fun syncOverlay() {
        val crop = state.attachment?.crop
        val w = overlay.width
        val h = overlay.height
        if (crop == null || img.drawable == null || w <= 0 || h <= 0) {
            overlay.visibility = View.INVISIBLE
            return
        }
        overlay.moveTo(crop.toPixelRect(w, h))
        overlay.visibility = View.VISIBLE
    }
    overlay.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
        if (r - l != or - ol || b - t != ob - ot) syncOverlay()
    }

    val buttonSize = (32 * density).toInt()
    val buttonMargin = (8 * density).toInt()
    // The crop button, on the same dark circle as the ✕; shown once the
    // frame is on screen, since it crops what is shown.
    val cropButton = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_crop)
        scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = (7 * density).toInt()
        setPadding(pad, pad, pad, pad)
        setBackgroundResource(R.drawable.bg_screenshot_remove)
        isClickable = true
        isFocusable = true
        contentDescription = ctx.getString(R.string.anki_screenshot_crop)
        visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(
            buttonSize, buttonSize,
            Gravity.TOP or Gravity.START,
        ).also {
            it.topMargin = buttonMargin
            it.marginStart = buttonMargin
        }
        setOnClickListener { onCrop() }
    }
    frame.addView(cropButton)

    // Semi-transparent black circle keeps the white "✕" legible against
    // bright frames; size is fixed so the hit target stays consistent
    // regardless of the glyph's intrinsic width.
    frame.addView(
        TextView(ctx).apply {
            text = "✕"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_screenshot_remove)
            isClickable = true
            isFocusable = true
            contentDescription = ctx.getString(R.string.anki_screenshot_remove_content_description)
            layoutParams = FrameLayout.LayoutParams(
                buttonSize, buttonSize,
                Gravity.TOP or Gravity.END,
            ).also {
                it.topMargin = buttonMargin
                it.marginEnd = buttonMargin
            }
            setOnClickListener { state.remove() }
        },
    )
    card.addView(frame)
    parent.addView(group)

    // Decoded off the main thread: a bind runs synchronously inside the
    // workspace push, and a full-screen decode there stalls the window's
    // enter animation. Display-only; the card gets the file itself
    // (addMediaFromFile), never this bitmap.
    scope.launch {
        val path = state.attachment?.path ?: return@launch
        val bmp = withContext(Dispatchers.IO) {
            decodeScreenshotForDisplay(File(path), ctx.resources.displayMetrics.widthPixels)
        }
        if (bmp == null || state.attachment == null) return@launch
        img.setImageBitmap(bmp)
        cropButton.visibility = View.VISIBLE
        syncOverlay()
    }

    state.addListener { s ->
        if (s.attachment == null) {
            (group.parent as? ViewGroup)?.removeView(group)
            img.setImageBitmap(null)
        } else {
            syncOverlay()
        }
    }
    return AnkiScreenshotGroup(group, img)
}

/** Decode [file] for an in-editor screenshot preview, sampled down
 *  (power-of-2) toward [targetW] instead of always inflating the full file.
 *  Conservative: the decoded width never drops below [targetW], so a
 *  same-display screenshot resolves to inSampleSize 1 and the cap only bites
 *  when the source is at least twice the width it will be displayed at. Call
 *  off the main thread; the bounds pass alone is a header parse. */
internal fun decodeScreenshotForDisplay(file: File, targetW: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    if (bounds.outWidth > 0 && targetW > 0) {
        while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.absolutePath, opts)
}
