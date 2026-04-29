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
import com.playtranslate.applyAccentOverlay
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
    private var displayListener: android.hardware.display.DisplayManager.DisplayListener? = null
    private var lastDisplayRotation: Int = -1
    private var itemTouchHelper: ItemTouchHelper? = null

    override fun getTheme(): Int = fullScreenDialogTheme(requireContext())

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        applyAccentOverlay(dialog.context.theme, requireContext())
        return dialog
    }

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

        // Inset divider between rows (matching settings card style)
        val dividerDrawable = android.graphics.drawable.GradientDrawable().apply {
            setSize(0, (1 * resources.displayMetrics.density).toInt())
            setColor(requireContext().themeColor(R.attr.ptDivider))
        }
        val dividerDecoration = object : RecyclerView.ItemDecoration() {
            private val insetStart = (resources.getDimension(R.dimen.pt_row_h_padding)).toInt()
            override fun onDraw(c: android.graphics.Canvas, parent: RecyclerView, state: RecyclerView.State) {
                val left = parent.paddingLeft + insetStart
                val right = parent.width - parent.paddingRight
                for (i in 0 until parent.childCount - 1) {
                    val child = parent.getChildAt(i)
                    val top = child.bottom + (child.layoutParams as RecyclerView.LayoutParams).bottomMargin
                    val bottom = top + (1 * resources.displayMetrics.density).toInt()
                    dividerDrawable.setBounds(left, top, right, bottom)
                    dividerDrawable.draw(c)
                }
            }
        }
        recyclerView.addItemDecoration(dividerDecoration)
        setupDragHelper()
        adapter.submitList()

        showSelectedOverlay()

        // Track display changes (rotation, dimensions) to update preview thumbnails
        lastDisplayRotation = gameDisplay?.rotation ?: -1
        val dm = requireContext().getSystemService(android.content.Context.DISPLAY_SERVICE)
            as android.hardware.display.DisplayManager
        displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {
                val gd = gameDisplay ?: return
                if (displayId == gd.displayId && isAdded) {
                    PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
                    if (showsDialog) dismissAllowingStateLoss()
                    else onClose?.invoke()
                }
            }
            override fun onDisplayChanged(displayId: Int) {
                val gd = gameDisplay ?: return
                if (displayId != gd.displayId) return
                val newRotation = gd.rotation
                if (newRotation != lastDisplayRotation) {
                    lastDisplayRotation = newRotation
                    adapter.submitList()
                }
            }
        }
        dm.registerDisplayListener(displayListener, null)

        // React to live mode changes while the sheet is visible
        com.playtranslate.CaptureService.instance?.liveModeState?.observe(viewLifecycleOwner) { live ->
            if (live) {
                PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
            } else {
                showSelectedOverlay()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        showSelectedOverlay()
    }

    /** App went to background — kill the overlay immediately so it doesn't get stuck. */
    override fun onStop() {
        PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
        displayListener?.let {
            val dm = requireContext().getSystemService(android.content.Context.DISPLAY_SERVICE)
                as android.hardware.display.DisplayManager
            dm.unregisterDisplayListener(it)
        }
        displayListener = null
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
                    workingList = prefs.getRegionList().toMutableList()
                    selectedId = prefs.selectedRegionId.ifEmpty { workingList.firstOrNull()?.id ?: "" }
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
                    workingList = prefs.getRegionList().toMutableList()
                    selectedId = prefs.selectedRegionId.ifEmpty { workingList.firstOrNull()?.id ?: "" }
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

    /** Reload the region list from prefs and refresh the UI. */
    fun refreshFromPrefs() {
        if (!isAdded || isDetached) return
        workingList = prefs.getRegionList().toMutableList()
        selectedId = prefs.selectedRegionId.ifEmpty { workingList.firstOrNull()?.id ?: "" }
        adapter.submitList()
        showSelectedOverlay()
    }

    // ── Overlay helpers ────────────────────────────────────────────────────

    private val isLive get() = com.playtranslate.CaptureService.instance?.liveModeState?.value == true

    private fun showSelectedOverlay() {
        if (isLive) return
        // Don't re-show the picker overlay while the region drag editor is active
        if (PlayTranslateAccessibilityService.instance?.isRegionEditorActive == true) return
        val display = gameDisplay ?: return
        val e = workingList.find { it.id == selectedId } ?: workingList.firstOrNull() ?: return
        PlayTranslateAccessibilityService.instance?.showRegionOverlay(display, e)
    }

    // ── RecyclerView Adapter ──────────────────────────────────────────────

    private inner class RegionAdapter : RecyclerView.Adapter<RegionAdapter.VH>() {

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val radio: RadioButton = itemView.findViewById(R.id.radioRegion)
            val preview: RegionPreviewView = itemView.findViewById(R.id.regionPreview)
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
            val ctx = holder.itemView.context
            val dp = ctx.resources.displayMetrics.density
            val radius = ctx.resources.getDimension(R.dimen.pt_radius)
            val count = workingList.size

            holder.label.text = entry.label
            val isSelected = workingList.getOrNull(position)?.id == selectedId
            holder.radio.isChecked = isSelected
            val accentC = ctx.themeColor(R.attr.ptAccent)
            val dividerColor = ctx.themeColor(R.attr.ptDivider)

            // Region preview thumbnail — swap physical dims based on rotation
            val display = gameDisplay
            if (display != null) {
                val pw = display.mode.physicalWidth.toFloat()
                val ph = display.mode.physicalHeight.toFloat()
                val rotated = display.rotation == android.view.Surface.ROTATION_90
                    || display.rotation == android.view.Surface.ROTATION_270
                val dw = if (rotated) ph else pw
                val dh = if (rotated) pw else ph
                holder.preview.setDisplayRatio(dw / dh)
            }
            holder.preview.surfaceColor = ctx.themeColor(R.attr.ptSurface)
            holder.preview.accentColor = accentC
            holder.preview.mutedColor = ctx.themeColor(R.attr.ptTextMuted)
            holder.preview.setRegion(entry.top, entry.bottom, entry.left, entry.right)
            holder.preview.setRegionSelected(isSelected)
            holder.radio.buttonTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accentC, dividerColor)
            )

            // Per-row rounded corners: first row gets top corners, last row gets bottom
            val cardColor = ctx.themeColor(R.attr.ptCard)
            val accent = ctx.themeColor(R.attr.ptAccent)
            // Blend 5% accent over card color (solid, no transparency)
            val alpha = 0.10f
            val selectedColor = android.graphics.Color.rgb(
                ((android.graphics.Color.red(accent) * alpha + android.graphics.Color.red(cardColor) * (1 - alpha))).toInt(),
                ((android.graphics.Color.green(accent) * alpha + android.graphics.Color.green(cardColor) * (1 - alpha))).toInt(),
                ((android.graphics.Color.blue(accent) * alpha + android.graphics.Color.blue(cardColor) * (1 - alpha))).toInt()
            )
            val fillColor = if (isSelected && !isEditMode) selectedColor else cardColor
            val strokeColor = ctx.themeColor(R.attr.ptDivider)

            val topRadius = if (position == 0) radius else 0f
            val bottomRadius = if (position == count - 1) radius else 0f

            holder.itemView.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(fillColor)
                cornerRadii = floatArrayOf(
                    topRadius, topRadius,     // top-left
                    topRadius, topRadius,     // top-right
                    bottomRadius, bottomRadius, // bottom-right
                    bottomRadius, bottomRadius  // bottom-left
                )
                setStroke((1 * dp).toInt(), strokeColor)
            }

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
                    if (!isLive) {
                        gameDisplay?.let { d -> PlayTranslateAccessibilityService.instance?.showRegionOverlay(d, e) }
                    }
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
