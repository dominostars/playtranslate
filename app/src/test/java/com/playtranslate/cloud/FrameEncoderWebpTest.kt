package com.playtranslate.cloud

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * [FrameEncoder.encode] for real: the uploaded size, the [EncodedFrame.scale]
 * the server's boxes are divided by, WebP bytes, and the caller's bitmap
 * left alone. NATIVE graphics so [Bitmap.createScaledBitmap] and
 * [Bitmap.compress] really run (Bitmap and Canvas only: no View, no Path).
 *
 * Order-sensitive like every native-graphics class here: when a legacy
 * sandbox loaded the native runtime first in the same test JVM, Bitmap
 * creation fails with `UnsatisfiedLinkError: ColorSpaceRgbNatives`. That is
 * the run's class order, not this code; run the class alone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrameEncoderWebpTest {

    private fun frame(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
        Canvas(this).drawRect(w * 0.1f, h * 0.4f, w * 0.9f, h * 0.6f, Paint().apply { color = Color.BLACK })
    }

    private fun assertWebp(bytes: ByteArray) {
        assertTrue("non-empty", bytes.size > 12)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WEBP", String(bytes, 8, 4, Charsets.US_ASCII))
    }

    @Test fun `a frame over the cap is scaled down, encoded as WebP, and the input survives`() {
        val input = frame(2560, 1440)
        val encoded = runBlocking { FrameEncoder.encode(input, longSideCap = 1920) }
        assertEquals(1920, encoded.width)
        assertEquals(1080, encoded.height)
        assertEquals(0.75f, encoded.scale)
        assertEquals("image/webp", encoded.mediaType)
        assertWebp(encoded.bytes)
        assertFalse("the caller's bitmap is never recycled", input.isRecycled)
    }

    @Test fun `a frame within the cap is sent at its own size with scale 1`() {
        val input = frame(800, 450)
        val encoded = runBlocking { FrameEncoder.encode(input, longSideCap = 1920) }
        assertEquals(800, encoded.width)
        assertEquals(450, encoded.height)
        assertEquals(1f, encoded.scale)
        assertWebp(encoded.bytes)
        assertFalse(input.isRecycled)
    }
}
