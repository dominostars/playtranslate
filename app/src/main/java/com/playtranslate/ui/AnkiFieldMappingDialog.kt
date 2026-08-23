package com.playtranslate.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import com.playtranslate.R
import com.playtranslate.applyAccentOverlay
import com.playtranslate.applyDialogEdgeToEdge
import com.playtranslate.fullScreenDialogTheme

/**
 * Per-field mapping editor. Shown after the user picks a non-Default card
 * type from [AnkiCardTypePickerDialog], or directly via the send-time guard
 * when the current model has no mapping configured. The rows and the
 * commit-on-Save live in [AnkiFieldMappingView] (shared with the floating
 * workspace's page); this shell keeps the dialog window, insets, toolbar
 * title, the Save footer wiring, and the content-source picker's
 * child-fragment presentation. Back / Cancel commits nothing — the card
 * type selection reverts to whatever was selected before the picker opened.
 */
class AnkiFieldMappingDialog : DialogFragment() {

    /** Fires when the user Saves. Not fired on Back / Cancel. */
    var onSaved: ((modelId: Long, modelName: String) -> Unit)? = null

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
            applyDialogEdgeToEdge(this, requireContext())
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(sys.left, sys.top, sys.right, maxOf(sys.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        val args = arguments ?: run { dismiss(); return }
        val modelId = args.getLong(ARG_MODEL_ID, -1L)
        val modelName = args.getString(ARG_MODEL_NAME).orEmpty()
        val fieldNames = args.getStringArray(ARG_FIELD_NAMES)?.toList().orEmpty()
        val mode = CardMode.valueOf(args.getString(ARG_MODE) ?: CardMode.SENTENCE.name)

        val ctx = requireContext()
        val toolbar = view.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.title = ctx.getString(R.string.anki_field_mapping_title, modelName)
        toolbar.setNavigationOnClickListener { dismiss() }

        val mappingView = AnkiFieldMappingView(
            ctx, modelId, modelName, fieldNames, mode,
            openSourcePicker = { fieldName, current, onPicked ->
                val picker = AnkiContentSourcePickerDialog.newInstance(fieldName, current)
                picker.onPicked = onPicked
                picker.show(childFragmentManager, AnkiContentSourcePickerDialog.TAG)
            },
            onSaved = { id, name ->
                onSaved?.invoke(id, name)
                dismiss()
            },
        )
        val container = view.findViewById<LinearLayout>(R.id.fieldMappingContainer)
        container.addView(mappingView.build(container))
        view.findViewById<FrameLayout>(R.id.btnSaveMapping).setOnClickListener {
            mappingView.save()
        }
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
