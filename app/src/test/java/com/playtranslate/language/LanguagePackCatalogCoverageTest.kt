package com.playtranslate.language

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression guard for the class of bug an adversarial review caught when Arabic
 * was added: a [SourceLangId] enum value makes the language appear in
 * `LanguageSetupActivity`'s picker (built from [SourceLangId.entries]), but if no
 * matching source dictionary-pack entry exists in `langpack_catalog.json`, the
 * language compiles and shows as supported yet fails the install path with
 * "No catalog entry for <code>".
 *
 * Every source language must resolve to a catalog entry. [entryFor] keys off
 * [SourceLangId.packId], so variants that share a pack (e.g. ZH_HANT → zh) are
 * covered. Bundled packs still have an entry (with bundled=true), so a null
 * result genuinely means the pack is unshippable.
 */
@RunWith(RobolectricTestRunner::class)
class LanguagePackCatalogCoverageTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun everySourceLangResolvesToACatalogEntry() {
        val missing = SourceLangId.entries.filter {
            LanguagePackCatalogLoader.entryFor(ctx, it) == null
        }
        assertTrue(
            "Source languages exposed in the picker with no source catalog entry " +
                "(selecting them would fail install with \"No catalog entry\"): " +
                missing.joinToString { "${it.name}(${it.code})" },
            missing.isEmpty(),
        )
    }
}
