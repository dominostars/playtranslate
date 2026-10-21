package com.playtranslate.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import com.playtranslate.R
import com.playtranslate.themeColor

/**
 * The region editors' chrome, built once: the instruction pill and the
 * ✕/🗑/✓ bar. Three editors show them: the floating icon's
 * ([com.playtranslate.RegionOverlayController.showRegionEditor], where each
 * is its own overlay window) and the view-tree editors [RegionEditor] hosts
 * (the camera / Import crop and the Anki screenshot crop). Where they go is
 * the host's business ([RegionEditorChrome]).
 *
 * [viewCtx] constructs the views: the floating-icon editor passes its raw
 * display context, a view-tree host its own. [themed] resolves the pt*
 * tokens and inflates the vector: a raw display context binds no theme
 * attrs (see [RegionDragView]), so the overlay passes
 * [com.playtranslate.overlayThemedContext] of its display context; a
 * themed host passes itself twice.
 */
internal object RegionEditorControls {

    private const val BUTTON_DP = 48
    private const val BAR_PAD_DP = 12
    private const val GAP_DP = 16
    private const val BUTTON_RADIUS_DP = 16
    private const val BAR_RADIUS_DP = 22
    private const val SURFACE_ALPHA = 230

    private fun surfaceAlpha(themed: Context): Int {
        val surface = themed.themeColor(R.attr.ptSurface)
        return Color.argb(
            SURFACE_ALPHA, Color.red(surface), Color.green(surface), Color.blue(surface),
        )
    }

    /** The instruction pill: [text] on the translucent surface, one line. */
    fun pill(viewCtx: Context, themed: Context, @StringRes text: Int): TextView {
        val dp = viewCtx.resources.displayMetrics.density
        return TextView(viewCtx).apply {
            setText(text)
            setTextColor(themed.themeColor(R.attr.ptText))
            textSize = 14f
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(surfaceAlpha(themed))
                setStroke((1 * dp).toInt(), themed.themeColor(R.attr.ptDivider))
                cornerRadius = 100 * dp
            }
        }
    }

    /**
     * The action bar: ✕ ([onCancel]), 🗑 ([onClear]; absent when null, for an
     * editor with nothing to clear) and the accent ✓ ([onConfirm]). The
     * buttons take focus so a d-pad reaches them in a dialog window.
     */
    fun bar(
        viewCtx: Context,
        themed: Context,
        onCancel: () -> Unit,
        onClear: (() -> Unit)?,
        onConfirm: () -> Unit,
    ): LinearLayout {
        val dp = viewCtx.resources.displayMetrics.density
        val btnSize = (BUTTON_DP * dp).toInt()
        val barPad = (BAR_PAD_DP * dp).toInt()
        val gap = (GAP_DP * dp).toInt()
        val cardColor = themed.themeColor(R.attr.ptCard)
        val textColor = themed.themeColor(R.attr.ptText)
        val btnRadius = BUTTON_RADIUS_DP * dp
        fun buttonBg(color: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = btnRadius
        }

        val bar = LinearLayout(viewCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(barPad * 2 - (9 * dp).toInt(), barPad, barPad * 2 - (9 * dp).toInt(), barPad)
            background = GradientDrawable().apply {
                setColor(surfaceAlpha(themed))
                setStroke((1 * dp).toInt(), themed.themeColor(R.attr.ptDivider))
                cornerRadius = BAR_RADIUS_DP * dp
            }
        }
        bar.addView(
            TextView(viewCtx).apply {
                text = "✕"
                setTextColor(textColor)
                textSize = 22f
                gravity = Gravity.CENTER
                background = buttonBg(cardColor)
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply { marginEnd = gap }
                setOnClickListener { onCancel() }
            },
        )
        if (onClear != null) {
            bar.addView(
                ImageView(viewCtx).apply {
                    setImageDrawable(themed.getDrawable(R.drawable.ic_delete))
                    imageTintList = ColorStateList.valueOf(textColor)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    val iconPad = (12 * dp).toInt()
                    setPadding(iconPad, iconPad, iconPad, iconPad)
                    contentDescription = viewCtx.getString(R.string.btn_clear)
                    background = buttonBg(cardColor)
                    isFocusable = true
                    layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply { marginEnd = gap }
                    setOnClickListener { onClear() }
                },
            )
        }
        bar.addView(
            TextView(viewCtx).apply {
                text = "✓"
                setTextColor(themed.themeColor(R.attr.ptAccentOn))
                textSize = 22f
                gravity = Gravity.CENTER
                background = buttonBg(themed.themeColor(R.attr.ptAccent))
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
                setOnClickListener { onConfirm() }
            },
        )
        return bar
    }
}
