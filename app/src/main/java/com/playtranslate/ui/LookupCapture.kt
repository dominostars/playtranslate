package com.playtranslate.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.playtranslate.OcrManager
import com.playtranslate.Prefs
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.ocr.FrameReaders
import com.playtranslate.ocr.readLines
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * The capture and the read the over-game word lookups take their scene
 * from: the drag lens ([DragLookupController]). It reads the whole display,
 * wherever the words are, so one shape serves every lens and their frames
 * cannot drift apart.
 */
internal object LookupCapture {

    private const val TAG = "LookupCapture"

    /** How long a lookup waits for its frame before it gives up. */
    private const val CAPTURE_TIMEOUT_MS = 3000L

    /**
     * A clean frame of [displayId] for a lookup, or null when the capture
     * failed or took longer than [CAPTURE_TIMEOUT_MS]. Warms PlayTranslate
     * Cloud first for the [readLines] that follows: a fresh capture, so it
     * reads in the source language from the same pref and never
     * device-only.
     *
     * maskOwnWindows = false: a lookup finds words wherever they are, our
     * own pages included, so the frame carries our UI's real pixels, and a
     * screenshot saved from it does too, by design. Every caller has
     * stopped live mode for its lookup, so no translate-path frame can be
     * served from this capture.
     */
    suspend fun cleanFrame(context: Context, displayId: Int): Bitmap? {
        FrameReaders.warm(Prefs(context).sourceLangId, deviceOnly = false)
        return withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
            CaptureBackendResolver.active().captureSource
                ?.requestClean(displayId, maskOwnWindows = false)?.bitmap
        }
    }

    /**
     * The lines in [bitmap]: through PlayTranslate Cloud when the router
     * ([FrameReaders.readerFor]) would send a capture there (a frame it
     * cannot read falls back to the device), else on the device; the same
     * read every other consumer makes, with or without a running service.
     * [frameTag] names the frame in the reader's logs. Call it directly in
     * the job that owns the read, never inside a scoped builder, since a
     * cloud read's later work is a child of the calling job.
     */
    suspend fun readLines(context: Context, bitmap: Bitmap, frameTag: String): List<OcrManager.OcrLine>? {
        val srcId = Prefs(context).sourceLangId
        return FrameReaders.readerFor(srcId, deviceOnly = false)
            .readLines(bitmap, srcId, FrameReaders.frameId(frameTag))
    }

    /** [bitmap] saved as [fileName] in the screenshots cache, for the Anki
     *  card and the detail page of a word looked up in it; null when the
     *  write failed. Each lookup surface keeps its own file, so one never
     *  overwrites the screenshot the other's card points at. */
    fun saveScreenshot(context: Context, bitmap: Bitmap, fileName: String): String? {
        return try {
            val dir = File(context.cacheDir, "screenshots").apply { mkdirs() }
            val file = File(dir, fileName)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "saveScreenshot failed: ${e.message}")
            null
        }
    }
}
