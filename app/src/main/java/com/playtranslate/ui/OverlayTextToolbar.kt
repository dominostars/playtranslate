package com.playtranslate.ui

import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.playtranslate.R
import com.playtranslate.themeColor

/**
 * A text field's Cut / Copy / Paste bar in one of the app's overlay windows
 * ([OverlayWindowRoot]): the bar the decor's floating action mode draws in an
 * activity, kept to what a field needs. The field's own callback fills the
 * menu (Cut, Copy, Paste, Select all, and whatever else the platform offers
 * for that field and that selection) and does what a tap on an item asks.
 * This draws the items as a row of text buttons on a card, above the
 * selection, or under it where there is no room above, and moves with the
 * selection. The field ends the bar as it ends the platform's, by [finish]:
 * when it or its window loses focus, when it detaches or stops showing, and
 * when an item is done.
 *
 * A window of its own attached to the field's, as the platform's bar and the
 * selection handles are, and not a view in the field's window: in there it
 * would come under that root's own touch rules (the sheet's resize band and
 * outside taps, the workspace's tap off its card), and each would have to
 * know of it. It takes no focus, so the field's window keeps its focus,
 * which the selection needs (a window that loses it ends the selection), and
 * the keyboard stays up. It is kept inside the field's window, so a tap on it
 * lands nowhere the window does not already cover. Unlike our other windows
 * it is not hidden from a capture taken while it is up
 * ([com.playtranslate.overlay.OverlayHost]); neither are the platform's
 * selection handles, which show with it.
 */
internal class OverlayTextToolbar private constructor(
    private val field: TextView,
    private val callback: ActionMode.Callback,
) : ActionMode() {

    private val ctx = field.context
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

    /** What the callback fills: a PopupMenu's menu, the PopupMenu never
     *  shown, which is the SDK's way to a [Menu]. The items' titles are the
     *  platform's words, in this context's language. */
    private val menu: Menu = PopupMenu(ctx, field).menu

    /** The items, one text button each, in the menu's order ([showItems]). */
    private val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

    /** The card of the app's popovers ([PopoverHost]), holding the row, which
     *  scrolls sideways in a window narrower than it. */
    private val card = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        background = GradientDrawable().apply {
            setColor(ctx.themeColor(R.attr.ptElevated))
            cornerRadius = dp(PopoverHost.CARD_RADIUS_DP).toFloat()
            setStroke(dp(1f), ctx.themeColor(R.attr.ptDivider))
        }
        // The buttons' press ripples keep inside the rounded corners.
        clipToOutline = true
        addView(row, ViewGroup.LayoutParams(WRAP, WRAP))
    }

    private val popup = PopupWindow(card, WRAP, WRAP, false).apply {
        // Over the field's window and the panels on it, at the selection
        // handles' type (the platform's bar is a type above theirs, one the
        // SDK does not offer).
        windowLayoutType = WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL
    }

    /** The selection in the field's coordinates, as the callback last gave
     *  it ([invalidateContentRect]): with a caret, the caret and its handle. */
    private val content = Rect()

    // Scratch, in the field's window's coordinates (place, locate).
    private val anchor = Rect()
    private val shown = Rect()
    private val room = Rect()
    private val location = IntArray(2)

    /** The room's width [card] was last measured in; -1 after the items
     *  change, so the next placing measures it afresh. */
    private var measuredFor = -1

    /** The field asked the bar off for a while ([hide]). */
    private var hideRequested = false
    private var finished = false
    private var observer: ViewTreeObserver? = null

    private val showAgain = Runnable {
        hideRequested = false
        place()
    }

    /** Every frame of the field's window: the field may have moved (a scroll,
     *  the sheet resizing, the keyboard coming), and the bar goes with it, as
     *  the decor moves its own bar. */
    private val follow = ViewTreeObserver.OnPreDrawListener {
        place()
        true
    }

    init {
        type = ActionMode.TYPE_FLOATING
    }

    /** Whether the bar has finished, for good. */
    internal val isFinished: Boolean get() = finished

    /** Whether the bar is on screen now, for a test. */
    @get:VisibleForTesting
    internal val isShowing: Boolean get() = popup.isShowing

    /** The bar's row of buttons, for a test to read and tap. */
    @get:VisibleForTesting
    internal val rowForTest: LinearLayout get() = row

    override fun invalidate() {
        callback.onPrepareActionMode(this, menu)
        showItems()
        invalidateContentRect()
    }

    override fun invalidateContentRect() {
        val cb = callback
        if (cb is ActionMode.Callback2) cb.onGetContentRect(this, field, content)
        else content.set(0, 0, field.width, field.height)
        place()
    }

    /** The field asks the bar off for [duration] ms, as the platform's bar
     *  takes it: [ActionMode.DEFAULT_HIDE_DURATION] for the system's while,
     *  never past [MAX_HIDE_MS], and 0 or less to bring it back now (the
     *  field does so while a finger moves on the text, and again after the
     *  finger lifts). */
    override fun hide(duration: Long) {
        val ms = (
            if (duration == ActionMode.DEFAULT_HIDE_DURATION.toLong()) ViewConfiguration.getDefaultActionModeHideDuration()
            else duration
        ).coerceAtMost(MAX_HIDE_MS)
        field.removeCallbacks(showAgain)
        if (ms <= 0) {
            showAgain.run()
            return
        }
        hideRequested = true
        place()
        field.postDelayed(showAgain, ms)
    }

    /** Once: a second finish (a clean capture's, of a bar already ending)
     *  does nothing. */
    override fun finish() {
        if (finished) return
        finished = true
        field.removeCallbacks(showAgain)
        observer?.let { if (it.isAlive) it.removeOnPreDrawListener(follow) }
        observer = null
        popup.dismiss()
        callback.onDestroyActionMode(this)
    }

    override fun getMenu(): Menu = menu

    override fun getMenuInflater(): MenuInflater = MenuInflater(ctx)

    // A floating bar has no title and no view of the field's own, as the
    // platform's has none.
    override fun setTitle(title: CharSequence?) {}
    override fun setTitle(resId: Int) {}
    override fun setSubtitle(subtitle: CharSequence?) {}
    override fun setSubtitle(resId: Int) {}
    override fun setCustomView(view: View?) {}
    override fun getTitle(): CharSequence? = null
    override fun getSubtitle(): CharSequence? = null
    override fun getCustomView(): View? = null

    /** The menu's visible items as the row's buttons, in its order: a tap
     *  hands the item to the callback, which does what it says. Run at each
     *  [invalidate], since the callback's prepare adds and removes items
     *  (Select all goes once everything is selected). */
    private fun showItems() {
        row.removeAllViews()
        val pressed = TypedValue().also {
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            if (!item.isVisible) continue
            val button = TextView(ctx).apply {
                text = item.title
                item.contentDescription?.let { contentDescription = it }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_SP)
                setTextColor(ctx.themeColor(R.attr.ptText))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(ITEM_PAD_DP), 0, dp(ITEM_PAD_DP), 0)
                setBackgroundResource(pressed)
                isEnabled = item.isEnabled
                if (!item.isEnabled) alpha = DISABLED_ALPHA
                setOnClickListener { callback.onActionItemClicked(this@OverlayTextToolbar, item) }
            }
            row.addView(button, LinearLayout.LayoutParams(WRAP, dp(ITEM_H_DP)))
        }
        measuredFor = -1
    }

    /** Put the bar over the selection where it now is: above it while there
     *  is room above, else under it, else as near above as the room allows;
     *  centred on it, inside the room. Off while the field asks it hidden,
     *  is not showing, or shows none of the selection. */
    private fun place() {
        if (finished) return
        if (hideRequested || !field.isShown || !locate()) {
            popup.dismiss()
            return
        }
        if (measuredFor != room.width()) {
            card.measure(
                View.MeasureSpec.makeMeasureSpec(room.width(), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            measuredFor = room.width()
        }
        val w = card.measuredWidth
        val h = card.measuredHeight
        val gap = dp(GAP_DP)
        val above = anchor.top - gap - h
        val below = anchor.bottom + gap
        val y = when {
            above >= room.top -> above
            below + h <= room.bottom -> below
            else -> above.coerceIn(room.top, (room.bottom - h).coerceAtLeast(room.top))
        }
        val x = (anchor.centerX() - w / 2).coerceIn(room.left, (room.right - w).coerceAtLeast(room.left))
        if (popup.isShowing) {
            popup.update(x, y, w, h)
        } else {
            popup.width = w
            popup.height = h
            // LEFT, not START: x is from the window's left edge, which START
            // would turn into its right one in a right-to-left language.
            popup.showAtLocation(field, Gravity.LEFT or Gravity.TOP, x, y)
        }
    }

    /** Where the selection is in the field's window ([anchor]), cut to the
     *  part of the field that shows, and the room the bar has there
     *  ([room]): the window less what the system bars, the cutout and the
     *  keyboard cover of it, and a margin at its sides. False while none of
     *  the selection shows (a scroll has taken it out of sight), or no room
     *  is left. */
    private fun locate(): Boolean {
        if (!field.getGlobalVisibleRect(shown)) return false
        field.getLocationInWindow(location)
        anchor.set(content)
        anchor.offset(location[0], location[1])
        // Touching counts, as for the platform's bar: a caret at the
        // field's very edge still has its bar.
        if (anchor.left > shown.right || shown.left > anchor.right ||
            anchor.top > shown.bottom || shown.top > anchor.bottom
        ) return false
        anchor.left = maxOf(anchor.left, shown.left)
        anchor.right = minOf(anchor.right, shown.right)
        anchor.top = maxOf(anchor.top, shown.top)
        val root = field.rootView
        room.set(0, 0, root.width, root.height)
        ViewCompat.getRootWindowInsets(root)?.getInsets(COVERED)?.let {
            room.left += it.left
            room.top += it.top
            room.right -= it.right
            room.bottom -= it.bottom
        }
        // Never flush with the window's sides, as the platform's bar keeps
        // off the screen's.
        room.left += dp(EDGE_MARGIN_DP)
        room.right -= dp(EDGE_MARGIN_DP)
        return !room.isEmpty
    }

    companion object {
        /** The bar for [field] and its [callback], on screen; null when the
         *  callback declines one at its start, as under a decor. */
        fun start(field: TextView, callback: ActionMode.Callback): OverlayTextToolbar? {
            val bar = OverlayTextToolbar(field, callback)
            if (!callback.onCreateActionMode(bar, bar.menu)) return null
            bar.observer = field.viewTreeObserver.also { it.addOnPreDrawListener(bar.follow) }
            // As the decor starts its own: the callback's prepare, then the
            // bar placed and shown.
            bar.invalidate()
            return bar
        }

        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** What the bar keeps out of: the system bars, the cutout, the keyboard. */
        private val COVERED = WindowInsetsCompat.Type.systemBars() or
            WindowInsetsCompat.Type.displayCutout() or
            WindowInsetsCompat.Type.ime()

        /** Between the selection and the bar, the platform bar's. */
        private const val GAP_DP = 8f

        /** Between the bar and the window's sides, the platform bar's
         *  (floating_toolbar_horizontal_margin). */
        private const val EDGE_MARGIN_DP = 16f

        /** A button's height, a touch target's. */
        private const val ITEM_H_DP = 48f
        private const val ITEM_PAD_DP = 16f
        private const val LABEL_SP = 14f
        private const val DISABLED_ALPHA = 0.38f

        /** The platform's bar's longest hide (FloatingActionMode). */
        private const val MAX_HIDE_MS = 3000L
    }
}
