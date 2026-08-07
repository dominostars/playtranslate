package com.playtranslate

/**
 * What the live loop last DELIVERED to the in-app panel, per capture
 * display, recorded by the delivery layer
 * ([CaptureService.translateAndSendToPanel]) and nowhere else. Live
 * furigana offers every settled frame; this record makes the offers
 * idempotent, replacing the mode-side "lastEmittedText" mirror whose five
 * defect rounds all shared one shape: freshness metadata detached from the
 * truth it described.
 *
 * The rules, each pinned by a prior bug (tests carry the same names):
 *  - COMMIT ONLY ON SCREEN-DERIVED DELIVERY. Only
 *    [CaptureService.translateAndSendToPanel] commits — its callers all
 *    deliver what the capture display currently shows. Deliberate flows
 *    (re-translate, deferred history items) emit results whose text is NOT
 *    the live screen; they leave the record alone, so the record keeps
 *    matching the screen, live offers stay deduped, and the deliberate
 *    result persists until the screen really changes (committing those
 *    emissions let the live loop stomp them one cycle later). An offer
 *    that dies at the visibility gate commits nothing, so a panel opened
 *    later still receives the settled result on the next offer.
 *  - PER DISPLAY. Two live displays feeding one panel must each dedup
 *    against their OWN last delivery — a single slot made them read each
 *    other's commits as new content and re-emit every cycle.
 *  - CLEAR ON EVERY NON-RESULT TRANSITION, all displays. No-text, error,
 *    idle, live start: the panel no longer shows any recorded text, so
 *    identical text reappearing is NEW.
 *  - GROWTH IS CONTENT, SHRINK IS JITTER. A longer candidate always
 *    delivers (a completion whose tail sits inside the bag-of-chars
 *    tolerance must not be swallowed — the stuck-partial bug). A shorter
 *    candidate delivers only on a significant change: typewriters never
 *    shrink, so a small shrink is an OCR glyph dropout, and delivering it
 *    would flap the panel with a truncated read and then the repair.
 *  - A LANGUAGE CHANGE INVALIDATES. Same text, new source/target pair →
 *    new translation wanted.
 */
internal class LivePanelRecord {

    private class Entry(val text: String, val langStamp: String)

    private val byDisplay = HashMap<Int, Entry>()

    /** Would delivering [candidate] from [displayId] (under [stamp], a
     *  source>target key) change what the panel shows? */
    fun isNew(displayId: Int, candidate: String, stamp: String): Boolean {
        val e = byDisplay[displayId] ?: return true
        if (stamp != e.langStamp) return true
        if (candidate.length > e.text.length) return true
        return OverlayToolkit.isSignificantChange(e.text, candidate)
    }

    /** A screen-derived result from [displayId] was actually emitted. */
    fun committed(displayId: Int, delivered: String, stamp: String) {
        byDisplay[displayId] = Entry(delivered, stamp)
    }

    /** The panel left its Result state (no-text, error, idle, searching). */
    fun clear() {
        byDisplay.clear()
    }
}
