package com.playtranslate.camera

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.withSave
import com.playtranslate.R
import com.playtranslate.themeColor
import com.playtranslate.ui.REGION_DASH_DP
import com.playtranslate.ui.REGION_GAP_DP
import com.playtranslate.ui.RegionDragView
import com.playtranslate.ui.drawScreenSpaceDashes

/**
 * Camera snapshot region UI: the in-activity crop editor (the floating-icon
 * region editor's drag box + cancel/clear/confirm bar, rebuilt as activity
 * children instead of overlay windows — styling mirrors
 * [com.playtranslate.RegionOverlayController.showRegionEditor]) and the
 * persistent active-region indicator (the region picker preview's dim + glow,
 * with the solid border swapped for the editor's DASHED accent and the name
 * pill replaced by a tappable "Remove" — the two deliberate differences).
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

    private var dragView: RegionDragView? = null
    private var editorBar: View? = null
    private var editorLabel: View? = null

    val isEditorShowing: Boolean get() = dragView != null

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
    ) {
        hideEditor()
        val start = init ?: RectF(0.25f, 0.25f, 0.75f, 0.75f)
        val drag = RegionDragView(activity).apply {
            setRegion(
                top = start.top.coerceIn(0f, 1f),
                bottom = start.bottom.coerceIn(0f, 1f),
                left = start.left.coerceIn(0f, 1f),
                right = start.right.coerceIn(0f, 1f),
            )
            onDragStart = {
                editorBar?.visibility = View.INVISIBLE
                editorLabel?.visibility = View.INVISIBLE
            }
            onDragEnd = {
                editorBar?.visibility = View.VISIBLE
                editorLabel?.visibility = View.VISIBLE
            }
        }
        fullBleedHost.addView(
            drag,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        dragView = drag

        val btnSize = (48 * dp).toInt()
        val barPad = (12 * dp).toInt()
        val gap = (16 * dp).toInt()
        val surfaceColor = activity.themeColor(R.attr.ptSurface)
        val cardColor = activity.themeColor(R.attr.ptCard)
        val dividerColor = activity.themeColor(R.attr.ptDivider)
        val accentColor = activity.themeColor(R.attr.ptAccent)
        val accentOnColor = activity.themeColor(R.attr.ptAccentOn)
        val textColor = activity.themeColor(R.attr.ptText)
        val surfaceAlpha = Color.argb(
            230, Color.red(surfaceColor), Color.green(surfaceColor), Color.blue(surfaceColor),
        )
        val btnRadius = 16 * dp
        fun buttonBg(color: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = btnRadius
        }

        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(barPad * 2 - (9 * dp).toInt(), barPad, barPad * 2 - (9 * dp).toInt(), barPad)
            background = GradientDrawable().apply {
                setColor(surfaceAlpha)
                setStroke((1 * dp).toInt(), dividerColor)
                cornerRadius = 22 * dp
            }
        }
        bar.addView(
            TextView(activity).apply {
                text = "✕"
                setTextColor(textColor)
                textSize = 22f
                gravity = Gravity.CENTER
                background = buttonBg(cardColor)
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply { marginEnd = gap }
                setOnClickListener { onCancel() }
            }
        )
        bar.addView(
            ImageView(activity).apply {
                setImageDrawable(activity.getDrawable(R.drawable.ic_delete))
                imageTintList = ColorStateList.valueOf(textColor)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val iconPad = (12 * dp).toInt()
                setPadding(iconPad, iconPad, iconPad, iconPad)
                contentDescription = activity.getString(R.string.btn_clear)
                background = buttonBg(cardColor)
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply { marginEnd = gap }
                setOnClickListener { onClear() }
            }
        )
        bar.addView(
            TextView(activity).apply {
                text = "✓"
                setTextColor(accentOnColor)
                textSize = 22f
                gravity = Gravity.CENTER
                background = buttonBg(accentColor)
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
                setOnClickListener {
                    val dv = dragView ?: return@setOnClickListener
                    onConfirm(RectF(dv.leftFraction, dv.topFraction, dv.rightFraction, dv.bottomFraction))
                }
            }
        )
        controlsHost.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (32 * dp).toInt()
            },
        )
        editorBar = bar

        val label = TextView(activity).apply {
            setText(R.string.region_overlay_drag_instruction)
            setTextColor(textColor)
            textSize = 14f
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(surfaceAlpha)
                setStroke((1 * dp).toInt(), dividerColor)
                cornerRadius = 100 * dp
            }
        }
        controlsHost.addView(
            label,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = (16 * dp).toInt()
                marginStart = (16 * dp).toInt()
                marginEnd = (16 * dp).toInt()
            },
        )
        editorLabel = label
    }

    fun hideEditor() {
        dragView?.let { fullBleedHost.removeView(it) }
        dragView = null
        editorBar?.let { controlsHost.removeView(it) }
        editorBar = null
        editorLabel?.let { controlsHost.removeView(it) }
        editorLabel = null
    }

    // ── Active-region indicator ─────────────────────────────────────────

    private var indicatorView: View? = null
    private var removePill: TextView? = null

    /** Show the persistent indicator for [viewRect] (view px of the
     *  full-bleed host). The Remove pill sits centered above the region, or
     *  below it when the top inset leaves no room — the region picker
     *  preview's own placement rule for its name pill. */
    fun showIndicator(viewRect: Rect, onRemove: () -> Unit) {
        hideIndicator()
        val v = RegionIndicatorView(Rect(viewRect))
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
        controlsHost.addView(
            pill,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = (screenX - padL).coerceAtLeast(0)
                topMargin = (screenY - padT).coerceAtLeast(0)
            },
        )
        removePill = pill
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

    /** The indicator surface: the region picker preview's outside dim +
     *  drop shadow + accent glow, bordered with the editor's screen-space
     *  DASHES instead of its solid line. Draw-only — not touchable; the
     *  Remove pill lives on the controls layer above the sheet.
     *  BlurMaskFilter renders on the hardware canvas (minSdk 30); this view
     *  must not be software-layered (full-screen layer cap — see
     *  RegionOverlayController's label shadow note). */
    private inner class RegionIndicatorView(private val region: Rect) : View(activity) {
        private val accent = activity.themeColor(R.attr.ptAccent)
        private val dimPaint = Paint().apply {
            color = Color.argb(200, 0, 0, 0)
            style = Paint.Style.FILL
        }
        private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            style = Paint.Style.STROKE
            strokeWidth = 2 * dp
            strokeCap = Paint.Cap.ROUND
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(26, Color.red(accent), Color.green(accent), Color.blue(accent))
            style = Paint.Style.STROKE
            strokeWidth = 12 * dp
            maskFilter = BlurMaskFilter(14 * dp, BlurMaskFilter.Blur.NORMAL)
        }
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(110, 0, 0, 0)
            style = Paint.Style.STROKE
            strokeWidth = 13 * dp
            maskFilter = BlurMaskFilter(13 * dp, BlurMaskFilter.Blur.NORMAL)
        }

        override fun onDraw(canvas: Canvas) {
            val l = region.left.toFloat()
            val t = region.top.toFloat()
            val r = region.right.toFloat()
            val b = region.bottom.toFloat()
            canvas.withSave {
                clipOutRect(l, t, r, b)
                drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
                val shadowOffset = shadowPaint.strokeWidth / 2f
                drawRect(l - shadowOffset, t - shadowOffset, r + shadowOffset, b + shadowOffset, shadowPaint)
                val glowOffset = glowPaint.strokeWidth / 2f
                drawRect(l - glowOffset, t - glowOffset, r + glowOffset, b + glowOffset, glowPaint)
            }
            val dashPx = REGION_DASH_DP * dp
            val gapPx = REGION_GAP_DP * dp
            drawScreenSpaceDashes(canvas, dashPaint, l, t, r, t, dashPx, gapPx)
            drawScreenSpaceDashes(canvas, dashPaint, r, t, r, b, dashPx, gapPx)
            drawScreenSpaceDashes(canvas, dashPaint, l, b, r, b, dashPx, gapPx)
            drawScreenSpaceDashes(canvas, dashPaint, l, t, l, b, dashPx, gapPx)
        }
    }
}
