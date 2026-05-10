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
 */
class OverlayProgress private constructor(
    private val context: Context,
    private val title: String,
    private val initialMessage: String,
    private val initialProgress: Int,
    private val cancelLabel: String,
    private val onCancel: () -> Unit,
) {
    class Builder(private val context: Context) {
        private var title = ""
        private var initialMessage = ""
        private var initialProgress = 0
        private var cancelLabel = "Cancel"
        private var onCancel: () -> Unit = {}

        fun setTitle(title: String) = apply { this.title = title }
        fun setMessage(message: String) = apply { this.initialMessage = message }
        fun setProgress(percent: Int) = apply { this.initialProgress = percent }
        fun setCancelLabel(label: String) = apply { this.cancelLabel = label }
        fun setOnCancel(callback: () -> Unit) = apply { this.onCancel = callback }

        fun showInActivity(activity: Activity): OverlayProgress {
            val overlay = OverlayProgress(
                context, title, initialMessage, initialProgress, cancelLabel, onCancel,
            )
            overlay.showInActivity(activity)
            return overlay
        }
    }

    private var scrim: FrameLayout? = null
    private var dismissAction: (() -> Unit)? = null
    private var statusView: TextView? = null
    private var progressView: ProgressBar? = null
    private var cancelButton: Button? = null

    fun setMessage(message: String) {
        statusView?.text = message
    }

    fun setProgress(percent: Int) {
        progressView?.let {
            it.isIndeterminate = false
            it.progress = percent
        }
    }

    fun setIndeterminate(indeterminate: Boolean) {
        progressView?.isIndeterminate = indeterminate
    }

    /** Hide the Cancel button — used when the operation enters a phase
     *  that no longer supports cancellation (e.g. final on-device load
     *  after a successful download). */
    fun hideCancel() {
        cancelButton?.visibility = View.GONE
    }

    fun dismiss() {
        dismissAction?.invoke()
        dismissAction = null
        scrim = null
    }

    private fun showInActivity(activity: Activity) {
        val scrimView = buildScrim()
        val decor = activity.window.decorView as ViewGroup
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        decor.addView(scrimView, lp)
        scrim = scrimView
        dismissAction = { try { decor.removeView(scrimView) } catch (_: Exception) {} }
    }

    private fun buildScrim(): FrameLayout {
        val dp = context.resources.displayMetrics.density
        val oc = com.playtranslate.OverlayColors

        // Full-screen scrim. NOT dismissable on tap — downloads must be
        // cancelled explicitly so a stray tap can't abort a 2 GB transfer.
        val scrimView = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            setOnClickListener { /* swallow */ }
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(oc.surface(context))
                setStroke((1 * dp).toInt(), oc.divider(context))
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
                setTextColor(oc.text(context))
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
            text = initialMessage
            setTextColor(oc.textMuted(context))
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
            progress = initialProgress
            progressTintList = ColorStateList.valueOf(oc.accent(context))
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
            setTextColor(oc.text(context))
            textSize = 14f
            isAllCaps = false
            setPadding(hPad, vPad, hPad, vPad)
            background = GradientDrawable().apply {
                setColor(oc.divider(context))
                cornerRadius = 8 * dp
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener {
                dismiss()
                onCancel()
            }
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
