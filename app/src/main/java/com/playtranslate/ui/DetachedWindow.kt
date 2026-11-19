package com.playtranslate.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
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
import android.view.animation.PathInterpolator
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.annotation.VisibleForTesting
import android.widget.LinearLayout
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
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The floating controls' second form ([com.playtranslate.ControlsForm.WINDOW]):
 * a card over the game that the user moves and resizes, with a top bar
 * ([DetachedWindowBar]) over a slot for the results page ([setPage]).
 * Narrower than the page can be ([DetachedWindowGeometry.isPage]) the whole
 * card is one Capture button, the icon menu's ([PrimaryActionButton]).
 *
 * The bar holds what a capture needs and nothing a user has to decode:
 * Minimize, the menu button, Auto and Capture by name. Everything else is
 * in the one menu ([Menu]), by name, under four headings that say what each
 * row acts on: the result on show, how its text shows, the capture, and
 * PlayTranslate itself. The rows keep their places from one open to the
 * next; one with nothing to act on is dimmed. Turn Off is a row there and
 * not a button of the bar, where an X would read as closing the window.
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
 * The window and the icon are two forms of one thing, and where the user
 * turns one into the other the window shows it: it is somewhere between
 * itself on its frame and the icon in the icon's place ([away]), the card
 * shrinking and fading as a picture of the icon comes up in its middle
 * ([render]). It flies out of the icon's place as Detach brings it up
 * ([show]) and into it as Minimize takes it down ([remove]); and under a
 * finger it goes back and forth for as long as the finger stays down, the
 * release settling whichever it then is: a move carried on past a side of
 * the screen docks the window there ([DetachedWindowGeometry.dockedSide]),
 * and an icon's drag can pull the window out of it ([showPulled]). It is
 * this window that moves, as the icon's own snap moves the icon's; no
 * window is added for it.
 *
 * Gestures, one arbiter ([Root]), and the corners' own touch pads:
 *  - a bottom corner resizes ([DetachedWindowGeometry.resized]), from a
 *    touch pad that reaches past the card's corner ([HandlePad]); the bar
 *    and the page follow the window's size as it goes, the bar fitting its
 *    buttons and the page its text again; crossing the page width shows at
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
 *  - a move carried on past a side, where the card stops, docks the window
 *    there: it turns into the icon, at the finger's height, and back into
 *    the window if the finger comes back out; a release while docked hands
 *    the side to the host ([Host.onDocked]).
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

        /** A move was let go docked at [side], the finger at [fingerY]. */
        fun onDocked(displayId: Int, side: Side, fingerY: Int)

        /** Any touch went down on the window. */
        fun onTouched(displayId: Int)

        /** The quick settings' rows, which the window lists in its menu;
         *  [leave] closes the menu. */
        fun quickSettings(displayId: Int, leave: () -> Unit): List<QuickSetting>

        /** Whether the capture region is the whole screen. */
        fun regionIsFullScreen(displayId: Int): Boolean

        /** Whether [onExit] turns PlayTranslate off, or only hides the
         *  controls
         *  ([com.playtranslate.capture.CaptureLifecycle.hasActivateControl]). */
        fun exitTurnsOff(): Boolean
    }

    /**
     * What the window needs of the page it shows ([attachPage]): its view,
     * and the parts of the page that live in the window's menu and bar
     * because the compact page has no headers and no floating pill of its
     * own. The actions are models the page keeps (their look, whether each
     * is available): the window lists them and runs them, no more.
     */
    interface Page {
        val view: View

        /** Read aloud: a menu row, and a shortcut on a wide bar. */
        val readAloud: HeaderAction

        /** The rows about how the texts show (the ruby switch where the
         *  game language has one, the two eyes, Text size), read at each
         *  open of the menu. */
        fun textActions(): List<HeaderAction>

        /** The two languages' rows, each with its language as its value,
         *  read at each open of the menu. */
        fun languageRows(): List<HeaderAction>

        /** Follows every one of those actions' changes. */
        fun addActionListener(listener: FoldingActionRow.Listener)
        fun removeActionListener(listener: FoldingActionRow.Listener)

        /** Whether a result is on show: something for the page's actions
         *  to act on, and to make a card of. */
        val resultOnShow: Boolean

        /** [resultOnShow] changed. */
        var onResultOnShowChanged: (() -> Unit)?

        /** Add to Anki: the tap opens the review, the hold (the bar's
         *  shortcut only) sends the card. */
        fun addToAnki()
        fun ankiOneTap()

        /** Where the page's own popovers open (its text-size picker): the
         *  window's, which may reach past the window. */
        var popoverPresenter: ((PopoverContent, View) -> Unit)?
    }

    private val ctx: Context = overlayThemedContext(displayCtx)
    private val density = ctx.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val g = DetachedWindowGeometry

    /** The transparent rim around the card: room for the outer half of the
     *  handles' stroke, which is centred on the card's edge. */
    private val marginPx = (HANDLE_STROKE_DP / 2 * density).roundToInt()
    private val handleInsidePx = (HANDLE_HIT_INSIDE_DP * density).roundToInt()
    private val handleOutsidePx = (HANDLE_HIT_OUTSIDE_DP * density).roundToInt()
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
    private val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val captureButton = PrimaryActionButton(ctx).apply {
        id = R.id.detachedCaptureButton
        visibility = View.GONE
        // No press effect, as on the bar's Capture (DetachedWindowBar).
        setOnClickListener { host.onCapture(displayId) }
    }

    private var page: Page? = null

    /** The window's popovers (the menu, the page's text-size picker), in an
     *  overlay window of their own: the window can be smaller than either. */
    val popovers = FloatingPopover(ctx, wm, displayId, overlayHost)

    /** The page's actions are its header rows' to observe; the bar, and an
     *  open menu through it, hear of their changes through here. */
    private val pageActionListener = object : FoldingActionRow.Listener {
        override fun onActionChanged(action: HeaderAction) = bar.actionChanged(action)
    }

    // On the bar.
    private val captureAction = HeaderAction(
        R.id.detachedBarCapture, R.drawable.ic_capture, ctx.getString(R.string.detached_bar_capture),
    ).apply { onClick = { host.onCapture(displayId) } }
    private val autoAction = HeaderAction(
        R.id.detachedBarAuto, R.drawable.ic_play, ctx.getString(R.string.live_mode_auto_label),
        contentDescription = ctx.getString(R.string.live_mode_auto_translate_label),
    ).apply { onClick = { host.onToggleAuto(displayId) } }
    private val minimizeAction = HeaderAction(
        R.id.detachedBarMinimize, R.drawable.ic_window_minimize, ctx.getString(R.string.detached_bar_minimize),
    ).apply { onClick = { host.onMinimize(displayId) } }

    /** Add to Anki twice over: the bar's shortcut under its short name
     *  (a hold sends the card), and the menu's row under its full one. */
    private val ankiShortcut = HeaderAction(
        R.id.detachedBarAnki, R.drawable.ic_card_stack_add, ctx.getString(R.string.detached_bar_anki),
        contentDescription = ctx.getString(R.string.cd_add_to_anki),
    ).apply { onClick = { page?.addToAnki() } }
    private val ankiRow = menuOnly(R.drawable.ic_card_stack_add, ctx.getString(R.string.cd_add_to_anki)) {
        page?.addToAnki()
    }

    // In the menu only.
    private val regionRow = menuOnly(R.drawable.ic_crop, ctx.getString(R.string.floating_menu_edit_region)) {
        host.onEditRegion(displayId)
    }
    private val turnOffRow = menuOnly(R.drawable.ic_mode_off_on, "") { host.onExit(displayId) }

    /** An action that is only ever a row of the menu. No row of a section
     *  header owns it, so an open menu hears of its changes through the bar. */
    private fun menuOnly(@DrawableRes icon: Int, label: CharSequence, run: () -> Unit): HeaderAction =
        HeaderAction(R.id.detachedMenuRow, icon, label).apply {
            onClick = { run() }
            observer = { action, _ -> bar.actionChanged(action) }
        }

    /**
     * The menu, as one open of it lists it: everything the window can do
     * that the bar does not hold, in four groups under headings.
     *
     *  - the result on show: Add to Anki, Read aloud;
     *  - its text: the ruby switch, the two eyes, Text size;
     *  - the capture: Auto and Capture where a very narrow bar folded them,
     *    Edit region, the two languages, then the quick settings' own rows
     *    about a capture (the OCR tool, MangaOCR, Overlays);
     *  - PlayTranslate: open the app, turn it off.
     *
     * The first two groups act on a result: with none on show their rows
     * are dimmed, in their places. A switch leaves the menu up; so does a
     * quick setting, which closes it itself where it hands over to another
     * surface. The quick settings' Language row is left out: the page's
     * Game Language row is the same picker.
     *
     * A fresh one per open: the rows are read then (the page's, the quick
     * settings' values) and stay those while the menu is up, which is what
     * lets it tell a list that changed from one that did not.
     */
    private inner class Menu : FoldingActionRow {
        private val page = this@DetachedWindow.page
        private val onResult: List<HeaderAction> = if (page == null) emptyList() else listOf(ankiRow, page.readAloud)
        private val text: List<HeaderAction> = page?.textActions() ?: emptyList()
        private val languages: List<HeaderAction> = page?.languageRows() ?: emptyList()
        private val settings: Map<QuickSetting.Id, HeaderAction> =
            host.quickSettings(displayId) { popovers.dismiss() }.associate { it.id to settingRow(it) }

        private val needResult = (onResult + text).toSet()
        private val staysOpen = (text + settings.values).toSet()

        private fun settingRows(vararg ids: QuickSetting.Id): List<HeaderAction> = ids.mapNotNull { settings[it] }

        override fun foldedActions(): List<HeaderAction> = bar.foldedActions()

        override fun menuGroups(): List<MenuGroup> = listOf(
            MenuGroup(ctx.getString(R.string.detached_menu_group_result), onResult),
            MenuGroup(ctx.getString(R.string.detached_menu_group_text), text),
            MenuGroup(
                ctx.getString(R.string.detached_menu_group_capture),
                bar.foldedActions() + regionRow + languages +
                    settingRows(QuickSetting.Id.OCR, QuickSetting.Id.MANGA_OCR, QuickSetting.Id.OVERLAYS),
            ),
            MenuGroup(ctx.getString(R.string.app_name), settingRows(QuickSetting.Id.OPEN_APP) + turnOffRow),
        )

        override fun menuEnabled(action: HeaderAction): Boolean =
            action !in needResult || (page?.resultOnShow == true && action.available)

        override fun menuStaysOpen(action: HeaderAction): Boolean = action in staysOpen

        override val moreAnchor: View get() = bar.moreButton
        override fun addListener(listener: FoldingActionRow.Listener) = bar.addListener(listener)
        override fun removeListener(listener: FoldingActionRow.Listener) = bar.removeListener(listener)
    }

    /** One of the quick settings as a row of the menu: its title, its value
     *  at the row's end, and the value read again after each tap (the rows
     *  that flip in place set it as they run). */
    private fun settingRow(setting: QuickSetting): HeaderAction {
        val icon = when (setting.id) {
            QuickSetting.Id.OCR -> R.drawable.ic_settings
            QuickSetting.Id.OVERLAYS -> R.drawable.ic_subtitles
            // Under the OCR row and about it: no icon of its own.
            QuickSetting.Id.MANGA_OCR -> 0
            else -> setting.icon ?: 0
        }
        lateinit var row: HeaderAction
        row = menuOnly(icon, setting.title) {
            setting.click()
            row.detail = setting.value
        }
        row.detail = setting.value
        return row
    }

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
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        turnOffSystemMoveAnimation()
    }

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
                capture = captureAction, auto = autoAction, anki = ankiShortcut, minimize = minimizeAction,
            ),
        )
        bar.setOnHold(ankiShortcut) { page?.ankiOneTap() }
        bar.onMoreClick = { toggle(it.moreButton) { ActionOverflowMenu(Menu()) } }
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

    /** Put the window up at its rest place: at once, or flying there out
     *  of [from] (the icon it takes the place of, in display pixels). False
     *  when the add failed. */
    fun show(from: Rect? = null): Boolean {
        if (!addToDisplay()) return false
        if (from != null) {
            // In the same turn as the add, so the first frame drawn is
            // already the flight's first.
            morphIcon = from
            away = 1f
            render()
            morphTo(0f)
        }
        return true
    }

    private fun addToDisplay(): Boolean {
        added = overlayHost.addOverlayWindow(
            root, wm, params, displayId, OverlayPlacement.Placed(::layoutAtRest),
        )
        if (added) {
            imeWatcher.attach()
            // After the window, so above it: a pad's inner part lies over
            // the card's corner.
            for (pad in pads) pad.show()
        }
        return added
    }

    /**
     * Take the window down for good; the page's view is handed back. With
     * [to] (the icon that takes its place, in display pixels) what the
     * window is, and everything it holds of the page, ends now all the same,
     * and only a picture of the card stays up for the flight into the icon:
     * the page is its owner's to release the moment the window is done with
     * it, so nothing of it may be drawn after this returns. A window that
     * already shows as the icon (a dock let go) has no flight left to make.
     */
    fun remove(to: Rect? = null) {
        if (leaving) return
        val atIcon = away == 1f && flight == null
        clearGesture()
        imeWatcher.detach()
        val picture = if (to != null && added && !atIcon) pictureOfCard() else null
        attachPage(null)
        for (pad in pads) pad.remove()
        if (to == null || picture == null) {
            stopFlight()
            takeDown()
            return
        }
        leaving = true
        card.removeAllViews()
        card.addView(
            ImageView(ctx).apply {
                setImageBitmap(picture)
                scaleType = ImageView.ScaleType.FIT_XY
            },
            FrameLayout.LayoutParams(MATCH, MATCH),
        )
        morphIcon = to
        morphTo(1f)
    }

    private fun takeDown() {
        if (added) overlayHost.removeOverlayWindow(root)
        added = false
    }

    // ── Between the window and the icon ───────────────────────────────────

    /** How far the window is from being itself: 0 the window on its
     *  [frame], 1 the icon in [morphIcon]'s place. Anything but 0 and the
     *  window is [render]'s to place: the host's layout pass waits. */
    private var away = 0f

    /** The icon's place, in display pixels: where the window flies out of
     *  or into, or where a docked move's finger has the icon now. */
    private var morphIcon: Rect? = null

    /** The run of [away] under way, towards [morphTarget], or null. */
    private var flight: ValueAnimator? = null
    private var morphTarget = 0f

    /** The window is on its way out ([remove] with a flight): it takes no
     *  touch, and it is not taken down twice. */
    private var leaving = false

    /** The icon as the window shows it while it is, or is turning into,
     *  the icon: the icon's own view, drawing what the icon draws at its
     *  dock, as the Settings preview uses it. In the tree only while it
     *  shows, so its idle timers never run under a window at rest. */
    private val ghost = FloatingOverlayIcon(ctx)
    private val ghostHolder = object : FrameLayout(ctx) {
        // A picture: the gesture is the window's own.
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean = false
    }.apply { addView(ghost, FrameLayout.LayoutParams(ghost.viewSizePx, ghost.viewSizePx)) }

    /** Where the icon rests at [side] for a finger at height [fingerY]: the
     *  icon's own dock arithmetic ([IconDock]), so the picture of it stands
     *  exactly where the icon will. Null with no display to place it on. */
    private fun iconPlaceAt(side: Side, fingerY: Int): Rect? {
        val env = overlayHost.environment(displayId) ?: return null
        val inset = (IconDock.GESTURE_INSET_DP * density).toInt()
        val fraction = IconDock.fraction(fingerY, env.geometry.height, env.covered, inset)
        val place = IconDock.place(
            env.geometry.width, env.geometry.height, env.covered,
            side == Side.LEFT, fraction, ghost.viewSizePx, inset,
        )
        return Rect(place.x, place.y, place.x + ghost.viewSizePx, place.y + ghost.viewSizePx)
    }

    /**
     * Show the window as far [away] from itself as it is: the window is
     * placed so that the card's centre lies between its own and the
     * icon's, and the card is scaled between its own size and the icon's.
     * The card is solid for the first half of the way and gone at the
     * icon; the picture of the icon comes up over the second half. The
     * window keeps its size throughout (nothing inside is laid out again
     * for a frame of this); only its place is written, as a drag writes it.
     */
    private fun render() {
        val at = frame ?: return
        val icon = morphIcon?.takeIf { away != 0f }
        if (icon == null) {
            params.x = at.x - marginPx
            params.y = at.y - marginPx
            card.scaleX = 1f
            card.scaleY = 1f
            card.alpha = 1f
            if (ghostHolder.parent != null) root.removeView(ghostHolder)
        } else {
            val homeX = at.x + at.w / 2f
            val homeY = at.y + at.h / 2f
            val cx = homeX + (icon.exactCenterX() - homeX) * away
            val cy = homeY + (icon.exactCenterY() - homeY) * away
            params.x = (cx - at.w / 2f).roundToInt() - marginPx
            params.y = (cy - at.h / 2f).roundToInt() - marginPx
            card.pivotX = at.w / 2f
            card.pivotY = at.h / 2f
            card.scaleX = 1f + ((icon.width().toFloat() / at.w).coerceAtMost(1f) - 1f) * away
            card.scaleY = 1f + ((icon.height().toFloat() / at.h).coerceAtMost(1f) - 1f) * away
            card.alpha = ((1f - away) * 2f).coerceIn(0f, 1f)
            if (ghostHolder.parent == null) {
                root.addView(
                    ghostHolder,
                    FrameLayout.LayoutParams(ghost.viewSizePx, ghost.viewSizePx, Gravity.CENTER),
                )
            }
            // The half disc the icon shows faces the screen from its side.
            val edge = if (icon.exactCenterX() < homeX) FloatingOverlayIcon.Edge.LEFT else FloatingOverlayIcon.Edge.RIGHT
            if (ghost.currentEdge != edge) ghost.setSlot(edge.ordinal, 0.5f)
            ghostHolder.alpha = ((away - 0.5f) * 2f).coerceIn(0f, 1f)
        }
        if (!added) return
        try {
            wm.updateViewLayout(root, params)
        } catch (e: Exception) {
            Log.w(TAG, "window[$displayId]: place update failed: ${e.message}")
        }
    }

    /** Run [away] to [target] from where it is, in the part of [FLIGHT_MS]
     *  that is left of the way. Asked again for where it is already going,
     *  it goes on; with animations turned off it is there at once. */
    private fun morphTo(target: Float) {
        if (flight != null && morphTarget == target) return
        stopFlight()
        morphTarget = target
        if (away == target) {
            landed()
            return
        }
        val animator = ValueAnimator.ofFloat(away, target).apply {
            duration = (FLIGHT_MS * abs(target - away)).toLong().coerceAtLeast(1L)
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener {
                away = it.animatedValue as Float
                render()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (flight !== animation) return
                    flight = null
                    away = target
                    render()
                    landed()
                }
            })
        }
        flight = animator
        root.invalidate()
        animator.start()
    }

    /** A run of [away] ended where it was going: a window on its way out
     *  is taken down, and one back on its frame with no finger on it is at
     *  rest, the host's to lay out again (its corner targets with it). */
    private fun landed() {
        root.invalidate()
        when {
            leaving -> takeDown()
            away == 0f && gesture == null -> overlayHost.invalidateLayout(displayId)
        }
    }

    /** Stop a run of [away] where it is, landing nowhere. */
    private fun stopFlight() {
        val running = flight ?: return
        flight = null
        running.cancel()
    }

    /**
     * The card as a picture, for the flight out: what it shows now, drawn
     * once. Capped in pixels, since it is scaled down and faded from its
     * first frame and a large window's card at full size is several
     * megabytes on a device that is running a game. Null where there is no
     * memory for it, nothing to draw, or something in the card that will
     * not draw off screen; the window then goes at once. The flight is an
     * ornament: it never stands between the user and Minimize.
     */
    private fun pictureOfCard(): Bitmap? {
        val w = card.width
        val h = card.height
        if (w <= 0 || h <= 0) return null
        val scale = sqrt(MAX_PICTURE_PX / (w.toFloat() * h)).coerceAtMost(1f)
        return try {
            Bitmap.createBitmap(
                (w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            ).also {
                val canvas = Canvas(it)
                canvas.scale(scale, scale)
                card.draw(canvas)
            }
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "window[$displayId]: no memory for the flight's picture")
            null
        } catch (e: RuntimeException) {
            Log.w(TAG, "window[$displayId]: the card would not draw for the flight: ${e.message}")
            null
        }
    }

    /**
     * [page] is the window's page from here: its view under the bar, its
     * actions in the menu, its Read aloud and Add to Anki as the bar's
     * shortcuts too, and its popovers in the window's. Null takes the page
     * out and unhooks it.
     */
    fun attachPage(page: Page?) {
        popovers.dismiss()
        this.page?.let {
            it.onResultOnShowChanged = null
            it.popoverPresenter = null
            it.removeActionListener(pageActionListener)
        }
        this.page = page
        setPage(page?.view)
        bar.setReadAloud(page?.readAloud)
        syncResultOnShow()
        page ?: return
        page.addActionListener(pageActionListener)
        page.onResultOnShowChanged = ::syncResultOnShow
        page.popoverPresenter = { content, anchor -> popovers.show(content, anchor) }
    }

    /** With a result on show the bar offers its shortcuts and the menu's
     *  rows about a result can be tapped; with a status up there is nothing
     *  for them to act on. */
    private fun syncResultOnShow() {
        bar.shortcutsOffered = page?.resultOnShow ?: false
        bar.menuChanged()
    }

    /** The view shown under the bar; null takes it out. */
    fun setPage(view: View?) {
        pageSlot.removeAllViews()
        if (view == null) return
        (view.parent as? ViewGroup)?.removeView(view)
        pageSlot.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** What Capture says follows the capture region, and the menu's last
     *  row what it would do; read again here. */
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
        turnOffRow.icon = if (turnsOff) R.drawable.ic_mode_off_on else R.drawable.ic_exit_to_app
        turnOffRow.label = ctx.getString(
            if (turnsOff) R.string.floating_icon_close_label_turn_off else R.string.floating_icon_close_label_hide,
        )
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
        if (gesture != null || away != 0f || leaving) return
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
            /** The side the move has docked the window on, or null while
             *  it is the window ([DetachedWindowGeometry.dockedSide]). */
            var docked: Side? = null

            /** The finger's height at the last step. */
            var fingerY = 0
        }

        class Resize(start: Frame, val corner: Corner) : Gesture(start)
    }

    /** What a touch that went down can still become. */
    private enum class Claim { NONE, ANY_DRAG, SIDEWAYS_ONLY }

    private var claim = Claim.NONE
    private var downX = 0f
    private var downY = 0f

    /** The system took the touch away: the gesture ends where it stands,
     *  and a window it had docked is the window again. */
    private fun cancelGesture() {
        if (gesture == null) return
        clearGesture()
        saveBounds()
        if (away != 0f) morphTo(0f) else overlayHost.invalidateLayout(displayId)
    }

    // ── The corner handles' touch pads ────────────────────────────────────

    /**
     * One bottom corner's touch target: an unseen window of its own, a
     * square that reaches [HANDLE_HIT_OUTSIDE_DP] past the card's corner
     * and [HANDLE_HIT_INSIDE_DP] into it. A window's extent is its touch
     * region, so the reach past the card has to be a window; making the main
     * window larger instead would take every touch in a band around the
     * whole card from the game. The stroke the user sees is still the main
     * window's ([Root.dispatchDraw]).
     *
     * Placed from the card's frame at rest ([place], run by the overlay
     * host with the window's own layout). While a gesture moves the window
     * the pads stay where they were: the finger already owns its gesture
     * and nothing shows of them; the gesture's end lays them out again.
     */
    private inner class HandlePad(val corner: Corner) {
        private var up = false

        /** This pad began the resize under way. */
        private var active = false

        val view: View = object : View(ctx) {
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        host.onTouched(displayId)
                        val start = frame
                        // One gesture at a time: a second finger is not
                        // one, and a window in flight takes none.
                        active = gesture == null && away == 0f && start != null
                        if (active) {
                            downX = ev.rawX
                            downY = ev.rawY
                            claim = Claim.NONE
                            begin(Gesture.Resize(start!!, corner))
                        }
                    }
                    MotionEvent.ACTION_MOVE -> if (active) dragTo(ev.rawX, ev.rawY)
                    MotionEvent.ACTION_UP -> if (active) {
                        active = false
                        release()
                    }
                    MotionEvent.ACTION_CANCEL -> if (active) {
                        active = false
                        cancelGesture()
                    }
                }
                return true
            }
        }

        val params = WindowManager.LayoutParams(
            1, 1,
            overlayHost.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        fun show() {
            up = overlayHost.addOverlayWindow(
                view, wm, params, displayId, OverlayPlacement.Placed { _, p -> place(p) },
            )
        }

        fun remove() {
            if (up) overlayHost.removeOverlayWindow(view)
            up = false
        }

        private fun place(p: WindowManager.LayoutParams) {
            val f = frame ?: return
            val side = handleInsidePx + handleOutsidePx
            p.width = side
            p.height = side
            p.x = if (corner == Corner.BOTTOM_LEFT) f.x - handleOutsidePx else f.right - handleInsidePx
            p.y = f.bottom - handleInsidePx
        }
    }

    private val pads = listOf(HandlePad(Corner.BOTTOM_LEFT), HandlePad(Corner.BOTTOM_RIGHT))

    /** The corners' touch pads, bottom left then bottom right, each with its
     *  window's params: for a test to find and touch. */
    @VisibleForTesting
    internal fun handlePadsForTest(): List<Pair<View, WindowManager.LayoutParams>> =
        pads.map { it.view to it.params }

    private fun claimAt(x: Float, y: Float): Claim {
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
    }

    private fun dragTo(rawX: Float, rawY: Float) {
        val gesture = gesture ?: return
        val room = room ?: return
        val dx = (rawX - downX).roundToInt()
        val dy = (rawY - downY).roundToInt()
        when (gesture) {
            is Gesture.Move -> {
                val home = g.moved(gesture.start, dx, dy, room, density)
                gesture.fingerY = rawY.roundToInt()
                // Docked only where there is a place to show the icon.
                val place = g.dockedSide(gesture.docked, gesture.start, dx, rawX, room, density)
                    ?.let { side -> iconPlaceAt(side, gesture.fingerY)?.let { side to it } }
                if (place?.first != gesture.docked) {
                    gesture.docked = place?.first
                    root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                if (place == null && away == 0f) {
                    commit(home)
                    return
                }
                // Between the two: the window's frame is where it would
                // stand, the icon's place follows the finger's height, and
                // the run between them goes on from wherever it is.
                frame = home
                if (place != null) morphIcon = place.second
                morphTo(if (place != null) 1f else 0f)
                render()
            }
            is Gesture.Resize -> {
                val next = g.resized(gesture.start, gesture.corner, dx, dy, room, density)
                commit(next)
                setShowsPage(g.isPage(next.w, density))
            }
        }
    }

    /** The finger lifted: a move let go docked hands its side to the host,
     *  which puts the icon there; anything else rests where it is, and that
     *  place is saved. */
    private fun release() {
        val ended = gesture ?: return
        val move = ended as? Gesture.Move
        val docked = move?.docked
        clearGesture()
        if (docked != null) {
            host.onDocked(displayId, docked, move.fingerY)
            // No icon came of it: the window is the window after all, at
            // the side it was let go at.
            if (added && !leaving) {
                saveBounds()
                morphTo(0f)
            }
            return
        }
        saveBounds()
        // A window still on its way back lays the display out as it lands.
        if (away == 0f) overlayHost.invalidateLayout(displayId)
    }

    /**
     * The display reported a change. Most reports have nothing behind them
     * for this window: a display that adapts its refresh rate sends one as
     * the rate switches, which it does when a window starts to redraw, so
     * one arrives a moment into every resize (Moto G 2025, 2026-11-18: each
     * resize was cut short and the window put back at its saved size). A
     * gesture under way goes on through those. Only a display that is
     * really another shape (its size, its rotation, the room its bars
     * leave) ends the gesture: its frame belongs to the old geometry, and
     * the window goes back to its rest place in the new one, saving
     * nothing.
     */
    fun onDisplayChanged() {
        if (gesture == null) return
        val env = overlayHost.environment(displayId)
        val unchanged = env != null &&
            g.room(env.geometry.width, env.geometry.height, env.barRoom) == room &&
            (env.geometry.width >= env.geometry.height) == landscape
        if (!unchanged) endGesture()
    }

    /** End a gesture where it stands, saving nothing; a window it had
     *  docked is the window again. */
    private fun endGesture() {
        if (gesture == null) return
        clearGesture()
        if (away != 0f && !leaving) morphTo(0f)
    }

    private fun clearGesture() {
        gesture = null
        claim = Claim.NONE
    }

    // ── Pulled out of the icon ────────────────────────────────────────────

    /**
     * Put the window up as the icon at [icon], [side]'s, for a finger that
     * is on the icon's own window and has begun to drag it: the same move
     * a finger on the bar makes, begun docked. The finger holds the bar by
     * its end at the icon's side, so the window comes out from under it as
     * it leaves the side and goes back in as it returns. The gesture is
     * the icon's: its steps come through [pullTo], its end through
     * [pullEnd], and a system cancel is a [remove]. False when the add
     * failed.
     */
    fun showPulled(icon: Rect, side: Side, rawX: Float, rawY: Float): Boolean {
        if (!addToDisplay()) return false
        val rest = frame
        val room = room
        if (rest == null || room == null) {
            // No display to lay it out on: nothing to pull.
            remove()
            return false
        }
        val grab = (PULL_GRAB_DP * density).roundToInt()
        val start = Frame(
            x = rawX.roundToInt() - if (side == Side.RIGHT) rest.w - grab else grab,
            y = rawY.roundToInt() - barPx / 2,
            w = rest.w,
            h = rest.h,
        )
        downX = rawX
        downY = rawY
        gesture = Gesture.Move(start).apply {
            docked = side
            fingerY = rawY.roundToInt()
        }
        frame = g.moved(start, 0, 0, room, density)
        morphIcon = icon
        away = 1f
        render()
        return true
    }

    /** The pulling finger moved. */
    fun pullTo(rawX: Float, rawY: Float) = dragTo(rawX, rawY)

    /**
     * The pulling finger lifted. Null when the window was out: it rests
     * where it is and that place is saved, the window from here on. Else
     * the side and the height the icon was left at; the window, showing as
     * that icon, is the caller's to [remove].
     */
    fun pullEnd(): Pair<Side, Int>? {
        val move = gesture as? Gesture.Move
        val docked = move?.docked
        clearGesture()
        if (move != null && docked != null) return docked to move.fingerY
        saveBounds()
        if (away == 0f) overlayHost.invalidateLayout(displayId)
        return null
    }

    // ── The bar's popovers ────────────────────────────────────────────────

    private fun toggle(anchor: View, content: () -> PopoverContent) {
        val wasOpen = popovers.anchor === anchor
        popovers.dismiss()
        if (!wasOpen) popovers.show(content(), anchor)
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

        /** A touch that went down while a corner's pad held a resize, or
         *  while the window was in flight: the window takes none of it. */
        private var ignoring = false

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                if (!leaving) host.onTouched(displayId)
                // A touch that goes down on a window with a gesture under way
                // is a second one; one on a window between its two forms has
                // nothing to take hold of.
                ignoring = gesture != null || away != 0f || leaving
            }
            if (ignoring) return true
            return super.dispatchTouchEvent(ev)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    claim = claimAt(ev.x, ev.y)
                    return false
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
                    dragTo(ev.rawX, ev.rawY)
                }
                MotionEvent.ACTION_UP -> release()
                MotionEvent.ACTION_CANCEL -> cancelGesture()
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
            // In flight the card is scaled inside the window: the handles
            // wait for it to land.
            if (away != 0f || leaving) return
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

        /** The page card's corner radius. */
        const val PAGE_CORNER_DP = 16f

        /** The handles' stroke: centred on the card's edge, so half of it is
         *  the window's rim. */
        const val HANDLE_STROKE_DP = 4f
        private const val HANDLE_ARM_DP = 16f
        private const val HANDLE_ALPHA = 0x80

        /** How far a corner handle's touch target reaches into the card, and
         *  how far past its corner. */
        const val HANDLE_HIT_INSIDE_DP = 34f
        const val HANDLE_HIT_OUTSIDE_DP = 24f

        /** How long the whole way between the window and the icon takes. */
        const val FLIGHT_MS = 220L

        /** The most pixels the flight out's picture of the card holds. */
        private const val MAX_PICTURE_PX = 1_500_000f

        /** Where along its bar a window pulled out of the icon is held: this
         *  far in from the bar's end at the icon's side ([showPulled]). */
        private const val PULL_GRAB_DP = 28f
    }
}

/** The platform's private window flag "never animate position changes of the
 *  window": the bit [WindowManager.LayoutParams.setCanPlayMoveAnimation] sets
 *  from API 34, 0x40 in every release from Android 10 to 13 (read from each
 *  release's WindowManager.java). */
internal const val PRIVATE_FLAG_NO_MOVE_ANIMATION = 0x40

/**
 * Turn off the system's own animation of this window's moves. Returns
 * whether it could.
 *
 * The system animates a window whose place changes together with its size,
 * from the last frame it has on record for the window to the new one, and a
 * change of place alone does not bring that record up to date. So the first
 * resize of the detached window after a move slid it in from where it stood
 * before the move (reported 2026-11-18; the stale record seen in the Thor's
 * window dump, and the slide gone on the Moto G once the switch was set).
 * That window is placed by the finger and by its own flights, never by the
 * system.
 *
 * From API 34 the platform has a switch for it. Below that the only one is
 * the private flag behind that switch, set through the hidden `privateFlags`
 * field: on Google's published non-SDK lists the field is "unsupported"
 * (usable, no promise it stays) for Android 10 to 13, the only releases
 * this branch runs on, and those lists are final. It is the one hidden
 * member this app touches, and it is an ornament: where a device does not
 * have the field or refuses it (a manufacturer may block what the lists
 * allow), nothing is set, nothing is thrown, and that device keeps the
 * slide. [sdk] and [privateFlags] are for a test to stand in for such a
 * device.
 */
@SuppressLint("DiscouragedPrivateApi")
internal fun WindowManager.LayoutParams.turnOffSystemMoveAnimation(
    sdk: Int = Build.VERSION.SDK_INT,
    privateFlags: () -> java.lang.reflect.Field = { WindowManager.LayoutParams::class.java.getField("privateFlags") },
): Boolean {
    if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        setCanPlayMoveAnimation(false)
        return true
    }
    return try {
        val field = privateFlags()
        field.setInt(this, field.getInt(this) or PRIVATE_FLAG_NO_MOVE_ANIMATION)
        true
    } catch (e: Exception) {
        Log.i("DetachedWindow", "no switch for the system's move animation here: ${e.javaClass.simpleName}")
        false
    } catch (e: LinkageError) {
        Log.i("DetachedWindow", "no switch for the system's move animation here: ${e.javaClass.simpleName}")
        false
    }
}
