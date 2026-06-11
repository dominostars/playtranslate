package com.playtranslate.ui

import android.os.Bundle
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
import com.playtranslate.R
import com.playtranslate.themeColor
import com.playtranslate.yomitan.YomitanCategory
import com.playtranslate.yomitan.YomitanDictionary
import com.playtranslate.yomitan.YomitanDictionaryStore
import com.playtranslate.yomitan.YomitanImportResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Debug-only Yomitan dictionary manager (Settings → Configure → Yomitan).
 *
 * Imports Yomitan dictionary zips via SAF, validates them through
 * [YomitanDictionaryStore], and lists them grouped by data category — one
 * section per [YomitanCategory] that has at least one dictionary, each with
 * its own drag-reorder priority (a multi-category dictionary appears in every
 * matching section and is ordered independently in each). Stage 1: imported
 * dictionaries are stored and managed but not yet used for lookups.
 */
class YomitanSettingsActivity : SettingsSubPageActivity() {

    override val layoutResId: Int = R.layout.activity_yomitan_settings

    private lateinit var sectionsContainer: LinearLayout
    private var importJob: Job? = null

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
                YomitanImportResult.IoError -> showImportAlert(
                    getString(R.string.yomitan_io_error_title),
                    getString(R.string.yomitan_io_error_message),
                )
            }
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

    private fun renderSections(registry: com.playtranslate.yomitan.YomitanRegistry) {
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

            sectionsContainer.addView(section)
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
}
