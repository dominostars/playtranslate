package com.playtranslate.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.language.LanguagePackDownloader
import com.playtranslate.language.SourceLangId
import com.playtranslate.themeColor
import com.playtranslate.yomitan.GroupedNames
import com.playtranslate.yomitan.RecommendedYomitanDictionaries
import com.playtranslate.yomitan.RecommendedYomitanDictionary
import com.playtranslate.yomitan.YomitanCategory
import com.playtranslate.yomitan.YomitanDataStore
import com.playtranslate.yomitan.YomitanDictionary
import com.playtranslate.yomitan.YomitanDictionaryStore
import com.playtranslate.yomitan.YomitanImportResult
import com.playtranslate.yomitan.YomitanRegistry
import com.playtranslate.yomitan.summarizeBatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Yomitan dictionary manager (Settings → Configure → Yomitan).
 *
 * Imports Yomitan dictionary zips and "Export Dictionary Collection" dumps
 * via SAF, validates them through [YomitanDictionaryStore], and lists them
 * grouped by data category — one section per [YomitanCategory] that has at
 * least one dictionary, each with its own drag-reorder priority (a
 * multi-category dictionary appears in every matching section and is ordered
 * independently in each). OUTDATED dictionaries (registry entries whose rows
 * were dropped by a schema bump) render as warning rows: tapping one opens
 * the file picker to re-import, and delete still removes it.
 */
class YomitanSettingsActivity : SettingsSubPageActivity() {

    override val layoutResId: Int = R.layout.activity_yomitan_settings

    private lateinit var sectionsContainer: LinearLayout
    private var importJob: Job? = null
    private var toggleWriteJob: Job? = null
    private val prefs by lazy { Prefs(this) }

    /** Zips come from downloads/file managers with inconsistent MIME types —
     *  octet-stream is common for GitHub release assets. The importer's own
     *  validation is what actually decides whether the pick was a dictionary. */
    private val pickDictionaries = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        // OpenMultipleDocuments returns an EMPTY list on cancel (not null).
        if (uris.isNotEmpty()) startBatchImport(uris)
    }

    override fun onContentCreated(savedInstanceState: Bundle?) {
        sectionsContainer = findViewById(R.id.yomitanSections)

        findViewById<View>(R.id.btnYomitanImport).setOnClickListener {
            pickDictionaries.launch(IMPORT_MIME_TYPES)
        }

        refresh()
    }

    /** Re-render on return from a sub-page (e.g. the detail page edited an
     *  alias). onRestart fires only when coming back from stopped, so it won't
     *  double-render on first launch (onContentCreated already renders). */
    override fun onRestart() {
        super.onRestart()
        refresh()
    }

    // ── Import ──────────────────────────────────────────────────────────

    /** Imports one or more picked zips sequentially behind a single progress
     *  popup. Single pick → the rich per-file alert; multi-pick → an aggregated
     *  summary. Sequential (not parallel) so each import's live-free-space disk
     *  guard is a real aggregate bound, mirroring the auto-update scan. */
    private fun startBatchImport(uris: List<Uri>) {
        val progress = OverlayProgress.Builder(this)
            .setTitle(getString(R.string.yomitan_importing_title))
            .setMessage(getString(R.string.yomitan_importing_message))
            .setOnDismiss { importJob?.cancel() } // USER cancel/back or host teardown; a plain background keeps importing
            .show()
        // Indeterminate: each file's import is itself indeterminate, and a
        // per-file "% of files" bar would sit visibly stuck while the largest
        // term bank validates. The "N of M" message carries the progress.
        progress.setIndeterminate(true)

        importJob = lifecycleScope.launch {
            val outcomes = ArrayList<Pair<Uri, YomitanImportResult>>(uris.size)
            try {
                for ((index, uri) in uris.withIndex()) {
                    // Multi-file only: show the running count. A lone file keeps
                    // the generic "Validating…" message (no "1 of 1").
                    if (uris.size > 1) {
                        progress.setMessage(
                            getString(R.string.yomitan_importing_progress, index + 1, uris.size),
                        )
                    }
                    val result = try {
                        YomitanDictionaryStore.import(this@YomitanSettingsActivity, uri)
                    } catch (c: CancellationException) {
                        throw c // cooperative cancel (Cancel tap / pause) must propagate
                    } catch (e: Exception) {
                        // import() is total on its documented paths except the
                        // unguarded sha256 (and latent OOM); contain a per-file
                        // throw so one bad file can't sink the batch + its summary.
                        Log.w(TAG, "import threw for $uri", e)
                        YomitanImportResult.IoError
                    }
                    // No global "storage wall" break: import()'s disk guard is
                    // per-file (≈2× THIS zip) and its temp copy is deleted as it
                    // returns, so a later, smaller dictionary can still fit. Each
                    // out-of-space file is just reported individually in the summary.
                    outcomes += uri to result
                }
            } catch (c: CancellationException) {
                refresh() // surface the imports that did land before the cancel
                throw c
            } finally {
                progress.dismiss() // idempotent; before the summary so scrims don't stack
            }
            // Normal completion only (a cancel rethrew above).
            if (uris.size == 1) {
                // Preserve the rich single-file alerts (specific invalid reason,
                // insufficient-space byte sizes).
                handleImportResult(outcomes.single().second)
            } else {
                refresh()
                showBatchSummary(outcomes)
            }
        }
    }

    /** Downloads a recommended dictionary, then funnels the zip through the
     *  same import as a hand-picked file. The upstream URL is mutable (content
     *  is regenerated upstream), so we never resume a stale partial and lean on
     *  [YomitanDictionaryStore.import]'s validation rather than a SHA-256 pin. */
    private fun startRecommendedDownload(rec: RecommendedYomitanDictionary) {
        val progress = OverlayProgress.Builder(this)
            .setTitle(getString(R.string.yomitan_downloading_title))
            .setMessage(rec.displayTitle)
            .setOnDismiss { importJob?.cancel() } // USER cancel/back or host teardown; a plain background keeps importing
            .show()

        val tmp = File(
            File(cacheDir, "yomitan-recommended").apply { mkdirs() },
            "${rec.displayTitle.hashCode()}.zip",
        )

        importJob = lifecycleScope.launch {
            var downloadFailed = false
            // Flips (on the main thread) once bytes finish arriving. The
            // download's progress callback posts via runOnUiThread (sync
            // messages) while the coroutine resumes via Main-dispatch (async
            // messages that can leapfrog a sync one past a frame sync-barrier),
            // so a final determinate update can otherwise land AFTER the switch
            // to indeterminate and clobber it back. The guard drops late updates.
            var downloadComplete = false
            val result: YomitanImportResult? = try {
                tmp.delete() // mutable URL: start fresh, never resume a stale partial
                // identity encoding: Jiten gzips its zip (verified to honor
                // identity), which otherwise strips Content-Length and hides
                // the size. Safe here; the shared default stays transparent-gzip.
                LanguagePackDownloader().download(rec.url, tmp, requestIdentityEncoding = true) { p ->
                    runOnUiThread {
                        if (!downloadComplete) {
                            progress.showYomitanDownloadProgress(
                                this@YomitanSettingsActivity, p.bytesReceived, p.totalBytes,
                            )
                        }
                    }
                }
                downloadComplete = true
                progress.setIndeterminate(true)
                progress.setMessage(getString(R.string.yomitan_importing_message))
                YomitanDictionaryStore.import(this@YomitanSettingsActivity, Uri.fromFile(tmp))
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "recommended download failed: ${rec.displayTitle}", e)
                downloadFailed = true
                null
            } finally {
                tmp.delete()
                progress.dismiss()
            }
            when {
                downloadFailed -> showImportAlert(
                    getString(R.string.yomitan_download_error_title),
                    getString(R.string.yomitan_download_error_message),
                )
                result != null -> handleImportResult(result)
            }
        }
    }

    /** Routes an import outcome to a refresh (success) or the matching alert.
     *  Shared by the file-picker import and the recommended-download import. */
    private fun handleImportResult(result: YomitanImportResult) {
        when (result) {
            is YomitanImportResult.Success -> refresh()
            is YomitanImportResult.CollectionImported -> {
                refresh()
                val lines = buildList {
                    add(
                        resources.getQuantityString(
                            R.plurals.yomitan_collection_imported_count,
                            result.imported, result.imported,
                        ),
                    )
                    if (result.skippedExisting > 0) {
                        add(
                            resources.getQuantityString(
                                R.plurals.yomitan_collection_skipped_count,
                                result.skippedExisting, result.skippedExisting,
                            ),
                        )
                    }
                }
                showImportAlert(
                    getString(
                        if (result.imported > 0) R.string.yomitan_collection_imported_title
                        else R.string.yomitan_collection_none_title,
                    ),
                    lines.joinToString("\n"),
                )
            }
            is YomitanImportResult.Duplicate -> showImportAlert(
                getString(R.string.yomitan_duplicate_title),
                getString(R.string.yomitan_duplicate_message, result.title),
            )
            is YomitanImportResult.InvalidFormat -> showImportAlert(
                getString(R.string.yomitan_invalid_title),
                // Diagnostic detail line under the generic message —
                // dictionary authors need to know WHICH bank/entry broke.
                listOfNotNull(getString(R.string.yomitan_invalid_message), result.reason)
                    .joinToString("\n\n"),
            )
            is YomitanImportResult.InsufficientSpace -> showImportAlert(
                getString(R.string.yomitan_no_space_title),
                getString(
                    R.string.yomitan_no_space_message,
                    android.text.format.Formatter.formatShortFileSize(
                        this@YomitanSettingsActivity, result.requiredBytes,
                    ),
                    android.text.format.Formatter.formatShortFileSize(
                        this@YomitanSettingsActivity, result.availableBytes,
                    ),
                ),
            )
            YomitanImportResult.IoError -> showImportAlert(
                getString(R.string.yomitan_io_error_title),
                getString(R.string.yomitan_io_error_message),
            )
            // Auto-update-only outcome (deck deleted/opted-out mid-update); the
            // manual import path here never produces it.
            is YomitanImportResult.Skipped -> Unit
        }
    }

    private fun showImportAlert(title: String, message: String) {
        OverlayAlert.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .addButton(
                getString(android.R.string.ok),
                themeColor(R.attr.ptAccent),
                themeColor(R.attr.ptAccentOn),
            ) {}
            .show()
    }

    /** Aggregated result alert for a multi-file import. Never enumerates the
     *  successes (they're already visible in the refreshed list behind the
     *  alert) — only the count plus the failure/duplicate groups, with failed
     *  files named by their SAF display name (resolved here, lazily, only for
     *  the outcomes that have no dictionary title). */
    private suspend fun showBatchSummary(outcomes: List<Pair<Uri, YomitanImportResult>>) {
        val labeled = outcomes.map { (uri, result) ->
            val label = when (result) {
                is YomitanImportResult.Success -> result.dictionary.title
                is YomitanImportResult.Duplicate -> result.title
                // Failures carry no title — name the source file instead.
                else -> uri.displayName(this@YomitanSettingsActivity)
            }
            label to result
        }
        val tally = summarizeBatch(labeled, MAX_SUMMARY_NAMES_PER_GROUP)

        val lines = buildList {
            add(
                resources.getQuantityString(
                    R.plurals.yomitan_import_summary_count, tally.totalSelected,
                    tally.importedCount, tally.totalSelected,
                ),
            )
            groupLine(R.string.yomitan_import_summary_duplicates, tally.duplicates)?.let { add(it) }
            groupLine(R.string.yomitan_import_summary_invalid, tally.invalid)?.let { add(it) }
            groupLine(R.string.yomitan_import_summary_no_space, tally.noSpace)?.let { add(it) }
            groupLine(R.string.yomitan_import_summary_failed, tally.failed)?.let { add(it) }
        }
        val title = getString(
            if (tally.importedCount > 0) R.string.yomitan_import_summary_title
            else R.string.yomitan_import_summary_title_none,
        )
        showImportAlert(title, lines.joinToString("\n\n"))
    }

    /** One failure/duplicate summary line: header + up to
     *  [MAX_SUMMARY_NAMES_PER_GROUP] example names + a "+K more" tail. Null when
     *  the group is empty, so the caller omits the line. */
    private fun groupLine(headerRes: Int, group: GroupedNames): String? {
        if (group.isEmpty) return null
        val names = buildList {
            addAll(group.examples)
            if (group.overflow > 0) {
                add(
                    resources.getQuantityString(
                        R.plurals.yomitan_import_summary_more, group.overflow, group.overflow,
                    ),
                )
            }
        }.joinToString(", ")
        return getString(headerRes, names)
    }

    /** SAF display name for a content [Uri] (to name a failed file in the batch
     *  summary). Off the main thread; null-safe — a recreated activity can lapse
     *  the read grant (SecurityException) and some providers return a blank name,
     *  so it falls back to the last path segment, then a generic label. */
    private suspend fun Uri.displayName(ctx: Context): String {
        val uri = this
        return withContext(Dispatchers.IO) {
            val resolved = try {
                ctx.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(0)?.takeIf { it.isNotBlank() }
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "display-name query failed for $uri", e)
                null
            }
            resolved
                ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: ctx.getString(R.string.yomitan_import_summary_unknown_file)
        }
    }

    // ── Sections ────────────────────────────────────────────────────────

    private fun refresh() {
        lifecycleScope.launch {
            val registry = YomitanDictionaryStore.load(this@YomitanSettingsActivity)
            // Registry entries with no ingested rows (post-schema-bump) —
            // rendered as warning rows below.
            val outdated = YomitanDataStore.outdatedDictIds(this@YomitanSettingsActivity)
            renderSections(registry, outdated)
        }
    }

    private fun renderSections(registry: YomitanRegistry, outdatedIds: Set<String>) {
        sectionsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (category in YomitanCategory.entries) {
            val dictionaries = registry.orderedFor(category)
            if (dictionaries.isEmpty()) continue

            val section = inflater.inflate(R.layout.yomitan_section_card, sectionsContainer, false)
            section.findViewById<View>(R.id.yomitanSectionHeader)
                .findViewById<TextView>(R.id.tvGroupTitle).text = getString(categoryTitle(category))

            val recycler = section.findViewById<RecyclerView>(R.id.rvYomitanSection)
            recycler.layoutManager = LinearLayoutManager(this)
            val adapter = DictionaryAdapter(dictionaries.toMutableList(), outdatedIds)
            recycler.adapter = adapter
            adapter.touchHelper = attachDragHelper(recycler, category, adapter)

            if (category == YomitanCategory.TERMS) {
                bindSingleDictionaryToggle(section, registry.termsSingleDictionary)
                bindDictionaryStylingToggle(section, registry.dictionaryStyling)
            }

            sectionsContainer.addView(section)
        }

        // Curated downloads last — below the user's installed dictionaries, and
        // only for a Japanese source: the recommended dicts are all JA-source
        // (matching the capability-cache gate), so they're irrelevant otherwise.
        // Each entry drops out once it's installed (and then appears in its own
        // category section above).
        val recommended =
            if (prefs.sourceLangId == SourceLangId.JA) {
                RecommendedYomitanDictionaries.notInstalled(registry)
            } else {
                emptyList()
            }
        if (recommended.isNotEmpty()) {
            val section = inflater.inflate(R.layout.yomitan_section_card, sectionsContainer, false)
            section.findViewById<View>(R.id.yomitanSectionHeader)
                .findViewById<TextView>(R.id.tvGroupTitle).text =
                getString(R.string.yomitan_category_recommended)
            val recycler = section.findViewById<RecyclerView>(R.id.rvYomitanSection)
            recycler.layoutManager = LinearLayoutManager(this)
            recycler.adapter = RecommendedAdapter(recommended)
            sectionsContainer.addView(section)
        }
    }

    /** Shows the TERMS section's fixed footer row — the single-dictionary
     *  toggle. Lives below the RecyclerView (not in the adapter), so it
     *  stays put through drag-reorders. */
    private fun bindSingleDictionaryToggle(section: View, checked: Boolean) {
        section.findViewById<View>(R.id.yomitanSectionFooterDivider).isVisible = true
        val row = section.findViewById<View>(R.id.yomitanSectionFooterToggle)
        row.isVisible = true
        row.findViewById<TextView>(R.id.tvRowTitle)
            .setText(R.string.yomitan_single_dict_title)
        row.findViewById<TextView>(R.id.tvRowSubtitle).apply {
            setText(R.string.yomitan_single_dict_subtitle)
            isVisible = true
        }
        val toggle = row.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        toggle.isChecked = checked
        // The row is the tap target (the switch itself is non-clickable by
        // layout contract); capture the new value at tap time so rapid taps
        // can't persist a stale read.
        row.setOnClickListener {
            val enabled = !toggle.isChecked
            toggle.isChecked = enabled
            // Chain on the previous write: independent launches can acquire
            // the store's IO mutex out of tap order, persisting a stale value
            // last. Launch order on Main == tap order, so joining the prior
            // job keeps writes sequential.
            val previous = toggleWriteJob
            toggleWriteJob = lifecycleScope.launch {
                previous?.join()
                YomitanDictionaryStore.setTermsSingleDictionary(
                    this@YomitanSettingsActivity, enabled,
                )
            }
        }
    }

    /** Second TERMS footer row: the styled-rendering toggle. Same
     *  outside-the-adapter placement and write-chaining discipline as
     *  [bindSingleDictionaryToggle]. */
    private fun bindDictionaryStylingToggle(section: View, checked: Boolean) {
        section.findViewById<View>(R.id.yomitanSectionFooterDivider2).isVisible = true
        val row = section.findViewById<View>(R.id.yomitanSectionFooterToggle2)
        row.isVisible = true
        row.findViewById<TextView>(R.id.tvRowTitle)
            .setText(R.string.yomitan_styling_title)
        row.findViewById<TextView>(R.id.tvRowSubtitle).apply {
            setText(R.string.yomitan_styling_subtitle)
            isVisible = true
        }
        val toggle = row.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        toggle.isChecked = checked
        row.setOnClickListener {
            val enabled = !toggle.isChecked
            toggle.isChecked = enabled
            val previous = toggleWriteJob
            toggleWriteJob = lifecycleScope.launch {
                previous?.join()
                YomitanDictionaryStore.setDictionaryStyling(
                    this@YomitanSettingsActivity, enabled,
                )
            }
        }
    }

    private fun categoryTitle(category: YomitanCategory): Int = when (category) {
        YomitanCategory.TERMS -> R.string.yomitan_category_terms
        YomitanCategory.KANJI -> R.string.yomitan_category_kanji
        YomitanCategory.FREQUENCY -> R.string.yomitan_category_frequency
        YomitanCategory.KANJI_FREQUENCY -> R.string.yomitan_category_kanji_frequency
        YomitanCategory.PITCH_ACCENT -> R.string.yomitan_category_pitch_accent
        YomitanCategory.PRONUNCIATION -> R.string.yomitan_category_pronunciation
    }

    // ── Drag-to-reorder (RegionPickerSheet idiom) ───────────────────────

    private fun attachDragHelper(
        recycler: RecyclerView,
        category: YomitanCategory,
        adapter: DictionaryAdapter,
    ): ItemTouchHelper {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                val item = adapter.working.removeAt(from)
                adapter.working.add(to, item)
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled() = false

            /** Drop finished — persist this section's order. Other sections'
             *  orders are independent and untouched. */
            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // Re-pin dividers: the dragged row may have become (or stopped
                // being) the last one.
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
                val ids = adapter.working.map { it.id }
                lifecycleScope.launch {
                    YomitanDictionaryStore.reorder(this@YomitanSettingsActivity, category, ids)
                }
            }
        }
        val helper = ItemTouchHelper(callback)
        helper.attachToRecyclerView(recycler)
        return helper
    }

    // ── Delete ──────────────────────────────────────────────────────────

    private fun confirmDelete(dictionary: YomitanDictionary) {
        val message = if (dictionary.categories.size > 1) {
            getString(R.string.yomitan_delete_message_multi)
        } else {
            getString(R.string.yomitan_delete_message)
        }
        OverlayAlert.Builder(this)
            .setTitle(getString(R.string.yomitan_delete_title, dictionary.title))
            .setMessage(message)
            .addButton(
                getString(R.string.yomitan_delete_confirm),
                themeColor(R.attr.ptDanger),
                themeColor(R.attr.ptAccentOn),
            ) {
                lifecycleScope.launch {
                    YomitanDictionaryStore.delete(this@YomitanSettingsActivity, dictionary.id)
                    refresh()
                }
            }
            .addCancelButton()
            .show()
    }

    // ── Adapter ─────────────────────────────────────────────────────────

    private inner class DictionaryAdapter(
        val working: MutableList<YomitanDictionary>,
        private val outdatedIds: Set<String>,
    ) : RecyclerView.Adapter<DictionaryAdapter.VH>() {

        var touchHelper: ItemTouchHelper? = null

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val dragHandle: ImageView = view.findViewById(R.id.yomitanDragHandle)
            val title: TextView = view.findViewById(R.id.tvYomitanTitle)
            val subtitle: TextView = view.findViewById(R.id.tvYomitanSubtitle)
            val delete: ImageView = view.findViewById(R.id.btnYomitanDelete)
            val divider: View = view.findViewById(R.id.yomitanRowDivider)
            val content: View = view.findViewById(R.id.yomitanRowContent)
            val colorDot: View = view.findViewById(R.id.yomitanColorDot)

            /** Style-declared colors, captured before any warning tint — the
             *  reset for recycled holders. */
            val defaultTitleColors = title.textColors
            val defaultSubtitleColors = subtitle.textColors
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = VH(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_yomitan_dictionary_row, parent, false)
        )

        override fun getItemCount(): Int = working.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = working[position]
            val outdated = entry.id in outdatedIds
            // Show the user's alias (nickname) when set; the detail page keeps
            // the original title.
            holder.title.text = entry.alias ?: entry.title
            // Accent dot: the per-dict override, or the subtitle text color.
            holder.colorDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(entry.accentColor ?: this@YomitanSettingsActivity.themeColor(R.attr.ptTextMuted))
            }
            if (outdated) {
                // Warning treatment: title + subtitle in ptWarning, subtitle
                // carrying the triangle + re-import hint.
                holder.title.setTextColor(
                    this@YomitanSettingsActivity.themeColor(R.attr.ptWarning),
                )
                holder.subtitle.setTextColor(
                    this@YomitanSettingsActivity.themeColor(R.attr.ptWarning),
                )
                holder.subtitle.text = warningSummary(
                    this@YomitanSettingsActivity,
                    getString(R.string.yomitan_outdated_label),
                )
                holder.subtitle.isGone = false
            } else {
                holder.title.setTextColor(holder.defaultTitleColors)
                holder.subtitle.setTextColor(holder.defaultSubtitleColors)
                val description = entry.description.orEmpty()
                holder.subtitle.isGone = description.isEmpty()
                holder.subtitle.text = description
            }
            holder.divider.isVisible = position < working.size - 1

            holder.dragHandle.setOnTouchListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    touchHelper?.startDrag(holder)
                } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                    v.performClick()
                }
                false
            }
            holder.delete.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) confirmDelete(working[pos])
            }
            // Scoped to the content block (not itemView) so tapping the drag
            // handle never also opens details. An outdated row's tap is the
            // heal path: open the import picker instead of the detail page
            // (a same-title re-import supersedes the outdated entry).
            holder.content.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val dict = working[pos]
                    if (dict.id in outdatedIds) {
                        pickDictionaries.launch(IMPORT_MIME_TYPES)
                    } else {
                        startActivity(
                            YomitanDictionaryDetailActivity.intent(
                                this@YomitanSettingsActivity, dict.id, dict.title,
                            ),
                        )
                    }
                }
            }
        }
    }

    // ── Recommended (downloadable) adapter ──────────────────────────────

    /** Static, non-orderable rows for not-yet-installed recommended
     *  dictionaries. The whole row downloads + imports; there's no drag or
     *  delete (nothing is stored until it's imported). */
    private inner class RecommendedAdapter(
        private val items: List<RecommendedYomitanDictionary>,
    ) : RecyclerView.Adapter<RecommendedAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.tvYomitanTitle)
            val subtitle: TextView = view.findViewById(R.id.tvYomitanSubtitle)
            val divider: View = view.findViewById(R.id.yomitanRowDivider)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = VH(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_yomitan_recommended_row, parent, false)
        )

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.title.text = item.displayTitle
            holder.subtitle.text = item.description
            holder.divider.isVisible = position < items.size - 1
            holder.itemView.setOnClickListener { startRecommendedDownload(item) }
        }
    }

    private companion object {
        const val TAG = "YomitanSettings"

        /** Zips come with inconsistent MIME types (octet-stream for GitHub
         *  release assets); json covers bare collection dumps. The importer's
         *  content sniff decides what the pick actually was. */
        val IMPORT_MIME_TYPES = arrayOf(
            "application/zip",
            "application/octet-stream",
            "application/x-zip-compressed",
            "application/json",
        )

        /** Cap on example names shown per failure/duplicate group in the batch
         *  summary; the rest collapse into a "+K more" tail (the alert has no
         *  scroll and a capped width). */
        const val MAX_SUMMARY_NAMES_PER_GROUP = 3
    }
}
