package com.playtranslate.ui

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [AnkiScreenshotState] owns the card editor's screenshot: the pin it
 * holds, the crop and the removal, every one written back into the launch
 * args so a saved-state recreation ([AnkiScreenshotState.open] on the
 * restored bundle) finds the same screenshot, the same crop, or the
 * removal. The pin's lifetime follows: adopted on a reopen, released on a
 * removal or a final teardown, kept through a saved-state one.
 */
@RunWith(RobolectricTestRunner::class)
class AnkiScreenshotStateTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** A capture-cache file, the fixed name a capture overwrites. */
    private fun liveFile(): File =
        File(ctx.cacheDir, "screenshots").apply { mkdirs() }
            .let { File(it, "capture-d0.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) } }

    private fun pins(): List<File> = File(ctx.cacheDir, "anki-pins").listFiles()?.toList().orEmpty()

    private fun argsFor(path: String?) = Bundle().apply {
        if (path != null) putString(AnkiScreenshotState.ARG_SCREENSHOT_PATH, path)
    }

    @Test fun `open pins a live capture, writes the pin back, and a reopen adopts it`() {
        val live = liveFile()
        val args = argsFor(live.absolutePath)
        val state = AnkiScreenshotState.open(ctx, args)!!
        val pinned = state.attachment!!.path
        assertTrue(AnkiScreenshotPin.isPin(ctx, pinned))
        assertEquals(pinned, args.getString(AnkiScreenshotState.ARG_SCREENSHOT_PATH))
        assertNull(state.attachment!!.crop)
        // The restored instance adopts the pin instead of copying it again.
        val again = AnkiScreenshotState.open(ctx, args)!!
        assertEquals(pinned, again.attachment!!.path)
        assertEquals(1, pins().size)
    }

    @Test fun `no path, or a file that is gone, is no screenshot`() {
        assertNull(AnkiScreenshotState.open(ctx, argsFor(null)))
        val args = argsFor(File(ctx.cacheDir, "screenshots/gone.jpg").absolutePath)
        assertNull(AnkiScreenshotState.open(ctx, args))
        assertFalse(args.containsKey(AnkiScreenshotState.ARG_SCREENSHOT_PATH))
    }

    @Test fun `a crop is written back and survives a reopen, and clearing it does too`() {
        val args = argsFor(liveFile().absolutePath)
        val state = AnkiScreenshotState.open(ctx, args)!!
        val crop = ScreenshotCrop.of(0.1f, 0.2f, 0.9f, 0.8f)
        state.setCrop(crop)
        assertEquals(crop, state.attachment!!.crop)
        assertEquals(crop, AnkiScreenshotState.open(ctx, args)!!.attachment!!.crop)
        state.setCrop(null)
        assertNull(AnkiScreenshotState.open(ctx, args)!!.attachment!!.crop)
    }

    @Test fun `remove keeps the pin for a send in flight, notifies, and a reopen finds the removal and still owns the file`() {
        val args = argsFor(liveFile().absolutePath)
        val state = AnkiScreenshotState.open(ctx, args)!!
        val pinned = state.attachment!!.path
        state.setCrop(ScreenshotCrop.of(0.1f, 0.2f, 0.9f, 0.8f))
        var notified = 0
        state.addListener { notified++ }
        state.remove()
        assertNull(state.attachment)
        assertEquals(1, notified)
        // A send already past Save freezes from this file: it stays.
        assertTrue(File(pinned).isFile)
        assertEquals(1, pins().size)
        // Idempotent; a crop after removal is nothing.
        state.remove()
        state.setCrop(ScreenshotCrop.of(0.1f, 0.2f, 0.9f, 0.8f))
        assertEquals(1, notified)
        assertNull(state.attachment)
        // The restored editor finds the removal and still owns the pin, so
        // its final teardown is what deletes the file.
        val restored = AnkiScreenshotState.open(ctx, args)!!
        assertNull(restored.attachment)
        assertEquals(1, pins().size)
        restored.release(ctx, final = false)
        assertEquals(1, pins().size)
        restored.release(ctx, final = true)
        assertEquals(0, pins().size)
        assertFalse(File(pinned).isFile)
    }

    @Test fun `release keeps the pin on a saved-state teardown and deletes it on a final one`() {
        val args = argsFor(liveFile().absolutePath)
        val saved = AnkiScreenshotState.open(ctx, args)!!
        saved.release(ctx, final = false)
        assertEquals(1, pins().size)
        val restored = AnkiScreenshotState.open(ctx, args)!!
        restored.release(ctx, final = true)
        assertEquals(0, pins().size)
    }

    @Test fun `listeners fire once per change and not for a repeat`() {
        val state = AnkiScreenshotState.open(ctx, argsFor(liveFile().absolutePath))!!
        var notified = 0
        val listener: (AnkiScreenshotState) -> Unit = { notified++ }
        state.addListener(listener)
        val crop = ScreenshotCrop.of(0.1f, 0.2f, 0.9f, 0.8f)
        state.setCrop(crop)
        state.setCrop(crop)
        assertEquals(1, notified)
        state.setCrop(null)
        assertEquals(2, notified)
        state.removeListener(listener)
        state.setCrop(crop)
        assertEquals(2, notified)
    }
}
