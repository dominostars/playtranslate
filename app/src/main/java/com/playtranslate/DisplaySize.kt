package com.playtranslate

import android.content.Context
import com.playtranslate.overlay.OwnWindows
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import androidx.annotation.RequiresApi

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
 * Every read of a display goes through ONE window context of that display
 * ([DisplayWindowContexts]), kept while the display's rotation and panel size
 * are the ones it was made against and made again when they change. A window
 * context's bounds come from its configuration, which the platform updates
 * after a rotation asynchronously, so a context kept across a rotation can
 * report the pre-rotation bounds for a moment (seen when one was cached
 * without that check); its insets are asked of the window manager on every
 * read (android-11 WindowManagerImpl, android-16 WindowMetricsController), so
 * a kept context reports the bars as they are. A context per read is not an
 * option: Android 11 refuses an app a sixth window context that holds no
 * window until a garbage collection finalizes one (android-11
 * WindowManagerService tokenCountExceed), and the MediaProjection capture
 * loop reads twice per capture; in live mode that ran out about once a minute
 * and the capture was rebuilt at the size of the app area (API 30 emulator,
 * 2026-10-12). Kept contexts still need a new one per rotation, so rotations
 * faster than garbage collection could run Android 11 out again (24 rotations
 * on the emulator did not); a read that fails then still knows the display's
 * size from an earlier read at the same rotation ([displaySizePx]).
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

/** [WindowMetrics] for this context's display, read through the display's
 *  window context ([DisplayWindowContexts]), or `null` if the query fails:
 *  callers fall back to coarser metrics rather than crash. */
fun Context.displayWindowMetrics(): WindowMetrics? {
    // createWindowContext + currentWindowMetrics are API 30. Below R there is
    // no window context at all; callers fall back to coarser metrics (see
    // [displaySizePx] / [statusBarHeightPx]).
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    // This context names the display; a context with none throws here, as
    // createWindowContext on it would.
    val displayId = try {
        display?.displayId
    } catch (e: RuntimeException) {
        Log.w("DisplaySize", "windowMetrics query failed; using fallback metrics", e)
        null
    } ?: return null
    return DisplayWindowContexts.metrics(applicationContext, displayId)
}

/**
 * The window context every read of a display goes through
 * ([displayWindowMetrics]): one per display, kept while the display's
 * [Geometry] is the one it was made against and made again when it differs.
 * It also remembers the size each display read at each geometry
 * ([knownSize]): one display at one rotation and panel size always has the
 * same size, so a failed read of a geometry read before still knows it.
 * Process-wide, so every reader of a display shares it; [reset] runs at
 * Application.onCreate, since a JVM that runs many tests keeps statics across
 * their Applications.
 */
internal object DisplayWindowContexts {

    /** What a display's bounds depend on, read from the display itself: its
     *  rotation and its panel mode's size (a resolution switch or a fold
     *  changes the mode's size; a refresh-rate switch changes the mode but
     *  not its size, and keeps the context). A forced display size (`wm
     *  size`, some makers' resolution settings) changes the bounds alone:
     *  the kept context follows once the platform's configuration update
     *  reaches it, and a read between the change and that update reports
     *  the old bounds, where a context made for the read would not have
     *  from Android 13 (it is attached with the current configuration;
     *  android-13 WindowTokenClient.attachToDisplayArea). A layout made from
     *  such a read stands until the display's next refresh. Not keyed on:
     *  only a display event would show the change, and rebuilding at every
     *  one (a refresh-rate switch is one) would spend Android 11's five
     *  windowless contexts. [knownSize] can lag it the same way. */
    private data class Geometry(val rotation: Int, val modeWidth: Int, val modeHeight: Int)

    private class Entry(val windowContext: Context, val geometry: Geometry)

    private val entries = HashMap<Int, Entry>()

    /** Per display, the size it last read at each geometry. */
    private val sizes = HashMap<Int, HashMap<Geometry, Point>>()

    /** Makes a display's window context; tests substitute one that fails. */
    @RequiresApi(Build.VERSION_CODES.R)
    internal var make: (app: Context, display: Display) -> Context = { app, display ->
        app.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    }

    /** [displayId]'s window metrics now, or null when the read failed or the
     *  display went away. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun metrics(app: Context, displayId: Int): WindowMetrics? {
        val dm = app.getSystemService(DisplayManager::class.java) ?: return null
        synchronized(this) {
            forgetRemoved(dm)
            val display = dm.getDisplay(displayId) ?: return null
            val geometry = geometryOf(display)
            val read = try {
                OwnWindows.manager(of(app, display, geometry))?.currentWindowMetrics
            } catch (e: RuntimeException) {
                Log.w("DisplaySize", "windowMetrics query failed; using fallback metrics", e)
                null
            }
            read?.bounds?.takeIf { it.width() > 0 && it.height() > 0 }?.let {
                sizes.getOrPut(displayId) { HashMap() }[geometry] = Point(it.width(), it.height())
            }
            return read
        }
    }

    /** The size [displayId] read when it last had the rotation and panel
     *  size it has now, or null when it never has. */
    fun knownSize(app: Context, displayId: Int): Point? {
        val display = app.getSystemService(DisplayManager::class.java)?.getDisplay(displayId) ?: return null
        return synchronized(this) { sizes[displayId]?.get(geometryOf(display))?.let { Point(it) } }
    }

    /** The window context of [displayId], or null for a display that went
     *  away. Throws what createWindowContext throws. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun of(app: Context, displayId: Int): Context? =
        app.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
            ?.let { of(app, it, geometryOf(it)) }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun of(app: Context, display: Display, geometry: Geometry): Context = synchronized(this) {
        entries[display.displayId]?.takeIf { it.geometry == geometry }?.let { return it.windowContext }
        make(app, display).also { entries[display.displayId] = Entry(it, geometry) }
    }

    private fun geometryOf(display: Display): Geometry {
        val mode = display.mode
        return Geometry(display.rotation, mode.physicalWidth, mode.physicalHeight)
    }

    /** Every display this holds anything for and the platform no longer has
     *  is forgotten: on Android 11 a kept context holds one of the app's five
     *  windowless tokens until it is collected. */
    private fun forgetRemoved(dm: DisplayManager) {
        for (id in entries.keys + sizes.keys) {
            if (dm.getDisplay(id) == null) {
                entries.remove(id)
                sizes.remove(id)
            }
        }
    }

    /** The displays a window context is kept for. */
    internal fun kept(): Set<Int> = synchronized(this) { entries.keys.toSet() }

    fun reset() = synchronized(this) {
        entries.clear()
        sizes.clear()
    }
}

/** Full pixel size of this context's display in its current rotation. Falls
 *  back to [android.util.DisplayMetrics] if the window-metrics query fails. */
fun Context.displaySizePx(): Point {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        displayWindowMetrics()?.bounds?.let { return Point(it.width(), it.height()) }
        // The read failed: the size this display read when it last had its
        // current rotation and panel size is the size it has now. The coarse
        // read below is not the display's size (the header), and handed out
        // it rebuilt the capture at the wrong size and read to the capture
        // panel and the drag session as a display change.
        val displayId = try { display?.displayId } catch (_: RuntimeException) { null }
        displayId?.let { DisplayWindowContexts.knownSize(applicationContext, it) }?.let { return it }
    } else {
        // API 29 has no window context. getRealSize from the display context's
        // default display reports the correct post-rotation panel size here
        // (verified on-device: matches the window-context value), keeping the
        // capture bitmap and overlay coordinate spaces 1:1 so OCR boxes align.
        val wm = OwnWindows.manager(this)
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

/**
 * The geometry a surface is laid out against: a display's pixel size AND
 * rotation. A 180 degree flip keeps the size but changes the rotation, so
 * comparing this, not the size alone, still tells a stale surface from a
 * live one; a refresh-rate or HDR event keeps both, so it churns nothing.
 * The capture panel, the workspace and a drag session each record the
 * value they were built against and end when the display's differs.
 */
data class DisplayGeometry(val width: Int, val height: Int, val rotation: Int)

/** Size + rotation of [displayId], or null if it can't be resolved (a
 *  display that went away). The size is the display's own, through a
 *  display context ([displaySizePx]). */
fun Context.displayGeometryOf(displayId: Int): DisplayGeometry? {
    val display = (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
        ?.getDisplay(displayId) ?: return null
    val size = createDisplayContext(display).displaySizePx()
    return DisplayGeometry(size.x, size.y, display.rotation)
}
