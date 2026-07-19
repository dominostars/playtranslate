package com.playtranslate.imageimport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [ImageImportSession.sweepOrphanedFrames]: only the import tool's own
 * aged frame files are collected — a restore target survives regardless of
 * age, young files (a sibling activity instance's live review) survive, and
 * other cache files in the shared screenshots dir are never touched.
 */
@RunWith(RobolectricTestRunner::class)
class ImportFrameSweepTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(ctx.cacheDir, "screenshots")

    private fun file(name: String, ageMs: Long = 0L): File {
        val f = File(dir, name)
        f.writeText("frame")
        assertTrue(f.setLastModified(System.currentTimeMillis() - ageMs))
        return f
    }

    @Before fun setUp() {
        dir.deleteRecursively()
        dir.mkdirs()
    }

    @Test fun sweepCollectsOnlyAgedImportFrames() {
        val dayAndChange = 25 * 60 * 60 * 1000L
        val oldOrphan = file("import-image-1-1.jpg", ageMs = dayAndChange)
        val oldKeep = file("import-image-2-2.jpg", ageMs = dayAndChange)
        val young = file("import-image-3-3.jpg")
        val cameraSnapshot = file("camera-snapshot.jpg", ageMs = dayAndChange)

        ImageImportSession.sweepOrphanedFrames(ctx, keepPath = oldKeep.absolutePath)

        assertFalse("aged orphan must be collected", oldOrphan.exists())
        assertTrue("restore target survives regardless of age", oldKeep.exists())
        assertTrue("young frames (sibling live review) survive", young.exists())
        assertTrue("other surfaces' files are never touched", cameraSnapshot.exists())
    }
}
