package com.playtranslate.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import com.playtranslate.R
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.overlay.OverlayEnvironment
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.OverlayPlacement
import com.playtranslate.overlay.OwnWindows
import com.playtranslate.overlayThemedContext
import com.playtranslate.themeColor
import com.playtranslate.translation.TranslationError
import com.playtranslate.translation.TranslationErrorKey
import com.playtranslate.translation.TranslationErrorPresenter

/**
 * The translation-error pills on a game display: a stack at the top centre,
 * one pill per owner (a service, or the connection), each the PlayTranslate
 * mark, the message, a gear for the Translation services page
 * ([onSettings]), and a × that closes it. [com.playtranslate.translation.TranslationErrorTracker]
 * decides when an error shows and when its owner's recovery takes it down;
 * this class only draws. A newer error from an owner that already has a
 * pill replaces that pill's text ([show], [update]).
 *
 * Each pill spans most of the display's width (all but [SCREEN_MARGIN_DP]
 * on each side) and wears the floating icon menu card's look: ptBg fill,
 * the 1dp ptDivider hairline, 8dp elevation. One window holds the whole
 * stack plus [SHADOW_ROOM_DP] of padding for the shadow to fall in, since a
 * shadow can't draw outside its window. The window has to be touchable for
 * the buttons, and a touchable window takes every touch inside its bounds, so
 * while a pill is up the game doesn't get touches in that band at the top,
 * shadow room included. It goes through [OverlayHost], so clean captures
 * blank it and its removal is churn-gated like every game-display window.
 * Raw frames (the live pinhole tier) contain it, so [screenRects] joins the
 * floating icon's rect in [com.playtranslate.OverlayUiController.ownChromeRects],
 * which the OCR blackout, the pinhole change gate and the pinhole sampling
 * all honour.
 *
 * The stack lives on one captured display at a time: a pill for another
 * display joins the stack where it is, and when the stack's display leaves
 * the capture set its pills go ([withdrawFrom]), as they do when its window
 * vanishes under it (a display unplugged). The pills keep priority over the floating icon:
 * each time the icon's window goes up again, the stack's does after it
 * ([raise]). An emptied stack's window is removed, and the next pill gets a
 * fresh one; a removed view is never re-added (the churn gate may still
 * hold it).
 *
 * Main thread only.
 */
internal class TranslationErrorPills(private val overlayHost: OverlayHost) {

    private class Row(val view: View, val text: TextView)

    private class Stack(
        val displayId: Int,
        val displayContext: Context,
        val root: LinearLayout,
        val params: WindowManager.LayoutParams,
        val wm: WindowManager,
    ) {
        /** One pill per owner, in arrival order. */
        val rows = LinkedHashMap<String, Row>()
        /** Every pill's width, from the stack's last layout ([layoutStack]):
         *  the one derivation a new row, a raised row and a resized row all
         *  take. */
        var pillWidth = 0
    }

    private var stack: Stack? = null

    /** The display whose stack [raise] is replacing, while the fresh window
     *  is being added: a stack is there, its size not yet known. */
    private var raisingOn: Int? = null

    /** The display the stack is on, or null when no pill is up. */
    val displayId: Int? get() = stack?.displayId

    /** Called with the owners whose pills came down without the user
     *  closing them or their owners recovering: the stack's display left the
     *  capture set ([withdrawFrom]), or its window vanished under it.
     *  Nobody dismissed those errors, so the tracker lets each show again
     *  ([com.playtranslate.translation.TranslationErrorTracker.withdrawn]). */
    var onWithdrawn: ((owners: List<String>) -> Unit)? = null

    /** Called with a pill's owner, and the display the stack is on, when
     *  the user taps the pill's gear. The pill stays up: the controller
     *  takes it down if it goes on to open the Translation services page
     *  (it may ask first), and the tracker then shows the owner's next
     *  failure again
     *  ([com.playtranslate.translation.TranslationErrorTracker.settingsOpened]). */
    var onSettings: ((owner: String, displayId: Int) -> Unit)? = null

    /** The stack window's root: one child per pill, each [logo, message,
     *  gear, ×]. For tests, which have no other way to reach the window. */
    @VisibleForTesting
    internal val stackRoot: LinearLayout? get() = stack?.root

    /**
     * Put [error]'s pill up: a new pill at the bottom of the stack, or the
     * text of its owner's pill updated. [displayContext] is a display
     * context derived from the overlay host's own context (only that one
     * can add this host's window type); used only when there is no stack
     * yet. Returns false when the window can't be added.
     */
    fun show(displayContext: Context, displayId: Int, error: TranslationError): Boolean {
        if (update(error)) return true
        val s = stack ?: createStack(displayContext, displayId) ?: return false
        val owner = error.key.owner
        val row = buildRow(s, owner, TranslationErrorMessages.text(s.root.context, error))
        s.rows[owner] = row
        s.root.addView(row.view)
        if (s.rows.size > 1) fadeIn(row.view)
        return true
    }

    /** Make [error]'s owner's pill say [error], if that owner has a pill
     *  up; true if it has. Never adds a pill. */
    fun update(error: TranslationError): Boolean {
        val s = stack ?: return false
        val row = s.rows[error.key.owner] ?: return false
        val message = TranslationErrorMessages.text(s.root.context, error)
        // The same words again (a live pass failing the same way) leave the
        // view alone rather than relayout the stack.
        if (row.text.text.toString() != message) row.text.text = message
        return true
    }

    /** Take down the pills of [owners]; the window goes with the last. */
    fun hide(owners: Collection<String>) {
        val s = stack ?: return
        var removed = false
        for (owner in owners) {
            val row = s.rows.remove(owner) ?: continue
            s.root.removeView(row.view)
            removed = true
        }
        if (removed && s.rows.isEmpty()) removeStack()
    }

    /** Take every pill down (capture ended, backend teardown). */
    fun removeAll() {
        if (stack != null) removeStack()
    }

    /**
     * Put the stack back above this app's other windows on [displayId]: a
     * window added after it sits on top, and the pills keep priority over
     * the floating icon, whose window goes up again on every install and
     * re-raise (Gilad, 2026-09-27). A fresh window takes the same pills,
     * added before the old one is removed so no frame goes without them;
     * the old one leaves through the churn gate like every removal. Inside a
     * clean capture the fresh window goes up blanked and comes back with the
     * capture's restore, as every window added then does ([OverlayHost]).
     */
    fun raise(displayId: Int) {
        val old = stack?.takeIf { it.displayId == displayId } ?: return
        stack = null
        raisingOn = displayId
        val fresh = try {
            createStack(old.displayContext, displayId, fadeIn = false)
        } finally {
            raisingOn = null
        }
        if (fresh == null) {
            stack = old
            return
        }
        for ((owner, row) in old.rows) {
            old.root.removeView(row.view)
            fresh.root.addView(row.view)
            fresh.rows[owner] = row
        }
        // The fresh window was laid out before it held the rows.
        applyPillWidth(fresh)
        overlayHost.removeOverlayWindow(old.root)
    }

    /** [displayId] left the capture set: the stack, if it is there, goes;
     *  see [onWithdrawn]. */
    fun withdrawFrom(displayId: Int) {
        val s = stack?.takeIf { it.displayId == displayId } ?: return
        val owners = s.rows.keys.toList()
        removeStack()
        onWithdrawn?.invoke(owners)
    }

    /**
     * Screen rect of the stack on [displayId], for the OCR blackout and the
     * pinhole mode: the whole window, pills and the room their shadows fall
     * in (a shadow darkening a box's pinholes reads as its game content
     * changing). Empty when the stack is elsewhere or not yet laid out (a
     * window that hasn't been laid out hasn't been drawn, so no frame
     * contains it). Main thread.
     */
    fun screenRects(displayId: Int): List<Rect> {
        val s = stack?.takeIf { it.displayId == displayId } ?: return emptyList()
        val root = s.root
        if (!root.isLaidOut || root.width <= 0 || root.height <= 0) return emptyList()
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        return listOf(Rect(loc[0], loc[1], loc[0] + root.width, loc[1] + root.height))
    }

    /** Where a window placed just below the stack on [displayId] goes (the
     *  no-text pill): the stack window's y plus its height and a gap, or null
     *  when there is no stack there, or none laid out yet (its height isn't
     *  known until then; its first layout invalidates the display's layout,
     *  so whoever asked is asked again). Reads the height, not
     *  [View.isLaidOut]: inside that first layout's callback the frame is
     *  set but isLaidOut isn't yet. */
    fun belowStackY(displayId: Int): Int? {
        val s = stack?.takeIf { it.displayId == displayId } ?: return null
        if (s.root.height <= 0) return null
        return s.params.y + s.root.height + gapPx(s.displayContext)
    }

    /** A stack is on [displayId] whose size is not known yet: just shown and
     *  not laid out, or being replaced by [raise]. A window placed below it
     *  stays where it is meanwhile, rather than jump onto the stack's line
     *  for the frame until the stack's first layout (which lays the display
     *  out again). */
    fun stackPendingOn(displayId: Int): Boolean =
        raisingOn == displayId || stack?.let { it.displayId == displayId && it.root.height <= 0 } == true

    /**
     * The stack window's layout ([OverlayPlacement.Placed]), run by the host
     * when it is added and whenever the display changes: the first pill's
     * top edge on the pills' line ([topLinePx]), the window [SHADOW_ROOM_DP]
     * above it (never above the display's edge), and every pill as wide as
     * the clear span ([clearSpan]) less [SCREEN_MARGIN_DP] a side, centred in
     * it. The rows' widths are view-level, so they are written only when
     * they differ.
     */
    private fun layoutStack(s: Stack, env: OverlayEnvironment, p: WindowManager.LayoutParams) {
        val dp = s.displayContext.resources.displayMetrics.density
        val room = (SHADOW_ROOM_DP * dp).toInt()
        val span = clearSpan(env)
        s.pillWidth = ((span.last - span.first) - 2 * SCREEN_MARGIN_DP * dp).toInt()
            .coerceAtLeast((MIN_PILL_WIDTH_DP * dp).toInt())
        p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        p.width = s.pillWidth + 2 * room
        p.x = centreOffsetPx(env)
        p.y = (topLinePx(env, dp) - room).coerceAtLeast(0)
        applyPillWidth(s)
    }

    /** Give every row the stack's [Stack.pillWidth], where it differs. */
    private fun applyPillWidth(s: Stack) {
        for (row in s.rows.values) {
            val lp = row.view.layoutParams ?: continue
            if (lp.width == s.pillWidth) continue
            lp.width = s.pillWidth
            row.view.layoutParams = lp
        }
    }

    private fun createStack(displayContext: Context, displayId: Int, fadeIn: Boolean = true): Stack? {
        val wm = OwnWindows.manager(displayContext) ?: return null
        val themed = overlayThemedContext(displayContext)
        val dp = themed.resources.displayMetrics.density
        val shadowRoom = (SHADOW_ROOM_DP * dp).toInt()
        val root = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // The gap between pills is a divider, so removing the first pill
            // never leaves a margin at the top of the stack.
            dividerDrawable = GradientDrawable().apply { setSize(0, (GAP_DP * dp).toInt()) }
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            // Room for the pills' shadows, which a parent draws outside each
            // pill's bounds and the window's edge would otherwise cut off.
            setPadding(shadowRoom, shadowRoom, shadowRoom, shadowRoom)
            clipToPadding = false
            clipChildren = false
        }
        val params = WindowManager.LayoutParams(
            // Pinned by [layoutStack], not WRAP_CONTENT: the platform's own
            // width for a wrap window can't be trusted on a multi-display
            // device. On the Thor (2026-09-26, dumpsys) this window,
            // WRAP_CONTENT on the 1920px screen, came out 1240px wide, the
            // second screen's width, and cut the pills off on both sides; the
            // same delegation that OverlayHost.addOverlayWindow pins
            // full-screen windows against. The height stays WRAP_CONTENT: a
            // stack of pills is far shorter than any screen, so no wrong
            // display's height can clip it.
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayHost.windowType,
            // Touchable for the buttons; NOT_TOUCH_MODAL passes every touch
            // outside the pills to the game; never focusable, so a controller
            // keeps driving the game.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        val s = Stack(displayId, displayContext, root, params, wm)
        // Laid out at its first layout and at each new height (a pill added,
        // removed or rewrapped): a window placed below the stack (the no-text
        // pill) reads its height, so the display is laid out again.
        root.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (stack === s && bottom - top != oldBottom - oldTop) overlayHost.invalidateLayout(displayId)
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                // Something else removed the window (a display unplugged,
                // the host's own sweep). Our own removals clear [stack]
                // first, so this only fires for the stack still current
                // when its window vanished, and nobody closed its pills.
                if (stack !== s) return
                stack = null
                onWithdrawn?.invoke(s.rows.keys.toList())
            }
        })
        val placement = OverlayPlacement.Placed { env, p -> layoutStack(s, env, p) }
        if (!overlayHost.addOverlayWindow(root, wm, params, displayId, placement)) return null
        stack = s
        if (fadeIn) fadeIn(root)
        return s
    }

    private fun removeStack() {
        val s = stack ?: return
        stack = null
        overlayHost.removeOverlayWindow(s.root)
    }

    private fun buildRow(s: Stack, owner: String, message: String): Row {
        val ctx = s.root.context
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(ROW_HEIGHT_DP)
            layoutParams = LinearLayout.LayoutParams(
                s.pillWidth, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            // The floating icon menu card's recipe (FloatingIconMenu.menuCard):
            // ptBg fill, the 1dp ptDivider hairline, 8dp elevation. The
            // hairline is a 7%-alpha line, which only reads on the dark ptBg
            // fill, not on a lighter one. The background's rounded outline is
            // what the shadow follows.
            background = GradientDrawable().apply {
                setColor(ctx.themeColor(R.attr.ptBg))
                setStroke(px(1).coerceAtLeast(1), ctx.themeColor(R.attr.ptDivider))
                cornerRadius = ROW_HEIGHT_DP * dp / 2f
            }
            elevation = ELEVATION_DP * dp
        }
        row.addView(appIconCircle(ctx, px(LOGO_DP)).apply {
            layoutParams = LinearLayout.LayoutParams(px(LOGO_DP), px(LOGO_DP)).apply {
                marginStart = px(LOGO_INSET_DP)
                topMargin = px(LOGO_INSET_DP)
                bottomMargin = px(LOGO_INSET_DP)
            }
            // Decorative: TalkBack reads the message and the two buttons,
            // not an unlabeled image.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        })
        val text = TextView(ctx).apply {
            text = message
            setTextColor(ctx.themeColor(R.attr.ptText))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            // Everything between the logo and the gear.
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPaddingRelative(px(TEXT_PAD_START_DP), px(4), px(TEXT_PAD_END_DP), px(4))
            // TalkBack reads a pill when it appears.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        row.addView(text)
        // Rows never change display: a raise moves them to a stack on the
        // same one.
        val displayId = s.displayId
        row.addView(button(ctx, R.drawable.ic_settings, R.string.settings_cell_translation_services) {
            // "Try again": the controller closes the pill if the user goes
            // on to the settings (see onSettings).
            onSettings?.invoke(owner, displayId)
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = px(BUTTON_GAP_DP)
        })
        row.addView(button(ctx, R.drawable.ic_close, R.string.cd_close) {
            // The error still counts as shown: closing it is "seen", not
            // "try again" (see TranslationErrorTracker).
            hide(listOf(owner))
        })
        return Row(row, text)
    }

    /** One of a pill's two buttons, the gear and the ×: a square target as
     *  tall as the pill around a [GLYPH_DP] glyph, muted, with a borderless
     *  ripple. */
    private fun button(
        ctx: Context,
        @DrawableRes icon: Int,
        @StringRes description: Int,
        onClick: () -> Unit,
    ): ImageView {
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        return ImageView(ctx).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(ctx.themeColor(R.attr.ptTextMuted))
            contentDescription = ctx.getString(description)
            layoutParams = LinearLayout.LayoutParams(px(BUTTON_DP), px(BUTTON_DP))
            val pad = px((BUTTON_DP - GLYPH_DP) / 2)
            setPadding(pad, pad, pad, pad)
            val ripple = TypedValue()
            if (ctx.theme.resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless, ripple, true,
                )) {
                setBackgroundResource(ripple.resourceId)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun fadeIn(view: View) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val dp = view.resources.displayMetrics.density
        view.alpha = 0f
        view.translationY = -ENTER_SLIDE_DP * dp
        view.animate().alpha(1f).translationY(0f).setDuration(ENTER_MS).start()
    }

    private fun gapPx(context: Context): Int =
        (GAP_DP * context.resources.displayMetrics.density).toInt()

    companion object {
        internal const val ROW_HEIGHT_DP = 48
        internal const val LOGO_DP = 28
        /** Around the logo on three sides: the taller pill keeps the logo's
         *  size and grows this buffer, so the circle stays centred in the
         *  pill's rounded end. */
        private const val LOGO_INSET_DP = (ROW_HEIGHT_DP - LOGO_DP) / 2
        private const val TEXT_PAD_START_DP = 10
        private const val TEXT_PAD_END_DP = 2
        /** The gear's and the ×'s square target. */
        internal const val BUTTON_DP = ROW_HEIGHT_DP
        /** Between the gear and the × (Gilad, 2026-09-28). */
        internal const val BUTTON_GAP_DP = 8
        private const val GLYPH_DP = 18
        /** The floating icon menu's screen margin. */
        internal const val SCREEN_MARGIN_DP = 16
        private const val MIN_PILL_WIDTH_DP = 200
        internal const val ELEVATION_DP = 8
        /** Window padding around the pills for their shadow: an 8dp-elevated
         *  surface near the top of the screen, where the light sits, casts
         *  roughly this far on every side. */
        internal const val SHADOW_ROOM_DP = 10
        private const val GAP_DP = 8
        private const val TOP_MARGIN_DP = 8
        private const val ENTER_SLIDE_DP = 8
        private const val ENTER_MS = 180L

        /** API 29's line: a fixed offset, since there are no insets to place
         *  against there (the pills keep the platform's frame below R). */
        private const val LEGACY_TOP_DP = 40

        /**
         * The line the pills hang from on a display: the first error pill's
         * top edge, and the no-text pill's when no error pill is up (one line
         * for both, Gilad, 2026-10-11). [TOP_MARGIN_DP] below the bar room
         * ([OverlayEnvironment.barRoom]: the status bar's room whether or not
         * it is showing, and the cutout): a game that hides its bars can
         * reveal them with a swipe, and a revealed bar must not land on a
         * pill. On API 29, [LEGACY_TOP_DP].
         */
        fun topLinePx(env: OverlayEnvironment, density: Float): Int =
            if (env.insetsKnown) env.barRoom.top + (TOP_MARGIN_DP * density).toInt()
            else (LEGACY_TOP_DP * density).toInt()

        /** The horizontal span the pills stay inside: the display less the
         *  bar room on each side, a side navigation bar's room (shown or not)
         *  and a side cutout, on both backends. Below the bars one would
         *  cover a pill's end and its ×; above them a pill would sit over
         *  the bar's buttons and take their touches. */
        fun clearSpan(env: OverlayEnvironment): IntRange =
            env.barRoom.left..(env.geometry.width - env.barRoom.right)

        /** The x of a TOP|CENTER_HORIZONTAL window centred in [clearSpan]:
         *  the gravity's offset from the display's centre. */
        fun centreOffsetPx(env: OverlayEnvironment): Int =
            (env.barRoom.left - env.barRoom.right) / 2
    }
}

/**
 * The tracker's presenter: routes to whichever capture backend's overlay
 * controller is active, the owner of the overlay windows. With none ready
 * (no backend can add overlay windows yet) nothing shows.
 */
object ActiveOverlayErrorPills : TranslationErrorPresenter {
    override fun show(error: TranslationError): Boolean =
        CaptureBackendResolver.activeOverlayUi?.showTranslationErrorPill(error) == true

    override fun update(error: TranslationError) {
        CaptureBackendResolver.activeOverlayUi?.updateTranslationErrorPill(error)
    }

    override fun hide(keys: Collection<TranslationErrorKey>) {
        CaptureBackendResolver.activeOverlayUi?.hideTranslationErrorPills(keys)
    }
}
