package com.playtranslate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
 * empty state doubles as onboarding when the feature is off) and per-date
 * sections in the settings-page rhythm: a group header OUTSIDE each day's
 * card, rows inside. Rows carry an inline action cluster (copy /
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

    /** Per-entry row views, reused across renders — inflation happens once
     *  per entry id; bindings refresh every render. */
    private val rowViews = HashMap<Long, View>()

    private var hasEntries = false

    override fun onContentCreated(savedInstanceState: Bundle?) {
        emptyView = findViewById(R.id.tvHistoryEmpty)
        sections = findViewById(R.id.historySections)
        scroll = findViewById(R.id.historyScroll)
        bindMasterToggle()
        findViewById<View>(R.id.rowClearHistory).setOnClickListener { confirmClear() }

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
        render(fresh)
    }

    /** Rebuild the section tree from [fresh] (newest first). Row views are
     *  reused by entry id, so a live-update render costs one layout pass
     *  and inflates only genuinely new entries; the scroll offset is
     *  compensated when the user has scrolled into the list so top-inserts
     *  don't shove their reading position. */
    private fun render(fresh: List<HistoryEntry>) {
        val inflater = LayoutInflater.from(this)
        val grewFrom = sections.height
        val anchored = scroll.scrollY > sections.top

        sections.removeAllViews()
        var day: String? = null
        var rowsHost: LinearLayout? = null
        fresh.forEachIndexed { index, entry ->
            val entryDay = dayKeyFormat.format(Date(entry.atMs))
            if (entryDay != day) {
                day = entryDay
                val header = inflater.inflate(R.layout.settings_group_header, sections, false)
                header.findViewById<TextView>(R.id.tvGroupTitle).text =
                    headerFormat.format(Date(entry.atMs))
                header.findViewById<TextView>(R.id.tvGroupBadge)?.isVisible = false
                sections.addView(header)
                val card = inflater.inflate(R.layout.history_section_card, sections, false)
                rowsHost = card.findViewById(R.id.sectionRows)
                sections.addView(card)
            }
            val host = rowsHost ?: return@forEachIndexed
            val row = rowViews.getOrPut(entry.id) {
                inflater.inflate(R.layout.item_translation_history_row, host, false)
            }
            (row.parent as? ViewGroup)?.removeView(row)
            val nextIsSameDay = fresh.getOrNull(index + 1)
                ?.let { dayKeyFormat.format(Date(it.atMs)) == entryDay } == true
            bindRow(row, entry, showDivider = nextIsSameDay)
            host.addView(row)
        }

        // Drop views for entries no longer present (deletes, prune, clear).
        val liveIds = fresh.mapTo(HashSet()) { it.id }
        rowViews.keys.retainAll(liveIds)

        hasEntries = fresh.isNotEmpty()
        if (anchored) {
            sections.doOnNextLayout {
                val delta = it.height - grewFrom
                if (delta > 0) scroll.scrollBy(0, delta)
            }
        }
        updateEmptyState()
    }

    private fun bindRow(row: View, entry: HistoryEntry, showDivider: Boolean) {
        row.findViewById<TextView>(R.id.tvHistoryMeta).text = listOfNotNull(
            timeFormat.format(Date(entry.atMs)),
            entry.backendDisplayName,
        ).joinToString(" · ")
        row.findViewById<TextView>(R.id.tvHistorySource).text = entry.sourceText
        row.findViewById<TextView>(R.id.tvHistoryTranslation).apply {
            text = entry.translation.orEmpty()
            isVisible = !entry.translation.isNullOrEmpty()
        }
        row.findViewById<View>(R.id.historyRowDivider).isVisible = showDivider
        row.findViewById<View>(R.id.historyRowContent).setOnClickListener { openEntry(entry) }
        row.findViewById<View>(R.id.btnHistoryCopy).setOnClickListener { copyEntry(entry) }
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
    }
}
