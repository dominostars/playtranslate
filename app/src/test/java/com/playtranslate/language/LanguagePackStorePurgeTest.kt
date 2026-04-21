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
 * Unit tests for [LanguagePackStore.purgeLegacyJaDatabase].
 *
 * Runs under Robolectric for a real [Context.getDatabasePath] resolver.
 * Guards the upgrade-cleanup behavior: after JA pack install succeeds,
 * the legacy ~45 MB `databases/jmdict.db` file must be deleted so
 * upgraders don't carry an orphaned DB forever. A silent regression
 * (e.g. install refactor that forgets to call the purge) wouldn't
 * surface in any user bug report — users don't typically notice 45 MB
 * of dead disk.
 */
@RunWith(RobolectricTestRunner::class)
class LanguagePackStorePurgeTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @After fun cleanup() {
        ctx.getDatabasePath("jmdict.db").delete()
    }

    @Test fun `purge deletes an existing legacy JMdict DB`() {
        val legacy = ctx.getDatabasePath("jmdict.db").apply {
            parentFile?.mkdirs()
            writeText("stub contents")
        }
        assertTrue("precondition: legacy DB present", legacy.exists())

        LanguagePackStore.purgeLegacyJaDatabase(ctx)

        assertFalse("legacy DB should be gone after purge", legacy.exists())
    }

    @Test fun `purge is a noop when legacy DB is absent`() {
        val legacy = ctx.getDatabasePath("jmdict.db")
        assertFalse("precondition: no legacy DB", legacy.exists())

        // No throw, no crash.
        LanguagePackStore.purgeLegacyJaDatabase(ctx)

        assertFalse(legacy.exists())
    }
}
