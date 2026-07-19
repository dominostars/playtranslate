package com.playtranslate.imageimport

import android.app.Activity
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.view.isVisible
import com.playtranslate.OcrTokenScope
import com.playtranslate.OneShotOverlayData
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.camera.CameraCoordinates
import com.playtranslate.camera.CameraRegionUi
import com.playtranslate.camera.FrozenReviewPanel
import com.playtranslate.ui.CaptureResultOverlay

/**
 * Activity-scoped owner of the import tool's review state: the imported
 * image's display + bitmap lifetime, the review chrome (X, crop, gear pill),
 * and the shared [FrozenReviewPanel] driven by [ImageImportSession].
 *
 * The import review is the camera's FROZEN state minus the camera: there is
 * no play/pause, no shutter, and no pre-freeze mode to restore — dismissing
 * the review returns to the picker landing (or finishes a share-sheet entry;
 * the host decides via [onReviewClosed]).
 *
 * Bitmap discipline matches the camera's: replaced frames are DROPPED for
 * GC, never recycle()d — pipeline cancellation is cooperative, so a
 * cancelled recognizer can still be reading the outgoing bitmap (ML Kit
 * holds it through its whole pass), and no spacing rule survives a fast
 * dismiss-then-reload.
 *
 * Main thread only.
 */
class ImageReviewController(
    private val activity: Activity,
    private val session: ImageImportSession,
    private val backButton: ImageButton,
    private val regionButton: ImageButton,
    /** The top-right control pill hosting crop/settings — hidden on the
     *  landing and while the crop editor owns the screen. */
    private val controlPill: View,
    private val imageFrame: ImageView,
    panelHost: ViewGroup,
    regionUi: CameraRegionUi,
    /** Back tap while NOT reviewing (the landing) — leave the screen. */
    private val onExit: () -> Unit,
    /** The review was dismissed (X, system back) and its teardown finished —
     *  show the landing or finish. */
    private val onReviewClosed: () -> Unit,
) {
    private val prefs = Prefs(activity)

    private var currentBitmap: Bitmap? = null

    /** The previous review's frame — see the class doc. */
    private var retiredBitmap: Bitmap? = null

    /** Orientation request to restore when the review closes. While
     *  reviewing, the activity is pinned to its load-time orientation — a
     *  rotation would recreate the activity mid-review; the landing rotates
     *  freely. Pin/restore own their idempotence as a matched pair. */
    private var preReviewOrientation =
        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var orientationPinned = false

    private fun restoreOrientation() {
        if (!orientationPinned) return
        orientationPinned = false
        activity.requestedOrientation = preReviewOrientation
    }

    /** The shared frozen-review surface, letterbox (FIT) mode, import OCR
     *  scope. The presenter routes on-frame boxes through the import
     *  session's static warp path — no kept-overlays gate: an import never
     *  has live boxes to keep. */
    private val panel = FrozenReviewPanel(
        activity = activity,
        panelHost = panelHost,
        regionUi = regionUi,
        backend = session,
        bitmap = { currentBitmap },
        boxPresenter = object : CaptureResultOverlay.BoxPresenter {
            override fun show(data: OneShotOverlayData): Boolean {
                syncControls()
                session.showOverlays()
                return true
            }

            override fun update(data: OneShotOverlayData) {
                session.showOverlays()
            }

            override fun hide() {
                syncControls()
                session.hideOverlays()
            }
        },
        presentationPrefs = object : CaptureResultOverlay.PresentationPrefs {
            override var boxesEnabled: Boolean
                get() = prefs.importBoxesEnabled
                set(value) {
                    prefs.importBoxesEnabled = value
                }
            override var startCollapsed: Boolean
                get() = prefs.importPanelStartCollapsed
                set(value) {
                    prefs.importPanelStartCollapsed = value
                }
        },
        tokenScope = OcrTokenScope.IMPORT,
        fitMode = CameraCoordinates.FitMode.FIT,
        zoomPolicy = com.playtranslate.camera.ReviewZoom.CeilingPolicy.IMPORT,
        setImageTransform = { m ->
            if (m == null) {
                // The regression path: at fit the view runs its DECLARED
                // scale type, byte-identical to a build without zoom.
                imageFrame.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            } else {
                imageFrame.scaleType = android.widget.ImageView.ScaleType.MATRIX
                imageFrame.imageMatrix = m
            }
        },
        setOverlayTransform = { session.setOverlayViewTransform(it) },
        setRenderScaleBoost = { session.setOverlayRenderBoost(it) },
        repaintOverlays = {
            // Gated repaint (the flavor-cycle precedent): only when boxes
            // own the frame — an ungated showOverlays would resurrect boxes
            // the user toggled off.
            if (session.hasVisibleOverlays()) session.showOverlays()
        },
        onControlsChanged = { syncControls() },
        // A not-downloaded OCR pick navigates to the download screen; the
        // review stays behind it and the activity's onResume langKey diff
        // re-reads on return (the camera closes its snapshot here — an
        // import has a retained image worth coming back to).
        onDownloadLaunched = {},
        onDismissed = { finishReview() },
    )

    /** Set by the activity's onDestroy. */
    private var released = false

    val isReviewing: Boolean get() = panel.hasActiveReview
    val isCropActive: Boolean get() = panel.isCropActive

    init {
        backButton.setOnClickListener { if (isReviewing) dismissReview() else onExit() }
        regionButton.setOnClickListener { panel.enterCropMode() }
        syncControls()
    }

    /** System back while the crop editor is up: cancel the edit, keep the
     *  review. */
    fun cancelCrop() = panel.cancelCrop()

    /** Begin reviewing [bitmap] (ownership transfers here — dropped for GC
     *  on replacement). Any active review is torn down first so its panel,
     *  region, and caches never describe the new image. */
    fun startReview(bitmap: Bitmap) {
        if (released) return
        if (isReviewing) panel.dismissReview()
        retiredBitmap = null
        currentBitmap = bitmap
        session.startEpisode()
        imageFrame.setImageBitmap(bitmap)
        imageFrame.isVisible = true
        if (!orientationPinned) {
            preReviewOrientation = activity.requestedOrientation
            orientationPinned = true
        }
        activity.requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
        syncControls()
        panel.startReview(bitmap)
    }

    /** Read settings changed (source language, OCR engine — the gear menu
     *  or the slow-OCR rescue): re-read the SAME retained image under the
     *  new selections. */
    fun refreshReview() {
        if (!isReviewing) return
        panel.refreshAfterSettings()
    }

    /** X or system back: route through the panel's dismiss so every exit
     *  path is the same path. */
    fun dismissReview() {
        if (!isReviewing) return
        panel.dismissReview()
    }

    /** The import share of the episode teardown (the panel already tore
     *  down the review-scoped UI): wipe the session's scene, drop the image,
     *  restore orientation, hand control back to the host. */
    private fun finishReview() {
        session.endEpisode()
        restoreOrientation()
        imageFrame.isVisible = false
        imageFrame.setImageBitmap(null)
        // Dropped for GC, never recycled — see the class doc.
        retiredBitmap = currentBitmap
        currentBitmap = null
        syncControls()
        onReviewClosed()
    }

    /** Re-derive the chrome from the review/crop state. Public for the
     *  activity's onResume re-sync. */
    fun syncControls() {
        val reviewing = isReviewing
        val cropActive = panel.isCropActive
        backButton.setImageResource(if (reviewing) R.drawable.ic_close else R.drawable.ic_arrow_back)
        backButton.contentDescription = backButton.context.getString(
            if (reviewing) R.string.image_import_close_cd else R.string.camera_back_cd
        )
        // The crop editor owns the whole screen while active: its own bar
        // carries cancel/confirm, so the X and the pill step aside.
        backButton.isVisible = !cropActive
        controlPill.isVisible = reviewing && !cropActive
        regionButton.isVisible = reviewing
    }

    fun release() {
        released = true
        restoreOrientation()
        panel.release()
        // Deliberately NOT recycled — see the class doc.
        currentBitmap = null
        retiredBitmap = null
    }
}
