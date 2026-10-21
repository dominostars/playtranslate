package com.playtranslate.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * [AnkiScreenshotPin.pinForSend] is where a card's crop becomes a file: the
 * cut is written as a new pin at the capture's quality and the source is
 * untouched; without a crop the pin is today's byte copy; a crop that
 * cannot be written is NO screenshot (the pipeline reports the drop),
 * never the whole frame. NATIVE graphics so BitmapFactory and compress
 * really run on the JVM; Bitmap and Canvas only (no View, no Path: see the
 * sandbox-order note).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnkiScreenshotPinCropTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** A 40 x 20 JPEG: the left half red, the right half blue. */
    private fun halves(): File {
        val bmp = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.RED)
            drawRect(20f, 0f, 40f, 20f, Paint().apply { color = Color.BLUE })
        }
        val f = File(ctx.cacheDir, "screenshots/capture-d0.jpg")
        f.parentFile!!.mkdirs()
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return f
    }

    private fun pins(): List<File> = File(ctx.cacheDir, "anki-pins").listFiles()?.toList().orEmpty()

    @Test fun `a crop writes the cut as a new pin and leaves the source alone`() = runBlocking {
        val src = halves()
        val before = src.readBytes()
        val out = AnkiScreenshotPin.pinForSend(
            ctx, ScreenshotAttachment(src.absolutePath, ScreenshotCrop.of(0.5f, 0f, 1f, 1f)),
        )!!
        assertTrue(AnkiScreenshotPin.isPin(ctx, out))
        val cut = BitmapFactory.decodeFile(out)!!
        assertEquals(20, cut.width)
        assertEquals(20, cut.height)
        val c = cut.getPixel(10, 10)
        assertTrue("the right half, blue: ${Integer.toHexString(c)}", Color.blue(c) > 200 && Color.red(c) < 80)
        assertArrayEquals(before, src.readBytes())
        assertEquals("the uncropped copy is released", 1, pins().size)
    }

    @Test fun `the frame is frozen before the send suspends, so a recapture meanwhile cannot reach the card`() = runBlocking {
        val src = halves()
        val shot = ScreenshotAttachment(src.absolutePath, ScreenshotCrop.of(0.5f, 0f, 1f, 1f))
        var out: String? = null
        // UNDISPATCHED runs the body on this thread up to its first
        // suspension, the switch to IO for the crop.
        val job = launch(start = CoroutineStart.UNDISPATCHED) { out = AnkiScreenshotPin.pinForSend(ctx, shot) }
        assertEquals("the copy exists before anything suspended", 1, pins().size)
        // The capture surface rewrites its fixed filename while the crop runs.
        src.writeBytes(ByteArray(64) { 7 })
        job.join()
        val cut = BitmapFactory.decodeFile(out!!)!!
        assertEquals(20, cut.width)
        assertTrue(Color.blue(cut.getPixel(10, 10)) > 200)
    }

    @Test fun `a send cancelled during the crop still receives its pin, so its cleanup can release it`() = runBlocking {
        val src = halves()
        val shot = ScreenshotAttachment(src.absolutePath, ScreenshotCrop.of(0.5f, 0f, 1f, 1f))
        var out: String? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) { out = AnkiScreenshotPin.pinForSend(ctx, shot) }
        job.cancel()
        job.join()
        assertTrue("the path came back", out != null && File(out!!).isFile)
        assertEquals("one pin: the cropped one, the uncropped copy released", 1, pins().size)
        AnkiScreenshotPin.release(ctx, out)
        assertEquals(0, pins().size)
    }

    @Test fun `without a crop the pin is a byte copy`() = runBlocking {
        val src = halves()
        val out = AnkiScreenshotPin.pinForSend(ctx, ScreenshotAttachment(src.absolutePath))!!
        assertTrue(AnkiScreenshotPin.isPin(ctx, out))
        assertArrayEquals(src.readBytes(), File(out).readBytes())
    }

    @Test fun `a crop of a file that cannot be decoded is no screenshot and leaves no pin`() = runBlocking {
        val garbage = File(ctx.cacheDir, "screenshots/capture-d0.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(64) { 7 })
        }
        assertNull(
            AnkiScreenshotPin.pinForSend(
                ctx, ScreenshotAttachment(garbage.absolutePath, ScreenshotCrop.of(0.5f, 0f, 1f, 1f)),
            ),
        )
        assertEquals(0, pins().size)
    }

    @Test fun `a missing file is no screenshot with a crop, and today's passthrough without one`() = runBlocking {
        val missing = File(ctx.cacheDir, "screenshots/gone.jpg").absolutePath
        assertNull(AnkiScreenshotPin.pinForSend(ctx, ScreenshotAttachment(missing, ScreenshotCrop.of(0.5f, 0f, 1f, 1f))))
        assertEquals(missing, AnkiScreenshotPin.pinForSend(ctx, ScreenshotAttachment(missing)))
        assertNull(AnkiScreenshotPin.pinForSend(ctx, null))
    }
}
