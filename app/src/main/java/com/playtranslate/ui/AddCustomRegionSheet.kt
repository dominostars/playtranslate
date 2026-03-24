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
import com.playtranslate.fullScreenDialogTheme

class AddCustomRegionSheet : DialogFragment() {

    var gameDisplay: Display? = null
    var onRegionAdded: ((newIndex: Int) -> Unit)? = null
    var onRegionEdited: ((editIndex: Int) -> Unit)? = null
    var onDismissed: (() -> Unit)? = null
    /** Invoked instead of [onDismissed] when "Translate Once" is tapped. */
    var onTranslateOnce: ((RegionEntry) -> Unit)? = null

    /** Non-null to enable edit mode: the region to edit. */
    var editRegion: RegionEntry? = null
    /** Index into the saved region list (for updating in place). */
    var editIndex: Int = -1

    private val isEditMode get() = editRegion != null

    private var topFraction    = 0.25f
    private var bottomFraction = 0.75f
    private var leftFraction   = 0.25f
    private var rightFraction  = 0.75f
    private var translateOnceRequested = false
    private var translateOnceLabel = "Custom Region"

    override fun getTheme(): Int = fullScreenDialogTheme(requireContext())

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

        val edit = editRegion
        if (edit != null) {
            topFraction = edit.top
            bottomFraction = edit.bottom
            leftFraction = edit.left
            rightFraction = edit.right
            tvTitle.text = "Edit ${edit.label}"
            etName.setText(edit.label)
            btnTranslateOnce.visibility = View.GONE
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
            if (isEditMode && editIndex in list.indices) {
                list[editIndex] = RegionEntry(label, topFraction, bottomFraction, leftFraction, rightFraction)
                prefs.setRegionList(list)
                onRegionEdited?.invoke(editIndex)
            } else {
                list.add(RegionEntry(label, topFraction, bottomFraction, leftFraction, rightFraction))
                prefs.setRegionList(list)
                onRegionAdded?.invoke(list.lastIndex)
            }
            PlayTranslateAccessibilityService.instance?.hideRegionDragOverlay()
            dismiss()
        }

        btnTranslateOnce.setOnClickListener {
            translateOnceRequested = true
            translateOnceLabel = etName.text.toString().trim().ifEmpty { "Custom Region" }
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
