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
    private val regionButton: ImageButton,
    private val modeToggle: View,
    private val freezeFrame: ImageView,
    private val panelHost: ViewGroup,
    private val regionUi: CameraRegionUi,
    /** Whether the Translation/Furigana toggle is available at all for the
     *  current source language (the activity owns that decision). */
    private val modeToggleSupported: () -> Boolean,
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

    /** Play state at shutter time — the first-ever snapshot's presentation
     *  default (auto-detecting → on-frame overlays, paused → panel). */
    private var wasPlayingAtShutter = true

    /** True while snapshot overlays own the frame (sliver engaged, or the
     *  live boxes kept through the load) — drives the flavor switcher's
     *  visibility in FROZEN. */
    private var overlaysShowing = false

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
        wasPlayingAtShutter = session.mode == CameraSession.Mode.LIVE
        // Overlays-preferred snapshot taken while live boxes are up: keep
        // them through the load (they track the very frame being frozen) and
        // swap in the snapshot's boxes at Done — no skeleton flash.
        val keepOverlays =
            (prefs.cameraSnapshotOnScreenPreferred ?: wasPlayingAtShutter) && session.hasLiveOverlays()
        keptLiveOverlays = keepOverlays
        overlaysShowing = keepOverlays
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
                overlaysShowing = true
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
                overlaysShowing = false
                syncControls()
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
        overlaysShowing = false
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
        // The crop editor owns the whole screen while active: its own bar
        // carries cancel/confirm, so the X (and the crop button itself)
        // step aside until it closes.
        backButton.isVisible = !cropActive
        regionButton.isVisible = frozen && !cropActive
        playPauseButton.isVisible = !frozen
        playPauseButton.setImageResource(
            if (mode == CameraSession.Mode.PAUSED) R.drawable.ic_play else R.drawable.ic_pause
        )
        playPauseButton.contentDescription = playPauseButton.context.getString(
            if (mode == CameraSession.Mode.PAUSED) R.string.camera_play_cd else R.string.camera_pause_cd
        )
        shutterButton.isVisible = !frozen
        shutterButton.isEnabled = !frozen
        // The flavor switcher stays available whenever boxes are on the
        // frame — live modes, and the snapshot's overlays presentation (a
        // toggle there re-flavors the frozen boxes from the snapshot caches;
        // translations come from the translator's LRU, no fresh backend
        // call). Panel-expanded FROZEN hides it: the flavor only affects
        // boxes that aren't showing.
        fadeToggle((!frozen || overlaysShowing) && modeToggleSupported() && !cropActive)
    }

    /** The switcher's target visibility, so repeated syncControls calls
     *  don't restart the fade; null until the first (instant) apply. */
    private var toggleVisibleTarget: Boolean? = null

    /** Fade the flavor switcher in/out as it enters/leaves the frame —
     *  it comes and goes with presentation changes (sliver ↔ panel,
     *  freeze ↔ live), and an instant pop reads as glitch. */
    private fun fadeToggle(visible: Boolean) {
        if (toggleVisibleTarget == visible) return
        val first = toggleVisibleTarget == null
        toggleVisibleTarget = visible
        modeToggle.animate().cancel()
        if (first) {
            // Initial state: no animation to fade from.
            modeToggle.alpha = if (visible) 1f else 0f
            modeToggle.isVisible = visible
            return
        }
        if (visible) {
            if (!modeToggle.isVisible) modeToggle.alpha = 0f
            modeToggle.isVisible = true
            modeToggle.animate().alpha(1f).setDuration(TOGGLE_FADE_MS).start()
        } else {
            modeToggle.animate().alpha(0f).setDuration(TOGGLE_FADE_MS)
                .withEndAction { if (toggleVisibleTarget == false) modeToggle.isVisible = false }
                .start()
        }
    }

    private companion object {
        const val TOGGLE_FADE_MS = 160L
    }

    fun release() {
        released = true
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
