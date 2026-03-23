package com.playtranslate.ui

import android.content.DialogInterface
import android.graphics.Typeface
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.Prefs
import com.playtranslate.RegionEntry
import com.playtranslate.R
import com.playtranslate.fullScreenDialogTheme
import com.playtranslate.themeColor

class RegionPickerSheet : DialogFragment() {

    var onSaved: ((Int) -> Unit)? = null
    var onTranslateOnce: ((top: Float, bottom: Float, left: Float, right: Float, label: String) -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var gameDisplay: Display? = null

    private lateinit var prefs: Prefs
    private var workingList: MutableList<RegionEntry> = mutableListOf()
    private var selectedIndex = 0
    private var isEditMode = false

    private lateinit var listContainer: LinearLayout
    private lateinit var btnEdit: Button
    private lateinit var btnSave: Button
    private lateinit var dividerSave: View

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
        selectedIndex = prefs.captureRegionIndex.coerceIn(0, (workingList.size - 1).coerceAtLeast(0))

        listContainer = view.findViewById(R.id.regionListContainer)
        btnEdit       = view.findViewById(R.id.btnEditRegion)
        btnSave       = view.findViewById(R.id.btnSaveRegion)
        dividerSave   = view.findViewById(R.id.dividerSave)

        val noPreviewNotice = view.findViewById<View>(R.id.noPreviewNotice)
        if (PlayTranslateAccessibilityService.isEnabled) {
            noPreviewNotice.visibility = View.GONE
        } else {
            noPreviewNotice.visibility = View.VISIBLE
            noPreviewNotice.setOnClickListener {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        view.findViewById<View>(R.id.btnCloseRegion).setOnClickListener {
            PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
            if (showsDialog) dismiss() else onClose?.invoke()
        }

        btnEdit.setOnClickListener {
            if (isEditMode) exitEditMode() else enterEditMode()
        }

        btnSave.setOnClickListener {
            prefs.captureRegionIndex = selectedIndex
            PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
            onSaved?.invoke(selectedIndex)
            if (showsDialog) dismiss()
        }

        rebuildList()
        showOverlayForIndex(selectedIndex)
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
        btnSave.visibility = View.GONE
        dividerSave.visibility = View.GONE
        rebuildList()
    }

    private fun exitEditMode() {
        // Commit the reordered/deleted list and clamp the selected index
        prefs.setRegionList(workingList)
        selectedIndex = selectedIndex.coerceIn(0, (workingList.size - 1).coerceAtLeast(0))
        isEditMode = false
        btnEdit.text = getString(R.string.label_edit)
        btnSave.visibility = View.VISIBLE
        dividerSave.visibility = View.VISIBLE
        rebuildList()
        showOverlayForIndex(selectedIndex)
    }

    // ── List building ──────────────────────────────────────────────────────

    private fun rebuildList() {
        listContainer.removeAllViews()
        if (isEditMode) {
            workingList.forEachIndexed { i, entry -> listContainer.addView(makeEditRow(i, entry)) }
        } else {
            workingList.forEachIndexed { i, entry -> listContainer.addView(makeNormalRow(i, entry)) }
            listContainer.addView(makeAddFooter())
        }
    }

    private fun makeNormalRow(index: Int, entry: RegionEntry): View {
        val ctx = requireContext()
        val dp = ctx.resources.displayMetrics.density
        val isSelected = (index == selectedIndex)

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
            val attrs = intArrayOf(android.R.attr.selectableItemBackground)
            val ta = ctx.obtainStyledAttributes(attrs)
            background = ta.getDrawable(0)
            ta.recycle()
        }

        val rb = RadioButton(ctx).apply {
            isChecked = isSelected
            isClickable = false
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = (10 * dp).toInt() }
        }

        val tv = TextView(ctx).apply {
            text = entry.label
            textSize = 15f
            setTextColor(ctx.themeColor(R.attr.colorTextPrimary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        row.addView(rb)
        row.addView(tv)

        row.setOnClickListener {
            selectedIndex = index
            val e = workingList.getOrElse(index) { Prefs.DEFAULT_REGION_LIST[0] }
            PlayTranslateAccessibilityService.instance?.updateRegionOverlay(e.top, e.bottom, e.left, e.right)
            rebuildList()
        }

        return row
    }

    private fun makeEditRow(index: Int, entry: RegionEntry): View {
        val ctx = requireContext()
        val dp = ctx.resources.displayMetrics.density
        val padV = (8 * dp).toInt()
        val padH = (16 * dp).toInt()
        val btnSize = (36 * dp).toInt()

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padH, padV, padH, padV)
        }

        // Up / Down buttons stacked vertically
        val reorderCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(btnSize, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val btnUp = makeTextBtn(ctx, "▲", (11 * dp).toInt()).apply {
            isEnabled = index > 0
            alpha = if (index > 0) 1f else 0.3f
            setOnClickListener { moveUp(index) }
        }
        val btnDown = makeTextBtn(ctx, "▼", (11 * dp).toInt()).apply {
            isEnabled = index < workingList.lastIndex
            alpha = if (index < workingList.lastIndex) 1f else 0.3f
            setOnClickListener { moveDown(index) }
        }
        reorderCol.addView(btnUp)
        reorderCol.addView(btnDown)
        row.addView(reorderCol)

        // Label — tap to rename
        val tv = TextView(ctx).apply {
            text = entry.label
            textSize = 15f
            setTextColor(ctx.themeColor(R.attr.colorTextPrimary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                it.marginStart = (8 * dp).toInt()
                it.marginEnd = (8 * dp).toInt()
            }
            // Underline hint that it's tappable
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener { showRenameDialog(index) }
        }
        row.addView(tv)

        // Delete button
        val btnDel = makeTextBtn(ctx, "✕", (14 * dp).toInt()).apply {
            setTextColor(resources.getColor(R.color.status_error, null))
            setOnClickListener { deleteItem(index) }
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
        }
        row.addView(btnDel)

        return row
    }

    private fun makeAddFooter(): View {
        val ctx = requireContext()
        val dp = ctx.resources.displayMetrics.density

        val tv = TextView(ctx).apply {
            text = getString(R.string.label_add_custom_region)
            textSize = 14f
            setTextColor(ctx.themeColor(R.attr.colorAccentPrimary))
            typeface = Typeface.create(typeface, Typeface.BOLD)
            setPadding((16 * dp).toInt(), (18 * dp).toInt(), (16 * dp).toInt(), (18 * dp).toInt())
            val attrs = intArrayOf(android.R.attr.selectableItemBackground)
            val ta = ctx.obtainStyledAttributes(attrs)
            background = ta.getDrawable(0)
            ta.recycle()
            setOnClickListener {
                if (PlayTranslateAccessibilityService.isEnabled) {
                    openAddCustomSheet()
                } else {
                    showCustomRegionA11yDialog()
                }
            }
        }
        return tv
    }

    private fun makeTextBtn(ctx: android.content.Context, label: String, textSizePx: Int): TextView {
        val dp = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            text = label
            textSize = (textSizePx / dp)
            setTextColor(ctx.themeColor(R.attr.colorTextSecondary))
            gravity = Gravity.CENTER
            val pad = (8 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            val attrs = intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
            val ta = ctx.obtainStyledAttributes(attrs)
            background = ta.getDrawable(0)
            ta.recycle()
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────

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

    // ── Actions ────────────────────────────────────────────────────────────

    private fun moveUp(index: Int) {
        if (index <= 0) return
        val tmp = workingList[index]; workingList[index] = workingList[index - 1]; workingList[index - 1] = tmp
        if (selectedIndex == index) selectedIndex = index - 1
        else if (selectedIndex == index - 1) selectedIndex = index
        rebuildList()
    }

    private fun moveDown(index: Int) {
        if (index >= workingList.lastIndex) return
        val tmp = workingList[index]; workingList[index] = workingList[index + 1]; workingList[index + 1] = tmp
        if (selectedIndex == index) selectedIndex = index + 1
        else if (selectedIndex == index + 1) selectedIndex = index
        rebuildList()
    }

    private fun deleteItem(index: Int) {
        workingList.removeAt(index)
        if (workingList.isEmpty()) {
            workingList.addAll(Prefs.DEFAULT_REGION_LIST)
        }
        selectedIndex = selectedIndex.coerceIn(0, workingList.lastIndex)
        rebuildList()
    }

    private fun showRenameDialog(index: Int) {
        val ctx = requireContext()
        val et = EditText(ctx).apply {
            setText(workingList[index].label)
            selectAll()
            setPadding(
                (20 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt(),
                (20 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt()
            )
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.label_rename_region)
            .setView(et)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newLabel = et.text.toString().trim().ifEmpty { workingList[index].label }
                workingList[index] = workingList[index].copy(label = newLabel)
                rebuildList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openAddCustomSheet() {
        PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
        AddCustomRegionSheet().also { sheet ->
            sheet.gameDisplay = gameDisplay
            sheet.onRegionAdded = { newIndex ->
                prefs.captureRegionIndex = newIndex
                onSaved?.invoke(newIndex)
                PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
                dismissAllowingStateLoss()
            }
            sheet.onDismissed = {
                if (isAdded && !isDetached) {
                    rebuildList()
                    showOverlayForIndex(selectedIndex)
                }
            }
            sheet.onTranslateOnce = { top, bottom, left, right, label ->
                PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
                if (showsDialog) dismissAllowingStateLoss()
                onTranslateOnce?.invoke(top, bottom, left, right, label)
            }
        }.show(childFragmentManager, AddCustomRegionSheet.TAG)
    }

    // ── Overlay helpers ────────────────────────────────────────────────────

    private fun showOverlayForIndex(index: Int) {
        val display = gameDisplay ?: return
        val e = workingList.getOrElse(index) { Prefs.DEFAULT_REGION_LIST[0] }
        PlayTranslateAccessibilityService.instance?.showRegionOverlay(display, e.top, e.bottom, e.left, e.right)
    }

    companion object {
        const val TAG = "RegionPickerSheet"
    }
}
