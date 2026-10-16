package com.playtranslate

import android.content.Context
import android.net.ConnectivityManager
import android.os.Looper
import android.view.Display
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CaptureLifecycle
import com.playtranslate.net.NetworkConnectivity
import com.playtranslate.ui.FloatingIconMenu
import com.playtranslate.ui.FloatingOverlayIcon
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import java.time.Duration

/**
 * The quick menu's PlayTranslate Cloud strip as the overlay controller
 * drives it: built only while the cloud switch is on, redrawn at every
 * change of the device's network and every restore while the menu is up,
 * and left alone once the menu closes, whichever menu replaced it. The
 * Overlays row's redraw needs a live session, so it is
 * [QuickMenuOverlaysRowTest]'s.
 *
 * A client key cannot be stored here (the AndroidKeyStore is
 * instrumented-only and [Prefs] stores no plaintext), so every value the
 * controller computes reads "unavailable"; a redraw is seen by setting the
 * glyph to the other value first. What the value is for each state of the
 * service is [com.playtranslate.cloud.PtCloudNextCaptureTest]'s.
 *
 * The network is driven through a callback this test installs; the
 * application's own, installed at startup, writes the same state and is
 * never driven here.
 */
@RunWith(RobolectricTestRunner::class)
class QuickMenuCloudStripTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private val wifi = ShadowNetwork.newInstance(101)
    private val cell = ShadowNetwork.newInstance(102)
    private lateinit var controller: OverlayUiController
    private lateinit var icon: FloatingOverlayIcon
    private lateinit var network: ConnectivityManager.NetworkCallback

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Before fun setUp() {
        clearPrefs()
        CaptureLifecycle.setFloatingIconSuppressed(ctx, false)
        prefs.captureDisplayIds = setOf(Display.DEFAULT_DISPLAY)

        val a11y = Robolectric.buildService(PlayTranslateAccessibilityService::class.java).create()
        PlayTranslateAccessibilityService.instance = a11y.get()
        controller = a11y.get().overlayUiController
        controller.reconcileFloatingIcons(freshAppearance = false)
        settle()
        icon = iconOnDefaultDisplay()

        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val before = shadowOf(cm).networkCallbacks.toSet()
        NetworkConnectivity.install(ctx) {}
        network = (shadowOf(cm).networkCallbacks - before).single()
        network.onAvailable(wifi)
    }

    @After fun tearDown() {
        controller.hideAll()
        PlayTranslateAccessibilityService.instance = null
        CaptureLifecycle.setFloatingIconSuppressed(ctx, true)
        // Let the churn gate finish every removal inside this test.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        clearPrefs()
    }

    private fun iconOnDefaultDisplay(): FloatingOverlayIcon {
        val handles = OverlayUiController::class.java.getDeclaredField("iconHandles")
            .apply { isAccessible = true }.get(controller) as Map<*, *>
        val handle = handles[Display.DEFAULT_DISPLAY]
        assertNotNull("an icon on the default display", handle)
        return handle!!.javaClass.getDeclaredField("icon").apply { isAccessible = true }
            .get(handle) as FloatingOverlayIcon
    }

    /** Open the quick menu with a tap on the icon (the tap's default). */
    private fun openQuickMenu(): FloatingIconMenu {
        icon.onTap!!.invoke()
        val menu = OverlayUiController::class.java.getDeclaredField("floatingMenu")
            .apply { isAccessible = true }.get(controller) as FloatingIconMenu?
        assertNotNull("the tap opened the quick menu", menu)
        return menu!!
    }

    private fun cloudJob(): Job? = OverlayUiController::class.java.getDeclaredField("floatingMenuCloudJob")
        .apply { isAccessible = true }.get(controller) as Job?

    private fun strip(menu: FloatingIconMenu): View = FloatingIconMenu::class.java
        .getDeclaredField("cloudHeader").apply { isAccessible = true }.get(menu) as View

    @Test fun `with the switch off the menu has no strip and follows nothing`() {
        prefs.ptCloudEnabled = false
        val menu = openQuickMenu()
        assertEquals(View.GONE, strip(menu).visibility)
        assertNull(cloudJob())
    }

    @Test fun `with the switch on the menu has the strip and follows the network`() {
        prefs.ptCloudEnabled = true
        val menu = openQuickMenu()
        assertEquals(View.VISIBLE, strip(menu).visibility)
        assertFalse("no key here, so unavailable", menu.cloudAvailable)
        assertTrue(cloudJob()!!.isActive)
    }

    @Test fun `every network change while the menu is up redraws the strip`() {
        prefs.ptCloudEnabled = true
        val menu = openQuickMenu()

        menu.cloudAvailable = true
        network.onLost(wifi)
        settle()
        assertEquals(NetworkConnectivity.InternetState.NONE, NetworkConnectivity.state.value.internet)
        assertFalse("redrawn on the loss", menu.cloudAvailable)

        menu.cloudAvailable = true
        network.onAvailable(wifi)
        settle()
        assertFalse("redrawn on the gain", menu.cloudAvailable)
    }

    // Codex: the switch ends a "Connection failed" cooldown, and the strip
    // never heard of it while the state stayed UNVALIDATED.
    @Test fun `a switch between two unvalidated networks redraws the strip`() {
        prefs.ptCloudEnabled = true
        val menu = openQuickMenu()
        assertEquals(NetworkConnectivity.InternetState.UNVALIDATED, NetworkConnectivity.state.value.internet)

        menu.cloudAvailable = true
        network.onAvailable(cell)
        settle()
        assertEquals(NetworkConnectivity.InternetState.UNVALIDATED, NetworkConnectivity.state.value.internet)
        assertFalse("redrawn on the restore", menu.cloudAvailable)
    }

    @Test fun `closing the menu stops it following the network`() {
        prefs.ptCloudEnabled = true
        val menu = openQuickMenu()
        val job = cloudJob()!!

        menu.onDismiss!!.invoke()
        assertTrue(job.isCancelled)
        assertNull(cloudJob())

        menu.cloudAvailable = true
        network.onLost(wifi)
        settle()
        assertTrue("a closed menu is left alone", menu.cloudAvailable)
    }

    @Test fun `a menu opened over another follows alone`() {
        prefs.ptCloudEnabled = true
        val first = openQuickMenu()
        val second = openQuickMenu()

        first.cloudAvailable = true
        second.cloudAvailable = true
        network.onLost(wifi)
        settle()
        assertTrue("the replaced menu is left alone", first.cloudAvailable)
        assertFalse(second.cloudAvailable)
    }
}
