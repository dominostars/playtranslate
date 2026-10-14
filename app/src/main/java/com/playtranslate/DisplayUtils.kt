package com.playtranslate

import android.hardware.display.DisplayManager
import android.view.Display

/** Displays the picker may offer; FLAG_PRIVATE fails takeScreenshot, non-STATE_ON matches CaptureService.shouldSkipDisplay. */
fun DisplayManager.capturableDisplays(): List<Display> =
    displays.filter {
        it.flags and Display.FLAG_PRIVATE == 0
            && it.state == Display.STATE_ON
    }

/**
 * Whether [displayId] is a display `takeScreenshot` can target now:
 * connected and not private (`takeScreenshot` refuses private displays, and
 * this app's own MediaProjection VirtualDisplay is one). The power state is
 * deliberately not read, unlike [capturableDisplays]: a selected display
 * that is off stays selected, and the capture paths pause on it
 * (CaptureService's skip predicate) rather than move to a display that was
 * never selected. The accessibility backend's
 * [com.playtranslate.capture.CaptureBackend.canCapture].
 */
fun DisplayManager.isReachableDisplay(displayId: Int): Boolean {
    val display = getDisplay(displayId) ?: return false
    return display.flags and Display.FLAG_PRIVATE == 0
}

/** Every display [isReachableDisplay] is true for: connected and not
 *  private, whatever its power state. The first-launch seed's candidates. */
fun DisplayManager.reachableDisplays(): List<Display> =
    displays.filter { it.flags and Display.FLAG_PRIVATE == 0 }
