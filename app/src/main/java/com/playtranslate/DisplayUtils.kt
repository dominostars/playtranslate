package com.playtranslate

import android.hardware.display.DisplayManager
import android.view.Display

/** Displays the picker may offer; FLAG_PRIVATE fails takeScreenshot, non-STATE_ON matches CaptureService.shouldSkipDisplay. */
fun DisplayManager.capturableDisplays(): List<Display> =
    displays.filter {
        it.flags and Display.FLAG_PRIVATE == 0
            && it.state == Display.STATE_ON
    }
