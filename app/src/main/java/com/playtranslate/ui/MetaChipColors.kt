package com.playtranslate.ui

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A frequency chip's fill and text colour — ONE decision, carried as a pair
 * so no renderer can apply half of it.
 *
 * The half-application is not hypothetical: the styled WebView document
 * ([DefinitionsDocument]) is a second implementation of the meta row, and
 * its port of the accent override took the fill and left the text at the
 * stylesheet's muted secondary colour — on the accent palette that lands
 * between 1.0:1 and 1.5:1 against its own fill, invisible for the greyer
 * swatches. Pure data with no Android imports, so the contract is reachable
 * from both the view builders ([BadgeChips]) and the HTML builder without
 * either depending on the other.
 */
class MetaChipColors(val fill: Int, val text: Int)

/**
 * The two candidate inks for a label sitting ON an accent fill: the app's
 * `pt_dark_text_on_accent` and `pt_light_text_on_accent`, resolved by
 * [BadgeChips.onAccentInk].
 *
 * Both are passed, not one, because the winner is a property of the ACCENT,
 * not of the theme. The per-dictionary swatches are all mid-to-light pastels
 * designed to sit on a dark UI, so in light theme a theme-chosen ink is the
 * white one — white on Lime `#AACF5B` is 1.78:1, unreadable. Picking by
 * measured contrast gives the same near-black ink in both themes and makes
 * the chip legible in each.
 */
class OnAccentInk(val dark: Int, val light: Int)

/**
 * The frequency chip's colour contract for every surface that draws one.
 *
 * A per-dictionary accent override ([accentColor], ARGB) makes the chip a
 * FILLED pill: the accent becomes the fill and the text takes whichever of
 * [ink]'s two candidates contrasts better against that fill. Without an
 * override the chip stays the neutral data chip — [chipFill] behind
 * [mutedText] — because frequency is information, not a highlight.
 *
 * [chipFill] is the surface's own neutral chip fill (ptCard on the lens
 * panel, ptSurface on the card surfaces). It deliberately no longer doubles
 * as the knockout ink: that coupling read as "knocked out of the fill" and
 * happened to work only because the dark palette's chip fills are near-black.
 */
fun freqChipColors(
    accentColor: Int?,
    chipFill: Int,
    mutedText: Int,
    ink: OnAccentInk,
): MetaChipColors =
    if (accentColor != null) accentChipColors(accentColor, ink)
    else MetaChipColors(fill = chipFill, text = mutedText)

/** The override half of [freqChipColors], for renderers that own their own
 *  neutral treatment and only need the tinted case — the styled document,
 *  whose untinted chip is a stylesheet wash rather than a surface fill. */
fun accentChipColors(accentColor: Int, ink: OnAccentInk): MetaChipColors =
    MetaChipColors(fill = accentColor, text = onAccentInkFor(accentColor, ink))

/** Whichever of [ink]'s candidates reads better on [fill]. Measured rather
 *  than thresholded, so it stays correct if the palette gains a dark swatch
 *  or a dictionary carries an imported accent from outside the picker. */
internal fun onAccentInkFor(fill: Int, ink: OnAccentInk): Int =
    if (contrastRatio(fill, ink.dark) >= contrastRatio(fill, ink.light)) ink.dark else ink.light

/** WCAG 2.x contrast ratio between two opaque colours (alpha ignored). */
internal fun contrastRatio(a: Int, b: Int): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

/** WCAG 2.x relative luminance of an ARGB colour's RGB. */
internal fun relativeLuminance(color: Int): Double {
    fun ch(v: Int): Double {
        val c = v / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * ch((color shr 16) and 0xFF) +
        0.7152 * ch((color shr 8) and 0xFF) +
        0.0722 * ch(color and 0xFF)
}
