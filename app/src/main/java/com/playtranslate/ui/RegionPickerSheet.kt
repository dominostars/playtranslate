package com.playtranslate.ui

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import android.view.Display
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.Prefs
import com.playtranslate.RegionEntry
import com.playtranslate.R
import com.playtranslate.fullScreenDialogTheme
import com.playtranslate.themeColor

class RegionPickerSheet : DialogFragment() {

    var onSaved: (() -> Unit)? = null
    var onTranslateOnce: ((RegionEntry) -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var gameDisplay: Display? = null

    private lateinit var prefs: Prefs
    private var workingList: MutableList<RegionEntry> = mutableListOf()
    private var selectedId: String = ""
    private var isEditMode = false

    private lateinit var recyclerView: RecyclerView
    private lateinit var btnEdit: Button
    private lateinit var tvTitle: TextView
    private lateinit var adapter: RegionAdapter
    private var itemTouchHelper: ItemTouchHelper? = null

    override fun getTheme(): Int = fullScreenDialogTheme(requireContext())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_region_picker, container, false)

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(R.style.AnimSlideBottom)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = Prefs(requireContext())
        workingList = prefs.getRegionList().toMutableList()
        selectedId = prefs.selectedRegionId.ifEmpty { workingList.firstOrNull()?.id ?: "" }

        recyclerView = view.findViewById(R.id.regionRecyclerView)
        btnEdit      = view.findViewById(R.id.btnEditRegion)
        tvTitle      = view.findViewById(R.id.tvRegionPickerTitle)

        val noPreviewNotice = view.findViewById<View>(R.id.noPreviewNotice)
        if (PlayTranslateAccessibilityService.isEnabled) {
            noPreviewNotice.visibility = View.GONE
        } else {
            noPreviewNotice.visibility = View.VISIBLE
            noPreviewNotice.setOnClickListener {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        view.findViewById<View>(R.id.btnAddRegion).setOnClickListener {
            if (isEditMode) exitEditMode()
            if (PlayTranslateAccessibilityService.isEnabled) {
                openAddCustomSheet()
            } else {
                showCustomRegionA11yDialog()
            }
        }

        btnEdit.setOnClickListener {
            if (isEditMode) exitEditMode() else enterEditMode()
        }

        adapter = RegionAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        setupDragHelper()
        adapter.submitList()

        showSelectedOverlay()
    }

    override fun onResume() {
        super.onResume()
        showSelectedOverlay()
    }

    /** App went to background — kill the overlay immediately so it doesn't get stuck. */
    override fun onStop() {
        PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
        super.onStop()
        if (showsDialog) dismissAllowingStateLoss()
    }

    override fun onDismiss(dialog: DialogInterface) {
        PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
        super.onDismiss(dialog)
    }

    // ── Edit mode ─────────────────────────────────────────────────────────

    private fun enterEditMode() {
        isEditMode = true
        btnEdit.text = getString(R.string.label_done)
        tvTitle.text = "Editing Regions"
        adapter.submitList()
        itemTouchHelper?.attachToRecyclerView(recyclerView)
    }

    private fun exitEditMode() {
        prefs.setRegionList(workingList)
        isEditMode = false
        btnEdit.text = getString(R.string.label_edit)
        tvTitle.text = getString(R.string.label_select_region)
        itemTouchHelper?.attachToRecyclerView(null)
        adapter.submitList()
        showSelectedOverlay()
    }

    // ── Drag-to-reorder ──────────────────────────────────────────────────

    private fun setupDragHelper() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                val item = workingList.removeAt(from)
                workingList.add(to, item)
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled() = false
        }
        itemTouchHelper = ItemTouchHelper(callback)
    }

    // ── Actions ────────────────────────────────────────────────────────────

    private fun deleteItem(index: Int) {
        workingList.removeAt(index)
        if (workingList.isEmpty()) {
            workingList.addAll(Prefs.DEFAULT_REGION_LIST)
        }
        adapter.submitList()
    }

    // ── Sheets ──────────────────────────────────────────────────────────

    private fun showCustomRegionA11yDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.custom_region_a11y_required_title)
            .setMessage(R.string.custom_region_a11y_required_message)
            .setPositiveButton(R.string.btn_open_a11y_settings) { _, _ ->
                startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openEditSheet(index: Int) {
        if (childFragmentManager.findFragmentByTag(AddCustomRegionSheet.TAG) != null) return
        val entry = workingList.getOrNull(index) ?: return
        PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
        AddCustomRegionSheet().also { sheet ->
            sheet.gameDisplay = gameDisplay
            sheet.initRegion(entry, index)
            sheet.onRegionEdited = { edited ->
                workingList = prefs.getRegionList().toMutableList()
                selectedId = edited.id
                adapter.submitList()
                showSelectedOverlay()
            }
            sheet.onTranslateOnce = { region ->
                PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
                if (showsDialog) dismissAllowingStateLoss()
                onTranslateOnce?.invoke(region)
            }
            sheet.onDismissed = {
                if (isAdded && !isDetached) {
                    adapter.submitList()
                    showSelectedOverlay()
                }
            }
        }.show(childFragmentManager, AddCustomRegionSheet.TAG)
    }

    private fun openAddCustomSheet() {
        if (childFragmentManager.findFragmentByTag(AddCustomRegionSheet.TAG) != null) return
        PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
        AddCustomRegionSheet().also { sheet ->
            sheet.gameDisplay = gameDisplay
            sheet.onRegionAdded = { newEntry ->
                prefs.selectedRegionId = newEntry.id
                PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
                onSaved?.invoke()
                if (showsDialog) dismissAllowingStateLoss()
            }
            sheet.onDismissed = {
                if (isAdded && !isDetached) {
                    adapter.submitList()
                    showSelectedOverlay()
                }
            }
            sheet.onTranslateOnce = { region ->
                PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
                if (showsDialog) dismissAllowingStateLoss()
                onTranslateOnce?.invoke(region)
            }
        }.show(childFragmentManager, AddCustomRegionSheet.TAG)
    }

    // ── Overlay helpers ────────────────────────────────────────────────────

    private fun showSelectedOverlay() {
        val display = gameDisplay ?: return
        val e = workingList.find { it.id == selectedId } ?: workingList.firstOrNull() ?: return
        PlayTranslateAccessibilityService.instance?.showRegionOverlay(display, e)
    }

    // ── RecyclerView Adapter ──────────────────────────────────────────────

    private inner class RegionAdapter : RecyclerView.Adapter<RegionAdapter.VH>() {

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val radio: RadioButton = itemView.findViewById(R.id.radioRegion)
            val label: TextView = itemView.findViewById(R.id.tvRegionLabel)
            val dragHandle: ImageView = itemView.findViewById(R.id.dragHandle)
            val btnDelete: ImageView = itemView.findViewById(R.id.btnDeleteRegion)
        }

        @SuppressLint("NotifyDataSetChanged")
        fun submitList() {
            notifyDataSetChanged()
        }

        override fun getItemCount() = workingList.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_region_row, parent, false)
            return VH(view)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = workingList[position]

            holder.label.text = entry.label
            val isSelected = workingList.getOrNull(position)?.id == selectedId
            holder.radio.isChecked = isSelected
            holder.itemView.setBackgroundColor(
                if (isSelected && !isEditMode) holder.itemView.context.themeColor(R.attr.colorBgCard)
                else android.graphics.Color.TRANSPARENT
            )

            if (isEditMode) {
                holder.radio.visibility = View.GONE
                holder.dragHandle.visibility = View.VISIBLE
                holder.btnDelete.visibility = View.VISIBLE

                holder.dragHandle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        itemTouchHelper?.startDrag(holder)
                    }
                    false
                }

                holder.btnDelete.setOnClickListener {
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) deleteItem(pos)
                }

                holder.itemView.setOnClickListener {
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) openEditSheet(pos)
                }
            } else {
                holder.radio.visibility = View.VISIBLE
                holder.dragHandle.visibility = View.GONE
                holder.btnDelete.visibility = View.GONE

                holder.itemView.setOnClickListener {
                    val pos = holder.bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                    val e = workingList.getOrElse(pos) { return@setOnClickListener }
                    selectedId = e.id
                    prefs.selectedRegionId = e.id
                    gameDisplay?.let { d -> PlayTranslateAccessibilityService.instance?.showRegionOverlay(d, e) }
                    onSaved?.invoke()
                    submitList()
                }
            }
        }
    }

    companion object {
        const val TAG = "RegionPickerSheet"
    }
}
