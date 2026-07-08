package com.playtranslate

import android.os.SystemClock
import android.view.View

/**
 * Debug-only per-window draw-rate meter. [attach] hooks a root view's
 * ViewTreeObserver; every draw pass of that window ticks a named counter,
 * flushed to [DetectionLog] at most once a second per name (and only while
 * draws are actually happening — a quiet window logs nothing).
 *
 * Exists to answer one question from the Step-0 stream characterization:
 * when the mirrored VirtualDisplay reports continuous deliveries on a
 * visually static screen, WHICH window is redrawing, and at what rate.
 * Process-level gfxinfo can't separate our windows, and an occluded window
 * can render without causing compositions, so per-root attribution needs
 * an in-app hook.
 *
 * Draw listeners run on the main thread; no locking. No-op in release
 * builds. OnDrawListeners added before attach survive the ViewTreeObserver
 * merge, so [attach] is safe at window-creation time.
 */
object DrawRateProbe {
    private class Meter(var count: Int = 0, var windowStartMs: Long = 0L)

    private val meters = HashMap<String, Meter>()

    fun attach(view: View, name: String) {
        if (!BuildConfig.DEBUG) return
        view.viewTreeObserver.addOnDrawListener { tick(name) }
    }

    fun tick(name: String) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.uptimeMillis()
        val m = meters.getOrPut(name) { Meter() }
        if (m.windowStartMs == 0L) m.windowStartMs = now
        m.count++
        val elapsed = now - m.windowStartMs
        if (elapsed >= 1000L) {
            DetectionLog.log("DrawRate: $name ${m.count} draws/${elapsed}ms")
            m.count = 0
            m.windowStartMs = now
        }
    }
}
