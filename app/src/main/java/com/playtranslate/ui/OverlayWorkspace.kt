package com.playtranslate.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.playtranslate.R
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlayThemedContext
import com.playtranslate.themeColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The floating over-game workspace: a rounded card inset
 * [R.dimen.pt_workspace_inset] from every screen edge, hosting a back stack
 * of [WorkspacePage]s — so flows that previously escaped to full-screen
 * Activities (language picker, word detail, the Anki editor) run over the
 * game without pausing it. Dismissed by the corner X, a tap outside the
 * card, or B/back at depth 1.
 *
 * One full-screen WINDOW whose card and modals are in-window children —
 * never sibling overlay windows (the MediaProjection QTI clamp dims a
 * sibling to ~80% and it steals taps; see [FontSizeRangePopover]).
 * Window/focus/IME plumbing rides [SheetHost], the same seam the capture
 * sheet uses; controller navigation is [WorkspaceControllerNav].
 *
 * Built per open (like [FloatingIconMenu]) — a theme/accent change is
 * handled by the next open constructing against the new palette, so no
 * cached-theme drift key is needed. Not rebuilt across display changes:
 * [com.playtranslate.OverlayUiController] dismisses it when its display
 * reconfigures, mirroring the capture sheet's policy.
 */
class OverlayWorkspace(
    rawCtx: Context,
    private val wm: WindowManager,
    private val displayId: Int,
    private val overlayHost: OverlayHost,
    private val sheetHost: SheetHost = WindowSheetHost(wm, displayId, overlayHost),
) {
    private val ctx = overlayThemedContext(rawCtx)
    private val density = ctx.resources.displayMetrics.density
    private val insetPx = ctx.resources.getDimensionPixelSize(R.dimen.pt_workspace_inset)
    private val cardRadiusPx = ctx.resources.getDimension(R.dimen.pt_radius) * CARD_RADIUS_MULT
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Invoked once, on any dismissal path. */
    var onDismiss: (() -> Unit)? = null

    private var dismissed = false
    private var animatingOut = false
    private var imeMode = false
    private var nav: WorkspaceControllerNav? = null
    private var ringSync: ViewTreeObserver.OnPreDrawListener? = null

    private fun dp(v: Float): Int = (v * density).toInt()

    // ── View tree ────────────────────────────────────────────────────────

    private val root = WorkspaceRoot(ctx)
    private val scrim = View(ctx)
    private val cardWrap = FrameLayout(ctx)
    private val card = LinearLayout(ctx)
    private val backBtn = FrameLayout(ctx)
    private val titleView = TextView(ctx)
    private val headerFrame = FrameLayout(ctx)
    private val pageContainer = FrameLayout(ctx)

    /** The top page's custom header content (e.g. the Anki editor's
     *  Sentence/Word toggle), swapped by [updateHeader]. While set, the
     *  title is hidden — the header IS the page's navigation bar, never a
     *  second one stacked above the content's own. */
    private var currentHeaderView: View? = null
    private val closeBtn = FrameLayout(ctx)
    val modalLayer = FrameLayout(ctx)
    private val focusRing = FocusRingView(ctx)

    init {
        // The card's elevation shadow draws outside its own bounds; every
        // ancestor between it and the screen must not clip children.
        root.clipChildren = false
        cardWrap.clipChildren = false

        scrim.setBackgroundColor(Color.argb(SCRIM_ALPHA, 0, 0, 0))
        root.addView(
            scrim,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        card.orientation = LinearLayout.VERTICAL
        card.background = GradientDrawable().apply {
            setColor(ctx.themeColor(R.attr.ptSurface))
            setStroke(dp(1f), ctx.themeColor(R.attr.ptDivider))
            cornerRadius = cardRadiusPx
        }
        // The GradientDrawable supplies the rounded outline: the same shape
        // drives the elevation shadow and the child clip.
        card.outlineProvider = ViewOutlineProvider.BACKGROUND
        card.clipToOutline = true
        card.elevation = CARD_ELEVATION_DP * density

        // Header: back chevron (absolute LEFT — WindowManager roots are
        // absolute; see FloatingIconMenu's RTL rule) + centered bold title,
        // hairline below — the house toolbar pattern.
        val header = headerFrame
        // The 48dp chevron/X touch frames overhang the 44dp header strip.
        header.clipChildren = false
        val chevron = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = ColorStateList.valueOf(ctx.themeColor(R.attr.ptText))
            scaleType = ImageView.ScaleType.FIT_CENTER
            val inset = dp(12f)
            setPadding(inset, inset, inset, inset)
        }
        backBtn.addView(
            chevron,
            FrameLayout.LayoutParams(dp(48f), dp(48f), Gravity.CENTER),
        )
        backBtn.contentDescription = ctx.getString(R.string.cd_back)
        backBtn.foreground = borderlessRipple()
        backBtn.setOnClickListener { onBackPressed() }
        backBtn.visibility = View.GONE
        header.addView(
            backBtn,
            FrameLayout.LayoutParams(dp(48f), dp(48f), Gravity.LEFT or Gravity.CENTER_VERTICAL)
                .apply { leftMargin = dp(4f) },
        )
        titleView.setTextColor(ctx.themeColor(R.attr.ptText))
        titleView.textSize = 16f
        titleView.setTypeface(null, android.graphics.Typeface.BOLD)
        titleView.gravity = Gravity.CENTER
        titleView.maxLines = 1
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        header.addView(
            titleView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply {
                // Keep a long title clear of the chevron well on either side.
                leftMargin = dp(56f)
                rightMargin = dp(56f)
            },
        )
        card.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(HEADER_H_DP)),
        )
        card.addView(
            View(ctx).apply { setBackgroundColor(ctx.themeColor(R.attr.ptDivider)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)),
        )
        card.addView(
            pageContainer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        cardWrap.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            cardWrap,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply {
                leftMargin = insetPx
                topMargin = insetPx
                rightMargin = insetPx
                bottomMargin = insetPx
            },
        )

        // Corner X: the FloatingIconMenu region-chrome pattern — a 32dp
        // circle riding a transparent 48dp tap frame, centred on the card's
        // top-right corner. A direct child of the ROOT (drawn after cardWrap,
        // so above the elevated card): as a cardWrap child its outer half
        // would sit outside cardWrap's bounds where hit testing never offers
        // it the DOWN.
        val circle = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(ctx.themeColor(R.attr.ptText))
            scaleType = ImageView.ScaleType.FIT_CENTER
            val glyphInset = ((CLOSE_BTN_DP * density - dp(16f)) / 2).toInt()
            setPadding(glyphInset, glyphInset, glyphInset, glyphInset)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ctx.themeColor(R.attr.ptCard))
                setStroke(dp(1f), ctx.themeColor(R.attr.ptOutline))
            }
            elevation = 3 * density
        }
        closeBtn.addView(
            circle,
            FrameLayout.LayoutParams(dp(CLOSE_BTN_DP), dp(CLOSE_BTN_DP), Gravity.CENTER),
        )
        closeBtn.contentDescription = ctx.getString(R.string.cd_close)
        closeBtn.foreground = borderlessRipple()
        closeBtn.setOnClickListener { animateOutAndDismiss() }
        val touchPx = ctx.resources.getDimensionPixelSize(R.dimen.pt_touch_target)
        root.addView(
            closeBtn,
            FrameLayout.LayoutParams(touchPx, touchPx, Gravity.TOP or Gravity.RIGHT).apply {
                // 8dp inward from riding the corner exactly: the circle sits
                // slightly inside the card's top-right corner.
                topMargin = insetPx - touchPx / 2 + dp(8f)
                rightMargin = insetPx - touchPx / 2 + dp(8f)
            },
        )

        root.addView(
            modalLayer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            focusRing,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun borderlessRipple(): android.graphics.drawable.Drawable? {
        // Plain-inflater world: resolve the ripple by hand (the
        // CaptureResultOverlay pattern for app:-less ripples).
        val tv = TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        return if (tv.resourceId != 0) {
            androidx.core.content.ContextCompat.getDrawable(ctx, tv.resourceId)
        } else {
            null
        }
    }

    // ── Back stack ───────────────────────────────────────────────────────

    private class PageEntry(val page: WorkspacePage) {
        lateinit var view: View

        /** The page's custom header content ([WorkspaceHost.setHeaderView]);
         *  null = the plain title from [WorkspacePage.title]. */
        var headerView: View? = null
    }

    private val stack = ArrayList<PageEntry>()

    /** The entry whose [WorkspacePage.onCreateView] is currently running —
     *  a page's [WorkspaceHost.setHeaderView] during creation must land on
     *  the entry being pushed, which is not on [stack] yet. */
    private var pushTarget: PageEntry? = null

    private fun push(page: WorkspacePage) {
        if (dismissed) return
        // Depth cap: every buried page keeps its views (and any WebView)
        // alive for scroll-position restore — unbounded cross-reference
        // drill-downs would hold N renderers on a 4GB handheld. Past the
        // cap the OLDEST entry is retired; back then bottoms out one page
        // earlier, which nobody walking six references deep will miss.
        if (stack.size >= MAX_STACK_DEPTH) {
            destroyEntry(stack.removeAt(0))
        }
        val entry = PageEntry(page)
        pushTarget = entry
        val view = try {
            page.onCreateView(ctx, pageContainer, hostImpl)
        } finally {
            pushTarget = null
        }
        entry.view = view
        val prev = stack.lastOrNull()
        stack.add(entry)
        pageContainer.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        if (prev != null) {
            // The buried view is kept in the container (GONE) so its scroll
            // position survives a pop.
            prev.view.animate().cancel()
            prev.view.animate()
                .alpha(0f)
                .setDuration(PAGE_FADE_MS)
                .withEndAction {
                    if (!dismissed && stack.lastOrNull()?.view !== prev.view) {
                        prev.view.visibility = View.GONE
                    }
                }
                .start()
            view.alpha = 0f
            view.translationX = 12 * density
            view.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(PAGE_FADE_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        setImeMode(false)
        updateHeader()
        nav?.clearCursor()
    }

    private fun pop() {
        if (dismissed) return
        if (stack.size <= 1) {
            animateOutAndDismiss()
            return
        }
        val top = stack.removeAt(stack.size - 1)
        val revealed = stack.last()
        revealed.view.animate().cancel()
        revealed.view.visibility = View.VISIBLE
        revealed.view.translationX = 0f
        revealed.view.animate().alpha(1f).setDuration(PAGE_FADE_MS).start()
        top.view.animate().cancel()
        top.view.animate()
            .alpha(0f)
            .translationX(12 * density)
            .setDuration(PAGE_FADE_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { if (!dismissed) destroyEntry(top) }
            .start()
        setImeMode(false)
        updateHeader()
        nav?.clearCursor()
    }

    private fun destroyEntry(entry: PageEntry) {
        pageContainer.removeView(entry.view)
        entry.page.onDestroy()
    }

    private fun updateHeader() {
        val top = stack.lastOrNull() ?: return
        // Swap the custom header slot for the top page's (the Anki editor's
        // mode toggle); the plain title stands in when a page has none.
        val custom = top.headerView
        if (currentHeaderView !== custom) {
            currentHeaderView?.let { headerFrame.removeView(it) }
            custom?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                headerFrame.addView(it)
            }
            currentHeaderView = custom
        }
        titleView.visibility = if (custom != null) View.GONE else View.VISIBLE
        titleView.text = top.page.title(ctx)
        backBtn.visibility = if (stack.size > 1) View.VISIBLE else View.GONE
    }

    /** Modal → page.onBack → pop (dismiss at depth 1) — the workspace mirror
     *  of the sheet's [CaptureResultOverlay] precedence ladder. */
    private fun onBackPressed() {
        topLiveModalDismisser()?.let {
            it()
            return
        }
        if (stack.lastOrNull()?.page?.onBack() == true) return
        pop()
    }

    // ── In-window modals ─────────────────────────────────────────────────

    /** Modals whose B-dismissal needs more than a scrim click — currently
     *  only [OverlayProgress] (its scrim deliberately swallows taps). Keyed
     *  by the modal's root child in [modalLayer]; pruned when it detaches. */
    private val modalDismissers = ArrayList<Pair<View, () -> Unit>>()

    private fun topLiveModalDismisser(): (() -> Unit)? {
        modalDismissers.removeAll { it.first.parent == null }
        if (modalLayer.childCount == 0) return null
        val topChild = modalLayer.getChildAt(modalLayer.childCount - 1)
        modalDismissers.lastOrNull { it.first === topChild }?.let { return it.second }
        // OverlayAlert's scrim click IS its cancel — performClick runs the
        // scrim's own listener, exactly like a tap beside the card.
        return { topChild.performClick() }
    }

    private val hasModal: Boolean get() = modalLayer.childCount > 0

    // ── Host seam ────────────────────────────────────────────────────────

    private val hostImpl = object : WorkspaceHost {
        override val ctx: Context get() = this@OverlayWorkspace.ctx
        override val scope: CoroutineScope get() = this@OverlayWorkspace.scope
        override val displayId: Int get() = this@OverlayWorkspace.displayId
        override val wm: WindowManager get() = this@OverlayWorkspace.wm
        override val overlayHost: OverlayHost get() = this@OverlayWorkspace.overlayHost
        override val modalLayer: FrameLayout get() = this@OverlayWorkspace.modalLayer

        override fun push(page: WorkspacePage) = this@OverlayWorkspace.push(page)
        override fun pop() = this@OverlayWorkspace.pop()
        override fun dismiss() = animateOutAndDismiss()

        override fun setTitle(title: CharSequence) {
            titleView.text = title
        }

        override fun setHeaderView(view: View?) {
            val entry = pushTarget ?: stack.lastOrNull() ?: return
            entry.headerView = view
            // During onCreateView the entry isn't on the stack yet — the
            // push's own updateHeader applies it once it is.
            if (pushTarget == null) updateHeader()
        }

        override fun setImeMode(wantsIme: Boolean) = this@OverlayWorkspace.setImeMode(wantsIme)

        override fun setParkedForActivity(parked: Boolean) =
            this@OverlayWorkspace.setParkedForActivity(parked)

        override fun showProgress(
            title: String,
            onDismiss: (DismissReason) -> Unit,
        ): OverlayProgress {
            val progress = OverlayProgress.Builder(ctx)
                .setTitle(title)
                .setOnDismiss(onDismiss)
                .showInParent(modalLayer)
            // B cancels like the Activity flows' back-press — the progress
            // scrim itself swallows taps, so it needs an explicit dismisser.
            modalLayer.getChildAt(modalLayer.childCount - 1)?.let { scrimChild ->
                modalDismissers.add(scrimChild to { progress.cancel() })
            }
            return progress
        }

        override fun alert(): OverlayAlert.Builder = OverlayAlert.Builder(ctx)

        override fun invalidateNav() {
            nav?.revalidateCursor()
        }
    }

    // ── Controller navigation seam ───────────────────────────────────────

    private val tmpLoc = IntArray(2)
    private val ringItem = Rect()
    private val ringClip = Rect()

    private val navHost = object : WorkspaceNavHost {
        override val isModalUp: Boolean get() = hasModal
        override val isEditing: Boolean get() = imeMode

        override fun onControllerBack() = onBackPressed()

        override fun navActions(): List<NavAction> =
            stack.lastOrNull()?.page?.navActions() ?: emptyList()

        override fun headerNavActions(): List<NavAction> =
            collectWorkspaceNavActions(currentHeaderView)

        override fun viewInScrollViewport(v: View): Boolean {
            val sv = stack.lastOrNull()?.page?.scrollView() ?: return false
            var p: android.view.ViewParent? = v.parent
            while (p != null) {
                if (p === sv) return true
                p = p.parent
            }
            return false
        }

        override fun scrollViewportOnScreen(out: Rect): Boolean {
            val sv = stack.lastOrNull()?.page?.scrollView() ?: return false
            if (!sv.isShown || sv.width <= 0 || sv.height <= 0) return false
            sv.getLocationOnScreen(tmpLoc)
            out.set(tmpLoc[0], tmpLoc[1], tmpLoc[0] + sv.width, tmpLoc[1] + sv.height)
            return true
        }

        override fun scrollBy(dy: Int) {
            val sv = stack.lastOrNull()?.page?.scrollView() ?: return
            val child = sv.getChildAt(0) ?: return
            val max = (child.height + sv.paddingTop + sv.paddingBottom - sv.height)
                .coerceAtLeast(0)
            val target = (sv.scrollY + dy).coerceIn(0, max)
            if (target != sv.scrollY) sv.scrollTo(sv.scrollX, target)
        }

        override fun ensureVisible(itemOnScreen: Rect) {
            val viewport = Rect()
            if (!scrollViewportOnScreen(viewport)) return
            val dy = when {
                itemOnScreen.bottom > viewport.bottom -> itemOnScreen.bottom - viewport.bottom
                itemOnScreen.top < viewport.top -> itemOnScreen.top - viewport.top
                else -> 0
            }
            if (dy != 0) scrollBy(dy)
        }

        override fun setRing(itemOnScreen: Rect?, clipOnScreen: Rect?) {
            if (itemOnScreen == null) {
                focusRing.setTarget(null, null)
                return
            }
            root.getLocationOnScreen(tmpLoc)
            ringItem.set(itemOnScreen)
            ringItem.offset(-tmpLoc[0], -tmpLoc[1])
            if (clipOnScreen != null) {
                ringClip.set(clipOnScreen)
                ringClip.offset(-tmpLoc[0], -tmpLoc[1])
                focusRing.setTarget(ringItem, ringClip)
            } else {
                focusRing.setTarget(ringItem, null)
            }
        }
    }

    // ── Focus / IME ──────────────────────────────────────────────────────

    /** Park this window while a helper Activity (the audio picker) runs
     *  above the game — an overlay window would otherwise cover and block
     *  it. The gate's result callback un-parks. */
    private fun setParkedForActivity(parked: Boolean) {
        if (dismissed) return
        sheetHost.setParked(root, parked)
        if (!parked) applyWindowFocusPolicy()
    }

    private fun setImeMode(wantsIme: Boolean) {
        if (imeMode == wantsIme) return
        imeMode = wantsIme
        if (!wantsIme) {
            ctx.getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(root.windowToken, 0)
            if (nav != null) root.requestFocus()   // pull view focus off the EditText
        }
        applyWindowFocusPolicy()
    }

    /** Single owner of the window's focus/IME flags — the sheet's rule:
     *  focus for editing (IME) or controller nav (keys + sticks); the IME
     *  only ever for editing. Non-focusable otherwise, so the nav pill never
     *  pops over an immersive game while the user only taps. */
    private fun applyWindowFocusPolicy() {
        sheetHost.setFocusPolicy(root, focusable = imeMode || nav != null, wantsIme = imeMode)
    }

    // ── Show / dismiss ───────────────────────────────────────────────────

    fun show(screenW: Int, screenH: Int, initialPage: (WorkspaceHost) -> WorkspacePage) {
        // Controller nav arms only when a nav-capable device is attached —
        // evaluated once here, like the capture sheet: the window is created
        // already-focusable so there's no async flag flip to race.
        val navActive = hasNavInputDevice(ctx)
        sheetHost.attach(root, screenW, screenH, focusable = navActive)
        if (navActive) {
            root.isFocusable = true
            root.isFocusableInTouchMode = true
            root.defaultFocusHighlightEnabled = false
            root.requestFocus()
            nav = WorkspaceControllerNav(ctx, navHost)
        }
        ringSync = ViewTreeObserver.OnPreDrawListener { nav?.syncRing(); true }.also {
            root.viewTreeObserver.addOnPreDrawListener(it)
        }
        // Nav-bar buffer + IME lift, both as extra bottom margin on the card:
        // FLAG_LAYOUT_NO_LIMITS makes the window ignore ADJUST_RESIZE, so the
        // card shrinks from the bottom instead — keeping the top edge and the
        // X pinned (the only visible exit while the IME is up).
        root.setOnApplyWindowInsetsListener { _, insets ->
            val navInset: Int
            val imeLift: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                navInset = if (insets.isVisible(WindowInsets.Type.navigationBars())) nav else 0
                val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                imeLift = if (insets.isVisible(WindowInsets.Type.ime())) ime else 0
            } else {
                // API 29 fallback — see CaptureResultOverlay's inset listener.
                @Suppress("DEPRECATION")
                val current = insets.systemWindowInsetBottom
                @Suppress("DEPRECATION")
                val stable = insets.stableInsetBottom
                navInset = minOf(current, stable)
                imeLift = if (current > stable) current else 0
            }
            val lp = cardWrap.layoutParams as FrameLayout.LayoutParams
            val want = insetPx + maxOf(navInset, imeLift)
            if (lp.bottomMargin != want) {
                lp.bottomMargin = want
                cardWrap.requestLayout()
            }
            insets
        }
        push(initialPage(hostImpl))
        // Enter: scrim fade + card scale-up, the popup house pattern.
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(ENTER_MS).start()
        for (v in listOf<View>(cardWrap, closeBtn)) {
            v.alpha = 0f
            v.scaleX = 0.96f
            v.scaleY = 0.96f
            v.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(ENTER_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    fun animateOutAndDismiss() {
        if (dismissed || animatingOut) return
        animatingOut = true
        nav?.clearCursor()
        scrim.animate().alpha(0f).setDuration(EXIT_MS).start()
        closeBtn.animate().alpha(0f).setDuration(EXIT_MS).start()
        cardWrap.animate()
            .alpha(0f)
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(EXIT_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { dismiss() }
            .start()
    }

    fun dismiss() {
        if (dismissed) return
        dismissed = true
        imeMode = false
        nav?.release()
        nav = null
        ringSync?.let { root.viewTreeObserver.removeOnPreDrawListener(it) }
        ringSync = null
        while (stack.isNotEmpty()) {
            destroyEntry(stack.removeAt(stack.size - 1))
        }
        // Orphaned-UP guard: a key that went down on this focused window may
        // still be held — linger the window as an invisible key sink until
        // the release lands here (see [WindowKeyPairGuard]).
        if (root.keyGuard.hasPendingDown && sheetHost.beginKeySink(root)) {
            root.keyGuard.beginLinger { sheetHost.detach(root) }
        } else {
            sheetHost.detach(root)
        }
        scope.cancel()
        onDismiss?.invoke()
    }

    // ── Root ─────────────────────────────────────────────────────────────

    /** Full-screen transparent host: owns tap-outside dismissal (consuming
     *  the DOWN so it can never leak to the game) and the key ladder. */
    private inner class WorkspaceRoot(c: Context) : FrameLayout(c) {
        val keyGuard = WindowKeyPairGuard()

        /** Hardware back/Escape when controller nav is off (IME editing via a
         *  physical keyboard): armed on the first DOWN, fired on the consumed
         *  UP — the same orphaned-UP rule as nav's back. */
        private var backArmed = false

        override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
            if (keyGuard.isLingering) return keyGuard.lingerKey(ev)
            keyGuard.track(ev)
            if (nav?.handleKey(ev) == true) return true
            if (ControllerKeys.isBack(ev.keyCode)) {
                when (ev.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (ev.repeatCount == 0) backArmed = true
                        return true
                    }
                    KeyEvent.ACTION_UP -> {
                        if (backArmed) {
                            backArmed = false
                            onBackPressed()
                        }
                        return true
                    }
                }
            }
            return super.dispatchKeyEvent(ev)
        }

        override fun onGenericMotionEvent(ev: MotionEvent): Boolean =
            nav?.handleGenericMotion(ev) == true || super.onGenericMotionEvent(ev)

        private val hitRect = Rect()

        private fun hits(v: View, ev: MotionEvent): Boolean {
            v.getHitRect(hitRect)
            return hitRect.contains(ev.x.toInt(), ev.y.toInt())
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            // A modal owns the whole window — its own scrim decides what an
            // outside tap means (the alert dismisses, progress swallows).
            if (hasModal) return super.dispatchTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    if (!hits(cardWrap, ev) && !hits(closeBtn, ev)) {
                        animateOutAndDismiss()
                        return true   // consumed — never leaks to the game
                    }
                MotionEvent.ACTION_OUTSIDE -> {
                    animateOutAndDismiss()
                    return true
                }
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    private companion object {
        /** The sheet's corner ratio — a 32dp-inset card wants the larger
         *  radius (21dp at the standard 14dp token). */
        const val CARD_RADIUS_MULT = 1.5f
        const val CARD_ELEVATION_DP = 12f
        const val CLOSE_BTN_DP = 32f
        const val HEADER_H_DP = 44f
        const val SCRIM_ALPHA = 0x80
        /** Live pages kept on the back stack (see [push]'s retirement rule). */
        const val MAX_STACK_DEPTH = 6
        const val ENTER_MS = 200L
        const val EXIT_MS = 160L
        const val PAGE_FADE_MS = 150L
    }
}
