package com.playtranslate.capture

/**
 * The single place that decides which [CaptureBackend] is active. Consumers
 * (CaptureService and the capture/overlay call sites) route through [active]
 * and never read the backend preference themselves — so the MediaProjection-
 * vs-accessibility split stays contained here.
 *
 * Until the MediaProjection backend and its debug toggle land, [active] always
 * returns [AccessibilityCaptureBackend].
 */
object CaptureBackendResolver {
    /** The capture backend the app should use right now. */
    fun active(): CaptureBackend = AccessibilityCaptureBackend
}
