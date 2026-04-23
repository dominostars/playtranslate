package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
import androidx.core.content.ContextCompat
import com.playtranslate.R

/**
 * A reusable alert dialog that can be attached either to a WindowManager
 * (as a TYPE_ACCESSIBILITY_OVERLAY from an accessibility service) or to an
 * Activity's decorView. Matches the visual style of the floating icon hide
 * confirmation dialog.
 */
class OverlayAlert private constructor(
    private val context: Context,
    private val title: String,
    private val message: String?,
    private val buttons: List<ButtonConfig>,
    private val showIcon: Boolean,
) {

    data class ButtonConfig(
        val label: String,
        val color: Int,
        val textColor: Int,
        val onClick: () -> Unit
    )

    class Builder(private val context: Context) {
        private var title = ""
        private var message: String? = null
        private val buttons = mutableListOf<ButtonConfig>()
        private var showIcon = true

        constructor(context: Context, wm: WindowManager) : this(context) {
            this.wm = wm
        }

        private var wm: WindowManager? = null

        fun setTitle(title: String) = apply { this.title = title }
        fun setMessage(message: String) = apply { this.message = message }

        /** Suppresses the circular app-icon header above the title. Use for
         *  utility popups where branding is noise (e.g. settings-scoped
         *  confirms). */
        fun hideIcon() = apply { this.showIcon = false }

        fun addButton(label: String, color: Int, textColor: Int = com.playtranslate.OverlayColors.card(context), onClick: () -> Unit) = apply {
            buttons.add(ButtonConfig(label, color, textColor, onClick))
        }

        fun addCancelButton(onClick: (() -> Unit)? = null) = apply {
            buttons.add(ButtonConfig("Cancel",
                com.playtranslate.OverlayColors.divider(context),
                com.playtranslate.OverlayColors.text(context)
            ) {
                onClick?.invoke()
            })
        }

        /** Shows via WindowManager as an accessibility overlay. */
        fun show(): OverlayAlert {
            val alert = OverlayAlert(context, title, message, buttons, showIcon)
            alert.showAsAccessibilityOverlay(
                wm ?: error("OverlayAlert.Builder.show() requires a WindowManager")
            )
            return alert
        }

        /** Shows attached to the given Activity's decorView. */
        fun showInActivity(activity: Activity): OverlayAlert {
            val alert = OverlayAlert(activity, title, message, buttons, showIcon)
            alert.showInActivity(activity)
            return alert
        }
    }

    private var scrim: FrameLayout? = null
    private var dismissAction: (() -> Unit)? = null

    private fun buildScrim(): FrameLayout {
        val dp = context.resources.displayMetrics.density

        // Full-screen scrim
        val scrimView = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            setOnClickListener { dismiss() }
        }

        // Dialog card
        val oc = com.playtranslate.OverlayColors
        val dialog = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(oc.surface(context))
                setStroke((1 * dp).toInt(), oc.divider(context))
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
            setTextColor(oc.text(context))
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
                setTextColor(oc.textMuted(context))
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

    private fun showAsAccessibilityOverlay(wm: WindowManager) {
        val scrimView = buildScrim()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(scrimView, params)
        scrim = scrimView
        dismissAction = { try { wm.removeView(scrimView) } catch (_: Exception) {} }
    }

    private fun showInActivity(activity: Activity) {
        val scrimView = buildScrim()
        val decor = activity.window.decorView as ViewGroup
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        decor.addView(scrimView, lp)
        scrim = scrimView
        dismissAction = { try { decor.removeView(scrimView) } catch (_: Exception) {} }
    }

    fun dismiss() {
        dismissAction?.invoke()
        dismissAction = null
        scrim = null
    }
}
