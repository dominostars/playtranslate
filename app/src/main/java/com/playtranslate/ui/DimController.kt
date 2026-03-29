package com.playtranslate.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import java.lang.ref.WeakReference

/**
 * Dims the app screen after a period of inactivity. Any interaction
 * (app touch or floating icon touch) resets the timer and undims.
 *
 * Create when the app should dim on inactivity (not during live mode).
 * Call [cancel] to clean up and undim.
 */
class DimController(overlay: View, private val timeoutMs: Long = 20000L) {

    companion object {
        private var instanceRef: WeakReference<DimController>? = null

        /** Called from AccessibilityService when the floating icon is touched. */
        fun notifyInteraction() { instanceRef?.get()?.onInteraction() }
    }

    private val overlayRef = WeakReference(overlay)
    private val handler = Handler(Looper.getMainLooper())
    private val dimRunnable = Runnable { dim() }
    private var isDimmed = false

    init {
        instanceRef = WeakReference(this)
        resetTimer()
    }

    fun onInteraction() {
        if (isDimmed) undim()
        resetTimer()
    }

    fun cancel() {
        handler.removeCallbacks(dimRunnable)
        undim()
        if (instanceRef?.get() == this) instanceRef = null
    }

    private fun resetTimer() {
        handler.removeCallbacks(dimRunnable)
        handler.postDelayed(dimRunnable, timeoutMs)
    }

    private fun dim() {
        val overlay = overlayRef.get() ?: return
        isDimmed = true
        overlay.visibility = View.VISIBLE
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(600L).start()
    }

    private fun undim() {
        isDimmed = false
        val overlay = overlayRef.get() ?: return
        overlay.animate().cancel()
        overlay.visibility = View.GONE
    }
}
