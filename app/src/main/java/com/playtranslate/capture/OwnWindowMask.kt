package com.playtranslate.capture

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.playtranslate.DetectionLog
import com.playtranslate.OverlayToolkit
import com.playtranslate.displaySizePx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps this app's own activity windows out of captured frames.
 *
 * Why this exists: in split-screen the capture backends grab the whole
 * display, so without protection the OCR loop reads the translations we
 * just rendered and feeds them back (commit 41aa9a1d). That used to be
 * `FLAG_SECURE` on a handful of activities, which SurfaceFlinger honours in
 * every capture path — but also in the user's own screenshots and screen
 * recordings, in Recents, and in the drag-lookup lens, which OCRs a clean
 * frame and therefore saw black wherever our UI was. FLAG_SECURE was also
 * per-window: a settings sheet or AlertDialog over MainActivity was never
 * covered, and the activities that were never flagged (History, Dictionary
 * lookup, every settings page) leaked all along.
 *
 * Replacement: every frame a capture source serves is painted black over
 * the window bounds of each of our STARTED opaque activities on that
 * display — the same family as [com.playtranslate.overlay.OverlayHost]'s
 * clean-capture alpha blanking and the floating-icon blackout in
 * [OverlayToolkit.blackoutFloatingIcon]. Painting at the SOURCE (not at the
 * OCR sites) reproduces FLAG_SECURE's pixels for every downstream consumer
 * — pixel diffs, cleanRef baselines, colour sampling, cached screenshots,
 * History thumbnails — so an animating app pane can't trip change detection
 * any more than it could before. Consumers that want the real screen (the
 * lens, the capture-settings display thumbnails, the debug mirror dump) opt
 * out per call via `maskOwnWindows = false` on [CaptureSource.requestClean]
 * / [LiveCaptureSource.requestRaw].
 *
 * Membership rules:
 *  - STARTED, not RESUMED: a split-screen activity that lost focus is still
 *    on screen. Removed on stop and on destroy.
 *  - Translucent and floating windows are excluded (`windowIsTranslucent`
 *    / `windowIsFloating`). The permission and MediaProjection-consent
 *    trampolines are full-display translucent activities that are still
 *    STARTED while their system dialog shows — MediaProjectionConsentActivity
 *    completes the consent gate BEFORE it finishes, so the first one-shot
 *    after consent runs under it; masking it would serve an all-black
 *    frame. ProcessTextActivity is transparent so the calling app stays
 *    visible behind the sheet, which is exactly the content the user wants
 *    translated. None of these three was ever secure.
 *
 * Geometry is read LIVE at serve time, on the main thread (the way
 * `OverlayUiController.getFloatingIconRect` reads live icon params), so a
 * split-screen divider drag needs no listener. Whole-display frames and
 * `currentWindowMetrics.bounds` share the display's pixel space; if a served
 * frame's size ever disagrees with the display's, the whole frame is masked
 * rather than a rect clamped against the wrong geometry — FLAG_SECURE could
 * never fail open, and neither may this.
 */
object OwnWindowMask {

    private const val TAG = "OwnWindowMask"

    /** Display id and window bounds of one started activity, as read at
     *  serve time. [bounds] may be empty while the window is not attached. */
    data class Geometry(val displayId: Int, val bounds: Rect)

    /** Started, maskable activities. Main-thread only, like every lifecycle
     *  callback that mutates it. */
    private val started = LinkedHashSet<Activity>()

    private var app: Application? = null

    /** Last rect list logged per display — the `[OwnWindowMask]` line is a
     *  transition log, not a per-frame one. Main-thread only. */
    private val lastLogged = HashMap<Int, List<Rect>>()

    /** How an activity's geometry is read. Injectable because Robolectric
     *  ships no shadow for `currentWindowMetrics` or `Activity.getDisplay`;
     *  production uses [defaultGeometry]. */
    @VisibleForTesting
    internal var geometryProbe: (Activity) -> Geometry? = ::defaultGeometry

    /** Register the lifecycle tracker. Idempotent per [Application]. */
    fun install(app: Application) {
        if (this.app === app) return
        this.app = app
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (isMaskable(activity)) started += activity
            }
            override fun onActivityStopped(activity: Activity) { started -= activity }
            override fun onActivityDestroyed(activity: Activity) { started -= activity }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }

    /**
     * Serve-time entry point for the capture sources. Paints our started
     * windows on [displayId] black into [bitmap] and returns the bitmap to
     * serve. OWNERSHIP: consumes [bitmap] — when painting had to copy (an
     * immutable frame), the input is recycled and the copy returned; the
     * caller must use only the return value. Unlike [apply] and
     * [OverlayToolkit.blackoutFloatingIcon], which never recycle. The
     * ownership holds on EVERY exit: this suspends (the main-thread hop),
     * so a caller cancelled between its capture and this call would
     * otherwise have a just-captured frame that nothing ever recycles —
     * on a throw, cancellation included, the input is recycled before the
     * exception propagates (Codex review finding).
     */
    suspend fun maskServedFrame(bitmap: Bitmap, displayId: Int): Bitmap {
        val out = try {
            val rects = withContext(Dispatchers.Main.immediate) { rectsOn(displayId) }
            if (rects.isEmpty()) return bitmap
            val expected = displaySize(displayId)
            val toPaint = if (expected != null &&
                (expected.x != bitmap.width || expected.y != bitmap.height)
            ) {
                DetectionLog.log(
                    "[OwnWindowMask] d$displayId frame ${bitmap.width}x${bitmap.height} != display " +
                        "${expected.x}x${expected.y}: masking whole frame"
                )
                listOf(Rect(0, 0, bitmap.width, bitmap.height))
            } else {
                rects
            }
            apply(bitmap, toPaint, allowInPlace = true)
        } catch (t: Throwable) {
            bitmap.recycle()
            throw t
        }
        if (out !== bitmap) bitmap.recycle()
        return out
    }

    /**
     * Window bounds (display coordinates) of every started, maskable
     * activity currently on [displayId]. Empty rects (window not attached
     * yet) are dropped. Main thread only: reads live window geometry.
     */
    fun rectsOn(displayId: Int): List<Rect> {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "OwnWindowMask.rectsOn must run on the main thread (got ${Thread.currentThread().name})"
        }
        val rects = started.mapNotNull { activity ->
            geometryProbe(activity)
                ?.takeIf { it.displayId == displayId && !it.bounds.isEmpty }
                ?.let { Rect(it.bounds) }
        }
        if (lastLogged[displayId] != rects) {
            lastLogged[displayId] = rects
            DetectionLog.log(
                "[OwnWindowMask] d$displayId: " +
                    if (rects.isEmpty()) "none" else rects.joinToString { it.toShortString() }
            )
        }
        return rects
    }

    /**
     * Paint every rect in [rects] black. Empty list returns [bitmap]
     * itself. Folds [OverlayToolkit.blackoutFloatingIcon] and inherits its
     * contract: [allowInPlace] is the ownership declaration for the FIRST
     * draw; once a copy exists it is ours and later rects draw into it.
     * Never recycles anything.
     */
    fun apply(bitmap: Bitmap, rects: List<Rect>, allowInPlace: Boolean): Bitmap {
        var out = bitmap
        var inPlace = allowInPlace
        for (rect in rects) {
            val next = OverlayToolkit.blackoutFloatingIcon(out, 0, 0, rect, allowInPlace = inPlace)
            if (next !== out) {
                out = next
                inPlace = true
            }
        }
        return out
    }

    private fun isMaskable(activity: Activity): Boolean =
        !themeBoolean(activity, android.R.attr.windowIsTranslucent) &&
            !themeBoolean(activity, android.R.attr.windowIsFloating)

    private fun themeBoolean(activity: Activity, attr: Int): Boolean {
        val a = activity.theme.obtainStyledAttributes(intArrayOf(attr))
        try {
            return a.getBoolean(0, false)
        } finally {
            a.recycle()
        }
    }

    private fun defaultGeometry(activity: Activity): Geometry? {
        val displayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display?.displayId ?: return null
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.displayId
        }
        val bounds = windowMetricsBounds(activity) ?: decorBounds(activity) ?: return null
        return Geometry(displayId, bounds)
    }

    /** The activity window's bounds in display coordinates from its own
     *  window metrics (R+). Null below R, and null if the query throws:
     *  window-metrics queries are known to vary by OEM (see DisplaySize.kt),
     *  and a throw here must degrade to [decorBounds], never take the
     *  capture serve path down with it. */
    private fun windowMetricsBounds(activity: Activity): Rect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            Rect(activity.windowManager.currentWindowMetrics.bounds)
        } catch (e: RuntimeException) {
            Log.w(TAG, "currentWindowMetrics failed for ${activity.javaClass.simpleName}; using decor bounds", e)
            null
        }
    }

    /** The decor view's on-screen frame: the window's bounds in display
     *  coordinates once attached, on every API level. Null before the first
     *  attach, when there is nothing on screen to mask yet. */
    private fun decorBounds(activity: Activity): Rect? {
        val decor = activity.window?.decorView ?: return null
        if (!decor.isAttachedToWindow) return null
        val loc = IntArray(2)
        decor.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + decor.width, loc[1] + decor.height)
    }

    /** Full pixel size of [displayId], or null when it can't be read (then
     *  the rects are painted as-is — there is nothing to compare against). */
    private fun displaySize(displayId: Int): Point? {
        val app = app ?: return null
        val display = app.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
            ?: return null
        return app.createDisplayContext(display).displaySizePx().takeIf { it.x > 0 && it.y > 0 }
    }

    /** Test hook: forget every started activity and the log memory. */
    @VisibleForTesting
    internal fun resetForTest() {
        started.clear()
        lastLogged.clear()
        geometryProbe = ::defaultGeometry
    }
}
