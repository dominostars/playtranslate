package com.playtranslate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.AnkiManager
import com.playtranslate.CaptureService
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.themeColor
import com.playtranslate.translationlog.TranslationHistoryStore
import com.playtranslate.translationlog.TranslationHistoryStore.HistoryEntry
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Tools → History: the reading surface for the translation log. Hosts its
 * OWN master switch (enable/consume/clear all live at one address — the
 * empty state doubles as onboarding when the feature is off) and a
 * reverse-chronological list grouped under per-date section headers; rows
 * carry an inline action cluster (copy / add-to-Anki / delete + chevron)
 * mirroring WordResultCell's header-button idiom, and tapping a row opens
 * [TranslationResultActivity] seeded with the entry (its date/time in the
 * toolbar; a missing translation self-translates there). Clear history is
 * a danger row under the switch. The whole page is one scroll surface
 * (the list inflates fully; reload compensates the scroll offset on
 * top-inserts to keep the reading position anchored). Live-updates via
 * [TranslationHistoryStore.revision] — the page can sit on the second
 * screen while auto-translate feeds it. Deletes and clears also reset the
 * recorder's dedupe memory so a removed line can record again.
 */
class TranslationHistoryActivity : SettingsSubPageActivity() {

    override val layoutResId = R.layout.activity_translation_history

    /** Display list: date headers interleaved with entries, newest first. */
    private sealed interface Row {
        val key: String

        data class Header(val label: String, override val key: String) : Row
        data class Item(val entry: HistoryEntry) : Row {
            override val key get() = "e:${entry.id}"
        }
    }

    private val rows = mutableListOf<Row>()
    private lateinit var adapter: HistoryAdapter
    private lateinit var emptyView: TextView
    private lateinit var listCard: View
    private lateinit var scroll: androidx.core.widget.NestedScrollView

    override fun onContentCreated(savedInstanceState: Bundle?) {
        emptyView = findViewById(R.id.tvHistoryEmpty)
        listCard = findViewById(R.id.cardHistory)
        scroll = findViewById(R.id.historyScroll)
        bindMasterToggle()
        findViewById<View>(R.id.rowClearHistory).setOnClickListener { confirmClear() }

        val recycler = findViewById<RecyclerView>(R.id.rvHistory)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = HistoryAdapter()
        recycler.adapter = adapter

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
        ) {
            override fun onMove(
                rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder,
            ) = false

            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                val pos = vh.bindingAdapterPosition
                return if (pos != RecyclerView.NO_POSITION && rows[pos] is Row.Item) {
                    super.getSwipeDirs(rv, vh)
                } else 0
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                val row = rows[pos] as? Row.Item ?: return
                // Restore the row immediately — the confirm dialog floats
                // above it; a confirmed delete reloads the list anyway.
                adapter.notifyItemChanged(pos)
                confirmDelete(row.entry)
            }
        }).attachToRecyclerView(recycler)

        // Initial load + live updates while visible: the store's revision
        // bumps on every mutation and StateFlow replays/conflates, so this
        // both loads now and coalesces bursts from live-mode cycles.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TranslationHistoryStore.revision.collectLatest { reloadNow() }
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
        toggle.isChecked = Prefs(this).translationHistoryEnabled
        // The row is the tap target; read the new value at tap time.
        row.setOnClickListener {
            val enabled = !toggle.isChecked
            toggle.isChecked = enabled
            Prefs(this).translationHistoryEnabled = enabled
            updateEmptyState()
        }
    }

    private suspend fun reloadNow() {
        val fresh = TranslationHistoryStore.recent(this, LOAD_LIMIT)
        // Interleave date headers (entries are newest-first, so days arrive
        // in descending order and consecutive-day grouping is correct).
        val newRows = ArrayList<Row>(fresh.size + 8)
        var lastDay: String? = null
        for (entry in fresh) {
            val day = dayKeyFormat.format(Date(entry.atMs))
            if (day != lastDay) {
                lastDay = day
                newRows.add(Row.Header(headerFormat.format(Date(entry.atMs)), "d:$day"))
            }
            newRows.add(Row.Item(entry))
        }

        // Diff instead of reset so live updates rebind only what changed.
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = rows.size
            override fun getNewListSize() = newRows.size
            override fun areItemsTheSame(old: Int, new: Int) = rows[old].key == newRows[new].key
            override fun areContentsTheSame(old: Int, new: Int) = rows[old] == newRows[new]
        })
        // The outer NestedScrollView owns scrolling (the list is fully
        // inflated), so a top-insert grows the card and would shove the
        // reading position down by the new rows' height. Compensate: if the
        // user has scrolled into the list, restore their anchor by scrolling
        // down by the card's height delta after layout. At the top (or in
        // the header) no compensation — the newest line should appear.
        val grewFrom = listCard.height
        val anchored = scroll.scrollY > listCard.top
        rows.clear()
        rows.addAll(newRows)
        diff.dispatchUpdatesTo(adapter)
        // Divider/last-row decisions are bind-time; a removal can move them
        // onto rows the diff didn't touch — rebind the tail.
        if (rows.isNotEmpty()) adapter.notifyItemChanged(rows.size - 1)
        if (anchored) {
            listCard.doOnNextLayout {
                val delta = it.height - grewFrom
                if (delta > 0) scroll.scrollBy(0, delta)
            }
        }
        updateEmptyState()
    }

    private fun updateEmptyState() {
        val empty = rows.isEmpty()
        emptyView.isVisible = empty
        listCard.isVisible = !empty
        if (empty) {
            emptyView.setText(
                if (Prefs(this).translationHistoryEnabled) R.string.history_empty_none
                else R.string.history_empty_off
            )
        }
    }

    private fun confirmClear() {
        OverlayAlert.Builder(this)
            .hideIcon()
            .setTitle(getString(R.string.history_clear_confirm_title))
            .setMessage(getString(R.string.history_clear_confirm_message))
            .addButton(
                getString(R.string.history_clear_menu),
                themeColor(R.attr.ptDanger),
                themeColor(R.attr.ptAccentOn),
            ) {
                lifecycleScope.launch {
                    TranslationHistoryStore.clear(this@TranslationHistoryActivity)
                    // The store is empty — nothing is a duplicate anymore.
                    CaptureService.instance?.translationLogRecorderIfInitialized?.onHistoryCleared()
                }
            }
            .addCancelButton()
            .show()
    }

    private fun confirmDelete(entry: HistoryEntry) {
        OverlayAlert.Builder(this)
            .hideIcon()
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

    private fun copyEntry(entry: HistoryEntry) {
        val text = listOfNotNull(entry.sourceText, entry.translation).joinToString("\n")
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("PlayTranslate", text))
        Toast.makeText(this, R.string.history_copied_toast, Toast.LENGTH_SHORT).show()
    }

    private fun addToAnki(entry: HistoryEntry) {
        if (!AnkiManager(this).isAnkiDroidInstalled()) {
            OverlayAlert.Builder(this)
                .hideIcon()
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
     *  entry. A missing translation self-translates there
     *  (handleSentenceMode's translateOnce path); the toolbar shows the
     *  entry's date/time. */
    private fun openEntry(entry: HistoryEntry) {
        startActivity(Intent(this, TranslationResultActivity::class.java).apply {
            putExtra(TranslationResultActivity.EXTRA_SENTENCE_TEXT, entry.sourceText)
            entry.translation?.takeIf { it.isNotEmpty() }?.let { tr ->
                putExtra(TranslationResultActivity.EXTRA_DRAG_SENTENCE_TRANSLATION, tr)
                entry.backendDisplayName?.let {
                    putExtra(TranslationResultActivity.EXTRA_DRAG_SENTENCE_TRANSLATION_SOURCE, it)
                }
            }
            putExtra(
                TranslationResultActivity.EXTRA_TOOLBAR_TITLE,
                titleFormat.format(Date(entry.atMs)),
            )
        })
    }

    private inner class HistoryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
            val label: TextView = view.findViewById(R.id.tvHistoryDateHeader)
        }

        inner class ItemVH(view: View) : RecyclerView.ViewHolder(view) {
            val content: View = view.findViewById(R.id.historyRowContent)
            val meta: TextView = view.findViewById(R.id.tvHistoryMeta)
            val copy: View = view.findViewById(R.id.btnHistoryCopy)
            val anki: View = view.findViewById(R.id.btnHistoryAnki)
            val delete: View = view.findViewById(R.id.btnHistoryDelete)
            val source: TextView = view.findViewById(R.id.tvHistorySource)
            val translation: TextView = view.findViewById(R.id.tvHistoryTranslation)
            val divider: View = view.findViewById(R.id.historyRowDivider)
        }

        override fun getItemViewType(position: Int) =
            if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ITEM

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                HeaderVH(inflater.inflate(R.layout.item_translation_history_header, parent, false))
            } else {
                ItemVH(inflater.inflate(R.layout.item_translation_history_row, parent, false))
            }
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderVH).label.text = row.label
                is Row.Item -> bindItem(holder as ItemVH, row.entry, position)
            }
        }

        private fun bindItem(holder: ItemVH, entry: HistoryEntry, position: Int) {
            holder.meta.text = listOfNotNull(
                timeFormat.format(Date(entry.atMs)),
                entry.backendDisplayName,
            ).joinToString(" · ")
            holder.source.text = entry.sourceText
            holder.translation.text = entry.translation.orEmpty()
            holder.translation.isVisible = !entry.translation.isNullOrEmpty()
            // No divider before a section header or at the card's end.
            holder.divider.isVisible = rows.getOrNull(position + 1) is Row.Item

            holder.content.setOnClickListener { openEntry(entry) }
            holder.copy.setOnClickListener { copyEntry(entry) }
            holder.anki.setOnClickListener { addToAnki(entry) }
            holder.delete.setOnClickListener { confirmDelete(entry) }
        }
    }

    /** Groups by calendar day; headers render the locale's medium date,
     *  rows the short time only (the day is the section header's job). */
    private val dayKeyFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val headerFormat: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
    private val timeFormat: DateFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
    private val titleFormat: DateFormat =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    private companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1

        /** Rows loaded per view. Lower than the store's cap on purpose: the
         *  page is one scroll surface, so every loaded row inflates (nested
         *  scrolling off disables recycling); paging can come later. */
        const val LOAD_LIMIT = 200
    }
}
