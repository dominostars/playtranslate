package com.playtranslate.ui

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.view.WindowManager
import com.playtranslate.Prefs
import com.playtranslate.overlay.OverlayHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The tap-a-word lens over a bound source text: resolve the tapped span
 * (phrase-aware, [SourceWordLookup.resolveAt]), anchor a [MagnifierLens] on
 * the word's own line box, give it a speak chip, the accent highlight, a
 * single or split body (the containing phrase above for Latin scripts, a
 * fused expression's member words below for JA), a "Load more (n)" row for
 * the sections whose definitions would be machine-translated
 * ([SourceWordLookup.ResolvedAt.pending], resolved on tap by [loadMore]),
 * and an optional Anki deck-badge back-fill. One presenter for every
 * surface that binds a source text through [TranslationSectionBinder]:
 * the in-app results fragment, the over-game capture sheet and the
 * workspace's Sentence page.
 * What differs per surface is only the window it lives in ([overlayHost]
 * null = the activity window) and what the lens's chips DO
 * ([wireActions]).
 *
 * Geometry is the sheet's [wordRectOnScreen]: the span's FIRST line box,
 * so a wrapped word anchors on its first line, with the right edge falling
 * back to the line's right edge when the offset past the word lands on the
 * next line (getPrimaryHorizontal would return that line's start and
 * collapse the box mid-screen). The controller cursor's ring reads the
 * same rects, so the two can't drift.
 *
 * Staleness: the resolve suspends, and a result/edit binding meanwhile must
 * not let a stale span open a lens over the new text (with the new
 * capture's context riding into its actions), so the displayed text is
 * snapshotted at tap time and re-checked after the resolve, and again
 * after a [loadMore] resolve before its sections join the lens.
 */
class SourceTextLens(
    private val ctx: Context,
    private val wm: WindowManager,
    private val displayId: Int,
    /** Null: the lens attaches to the activity window (the in-app page, the
     *  camera panel); else the backend's overlay host. */
    private val overlayHost: OverlayHost?,
    private val scope: CoroutineScope,
    private val ttsAlertTarget: TtsAlertTarget,
    private val binder: TranslationSectionBinder,
    /** The display's size in px, read at tap time (rotation-safe). */
    private val screenSize: () -> Point,
    /** False for a lens that is display + speak only (the sheet's default
     *  before a resolved entry). Read per tap, so a surface may key it on
     *  the resolved entry. */
    private val showAnkiChip: (resolved: SourceWordLookup.ResolvedAt) -> Boolean,
    /** Whether the tapped unit opens even without a dictionary entry: the
     *  over-game surfaces open the sentence/word view for any unit; the
     *  in-app page only opens into a matched entry (no entry, nothing to
     *  open into, no chevron). Secondary sections always open. */
    private val opensWithoutEntry: Boolean,
    /** Deck badges for the lens body, when the surface has a cache to
     *  share (the words list's). Null: no badge back-fill. */
    private val decks: DeckSource? = null,
    /** Wire the chips of a lens for this tap: the open chevron, the Anki
     *  chip's tap + long-press, the secondary sections' opens. Runs before
     *  the lens shows, and again on the same lens with the filled
     *  resolution after a [loadMore], so the secondary opens index the
     *  sections it added. */
    private val wireActions: (lens: MagnifierLens, resolved: SourceWordLookup.ResolvedAt) -> Unit,
) {
    interface DeckSource {
        /** Already-known decks for [word] (may be empty), or null when not
         *  yet queried. */
        fun cached(word: String): List<String>?

        /** Query (and cache) the decks; null when AnkiDroid is absent or
         *  unpermitted. */
        suspend fun load(word: String): List<String>?
    }

    /** Gate for surfaces that switch the lens off (the sheet's hosts that
     *  keep their own word handling). */
    var enabled: Boolean = true

    /** Tap spans over the DISPLAYED source text (char range, lookup form,
     *  reading, token), computed by the host from its own token source (the
     *  VM's settled spans in-app, a tokenize over the game). */
    var wordSpans: List<SourceWordLookup.TapSpan> = emptyList()

    /** Fired after every teardown path (tap-outside, the speak chip's
     *  no-engine action, [dismiss]). */
    var onDismissed: (() -> Unit)? = null

    private var lens: MagnifierLens? = null
    private var speakChip: LensSpeakChip? = null
    private val locTmp = IntArray(2)

    // The showing lens's body state, read by [bindBody]: set by [present]
    // once the previous lens is dismissed (and updated by the deck
    // back-fill and [loadMore]), cleared by the showing lens's onDismiss,
    // so while [lens] is set they describe it.
    /** The resolution the body shows; [loadMore] replaces it with the
     *  filled one. */
    private var currentAt: SourceWordLookup.ResolvedAt? = null
    /** The primary's Anki decks once the back-fill found any; null until. */
    private var currentDecks: List<String>? = null
    /** True while [loadMore] resolves the pending sections. */
    private var loadingMore = false
    private var loadJob: Job? = null
    /** The displayed source text the showing lens was resolved against. */
    private var presentedText: String? = null

    val isShowing: Boolean get() = lens != null

    /** The lens for the span under [offset] (a tap on the source text, or
     *  the controller cursor's A press with [fromController], which
     *  pre-selects the pill so the next A opens). No span = no-op. */
    fun onTapAtOffset(offset: Int, fromController: Boolean = false) {
        if (!enabled) return
        val span = wordSpans.firstOrNull { offset in it.range } ?: return
        val tappedText = binder.displayedSourceText()
        scope.launch {
            try {
                val resolvedAt = SourceWordLookup.resolveAt(
                    ctx.applicationContext, tappedText, span.range.first,
                    span.lookupForm, span.reading, span.token,
                )
                if (binder.displayedSourceText() != tappedText) return@launch
                val rect = Rect()
                if (!wordRectOnScreen(span.range, rect)) return@launch
                present(span.range, rect, resolvedAt, fromController, tappedText)
            } catch (_: Exception) {
            }
        }
    }

    private fun present(
        span: IntRange,
        rect: Rect,
        resolvedAt: SourceWordLookup.ResolvedAt,
        fromController: Boolean,
        tappedText: String,
    ) {
        val resolved = resolvedAt.word
        dismiss()
        // After dismiss(): the previous lens's onDismiss clears these.
        currentAt = resolvedAt
        currentDecks = null
        loadingMore = false
        presentedText = tappedText
        val lens = MagnifierLens(
            ctx, wm, displayId,
            overlayHost = overlayHost,
            showAnkiChip = showAnkiChip(resolvedAt),
        )
        // onDismiss is the single funnel for every teardown path, so the
        // speak chip + highlight cleanup lives here, not only in dismiss().
        lens.onDismiss = {
            binder.setWordHighlight(null)
            speakChip?.release()
            speakChip = null
            if (this.lens === lens) {
                this.lens = null
                loadJob?.cancel()
                loadJob = null
                currentAt = null
                currentDecks = null
                loadingMore = false
                presentedText = null
            }
            onDismissed?.invoke()
        }
        lens.onLoadMoreTap = { loadMore(lens) }
        this.lens = lens
        speakChip = LensSpeakChip(lens, scope, ttsAlertTarget) {
            LensSpeakChip.Request(resolved.word, Prefs(ctx).sourceLangId, reading = resolved.reading)
        }
        wireActions(lens, resolvedAt)
        binder.setWordHighlight(span)
        val size = screenSize()
        lens.show(rect.centerX(), rect.top, size.x, size.y, anchorHeight = rect.height())
        bindBody(lens, LensScroll.Top)
        backfillDecks(lens, resolved.data, resolved.word) { updated ->
            currentDecks = updated.ankiDecks
            bindBody(lens, LensScroll.Keep)
        }
        lens.makeInteractive()
        if (fromController) lens.focusPillForController()
    }

    /** Binds [lens]'s body from the fields, the one place that does: the
     *  first bind, the deck back-fill and a [loadMore] all come here, so
     *  the back-fill and the load cannot drop what the other added. With
     *  secondaries or a pending row, the split body: the tapped unit (pill
     *  identity) plus the related units (the containing phrase above it on
     *  Latin scripts; member words and then alternative entries below it
     *  on JA), each with its own drill-in, and the "Load more (n)" row
     *  last ([SourceWordLookup.loadMoreFor]). Otherwise the single body.
     *  [scroll] places the split body's viewport ([LensScroll]): the first
     *  show passes Top, the deck back-fill Keep, a [loadMore] a Reveal of
     *  its first loaded section. The single body takes no policy and binds
     *  at its top. */
    private fun bindBody(lens: MagnifierLens, scroll: LensScroll) {
        val at = currentAt ?: return
        val resolved = at.word
        val data = currentDecks?.let { resolved.data.copy(ankiDecks = it) } ?: resolved.data
        val canOpen = resolved.entry != null || opensWithoutEntry
        val secondaries = at.secondaries()
        val loadMore = SourceWordLookup.loadMoreFor(at, loadingMore)
        if (secondaries.isNotEmpty() || loadMore != null) {
            lens.setSplitDefinitions(
                LensSection(data, resolved.label, opens = canOpen),
                secondaries.map { LensSection(it.data, it.label, opens = true, caption = it.caption) },
                secondariesOnTop = at.phrase != null,
                loadMore,
                scroll,
            )
        } else {
            lens.setDefinitions(data, resolved.label, opens = canOpen)
        }
    }

    /** The "Load more (n)" tap: resolves the showing lens's pending
     *  sections ([SourceWordLookup.resolvePending]) with the row reading
     *  "Looking up…", then re-wires the lens's actions on the filled
     *  resolution and rebinds the body with each loaded section in its
     *  key's place ([SourceWordLookup.mergeLoaded]), the viewport on the
     *  first of them ([firstLoadedIndex], [LensScroll.Reveal]). No-op
     *  while a load runs or with nothing pending. The result is dropped
     *  when [lens] is no longer the showing one (its onDismiss also
     *  cancels the job) or the displayed text changed since the tap. A
     *  failed resolve restores the row on a still-showing lens, so the tap
     *  can be retried. */
    private fun loadMore(lens: MagnifierLens) {
        val at = currentAt ?: return
        if (loadingMore || at.pending.isEmpty()) return
        loadingMore = true
        lens.setLoadMoreLoading(true)
        loadJob = scope.launch {
            try {
                val filled = SourceWordLookup.resolvePending(ctx.applicationContext, at)
                if (this@SourceTextLens.lens !== lens || binder.displayedSourceText() != presentedText) return@launch
                currentAt = filled
                loadingMore = false
                wireActions(lens, filled)
                val firstLoaded = firstLoadedIndex(filled.keys, at.pending)
                bindBody(lens, firstLoaded?.let(LensScroll::Reveal) ?: LensScroll.Keep)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (this@SourceTextLens.lens === lens) {
                    loadingMore = false
                    lens.setLoadMoreLoading(false)
                }
            }
        }
    }

    /** Once decks are known, re-render the lens body so its meta row carries
     *  the deck pill. Reuses the host's cache and no-ops when the lens has
     *  since been dismissed/replaced. The caller owns the rebind shape. */
    private fun backfillDecks(
        lens: MagnifierLens,
        base: WordDefinitionData,
        word: String,
        rebind: (WordDefinitionData) -> Unit,
    ) {
        val source = decks ?: return
        source.cached(word)?.let { cached ->
            if (cached.isNotEmpty()) rebind(base.copy(ankiDecks = cached))
            return
        }
        scope.launch {
            val loaded = source.load(word) ?: return@launch
            if (loaded.isEmpty() || this@SourceTextLens.lens !== lens) return@launch
            rebind(base.copy(ankiDecks = loaded))
        }
    }

    /** Screen rect of [span]'s FIRST line box inside the source text, or
     *  false while the text isn't laid out. */
    fun wordRectOnScreen(span: IntRange, out: Rect): Boolean {
        val tv = binder.tvOriginal
        if (!tv.isShown) return false
        val layout = tv.layout ?: return false
        val endOffset = span.last + 1
        if (span.first < 0 || endOffset > layout.text.length) return false
        val lineStart = layout.getLineForOffset(span.first)
        val xStart = layout.getPrimaryHorizontal(span.first)
        // The offset just past the word can land on the NEXT line (the word
        // ends a wrapped line) — getPrimaryHorizontal then returns that line's
        // start (~0), collapsing the box to mid-screen and throwing off the
        // lens/arrow for right-edge words. Fall back to the line's right edge.
        val xEnd = if (layout.getLineForOffset(endOffset) == lineStart) {
            layout.getPrimaryHorizontal(endOffset)
        } else {
            layout.getLineRight(lineStart)
        }
        // min/max, not start/end: an RTL run's primary horizontals arrive inverted.
        var left = minOf(xStart, xEnd).toInt() + tv.paddingLeft
        var right = maxOf(xStart, xEnd).toInt() + tv.paddingLeft
        if (right <= left) right = left + 1
        val top = layout.getLineTop(lineStart) - tv.scrollY + tv.paddingTop
        val bottom = layout.getLineBottom(lineStart) - tv.scrollY + tv.paddingTop
        if (bottom <= top) return false
        tv.getLocationOnScreen(locTmp)
        out.set(
            locTmp[0] + left, locTmp[1] + top,
            locTmp[0] + right, locTmp[1] + bottom,
        )
        return true
    }

    /** Tear the lens down (fires its onDismiss, which releases the speak
     *  chip and clears the highlight). No-op when none is up. */
    fun dismiss() {
        lens?.dismiss()
    }
}
