package com.playtranslate.ui

import android.content.Context
import android.content.res.ColorStateList
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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.playtranslate.R
import com.playtranslate.RegionEntry
import com.playtranslate.themeColor
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.graphics.toColorInt

/** Reason the floating-icon menu's warning pill is shown. None hides the
 *  pill; the other two pick the corresponding label string. Set by the
 *  accessibility service from CaptureService's (degraded, displaced)
 *  state at menu build time. */
enum class DegradedWarningKind { None, Offline, LowMemory }

/**
 * Full-screen overlay that dims the screen and shows a small popup menu
 * next to the floating icon. Tapping outside the menu dismisses it.
 *
 * The menu uses the B4 split layout: a left lane of three icon-only
 * secondary actions (Edit region, Settings, Exit) and a right lane of two
 * labelled primary actions stacked vertically (Translate once, then the
 * Auto-translate toggle).
 *
 * Also supports drag-to-select: dragging outside the menu draws a selection
 * rectangle and fires [onRegionSelected] with fractional coordinates.
 */
class FloatingIconMenu(context: Context) : FrameLayout(context) {

    private val dp = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // Theme colors resolved from the user's selected palette
    private val accentColor: Int = context.themeColor(R.attr.ptAccent).takeIf { it != 0 } ?: "#4DD0C2".toColorInt()
    private val onAccentColor: Int = context.themeColor(R.attr.ptAccentOn).takeIf { it != 0 } ?: Color.BLACK
    private val cardColor: Int = context.themeColor(R.attr.ptCard).takeIf { it != 0 } ?: "#1C1F22".toColorInt()
    private val textColor: Int = context.themeColor(R.attr.ptText).takeIf { it != 0 } ?: "#ECEFF1".toColorInt()
    private val mutedColor: Int = context.themeColor(R.attr.ptTextMuted).takeIf { it != 0 } ?: "#9AA1A8".toColorInt()
    private val bgColor: Int = context.themeColor(R.attr.ptBg).takeIf { it != 0 } ?: "#0B0D0E".toColorInt()
    private val dangerColor: Int = context.themeColor(R.attr.ptDanger).takeIf { it != 0 } ?: "#E05D5D".toColorInt()
    // Faint accent wash behind the resting "Translate once" primary.
    private val accentTintColor: Int = context.themeColor(R.attr.ptAccentTint).takeIf { it != 0 }
        ?: Color.argb(0x24, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))

    var onHideIcon: (() -> Unit)? = null
    var onHideTemporary: (() -> Unit)? = null
    var onCloseRequested: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null
    var onRegionSelected: ((RegionEntry) -> Unit)? = null
    var onClearRegion: (() -> Unit)? = null
    var onToggleLive: (() -> Unit)? = null
    /** Momentary one-shot: capture-and-translate the current region. */
    var onTranslateOnce: (() -> Unit)? = null
    var onCaptureRegion: (() -> Unit)? = null
    var onSettings: (() -> Unit)? = null
    var isSingleScreen: Boolean = false

    /** True in MediaProjection mode or single-screen — the Exit control then
     *  swaps to the "Turn Off" glyph and confirms turning PlayTranslate off.
     *  Set by showFloatingMenu. Exit is icon-only now, so the wording lives in
     *  the button's accessibility label rather than a visible caption. */
    var exitFlow: Boolean = false
        set(value) {
            field = value
            hideIcon.setImageResource(
                if (value) R.drawable.ic_mode_off_on else R.drawable.ic_exit_to_app
            )
            hideBtn.contentDescription = context.getString(
                if (value) R.string.floating_icon_close_label_turn_off
                else R.string.floating_icon_close_label_hide
            )
        }

    /** Current active capture region as fractional coordinates (top, bottom, left, right).
     *  null or (0,1,0,1) means full screen — no region highlight shown. */
    var activeRegion: RegionEntry? = null
        set(value) {
            field = value
            // The drag-hint pill and the region preview are mutually exclusive.
            instructionPill.visibility =
                if (value != null && !value.isFullScreen) View.GONE else View.VISIBLE
            // Capture button reflects full screen vs custom region.
            updateCaptureButton()
        }
    /** Label for the hint-text overlay mode ("Furigana", "Pinyin", etc.), or null for translation mode. */
    var hintModeLabel: String? = null
        set(value) { field = value; updateLiveButton() }
    var isLiveMode: Boolean = false
        set(value) {
            field = value
            updateLiveButton()
            // Capture button restyles to the neutral left-lane look while live.
            updateCaptureButton()
        }

    /** Kind of warning to show on the bottom-center pill.
     *  [DegradedWarningKind.None] hides the pill;
     *  [DegradedWarningKind.Offline] / [DegradedWarningKind.LowMemory] show
     *  it with the appropriate label. Set by the accessibility service at
     *  menu build time based on the current
     *  [com.playtranslate.CaptureService] state. */
    var degradedWarningKind: DegradedWarningKind = DegradedWarningKind.None
        set(value) {
            field = value
            when (value) {
                DegradedWarningKind.None ->
                    degradedWarningView?.visibility = View.GONE
                DegradedWarningKind.Offline -> {
                    degradedWarningLabel?.setText(R.string.degraded_warning_offline)
                    degradedWarningView?.visibility = View.VISIBLE
                }
                DegradedWarningKind.LowMemory -> {
                    degradedWarningLabel?.setText(R.string.degraded_warning_low_memory)
                    degradedWarningView?.visibility = View.VISIBLE
                }
            }
        }

    private val dimPaint = Paint().apply {
        // Match the region-editing view's dim (alpha 200) — a little less see-through.
        color = Color.argb(200, 0, 0, 0)
    }
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val selectionBasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.themeColor(R.attr.ptDivider)
        strokeWidth = 2f * dp
    }
    private val selectionDashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accentColor
        strokeWidth = 2f * dp
        strokeCap = Paint.Cap.ROUND
    }
    private val selDashLen = 8f
    private val selGapLen = 6f

    private val regionFillPaint = Paint().apply {
        // Half the previous 60 — a lighter wash so the game shows through more.
        color = Color.argb(30, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
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
    /** TextView inside [degradedWarningView] holding the pill's label.
     *  Stored so [degradedWarningKind]'s setter can rewrite it on-the-fly
     *  instead of teardown/rebuild. Set during inflation. */
    private var degradedWarningLabel: TextView? = null

    private val menuCard: LinearLayout
    private val instructionPill: LinearLayout

    // Capture button (right lane, top). [updateCaptureButton] sets its glyph +
    // label from the active region (full screen vs custom) and its colors from
    // live state (resting accent tint, or the neutral left-lane styling while live).
    private val captureBtn: View
    private val captureIcon: ImageView
    private val captureLabel: TextView
    // Auto-translate toggle (right lane, bottom). The icon + fill swap on
    // [updateLiveButton] between the resting play/accent and running pause/danger.
    private val liveIcon: ImageView
    private val liveLabel: TextView
    private val liveBtn: View
    // Exit (left lane, bottom). [exitFlow] swaps hideIcon's glyph and the
    // accessibility label on the clickable hideBtn.
    private val hideIcon: ImageView
    private val hideBtn: View

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

        // Uniform 11dp rhythm: container padding, lane gap, and the gap
        // between the two stacked primaries are all the same value.
        val pad = (11 * dp).toInt()
        val laneGap = (11 * dp).toInt()
        val primarySize = (78 * dp).toInt()
        val primaryGap = (11 * dp).toInt()
        val secondarySize = (48 * dp).toInt()
        // Secondary lane matches the primary lane's height so its three icons
        // distribute top-to-bottom (space-between) across the same span.
        val laneHeight = primarySize * 2 + primaryGap
        val hairline = context.themeColor(R.attr.ptDivider)

        // ── Secondary lane (left): three icon-only square buttons ─────────
        val editIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_crop)
            imageTintList = ColorStateList.valueOf(mutedColor)
        }
        val editBtn = makeSecondaryButton(
            editIcon, context.getString(R.string.floating_menu_edit_region)
        ) { onCaptureRegion?.invoke() }

        val settingsIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_settings)
            imageTintList = ColorStateList.valueOf(mutedColor)
        }
        val settingsButton = makeSecondaryButton(
            settingsIcon, context.getString(R.string.nav_settings)
        ) { onSettings?.invoke() }

        val exitDesc = context.getString(R.string.floating_icon_close_label_hide)
        hideIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_exit_to_app)
            imageTintList = ColorStateList.valueOf(mutedColor)
        }
        hideBtn = makeSecondaryButton(hideIcon, exitDesc) { onCloseRequested?.invoke() }

        val secondaryLane = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, laneHeight
            ).apply { marginEnd = laneGap }
            addView(editBtn, LinearLayout.LayoutParams(secondarySize, secondarySize))
            addView(laneSpacer())
            addView(settingsButton, LinearLayout.LayoutParams(secondarySize, secondarySize))
            addView(laneSpacer())
            addView(hideBtn, LinearLayout.LayoutParams(secondarySize, secondarySize))
        }

        // ── Primary lane (right): Capture + Auto-translate ────────────────
        // captureIcon/captureLabel/fill are populated by updateCaptureButton()
        // from the active region + live state, below.
        captureIcon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams((26 * dp).toInt(), (26 * dp).toInt())
        }
        captureLabel = TextView(context).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 2
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (5 * dp).toInt() }
        }
        captureBtn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = 11 * dp }
            layoutParams = LinearLayout.LayoutParams(primarySize, primarySize)
            addView(captureIcon)
            addView(captureLabel)
            setOnClickListener { onTranslateOnce?.invoke() }
        }
        updateCaptureButton()

        liveIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_play)
            imageTintList = ColorStateList.valueOf(onAccentColor)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams((26 * dp).toInt(), (26 * dp).toInt())
        }
        liveLabel = TextView(context).apply {
            text = context.getString(R.string.live_mode_auto_translate_label)
            setTextColor(onAccentColor)
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 2
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (5 * dp).toInt() }
        }
        liveBtn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            contentDescription = context.getString(R.string.live_mode_auto_translate_label)
            background = GradientDrawable().apply {
                setColor(accentColor)
                cornerRadius = 11 * dp
            }
            layoutParams = LinearLayout.LayoutParams(primarySize, primarySize).apply {
                topMargin = primaryGap
            }
            addView(liveIcon)
            addView(liveLabel)
            setOnClickListener { onToggleLive?.invoke() }
        }

        val primaryLane = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(captureBtn)
            addView(liveBtn)
        }

        // ── Container: both lanes side by side on one elevated surface ────
        menuCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = 14 * dp
                setStroke((1 * dp).toInt(), hairline)
            }
            elevation = 8 * dp
            clipChildren = false
            clipToPadding = false
            setPadding(pad, pad, pad, pad)
            visibility = View.INVISIBLE
            addView(secondaryLane)
            addView(primaryLane)
        }
        addView(menuCard, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // Drag-hint pill, centered on screen
        val dividerColor = context.themeColor(R.attr.ptDivider)
        val instructionIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_gesture_select)
            imageTintList = ColorStateList.valueOf(textColor)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt()).apply {
                rightMargin = (8 * dp).toInt()
            }
        }
        val instructionLabel = TextView(context).apply {
            text = context.getString(R.string.floating_menu_drag_instruction)
            setTextColor(textColor)
            textSize = 14f
        }
        instructionPill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (18 * dp).toInt(), (10 * dp).toInt(),
                (18 * dp).toInt(), (10 * dp).toInt()
            )
            background = GradientDrawable().apply {
                setColor(Color.argb(0xD9, Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor)))
                setStroke((1 * dp).toInt(), dividerColor)
                cornerRadius = 100 * dp
            }
            addView(instructionIcon)
            addView(instructionLabel)
        }
        addView(instructionPill, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
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
                // Initial text is a safe default — overwritten by
                // [degradedWarningKind]'s setter before the pill is shown.
                setText(R.string.degraded_warning_offline)
                setTextColor(Color.WHITE)
                textSize = 12f
            }
            degradedWarningLabel = label
            addView(icon)
            addView(label, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                // Visual gap between the ⚠ icon glyph and the label.
                // Previously the resource strings carried two leading
                // spaces; consolidated to a marginStart so translators
                // don't have to preserve invisible whitespace.
                marginStart = (6 * dp).toInt()
            })
        }
        addView(degradedWarningView, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = (32 * dp).toInt()
        })
    }

    /** Equal-weight filler that pushes the secondary lane's three icons to a
     *  space-between distribution within the fixed lane height. */
    private fun laneSpacer(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )
    }

    /** Refreshes the capture button's glyph + label from the active region
     *  (full screen → "Capture screen"; a custom region → "Capture region"),
     *  and its colors from live state — the prominent accent tint at rest, or
     *  the neutral card styling of the left-lane buttons while auto-translate runs. */
    private fun updateCaptureButton() {
        val fullScreen = activeRegion?.isFullScreen ?: true
        captureIcon.setImageResource(
            if (fullScreen) R.drawable.ic_capture else R.drawable.ic_picture_in_picture_alt
        )
        captureLabel.text = context.getString(
            if (fullScreen) R.string.floating_menu_capture_screen
            else R.string.floating_menu_btn_capture_region
        )
        captureBtn.contentDescription = captureLabel.text

        val content = if (isLiveMode) mutedColor else accentColor
        captureIcon.imageTintList = ColorStateList.valueOf(content)
        captureLabel.setTextColor(content)
        (captureBtn.background as? GradientDrawable)?.apply {
            setColor(if (isLiveMode) cardColor else accentTintColor)
            // While live, match the left-lane buttons' hairline; none at rest.
            if (isLiveMode) setStroke((1 * dp).toInt(), context.themeColor(R.attr.ptDivider))
            else setStroke(0, Color.TRANSPARENT)
        }
    }

    /** Builds a 48dp icon-only secondary button with a faint wash + hairline.
     *  The caller supplies the [icon] (pre-tinted) and keeps any reference it
     *  needs to mutate later. */
    private fun makeSecondaryButton(
        icon: ImageView,
        desc: CharSequence,
        onClick: () -> Unit,
    ): FrameLayout {
        val iconPad = (14 * dp).toInt()
        icon.apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(iconPad, iconPad, iconPad, iconPad)
            // The glyph is decorative; the clickable button carries the label,
            // so keep the icon out of the accessibility tree.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        return FrameLayout(context).apply {
            background = GradientDrawable().apply {
                // Solid grouped-card fill, matching the Settings cells. A solid
                // token (not a translucent wash) keeps these stable regardless
                // of the container color behind them.
                setColor(cardColor)
                cornerRadius = 9 * dp
                setStroke((1 * dp).toInt(), context.themeColor(R.attr.ptDivider))
            }
            // Label the clickable control itself — this is the node TalkBack
            // focuses and activates, so the description must live here.
            contentDescription = desc
            addView(icon, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            setOnClickListener { onClick() }
        }
    }

    private fun updateLiveButton() {
        if (isLiveMode) {
            // Running: pause glyph on the danger fill, light content for contrast.
            liveIcon.setImageResource(R.drawable.ic_pause)
            liveIcon.imageTintList = ColorStateList.valueOf(textColor)
            liveLabel.text = context.getString(R.string.live_mode_pause_auto_label)
            liveLabel.setTextColor(textColor)
            (liveBtn.background as? GradientDrawable)?.setColor(dangerColor)
        } else {
            // Resting: play glyph on the accent fill, on-accent content.
            liveIcon.setImageResource(R.drawable.ic_play)
            liveIcon.imageTintList = ColorStateList.valueOf(onAccentColor)
            liveLabel.text = hintModeLabel
                ?.let { context.getString(R.string.live_mode_auto_with_hint, it) }
                ?: context.getString(R.string.live_mode_auto_translate_label)
            liveLabel.setTextColor(onAccentColor)
            (liveBtn.background as? GradientDrawable)?.setColor(accentColor)
        }
        liveBtn.contentDescription = liveLabel.text
    }

    override fun onDraw(canvas: Canvas) {
        val sel = selectionRect
        if (sel != null && isDragging) {
            // User is dragging a new region selection
            val sc = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            canvas.drawRect(sel, clearPaint)
            canvas.restoreToCount(sc)
            // Card-colored base border + accent dashes (screen-space stable)
            drawDashedBorder(canvas, sel)
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
                // Same separated-line boundary as region editing.
                drawDashedBorder(canvas, regionRect)
                // Label centered in the region (shadow provides contrast)
                val label = context.getString(R.string.region_label_current_capture)
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

    /** Required by ClickableViewAccessibility — the menu intercepts touches
     *  to detect tap-outside-the-card dismissal, not "click on the menu
     *  itself". No accessibility-click action to expose; the menu's row
     *  buttons (which use setOnClickListener) are the actionable items
     *  TalkBack should focus and activate. */
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // onTouchEvent detects tap-outside-the-card dismissal, not clicks on
    // this view — no click semantic to wire through performClick. The
    // inner row buttons have their own setOnClickListener.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
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
                    menuCard.isGone = true
                    instructionPill.isGone = true
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

    /** The separated-line border shared by the drag selection and the active-region
     *  preview: a card-colored base + accent dashes (screen-space stable). */
    private fun drawDashedBorder(canvas: Canvas, r: RectF) {
        canvas.drawRect(r, selectionBasePaint)
        val dashPx = selDashLen * dp
        val period = dashPx + selGapLen * dp
        drawScreenDashes(canvas, r.left, r.top, r.right, r.top, dashPx, period, true)
        drawScreenDashes(canvas, r.right, r.top, r.right, r.bottom, dashPx, period, false)
        drawScreenDashes(canvas, r.left, r.bottom, r.right, r.bottom, dashPx, period, true)
        drawScreenDashes(canvas, r.left, r.top, r.left, r.bottom, dashPx, period, false)
    }

    @Suppress("UNUSED_PARAMETER")
    /** Draws dashes along a line at fixed screen-space positions. */
    private fun drawScreenDashes(
        canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float,
        dashPx: Float, period: Float, horizontal: Boolean
    ) {
        if (horizontal) {
            val y = y1
            var pos = (x1 / period).toInt() * period
            if (pos > x1) pos -= period
            while (pos < x2) {
                val s = pos.coerceAtLeast(x1)
                val e = (pos + dashPx).coerceAtMost(x2)
                if (e > s) canvas.drawLine(s, y, e, y, selectionDashPaint)
                pos += period
            }
        } else {
            val x = x1
            var pos = (y1 / period).toInt() * period
            if (pos > y1) pos -= period
            while (pos < y2) {
                val s = pos.coerceAtLeast(y1)
                val e = (pos + dashPx).coerceAtMost(y2)
                if (e > s) canvas.drawLine(x, s, x, e, selectionDashPaint)
                pos += period
            }
        }
    }

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
            menuCard.isVisible = true

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

            // Show red X button to clear region (if a custom region is active)
            showClearRegionButton(iconEdge, screenW, screenH)
        }
    }

    private fun showClearRegionButton(iconEdge: FloatingOverlayIcon.Edge, screenW: Int, screenH: Int) {
        clearRegionButton?.let { removeView(it) }
        clearRegionButton = null

        val region = activeRegion ?: return
        if (region.isFullScreen) return

        // Tapping the X clears the active region. Outside live mode, keep the
        // menu open and flip it to its full-screen state (remove the preview + X,
        // switch the capture button to "Capture screen"); in live mode, preserve
        // the prior behavior and close the menu.
        val onClearTapped: () -> Unit = {
            onClearRegion?.invoke()
            if (isLiveMode) {
                onDismiss?.invoke()
            } else {
                clearRegionButton?.let { removeView(it) }
                clearRegionButton = null
                activeRegion = null
                invalidate()
            }
        }

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
                setColor(dangerColor)
            }
            setOnClickListener { onClearTapped() }
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
            setOnClickListener { onClearTapped() }
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
