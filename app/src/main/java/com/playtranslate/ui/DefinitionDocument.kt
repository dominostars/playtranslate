package com.playtranslate.ui

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import com.playtranslate.R
import com.playtranslate.model.ImportedSenseGroup
import com.playtranslate.themeColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A row, or a tappable element of a row, of a laid-out document, as the
 * page last reported it: [act] is the [DefAction] it fires, empty for a row
 * that is no tap target itself. The rect is in CSS px from the document's
 * top-left corner.
 */
internal data class DocTarget(
    val rowId: String,
    val act: String,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

/**
 * A word row's head that pins at the top of what is visible while the row's
 * definitions scroll under it, as the page last reported it: where it is in
 * its row, in CSS px from the document's top.
 */
internal data class DocHead(
    val rowId: String,
    val top: Int,
    val height: Int,
)

/** One tappable document target as a controller action ([NavAction.target]). */
internal data class DocNavTarget(
    val document: DefinitionDocument,
    val rowId: String,
    val act: String,
) : NavTarget {
    override fun rectOnScreen(out: Rect): Boolean = document.rectOnScreen(rowId, act, out)
    override fun revealRectOnScreen(out: Rect): Boolean = document.revealRectOnScreen(rowId, act, out)
    override fun activate() = document.activate(rowId, act)
    override fun successor(): NavTarget? = DefAction.fallback(act)?.let { copy(act = it) }
}

/**
 * A surface's definitions as ONE page: the controller a host hands its rows
 * to ([setRows]) and hears taps from ([onAction]). It owns the view (added
 * to [slot]), the rows, what has been sent to the page, the controller
 * cursor, and what happens when the page cannot draw.
 *
 * The rules:
 *  - **The row list here is the truth.** The page is a rendering of it. A
 *    change to a row changes the list first and the page only if that row
 *    has been sent, so nothing is lost when rows are sent later or again.
 *  - **Rows are fed, not dumped.** The first [CHUNK] rows go at once; more
 *    follow while the document's end is within two screens of what is
 *    visible ([endIsNear]), checked after every layout and on every scroll.
 *    The host side decides: it holds the scroll, which the page only
 *    follows ([DefinitionDocumentView]). Each chunk's structured content is
 *    fetched and its markup built off the main thread, under the language
 *    the rows were looked up in.
 *  - **A word's head pins** at the top of what is visible while its row's
 *    definitions scroll under it (the page's CSS, lined up by the view).
 *    The page reports every place unpinned ([targets], [heads]); where a
 *    controller needs a head's buttons, this side shifts them to where the
 *    head is pinned ([rectOnScreen]), reveals a target under a pinned head
 *    below it ([revealRectOnScreen]), and a tap that changes a row whose
 *    head is pinned brings the row's top to the top of what is visible once
 *    the page has redrawn the row with that change ([PendingAnchor]).
 *  - **A rebind with the same rows patches.** [setRows] with the same ids
 *    in the same order replaces only the rows whose value changed (a deck
 *    badge, a hide, a spinner): no new row set, no jump. The exception is
 *    a row that gains a dictionary's structured glossary under its id:
 *    only feeding fetches that, so the rebind is a new row set.
 *  - **The view is 1 px only before its first layout.** A later row set
 *    keeps the height on screen until its own first layout, so a rebind
 *    never collapses the surface. Never GONE: the page reports nothing
 *    while its viewport has no width.
 *  - **Renderer death is recovered once per content.** A new shell takes
 *    the dead one's place and the rows are sent again. If that one dies
 *    too before the host sets new rows, the notice shows instead: the
 *    content itself may be what kills the renderer, and a loop of rebuilds
 *    would never end. There is no latch beyond that, because there is no
 *    other renderer to fall back to.
 *  - **No usable WebView shows the notice** where the definitions would be.
 *
 * [scope] must be confined to the main thread, like everything else here.
 * [theme] is read each time a shell is taken. A document holds its shell
 * from the first [prepare] or [setRows] until [release].
 */
internal class DefinitionDocument(
    private val ctx: Context,
    private val slot: ViewGroup,
    private val scope: CoroutineScope,
    private val theme: () -> DefinitionsDocument.DocTheme,
    private val onAction: (row: DefRow, action: String) -> Unit,
    /** What stands in the slot has changed: the page laid its rows out, or
     *  no layout will follow for these rows, because the notice took the
     *  page's place after its renderer died a second time ([showsNotice]) or
     *  their first chunk could not be built ([buildFailed]). A notice put up
     *  inside [setRows] (no usable WebView) is not announced: the caller
     *  reads [showsNotice] when the call returns. */
    private val onLayout: () -> Unit = {},
    /** Where a chunk's content is fetched and its markup built. */
    private val buildOn: CoroutineDispatcher = Dispatchers.Default,
    /** A tap on the text of a row that reports them
     *  ([DefRow.Imported.words], [DefRow.Sense.words]); null for a document
     *  with no such row. */
    private val onTextTap: ((row: DefRow, tap: TextTap) -> Unit)? = null,
) {
    /**
     * A tap on a row's text, as the page reports it: the run of [text] it
     * fell in (one line of meaning, without the readings set over its
     * words) and the tapped character's [offset] in it; the language the
     * markup says the text is in ([lang]) and the dictionary it is from
     * ([dictId]), each "" where the markup says nothing. [id] names the run
     * to [mark], for as long as the page keeps it: until the next tap, or
     * until its row is drawn again.
     */
    class TextTap(val id: Int, val text: String, val offset: Int, val lang: String, val dictId: String)

    /** Where the page drew the first line of a marked part of a run, in
     *  CSS px from the document's corner ([markRectOnScreen]). */
    class MarkBox(val left: Int, val top: Int, val width: Int, val height: Int)

    /**
     * The page's answer to [mark] for a tap: the box of the marked part's
     * first line, or null when that run's text is no longer in the page.
     * Null is also how a painted mark is reported gone, when its row was
     * drawn again or new rows were set.
     */
    var onMark: ((tapId: Int, box: MarkBox?) -> Unit)? = null

    private val app = ctx.applicationContext
    private val density = ctx.resources.displayMetrics.density
    private val rtl = ctx.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

    private var rows: List<DefRow> = emptyList()
    private var language: String = ""

    /** The page's row-set generation: every `ptSet` starts one. */
    private var gen = 0

    /** Bumps when the host sets rows that are not a patch of the last ones;
     *  recovery is allowed once per value. */
    private var content = 0
    private var recoveredContent = -1

    /** How many of [rows], from the top, the page holds. */
    private var fed = 0
    private var feedJob: Job? = null

    /** Row id to the id of its element in the page, and back. Ids are made
     *  here and never derived from the row's own, which can hold anything. */
    private val domIds = HashMap<String, String>()
    private val rowIds = HashMap<String, String>()
    private var nextDom = 0

    /** The structured glossaries of the fed rows, kept for patches. A new
     *  map each time, so a build off the main thread reads a stable one. */
    private var structured: Map<Long, String> = emptyMap()
    private val appliedCss = HashSet<String>()

    /** Per row id: the latest patch asked for, so an older build that lands
     *  later is dropped. */
    private val patchSeq = HashMap<String, Int>()

    private var view: DefinitionDocumentView? = null
    private var shell: DefinitionShell? = null
    private var notice: TextView? = null
    private var holding = false
    private var height = 1

    /** Every row and tappable element of the last layout, in page order. */
    var targets: List<DocTarget> = emptyList()
        private set

    /** Every word head of the last layout that pins, by row id. */
    var heads: Map<String, DocHead> = emptyMap()
        private set

    /**
     * A tap of [action] on row [rowId] whose head was pinned, the top of
     * what was visible then being [visibleTop] px down the document: with
     * the head pinned, the row's top is above the visible part, and so is
     * where its answer opens, so the row comes to the top once the page has
     * redrawn it with the change the tap asked for. [armed] once that change
     * reaches the row ([setRows]): a tap that changed nothing (no model to
     * ask, a hide that failed to save) never moves the list, nor does a
     * later redraw of the row for anything else. Gone when it brings the
     * row up, when the user scrolls, taps again, or the rows are set anew.
     */
    private class PendingAnchor(val rowId: String, val action: String, val visibleTop: Int) {
        var armed = false
    }

    private var anchor: PendingAnchor? = null

    private var cursor: DocTarget? = null

    @get:VisibleForTesting
    val shownRows: List<DefRow> get() = rows

    /** True when the notice stands where the definitions would be. */
    @get:VisibleForTesting
    val showsNotice: Boolean get() = notice?.parent != null

    /** True when the first chunk of the rows could not be built: the page
     *  draws none of this row set and reports no layout for it. Until the
     *  next row set, or a later send of the chunk that goes through. */
    var buildFailed = false
        private set

    // ── Rows ─────────────────────────────────────────────────────────────

    /** The user has asked for definitions this document will draw: start
     *  loading a shell now, while the lookup runs. Idempotent. */
    fun prepare() {
        if (holding) return
        holding = true
        DefinitionShells.retain(ctx)
    }

    /**
     * The rows to show, looked up in [language] (the dictionary store's
     * language key, which also routes the page's media). Same ids in the
     * same order as the last call, no row gaining a structured glossary
     * ([gainsStructured]): a patch of the rows that changed. Anything else:
     * a new row set, fed from the top.
     */
    fun setRows(newRows: List<DefRow>, language: String) {
        if (newRows.isEmpty() && shell == null) {
            // Nothing to draw and nothing drawn: no shell is taken for it.
            rows = newRows
            this.language = language
            return
        }
        prepare()
        val sameIds = shell != null && language == this.language && newRows.size == rows.size &&
            newRows.indices.all { newRows[it].id == rows[it].id }
        // Over every row, sent or not: a chunk in flight was fetched for
        // the rows as they were.
        val patch = sameIds && newRows.indices.none { gainsStructured(rows[it], newRows[it]) }
        if (patch) {
            val old = rows
            rows = newRows
            anchor?.let { a ->
                val i = newRows.indexOfFirst { it.id == a.rowId }
                if (i >= 0 && changedBy(a.action, old[i], newRows[i])) a.armed = true
            }
            syncFocusable()
            // A row not yet sent needs nothing: it is built from the list
            // when its chunk goes, and a chunk in flight checks on landing.
            for (i in 0 until min(fed, newRows.size)) if (newRows[i] != old[i]) patch(i)
            return
        }
        rows = newRows
        this.language = language
        content++
        restart()
    }

    /** The imported dictionary content [row] draws, whose structured form
     *  is fetched when the row is fed ([build]). */
    private fun importedGroups(row: DefRow): List<ImportedSenseGroup> = when (row) {
        is DefRow.Word -> row.data.importedGroups
        is DefRow.Imported -> row.groups
        is DefRow.SheetWord -> row.importedGroups
        is DefRow.More, is DefRow.Sense -> emptyList()
    }

    /**
     * Whether [new] draws a structured glossary that [old], the row it
     * replaces under the same id, did not. Such a row cannot be patched: a
     * patch draws with what feeding fetched, so the new glossary would stay
     * flat text until the next row set. Compared row against row and never
     * against what has been fetched, which stays empty when styling is off
     * or a fetch failed and would make every patch a new row set.
     */
    private fun gainsStructured(old: DefRow, new: DefRow): Boolean {
        val now = importedGroups(new)
        val before = importedGroups(old)
        if (now == before) return false
        val had = before.flatMapTo(HashSet()) { g -> g.senses.mapNotNull { it.scRowid } }
        return now.any { g -> g.senses.any { s -> s.scRowid?.let { it !in had } == true } }
    }

    /** The view is a focus stop only while a row offers something to act
     *  on: a block of plain definitions (a word page's senses) is passed
     *  over, as the native rows it replaced were. What a row's own content
     *  offers is known only once the page has laid it out: a block of
     *  imported definitions that follows its dictionaries' links
     *  ([DefRow.Imported.links]) is a stop when one of them holds a link,
     *  which the page reports as a target like any other. */
    private fun syncFocusable() {
        view?.isFocusable = rows.any {
            when (it) {
                // A dictionary's title is a target too. (A hidden word's
                // stub draws none, and is a stop for its eye.)
                is DefRow.Word -> it.opens || it.speak != null || it.trailing != null ||
                    it.data.importedGroups.isNotEmpty()
                is DefRow.More -> true
                is DefRow.Imported -> it.curation?.removable == true ||
                    (it.links && targets.any { t -> t.rowId == it.id && t.act.isNotEmpty() })
                is DefRow.Sense -> it.removable || it.examples.any { e -> e.removeIndex != null }
                is DefRow.SheetWord -> true
            }
        }
    }

    /** Drops the shell and the view; the rows are forgotten too. A later
     *  [setRows] starts over. For a surface that closes, or that hides its
     *  definitions for long (a live view pins the render process's memory). */
    fun release() {
        feedJob?.cancel()
        feedJob = null
        gen++
        rows = emptyList()
        fed = 0
        buildFailed = false
        targets = emptyList()
        heads = emptyMap()
        anchor = null
        cursor = null
        shell?.destroy()
        shell = null
        view?.let {
            it.removeOnAttachStateChangeListener(attach)
            if (it.isAttachedToWindow) it.viewTreeObserver.removeOnScrollChangedListener(onScroll)
            slot.removeView(it)
        }
        view = null
        // No view, no height: the next one starts at 1 px.
        height = 1
        notice?.let { slot.removeView(it) }
        notice = null
        if (holding) {
            holding = false
            DefinitionShells.release()
        }
    }

    private fun restart() {
        feedJob?.cancel()
        feedJob = null
        gen++
        fed = 0
        buildFailed = false
        domIds.clear()
        rowIds.clear()
        patchSeq.clear()
        structured = emptyMap()
        targets = emptyList()
        heads = emptyMap()
        anchor = null
        if (!ensureShell()) return
        syncFocusable()
        if (rows.isEmpty()) {
            // Nothing to show: an empty page at 1 px, the shell kept for
            // the rows that follow a lookup.
            shell?.exec("ptSet('',$gen)")
            applyHeight(1)
            return
        }
        feed()
    }

    private fun feed() {
        if (feedJob?.isActive == true || fed >= rows.size) return
        val g = gen
        val from = fed
        val chunk = rows.subList(from, min(from + CHUNK, rows.size)).toList()
        val doms = chunk.map { row ->
            domIds.getOrPut(row.id) { "k${nextDom++}".also { rowIds[it] = row.id } }
        }
        val lang = language
        val known = structured
        feedJob = scope.launch {
            val built = try {
                withContext(buildOn) { build(chunk, doms, lang, known) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Class only: no message (it may carry a path), no content.
                Log.w(TAG, "chunk build failed: ${e.javaClass.simpleName}")
                // Nothing sends a first chunk again, so nothing of these
                // rows will be drawn: a host holding a loading state up for
                // their layout is told that none is coming.
                if (g == gen && from == 0) {
                    buildFailed = true
                    onLayout()
                }
                return@launch
            }
            if (g != gen) return@launch
            deliver(from, chunk, doms, built, g)
        }
    }

    private class Built(
        val htmls: List<String>,
        val structured: Map<Long, String>,
        val css: Map<String, String>,
    )

    private suspend fun build(
        chunk: List<DefRow>,
        doms: List<String>,
        lang: String,
        known: Map<Long, String>,
    ): Built {
        val groups = chunk.flatMap(::importedGroups)
        // Null when there is nothing structured to draw, and when the store
        // fails: these rows then draw their dictionaries' flat text, which
        // every imported sense carries. A store failure costs the styling,
        // never the rows.
        val styled = fetchYomitanStyledData(app, lang, groups)
        val all = if (styled == null) known else known + styled.structured
        val htmls = chunk.indices.map { DefRowHtml.html(ctx, chunk[it], doms[it], all, lang) }
        return Built(htmls, all, styled?.dictStyles.orEmpty())
    }

    private fun deliver(from: Int, chunk: List<DefRow>, doms: List<String>, built: Built, g: Int) {
        val s = shell ?: return
        structured = built.structured
        for ((dictId, css) in built.css) {
            if (appliedCss.add(dictId)) s.exec("ptApplyDictCss(${quote(dictId)},${quote(css)})")
        }
        val sb = StringBuilder()
        for (i in chunk.indices) {
            // The list moved on while this chunk was being built: the page
            // gets the row as it is now.
            val now = rows.getOrNull(from + i)
            sb.append(
                if (now != null && now != chunk[i] && now.id == chunk[i].id) {
                    DefRowHtml.html(ctx, now, doms[i], built.structured, language)
                } else {
                    built.htmls[i]
                },
            )
        }
        s.exec(if (from == 0) "ptSet(${quote(sb.toString())},$g)" else "ptAppend(${quote(sb.toString())},$g)")
        fed = from + chunk.size
        buildFailed = false
    }

    private fun patch(index: Int) {
        val row = rows[index]
        val dom = domIds[row.id] ?: return
        val seq = (patchSeq[row.id] ?: 0) + 1
        patchSeq[row.id] = seq
        val g = gen
        val lang = language
        val known = structured
        scope.launch {
            val html = withContext(buildOn) { DefRowHtml.html(ctx, row, dom, known, lang) }
            if (g != gen || patchSeq[row.id] != seq) return@launch
            shell?.exec("ptReplace(${quote(dom)},${quote(html)},$g)")
        }
    }

    // ── The view and its shell ───────────────────────────────────────────

    /** A live shell showing in the view, themed; false with the notice up
     *  when the device has no usable WebView. */
    private fun ensureShell(): Boolean {
        if (shell != null) return true
        val s = DefinitionShells.obtain(ctx) ?: run {
            showNotice()
            return false
        }
        hideNotice()
        val v = view ?: DefinitionDocumentView(ctx).also {
            view = it
            it.keys = keys
            it.addOnAttachStateChangeListener(attach)
            slot.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            if (it.isAttachedToWindow) it.viewTreeObserver.addOnScrollChangedListener(onScroll)
        }
        shell = s
        s.listener = listener
        s.mediaLanguage = language
        appliedCss.clear()
        v.show(s)
        s.exec(DefinitionsDocument.themeJs(theme(), rtl))
        return true
    }

    private fun applyHeight(px: Int) {
        height = max(1, px)
        val v = view ?: return
        val lp = v.layoutParams ?: return
        if (lp.height != height) {
            lp.height = height
            v.layoutParams = lp
        }
    }

    private fun showNotice() {
        view?.let { slot.removeView(it) }
        view = null
        height = 1
        if (notice?.parent != null) return
        val t = theme().tokens
        val pad = (16 * density).roundToInt()
        notice = TextView(ctx).apply {
            text = ctx.getString(R.string.definitions_need_webview)
            setTextColor(t.textMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(pad, (12 * density).roundToInt(), pad, (12 * density).roundToInt())
        }.also {
            slot.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun hideNotice() {
        notice?.let { slot.removeView(it) }
        notice = null
    }

    private val listener = object : DefinitionShell.Listener {
        override fun onLayout(gen: Int, heightCssPx: Int, targetsJson: String, headsJson: String, redrawnJson: String) {
            if (gen != this@DefinitionDocument.gen) return
            applyHeight((heightCssPx * density).roundToInt())
            laidOut(parseTargets(targetsJson), parseHeads(headsJson), parseRedrawn(redrawnJson))
            this@DefinitionDocument.onLayout()
            feedIfNear()
        }

        override fun onAction(domId: String, act: String) {
            val rowId = rowIds[domId] ?: return
            val row = rows.firstOrNull { it.id == rowId } ?: return
            perform(row, act)
        }

        override fun onText(gen: Int, domId: String, tapId: Int, lang: String, dictId: String, text: String, offset: Int) {
            if (gen != this@DefinitionDocument.gen) return
            val rowId = rowIds[domId] ?: return
            val row = rows.firstOrNull { it.id == rowId } ?: return
            if (offset !in text.indices) return
            onTextTap?.invoke(row, TextTap(tapId, text, offset, lang, dictId))
        }

        override fun onMark(tapId: Int, left: Int, top: Int, width: Int, height: Int) {
            onMark?.invoke(tapId, if (width <= 0) null else MarkBox(left, top, width, height))
        }

        override fun onRendererGone() {
            shell = null
            if (recoveredContent == content) {
                Log.w(TAG, "renderer died twice on one content; showing the notice")
                showNotice()
                // The host may be holding something up until these rows are
                // drawn, and no layout will come to say so.
                this@DefinitionDocument.onLayout()
                return
            }
            recoveredContent = content
            restart()
        }
    }

    private fun parseTargets(json: String): List<DocTarget> = try {
        val a = JSONArray(json)
        val out = ArrayList<DocTarget>(a.length() / 6)
        var i = 0
        while (i + 5 < a.length()) {
            rowIds[a.getString(i)]?.let { rowId ->
                out += DocTarget(rowId, a.getString(i + 1), a.getInt(i + 2), a.getInt(i + 3), a.getInt(i + 4), a.getInt(i + 5))
            }
            i += 6
        }
        out
    } catch (e: Exception) {
        Log.w(TAG, "targets unreadable: ${e.javaClass.simpleName}")
        emptyList()
    }

    private fun parseHeads(json: String): Map<String, DocHead> = try {
        val a = JSONArray(json)
        val out = HashMap<String, DocHead>(a.length() / 3)
        var i = 0
        while (i + 2 < a.length()) {
            rowIds[a.getString(i)]?.let { rowId -> out[rowId] = DocHead(rowId, a.getInt(i + 1), a.getInt(i + 2)) }
            i += 3
        }
        out
    } catch (e: Exception) {
        Log.w(TAG, "heads unreadable: ${e.javaClass.simpleName}")
        emptyMap()
    }

    private fun parseRedrawn(json: String): Set<String> = try {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNullTo(HashSet()) { rowIds[a.getString(it)] }
    } catch (e: Exception) {
        Log.w(TAG, "redrawn rows unreadable: ${e.javaClass.simpleName}")
        emptySet()
    }

    // ── Feeding on scroll ────────────────────────────────────────────────

    private val onScroll = ViewTreeObserver.OnScrollChangedListener { feedIfNear() }

    private val attach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            v.viewTreeObserver.addOnScrollChangedListener(onScroll)
        }

        override fun onViewDetachedFromWindow(v: View) {
            v.viewTreeObserver.removeOnScrollChangedListener(onScroll)
        }
    }

    private fun feedIfNear() {
        if (fed < rows.size && fed > 0 && endIsNear()) feed()
    }

    /** Whether what the page holds ends within two screens of the visible
     *  part of the view (a view not on screen at all counts from its top).
     *  The view's own coordinates are the document's: the scroll that lines
     *  the page up is inside it ([DefinitionDocumentView]). */
    private fun endIsNear(): Boolean {
        val v = view ?: return false
        val visible = Rect()
        val visibleBottom = if (v.isShown && v.getLocalVisibleRect(visible)) visible.bottom else 0
        return height - visibleBottom < 2 * ctx.resources.displayMetrics.heightPixels
    }

    // ── Targets for a controller ─────────────────────────────────────────

    /** The tappable targets as actions for a virtual cursor. */
    fun navActions(): List<NavAction> {
        val v = view ?: return emptyList()
        return targets.filter { it.act.isNotEmpty() }
            .map { NavAction(v, target = DocNavTarget(this, it.rowId, it.act)) }
    }

    /** The top of row [rowId] in the view's own px, null when the page does
     *  not hold it. For a host that anchors its scroll on a row. */
    fun rowTopPx(rowId: String): Int? =
        targets.firstOrNull { it.rowId == rowId }?.let { (it.top * density).roundToInt() }

    /** Asks the page where characters [start] up to [end] of tap [tapId]'s
     *  run are; it answers through [onMark]. With [draw] it also paints
     *  them as the tapped word, until [unmark], the next tap, or their row
     *  being drawn again. Without, a mark painted before is cleared. */
    fun mark(tapId: Int, start: Int, end: Int, draw: Boolean) {
        shell?.exec("ptMark($tapId,$start,$end,$draw)")
    }

    /** Clears the painted mark, if there is one. */
    fun unmark() {
        shell?.exec("ptUnmark()")
    }

    /** [box] on screen now, as [rectOnScreen] places a target; false when
     *  the view is not shown or none of the box is in what shows of it (the
     *  page was scrolled since the tap). */
    fun markRectOnScreen(box: MarkBox, out: Rect): Boolean {
        val v = view ?: return false
        if (!v.isShown) return false
        out.set(
            (box.left * density).roundToInt(),
            (box.top * density).roundToInt(),
            ((box.left + box.width) * density).roundToInt(),
            ((box.top + box.height) * density).roundToInt(),
        )
        val visible = Rect()
        if (!v.getLocalVisibleRect(visible) || !Rect.intersects(visible, out)) return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        out.offset(loc[0], loc[1])
        return true
    }

    /** What a cursor on [t] needs to show of the view, top to bottom in its
     *  own px ([revealRect]): for a host that scrolls the view itself to a
     *  target inside a row (the lens). */
    internal fun revealSpanPx(t: DocTarget): IntRange {
        val r = Rect()
        revealRect(t, r)
        return r.top..r.bottom
    }

    /** The id of the row at [y] (the view's own px), or of the first row
     *  below it; null for none. Rows that stand side by side share a top,
     *  and the first of them stands for their line: a host that anchors its
     *  scroll on this row is still on the line's start when the page is one
     *  column again (a rotation). */
    fun rowAt(y: Int): String? {
        val rowTops = targets.distinctBy { it.rowId }
        val at = rowTops.lastOrNull { (it.top * density).roundToInt() <= y } ?: rowTops.firstOrNull() ?: return null
        return rowTops.first { it.top == at.top }.rowId
    }

    /** The document's view, for a host that measures against it. */
    val hostView: View? get() = view

    /** The view's height as the page last reported it, in px; 1 before
     *  the first layout, for an empty document and while there is no view
     *  (released, or the notice standing in). The view keeps its height
     *  across a new row set and across a new shell, until the next layout:
     *  more than 1 says a layout was once reported to this view, not that
     *  rows are on screen now. After a new row set the last one's still
     *  are; after a renderer death the view is blank at that height until
     *  the new shell has drawn them. */
    val heightPx: Int get() = height

    /** Where the target [act] of row [rowId] is drawn on screen now
     *  ([drawnRect]); false when the page does not hold it or the view is
     *  not shown. */
    internal fun rectOnScreen(rowId: String, act: String, out: Rect): Boolean =
        onScreen(rowId, act, out, ::drawnRect)

    /** What of the screen a cursor moving onto the target [act] of row
     *  [rowId] needs to see ([revealRect]). */
    internal fun revealRectOnScreen(rowId: String, act: String, out: Rect): Boolean =
        onScreen(rowId, act, out, ::revealRect)

    private inline fun onScreen(rowId: String, act: String, out: Rect, rect: (DocTarget, Rect) -> Unit): Boolean {
        val v = view ?: return false
        val t = targets.firstOrNull { it.rowId == rowId && it.act == act } ?: return false
        if (!v.isShown) return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        rect(t, out)
        out.offset(loc[0], loc[1])
        return true
    }

    /** [t] where the page reported it, in the view's px. */
    private fun localRect(t: DocTarget, out: Rect) {
        out.set(
            (t.left * density).roundToInt(),
            (t.top * density).roundToInt(),
            ((t.left + t.width) * density).roundToInt(),
            ((t.top + t.height) * density).roundToInt(),
        )
    }

    /** [t] where the page draws it now, in the view's px: its reported place
     *  ([localRect]), moved down with its head when [t] is one of a pinned
     *  head's buttons ([pinShiftPx]). */
    private fun drawnRect(t: DocTarget, out: Rect) {
        localRect(t, out)
        out.offset(0, pinShiftPx(t))
    }

    /** What a cursor on [t] needs of the screen: where it is drawn
     *  ([drawnRect]), reaching up by its row's head when [t] is below the
     *  head, which pins over whatever is under it. A row's own target and a
     *  head's buttons need only themselves. */
    private fun revealRect(t: DocTarget, out: Rect) {
        drawnRect(t, out)
        val head = heads[t.rowId] ?: return
        if (t.top >= head.top + head.height) out.top -= (head.height * density).roundToInt()
    }

    /**
     * How far below its reported place the page draws [t], in px: as far as
     * its head is pinned when [t] is inside the head (one of its buttons),
     * else 0. The page's rule, CSS sticky in the row: the head sits at the
     * top of what is visible, never above its own place, and never past the
     * row's end. What is visible is read off the view as it is now, as the
     * line-up reads it ([DefinitionDocumentView]), so a ring drawn from this
     * ahead of the draw follows a scroll the line-up is about to take.
     */
    private fun pinShiftPx(t: DocTarget): Int {
        val head = heads[t.rowId] ?: return 0
        if (t.top < head.top || t.top + t.height > head.top + head.height) return 0
        val visibleTop = visibleTopCss() ?: return 0
        val row = targets.firstOrNull { it.rowId == t.rowId } ?: return 0
        val furthest = (row.top + row.height - head.height - head.top).coerceAtLeast(0).toFloat()
        return ((visibleTop - head.top).coerceIn(0f, furthest) * density).roundToInt()
    }

    /** The top of what is visible of the view, in its px from the
     *  document's top; null when none of it is. */
    private fun visibleTopPx(): Int? {
        val v = view ?: return null
        val visible = Rect()
        return if (v.isShown && v.getLocalVisibleRect(visible)) visible.top else null
    }

    /** [visibleTopPx] in CSS px. */
    private fun visibleTopCss(): Float? = visibleTopPx()?.let { it / density }

    internal fun activate(rowId: String, act: String) {
        val row = rows.firstOrNull { it.id == rowId } ?: return
        perform(row, act)
    }

    /** A tap or a controller's press of [action] on [row], for the host. A
     *  press that changes the row while its head is pinned leaves it to be
     *  brought into view once the page has redrawn it ([PendingAnchor]);
     *  any press ends the one before. */
    private fun perform(row: DefRow, action: String) {
        val top = visibleTopPx()
        anchor = if (top != null && changesRow(row, action) && headPinned(row.id)) {
            PendingAnchor(row.id, action, top)
        } else {
            null
        }
        onAction(row, action)
    }

    /** Whether [new], the row a patch puts in place of [old], carries the
     *  change a press of [action] asks for ([changesRow]): another explain
     *  state, or another trailing (a word hidden or shown). */
    private fun changedBy(action: String, old: DefRow, new: DefRow): Boolean {
        val o = old as? DefRow.Word ?: return false
        val n = new as? DefRow.Word ?: return false
        return if (action == DefAction.TRAILING) o.trailing != n.trailing else o.explain != n.explain
    }

    /** Whether [action] on [row] changes what the row draws above its
     *  definitions: an explain question, its answer's X and Try again, a
     *  word hidden or shown. Not speak, not Anki, not opening the word. */
    private fun changesRow(row: DefRow, action: String): Boolean = when (action) {
        DefAction.EXPLAIN, DefAction.EXPLAIN_CLOSE, DefAction.EXPLAIN_RETRY -> true
        DefAction.TRAILING -> (row as? DefRow.Word)?.trailing.let {
            it == DefRow.Trailing.HIDE || it == DefRow.Trailing.SHOW
        }
        else -> false
    }

    /** Whether row [rowId]'s head is pinned now: what is visible begins
     *  below the head's own place. */
    private fun headPinned(rowId: String): Boolean {
        val head = heads[rowId] ?: return false
        val visibleTop = visibleTopCss() ?: return false
        return visibleTop > head.top
    }

    /** Brings row [rowId] to the top of what is visible, at once: its head
     *  where it was pinned, now in its own place, and what the row has
     *  under it after it. A row with no head that pins (a stub) by its
     *  top. */
    private fun bringToTop(rowId: String) {
        val v = view ?: return
        val r = Rect()
        val head = heads[rowId]
        if (head != null) {
            r.set(0, (head.top * density).roundToInt(), v.width, ((head.top + head.height) * density).roundToInt())
        } else {
            val row = targets.firstOrNull { it.rowId == rowId } ?: return
            localRect(row, r)
        }
        v.requestRectangleOnScreen(r, true)
    }

    /**
     * Plays the page for a JVM test, where no engine lays anything out: a
     * layout report in which every row the page has been sent is
     * [rowHeightCssPx] tall, stacked from the top, a tap target for its own
     * action when it has one, a word row's buttons after it as the page
     * reports them. Goes through what a real report does: the height, the
     * targets and the heads that pin (each word row's, unless a stub, at its
     * top and as tall as its buttons), the rows the page redrew since its
     * last report ([redrawn], by row id), the host's callback, the next
     * chunk.
     */
    @VisibleForTesting
    fun layoutForTest(rowHeightCssPx: Int, redrawn: Set<String> = emptySet()) {
        val out = ArrayList<DocTarget>()
        val heads = HashMap<String, DocHead>()
        var top = 0
        for (row in rows) {
            if (row.id !in domIds) break
            val act = when (row) {
                is DefRow.Word -> if (row.opens) DefAction.OPEN else ""
                is DefRow.More -> DefAction.MORE
                is DefRow.Imported, is DefRow.Sense -> ""
                is DefRow.SheetWord -> if (row.hidden) "" else DefAction.TARGET
            }
            out += DocTarget(row.id, act, 0, top, 300, rowHeightCssPx)
            if (row is DefRow.Word) {
                val stub = row.trailing == DefRow.Trailing.SHOW
                // A head that pins, as tall as its buttons, at the row's top.
                if (!stub) heads[row.id] = DocHead(row.id, top, min(40, rowHeightCssPx))
                // In page order: the head's buttons (explain first), then
                // what an explain answer adds under the head.
                if (row.explain != null && !stub) out += DocTarget(row.id, DefAction.EXPLAIN, 128, top, 36, 40)
                if (row.speak != null && !stub) out += DocTarget(row.id, DefAction.SPEAK, 180, top, 36, 40)
                if (row.trailing != null) out += DocTarget(row.id, DefAction.TRAILING, 232, top, 36, 40)
                row.explain?.answer?.takeIf { !stub }?.let { answer ->
                    out += DocTarget(row.id, DefAction.EXPLAIN_CLOSE, 252, top + 40, 36, 32)
                    if (answer.retry) out += DocTarget(row.id, DefAction.EXPLAIN_RETRY, 16, top + 72, 80, 32)
                }
            }
            top += rowHeightCssPx
        }
        applyHeight((top * density).roundToInt())
        laidOut(out, heads, redrawn)
        onLayout()
        feedIfNear()
    }

    /** The page laid its rows out as [laid], with the heads that pin
     *  [laidHeads], having redrawn the rows [redrawn] since its last report:
     *  they are the targets now, and the cursor stands on its own target
     *  among them or on what took its place ([DocNav.settle]). A move is
     *  told to the page, which draws the highlight from its own copy of the
     *  cursor; a cursor that merely lost its target is dropped here, as it
     *  always was. The view lines the page up again (the page's room to
     *  scroll may have changed). A row a tap changed while its head was
     *  pinned comes to the top once this is its redraw with that change, or
     *  stays where it is if the user has scrolled since ([PendingAnchor]). */
    private fun laidOut(laid: List<DocTarget>, laidHeads: Map<String, DocHead>, redrawn: Set<String>) {
        targets = laid
        heads = laidHeads
        // What a row's content offers a cursor is known only now.
        syncFocusable()
        val was = cursor
        val now = was?.let { DocNav.settle(laid, it.rowId, it.act) }
        if (was != null && now != null && now.act != was.act) setCursor(now) else cursor = now
        view?.pageLaidOut()
        anchor?.let { a ->
            when {
                visibleTopPx() != a.visibleTop -> anchor = null
                a.armed && a.rowId in redrawn -> {
                    anchor = null
                    bringToTop(a.rowId)
                }
            }
        }
    }

    /** Draws the controller's highlight on a target, or clears it (null). */
    fun setCursor(target: DocTarget?) {
        cursor = target
        val dom = target?.let { domIds[it.rowId] }
        shell?.exec(if (dom == null) "ptCursor(null,'')" else "ptCursor(${quote(dom)},${quote(target.act)})")
    }

    // ── Focus and D-pad on an Activity page ──────────────────────────────

    private val keys = object : DefinitionDocumentView.Keys {
        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            val dir = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> DocNav.Dir.UP
                KeyEvent.KEYCODE_DPAD_DOWN -> DocNav.Dir.DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> DocNav.Dir.LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> DocNav.Dir.RIGHT
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A,
                -> {
                    val c = cursor ?: return false
                    if (event.repeatCount == 0) activate(c.rowId, c.act)
                    return true
                }
                else -> return false
            }
            val next = DocNav.next(targets, cursor, dir, rtl)
            if (next == null) {
                // The last row the page holds is not the last row there is:
                // send the next ones and keep focus here for them.
                if (dir == DocNav.Dir.DOWN && fed < rows.size) {
                    feed()
                    return true
                }
                return false
            }
            moveCursorTo(next)
            return true
        }

        override fun onFocus(gained: Boolean, direction: Int) {
            if (!gained) {
                setCursor(null)
                return
            }
            val from = if (direction == View.FOCUS_UP || direction == View.FOCUS_BACKWARD) DocNav.Dir.UP else DocNav.Dir.DOWN
            DocNav.next(targets, null, from, rtl)?.let { moveCursorTo(it) }
        }

        override fun cursorRect(out: Rect): Boolean {
            val c = cursor ?: return false
            drawnRect(c, out)
            return true
        }

        override fun navActions(): List<NavAction> = this@DefinitionDocument.navActions()
    }

    private fun moveCursorTo(target: DocTarget) {
        setCursor(target)
        val v = view ?: return
        val r = Rect()
        revealRect(target, r)
        v.requestRectangleOnScreen(r, false)
    }

    /** JSON string literal, additionally escaping the two codepoints legal
     *  in JSON but not in script source (U+2028/U+2029: they occur in
     *  CJK-adjacent dictionary text). */
    private fun quote(s: String): String =
        JSONObject.quote(s).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    private companion object {
        const val TAG = "DefinitionDocument"

        /** Rows per send: about two screens of list rows, and the size the
         *  measurements put at one short wait on a slow phone. */
        const val CHUNK = 8
    }
}

/**
 * Moving a cursor over a document's targets with a D-pad, by where the page
 * laid them out. Pure.
 *
 * The page reports targets in document order, a row before its buttons,
 * which is also their order along the line: the row, then each button toward
 * the line's end. A row is where its first reported target is: its own
 * element, reported whether or not it takes a tap itself. A line is a run of
 * consecutive rows that share a top and stand side by side: in one column
 * every row is a line of its own, in two (the results' Words card on a wide
 * page) a line is the rows across. Up and down move to the previous or next
 * line that holds a tap target, onto its row nearest the cursor's row across
 * (the cursor keeps its column; a last line holding one row takes it from
 * either), and in that row onto the kind of target the cursor is on when the
 * row has one (a column of eyes walks as a column), else the row's first.
 * Left and right step along the line's targets, a row's own and then its
 * buttons, row after row, toward the line's end being right on a
 * left-to-right page and left on a right-to-left one. Null means there is
 * nowhere further that way.
 */
internal object DocNav {
    enum class Dir { UP, DOWN, LEFT, RIGHT }

    /** A row: where it is (its first reported target) and its targets that
     *  take a tap, in page order. */
    private class Row(val box: DocTarget) {
        val taps = ArrayList<DocTarget>()
    }

    fun next(targets: List<DocTarget>, cur: DocTarget?, dir: Dir, rtl: Boolean): DocTarget? {
        val rows = LinkedHashMap<String, Row>()
        for (t in targets) {
            val row = rows.getOrPut(t.rowId) { Row(t) }
            if (t.act.isNotEmpty()) row.taps += t
        }
        val lines = ArrayList<MutableList<Row>>()
        for (row in rows.values) {
            val line = lines.lastOrNull()
            if (line != null && onLine(line.last().box, row.box)) line += row else lines += mutableListOf(row)
        }
        // A target with no act is never a cursor position: a row without a
        // tap target is passed over, and so is a line of such rows.
        val reachable = lines.map { line -> line.filter { it.taps.isNotEmpty() } }.filter { it.isNotEmpty() }
        if (reachable.isEmpty()) return null
        val at = cur?.let { c -> rows[c.rowId]?.taps?.firstOrNull { it.act == c.act } }
        if (at == null) {
            // A fresh cursor: the first row coming down, the last going up.
            val row = if (dir == Dir.UP) reachable.last().last() else reachable.first().first()
            return row.taps.first()
        }
        val i = reachable.indexOfFirst { line -> line.any { it.box.rowId == at.rowId } }
        return when (dir) {
            Dir.UP, Dir.DOWN -> {
                val line = reachable.getOrNull(i + if (dir == Dir.DOWN) 1 else -1) ?: return null
                val left = rows.getValue(at.rowId).box.left
                val row = line.minBy { abs(it.box.left - left) }
                row.taps.firstOrNull { it.act == at.act } ?: row.taps.first()
            }
            Dir.LEFT, Dir.RIGHT -> {
                val line = reachable[i].flatMap { it.taps }
                val towardEnd = (dir == Dir.RIGHT) != rtl
                line.getOrNull(line.indexOf(at) + if (towardEnd) 1 else -1)
            }
        }
    }

    /**
     * Where a cursor on [act] of row [rowId] stands in [targets], a layout
     * that may have come after a press of that very target: on it, while
     * the page still lays it out; else on the action that takes its place
     * in the same row, for the few that name one ([DefAction.fallback]: an
     * explain answer's ✕ and its Try again go with the press, and leave the
     * row's explain button); else nowhere (null), as a cursor whose target
     * is gone has always been. The one rule for every cursor that walks a
     * document's targets.
     */
    fun settle(targets: List<DocTarget>, rowId: String, act: String): DocTarget? =
        targets.firstOrNull { it.rowId == rowId && it.act == act }
            ?: DefAction.fallback(act)?.let { next -> targets.firstOrNull { it.rowId == rowId && it.act == next } }

    /** Whether the row at [b], the one after the row at [a], is on [a]'s
     *  line: the same top, and beside it, not over it. Rows reported at one
     *  spot are therefore a column, in document order. */
    private fun onLine(a: DocTarget, b: DocTarget): Boolean =
        b.top == a.top && (b.left >= a.left + a.width || b.left + b.width <= a.left)
}

/** The theme of a document that sits in one of the app's cards, its text
 *  sized from [baseFontSizePx], its rows in two columns on a wide page when
 *  [twoColumnsWhenWide] (the results' Words card only), on a card of
 *  [panel]: the theme's card colour, or the card as seen where it lets
 *  something show through ([DefinitionsDocument.Tokens.panel]). */
internal fun cardDocTheme(
    ctx: Context,
    baseFontSizePx: Float,
    twoColumnsWhenWide: Boolean = false,
    panel: Int = ctx.themeColor(R.attr.ptCard),
) = DefinitionsDocument.DocTheme(
    DefinitionsDocument.Tokens(
        text = ctx.themeColor(R.attr.ptText),
        textMuted = ctx.themeColor(R.attr.ptTextMuted),
        textHint = ctx.themeColor(R.attr.ptTextHint),
        accent = ctx.themeColor(R.attr.ptAccent),
        panel = panel,
        baseFontSizePx = baseFontSizePx,
    ),
    divider = ctx.themeColor(R.attr.ptDivider),
    band = hiddenWordBandColor(ctx),
    outline = ctx.themeColor(R.attr.ptOutline),
    wash = ctx.themeColor(R.attr.ptAccentTint),
    twoColumnsWhenWide = twoColumnsWhenWide,
)
