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
 * the server's boxes are divided by, JPEG bytes, and the caller's bitmap
 * left alone. NATIVE graphics so
 * [Bitmap.createScaledBitmap] and [Bitmap.compress] really run (Bitmap and
 * Canvas only: no View, no Path).
 *
 * Order-sensitive like every native-graphics class here: when a legacy
 * sandbox loaded the native runtime first in the same test JVM, Bitmap
 * creation fails with `UnsatisfiedLinkError: ColorSpaceRgbNatives`. That is
 * the run's class order, not this code; run the class alone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrameEncoderNativeTest {

    private fun frame(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
        Canvas(this).drawRect(w * 0.1f, h * 0.4f, w * 0.9f, h * 0.6f, Paint().apply { color = Color.BLACK })
    }

    /** A JPEG starts with the SOI marker FF D8 and ends with EOI FF D9. */
    private fun assertJpeg(bytes: ByteArray) {
        assertTrue("non-empty", bytes.size > 4)
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])
        assertEquals(0xFF.toByte(), bytes[bytes.size - 2])
        assertEquals(0xD9.toByte(), bytes[bytes.size - 1])
    }

    @Test fun `a frame over the cap is scaled down, encoded as JPEG, and the input survives`() {
        val input = frame(2560, 1440)
        val encoded = runBlocking { FrameEncoder.encode(input, longSideCap = 1920) }
        assertEquals(1920, encoded.width)
        assertEquals(1080, encoded.height)
        assertEquals(0.75f, encoded.scale)
        assertJpeg(encoded.bytes)
        assertFalse("the caller's bitmap is never recycled", input.isRecycled)
    }

    @Test fun `a frame within the cap is sent at its own size with scale 1, as JPEG`() {
        val input = frame(800, 450)
        val encoded = runBlocking { FrameEncoder.encode(input, longSideCap = 1920) }
        assertEquals(800, encoded.width)
        assertEquals(450, encoded.height)
        assertEquals(1f, encoded.scale)
        assertJpeg(encoded.bytes)
        assertFalse(input.isRecycled)
    }
}
