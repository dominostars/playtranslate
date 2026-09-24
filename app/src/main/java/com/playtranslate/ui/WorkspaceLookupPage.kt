package com.playtranslate.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.playtranslate.Prefs
import com.playtranslate.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Which side of the lookup page's Sentence/word toggle is showing.
 *  Persisted as [Prefs.lookupPreferredView]: the last choice is the next
 *  open's default, on this page and on the results activity's drag-word
 *  tabs alike. */
enum class LookupView { WORD, SENTENCE }

/**
 * The floating-icon drag flow's detail page: the looked-up word's detail
 * ([WorkspaceWordDetailPage]) and the sentence's results page
 * ([WorkspaceSentencePage]) under ONE header toggle — "Sentence" on the
 * left, the word itself on the right — the over-game counterpart of
 * [TranslationResultActivity]'s drag-word tabs. The toggle is the Anki
 * editors' pill parked in the workspace header ([WorkspaceHost.setHeaderView]),
 * seeded from [Prefs.lookupPreferredView] and writing every tap back.
 *
 * The two tabs are pages of their own, built LAZILY on first selection
 * into this page's container and kept (GONE) once built, so flipping back
 * restores scroll and content; only the shown tab answers the workspace's
 * controller-nav, scroll-viewport and back queries. Both see this page's
 * host through a wrapper that swallows title and header writes: the
 * toggle owns the header, and the word page's headword-reveal title would
 * otherwise clobber it.
 *
 * One [TranslationResultViewModel], owned here with the page's scope,
 * backs both tabs: the Sentence tab drives it (translation, word lookups),
 * and the word tab's Anki card reads its live state through
 * [TranslationResultViewModel.sentenceContext] with the lens's snapshot as
 * the fallback — so a translation that lands on the Sentence tab is on the
 * word's card too, and a lookup that never opened the Sentence tab still
 * carries whatever the drag flow cached.
 */
class WorkspaceLookupPage internal constructor(
    private val args: LensDetailArgs,
    private val wordPage: (TranslationResultViewModel) -> WorkspacePage,
    private val sentencePage: (TranslationResultViewModel) -> WorkspacePage,
) : WorkspacePage {

    constructor(args: LensDetailArgs) : this(
        args,
        wordPage = { vm ->
            WorkspaceWordDetailPage(
                word = args.word,
                reading = args.reading,
                screenshotPath = args.screenshotPath,
                audioAnchorMs = args.audioAnchorMs,
                sentenceContext = { vm.sentenceContext(args.sentenceContext) },
            )
        },
        sentencePage = { vm -> WorkspaceSentencePage(vm, args) },
    )

    private class Tab(val page: WorkspacePage, val view: View)

    private var pageScope: CoroutineScope? = null
    private var vm: TranslationResultViewModel? = null
    private var container: FrameLayout? = null
    private var childHost: WorkspaceHost? = null
    private var hostRef: WorkspaceHost? = null
    private val tabs = HashMap<LookupView, Tab>()

    /** The tab currently shown; null before [onCreateView]. */
    var selected: LookupView? = null
        private set

    override fun title(ctx: Context): CharSequence = args.word

    override fun onCreateView(ctx: Context, parent: ViewGroup, host: WorkspaceHost): View {
        hostRef = host
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        pageScope = scope
        val vm = TranslationResultViewModel(scope)
        this.vm = vm
        childHost = object : WorkspaceHost by host {
            override fun setTitle(title: CharSequence) {}
            override fun setHeaderView(view: View?) {}
        }
        val c = FrameLayout(ctx)
        container = c
        val initial = Prefs(ctx).lookupPreferredView
        val density = ctx.resources.displayMetrics.density
        // The Anki editors' pill, at the header's fixed centre slot (its
        // track fills its container; the header centre is ~56dp in from each
        // edge for the chevron/X wells).
        val toggleHost = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                (TOGGLE_WIDTH_DP * density).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }
        buildAnkiModeToggle(
            container = toggleHost,
            leftLabel = ctx.getString(R.string.anki_mode_sentence),
            rightLabel = args.word,
            leftActive = initial == LookupView.SENTENCE,
        ) { leftSelected ->
            val view = if (leftSelected) LookupView.SENTENCE else LookupView.WORD
            // An explicit flip is the user's new default for the next open.
            Prefs(ctx).lookupPreferredView = view
            select(ctx, view)
        }
        host.setHeaderView(toggleHost)
        select(ctx, initial)
        return c
    }

    private fun select(ctx: Context, view: LookupView) {
        val c = container ?: return
        val host = childHost ?: return
        val vm = vm ?: return
        // The header toggle sits outside a page's popover scrim, so a tab can
        // be switched away with its popover still open: close it rather than
        // leave it stranded in the hidden tab.
        if (selected != view) shownTab?.page?.dismissPopovers()
        val tab = tabs[view] ?: run {
            val page = when (view) {
                LookupView.WORD -> wordPage(vm)
                LookupView.SENTENCE -> sentencePage(vm)
            }
            val v = page.onCreateView(ctx, c, host)
            c.addView(
                v,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            Tab(page, v).also { tabs[view] = it }
        }
        selected = view
        for ((key, t) in tabs) {
            t.view.visibility = if (key == view) View.VISIBLE else View.GONE
        }
        // The shown tab's targets replace the hidden one's under the cursor.
        hostRef?.invalidateNav()
    }

    private val shownTab: Tab? get() = selected?.let { tabs[it] }

    override fun navActions(): List<NavAction> = shownTab?.page?.navActions() ?: emptyList()

    override fun scrollView(): ViewGroup? = shownTab?.page?.scrollView()

    override fun onBack(): Boolean = shownTab?.page?.onBack() == true

    override val isPopoverOpen: Boolean get() = shownTab?.page?.isPopoverOpen == true

    override fun dismissPopovers() {
        shownTab?.page?.dismissPopovers()
    }

    override fun onDestroy() {
        for (t in tabs.values) t.page.onDestroy()
        tabs.clear()
        pageScope?.cancel()
        pageScope = null
        vm = null
        container = null
        childHost = null
        hostRef = null
        selected = null
    }

    private companion object {
        /** The Anki editor's header slot width, so the two pills match. */
        const val TOGGLE_WIDTH_DP = 220
    }
}
