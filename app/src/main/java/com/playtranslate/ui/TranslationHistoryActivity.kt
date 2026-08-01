package com.playtranslate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.AnkiManager
import com.playtranslate.CaptureService
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.imageimport.ImageImportActivity
import com.playtranslate.themeColor
import com.playtranslate.translationlog.HistoryImageStore
import com.playtranslate.translationlog.TranslationHistoryStore
import com.playtranslate.translationlog.TranslationHistoryStore.HistoryEntry
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tools → History: the reading surface for the translation log. Hosts its
 * OWN master switch (enable/consume/clear all live at one address — the
 * empty state doubles as onboarding when the feature is off), a
 * display-only "hide translations" toggle for reading the source before the
 * answer (the row tap still reveals it), and per-date
 * sections in the settings-page rhythm: a group header OUTSIDE each day's
 * cards. Within a day, rows group into session cards ([Block]): capture
 * sessions (camera/screen badge, optional saved-image thumbnail that
 * re-opens in the import review), collapsible live-session transcripts,
 * and plain cards for lookups + legacy rows. Rows carry an inline action cluster (copy /
 * add-to-Anki / delete + chevron) mirroring WordResultCell's header-button
 * idiom, and tapping a row opens [TranslationResultActivity] pushed
 * in-task, seeded with the entry (its date/time in the toolbar; a missing
 * translation self-translates there AND feeds back into the log). The
 * whole page is one scroll surface; sections are rebuilt per store
 * revision with per-entry VIEW REUSE (only new entries inflate) and a
 * scroll-offset compensation on top-inserts so the reading position holds
 * while the page live-updates on the second display. Deletes and clears
 * also reset the recorder's dedupe memory so a removed line can record
 * again.
 */
class TranslationHistoryActivity : SettingsSubPageActivity() {

    override val layoutResId = R.layout.activity_translation_history

    private lateinit var emptyView: TextView
    private lateinit var sections: LinearLayout
    private lateinit var scroll: androidx.core.widget.NestedScrollView
    private lateinit var toolbar: com.google.android.material.appbar.MaterialToolbar

    /** Day-section header views in top-to-bottom order, paired with the date
     *  label the toolbar shows once that section reaches the top edge. Rebuilt
     *  every render; drives [updateToolbarTitle]. */
    private val dayHeaders = ArrayList<Pair<View, String>>()

    /** Per-entry row views, reused across renders — inflation happens once
     *  per entry id; bindings refresh every render. Cards and headers are
     *  cheap and re-inflate every render, matching the original design. */
    private val rowViews = HashMap<Long, View>()

    /** Live-session cards the user collapsed — in-memory mirror of the
     *  store's persisted set ([TranslationHistoryStore.collapsedSessions]),
     *  refreshed on every reload so the state survives app restarts. */
    private val collapsedLiveSessions = HashSet<String>()

    /** Decoded capture-card thumbnails by session id. Re-renders hit this
     *  instead of re-decoding; a concurrent render simply kicks a second
     *  idempotent decode. */
    private val thumbCache = android.util.LruCache<String, android.graphics.Bitmap>(12)

    /** The last rendered list, so an expand/collapse toggle re-renders
     *  without a store round-trip. */
    private var lastEntries: List<HistoryEntry> = emptyList()

    private var hasEntries = false

    /** [Prefs.historyHideTranslations] snapshot for the render in flight —
     *  read once per render rather than per row (constructing Prefs runs the
     *  legacy-key migration check). Every path into [bindRow] goes through
     *  [render], so it is never stale. */
    private var hideTranslations = false

    override fun onContentCreated(savedInstanceState: Bundle?) {
        emptyView = findViewById(R.id.tvHistoryEmpty)
        sections = findViewById(R.id.historySections)
        scroll = findViewById(R.id.historyScroll)
        toolbar = findViewById(R.id.toolbar)
        bindMasterToggle()
        bindCaptureImageToggle()
        bindHideTranslationsToggle()
        findViewById<View>(R.id.rowClearHistory).setOnClickListener { confirmClear() }

        // The toolbar tracks the section under the top edge as you scroll.
        scroll.setOnScrollChangeListener(
            androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, y, _, _ ->
                updateToolbarTitle(y)
            }
        )

        // Reconcile capture images against surviving rows (FIFO prune and
        // per-row deletes orphan them silently).
        HistoryImageStore.sweepAsync(this)

        // Initial load + live updates while visible: the store's revision
        // bumps on every mutation and StateFlow replays/conflates, so this
        // both loads now and coalesces bursts from live-mode cycles.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TranslationHistoryStore.revision.collectLatest { reloadNow() }
            }
        }
        // A capture's image lands on its own async write, after its row's
        // revision — reload (without top-insert compensation, the change is
        // localized to one card) so the thumbnail/reopen affordance appears
        // without waiting for an unrelated mutation.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                HistoryImageStore.revision.collectLatest { reloadNow(compensateTopInsert = false) }
            }
        }
    }

    private fun bindMasterToggle() {
        val row = findViewById<View>(R.id.rowHistoryToggle)
        row.findViewById<TextView>(R.id.tvRowTitle).setText(R.string.history_toggle_title)
        row.findViewById<TextView>(R.id.tvRowSubtitle).apply {
            setText(R.string.history_toggle_subtitle)
            isVisible = true
        }
        val toggle = row.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        // Seed BEFORE attaching the listener so restoring the saved state
        // doesn't fire a redundant write.
        toggle.isChecked = Prefs(this).translationHistoryEnabled
        // SINGLE persistence path: every state change — row tap, any direct
        // switch interaction (a11y actions; the layout keeps the switch
        // non-clickable for touch), programmatic — funnels through the
        // checked-change listener. The row tap only toggles.
        toggle.setOnCheckedChangeListener { _, checked ->
            Prefs(this).translationHistoryEnabled = checked
            if (checked) {
                // Dedupe accumulated during context-only use must not block
                // the first persistence of re-sighted lines.
                CaptureService.instance?.translationLogRecorderIfInitialized?.onHistoryEnabled()
            }
            updateEmptyState()
            syncCaptureImageRowEnabled()
        }
        row.setOnClickListener { toggle.toggle() }
    }

    private fun bindCaptureImageToggle() {
        val row = findViewById<View>(R.id.rowCaptureImageToggle)
        row.findViewById<TextView>(R.id.tvRowTitle).setText(R.string.history_capture_image_toggle_title)
        row.findViewById<TextView>(R.id.tvRowSubtitle).apply {
            setText(R.string.history_capture_image_toggle_subtitle)
            isVisible = true
        }
        val toggle = row.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        toggle.isChecked = Prefs(this).captureImageHistoryEnabled
        toggle.setOnCheckedChangeListener { _, checked ->
            Prefs(this).captureImageHistoryEnabled = checked
        }
        row.setOnClickListener { toggle.toggle() }
        syncCaptureImageRowEnabled()
    }

    /** Display-only toggle over the list rows below — NOT gated on the master
     *  switch: it governs how already-recorded rows read, which stays
     *  meaningful while recording is off. Nothing observes prefs here, so the
     *  change repaints the rows itself; no top-insert compensation, since the
     *  rows that resize are the ones on screen (and this row sits at the top
     *  of the scroll surface, so it is tapped at scroll 0 anyway). */
    private fun bindHideTranslationsToggle() {
        val row = findViewById<View>(R.id.rowHideTranslationsToggle)
        row.findViewById<TextView>(R.id.tvRowTitle)
            .setText(R.string.history_hide_translations_toggle_title)
        row.findViewById<TextView>(R.id.tvRowSubtitle).apply {
            setText(R.string.history_hide_translations_toggle_subtitle)
            isVisible = true
        }
        val toggle = row.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        toggle.isChecked = Prefs(this).historyHideTranslations
        toggle.setOnCheckedChangeListener { _, checked ->
            Prefs(this).historyHideTranslations = checked
            render(lastEntries, compensateTopInsert = false)
        }
        row.setOnClickListener { toggle.toggle() }
    }

    /** The sub-toggle is meaningless while the master switch is off:
     *  dimmed and inert, re-synced on every master change. */
    private fun syncCaptureImageRowEnabled() {
        val row = findViewById<View>(R.id.rowCaptureImageToggle)
        val enabled = Prefs(this).translationHistoryEnabled
        row.isEnabled = enabled
        row.alpha = if (enabled) 1f else 0.5f
        row.findViewById<MaterialSwitch>(R.id.switchRowToggle).isEnabled = enabled
    }

    private suspend fun reloadNow(compensateTopInsert: Boolean = true) {
        val fresh = TranslationHistoryStore.recent(this, LOAD_LIMIT)
        collapsedLiveSessions.clear()
        collapsedLiveSessions.addAll(TranslationHistoryStore.collapsedSessions(this))
        render(fresh, compensateTopInsert)
    }

    /** One rendered unit inside a day section. Entries stay newest-first,
     *  matching the store order. */
    private sealed class Block {
        val entries = mutableListOf<HistoryEntry>()

        class Plain : Block()
        class Capture(val sessionId: String, val provenance: String) : Block()
        class Live(val sessionId: String) : Block()
    }

    /** Partition one day's entries (newest-first, session rows contiguous
     *  by id order) into consecutive-run blocks: capture sessions (new
     *  "cap:"-prefixed writes only — legacy one_shot rows share
     *  construction-era session ids across unrelated captures and must
     *  stay plain), live-session transcripts (auto rows already carry
     *  per-session ids), and plain runs for everything else. */
    private fun groupBlocks(dayEntries: List<HistoryEntry>): List<Block> {
        val blocks = ArrayList<Block>()
        for (entry in dayEntries) {
            val last = blocks.lastOrNull()
            val isCapture =
                entry.sessionId.startsWith(TranslationHistoryStore.CAPTURE_SESSION_PREFIX) &&
                    (entry.provenance == TranslationHistoryStore.PROVENANCE_ONE_SHOT ||
                        entry.provenance == TranslationHistoryStore.PROVENANCE_CAMERA)
            val isLive = entry.provenance == TranslationHistoryStore.PROVENANCE_AUTO
            val target = when {
                isCapture ->
                    if (last is Block.Capture && last.sessionId == entry.sessionId) last
                    else Block.Capture(entry.sessionId, entry.provenance).also { blocks.add(it) }
                isLive ->
                    if (last is Block.Live && last.sessionId == entry.sessionId) last
                    else Block.Live(entry.sessionId).also { blocks.add(it) }
                else ->
                    if (last is Block.Plain) last
                    else Block.Plain().also { blocks.add(it) }
            }
            target.entries.add(entry)
        }
        return blocks
    }

    /** Rebuild the section tree from [fresh] (newest first): a day header
     *  per calendar day, then that day's blocks as cards. Row views are
     *  reused by entry id, so a live-update render costs one layout pass
     *  and inflates only genuinely new entries.
     *
     *  [compensateTopInsert] compensates the scroll offset for growth ABOVE
     *  the viewport — the live-stream reload inserts newest rows at the top,
     *  which would shove the reading position down without it. A
     *  collapse/expand re-render must NOT compensate: its growth is at the
     *  tapped card (in the viewport), so compensating would scroll past the
     *  revealed rows to their end. Leaving scrollY untouched keeps the
     *  tapped header pinned — everything above it is invariant under its own
     *  toggle — which reads as natural accordion motion. */
    private fun render(fresh: List<HistoryEntry>, compensateTopInsert: Boolean = true) {
        lastEntries = fresh
        hideTranslations = Prefs(this).historyHideTranslations
        val inflater = LayoutInflater.from(this)
        val grewFrom = sections.height
        val anchored = scroll.scrollY > sections.top

        sections.removeAllViews()
        dayHeaders.clear()
        var day: String? = null
        var dayEntries = mutableListOf<HistoryEntry>()
        val flushDay = {
            if (dayEntries.isNotEmpty()) {
                val label = headerFormat.format(Date(dayEntries.first().atMs))
                val header = inflater.inflate(R.layout.settings_group_header, sections, false)
                header.findViewById<TextView>(R.id.tvGroupTitle).text = label
                header.findViewById<TextView>(R.id.tvGroupBadge)?.isVisible = false
                sections.addView(header)
                dayHeaders.add(header to label)
                groupBlocks(dayEntries).forEach { sections.addView(renderBlock(inflater, it)) }
            }
        }
        fresh.forEach { entry ->
            val entryDay = dayKeyFormat.format(Date(entry.atMs))
            if (entryDay != day) {
                flushDay()
                day = entryDay
                dayEntries = mutableListOf()
            }
            dayEntries.add(entry)
        }
        flushDay()

        // Drop views for entries no longer present (deletes, prune, clear).
        val liveIds = fresh.mapTo(HashSet()) { it.id }
        rowViews.keys.retainAll(liveIds)

        hasEntries = fresh.isNotEmpty()
        if (compensateTopInsert && anchored) {
            sections.doOnNextLayout {
                val delta = it.height - grewFrom
                if (delta > 0) scroll.scrollBy(0, delta)
            }
        }
        // Header positions only exist post-layout; refresh the sticky title
        // once they do (content changed under the current scroll offset).
        sections.doOnNextLayout { updateToolbarTitle(scroll.scrollY) }
        updateEmptyState()
    }

    /** Sticky section date: the toolbar shows the date of the day-section
     *  currently under the top edge — the last header scrolled to or past —
     *  reverting to the screen title above the first header. */
    private fun updateToolbarTitle(scrollY: Int) {
        val base = sections.top
        var current: String? = null
        for ((header, label) in dayHeaders) {
            if (base + header.top <= scrollY) current = label else break
        }
        val next = current ?: getString(R.string.history_screen_title)
        if (toolbar.title?.toString() != next) toolbar.title = next
    }

    private fun renderBlock(inflater: LayoutInflater, block: Block): View = when (block) {
        is Block.Plain ->
            inflater.inflate(R.layout.history_section_card, sections, false).also { card ->
                addRows(inflater, card.findViewById(R.id.sectionRows), block.entries)
            }
        is Block.Capture ->
            inflater.inflate(R.layout.history_capture_card, sections, false).also { card ->
                bindCaptureCard(inflater, card, block)
            }
        is Block.Live ->
            inflater.inflate(R.layout.history_live_card, sections, false).also { card ->
                bindLiveCard(inflater, card, block)
            }
    }

    private fun addRows(inflater: LayoutInflater, host: LinearLayout, entries: List<HistoryEntry>) {
        entries.forEachIndexed { i, entry ->
            val row = rowViews.getOrPut(entry.id) {
                inflater.inflate(R.layout.item_translation_history_row, host, false)
            }
            (row.parent as? ViewGroup)?.removeView(row)
            bindRow(row, entry, showDivider = i < entries.lastIndex)
            host.addView(row)
        }
    }

    private fun bindCaptureCard(inflater: LayoutInflater, card: View, block: Block.Capture) {
        val isCamera = block.provenance == TranslationHistoryStore.PROVENANCE_CAMERA
        card.findViewById<ImageView>(R.id.captureBadge).apply {
            setImageResource(if (isCamera) R.drawable.ic_camera else R.drawable.ic_capture)
            contentDescription = getString(
                if (isCamera) R.string.history_capture_camera_cd
                else R.string.history_capture_screen_cd
            )
        }
        // The capture moment is the session's OLDEST row (list is newest-first).
        card.findViewById<TextView>(R.id.captureMeta).text =
            timeFormat.format(Date(block.entries.last().atMs))
        addRows(inflater, card.findViewById(R.id.sectionRows), block.entries)
        // The whole header is one tap target: the saved image when the
        // session has one, else the combined-text results page. Existence
        // is checked synchronously (one stat) so the tap behavior is
        // deterministic from the first frame; the pixel decode stays async.
        val hasImage = HistoryImageStore.fileFor(this, block.sessionId).exists()
        card.findViewById<View>(R.id.captureHeader).setOnClickListener {
            if (hasImage) reopenCapture(block.sessionId) else openCaptureCombined(block)
        }
        if (hasImage) bindThumbnail(card, block.sessionId)
    }

    /** Header tap with no saved image: the results page seeded with the
     *  whole capture's text in reading order (rows are newest-first, so
     *  reversed), translations joined the same way. No history-row id —
     *  the combined text is a view over the rows, not a row itself, so
     *  nothing should attach back. */
    private fun openCaptureCombined(block: Block.Capture) {
        val ordered = block.entries.asReversed()
        val source = ordered.joinToString("\n\n") { it.sourceText }
        val translation = ordered
            .mapNotNull { e -> e.translation?.takeIf { it.isNotEmpty() } }
            .joinToString("\n\n")
        startActivity(Intent(this, TranslationResultActivity::class.java).apply {
            putExtra(TranslationResultActivity.EXTRA_SENTENCE_TEXT, source)
            if (translation.isNotEmpty()) {
                putExtra(TranslationResultActivity.EXTRA_DRAG_SENTENCE_TRANSLATION, translation)
                block.entries.firstNotNullOfOrNull { it.backendDisplayName }?.let {
                    putExtra(TranslationResultActivity.EXTRA_DRAG_SENTENCE_TRANSLATION_SOURCE, it)
                }
            }
            putExtra(
                TranslationResultActivity.EXTRA_TOOLBAR_TITLE,
                titleFormat.format(Date(block.entries.last().atMs)),
            )
        })
    }

    /** Shows the capture card's thumbnail when the session's image exists:
     *  cache hit binds immediately; a miss decodes sampled off-main and
     *  binds if this card is still the one on screen (a re-render inflates
     *  a fresh card, whose own bind hits the now-warm cache). No image on
     *  disk (toggle off, orphan swept) leaves the thumbnail GONE. */
    private fun bindThumbnail(card: View, sessionId: String) {
        val thumbCard = card.findViewById<View>(R.id.captureThumbCard)
        val thumb = card.findViewById<ImageView>(R.id.captureThumb)
        val show = { bmp: android.graphics.Bitmap ->
            thumb.setImageBitmap(bmp)
            thumbCard.isVisible = true
            thumbCard.setOnClickListener { reopenCapture(sessionId) }
        }
        thumbCache.get(sessionId)?.let { show(it); return }
        thumbCard.isVisible = false
        thumb.tag = sessionId
        lifecycleScope.launch(Dispatchers.IO) {
            val file = HistoryImageStore.fileFor(this@TranslationHistoryActivity, sessionId)
            if (!file.exists()) return@launch
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return@launch
            val opts = BitmapFactory.Options().apply {
                var sample = 1
                while (longest / sample > THUMB_TARGET_PX) sample *= 2
                inSampleSize = sample
            }
            val bmp = BitmapFactory.decodeFile(file.path, opts) ?: return@launch
            withContext(Dispatchers.Main) {
                thumbCache.put(sessionId, bmp)
                if (thumb.tag == sessionId && thumb.isAttachedToWindow) show(bmp)
            }
        }
    }

    /** Thumbnail tap: the saved capture image re-opens in the import
     *  review, which re-runs OCR + translation on it (review-only entry —
     *  closing it returns here). */
    private fun reopenCapture(sessionId: String) {
        startActivity(Intent(this, ImageImportActivity::class.java).putExtra(
            ImageImportActivity.EXTRA_REOPEN_PATH,
            HistoryImageStore.fileFor(this, sessionId).path,
        ))
    }

    /** Binary collapse: a collapsed card is JUST its header row — no
     *  transcript rows, no divider — and the state persists per session in
     *  the store, so a session stays collapsed across viewings until the
     *  header is tapped again. */
    private fun bindLiveCard(inflater: LayoutInflater, card: View, block: Block.Live) {
        val total = block.entries.size
        val collapsed = block.sessionId in collapsedLiveSessions
        // Session start = the OLDEST row (list is newest-first).
        card.findViewById<TextView>(R.id.liveMeta).text = listOf(
            timeFormat.format(Date(block.entries.last().atMs)),
            resources.getQuantityString(R.plurals.history_line_count, total, total),
        ).joinToString(" · ")
        card.findViewById<View>(R.id.liveHeaderDivider).isVisible = !collapsed
        val rows = card.findViewById<LinearLayout>(R.id.sectionRows)
        rows.isVisible = !collapsed
        if (!collapsed) addRows(inflater, rows, block.entries)
        card.findViewById<ImageView>(R.id.liveChevron).rotation = if (collapsed) 180f else 0f
        card.findViewById<View>(R.id.liveHeader).setOnClickListener {
            val nowCollapsed = block.sessionId !in collapsedLiveSessions
            if (nowCollapsed) collapsedLiveSessions.add(block.sessionId)
            else collapsedLiveSessions.remove(block.sessionId)
            lifecycleScope.launch {
                TranslationHistoryStore.setSessionCollapsed(
                    this@TranslationHistoryActivity, block.sessionId, nowCollapsed,
                )
            }
            render(lastEntries, compensateTopInsert = false)
        }
    }

    private fun bindRow(row: View, entry: HistoryEntry, showDivider: Boolean) {
        row.findViewById<TextView>(R.id.tvHistoryMeta).text = listOfNotNull(
            timeFormat.format(Date(entry.atMs)),
            entry.backendDisplayName,
        ).joinToString(" · ")
        row.findViewById<TextView>(R.id.tvHistorySource).text = entry.sourceText
        // Hidden is a pure visibility flip on the SAME bound text: rows are
        // reused by entry id, so both branches must be written every bind or a
        // recycled row keeps the previous setting's state.
        row.findViewById<TextView>(R.id.tvHistoryTranslation).apply {
            text = entry.translation.orEmpty()
            isVisible = !hideTranslations && !entry.translation.isNullOrEmpty()
        }
        row.findViewById<View>(R.id.historyRowDivider).isVisible = showDivider
        row.findViewById<View>(R.id.historyRowContent).apply {
            setOnClickListener { openEntry(entry) }
            // Copy moved off a button and onto a long-press of the row.
            setOnLongClickListener { copyEntry(entry); true }
        }
        row.findViewById<View>(R.id.btnHistoryAnki).setOnClickListener { addToAnki(entry) }
        row.findViewById<View>(R.id.btnHistoryDelete).setOnClickListener { confirmDelete(entry) }
    }

    private fun updateEmptyState() {
        emptyView.isVisible = !hasEntries
        sections.isVisible = hasEntries
        if (!hasEntries) {
            emptyView.setText(
                if (Prefs(this).translationHistoryEnabled) R.string.history_empty_none
                else R.string.history_empty_off
            )
        }
    }

    private fun confirmClear() {
        OverlayAlert.Builder(this)
            .setTitle(getString(R.string.history_clear_confirm_title))
            .setMessage(getString(R.string.history_clear_confirm_message))
            .addButton(
                getString(R.string.history_clear_menu),
                themeColor(R.attr.ptDanger),
                themeColor(R.attr.ptAccentOn),
            ) {
                lifecycleScope.launch {
                    // clear() wipes rows AND their images atomically; we only
                    // drop the UI-side decoded-thumbnail cache here.
                    TranslationHistoryStore.clear(this@TranslationHistoryActivity)
                    thumbCache.evictAll()
                    // The store is empty — nothing is a duplicate anymore.
                    CaptureService.instance?.translationLogRecorderIfInitialized?.onHistoryCleared()
                }
            }
            .addCancelButton()
            .show()
    }

    private fun confirmDelete(entry: HistoryEntry) {
        OverlayAlert.Builder(this)
            .setTitle(getString(R.string.history_delete_confirm_title))
            .setMessage(entry.sourceText)
            .addButton(
                getString(R.string.history_action_delete),
                themeColor(R.attr.ptDanger),
                themeColor(R.attr.ptAccentOn),
            ) {
                lifecycleScope.launch {
                    TranslationHistoryStore.delete(this@TranslationHistoryActivity, entry.id)
                    // Un-remember the line so its next sighting records again.
                    CaptureService.instance?.translationLogRecorderIfInitialized
                        ?.onEntryDeleted(entry.normKey)
                }
            }
            .addCancelButton()
            .show()
    }

    /** The clipboard mirrors the row AS RENDERED: with translations hidden a
     *  long-press copies the captured text alone, so the one gesture that
     *  reaches a row without opening it can't leak the answer the toggle is
     *  deliberately withholding. Tapping through to the entry is still the
     *  way to get the translation. */
    private fun copyEntry(entry: HistoryEntry) {
        val text = listOfNotNull(
            entry.sourceText,
            if (hideTranslations) null else entry.translation,
        ).joinToString("\n")
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("PlayTranslate", text))
        Toast.makeText(this, R.string.history_copied_toast, Toast.LENGTH_SHORT).show()
    }

    private fun addToAnki(entry: HistoryEntry) {
        if (!AnkiManager(this).isAnkiDroidInstalled()) {
            OverlayAlert.Builder(this)
                .setTitle(getString(R.string.anki_not_installed_title))
                .setMessage(getString(R.string.anki_not_installed_message))
                .addCancelButton()
                .show()
            return
        }
        startActivity(Intent(this, AnkiPermissionActivity::class.java).apply {
            putExtra(AnkiPermissionActivity.EXTRA_FORWARD_TARGET, AnkiPermissionActivity.TARGET_SENTENCE)
            putExtra(SentenceAnkiReviewActivity.EXTRA_SENTENCE, entry.sourceText)
            putExtra(SentenceAnkiReviewActivity.EXTRA_TRANSLATION, entry.translation ?: "")
            putExtra(SentenceAnkiReviewActivity.EXTRA_SOURCE_LANG, entry.sourceLang)
        })
    }

    /** Row tap: the full translation-results page, pushed in-task (plain
     *  launch — no NEW_TASK — so back returns here), seeded with the
     *  entry. A missing translation self-translates there and attaches
     *  back onto this entry; the toolbar shows the entry's date/time. */
    private fun openEntry(entry: HistoryEntry) {
        startActivity(Intent(this, TranslationResultActivity::class.java).apply {
            putExtra(TranslationResultActivity.EXTRA_SENTENCE_TEXT, entry.sourceText)
            entry.translation?.takeIf { it.isNotEmpty() }?.let { tr ->
                putExtra(TranslationResultActivity.EXTRA_DRAG_SENTENCE_TRANSLATION, tr)
                entry.backendDisplayName?.let {
                    putExtra(TranslationResultActivity.EXTRA_DRAG_SENTENCE_TRANSLATION_SOURCE, it)
                }
            }
            // Row identity + stored pair: a late translation attaches to
            // exactly this row, and only when the pair still matches.
            putExtra(TranslationResultActivity.EXTRA_HISTORY_ENTRY_ID, entry.id)
            putExtra(TranslationResultActivity.EXTRA_HISTORY_SOURCE_LANG, entry.sourceLang)
            putExtra(TranslationResultActivity.EXTRA_HISTORY_TARGET_LANG, entry.targetLang)
            putExtra(
                TranslationResultActivity.EXTRA_TOOLBAR_TITLE,
                titleFormat.format(Date(entry.atMs)),
            )
        })
    }

    /** Groups by calendar day; headers render the locale's medium date,
     *  rows the short time only (the day is the section header's job). */
    private val dayKeyFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val headerFormat: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
    private val timeFormat: DateFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
    private val titleFormat: DateFormat =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    private companion object {
        /** Rows loaded per view. The page is one scroll surface, so every
         *  loaded row stays inflated (reused by id across renders); paging
         *  can come later. */
        const val LOAD_LIMIT = 200

        /** Longest-side bound for capture-card thumbnail decodes — the
         *  view is 44dp, so ~256px covers any density comfortably. */
        const val THUMB_TARGET_PX = 256
    }
}
