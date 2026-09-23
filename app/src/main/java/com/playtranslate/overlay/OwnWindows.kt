package com.playtranslate.overlay

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.view.Choreographer
import android.view.SurfaceControl
import android.view.SurfaceControlInputReceiver
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowMetrics
import android.window.InputTransferToken
import android.window.TrustedPresentationThresholds
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor
import java.util.function.Consumer
import java.util.function.IntConsumer

/**
 * The only way this app obtains a [WindowManager]. Every instance handed
 * out is a [TickingWindowManager], so every window this process adds,
 * updates or removes — through any code path, present or future — records
 * itself on [OwnWindowClock] and, for adds, on [WindowChurnGate]. The
 * guarantee lives at acquisition, not at call sites: WindowManagerAcquisitionTest
 * fails the build on any `getSystemService(WindowManager::class.java)`,
 * `WINDOW_SERVICE` or `.windowManager` outside this file, so a new call
 * site cannot bypass it by construction.
 *
 * Why (AYN Thor firmware, see [OwnWindowClock]): a display-info query that
 * lands inside our `createVirtualDisplay` deadlocks system_server, and the
 * accessibility observer issues one every time a window of ours appears,
 * disappears, moves, resizes, changes flags or alpha. The clock has to see
 * ALL of those; earlier cuts covered them verb by verb (adds by convention,
 * updates by a funnel, removes only through the churn gate) and each review
 * found the verb that was missed. Wrapping the manager covers the three
 * mutating verbs at once.
 *
 * Residual, by design: windows the FRAMEWORK creates inside our activities
 * (Dialog, PopupWindow, Toast, the IME) never touch our WindowManager. The
 * activity lifecycle ticks cover an activity coming and going; a dialog
 * opened inside one before the eager session has come up is not seen. On
 * the guarded firmware the session is normally ready within a second of
 * consent, before a user can reach one.
 */
object OwnWindows {

    /** The ticking manager for [context]'s window service — a display or
     *  window context yields that display's manager, an [Activity] its own
     *  (Activity routes WINDOW_SERVICE to its window's manager). Null only
     *  where the platform has no window service. */
    fun manager(context: Context): WindowManager? =
        context.getSystemService(WindowManager::class.java)?.let { TickingWindowManager(it) }

    /** The ticking manager for [activity]'s own window; never null. */
    fun managerOf(activity: Activity): WindowManager =
        TickingWindowManager(activity.windowManager)
}

/**
 * A [WindowManager] that delegates every mutation and then records it on
 * [OwnWindowClock] — and, for an add, on [WindowChurnGate], which also
 * starts following the view's layout passes. Every read is forwarded to
 * the delegate untouched — explicitly, see the note above the reads.
 *
 * Recorded AFTER delegation, so a call that throws (view not attached,
 * add refused) records nothing: no window changed. The platform sees the
 * change later than the call returns: an update or a plain remove at the
 * main thread's next traversal, an add's surface at its first draw, an
 * immediate remove at the window manager's next transaction — and the
 * accessibility observer reacts to what SurfaceFlinger commits, not to the
 * call. So each mutation is recorded twice: once when the call returns,
 * and again two Choreographer frames on, the churn gate's composited
 * signal, so the clock marks the traversal that submits the change rather
 * than the call that requests it (Codex native review 2026-09-22). Two
 * frames of extra hold at most, and only before a session exists.
 */
class TickingWindowManager(private val delegate: WindowManager) : WindowManager by delegate {

    override fun addView(view: View, params: ViewGroup.LayoutParams) {
        delegate.addView(view, params)
        WindowChurnGate.noteWindowAdded(view)
        recordCommitted()
    }

    override fun updateViewLayout(view: View, params: ViewGroup.LayoutParams) {
        delegate.updateViewLayout(view, params)
        recordMutation()
    }

    override fun removeView(view: View) {
        delegate.removeView(view)
        recordMutation()
    }

    override fun removeViewImmediate(view: View) {
        delegate.removeViewImmediate(view)
        recordMutation()
    }

    private fun recordMutation() {
        OwnWindowClock.tick()
        recordCommitted()
    }

    /** Tick again once the change has had two frames to reach the window
     *  manager and SurfaceFlinger. Best effort: a thread without a
     *  Choreographer (or the unit-test stubs) just keeps the immediate
     *  tick. */
    private fun recordCommitted() {
        runCatching {
            val choreographer = Choreographer.getInstance()
            choreographer.postFrameCallback {
                choreographer.postFrameCallback { OwnWindowClock.tick() }
            }
        }
    }

    // ── Reads: forwarded EXPLICITLY ──────────────────────────────────────
    //
    // `by delegate` forwards only the interface's ABSTRACT members. Every
    // Java `default` method below falls through to the interface's own body
    // unless overridden here — and WindowManager's defaults for the metrics
    // getters THROW UnsupportedOperationException. The first cut of this
    // wrapper forwarded nothing here, so every currentWindowMetrics query in
    // the app threw, displaySizePx fell back to the inset-excluded metrics,
    // and every overlay came up short by the navigation bar (2026-09-23
    // regression). TickingWindowManagerTest now asserts by reflection that
    // this class declares every method the interface does, so a compileSdk
    // bump that adds a default cannot silently reopen this.

    @RequiresApi(Build.VERSION_CODES.R)
    override fun getCurrentWindowMetrics(): WindowMetrics = delegate.currentWindowMetrics

    @RequiresApi(Build.VERSION_CODES.R)
    override fun getMaximumWindowMetrics(): WindowMetrics = delegate.maximumWindowMetrics

    @RequiresApi(Build.VERSION_CODES.S)
    override fun isCrossWindowBlurEnabled(): Boolean = delegate.isCrossWindowBlurEnabled

    @RequiresApi(Build.VERSION_CODES.S)
    override fun addCrossWindowBlurEnabledListener(listener: Consumer<Boolean>) =
        delegate.addCrossWindowBlurEnabledListener(listener)

    @RequiresApi(Build.VERSION_CODES.S)
    override fun addCrossWindowBlurEnabledListener(executor: Executor, listener: Consumer<Boolean>) =
        delegate.addCrossWindowBlurEnabledListener(executor, listener)

    @RequiresApi(Build.VERSION_CODES.S)
    override fun removeCrossWindowBlurEnabledListener(listener: Consumer<Boolean>) =
        delegate.removeCrossWindowBlurEnabledListener(listener)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun registerTrustedPresentationListener(
        window: IBinder,
        thresholds: TrustedPresentationThresholds,
        executor: Executor,
        listener: Consumer<Boolean>,
    ) = delegate.registerTrustedPresentationListener(window, thresholds, executor, listener)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun unregisterTrustedPresentationListener(listener: Consumer<Boolean>) =
        delegate.unregisterTrustedPresentationListener(listener)

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun addProposedRotationListener(executor: Executor, listener: IntConsumer) =
        delegate.addProposedRotationListener(executor, listener)

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun removeProposedRotationListener(listener: IntConsumer) =
        delegate.removeProposedRotationListener(listener)

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun addScreenRecordingCallback(executor: Executor, callback: Consumer<Int>): Int =
        delegate.addScreenRecordingCallback(executor, callback)

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun removeScreenRecordingCallback(callback: Consumer<Int>) =
        delegate.removeScreenRecordingCallback(callback)

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun registerBatchedSurfaceControlInputReceiver(
        hostInputTransferToken: InputTransferToken,
        surfaceControl: SurfaceControl,
        choreographer: Choreographer,
        receiver: SurfaceControlInputReceiver,
    ): InputTransferToken = delegate.registerBatchedSurfaceControlInputReceiver(
        hostInputTransferToken, surfaceControl, choreographer, receiver,
    )

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun registerUnbatchedSurfaceControlInputReceiver(
        hostInputTransferToken: InputTransferToken,
        surfaceControl: SurfaceControl,
        looper: Looper,
        receiver: SurfaceControlInputReceiver,
    ): InputTransferToken = delegate.registerUnbatchedSurfaceControlInputReceiver(
        hostInputTransferToken, surfaceControl, looper, receiver,
    )

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun unregisterSurfaceControlInputReceiver(surfaceControl: SurfaceControl) =
        delegate.unregisterSurfaceControlInputReceiver(surfaceControl)

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun transferTouchGesture(
        transferFromToken: InputTransferToken,
        transferToToken: InputTransferToken,
    ): Boolean = delegate.transferTouchGesture(transferFromToken, transferToToken)
}
