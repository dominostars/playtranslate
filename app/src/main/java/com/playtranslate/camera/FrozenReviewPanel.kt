package com.playtranslate.camera

import android.app.Activity
import android.graphics.Bitmap
import android.view.Display
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.isVisible
import com.playtranslate.CaptureSession
import com.playtranslate.OcrTokenScope
import com.playtranslate.Prefs
import com.playtranslate.camera.render.FrozenLookupScene
import com.playtranslate.language.SourceLangId
import com.playtranslate.ocr.registry.selectionToken
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.ui.ActivitySheetHost
import com.playtranslate.ui.CaptureOverlaySettingsActivity
import com.playtranslate.ui.CaptureResultOverlay
import com.playtranslate.ui.OcrPicker
import com.playtranslate.ui.OverlayAlert
import com.playtranslate.ui.TtsAlertTarget
import com.playtranslate.ui.showAnkiNotInstalledDialog

/** Re-raster hysteresis for the review zoom (the live path's
 *  RASTER_SCALE_DRIFT): pinching around a midpoint must not re-bake the
 *  rasters on every settle. */
private const val RE_RASTER_DRIFT = 1.3f

/**
 * The pipeline a [FrozenReviewPanel] drives: one-shot OCR + translate over a
 * retained still frame. The camera's [CameraSession] snapshot cluster and the
 * import tool's session both implement it.
 */
interface FrozenReviewBackend {
    /** Run (or re-run) the review pipeline over [bitmap], optionally
     *  region-filtered. Each call supersedes the previous run. */
    fun runReview(bitmap: Bitmap, regionAu: android.graphics.Rect?): CaptureSession

    /** The frozen scene currently owning the display, for word lookup. */
    fun lookupScene(): FrozenLookupScene?

    /** The panel's in-place-edit re-translation. */
    suspend fun translateForPanel(text: String): CaptureResultOverlay.PanelTranslation?
}

/**
 * The flow-agnostic frozen-review glue shared by the camera snapshot and the
 * import-image review: builds the in-app [CaptureResultOverlay] with every
 * over-game behavior overridden, runs the backend pipeline into it, and owns
 * the review-scoped UI — the crop editor + region indicator (AU↔view through
 * [CameraCoordinates] in the flow's [fitMode]), the frozen-frame word lookup,
 * and the frosted-backdrop projection.
 *
 * The HOST keeps what differs per flow: the bitmap's lifetime (supplied via
 * [bitmap]), the box presenter (the camera gates skeletons behind kept live
 * overlays), chrome sync ([onControlsChanged]), and teardown beyond the
 * review itself ([onDismissed] — mode restore, orientation, bitmap
 * retirement).
 *
 * A REVIEW EPISODE spans startReview()..(dismiss teardown): the user-drawn
 * region is scoped to one episode — a new still means the scene changed and
 * a stale rect would silently drop text.
 *
 * Main thread only.
 */
class FrozenReviewPanel(
    private val activity: Activity,
    private val panelHost: ViewGroup,
    private val regionUi: CameraRegionUi,
    private val backend: FrozenReviewBackend,
    /** The retained frame under review, or null when none — read at use
     *  time so replacements are picked up automatically. */
    private val bitmap: () -> Bitmap?,
    /** The flow's on-frame box rendering — see [CaptureResultOverlay.BoxPresenter]. */
    private val boxPresenter: CaptureResultOverlay.BoxPresenter,
    /** The flow's boxes-toggle + start-collapsed persistence. */
    private val presentationPrefs: CaptureResultOverlay.PresentationPrefs,
    /** Which per-surface OCR selection the panel's picker persists to, and
     *  which scope its download deep-links carry. */
    private val tokenScope: OcrTokenScope,
    /** AU→view mapping mode; must match the flow's image scale type. */
    private val fitMode: CameraCoordinates.FitMode,
    /** The flow's zoom ceiling policy (IMPORT native-capped, CAMERA
     *  photo-floored) — see [ReviewZoom]. */
    private val zoomPolicy: ReviewZoom.CeilingPolicy,
    /** Apply the review zoom to the flow's image view: a full Z·fit matrix,
     *  or null at fit — null MUST restore the view's declared scale type
     *  (fitCenter/centerCrop), the byte-identical regression path. */
    private val setImageTransform: (android.graphics.Matrix?) -> Unit,
    /** The session's warp-transform seam (null at fit). */
    private val setOverlayTransform: (DoubleArray?) -> Unit,
    /** The session's raster-crispness seam (1f at fit). */
    private val setRenderScaleBoost: (Float) -> Unit,
    /** Repaint the boxes from the session caches at the current transform +
     *  boost. The HOST gates on boxes actually being the current
     *  presentation (the flavor-cycle precedent: an ungated repaint would
     *  resurrect boxes the user toggled off). */
    private val repaintOverlays: () -> Unit,
    /** Chrome must re-derive visibility (crop editor took/released the
     *  screen, boxes presentation changed). */
    private val onControlsChanged: () -> Unit,
    /** The picker launched the OCR download screen — the camera closes its
     *  snapshot (the app navigates away); the import review stays behind
     *  and refreshes on return. */
    private val onDownloadLaunched: () -> Unit,
    /** Review-episode teardown beyond the panel's own cleanup. Runs exactly
     *  once per episode, on EVERY dismissal path. */
    private val onDismissed: () -> Unit,
) {
    private val prefs = Prefs(activity)

    private var overlay: CaptureResultOverlay? = null
    private var reviewSession: CaptureSession? = null

    /** User-drawn region (AU px of the reviewed frame), or null for the
     *  whole frame. Episode-scoped — see the class doc. */
    private var regionAu: android.graphics.Rect? = null

    /** True while the crop editor owns the screen (panel hidden, region
     *  drag box + confirm bar up). */
    private var cropActive = false

    /** The review zoom's state + gesture arbiter. The gesture layer wraps
     *  the outside-touch stream: lookup at fit, pan/tap-define while
     *  zoomed, pinch always (up to the policy ceiling). */
    private val zoom = ReviewZoom(zoomPolicy)

    /** Frozen-frame word lookup: tap/drag on the still itself (outside the
     *  sheet) drives the floating-icon lens machinery against the review's
     *  cached OCR. Gesture-time suppliers keep it current across re-runs.
     *  The zoom transform composes into its line projection AND the
     *  lens-backdrop projection — the drag stack's "bitmap == screen"
     *  contract must hold in both zoom states (zoomed taps route through
     *  this machine too). */
    private val wordLookup = CameraWordLookup(
        activity,
        scene = { backend.lookupScene() },
        frozenBitmap = { bitmap() },
        renderViewSpace = { bmp, w, h -> renderViewSpaceBackdrop(bmp, w, h, zoomMatrix()) },
        hostSize = { hostSize() },
        hostOrigin = { hostOrigin() },
        fitMode = fitMode,
        viewTransform = { zoomMatrix() },
    )

    private val zoomGesture = ReviewZoomGesture(
        activity,
        zoom,
        wordLookup,
        hostOrigin = { hostOrigin() },
        onChanged = { onZoomChanged() },
        onSettled = { onZoomSettled() },
    )

    /** The raster boost last committed to the session — the settle
     *  hysteresis baseline (mirrors the live path's RASTER_SCALE_DRIFT). */
    private var lastRasterBoost = 1f

    private fun hostOrigin(): Pair<Int, Int> {
        val loc = IntArray(2)
        panelHost.getLocationOnScreen(loc)
        return loc[0] to loc[1]
    }

    /** The current zoom as a view-space Matrix, or null at fit. */
    private fun zoomMatrix(): android.graphics.Matrix? =
        if (zoom.isAtFit) null
        else android.graphics.Matrix().apply {
            setScale(zoom.zoom, zoom.zoom)
            postTranslate(zoom.panX, zoom.panY)
        }

    /** Transform fan-out: every consumer reads the same Z (or null at fit —
     *  the byte-identical path). */
    private fun onZoomChanged() {
        val z = zoomMatrix()
        if (z == null) {
            setImageTransform(null)
            setOverlayTransform(null)
        } else {
            val bmp = bitmap()
            if (bmp != null) {
                val (w, h) = hostSize()
                val c = coords(bmp, w, h)
                setImageTransform(
                    android.graphics.Matrix().apply {
                        setScale(c.scale, c.scale)
                        postTranslate(c.offsetX, c.offsetY)
                        postConcat(z)
                    },
                )
            }
            setOverlayTransform(zoom.toHomographyRow())
        }
        // The active-region indicator follows the transform in place.
        val au = currentRegionForIndicator()
        if (au != null) {
            val bmp = bitmap()
            if (bmp != null) {
                val (w, h) = hostSize()
                val viewRect = coords(bmp, w, h).auToView(au)
                z?.let { m ->
                    val rf = android.graphics.RectF(viewRect)
                    m.mapRect(rf)
                    viewRect.set(
                        Math.round(rf.left), Math.round(rf.top),
                        Math.round(rf.right), Math.round(rf.bottom),
                    )
                }
                regionUi.updateIndicatorRect(viewRect)
            }
        }
    }

    private fun currentRegionForIndicator(): android.graphics.Rect? =
        if (cropActive) null else regionAu

    /** Gesture ended: re-raster for crispness when the zoom drifted far
     *  enough from the last-baked resolution (1.3x hysteresis, the live
     *  path's drift constant — pinching around a midpoint doesn't re-bake
     *  every frame). */
    private fun onZoomSettled() {
        val target = if (zoom.isAtFit) 1f else zoom.zoom
        val ratio = if (target > lastRasterBoost) target / lastRasterBoost else lastRasterBoost / target
        if (ratio < RE_RASTER_DRIFT) return
        lastRasterBoost = target
        setRenderScaleBoost(target)
        repaintOverlays()
    }

    val isCropActive: Boolean get() = cropActive

    /** True while a review episode is active (panel up or slivered). */
    val hasActiveReview: Boolean get() = overlay != null

    // ── Review lifecycle ────────────────────────────────────────────────

    /** Build the in-app panel with every over-game behavior overridden, then
     *  run the backend pipeline into it. */
    fun startReview(frame: Bitmap) {
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
        o.boxPresenter = boxPresenter
        o.presentationPrefs = presentationPrefs
        o.ttsAlertTarget = TtsAlertTarget.InActivity(activity)
        // Tap-a-word lens hosted as an activity window (the panel's wm IS
        // the activity's WindowManager) — no overlay permission involved.
        o.wordLensInActivity = true
        o.showAnkiNotInstalled = { showAnkiNotInstalledDialog(activity) }
        // Anki review launches on top of the hosting activity; the still
        // frame + sheet stay behind it and restore when the user backs out.
        o.dismissOnActivityLaunch = false
        // Leaving the review is the explicit X only — outside taps and
        // drag/fling-downs settle instead of dismissing.
        o.dismissOnGesture = false
        // Outside gestures route through the zoom arbiter: lookup at fit
        // (tap = definition lens, drag = magnifier flow), pan/tap-define
        // while zoomed, pinch always. With a ceiling of 1 (at-native
        // content) the arbiter is a pure passthrough to the lookup.
        o.outsideLookupRouter = { ev -> zoomGesture.onOutsideTouch(ev) }
        o.retranslate = { text -> backend.translateForPanel(text) }
        o.chooseOcr = { prov, _ ->
            // The shared picker's activity form (same one the in-app results
            // page uses). A downloaded pick re-OCRs the SAME retained frame
            // through a fresh backend run on the same panel; a not-downloaded
            // pick deep-links the OCR download screen (the host decides
            // whether the review survives the navigation).
            OcrPicker.populate(
                OverlayAlert.Builder(activity),
                activity,
                prov.sourceLangId,
                prov.engineToken,
                onReOcr = { reRunReview() },
                onDownload = { chosen ->
                    // Scope-routed: the download screen persists THIS flow's
                    // token, only on verified success — an aborted download
                    // can't cost the user their current engine, and the
                    // global/live engine never moves on a tool-only action.
                    activity.startActivity(
                        CaptureOverlaySettingsActivity.downloadIntent(
                            activity, prov.sourceLangId, chosen.selectionToken,
                            scope = tokenScope,
                        )
                    )
                    onDownloadLaunched()
                },
                // This flow's OCR choice is its own per-surface setting.
                applyToken = { chosen ->
                    persistToken(prov.sourceLangId, chosen.selectionToken)
                },
            ).show()
        }
        o.onDismiss = { finishReview() }
        overlay = o

        val (w, h) = hostSize()
        // Bind the zoom to this frame's geometry (ceiling from content vs
        // viewport) and start at fit with identity fanned out.
        zoom.configure(frame.width, frame.height, w, h)
        lastRasterBoost = 1f
        onZoomChanged()
        // The sheet's frosted body samples a SCREEN-SPACE image of what sits
        // behind it (the capture flow passes the clean screen frame). Project
        // the AU-space frame through the same mapping the image view displays
        // it with, so the frost shows exactly the visible slice. show()
        // copies what it needs (downscaled blur), so the projection is
        // recycled immediately.
        val backdrop = renderViewSpaceBackdrop(frame, w, h)
        o.show(w, h, backdrop)
        backdrop?.recycle()
        val s = backend.runReview(frame, regionAu)
        reviewSession = s
        o.observe(s)
    }

    private fun persistToken(id: SourceLangId, token: String) = when (tokenScope) {
        OcrTokenScope.GLOBAL -> prefs.setOcrBackendToken(id, token)
        OcrTokenScope.CAMERA -> prefs.setCameraOcrBackendToken(id, token)
        OcrTokenScope.IMPORT -> prefs.setImportOcrBackendToken(id, token)
    }

    /** Read settings changed (source language, OCR engine — the pill's gear
     *  menu): re-read the SAME retained frame under the new selections,
     *  restarting the panel flow from its loading indication — the boxes
     *  and results on display describe the OLD settings. */
    fun refreshAfterSettings() {
        if (overlay == null) return
        overlay?.prepareForSettingsRefresh()
        reRunReview()
    }

    /** Gear re-OCR and region changes: run a fresh backend session over the
     *  SAME retained frame (recognise picks up the engine selection the
     *  picker just persisted; the current [regionAu] rides along) and drive
     *  the same panel through the loading stages again — observe() cancels
     *  the prior session. */
    fun reRunReview() {
        val o = overlay ?: return
        val frame = bitmap() ?: return
        // The lookup lens describes the outgoing scene (its lines/region
        // are about to be replaced) — take it down with the re-run.
        wordLookup.dismiss()
        val s = backend.runReview(frame, regionAu)
        reviewSession = s
        o.observe(s)
    }

    /** X or system back: route through the panel's dismiss so every exit
     *  path is the same path (the panel also dismisses via its own flows,
     *  which land in onDismiss → [finishReview] directly). */
    fun dismissReview() {
        val o = overlay
        if (o != null) o.dismiss() else finishReview()
    }

    /** The single episode teardown: drop the panel + review-scoped UI, then
     *  hand the host its share. Runs exactly once per episode (the panel's
     *  dismiss is idempotent). */
    private fun finishReview() {
        overlay = null
        reviewSession = null
        // The zoom dies with the episode (the sessions also reset their own
        // seams at endEpisode/unfreeze — this is the panel's share).
        zoomGesture.resetToFit()
        lastRasterBoost = 1f
        setRenderScaleBoost(1f)
        wordLookup.dismiss()
        // Region + its UI die with the episode (see regionAu).
        if (cropActive) {
            cropActive = false
            regionUi.hideEditor()
            panelHost.isVisible = true
        }
        regionAu = null
        regionUi.hideIndicator()
        onDismissed()
    }

    /** Activity teardown: cancel the review without the episode restore —
     *  there is no UI state to put back, and the backend's executors are
     *  about to die. */
    fun release() {
        wordLookup.destroy()
        regionUi.destroy()
        overlay?.let { o ->
            o.onDismiss = null
            o.dismiss()
        }
        overlay = null
        reviewSession = null
    }

    // ── Crop editor ─────────────────────────────────────────────────────

    /** System back while the crop editor is up: cancel the edit, keep the
     *  review. */
    fun cancelCrop() = exitCropMode(rerun = false)

    fun enterCropMode() {
        if (overlay == null || cropActive || bitmap() == null) return
        // The editor's fraction↔AU math runs at fit — snap back first
        // (user-decided design: crop precision lives at the fit view).
        zoomGesture.resetToFit()
        onZoomSettled()
        cropActive = true
        // The editor replaces both region surfaces: the indicator (it IS the
        // editable version of it) and the panel ("temporarily hide" — the
        // sheet comes back exactly as left; visibility only, no dismissal).
        // An open word-lookup lens goes too — it floats above the editor.
        wordLookup.dismiss()
        regionUi.hideIndicator()
        panelHost.isVisible = false
        onControlsChanged()
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
        onControlsChanged()
        syncIndicator()
        if (rerun) reRunReview()
    }

    /** Show/hide the dashed active-region indicator to match [regionAu]. */
    private fun syncIndicator() {
        val au = regionAu
        val bmp = bitmap()
        if (au == null || bmp == null || overlay == null || cropActive) {
            regionUi.hideIndicator()
            return
        }
        val (w, h) = hostSize()
        regionUi.showIndicator(coords(bmp, w, h).auToView(au)) {
            removeRegion()
        }
    }

    /** The indicator's Remove pill: drop the region and read the whole
     *  frame again. */
    private fun removeRegion() {
        if (regionAu == null) return
        regionAu = null
        syncIndicator()
        reRunReview()
    }

    /** The current region as screen fractions for the editor, or null for
     *  the editor's default box. */
    private fun editorInitFractions(): android.graphics.RectF? {
        val au = regionAu ?: return null
        val bmp = bitmap() ?: return null
        val (w, h) = hostSize()
        val vr = coords(bmp, w, h).auToView(au)
        return android.graphics.RectF(
            vr.left.toFloat() / w,
            vr.top.toFloat() / h,
            vr.right.toFloat() / w,
            vr.bottom.toFloat() / h,
        )
    }

    /** Confirmed drag fractions → AU px on the reviewed frame, clamped to
     *  the frame. Null (nothing intersects — reachable under FIT when the
     *  drag lands entirely in the letterbox) clears the region. */
    private fun fractionsToAu(f: android.graphics.RectF): android.graphics.Rect? {
        val bmp = bitmap() ?: return null
        val (w, h) = hostSize()
        val viewRect = android.graphics.Rect(
            Math.round(f.left * w),
            Math.round(f.top * h),
            Math.round(f.right * w),
            Math.round(f.bottom * h),
        )
        val au = coords(bmp, w, h).viewToAu(viewRect)
        return if (au.intersect(0, 0, bmp.width, bmp.height)) au else null
    }

    // ── Geometry ────────────────────────────────────────────────────────

    private fun coords(bmp: Bitmap, w: Int, h: Int) =
        CameraCoordinates(bmp.width, bmp.height, w, h, fitMode)

    private fun hostSize(): Pair<Int, Int> {
        val w = panelHost.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val h = panelHost.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        return w to h
    }

    /** The AU frame drawn into a view-sized bitmap via the exact
     *  [CameraCoordinates] transform the image view displays it with —
     *  what the user actually sees behind the sheet. Under FIT the
     *  letterbox bars come out black (the projection canvas's default),
     *  matching the screen. [transform] composes the review zoom for the
     *  word-lens backdrop (bitmap == screen must hold zoomed too); the
     *  sheet's frost passes null — it is captured at show(), when the zoom
     *  is always at fit. */
    private fun renderViewSpaceBackdrop(
        frame: Bitmap,
        viewW: Int,
        viewH: Int,
        transform: android.graphics.Matrix? = null,
    ): Bitmap? {
        if (viewW <= 0 || viewH <= 0 || frame.isRecycled) return null
        return try {
            val c = coords(frame, viewW, viewH)
            val out = Bitmap.createBitmap(viewW, viewH, Bitmap.Config.ARGB_8888)
            val matrix = android.graphics.Matrix().apply {
                setScale(c.scale, c.scale)
                postTranslate(c.offsetX, c.offsetY)
                transform?.let { postConcat(it) }
            }
            android.graphics.Canvas(out).drawBitmap(
                frame, matrix,
                android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
            )
            out
        } catch (e: Exception) {
            android.util.Log.w("FrozenReviewPanel", "backdrop projection failed", e)
            null
        }
    }
}
