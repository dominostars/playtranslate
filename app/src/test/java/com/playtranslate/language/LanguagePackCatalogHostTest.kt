package com.playtranslate.language

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every download URL in the shipped `langpack_catalog.json` must be on the
 * packs host, in one of its three path shapes.
 *
 * The catalog ships inside the APK, so a URL written against a backend host
 * (a GitHub release asset, a Hugging Face file, Mozilla's model bucket) pins
 * every install of that release to that backend forever. `packs.playtranslate.com`
 * is a redirect layer (the website repo's `_redirects`) that answers each path
 * with a 302 to wherever the bytes live today, so a backend move is a line
 * there instead of an app update. A pasted upstream URL in a new entry is the
 * regression this test exists to catch; `scripts/pack_urls.py` mints the
 * shapes and `scripts/verify_catalog_urls.py` holds the live layer to them.
 */
@RunWith(RobolectricTestRunner::class)
class LanguagePackCatalogHostTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun everyCatalogUrlIsOnThePacksHost() {
        val catalog = LanguagePackCatalogLoader.load(ctx)
        val offenders = catalog.packs.flatMap { (id, entry) ->
            val urls = listOfNotNull(entry.url) + entry.files.orEmpty().map { it.url }
            urls.filterNot { PACKS_URL.matches(it) }.map { "$id: $it" }
        }
        assertTrue(
            "Catalog URLs off the packs host or outside its path shapes " +
                "(mint them with scripts/pack_urls.py):\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    private companion object {
        /** `/langpacks/<tag>/<file>`, `/models/<owner>/<repo>/<path>`, `/bergamot/<path>`. */
        val PACKS_URL = Regex(
            "^https://packs\\.playtranslate\\.com/" +
                "(langpacks/[^/]+/[^/]+|models/[^/]+/[^/]+/.+|bergamot/.+)$"
        )
    }
}
