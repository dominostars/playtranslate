package com.playtranslate

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.playtranslate.yomitan.YomitanDictionaryStore
import com.playtranslate.yomitan.YomitanImportResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Harness helper, not a regression test: imports a Yomitan dictionary zip
 * staged at files/pt-expressions-fixture.zip into the app's registry, so
 * segmentation-diff runs (see [SegmentationBatchTest]) can exercise the
 * imported-dictionary phrase oracle on a device without driving the UI.
 * Skips when no fixture is staged; Duplicate counts as success (re-runs).
 */
@RunWith(AndroidJUnit4::class)
class YomitanFixtureImportTest {

    @Test
    fun importStagedFixture() = runBlocking {
        val appCtx = InstrumentationRegistry.getInstrumentation().targetContext
        val zip = File(appCtx.filesDir, "pt-expressions-fixture.zip")
        assumeTrue("Skipped: stage a fixture zip at ${zip.absolutePath} first.", zip.isFile)

        val result = YomitanDictionaryStore.import(appCtx, Uri.fromFile(zip))
        println("YOMITAN_FIXTURE_IMPORT: $result")
        assertTrue(
            "import failed: $result",
            result is YomitanImportResult.Success || result is YomitanImportResult.Duplicate,
        )
    }
}
