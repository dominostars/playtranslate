package com.playtranslate.ui

import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [AnkiScreenshotState] owns the card editor's screenshot: the pin it
 * holds, the crop and the removal, every one written back into the launch
 * args so a saved-state recreation ([AnkiScreenshotState.open] on the
 * restored bundle) finds the same screenshot, the same crop, or the
 * removal. The pin's lifetime follows: adopted on a reopen, released on a
 * removal or a final teardown, kept through a saved-state one. The starting
 * crop: a search of the pinned file that open starts, decided once (found or
 * not) and recorded in the args, so a reopen never searches again and a crop
 * the user cleared stays cleared; the search here is handed in, since the
 * file is bytes and the real one has its own native-graphics tests.
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

    // ── The starting crop ────────────────────────────────────────────────

    private val picture = ScreenshotCrop.of(0.125f, 0f, 0.875f, 1f)!!

    /** The search lands on the main thread: pump it. */
    private fun awaitDecision(state: AnkiScreenshotState) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!state.startingCropDecided) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < deadline) { "the starting crop was never decided" }
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun noSearch(): (File, List<IconDiscFootprint>) -> ScreenshotCrop? = { _, _ -> error("searched again") }

    @Test fun `open searches the pinned file and starts the crop on the picture found, once`() {
        val args = argsFor(liveFile().absolutePath)
        val searched = CopyOnWriteArrayList<File>()
        val state = AnkiScreenshotState.open(ctx, args) { file, icons ->
            searched += file
            check(icons.isEmpty()) { "no capture is running here" }
            picture
        }!!
        var notified = 0
        state.addListener { notified++ }
        assertFalse(state.startingCropDecided)
        assertNull(state.attachment!!.crop)
        awaitDecision(state)
        assertEquals(picture, state.attachment!!.crop)
        assertEquals(1, notified)
        assertEquals("the pinned copy, not the live file", listOf(File(state.attachment!!.path)), searched)
        assertEquals(picture, ScreenshotCrop.fromFloatArray(args.getFloatArray("screenshot_crop")))
        // Decided: a reopen keeps the crop and does not search again.
        val again = AnkiScreenshotState.open(ctx, args, noSearch())!!
        assertTrue(again.startingCropDecided)
        assertEquals(picture, again.attachment!!.crop)
    }

    @Test fun `a crop the user cleared stays cleared on a reopen`() {
        val args = argsFor(liveFile().absolutePath)
        val state = AnkiScreenshotState.open(ctx, args) { _, _ -> picture }!!
        awaitDecision(state)
        state.setCrop(null)
        val again = AnkiScreenshotState.open(ctx, args, noSearch())!!
        assertTrue(again.startingCropDecided)
        assertNull(again.attachment!!.crop)
    }

    @Test fun `nothing found is decided too, and a reopen does not search again`() {
        val args = argsFor(liveFile().absolutePath)
        val state = AnkiScreenshotState.open(ctx, args) { _, _ -> null }!!
        var notified = 0
        state.addListener { notified++ }
        awaitDecision(state)
        assertNull(state.attachment!!.crop)
        assertEquals(0, notified)
        assertFalse(args.containsKey("screenshot_crop"))
        val again = AnkiScreenshotState.open(ctx, args, noSearch())!!
        assertTrue(again.startingCropDecided)
        assertNull(again.attachment!!.crop)
    }

    @Test fun `a search that fails decides nothing found`() {
        val args = argsFor(liveFile().absolutePath)
        val state = AnkiScreenshotState.open(ctx, args) { _, _ -> throw IllegalStateException("no decoder") }!!
        awaitDecision(state)
        assertNull(state.attachment!!.crop)
        assertTrue(AnkiScreenshotState.open(ctx, args, noSearch())!!.startingCropDecided)
    }

    @Test fun `a crop or a removal already in the args is a decision made`() {
        val args = argsFor(liveFile().absolutePath)
        val first = AnkiScreenshotState.open(ctx, args) { _, _ -> picture }!!
        awaitDecision(first)
        val crop = ScreenshotCrop.of(0.1f, 0.2f, 0.9f, 0.8f)
        first.setCrop(crop)
        val restored = AnkiScreenshotState.open(ctx, args, noSearch())!!
        assertTrue(restored.startingCropDecided)
        assertEquals(crop, restored.attachment!!.crop)
        restored.remove()
        val afterRemoval = AnkiScreenshotState.open(ctx, args, noSearch())!!
        assertTrue(afterRemoval.startingCropDecided)
        assertNull(afterRemoval.attachment)
    }

    @Test fun `a crop saved by an editor that never searched is a decision made`() {
        // A saved state from before the starting crop existed: a crop, no record
        // of a decision. The user's crop stands; nothing searches.
        val args = argsFor(liveFile().absolutePath).apply {
            putFloatArray("screenshot_crop", floatArrayOf(0.1f, 0.2f, 0.9f, 0.8f))
        }
        val state = AnkiScreenshotState.open(ctx, args, noSearch())!!
        assertTrue(state.startingCropDecided)
        assertEquals(ScreenshotCrop.of(0.1f, 0.2f, 0.9f, 0.8f), state.attachment!!.crop)
    }

    @Test fun `a removal before the search lands leaves no crop`() {
        val args = argsFor(liveFile().absolutePath)
        val gate = CountDownLatch(1)
        val state = AnkiScreenshotState.open(ctx, args) { _, _ ->
            gate.await(5, TimeUnit.SECONDS)
            picture
        }!!
        state.remove()
        gate.countDown()
        awaitDecision(state)
        assertNull(state.attachment)
        assertFalse(args.containsKey("screenshot_crop"))
        assertTrue(args.getBoolean("screenshot_removed"))
    }

    @Test fun `release cancels the search, and the restored editor searches again`() {
        val args = argsFor(liveFile().absolutePath)
        val gate = CountDownLatch(1)
        val state = AnkiScreenshotState.open(ctx, args) { _, _ ->
            gate.await(5, TimeUnit.SECONDS)
            picture
        }!!
        state.release(ctx, final = false)
        assertTrue("awaiters are let go", state.startingCropDecided)
        gate.countDown()
        // The cancelled search's result is dropped and no decision recorded.
        repeat(5) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertNull(state.attachment!!.crop)
        assertFalse(args.containsKey("screenshot_crop"))
        val searched = CopyOnWriteArrayList<File>()
        val restored = AnkiScreenshotState.open(ctx, args) { f, _ ->
            searched += f
            picture
        }!!
        awaitDecision(restored)
        assertEquals(1, searched.size)
        assertEquals(picture, restored.attachment!!.crop)
    }
}
