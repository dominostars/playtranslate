package com.playtranslate.capture

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File

/**
 * Shared screenshot-cache writer. Both capture backends persist frames the
 * same way — one JPEG per display under `cacheDir/screenshots` — so the write
 * lives here instead of being duplicated per backend.
 */
object CaptureCache {

    private const val TAG = "CaptureCache"

    /**
     * Write [bitmap] as `capture-d{displayId}.jpg` under the app screenshot
     * cache. Per-display filenames keep the cache bounded to one file per
     * display and stop a concurrent capture on display B from clobbering
     * display A's screenshot. JPEG quality 90 (fast — ~10-30 ms vs PNG's
     * 50-200 ms). Returns the absolute path, or null on failure.
     */
    fun save(context: Context, bitmap: Bitmap, displayId: Int): String? {
        return try {
            val dir = File(context.cacheDir, "screenshots").apply { mkdirs() }
            val file = File(dir, "capture-d$displayId.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "save failed: ${e.message}")
            null
        }
    }
}
