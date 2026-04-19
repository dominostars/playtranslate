package com.playtranslate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.playtranslate.R
import com.playtranslate.RegionEntry
import com.playtranslate.themeColor

/**
 * Full-screen overlay that dims the screen and shows a small popup menu
 * next to the floating icon. Tapping outside the menu dismisses it.
 * When the hide button is tapped, shows a confirmation dialog.
 *
 * Also supports drag-to-select: dragging outside the menu draws a selection
 * rectangle and fires [onRegionSelected] with fractional coordinates.
 */
class FloatingIconMenu(context: Context) : FrameLayout(context) {

    private val dp = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // Theme colors resolved from the user's selected palette
    private val accentColor: Int = context.themeColor(R.attr.ptAccent).takeIf { it != 0 } ?: Color.parseColor("#4DD0C2")
    private val onAccentColor: Int = context.themeColor(R.attr.ptAccentOn).takeIf { it != 0 } ?: Color.BLACK
    private val cardColor: Int = context.themeColor(R.attr.ptCard).takeIf { it != 0 } ?: Color.parseColor("#1C1F22")
    private val textColor: Int = context.themeColor(R.attr.ptText).takeIf { it != 0 } ?: Color.parseColor("#ECEFF1")
    private val mutedColor: Int = context.themeColor(R.attr.ptTextMuted).takeIf { it != 0 } ?: Color.parseColor("#9AA1A8")
    private val bgColor: Int = context.themeColor(R.attr.ptBg).takeIf { it != 0 } ?: Color.parseColor("#0B0D0E")
    private val pauseColor: Int = context.themeColor(R.attr.ptDanger).takeIf { it != 0 } ?: Color.parseColor("#E05D5D")

    var onHideIcon: (() -> Unit)? = null
    var onHideTemporary: (() -> Unit)? = null
    var onCloseRequested: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null
    var onRegionSelected: ((RegionEntry) -> Unit)? = null
    var onClearRegion: (() -> Unit)? = null
    var onToggleLive: (() -> Unit)? = null
    var onCaptureRegion: (() -> Unit)? = null
    var onSettings: (() -> Unit)? = null
    var isSingleScreen: Boolean = false

    /** Current active capture region as fractional coordinates (top, bottom, left, right).
     *  null or (0,1,0,1) means full screen — no region highlight shown. */
    var activeRegion: RegionEntry? = null
    /** Label for the hint-text overlay mode ("Furigana", "Pinyin", etc.), or null for translation mode. */
    var hintModeLabel: String? = null
        set(value) { field = value; updateLiveButton() }
    var isLiveMode: Boolean = false
        set(value) {
            field = value
            updateLiveButton()
        }

    var showDegradedWarning: Boolean = false
        set(value) {
            field = value
            degradedWarningView?.visibility = if (value) View.VISIBLE else View.GONE
        }

    private val dimPaint = Paint().apply {
        color = Color.argb(170, 0, 0, 0)
    }
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val selectionStrokePaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 3f * dp
        isAntiAlias = true
    }

    private val regionStrokePaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.argb(200, 100, 180, 255)
        strokeWidth = 2f * dp
        isAntiAlias = true
    }
    private val regionFillPaint = Paint().apply {
        color = Color.argb(60, 100, 180, 255)
        style = Paint.Style.FILL
    }
    private val regionLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * dp
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f * dp, 0f, 0f, Color.BLACK)
    }

    // Scratch RectF reused in onDraw — allocating per frame is lint DrawAllocation.
    private val regionRect = RectF()

    private var clearRegionButton: View? = null
    private var degradedWarningView: View? = null

    private val menuCard: LinearLayout
    private val settingsBtn: View
    private val instructionText: TextView
    private val appName: String = context.getString(R.string.app_name)

    private lateinit var liveIcon: TextView
    private lateinit var liveLabel: TextView
    private lateinit var liveBtn: FrameLayout

    // ── Drag state ────────────────────────────────────────────────────────
    private var isDragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var selectionRect: RectF? = null
    private var potentialDrag = false

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_HARDWARE, null)
        clipChildren = false
        clipToPadding = false

        val btnSize = (54 * dp).toInt()
        val iconPad = (14 * dp).toInt()

        // Rounded rectangle container for both buttons
        val borderColor = context.themeColor(R.attr.ptDivider)
        menuCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(0xD9, Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor)))
                cornerRadius = 20 * dp
                setStroke((1 * dp).toInt(), borderColor)
            }
            elevation = 8 * dp
            clipChildren = false
            clipToPadding = false
            gravity = Gravity.CENTER_HORIZONTAL
            val hPad = (14 * dp).toInt()
            setPadding(hPad, (14 * dp).toInt(), hPad, (12 * dp).toInt())
            visibility = View.INVISIBLE
        }

        // Live mode button + label
        val liveGroup = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * dp).toInt() }
        }
        liveBtn = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(accentColor)
                cornerRadius = 14 * dp
            }
            elevation = 4 * dp
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener { onToggleLive?.invoke() }
        }
        liveIcon = TextView(context).apply {
            text = "\u25B6"
            setTextColor(onAccentColor)
            textSize = 22f
            gravity = Gravity.CENTER
        }
        liveBtn.addView(liveIcon, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        liveLabel = TextView(context).apply {
            text = "Auto Translate"
            setTextColor(textColor)
            textSize = 9f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(null, Typeface.BOLD)
            maxWidth = btnSize
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() }
        }
        liveGroup.addView(liveBtn)
        liveGroup.addView(liveLabel)
        menuCard.addView(liveGroup)

        // Capture Region button + label
        val regionGroup = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * dp).toInt() }
        }
        val regionBtn = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(accentColor)
                cornerRadius = 14 * dp
            }
            elevation = 4 * dp
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener { onCaptureRegion?.invoke() }
        }
        val regionIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_crop)
            imageTintList = android.content.res.ColorStateList.valueOf(onAccentColor)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(iconPad, iconPad, iconPad, iconPad)
        }
        regionBtn.addView(regionIcon, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val regionLabel = TextView(context).apply {
            text = "Capture\nRegion"
            setTextColor(textColor)
            textSize = 9f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(null, Typeface.BOLD)
            maxWidth = btnSize
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() }
        }
        regionGroup.addView(regionBtn)
        regionGroup.addView(regionLabel)
        menuCard.addView(regionGroup)

        // Hide button + label
        val hideGroup = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val hideBtn = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(cardColor)
                cornerRadius = 14 * dp
            }
            elevation = 4 * dp
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener { onCloseRequested?.invoke() }
        }
        val hideIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_exit_to_app)
            imageTintList = android.content.res.ColorStateList.valueOf(textColor)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(iconPad, iconPad, iconPad, iconPad)
        }
        hideBtn.addView(hideIcon, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val hideLabel = TextView(context).apply {
            text = "Hide"
            setTextColor(textColor)
            textSize = 9f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(null, Typeface.BOLD)
            maxWidth = btnSize
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() }
        }
        hideGroup.addView(hideBtn)
        hideGroup.addView(hideLabel)
        menuCard.addView(hideGroup)

        addView(menuCard, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // Instruction text at top center with pill background
        instructionText = TextView(context).apply {
            text = "Drag finger to capture a specific area"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(
                (14 * dp).toInt(), (3 * dp).toInt(),
                (14 * dp).toInt(), (9 * dp).toInt()
            )
            background = GradientDrawable().apply {
                setColor(Color.argb(180, 30, 30, 30))
                cornerRadii = floatArrayOf(
                    0f, 0f,           // top-left
                    0f, 0f,           // top-right
                    24 * dp, 24 * dp, // bottom-right
                    24 * dp, 24 * dp  // bottom-left
                )
            }
        }
        addView(instructionText, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        })

        // Degraded translation warning pill at bottom-center (initially hidden)
        degradedWarningView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(Color.argb(200, 139, 105, 20))
                cornerRadius = 16 * dp
            }
            visibility = View.GONE
            val icon = TextView(context).apply {
                text = "⚠"
                textSize = 14f
                setTextColor(context.themeColor(R.attr.ptWarning))
            }
            val label = TextView(context).apply {
                text = "  Offline — translation quality is reduced"
                setTextColor(Color.WHITE)
                textSize = 12f
            }
            addView(icon)
            addView(label)
        }
        addView(degradedWarningView, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = (32 * dp).toInt()
        })

        // Gear icon for settings — positioned above/below menu card in positionNearIcon
        val gearSize = (48 * dp).toInt()
        val gearIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_settings)
            imageTintList = android.content.res.ColorStateList.valueOf(textColor)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val gearPad = (10 * dp).toInt()
            setPadding(gearPad, gearPad, gearPad, gearPad)
        }
        settingsBtn = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(0xD9, Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor)))
                setStroke((1 * dp).toInt(), borderColor)
            }
            elevation = 4 * dp
            addView(gearIcon, LayoutParams(gearSize, gearSize))
            setOnClickListener { onSettings?.invoke() }
            visibility = View.INVISIBLE
        }
        addView(settingsBtn, LayoutParams(gearSize, gearSize))
    }

    private fun updateLiveButton() {
        if (isLiveMode) {
            liveIcon.text = "\u275A\u275A" // ❚❚ pause
            liveIcon.textSize = 20f
            liveIcon.setTextColor(Color.parseColor("#E8E8E8"))
            liveIcon.setPadding(0, 0, 0, 0)
            liveLabel.text = "Pause Auto"
            (liveBtn.background as? GradientDrawable)?.setColor(pauseColor)
        } else {
            liveIcon.text = "\u25B6" // ▶ play
            liveIcon.textSize = 26f
            liveIcon.setTextColor(onAccentColor)
            liveIcon.setPadding((2 * dp).toInt(), 0, 0, (1 * dp).toInt())
            liveLabel.text = hintModeLabel?.let { "Auto $it" } ?: "Auto Translate"
            (liveBtn.background as? GradientDrawable)?.setColor(accentColor)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val sel = selectionRect
        if (sel != null && isDragging) {
            // User is dragging a new region selection
            val sc = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            canvas.drawRect(sel, clearPaint)
            canvas.restoreToCount(sc)
            canvas.drawRect(sel, selectionStrokePaint)
        } else {
            val region = activeRegion
            if (region != null && !region.isFullScreen) {
                // Show the active capture region as a clear window
                val w = width.toFloat()
                val h = height.toFloat()
                regionRect.set(
                    region.left * w, region.top * h,
                    region.right * w, region.bottom * h
                )
                canvas.drawRect(0f, 0f, w, h, dimPaint)
                canvas.drawRect(regionRect, regionFillPaint)
                canvas.drawRect(regionRect, regionStrokePaint)
                // Label centered in the region (shadow provides contrast)
                val label = "Current capture region"
                val labelCx = regionRect.centerX()
                val labelCy = regionRect.centerY()
                canvas.drawText(label, labelCx,
                    labelCy - (regionLabelPaint.descent() + regionLabelPaint.ascent()) / 2,
                    regionLabelPaint)
            } else {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val loc = IntArray(2)
                menuCard.getLocationOnScreen(loc)
                val menuRect = RectF(
                    loc[0].toFloat(), loc[1].toFloat(),
                    loc[0].toFloat() + menuCard.width, loc[1].toFloat() + menuCard.height
                )
                if (menuRect.contains(event.rawX, event.rawY)) {
                    return super.onTouchEvent(event)
                }
                potentialDrag = true
                dragStartX = event.x
                dragStartY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!potentialDrag) return super.onTouchEvent(event)
                val dx = event.x - dragStartX
                val dy = event.y - dragStartY
                if (!isDragging && (dx * dx + dy * dy > touchSlop * touchSlop)) {
                    isDragging = true
                    menuCard.visibility = View.GONE
                    instructionText.visibility = View.GONE
                    clearRegionButton?.visibility = View.GONE
                }
                if (isDragging) {
                    val left   = minOf(dragStartX, event.x)
                    val top    = minOf(dragStartY, event.y)
                    val right  = maxOf(dragStartX, event.x)
                    val bottom = maxOf(dragStartY, event.y)
                    selectionRect = RectF(left, top, right, bottom)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    val sel = selectionRect
                    if (sel != null && sel.width() > touchSlop && sel.height() > touchSlop) {
                        val w = width.toFloat()
                        val h = height.toFloat()
                        if (w > 0 && h > 0) {
                            onRegionSelected?.invoke(
                                RegionEntry("Drawn Region", sel.top / h, sel.bottom / h, sel.left / w, sel.right / w)
                            )
                        }
                    }
                    isDragging = false
                    potentialDrag = false
                    selectionRect = null
                    return true
                }
                potentialDrag = false
                onDismiss?.invoke()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                potentialDrag = false
                selectionRect = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // ── Positioning ──────────────────────────────────────────────────────

    @Suppress("UNUSED_PARAMETER")
    fun positionNearIcon(iconCx: Int, iconCy: Int, iconEdge: FloatingOverlayIcon.Edge, screenW: Int, screenH: Int) {
        post {
            menuCard.measure(
                MeasureSpec.makeMeasureSpec(screenW, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(screenH, MeasureSpec.AT_MOST)
            )
            val mw = menuCard.measuredWidth
            val mh = menuCard.measuredHeight
            val margin = (16 * dp).toInt()

            val lp = menuCard.layoutParams as LayoutParams

            val menuX = if (iconEdge == FloatingOverlayIcon.Edge.LEFT) {
                margin
            } else {
                screenW - mw - margin
            }

            val menuY = (iconCy - mh / 2).coerceIn(margin, screenH - mh - margin)

            lp.gravity = Gravity.TOP or Gravity.START
            lp.leftMargin = menuX
            lp.topMargin = menuY
            menuCard.layoutParams = lp
            menuCard.visibility = View.VISIBLE

            menuCard.alpha = 0f
            menuCard.scaleX = 0.8f
            menuCard.scaleY = 0.8f
            menuCard.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(150)
                .setInterpolator(DecelerateInterpolator())
                .start()

            // Position gear icon centered above (or below) the menu card
            val gearSize = (48 * dp).toInt()
            val gearGap = (8 * dp).toInt()
            val gearX = menuX + (mw - gearSize) / 2
            val gearAboveY = menuY - gearSize - gearGap
            val gearBelowY = menuY + mh + gearGap
            val gearY = if (gearAboveY >= 0) gearAboveY else gearBelowY
            val glp = settingsBtn.layoutParams as LayoutParams
            glp.gravity = Gravity.TOP or Gravity.START
            glp.leftMargin = gearX
            glp.topMargin = gearY
            settingsBtn.layoutParams = glp
            settingsBtn.visibility = View.VISIBLE
            settingsBtn.alpha = 0f
            settingsBtn.scaleX = 0.8f
            settingsBtn.scaleY = 0.8f
            settingsBtn.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(150)
                .setInterpolator(DecelerateInterpolator()).start()

            // Show red X button to clear region (if a custom region is active)
            showClearRegionButton(iconEdge, screenW, screenH)
        }
    }

    private fun showClearRegionButton(iconEdge: FloatingOverlayIcon.Edge, screenW: Int, screenH: Int) {
        clearRegionButton?.let { removeView(it) }
        clearRegionButton = null

        val region = activeRegion ?: return
        if (region.isFullScreen) return

        val btnSize = (36 * dp).toInt()
        val touchSize = (56 * dp).toInt()
        val touchPad = (touchSize - btnSize) / 2
        val regionRect = RectF(
            region.left * screenW, region.top * screenH,
            region.right * screenW, region.bottom * screenH
        )

        // Position on the opposite side from the menu
        val btnX = if (iconEdge == FloatingOverlayIcon.Edge.LEFT) {
            (regionRect.right - btnSize - 8 * dp).toInt()
        } else {
            (regionRect.left + 8 * dp).toInt()
        }
        val btnY = (regionRect.top + 8 * dp).toInt()

        val btn = View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.argb(220, 200, 40, 40))
            }
            setOnClickListener {
                onClearRegion?.invoke()
                onDismiss?.invoke()
            }
        }

        // Draw X using a simple TextView overlay
        val xLabel = TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        val container = FrameLayout(context).apply {
            val innerLp = FrameLayout.LayoutParams(btnSize, btnSize).apply {
                gravity = Gravity.CENTER
            }
            addView(btn, innerLp)
            addView(xLabel, FrameLayout.LayoutParams(touchSize, touchSize).apply {
                gravity = Gravity.CENTER
            })
            setOnClickListener {
                onClearRegion?.invoke()
                onDismiss?.invoke()
            }
        }

        val lp = LayoutParams(touchSize, touchSize).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = btnX - touchPad
            topMargin = btnY - touchPad
        }
        addView(container, lp)
        clearRegionButton = container
    }
}
