package com.playtranslate.ui

import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.card.MaterialCardView
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

        val container = view.findViewById<LinearLayout>(R.id.fieldMappingContainer)
        renderRows(container)

        view.findViewById<FrameLayout>(R.id.btnSaveMapping).setOnClickListener {
            prefs.ankiModelId = modelId
            prefs.ankiModelName = modelName
            prefs.setAnkiFieldMapping(modelId, workingMapping)
            onSaved?.invoke(modelId, modelName)
            dismiss()
        }
    }

    private fun renderRows(container: LinearLayout) {
        if (fieldNames.isEmpty()) return
        val ctx = requireContext()
        val inflater = LayoutInflater.from(ctx)

        // Group all fields inside a single MaterialCardView, with inset
        // dividers between rows — same grouped-card look as the Anki
        // section in Settings and the Card Type picker. Reuses the
        // shared `language_list_section` wrapper.
        val card = inflater.inflate(R.layout.language_list_section, container, false) as MaterialCardView
        val rowContainer = card.findViewById<LinearLayout>(R.id.sectionRows)

        fieldNames.forEachIndexed { idx, fieldName ->
            if (idx > 0) {
                rowContainer.addView(
                    inflater.inflate(R.layout.settings_row_divider, rowContainer, false)
                )
            }
            val row = inflater.inflate(R.layout.settings_row_value, rowContainer, false)
            val titleTv = row.findViewById<TextView>(R.id.tvRowTitle)
            val valueTv = row.findViewById<TextView>(R.id.tvRowValue)
            titleTv.text = fieldName
            val current = workingMapping[fieldName] ?: ContentSource.NONE
            applyValueStyle(valueTv, current)
            row.setOnClickListener {
                showSourcePopup(row) { picked ->
                    workingMapping[fieldName] = picked
                    applyValueStyle(valueTv, picked)
                }
            }
            rowContainer.addView(row)
        }
        container.addView(card)
    }

    /**
     * Renders the row's value text. NONE gets the same italic +
     * `ptTextHint` treatment the offline-translation cells use for
     * their neutral-tone secondary subtitle — visually marks the
     * absence of a mapping without making it disappear. Any other
     * source uses the default RowValue color (`ptTextMuted`) with
     * upright type. Pass null for the family in setTypeface so only
     * the style flag toggles; otherwise platforms can cache italic
     * state on the resolved typeface.
     */
    private fun applyValueStyle(tv: TextView, source: ContentSource) {
        val ctx = requireContext()
        tv.text = ctx.getString(source.labelRes)
        if (source == ContentSource.NONE) {
            tv.setTypeface(null, Typeface.ITALIC)
            tv.setTextColor(ctx.themeColor(R.attr.ptTextHint))
        } else {
            tv.setTypeface(null, Typeface.NORMAL)
            tv.setTextColor(ctx.themeColor(R.attr.ptTextMuted))
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
