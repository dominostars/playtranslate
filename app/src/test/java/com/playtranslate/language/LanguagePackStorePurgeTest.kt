package com.playtranslate.language

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the eager legacy-cleanup behavior baked into
 * [LanguagePackStore.isInstalled]. A pre-LanguagePackStore `databases/
 * jmdict.db` can no longer power the JA engine (tokenizer binaries are
 * stripped from the APK), so [isInstalled] must delete it on sight and
 * report the pack as missing to force a fresh download. A silent
 * regression (refactor that forgets to purge) would leave upgraders
 * carrying an orphaned ~45 MB DB forever.
 */
@RunWith(RobolectricTestRunner::class)
class LanguagePackStorePurgeTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @After fun cleanup() {
        ctx.getDatabasePath("jmdict.db").delete()
    }

    @Test fun `isInstalled deletes a legacy JMdict DB and reports missing`() {
        val legacy = ctx.getDatabasePath("jmdict.db").apply {
            parentFile?.mkdirs()
            writeText("stub contents")
        }
        assertTrue("precondition: legacy DB present", legacy.exists())

        val installed = LanguagePackStore.isInstalled(ctx, SourceLangId.JA)

        assertFalse("legacy DB alone does not count as installed", installed)
        assertFalse("legacy DB should be gone after isInstalled check", legacy.exists())
    }

    @Test fun `isInstalled is a noop when legacy DB is absent`() {
        val legacy = ctx.getDatabasePath("jmdict.db")
        assertFalse("precondition: no legacy DB", legacy.exists())

        LanguagePackStore.isInstalled(ctx, SourceLangId.JA)

        assertFalse(legacy.exists())
    }
}
