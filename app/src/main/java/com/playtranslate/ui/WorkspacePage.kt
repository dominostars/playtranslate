package com.playtranslate.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.playtranslate.capture.CaptureBackendResolver
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

    /** An in-page popover is open ([PopoverHost]: the results' text-size
     *  picker or a header's ⋯ menu). While one is, the page's [navActions]
     *  are the popover's alone, and the workspace keeps the controller out
     *  of its own header and stops the right stick scrolling under it. */
    val isPopoverOpen: Boolean get() = false

    /** Close any in-page popover (the page is being hidden without being
     *  destroyed, e.g. a tab switch). */
    fun dismissPopovers() {}

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

    /** Suspends until the workspace's enter animation has settled (returns
     *  immediately once it has, and forever after). Heavy completion binds —
     *  styled-definition WebViews, large content trees — await this before
     *  touching the view tree, so their main-thread cost can't land inside
     *  the enter window and drop its frames. Guaranteed to unblock: the end
     *  action completes it, and dismissal sweeps it (end actions stand down
     *  on cancel). Re-check liveness after resuming. */
    suspend fun awaitEnterSettled() {}

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

    /** Re-target the controller cursor after a layout-changing activation.
     *  If the cursor's item is out of reach, it moves to [prefer] when that
     *  is a current target (a popover's first row as it opens, its anchor as
     *  it closes), else to whatever sits nearest. */
    fun invalidateNav(prefer: View? = null)
}

/**
 * How a flow reaches the floating workspace from wherever it started —
 * the one decision every over-game entry (the lens's open/Anki chips, the
 * sheet's sentence Anki) used to make inline as a Boolean plus an
 * `openWorkspace` call. [present] returns false when the workspace is not
 * available on this route (an Activity-routed host, or the coordinator
 * posture: dual-screen with the app foregrounded), and every caller falls
 * back to its Activity launch on false.
 */
sealed interface WorkspaceRoute {
    /** Show [page] in the workspace; false = not available here. */
    fun present(screenshotPath: String?, page: (WorkspaceHost) -> WorkspacePage): Boolean

    /** Called right before a flow on this route launches an Activity
     *  instead (the permission trampolines, the results activity): a
     *  workspace page's surface must get out of the way — its overlay window
     *  would otherwise sit above the launched activity — and it must do so
     *  PROGRAMMATICALLY, so no stashed capture sheet re-shows underneath
     *  (the word page's trampoline precedent). No-op elsewhere. */
    fun prepareActivityLaunch() {}

    /** Activity-routed hosts (the camera, the image import): never the
     *  workspace. */
    data object None : WorkspaceRoute {
        override fun present(screenshotPath: String?, page: (WorkspaceHost) -> WorkspacePage) = false
    }

    /** A fresh workspace over the game on [displayId], replacing any showing
     *  one — the entry from a surface that is not itself a workspace page
     *  (the drag lens, the capture sheet). False under the coordinator
     *  posture (see [com.playtranslate.OverlayUiController.openWorkspace]). */
    data class OpenNew(val displayId: Int) : WorkspaceRoute {
        override fun present(screenshotPath: String?, page: (WorkspaceHost) -> WorkspacePage): Boolean =
            CaptureBackendResolver.activeOverlayUi?.openWorkspace(displayId, screenshotPath, page) == true
    }

    /** The workspace hosting the caller (a page's own lens or Anki entry):
     *  push onto its back stack, so back returns to the page. The
     *  screenshot is ignored — the card's ground was frosted at open. */
    class PushInto(private val host: WorkspaceHost) : WorkspaceRoute {
        override fun present(screenshotPath: String?, page: (WorkspaceHost) -> WorkspacePage): Boolean {
            host.push(page(host))
            return true
        }

        override fun prepareActivityLaunch() {
            CaptureBackendResolver.activeOverlayUi?.dismissWorkspace()
        }
    }
}
