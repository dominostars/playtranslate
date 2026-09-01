package com.playtranslate.overlay

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.WindowManager

/**
 * Serializes this app's overlay-window destruction away from its overlay-window
 * creation, because the AYN Thor firmware can crash the whole device when the
 * two coincide.
 *
 * The vendor build patches `WindowState.setHasSurface` to post an async walk
 * over every visible window that calls `SurfaceControl.getContentFrameStats()`
 * on each — with no tolerance for a surface released between enumeration and
 * the call (`CollectWindowContentFrameStatsUtils`, absent from AOSP). A window
 * we remove ~1 ms after adding another lands its surface release inside that
 * walk and NPEs system_server: full runtime restart, game killed
 * (docs/ayn-thor-system-server-crash-report.md, captured 2026-08-29). Every
 * surface CREATION arms the walk; a release is only dangerous NEAR one — so
 * the mitigation is temporal separation of our own adds and removes, not a
 * plain removal delay. A fixed delay alone could re-synchronize deferred
 * releases with the next live-cycle's adds and buy nothing.
 *
 * Contract, per removal routed through [removeWindow]:
 *  - DEFUSED synchronously: window alpha → 0 (SurfaceFlinger stops compositing
 *    it — invisible by the same mechanism clean-capture blanking trusts) and
 *    NOT_TOUCHABLE + NOT_FOCUSABLE set, WATCH_OUTSIDE_TOUCH cleared, so the
 *    ghost can't eat taps, hold focus/back-dispatch, or fire outside-touch
 *    callbacks during its linger. User-visibly the window is gone at the same
 *    instant it always was. Until the alpha-0 provably composites (two
 *    Choreographer frames), the ghost arms
 *    [OverlayHost.OverlayState.awaitRepaint] via [hasUncompositedGhostOn],
 *    so a clean capture racing the removal waits for a post-defuse frame
 *    instead of taking its no-blank fast path — the ghost is unregistered
 *    and the blanking registry alone can't see it.
 *  - DESTROYED (the real [WindowManager.removeView]) once no add of ours
 *    happened for [QUIET_GAP_MS] — every [noteWindowAdded] pushes pending
 *    destroys back — but never later than [MAX_LINGER_MS] after submission.
 *    The cap bounds ghost lifetime under continuous churn; a cap-forced
 *    destroy near an add is exactly today's (pre-gate) behavior, so the gate
 *    can only remove risk, never add it.
 *
 * This shrinks the race, it cannot close it: another PROCESS creating a window
 * while our deferred destroy fires is the same firmware race, and our own adds
 * are never delayed (they are user-facing latency). We are overwhelmingly the
 * dominant window churner while live mode runs, and the captured self-swap
 * shape is eliminated by construction.
 *
 * Params are mutated to defuse and restored (alpha/flags) after destruction,
 * so a params object that outlives its window is left as its owner last set
 * it. [flushPendingFor] is a correctness BELT for same-view re-adds (a view
 * with a pending gated destroy would make `addView` throw "view already
 * attached") — but note its destroy is synchronous and therefore
 * release-adjacent to the caller's add, the exact shape this gate exists to
 * prevent. NO current caller re-adds a removed view (the icon re-raise, the
 * one path that used to, now REPLACES its view — see
 * [com.playtranslate.OverlayUiController.bringFloatingIconsToFront]); any
 * new code that re-adds a same view reopens that hole and should replace
 * instead.
 *
 * Main-thread only, like every WindowManager mutation in this app.
 */
object WindowChurnGate {

    /** Our-adds-quiet window a destroy must see before it runs. Padding for
     *  the vendor walk's post-to-run latency: 16 ms observed at load-avg 8 in
     *  the captured crash; 400 ms is generous slack for a busier queue. */
    const val QUIET_GAP_MS = 400L

    /** Hard ceiling on a ghost window's linger, so continuous add churn
     *  (live mode never quiet for [QUIET_GAP_MS]) can't accumulate defused
     *  surfaces — each holds real GPU buffers. */
    const val MAX_LINGER_MS = 2000L

    /** Failure bound on [Pending.composited]: past this age a ghost's alpha-0
     *  is assumed committed even if the Choreographer chain never fired (a
     *  stalled main thread stalls captures too). The structural signal — two
     *  observed frames — is the real gate; this timer only bounds its
     *  failure, and it is ~10x the 2-vsync trust the capture paths already
     *  place in commit latency. */
    const val COMPOSITE_TIMEOUT_MS = 500L

    /** [removeWindow]'s displayId when the caller can't know it (the
     *  no-service fallback removal path). Conservatively matches every
     *  display in [hasUncompositedGhostOn]. */
    const val DISPLAY_UNKNOWN = -1

    /** [Pending.defuseDeliverySeq] when no [deliverySeqClock] was installed
     *  at defuse time. */
    const val SEQ_UNKNOWN = -1L

    /**
     * Mirror-delivery sequence clock, installed by the MediaProjection
     * capture source while its projection lives and cleared on its destroy.
     * The gate only ever READS one opaque Long per defuse — it stays
     * backend-generic; the meaning of the number belongs to the installer.
     * Read on the main thread inside [removeWindow].
     */
    var deliverySeqClock: (() -> Long)? = null

    private class Pending(
        val view: View,
        val wm: WindowManager,
        val params: WindowManager.LayoutParams,
        /** The alpha to write back to [params] after destruction. Snapshotted
         *  at defuse — which can capture a CAPTURE-BLANKED 0 rather than the
         *  window's true alpha when the removal landed mid-clean-capture;
         *  [correctSnapshotAlpha] overwrites it with the pre-blank truth from
         *  the capture's own snapshot when its restore skips this ghost. */
        var originalAlpha: Float,
        val originalFlags: Int,
        /** Also the defuse moment — the alpha-0 updateViewLayout is submitted
         *  synchronously inside [removeWindow] before this is stamped. */
        val submittedAtUptime: Long,
        val displayId: Int,
        /** Whether the window was actually composited (alpha > 0) when the
         *  defuse hit it. IMMUTABLE — unlike [originalAlpha], which
         *  [correctSnapshotAlpha] may rewrite later. False means the window
         *  was already capture-blanked at removal: its absence from frames
         *  was committed by the blank's own earlier repaint, and the defuse
         *  (alpha 0 → 0) produces NO new composition — so such a ghost must
         *  never arm a capture wait, which would starve on a repaint that
         *  is not coming. */
        val visibleAtDefuse: Boolean,
        /** [deliverySeqClock] read BEFORE the defuse's updateViewLayout was
         *  submitted — so a visible-at-defuse ghost's repaint is guaranteed
         *  to be DELIVERED strictly above this value. [SEQ_UNKNOWN] when no
         *  clock was installed at defuse time. */
        val defuseDeliverySeq: Long,
    ) {
        /** True once two Choreographer frames have passed since the defuse —
         *  the alpha-0 has reached composition under the same trust model as
         *  every waitVsync(2) in the capture paths. Counted in actual frames,
         *  not wall time, so it stretches with load exactly when the commit
         *  does. */
        var composited = false
    }

    private val pending = mutableListOf<Pending>()
    private var lastAddUptime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val destroyDueRunnable = Runnable { destroyDue() }

    /** Record one of our window creations. Call after every successful
     *  [WindowManager.addView] this app performs, whichever path adds it. */
    fun noteWindowAdded() {
        lastAddUptime = SystemClock.uptimeMillis()
        if (pending.isNotEmpty()) reschedule()
    }

    /**
     * Remove [view]'s window: defuse now, destroy in the next quiet gap.
     * [params] must be the LIVE params object the window was added/last
     * updated with — the defuse mutates it through
     * [WindowManager.updateViewLayout]. [displayId] scopes the ghost for
     * [hasUncompositedGhostOn]; pass [DISPLAY_UNKNOWN] when unknowable.
     *
     * Falls back to a direct synchronous remove when the window can't be
     * defused (never attached, already removed): an undefused deferral would
     * leave a visible, interactive ghost, which is worse than today's timing.
     */
    fun removeWindow(
        view: View,
        wm: WindowManager,
        params: WindowManager.LayoutParams,
        displayId: Int,
    ) {
        if (pending.any { it.view === view }) return // double-remove; first submission owns it
        // Anchor BEFORE the defuse below is submitted: the repaint the defuse
        // produces must land above this value for the anchor proof to hold.
        val defuseSeq = deliverySeqClock?.invoke() ?: SEQ_UNKNOWN
        val originalAlpha = params.alpha
        val originalFlags = params.flags
        params.alpha = 0f
        params.flags = (params.flags
            or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) and
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
        try {
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {
            params.alpha = originalAlpha
            params.flags = originalFlags
            try { wm.removeView(view) } catch (_: Exception) {}
            return
        }
        val p = Pending(
            view, wm, params, originalAlpha, originalFlags,
            SystemClock.uptimeMillis(), displayId,
            visibleAtDefuse = originalAlpha != 0f,
            defuseDeliverySeq = defuseSeq,
        )
        pending += p
        // Two observed frames = the defuse's alpha-0 has been composited
        // (fires harmlessly late for a Pending already flushed/destroyed).
        Choreographer.getInstance().postFrameCallback {
            Choreographer.getInstance().postFrameCallback { p.composited = true }
        }
        reschedule()
    }

    /**
     * True while a defused ghost on [displayId] may still be composited into
     * the next frame — it was VISIBLE when defused ([Pending.visibleAtDefuse])
     * and its two post-defuse Choreographer frames haven't passed (bounded by
     * [COMPOSITE_TIMEOUT_MS]). THE input that keeps
     * [OverlayHost.prepareForCleanCapture]'s no-blank fast path honest: an
     * unregistered ghost is invisible to the blanking registry, so without
     * this bit a capture racing a just-dismissed window could serve a frame
     * from before the defuse committed (the floating-menu dim/hint in live
     * mode's first frame — the bug removeViewImmediate used to guard).
     *
     * A ghost that was already capture-blanked at removal never counts: every
     * frame since its blank's repaint already excludes it, and its defuse
     * composites nothing new — arming a wait on it starves the wait.
     */
    fun hasUncompositedGhostOn(displayId: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        return pending.any {
            ghostBlocksFastPath(
                now, it.submittedAtUptime, it.composited, it.visibleAtDefuse,
                it.displayId, displayId,
            )
        }
    }

    /**
     * The freshness anchor for a ghost-only clean capture on [displayId]:
     * the LATEST [Pending.defuseDeliverySeq] among still-blocking ghosts.
     * Every blocking ghost was visible at defuse, so its repaint is
     * guaranteed to be delivered above its own anchor — waiting for a frame
     * newer than the latest anchor therefore terminates AND carries the same
     * take-newest tolerance the blank branch ships with. Null when no ghost
     * blocks anymore (composited meanwhile — the current frame is fine) or
     * when any blocking ghost predates the clock's installation
     * ([SEQ_UNKNOWN] — near-unreachable during a capture: projection startup
     * takes far longer than [COMPOSITE_TIMEOUT_MS], so a pre-projection
     * ghost has always composited by the first capture; callers keep a
     * heuristic fallback for it).
     */
    fun ghostDefuseAnchorOn(displayId: Int): Long? {
        val now = SystemClock.uptimeMillis()
        return selectGhostAnchor(
            pending.filter {
                ghostBlocksFastPath(
                    now, it.submittedAtUptime, it.composited, it.visibleAtDefuse,
                    it.displayId, displayId,
                )
            }.map { it.defuseDeliverySeq }
        )
    }

    /**
     * Overwrite [view]'s pending ghost's restore-alpha with [alpha] — the
     * window's true pre-blank alpha, known only to the clean-capture snapshot
     * that blanked it. Called by [OverlayHost.restoreAfterCapture] when its
     * restore skips a window that was removed mid-capture: the defuse-time
     * snapshot saw the blanked 0, and without this correction the deferred
     * destroy would write that 0 back into a params object a later re-add
     * may reuse (an invisible re-added window). No-op when [view] has no
     * pending ghost (already destroyed — the caller's own in-memory params
     * write, which runs after any destroy, covers that ordering).
     */
    fun correctSnapshotAlpha(view: View, alpha: Float) {
        pending.firstOrNull { it.view === view }?.originalAlpha = alpha
    }

    /**
     * Synchronously destroy [view]'s pending ghost, restoring its params, so
     * the view can be re-added immediately. Returns false if [view] has no
     * pending destroy. MUST be called before any `addView` of a view that may
     * have passed through [removeWindow] — [OverlayHost.addOverlayWindow] and
     * [com.playtranslate.PlayTranslateAccessibilityService.addOverlay] do.
     */
    fun flushPendingFor(view: View): Boolean {
        val p = pending.firstOrNull { it.view === view } ?: return false
        pending.remove(p)
        destroy(p, immediate = true)
        if (pending.isEmpty()) handler.removeCallbacks(destroyDueRunnable)
        return true
    }

    private fun destroyDue() {
        val now = SystemClock.uptimeMillis()
        val due = pending.filter { destroyEligible(now, lastAddUptime, it.submittedAtUptime) }
        pending.removeAll(due)
        for (p in due) {
            if (now - p.submittedAtUptime >= MAX_LINGER_MS && now - lastAddUptime < QUIET_GAP_MS) {
                // Cap-forced destroy with adds still churning — the one case
                // the gate couldn't separate. Worth a paper trail if a crash
                // recurs anyway.
                Log.i(TAG, "cap-forced destroy ${p.view.javaClass.simpleName} " +
                    "(no quiet gap in ${MAX_LINGER_MS}ms)")
            }
            destroy(p, immediate = false)
        }
        if (pending.isNotEmpty()) reschedule()
    }

    private fun destroy(p: Pending, immediate: Boolean) {
        try {
            if (immediate) p.wm.removeViewImmediate(p.view) else p.wm.removeView(p.view)
        } catch (_: Exception) {}
        // Restore AFTER the remove: the ghost must never composite visibly,
        // but a later re-add reusing this params object must see pre-defuse
        // values (the icon bring-to-front path relies on params.alpha).
        p.params.alpha = p.originalAlpha
        p.params.flags = p.originalFlags
    }

    private fun reschedule() {
        handler.removeCallbacks(destroyDueRunnable)
        val oldestSubmit = pending.minOfOrNull { it.submittedAtUptime } ?: return
        handler.postDelayed(
            destroyDueRunnable,
            nextCheckDelayMs(SystemClock.uptimeMillis(), lastAddUptime, oldestSubmit)
        )
    }

    /** True when [submittedAtUptime]'s destroy may run at [now]: the quiet gap
     *  since the last add has elapsed, or the linger cap forces it. Pure —
     *  pinned by unit tests. */
    fun destroyEligible(now: Long, lastAddUptime: Long, submittedAtUptime: Long): Boolean =
        now - lastAddUptime >= QUIET_GAP_MS || now - submittedAtUptime >= MAX_LINGER_MS

    /** Delay until the earliest moment some pending destroy could become
     *  eligible. Pure — pinned by unit tests. */
    fun nextCheckDelayMs(now: Long, lastAddUptime: Long, oldestSubmittedAtUptime: Long): Long =
        minOf(
            lastAddUptime + QUIET_GAP_MS - now,
            oldestSubmittedAtUptime + MAX_LINGER_MS - now,
        ).coerceAtLeast(1L)

    /** Whether one ghost forces a capture off the no-blank fast path: it was
     *  visible when defused (an invisible-at-defuse ghost owes no repaint and
     *  is already absent from every frame), not yet composited, within the
     *  composite failure bound, and on the captured display (or of unknown
     *  display, which conservatively matches all). Pure — pinned by unit
     *  tests. */
    fun ghostBlocksFastPath(
        now: Long,
        defusedAtUptime: Long,
        composited: Boolean,
        visibleAtDefuse: Boolean,
        ghostDisplayId: Int,
        captureDisplayId: Int,
    ): Boolean =
        visibleAtDefuse &&
            !composited &&
            now - defusedAtUptime < COMPOSITE_TIMEOUT_MS &&
            (ghostDisplayId == DISPLAY_UNKNOWN || ghostDisplayId == captureDisplayId)

    /** Anchor selection over blocking ghosts' defuse seqs: the LATEST anchor
     *  (most conservative wait — the newest defuse's guaranteed repaint
     *  covers the older ones' commits), or null when the list is empty or
     *  any anchor is [SEQ_UNKNOWN] (one unprovable ghost makes the whole
     *  proof unavailable). Pure — pinned by unit tests. */
    fun selectGhostAnchor(anchors: List<Long>): Long? =
        if (anchors.isEmpty() || anchors.any { it == SEQ_UNKNOWN }) null
        else anchors.max()

    private const val TAG = "WindowChurnGate"
}
