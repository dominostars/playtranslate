package com.playtranslate.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.net.NetworkConnectivity.InternetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * [NetworkConnectivity.state], the default network's state as the one
 * callback heard it: seeded by a query at install, then written from each
 * event's own arguments and never from a query (the platform says a query
 * inside a callback may be outdated, and an outdated read in `onLost` would
 * leave a network standing with nothing to correct it), and published after
 * the restored signal of the same event, with a count of those signals: each
 * restore is a new value even where the state stays the same, and an update
 * that restores and changes nothing is not.
 *
 * Each test installs a callback of its own and drives it by hand; the
 * application's, installed at startup, is never driven here.
 */
@RunWith(RobolectricTestRunner::class)
class NetworkConnectivityStateTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val cm: ConnectivityManager = ctx.getSystemService(ConnectivityManager::class.java)
    private val wifi = ShadowNetwork.newInstance(101)
    private val cell = ShadowNetwork.newInstance(102)

    private val state: InternetState get() = NetworkConnectivity.state.value.internet

    /** Installs a callback and returns it: the one the install added. */
    private fun install(onRestored: () -> Unit = {}): ConnectivityManager.NetworkCallback {
        val before = shadowOf(cm).networkCallbacks.toSet()
        NetworkConnectivity.install(ctx, onRestored)
        return (shadowOf(cm).networkCallbacks - before).single()
    }

    private fun caps(validated: Boolean): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also {
            if (validated) shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

    @Test fun `install seeds the state from the query, network or none`() {
        install()
        assertEquals(NetworkConnectivity.internetState(ctx), state)

        shadowOf(cm).setActiveNetworkInfo(null)
        install()
        assertEquals(InternetState.NONE, NetworkConnectivity.internetState(ctx))
        assertEquals(InternetState.NONE, state)
    }

    @Test fun `each event writes the state from its own arguments, not from a query`() {
        val callback = install()
        // The query answers "no network" throughout, so every state below
        // other than NONE can only have come from the events.
        shadowOf(cm).setActiveNetworkInfo(null)

        callback.onAvailable(wifi)
        assertEquals(InternetState.UNVALIDATED, state)
        callback.onCapabilitiesChanged(wifi, caps(validated = true))
        assertEquals(InternetState.VALIDATED, state)
        callback.onBlockedStatusChanged(wifi, true)
        assertEquals("a network this app is blocked from is none", InternetState.NONE, state)
        callback.onBlockedStatusChanged(wifi, false)
        assertEquals(InternetState.VALIDATED, state)
        callback.onCapabilitiesChanged(wifi, caps(validated = false))
        assertEquals(InternetState.UNVALIDATED, state)
        callback.onLost(wifi)
        assertEquals(InternetState.NONE, state)
    }

    @Test fun `a new default network starts over and the old one's events change nothing`() {
        val callback = install()
        callback.onAvailable(wifi)
        callback.onCapabilitiesChanged(wifi, caps(validated = true))
        callback.onBlockedStatusChanged(wifi, true)
        assertEquals(InternetState.NONE, state)

        callback.onAvailable(cell)
        assertEquals("neither the old capabilities nor the old block carry over",
            InternetState.UNVALIDATED, state)

        callback.onCapabilitiesChanged(wifi, caps(validated = true))
        callback.onBlockedStatusChanged(wifi, true)
        callback.onLost(wifi)
        assertEquals(InternetState.UNVALIDATED, state)

        callback.onCapabilitiesChanged(cell, caps(validated = true))
        assertEquals(InternetState.VALIDATED, state)
    }

    @Test fun `the state changes only after the restored signal of the same event`() {
        shadowOf(cm).setActiveNetworkInfo(null)
        val seenAtRestore = mutableListOf<InternetState>()
        val callback = install { seenAtRestore += NetworkConnectivity.state.value.internet }
        assertEquals(InternetState.NONE, state)

        callback.onAvailable(wifi)
        callback.onCapabilitiesChanged(wifi, caps(validated = true))

        assertEquals(listOf(InternetState.NONE, InternetState.UNVALIDATED), seenAtRestore)
        assertEquals(InternetState.VALIDATED, state)
    }

    @Test fun `the restored signal still fires on a gain and once per validation`() {
        var restored = 0
        val callback = install { restored++ }

        callback.onAvailable(wifi)
        assertEquals(1, restored)
        callback.onCapabilitiesChanged(wifi, caps(validated = true))
        assertEquals(2, restored)
        callback.onCapabilitiesChanged(wifi, caps(validated = true))
        assertEquals("a repeated VALIDATED is not a gain", 2, restored)
        callback.onCapabilitiesChanged(wifi, caps(validated = false))
        callback.onCapabilitiesChanged(wifi, caps(validated = true))
        assertEquals("validated again after losing it", 3, restored)
        callback.onBlockedStatusChanged(wifi, true)
        callback.onLost(wifi)
        assertEquals("a loss is not a gain", 3, restored)
        assertEquals("the state counts exactly the restores", restored, NetworkConnectivity.state.value.restores)
    }

    // Codex: a restore that left the state as it was went unheard, since a
    // flow drops a value equal to the last.
    @Test fun `a switch between two unvalidated networks is a new value`() {
        val callback = install()
        callback.onAvailable(wifi)
        val onWifi = NetworkConnectivity.state.value

        callback.onAvailable(cell)

        assertEquals(InternetState.UNVALIDATED, onWifi.internet)
        assertEquals("the state is the same", InternetState.UNVALIDATED, state)
        assertNotEquals("the restore is news", onWifi, NetworkConnectivity.state.value)
    }

    @Test fun `an update that restores nothing and changes nothing is the same value`() {
        val callback = install()
        callback.onAvailable(wifi)
        callback.onCapabilitiesChanged(wifi, caps(validated = true))
        val validated = NetworkConnectivity.state.value

        // As a bandwidth or signal change delivers it.
        callback.onCapabilitiesChanged(wifi, caps(validated = true))

        assertEquals(validated, NetworkConnectivity.state.value)
    }
}
