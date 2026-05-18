package com.playtranslate

import android.content.Context
import android.graphics.Point
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics

/**
 * Display-size and window-inset queries.
 *
 * `WindowManager.currentWindowMetrics` — and the deprecated
 * `Display.getRealSize()` — only report accurate values when the
 * `WindowManager` comes from a *window* context (an Activity, or a
 * `Context.createWindowContext()`). Asked through a plain display context or a
 * service context they return garbage: on an AYN Thor a 1920x1080 panel
 * reported 1240x1080.
 *
 * Each call builds a *fresh* window context: a cached one was observed to
 * occasionally report the pre-rotation orientation's bounds. The binder cost
 * is small and none of these are hot-path calls.
 *
 * To query a display other than the receiver context's own, pass
 * `createDisplayContext(display)` as the receiver.
 */

/** [WindowMetrics] for this context's display, queried via a window context. */
fun Context.displayWindowMetrics(): WindowMetrics? =
    createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        .getSystemService(WindowManager::class.java)
        ?.currentWindowMetrics

/** Full pixel size of this context's display in its current rotation. */
fun Context.displaySizePx(): Point {
    val b = displayWindowMetrics()?.bounds ?: return Point()
    return Point(b.width(), b.height())
}

/** Status-bar inset height in pixels on this context's display, or 0. */
fun Context.statusBarHeightPx(): Int =
    displayWindowMetrics()?.windowInsets
        ?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
