package com.playtranslate.ui

import android.graphics.Color
import android.graphics.Rect
import com.playtranslate.language.TextAlignment
import com.playtranslate.language.TextOrientation

/**
 * A single translated (or furigana) text box positioned over the game screen.
 *
 * Extracted from `TranslationOverlayView` to a top-level type so the live-mode
 * classes, classification, and the overlay window registry can reference it
 * without depending on a particular overlay View implementation.
 */
data class TextBox(
    val translatedText: String,
    /** Bounding box in original bitmap pixel coordinates. */
    val bounds: Rect,
    /** Average color of the game content behind this box (ARGB). */
    val bgColor: Int = Color.argb(200, 0, 0, 0),
    /** Text color — estimated from game text or chosen for contrast. */
    val textColor: Int = Color.WHITE,
    /** Number of OCR lines in this group (for skeleton placeholders). */
    val lineCount: Int = 1,
    /** True for furigana readings (smaller text, pill background). */
    val isFurigana: Boolean = false,
    /** Marked when pinhole detection finds a minor change under this overlay. */
    val dirty: Boolean = false,
    /** Original OCR source text this overlay translates. Used for content-based matching. */
    val sourceText: String = "",
    /** Text orientation — vertical boxes render with 90° CW rotated text. */
    val orientation: TextOrientation = TextOrientation.HORIZONTAL,
    /** Block alignment for horizontal boxes — drives skeleton bar placement
     *  and translated-text gravity. Ignored for vertical boxes. */
    val alignment: TextAlignment = TextAlignment.LEFT
)
