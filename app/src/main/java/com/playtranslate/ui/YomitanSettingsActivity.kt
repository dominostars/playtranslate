package com.playtranslate.ui

import android.net.Uri
import android.os.Bundle
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
import com.playtranslate.yomitan.RecommendedYomitanDictionaries
import com.playtranslate.yomitan.RecommendedYomitanDictionary
import com.playtranslate.yomitan.YomitanCategory
import com.playtranslate.yomitan.YomitanDictionary
import com.playtranslate.yomitan.YomitanDictionaryStore
import com.playtranslate.yomitan.YomitanImportResult
import com.playtranslate.yomitan.YomitanRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * Yomitan dictionary manager (Settings → Configure → Yomitan).
 *
 * Imports Yomitan dictionary zips via SAF, validates them through
 * [YomitanDictionaryStore], and lists them grouped by data category — one
 * section per [YomitanCategory] that has at least one dictionary, each with
 * its own drag-reorder priority (a multi-category dictionary appears in every
 * matching section and is ordered independently in each).
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
    private val pickDictionary = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) startImport(uri)
    }

    override fun onContentCreated(savedInstanceState: Bundle?) {
        sectionsContainer = findViewById(R.id.yomitanSections)

        findViewById<View>(R.id.btnYomitanImport).setOnClickListener {
            pickDictionary.launch(
                arrayOf(
                    "application/zip",
                    "application/octet-stream",
                    "application/x-zip-compressed",
                )
            )
        }

        refresh()
    }

    // ── Import ──────────────────────────────────────────────────────────

    private fun startImport(uri: android.net.Uri) {
        val progress = OverlayProgress.Builder(this)
            .setTitle(getString(R.string.yomitan_importing_title))
            .setMessage(getString(R.string.yomitan_importing_message))
            .setOnDismiss { importJob?.cancel() } // USER cancel and activity pause alike
            .show()
        progress.setIndeterminate(true)

        importJob = lifecycleScope.launch {
            val result = try {
                YomitanDictionaryStore.import(this@YomitanSettingsActivity, uri)
            } finally {
                progress.dismiss()
            }
            handleImportResult(result)
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
            .setOnDismiss { importJob?.cancel() } // USER cancel and activity pause alike
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
        }
    }

    private fun showImportAlert(title: String, message: String) {
        OverlayAlert.Builder(this)
            .hideIcon()
            .setTitle(title)
            .setMessage(message)
            .addButton(
                getString(android.R.string.ok),
                themeColor(R.attr.ptAccent),
                themeColor(R.attr.ptAccentOn),
            ) {}
            .show()
    }

    // ── Sections ────────────────────────────────────────────────────────

    private fun refresh() {
        lifecycleScope.launch {
            val registry = YomitanDictionaryStore.load(this@YomitanSettingsActivity)
            renderSections(registry)
        }
    }

    private fun renderSections(registry: YomitanRegistry) {
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
            val adapter = DictionaryAdapter(dictionaries.toMutableList())
            recycler.adapter = adapter
            adapter.touchHelper = attachDragHelper(recycler, category, adapter)

            if (category == YomitanCategory.TERMS) {
                bindSingleDictionaryToggle(section, registry.termsSingleDictionary)
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
            getString(R.string.yomitan_delete_message_multi, dictionary.title)
        } else {
            getString(R.string.yomitan_delete_message, dictionary.title)
        }
        OverlayAlert.Builder(this)
            .hideIcon()
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
    ) : RecyclerView.Adapter<DictionaryAdapter.VH>() {

        var touchHelper: ItemTouchHelper? = null

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val dragHandle: ImageView = view.findViewById(R.id.yomitanDragHandle)
            val title: TextView = view.findViewById(R.id.tvYomitanTitle)
            val subtitle: TextView = view.findViewById(R.id.tvYomitanSubtitle)
            val delete: ImageView = view.findViewById(R.id.btnYomitanDelete)
            val divider: View = view.findViewById(R.id.yomitanRowDivider)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = VH(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_yomitan_dictionary_row, parent, false)
        )

        override fun getItemCount(): Int = working.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = working[position]
            holder.title.text = entry.title
            val description = entry.description.orEmpty()
            holder.subtitle.isGone = description.isEmpty()
            holder.subtitle.text = description
            holder.divider.isVisible = position < working.size - 1

            holder.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    touchHelper?.startDrag(holder)
                }
                false
            }
            holder.delete.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) confirmDelete(working[pos])
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
    }
}
