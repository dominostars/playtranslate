package com.playtranslate.ui

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.FocusFinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.core.view.OneShotPreDrawListener
import com.playtranslate.R
import com.playtranslate.themeColor

/**
 * Scroll this view fully into sight inside its nearest [ScrollView]
 * ancestor, and no further. The controller navs call it for a popover's row:
 * the row lives outside the page's own scroll (which their ensure-visible
 * serves), but a short window can make the menu around it scroll. Unlike
 * [View.requestRectangleOnScreen] it stops at that ScrollView instead of
 * walking on to the window. No-op outside any ScrollView.
 */
internal fun View.revealInScrollingAncestor() {
    var child: View = this
    var parent = child.parent
    while (parent is ViewGroup) {
        if (parent is ScrollView) {
            val r = Rect(0, 0, width, height)
            if (child !== this) (child as ViewGroup).offsetDescendantRectToMyCoords(this, r)
            parent.requestChildRectangleOnScreen(child, r, true)
            return
        }
        child = parent
        parent = child.parent
    }
}

/** What a [PopoverHost] shows inside its card. */
interface PopoverContent {
    /** Build the card's inside, fresh for each show. [host] is the host
     *  showing it: content that closes itself calls [PopoverHost.dismiss].
     *  The card is never taller than its host can show (a short window caps
     *  it), so content that can outgrow that must scroll. */
    fun createView(ctx: Context, host: PopoverHost): View

    /** The card's width in px, given [natural], the content's own width
     *  measured unconstrained. */
    fun cardWidth(ctx: Context, natural: Int): Int

    /** Controller targets while this shows. Empty (the default) means a
     *  controller can only dismiss it: the navs swallow everything else. */
    fun navActions(): List<NavAction> = emptyList()

    /** The popover left the screen: dismissed, replaced or released. */
    fun onDismissed() {}
}

/**
 * The one in-window popover of a surface: a rounded card with an [ArrowView]
 * tying it to the view that opened it, over a transparent full-size scrim
 * whose tap dismisses it. The results page's root and the capture sheet's
 * root each own one; the text-size picker ([FontSizeRangePopover]) and the
 * section headers' ⋯ menu ([ActionOverflowMenu]) are its contents.
 *
 * Shown as CHILD VIEWS of [host], never as a window: the capture sheet is one
 * full-screen window whose in-window children are the only things
 * MediaProjection's QTI clamp leaves undimmed (a sibling overlay window
 * renders at ~80% and steals the panel's taps), and hosting as a child is
 * also what lets the in-app page and every overlay panel share this one
 * implementation. Reverse z-order dispatch makes card > scrim > content, so a
 * popover is modal while open and any tap outside it dismisses. Everything
 * is built in code: the overlay inflates with a plain LayoutInflater that
 * drops `app:` attributes.
 *
 * ONE popover at a time per host, and one answer to "is a popover up"
 * ([isShowing]): the sheet's touch rules, its B ladder, both controller
 * navs, the workspace's back and the in-app back all ask it, so a new kind
 * of popover needs no new check anywhere.
 *
 * Placement: above the anchor if the card fits there, else below (the side
 * is chosen once, at show); centred on it, clamped to the host, and never
 * taller than the host (a short window caps the card; its content scrolls).
 * The card then FOLLOWS its anchor: a pre-draw hook re-places it, on the
 * same side, whenever the anchor has moved in host space — a result landing
 * under an open menu grows the sheet or re-fits the cards and moves the
 * header, and a card left where it opened would point at nothing.
 *
 * A popover is only valid while its anchor is there to act for: once the
 * anchor detaches or it, or anything between it and the host, goes GONE (a
 * status takeover replaces the results, a column collapses, the header
 * re-folds), a posted dismiss closes it. INVISIBLE does not count: the
 * in-app page hides its results for a frame or two while it re-fits each
 * render, and that must not close a picker mid-drag.
 *
 * Native focus (the in-app page's dpad): an anchor that had focus hands it
 * to the card's first focusable once the card is laid out, focus search
 * stays inside the card while it's open, and dismissal hands focus back to
 * the anchor before the views go (otherwise the window re-focuses its first
 * focusable). The virtual controller navs never give views native focus.
 *
 * [below] (optional) is a host child the popover must stay under: the
 * capture sheet passes its controller focus ring, which then outlines the
 * menu's rows.
 */
class PopoverHost(
    private val host: FrameLayout,
    private val below: View? = null,
) {
    /** Follows the popover. */
    interface Listener {
        /** A popover opened (at [show], before it has laid out) or closed.
         *  Synchronous with the state change, so [isShowing] already agrees:
         *  a back handler arms and disarms here. */
        fun onPopoverChanged(open: Boolean, content: PopoverContent, anchor: View) {}

        /** The popover that just opened has laid out: its rows have a size
         *  and a place, so a controller nav can ring the first one. */
        fun onPopoverLaidOut(content: PopoverContent, anchor: View) {}
    }

    /** The showing popover's content, or null. */
    var content: PopoverContent? = null
        private set

    /** The view the showing popover points at, or null. */
    var anchor: View? = null
        private set

    val isShowing: Boolean get() = content != null

    /** The showing popover's views and geometry, kept to re-place it as its
     *  anchor moves. [anchorRect] is where the anchor was when last placed. */
    private class Placement(
        val box: View,
        val arrow: View,
        val cardW: Int,
        val totalH: Int,
        val arrowW: Int,
        val arrowEdge: Int,
        val gap: Int,
        val pointsDown: Boolean,
    ) {
        val anchorRect = Rect()
    }

    private var scrim: View? = null
    private var placement: Placement? = null
    private var observer: ViewTreeObserver? = null
    private val listeners = ArrayList<Listener>()
    private var released = false
    private val syncRect = Rect()

    /** Bumped on every show and dismiss, so a posted step from an earlier
     *  popover can't touch a later one. */
    private var generation = 0

    /** Every frame while a popover shows: close it once its anchor is gone,
     *  else re-place it if the anchor moved. Placing here, after layout and
     *  before draw, lands the card in the same frame as the move. */
    private val anchorSync = ViewTreeObserver.OnPreDrawListener {
        val a = anchor
        val p = placement
        if (a != null && p != null) {
            if (!anchorRectInHost(a, syncRect)) {
                val gen = generation
                host.post { if (gen == generation) dismiss() }
            } else if (syncRect != p.anchorRect) {
                place(p, syncRect)
            }
        }
        true
    }

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /** The showing popover's controller targets, or null when none shows. */
    fun navActions(): List<NavAction>? = content?.navActions()

    /** Show [content] on [anchor], or close whatever shows. */
    fun toggle(content: PopoverContent, anchor: View) {
        if (isShowing) dismiss() else show(content, anchor)
    }

    fun show(content: PopoverContent, anchor: View) {
        // Where the anchor sits in host space (it lives several levels down
        // inside a scroll on every surface). No anchor on screen, no popover.
        val r = Rect()
        if (released || !anchorRectInHost(anchor, r)) return
        dismiss()
        val gen = ++generation
        val ctx = host.context
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float) = v * density

        val inner = content.createView(ctx, this)
        inner.measure(UNSPECIFIED, UNSPECIFIED)
        val cardW = content.cardWidth(ctx, inner.measuredWidth)

        val fill = ctx.themeColor(R.attr.ptElevated)
        val strokePx = dp(1f).toInt()
        val card = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                setColor(fill)
                cornerRadius = dp(CARD_RADIUS_DP)
                setStroke(strokePx, ctx.themeColor(R.attr.ptDivider))
            }
            // The card must swallow touches that land on its padding. Without
            // this the bare FrameLayout declines them, dispatch falls through
            // to the scrim underneath, and a touch a few dp off a control
            // closes the popover instead.
            isClickable = true
            // ...but never take focus itself: a clickable view is focusable
            // by default (focusable="auto"), and the card would then catch the
            // focus handed in from the anchor instead of the first row.
            isFocusable = false
            addView(inner, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        // Never taller than the host can show, arrow included. Clamping an
        // oversized card to the top instead would leave its bottom clipped
        // off by the host, and whatever sat there (a menu's last rows)
        // unreachable: the in-app page can be that short in split screen or
        // a freeform window. The content scrolls within the cap.
        val arrowH = dp(ARROW_H_DP).toInt()
        val maxCardH = host.height - arrowH
        card.measure(
            View.MeasureSpec.makeMeasureSpec(cardW, View.MeasureSpec.EXACTLY),
            if (maxCardH > 0) View.MeasureSpec.makeMeasureSpec(maxCardH, View.MeasureSpec.AT_MOST)
            else UNSPECIFIED,
        )
        val cardH = card.measuredHeight
        val totalH = cardH + arrowH

        // The side is picked once: above when the card clears the host's top
        // edge there, else below. Following the anchor later keeps it.
        val gap = dp(ANCHOR_GAP_DP).toInt()
        val pointsDown = r.top - totalH - gap >= 0

        // The arrow overlaps the card's edge by the stroke width so its fill
        // covers the boundary line; otherwise the stroke draws straight across
        // the arrow's base and the two read as separate shapes.
        val arrow = ArrowView(ctx, fill, pointsDown = pointsDown)
        val arrowW = dp(ARROW_W_DP).toInt()
        val arrowEdge = dp(CARD_RADIUS_DP).toInt() + arrowW / 2

        val box = FocusTrap(ctx)
        box.addView(
            card,
            FrameLayout.LayoutParams(cardW, cardH, Gravity.TOP or Gravity.LEFT).apply {
                topMargin = if (pointsDown) 0 else arrowH
            },
        )
        // Added AFTER the card so it draws over that edge stroke. Slid along
        // the edge by translationX ([place]), so following the anchor never
        // needs a relayout.
        box.addView(
            arrow,
            FrameLayout.LayoutParams(
                arrowW, arrowH + strokePx,
                (if (pointsDown) Gravity.BOTTOM else Gravity.TOP) or Gravity.LEFT,
            ),
        )

        // A scrim UNDER the popup, sized to the whole host: it is the only
        // outside-tap handler, which keeps dismissal identical on a surface
        // whose root pre-empts touches (the capture panel) and one that
        // doesn't (the in-app page).
        val outside = View(ctx).apply {
            isClickable = true
            isFocusable = false
            contentDescription = ctx.getString(R.string.cd_close)
            setOnClickListener { dismiss() }
        }
        val index = below?.let { host.indexOfChild(it) }?.takeIf { it >= 0 } ?: host.childCount
        host.addView(outside, index, FrameLayout.LayoutParams(MATCH, MATCH))
        host.addView(
            box,
            index + 1,
            // ABSOLUTE left/top, not the START default: the placement uses
            // setX/setY, which are offsets from the laid-out left edge. Under
            // RTL (Arabic) START resolves to the RIGHT edge, so the default
            // would land the popup a full host-width off.
            FrameLayout.LayoutParams(cardW, totalH, Gravity.TOP or Gravity.LEFT),
        )
        val p = Placement(box, arrow, cardW, totalH, arrowW, arrowEdge, gap, pointsDown)
        place(p, r)

        this.content = content
        this.anchor = anchor
        scrim = outside
        placement = p
        observer = host.viewTreeObserver.also { it.addOnPreDrawListener(anchorSync) }
        for (l in listeners.toList()) l.onPopoverChanged(true, content, anchor)

        val focusIn = anchor.hasFocus()
        OneShotPreDrawListener.add(box) {
            if (gen != generation) return@add
            if (focusIn) box.requestFocus(View.FOCUS_DOWN)
            for (l in listeners.toList()) l.onPopoverLaidOut(content, anchor)
        }
    }

    /** Put the card beside [r], the anchor's rect in host space, on the side
     *  picked at show, centred on it and clamped to the host; the arrow slides
     *  along the card's edge to stay on the anchor's centre. */
    private fun place(p: Placement, r: Rect) {
        p.anchorRect.set(r)
        val x = (r.centerX() - p.cardW / 2).coerceIn(0, (host.width - p.cardW).coerceAtLeast(0))
        val y = if (p.pointsDown) r.top - p.totalH - p.gap else r.bottom + p.gap
        p.box.x = x.toFloat()
        p.box.y = y.coerceIn(0, (host.height - p.totalH).coerceAtLeast(0)).toFloat()
        val arrowCenterX = (r.centerX() - x)
            .coerceIn(p.arrowEdge, (p.cardW - p.arrowEdge).coerceAtLeast(p.arrowEdge))
        p.arrow.translationX = (arrowCenterX - p.arrowW / 2).toFloat()
    }

    /** [a]'s bounds in host space into [out]; false when it is no longer
     *  there to act for: detached, not under the host, or it or anything
     *  between it and the host GONE. INVISIBLE passes (see the class doc). */
    private fun anchorRectInHost(a: View, out: Rect): Boolean {
        if (!a.isAttachedToWindow) return false
        var v: View = a
        while (true) {
            if (v.visibility == View.GONE) return false
            val parent = v.parent
            if (parent === host) break
            v = parent as? View ?: return false
        }
        out.set(0, 0, a.width, a.height)
        host.offsetDescendantRectToMyCoords(a, out)
        return true
    }

    /** Close the showing popover. False when none was showing. */
    fun dismiss(): Boolean {
        val c = content ?: return false
        val a = anchor ?: return false
        generation++
        val box = placement?.box
        if (box != null && box.hasFocus() && a.isShown) a.requestFocus()
        observer?.let {
            if (it.isAlive) it.removeOnPreDrawListener(anchorSync)
            else host.viewTreeObserver.removeOnPreDrawListener(anchorSync)
        }
        observer = null
        box?.let(host::removeView)
        scrim?.let(host::removeView)
        placement = null
        scrim = null
        content = null
        anchor = null
        c.onDismissed()
        for (l in listeners.toList()) l.onPopoverChanged(false, c, a)
        return true
    }

    /** Teardown: close, and refuse any later show (a late tap on a sheet
     *  that is going away). */
    fun release() {
        dismiss()
        released = true
    }

    /** Keeps native focus search inside the popover while it's open: the
     *  window-wide search would otherwise walk off the last row into the
     *  page under the scrim. At the edges focus stays put. */
    private class FocusTrap(ctx: Context) : FrameLayout(ctx) {
        override fun focusSearch(focused: View?, direction: Int): View? =
            FocusFinder.getInstance().findNextFocus(this, focused, direction) ?: focused
    }

    private companion object {
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
        val UNSPECIFIED = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

        const val CARD_RADIUS_DP = 12f
        /** Gap between the anchor and the arrow's tip. */
        const val ANCHOR_GAP_DP = 4f
        const val ARROW_W_DP = 20f
        const val ARROW_H_DP = 10f
    }
}
