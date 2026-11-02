package com.playtranslate.ui

import com.playtranslate.OverlayMode

/**
 * When the results page puts a capture's presentation up on the game display
 * by itself (two screens): on the edge where a state with boxes replaces one
 * without, the capture's overlay mode paints something, and nothing is up
 * yet. A new capture always hops through a boxless status first, so the
 * edge is the capture arriving; the page's render funnel replaying the same
 * boxes (a STARTED replay after the window was taken down in onStop) has no
 * edge, so a window the user tapped away stays down until they ask again.
 */
internal fun presentsCaptureOnEdge(
    previousBoxes: Any?,
    nextBoxes: Any?,
    shownNow: Boolean,
    mode: OverlayMode,
): Boolean = nextBoxes != null && previousBoxes == null && !shownNow && mode != OverlayMode.NONE
