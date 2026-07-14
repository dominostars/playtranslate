package com.playtranslate.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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

    // Screen margin the menu card is anchored by, and the clearance the
    // drag-hint pill keeps from the card / screen edges. See positionInstructionPill.
    private val screenMargin = (16 * dp).toInt()
    private val pillBuffer = (16 * dp).toInt()

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
    // Expanded settings-panel row actions.
    var onSelectLanguage: (() -> Unit)? = null
    var onSelectOcr: (() -> Unit)? = null
    var onCycleOverlayMode: (() -> Unit)? = null
    var onOpenApp: (() -> Unit)? = null
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
            // The drag-hint pill shows only for a full-screen region, and never
            // while the settings panel is open (the panel fades it out).
            applyInstructionPillVisibility()
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

    /** Which primary carries the strong accent when not live: true = the
     *  capture button, false = auto-translate (the default). Tracks the
     *  most-recently-used primary; [isLiveMode] overrides it. */
    var captureHighlighted: Boolean = false
        set(value) {
            field = value
            updateLiveButton()
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
    // The pill's label, kept as a field so positionInstructionPill can cap its
    // width (forcing a wrap) when the pill can't fit on one line.
    private val instructionLabel: TextView

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
    // Settings gear (left lane, middle). Toggles the inline expanded panel
    // rather than opening the app; [updateSettingsButton] swaps its styling.
    private val settingsBtn: View
    private val settingsIcon: ImageView
    // The two big primaries ([primaryLane]) and the future expanded-panel
    // [contentArea] share [rightStack]; expanding fades the primaries out and
    // widens the rightStack (and the whole card) to [expandedRightWidthPx].
    private val primaryLane: View
    private val rightStack: FrameLayout
    private val contentArea: FrameLayout
    /** The icon-only lane; [positionNearIcon] orders it vs. [rightStack] so the
     *  big lane sits nearest the floating icon (flipped when the icon is on the
     *  left edge). */
    private val secondaryLane: LinearLayout
    // Expanded-panel table: a scrolling list of settings-style rows, populated
    // by [setPanelData]. Cell height set so three cells span the secondary lane.
    private val panelRows: LinearLayout
    private var panelCellHeightPx = 0
    /** The Overlays row's value view, updated in place when the mode cycles. */
    private var overlayModeValueView: TextView? = null

    // ── Expanded settings panel state ─────────────────────────────────────
    private var expanded = false
    private var widthAnimator: ValueAnimator? = null
    private var collapsedRightWidthPx = 0
    private var expandedRightWidthPx = 0
    /** Collapsed menu-card footprint. The drag-hint pill only shows while the
     *  card is collapsed, so it's positioned against these dims rather than the
     *  live (possibly expanded) measurement. */
    private var collapsedCardWidthPx = 0
    private var collapsedCardHeightPx = 0
    /** Gates the drag-hint pill hidden until [positionInstructionPill] runs once,
     *  so it never flashes at screen-center before being placed. */
    private var instructionPillPlaced = false
    /** Collapsed left margin + right-edge anchoring, captured by
     *  [positionNearIcon] so the width animation grows the card toward screen
     *  center rather than off the icon's edge. */
    private var collapsedLeftMargin = 0
    private var menuAnchorRight = false

    // ── Primary label sizing ──────────────────────────────────────────────
    /** Resting text size of a primary button's label, and the floor [fitLabel]
     *  is allowed to shrink it to. */
    private val labelMaxSp = 11f
    private val labelMinSp = 8.5f
    /** Text column inside a primary button: its width less the label's side padding. */
    private var labelColumnPx = 0

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
        // Keeps a primary's label off the button's rounded edges: wrap-content plus
        // this padding caps the text at primarySize - 2 * labelSidePad, so long
        // (or localized) labels wrap instead of running to the corners.
        val labelSidePad = (6 * dp).toInt()
        labelColumnPx = primarySize - 2 * labelSidePad
        // Secondary lane matches the primary lane's height so its three icons
        // distribute top-to-bottom (space-between) across the same span.
        val laneHeight = primarySize * 2 + primaryGap
        val hairline = context.themeColor(R.attr.ptDivider)
        // Collapsed: rightStack wraps the 78dp primaries. Expanded: it widens so
        // the whole card reaches 300dp (card = padding*2 + secondary + gap + right) —
        // wide enough for the panel's longest row label to fit on one line.
        collapsedRightWidthPx = primarySize
        expandedRightWidthPx = (300 * dp).toInt() - (pad * 2 + secondarySize + laneGap)
        // Collapsed card = padding + secondary lane + lane gap + collapsed right lane.
        collapsedCardWidthPx = pad * 2 + secondarySize + laneGap + collapsedRightWidthPx
        collapsedCardHeightPx = laneHeight + 2 * pad

        // ── Secondary lane (left): three icon-only square buttons ─────────
        val editIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_crop)
            imageTintList = ColorStateList.valueOf(mutedColor)
        }
        val editBtn = makeSecondaryButton(
            editIcon, context.getString(R.string.floating_menu_edit_region)
        ) { onCaptureRegion?.invoke() }

        settingsIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_settings)
            imageTintList = ColorStateList.valueOf(mutedColor)
        }
        settingsBtn = makeSecondaryButton(
            settingsIcon, context.getString(R.string.nav_settings)
        ) { toggleExpanded() }

        val exitDesc = context.getString(R.string.floating_icon_close_label_hide)
        hideIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_exit_to_app)
            imageTintList = ColorStateList.valueOf(mutedColor)
        }
        hideBtn = makeSecondaryButton(hideIcon, exitDesc) { onCloseRequested?.invoke() }

        secondaryLane = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, laneHeight
            ).apply { marginEnd = laneGap }
            addView(editBtn, LinearLayout.LayoutParams(secondarySize, secondarySize))
            addView(laneSpacer())
            addView(settingsBtn, LinearLayout.LayoutParams(secondarySize, secondarySize))
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
            textSize = labelMaxSp
            gravity = Gravity.CENTER
            maxLines = 2
            setTypeface(null, Typeface.BOLD)
            setPadding(labelSidePad, 0, labelSidePad, 0)
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
            textSize = labelMaxSp
            gravity = Gravity.CENTER
            maxLines = 2
            setTypeface(null, Typeface.BOLD)
            setPadding(labelSidePad, 0, labelSidePad, 0)
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
        fitLabel(liveLabel)

        primaryLane = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(captureBtn)
            addView(liveBtn)
        }

        // The primaries sit in front of a content area (the expanded panel's
        // scrolling table); both share rightStack. Its fixed height keeps the
        // card height constant when the primaries fade out; its width animates
        // between collapsed/expanded.
        // Compact 52dp rows; any rows beyond what fits in the menu height scroll.
        panelCellHeightPx = (52 * dp).toInt()
        panelRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        contentArea = FrameLayout(context).apply {
            visibility = View.GONE
            addView(
                ScrollView(context).apply {
                    isVerticalScrollBarEnabled = false
                    addView(panelRows, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ))
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        rightStack = FrameLayout(context).apply {
            // Span the full menu-card height — into the 11dp top/bottom padding via
            // negative margins — so the expanded panel's scroll view matches the
            // menu's top/bottom bounds. The margins cancel, so the card height is
            // unchanged (menuCard's clipToPadding=false lets it draw to the edges).
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, laneHeight + 2 * pad
            ).apply { topMargin = -pad; bottomMargin = -pad }
            addView(contentArea, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            // Keep the two big buttons aligned with the secondary lane (centered in
            // the taller stack), not pushed up to the card's top edge.
            addView(primaryLane, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_VERTICAL })
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
            addView(rightStack)
        }
        addView(menuCard, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // Drag-hint pill — placed by positionInstructionPill; hidden until then.
        val dividerColor = context.themeColor(R.attr.ptDivider)
        val instructionIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_gesture_select)
            imageTintList = ColorStateList.valueOf(textColor)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt()).apply {
                rightMargin = (8 * dp).toInt()
            }
        }
        instructionLabel = TextView(context).apply {
            text = context.getString(R.string.floating_menu_drag_instruction)
            setTextColor(textColor)
            textSize = 14f
        }
        instructionPill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Hidden until positioned, so it can't appear at screen-center first.
            visibility = View.INVISIBLE
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

    /** Sizes [tv]'s text so every authored line fits the button's column.
     *
     *  The labels are written as one or two `\n`-separated lines and the view is
     *  `maxLines = 2`, so a line wider than the column soft-wraps and pushes the
     *  overflow past the cap, where it is dropped without a trace. Measuring each
     *  whole LINE is what forbids that. Measuring the unbreakable runs inside a
     *  line instead — as this did — only ever proves the pieces fit, never the
     *  line: vi "Chụp\nmàn hình" breaks line 2 into "màn" and "hình", each of
     *  which clears the column alone while the line does not, so "hình" silently
     *  vanished at a large font scale.
     *
     *  Stepping the size down also keeps a single long token with no legal break
     *  to take (ru "Автоперевод", th "อัตโนมัติ") off Android's mid-character
     *  split, and absorbs a large system font scale, which neither a fixed size
     *  nor a hard-wrapped string could. */
    private fun fitLabel(tv: TextView) {
        if (labelColumnPx <= 0) return
        val lines = tv.text?.toString().orEmpty().split('\n').filter { it.isNotBlank() }
        if (lines.isEmpty()) return
        var size = labelMaxSp
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        while (size > labelMinSp && lines.maxOf { tv.paint.measureText(it) } > labelColumnPx) {
            size -= 0.5f
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        }
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
        fitLabel(captureLabel)

        // Live: neutral left-lane styling (override). Otherwise the strong accent
        // when this is the active primary, else the subdued accent tint.
        val content = when {
            isLiveMode -> mutedColor
            captureHighlighted -> onAccentColor
            else -> accentColor
        }
        val fill = when {
            isLiveMode -> cardColor
            captureHighlighted -> accentColor
            else -> accentTintColor
        }
        captureIcon.imageTintList = ColorStateList.valueOf(content)
        captureLabel.setTextColor(content)
        (captureBtn.background as? GradientDrawable)?.apply {
            setColor(fill)
            // While live, match the left-lane buttons' hairline; none at rest.
            if (isLiveMode) setStroke((1 * dp).toInt(), context.themeColor(R.attr.ptDivider))
            else setStroke(0, Color.TRANSPARENT)
        }
    }

    /** Toggle the inline expanded settings panel: the gear takes the accent
     *  styling, the two primaries fade out, and the card widens to 300dp with an
     *  ease-out animation (and back). Panel content is added later. */
    private fun toggleExpanded() {
        expanded = !expanded
        updateSettingsButton()

        val startRight = rightStack.width.takeIf { it > 0 } ?: collapsedRightWidthPx
        val targetRight = if (expanded) expandedRightWidthPx else collapsedRightWidthPx

        if (expanded) {
            // Fade the panel's table in.
            contentArea.animate().cancel()
            contentArea.alpha = 0f
            contentArea.visibility = View.VISIBLE
            contentArea.animate().alpha(1f).setDuration(200).start()
            primaryLane.animate().cancel()
            primaryLane.animate().alpha(0f).setDuration(160).withEndAction {
                if (expanded) primaryLane.visibility = View.GONE
            }.start()
            // Fade the drag-finger hint out while the panel is open.
            if (instructionPill.isVisible) {
                instructionPill.animate().cancel()
                instructionPill.animate().alpha(0f).setDuration(160).withEndAction {
                    if (expanded) { instructionPill.visibility = View.GONE; instructionPill.alpha = 1f }
                }.start()
            }
        } else {
            // Fade the panel's table out, then hide it.
            contentArea.animate().cancel()
            contentArea.animate().alpha(0f).setDuration(160).withEndAction {
                if (!expanded) contentArea.visibility = View.GONE
            }.start()
            primaryLane.animate().cancel()
            primaryLane.visibility = View.VISIBLE
            primaryLane.animate().alpha(1f).setDuration(200).start()
            // Fade the drag-finger hint back in if the current region warrants it.
            if (activeRegion?.isFullScreen != false) {
                instructionPill.animate().cancel()
                instructionPill.alpha = 0f
                instructionPill.visibility = View.VISIBLE
                instructionPill.animate().alpha(1f).setDuration(200).start()
            }
        }

        widthAnimator?.cancel()
        var cancelled = false
        widthAnimator = ValueAnimator.ofInt(startRight, targetRight).apply {
            duration = 260
            interpolator = DecelerateInterpolator() // ease-out
            addUpdateListener { a ->
                val w = a.animatedValue as Int
                rightStack.layoutParams = rightStack.layoutParams.apply { width = w }
                // Right-anchored: keep the right edge fixed so the card grows
                // leftward (toward screen center) instead of off the icon's edge.
                if (menuAnchorRight) {
                    (menuCard.layoutParams as LayoutParams).let {
                        it.leftMargin = collapsedLeftMargin - (w - collapsedRightWidthPx)
                        menuCard.layoutParams = it
                    }
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    // Re-wrap to the primaries' width once collapsed; the next
                    // animation owns the end state if this one was superseded.
                    if (cancelled || expanded) return
                    rightStack.layoutParams = rightStack.layoutParams.apply {
                        width = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                }
            })
            start()
        }
    }

    /** Accent-filled "active" look while the panel is expanded; the muted
     *  grouped-card secondary look when collapsed. */
    private fun updateSettingsButton() {
        val bg = settingsBtn.background as? GradientDrawable
        if (expanded) {
            bg?.setColor(accentColor)
            bg?.setStroke(0, Color.TRANSPARENT)
            settingsIcon.imageTintList = ColorStateList.valueOf(onAccentColor)
        } else {
            bg?.setColor(cardColor)
            bg?.setStroke((1 * dp).toInt(), context.themeColor(R.attr.ptDivider))
            settingsIcon.imageTintList = ColorStateList.valueOf(mutedColor)
        }
    }

    /** Populate the expanded panel's table. Rows are tappable (ripple) but their
     *  actions aren't wired yet. The reading-hint row appears only when the
     *  source language has one ([hintLabel] non-null). */
    fun setPanelData(languageName: String, ocrName: String, overlayValue: String?) {
        val inflater = LayoutInflater.from(context)
        panelRows.removeAllViews()
        overlayModeValueView = null

        // Language + OCR: title on the left, right-aligned value (chevron hidden).
        addPanelValueRow(inflater, context.getString(R.string.floating_menu_panel_language), languageName) {
            onSelectLanguage?.invoke()
        }
        panelRows.addView(panelDivider())
        addPanelValueRow(inflater, context.getString(R.string.floating_menu_panel_ocr), ocrName) {
            onSelectOcr?.invoke()
        }

        // Overlays row: the current overlay mode as the value; tapping cycles the
        // available modes. Shown only when the language offers more than one.
        if (overlayValue != null) {
            panelRows.addView(panelDivider())
            val overlayRow = addPanelValueRow(
                inflater, context.getString(R.string.floating_menu_panel_overlays), overlayValue
            ) { onCycleOverlayMode?.invoke() }
            overlayModeValueView = overlayRow.findViewById(R.id.tvRowValue)
        }

        // Open-app row: title + external-link icon (ic_open_in_new in the layout).
        panelRows.addView(panelDivider())
        val openRow = inflater.inflate(R.layout.settings_row_link, panelRows, false)
        compactPanelRow(openRow)
        openRow.findViewById<TextView>(R.id.tvRowTitle).text = context.getString(
            R.string.floating_menu_panel_open_app, context.getString(R.string.app_name)
        )
        openRow.setOnClickListener { onOpenApp?.invoke() }
        panelRows.addView(openRow, panelRowParams())
    }

    /** Update the Overlays row's value after the mode cycles, without a rebuild. */
    fun setOverlayModeValue(name: String) { overlayModeValueView?.text = name }

    /** A value row (title + right-aligned value, no chevron) at the panel's cell
     *  height, with a tap action. Returns the row so the caller can grab its value. */
    private fun addPanelValueRow(
        inflater: LayoutInflater, title: String, value: String, onClick: () -> Unit
    ): View {
        val row = inflater.inflate(R.layout.settings_row_value, panelRows, false)
        compactPanelRow(row)
        row.findViewById<TextView>(R.id.tvRowTitle).text = title
        row.findViewById<TextView>(R.id.tvRowValue).text = value
        row.findViewById<View>(R.id.ivRowChevron).visibility = View.GONE
        row.setOnClickListener { onClick() }
        panelRows.addView(row, panelRowParams())
        return row
    }

    private fun panelRowParams() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, panelCellHeightPx)

    private fun panelDivider(): View = View(context).apply {
        setBackgroundColor(context.themeColor(R.attr.ptDivider))
        // Inset to match the compacted row's horizontal padding.
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
        ).apply { marginStart = (12 * dp).toInt() }
    }

    /** Fit a settings row into the narrow panel: lighter horizontal padding and
     *  a single-line title (ellipsized) so it never wraps into the short cell. */
    private fun compactPanelRow(row: View) {
        row.setPaddingRelative((12 * dp).toInt(), row.paddingTop, (12 * dp).toInt(), row.paddingBottom)
        row.findViewById<TextView>(R.id.tvRowTitle).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
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
            // Resting play glyph. Strong accent unless the capture button is the
            // active primary, in which case auto-translate takes the accent tint.
            liveIcon.setImageResource(R.drawable.ic_play)
            liveLabel.text = hintModeLabel
                ?.let { context.getString(R.string.live_mode_auto_with_hint, it) }
                ?: context.getString(R.string.live_mode_auto_translate_label)
            val strong = !captureHighlighted
            val content = if (strong) onAccentColor else accentColor
            liveIcon.imageTintList = ColorStateList.valueOf(content)
            liveLabel.setTextColor(content)
            (liveBtn.background as? GradientDrawable)?.setColor(if (strong) accentColor else accentTintColor)
        }
        liveBtn.contentDescription = liveLabel.text
        fitLabel(liveLabel)
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

    /** Order the two lanes so the big (primary/content) lane sits nearest the
     *  floating icon: small lane left + big lane right by default (icon on the
     *  right edge), flipped when the icon is on the left edge. The lane gap rides
     *  the first lane's marginEnd. The expanded panel follows since it lives in
     *  the big lane ([rightStack]). */
    private fun applyLaneOrder(iconEdge: FloatingOverlayIcon.Edge) {
        val bigLaneFirst = iconEdge == FloatingOverlayIcon.Edge.LEFT
        val first: View = if (bigLaneFirst) rightStack else secondaryLane
        val second: View = if (bigLaneFirst) secondaryLane else rightStack
        (first.layoutParams as LinearLayout.LayoutParams).marginEnd = (11 * dp).toInt()
        (second.layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        if (menuCard.getChildAt(0) !== first) {
            menuCard.removeAllViews()
            menuCard.addView(first)
            menuCard.addView(second)
        }
    }

    /** Anchor the menu card beside the icon against [screenW]×[screenH].
     *
     *  [animateEntrance] plays the fade/scale-in used when the menu first
     *  opens; pass false to re-anchor an already-open menu in place (on
     *  rotation), matching the icon's silent snap to its new edge slot. */
    fun positionNearIcon(
        iconCx: Int,
        iconCy: Int,
        iconEdge: FloatingOverlayIcon.Edge,
        screenW: Int,
        screenH: Int,
        animateEntrance: Boolean = true,
    ) {
        post {
            // A re-anchor that lands mid-[toggleExpanded] would measure the card
            // at the animator's transient rightStack width and poison
            // collapsedLeftMargin (the running animator keeps applying it,
            // detaching the card from the icon's edge). Settle any in-flight
            // expand/collapse to its target first so the measure below reads the
            // logical collapsed/expanded geometry. Only the in-place re-anchor
            // (rotation) can collide; the entrance path has no animator running.
            if (!animateEntrance) widthAnimator?.end()
            applyLaneOrder(iconEdge)
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
            // Remember the collapsed anchor so the expand/collapse width
            // animation grows the card toward screen center. When re-anchoring
            // an already-expanded, right-anchored card (rotation), the measured
            // width carries the expansion delta, so back it out to recover the
            // collapsed margin — otherwise a later collapse would slide the card
            // off the icon's edge.
            menuAnchorRight = iconEdge == FloatingOverlayIcon.Edge.RIGHT
            collapsedLeftMargin = if (expanded && menuAnchorRight)
                menuX + (expandedRightWidthPx - collapsedRightWidthPx)
            else
                menuX

            if (animateEntrance) {
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
            }

            // Show red X button to clear region (if a custom region is active)
            showClearRegionButton(iconEdge, screenW, screenH)

            // Keep the drag-hint pill clear of the menu card.
            positionInstructionPill(iconCy, iconEdge, screenW, screenH)
        }
    }

    /** The drag-hint pill shows only for a full-screen region, never while the
     *  settings panel is open, and not until it's been positioned once. */
    private fun applyInstructionPillVisibility() {
        instructionPill.visibility = when {
            !instructionPillPlaced -> View.INVISIBLE
            expanded || activeRegion?.isFullScreen == false -> View.GONE
            else -> View.VISIBLE
        }
    }

    /** Place the drag-hint pill so it never collides with the collapsed menu
     *  card. Preference order:
     *   1. Centered on screen, when there's a [pillBuffer] gap to the card.
     *   2. Centered horizontally, vertically halved into the larger of the bands
     *      above / below the card — the bottom band stops at the degraded-warning
     *      pill's top edge when that pill is showing, so the two never collide.
     *   3. Centered vertically, horizontally halved into the gap between the
     *      card's inner edge and the opposite screen edge.
     *  In cases 2 and 3 the pill wraps onto extra lines when its single-line
     *  width won't fit the available width (minus buffers). */
    private fun positionInstructionPill(
        iconCy: Int,
        iconEdge: FloatingOverlayIcon.Edge,
        screenW: Int,
        screenH: Int,
    ) {
        val cardOnLeft = iconEdge == FloatingOverlayIcon.Edge.LEFT
        val cardLeft = if (cardOnLeft) screenMargin else screenW - collapsedCardWidthPx - screenMargin
        val cardRight = cardLeft + collapsedCardWidthPx
        val cardTop = (iconCy - collapsedCardHeightPx / 2)
            .coerceIn(screenMargin, screenH - collapsedCardHeightPx - screenMargin)
        val cardBottom = cardTop + collapsedCardHeightPx
        val centerX = screenW / 2
        val centerY = screenH / 2

        // Natural single-line size, plus the fixed chrome (icon + padding) so a
        // wrap target can be expressed as a label maxWidth.
        val unspec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        instructionLabel.maxWidth = Int.MAX_VALUE
        instructionPill.measure(unspec, unspec)
        val naturalW = instructionPill.measuredWidth
        val naturalH = instructionPill.measuredHeight
        val chrome = naturalW - instructionLabel.measuredWidth

        // Bottom band stops at the degraded-warning pill's top edge when it's
        // shown (it sits 32dp up from the bottom), so the buffer applied by
        // centering keeps the two pills from touching.
        val warning = degradedWarningView
        val effBottom = if (warning != null && warning.isVisible) {
            warning.measure(unspec, unspec)
            screenH - (32 * dp).toInt() - warning.measuredHeight
        } else screenH

        // Commit a placement: wrap to [availW] when the single line overflows,
        // then center the (possibly taller) pill at (cx, cy), clamped on-screen.
        fun place(cx: Int, cy: Int, availW: Int) {
            var pw = naturalW
            var ph = naturalH
            if (naturalW > availW) {
                instructionLabel.maxWidth = (availW - chrome).coerceAtLeast((48 * dp).toInt())
                instructionPill.measure(
                    MeasureSpec.makeMeasureSpec(availW.coerceAtLeast(0), MeasureSpec.AT_MOST),
                    unspec,
                )
                pw = instructionPill.measuredWidth
                ph = instructionPill.measuredHeight
            }
            val lp = instructionPill.layoutParams as LayoutParams
            lp.gravity = Gravity.TOP or Gravity.START
            lp.leftMargin = (cx - pw / 2).coerceIn(0, (screenW - pw).coerceAtLeast(0))
            lp.topMargin = (cy - ph / 2).coerceIn(0, (screenH - ph).coerceAtLeast(0))
            instructionPill.layoutParams = lp
            instructionPillPlaced = true
            applyInstructionPillVisibility()
        }

        // Case 1: centered, if it clears the card horizontally by a buffer.
        val centeredTop = centerY - naturalH / 2
        val centeredBottom = centerY + naturalH / 2
        val verticallyOverlapsCard = centeredBottom > cardTop && centeredTop < cardBottom
        val clearsCard = if (cardOnLeft)
            centerX - naturalW / 2 >= cardRight + pillBuffer
        else
            centerX + naturalW / 2 <= cardLeft - pillBuffer
        if (!verticallyOverlapsCard || clearsCard) {
            place(centerX, centerY, screenW)
            return
        }

        // Case 2: the larger of the bands above / below the card, if it fits.
        val spaceAbove = cardTop
        val spaceBelow = effBottom - cardBottom
        val useTop = spaceAbove >= spaceBelow
        val band = if (useTop) spaceAbove else spaceBelow
        if (band >= naturalH + 2 * pillBuffer) {
            val cy = if (useTop) cardTop / 2 else (cardBottom + effBottom) / 2
            place(centerX, cy, screenW - 2 * pillBuffer)
            return
        }

        // Case 3: the gap beside the card, between its inner edge and the
        // opposite screen edge.
        val gapStart = if (cardOnLeft) cardRight else 0
        val gapEnd = if (cardOnLeft) screenW else cardLeft
        place((gapStart + gapEnd) / 2, centerY, (gapEnd - gapStart) - 2 * pillBuffer)
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
