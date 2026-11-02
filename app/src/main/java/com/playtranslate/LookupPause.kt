package com.playtranslate

/**
 * What an over-game lookup surface paused while it is up: a running
 * auto-translate and the region indicator. Each surface holds one record
 * (every floating icon's drag lens, the tap-to-lookup session), so the way
 * the surface ends decides both together: a close restores them
 * ([settle]), a teardown of capture forgets them ([drop]), and a surface
 * that takes over from another takes its record over ([handTo]) instead of
 * letting it restore, so nothing restarts in between. Built by
 * [OverlayUiController], whose routed start and stop it calls. Main thread.
 */
internal class LookupPause(
    /** Hide the region indicator for the lookup; true when it was showing
     *  and is now hidden ([RegionOverlayController.hideIndicatorForDrag]). */
    private val hideIndicator: () -> Boolean,
    private val restoreIndicator: () -> Unit,
    private val liveRunning: () -> Boolean,
    private val stopLive: () -> Unit,
    private val startLive: () -> Unit,
) {
    /** True while this record stopped auto-translate and owes its restart. */
    var pausedLive = false
        private set
    /** True while this record hid the region indicator and owes its return. */
    var hidIndicator = false
        private set

    /** The surface came up: hide the region indicator, and stop a running
     *  auto-translate when [pauseLive]. Adds to what the record already
     *  holds (a hand-over may have filled it), never clears it. */
    fun begin(pauseLive: Boolean = true) {
        if (hideIndicator()) hidIndicator = true
        if (pauseLive && liveRunning()) {
            pausedLive = true
            stopLive()
        }
    }

    /** The surface closed: the indicator comes back, and auto-translate
     *  restarts when this record stopped it and [resumeLive] says so (false
     *  when what comes next stops it anyway: a capture, a live start). */
    fun settle(resumeLive: Boolean) {
        if (hidIndicator) {
            hidIndicator = false
            restoreIndicator()
        }
        val resume = pausedLive && resumeLive
        pausedLive = false
        if (resume) startLive()
    }

    /** Capture is ending: forget both without acting, since a restart here
     *  would land on a backend on its way out. */
    fun drop() {
        hidIndicator = false
        pausedLive = false
    }

    /** Forget the restart only: a page now covers the game
     *  ([OverlayUiController.cancelLivePauseObligation]). */
    fun cancelResume() {
        pausedLive = false
    }

    /** Another surface takes over: it owes what this record owed, and this
     *  one owes nothing. */
    fun handTo(other: LookupPause) {
        if (other === this) return
        other.pausedLive = other.pausedLive || pausedLive
        other.hidIndicator = other.hidIndicator || hidIndicator
        pausedLive = false
        hidIndicator = false
    }
}
