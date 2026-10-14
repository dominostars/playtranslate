package com.playtranslate.capture

import android.content.Context
import android.hardware.display.DisplayManager
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.reachableDisplays

/**
 * The first-launch display-selection seed, split from MainActivity so its
 * two decisions can be pinned: WHEN a seed may run, and WHAT it stores.
 *
 * The seed is a one-time guess that becomes the persisted selection, so it
 * must be made under the backend that will actually capture. Before
 * onboarding grants anything, [CaptureBackendResolver] sits on its
 * accessibility default with no service behind it; that backend's shim
 * keeps a connected display as given, so a seed taken then stores the
 * detected display — on a dual-screen device, the screen the app did not
 * launch on — and a MediaProjection user carries that stale guess, hidden
 * by read-time collapse, until the day they enable the accessibility
 * service and it resurfaces as the icon and the display picker jumping to
 * the app's own screen (Thor, 2026-09-02).
 *
 * The guess itself ([detectGameDisplayId]) is made among the displays the
 * app counts as screens, never among every display the process can see.
 */
object DisplaySelectionSeed {

    /**
     * The display the game is guessed to be on: the first connected,
     * non-private display ([reachableDisplays]) that is not [ownDisplayId],
     * the display the app itself is on; null when there is no other. A
     * dual-screen handheld has the game on its other screen; anything else
     * has no other screen to guess, and the caller falls back to what the
     * selection already reads as.
     *
     * Read from [reachableDisplays] rather than [DisplayManager.getDisplays]
     * because the latter also lists this app's own MediaProjection
     * VirtualDisplay ("PlayTranslateCapture": private, presentation, alive
     * for the process lifetime once a projection was consented). A seed
     * taken under the accessibility backend, whose shim stores what it is
     * given, once took our own virtual display as the game screen; its id
     * died with the projection, and from then on the selection named
     * nothing that existed, which no picker could correct on a
     * single-screen phone (Galaxy S25, 2026-10-14). Another app's PUBLIC
     * virtual display cannot be told from a built-in second screen with
     * public API (the Thor's Screen-2 is internal and carries
     * FLAG_PRESENTATION like a cast display does), so it stays a candidate;
     * the shim makes a seed that dies harmless by resolving it to the
     * default display at read time.
     *
     * The power state is deliberately ignored, unlike the picker's list
     * ([com.playtranslate.capturableDisplays]): the seed is taken once per
     * install, and a second screen that happens to be off during onboarding
     * is still the game screen. A first cut required the screen to be on,
     * which would have seeded the app's own screen in that case and kept it
     * until the user changed the selection (Codex adversarial review,
     * 2026-10-14). The picker shows the seeded screen whenever it is on.
     */
    fun detectGameDisplayId(displayManager: DisplayManager, ownDisplayId: Int): Int? =
        displayManager.reachableDisplays()
            .firstOrNull { it.displayId != ownDisplayId }
            ?.displayId

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
     * accessibility keeps until they change it; accessibility stores the
     * detected display, which [detectGameDisplayId] took from the connected
     * ones.
     */
    fun seedFor(context: Context, backend: CaptureBackend, detectedGameDisplayId: Int): Set<Int> =
        backend.capturableTargets(context, setOf(detectedGameDisplayId))
}
