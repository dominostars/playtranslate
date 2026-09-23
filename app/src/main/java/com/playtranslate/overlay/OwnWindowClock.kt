package com.playtranslate.overlay

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import kotlinx.coroutines.delay


/**
 * Uptime of this app's most recent WINDOW EVENT — anything we do that
 * changes what SurfaceFlinger reports to system_server's accessibility
 * window observer: every add, update (move, resize, flag, alpha) and remove
 * through the app's only WindowManager ([TickingWindowManager], see
 * [OwnWindows]), every layout pass of an overlay root ([track]), and one of
 * our activities starting or stopping ([install]).
 *
 * Why it exists (AYN Thor firmware, field bug report 2026-09-20; see
 * docs/ayn-thor-displaymanager-deadlock-report.md and
 * [com.playtranslate.capture.DisplayServiceGuard]): the vendor's
 * DisplayPowerController constructor calls the client-side DisplayManager
 * while the display service lock is held, so any in-process display-info
 * query that lands inside our `createVirtualDisplay` deadlocks system_server
 * (watchdog kill, runtime restart). The query that lands there in practice
 * is `AccessibilityController.computeChangedWindows` → `Display.getRealSize`,
 * run by `AccessibilityWindowsPopulator`: IMMEDIATELY when the visible-window
 * set on a display changes (an add, a remove, an alpha-0 blank), and 35 ms
 * after the last change when only geometry moved (a drag, a resize, a flag
 * flip), under a 500 ms forced-notify cap while changes keep coming. Our
 * pre-capture blank is a set change, and the first capture after consent
 * used to blank, wait two vsyncs, promote the foreground service and only
 * then create the VirtualDisplay: the observer's run landed on the create
 * every time the scheduler lined them up.
 *
 * The consumer is [com.playtranslate.capture.MediaProjectionController]: on
 * the affected firmware it creates its VirtualDisplay only once nothing of
 * ours has touched a window for [QUIET_MS] (via [awaitQuiet]), and the
 * clean-capture path establishes that display BEFORE it blanks anything.
 * [QUIET_MS] covers the observer's worst case after one of our events — the
 * 500 ms forced-notify cap plus the 35 ms stable timer, for a window whose
 * enter or exit animation keeps transforms moving — with margin. A hold is
 * bounded by [MAX_WAIT_MS], and when churn outlasts it the hold REFUSES
 * rather than releases: creating right after an event is the exact overlap
 * the hold exists to avoid, so the capture fails loudly and the caller
 * retries or re-arms. One hold per consent: the VirtualDisplay lives until
 * Turn Off.
 *
 * Distinct from [WindowChurnGate]'s add-only clock, whose quiet gap must NOT
 * be reset by removals (a deferred destroy resetting it would re-synchronise
 * with the next add). This clock counts every event; the two answer different
 * firmware bugs and stay separate.
 *
 * Every write happens on the main thread, like the window mutations that
 * cause them; [awaitQuiet] suspends on the caller's dispatcher.
 */
object OwnWindowClock {

    /** Own-window silence a VirtualDisplay creation must see first. */
    const val QUIET_MS = 550L

    /** Ceiling on a hold; past it the hold refuses (see [decide]). */
    const val MAX_WAIT_MS = 2_000L

    /** [lastEventUptime] before any event: never held. */
    const val NO_EVENT = -1L

    @Volatile private var lastEventUptime = NO_EVENT

    /** Injection seam for tests; production reads the uptime clock. */
    internal var now: () -> Long = { SystemClock.uptimeMillis() }

    /** Test seam: forget every event. */
    internal fun reset() {
        lastEventUptime = NO_EVENT
    }

    /** Record one of our window events (see the class doc for the list). */
    fun tick() {
        lastEventUptime = now()
    }

    /** Milliseconds since the last event, or null when there has been none —
     *  the paper-trail datum the VirtualDisplay creation logs. */
    fun sinceLastEventMs(): Long? =
        lastEventUptime.takeIf { it != NO_EVENT }?.let { now() - it }

    private val layoutTick = ViewTreeObserver.OnGlobalLayoutListener { tick() }

    /**
     * Follow [view]'s window for the rest of its life through its
     * global-layout listener. That fires on a view LAYOUT pass: the root's
     * visibility changing, a WRAP_CONTENT window re-laying out as its
     * content grows, a size change — each a relayout the window manager
     * forwards to SurfaceFlinger. It does NOT fire for a setLayoutParams
     * position, flag or alpha change (no view layout happens); those are
     * recorded synchronously by [TickingWindowManager], the only
     * WindowManager this app holds (see [OwnWindows]). Called from
     * [WindowChurnGate.noteWindowAdded], which that manager runs on every
     * successful add. A layout pass that changed nothing SurfaceFlinger
     * sees only lengthens a hold. The listener lives and dies with the
     * view's observer; the null-safe call is for stub Views in unit tests.
     */
    fun track(view: View) {
        view.viewTreeObserver?.addOnGlobalLayoutListener(layoutTick)
    }

    /** What [awaitQuiet] does next. */
    sealed interface Decision {
        /** Quiet for [QUIET_MS] (or never an event): create now. */
        object Quiet : Decision
        /** The cap passed with an event still fresh: do NOT create. */
        object Refuse : Decision
        /** Sleep this long, then decide again. */
        data class Sleep(val ms: Long) : Decision
    }

    /**
     * Pure hold rule. Quiet wins over the cap when both hold at once, so a
     * hold whose last sleep ends exactly at the cap with the quiet span just
     * elapsed releases; an event inside the final quiet span refuses. While
     * neither holds, the next sleep is the shorter of the two remaining
     * spans, so a re-tick during the hold extends it only up to the cap.
     */
    fun decide(now: Long, lastEventUptime: Long, waitStartedAt: Long): Decision {
        if (lastEventUptime == NO_EVENT) return Decision.Quiet
        val untilQuiet = lastEventUptime + QUIET_MS - now
        if (untilQuiet <= 0L) return Decision.Quiet
        val untilCap = waitStartedAt + MAX_WAIT_MS - now
        if (untilCap <= 0L) return Decision.Refuse
        return Decision.Sleep(minOf(untilQuiet, untilCap))
    }

    /** Outcome of a hold: how long it lasted, and whether it ended in quiet
     *  ([quiet] true) or at the cap with churn still fresh (false). */
    class HoldResult(val heldMs: Long, val quiet: Boolean)

    /**
     * Suspend until no event has happened for [QUIET_MS], or refuse once
     * [MAX_WAIT_MS] has passed since this call began with an event still
     * inside the quiet span. An event DURING the hold extends it (the
     * observer's run for that event is what the hold is for). [sleep] is the
     * test seam; production delays on the calling dispatcher.
     */
    suspend fun awaitQuiet(sleep: suspend (Long) -> Unit = { delay(it) }): HoldResult {
        val start = now()
        while (true) {
            when (val d = decide(now(), lastEventUptime, start)) {
                Decision.Quiet -> return HoldResult(now() - start, quiet = true)
                Decision.Refuse -> return HoldResult(now() - start, quiet = false)
                is Decision.Sleep -> sleep(d.ms)
            }
        }
    }

    private var app: Application? = null

    /** Tick on every activity transition that brackets one of our activity
     *  windows appearing or disappearing — the consent trampoline
     *  ([com.playtranslate.capture.MediaProjectionConsentActivity]) finishing
     *  right before the first capture is the case the field trace shows. */
    fun install(app: Application) {
        if (this.app === app) return
        this.app = app
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = tick()
            override fun onActivityResumed(activity: Activity) = tick()
            override fun onActivityPaused(activity: Activity) = tick()
            override fun onActivityStopped(activity: Activity) = tick()
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
