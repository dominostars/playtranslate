package com.playtranslate.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.playtranslate.overlay.OverlayHost
import kotlinx.coroutines.CoroutineScope

/**
 * One screen inside the floating [OverlayWorkspace] — the over-game window
 * that hosts flows which previously escaped to full-screen Activities
 * (language picker, word detail, the Anki editor). A page builds a plain view
 * tree ([onCreateView]) into the workspace's page container; the workspace
 * owns the back stack, the header (title + back chevron), dismissal, and
 * controller navigation.
 *
 * Pages are plain views by design: the workspace is a WindowManager window
 * with no FragmentManager, no Activity, and a plain [android.view.LayoutInflater]
 * that silently drops `app:` attributes — the same constraints as every other
 * overlay surface ([CaptureResultOverlay], [MagnifierLens]).
 */
interface WorkspacePage {
    /** Shown in the workspace header while this page is topmost. */
    fun title(ctx: Context): CharSequence

    /** Build the page's content into [parent] (do NOT add it — the workspace
     *  does). Called once per push; the view lives until [onDestroy]. */
    fun onCreateView(ctx: Context, parent: ViewGroup, host: WorkspaceHost): View

    /** Controller-reachable actions on this page, collected per keypress
     *  (no registry to go stale) — same contract as
     *  [CaptureSheetNavHost.navActions]. */
    fun navActions(): List<NavAction> = emptyList()

    /** The page's scroll viewport, for right-stick scroll + the focus ring's
     *  ensure-visible/clip. Null → no scrolling, ring unclipped. */
    fun scrollView(): ViewGroup? = null

    /** Page-level back (B / X-less inner modal). True = consumed; false lets
     *  the workspace pop (or dismiss at depth 1). */
    fun onBack(): Boolean = false

    /** The page's view left the container (pop or workspace dismissal) —
     *  release WebViews, players, collectors. Must be idempotent. */
    fun onDestroy() {}
}

/**
 * What a [WorkspacePage] can ask of the workspace that hosts it. Handed to
 * [WorkspacePage.onCreateView]; valid until the page's [WorkspacePage.onDestroy].
 */
interface WorkspaceHost {
    /** Themed ([com.playtranslate.overlayThemedContext]), display-scoped. */
    val ctx: Context

    /** Main-thread scope, cancelled when the workspace dismisses. Page-scoped
     *  work that must stop on pop needs its own child Job. */
    val scope: CoroutineScope

    val displayId: Int
    val wm: WindowManager
    val overlayHost: OverlayHost

    /** Where in-window modal surfaces live (alerts, progress) — children of
     *  the workspace's own window, never sibling overlay windows (the
     *  MediaProjection QTI clamp dims a sibling to ~80% and it steals taps;
     *  see [FontSizeRangePopover]'s class doc). Pages present into it via
     *  [alert]/[showProgress]. */
    val modalLayer: FrameLayout

    /** Push a page onto the back stack (back chevron + B pop it). */
    fun push(page: WorkspacePage)

    /** Pop the top page; at depth 1 this dismisses the workspace. */
    fun pop()

    /** Tear the whole workspace down (a flow completed). */
    fun dismiss()

    /** Retitle the header for the topmost page (e.g. after a data load). */
    fun setTitle(title: CharSequence)

    /** Put a custom view (e.g. a segmented mode toggle) in the header's
     *  centre, replacing the title — the workspace header IS the page's
     *  navigation bar; a page must never render a second bar of its own.
     *  Callable during [WorkspacePage.onCreateView] (it binds to the page
     *  being pushed); per-page, restored on pop. Null clears back to the
     *  title. The view should carry FrameLayout LayoutParams (centre
     *  gravity, explicit width — the header centre is ~56dp in from each
     *  edge for the chevron/X wells). */
    fun setHeaderView(view: View?)

    /** Flip the window's focus/IME policy for in-page text entry — true while
     *  an EditText needs the keyboard, false when editing ends. The workspace
     *  restores controller focus (when a controller is attached) on false. */
    fun setImeMode(wantsIme: Boolean)

    /** Park the workspace window while a helper Activity runs above the
     *  game (the audio picker) — an overlay window would otherwise cover
     *  it. The launching page un-parks from its result gate. */
    fun setParkedForActivity(parked: Boolean)

    /** Show an in-window progress modal (B / its Cancel button cancel it with
     *  [DismissReason.USER], matching the Activity flows' back-press). */
    fun showProgress(title: String, onDismiss: (DismissReason) -> Unit): OverlayProgress

    /** Builder for an in-window alert — finish it with
     *  `.showInParent(host.modalLayer)`. B dismisses it like a scrim tap. */
    fun alert(): OverlayAlert.Builder

    /** Re-target the controller cursor after a layout-changing activation. */
    fun invalidateNav()
}
