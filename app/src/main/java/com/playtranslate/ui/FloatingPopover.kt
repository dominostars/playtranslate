package com.playtranslate.ui

import android.content.Context
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.VisibleForTesting
import androidx.core.view.doOnLayout
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.OverlayPlacement

/**
 * A surface's popovers in an overlay window of their own, as large as the
 * display: for a surface that can be smaller than a menu. The detached
 * window is one: its more menu, its quick settings and its page's text-size
 * picker would be clipped by a window made small, so they open here, above
 * it, and may reach past its edges.
 *
 * The card, its arrow and its outside-tap scrim are [PopoverHost]'s, the same
 * as every in-window popover, with the anchor (a view of the other window)
 * placed by where it is on the screen. The window goes up for one popover
 * and comes down with it, a fresh root each time (a removed view is never
 * added again). While it is up it takes every touch on the display: a tap
 * off the card closes it and reaches nothing under it. It takes no key
 * focus, so the game keeps its keys.
 *
 * Registered with the [overlayHost] like every window of ours, so a capture
 * taken while it is up does not see it.
 */
internal class FloatingPopover(
    private val ctx: Context,
    private val wm: WindowManager,
    private val displayId: Int,
    private val overlayHost: OverlayHost,
) {
    private class Shown(
        val root: FrameLayout,
        val host: PopoverHost,
        val content: PopoverContent,
        val anchor: View,
    )

    private var shown: Shown? = null

    /** A popover is up, or its window is and the card follows on its first
     *  layout. */
    val isShowing: Boolean get() = shown != null

    /** The view the popover points at, or null. */
    val anchor: View? get() = shown?.anchor

    val content: PopoverContent? get() = shown?.content

    /** The popover's window root, for a test to lay out and search. */
    @get:VisibleForTesting
    internal val rootForTest: View? get() = shown?.root

    /** Show [content] on [anchor], replacing whatever is up. */
    fun show(content: PopoverContent, anchor: View) {
        dismiss()
        val root = FrameLayout(ctx)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayHost.windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        if (!overlayHost.addOverlayWindow(root, wm, params, displayId, OverlayPlacement.Content)) return
        val host = PopoverHost(root, anchorsInOtherWindows = true)
        val s = Shown(root, host, content, anchor)
        shown = s
        host.addListener(object : PopoverHost.Listener {
            override fun onPopoverChanged(open: Boolean, content: PopoverContent, anchor: View) {
                if (!open) close(s)
            }
        })
        // The card is sized and placed against the window, so it waits for
        // the window's first layout; posted, so nothing is added to the tree
        // from inside that layout pass.
        root.doOnLayout {
            root.post {
                if (shown !== s) return@post
                host.show(content, anchor)
                // The anchor went meanwhile: nothing to show.
                if (!host.isShowing) close(s)
            }
        }
    }

    /** Close whatever is up. */
    fun dismiss() {
        val s = shown ?: return
        // A card that is up closes through its host, whose listener takes
        // the window down; one not up yet has only the window.
        if (!s.host.dismiss()) close(s)
    }

    private fun close(s: Shown) {
        if (shown === s) shown = null
        s.host.release()
        overlayHost.removeOverlayWindow(s.root)
    }
}
