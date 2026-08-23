package com.playtranslate.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.playtranslate.R
import com.playtranslate.applyAccentOverlay
import com.playtranslate.applyDialogEdgeToEdge
import com.playtranslate.fullScreenDialogTheme

/**
 * Full-screen card-type picker. The Default/Card Types list itself lives in
 * [AnkiCardTypePickerView] (shared with the floating workspace's page);
 * this shell keeps the dialog window, insets, and toolbar, and owns the
 * DialogFragment choreography for a non-basic model: dismiss this picker,
 * then push [AnkiFieldMappingDialog] on the PARENT fragment manager — that
 * dialog commits the model id + name + per-field mapping if the user Saves.
 */
class AnkiCardTypePickerDialog : DialogFragment() {

    var onCardTypePicked: ((modelId: Long, modelName: String) -> Unit)? = null

    private var mode: CardMode = CardMode.SENTENCE

    fun setMode(mode: CardMode) {
        this.mode = mode
    }

    override fun getTheme(): Int = fullScreenDialogTheme(requireContext())

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        applyAccentOverlay(dialog.context.theme, requireContext())
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_anki_card_type_picker, container, false)

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
        val toolbar = view.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { dismiss() }

        val container = view.findViewById<LinearLayout>(R.id.cardTypeListContainer)
        container.addView(
            AnkiCardTypePickerView(
                requireContext(),
                viewLifecycleOwner.lifecycleScope,
                isAlive = { isAdded },
                onCardTypePicked = { id, name ->
                    onCardTypePicked?.invoke(id, name)
                    dismiss()
                },
                openFieldMapping = { model ->
                    val mapping = AnkiFieldMappingDialog.newInstance(
                        modelId = model.id,
                        modelName = model.name,
                        fieldNames = model.fieldNames,
                        mode = mode,
                    )
                    mapping.onSaved = { id, name ->
                        onCardTypePicked?.invoke(id, name)
                    }
                    val fm = parentFragmentManager
                    dismiss()
                    mapping.show(fm, AnkiFieldMappingDialog.TAG)
                },
            ).build(container)
        )
    }

    companion object {
        const val TAG = "AnkiCardTypePickerDialog"

        fun newInstance(mode: CardMode): AnkiCardTypePickerDialog =
            AnkiCardTypePickerDialog().also { it.setMode(mode) }
    }
}
