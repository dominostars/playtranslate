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
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.AnkiManager
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.themeColor
import com.playtranslate.translationlog.TranslationHistoryStore
import com.playtranslate.translationlog.TranslationHistoryStore.HistoryEntry
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * Tools → History: the reading surface for the translation log. Hosts its
 * OWN master switch (enable/consume/clear all live at one address — the
 * empty state doubles as onboarding when the feature is off), a compact
 * reverse-chronological list, tap-to-expand per-row actions (copy /
 * add-to-Anki / delete), swipe-to-delete, and Clear History in the
 * toolbar overflow. Reads [TranslationHistoryStore]; writes arrive from
 * [com.playtranslate.translationlog.TranslationLogRecorder] while the
 * capture pipelines run.
 */
class TranslationHistoryActivity : SettingsSubPageActivity() {

    override val layoutResId = R.layout.activity_translation_history

    private val entries = mutableListOf<HistoryEntry>()
    private lateinit var adapter: HistoryAdapter
    private lateinit var emptyView: TextView

    /** The single expanded row's entry id, or -1. */
    private var expandedId: Long = -1

    override fun onContentCreated(savedInstanceState: Bundle?) {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.menu_translation_history)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_clear_history) {
                confirmClear(); true
            } else false
        }

        emptyView = findViewById(R.id.tvHistoryEmpty)
        bindMasterToggle()

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

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                // Restore the row immediately — the confirm dialog floats
                // above it; a confirmed delete reloads the list anyway.
                adapter.notifyItemChanged(pos)
                confirmDelete(entries[pos])
            }
        }).attachToRecyclerView(recycler)

        reload()
    }

    override fun onResume() {
        super.onResume()
        // Fresh after returning from the Anki composer or a play session.
        reload()
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

    private fun reload() {
        lifecycleScope.launch {
            val fresh = TranslationHistoryStore.recent(this@TranslationHistoryActivity, LOAD_LIMIT)
            entries.clear()
            entries.addAll(fresh)
            adapter.notifyDataSetChanged()
            updateEmptyState()
        }
    }

    private fun updateEmptyState() {
        val empty = entries.isEmpty()
        emptyView.isVisible = empty
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
                    expandedId = -1
                    reload()
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
                    if (expandedId == entry.id) expandedId = -1
                    reload()
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

    private inner class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val content: View = view.findViewById(R.id.historyRowContent)
            val source: TextView = view.findViewById(R.id.tvHistorySource)
            val translation: TextView = view.findViewById(R.id.tvHistoryTranslation)
            val meta: TextView = view.findViewById(R.id.tvHistoryMeta)
            val actions: View = view.findViewById(R.id.historyRowActions)
            val copy: View = view.findViewById(R.id.btnHistoryCopy)
            val anki: View = view.findViewById(R.id.btnHistoryAnki)
            val delete: View = view.findViewById(R.id.btnHistoryDelete)
            val divider: View = view.findViewById(R.id.historyRowDivider)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_translation_history_row, parent, false)
            )

        override fun getItemCount() = entries.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = entries[position]
            holder.source.text = entry.sourceText
            holder.translation.text = entry.translation.orEmpty()
            holder.translation.isVisible = !entry.translation.isNullOrEmpty()
            holder.meta.text = listOfNotNull(
                timeFormat.format(Date(entry.atMs)),
                entry.backendDisplayName,
            ).joinToString(" · ")
            holder.actions.isVisible = entry.id == expandedId
            holder.divider.isVisible = position < entries.size - 1

            holder.content.setOnClickListener {
                val previous = expandedId
                expandedId = if (expandedId == entry.id) -1 else entry.id
                if (previous != -1L) {
                    val prevPos = entries.indexOfFirst { e -> e.id == previous }
                    if (prevPos >= 0) notifyItemChanged(prevPos)
                }
                notifyItemChanged(holder.bindingAdapterPosition)
            }
            holder.copy.setOnClickListener { copyEntry(entry) }
            holder.anki.setOnClickListener { addToAnki(entry) }
            holder.delete.setOnClickListener { confirmDelete(entry) }
        }
    }

    private val timeFormat: DateFormat =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    private companion object {
        /** Rows loaded per view — the store holds up to
         *  [TranslationHistoryStore.MAX_ROWS]; paging can come later. */
        const val LOAD_LIMIT = 500
    }
}
