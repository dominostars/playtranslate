package com.playtranslate.ui

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A crop of a screenshot as fractions of the image (left, top, right,
 * bottom in 0..1): ordered, inside the image, and neither empty nor the
 * whole image. A crop of everything is no crop, so [of] returns null for
 * both and a stored crop always means a cut frame. The fractions are of
 * the decoded file (BitmapFactory, no EXIF handling: the preview decode,
 * the editor and the send all read it the same way). Pure Kotlin, so the
 * pixel math is JVM-testable.
 */
data class ScreenshotCrop private constructor(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    /** The crop on a [width] x [height] image, in px: the outer pixels the
     *  fractions touch (the near edges floored, the far ones ceiled),
     *  inside the image, at least one pixel on each axis. */
    fun toPixelRect(width: Int, height: Int): Rect {
        require(width > 0 && height > 0) { "an empty image: $width x $height" }
        val l = floor(left * width).toInt().coerceIn(0, width - 1)
        val t = floor(top * height).toInt().coerceIn(0, height - 1)
        val r = ceil(right * width).toInt().coerceIn(l + 1, width)
        val b = ceil(bottom * height).toInt().coerceIn(t + 1, height)
        return Rect(l, t, r, b)
    }

    fun toRectF(): RectF = RectF(left, top, right, bottom)

    fun toFloatArray(): FloatArray = floatArrayOf(left, top, right, bottom)

    companion object {
        /** The crop with its edges ordered and clamped to the image, or
         *  null when that leaves nothing or everything. */
        fun of(left: Float, top: Float, right: Float, bottom: Float): ScreenshotCrop? {
            val l = minOf(left, right).coerceIn(0f, 1f)
            val r = maxOf(left, right).coerceIn(0f, 1f)
            val t = minOf(top, bottom).coerceIn(0f, 1f)
            val b = maxOf(top, bottom).coerceIn(0f, 1f)
            if (r <= l || b <= t) return null
            if (l == 0f && t == 0f && r == 1f && b == 1f) return null
            return ScreenshotCrop(l, t, r, b)
        }

        fun of(rect: RectF): ScreenshotCrop? = of(rect.left, rect.top, rect.right, rect.bottom)

        /** The inverse of [toFloatArray]; anything else is no crop. */
        fun fromFloatArray(a: FloatArray?): ScreenshotCrop? =
            a?.takeIf { it.size == 4 }?.let { of(it[0], it[1], it[2], it[3]) }
    }
}

/** What a send attaches: the screenshot file and the crop to apply to it. */
data class ScreenshotAttachment(val path: String, val crop: ScreenshotCrop? = null)

/**
 * The card editor's screenshot: the pinned file plus the user's edits (a
 * crop, or its removal). ONE per editor, shared by the word and sentence
 * tabs: the cells render it and the send reads it, so the tabs cannot
 * disagree. Every edit is written back into the host's [args], the bundle
 * the editor was launched with and the one it saves (a fragment's
 * arguments), which is how the pinned path already survives a saved-state
 * recreation: [open] on the restored bundle adopts the pin and the crop,
 * or finds the removal. A workspace page's bundle is never saved, and the
 * write-back is inert there.
 *
 * The pin itself is owned from [open] to a final [release], through a
 * removal and across recreations: a send already past Save holds the path
 * and freezes from it ([AnkiScreenshotPin.pinForSend]), so a ✕ tapped while
 * the send runs leaves the card with the screenshot as it stood when Save
 * was tapped (deleting the file on removal made that send land without its
 * image; Codex adversarial review).
 *
 * Main thread only.
 */
class AnkiScreenshotState private constructor(
    private val args: Bundle,
    /** The open-time pin (or the live path when the copy failed; never
     *  deleted then, [AnkiScreenshotPin.release] checks). */
    private val pinnedPath: String,
    initial: ScreenshotAttachment?,
) {
    /** The screenshot as it stands; null once removed. */
    var attachment: ScreenshotAttachment? = initial
        private set

    private val listeners = mutableListOf<(AnkiScreenshotState) -> Unit>()

    fun addListener(listener: (AnkiScreenshotState) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (AnkiScreenshotState) -> Unit) {
        listeners -= listener
    }

    private fun notifyChanged() {
        listeners.toList().forEach { it(this) }
    }

    /** Set or clear the crop. A no-op once removed, or for the crop that
     *  already stands. */
    fun setCrop(crop: ScreenshotCrop?) {
        val current = attachment ?: return
        if (current.crop == crop) return
        attachment = current.copy(crop = crop)
        if (crop == null) args.remove(ARG_SCREENSHOT_CROP)
        else args.putFloatArray(ARG_SCREENSHOT_CROP, crop.toFloatArray())
        notifyChanged()
    }

    /** Drop the screenshot from the card: the Screenshot groups leave, the
     *  send attaches nothing, a restore finds the removal. The pin file
     *  stays until [release] (see the class doc). */
    fun remove() {
        if (attachment == null) return
        attachment = null
        args.putBoolean(ARG_SCREENSHOT_REMOVED, true)
        args.remove(ARG_SCREENSHOT_CROP)
        notifyChanged()
    }

    /** Host-view teardown. [final] is the host's finality verdict (its
     *  isFinalMediaTeardown clause): a saved-state destroy keeps the pin
     *  for the restored instance, a final teardown releases it, removed or
     *  not. */
    fun release(ctx: Context, final: Boolean) {
        listeners.clear()
        if (final) AnkiScreenshotPin.release(ctx, pinnedPath)
    }

    companion object {
        /** The launch-args key every editor entry writes the screenshot's
         *  path to. */
        const val ARG_SCREENSHOT_PATH = "screenshot_path"
        private const val ARG_SCREENSHOT_CROP = "screenshot_crop"
        private const val ARG_SCREENSHOT_REMOVED = "screenshot_removed"

        /**
         * The editor's screenshot from its [args]. A live capture path is
         * pinned (the capture surfaces overwrite fixed filenames; field
         * report 2026-07-29) and the pin written back; a pin written back
         * earlier is adopted, with the crop saved beside it, or the removal
         * (the state then holds no attachment and still owns the pin, so
         * the final teardown deletes it). Null with no path, or when the
         * file is gone, in which case the keys are cleared so the send has
         * nothing to attach.
         */
        fun open(ctx: Context, args: Bundle): AnkiScreenshotState? {
            val path = args.getString(ARG_SCREENSHOT_PATH) ?: return null
            val pinned = if (AnkiScreenshotPin.isPin(ctx, path)) path else AnkiScreenshotPin.pin(ctx, path)
            if (pinned == null || !File(pinned).isFile) {
                args.remove(ARG_SCREENSHOT_PATH)
                args.remove(ARG_SCREENSHOT_CROP)
                args.remove(ARG_SCREENSHOT_REMOVED)
                return null
            }
            args.putString(ARG_SCREENSHOT_PATH, pinned)
            val attachment = if (args.getBoolean(ARG_SCREENSHOT_REMOVED, false)) {
                null
            } else {
                ScreenshotAttachment(pinned, ScreenshotCrop.fromFloatArray(args.getFloatArray(ARG_SCREENSHOT_CROP)))
            }
            return AnkiScreenshotState(args, pinned, attachment)
        }
    }
}
