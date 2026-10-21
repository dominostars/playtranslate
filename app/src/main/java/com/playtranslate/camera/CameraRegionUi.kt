package com.playtranslate.camera

import android.app.Activity
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.playtranslate.R
import com.playtranslate.themeColor
import com.playtranslate.ui.RegionEditor
import com.playtranslate.ui.RegionIndicatorView

/**
 * Camera snapshot region UI: the in-activity crop editor (the shared
 * [RegionEditor]: the floating-icon region editor's drag box + cancel/clear/
 * confirm bar as activity children) and the persistent active-region
 * indicator (the shared [RegionIndicatorView] with a tappable "Remove" pill
 * in place of the region picker preview's name pill).
 *
 * Two hosts because of the result sheet: [fullBleedHost] (the camera overlay
 * layer) sits UNDER the sheet — the drag box and the indicator's dim/dashes
 * draw there, so the sheet keeps its frost over them — while [controlsHost]
 * (the inset-padded floating-controls layer) sits ABOVE the sheet, which is
 * what keeps the editor bar and the Remove pill tappable: the sheet's root
 * consumes every outside tap (X-only dismissal).
 *
 * Main thread only.
 */
class CameraRegionUi(
    private val activity: Activity,
    private val fullBleedHost: ViewGroup,
    private val controlsHost: ViewGroup,
) {
    private val dp = activity.resources.displayMetrics.density

    // ── Crop editor ─────────────────────────────────────────────────────

    private val editor = RegionEditor(activity, fullBleedHost, controlsHost)

    val isEditorShowing: Boolean get() = editor.isShowing

    /**
     * Show the drag editor. [init] is the region as SCREEN FRACTIONS
     * (left/top/right/bottom of the full-bleed host), null for the default
     * centered box. [onConfirm] receives the dragged fractions the same way.
     */
    fun showEditor(
        init: RectF?,
        onCancel: () -> Unit,
        onClear: () -> Unit,
        onConfirm: (fractions: RectF) -> Unit,
    ) = editor.show(
        init,
        instruction = R.string.region_overlay_drag_instruction,
        onCancel = onCancel,
        onClear = onClear,
        onConfirm = onConfirm,
    )

    fun hideEditor() = editor.hide()

    // ── Active-region indicator ─────────────────────────────────────────

    private var indicatorView: RegionIndicatorView? = null
    private var removePill: TextView? = null

    /** Show the persistent indicator for [viewRect] (view px of the
     *  full-bleed host). The Remove pill sits centered above the region, or
     *  below it when the top inset leaves no room — the region picker
     *  preview's own placement rule for its name pill. */
    fun showIndicator(viewRect: Rect, onRemove: () -> Unit) {
        hideIndicator()
        val v = RegionIndicatorView(activity, viewRect)
        // Index 0: the indicator sits UNDER everything else in the overlay
        // layer — the frozen overlays (translation boxes, furigana ruby that
        // pokes past the region edge) must render over the dim and dashes,
        // never be shaded by them.
        fullBleedHost.addView(
            v, 0,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        indicatorView = v

        // Neutral but BRIGHT: inverted colors (text token as fill, bg token
        // as text), not the accent name-pill this replaces and not a
        // background-family token — ptCard/ptSurface blend straight into
        // the indicator's dark dim in dark mode. Inverting stays readable
        // in both themes: near-white pill on the dim in dark mode, dark
        // pill over a light frame in light mode.
        val pill = TextView(activity).apply {
            setText(R.string.camera_region_remove)
            setTextColor(activity.themeColor(R.attr.ptBg))
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(activity.themeColor(R.attr.ptText))
                cornerRadius = 6 * dp
            }
            elevation = 3 * dp
            setOnClickListener { onRemove() }
        }
        pill.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val (leftMargin, topMargin) = pillMarginsFor(pill, viewRect)
        controlsHost.addView(
            pill,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                this.leftMargin = leftMargin
                this.topMargin = topMargin
            },
        )
        removePill = pill
    }

    /** The pill placement rule (centered above the region, below it when
     *  the top inset leaves no room), as controls-layer margins. */
    private fun pillMarginsFor(pill: TextView, viewRect: Rect): Pair<Int, Int> {
        val margin = (8 * dp).toInt()
        val padL = controlsHost.paddingLeft
        val padT = controlsHost.paddingTop
        val hostW = controlsHost.width.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.widthPixels
        val aboveY = viewRect.top - margin - pill.measuredHeight
        val screenY = if (aboveY >= padT) aboveY else viewRect.bottom + margin
        val maxX = (hostW - controlsHost.paddingRight - pill.measuredWidth - margin)
            .coerceAtLeast(padL + margin)
        val screenX = (viewRect.centerX() - pill.measuredWidth / 2).coerceIn(padL + margin, maxX)
        return (screenX - padL).coerceAtLeast(0) to (screenY - padT).coerceAtLeast(0)
    }

    /** Move a visible indicator (and its Remove pill) to a new view rect IN
     *  PLACE — the review zoom re-projects the region on every transform
     *  change, and an addView/removeView per gesture frame would churn the
     *  hierarchy. No-op while no indicator is up. */
    fun updateIndicatorRect(viewRect: Rect) {
        val v = indicatorView ?: return
        v.moveTo(viewRect)
        val pill = removePill ?: return
        val (leftMargin, topMargin) = pillMarginsFor(pill, viewRect)
        (pill.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            if (lp.leftMargin != leftMargin || lp.topMargin != topMargin) {
                lp.leftMargin = leftMargin
                lp.topMargin = topMargin
                pill.layoutParams = lp
            }
        }
    }

    fun hideIndicator() {
        indicatorView?.let { fullBleedHost.removeView(it) }
        indicatorView = null
        removePill?.let { controlsHost.removeView(it) }
        removePill = null
    }

    fun destroy() {
        hideEditor()
        hideIndicator()
    }
}
