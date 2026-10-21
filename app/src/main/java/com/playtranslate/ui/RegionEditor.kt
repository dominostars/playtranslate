package com.playtranslate.ui

import android.content.Context
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.StringRes

/**
 * A region editor inside a view tree: the floating-icon editor's drag box
 * and ✕/🗑/✓ bar, rebuilt as children of a host instead of overlay windows
 * (styling and chrome shared through [RegionEditorControls]; placement
 * through [RegionEditorChrome]). The camera / Import crop editor
 * ([com.playtranslate.camera.CameraRegionUi]) and the Anki screenshot crop
 * editor host it.
 *
 * Two hosts because of the camera's result sheet: [fullBleedHost] sits
 * UNDER the sheet (the drag box draws there, so the sheet keeps its frost
 * over it) while [controlsHost] (an inset-padded floating-controls layer)
 * sits ABOVE it, which keeps the bar tappable (the sheet's root consumes
 * every outside tap). A host with no sheet passes one view for both.
 *
 * Main thread only.
 */
class RegionEditor(
    private val ctx: Context,
    private val fullBleedHost: ViewGroup,
    private val controlsHost: ViewGroup = fullBleedHost,
) {
    private val dp = ctx.resources.displayMetrics.density

    private var dragView: RegionDragView? = null
    private var bar: View? = null
    private var label: View? = null

    val isShowing: Boolean get() = dragView != null

    /**
     * Show the editor. [init] is the box as FRACTIONS of the full-bleed host
     * (left/top/right/bottom), null for the default centred box; [onConfirm]
     * receives the dragged fractions the same way. [instruction] is the
     * pill's text; a null [onClear] shows no 🗑.
     */
    fun show(
        init: RectF?,
        @StringRes instruction: Int,
        onCancel: () -> Unit,
        onClear: (() -> Unit)?,
        onConfirm: (fractions: RectF) -> Unit,
    ) {
        hide()
        val start = init ?: RectF(0.25f, 0.25f, 0.75f, 0.75f)
        val drag = RegionDragView(ctx).apply {
            setRegion(top = start.top, bottom = start.bottom, left = start.left, right = start.right)
            onDragStart = {
                bar?.visibility = View.INVISIBLE
                label?.visibility = View.INVISIBLE
            }
        }
        fullBleedHost.addView(
            drag,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        dragView = drag

        val bar = RegionEditorControls.bar(
            ctx, ctx,
            onCancel = onCancel,
            onClear = onClear,
            onConfirm = {
                val dv = dragView ?: return@bar
                onConfirm(RectF(dv.leftFraction, dv.topFraction, dv.rightFraction, dv.bottomFraction))
            },
        )
        val label = RegionEditorControls.pill(ctx, ctx, instruction)

        // The drag box's fractions are of the full-bleed host; the chrome sits
        // in the controls host, inside its inset padding.
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        label.measure(unspecified, unspecified)
        bar.measure(unspecified, unspecified)
        val stackDepth = RegionEditorChrome.stackDepthPx(label.measuredHeight, bar.measuredHeight, dp)
        val barOffset = RegionEditorChrome.barOffsetPx(label.measuredHeight, dp)
        val pillOffset = RegionEditorChrome.pillOffsetPx(dp)
        val barParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        val labelParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginStart = (16 * dp).toInt()
            marginEnd = (16 * dp).toInt()
        }
        fun placeChrome(region: ClosedFloatingPointRange<Float>?) {
            val hostH = fullBleedHost.height.toFloat()
            val edge = RegionEditorChrome.edgeFor(
                region,
                topChromeEnd = (controlsHost.paddingTop + stackDepth) / hostH,
                bottomChromeStart = 1f - (controlsHost.paddingBottom + stackDepth) / hostH,
            )
            val onTop = edge == RegionEditorChrome.Edge.TOP
            for ((params, offset) in listOf(barParams to barOffset, labelParams to pillOffset)) {
                params.gravity = edge.gravity or Gravity.CENTER_HORIZONTAL
                params.topMargin = if (onTop) offset else 0
                params.bottomMargin = if (onTop) 0 else offset
            }
        }

        placeChrome(init?.let { it.top..it.bottom })
        controlsHost.addView(bar, barParams)
        this.bar = bar
        controlsHost.addView(label, labelParams)
        this.label = label

        // The chrome comes back at each drag's end on the edge the box, as it
        // now stands, calls for.
        drag.onDragEnd = {
            placeChrome(drag.topFraction..drag.bottomFraction)
            bar.layoutParams = barParams
            label.layoutParams = labelParams
            bar.visibility = View.VISIBLE
            label.visibility = View.VISIBLE
        }
    }

    fun hide() {
        dragView?.let { fullBleedHost.removeView(it) }
        dragView = null
        bar?.let { controlsHost.removeView(it) }
        bar = null
        label?.let { controlsHost.removeView(it) }
        label = null
    }
}
