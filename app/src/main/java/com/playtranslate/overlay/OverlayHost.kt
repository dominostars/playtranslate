package com.playtranslate.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.view.doOnLayout
import com.playtranslate.displaySizePx

/**
 * Owns every overlay window the app paints on a game display — the registry,
 * add/remove, and the clean-capture blanking that hides those windows from
 * screenshots.
 *
 * Extracted from PlayTranslateAccessibilityService so the same machinery backs
 * either capture backend. [windowType] is the only thing that differs: it is
 * `TYPE_ACCESSIBILITY_OVERLAY` when the accessibility service hosts the
 * windows, `TYPE_APPLICATION_OVERLAY` when MediaProjection mode does. Every
 * window added through [addOverlayWindow] is stamped with [windowType], so call
 * sites never pick a backend themselves.
 *
 * Main-thread only — every WindowManager mutation happens on Main.
 */
class OverlayHost(
    private val context: Context,
    val windowType: Int,
) {

    /** Registered overlay window. The stored handle keeps the wm + params so
     *  blanking can flip [WindowManager.LayoutParams.alpha] and call
     *  [WindowManager.updateViewLayout] without each call site managing its
     *  own state. */
    data class OverlayHandle(
        val view: View,
        val wm: WindowManager,
        val params: WindowManager.LayoutParams,
        val displayId: Int,
    )

    private val overlayWindows = mutableListOf<OverlayHandle>()

    /** Per-display 1×1 touch sentinels — see [addTouchSentinel]. */
    private val touchSentinels = mutableMapOf<Int, View>()

    /** One handle's snapshot for restore: the handle plus the alpha it was
     *  at before [prepareForCleanCapture] blanked it. Stored because not
     *  every overlay runs at α=1 — the MediaProjection live-pinhole window
     *  sits at the system obscuring cap (~0.79) with a compensated pinhole
     *  mask tuned to that exact alpha; resetting it to 1.0 on restore would
     *  trip the QTI BSP visual clamp and break the 50/50 blend math. */
    internal data class SavedHandle(
        val handle: OverlayHandle,
        val originalAlpha: Float,
    )

    /** Opaque snapshot returned by [prepareForCleanCapture]. */
    class OverlayState internal constructor(
        internal val saved: List<SavedHandle>
    )

    /**
     * Add a window via [WindowManager.addView] AND register it for
     * clean-capture blanking. The window's type is forced to [windowType], so
     * the caller's params need not pick a backend. Returns true on success.
     *
     * Honors whatever [WindowManager.LayoutParams.alpha] is on [params]. In
     * particular, the floating-icon "bring to front" path removes and re-adds
     * the icon with the same params object — if a clean capture has the icon
     * blanked at alpha=0, the re-added window stays invisible until the
     * capture's restore fires. Forcing alpha=1 here would flash the icon
     * mid-capture and contaminate the bitmap.
     */
    fun addOverlayWindow(
        view: View,
        wm: WindowManager,
        params: WindowManager.LayoutParams,
        displayId: Int,
    ): Boolean {
        params.type = windowType
        applyFullScreenOverlayDefaults(params)
        return try {
            wm.addView(view, params)
            overlayWindows += OverlayHandle(view, wm, params, displayId)
            logOverlayGeometry(view, params, displayId)
            true
        } catch (e: Exception) {
            Log.w(TAG, "addOverlayWindow failed: ${e.message}")
            false
        }
    }

    // One-shot diagnostic: verifies whether MATCH_PARENT overlay windows
    // actually cover the full display — a view origin != display origin or
    // view dims != display dims silently miscalibrates OCR-box overlays.
    // Grep with: adb logcat -s OverlayHost | grep Geometry
    private fun logOverlayGeometry(
        view: View,
        params: WindowManager.LayoutParams,
        displayId: Int,
    ) {
        val fullScreenParams =
            params.width == WindowManager.LayoutParams.MATCH_PARENT &&
                params.height == WindowManager.LayoutParams.MATCH_PARENT
        if (!fullScreenParams) return
        view.doOnLayout {
            val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
            val ds = if (display != null)
                context.createDisplayContext(display).displaySizePx()
            else Point()
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val name = view.javaClass.simpleName.ifBlank { "anon-View@${view.hashCode().toString(16)}" }
            val flagBits = listOfNotNull(
                "NO_LIMITS".takeIf { params.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS != 0 },
                "IN_SCREEN".takeIf { params.flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN != 0 },
                "NOT_TOUCHABLE".takeIf { params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0 },
            ).joinToString("|").ifEmpty { "none" }
            val matches = view.width == ds.x && view.height == ds.y && loc[0] == 0 && loc[1] == 0
            Log.i(
                TAG,
                "[Geometry] $name displayId=$displayId display=${ds.x}x${ds.y} " +
                    "view=${view.width}x${view.height} origin=(${loc[0]},${loc[1]}) " +
                    "matchesDisplay=$matches flags=$flagBits"
            )
        }
    }

    /** Unregister and call [WindowManager.removeView]. Returns true if the
     *  view was registered (and thus removed). Returns false if the view was
     *  never registered — callers that fall back to a direct removeView for
     *  windows added before a host existed rely on this. */
    fun removeOverlayWindow(view: View): Boolean {
        val handle = overlayWindows.firstOrNull { it.view === view } ?: return false
        overlayWindows -= handle
        try { handle.wm.removeView(view) } catch (_: Exception) {}
        return true
    }

    /**
     * Add a 1×1 transparent watcher window on [displayId]. With
     * FLAG_WATCH_OUTSIDE_TOUCH it receives an ACTION_OUTSIDE — running
     * [onOutsideTouch] — for every touch elsewhere on the display, without
     * consuming the event, so the game still gets normal input. The window
     * carries [windowType], so this works on either backend. Idempotent per
     * display.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun addTouchSentinel(displayId: Int, onOutsideTouch: () -> Unit) {
        if (displayId in touchSentinels) return
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(displayId) ?: return
        val displayContext = context.createDisplayContext(display)
        val wm = displayContext.getSystemService(WindowManager::class.java) ?: return
        val view = View(displayContext)
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) onOutsideTouch()
            false
        }
        val params = WindowManager.LayoutParams(
            1, 1,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        )
        if (addOverlayWindow(view, wm, params, displayId)) {
            touchSentinels[displayId] = view
        }
    }

    /** Remove [displayId]'s touch sentinel, if one is registered. */
    fun removeTouchSentinel(displayId: Int) {
        val view = touchSentinels.remove(displayId) ?: return
        removeOverlayWindow(view)
    }

    /** Remove every touch sentinel — e.g. when live mode stops entirely. */
    fun removeAllTouchSentinels() {
        for (view in touchSentinels.values.toList()) removeOverlayWindow(view)
        touchSentinels.clear()
    }

    /**
     * Hide every registered overlay on [displayId] so it doesn't appear in a
     * screenshot of that display. Overlays on other displays are left alone —
     * blanking them would flicker every cycle when N displays are captured in
     * turn.
     *
     * Uses [WindowManager.LayoutParams.alpha] (window-level, applied by
     * SurfaceFlinger during composition) rather than [View.alpha] (applied
     * during view drawing, which can lag a frame behind). Combined with the
     * 2-vsync wait in the capture path, this reliably composites the
     * overlay-free frame before capture.
     *
     * Skips handles already at alpha=0 — they belong to a concurrent in-flight
     * capture that hasn't restored yet. Including them would let our restore
     * re-show overlays another capture still needs hidden.
     */
    fun prepareForCleanCapture(displayId: Int): OverlayState {
        val saved = mutableListOf<SavedHandle>()
        for (handle in overlayWindows) {
            if (handle.displayId != displayId) continue
            if (handle.params.alpha == 0f) continue
            val originalAlpha = handle.params.alpha
            handle.params.alpha = 0f
            try {
                handle.wm.updateViewLayout(handle.view, handle.params)
                saved += SavedHandle(handle, originalAlpha)
            } catch (_: Exception) {
                // Roll back the params mutation so the in-memory state still
                // reflects what's on screen.
                handle.params.alpha = originalAlpha
            }
        }
        return OverlayState(saved)
    }

    /** Restores blanked overlays to the alpha they had before
     *  [prepareForCleanCapture] blanked them. Most overlays were at α=1.0
     *  and come back there; the MediaProjection live-pinhole window is the
     *  only current exception (returns to the system obscuring cap). */
    fun restoreAfterCapture(state: OverlayState) {
        for (saved in state.saved) {
            saved.handle.params.alpha = saved.originalAlpha
            try {
                saved.handle.wm.updateViewLayout(saved.handle.view, saved.handle.params)
            } catch (_: Exception) {}
        }
    }

    /** Remove and unregister every window. Used on host teardown — service
     *  unbind, or a backend swap. Idempotent. */
    fun removeAll() {
        val handles = overlayWindows.toList()
        overlayWindows.clear()
        touchSentinels.clear()
        for (h in handles) {
            try { h.wm.removeView(h.view) } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "OverlayHost"

        /**
         * Defensive defaults for full-display (MATCH_PARENT × MATCH_PARENT)
         * overlays — they must cover the whole display so OCR-box coordinates,
         * which are in capture-bitmap pixels, map 1:1 onto the overlay.
         *
         * `fitInsetsTypes = 0` is what actually makes the window span the full
         * display: a non-focusable TYPE_APPLICATION_OVERLAY (the translation
         * overlay) is otherwise laid out inside the system-bar insets — shorter
         * than the capture — which vertically compresses every box.
         * `FLAG_LAYOUT_NO_LIMITS` alone does not prevent that;
         * `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` covers the display cutout.
         *
         * Idempotent. Honors callers that explicitly set a non-DEFAULT cutout
         * mode — only the DEFAULT case is upgraded.
         */
        fun applyFullScreenOverlayDefaults(params: WindowManager.LayoutParams) {
            val fullScreen =
                params.width == WindowManager.LayoutParams.MATCH_PARENT &&
                    params.height == WindowManager.LayoutParams.MATCH_PARENT
            if (!fullScreen) return
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            params.fitInsetsTypes = 0
            if (params.layoutInDisplayCutoutMode ==
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            ) {
                params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }
}
