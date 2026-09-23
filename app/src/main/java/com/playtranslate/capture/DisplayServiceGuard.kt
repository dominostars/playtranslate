package com.playtranslate.capture

import android.os.Build

/**
 * Whether this device runs the AYN kalama framework whose
 * DisplayPowerController constructor calls the client-side DisplayManager
 * under the display service lock, so that an accessibility window recompute
 * landing inside our `createVirtualDisplay` deadlocks system_server
 * (docs/ayn-thor-displaymanager-deadlock-report.md; field bug report
 * 2026-09-20 on a Thor). AOSP has no such call.
 *
 * The guard — session before blank, the own-window quiet hold and its
 * refusal, the first-frame warm-up (see
 * [MediaProjectionController.ensureSession] and
 * [com.playtranslate.overlay.OwnWindowClock]) — runs only where the hazard
 * is known: AYN's Thor and Odin 2 share the BSP. Everywhere else the capture
 * path keeps its field-proven pre-guard order and pays none of the hold's
 * latency, and the creation log line still records how quiet our windows
 * were, so an exported log from another vendor can show whether it needs
 * the guard too.
 */
object DisplayServiceGuard {

    /** Test seam: force the verdict. */
    internal var override: Boolean? = null

    val applies: Boolean
        get() = override ?: appliesTo(Build.MANUFACTURER)

    /** Pure rule over the manufacturer string as [Build.MANUFACTURER]
     *  reports it (`AYN` on the Thor's `Thor_V1.0.0.377` firmware). */
    fun appliesTo(manufacturer: String?): Boolean =
        manufacturer?.trim().equals(AFFECTED_MANUFACTURER, ignoreCase = true)

    private const val AFFECTED_MANUFACTURER = "AYN"
}
