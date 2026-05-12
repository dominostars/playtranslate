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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.playtranslate.MainActivity
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.Prefs
import com.playtranslate.RegionEntry
import com.playtranslate.R
import com.playtranslate.applyAccentOverlay
import com.playtranslate.fullScreenDialogTheme
import com.playtranslate.themeColor

class RegionPickerSheet : DialogFragment() {

    var onSaved: (() -> Unit)? = null
    /** Region the user wants to one-shot, plus the display segment the
     *  picker is currently targeting. The display id needs to thread up
     *  to MainActivity so the override + capture both land on the
     *  segment the user was looking at — not [CaptureService.primaryGameDisplayId],
     *  which can be a different display in multi-display selections. */
    var onTranslateOnce: ((RegionEntry, Int) -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private lateinit var prefs: Prefs
    private var workingList: MutableList<RegionEntry> = mutableListOf()
    private var selectedId: String = ""
    private var isEditMode = false

    /** Displays the user has selected for capture (insertion-order from
     *  [Prefs.captureDisplayIds]), filtered to ids the system can still
     *  resolve. Drives the segmented control's contents. */
    private var selectedDisplayIds: List<Int> = emptyList()
    /** The display the segmented control currently has selected. All region
     *  reads/writes during this picker session key off this id. */
    private var activeDisplayId: Int = Display.DEFAULT_DISPLAY

    private lateinit var recyclerView: RecyclerView
    private lateinit var btnEdit: Button
    private lateinit var tvTitle: TextView
    private lateinit var displaySegmentedControl: FrameLayout
    private lateinit var adapter: RegionAdapter
    private var displayListener: android.hardware.display.DisplayManager.DisplayListener? = null
    private var lastDisplayRotation: Int = -1
    private var itemTouchHelper: ItemTouchHelper? = null

    /** Resolved on demand from [activeDisplayId]; null if the display has
     *  been hot-unplugged between the segment switch and the lookup. */
    private val gameDisplay: Display?
        get() = displayManager()?.getDisplay(activeDisplayId)

    private fun displayManager(): android.hardware.display.DisplayManager? =
        context?.getSystemService(android.content.Context.DISPLAY_SERVICE)
            as? android.hardware.display.DisplayManager

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

        // Resolve the user's selected-for-capture displays (Settings → display
        // picker writes [Prefs.captureDisplayIds]). Filter out stale ids the
        // system can no longer resolve so a hot-unplugged-but-persisted entry
        // doesn't leave the segmented control with a dead segment.
        val dm = displayManager()
        selectedDisplayIds = prefs.captureDisplayIds
            .filter { dm?.getDisplay(it) != null }
            .ifEmpty { listOf(Display.DEFAULT_DISPLAY) }

        // Default to the first selected display PlayTranslate is NOT
        // foregrounded on — the user is configuring the screen they're
        // looking at game content on, not the one currently holding the app.
        // MainActivity.foregroundDisplayId is set in onResume before
        // openRegionPickerInline runs, so reading it here is safe.
        activeDisplayId = selectedDisplayIds.firstOrNull { it != MainActivity.foregroundDisplayId }
            ?: selectedDisplayIds.first()

        selectedId = prefs.selectedRegionIdForDisplay(activeDisplayId)
            .ifEmpty { workingList.firstOrNull()?.id ?: "" }

        recyclerView = view.findViewById(R.id.regionRecyclerView)
        btnEdit      = view.findViewById(R.id.btnEditRegion)
        tvTitle      = view.findViewById(R.id.tvRegionPickerTitle)
        displaySegmentedControl = view.findViewById(R.id.displaySegmentedControl)
        setupDisplaySegmentedControl()

        val noPreviewNotice = view.findViewById<View>(R.id.noPreviewNotice)
        if (PlayTranslateAccessibilityService.isEnabled(requireContext())) {
            noPreviewNotice.visibility = View.GONE
        } else {
            noPreviewNotice.visibility = View.VISIBLE
            noPreviewNotice.setOnClickListener {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        view.findViewById<View>(R.id.btnAddRegion).setOnClickListener {
            if (isEditMode) exitEditMode()
            if (PlayTranslateAccessibilityService.isEnabled(requireContext())) {
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
        val systemDm = displayManager() ?: return
        displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {
                if (!isAdded || displayId !in selectedDisplayIds) return
                onSelectedDisplayUnplugged(displayId)
            }
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != activeDisplayId) return
                val newRotation = gameDisplay?.rotation ?: return
                if (newRotation != lastDisplayRotation) {
                    lastDisplayRotation = newRotation
                    adapter.submitList()
                }
            }
        }
        systemDm.registerDisplayListener(displayListener, null)

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
        applyTitleVisibility()
        adapter.submitList()
        itemTouchHelper?.attachToRecyclerView(recyclerView)
    }

    private fun exitEditMode() {
        prefs.setRegionList(workingList)
        isEditMode = false
        btnEdit.text = getString(R.string.label_edit)
        tvTitle.text = getString(R.string.label_select_region)
        applyTitleVisibility()
        itemTouchHelper?.attachToRecyclerView(null)
        adapter.submitList()
        showSelectedOverlay()
    }

    // ── Display segmented control ─────────────────────────────────────────

    /** Build per-display pill segments when the user has more than one
     *  display selected for capture, and toggle which of the title TextView
     *  vs. the pill toggle is visible. Re-callable so display-removal can
     *  rebuild against the new set. Uses the shared [buildPillToggle]
     *  helper for the same sliding-pill look as the Settings overlay-mode
     *  toggle. */
    private fun setupDisplaySegmentedControl() {
        if (selectedDisplayIds.size <= 1) {
            displaySegmentedControl.removeAllViews()
            applyTitleVisibility()
            return
        }
        buildPillToggle(
            container = displaySegmentedControl,
            options = selectedDisplayIds.map { id -> displayLabelFor(id) to id },
            selected = activeDisplayId,
            onSelect = { newId -> onActiveDisplayChanged(newId) },
        )
        applyTitleVisibility()
    }

    /** Apply the right title-area visibility for the current edit mode +
     *  display-count state. Multi-display non-edit shows the segmented
     *  control; everything else shows the TextView. */
    private fun applyTitleVisibility() {
        val multiDisplay = selectedDisplayIds.size > 1
        if (multiDisplay && !isEditMode) {
            tvTitle.visibility = View.GONE
            displaySegmentedControl.visibility = View.VISIBLE
        } else {
            tvTitle.visibility = View.VISIBLE
            displaySegmentedControl.visibility = View.GONE
        }
    }

    /** Switch the picker to a different selected display: reload that
     *  display's stored region selection, refresh thumbnails (display
     *  rotation/aspect can differ), and re-target the live preview rect. */
    private fun onActiveDisplayChanged(newId: Int) {
        if (newId == activeDisplayId) return
        // Drop the preview on the old display before drawing on the new
        // one so the picker's accessibility-overlay rect doesn't linger.
        PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
        activeDisplayId = newId
        selectedId = prefs.selectedRegionIdForDisplay(activeDisplayId)
            .ifEmpty { workingList.firstOrNull()?.id ?: "" }
        lastDisplayRotation = gameDisplay?.rotation ?: -1
        adapter.submitList()
        showSelectedOverlay()
    }

    /** A display the user had selected for capture went away. Drop it from
     *  our local segment list. If it was the active one, fall through to a
     *  surviving id; if no displays remain, dismiss the picker. */
    private fun onSelectedDisplayUnplugged(removedId: Int) {
        val remaining = selectedDisplayIds.filterNot { it == removedId }
        if (remaining.isEmpty()) {
            PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
            if (showsDialog) dismissAllowingStateLoss()
            else onClose?.invoke()
            return
        }
        selectedDisplayIds = remaining
        if (removedId == activeDisplayId) {
            val nextId = remaining.first()
            // Reset state and rebuild the segmented control around the new
            // active id; setupDisplaySegmentedControl() will check the
            // matching segment and applyTitleVisibility() handles the
            // single-display fallback (no segmented control at all).
            activeDisplayId = nextId
            selectedId = prefs.selectedRegionIdForDisplay(activeDisplayId)
                .ifEmpty { workingList.firstOrNull()?.id ?: "" }
            lastDisplayRotation = gameDisplay?.rotation ?: -1
            setupDisplaySegmentedControl()
            adapter.submitList()
            showSelectedOverlay()
        } else {
            // Active display unchanged — just rebuild the segmented control
            // so the unplugged segment disappears.
            setupDisplaySegmentedControl()
        }
    }

    /** User-facing label for a segmented-control button. Prefers the
     *  system-provided [Display.getName] (e.g. "Built-in Screen", "USB-C");
     *  falls back to "Display N" when the name is missing or duplicated
     *  across displays in this set. The pill TextView handles width-based
     *  ellipsization at render time, so we don't pre-truncate here — that
     *  would clip names like "Built-in Screen" even when the pill has room
     *  for the full string. */
    private fun displayLabelFor(displayId: Int): String {
        val display = displayManager()?.getDisplay(displayId)
        val rawName = display?.name?.trim().orEmpty()
        val nameIsDuplicated = rawName.isNotEmpty() && selectedDisplayIds.count { other ->
            other != displayId &&
                displayManager()?.getDisplay(other)?.name?.trim() == rawName
        } > 0
        if (rawName.isEmpty() || nameIsDuplicated) return "Display $displayId"
        return rawName
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
                onTranslateOnce?.invoke(region, activeDisplayId)
            }
            sheet.onDismissed = {
                if (isAdded && !isDetached) {
                    workingList = prefs.getRegionList().toMutableList()
                    selectedId = prefs.selectedRegionIdForDisplay(activeDisplayId)
                        .ifEmpty { workingList.firstOrNull()?.id ?: "" }
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
                prefs.setSelectedRegionIdForDisplay(activeDisplayId, newEntry.id)
                PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
                onSaved?.invoke()
                if (showsDialog) dismissAllowingStateLoss()
            }
            sheet.onDismissed = {
                if (isAdded && !isDetached) {
                    workingList = prefs.getRegionList().toMutableList()
                    selectedId = prefs.selectedRegionIdForDisplay(activeDisplayId)
                        .ifEmpty { workingList.firstOrNull()?.id ?: "" }
                    adapter.submitList()
                    showSelectedOverlay()
                }
            }
            sheet.onTranslateOnce = { region ->
                PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
                if (showsDialog) dismissAllowingStateLoss()
                onTranslateOnce?.invoke(region, activeDisplayId)
            }
        }.show(childFragmentManager, AddCustomRegionSheet.TAG)
    }

    /** Reload the region list from prefs and refresh the UI. */
    fun refreshFromPrefs() {
        if (!isAdded || isDetached) return
        workingList = prefs.getRegionList().toMutableList()
        selectedId = prefs.selectedRegionIdForDisplay(activeDisplayId)
            .ifEmpty { workingList.firstOrNull()?.id ?: "" }
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
                    prefs.setSelectedRegionIdForDisplay(activeDisplayId, e.id)
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
