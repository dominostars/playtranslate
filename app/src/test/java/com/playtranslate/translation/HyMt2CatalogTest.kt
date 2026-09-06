package com.playtranslate.translation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.language.LanguagePackCatalogLoader
import com.playtranslate.translation.hymt.HyMt2Model
import com.playtranslate.translation.hymt.HyMtModel
import com.playtranslate.translation.llm.MultiFileSha
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards for the Hy-MT2 rollout: the shipped catalog entry's self-consistency,
 * the retirement of Hunyuan-MT 1.5, and the waterfall slot Hy-MT2 was given.
 *
 * The catalog assertions read the real `langpack_catalog.json` asset (like
 * [com.playtranslate.language.LanguagePackCatalogCoverageTest]) because the
 * failure mode being guarded is a hand-edited entry: a size that doesn't match
 * the file list makes the storage preflight lie, and a mistyped sha makes every
 * download fail verification on device with nothing to show for the round trip.
 */
@RunWith(RobolectricTestRunner::class)
class HyMt2CatalogTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private val hyMt2 get() = LanguagePackCatalogLoader.entryForKey(ctx, "engine-hy-mt2-1-8b-mnn")
    private val hyMt15 get() =
        LanguagePackCatalogLoader.entryForKey(ctx, "engine-hunyuan-mt1-5-1-8b-mnn")

    @Test
    fun `hy-mt2 entry is multi-file and internally consistent`() {
        val entry = requireNotNull(hyMt2) { "engine-hy-mt2-1-8b-mnn missing from the catalog" }
        val files = requireNotNull(entry.files) { "Hy-MT2 must ship as a MultiFile entry" }

        assertTrue("Hy-MT2 file list is empty", files.isNotEmpty())
        assertEquals(
            "top-level size must equal the sum of the file sizes (the storage " +
                "preflight and the size label both read one of the two)",
            files.sumOf { it.size },
            entry.size,
        )
        // MultiFile entries carry per-file integrity only; a stray top-level
        // sha256 would silently win in HyMt2Model.isInstalled's fallback.
        assertNull("MultiFile entries must not carry a top-level sha256", entry.sha256)

        for (f in files) {
            assertTrue(
                "${f.path}: sha256 must be 64 lowercase hex chars, was '${f.sha256}'",
                Regex("^[0-9a-f]{64}$").matches(f.sha256),
            )
            assertTrue("${f.path}: url must be https", f.url.startsWith("https://"))
            assertTrue("${f.path}: url must serve that same file", f.url.endsWith("/${f.path}"))
            assertTrue("${f.path}: size must be positive", f.size > 0)
        }
        // The runtime needs all four to load: config.json names the other three.
        val paths = files.map { it.path }.toSet()
        assertTrue(
            "missing runtime files, have $paths",
            paths.containsAll(setOf("config.json", "llm_config.json", "llm.mnn", "llm.mnn.weight")),
        )
    }

    @Test
    fun `hy-mt2 is declared apache and claims no territory restriction`() {
        val licenses = requireNotNull(hyMt2?.licenses) { "Hy-MT2 must carry a licenses block" }
        val model = licenses.first()
        assertEquals("Apache-2.0", model.license)
        // The 1.5 entry's attribution carries the Territory carve-out; if this
        // text ever grows one, the region gate has to come back with it.
        assertFalse(
            "Hy-MT2's attribution must not claim a restricted territory",
            model.attribution.contains("European Union") ||
                model.attribution.contains("Community License"),
        )
    }

    @Test
    fun `hunyuan-mt 1_5 is retired and hy-mt2 is not`() {
        // deprecated == true is the whole retirement mechanism: the Settings row
        // renders only while the model is installed, and launch drops partials.
        assertEquals(true, hyMt15?.deprecated)
        assertNotEquals(true, hyMt2?.deprecated)
    }

    @Test
    fun `hy-mt2 model helper reads its own entry and sums its files`() {
        assertEquals("engine-hy-mt2-1-8b-mnn", HyMt2Model.catalogKey)
        val files = requireNotNull(hyMt2?.files)
        assertEquals(files.sumOf { it.size }, HyMt2Model.expectedSize(ctx))
    }

    @Test
    fun `install state follows the aggregate-sha sentinel`() {
        val dir = HyMt2Model.file(ctx)
        dir.mkdirs()
        val sentinel = File(dir, ".sentinel")

        sentinel.delete()
        assertFalse("no sentinel means not installed", HyMt2Model.isInstalled(ctx))

        sentinel.writeText(MultiFileSha.aggregate(requireNotNull(hyMt2?.files)))
        assertTrue("aggregate sentinel means installed", HyMt2Model.isInstalled(ctx))

        // A catalog edit (any per-file sha/path change) flips the aggregate, which
        // is how a re-host or a re-export forces the re-download.
        sentinel.writeText("0".repeat(64))
        assertFalse("stale sentinel means not installed", HyMt2Model.isInstalled(ctx))
    }

    @Test
    fun `hy-mt2 installs beside 1_5 rather than over it`() {
        assertNotEquals(
            "a shared directory would make keeping the retired model impossible",
            HyMtModel.file(ctx).absolutePath,
            HyMt2Model.file(ctx).absolutePath,
        )
    }

    @Test
    fun `hy-mt2 takes the specialist slot without reordering the rest of the band`() {
        // Gemma moved 25 -> 24 to open 25 for Hy-MT2; every relative order below
        // it is unchanged, so a user who keeps 1.5 and never installs Hy-MT2
        // routes exactly as before.
        assertEquals(24, GemmaE2BMnnBackend.PRIORITY)
        assertEquals(25, HyMt2Backend.PRIORITY)
        assertEquals(26, HyMtBackend.PRIORITY)
        assertEquals(27, Qwen35Mnn2bBackend.PRIORITY)
        assertEquals(29, QwenMnnBackend.PRIORITY)

        val band = listOf(
            GemmaE2BMnnBackend.PRIORITY,
            HyMt2Backend.PRIORITY,
            HyMtBackend.PRIORITY,
            Qwen35Mnn2bBackend.PRIORITY,
            QwenMnnBackend.PRIORITY,
        )
        assertEquals("offline priorities must stay strictly ordered", band.sorted(), band)
        assertEquals("offline priorities must stay distinct", band.toSet().size, band.size)
    }
}
