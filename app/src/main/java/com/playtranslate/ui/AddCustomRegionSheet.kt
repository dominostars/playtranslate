package com.playtranslate.ui

import android.content.DialogInterface
import android.os.Bundle
import android.view.Display
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.fragment.app.DialogFragment
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.Prefs
import com.playtranslate.RegionEntry
import com.playtranslate.R
import com.playtranslate.applyAccentOverlay
import com.playtranslate.fullScreenDialogTheme

class AddCustomRegionSheet : DialogFragment() {

    var gameDisplay: Display? = null
    var onRegionAdded: ((RegionEntry) -> Unit)? = null
    var onRegionEdited: ((RegionEntry) -> Unit)? = null
    var onDismissed: (() -> Unit)? = null
    /** Invoked instead of [onDismissed] when "Translate Once" is tapped. */
    var onTranslateOnce: ((RegionEntry) -> Unit)? = null

    private var initRegionEntry: RegionEntry? = null
    private var editIndex: Int = -1

    private val isEditMode get() = initRegionEntry != null && editIndex >= 0

    /** Prepopulate the drag overlay with [region]'s bounds.
     *  Pass [editIndex] to enable edit mode (save in place instead of adding). */
    fun initRegion(region: RegionEntry, editIndex: Int = -1) {
        this.initRegionEntry = region
        this.editIndex = editIndex
    }

    private var topFraction    = 0.25f
    private var bottomFraction = 0.75f
    private var leftFraction   = 0.25f
    private var rightFraction  = 0.75f
    private var translateOnceRequested = false
    private var translateOnceLabel = "Custom Region"

    override fun getTheme(): Int = fullScreenDialogTheme(requireContext())

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        applyAccentOverlay(dialog.context.theme, requireContext())
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_add_custom_region, container, false)

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(R.style.AnimSlideBottom)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvTitle          = view.findViewById<android.widget.TextView>(R.id.tvCustomRegionTitle)
        val etName           = view.findViewById<EditText>(R.id.etRegionName)
        val btnSave          = view.findViewById<Button>(R.id.btnSaveCustomRegion)
        val btnClose         = view.findViewById<View>(R.id.btnCloseCustomRegion)
        val btnTranslateOnce = view.findViewById<View>(R.id.btnTranslateOnce)

        val init = initRegionEntry
        if (init != null) {
            topFraction = init.top
            bottomFraction = init.bottom
            leftFraction = init.left
            rightFraction = init.right
            if (isEditMode) {
                tvTitle.text = "Edit ${init.label}"
                etName.setText(init.label)
            }
        }

        gameDisplay?.let { display ->
            PlayTranslateAccessibilityService.instance?.showRegionDragOverlay(
                display, RegionEntry("", topFraction, bottomFraction, leftFraction, rightFraction)
            ) { region ->
                topFraction    = region.top
                bottomFraction = region.bottom
                leftFraction   = region.left
                rightFraction  = region.right
            }
        }

        btnSave.setOnClickListener {
            val label = etName.text.toString().trim().ifEmpty { "Custom Region" }
            val prefs = Prefs(requireContext())
            val list  = prefs.getRegionList().toMutableList()
            val existingId = initRegionEntry?.id
            if (isEditMode && editIndex in list.indices && existingId != null) {
                val updated = RegionEntry(label, topFraction, bottomFraction, leftFraction, rightFraction, id = existingId)
                list[editIndex] = updated
                prefs.setRegionList(list)
                onRegionEdited?.invoke(updated)
            } else {
                val newEntry = RegionEntry(label, topFraction, bottomFraction, leftFraction, rightFraction)
                list.add(newEntry)
                prefs.setRegionList(list)
                onRegionAdded?.invoke(newEntry)
            }
            PlayTranslateAccessibilityService.instance?.hideRegionDragOverlay()
            dismiss()
        }

        btnTranslateOnce.setOnClickListener {
            translateOnceRequested = true
            PlayTranslateAccessibilityService.instance?.hideRegionDragOverlay()
            dismiss()
        }

        btnClose.setOnClickListener {
            PlayTranslateAccessibilityService.instance?.hideRegionDragOverlay()
            dismiss()
        }
    }

    /** App went to background — kill the overlay immediately so it doesn't get stuck. */
    override fun onStop() {
        PlayTranslateAccessibilityService.instance?.hideRegionDragOverlay()
        super.onStop()
        dismissAllowingStateLoss()
    }

    override fun onDismiss(dialog: DialogInterface) {
        PlayTranslateAccessibilityService.instance?.hideRegionDragOverlay()
        if (translateOnceRequested) {
            onTranslateOnce?.invoke(RegionEntry(translateOnceLabel, topFraction, bottomFraction, leftFraction, rightFraction))
        } else {
            onDismissed?.invoke()
        }
        super.onDismiss(dialog)
    }

    companion object {
        const val TAG = "AddCustomRegionSheet"
    }
}
