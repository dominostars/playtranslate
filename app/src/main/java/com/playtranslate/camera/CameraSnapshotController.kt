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
 *  - LIVE:   back + control pill (pause, gear) + shutter.
 *  - PAUSED: back + control pill (play, gear) + shutter; overlays cleared.
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
    private val regionButton: ImageButton,
    /** The top-right control pill hosting play-pause/crop/settings —
     *  hidden wholesale while the crop editor owns the screen. */
    private val controlPill: View,
    private val freezeFrame: ImageView,
    private val panelHost: ViewGroup,
    private val regionUi: CameraRegionUi,
    /** LIVE/PAUSED back press — leave the screen. */
    private val onExit: () -> Unit,
) {
    private val prefs = Prefs(activity)

    private var frozenBitmap: Bitmap? = null

    /** User-drawn snapshot region (AU px of the frozen frame), or null for
     *  the whole frame. Scoped to ONE frozen episode: a new snapshot starts
     *  regionless (the camera has moved — a stale rect would silently drop
     *  text the user is looking at). */
    private var regionAu: android.graphics.Rect? = null

    /** True while the crop editor owns the screen (panel hidden, region
     *  drag box + confirm bar up). */
    private var cropActive = false

    /** The previous snapshot's frame, retained (not recycled) until the next
     *  freeze or [release]: pipeline cancellation is cooperative, so the
     *  recognizer may still be reading the bitmap briefly after a dismissal
     *  cancels the session — recycling at unfreeze would race it. One
     *  camera-frame-sized bitmap, bounded. */
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

    private var overlay: CaptureResultOverlay? = null
    private var snapshotSession: CaptureSession? = null

    /** Frozen-frame word lookup: tap/drag on the screenshot itself (outside
     *  the sheet) drives the floating-icon lens machinery against the
     *  snapshot's cached OCR. Gesture-time suppliers keep it current across
     *  re-snapshots and region re-runs. */
    private val wordLookup = CameraWordLookup(
        activity, session,
        frozenBitmap = { frozenBitmap },
        renderViewSpace = { bmp, w, h -> renderViewSpaceBackdrop(bmp, w, h) },
        hostSize = { hostSize() },
        hostOrigin = {
            val loc = IntArray(2)
            panelHost.getLocationOnScreen(loc)
            loc[0] to loc[1]
        },
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
        regionButton.setOnClickListener { enterCropMode() }
        syncControls()
    }

    // ── Snapshot region (crop) ──────────────────────────────────────────

    val isCropActive: Boolean get() = cropActive

    /** System back while the crop editor is up: cancel the edit, keep the
     *  snapshot. */
    fun cancelCrop() = exitCropMode(rerun = false)

    private fun enterCropMode() {
        if (!isFrozen || cropActive || frozenBitmap == null) return
        cropActive = true
        // The editor replaces both region surfaces: the indicator (it IS the
        // editable version of it) and the panel ("temporarily hide" — the
        // sheet comes back exactly as left; visibility only, no dismissal).
        // An open word-lookup lens goes too — it floats above the editor.
        wordLookup.dismiss()
        regionUi.hideIndicator()
        panelHost.isVisible = false
        syncControls()
        regionUi.showEditor(
            init = editorInitFractions(),
            onCancel = { exitCropMode(rerun = false) },
            onClear = {
                val had = regionAu != null
                regionAu = null
                exitCropMode(rerun = had)
            },
            onConfirm = { fractions ->
                val au = fractionsToAu(fractions)
                val changed = au != regionAu
                regionAu = au
                exitCropMode(rerun = changed)
            },
        )
    }

    private fun exitCropMode(rerun: Boolean) {
        if (!cropActive) return
        cropActive = false
        regionUi.hideEditor()
        panelHost.isVisible = true
        syncControls()
        syncIndicator()
        if (rerun) reRunSnapshot()
    }

    /** Show/hide the dashed active-region indicator to match [regionAu]. */
    private fun syncIndicator() {
        val au = regionAu
        val bmp = frozenBitmap
        if (au == null || bmp == null || !isFrozen || cropActive) {
            regionUi.hideIndicator()
            return
        }
        val (w, h) = hostSize()
        regionUi.showIndicator(CameraCoordinates(bmp.width, bmp.height, w, h).auToView(au)) {
            removeRegion()
        }
    }

    /** The indicator's Remove pill: drop the region and read the whole
     *  frame again. */
    private fun removeRegion() {
        if (regionAu == null) return
        regionAu = null
        syncIndicator()
        reRunSnapshot()
    }

    /** The current region as screen fractions for the editor, or null for
     *  the editor's default box. */
    private fun editorInitFractions(): android.graphics.RectF? {
        val au = regionAu ?: return null
        val bmp = frozenBitmap ?: return null
        val (w, h) = hostSize()
        val vr = CameraCoordinates(bmp.width, bmp.height, w, h).auToView(au)
        return android.graphics.RectF(
            vr.left.toFloat() / w,
            vr.top.toFloat() / h,
            vr.right.toFloat() / w,
            vr.bottom.toFloat() / h,
        )
    }

    /** Confirmed drag fractions → AU px on the frozen frame, clamped to the
     *  frame. Null (nothing intersects — unreachable for an on-screen drag,
     *  the frame covers the view) clears the region. */
    private fun fractionsToAu(f: android.graphics.RectF): android.graphics.Rect? {
        val bmp = frozenBitmap ?: return null
        val (w, h) = hostSize()
        val viewRect = android.graphics.Rect(
            Math.round(f.left * w),
            Math.round(f.top * h),
            Math.round(f.right * w),
            Math.round(f.bottom * h),
        )
        val au = CameraCoordinates(bmp.width, bmp.height, w, h).viewToAu(viewRect)
        return if (au.intersect(0, 0, bmp.width, bmp.height)) au else null
    }

    private fun hostSize(): Pair<Int, Int> {
        val w = panelHost.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val h = panelHost.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        return w to h
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
            retiredBitmap?.recycle()
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
        }
        o.presentationPrefs = object : CaptureResultOverlay.PresentationPrefs {
            override var boxesEnabled: Boolean
                get() = prefs.cameraBoxesEnabled
                set(value) {
                    prefs.cameraBoxesEnabled = value
                }
            override var startCollapsed: Boolean
                get() = prefs.cameraPanelStartCollapsed
                set(value) {
                    prefs.cameraPanelStartCollapsed = value
                }
        }
        o.ttsAlertTarget = TtsAlertTarget.InActivity(activity)
        // Tap-a-word lens hosted as an activity window (the panel's wm IS
        // the activity's WindowManager) — no overlay permission involved.
        o.wordLensInActivity = true
        o.showAnkiNotInstalled = { showAnkiNotInstalledDialog(activity) }
        // Anki review launches on top of the camera activity; the frozen
        // frame + sheet stay behind it and restore when the user backs out.
        o.dismissOnActivityLaunch = false
        // Leaving the frozen snapshot is the explicit X only — outside taps
        // and drag/fling-downs settle instead of dismissing.
        o.dismissOnGesture = false
        // Outside gestures near recognised text become frozen-frame word
        // lookups (tap = definition lens, drag = magnifier flow).
        o.outsideLookupRouter = { ev -> wordLookup.onOutsideTouch(ev) }
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
                    // forCamera: the download screen persists the CAMERA
                    // token, only on verified success — an aborted download
                    // can't cost the user their current camera engine, and
                    // the global/live engine never moves.
                    activity.startActivity(
                        CaptureOverlaySettingsActivity.downloadIntent(
                            activity, prov.sourceLangId, backend.selectionToken, forCamera = true,
                        )
                    )
                    unfreeze()
                },
                // The camera's OCR choice is its own per-flow setting.
                applyToken = { backend ->
                    prefs.setCameraOcrBackendToken(prov.sourceLangId, backend.selectionToken)
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
        val s = session.runSnapshot(bitmap, regionAu)
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

    /** Read settings changed (source language, OCR engine — the pill's gear
     *  menu): re-read the SAME frozen frame under the new selections,
     *  restarting the panel flow from its loading indication — the boxes
     *  and results on display describe the OLD settings. */
    fun refreshSnapshot() {
        if (!isFrozen) return
        overlay?.prepareForSettingsRefresh()
        reRunSnapshot()
    }

    /** Gear re-OCR and region changes: run a fresh snapshot session over the
     *  SAME frozen frame (recognise picks up the engine selection the picker
     *  just persisted; the current [regionAu] rides along) and drive the same
     *  panel through the loading stages again — observe() cancels the prior
     *  session. */
    private fun reRunSnapshot() {
        val o = overlay ?: return
        val bitmap = frozenBitmap ?: return
        // The lookup lens describes the outgoing scene (its lines/region
        // are about to be replaced) — take it down with the re-run.
        wordLookup.dismiss()
        val s = session.runSnapshot(bitmap, regionAu)
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
        keptLiveOverlays = false
        wordLookup.dismiss()
        // Region + its UI die with the frozen episode (see regionAu).
        if (cropActive) {
            cropActive = false
            regionUi.hideEditor()
            panelHost.isVisible = true
        }
        regionAu = null
        regionUi.hideIndicator()
        if (session.mode == CameraSession.Mode.FROZEN) {
            session.unfreeze(preFreezeMode)
        }
        restoreOrientation()
        freezeFrame.isVisible = false
        freezeFrame.setImageBitmap(null)
        // Not recycled here — see retiredBitmap.
        retiredBitmap?.recycle()
        retiredBitmap = frozenBitmap
        frozenBitmap = null
        syncControls()
    }

    /** Re-derive every control from the session mode. Public for the
     *  activity's onResume re-sync. */
    fun syncControls() {
        val mode = session.mode
        val frozen = mode == CameraSession.Mode.FROZEN
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
        wordLookup.destroy()
        regionUi.destroy()
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
