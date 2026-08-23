package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import com.playtranslate.PlayTranslateApplication
import com.playtranslate.R
import com.playtranslate.overlayThemedContext
import com.playtranslate.themeColor
import com.playtranslate.translation.llm.humanSize

/**
 * Switch this progress dialog to a determinate "Downloading offline model…"
 * state for a Bergamot (Firefox Translations) warm-up download. [index]/[count]
 * is 1-based across the pair's required directions (1 = single hop, 2 = English
 * pivot). Call on the UI thread.
 */
fun OverlayProgress.showBergamotWarmupProgress(
    context: Context,
    index: Int,
    count: Int,
    received: Long,
    total: Long,
) {
    setIndeterminate(false)
    setProgress(if (total > 0) ((received * 100L) / total).toInt() else 0)
    setMessage(
        if (count > 1) {
            context.getString(
                R.string.bergamot_warmup_downloading_multi,
                index, count, humanSize(context, received), humanSize(context, total),
            )
        } else {
            context.getString(
                R.string.bergamot_warmup_downloading,
                humanSize(context, received), humanSize(context, total),
            )
        },
    )
}

/**
 * Switch this progress dialog to a determinate OCR-model download state, mirroring
 * [showBergamotWarmupProgress] so the OCR pack download reads like the other
 * downloads in the language-setup flow. Call on the UI thread.
 */
fun OverlayProgress.showOcrDownloadProgress(context: Context, received: Long, total: Long) {
    setIndeterminate(false)
    setProgress(if (total > 0) ((received * 100L) / total).toInt() else 0)
    setMessage(
        context.getString(
            R.string.lang_setup_downloading_ocr_model,
            humanSize(context, received), humanSize(context, total),
        ),
    )
}

/**
 * Switch this progress dialog to a determinate Yomitan-dictionary download
 * state, mirroring [showOcrDownloadProgress]. Call on the UI thread.
 */
fun OverlayProgress.showYomitanDownloadProgress(context: Context, received: Long, total: Long) {
    setIndeterminate(false)
    setProgress(if (total > 0) ((received * 100L) / total).toInt() else 0)
    setMessage(
        context.getString(
            R.string.yomitan_downloading_message,
            humanSize(context, received), humanSize(context, total),
        ),
    )
}

/** Why this dialog went away. Callers branch on this in [OverlayProgress.Builder.setOnDismiss]
 *  to decide whether to nuke resume state (USER) or just stop using
 *  bandwidth/CPU (LIFECYCLE_PAUSE). */
enum class DismissReason {
    /** User explicitly bailed — cancel-button tap or back-press. */
    USER,
    /** Dismissed by the lifecycle rather than the user; they may come back, so
     *  keep the .partial. For [OverlayAlert] this fires when the host activity
     *  pauses. For [OverlayProgress] it fires only on host-window teardown /
     *  activity destroy — a plain pause/stop no longer dismisses progress (it
     *  rides the decorView), so by the time this fires the job is already
     *  ending via lifecycleScope. */
    LIFECYCLE_PAUSE,
}

/**
 * Reusable progress popup that mirrors [OverlayAlert]'s visual treatment
 * (full-screen scrim, rounded card, accent tints) but with a progress bar
 * in place of buttons. Used for downloads where the only user choice is
 * Cancel; the scrim itself is non-dismissable so a stray tap can't abort
 * a multi-GB download.
 *
 * Mutable state — message, progress, indeterminate flag, cancel-button
 * visibility — is exposed on the returned instance so the caller can
 * stream updates from a coroutine.
 *
 * Attaches to whichever PlayTranslate activity is currently foregrounded, then
 * RIDES that activity's decorView: it deliberately does NOT dismiss when the
 * host merely pauses or stops, so backgrounding the app, a screen timeout, or a
 * system dialog leaves the download running (on the host's lifecycleScope) and
 * the popup simply reappears on return. It is dismissed only by the user
 * (cancel-button tap / back-press → [DismissReason.USER], which typically wipes
 * the .partial so a new attempt starts fresh) or by host-window teardown /
 * activity destroy (→ [DismissReason.LIFECYCLE_PAUSE], where the job is already
 * ending via lifecycleScope and the .partial is kept for a later resume).
 * Contrast [OverlayAlert], which still dismisses on pause — correct for a
 * one-shot decision prompt, wrong for a long-running download.
 */
class OverlayProgress private constructor(
    rawContext: Context,
    private val title: String,
    private val initialMessage: String,
    private val initialProgress: Int,
    private val cancelLabel: String,
    private val onDismiss: (DismissReason) -> Unit,
) {
    private val context: Context = overlayThemedContext(rawContext)
    class Builder(rawContext: Context) {
        private val context: Context = overlayThemedContext(rawContext)
        private var title = ""
        private var initialMessage = ""
        private var initialProgress = 0
        private var cancelLabel = "Cancel"
        private var onDismiss: (DismissReason) -> Unit = {}

        fun setTitle(title: String) = apply { this.title = title }
        fun setMessage(message: String) = apply { this.initialMessage = message }
        fun setProgress(percent: Int) = apply { this.initialProgress = percent }
        fun setCancelLabel(label: String) = apply { this.cancelLabel = label }

        /** Fires when the dialog is dismissed by the user (cancel/back) or by
         *  host-window teardown / activity destroy — NOT on a plain background
         *  (the popup rides the decor and survives pause/stop). Branch on
         *  [DismissReason] to decide whether to wipe partial state (USER) or
         *  just stop the job (LIFECYCLE_PAUSE). See [OverlayProgress] class doc
         *  for the full contract. */
        fun setOnDismiss(callback: (DismissReason) -> Unit) = apply { this.onDismiss = callback }

        /** Attaches above whichever PlayTranslate surface is currently on top
         *  — a showing DialogFragment's own window (the full-screen Settings
         *  sheet that launches downloads, its nested dialogs) if one is up,
         *  else the foregrounded activity's decorView — or defers attachment
         *  to the next [Activity.onResume] if none is resumed. See
         *  [OverlayProgress] class doc for detach semantics. */
        fun show(): OverlayProgress {
            val overlay = OverlayProgress(
                context, title, initialMessage, initialProgress, cancelLabel, onDismiss,
            )
            PlayTranslateApplication.runWithForegroundActivity { activity ->
                if (overlay.dismissed || activity.isFinishing || activity.isDestroyed) return@runWithForegroundActivity
                overlay.attachToForeground(activity)
            }
            return overlay
        }

        /** Attaches as an in-window child of [parent] — the floating
         *  workspace's modal layer ([com.playtranslate.ui.OverlayWorkspace]).
         *  In-window rather than a sibling overlay window because
         *  MediaProjection's QTI clamp dims a sibling to ~80% and it steals
         *  the host window's taps (see [FontSizeRangePopover]). Cancel-button
         *  tap (and the workspace's B) dispatch [DismissReason.USER]; the
         *  parent's window tearing down mid-download (workspace dismissal)
         *  dispatches [DismissReason.LIFECYCLE_PAUSE] so the .partial is
         *  kept for a later resume. */
        fun showInParent(parent: ViewGroup): OverlayProgress {
            val overlay = OverlayProgress(
                context, title, initialMessage, initialProgress, cancelLabel, onDismiss,
            )
            overlay.attachToParent(parent)
            return overlay
        }
    }

    private var scrim: FrameLayout? = null
    private var dismissAction: (() -> Unit)? = null
    private var statusView: TextView? = null
    private var progressView: ProgressBar? = null
    private var cancelButton: Button? = null
    private var backPressedCallback: OnBackPressedCallback? = null
    private var dismissed = false

    // Mutable display state, buffered so it survives a DEFERRED attach:
    // show() runs through runWithForegroundActivity, which postpones view
    // creation to the next onResume when no activity is resumed — exactly
    // the window an ActivityResult callback (SAF picker) runs in, since
    // results are dispatched BEFORE onResume. Mutators called there must
    // land in these fields, not vanish into null views.
    private var message: String = initialMessage
    private var progressPercent: Int = initialProgress
    private var indeterminate = false
    private var cancelHidden = false

    fun setMessage(message: String) {
        this.message = message
        statusView?.text = message
    }

    fun setProgress(percent: Int) {
        indeterminate = false
        progressPercent = percent
        progressView?.let {
            it.isIndeterminate = false
            it.progress = percent
        }
    }

    fun setIndeterminate(indeterminate: Boolean) {
        this.indeterminate = indeterminate
        progressView?.isIndeterminate = indeterminate
    }

    /** Hide the Cancel button — used when the operation enters a phase
     *  that no longer supports cancellation (e.g. final on-device load
     *  after a successful download). */
    fun hideCancel() {
        cancelHidden = true
        cancelButton?.visibility = View.GONE
    }

    fun dismiss() {
        dismissed = true
        dismissAction?.invoke()
        dismissAction = null
        scrim = null
        backPressedCallback?.remove()
        backPressedCallback = null
    }

    /** Explicit user cancellation from a host that owns its own back/key
     *  handling (the workspace's B press) — identical to a cancel-button tap:
     *  dismisses and dispatches [DismissReason.USER]. */
    fun cancel() = detachAndDispatch(DismissReason.USER)

    private fun detachAndDispatch(reason: DismissReason) {
        // Idempotent: cancel / back / the detach-listener (host teardown or
        // destroy) can all race to dismiss. First one wins; the rest (including
        // the removeView that fires the detach listener) no-op so onDismiss
        // isn't invoked twice.
        if (dismissed) return
        dismiss()
        onDismiss(reason)
    }

    /**
     * Attach above whatever PlayTranslate surface is currently on top of
     * [activity] — a showing DialogFragment's own window if one is up (the
     * Settings sheet that launches downloads, its nested dialogs), otherwise
     * the activity's decorView. Attaching to the activity decor while a dialog
     * window sits above it z-orders the progress scrim behind the dialog and
     * leaves it invisible; resolving the top dialog ([topmostDialogWindow]) is
     * what keeps a settings-initiated download's progress visible.
     */
    private fun attachToForeground(activity: Activity) {
        val topDialog = topmostDialogWindow(activity)
        val scrimView = buildScrim()
        val decor = (topDialog?.window?.decorView ?: activity.window.decorView) as ViewGroup
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        decor.addView(scrimView, lp)
        scrim = scrimView
        dismissAction = { try { decor.removeView(scrimView) } catch (_: Exception) {} }

        // Terminal trigger. Unlike [OverlayAlert], a progress popup deliberately
        // does NOT dismiss when its host activity merely pauses or stops: the
        // scrim rides the host's decorView (hidden while backgrounded, re-shown
        // by the framework on return), so a screen-off, a system dialog, or a
        // brief app-switch can't kill an in-flight download — the coroutine keeps
        // running on the host's lifecycleScope. The scrim only leaves the
        // hierarchy when the host is DESTROYED (config-change recreate / process
        // kill, where lifecycleScope cancels the job anyway) or a parent Dialog
        // we're parented to is dismissed. Both tear down the decor → fire this
        // listener → detachAndDispatch(LIFECYCLE_PAUSE) (keep the .partial; the
        // job is already ending). Our own removeView is guarded by [dismissed].
        scrimView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                detachAndDispatch(DismissReason.LIFECYCLE_PAUSE)
            }
        })

        // Back-press cancels. Parented to a dialog window → register on that
        // window's dispatcher so back hits the progress popup, not the sheet.
        val backDispatcher = (topDialog as? ComponentDialog)?.onBackPressedDispatcher
            ?: (activity as? ComponentActivity)?.onBackPressedDispatcher
        backDispatcher?.let { dispatcher ->
            val backCb = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { detachAndDispatch(DismissReason.USER) }
            }
            dispatcher.addCallback(backCb)
            backPressedCallback = backCb
        }
    }

    /** In-window attach (the workspace modal layer): same scrim, no back
     *  dispatcher (the workspace owns back), detach-not-ours →
     *  LIFECYCLE_PAUSE — mirroring [attachToForeground]'s listener. */
    private fun attachToParent(parent: ViewGroup) {
        val scrimView = buildScrim()
        parent.addView(
            scrimView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        scrim = scrimView
        dismissAction = { try { parent.removeView(scrimView) } catch (_: Exception) {} }
        scrimView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                detachAndDispatch(DismissReason.LIFECYCLE_PAUSE)
            }
        })
    }

    private fun buildScrim(): FrameLayout {
        val dp = context.resources.displayMetrics.density

        // Full-screen scrim. NOT dismissable on tap — downloads must be
        // cancelled explicitly so a stray tap can't abort a 2 GB transfer.
        val scrimView = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            setOnClickListener { /* swallow */ }
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(context.themeColor(R.attr.ptSurface))
                setStroke((1 * dp).toInt(), context.themeColor(R.attr.ptDivider))
                cornerRadius = 16 * dp
            }
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt())
            gravity = Gravity.CENTER_HORIZONTAL
            // Prevent click-through to scrim (which would also be a no-op
            // here, but matches OverlayAlert's belt-and-suspenders pattern).
            setOnClickListener { }
        }

        if (title.isNotEmpty()) {
            card.addView(TextView(context).apply {
                text = title
                setTextColor(context.themeColor(R.attr.ptText))
                textSize = 17f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = (12 * dp).toInt()
                }
            })
        }

        statusView = TextView(context).apply {
            text = message
            setTextColor(context.themeColor(R.attr.ptTextMuted))
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = (16 * dp).toInt()
            }
        }
        card.addView(statusView)

        progressView = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = progressPercent
            isIndeterminate = indeterminate
            progressTintList = ColorStateList.valueOf(context.themeColor(R.attr.ptAccent))
            // Indeterminate mode draws through indeterminateDrawable, which
            // progressTintList does NOT touch — untinted it renders in the
            // track's own gray and the sliding animation is invisible.
            indeterminateTintList = ColorStateList.valueOf(context.themeColor(R.attr.ptAccent))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = (16 * dp).toInt()
            }
        }
        card.addView(progressView)

        // Cancel button — same styling as OverlayAlert.addCancelButton's default.
        val hPad = (20 * dp).toInt()
        val vPad = (10 * dp).toInt()
        cancelButton = Button(context).apply {
            text = cancelLabel
            if (cancelHidden) visibility = View.GONE
            setTextColor(context.themeColor(R.attr.ptText))
            textSize = 14f
            isAllCaps = false
            setPadding(hPad, vPad, hPad, vPad)
            background = GradientDrawable().apply {
                setColor(context.themeColor(R.attr.ptDivider))
                cornerRadius = 8 * dp
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener { detachAndDispatch(DismissReason.USER) }
        }
        card.addView(cancelButton)

        val maxW = (280 * dp).toInt()
        val cardLp = FrameLayout.LayoutParams(maxW, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
        scrimView.addView(card, cardLp)

        // Scale-and-fade in (matches OverlayAlert).
        card.alpha = 0f
        card.scaleX = 0.9f
        card.scaleY = 0.9f
        card.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(150)
            .setInterpolator(DecelerateInterpolator())
            .start()

        return scrimView
    }
}
