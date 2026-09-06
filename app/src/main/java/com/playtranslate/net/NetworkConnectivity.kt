package com.playtranslate.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log

/**
 * The app's one view of device connectivity.
 *
 * Two things live here so no caller grows its own copy:
 *  - [isAvailable], the synchronous "is there a default network" query
 *    the degraded-translation notes use to say "no internet" instead of
 *    blaming a backend;
 *  - [install], a process-lifetime default-network callback that fires
 *    [onRestored] whenever the device GAINS connectivity. Its consumer is
 *    the translation cooldown system: a backend cooled down for
 *    "Connection failed" was punished for the device being offline, and
 *    the user coming back from airplane mode expects online services to
 *    work at once, not after the cooldown a dead network had climbed to.
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

    /** Wire once from Application.onCreate, AFTER the consumer is ready to
     *  receive (the registration replays the current network). */
    fun install(context: Context, onRestored: () -> Unit) {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            Log.w(TAG, "no ConnectivityManager — connectivity-restored signal disabled")
            return
        }
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            /** The default network we have already fired VALIDATED for. */
            private var validated: Network? = null

            override fun onAvailable(network: Network) {
                validated = null
                Log.i(TAG, "default network available")
                onRestored()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (isValidated && validated != network) {
                    validated = network
                    Log.i(TAG, "default network validated")
                    onRestored()
                } else if (!isValidated && validated == network) {
                    // Lost upstream without losing the network; re-arm so
                    // the next validation fires again.
                    validated = null
                }
            }

            override fun onLost(network: Network) {
                if (validated == network) validated = null
                Log.i(TAG, "default network lost")
            }
        })
    }
}
