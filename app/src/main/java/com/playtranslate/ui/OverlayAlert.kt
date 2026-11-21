package com.playtranslate.ui

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import com.playtranslate.PlayTranslateApplication
import com.playtranslate.R
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.OverlayPlacement
import com.playtranslate.overlayThemedContext
import com.playtranslate.themeColor

/**
 * A reusable alert dialog that can be attached either to a capture-overlay
 * window (via the (context, overlayHost, wm, displayId) [Builder] constructor
 * + [Builder.showAsOverlay]) or above whichever PlayTranslate surface is on
 * top — a showing dialog's own window, else the foregrounded activity (via
 * [Builder.show]). Matches the visual style of the floating icon hide
 * confirmation dialog.
 *
 * The foreground-attached path detaches itself when its host activity pauses
 * (sub-Activity nav, app backgrounded, etc.) so the alert dies cleanly
 * instead of becoming a hidden ghost behind the new top window. Back-press
 * dismisses the same way. The cancel handler ([Builder.addCancelButton]'s
 * onClick) fires on both user-initiated dismissal (button/scrim/back) AND
 * lifecycle pause — but receives a [DismissReason] so callers whose cancel
 * action runs control flow (advance a setup chain, etc.) can branch on USER
 * to avoid silently advancing past a decision the user never made.
 */
class OverlayAlert private constructor(
    rawContext: Context,
    private val title: String,
    private val message: String?,
    private val items: List<Item>,
    /** Where the footer begins in [items] ([Builder.beginFooter]); their
     *  count when there is none. */
    private val footerStart: Int,
    private val showAppIcon: Boolean,
    private val onCancel: ((DismissReason) -> Unit)?,
) {
    private val context: Context = overlayThemedContext(rawContext)

    data class ButtonConfig(
        val label: String,
        val color: Int,
        val textColor: Int,
        /** Optional drawable shown at the LEFT of the button label (e.g. a download
         *  icon), tinted to [textColor]. */
        val leadingIconRes: Int? = null,
        /** Non-null draws the button as an outline of this colour with no
         *  fill, [color] aside ([Builder.addOutlinedButton]). */
        val outlineColor: Int? = null,
        /** The icon stands beside the label, the two centred together as
         *  one, at the label's size, where [leadingIconRes] alone puts it
         *  at the button's edge ([Builder.addOutlinedButton]). */
        val iconWithLabel: Boolean = false,
        val onClick: () -> Unit,
    )

    /** One entry of the card under the message, in the order the builder
     *  was given them: a button, or a label that heads the buttons after it
     *  ([Builder.addSectionLabel]). */
    private sealed interface Item {
        class Button(val config: ButtonConfig) : Item
        class Label(val text: String) : Item
    }

    class Builder(rawContext: Context) {
        private val context: Context = overlayThemedContext(rawContext)
        private var title = ""
        private var message: String? = null
        private val items = mutableListOf<Item>()
        /** Set by [beginFooter]: the index in [items] its buttons start at. */
        private var footerStart: Int? = null
        private var showAppIcon = false
        /** Set by [addCancelButton]. Invoked on cancel-button tap, scrim
         *  tap, back-press, AND host-activity pause — all paths receive a
         *  [DismissReason] so callers with side effects can branch on USER. */
        private var onCancel: ((DismissReason) -> Unit)? = null

        /** Overlay-window path. [overlayHost] stamps the window with the
         *  active capture backend's type (TYPE_ACCESSIBILITY_OVERLAY or
         *  TYPE_APPLICATION_OVERLAY), so the alert displays on either
         *  backend — pass the host owned by the surface presenting the
         *  alert. Finish with [showAsOverlay]. The activity path uses the
         *  primary [Builder] constructor + [show] instead. */
        constructor(
            context: Context,
            overlayHost: OverlayHost,
            wm: WindowManager,
            displayId: Int,
        ) : this(context) {
            this.overlayHost = overlayHost
            this.wm = wm
            this.displayId = displayId
        }

        /** Set together by the overlay-window constructor — both null on the
         *  activity path. [showAsOverlay] requires them; [show] ignores them. */
        private var overlayHost: OverlayHost? = null
        private var wm: WindowManager? = null
        private var displayId: Int = android.view.Display.DEFAULT_DISPLAY

        fun setTitle(title: String) = apply { this.title = title }
        fun setMessage(message: String) = apply { this.message = message }

        /** Shows the circular app-icon header above the title. Off by
         *  default — on the app's utility popups the brand mark is just
         *  noise, so callers opt in only where it belongs (currently only
         *  the floating-icon Turn Off / Hide confirmation). */
        fun showIcon() = apply { this.showAppIcon = true }

        fun addButton(label: String, color: Int, textColor: Int = context.themeColor(R.attr.ptCard), leadingIconRes: Int? = null, onClick: () -> Unit) = apply {
            items.add(Item.Button(ButtonConfig(label, color, textColor, leadingIconRes, onClick = onClick)))
        }

        /** Adds a full-size button drawn as an outline, with no fill: an
         *  action that is of the alert's weight and is not one of its
         *  choices (the translation service picker's way to the Translation
         *  services page, beside the services it lists as filled buttons).
         *  The outline is the muted text colour, the app's secondary action
         *  button's, at a weight that holds its own beside a filled button:
         *  the hairline border colour left it the faintest thing on the
         *  card. Its icon stands beside its label, as one. */
        fun addOutlinedButton(label: String, leadingIconRes: Int? = null, onClick: () -> Unit) = apply {
            items.add(Item.Button(ButtonConfig(
                label,
                Color.TRANSPARENT,
                context.themeColor(R.attr.ptText),
                leadingIconRes,
                outlineColor = context.themeColor(R.attr.ptTextMuted),
                iconWithLabel = true,
                onClick = onClick,
            )))
        }

        /**
         * The buttons added from here on are the alert's footer: its
         * actions that are not among its choices (a picker's way to a
         * settings page, its Cancel), set off from what is above them by a
         * rule across the card, so none of them is read as one more of the
         * last list.
         *
         * With a footer, or with two or more headed groups
         * ([addSectionLabel]), the alert also has an arrangement for a wide
         * window (a phone on its side, a tablet), where a column of buttons
         * is taller than the screen and most of its width goes unused: the
         * card widens, the headed groups stand side by side, and the
         * footer's buttons share one row that stays on screen under the
         * rule while what is above it scrolls. In a narrow window the
         * footer's buttons are stacked at the end of the one column and
         * scroll with it, as every alert's buttons do.
         */
        fun beginFooter() = apply {
            footerStart = items.size
        }

        /** Adds a small header over the buttons added after it, in the
         *  settings pages' group-header look, for a picker whose choices
         *  fall into kinds (the translation service picker's online and
         *  offline services). Not tappable. */
        fun addSectionLabel(text: String) = apply {
            items.add(Item.Label(text))
        }

        /** Adds a styled cancel button (divider background, ptText label).
         *  [onClick] fires on cancel-button tap, scrim tap, back-press, AND
         *  host-activity pause; the [DismissReason] tells callers which.
         *  Use [DismissReason.USER] to gate side effects that should only
         *  run when the user explicitly dismissed (e.g. advancing a setup
         *  chain) — leaving [DismissReason.LIFECYCLE_PAUSE] to harmless
         *  cleanup like UI-state refresh.
         *  Action buttons (added via [addButton]) keep their own onClick
         *  and do NOT invoke [onClick] here. */
        fun addCancelButton(label: String = "Cancel", onClick: ((DismissReason) -> Unit)? = null) = apply {
            onCancel = onClick
            items.add(Item.Button(ButtonConfig(
                label,
                context.themeColor(R.attr.ptDivider),
                context.themeColor(R.attr.ptText),
                onClick = { onClick?.invoke(DismissReason.USER) },
            )))
        }

        /** Shows the alert as a capture-overlay window through the host
         *  supplied to the overlay-window constructor. */
        fun showAsOverlay(): OverlayAlert {
            val host = overlayHost ?: error(
                "OverlayAlert.showAsOverlay() requires the " +
                    "(context, overlayHost, wm, displayId) constructor"
            )
            val alert = OverlayAlert(context, title, message, items, footerStart ?: items.size, showAppIcon, onCancel)
            // wm is non-null whenever overlayHost is — both are set together
            // by the overlay-window constructor.
            alert.showAsOverlay(host, wm!!, displayId)
            return alert
        }

        /** Shows above whichever PlayTranslate surface is currently on top: a
         *  showing DialogFragment's own window if one is up (the full-screen
         *  Settings sheet and its nested dialogs included), otherwise the
         *  foregrounded activity's decorView. If no activity is resumed yet
         *  (e.g. called from MainActivity.onCreate before its first onResume),
         *  the attach is deferred to the next [Activity.onResume] via
         *  [PlayTranslateApplication.runWithForegroundActivity].
         *
         *  Detaches and invokes the cancel handler if the host activity
         *  pauses — so navigating to a sub-Activity or backgrounding the
         *  app dismisses the alert instead of leaving it hidden behind the
         *  new top window. Back-press dismisses the same way. */
        fun show(): OverlayAlert {
            val alert = OverlayAlert(context, title, message, items, footerStart ?: items.size, showAppIcon, onCancel)
            PlayTranslateApplication.runWithForegroundActivity { activity ->
                if (alert.dismissed || activity.isFinishing || activity.isDestroyed) return@runWithForegroundActivity
                alert.attachToForeground(activity)
            }
            return alert
        }

        /** Shows attached to the decorView of [dialog]'s own window, for an
         *  explicit dialog target. [show] now auto-detects a showing
         *  DialogFragment and layers above it, so prefer [show] unless you
         *  must pin the alert to a specific [dialog] not reachable from the
         *  foreground activity's fragment tree. */
        fun showInDialog(dialog: Dialog): OverlayAlert {
            val alert = OverlayAlert(context, title, message, items, footerStart ?: items.size, showAppIcon, onCancel)
            alert.showInDialog(dialog)
            return alert
        }

        /** Shows as an in-window child of [parent] — the floating workspace's
         *  modal layer ([com.playtranslate.ui.OverlayWorkspace]). In-window
         *  rather than a sibling overlay window because MediaProjection's QTI
         *  clamp dims a sibling to ~80% and it steals the host window's taps
         *  (see [FontSizeRangePopover]). If the parent's window is torn down
         *  while the alert is up (workspace dismissal), the cancel handler
         *  fires with [DismissReason.LIFECYCLE_PAUSE]. */
        fun showInParent(parent: ViewGroup): OverlayAlert {
            val alert = OverlayAlert(context, title, message, items, footerStart ?: items.size, showAppIcon, onCancel)
            alert.showInParent(parent)
            return alert
        }
    }

    private var scrim: FrameLayout? = null
    private var dismissAction: (() -> Unit)? = null

    /** True while the alert is actually attached to a window. False after
     *  dismiss — and, crucially, after a [Builder.showAsOverlay] whose
     *  window add FAILED: callers that gate behavior on an alert being
     *  visible (e.g. pausing live cycles beneath it) must check this
     *  rather than assume construction implies presence. */
    val isShowing: Boolean get() = scrim != null
    private var attachedActivity: Activity? = null
    private var lifecycleCallback: Application.ActivityLifecycleCallbacks? = null
    private var backPressedCallback: OnBackPressedCallback? = null
    private var dismissed = false

    // ── The card ─────────────────────────────────────────────────────────

    /** Whether this alert has an arrangement of its own for a wide window
     *  ([Builder.beginFooter]): it has a footer, or two or more headed
     *  groups to stand side by side. Every other alert is the one narrow
     *  column whatever the window. */
    private val adaptsToWidth: Boolean =
        footerStart < items.size || items.subList(0, footerStart).count { it is Item.Label } >= 2

    /** Whether the card on show is the wide arrangement. */
    private var arrangedWide = false

    /** Whether a window with [availablePx] of width for the card takes the
     *  wide arrangement: room for two columns that still hold a button's
     *  label on one line. */
    private fun wideFor(availablePx: Int): Boolean =
        adaptsToWidth && availablePx >= WIDE_MIN_DP * context.resources.displayMetrics.density

    /** The card's width in a window with [availablePx] for it: the narrow
     *  card's, or for the wide arrangement as much of the wide card's as
     *  the window has past a margin on each side (a small phone on its side
     *  has a little less than the whole). */
    private fun cardWidthPx(wide: Boolean, availablePx: Int): Int {
        val dp = context.resources.displayMetrics.density
        if (!wide) return (CARD_DP * dp).toInt()
        return minOf((WIDE_CARD_DP * dp).toInt(), availablePx - (2 * WIDE_SIDE_MARGIN_DP * dp).toInt())
    }

    /**
     * The scrim with the card in it. [hostWidthPx] is the width of what the
     * scrim will fill, where the caller has it (a laid-out decor or parent);
     * without one the display's is taken, which an overlay window fills.
     * That decides the card's arrangement before anything is drawn; the
     * scrim's own width decides it again whenever it changes (the first
     * layout, a rotation under an alert that stays up), and the card is
     * built again for it.
     */
    private fun buildScrim(hostWidthPx: Int? = null): FrameLayout {
        // Full-screen scrim. Tapping it acts as a shortcut for the cancel
        // button — invokes the same onCancel handler with USER reason.
        // Order: dismiss first, then handler — matches the button path so
        // any handler that chains another dialog sees the alert already
        // gone, no overlap/flicker.
        val scrimView = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            setOnClickListener { detachAndDispatch(DismissReason.USER) }
            // The scrim spans the whole window, bars included (edge-to-edge
            // activities, FLAG_LAYOUT_IN_SCREEN overlays), so the room the
            // card may fill is the window minus the bars and any cutout:
            // a card at full height would otherwise end under the
            // navigation bar, buttons first.
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        val available = hostWidthPx?.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        arrangedWide = wideFor(available)
        addCard(scrimView, arrangedWide, available, animate = true)
        if (adaptsToWidth) {
            scrimView.addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left == oldRight - oldLeft) return@addOnLayoutChangeListener
                // Not here: the children cannot change during the layout
                // that reports the width.
                val now = right - left - v.paddingLeft - v.paddingRight
                val wide = wideFor(now)
                val card = scrimView.getChildAt(0)
                if (wide != arrangedWide || card?.layoutParams?.width != cardWidthPx(wide, now)) {
                    v.post { rearrange(scrimView) }
                }
            }
        }
        return scrimView
    }

    /** The scrim's width now asks for another card, the other arrangement
     *  or the same one at another width: built again in place, with no
     *  entrance. Nothing of the card is state (its buttons dismiss it), so
     *  a new one is the same alert; and a card is built for its width (a
     *  button that centres its icon with its label places them by it). */
    private fun rearrange(scrimView: FrameLayout) {
        if (dismissed || scrim !== scrimView) return
        val available = scrimView.width - scrimView.paddingLeft - scrimView.paddingRight
        val wide = wideFor(available)
        val card = scrimView.getChildAt(0)
        if (wide == arrangedWide && card?.layoutParams?.width == cardWidthPx(wide, available)) return
        arrangedWide = wide
        scrimView.removeAllViews()
        addCard(scrimView, wide, available, animate = false)
    }

    private fun addCard(scrimView: FrameLayout, wide: Boolean, availablePx: Int, animate: Boolean) {
        val dp = context.resources.displayMetrics.density
        val width = cardWidthPx(wide, availablePx)
        val card = if (wide) buildWideCard(width) else buildColumnCard(width)
        scrimView.addView(
            card,
            FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
                topMargin = (16 * dp).toInt()
                bottomMargin = (16 * dp).toInt()
            },
        )
        if (!animate) return
        card.alpha = 0f
        card.scaleX = 0.9f
        card.scaleY = 0.9f
        card.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(150)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** The card's chrome: surface, hairline, corners. */
    private fun cardChrome(): GradientDrawable {
        val dp = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(context.themeColor(R.attr.ptSurface))
            setStroke((1 * dp).toInt(), context.themeColor(R.attr.ptDivider))
            cornerRadius = 16 * dp
        }
    }

    private fun column(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /**
     * The one-column card, every alert's in a narrow window: a scroll view
     * wearing the card's chrome around one column holding the icon, the
     * title, the message and the buttons. A card that fits lays out at its
     * natural size with nothing to scroll; one the window is too short for
     * (a large font, a long message, a picker's column of buttons) scrolls
     * as a whole, buttons included. Before this only the part above the
     * buttons scrolled, in a weighted view inside the column, so a card
     * tall from its buttons alone still ran off the bottom. A footer
     * ([Builder.beginFooter]) is the column's end here, its buttons stacked
     * under their rule.
     */
    private fun buildColumnCard(cardWidthPx: Int): View {
        val dp = context.resources.displayMetrics.density
        val stroke = (1 * dp).toInt()
        // A button spans the column: the card less its side padding.
        val buttonWidth = cardWidthPx - 2 * (24 * dp).toInt()
        val card = ScrollView(context).apply {
            background = cardChrome()
            // The column scrolls over the background, so it is clipped
            // just inside the hairline (clipToPadding, the default) rather
            // than painting a button across it at the top or bottom edge;
            // the column's own side padding keeps it clear of the corners.
            setPadding(0, stroke, 0, stroke)
        }
        val body = column().apply {
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt())
            gravity = Gravity.CENTER_HORIZONTAL
            // Prevent clicks from passing through to scrim
            setOnClickListener { }
        }
        card.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addHeader(body)
        val hasFooter = footerStart < items.size
        addStacked(body, items.subList(0, footerStart), endsColumn = !hasFooter, buttonWidth)
        if (hasFooter) {
            body.addView(ruleView())
            addStacked(body, items.subList(footerStart, items.size), endsColumn = true, buttonWidth)
        }
        return card
    }

    /**
     * The wide card ([Builder.beginFooter]): the title and the message
     * across it, the headed groups side by side under them, and the footer's
     * buttons in one row under a rule. Only what is above the rule scrolls:
     * the footer is two buttons of height whatever the lists hold, so it is
     * kept on screen and the scroll view takes what room is left (the
     * weighted child of the card's column, which is what shrinks when the
     * whole does not fit).
     */
    private fun buildWideCard(cardWidthPx: Int): View {
        val dp = context.resources.displayMetrics.density
        val stroke = (1 * dp).toInt()
        val side = (24 * dp).toInt()
        val contentWidth = cardWidthPx - 2 * side
        val hasFooter = footerStart < items.size
        val card = column().apply {
            background = cardChrome()
            // The scroll view is a child here, not the card itself: its
            // scrollbar runs up the card's edge into the corner's curve.
            clipToOutline = true
            setPadding(0, stroke, 0, stroke)
            // Prevent clicks from passing through to scrim
            setOnClickListener { }
        }
        val body = column().apply {
            setPadding(side, (24 * dp).toInt(), side, ((if (hasFooter) 8 else 16) * dp).toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val scroll = ScrollView(context).apply {
            // What does not fit is cut off by the footer: faded out there,
            // it reads as more to scroll to, where a button's cut edge
            // against the rule reads as a fault.
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength((24 * dp).toInt())
        }
        scroll.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addHeader(body)

        // What comes before the first header keeps the card's width; from
        // there each header starts a column of its own.
        val main = items.subList(0, footerStart)
        val firstHeader = main.indexOfFirst { it is Item.Label }.let { if (it < 0) main.size else it }
        addStacked(body, main.subList(0, firstHeader), endsColumn = firstHeader == main.size, contentWidth)
        val groups = ArrayList<MutableList<Item>>()
        for (item in main.subList(firstHeader, main.size)) {
            if (item is Item.Label) groups.add(ArrayList())
            groups.last().add(item)
        }
        if (groups.isNotEmpty()) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
            }
            val columnGap = (16 * dp).toInt()
            val columnWidth = (contentWidth - (groups.size - 1) * columnGap) / groups.size
            groups.forEachIndexed { i, group ->
                val col = column()
                addStacked(col, group, endsColumn = true, columnWidth)
                row.addView(
                    col,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (i > 0) marginStart = columnGap
                    },
                )
            }
            body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        if (hasFooter) {
            val footer = column().apply { setPadding(side, 0, side, (16 * dp).toInt()) }
            footer.addView(ruleView())
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
            }
            val actions = items.subList(footerStart, items.size).filterIsInstance<Item.Button>()
            val actionGap = (8 * dp).toInt()
            val actionWidth = (contentWidth - (actions.size - 1) * actionGap) / actions.size.coerceAtLeast(1)
            actions.forEachIndexed { i, item ->
                row.addView(
                    buttonView(item.config, actionWidth),
                    // One height for the row, the tallest label's.
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (i > 0) marginStart = actionGap
                    },
                )
            }
            footer.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            card.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return card
    }

    /** The icon (when asked for), the title and the message, into [body]. */
    private fun addHeader(body: LinearLayout) {
        val dp = context.resources.displayMetrics.density
        // App icon in a circle ([appIconCircle]). Shown only when the caller
        // opted in via Builder.showIcon() — off by default because the app's
        // utility popups don't need the brand mark.
        if (showAppIcon) {
            val circleSize = (56 * dp).toInt()
            body.addView(appIconCircle(context, circleSize).apply {
                layoutParams = LinearLayout.LayoutParams(circleSize, circleSize).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = (16 * dp).toInt()
                }
            })
        }

        // Title
        body.addView(TextView(context).apply {
            text = title
            setTextColor(context.themeColor(R.attr.ptText))
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
            body.addView(TextView(context).apply {
                text = message
                setTextColor(context.themeColor(R.attr.ptTextMuted))
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
    }

    /** [list]'s labels and buttons into [column], one under another, 8 dp
     *  apart. The last of them keeps its gap unless it ends the column
     *  ([endsColumn]); a header that follows something has twice the gap
     *  over it (its own and the one before it's), so it reads with what is
     *  under it. [widthPx] is the column's, which each button spans. */
    private fun addStacked(column: LinearLayout, list: List<Item>, endsColumn: Boolean, widthPx: Int) {
        val dp = context.resources.displayMetrics.density
        for ((idx, item) in list.withIndex()) {
            val view = when (item) {
                is Item.Label -> labelView(item.text)
                is Item.Button -> buttonView(item.config, widthPx)
            }
            column.addView(
                view,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (item is Item.Label && idx > 0) topMargin = (8 * dp).toInt()
                    if (!(endsColumn && idx == list.size - 1)) bottomMargin = (8 * dp).toInt()
                },
            )
        }
    }

    /** A group's header, in the settings pages' group-header look. */
    private fun labelView(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(context.themeColor(R.attr.ptTextMuted))
        textSize = 11f
        isAllCaps = true
        letterSpacing = 0.12f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        gravity = Gravity.START
    }

    /** The hairline over a footer, with the same room on both sides of it
     *  (the item before it brings 8 dp of its own). */
    private fun ruleView(): View {
        val dp = context.resources.displayMetrics.density
        return View(context).apply {
            setBackgroundColor(context.themeColor(R.attr.ptDivider))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (1 * dp).toInt().coerceAtLeast(1),
            ).apply {
                topMargin = (8 * dp).toInt()
                bottomMargin = (16 * dp).toInt()
            }
        }
    }

    /** One of the card's buttons, [widthPx] wide where it is placed. */
    private fun buttonView(cfg: ButtonConfig, widthPx: Int): Button {
        val dp = context.resources.displayMetrics.density
        val hPad = (20 * dp).toInt()
        val vPad = (10 * dp).toInt()
        return Button(context).apply {
            text = cfg.label
            setTextColor(cfg.textColor)
            textSize = 14f
            isAllCaps = false
            setPadding(hPad, vPad, hPad, vPad)
            val outline = cfg.outlineColor
            if (outline != null) {
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke(Math.round(1.5f * dp).coerceAtLeast(1), outline)
                    cornerRadius = 8 * dp
                }
            } else if (cfg.color != Color.TRANSPARENT) {
                background = GradientDrawable().apply {
                    setColor(cfg.color)
                    cornerRadius = 8 * dp
                }
            } else {
                setBackgroundColor(Color.TRANSPARENT)
            }
            cfg.leadingIconRes?.let { iconRes ->
                if (cfg.iconWithLabel) {
                    // A button's own gravity centres its label and leaves
                    // the icon at the edge. For the two to stand together
                    // in the middle, the icon takes the label's size and
                    // the content starts where the pair, measured, is
                    // centred in the width the button is given; a label too
                    // long for that keeps the plain padding and wraps
                    // beside the icon.
                    val size = (18 * dp).toInt()
                    val gap = (8 * dp).toInt()
                    val icon = ContextCompat.getDrawable(context, iconRes)?.mutate() ?: return@let
                    icon.setBounds(0, 0, size, size)
                    setCompoundDrawablesRelative(icon, null, null, null)
                    compoundDrawablePadding = gap
                    val pair = size + gap + paint.measureText(cfg.label).toInt()
                    val side = maxOf(hPad, (widthPx - pair) / 2)
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    setPaddingRelative(side, vPad, side, vPad)
                } else {
                    setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
                    compoundDrawablePadding = (8 * dp).toInt()
                }
                TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(cfg.textColor))
            }
            setOnClickListener {
                dismiss()
                cfg.onClick()
            }
        }
    }

    private fun showAsOverlay(overlayHost: OverlayHost, wm: WindowManager, displayId: Int) {
        val scrimView = buildScrim()
        // addOverlayWindow re-stamps params.type with the active backend's
        // window type, so the type given here is just a placeholder.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        if (!overlayHost.addOverlayWindow(scrimView, wm, params, displayId, OverlayPlacement.Content)) return
        ViewCompat.requestApplyInsets(scrimView)
        scrim = scrimView
        dismissAction = { overlayHost.removeOverlayWindow(scrimView) }
    }

    /**
     * Attach above whatever PlayTranslate surface is currently on top of
     * [activity] — a showing DialogFragment's own window if one is up (the
     * full-screen Settings sheet, its nested pickers, the Anki sheet…),
     * otherwise the activity's decorView. Attaching to the activity decor
     * while a dialog window sits above it z-orders the alert behind the dialog
     * and leaves it invisible; resolving the top dialog here ([topmostDialogWindow])
     * is what lets a single [Builder.show] cover both cases without callers
     * choosing between [Builder.show] and [Builder.showInDialog].
     */
    private fun attachToForeground(activity: Activity) {
        val topDialog = topmostDialogWindow(activity)
        attachToDecor((topDialog?.window?.decorView ?: activity.window.decorView) as ViewGroup)
        attachedActivity = activity

        // Detach + cancel if the host activity leaves the foreground —
        // sub-Activity navigation, app backgrounding, anything that fires
        // onPause. A dialog pauses together with its host activity, so this
        // covers the dialog-host case too. Without it the alert sits as an
        // invisible child of a covered window. See OverlayAlert class doc.
        val app = activity.application
        val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(a: Activity) {
                if (a === attachedActivity) detachAndDispatch(DismissReason.LIFECYCLE_PAUSE)
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        app.registerActivityLifecycleCallbacks(lifecycle)
        lifecycleCallback = lifecycle

        // If the host window is torn down independently of our own dismiss
        // (e.g. the dialog we're parented to is dismissed) our scrim leaves
        // the hierarchy without onActivityPaused firing. Treat that as a
        // non-user dismissal so the lifecycle callback is unregistered and any
        // control-flow caller branches on LIFECYCLE_PAUSE, not USER. Guarded
        // by [dismissed] in detachAndDispatch so our own removeView — which
        // also fires this — doesn't re-dispatch.
        scrim?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                detachAndDispatch(DismissReason.LIFECYCLE_PAUSE)
            }
        })

        // Back-press dismisses the alert, matching scrim-tap. When parented to
        // a dialog window, back is dispatched to that window's dispatcher —
        // register there so back closes the alert rather than the sheet
        // beneath it. Activity-host alerts use the activity dispatcher.
        val backDispatcher = (topDialog as? ComponentDialog)?.onBackPressedDispatcher
            ?: (activity as? ComponentActivity)?.onBackPressedDispatcher
        backDispatcher?.let { dispatcher ->
            val backCb = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { detachAndDispatch(DismissReason.USER) }
            }
            dispatcher.addCallback(backCb)
            backPressedCallback = backCb
        }
    }

    /** Attaches to the decorView of [dialog]'s window; a no-op if the
     *  dialog has already lost its window. */
    private fun showInDialog(dialog: Dialog) {
        val decor = dialog.window?.decorView as? ViewGroup ?: return
        attachToDecor(decor)
    }

    /** Attaches as a child of [parent] (the workspace modal layer). A detach
     *  of the scrim we didn't initiate — the parent's window tearing down —
     *  dispatches LIFECYCLE_PAUSE, mirroring [attachToForeground]'s listener;
     *  our own removeView is guarded by [dismissed] in [detachAndDispatch]. */
    private fun showInParent(parent: ViewGroup) {
        attachToDecor(parent)
        scrim?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                detachAndDispatch(DismissReason.LIFECYCLE_PAUSE)
            }
        })
    }

    private fun attachToDecor(decor: ViewGroup) {
        val scrimView = buildScrim(decor.width)
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        decor.addView(scrimView, lp)
        ViewCompat.requestApplyInsets(scrimView)
        scrim = scrimView
        dismissAction = { try { decor.removeView(scrimView) } catch (_: Exception) {} }
    }

    private fun detachAndDispatch(reason: DismissReason) {
        // Idempotent: scrim-tap / back / activity-pause / the
        // detach-listener can all race to dismiss. First one wins; the rest
        // (including the removeView that fires the detach listener) no-op so
        // onCancel isn't invoked twice.
        if (dismissed) return
        dismiss()
        onCancel?.invoke(reason)
    }

    fun dismiss() {
        dismissed = true
        dismissAction?.invoke()
        dismissAction = null
        scrim = null
        lifecycleCallback?.let {
            attachedActivity?.application?.unregisterActivityLifecycleCallbacks(it)
        }
        backPressedCallback?.remove()
        lifecycleCallback = null
        backPressedCallback = null
        attachedActivity = null
    }

    private companion object {
        /** Every alert's card in a narrow window, and an alert's with no
         *  wide arrangement in any. */
        const val CARD_DP = 280

        /** The wide arrangement's card at its widest
         *  ([Builder.beginFooter]): two columns of the narrow card's own
         *  content width, and the gap between. */
        const val WIDE_CARD_DP = 560

        /** What the wide card keeps clear on each side of it in a window
         *  that has less than its widest for it. */
        const val WIDE_SIDE_MARGIN_DP = 16

        /** The width a window gives the card from which the wide
         *  arrangement is taken. A phone on its side has it, the smallest
         *  included (about 590 dp once its bars are taken off), where the
         *  card is then a little under its widest: two columns of some
         *  210 dp, which still hold a service's name on one line. A phone
         *  held upright (430 dp at most) does not. */
        const val WIDE_MIN_DP = 520
    }
}
