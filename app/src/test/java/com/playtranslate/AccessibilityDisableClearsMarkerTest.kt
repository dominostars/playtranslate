package com.playtranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.SessionMarker
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Every accessibility Turn Off path ends in [PlayTranslateAccessibilityService.disable],
 *  which must clear the persisted session record (Codex finding 2026-10-06). */
@RunWith(RobolectricTestRunner::class)
class AccessibilityDisableClearsMarkerTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun `disable clears the session record`() {
        val prefs = Prefs(ctx)
        prefs.sessionOnBoot = 57
        prefs.sessionOnProcessStart = 10L
        prefs.sessionOnPid = 4660

        PlayTranslateAccessibilityService.disable(ctx, "test")

        assertEquals(-1, prefs.sessionOnBoot)
        assertEquals(0L, prefs.sessionOnProcessStart)
        assertEquals(0, prefs.sessionOnPid)
        SessionMarker.markOff(ctx)
    }
}
