package com.playtranslate.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import com.playtranslate.AnkiManager
import com.playtranslate.R
import com.playtranslate.ask.AskState
import com.playtranslate.ask.Explanation
import com.playtranslate.ask.WordExplanations
import com.playtranslate.ask.WordInSentence
import com.playtranslate.ask.explanation
import com.playtranslate.translation.TranslationBackendRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A list of looked-up words drawn as ONE definitions document: what the
 * results page's Words card, the dictionary search, the word page's "Words"
 * and "Other matches" sections and the lens all share. Each word of type
 * [T] is the one word row ([DefRow.Word]), with a speak button, the host's
 * second button, and a tap that opens the word; [describe] says what a row
 * shows of its word, and an optional footer is a one-line action under the
 * rows (the lens's "Load more").
 *
 * It keeps the state a row is drawn from beyond the row itself (the deck
 * cache, which rows are speaking, which of a row's dictionaries the user has
 * shut, what was asked about a row's word and what came of it) and
 * [publish] maps it all to rows. Every
 * change ends in [publish], and the document patches only the rows whose
 * value changed, so a deck badge, a spinner or the host's own change (a
 * word hidden) never rebuilds the list.
 *
 * The list holds a page only while it has rows and is [shown]: a hidden or
 * empty list neither loads the WebView nor keeps its render process.
 */
internal class WordRowList<T>(
    private val ctx: Context,
    slot: ViewGroup,
    private val scope: CoroutineScope,
    private val host: Host<T>,
    /** What a row shows of its word. Read once per [setRows]. */
    private val describe: (T) -> Entry,
    /** The page's theme, read each time a page is taken. */
    theme: () -> DefinitionsDocument.DocTheme,
    /** Whether the rows' definitions have live links ([DefRow.Word.full]):
     *  true where the row is where the word is read, false for a list whose
     *  rows tap through to the word's own page. */
    private val full: Boolean = false,
    /** The page laid the rows out, or no layout will follow for them: the
     *  notice took the page's place after the renderer died twice
     *  ([showsNotice]), or their first chunk could not be built. */
    onLayout: () -> Unit = {},
    /** Whether anything could answer an explain question, asked when a
     *  row's button is tapped, as the Ask section asks before it takes a
     *  question ([AskSectionBinder]). */
    private val hasLlm: () -> Boolean = { TranslationBackendRegistry.hasCompleter() },
) {
    /** What a row shows of its word. [data]'s Anki decks are the list's to
     *  fill ([Host.deckBadges]); the rest is drawn as given. */
    class Entry(
        val data: WordDefinitionData,
        /** Draws the chevron and makes the row a tap target ([Host.onOpen]). */
        val opens: Boolean = true,
        /** A warning line over the definitions ("Machine translated"). */
        val label: String? = null,
        /** What stands in for the definitions when the word has none. */
        val emptyText: String? = null,
    )

    interface Host<T> {
        /** The host view tree is still live; guards every async re-entry. */
        val isAlive: Boolean

        /** Whether rows carry the "already in Anki" deck pill. */
        val deckBadges: Boolean

        /** [row]'s second button as it should draw now; null for none. */
        fun trailing(row: T): DefRow.Trailing?

        /** Whether a press held on [row]'s second button is an action of
         *  its own ([onTrailingHold]) and not a tap. */
        fun trailingHolds(row: T): Boolean = false

        /** The row itself was tapped: open the word. */
        fun onOpen(row: T)

        /** A link in [row]'s definitions to another word was tapped
         *  ([DefAction.reference]): open [term], read as [reading] where the
         *  link names one, as [onOpen] opens the row's own word. Only a list
         *  whose rows' links are live reports it (`full`); the default is
         *  for the lists whose rows have none. */
        fun onReference(row: T, term: String, reading: String?) {}

        /** The second button was tapped while it showed [shown], which is
         *  what the user acted on. */
        fun onTrailing(row: T, shown: DefRow.Trailing)

        /** The second button was held ([trailingHolds]). */
        fun onTrailingHold(row: T) {}

        /** The sentence [row]'s word was read in, and how a question about
         *  the word names it: what gives the row its explain button
         *  ([DefRow.Word.explain]). Null, the default, for a word with no
         *  sentence behind it (a dictionary search's, a word page's list's):
         *  no button. Read at every draw, so it is cheap, and it is the
         *  identity an answer is kept by: an answer stays on its row only
         *  while this is what it was asked about. */
        fun explainSubject(row: T): WordInSentence? = null

        /** An explain button was tapped with nothing that could answer:
         *  raise the "needs an AI model" alert ([askNeedsLlm]) where this
         *  list's surface shows its alerts. Never called for a list whose
         *  rows have no button. */
        fun onExplainNeedsModel() {}

        /** Speak [row]'s word and return when it is done; the row draws its
         *  spinner for as long as this runs. */
        suspend fun speak(row: T)

        /** The footer was tapped. */
        fun onFooter() {}

        /** Any tap in the list (live-mode hosts pause on it). */
        fun onInteraction() {}
    }

    private val document = DefinitionDocument(
        ctx, slot, scope,
        theme = theme,
        onAction = ::onRowAction,
        onLayout = onLayout,
    )

    private var rows: List<T> = emptyList()
    private var entries: List<Entry> = emptyList()
    private var ids: List<String> = emptyList()
    private var footer: String? = null
    private var lang: String = ""

    /** word to its Anki decks, as far as they have been asked for. */
    private val decksByWord = HashMap<String, List<String>>()

    /** Row ids with a speak in flight (the row draws its spinner), and the
     *  jobs, so a second tap on a speaking row does nothing. */
    private val speaking = HashSet<String>()
    private val speakJobs = HashMap<String, Job>()

    /** Row id to the dictionaries the user has shut in that row
     *  ([dictionaryKey], [OWN_DICTIONARY_KEY]). A row's own, and only for as
     *  long as the row is listed: a rebind of the same words keeps it, a row
     *  that leaves the list forgets it ([sameRowAs], which also keeps an id
     *  from handing it to another row), and so does a new lookup ([clear]).
     *  Never stored: every dictionary of a new row is open. */
    private val shut = HashMap<String, Set<String>>()

    /** The explain questions of the rows, by row id: each asked about the
     *  word in its sentence as the host named it then
     *  ([Host.explainSubject]). A row's own, and for as long as the row is
     *  listed with that same word in that same sentence ([setRows]): a
     *  rebind of the same rows keeps a question, in flight or answered (the
     *  lens's later-loaded entries, a re-order), and the same word under
     *  another sentence starts with none, whether or not the list was
     *  emptied between the two. A question whose state moves redraws its
     *  row. Never stored; gone with the list ([clear], [release]). */
    private val explanations = WordExplanations(scope, onChange = { publish() })

    private var released = false

    /** Whether the list is on screen. A list that is not holds no page; it
     *  draws the rows it has when it is shown again. */
    var shown: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (value) publish() else document.release()
        }

    /** The rows as given, in their drawn order. */
    val wordRows: List<T> get() = rows

    val isEmpty: Boolean get() = rows.isEmpty()

    /**
     * The answer standing in [row]'s row: what its explain question
     * brought, whole (its text, who gave it, whether it was cut), as the
     * word's page draws it again. Null with nothing asked, with a question
     * still out, with one that failed or that the service would not answer,
     * with an empty answer, and once the user has put the answer away.
     * [row] is one of [wordRows] as a host callback hands it, found by
     * identity, so rows that are equal are still told apart.
     */
    fun explanationOf(row: T): Explanation? {
        val id = ids.getOrNull(rows.indexOfFirst { it === row }) ?: return null
        return (explanations.state(id) as? AskState.Answered)?.takeIf { it.text.isNotBlank() }?.explanation
    }

    /** Every row with an answer standing in it ([explanationOf]), in drawn
     *  order, with that answer. */
    fun explainedRows(): List<Pair<T, Explanation>> =
        rows.mapNotNull { row -> explanationOf(row)?.let { row to it } }

    /** The user is about to see rows here (their lookup is running): start
     *  loading the page now. A no-op for a list that is not shown. Never
     *  call it inside a page's entrance animation: the first page of a
     *  process blocks the main thread while the WebView loads. */
    fun prepare() {
        if (!released && shown) document.prepare()
    }

    /**
     * The words to list, in drawn order, looked up under [definitionLang]
     * (the dictionary store's language key; the document fetches each row's
     * structured content with it, so it must be the lookup's own), and the
     * [footer] under them, null for none. An empty list with no footer lets
     * the page go. Decks not yet known are asked for.
     */
    fun setRows(newRows: List<T>, definitionLang: String, footer: String? = null) {
        cancelSpeaking()
        val entriesBefore = entries
        val idsBefore = ids
        rows = newRows
        entries = newRows.map(describe)
        ids = rowIdsOf(entries)
        val sameRow = sameRowAs(entriesBefore, idsBefore)
        shut.keys.retainAll(sameRow)
        explanations.retain { id, subject ->
            sameRow(id) && host.explainSubject(newRows[ids.indexOf(id)]) == subject
        }
        this.footer = footer
        lang = definitionLang
        if (newRows.isEmpty() && footer == null) {
            // Nothing was found: the page goes, and with it this list's
            // claim on one ([prepare]), which alone keeps a spare loaded.
            // [clear] is the empty list of a lookup still running.
            document.release()
            return
        }
        publish()
        loadDeckBadges()
    }

    /** The footer's text alone (its loading state), the rows as they are. */
    fun setFooter(text: String?) {
        if (footer == text) return
        footer = text
        publish()
    }

    /** No rows, the page kept for the ones a running lookup will bring. */
    fun clear() {
        cancelSpeaking()
        shut.clear()
        explanations.clear()
        rows = emptyList()
        entries = emptyList()
        ids = emptyList()
        footer = null
        publish()
    }

    /**
     * The rows as the document should show them now. The host calls it when
     * something its [Host.trailing] reads has changed; everything in here
     * that changes a row calls it too.
     */
    fun publish() {
        if (released || !shown) return
        val words: List<DefRow> = rows.mapIndexed { i, row ->
            val entry = entries[i]
            DefRow.Word(
                id = ids[i],
                data = entry.data.copy(ankiDecks = decksByWord[entry.data.word].orEmpty()),
                opens = entry.opens,
                speak = if (ids[i] in speaking) DefRow.Speak.LOADING else DefRow.Speak.IDLE,
                trailing = host.trailing(row),
                trailingHolds = host.trailingHolds(row),
                full = full,
                shutDictionaries = shut[ids[i]].orEmpty(),
                label = entry.label,
                emptyText = entry.emptyText,
                explain = host.explainSubject(row)?.let { explainOf(ids[i]) },
            )
        }
        document.setRows(words + listOfNotNull(footer?.let { DefRow.More(FOOTER_ID, it) }), lang)
    }

    /** Terminal: the page and its shell go, nothing draws afterwards. */
    fun release() {
        released = true
        cancelSpeaking()
        explanations.clear()
        document.release()
    }

    private fun cancelSpeaking() {
        speakJobs.values.forEach { it.cancel() }
        speakJobs.clear()
        speaking.clear()
    }

    private fun onRowAction(row: DefRow, action: String) {
        if (row is DefRow.More) {
            host.onInteraction()
            host.onFooter()
            return
        }
        val state = rows.getOrNull(ids.indexOf(row.id)) ?: return
        host.onInteraction()
        when (action) {
            DefAction.OPEN -> host.onOpen(state)
            DefAction.SPEAK -> speak(row.id, state)
            DefAction.TRAILING -> (row as? DefRow.Word)?.trailing?.let { host.onTrailing(state, it) }
            DefAction.hold(DefAction.TRAILING) -> host.onTrailingHold(state)
            DefAction.EXPLAIN -> explain(row.id, state)
            DefAction.EXPLAIN_CLOSE -> {
                explanations.close(row.id)
                publish()
            }
            DefAction.EXPLAIN_RETRY -> explanations.retry(row.id)
            else -> {
                val reference = DefAction.referenceOf(action)
                if (reference != null) {
                    host.onReference(state, reference.term, reference.reading)
                } else {
                    toggleDictionary(row, action)
                }
            }
        }
    }

    /**
     * Whether a row id still names the row it named before a new row set,
     * the list having gone from [entriesBefore] under [idsBefore] to the
     * rows it has now: what a row keeps across the change is kept under its
     * id (the dictionaries shut in it, its explain question), so an id that
     * is gone, and an id that has passed to another row, keep nothing. Rows
     * that share a word and reading are told apart by their order alone
     * ([rowIdsOf]), so when one of them leaves, or another is listed before
     * it (the lens's later-loaded entries), an id names a different row;
     * among such rows an id names the row it did only while the row under
     * it is the row it was, read off what the row draws ([Entry.data]). A
     * row that is the only one of its word and reading, before and now, is
     * the same row whatever changed in it.
     */
    private fun sameRowAs(entriesBefore: List<Entry>, idsBefore: List<String>): (String) -> Boolean {
        val shared = sharedKeys(entriesBefore) + sharedKeys(entries)
        return { id ->
            val now = entries.getOrNull(ids.indexOf(id))
            now != null &&
                (keyOf(now) !in shared || now.data == entriesBefore.getOrNull(idsBefore.indexOf(id))?.data)
        }
    }

    /**
     * A row's explain question as the row draws it: the button alone with
     * nothing asked, the spinner while the question is out (and nothing
     * else: the row does not move until there is something to read), and
     * once there is, the block, in the words every surface draws a
     * question's state in ([askDisplay]).
     */
    private fun explainOf(id: String): DefRow.Explain {
        val state = explanations.state(id)
        if (state is AskState.Asking) return DefRow.Explain(busy = true)
        val shown = askDisplay(ctx, state) ?: return DefRow.Explain()
        return DefRow.Explain(
            answer = DefRow.Answer(shown.title, shown.body, shown.muted, shown.footer, shown.retry),
        )
    }

    /**
     * A tap on [row]'s explain button. With nothing asked it asks, or, with
     * nothing that could answer, hands the host the alert that says so
     * (asked at the tap, as the Ask section does: a model set up a moment
     * ago answers). With a question out or on screen it takes it back: a
     * request in flight is cancelled, an answer is put away.
     */
    private fun explain(id: String, row: T) {
        val subject = host.explainSubject(row) ?: return
        if (explanations.state(id) != AskState.Idle) {
            explanations.close(id)
            publish()
            return
        }
        if (!hasLlm()) {
            host.onExplainNeedsModel()
            return
        }
        explanations.ask(id, subject, ctx.getString(R.string.word_explain_title))
    }

    /** Shuts, or opens again, the dictionary whose title's [action] was
     *  tapped in [row] as it was drawn then, in that row alone: the pack's
     *  own, or the imported one at that place. Any other action is none. */
    private fun toggleDictionary(row: DefRow, action: String) {
        val key = if (action == DefAction.OWN_DICTIONARY) {
            OWN_DICTIONARY_KEY
        } else {
            val group = DefAction.dictionaryIndex(action) ?: return
            (row as? DefRow.Word)?.data?.importedGroups?.getOrNull(group)?.dictionaryKey() ?: return
        }
        val now = shut[row.id].orEmpty()
        shut[row.id] = if (key in now) now - key else now + key
        publish()
    }

    /** Speaks the word of row [rowId] as a tap on its Speak button does
     *  (the row's spinner, one speak per row at a time), played by [play]
     *  in place of [Host.speak]: for an owner that speaks a row by itself,
     *  with its own rule for a failure (the lens's auto-play, which raises
     *  no alert). Nothing for an id this list does not hold. */
    fun speak(rowId: String, play: suspend (T) -> Unit) {
        val row = rows.getOrNull(ids.indexOf(rowId)) ?: return
        speak(rowId, row, play)
    }

    /** Each row has its own job, so taps on different rows don't clobber
     *  one another and a second tap on a speaking row does nothing. */
    private fun speak(id: String, row: T, play: suspend (T) -> Unit = { host.speak(it) }) {
        if (speakJobs[id]?.isActive == true) return
        // Stored before it runs: a scope that runs a launch at once (the
        // lens's) would otherwise finish a [play] that never suspends before
        // the job is stored, and the row's spinner would stay up.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            speaking += id
            publish()
            try {
                play(row)
            } finally {
                // Only while this is still the row's job: a new list cancels
                // the old jobs and starts with nothing speaking.
                if (speakJobs[id] === coroutineContext[Job]) {
                    speakJobs.remove(id)
                    speaking -= id
                    publish()
                }
            }
        }
        speakJobs[id] = job
        job.start()
    }

    // ── Anki deck badges ─────────────────────────────────────────────────

    /** Asks again for every listed word's decks (a card was added, the
     *  page came back) and redraws the rows whose pill changed. */
    fun refreshDeckBadges() {
        if (!host.deckBadges || rows.isEmpty()) return
        decksByWord.clear()
        val anki = AnkiManager(ctx)
        if (!anki.isAnkiDroidInstalled() || !anki.hasPermission()) {
            publish()
            return
        }
        val words = entries.map { it.data.word }.distinct()
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { anki.decksByWord(words) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            if (!host.isAlive) return@launch
            for (word in words) decksByWord[word] = result[word].orEmpty()
            publish()
        }
    }

    /** Batched "already in Anki" lookup for the words not asked yet, after
     *  the rows are up so the query never delays them. Gated and silent. */
    private fun loadDeckBadges() {
        if (!host.deckBadges) return
        val anki = AnkiManager(ctx)
        if (!anki.isAnkiDroidInstalled() || !anki.hasPermission()) return
        val uncached = entries.map { it.data.word }.distinct().filter { it !in decksByWord }
        if (uncached.isEmpty()) return
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { anki.decksByWord(uncached) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // An Anki failure costs the badge, never the rows.
                return@launch
            }
            if (!host.isAlive) return@launch
            for (w in uncached) decksByWord[w] = result[w].orEmpty()
            publish()
        }
    }

    // ── What the page shows, for its host ────────────────────────────────

    /** The document's view once the list has drawn, for a host that anchors
     *  its scroll on a row ([rowAt], [rowTop]). */
    val view: View? get() = document.hostView

    /** The id of the row at [y] ([view]'s own px), else of the first row
     *  below it; null when no row is laid out. */
    fun rowAt(y: Int): String? = document.rowAt(y)

    /** The top of row [id] in [view]'s own px; null when it is gone. */
    fun rowTop(id: String): Int? = document.rowTopPx(id)

    /** What a cursor on [target] needs to show of [view], top to bottom in
     *  its own px ([DefinitionDocument.revealSpanPx]). */
    fun revealSpan(target: DocTarget): IntRange = document.revealSpanPx(target)

    /** The id of the word at [index] in the list as last set. */
    fun idAt(index: Int): String? = ids.getOrNull(index)

    /** Every row and tappable element the page has laid out, in page
     *  order, for a host that walks them with its own cursor (the lens). */
    val targets: List<DocTarget> get() = document.targets

    /** Draws the controller's highlight on [target], or clears it (null). */
    fun setCursor(target: DocTarget?) = document.setCursor(target)

    /** What a tap on [act] of row [rowId] does (a controller's press). */
    fun activate(rowId: String, act: String) = document.activate(rowId, act)

    /** The tappable targets the page last reported, as actions for a
     *  controller cursor that walks them with the host's own controls (the
     *  capture sheet's): each a target inside the document's view
     *  ([NavAction.target]). None while the list holds no page. */
    fun navActions(): List<NavAction> = document.navActions()

    /** True when the notice stands where the rows would be (no usable
     *  WebView): no layout report will come. */
    val showsNotice: Boolean get() = document.showsNotice

    /** True while the list has rows to show and its page's view has had no
     *  layout: from the rows being set, or the list being shown again, until
     *  the page's first layout. Never true for a list that is not shown,
     *  that has nothing to draw, whose notice stands in for the page or
     *  whose first rows could not be built, since no layout is on its way to
     *  any of them. A new row set over rows already on screen is not a wait:
     *  the old ones stay up until it is laid out.
     *
     *  It is read off the view's height ([DefinitionDocument.heightPx]), so
     *  it is also false, with nothing on screen, while a page that had drawn
     *  its rows recovers from a renderer death: the view keeps its height
     *  and is blank until the new shell draws them. */
    val awaitsFirstLayout: Boolean
        get() = !released && shown && !(rows.isEmpty() && footer == null) &&
            !document.showsNotice && !document.buildFailed && document.heightPx <= 1

    /** The word rows as last published, in drawn order. */
    @get:VisibleForTesting
    val shownRows: List<DefRow.Word> get() = document.shownRows.filterIsInstance<DefRow.Word>()

    /** Every row as last published, the footer included. */
    @get:VisibleForTesting
    val shownDocRows: List<DefRow> get() = document.shownRows

    /** A tap the page would report on the row at [index]. */
    @VisibleForTesting
    fun tapForTest(index: Int, action: String) {
        document.shownRows.getOrNull(index)?.let { onRowAction(it, action) }
    }

    /** Stands in for the page under a JVM test ([DefinitionDocument.layoutForTest]). */
    @VisibleForTesting
    fun layoutForTest(rowHeightCssPx: Int) = document.layoutForTest(rowHeightCssPx)

    companion object {
        /** A row's gloss size in sp before a list's scale. */
        const val GLOSS_SP = 16.5f

        /** The scale of a list that is the page's main content (the
         *  dictionary search, a word page's sections). */
        const val LARGE_SCALE = 1.12f

        /** The scale of the results page's Words card: a notch below, so
         *  its rows read denser beside the translation. */
        const val COMPACT_SCALE = 1.0f

        /** The footer's row id. */
        const val FOOTER_ID = "footer"

        /** A document id per row: the word and its reading, which a rebind
         *  of the same lookup repeats (so it patches), with a counter for a
         *  list that holds the same pair twice. */
        fun rowIdsOf(entries: List<Entry>): List<String> {
            val seen = HashMap<String, Int>()
            return entries.map { e ->
                val key = keyOf(e)
                val n = seen.merge(key, 1, Int::plus)!!
                if (n == 1) key else "$key\u0001$n"
            }
        }

        /** What a row's id is made of: its word and reading. */
        private fun keyOf(e: Entry): String = e.data.word + "\u0001" + e.data.reading.orEmpty()

        /** The [keyOf] values that more than one of [entries] has. */
        private fun sharedKeys(entries: List<Entry>): Set<String> =
            entries.groupingBy(::keyOf).eachCount().filterValues { it > 1 }.keys
    }
}

/** A looked-up word of the app's word lists as its row shows it. */
internal fun RowState.toWordEntry() = WordRowList.Entry(
    WordDefinitionData(
        word = displayWord,
        reading = reading.ifEmpty { null },
        senses = senses,
        freqScore = freqScore,
        isCommon = isCommon,
        pitch = pitch,
        frequencies = frequencies,
        readingRows = readingRows,
        importedGroups = importedGroups,
        inflectedForms = inflectedForms,
    ),
)

/** A word list of the app's own looked-up rows in one of its cards, its
 *  text sized by [scale] ([WordRowList.LARGE_SCALE] and its like). */
internal fun wordRowList(
    ctx: Context,
    slot: ViewGroup,
    scope: CoroutineScope,
    host: WordRowList.Host<RowState>,
    scale: Float,
    /** Two columns on a wide page
     *  ([DefinitionsDocument.DocTheme.twoColumnsWhenWide]): the results'
     *  Words card only. */
    twoColumnsWhenWide: Boolean = false,
    /** The card's colour as seen ([cardDocTheme]'s panel); null for the
     *  theme's card colour. */
    cardColor: Int? = null,
    /** The page laid the rows out ([WordRowList]'s own `onLayout`). */
    onLayout: () -> Unit = {},
) = WordRowList(
    ctx, slot, scope, host,
    describe = RowState::toWordEntry,
    theme = {
        if (cardColor == null) cardDocTheme(ctx, WordRowList.GLOSS_SP * scale, twoColumnsWhenWide)
        else cardDocTheme(ctx, WordRowList.GLOSS_SP * scale, twoColumnsWhenWide, cardColor)
    },
    onLayout = onLayout,
)
