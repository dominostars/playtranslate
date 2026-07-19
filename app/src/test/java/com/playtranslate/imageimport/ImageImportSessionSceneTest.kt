package com.playtranslate.imageimport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.CaptureState
import com.playtranslate.OcrManager
import com.playtranslate.camera.CameraTranslator
import com.playtranslate.model.TextSegments
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the page cache's session seam: [ImageImportSession.publishScene]
 * republishes a completed scene (no OCR, no translation) whose caches then
 * round-trip through [ImageImportSession.exportScene], and the episode wipe
 * self-guards a later export. This is the machinery instant page revisits
 * ride on.
 */
@RunWith(RobolectricTestRunner::class)
class ImageImportSessionSceneTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun scene(): ImageImportSession.CachedScene {
        val group = OcrManager.OcrGroup(
            text = "こんにちは",
            bounds = Rect(10, 10, 200, 60),
            lines = listOf(
                OcrManager.LineBox(text = "こんにちは", bounds = Rect(10, 10, 200, 60), groupIndex = 0),
            ),
        )
        val ocr = OcrManager.OcrResult(
            fullText = "こんにちは",
            segments = TextSegments.ofText("こんにちは"),
            groups = listOf(group),
        )
        return ImageImportSession.CachedScene(
            gatedOcr = ocr,
            groupColors = listOf(0xFF000000.toInt() to 0xFFFFFFFF.toInt()),
            perGroup = listOf(CameraTranslator.Detailed("Hello", null, "TestBackend")),
            provenance = null,
            panelText = "こんにちは",
            segments = ocr.segments,
            auW = 400,
            auH = 300,
        )
    }

    @Test
    fun publishSceneRoundTripsThroughExportAndWipesOnEpisodeEnd() = runBlocking {
        val session = ImageImportSession(
            context = ctx,
            scope = CoroutineScope(Dispatchers.Default),
            overlayHost = FrameLayout(ctx),
        )
        session.startEpisode()
        val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        val published = scene()

        val capture = session.publishScene(bitmap, published)
        val done = withTimeout(10_000) {
            capture.state.first { it is CaptureState.Done } as CaptureState.Done
        }
        assertEquals("Hello", done.result.translatedText)
        assertEquals("こんにちは", done.result.originalText)
        assertEquals("TestBackend", done.result.backendDisplayName)
        // The republish saved a fresh frame file for Anki/lookup.
        assertNotNull(done.result.screenshotPath)
        assertTrue(java.io.File(done.result.screenshotPath!!).exists())

        // The caches now describe the scene: export round-trips it.
        val exported = session.exportScene()
        assertNotNull(exported)
        assertEquals(published.perGroup, exported!!.perGroup)
        assertEquals(published.panelText, exported.panelText)
        assertEquals(published.auW, exported.auW)

        // Cache-admission form: a SUPERSEDED cycle's generation exports
        // null even while the caches still hold an intact scene — the
        // settings-refresh poisoning guard (refresh clears the page cache
        // and bumps the generation without wiping these caches).
        assertNull(session.exportScene(expectedGen = -1L))

        // Episode end wipes the caches — a later export self-guards.
        session.endEpisode()
        assertNull(session.exportScene())
    }
}
