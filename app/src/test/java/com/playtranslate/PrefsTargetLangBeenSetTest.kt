package com.playtranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [Prefs.hasTargetLangBeenSet].
 *
 * The welcome-page onboarding gate relies on `sp.contains(KEY_TARGET_LANG)`
 * as the "has the user ever explicitly picked a target" signal. If the
 * contract drifts (e.g. someone changes the setter to only write when
 * the value differs from the default), every fresh install skips the
 * welcome's Your Language row silently. Testing the contract directly
 * guards that edge.
 */
@RunWith(RobolectricTestRunner::class)
class PrefsTargetLangBeenSetTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After fun tearDown() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun `hasTargetLangBeenSet is false before any write`() {
        assertFalse(Prefs(ctx).hasTargetLangBeenSet)
    }

    @Test fun `hasTargetLangBeenSet is true after setting a non-default target`() {
        Prefs(ctx).targetLang = "es"
        assertTrue(Prefs(ctx).hasTargetLangBeenSet)
    }

    @Test fun `hasTargetLangBeenSet is true even when writing the default English target`() {
        // Ensures the flag tracks presence of the key rather than value-differs-
        // from-default. The welcome flow's Continue-with-English commit relies
        // on this so upgrade-migrated users stay migrated.
        Prefs(ctx).targetLang = "en"
        assertTrue(Prefs(ctx).hasTargetLangBeenSet)
    }
}
