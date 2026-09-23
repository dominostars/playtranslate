package com.playtranslate.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The device rule for the display-service deadlock guard: AYN firmware
 *  only, as [android.os.Build.MANUFACTURER] spells it, case and padding
 *  tolerated; everything else keeps the unguarded capture path. */
class DisplayServiceGuardTest {

    @Test
    fun `AYN applies, however cased or padded`() {
        assertTrue(DisplayServiceGuard.appliesTo("AYN"))
        assertTrue(DisplayServiceGuard.appliesTo("ayn"))
        assertTrue(DisplayServiceGuard.appliesTo(" AYN "))
    }

    @Test
    fun `other and missing manufacturers do not apply`() {
        assertFalse(DisplayServiceGuard.appliesTo("Google"))
        assertFalse(DisplayServiceGuard.appliesTo("Xiaomi"))
        assertFalse(DisplayServiceGuard.appliesTo(""))
        assertFalse(DisplayServiceGuard.appliesTo(null))
    }
}
