package com.playtranslate.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.playtranslate.AnkiManager
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.applyAccentOverlay
import com.playtranslate.fullScreenDialogTheme
import com.playtranslate.themeColor

private const val TAG = "FieldMappingDialog"

/**
 * Per-field mapping editor. Shown after the user picks a non-Default
 * card type from [AnkiCardTypePickerDialog], or directly via the
 * send-time guard when the current model has no mapping configured.
 *
 * Toolbar shows the model name and a Save action. Body lists one row
 * per field of the chosen model: tapping a row opens a `PopupMenu`
 * anchored to the row with all [ContentSource] options. Save commits
 * `prefs.ankiModelId` / `ankiModelName` / `setAnkiFieldMapping(id, map)`.
 * Back / Cancel commits nothing — the card type selection reverts to
 * whatever was selected before the picker opened.
 */
class AnkiFieldMappingDialog : DialogFragment() {

    /** Fires when the user Saves. Not fired on Back / Cancel. */
    var onSaved: ((modelId: Long, modelName: String) -> Unit)? = null

    private lateinit var modelName: String
    private var modelId: Long = -1L
    private lateinit var fieldNames: List<String>
    private lateinit var mode: CardMode

    /** Mutable working copy. Initial state from saved prefs (if any) or
     *  template defaults; user edits write here until Save commits. */
    private val workingMapping = linkedMapOf<String, ContentSource>()

    override fun getTheme(): Int = fullScreenDialogTheme(requireContext())

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        applyAccentOverlay(dialog.context.theme, requireContext())
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_anki_field_mapping, container, false)

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(R.style.AnimSlideRight)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = arguments ?: run { dismiss(); return }
        modelId = args.getLong(ARG_MODEL_ID, -1L)
        modelName = args.getString(ARG_MODEL_NAME).orEmpty()
        fieldNames = args.getStringArray(ARG_FIELD_NAMES)?.toList().orEmpty()
        mode = CardMode.valueOf(args.getString(ARG_MODE) ?: CardMode.SENTENCE.name)

        val ctx = requireContext()
        val prefs = Prefs(ctx)

        // Initial state: saved mapping if any, else template defaults.
        val saved = prefs.getAnkiFieldMapping(modelId)
        Log.d(TAG, "onViewCreated: model='$modelName' id=$modelId " +
            "mode=$mode fieldNames=$fieldNames saved=$saved")
        val starter = if (saved.isNotEmpty()) {
            Log.d(TAG, "  using saved mapping ($saved)")
            saved
        } else {
            val model = AnkiManager.ModelInfo(modelId, modelName, fieldNames, 0, 0)
            val defaults = AnkiCardTypeMapper.defaultsForModel(model, mode)
            Log.d(TAG, "  no saved mapping; defaults from mapper=$defaults")
            defaults
        }
        // Initialize working map: every field gets an entry (NONE if
        // unmapped) so the dialog shows a complete view.
        fieldNames.forEach { fieldName ->
            workingMapping[fieldName] = starter[fieldName] ?: ContentSource.NONE
        }
        Log.d(TAG, "  workingMapping initialized: $workingMapping")

        val toolbar = view.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.title = ctx.getString(R.string.anki_field_mapping_title, modelName)
        toolbar.setNavigationOnClickListener { dismiss() }
        toolbar.inflateMenu(R.menu.menu_anki_field_mapping)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_save) {
                prefs.ankiModelId = modelId
                prefs.ankiModelName = modelName
                prefs.setAnkiFieldMapping(modelId, workingMapping)
                onSaved?.invoke(modelId, modelName)
                dismiss()
                true
            } else false
        }

        val container = view.findViewById<LinearLayout>(R.id.fieldMappingContainer)
        renderRows(container)
    }

    private fun renderRows(container: LinearLayout) {
        val ctx = requireContext()
        val inflater = LayoutInflater.from(ctx)
        val density = ctx.resources.displayMetrics.density

        fieldNames.forEachIndexed { idx, fieldName ->
            if (idx > 0) {
                // Inset divider between rows (matches settings card style).
                container.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).also { it.marginStart = (16 * density).toInt() }
                    setBackgroundColor(ctx.themeColor(R.attr.ptDivider))
                })
            }
            val row = inflater.inflate(R.layout.settings_row_value, container, false)
            val titleTv = row.findViewById<TextView>(R.id.tvRowTitle)
            val valueTv = row.findViewById<TextView>(R.id.tvRowValue)
            titleTv.text = fieldName
            val current = workingMapping[fieldName] ?: ContentSource.NONE
            valueTv.text = ctx.getString(current.labelRes)
            row.setOnClickListener {
                showSourcePopup(row) { picked ->
                    workingMapping[fieldName] = picked
                    valueTv.text = ctx.getString(picked.labelRes)
                }
            }
            container.addView(row)
        }
    }

    private fun showSourcePopup(
        anchor: View,
        onPicked: (ContentSource) -> Unit,
    ) {
        val ctx = requireContext()
        val popup = PopupMenu(ctx, anchor)
        val options = ContentSource.values()
        options.forEachIndexed { i, source ->
            popup.menu.add(0, i, i, ctx.getString(source.labelRes))
        }
        popup.setOnMenuItemClickListener { item ->
            val picked = options[item.itemId]
            onPicked(picked)
            true
        }
        popup.show()
    }

    companion object {
        const val TAG = "AnkiFieldMappingDialog"

        private const val ARG_MODEL_ID    = "model_id"
        private const val ARG_MODEL_NAME  = "model_name"
        private const val ARG_FIELD_NAMES = "field_names"
        private const val ARG_MODE        = "mode"

        fun newInstance(
            modelId: Long,
            modelName: String,
            fieldNames: List<String>,
            mode: CardMode,
        ): AnkiFieldMappingDialog = AnkiFieldMappingDialog().apply {
            arguments = Bundle().apply {
                putLong(ARG_MODEL_ID, modelId)
                putString(ARG_MODEL_NAME, modelName)
                putStringArray(ARG_FIELD_NAMES, fieldNames.toTypedArray())
                putString(ARG_MODE, mode.name)
            }
        }
    }
}
