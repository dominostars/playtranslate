package com.playtranslate.language

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression guard for the class of bug an adversarial review looked for when
 * Bergamot coverage was widened to Mozilla's tiny tier: a `bergamot-*` catalog
 * entry whose model is NOT the base-memory architecture but which carries no
 * `arch` block. [com.playtranslate.translation.bergamot.BergamotModelManager.filesFor]
 * falls back to the base-memory layer counts (6 enc / 4 dec) when `arch` is
 * absent, so such an entry downloads and hash-verifies fine, then mis-loads the
 * weights into fluent garbage at runtime.
 *
 * `gen_bergamot_catalog.py` enforces this at generation time (it refuses to emit
 * an entry whose metadata.json can't be read); nothing re-checks the committed
 * catalog after hand edits or merges — that's this test.
 *
 * Tier is inferred from model-file size, which is structural, not incidental:
 * the tiny arch (dim-256, ~17M params, int8) tops out at ~17.1 MB even with the
 * largest shipped vocab, while base-memory (dim-512, ~39M params) starts at
 * ~31.6 MB — a parameter-count gap no vocab-size variation can bridge. Any
 * model under the midpoint must therefore declare its (non-default) arch.
 */
@RunWith(RobolectricTestRunner::class)
class BergamotCatalogArchTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun everyNonBaseMemoryModelDeclaresItsArch() {
        val packs = LanguagePackCatalogLoader.load(ctx).packs
            .filterKeys { it.startsWith(BERGAMOT_PREFIX) }
        assertTrue("no bergamot entries found — catalog failed to load?", packs.isNotEmpty())

        val offenders = packs.mapNotNull { (key, entry) ->
            val model = entry.files?.firstOrNull { MODEL_RE.matches(it.path) }
                ?: return@mapNotNull "$key: no model file matching engine regex"
            when {
                entry.arch == null && model.size < BASE_MEMORY_MIN_MODEL_BYTES ->
                    "$key: ${model.size}-byte model is below the base-memory floor " +
                        "but has no arch block (would mis-load as 6 enc / 4 dec)"
                entry.arch != null && entry.arch.let {
                    it.encoderLayers <= 0 || it.decoderLayers <= 0 ||
                        it.feedForwardDepth <= 0 || it.numHeads <= 0
                } ->
                    // filesFor treats a non-positive field as "no arch" and falls
                    // back to the default — the same mis-load, one step removed.
                    "$key: arch present but has a non-positive field: ${entry.arch}"
                else -> null
            }
        }
        assertTrue(
            "Bergamot entries the engine would mis-load:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    companion object {
        private const val BERGAMOT_PREFIX = "bergamot-"

        // Midpoint of the structural size gap described in the class kdoc:
        // largest tiny model shipped = 17,141,051 B; smallest base-memory =
        // 31,561,787 B.
        private const val BASE_MEMORY_MIN_MODEL_BYTES = 25_000_000L

        // Mirrors BergamotModelManager.MODEL_RE (private there): the file the
        // native engine will actually open.
        private val MODEL_RE = Regex("""model\..*\.intgemm\.alphas\.bin""")
    }
}
