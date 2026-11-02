package com.playtranslate.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.playtranslate.OverlayMode
import com.playtranslate.R
import com.playtranslate.themeColor

/**
 * The capture's overlay-mode control, floating over the game display while
 * a capture holds it: expanded, the segments of every mode the capture
 * offers ([buildPillToggle]) with an X at the end; collapsed by that X, a
 * disc with the selected mode's glyph that a tap expands again. It is panel
 * chrome that floats because the over-game panel parks itself out of reach,
 * so it lives in the host's root above the panel; the host routes touches
 * inside its bounds to it and places it under the status bar.
 *
 * Idempotent: [render] rebuilds only when the options, the selection or the
 * collapsed state changed, so a host may call it from every refresh without
 * cutting a segment's slide short.
 */
internal class OverlayModePill(context: Context) : FrameLayout(context) {

    /** A segment was picked. The pill shows it selected at once. */
    var onSelect: ((OverlayMode) -> Unit)? = null

    /** The user collapsed the control with its X, or expanded it from the disc. */
    var onCollapsedChanged: ((collapsed: Boolean) -> Unit)? = null

    private var options: List<Pair<String, OverlayMode>> = emptyList()
    private var selected: OverlayMode? = null

    /** Collapsed to the disc. */
    var collapsed: Boolean = false
        private set

    private val dp = resources.displayMetrics.density

    /** Show [options] with [selected] marked, [collapsed] or expanded; an
     *  empty [options] hides the control. */
    fun render(options: List<Pair<String, OverlayMode>>, selected: OverlayMode, collapsed: Boolean) {
        val unchanged = options == this.options && selected == this.selected &&
            collapsed == this.collapsed && (childCount > 0 || options.isEmpty())
        if (unchanged) return
        this.options = options
        this.selected = selected
        this.collapsed = collapsed
        removeAllViews()
        if (options.isEmpty()) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
        addView(if (collapsed) buildDisc(selected) else buildExpanded(options, selected))
    }

    private fun capsule(radiusPx: Float) = GradientDrawable().apply {
        setColor(context.themeColor(R.attr.ptElevated))
        setStroke((1 * dp).toInt(), context.themeColor(R.attr.ptDivider))
        cornerRadius = radiusPx
    }

    private fun buildDisc(mode: OverlayMode): View {
        val size = (DISC_DP * dp).toInt()
        val pad = (8 * dp).toInt()
        return ImageView(context).apply {
            layoutParams = LayoutParams(size, size)
            background = capsule(size / 2f)
            elevation = 4 * dp
            setImageResource(glyphOf(mode))
            imageTintList = android.content.res.ColorStateList.valueOf(context.themeColor(R.attr.ptText))
            setPadding(pad, pad, pad, pad)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = context.getString(
                R.string.capture_overlay_pill_collapsed_cd,
                options.firstOrNull { it.second == mode }?.first ?: "",
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                collapsed = false
                onCollapsedChanged?.invoke(false)
                val keep = options
                options = emptyList()   // force the rebuild
                render(keep, selected ?: mode, collapsed = false)
            }
        }
    }

    private fun buildExpanded(options: List<Pair<String, OverlayMode>>, selected: OverlayMode): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = capsule(ROW_RADIUS_DP * dp)
            elevation = 4 * dp
            val padH = (6 * dp).toInt()
            val padV = (4 * dp).toInt()
            setPadding(padH, padV, padH, padV)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
        val toggleHost = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                (SEGMENT_DP * dp).toInt() * options.size, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        buildPillToggle(toggleHost, options, selected) { mode ->
            this.selected = mode
            onSelect?.invoke(mode)
        }
        row.addView(toggleHost)
        val closeSize = (32 * dp).toInt()
        val closePad = (6 * dp).toInt()
        row.addView(ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(closeSize, closeSize).apply {
                marginStart = (4 * dp).toInt()
            }
            setImageResource(R.drawable.ic_close)
            imageTintList = android.content.res.ColorStateList.valueOf(context.themeColor(R.attr.ptTextMuted))
            setPadding(closePad, closePad, closePad, closePad)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = context.getString(R.string.capture_overlay_pill_collapse_cd)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                collapsed = true
                onCollapsedChanged?.invoke(true)
                val keep = this@OverlayModePill.options
                this@OverlayModePill.options = emptyList()   // force the rebuild
                render(keep, this@OverlayModePill.selected ?: selected, collapsed = true)
            }
        })
        return row
    }

    companion object {
        const val DISC_DP = 36
        const val ROW_RADIUS_DP = 20f
        /** Each segment's width in the expanded control. */
        const val SEGMENT_DP = 78

        fun glyphOf(mode: OverlayMode): Int = when (mode) {
            OverlayMode.TRANSLATION -> R.drawable.ic_subtitles
            OverlayMode.FURIGANA -> R.drawable.ic_furigana
            OverlayMode.DEFINITIONS -> R.drawable.ic_dictionary
            OverlayMode.NONE -> R.drawable.ic_visibility_off
        }
    }
}
