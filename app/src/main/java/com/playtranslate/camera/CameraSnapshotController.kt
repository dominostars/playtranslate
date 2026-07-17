package com.playtranslate.camera

import android.app.Activity
import android.graphics.Bitmap
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.view.isVisible
import com.playtranslate.CaptureSession
import com.playtranslate.OneShotOverlayData
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.ocr.registry.selectionToken
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.ui.ActivitySheetHost
import com.playtranslate.ui.CaptureOverlaySettingsActivity
import com.playtranslate.ui.CaptureResultOverlay
import com.playtranslate.ui.OcrPicker
import com.playtranslate.ui.OverlayAlert
import com.playtranslate.ui.TtsAlertTarget
import com.playtranslate.ui.showAnkiNotInstalledDialog

/**
 * Activity-scoped owner of the camera's play/pause + snapshot UI state:
 * control visibility per [CameraSession.Mode], the frozen-frame ImageView and
 * its bitmap lifetime, and the in-app capture result panel over the snapshot.
 *
 * Control states:
 *  - LIVE:   back + mode toggle + pause icon + shutter.
 *  - PAUSED: back + mode toggle + play icon + shutter; overlays cleared.
 *  - FROZEN: the back button becomes an X (close snapshot); everything else
 *    hides. Every dismissal path — X, system back, panel drag/fling/tap-
 *    outside — funnels through the panel's onDismiss and restores the
 *    PRE-snapshot mode (a paused camera stays paused).
 *
 * The panel is the shared [CaptureResultOverlay] hosted in the activity's
 * view tree ([ActivitySheetHost]), with every over-game behavior overridden:
 * on-frame boxes render through the camera's warp path, the presentation
 * preference is the camera's own (first-use default = play state at shutter),
 * TTS alerts are activity dialogs, and the word lens is disabled (it is an
 * overlay-window feature; extending it in-app is future work).
 *
 * Main thread only.
 */
class CameraSnapshotController(
    private val activity: Activity,
    private val session: CameraSession,
    private val backButton: ImageButton,
    private val playPauseButton: ImageButton,
    private val shutterButton: ImageButton,
    private val modeToggle: View,
    private val freezeFrame: ImageView,
    private val panelHost: ViewGroup,
    /** Whether the Translation/Furigana toggle is available at all for the
     *  current source language (the activity owns that decision). */
    private val modeToggleSupported: () -> Boolean,
    /** LIVE/PAUSED back press — leave the screen. */
    private val onExit: () -> Unit,
) {
    private val prefs = Prefs(activity)

    private var frozenBitmap: Bitmap? = null

    /** The previous snapshot's frame, retained (not recycled) until the next
     *  freeze or [release]: pipeline cancellation is cooperative, so the
     *  recognizer may still be reading the bitmap briefly after a dismissal
     *  cancels the session — recycling at unfreeze would race it. One
     *  camera-frame-sized bitmap, bounded. */
    private var retiredBitmap: Bitmap? = null

    /** Mode to restore when the snapshot closes. */
    private var preFreezeMode = CameraSession.Mode.LIVE

    /** Play state at shutter time — the first-ever snapshot's presentation
     *  default (auto-detecting → on-frame overlays, paused → panel). */
    private var wasPlayingAtShutter = true

    private var overlay: CaptureResultOverlay? = null
    private var snapshotSession: CaptureSession? = null

    /** Set by the activity's onDestroy: a freeze landing afterwards must
     *  drop its bitmap instead of touching dead views. */
    private var released = false

    val isFrozen: Boolean
        get() = session.mode == CameraSession.Mode.FROZEN

    init {
        playPauseButton.setOnClickListener { togglePlayPause() }
        shutterButton.setOnClickListener { freeze() }
        backButton.setOnClickListener { if (isFrozen) unfreeze() else onExit() }
        syncControls()
    }

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
        wasPlayingAtShutter = session.mode == CameraSession.Mode.LIVE
        // One freeze in flight at a time; re-enabled by syncControls on
        // either outcome.
        shutterButton.isEnabled = false
        session.requestFreeze { bitmap ->
            if (released) {
                bitmap.recycle()
                return@requestFreeze
            }
            retiredBitmap?.recycle()
            retiredBitmap = null
            frozenBitmap = bitmap
            freezeFrame.setImageBitmap(bitmap)
            freezeFrame.isVisible = true
            syncControls()
            startSnapshot(bitmap)
        }
    }

    /** Build the in-app panel with every over-game behavior overridden, then
     *  run the camera's snapshot pipeline into it. */
    private fun startSnapshot(bitmap: Bitmap) {
        val o = CaptureResultOverlay(
            activity,
            activity.windowManager,
            Display.DEFAULT_DISPLAY,
            // Dead parameter in this configuration: every path that would
            // spawn an overlay window is overridden below. Constructed only
            // to satisfy the shared signature.
            OverlayHost(activity, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY),
            sheetHost = ActivitySheetHost(panelHost),
        )
        o.boxPresenter = object : CaptureResultOverlay.BoxPresenter {
            override fun show(data: OneShotOverlayData): Boolean {
                session.showFrozenOverlays()
                return true
            }

            override fun update(data: OneShotOverlayData) {
                // Done landed while slivered: re-render — the pipeline's
                // translations are in the session's snapshot cache now, so
                // the skeletons promote to filled boxes.
                session.showFrozenOverlays()
            }

            override fun hide() {
                session.hideFrozenOverlays()
            }
        }
        o.onScreenPreference = object : CaptureResultOverlay.OnScreenPref {
            override var preferred: Boolean
                get() = prefs.cameraSnapshotOnScreenPreferred ?: wasPlayingAtShutter
                set(value) {
                    prefs.cameraSnapshotOnScreenPreferred = value
                }
        }
        o.ttsAlertTarget = TtsAlertTarget.InActivity(activity)
        o.wordLensEnabled = false
        o.showAnkiNotInstalled = { showAnkiNotInstalledDialog(activity) }
        // Anki review launches on top of the camera activity; the frozen
        // frame + sheet stay behind it and restore when the user backs out.
        o.dismissOnActivityLaunch = false
        o.retranslate = { text ->
            session.translateForPanel(text)
        }
        o.chooseOcr = { prov, _ ->
            // The shared picker's activity form (same one the in-app results
            // page uses). A downloaded pick re-OCRs the SAME frozen frame —
            // the bitmap is still held by this controller — through a fresh
            // snapshot session on the same panel; a not-downloaded pick
            // deep-links the OCR download screen and closes the snapshot
            // (the app navigates away).
            OcrPicker.populate(
                OverlayAlert.Builder(activity),
                activity,
                prov.sourceLangId,
                prov.engineToken,
                onReOcr = { reRunSnapshot() },
                onDownload = { backend ->
                    activity.startActivity(
                        CaptureOverlaySettingsActivity.downloadIntent(
                            activity, prov.sourceLangId, backend.selectionToken,
                        )
                    )
                    unfreeze()
                },
            ).show()
        }
        o.onDismiss = { finishUnfreeze() }
        overlay = o

        val w = panelHost.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val h = panelHost.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        // The sheet's frosted body samples a SCREEN-SPACE image of what sits
        // behind it (the capture flow passes the clean screen frame). Project
        // the AU-space frozen frame through the same FILL_CENTER mapping the
        // freeze ImageView displays it with, so the frost shows exactly the
        // visible slice. show() copies what it needs (downscaled blur), so
        // the projection is recycled immediately.
        val backdrop = renderViewSpaceBackdrop(bitmap, w, h)
        o.show(w, h, backdrop)
        backdrop?.recycle()
        val s = session.runSnapshot(bitmap)
        snapshotSession = s
        o.observe(s)
    }

    /** The frozen AU frame drawn into a view-sized bitmap via the exact
     *  [CameraCoordinates] FILL_CENTER transform (uniform cover-scale,
     *  center-crop) — what the user actually sees behind the sheet. */
    private fun renderViewSpaceBackdrop(frozen: Bitmap, viewW: Int, viewH: Int): Bitmap? {
        if (viewW <= 0 || viewH <= 0 || frozen.isRecycled) return null
        return try {
            val coords = CameraCoordinates(frozen.width, frozen.height, viewW, viewH)
            val out = Bitmap.createBitmap(viewW, viewH, Bitmap.Config.ARGB_8888)
            val matrix = android.graphics.Matrix().apply {
                setScale(coords.scale, coords.scale)
                postTranslate(coords.offsetX, coords.offsetY)
            }
            android.graphics.Canvas(out).drawBitmap(
                frozen, matrix,
                android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
            )
            out
        } catch (e: Exception) {
            android.util.Log.w("CameraSnapshot", "backdrop projection failed", e)
            null
        }
    }

    /** Gear re-OCR: run a fresh snapshot session over the SAME frozen frame
     *  (recognise picks up the engine selection the picker just persisted)
     *  and drive the same panel through the loading stages again. */
    private fun reRunSnapshot() {
        val o = overlay ?: return
        val bitmap = frozenBitmap ?: return
        val s = session.runSnapshot(bitmap)
        snapshotSession = s
        o.observe(s)
    }

    /** X or system back: route through the panel's dismiss so every exit
     *  path is the same path (the panel also dismisses via drag/fling/tap-
     *  outside, which land in onDismiss → [finishUnfreeze] directly). */
    fun unfreeze() {
        if (!isFrozen) return
        val o = overlay
        if (o != null) o.dismiss() else finishUnfreeze()
    }

    /** The single teardown: drop the panel + snapshot display, restore the
     *  pre-snapshot mode. Runs exactly once per snapshot (the panel's
     *  dismiss is idempotent). */
    private fun finishUnfreeze() {
        overlay = null
        snapshotSession = null
        if (session.mode == CameraSession.Mode.FROZEN) {
            session.unfreeze(preFreezeMode)
        }
        freezeFrame.isVisible = false
        freezeFrame.setImageBitmap(null)
        // Not recycled here — see retiredBitmap.
        retiredBitmap?.recycle()
        retiredBitmap = frozenBitmap
        frozenBitmap = null
        syncControls()
    }

    /** Re-derive every control from the session mode. Public because the
     *  activity's onResume rebinds the mode toggle and must not resurrect
     *  it while frozen. */
    fun syncControls() {
        val mode = session.mode
        val frozen = mode == CameraSession.Mode.FROZEN
        backButton.setImageResource(if (frozen) R.drawable.ic_close else R.drawable.ic_arrow_back)
        backButton.contentDescription = backButton.context.getString(
            if (frozen) R.string.camera_close_cd else R.string.camera_back_cd
        )
        playPauseButton.isVisible = !frozen
        playPauseButton.setImageResource(
            if (mode == CameraSession.Mode.PAUSED) R.drawable.ic_play else R.drawable.ic_pause
        )
        playPauseButton.contentDescription = playPauseButton.context.getString(
            if (mode == CameraSession.Mode.PAUSED) R.string.camera_play_cd else R.string.camera_pause_cd
        )
        shutterButton.isVisible = !frozen
        shutterButton.isEnabled = !frozen
        modeToggle.isVisible = !frozen && modeToggleSupported()
    }

    fun release() {
        released = true
        // Suppress finishUnfreeze — the activity is dying; there is no UI
        // state to restore, and the session's executors are about to shut
        // down. dismiss() still cancels the snapshot session.
        overlay?.let { o ->
            o.onDismiss = null
            o.dismiss()
        }
        overlay = null
        snapshotSession = null
        // Deliberately NOT recycled: the snapshot pipeline's cancellation is
        // cooperative, so the recognizer may still be reading either bitmap
        // for a moment after the cancel above. Dropping the references lets
        // GC reclaim them once the cancelled job's own reference dies.
        frozenBitmap = null
        retiredBitmap = null
    }
}
