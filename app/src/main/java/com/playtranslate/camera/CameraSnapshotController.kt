package com.playtranslate.camera

import android.app.Activity
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.view.isVisible
import com.playtranslate.CaptureSession
import com.playtranslate.OcrTokenScope
import com.playtranslate.OneShotOverlayData
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.ui.CaptureResultOverlay

/**
 * Activity-scoped owner of the camera's play/pause + snapshot UI state:
 * control visibility per [CameraSession.Mode], the frozen-frame ImageView and
 * its bitmap lifetime, and the in-app capture result panel over the snapshot.
 *
 * Control states:
 *  - LIVE:   back + control pill (pause, gear) + shutter.
 *  - PAUSED: back + control pill (play, gear) + shutter; overlays cleared.
 *  - FROZEN: the back button becomes an X (close snapshot); everything else
 *    hides. Every dismissal path — X, system back, panel drag/fling/tap-
 *    outside — funnels through the panel's onDismiss and restores the
 *    PRE-snapshot mode (a paused camera stays paused).
 *
 * The review surface itself (panel construction with the over-game behaviors
 * overridden, crop editor, word lookup, backdrop) is the shared
 * [FrozenReviewPanel]; this controller keeps only what is camera-specific —
 * the freeze handshake, kept-live-overlays presentation, mode restore, and
 * the orientation pin.
 *
 * Main thread only.
 */
class CameraSnapshotController(
    private val activity: Activity,
    private val session: CameraSession,
    private val backButton: ImageButton,
    private val playPauseButton: ImageButton,
    private val shutterButton: ImageButton,
    private val regionButton: ImageButton,
    /** The top-right control pill hosting play-pause/crop/settings —
     *  hidden wholesale while the crop editor owns the screen. */
    private val controlPill: View,
    private val freezeFrame: ImageView,
    private val panelHost: ViewGroup,
    regionUi: CameraRegionUi,
    /** LIVE/PAUSED back press — leave the screen. */
    private val onExit: () -> Unit,
) {
    private val prefs = Prefs(activity)

    private var frozenBitmap: Bitmap? = null

    /** The previous snapshot's frame, retained until the next freeze or
     *  [release] and then DROPPED for GC — never recycle()d anywhere:
     *  pipeline cancellation is cooperative, so a cancelled recognizer can
     *  still be reading the bitmap (ML Kit holds the original through its
     *  whole pass), and no fixed spacing rule survives a fast X-then-resnap.
     *  GC frees the pixels once the dead job's own reference dies; the cost
     *  is one camera-frame bitmap living to the next GC. */
    private var retiredBitmap: Bitmap? = null

    /** Mode to restore when the snapshot closes. */
    private var preFreezeMode = CameraSession.Mode.LIVE

    /** Orientation request to restore when the snapshot closes. While
     *  FROZEN the activity is pinned to its shutter-time orientation — a
     *  rotation would recreate the activity and destroy the snapshot (the
     *  V1 behavior); the live/paused viewfinder keeps rotating freely. */
    private var preFreezeOrientation =
        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    /** True while the freeze-time orientation pin is applied — gives the
     *  restore exactly-once semantics on WHATEVER exit path runs (normal
     *  close, activity teardown), instead of relying on the call-site fact
     *  that today's teardown coincides with activity death. */
    private var orientationPinned = false

    private fun restoreOrientation() {
        if (!orientationPinned) return
        orientationPinned = false
        activity.requestedOrientation = preFreezeOrientation
    }

    /** The live overlays were kept as this snapshot's loading state: the
     *  presenter must NOT paint skeletons over them at Translating; the Done
     *  promotion swaps them for the snapshot's boxes. */
    private var keptLiveOverlays = false

    /** The shared frozen-review surface, driven by the camera's snapshot
     *  pipeline. The presenter routes on-frame boxes through the session's
     *  warp path with the kept-live-overlays gate. */
    private val panel = FrozenReviewPanel(
        activity = activity,
        panelHost = panelHost,
        regionUi = regionUi,
        backend = object : FrozenReviewBackend {
            override fun runReview(
                bitmap: Bitmap,
                regionAu: android.graphics.Rect?,
                preOcrDelayMs: Long,
            ): CaptureSession =
                // The camera has no page flips — the dwell is unused here.
                session.runSnapshot(bitmap, regionAu)

            override fun lookupScene() = session.frozenLookupScene()

            override suspend fun translateForPanel(text: String) =
                session.translateForPanel(text)
        },
        bitmap = { frozenBitmap },
        boxPresenter = object : CaptureResultOverlay.BoxPresenter {
            override fun show(data: OneShotOverlayData): Boolean {
                syncControls()
                // Kept live boxes ARE the loading presentation — don't paint
                // skeletons over them; Done's update() does the swap.
                if (!keptLiveOverlays) session.showFrozenOverlays()
                return true
            }

            override fun update(data: OneShotOverlayData) {
                // Done landed while slivered: render the snapshot's boxes —
                // the pipeline's translations are in the session's snapshot
                // cache now, so this fills (and replaces any kept live boxes).
                keptLiveOverlays = false
                session.showFrozenOverlays()
            }

            override fun hide() {
                keptLiveOverlays = false
                syncControls()
                session.hideFrozenOverlays()
            }
        },
        presentationPrefs = object : CaptureResultOverlay.PresentationPrefs {
            override var boxesEnabled: Boolean
                get() = prefs.cameraBoxesEnabled
                set(value) {
                    prefs.cameraBoxesEnabled = value
                }
            override var startPosture: Float
                get() = prefs.cameraPanelPosture
                set(value) {
                    prefs.cameraPanelPosture = value
                }
        },
        tokenScope = OcrTokenScope.CAMERA,
        fitMode = CameraCoordinates.FitMode.FILL,
        zoomPolicy = ReviewZoom.CeilingPolicy.CAMERA,
        setImageTransform = { m ->
            if (m == null) {
                // The regression path: at fit the freeze frame runs its
                // DECLARED centerCrop, byte-identical to a build without
                // zoom. (Post-unfreeze the view is hidden and re-set fresh
                // at the next freeze.)
                freezeFrame.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            } else {
                freezeFrame.scaleType = android.widget.ImageView.ScaleType.MATRIX
                freezeFrame.imageMatrix = m
            }
        },
        setOverlayTransform = { session.setOverlayViewTransform(it) },
        setRenderScaleBoost = { session.setFrozenRenderBoost(it) },
        repaintOverlays = {
            // Gated repaint (the flavor-cycle precedent): only when boxes
            // own the frozen frame.
            if (session.hasLiveOverlays()) session.showFrozenOverlays()
        },
        onControlsChanged = { syncControls() },
        // A not-downloaded OCR pick navigates to the download screen; the
        // snapshot closes (the app navigates away).
        onDownloadLaunched = { unfreeze() },
        onDismissed = { finishUnfreeze() },
    )

    /** Set by the activity's onDestroy: a freeze landing afterwards must
     *  drop its bitmap instead of touching dead views. */
    private var released = false

    val isFrozen: Boolean
        get() = session.mode == CameraSession.Mode.FROZEN

    init {
        playPauseButton.setOnClickListener { togglePlayPause() }
        shutterButton.setOnClickListener { freeze() }
        backButton.setOnClickListener { if (isFrozen) unfreeze() else onExit() }
        regionButton.setOnClickListener { panel.enterCropMode() }
        syncControls()
    }

    // ── Snapshot region (crop) ──────────────────────────────────────────

    val isCropActive: Boolean get() = panel.isCropActive

    /** System back while the crop editor is up: cancel the edit, keep the
     *  snapshot. */
    fun cancelCrop() = panel.cancelCrop()

    private fun togglePlayPause() {
        when (session.mode) {
            CameraSession.Mode.LIVE -> session.pause()
            CameraSession.Mode.PAUSED -> session.resume()
            CameraSession.Mode.FROZEN -> return
        }
        syncControls()
    }

    private fun freeze() {
        if (session.mode == CameraSession.Mode.FROZEN) return
        preFreezeMode = session.mode
        // Boxes-on snapshot taken while live boxes are up: keep them through
        // the load (they track the very frame being frozen) and swap in the
        // snapshot's boxes at Done — no skeleton flash.
        val keepOverlays = prefs.cameraBoxesEnabled && session.hasLiveOverlays()
        keptLiveOverlays = keepOverlays
        // One freeze in flight at a time; re-enabled by syncControls on
        // either outcome.
        shutterButton.isEnabled = false
        session.requestFreeze(keepOverlays) { bitmap ->
            if (released) {
                bitmap.recycle()
                return@requestFreeze
            }
            // Dropped for GC, never recycled — see retiredBitmap.
            retiredBitmap = null
            frozenBitmap = bitmap
            freezeFrame.setImageBitmap(bitmap)
            freezeFrame.isVisible = true
            // Pin the orientation for the snapshot's lifetime (see
            // preFreezeOrientation); LOCKED = whatever the shutter caught.
            // The stash is guarded so a re-entrant pin — no path today,
            // freeze() rejects while FROZEN — can never record the LOCKED
            // value it itself set as "the state to restore": pin and
            // restore own their idempotence as a matched pair.
            if (!orientationPinned) {
                preFreezeOrientation = activity.requestedOrientation
                orientationPinned = true
            }
            activity.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
            syncControls()
            panel.startReview(bitmap)
        }
    }

    /** Read settings changed (source language, OCR engine — the pill's gear
     *  menu): re-read the SAME frozen frame under the new selections. */
    fun refreshSnapshot() {
        if (!isFrozen) return
        panel.refreshAfterSettings()
    }

    /** X or system back: route through the panel's dismiss so every exit
     *  path is the same path. */
    fun unfreeze() {
        if (!isFrozen) return
        panel.dismissReview()
    }

    /** The camera's share of the episode teardown (the panel already tore
     *  down the review-scoped UI): restore the pre-snapshot mode and the
     *  orientation, retire the frame. Runs exactly once per snapshot. */
    private fun finishUnfreeze() {
        keptLiveOverlays = false
        if (session.mode == CameraSession.Mode.FROZEN) {
            session.unfreeze(preFreezeMode)
        }
        restoreOrientation()
        freezeFrame.isVisible = false
        freezeFrame.setImageBitmap(null)
        // The outgoing retiree is dropped for GC, never recycled — see
        // retiredBitmap.
        retiredBitmap = frozenBitmap
        frozenBitmap = null
        syncControls()
    }

    /** Re-derive every control from the session mode. Public for the
     *  activity's onResume re-sync. */
    fun syncControls() {
        val mode = session.mode
        val frozen = mode == CameraSession.Mode.FROZEN
        val cropActive = panel.isCropActive
        backButton.setImageResource(if (frozen) R.drawable.ic_close else R.drawable.ic_arrow_back)
        backButton.contentDescription = backButton.context.getString(
            if (frozen) R.string.camera_close_cd else R.string.camera_back_cd
        )
        // The crop editor owns the whole screen while active: its own bar
        // carries cancel/confirm, so the X and the whole control pill
        // (crop, settings, flavor) step aside until it closes.
        backButton.isVisible = !cropActive
        controlPill.isVisible = !cropActive
        regionButton.isVisible = frozen
        playPauseButton.isVisible = !frozen
        playPauseButton.setImageResource(
            if (mode == CameraSession.Mode.PAUSED) R.drawable.ic_play else R.drawable.ic_pause
        )
        playPauseButton.contentDescription = playPauseButton.context.getString(
            if (mode == CameraSession.Mode.PAUSED) R.string.camera_play_cd else R.string.camera_pause_cd
        )
        shutterButton.isVisible = !frozen
        shutterButton.isEnabled = !frozen
    }

    fun release() {
        released = true
        // Not load-bearing today (release coincides with activity death and
        // requestedOrientation dies with the activity) — but the restore is
        // owned by the pin's lifecycle, not by that call-site fact.
        restoreOrientation()
        // Suppresses finishUnfreeze — the activity is dying; there is no UI
        // state to restore, and the session's executors are about to shut
        // down. The panel still cancels the snapshot session.
        panel.release()
        // Deliberately NOT recycled: the snapshot pipeline's cancellation is
        // cooperative, so the recognizer may still be reading either bitmap
        // for a moment after the cancel above. Dropping the references lets
        // GC reclaim them once the cancelled job's own reference dies.
        frozenBitmap = null
        retiredBitmap = null
    }
}
