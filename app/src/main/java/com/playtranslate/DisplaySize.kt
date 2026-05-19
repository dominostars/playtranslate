package com.playtranslate

import android.content.Context
import android.graphics.Point
import android.util.Log
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
 * The window context is typed `TYPE_APPLICATION_OVERLAY`, not
 * `TYPE_ACCESSIBILITY_OVERLAY`: the latter makes `createWindowContext` throw
 * `SecurityException` (MANAGE_APP_TOKENS) on Android 11 / API 30, crash-looping
 * the accessibility service the moment it connects. The window type does not
 * affect the per-display metrics returned.
 *
 * To query a display other than the receiver context's own, pass
 * `createDisplayContext(display)` as the receiver.
 */

/** [WindowMetrics] for this context's display, queried via a fresh window
 *  context, or `null` if the query throws. The catch is a safety net for OEM /
 *  future-OS variance — callers fall back to coarser metrics rather than
 *  crash. */
fun Context.displayWindowMetrics(): WindowMetrics? = try {
    createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        .getSystemService(WindowManager::class.java)
        ?.currentWindowMetrics
} catch (e: RuntimeException) {
    Log.w("DisplaySize", "windowMetrics query failed; using fallback metrics", e)
    null
}

/** Full pixel size of this context's display in its current rotation. Falls
 *  back to [android.util.DisplayMetrics] if the window-metrics query fails. */
fun Context.displaySizePx(): Point {
    displayWindowMetrics()?.bounds?.let { return Point(it.width(), it.height()) }
    val dm = resources.displayMetrics
    return Point(dm.widthPixels, dm.heightPixels)
}

/** Status-bar inset height in pixels on this context's display, or 0. */
fun Context.statusBarHeightPx(): Int =
    displayWindowMetrics()?.windowInsets
        ?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
