package com.playtranslate

/**
 * What the in-app panel SHOWS, per capture display, as the key of the last
 * live delivery: (original, translated, backend label). Owned by
 * [CaptureService] beside [LivePanelRecord] and written at the delivery
 * layer only ([CaptureService.emitPanelResult] checks and records;
 * [CaptureService.translateAndSendToPanel] commits). Every emission is a NEW
 * TranslationResult, and the in-app page treats each instance as a new
 * result (word lookups, list rebuild), so an emission identical to what the
 * panel already shows costs that work, plus a fresh timestamp and the
 * screenshot JPEG, for nothing the user can see.
 *
 * The key:
 *  - Reposition-only cycles re-enter with identical text (the reconciler
 *    counts pure scroll drift as a mutation; the pinhole tier's
 *    remove-then-replace flicker re-places the same text). The deleted
 *    In-App-Only mode's whole-text dedup suppressed this.
 *  - The backend label rides the key so the dedup covers the full emitted
 *    payload: an attribution change alone re-emits.
 *  - ocrProvenance stays OUT deliberately: its region rects change on every
 *    reposition and would defeat the dedup's whole purpose. The panel keeps
 *    the provenance of the delivery that last changed the text; that
 *    most-recent staleness is accepted, the same policy as the pinhole
 *    tier's panelProvenance.
 *
 * [LivePanelRecord]'s rules apply, for the same reasons: commit only on
 * screen-derived delivery (deliberate emits through
 * [CaptureService.emitResult] leave it alone), per display (two live
 * displays feeding one panel must each dedup against their OWN last
 * delivery), and clear on every non-result transition. An offer that dies
 * at a tier's visibility gate never reaches this record, so a panel opened
 * later still receives the settled result.
 *
 * Why not a field on each live tier: the panel's state changes in places
 * no tier sees (errors, Idle on a region change or language drift, a hold's
 * delivery or no-text, another display's no-text). A region change
 * refreshes the EXISTING mode instances, so a tier-local key survived it
 * and the panel stayed Idle until the text changed. LivePanelRecord's own
 * history is the same lesson: a mode-side mirror detached from the truth.
 */
internal class PanelEmissionDedup {

    private data class Key(val original: String, val translated: String, val label: String?)

    private val byDisplay = HashMap<Int, Key>()

    /** True, recording the key, when ([texts], [backendLabel]) differs from
     *  what [displayId] last delivered; false, recording nothing, when it is
     *  identical. */
    fun isNew(displayId: Int, texts: OverlayToolkit.PanelTexts, backendLabel: String?): Boolean {
        val key = Key(texts.originalText, texts.translatedText, backendLabel)
        if (byDisplay[displayId] == key) return false
        byDisplay[displayId] = key
        return true
    }

    /** A screen-derived result from [displayId] was delivered by another
     *  path (a furigana offer, a hold): record it unconditionally. */
    fun committed(displayId: Int, originalText: String, translatedText: String, backendLabel: String?) {
        byDisplay[displayId] = Key(originalText, translatedText, backendLabel)
    }

    /** The panel left its Result state (no-text, error, idle, searching). */
    fun clear() {
        byDisplay.clear()
    }
}
