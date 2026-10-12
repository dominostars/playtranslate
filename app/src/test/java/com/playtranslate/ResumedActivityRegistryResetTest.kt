package com.playtranslate

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController

/**
 * The resumed-activity registry ([PlayTranslateApplication.foregroundDisplayId])
 * is static and outlives an Application instance. Under Robolectric every
 * test creates a new Application in a JVM whose statics persist, so an
 * activity one test resumed and never paused stayed registered for the
 * next, held by the forgotten controller until the collector ran, and a
 * later test's display query reached into a torn-down environment and
 * threw. The first case leaves such an activity behind on purpose, held
 * strongly; the second runs after it (name order) and must start clean.
 */
@RunWith(RobolectricTestRunner::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ResumedActivityRegistryResetTest {

    class Host : Activity()

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun `a1 an activity resumed in one test is registered, and left behind`() {
        assertTrue("the manifest application runs here", ctx is PlayTranslateApplication)
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        // Held past this test, as a controller a test forgot to destroy is.
        leaked = controller
        assertEquals("Host", PlayTranslateApplication.resumedActivitySimpleName())
    }

    @Test fun `a2 the next test's application starts with no resumed activity`() {
        assertNotNull("the previous case ran first", leaked)
        assertNull(PlayTranslateApplication.resumedActivitySimpleName())
        assertNull(PlayTranslateApplication.foregroundDisplayId())
    }

    private companion object {
        var leaked: ActivityController<Host>? = null
    }
}
