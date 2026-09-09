package com.playtranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.security.SecretCipher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every debug override that changes what the app does (not just what it
 * logs) must read as OFF outside a debug build: debug and release share an
 * applicationId and signing key, so a release install inherits the pref a
 * debug build wrote, and release has no Settings row to clear it. Same
 * contract as [PrefsDebugForceMmapTest], one row per gated pref so a new
 * override is added here, not exempted.
 */
@RunWith(RobolectricTestRunner::class)
class PrefsDebugOverrideGateTest {

    private class GatedOverride(
        val name: String,
        val key: String,
        val get: Prefs.() -> Boolean,
        val set: Prefs.(Boolean) -> Unit,
    )

    private val overrides = listOf(
        GatedOverride(
            "debugShortTextRouting", "debug_short_text_routing",
            { debugShortTextRouting }, { debugShortTextRouting = it },
        ),
        GatedOverride(
            "debugLogTrace", "debug_log_trace",
            { debugLogTrace }, { debugLogTrace = it },
        ),
    )

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun sp() = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    @Before fun clearPrefs() { sp().edit().clear().commit() }
    @After fun tearDown() { sp().edit().clear().commit() }

    @Test
    fun debugBuild_honoursStoredOverride() {
        for (o in overrides) {
            clearPrefs()
            val prefs = Prefs(ctx, SecretCipher, debugBuild = true)
            assertFalse(o.name, o.get(prefs))
            o.set(prefs, true)
            assertTrue(o.name, o.get(prefs))
        }
    }

    @Test
    fun releaseBuild_ignoresStoredOverride() {
        for (o in overrides) {
            clearPrefs()
            // A debug build wrote true; a release install now reads the same file.
            sp().edit().putBoolean(o.key, true).commit()
            val prefs = Prefs(ctx, SecretCipher, debugBuild = false)
            assertFalse(o.name, o.get(prefs))
            // The gate is on the read, not a silent rewrite of the stored value.
            assertTrue(o.name, sp().getBoolean(o.key, false))
        }
    }

    @Test
    fun releaseBuild_setterStillWritesButGetterStaysOff() {
        for (o in overrides) {
            clearPrefs()
            val prefs = Prefs(ctx, SecretCipher, debugBuild = false)
            o.set(prefs, true)
            assertTrue(o.name, sp().getBoolean(o.key, false))
            assertFalse(o.name, o.get(prefs))
        }
    }

    @Test
    fun defaultSeam_followsBuildConfig() {
        for (o in overrides) {
            clearPrefs()
            val prefs = Prefs(ctx, SecretCipher)
            o.set(prefs, true)
            assertEquals(o.name, BuildConfig.DEBUG, o.get(prefs))
        }
    }
}
