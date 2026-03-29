package com.playtranslate.ui

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
import com.playtranslate.R

/**
 * A reusable overlay alert dialog that shows as a TYPE_ACCESSIBILITY_OVERLAY.
 * Matches the visual style of the floating icon hide confirmation dialog.
 */
class OverlayAlert private constructor(
    private val context: Context,
    private val wm: WindowManager,
    private val title: String,
    private val message: String?,
    private val buttons: List<ButtonConfig>
) {

    data class ButtonConfig(
        val label: String,
        val color: Int,
        val textColor: Int = Color.WHITE,
        val onClick: () -> Unit
    )

    class Builder(private val context: Context, private val wm: WindowManager) {
        private var title = ""
        private var message: String? = null
        private val buttons = mutableListOf<ButtonConfig>()

        fun setTitle(title: String) = apply { this.title = title }
        fun setMessage(message: String) = apply { this.message = message }

        fun addButton(label: String, color: Int, textColor: Int = Color.WHITE, onClick: () -> Unit) = apply {
            buttons.add(ButtonConfig(label, color, textColor, onClick))
        }

        fun addCancelButton(onClick: (() -> Unit)? = null) = apply {
            buttons.add(ButtonConfig("Cancel", Color.TRANSPARENT, Color.parseColor("#AAAAAA")) {
                onClick?.invoke()
            })
        }

        fun show(): OverlayAlert {
            val alert = OverlayAlert(context, wm, title, message, buttons)
            alert.show()
            return alert
        }
    }

    private var scrim: FrameLayout? = null

    private fun show() {
        val dp = context.resources.displayMetrics.density

        // Full-screen scrim
        val scrimView = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            setOnClickListener { dismiss() }
        }

        // Dialog card
        val dialog = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F0222222"))
                cornerRadius = 16 * dp
            }
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt())
            gravity = Gravity.CENTER_HORIZONTAL
            // Prevent clicks from passing through to scrim
            setOnClickListener { }
        }

        // App icon — larger image centered in a clipped circle (matches FloatingIconMenu)
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

        // Title
        dialog.addView(TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
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
                setTextColor(Color.parseColor("#AAAAAA"))
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

        // Add to window manager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(scrimView, params)
        scrim = scrimView

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
    }

    fun dismiss() {
        try { scrim?.let { wm.removeView(it) } } catch (_: Exception) {}
        scrim = null
    }
}
