package com.playtranslate.capture

import android.content.Context
import com.playtranslate.PlayTranslateAccessibilityService

/**
 * The first-launch display-selection seed, split from MainActivity so its
 * two decisions can be pinned: WHEN a seed may run, and WHAT it stores.
 *
 * The seed is a one-time guess that becomes the persisted selection, so it
 * must be made under the backend that will actually capture. Before
 * onboarding grants anything, [CaptureBackendResolver] sits on its
 * accessibility default with no service behind it; that backend's shim is
 * identity, so a seed taken then stores the raw detected display — on a
 * dual-screen device, the screen the app did not launch on — and a
 * MediaProjection user carries that stale guess, hidden by read-time
 * collapse, until the day they enable the accessibility service and it
 * resurfaces as the icon and the display picker jumping to the app's own
 * screen (Thor, 2026-09-02).
 */
object DisplaySelectionSeed {

    /**
     * True when [backend] is backed by a granted permission, i.e. it is the
     * backend captures will run on. MediaProjection is selected only once
     * "display over other apps" is granted (or below API 30, where it is the
     * only backend and its seed is the default display regardless). The
     * accessibility backend counts only while its service is enabled in
     * system Settings — the resolver's pre-onboarding default is the same
     * object with nothing behind it.
     */
    fun isBackendPermissionBacked(context: Context, backend: CaptureBackend): Boolean =
        !backend.requiresAccessibilityService ||
            PlayTranslateAccessibilityService.isEnabled(context)

    /**
     * The selection to store for [detectedGameDisplayId] under [backend]:
     * the detected display if the backend can capture it, else the backend's
     * fallback. Routed through [CaptureBackend.capturableTargets] so the seed
     * behaves like every other call site that turns a selection into the
     * working set — MediaProjection stores the default display whatever was
     * detected, which is what its user sees and what a later switch to
     * accessibility keeps until they change it.
     */
    fun seedFor(backend: CaptureBackend, detectedGameDisplayId: Int): Set<Int> =
        backend.capturableTargets(setOf(detectedGameDisplayId))
}
