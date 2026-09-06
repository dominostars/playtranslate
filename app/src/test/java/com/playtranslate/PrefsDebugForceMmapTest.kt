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
 * The force-mmap debug override must never act outside a debug build: debug
 * and release share an applicationId, so a release install inherits the pref
 * a debug build wrote, and release has no Settings row to clear it.
 */
@RunWith(RobolectricTestRunner::class)
class PrefsDebugForceMmapTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val key = "debug_force_mmap_weights"

    private fun sp() = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    @Before fun clearPrefs() { sp().edit().clear().commit() }
    @After fun tearDown() { sp().edit().clear().commit() }

    @Test
    fun debugBuild_honoursStoredOverride() {
        val prefs = Prefs(ctx, SecretCipher, debugBuild = true)
        assertFalse(prefs.debugForceMmapWeights)
        prefs.debugForceMmapWeights = true
        assertTrue(prefs.debugForceMmapWeights)
    }

    @Test
    fun releaseBuild_ignoresStoredOverride() {
        // A debug build wrote true; a release install now reads the same file.
        sp().edit().putBoolean(key, true).commit()
        val prefs = Prefs(ctx, SecretCipher, debugBuild = false)
        assertFalse(prefs.debugForceMmapWeights)
        // The gate is on the read, not a silent rewrite of the stored value.
        assertTrue(sp().getBoolean(key, false))
    }

    @Test
    fun releaseBuild_setterStillWritesButGetterStaysOff() {
        val prefs = Prefs(ctx, SecretCipher, debugBuild = false)
        prefs.debugForceMmapWeights = true
        assertTrue(sp().getBoolean(key, false))
        assertFalse(prefs.debugForceMmapWeights)
    }

    @Test
    fun defaultSeam_followsBuildConfig() {
        val prefs = Prefs(ctx, SecretCipher)
        prefs.debugForceMmapWeights = true
        assertEquals(BuildConfig.DEBUG, prefs.debugForceMmapWeights)
    }
}
