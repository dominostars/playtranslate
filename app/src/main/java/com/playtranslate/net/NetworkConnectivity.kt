package com.playtranslate.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's one view of device connectivity.
 *
 * Three things live here so no caller grows its own copy:
 *  - [isAvailable], the synchronous "is there a default network" query
 *    the degraded-translation notes use to say "no internet" instead of
 *    blaming a backend;
 *  - [install], a process-lifetime default-network callback that fires
 *    [onRestored] whenever the device GAINS connectivity. Its consumer is
 *    the translation cooldown system: a backend cooled down for
 *    "Connection failed" was punished for the device being offline, and
 *    the user coming back from airplane mode expects online services to
 *    work at once, not after the cooldown a dead network had climbed to;
 *  - [state], the default network's [InternetState] as that same callback
 *    last heard it, with a count of its restores, for a reader that follows
 *    connectivity both ways while it shows something (the floating menu's
 *    PlayTranslate Cloud header).
 *
 * What counts as a gain, and why both:
 *  - `onAvailable` — a default network appeared (airplane mode off, wifi
 *    associated, a wifi→cellular switch). Fired on its own because some
 *    networks never reach VALIDATED (the OS captive-portal probe is
 *    blocked on plenty of real networks) yet reach the providers fine.
 *  - the first `onCapabilitiesChanged` carrying VALIDATED for that
 *    network — the OS confirmed upstream reachability, typically a second
 *    or two after `onAvailable` when DNS wasn't up yet for the first fire.
 *
 * `onCapabilitiesChanged` also fires for metered / congested / suspended
 * flips, so VALIDATED is edge-detected per [Network] rather than fired on
 * every delivery; [onRestored] is idempotent either way.
 *
 * Registration replays the current default network immediately, so a
 * process that starts online fires once at startup — deliberately: a
 * "Connection failed" cooldown persisted by a previous, offline session
 * is exactly the stale state the consumer wants dropped.
 *
 * Callbacks arrive on the ConnectivityManager's own thread; [onRestored]
 * must be thread-safe. The callback is never unregistered — it lives as
 * long as the process, like the registry it feeds.
 */
object NetworkConnectivity {

    private const val TAG = "NetworkConnectivity"

    /** True when the device has a default network. The single copy of the
     *  check every "no internet" note keys on. */
    fun isAvailable(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm?.activeNetwork != null
    }

    /** What the OS knows about reaching the internet right now. */
    enum class InternetState {
        /** No default network at all (airplane mode, wifi off with no data). */
        NONE,
        /** A default network the OS has NOT confirmed reaches the internet: a
         *  captive portal, wifi without internet, a network still coming up —
         *  or one whose connectivity probe is blocked but works fine, which is
         *  why this is only ever read together with other evidence. */
        UNVALIDATED,
        /** A default network the OS confirmed reaches the internet. */
        VALIDATED,
    }

    /** The current [InternetState]. Safe from any thread. Unknown
     *  capabilities read as [InternetState.UNVALIDATED], the claim that
     *  asserts least. */
    fun internetState(context: Context): InternetState {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return InternetState.UNVALIDATED
        val network = cm.activeNetwork ?: return InternetState.NONE
        val caps = cm.getNetworkCapabilities(network) ?: return InternetState.UNVALIDATED
        return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            InternetState.VALIDATED
        } else {
            InternetState.UNVALIDATED
        }
    }

    /** One value of [state]: the default network's [internet] state and how
     *  many restores [install]'s callback has run ([restores]). */
    data class Connectivity(val internet: InternetState, val restores: Int)

    private val published = MutableStateFlow(Connectivity(InternetState.UNVALIDATED, restores = 0))

    /**
     * The default network as [install]'s callback last heard it.
     *
     * [Connectivity.internet] is its [InternetState] in [internetState]'s
     * terms: [InternetState.NONE] with no default network or one this app is
     * blocked from (which [ConnectivityManager.getActiveNetwork] reports as
     * no network, so [internetState] reads NONE too), else VALIDATED when its
     * latest capabilities carry VALIDATED, else UNVALIDATED.
     *
     * [Connectivity.restores] counts the restores (`onRestored`) the callback
     * has run, so every restore is a new value: a reader that also reads
     * what a restore resets (a "Connection failed" cooldown) hears of each
     * one, though a switch between two networks the OS has not validated
     * leaves [Connectivity.internet] UNVALIDATED, and a flow drops a value
     * equal to the last.
     *
     * Written from the events' own arguments, never from a query: the
     * platform orders a callback's arguments against the other callbacks,
     * and says a query made inside a callback may return something outdated
     * or null. An outdated read in `onLost` would leave a network standing
     * here after the last one went, and with no network nothing would come
     * to correct it.
     *
     * [install] seeds it with [internetState], since a process that starts
     * with no network gets no event. Before [install], and in a process
     * without a ConnectivityManager, it reads UNVALIDATED, the claim that
     * asserts least, as [internetState] does when it cannot ask.
     */
    val state: StateFlow<Connectivity> = published.asStateFlow()

    /** Wire once from Application.onCreate, AFTER the consumer is ready to
     *  receive (the registration replays the current network). */
    fun install(context: Context, onRestored: () -> Unit) {
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            Log.w(TAG, "no ConnectivityManager — connectivity signals disabled")
            return
        }
        published.value = Connectivity(internetState(app), restores = 0)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            /** The default network we have already fired VALIDATED for. */
            private var validated: Network? = null

            // The default network as these events describe it, for [state]:
            // which one, whether its latest capabilities carry VALIDATED,
            // and whether this app is blocked from it. Since Android O a
            // new default network's onAvailable is followed at once by its
            // capabilities and then its blocked status, so both start over
            // at onAvailable.
            private var current: Network? = null
            private var currentValidated = false
            private var currentBlocked = false

            /** The restores run so far, for [state]. */
            private var restores = 0

            override fun onAvailable(network: Network) {
                validated = null
                current = network
                currentValidated = false
                currentBlocked = false
                Log.i(TAG, "default network available")
                restore()
                publish()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (isValidated && validated != network) {
                    validated = network
                    Log.i(TAG, "default network validated")
                    restore()
                } else if (!isValidated && validated == network) {
                    // Lost upstream without losing the network; re-arm so
                    // the next validation fires again.
                    validated = null
                }
                if (network == current) currentValidated = isValidated
                publish()
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                if (network == current) currentBlocked = blocked
                publish()
            }

            override fun onLost(network: Network) {
                if (validated == network) validated = null
                if (network == current) current = null
                Log.i(TAG, "default network lost")
                publish()
            }

            private fun restore() {
                onRestored()
                restores++
            }

            /** Runs after [restore] in the same event, so a reader of
             *  [state] that also reads what [onRestored] resets (a
             *  "Connection failed" cooldown) finds it reset, and, with the
             *  count in the value, hears of every restore. */
            private fun publish() {
                val internet = when {
                    current == null || currentBlocked -> InternetState.NONE
                    currentValidated -> InternetState.VALIDATED
                    else -> InternetState.UNVALIDATED
                }
                published.value = Connectivity(internet, restores)
            }
        })
    }
}
