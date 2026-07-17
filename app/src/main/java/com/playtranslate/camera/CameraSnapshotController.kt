package com.playtranslate.camera

import android.graphics.Bitmap
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.view.isVisible
import com.playtranslate.R

/**
 * Activity-scoped owner of the camera's play/pause + snapshot UI state:
 * control visibility per [CameraSession.Mode], the frozen-frame ImageView
 * and its bitmap lifetime, and (Phase 5) hosting the capture result panel
 * in-app.
 *
 * Control states:
 *  - LIVE:   back + mode toggle + pause icon + shutter.
 *  - PAUSED: back + mode toggle + play icon + shutter; overlays cleared.
 *  - FROZEN: the back button becomes an X (close snapshot); everything else
 *    hides. X restores the PRE-snapshot mode — a paused camera stays paused.
 *
 * Main thread only.
 */
class CameraSnapshotController(
    private val session: CameraSession,
    private val backButton: ImageButton,
    private val playPauseButton: ImageButton,
    private val shutterButton: ImageButton,
    private val modeToggle: View,
    private val freezeFrame: ImageView,
    /** Whether the Translation/Furigana toggle is available at all for the
     *  current source language (the activity owns that decision). */
    private val modeToggleSupported: () -> Boolean,
    /** LIVE/PAUSED back press — leave the screen. */
    private val onExit: () -> Unit,
) {
    private var frozenBitmap: Bitmap? = null

    /** Mode to restore when the snapshot closes. */
    private var preFreezeMode = CameraSession.Mode.LIVE

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
        // One freeze in flight at a time; re-enabled by syncControls on
        // either outcome.
        shutterButton.isEnabled = false
        session.requestFreeze { bitmap ->
            if (released) {
                bitmap.recycle()
                return@requestFreeze
            }
            frozenBitmap = bitmap
            freezeFrame.setImageBitmap(bitmap)
            freezeFrame.isVisible = true
            syncControls()
            // Phase 4/5: launch the snapshot pipeline + capture panel here.
        }
    }

    /** X, system back, or (Phase 5) panel dismissal: drop the snapshot and
     *  restore the pre-snapshot mode. */
    fun unfreeze() {
        if (!isFrozen) return
        session.unfreeze(preFreezeMode)
        freezeFrame.isVisible = false
        freezeFrame.setImageBitmap(null)
        frozenBitmap?.recycle()
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
        frozenBitmap?.recycle()
        frozenBitmap = null
    }
}
