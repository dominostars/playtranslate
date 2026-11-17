package com.playtranslate.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * [GamePicture] over real pixels: a Bitmap read row and column, and a file
 * decoded whole into the crop that is exactly the picture found. NATIVE
 * graphics so BitmapFactory, Canvas and getPixels really run; Bitmap and
 * Canvas only (no View, no Path: see the sandbox-order note).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GamePictureBitmapTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** 400 x 200: a picture between 30 px black bars top and bottom. */
    private fun letterboxed(): Bitmap =
        Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply {
                drawColor(Color.BLACK)
                drawRect(0f, 30f, 400f, 170f, Paint().apply { color = 0xFF4080C0.toInt() })
            }
        }

    private fun file(name: String, write: (java.io.OutputStream) -> Unit): File {
        val f = File(ctx.cacheDir, "screenshots/$name")
        f.parentFile!!.mkdirs()
        f.outputStream().use(write)
        return f
    }

    @Test fun `a bitmap is read row and column`() {
        assertEquals(Rect(0, 30, 400, 170), GamePicture.find(letterboxed()))
        val full = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(0xFF4080C0.toInt()) }
        assertNull(GamePicture.find(full))
    }

    @Test fun `a file is decoded and read, as the crop that is exactly the picture`() {
        val f = file("capture-d0.jpg") { letterboxed().compress(Bitmap.CompressFormat.PNG, 100, it) }
        val crop = GamePicture.cropOf(f, emptyList())!!
        assertEquals(Rect(0, 30, 400, 170), crop.toPixelRect(400, 200))
    }

    @Test fun `a JPEG's bar edge lands within a pixel`() {
        val f = file("capture-d0.jpg") { letterboxed().compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val r = GamePicture.cropOf(f, emptyList())!!.toPixelRect(400, 200)
        assertEquals(0, r.left)
        assertEquals(400, r.right)
        assertTrue("top ${r.top}", r.top in 29..31)
        assertTrue("bottom ${r.bottom}", r.bottom in 169..171)
    }

    @Test fun `a file that is no image is no crop`() {
        val f = file("capture-d0.jpg") { it.write(ByteArray(64) { 7 }) }
        assertNull(GamePicture.cropOf(f, emptyList()))
    }
}
