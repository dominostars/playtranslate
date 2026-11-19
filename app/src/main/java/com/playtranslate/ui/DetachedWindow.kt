package com.playtranslate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsCompat
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.overlay.OverlayEnvironment
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.OverlayPlacement
import com.playtranslate.overlayThemedContext
import com.playtranslate.themeColor
import com.playtranslate.ui.DetachedWindowGeometry.Corner
import com.playtranslate.ui.DetachedWindowGeometry.Frame
import com.playtranslate.ui.DetachedWindowGeometry.Room
import com.playtranslate.ui.DetachedWindowGeometry.Side
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The floating controls' second form ([com.playtranslate.ControlsForm.WINDOW]):
 * a card over the game that the user moves and resizes, with the icon
 * menu's actions in a top bar ([DetachedWindowBar]) over a slot for the
 * results page ([setPage]). Narrower than the page can be
 * ([DetachedWindowGeometry.isPage]) the whole card is one Capture button,
 * the icon menu's ([PrimaryActionButton]).
 *
 * One window per display, owned by [com.playtranslate.OverlayUiController],
 * which decides when it is up; everything a tap means is the [Host]'s. The
 * window is registered with the [OverlayHost] like the floating icon, so a
 * clean capture takes it out of the frame as it takes the icon out. A fresh
 * instance per install: a removed window's view is never added again (the
 * churn gate's rule), and the page's view moves into the next one.
 *
 * Where it rests is the saved bounds for the display's orientation
 * ([Prefs.detachedStateForDisplay]), read by [layoutAtRest] whenever the host
 * lays the display out; a gesture owns the window until it ends, then saves
 * where it left it. The window's own alpha is never written here: that value
 * belongs to capture blanking and the churn gate.
 *
 * Gestures, one arbiter ([Root]):
 *  - a bottom corner resizes ([DetachedWindowGeometry.resized]); the bar
 *    follows the window's width as it goes, folding and unfolding its
 *    actions, while the page under it keeps the layout it had and lays out
 *    once on release, since a per-frame re-layout of the page is the cost
 *    the capture sheet's drag already has; crossing the page width shows at
 *    once;
 *  - the bar, and the capture button, move the window on any drag;
 *  - inside the page only a very clear sideways drag moves it
 *    ([DetachedWindowGeometry.isClearSideways]); everything else is the
 *    page's (its scroll, its taps). A drag that starts on something that pans
 *    sideways itself is left alone: a slider asks for that itself
 *    (requestDisallowInterceptTouchEvent), and a text field or the Words
 *    list's page is found here. All of the Words list is left alone, not
 *    only its wide tables: a dictionary's own style sheet can make any box
 *    scroll sideways, and the page cannot list those;
 *  - during a move, a finger kept at a side of the room for [DOCK_HOLD_MS]
 *    arms the dock: the card dims, and the release hands the side to the host
 *    ([Host.onDocked]). Leaving the side disarms it.
 */
internal class DetachedWindow(
    displayCtx: Context,
    private val wm: WindowManager,
    val displayId: Int,
    private val overlayHost: OverlayHost,
    private val prefs: Prefs,
    private val host: Host,
) {

    /** What the window's controls do; the controller's. */
    interface Host {
        /** Capture: the bar's button, and the whole card in its button state. */
        fun onCapture(displayId: Int)
        fun onToggleAuto(displayId: Int)
        fun onEditRegion(displayId: Int)
        fun onMinimize(displayId: Int)
        fun onExit(displayId: Int)

        /** The dock gesture ended armed at [side], the finger at [fingerY]. */
        fun onDocked(displayId: Int, side: Side, fingerY: Int)

        /** Any touch went down on the window. */
        fun onTouched(displayId: Int)

        /** The quick settings' rows; [leave] closes the window's popover. */
        fun quickSettings(displayId: Int, leave: () -> Unit): List<QuickSetting>

        /** Whether the capture region is the whole screen. */
        fun regionIsFullScreen(displayId: Int): Boolean

        /** Whether Exit turns PlayTranslate off, or only hides the controls
         *  ([com.playtranslate.capture.CaptureLifecycle.hasActivateControl]). */
        fun exitTurnsOff(): Boolean
    }

    private val ctx: Context = overlayThemedContext(displayCtx)
    private val density = ctx.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val g = DetachedWindowGeometry

    /** The transparent rim around the card: room for the outer half of the
     *  handles' stroke, which is centred on the card's edge. */
    private val marginPx = (HANDLE_STROKE_DP / 2 * density).roundToInt()
    private val handleHitPx = (HANDLE_HIT_DP * density).roundToInt()
    private val barPx = (DetachedWindowBar.HEIGHT_DP * density).roundToInt()
    private val dividerPx = (1 * density).roundToInt()

    private val accent = ctx.themeColor(R.attr.ptAccent)
    private val onAccent = ctx.themeColor(R.attr.ptAccentOn)

    val root = Root()
    private val cardFill = GradientDrawable()
    private val card = FrameLayout(ctx).apply {
        id = R.id.detachedCard
        background = cardFill
        clipToOutline = true
    }
    val bar = DetachedWindowBar(ctx)
    private val pageSlot = FrameLayout(ctx).apply { id = R.id.detachedPageSlot }
    private var pageView: View? = null
    private val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val captureButton = PrimaryActionButton(ctx).apply {
        id = R.id.detachedCaptureButton
        visibility = View.GONE
        // No press effect, as on the bar's Capture (DetachedWindowBar).
        setOnClickListener { host.onCapture(displayId) }
    }

    /** The bar's popovers (its more menu, the quick settings): the page's own
     *  host only takes anchors inside the page. */
    val popovers = PopoverHost(card)

    private val captureAction = HeaderAction(
        R.id.detachedBarCapture, R.drawable.ic_capture, ctx.getString(R.string.detached_bar_capture),
    ).apply { onClick = { host.onCapture(displayId) } }
    private val autoAction = HeaderAction(
        R.id.detachedBarAuto, R.drawable.ic_play, ctx.getString(R.string.live_mode_auto_label),
        contentDescription = ctx.getString(R.string.live_mode_auto_translate_label),
    ).apply { onClick = { host.onToggleAuto(displayId) } }
    private val regionAction = HeaderAction(
        R.id.detachedBarRegion, R.drawable.ic_crop, ctx.getString(R.string.floating_menu_edit_region),
    ).apply { onClick = { host.onEditRegion(displayId) } }
    private val settingsAction = HeaderAction(
        R.id.detachedBarSettings, R.drawable.ic_settings, ctx.getString(R.string.nav_settings),
    ).apply { onClick = { anchor -> toggle(anchor) { QuickSettingsPopover() } } }
    private val minimizeAction = HeaderAction(
        R.id.detachedBarMinimize, R.drawable.ic_collapse_content, ctx.getString(R.string.detached_bar_minimize),
    ).apply { onClick = { host.onMinimize(displayId) } }
    private val exitAction = HeaderAction(
        R.id.detachedBarExit, R.drawable.ic_exit_to_app, "", pinned = true,
    ).apply { onClick = { host.onExit(displayId) } }

    private val params = WindowManager.LayoutParams(
        1, 1,
        overlayHost.windowType,
        // The floating icon's flags: touchable, never focusable at rest, and
        // placed in display pixels (the rim may lie past a display edge).
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /** The room and orientation the window was last laid out in. */
    private var room: Room? = null
    private var landscape = true

    /** The card's rect now. */
    private var frame: Frame? = null

    /** Whether the card shows the page (else the capture button). */
    var showsPage = true
        private set

    private var gesture: Gesture? = null
    private var added = false

    /** How far a keyboard has raised the window, kept while it shows. */
    private var imeLift = 0
    private var imeShown = false
    private var imeMode = false
    private val imeWatcher = ImeFocusWatcher(root, ::setImeMode)

    init {
        bar.setActions(
            DetachedWindowBar.Actions(
                capture = captureAction, auto = autoAction, editRegion = regionAction,
                settings = settingsAction, minimize = minimizeAction, exit = exitAction,
            ),
        )
        bar.onMoreClick = { toggle(it.moreButton) { ActionOverflowMenu(bar) } }
        column.addView(bar, LinearLayout.LayoutParams(MATCH, barPx))
        column.addView(
            View(ctx).apply { setBackgroundColor(ctx.themeColor(R.attr.ptDivider)) },
            LinearLayout.LayoutParams(MATCH, dividerPx),
        )
        column.addView(pageSlot, LinearLayout.LayoutParams(MATCH, 0, 1f))
        card.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        card.addView(captureButton, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(
            card,
            FrameLayout.LayoutParams(MATCH, MATCH).apply { setMargins(marginPx, marginPx, marginPx, marginPx) },
        )
        refreshActions()
        applyLook()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /** Put the window up at its rest place. False when the add failed. */
    fun show(): Boolean {
        added = overlayHost.addOverlayWindow(
            root, wm, params, displayId, OverlayPlacement.Placed(::layoutAtRest),
        )
        if (added) imeWatcher.attach()
        return added
    }

    /** Take the window down for good; the page's view is handed back. */
    fun remove() {
        endGesture()
        imeWatcher.detach()
        popovers.dismiss()
        pageSlot.removeAllViews()
        pageView = null
        if (added) overlayHost.removeOverlayWindow(root)
        added = false
    }

    /** The results page's view, shown under the bar; null takes it out. */
    fun setPage(view: View?) {
        pageSlot.removeAllViews()
        pageView = view
        if (view == null) return
        (view.parent as? ViewGroup)?.removeView(view)
        pageSlot.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** What the bar's Capture and Exit say follows the capture region and
     *  what Exit would do; read again here. */
    fun refreshActions() {
        val fullScreen = host.regionIsFullScreen(displayId)
        val captureIcon = if (fullScreen) R.drawable.ic_capture else R.drawable.ic_picture_in_picture_alt
        // The icon menu's two-line label, for the button and (on one line)
        // for the bar's spoken name.
        val captureName = ctx.getString(
            if (fullScreen) R.string.floating_menu_capture_screen else R.string.floating_menu_btn_capture_region,
        )
        captureAction.icon = captureIcon
        captureAction.contentDescription = captureName.replace('\n', ' ')
        captureButton.show(captureIcon, captureName, fillColor = accent, contentColor = onAccent)
        val turnsOff = host.exitTurnsOff()
        exitAction.icon = if (turnsOff) R.drawable.ic_mode_off_on else R.drawable.ic_exit_to_app
        exitAction.label = ctx.getString(
            if (turnsOff) R.string.floating_icon_close_label_turn_off else R.string.floating_icon_close_label_hide,
        )
        exitAction.contentDescription = exitAction.label
    }

    // ── Placement ─────────────────────────────────────────────────────────

    /**
     * The host's layout pass ([OverlayPlacement.Placed]): at rest the card
     * goes to the saved bounds for the display's orientation (or the default
     * place), raised by the keyboard's lift, inside the room the display
     * leaves. While a gesture owns the window it stays where the finger put
     * it; the gesture's end saves that place, and the next pass reads it.
     */
    fun layoutAtRest(env: OverlayEnvironment, p: WindowManager.LayoutParams) {
        if (gesture != null) return
        val room = g.room(env.geometry.width, env.geometry.height, env.barRoom)
        val landscape = env.geometry.width >= env.geometry.height
        val saved = prefs.detachedStateForDisplay(displayId).bounds(landscape)
        val rest = saved?.let { g.fromBounds(it, room, density) } ?: g.default(room, density)
        this.room = room
        this.landscape = landscape
        place(g.lifted(rest, imeLift, room), p)
        setShowsPage(g.isPage(rest.w, density))
    }

    /** Write [frame] to [p]: the card's rect plus the rim. */
    private fun place(frame: Frame, p: WindowManager.LayoutParams) {
        this.frame = frame
        p.x = frame.x - marginPx
        p.y = frame.y - marginPx
        p.width = frame.w + 2 * marginPx
        p.height = frame.h + 2 * marginPx
    }

    /** Move or size the window to [frame] now (a gesture's step). */
    private fun commit(frame: Frame) {
        if (frame == this.frame) return
        place(frame, params)
        if (!added) return
        try {
            wm.updateViewLayout(root, params)
        } catch (e: Exception) {
            Log.w(TAG, "window[$displayId]: layout update failed: ${e.message}")
        }
    }

    private fun saveBounds() {
        val frame = frame ?: return
        val room = room ?: return
        prefs.setWindowBounds(displayId, landscape, g.toBounds(frame, room, density))
    }

    // ── Page or button ────────────────────────────────────────────────────

    /** Under the button the bar and the page are out of layout: nothing of
     *  them is laid out at a button's width. */
    private fun setShowsPage(page: Boolean) {
        val columnVisibility = if (page) View.VISIBLE else View.GONE
        if (column.visibility != columnVisibility) column.visibility = columnVisibility
        val buttonVisibility = if (page) View.GONE else View.VISIBLE
        if (captureButton.visibility != buttonVisibility) captureButton.visibility = buttonVisibility
        if (page == showsPage) return
        showsPage = page
        if (!page) popovers.dismiss()
        applyLook()
    }

    /** The card's fill and corners for its state, and the handles with it. */
    private fun applyLook() {
        if (showsPage) {
            cardFill.cornerRadius = PAGE_CORNER_DP * density
            cardFill.setColor(ctx.themeColor(R.attr.ptBg))
            cardFill.setStroke((1 * density).roundToInt(), ctx.themeColor(R.attr.ptOutline))
        } else {
            cardFill.cornerRadius = PrimaryActionButton.CORNER_DP * density
            cardFill.setColor(accent)
            cardFill.setStroke(0, 0)
        }
        card.invalidateOutline()
        root.invalidate()
    }

    // ── Gestures ──────────────────────────────────────────────────────────

    private sealed class Gesture(val start: Frame) {
        class Move(start: Frame) : Gesture(start) {
            var side: Side? = null
            var armed = false
        }

        class Resize(start: Frame, val corner: Corner) : Gesture(start)
    }

    /** What a touch that went down can still become. */
    private enum class Claim { NONE, BOTTOM_LEFT, BOTTOM_RIGHT, ANY_DRAG, SIDEWAYS_ONLY }

    private var claim = Claim.NONE
    private var downX = 0f
    private var downY = 0f

    /** The dock's one-second hold. A human-gesture timer, on its own handler
     *  so it does not wait on the view's attach state. */
    private val dockTimer = Handler(Looper.getMainLooper())

    private val armDock = Runnable {
        val move = gesture as? Gesture.Move ?: return@Runnable
        if (move.side == null) return@Runnable
        move.armed = true
        card.alpha = DOCK_ARMED_ALPHA
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun claimAt(x: Float, y: Float): Claim {
        val inHandleBand = y >= root.height - handleHitPx
        if (inHandleBand && x < handleHitPx) return Claim.BOTTOM_LEFT
        if (inHandleBand && x >= root.width - handleHitPx) return Claim.BOTTOM_RIGHT
        // An open popover is modal: its scrim takes the touch.
        if (popovers.isShowing) return Claim.NONE
        if (!showsPage) return Claim.ANY_DRAG
        if (y < marginPx + barPx) return Claim.ANY_DRAG
        if (keepsSidewaysDrags(viewAt(root, x, y))) return Claim.NONE
        return Claim.SIDEWAYS_ONLY
    }

    /** The deepest visible view under ([x], [y]), in [parent]'s coordinates. */
    private fun viewAt(parent: ViewGroup, x: Float, y: Float): View {
        for (i in parent.childCount - 1 downTo 0) {
            val c = parent.getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            if (x < c.left || x >= c.right || y < c.top || y >= c.bottom) continue
            return if (c is ViewGroup) viewAt(c, x - c.left + c.scrollX, y - c.top + c.scrollY) else c
        }
        return parent
    }

    private fun movesNow(ev: MotionEvent): Boolean {
        val dx = ev.rawX - downX
        val dy = ev.rawY - downY
        return when (claim) {
            Claim.ANY_DRAG -> hypot(dx, dy) > slop
            Claim.SIDEWAYS_ONLY -> g.isClearSideways(dx, dy, slop)
            else -> false
        }
    }

    private fun begin(next: Gesture) {
        popovers.dismiss()
        // The user takes the window's place over from the keyboard's lift.
        imeLift = 0
        gesture = next
        if (next is Gesture.Resize) freeze(next.corner)
    }

    private fun drag(ev: MotionEvent) {
        val gesture = gesture ?: return
        val room = room ?: return
        val dx = (ev.rawX - downX).roundToInt()
        val dy = (ev.rawY - downY).roundToInt()
        when (gesture) {
            is Gesture.Move -> {
                commit(g.moved(gesture.start, dx, dy, room, density))
                val side = g.dockSide(ev.rawX, room, density)
                if (side != gesture.side) {
                    disarm(gesture)
                    gesture.side = side
                    if (side != null) dockTimer.postDelayed(armDock, DOCK_HOLD_MS)
                }
            }
            is Gesture.Resize -> {
                val next = g.resized(gesture.start, gesture.corner, dx, dy, room, density)
                commit(next)
                val page = g.isPage(next.w, density)
                // Coming into view the page is laid out once, at the room
                // under the bar now: under the button it has no layout worth
                // keeping (none at all, or the one from before it was
                // narrowed).
                if (page && !showsPage) {
                    freeze(gesture.corner, next.w, (next.h - barPx - dividerPx).coerceAtLeast(0))
                }
                setShowsPage(page)
            }
        }
    }

    private fun disarm(move: Gesture.Move) {
        dockTimer.removeCallbacks(armDock)
        move.armed = false
        move.side = null
        card.alpha = 1f
    }

    /** The finger lifted: a move armed at a side docks; anything else rests
     *  where it is, and that place is saved. */
    private fun release(ev: MotionEvent) {
        val ended = gesture ?: return
        val docked = (ended as? Gesture.Move)?.takeIf { it.armed }?.side
        endGesture()
        if (docked != null) {
            host.onDocked(displayId, docked, ev.rawY.roundToInt())
            return
        }
        saveBounds()
        overlayHost.invalidateLayout(displayId)
    }

    /** End a gesture where it stands, saving nothing: the finger lifted
     *  ([release] saves), or the display changed under it and the window
     *  goes back to its rest place. */
    fun endGesture() {
        val ended = gesture ?: return
        gesture = null
        claim = Claim.NONE
        dockTimer.removeCallbacks(armDock)
        card.alpha = 1f
        if (ended is Gesture.Resize) thaw()
    }

    /** Hold the page at one size ([width] by [height], the size it has
     *  unless given), pinned to the corner that stays put, while a corner is
     *  dragged: its text fit and its Words list are not laid out again for
     *  every step of the finger. The bar is not held; it follows the
     *  window's width, folding and unfolding its actions as it goes. */
    private fun freeze(corner: Corner, width: Int? = null, height: Int? = null) {
        val page = pageView ?: return
        page.layoutParams = FrameLayout.LayoutParams(
            width ?: page.width, height ?: page.height,
            Gravity.TOP or if (corner == Corner.BOTTOM_LEFT) Gravity.RIGHT else Gravity.LEFT,
        )
    }

    private fun thaw() {
        pageView?.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
    }

    // ── The bar's popovers ────────────────────────────────────────────────

    private fun toggle(anchor: View, content: () -> PopoverContent) {
        val wasOpen = popovers.anchor === anchor
        popovers.dismiss()
        if (!wasOpen) popovers.show(content(), anchor)
    }

    /** The quick settings: the rows the icon menu's gear panel lists. */
    private inner class QuickSettingsPopover : PopoverContent {
        override fun createView(ctx: Context, host: PopoverHost): View {
            val rows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            QuickSettingsRows.populate(
                rows,
                this@DetachedWindow.host.quickSettings(displayId) { host.dismiss() },
                iconTint = ctx.themeColor(R.attr.ptTextMuted),
            )
            // A short window caps the card; the rows scroll.
            return ScrollView(ctx).apply { addView(rows, FrameLayout.LayoutParams(MATCH, WRAP)) }
        }

        override fun cardWidth(ctx: Context, natural: Int): Int =
            (QUICK_SETTINGS_WIDTH_DP * density).roundToInt()
    }

    // ── Typing ────────────────────────────────────────────────────────────

    /** A text field of the page took or lost focus ([ImeFocusWatcher]): the
     *  window takes key focus with the keyboard for it, and is back to taking
     *  none when the typing ends, so the game has its keys. */
    private fun setImeMode(wantsIme: Boolean) {
        if (imeMode == wantsIme) return
        imeMode = wantsIme
        if (wantsIme) {
            // Read now, not once at install: this window lives for hours.
            OverlayHost.mirrorSystemBars(root, overlayHost.hiddenSystemBarsOnDisplay(displayId))
        } else {
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(root.windowToken, 0)
        }
        params.applyOverlayFocus(focusable = wantsIme, wantsIme = wantsIme)
        if (!added) return
        try {
            wm.updateViewLayout(root, params)
        } catch (e: Exception) {
            Log.w(TAG, "window[$displayId]: focus update failed: ${e.message}")
        }
    }

    /** Take the view focus off a text field, onto the root: ends the typing. */
    private fun endTyping() {
        if (root.findFocus() is EditText) root.requestFocus()
    }

    /**
     * The keyboard against this window. While it shows, the window stays
     * raised by the largest overlap seen: the inset a small window is given
     * is only its own overlap, which reads zero again once it has been
     * raised. When the keyboard goes (its own hide button, Back) the lift
     * goes and the field gives its focus up, which ends the typing.
     */
    private fun onInsets(insets: WindowInsets) {
        val compat = WindowInsetsCompat.toWindowInsetsCompat(insets, root)
        val shown = compat.isVisible(WindowInsetsCompat.Type.ime())
        if (!shown) {
            if (!imeShown) return
            imeShown = false
            imeLift = 0
            endTyping()
            overlayHost.invalidateLayout(displayId)
            return
        }
        imeShown = true
        val overlap = compat.getInsets(WindowInsetsCompat.Type.ime()).bottom
        if (overlap <= 0 || gesture != null) return
        imeLift += overlap
        overlayHost.invalidateLayout(displayId)
    }

    // ── The root ──────────────────────────────────────────────────────────

    /** The window's root view: the touch arbiter and the handles' painter. */
    inner class Root : FrameLayout(ctx) {

        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = HANDLE_STROKE_DP * density
            strokeCap = Paint.Cap.ROUND
        }
        private val handlePath = Path()

        init {
            // Holds the view focus a text field gives up (endTyping).
            isFocusable = true
            isFocusableInTouchMode = true
            defaultFocusHighlightEnabled = false
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) host.onTouched(displayId)
            return super.dispatchTouchEvent(ev)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    claim = claimAt(ev.x, ev.y)
                    val corner = when (claim) {
                        Claim.BOTTOM_LEFT -> Corner.BOTTOM_LEFT
                        Claim.BOTTOM_RIGHT -> Corner.BOTTOM_RIGHT
                        else -> return false
                    }
                    val start = frame ?: return false
                    begin(Gesture.Resize(start, corner))
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (gesture != null) return true
                    if (!movesNow(ev)) return false
                    val start = frame ?: return false
                    begin(Gesture.Move(start))
                    return true
                }
            }
            return false
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    // No child took the touch, so nothing asked to intercept.
                    if (gesture == null && movesNow(ev)) frame?.let { begin(Gesture.Move(it)) }
                    drag(ev)
                }
                MotionEvent.ACTION_UP -> release(ev)
                MotionEvent.ACTION_CANCEL -> {
                    val ended = gesture != null
                    endGesture()
                    if (ended) {
                        saveBounds()
                        overlayHost.invalidateLayout(displayId)
                    }
                }
            }
            return true
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            // Back with the window holding key focus ends the typing, as the
            // keyboard's own close does.
            if (imeMode && (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE)) {
                if (event.action == KeyEvent.ACTION_UP) endTyping()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
            onInsets(insets)
            return super.onApplyWindowInsets(insets)
        }

        /** The two bottom-corner handles: a stroke that follows the card's
         *  corner, centred on its edge so half lies outside, at half alpha.
         *  Accent on the page; on the accent button the on-accent colour,
         *  since accent on accent cannot be seen. */
        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            val m = marginPx.toFloat()
            val right = width - m
            val bottom = height - m
            val r = (if (showsPage) PAGE_CORNER_DP else PrimaryActionButton.CORNER_DP) * density
            val arm = HANDLE_ARM_DP * density
            handlePaint.color = ColorUtils.setAlphaComponent(if (showsPage) accent else onAccent, HANDLE_ALPHA)

            handlePath.rewind()
            handlePath.moveTo(m, bottom - r - arm)
            handlePath.lineTo(m, bottom - r)
            handlePath.arcTo(m, bottom - 2 * r, m + 2 * r, bottom, 180f, -90f, false)
            handlePath.lineTo(m + r + arm, bottom)
            canvas.drawPath(handlePath, handlePaint)

            handlePath.rewind()
            handlePath.moveTo(right, bottom - r - arm)
            handlePath.lineTo(right, bottom - r)
            handlePath.arcTo(right - 2 * r, bottom - 2 * r, right, bottom, 0f, 90f, false)
            handlePath.lineTo(right - r - arm, bottom)
            canvas.drawPath(handlePath, handlePaint)
        }
    }

    companion object {
        private const val TAG = "DetachedWindow"

        /** Whether a sideways drag that starts on [view] is the view's own
         *  and never moves the window: a text field (its caret, its
         *  selection), and the Words list's page, where a dictionary's style
         *  sheet can make any box scroll sideways. */
        internal fun keepsSidewaysDrags(view: View): Boolean = view is EditText || view is WebView
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** The page card's corner radius. */
        const val PAGE_CORNER_DP = 16f

        /** The handles' stroke: centred on the card's edge, so half of it is
         *  the window's rim. */
        const val HANDLE_STROKE_DP = 4f
        private const val HANDLE_ARM_DP = 16f
        private const val HANDLE_ALPHA = 0x80

        /** A handle's touch square at each bottom corner. */
        const val HANDLE_HIT_DP = 44f

        /** How long the finger stays at a side before the dock arms. */
        const val DOCK_HOLD_MS = 1000L
        private const val DOCK_ARMED_ALPHA = 0.4f

        private const val QUICK_SETTINGS_WIDTH_DP = 280f
    }
}
