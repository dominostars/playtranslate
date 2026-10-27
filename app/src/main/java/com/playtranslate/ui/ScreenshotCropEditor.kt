package com.playtranslate.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.playtranslate.CaptureService
import com.playtranslate.R
import com.playtranslate.applyAccentOverlay
import com.playtranslate.applyDialogEdgeToEdge
import com.playtranslate.camera.CameraCoordinates
import com.playtranslate.fullScreenDialogTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Anki screenshot crop editor: the card's screenshot on a black ground,
 * fitted, with the shared [RegionEditor] over it (the drag box confined to
 * the image, the ✕/🗑/✓ bar and the instruction pill). It writes to the
 * [AnkiScreenshotState] it edits: the box starts on the crop that stands,
 * else on the capture region, else the editor's default; ✓ sets the crop (a
 * box over the whole image clears it), 🗑 (present only while a crop
 * stands) clears it, ✕ leaves it. Every outcome then calls [onClose] once;
 * the host takes the editor down. One class for every host: the review
 * sheets show it in a dialog window ([showScreenshotCropDialog]), the
 * floating workspace as a page ([ScreenshotCropPage]).
 *
 * [view] is the stage (the frame and the drag box, full bleed); [controls]
 * is the chrome's layer over it, which a host pads for the insets its bars
 * take (the camera editor's two-host shape). The frame is re-fitted on
 * every size change of the stage, the box keeping its fractions.
 *
 * Main thread only.
 */
internal class ScreenshotCropEditor(
    private val ctx: Context,
    scope: CoroutineScope,
    private val state: AnkiScreenshotState,
    private val onClose: () -> Unit,
) {
    /** The stage: the frame on a black ground, the drag box over it. Not
     *  clickable: the workspace collects clickable views as controller
     *  targets, and the stage is not one. */
    val view: FrameLayout = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }

    /** The chrome's layer, over the stage; pad it for the host's insets. */
    val controls: FrameLayout = FrameLayout(ctx)

    private val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val editor = RegionEditor(ctx, view, controls)
    private var bitmap: Bitmap? = null
    private var closed = false
    private val frame = RectF()

    init {
        view.addView(
            image,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        view.addView(
            controls,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        view.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) refit()
        }
        // The same decode the preview cell shows (display-only; the send
        // reads the file), off the main thread.
        scope.launch {
            val path = state.attachment?.path
            val bmp = path?.let {
                withContext(Dispatchers.IO) {
                    decodeScreenshotForDisplay(File(it), ctx.resources.displayMetrics.widthPixels)
                }
            }
            if (closed) return@launch
            if (bmp == null) {
                close()
                return@launch
            }
            bitmap = bmp
            image.setImageBitmap(bmp)
            refit()
        }
    }

    /** The image's fitted rect in the stage: the frame the box lives in. */
    private fun fittedFrame(bmp: Bitmap): RectF? {
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return null
        val r = CameraCoordinates(bmp.width, bmp.height, w, h, CameraCoordinates.FitMode.FIT)
            .auToView(Rect(0, 0, bmp.width, bmp.height))
        return RectF(r)
    }

    /** The stage has a size and the frame has landed: show the editor, or
     *  move its frame. */
    private fun refit() {
        val bmp = bitmap ?: return
        if (closed) return
        val f = fittedFrame(bmp) ?: return
        if (!editor.isShowing) {
            show(f)
        } else if (f != frame) {
            frame.set(f)
            editor.setContentBounds(f)
        }
    }

    private fun show(f: RectF) {
        frame.set(f)
        val crop = state.attachment?.crop
        editor.show(
            init = (crop ?: captureRegion())?.toRectF(),
            instruction = R.string.anki_screenshot_crop_instruction,
            onCancel = { close() },
            onClear = if (crop != null) {
                {
                    state.setCrop(null)
                    close()
                }
            } else null,
            onConfirm = { fractions ->
                state.setCrop(ScreenshotCrop.of(fractions))
                close()
            },
            bounds = f,
        )
        // The drag box was added above the chrome; the chrome goes back on top.
        view.bringChildToFront(controls)
    }

    /** Where the box starts while no crop stands: the capture region, as the
     *  floating icon's region editor starts on it (a capture saves the whole
     *  screen and the region is fractions of it). Null for a full-screen or
     *  empty region ([ScreenshotCrop.of]) or with no capture service, which
     *  leaves the editor's default box. */
    private fun captureRegion(): ScreenshotCrop? =
        CaptureService.instance?.activeRegion?.let { ScreenshotCrop.of(it.left, it.top, it.right, it.bottom) }

    private fun close() {
        if (closed) return
        closed = true
        onClose()
    }

    /** The host took the editor down. */
    fun release() {
        closed = true
        editor.hide()
        image.setImageBitmap(null)
        bitmap = null
    }
}

/**
 * The review sheets' presentation of [ScreenshotCropEditor]: its own
 * full-screen dialog window over the sheet, the sheet's own recipe (the
 * full-screen dialog theme with the accent overlay, edge to edge, the
 * slide animation), with the chrome padded clear of the system bars. Back,
 * the back gesture and a controller's B go through the dialog's own back
 * handling and cancel; the sheet's keyboard drops. The window goes with
 * the fragment's view, so process death closes it: the draft box is lost
 * and the crop that stands survives in the state's args.
 */
internal fun Fragment.showScreenshotCropDialog(state: AnkiScreenshotState) {
    val ctx = requireContext()
    val owner = viewLifecycleOwner
    val dialog = ComponentDialog(ctx, fullScreenDialogTheme(ctx))
    applyAccentOverlay(dialog.context.theme, ctx)
    val editor = ScreenshotCropEditor(ctx, owner.lifecycleScope, state) { dialog.dismiss() }
    ViewCompat.setOnApplyWindowInsetsListener(editor.view) { _, insets ->
        val sys = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        editor.controls.setPadding(sys.left, sys.top, sys.right, sys.bottom)
        WindowInsetsCompat.CONSUMED
    }
    dialog.setContentView(editor.view)
    dialog.window?.apply {
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setWindowAnimations(R.style.AnimSlideRight)
        applyDialogEdgeToEdge(this, ctx)
    }
    val closeWithView = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) = dialog.dismiss()
    }
    owner.lifecycle.addObserver(closeWithView)
    dialog.setOnDismissListener {
        owner.lifecycle.removeObserver(closeWithView)
        editor.release()
    }
    // The sheet's focused field would keep the keyboard up under the new
    // window.
    view?.findFocus()?.let { focused ->
        focused.clearFocus()
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(focused.windowToken, 0)
    }
    dialog.show()
}
