package com.playtranslate.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import com.playtranslate.R
import com.playtranslate.applyAccentOverlay
import com.playtranslate.applyDialogEdgeToEdge
import com.playtranslate.fullScreenDialogTheme

/**
 * Full-screen picker for a single field's [ContentSource]. Opened from
 * [AnkiFieldMappingDialog] when the user taps a row to change what content
 * fills that field. The option list itself lives in
 * [AnkiContentSourcePickerView] (shared with the floating workspace's
 * page); this shell keeps the dialog window, insets, and toolbar.
 *
 * Tapping any row dismisses the picker and invokes [onPicked] with the
 * selected source. Tapping back without picking is a no-op (the mapping
 * surface keeps the prior selection).
 */
class AnkiContentSourcePickerDialog : DialogFragment() {

    var onPicked: ((ContentSource) -> Unit)? = null

    private lateinit var fieldName: String
    private lateinit var current: ContentSource

    override fun getTheme(): Int = fullScreenDialogTheme(requireContext())

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        applyAccentOverlay(dialog.context.theme, requireContext())
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_anki_content_source_picker, container, false)

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
        fieldName = args.getString(ARG_FIELD_NAME).orEmpty()
        current = ContentSource.values()
            .firstOrNull { it.name == args.getString(ARG_CURRENT) }
            ?: ContentSource.NONE

        val ctx = requireContext()
        val toolbar = view.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.title = ctx.getString(R.string.anki_content_source_pick_title, fieldName)
        toolbar.setNavigationOnClickListener { dismiss() }

        val container = view.findViewById<LinearLayout>(R.id.contentSourceListContainer)
        container.addView(
            AnkiContentSourcePickerView(ctx, current) { source ->
                onPicked?.invoke(source)
                dismiss()
            }.build(container)
        )
    }

    companion object {
        const val TAG = "AnkiContentSourcePickerDialog"

        private const val ARG_FIELD_NAME = "field_name"
        private const val ARG_CURRENT    = "current"

        fun newInstance(
            fieldName: String,
            current: ContentSource,
        ): AnkiContentSourcePickerDialog = AnkiContentSourcePickerDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_FIELD_NAME, fieldName)
                putString(ARG_CURRENT, current.name)
            }
        }
    }
}
