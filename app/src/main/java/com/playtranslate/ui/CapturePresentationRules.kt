package com.playtranslate.ui

import com.playtranslate.OverlayMode

/**
 * When the results page puts a capture's presentation up on the game display
 * by itself (two screens): on the edge where a state with boxes replaces one
 * without, the capture's overlay mode paints something, and nothing is up
 * yet. The capture's mode is the one it asked for itself
 * ([OnScreenBoxes.modeOverride], tap-to-lookup's Definitions), else the
 * remembered one ([rememberedMode]). A new capture always hops through a
 * boxless status first, so the edge is the capture arriving; the page's
 * render funnel replaying the same boxes (a STARTED replay after the window
 * was taken down in onStop) has no edge, so a window the user tapped away
 * stays down until they ask again.
 */
internal fun presentsCaptureOnEdge(
    previousBoxes: OnScreenBoxes?,
    nextBoxes: OnScreenBoxes?,
    shownNow: Boolean,
    rememberedMode: OverlayMode,
): Boolean = nextBoxes != null && previousBoxes == null && !shownNow &&
    (nextBoxes.modeOverride ?: rememberedMode) != OverlayMode.NONE
