package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.view.Display
import android.view.WindowManager
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.OwnWindows

/**
 * The lens a word lookup shows its card in and the controller that drives
 * it, built for where the lookup is hosted. One place says what each host's
 * windows are, so a lookup hosted in an activity (the camera and import
 * tools' frozen frame: its drag lookup and its Definitions word boxes) and
 * one over the game (the capture sheet's Definitions) differ in nothing but
 * this.
 */
class LookupLensKit private constructor(
    val magnifier: MagnifierLens,
    val lookup: DragLookupController,
) {
    companion object {
        /**
         * Over the game: overlay windows on [displayId], added through
         * [overlayHost]. The card acts in [context] (the app's, as the drag
         * lens's acts in its service's: a page or Anki activity it starts
         * is not tied to the game display); its windows are [displayCtx]'s.
         */
        fun overGame(
            context: Context,
            displayCtx: Context,
            wm: WindowManager,
            overlayHost: OverlayHost,
            displayId: Int,
            takesControllerFocus: Boolean,
        ): LookupLensKit {
            val magnifier = MagnifierLens(
                displayCtx, wm, displayId, overlayHost, takesControllerFocus = takesControllerFocus,
            )
            val lookup = DragLookupController(
                context = context,
                displayId = displayId,
                popup = WordLookupPopup(displayCtx, wm, displayId, overlayHost),
                magnifier = magnifier,
                overlayHost = overlayHost,
            )
            return LookupLensKit(magnifier, lookup)
        }

        /**
         * In [activity]: every surface is one of its own panel windows
         * (TYPE_APPLICATION_PANEL), so no overlay permission is involved.
         * A Speak button's alerts and the Anki-not-installed dialog go to
         * activity surfaces, and the lens's open and Anki actions keep to
         * the activity's own routing.
         */
        fun inActivity(activity: Activity, takesControllerFocus: Boolean = true): LookupLensKit {
            val wm = OwnWindows.managerOf(activity)
            val magnifier = MagnifierLens(
                activity, wm, Display.DEFAULT_DISPLAY,
                overlayHost = null, takesControllerFocus = takesControllerFocus,
            )
            val lookup = DragLookupController(
                activity, Display.DEFAULT_DISPLAY, WordLookupPopup(activity, wm), magnifier,
                // Dead in this configuration: every consumer that would
                // touch it (a Speak button's alerts, the Anki-not-installed
                // dialog) is overridden with an activity surface below.
                // Constructed only to satisfy the shared signature.
                overlayHost = OverlayHost(activity, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY),
                ttsAlertTarget = TtsAlertTarget.InActivity(activity),
                showAnkiNotInstalled = { showAnkiNotInstalledDialog(activity) },
                route = WorkspaceRoute.None,
            )
            return LookupLensKit(magnifier, lookup)
        }
    }
}
