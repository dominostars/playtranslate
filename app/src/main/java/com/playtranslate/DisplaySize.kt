package com.playtranslate

import android.content.Context
import android.graphics.Point
import android.os.Build
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
 * Each call builds a *fresh* window context — never cached: a cached one was
 * observed to occasionally report the pre-rotation orientation's bounds. The
 * binder cost is small; the most frequent caller is the MediaProjection
 * capture loop, a few times a second — well short of a per-frame hot path.
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
fun Context.displayWindowMetrics(): WindowMetrics? {
    // createWindowContext + currentWindowMetrics are API 30. Below R there is
    // no window context at all; callers fall back to coarser metrics (see
    // [displaySizePx] / [statusBarHeightPx]).
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    return try {
        createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            .getSystemService(WindowManager::class.java)
            ?.currentWindowMetrics
    } catch (e: RuntimeException) {
        Log.w("DisplaySize", "windowMetrics query failed; using fallback metrics", e)
        null
    }
}

/** Full pixel size of this context's display in its current rotation. Falls
 *  back to [android.util.DisplayMetrics] if the window-metrics query fails. */
fun Context.displaySizePx(): Point {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        displayWindowMetrics()?.bounds?.let { return Point(it.width(), it.height()) }
    } else {
        // API 29 has no window context. getRealSize from the display context's
        // default display reports the correct post-rotation panel size here
        // (verified on-device: matches the window-context value), keeping the
        // capture bitmap and overlay coordinate spaces 1:1 so OCR boxes align.
        val wm = getSystemService(WindowManager::class.java)
        if (wm != null) {
            val p = Point()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(p)
            if (p.x > 0 && p.y > 0) return p
        }
    }
    val dm = resources.displayMetrics
    return Point(dm.widthPixels, dm.heightPixels)
}

/** Status-bar inset height in pixels on this context's display, or 0. */
fun Context.statusBarHeightPx(): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        return displayWindowMetrics()?.windowInsets
            ?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
    }
    // API 29: there is no reliable way to read the *current* status-bar inset
    // here — only a focused window tracks bar visibility, and the captured app
    // owns focus during capture (a background overlay always reports a static
    // value). This is only the OCR-crop top floor, so a static status_bar_height
    // would crop that many pixels off EVERY capture even when the bar is hidden,
    // losing the top of a fullscreen game. Return 0 (OCR the full frame):
    // status-bar content that is actually present (clock, icons) is dropped
    // downstream by LayoutAnalyzer's source-language group filter, so it never
    // becomes a box, and cropTop carries back the same — box positions are
    // unaffected. (API 30+ above reads the live inset, so it crops the bar only
    // when it's actually showing.)
    return 0
}
