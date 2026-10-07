package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.Prefs
import com.playtranslate.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The Fix disappearing icon page around its cards: the centered label that
 * takes their place once every setting is made, and the Report a bug row
 * beneath, which shows either way and is the Settings row (same words,
 * tap emails the logs, hold shares them).
 */
@RunWith(RobolectricTestRunner::class)
class KeepRunningPageTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    /** FileProvider keeps its path strategy in a static map keyed by
     *  authority, built from the first context that asks; Robolectric
     *  gives every test a fresh data directory, so a strategy left by an
     *  earlier test (this class's or another's in the same sandbox) makes
     *  the attachment URI throw "Failed to find configured root", and in
     *  the coroutine that only surfaces as nothing being sent. Cleared so
     *  each test builds its own. */
    @Before
    fun resetFileProviderCache() {
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
    }

    @After
    fun tearDown() {
        PlayTranslateAccessibilityService.instance = null
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    /** Every card's condition met: battery unrestricted, the accessibility
     *  service enabled and bound, the tile added. Robolectric's build is
     *  ROM OTHER, so these three are the whole list. */
    private fun settleEverySetting() {
        shadowOf(ctx.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(ctx.packageName, true)
        PlayTranslateAccessibilityService.instance = PlayTranslateAccessibilityService()
        Prefs(ctx).quickTileAdded = true
    }

    private fun launch(): ActivityController<KeepRunningActivity> =
        Robolectric.buildActivity(KeepRunningActivity::class.java).setup()

    private fun Activity.items() = findViewById<ViewGroup>(R.id.keepRunningItems)
    private fun Activity.empty() = findViewById<TextView>(R.id.tvKeepRunningEmpty)
    private fun Activity.reportCard() = findViewById<View>(R.id.cardReportBug)

    @Test
    fun `with settings left the cards show and the label stays hidden`() {
        val activity = launch().get()
        assertEquals(3, activity.items().childCount)
        assertFalse(activity.empty().isVisible)
        assertTrue(activity.reportCard().isVisible)
    }

    @Test
    fun `once every setting is made the label takes the cards' place and the bug report stays`() {
        val controller = launch()
        settleEverySetting()
        // The page rebuilds on every return, the same way a card drops
        // after the user changes its setting.
        controller.pause().resume()
        val activity = controller.get()
        assertEquals(0, activity.items().childCount)
        assertTrue(activity.empty().isVisible)
        assertEquals(ctx.getString(R.string.keep_running_empty), activity.empty().text.toString())
        assertTrue(activity.reportCard().isVisible)
    }

    @Test
    fun `the Report a bug row carries the Settings row's words and both gestures`() {
        val row = launch().get().findViewById<View>(R.id.rowReportBug)
        assertEquals(
            ctx.getString(R.string.settings_support_report_bug_title),
            row.findViewById<TextView>(R.id.tvRowTitle).text.toString(),
        )
        val subtitle = row.findViewById<TextView>(R.id.tvRowSubtitle)
        assertTrue(subtitle.isVisible)
        assertEquals(ctx.getString(R.string.settings_support_report_bug_subtitle), subtitle.text.toString())
        assertTrue(row.hasOnClickListeners())
        assertTrue(row.isLongClickable)
    }

    @Test
    fun `tapping Report a bug sends the logs`() {
        val activity = launch().get()
        activity.findViewById<View>(R.id.rowReportBug).performClick()
        val chooser = awaitStartedActivity(activity)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        // The test package manager has no email app, so the email falls
        // back to the plain share sheet with the one logcat file.
        assertEquals(Intent.ACTION_SEND, send.action)
        assertTrue(send.hasExtra(Intent.EXTRA_STREAM))
    }

    @Test
    fun `holding Report a bug shares the logs`() {
        val activity = launch().get()
        activity.findViewById<View>(R.id.rowReportBug).performLongClick()
        val chooser = awaitStartedActivity(activity)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(ctx.getString(R.string.settings_debug_export_logs_subject), send.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    /** The logs are gathered on the IO dispatcher and the chooser launched
     *  back on main, so the main looper is idled until it arrives. */
    private fun awaitStartedActivity(activity: Activity): Intent {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(activity).nextStartedActivity?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("no activity started within 10 s")
    }
}
