package com.playtranslate.overlay

/**
 * A registered overlay window's view that can put up windows of its own,
 * attached to its window, which a clean capture's blanking does not reach:
 * [OverlayHost.prepareForCleanCapture] sets the registered window's alpha,
 * and an attached window keeps its own. Told before the blanking of its
 * display, to take them down. The window roots that hold text fields are
 * such views: a field's Cut / Copy / Paste bar is a window of its own
 * ([com.playtranslate.ui.OverlayWindowRoot]), whose words would otherwise be
 * in the frame for the OCR to read.
 */
interface CleanCaptureAware {
    fun beforeCleanCapture()
}
