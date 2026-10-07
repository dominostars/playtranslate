package com.playtranslate.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isEmpty
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.playtranslate.AnkiManager
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.themeColor
import com.playtranslate.vocab.HiddenWordsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The results page's Words card — the rows a settled word lookup renders
 * ([WordResultCell] per [RowState]: reading, definitions, frequency, pitch,
 * the hidden-words eye), with the deck badges, the per-cell speak action,
 * the hidden-words ordering + in-place re-stub, and the card's own eye
 * (the persisted hide-words-section pref). Host-agnostic: the in-app
 * [TranslationResultFragment] and the floating workspace's
 * [WorkspaceSentencePage] both render through here, so the list can't
 * drift between the two surfaces. Views are found from [root]
 * (`fragment_translation_result.xml`'s Words card ids).
 *
 * Imported Yomitan dictionaries render styled on the first
 * [STYLED_WORD_ROW_CAP] rows that retained structured content (in list
 * order; the rest bind flat), through [WordResultCell]'s `styledRenderers`
 * path over a [StyledRendererPool] this binder owns for the page's life:
 * the VM fetches ONE payload for the whole list beside the rows
 * ([WordLookupsState.Settled.styled]), each cell takes its own share
 * ([YomitanStyledData.forGroups]) and borrows a pooled renderer, and every
 * rebuild hands the renderers back before the rows are rebuilt, so a
 * capture in live mode costs content swaps, not shell loads. [release]
 * destroys the pool; a dropped but undestroyed WebView holds renderer
 * resources until GC.
 *
 * Hidden words, the rules pinned by the store's tests: a fresh list draws
 * visible words first and hidden words last, each group in lookup order,
 * and only when the language's set had LOADED before the render
 * ([hiddenOrderApplied]); a list drawn before the load is provisional, and
 * the load's revision bump re-renders it instead of re-stubbing in place,
 * so the first real render is ordered like any other. A toggle never moves
 * a row: [applyHiddenState] re-stubs cells in place. The eye tap writes the
 * opposite of the state the cell was SHOWING (handed over by the cell),
 * never a fresh store read — the icon is what the user acted on.
 *
 * The card itself has two hide sources, and [isHidden] is their union:
 * the persisted pref ([Prefs.hideWordsSection]) and a session-only live
 * auto-hide ([setLiveMode]). The rows are lazy: the VM settles only the
 * analysis ([WordLookupsState.Analyzed], drawn like Loading) and builds the
 * rows when this card asks through [onRowsWanted], which it does whenever
 * it is effectively visible. A hidden card therefore costs no dictionary
 * lookups, no cells and no deck queries. A [WordLookupsState.Settled] that
 * lands while the card is hidden (the rows were requested, then the user
 * hid the card before they settled) still builds its rows: the lookup was
 * already paid, and the next text starts lazy again.
 *
 * Live mode auto-hides the card for its whole session: a dual-screen live
 * session lands a result on every panel emission, and the list is rarely
 * read there. The eye reveals it for the rest of that session without
 * touching the pref; hiding it again is an ordinary, persisted hide. The
 * live flag lives on this binder, so it is per view: a page recreated
 * during live mode is seeded with the running state and starts auto-hidden
 * again.
 */
class WordRowsBinder(
    root: View,
    private val ctx: Context,
    private val prefs: Prefs,
    private val host: Host,
    /** How many rows may hold a styled renderer at once; see
     *  [styledWordRowCap] (0 on low-RAM devices). */
    styledRowCap: Int = styledWordRowCap(ctx),
) {
    interface Host {
        /** The host view tree is still live — guards every async re-entry. */
        val isAlive: Boolean

        /** Where badge queries, speak jobs and the eye's store write run;
         *  view-scoped on the fragment, page-scoped on the workspace. */
        val scope: CoroutineScope

        val ttsAlertTarget: TtsAlertTarget

        /** A row body tap: open that word's detail. */
        fun onWordTapped(row: RowState)

        /** Any user interaction (live-mode hosts pause on it). */
        fun onInteraction() {}
    }

    /** The rows' container — exposed for scroll-anchor candidates. */
    val container: LinearLayout = root.findViewById(R.id.mainWordsContainer)
    private val loadingText: TextView = root.findViewById(R.id.tvMainWordsLoading)
    private val noWordsText: TextView = root.findViewById(R.id.tvNoWords)
    private val card: View = root.findViewById(R.id.cardWords)
    private val toggle: ImageButton = root.findViewById(R.id.btnToggleWords)

    private val ankiDecksByWord = HashMap<String, List<String>>()
    private var lastRenderedCells: Map<String, List<WordResultCell>> = emptyMap()

    /** The rows' styled renderers, capped and reused across rebuilds. */
    private val styledPool = StyledRendererPool(styledRowCap)

    /** The rows of the last Settled render, for the late-load re-render. */
    private var lastRows: List<RowState> = emptyList()

    /** The styled payload those rows were rendered with, re-applied by the
     *  late-load re-render. */
    private var lastStyled: YomitanStyledData? = null

    /** True once the rendered rows were ordered against a LOADED hidden set
     *  (see [renderRows]); while false, the next revision re-renders
     *  instead of re-stubbing in place. */
    private var hiddenOrderApplied = false

    /** The live session's auto-hide (class doc): set on live start, cleared
     *  on live stop and by any eye tap. Never persisted. */
    private var liveAutoHide = false

    /** The last live state [setLiveMode] saw; the edge detector. */
    private var liveRunning = false

    /** Asks the VM for the current analysis's rows
     *  ([TranslationResultViewModel.requestWordRows]). Invoked whenever the
     *  card is effectively visible and rows may be needed: every
     *  [applyWordsVisibility] that leaves it shown, and every
     *  [WordLookupsState.Analyzed] rendered while it is shown. The VM side is
     *  idempotent (a no-op unless rows are pending and none are in flight),
     *  so a redundant call costs nothing. */
    var onRowsWanted: (() -> Unit)? = null

    /** The card's effective hidden state: the persisted pref or the live
     *  session's auto-hide. */
    val isHidden: Boolean get() = prefs.hideWordsSection || liveAutoHide

    init {
        // Tint in code: the workspace inflates this layout with a plain
        // (non-AppCompat) inflater that drops app:tint (the section binder's
        // gear rule); the same color the XML asks for, so in-app matches.
        toggle.imageTintList = ColorStateList.valueOf(ctx.themeColor(R.attr.ptTextMuted))
        toggle.setOnClickListener {
            // The opposite of what the card is SHOWING, whichever source hid
            // it: a tap on a live auto-hidden card reveals it (and clears a
            // stale hiding pref, since the reveal is what the user asked for),
            // and a tap on a shown card is a persisted hide. Either way the
            // auto-hide is spent for this session.
            val hide = !isHidden
            prefs.hideWordsSection = hide
            liveAutoHide = false
            applyWordsVisibility()
        }
    }

    /** Reflect the effective hidden state ([isHidden]): the card and the eye
     *  icon; a shown card asks for its rows ([onRowsWanted]). */
    fun applyWordsVisibility() {
        val hidden = isHidden
        card.visibility = if (hidden) View.GONE else View.VISIBLE
        toggle.setImageResource(if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
        if (!hidden) onRowsWanted?.invoke()
    }

    /** The host's live-mode state. Edge-triggered: a start auto-hides the
     *  card, a stop drops the auto-hide (back to the pref), and a repeat of
     *  the current state is ignored, so a duplicate start after the user
     *  revealed the card does not hide it again. Never writes the pref. */
    fun setLiveMode(running: Boolean) {
        if (running == liveRunning) return
        liveRunning = running
        liveAutoHide = running
        applyWordsVisibility()
    }

    val isEmpty: Boolean get() = container.isEmpty()

    /** Mirror a [WordLookupsState] into the card. The pipeline itself runs
     *  in the VM; this only renders, and asks for the rows of an
     *  [WordLookupsState.Analyzed] when the card is shown (class doc). */
    fun render(state: WordLookupsState) {
        when (state) {
            is WordLookupsState.Idle -> {
                loadingText.isGone = true
                noWordsText.isGone = true
                clearRows()
            }
            // Analyzed draws exactly like Loading: to the card the rows are
            // still on their way (they start on the request below, or on the
            // reveal that makes the card visible).
            is WordLookupsState.Loading, is WordLookupsState.Analyzed -> {
                clearRows()
                loadingText.isVisible = true
                loadingText.text = ctx.getString(R.string.words_loading)
                noWordsText.isGone = true
                if (state is WordLookupsState.Analyzed && !isHidden) onRowsWanted?.invoke()
            }
            is WordLookupsState.Settled -> {
                renderRows(state.rows, state.styled)
                loadingText.isGone = true
                noWordsText.visibility = if (state.rows.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun clearRows() {
        releaseStyledCells()
        container.removeAllViews()
        lastRenderedCells = emptyMap()
        lastRows = emptyList()
        lastStyled = null
    }

    /** Host teardown: the cells hand their styled renderers back and the
     *  pool destroys them. Must be called when the view tree goes away
     *  ([TranslationResultContent.release]); terminal and idempotent — a
     *  render after it binds flat. */
    fun release() {
        releaseStyledCells()
        styledPool.destroyAll()
    }

    /** Every rendered cell hands its styled renderer back to the pool, if
     *  it holds one; the cells themselves stay until the container is
     *  rebuilt. */
    private fun releaseStyledCells() {
        lastRenderedCells.values.forEach { cells -> cells.forEach { it.releaseStyled() } }
    }

    private fun renderRows(rows: List<RowState>, styled: YomitanStyledData?) {
        releaseStyledCells()
        container.removeAllViews()
        lastRows = rows
        lastStyled = styled
        if (rows.isEmpty()) {
            lastRenderedCells = emptyMap()
            return
        }
        // A fresh list is ordered hidden-last. isLoaded BEFORE snapshot: true
        // means the snapshot taken next is the loaded set and this ordering
        // is final; false means it is provisional, and the load's revision
        // bump re-renders (applyHiddenState) rather than re-stubbing in
        // place, so the list's first real render is ordered like any other.
        val lang = prefs.sourceLangId
        hiddenOrderApplied = HiddenWordsStore.isLoaded(lang)
        val ordered = rows.hiddenLast(HiddenWordsStore.snapshot(ctx, lang))
        val cellsByWord = HashMap<String, MutableList<WordResultCell>>()
        ordered.forEachIndexed { idx, row ->
            if (idx > 0) container.addView(inflateDivider())
            val cell = WordResultCell(ctx)
            bindCell(cell, row, styled)
            container.addView(cell)
            cellsByWord.getOrPut(row.displayWord) { mutableListOf() }.add(cell)
        }
        lastRenderedCells = cellsByWord
        loadDeckBadges(ordered.map { it.displayWord }, cellsByWord)
    }

    private fun bindCell(cell: WordResultCell, row: RowState, styled: YomitanStyledData?) {
        // Any already-known Anki decks (cache / re-render) render immediately;
        // uncached words are filled in by renderRows' batched query.
        val data = WordDefinitionData(
            word = row.displayWord,
            reading = row.reading.ifEmpty { null },
            senses = row.senses,
            freqScore = row.freqScore,
            isCommon = row.isCommon,
            ankiDecks = ankiDecksByWord[row.displayWord].orEmpty(),
            pitch = row.pitch,
            frequencies = row.frequencies,
            readingRows = row.readingRows,
            importedGroups = row.importedGroups,
            // This row's share of the list's payload: null (the flat tier)
            // for a row whose imported dictionaries retained nothing
            // structured, so it mints no renderer.
            styled = styled?.forGroups(row.importedGroups),
        )
        cell.bind(
            data = data,
            scale = WORD_CELL_SCALE,
            inflectedForms = row.inflectedForms,
            onCellTap = {
                host.onInteraction()
                host.onWordTapped(row)
            },
            onSpeak = { speakFromCell(cell, row) },
            // The hidden-words eye, keyed like every other word path on
            // prefs.sourceLangId. The cells re-stub from the store's
            // revision ([attachCollectors]), the same path a toggle from the
            // sentence sheet takes.
            trailing = WordResultCell.TrailingAction.Hide(
                hidden = row.displayWord in HiddenWordsStore.snapshot(ctx, prefs.sourceLangId),
                onToggle = { shownHidden ->
                    host.onInteraction()
                    val app = ctx.applicationContext
                    val lang = prefs.sourceLangId
                    host.scope.launch {
                        val saved = HiddenWordsStore.setHidden(
                            app, lang, row.displayWord,
                            row.reading.ifEmpty { null }, hidden = !shownHidden,
                        )
                        // Nothing changed on screen when the write failed
                        // (the store leaves its cache alone), so say why.
                        if (!saved) {
                            Toast.makeText(app, R.string.hidden_word_save_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            ),
            // The cell borrows its styled renderer from the pool (see the
            // class doc); a null payload above, or an exhausted pool, keeps
            // it flat.
            styledRenderers = styledPool,
        )
    }

    /** Re-apply the hidden flag to every rendered cell in place: no list
     *  rebuild, no deck re-query, no reordering (a toggle never moves a
     *  row). A cell whose flag is unchanged is a no-op inside
     *  [WordResultCell.setHidden]. The one exception is a list drawn before
     *  its language's set had loaded: that render was never ordered, so the
     *  load's bump re-renders it as the fresh list it is. */
    fun applyHiddenState() {
        if (lastRenderedCells.isEmpty()) return
        val lang = prefs.sourceLangId
        if (!hiddenOrderApplied && HiddenWordsStore.isLoaded(lang)) {
            renderRows(lastRows, lastStyled)
            return
        }
        val hidden = HiddenWordsStore.snapshot(ctx, lang)
        for ((word, cells) in lastRenderedCells) {
            val flag = word in hidden
            cells.forEach { it.setHidden(flag) }
        }
    }

    /** Hidden-word changes from anywhere (this list's own eye taps, the
     *  sentence sheet on the other screen, the store's load landing after
     *  the rows were drawn) re-stub the cells in place, and a card added
     *  anywhere in the app refreshes the deck badges. Launched on [scope]:
     *  the fragment passes its STARTED-repeating scope, the page its own. */
    fun attachCollectors(scope: CoroutineScope) {
        scope.launch {
            AnkiManager.noteAddedTick.drop(1).collect { refreshWordBadges() }
        }
        scope.launch {
            HiddenWordsStore.revision.collect { applyHiddenState() }
        }
    }

    /** Clears the per-word cache and re-queries deck membership for the
     *  currently-rendered rows, updating each badge in place. No-op until
     *  the list is built. */
    fun refreshWordBadges() {
        if (container.isEmpty()) return
        val cells = lastRenderedCells
        if (cells.isEmpty()) return
        ankiDecksByWord.clear()
        val anki = AnkiManager(ctx)
        if (!anki.isAnkiDroidInstalled() || !anki.hasPermission()) {
            cells.values.flatten().forEach { it.updateAnkiDecks(emptyList()) }
            return
        }
        val words = cells.keys.toList()
        host.scope.launch {
            val result = withContext(Dispatchers.IO) { anki.decksByWord(words) }
            if (!host.isAlive) return@launch
            for ((word, list) in cells) {
                val decks = result[word].orEmpty()
                ankiDecksByWord[word] = decks
                list.forEach { it.updateAnkiDecks(decks) }
            }
        }
    }

    /** Batched "already in Anki" lookup for the list. Caches results (shared
     *  with the source lens's back-fill) and re-renders each matching cell's
     *  body so its meta row carries the deck pill. Gated + silent; words
     *  already cached were applied during bind, so only the rest queried. */
    private fun loadDeckBadges(
        words: List<String>,
        cellsByWord: Map<String, List<WordResultCell>>,
    ) {
        val anki = AnkiManager(ctx)
        if (!anki.isAnkiDroidInstalled() || !anki.hasPermission()) return
        val uncached = words.distinct().filter { it !in ankiDecksByWord }
        if (uncached.isEmpty()) return
        host.scope.launch {
            val result = withContext(Dispatchers.IO) { anki.decksByWord(uncached) }
            if (!host.isAlive) return@launch
            for (w in uncached) {
                val decks = result[w].orEmpty()
                ankiDecksByWord[w] = decks
                if (decks.isEmpty()) continue
                cellsByWord[w]?.forEach { it.updateAnkiDecks(decks) }
            }
        }
    }

    /** The lens's deck back-fill source: the list's cache first, else one
     *  query (cached for the list). Null when AnkiDroid is absent or
     *  unpermitted — nothing to show. */
    val deckSource: SourceTextLens.DeckSource = object : SourceTextLens.DeckSource {
        override fun cached(word: String): List<String>? = ankiDecksByWord[word]

        override suspend fun load(word: String): List<String>? {
            val anki = AnkiManager(ctx)
            if (!anki.isAnkiDroidInstalled() || !anki.hasPermission()) return null
            val decks = withContext(Dispatchers.IO) { anki.decksByWord(listOf(word))[word].orEmpty() }
            ankiDecksByWord[word] = decks
            return decks
        }
    }

    /** Speak a cell's word, driving that cell's own spinner. Each cell owns
     *  its in-flight [WordResultCell.speakJob] so concurrent taps on
     *  different rows don't clobber one another. */
    private fun speakFromCell(cell: WordResultCell, row: RowState) {
        if (cell.speakJob?.isActive == true) return
        cell.speakJob = host.scope.launch {
            cell.setSpeakLoading(true)
            try {
                speakWord(
                    host.ttsAlertTarget,
                    LensSpeakChip.Request(
                        row.displayWord,
                        prefs.sourceLangId,
                        reading = row.reading.ifEmpty { null },
                    ),
                )
            } finally {
                cell.setSpeakLoading(false)
            }
        }
    }

    /** 1dp ptDivider line inset from the start by pt_row_h_padding, matching
     *  `settings_row_divider` for rows inside the Words card. */
    private fun inflateDivider(): View {
        val dp1 = ctx.resources.displayMetrics.density.toInt().coerceAtLeast(1)
        return View(ctx).apply {
            tag = WORD_DIVIDER_TAG
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp1,
            ).apply {
                marginStart = ctx.resources.getDimensionPixelSize(R.dimen.pt_row_h_padding)
            }
            setBackgroundColor(ctx.themeColor(R.attr.ptDivider))
        }
    }

    private companion object {
        const val WORD_DIVIDER_TAG = "pt_word_divider"

        /** Word-cell text-size factor for the results list — a notch below
         *  [WordResultCell.DEFAULT_SCALE] (the "large" factor reserved for the
         *  full-screen dictionary results page) so the rows read denser here. */
        const val WORD_CELL_SCALE = 1.0f
    }
}
