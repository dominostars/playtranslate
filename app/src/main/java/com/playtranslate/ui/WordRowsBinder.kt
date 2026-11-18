package com.playtranslate.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.playtranslate.AnkiManager
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.ask.AskSentence
import com.playtranslate.ask.Explanation
import com.playtranslate.ask.WordInSentence
import com.playtranslate.vocab.HiddenWordsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The results page's Words card — the rows a settled word lookup renders
 * (reading, definitions, frequency, pitch, the hidden-words eye), with the
 * deck badges, the per-row speak action, the hidden-words ordering + in-place
 * re-stub, and the card's own eye (the persisted hide-words-section pref).
 * Host-agnostic: the in-app [TranslationResultFragment] and the floating
 * workspace's [WorkspaceSentencePage] both render through here, so the list
 * can't drift between the two surfaces. Views are found from [root]
 * (`section_words.xml`'s ids). The header is a [SectionHeaderRow] like the
 * source and target sections': its eye is a pinned [HeaderAction], and its
 * label carries the row count while rows are on screen ([syncLabel]).
 *
 * The rows are a [WordRowList] in [container]: ONE definitions document,
 * the pack's definitions and the imported dictionaries' alike drawn by its
 * page, with the deck badges and each row's speak spinner kept by the list.
 * It is the one list whose rows take two columns where its page is at
 * least 560px wide ([DefinitionsDocument.DocTheme.twoColumnsWhenWide]), as
 * the card is at full width on a landscape screen.
 * This binder adds what is the card's own: the hidden-words eye as the
 * rows' second button, the hidden-last ordering, and the card's visibility.
 * The document feeds its rows on demand and fetches their structured
 * content itself, under the language the lookup ran in
 * ([WordLookupsState.Settled.definitionLang]).
 *
 * Hidden words, the rules pinned by the store's tests: a fresh list draws
 * visible words first and hidden words last, each group in lookup order,
 * and only when the language's set had LOADED before the render
 * ([hiddenOrderApplied]); a list drawn before the load is provisional, and
 * the load's revision bump re-renders it instead of re-stubbing in place,
 * so the first real render is ordered like any other. A toggle never moves
 * a row: [applyHiddenState] re-stubs rows in place. The eye tap writes the
 * opposite of the state the row was SHOWING (the row the page reported the
 * tap on), never a fresh store read — the icon is what the user acted on.
 *
 * The card itself has two hide sources, and [isHidden] is their union:
 * the persisted pref ([Prefs.hideWordsSection]) and a session-only live
 * auto-hide ([setLiveMode]). The rows are lazy: the VM settles only the
 * analysis ([WordLookupsState.Analyzed], drawn like Loading) and builds the
 * rows when this card asks through [onRowsWanted], which it does whenever
 * it is effectively visible. A hidden card therefore costs no dictionary
 * lookups, no deck queries and no page: it holds no document view, so it
 * neither loads the WebView nor pins its render process. A
 * [WordLookupsState.Settled] that lands while the card is hidden (the rows
 * were requested, then the user hid the card before they settled) is kept
 * and drawn when the card is shown: the lookup was already paid, and the
 * next text starts lazy again.
 *
 * Rows are never shown as an empty card. A page takes a moment to draw the
 * rows it is handed (longer when it has to load first), and the card around
 * a page that has drawn nothing is a hairline. So the loading line is up for
 * the whole wait, whatever it is a wait for ([syncWaiting]): while a lookup
 * runs, and while the card has rows its page has not drawn ([rowsUndrawn]),
 * which is where a lookup's line stays until its rows are on screen and
 * what a card revealed with its rows already looked up shows meanwhile.
 * The wait ends without rows when none will be drawn: the page's notice
 * stands in, or its first rows could not be built, and then the card is as
 * empty as the page. One wait has no line: a page that had drawn its rows
 * and lost its renderer is blank at its old height until the new one has
 * drawn them, as on every surface ([WordRowList.awaitsFirstLayout]).
 *
 * A reveal draws the card first and takes its page after ([startPage]):
 * building a WebView holds the main thread (most of a second for a process's
 * first one on a slow phone), and a card that took its page inside the tap
 * would open, loading line and all, only once that was over. The page of a
 * card revealed while its lookup runs is started then too, so it loads
 * beside the lookup and not after it.
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
    /** The Words card's colour as it is seen, where the card lets what is
     *  behind it show through (the capture sheet's), for the band a pinned
     *  word head stands on ([cardDocTheme]); null for an opaque card of the
     *  theme's card colour. */
    cardColor: Int? = null,
) {
    interface Host {
        /** The host view tree is still live — guards every async re-entry. */
        val isAlive: Boolean

        /** Where badge queries, speak jobs and the eye's store write run;
         *  view-scoped on the fragment, page-scoped on the workspace. */
        val scope: CoroutineScope

        val ttsAlertTarget: TtsAlertTarget

        /** A row body tap: open that word's detail. [explanation] is the
         *  answer standing in the row when it was tapped
         *  ([WordRowList.explanationOf]), null with none: what the detail
         *  shows under the word's definitions, and what a card made for the
         *  word from there starts its note with. */
        fun onWordTapped(row: RowState, explanation: Explanation?)

        /** Any user interaction (live-mode hosts pause on it). */
        fun onInteraction() {}

        /** A row's explain button was tapped with no AI model to put the
         *  question to: show the alert that says so ([askNeedsLlm]) on this
         *  surface, as its Ask section's does. No default: every surface
         *  with this card has one to show. */
        fun onAskNeedsLlm()
    }

    /** The document's slot; exposed for the page's layout. */
    val container: LinearLayout = root.findViewById(R.id.mainWordsContainer)
    private val loadingText: TextView = root.findViewById(R.id.tvMainWordsLoading)
    private val noWordsText: TextView = root.findViewById(R.id.tvNoWords)
    private val card: View = root.findViewById(R.id.cardWords)
    private val header: SectionHeaderRow = root.findViewById(R.id.wordsHeaderRow)
    private val label: TextView = root.findViewById(R.id.labelWords)

    /** The card's eye: pinned, so it sits at the header's end like the
     *  other sections' eyes. The row tints and draws it from this model. */
    private val eye = HeaderAction(
        R.id.btnToggleWords, R.drawable.ic_visibility,
        ctx.getString(R.string.cd_toggle_words_visibility), pinned = true,
    )

    private val list: WordRowList<RowState> = wordRowList(
        ctx, container, host.scope,
        object : WordRowList.Host<RowState> {
            override val isAlive: Boolean get() = host.isAlive
            override val deckBadges: Boolean get() = true

            // The hidden-words eye, keyed like every other word path on
            // prefs.sourceLangId: a hidden word is a stub whose eye shows it.
            override fun trailing(row: RowState): DefRow.Trailing =
                if (row.displayWord in HiddenWordsStore.snapshot(ctx, prefs.sourceLangId)) DefRow.Trailing.SHOW
                else DefRow.Trailing.HIDE

            override fun onOpen(row: RowState) = host.onWordTapped(row, list.explanationOf(row))

            // The opposite of what the row was SHOWING when it was tapped.
            override fun onTrailing(row: RowState, shown: DefRow.Trailing) =
                setHidden(row, hidden = shown != DefRow.Trailing.SHOW)

            override suspend fun speak(row: RowState) = speakWord(
                host.ttsAlertTarget,
                SpeakRequest(row.displayWord, prefs.sourceLangId, reading = row.reading.ifEmpty { null }),
            )

            override fun onInteraction() = host.onInteraction()

            // The lookup's own text and pair, which came with these rows
            // ([WordLookupsState.Settled.sentence]). The forms are those the
            // word takes in it: the first occurrence's, and every inflected
            // one the row gathers. Which of them say anything is the
            // question's to judge when it names the word
            // ([com.playtranslate.ask.AskPrompts.wordValue]).
            override fun explainSubject(row: RowState): WordInSentence? = lastSentence?.let { sentence ->
                WordInSentence(
                    sentence,
                    row.displayWord,
                    row.reading.ifEmpty { null },
                    listOf(row.surface) + row.inflectedForms.map { it.surface },
                )
            }

            override fun onExplainNeedsModel() = host.onAskNeedsLlm()
        },
        WordRowList.COMPACT_SCALE,
        twoColumnsWhenWide = true,
        cardColor = cardColor,
        onLayout = {
            syncWaiting()
            onHeightChanged?.invoke()
        },
    )

    /** The rows of the last Settled render as the lookup gave them, and the
     *  language they were looked up in, for the late-load re-render. */
    private var lastRows: List<RowState> = emptyList()
    private var lastLang: String = ""

    /** The text the rows last rendered are the words of, and its pair, as
     *  their lookup gave it ([WordLookupsState.Settled.sentence]): what a
     *  row's explain question is about. Null for rows that came with none.
     *  Read only for rows that are listed, so it is not reset when they go. */
    private var lastSentence: AskSentence? = null

    /** True once the rendered rows were ordered against a LOADED hidden set
     *  (see [renderRows]); while false, the next revision re-renders
     *  instead of re-stubbing in place. */
    private var hiddenOrderApplied = false

    /** The live session's auto-hide (class doc): set on live start, cleared
     *  on live stop and by any eye tap. Never persisted. */
    private var liveAutoHide = false

    /** The last live state [setLiveMode] saw; the edge detector. */
    private var liveRunning = false

    /** The last state rendered was a lookup still on its way (Loading or
     *  Analyzed): the loading line is its sign. */
    private var lookupRunning = false

    /** A reveal has shown the card and its page is to start once that frame
     *  is drawn ([startPage]); the list is not shown yet. */
    private var pagePending = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * The second half of a reveal: the list takes its page (drawing the rows
     * it holds), or starts loading one for the rows a running lookup will
     * bring. A card with neither takes none.
     *
     * Posted as an ordinary message, which is what puts it after the card's
     * frame: the reveal's visibility change schedules a layout pass, and the
     * main queue holds ordinary messages back until that pass has run and
     * handed its frame to the render thread. With no pass pending (a card
     * not on screen) it runs at once. Never a coroutine: Dispatchers.Main
     * posts asynchronous messages, which are not held back.
     */
    private val startPage = Runnable {
        if (!pagePending) return@Runnable
        pagePending = false
        if (!host.isAlive) return@Runnable
        list.shown = true
        if (lookupRunning) list.prepare()
        syncWaiting()
        onHeightChanged?.invoke()
    }

    /** The card has rows to show that its page has not drawn: the rows of a
     *  reveal whose page has yet to start, or of a page still to lay them
     *  out ([WordRowList.awaitsFirstLayout]). */
    private val rowsUndrawn: Boolean
        get() = if (pagePending) !list.isEmpty else list.awaitsFirstLayout

    /** Asks the VM for the current analysis's rows
     *  ([TranslationResultViewModel.requestWordRows]). Invoked whenever the
     *  card is effectively visible and rows may be needed: every
     *  [applyWordsVisibility] that leaves it shown, and every
     *  [WordLookupsState.Analyzed] rendered while it is shown. The VM side is
     *  idempotent (a no-op unless rows are pending and none are in flight),
     *  so a redundant call costs nothing. */
    var onRowsWanted: (() -> Unit)? = null

    /** The card's height may have changed: its page laid its rows out, a
     *  lookup state changed what it shows, or its eye was tapped. For a host
     *  that sizes itself to its content (the capture sheet); a page just
     *  reflows. The change may not have laid out yet when this is called. */
    var onHeightChanged: (() -> Unit)? = null

    /** The card's effective hidden state: the persisted pref or the live
     *  session's auto-hide. */
    val isHidden: Boolean get() = prefs.hideWordsSection || liveAutoHide

    init {
        header.setActions(listOf(eye), R.id.btnMoreWords)
        eye.onClick = {
            // The opposite of what the card is SHOWING, whichever source hid
            // it: a tap on a live auto-hidden card reveals it (and clears a
            // stale hiding pref, since the reveal is what the user asked for),
            // and a tap on a shown card is a persisted hide. Either way the
            // auto-hide is spent for this session.
            val hide = !isHidden
            prefs.hideWordsSection = hide
            liveAutoHide = false
            applyWordsVisibility()
            onHeightChanged?.invoke()
        }
    }

    /** Reflect the effective hidden state ([isHidden]): the card and the eye
     *  icon; a shown card asks for its rows ([onRowsWanted]) and draws the
     *  ones it has, a hidden card lets its page go (class doc). A card that
     *  goes from hidden to shown is drawn first and takes its page after
     *  ([startPage]). */
    fun applyWordsVisibility() {
        val hidden = isHidden
        card.visibility = if (hidden) View.GONE else View.VISIBLE
        eye.icon = if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility
        header.labelDimmed = hidden
        if (hidden) {
            cancelPageStart()
            list.shown = false
        } else if (!list.shown && !pagePending) {
            pagePending = true
            mainHandler.post(startPage)
        }
        syncWaiting()
        syncLabel()
        if (!hidden) onRowsWanted?.invoke()
    }

    private fun cancelPageStart() {
        pagePending = false
        mainHandler.removeCallbacks(startPage)
    }

    /**
     * The loading line, from where the lookup and the page stand (class
     * doc): up while a lookup runs and while the card has rows its page has
     * not drawn. Called wherever either may have moved: a render, a change
     * of the card's visibility, the start of its page, the page's layout.
     */
    private fun syncWaiting() {
        loadingText.isVisible = lookupRunning || rowsUndrawn
    }

    /** The header's label: the section's name, with the number of rows
     *  while the card is showing rows. A hidden card builds none (class
     *  doc), so it has no count to show. */
    private fun syncLabel() {
        val count = if (isHidden) 0 else lastRows.size
        label.text =
            if (count > 0) ctx.getString(R.string.section_words_count, count)
            else ctx.getString(R.string.section_words)
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

    val isEmpty: Boolean get() = list.isEmpty

    /** The answers standing in this card's rows now, each with its row's
     *  word, in the rows' order: the words' part of the note a card of
     *  their sentence starts with ([sentenceCardNote]). A card hidden with
     *  answers in its rows still has them, as it shows them again when it
     *  is revealed. */
    fun explainedWords(): List<ExplainedWord> =
        list.explainedRows().map { (row, answer) -> ExplainedWord(row.displayWord, answer.text) }

    /** What a controller cursor can reach here, in visual order: the
     *  header's shown actions (its eye, which stays whether the card shows
     *  or not), then each tappable target the page reported for its rows (a
     *  row, its speak button, its hidden-words eye), all inside the one
     *  document view ([NavAction.target]). A hidden card holds no page, so
     *  it has no such targets. */
    fun navigableActions(): List<NavAction> = header.navActions() + list.navActions()

    /** The user is about to see this card's rows (the lookup for them is
     *  running): start loading the page now. A no-op for a hidden card.
     *  Never called inside a page's entrance animation: the first page of a
     *  process blocks the main thread while the WebView loads. */
    fun prepareRows() {
        if (!isHidden) list.prepare()
    }

    /** Mirror a [WordLookupsState] into the card. The pipeline itself runs
     *  in the VM; this only renders, and asks for the rows of an
     *  [WordLookupsState.Analyzed] when the card is shown (class doc). */
    fun render(state: WordLookupsState) {
        when (state) {
            is WordLookupsState.Idle -> {
                lookupRunning = false
                noWordsText.isGone = true
                clearRows()
            }
            // Analyzed draws exactly like Loading: to the card the rows are
            // still on their way (they start on the request below, or on the
            // reveal that makes the card visible).
            is WordLookupsState.Loading, is WordLookupsState.Analyzed -> {
                lookupRunning = true
                clearRows()
                loadingText.text = ctx.getString(R.string.words_loading)
                noWordsText.isGone = true
                if (state is WordLookupsState.Analyzed && !isHidden) onRowsWanted?.invoke()
            }
            is WordLookupsState.Settled -> {
                lookupRunning = false
                renderRows(state.rows, state.definitionLang, state.sentence)
                noWordsText.visibility = if (state.rows.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        syncWaiting()
        onHeightChanged?.invoke()
    }

    private fun clearRows() {
        lastRows = emptyList()
        list.clear()
        syncLabel()
    }

    /** Host teardown: the page and its shell go. Must be called when the
     *  view tree goes away ([TranslationResultContent.release]); terminal
     *  and idempotent. */
    fun release() {
        cancelPageStart()
        list.release()
    }

    private fun renderRows(rows: List<RowState>, lang: String, sentence: AskSentence?) {
        lastRows = rows
        lastLang = lang
        lastSentence = sentence
        // A fresh list is ordered hidden-last. isLoaded BEFORE snapshot: true
        // means the snapshot taken next is the loaded set and this ordering
        // is final; false means it is provisional, and the load's revision
        // bump re-renders (applyHiddenState) rather than re-stubbing in
        // place, so the list's first real render is ordered like any other.
        val sourceLang = prefs.sourceLangId
        hiddenOrderApplied = HiddenWordsStore.isLoaded(sourceLang)
        list.setRows(rows.hiddenLast(HiddenWordsStore.snapshot(ctx, sourceLang)), lang)
        syncLabel()
    }

    private fun setHidden(row: RowState, hidden: Boolean) {
        val app = ctx.applicationContext
        val lang = prefs.sourceLangId
        host.scope.launch {
            val saved = HiddenWordsStore.setHidden(
                app, lang, row.displayWord, row.reading.ifEmpty { null }, hidden = hidden,
            )
            // Nothing changed on screen when the write failed (the store
            // leaves its cache alone), so say why. A write that landed
            // re-stubs the row from the store's revision ([attachCollectors]),
            // the same path a toggle from the sentence sheet takes.
            if (!saved) {
                Toast.makeText(app, R.string.hidden_word_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Re-apply the hidden flag to every row in place: no reordering (a
     *  toggle never moves a row), no deck re-query, and only the rows whose
     *  flag changed are redrawn. The one exception is a list drawn before
     *  its language's set had loaded: that render was never ordered, so the
     *  load's bump re-renders it as the fresh list it is. */
    fun applyHiddenState() {
        if (list.isEmpty) return
        if (!hiddenOrderApplied && HiddenWordsStore.isLoaded(prefs.sourceLangId)) {
            renderRows(lastRows, lastLang, lastSentence)
            return
        }
        list.publish()
    }

    /** Hidden-word changes from anywhere (this list's own eye taps, the
     *  sentence sheet on the other screen, the store's load landing after
     *  the rows were drawn) re-stub the rows in place, and a card added
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

    /** Re-queries deck membership for the listed words and redraws the rows
     *  whose badge changed. No-op until the list is built. */
    fun refreshWordBadges() = list.refreshDeckBadges()

    // ── What the page shows, for its host ────────────────────────────────

    /** The rows' view once they are drawn, for a host that anchors its
     *  scroll on a row ([rowAt], [rowTop]). */
    val rowsView: View? get() = list.view

    /** The id of the row at [y] ([rowsView]'s own px), else of the first
     *  row below it; null when no row is laid out. */
    internal fun rowAt(y: Int): String? = list.rowAt(y)

    /** The top of row [id] in [rowsView]'s own px; null when it is gone. */
    internal fun rowTop(id: String): Int? = list.rowTop(id)

    /** The rows as last published, in drawn order. */
    @get:VisibleForTesting
    internal val shownRows: List<DefRow.Word> get() = list.shownRows

    /** A tap the page would report on the row at [index]. */
    @VisibleForTesting
    internal fun tapForTest(index: Int, action: String) = list.tapForTest(index, action)
}
