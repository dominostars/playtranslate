package com.playtranslate.language

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the additive-pack-upgrade eviction path for Thai.
 *
 * `PackUpgradeOrchestrator.upgradeSourcePack` now calls
 * `SourceLanguageEngines.releaseForPack(sid.packId)` for EVERY source language
 * after a successful install (previously JA-only). Without that, a warm
 * [ThaiEngine] cached before an in-place additive swap would keep serving its
 * old segmenter trie (built from the old `words.txt`) and its old dict handle
 * until process death. This test pins the primitive that fix relies on: a
 * cached Thai engine must be dropped + rebuilt by `releaseForPack`, and
 * `ThaiEngine.close()` must not throw on an engine whose lazy trie/dict were
 * never opened. (The full orchestrator flow is manual-tested on Thor — see
 * [PackUpgradeOrchestratorTest].)
 */
@RunWith(RobolectricTestRunner::class)
class SourceLanguageEnginesEvictionTest {

    private lateinit var ctx: Context

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
    }

    @After fun tearDown() {
        SourceLanguageEngines.releaseForPack(SourceLangId.TH.packId)
    }

    @Test fun `releaseForPack evicts and rebuilds the Thai engine`() {
        val first = SourceLanguageEngines.get(ctx, SourceLangId.TH)
        assertTrue("TH resolves to a ThaiEngine", first is ThaiEngine)
        // Same instance while cached.
        assertTrue(first === SourceLanguageEngines.get(ctx, SourceLangId.TH))

        SourceLanguageEngines.releaseForPack(SourceLangId.TH.packId) // closes + evicts

        val second = SourceLanguageEngines.get(ctx, SourceLangId.TH)
        assertNotSame("releaseForPack must evict the cached ThaiEngine", first, second)
    }
}
