package com.playtranslate.ui

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import com.playtranslate.PlayTranslateApplication
import com.playtranslate.R
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlayThemedContext
import com.playtranslate.themeColor

/**
 * A reusable alert dialog that can be attached either to a capture-overlay
 * window (via the (context, overlayHost, wm, displayId) [Builder] constructor
 * + [Builder.showAsOverlay]) or to whichever PlayTranslate activity is
 * currently foregrounded (via [Builder.show]). Matches the visual style of
 * the floating icon hide confirmation dialog.
 *
 * The activity-attached path detaches itself when its host activity pauses
 * (sub-Activity nav, app backgrounded, etc.) so the alert dies cleanly
 * instead of becoming a hidden ghost behind the new top window. Back-press
 * dismisses the same way. The cancel handler ([Builder.addCancelButton]'s
 * onClick) fires on both user-initiated dismissal (button/scrim/back) AND
 * lifecycle pause — but receives a [DismissReason] so callers whose cancel
 * action runs control flow (advance a setup chain, etc.) can branch on USER
 * to avoid silently advancing past a decision the user never made.
 */
class OverlayAlert private constructor(
    rawContext: Context,
    private val title: String,
    private val message: String?,
    private val buttons: List<ButtonConfig>,
    private val showIcon: Boolean,
    private val onCancel: ((DismissReason) -> Unit)?,
) {
    private val context: Context = overlayThemedContext(rawContext)

    data class ButtonConfig(
        val label: String,
        val color: Int,
        val textColor: Int,
        val onClick: () -> Unit,
    )

    class Builder(rawContext: Context) {
        private val context: Context = overlayThemedContext(rawContext)
        private var title = ""
        private var message: String? = null
        private val buttons = mutableListOf<ButtonConfig>()
        private var showIcon = true
        /** Set by [addCancelButton]. Invoked on cancel-button tap, scrim
         *  tap, back-press, AND host-activity pause — all paths receive a
         *  [DismissReason] so callers with side effects can branch on USER. */
        private var onCancel: ((DismissReason) -> Unit)? = null

        /** Overlay-window path. [overlayHost] stamps the window with the
         *  active capture backend's type (TYPE_ACCESSIBILITY_OVERLAY or
         *  TYPE_APPLICATION_OVERLAY), so the alert displays on either
         *  backend — pass the host owned by the surface presenting the
         *  alert. Finish with [showAsOverlay]. The activity path uses the
         *  primary [Builder] constructor + [show] instead. */
        constructor(
            context: Context,
            overlayHost: OverlayHost,
            wm: WindowManager,
            displayId: Int,
        ) : this(context) {
            this.overlayHost = overlayHost
            this.wm = wm
            this.displayId = displayId
        }

        /** Set together by the overlay-window constructor — both null on the
         *  activity path. [showAsOverlay] requires them; [show] ignores them. */
        private var overlayHost: OverlayHost? = null
        private var wm: WindowManager? = null
        private var displayId: Int = android.view.Display.DEFAULT_DISPLAY

        fun setTitle(title: String) = apply { this.title = title }
        fun setMessage(message: String) = apply { this.message = message }

        /** Suppresses the circular app-icon header above the title. Use for
         *  utility popups where branding is noise (e.g. settings-scoped
         *  confirms). */
        fun hideIcon() = apply { this.showIcon = false }

        fun addButton(label: String, color: Int, textColor: Int = context.themeColor(R.attr.ptCard), onClick: () -> Unit) = apply {
            buttons.add(ButtonConfig(label, color, textColor, onClick))
        }

        /** Adds a styled cancel button (divider background, ptText label).
         *  [onClick] fires on cancel-button tap, scrim tap, back-press, AND
         *  host-activity pause; the [DismissReason] tells callers which.
         *  Use [DismissReason.USER] to gate side effects that should only
         *  run when the user explicitly dismissed (e.g. advancing a setup
         *  chain) — leaving [DismissReason.LIFECYCLE_PAUSE] to harmless
         *  cleanup like UI-state refresh.
         *  Action buttons (added via [addButton]) keep their own onClick
         *  and do NOT invoke [onClick] here. */
        fun addCancelButton(label: String = "Cancel", onClick: ((DismissReason) -> Unit)? = null) = apply {
            onCancel = onClick
            buttons.add(ButtonConfig(
                label,
                context.themeColor(R.attr.ptDivider),
                context.themeColor(R.attr.ptText),
                onClick = { onClick?.invoke(DismissReason.USER) },
            ))
        }

        /** Shows the alert as a capture-overlay window through the host
         *  supplied to the overlay-window constructor. */
        fun showAsOverlay(): OverlayAlert {
            val host = overlayHost ?: error(
                "OverlayAlert.showAsOverlay() requires the " +
                    "(context, overlayHost, wm, displayId) constructor"
            )
            val alert = OverlayAlert(context, title, message, buttons, showIcon, onCancel)
            // wm is non-null whenever overlayHost is — both are set together
            // by the overlay-window constructor.
            alert.showAsOverlay(host, wm!!, displayId)
            return alert
        }

        /** Shows attached to whichever PlayTranslate activity is currently
         *  foregrounded. If none is resumed yet (e.g. called from
         *  MainActivity.onCreate before its first onResume), the attach is
         *  deferred to the next [Activity.onResume] via
         *  [PlayTranslateApplication.runWithForegroundActivity].
         *
         *  Detaches and invokes the cancel handler if the host activity
         *  pauses — so navigating to a sub-Activity or backgrounding the
         *  app dismisses the alert instead of leaving it hidden behind the
         *  new top window. Back-press dismisses the same way. */
        fun show(): OverlayAlert {
            val alert = OverlayAlert(context, title, message, buttons, showIcon, onCancel)
            PlayTranslateApplication.runWithForegroundActivity { activity ->
                if (alert.dismissed || activity.isFinishing || activity.isDestroyed) return@runWithForegroundActivity
                alert.attachToActivity(activity)
            }
            return alert
        }

        /** Shows attached to the decorView of [dialog]'s own window. Use
         *  from a DialogFragment so the alert layers above the dialog;
         *  [show] would attach to the Activity window beneath
         *  the dialog, where the alert would stay hidden. */
        fun showInDialog(dialog: Dialog): OverlayAlert {
            val alert = OverlayAlert(context, title, message, buttons, showIcon, onCancel)
            alert.showInDialog(dialog)
            return alert
        }
    }

    private var scrim: FrameLayout? = null
    private var dismissAction: (() -> Unit)? = null
    private var attachedActivity: Activity? = null
    private var lifecycleCallback: Application.ActivityLifecycleCallbacks? = null
    private var backPressedCallback: OnBackPressedCallback? = null
    private var dismissed = false

    private fun buildScrim(): FrameLayout {
        val dp = context.resources.displayMetrics.density

        // Full-screen scrim. Tapping it acts as a shortcut for the cancel
        // button — invokes the same onCancel handler with USER reason.
        // Order: dismiss first, then handler — matches the button path so
        // any handler that chains another dialog sees the alert already
        // gone, no overlap/flicker.
        val scrimView = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            setOnClickListener { detachAndDispatch(DismissReason.USER) }
        }

        // Dialog card
        val dialog = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(context.themeColor(R.attr.ptSurface))
                setStroke((1 * dp).toInt(), context.themeColor(R.attr.ptDivider))
                cornerRadius = 16 * dp
            }
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt())
            gravity = Gravity.CENTER_HORIZONTAL
            // Prevent clicks from passing through to scrim
            setOnClickListener { }
        }

        // App icon — larger image centered in a clipped circle (matches FloatingIconMenu).
        // Suppressed when the caller opted out via Builder.hideIcon() — utility
        // popups don't need the brand mark.
        if (showIcon) {
            val circleSize = (56 * dp).toInt()
            val imgSize = (circleSize * 1.5f).toInt()
            val imgOffset = (circleSize - imgSize) / 2
            val iconFrame = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(circleSize, circleSize).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = (16 * dp).toInt()
                }
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
            }
            val icon = ImageView(context).apply {
                setImageResource(R.mipmap.ic_launcher_img)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = FrameLayout.LayoutParams(imgSize, imgSize).apply {
                    leftMargin = imgOffset
                    topMargin = imgOffset
                }
            }
            iconFrame.addView(icon)
            dialog.addView(iconFrame)
        }

        // Title
        dialog.addView(TextView(context).apply {
            text = title
            setTextColor(context.themeColor(R.attr.ptText))
            textSize = 17f
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (8 * dp).toInt()
            }
        })

        // Message
        if (message != null) {
            dialog.addView(TextView(context).apply {
                text = message
                setTextColor(context.themeColor(R.attr.ptTextMuted))
                textSize = 13f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = (20 * dp).toInt()
                }
            })
        }

        // Buttons
        val hPad = (20 * dp).toInt()
        val vPad = (10 * dp).toInt()
        val btnLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        for ((idx, cfg) in buttons.withIndex()) {
            val btn = Button(context).apply {
                text = cfg.label
                setTextColor(cfg.textColor)
                textSize = 14f
                isAllCaps = false
                setPadding(hPad, vPad, hPad, vPad)
                layoutParams = LinearLayout.LayoutParams(btnLp).apply {
                    if (idx < buttons.size - 1) bottomMargin = (8 * dp).toInt()
                }
                if (cfg.color != Color.TRANSPARENT) {
                    background = GradientDrawable().apply {
                        setColor(cfg.color)
                        cornerRadius = 8 * dp
                    }
                } else {
                    setBackgroundColor(Color.TRANSPARENT)
                }
                setOnClickListener {
                    dismiss()
                    cfg.onClick()
                }
            }
            dialog.addView(btn)
        }

        val maxW = (280 * dp).toInt()
        val dlp = FrameLayout.LayoutParams(maxW, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
        scrimView.addView(dialog, dlp)

        // Animate in
        dialog.alpha = 0f
        dialog.scaleX = 0.9f
        dialog.scaleY = 0.9f
        dialog.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(150)
            .setInterpolator(DecelerateInterpolator())
            .start()

        return scrimView
    }

    private fun showAsOverlay(overlayHost: OverlayHost, wm: WindowManager, displayId: Int) {
        val scrimView = buildScrim()
        // addOverlayWindow re-stamps params.type with the active backend's
        // window type, so the type given here is just a placeholder.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        if (!overlayHost.addOverlayWindow(scrimView, wm, params, displayId)) return
        scrim = scrimView
        dismissAction = { overlayHost.removeOverlayWindow(scrimView) }
    }

    private fun attachToActivity(activity: Activity) {
        attachToDecor(activity.window.decorView as ViewGroup)
        attachedActivity = activity

        // Detach + cancel if the host activity leaves the foreground —
        // sub-Activity navigation, app backgrounding, anything that fires
        // onPause. Without this the alert sits as an invisible child of a
        // covered window. See OverlayAlert class doc.
        val app = activity.application
        val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(a: Activity) {
                if (a === attachedActivity) detachAndDispatch(DismissReason.LIFECYCLE_PAUSE)
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        app.registerActivityLifecycleCallbacks(lifecycle)
        lifecycleCallback = lifecycle

        if (activity is ComponentActivity) {
            val backCb = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { detachAndDispatch(DismissReason.USER) }
            }
            activity.onBackPressedDispatcher.addCallback(backCb)
            backPressedCallback = backCb
        }
    }

    /** Attaches to the decorView of [dialog]'s window; a no-op if the
     *  dialog has already lost its window. */
    private fun showInDialog(dialog: Dialog) {
        val decor = dialog.window?.decorView as? ViewGroup ?: return
        attachToDecor(decor)
    }

    private fun attachToDecor(decor: ViewGroup) {
        val scrimView = buildScrim()
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        decor.addView(scrimView, lp)
        scrim = scrimView
        dismissAction = { try { decor.removeView(scrimView) } catch (_: Exception) {} }
    }

    private fun detachAndDispatch(reason: DismissReason) {
        dismiss()
        onCancel?.invoke(reason)
    }

    fun dismiss() {
        dismissed = true
        dismissAction?.invoke()
        dismissAction = null
        scrim = null
        lifecycleCallback?.let {
            attachedActivity?.application?.unregisterActivityLifecycleCallbacks(it)
        }
        backPressedCallback?.remove()
        lifecycleCallback = null
        backPressedCallback = null
        attachedActivity = null
    }
}
