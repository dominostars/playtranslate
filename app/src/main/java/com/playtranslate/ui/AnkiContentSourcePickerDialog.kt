package com.playtranslate.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.card.MaterialCardView
import com.playtranslate.R
import com.playtranslate.applyAccentOverlay
import com.playtranslate.fullScreenDialogTheme
import com.playtranslate.themeColor

/**
 * Full-screen picker for a single field's [ContentSource]. Opened from
 * [AnkiFieldMappingDialog] when the user taps a row to change what
 * content fills that field. Same grouped-card surface as
 * [AnkiCardTypePickerDialog], split into two sections by
 * [ContentSource.Kind]:
 *  - CONTENT (NONE + the substantive content sources, including the
 *    Sentence/Expression furigana-bracket variants — those appear
 *    adjacent to their plain counterparts so users see "Sentence" and
 *    "Sentence with furigana (Migaku)" as a contiguous group instead
 *    of scattered across the picker)
 *  - CARD TYPE FLAG (mode-aware "x"/"" markers for Mustache section
 *    gates like Migaku's `Is Vocabulary Card` or Lapis's
 *    `IsSentenceCard`)
 * The currently-mapped source is highlighted (accent background +
 * bold title + trailing checkmark).
 *
 * Tapping any row dismisses the picker and invokes [onPicked] with the
 * selected source. Tapping back without picking is a no-op (the
 * mapping dialog keeps the prior selection).
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
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
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
        val content = ContentSource.values().filter { it.kind == ContentSource.Kind.CONTENT }
        val flags = ContentSource.values().filter { it.kind == ContentSource.Kind.FLAG }
        renderSection(container, R.string.anki_content_section_content, content)
        renderSection(container, R.string.anki_content_section_flag, flags)
    }

    private fun renderSection(
        parent: LinearLayout,
        @androidx.annotation.StringRes titleRes: Int,
        options: List<ContentSource>,
    ) {
        if (options.isEmpty()) return
        val ctx = requireContext()
        val inflater = LayoutInflater.from(ctx)

        val header = inflater.inflate(R.layout.settings_group_header, parent, false)
        header.findViewById<TextView>(R.id.tvGroupTitle).text =
            ctx.getString(titleRes).uppercase()
        parent.addView(header)

        val card = inflater.inflate(R.layout.language_list_section, parent, false) as MaterialCardView
        val rowContainer = card.findViewById<LinearLayout>(R.id.sectionRows)
        val cardRadius = card.radius
        val lastIdx = options.lastIndex
        options.forEachIndexed { idx, source ->
            if (idx > 0) {
                rowContainer.addView(
                    inflater.inflate(R.layout.settings_row_divider, rowContainer, false)
                )
            }
            val topRadius = if (idx == 0) cardRadius else 0f
            val bottomRadius = if (idx == lastIdx) cardRadius else 0f
            rowContainer.addView(buildRow(rowContainer, source, topRadius, bottomRadius))
        }
        parent.addView(card)
    }

    private fun buildRow(
        container: ViewGroup,
        source: ContentSource,
        topCornerRadius: Float,
        bottomCornerRadius: Float,
    ): View {
        val ctx = requireContext()
        val isSelected = source == current
        // language_list_row gives us the title + trailing slot + correct
        // ripple-on-foreground. Reuses the same row component the
        // language picker / deck picker use so the visual rhythm stays
        // consistent across all picker surfaces.
        val view = LayoutInflater.from(ctx)
            .inflate(R.layout.language_list_row, container, false)
        val titleTv = view.findViewById<TextView>(R.id.tvRowTitle)
        titleTv.text = ctx.getString(source.labelRes)
        if (source == ContentSource.NONE) {
            // Match the "Empty" label styling on the mapping screen —
            // italic + hint color — so the visual identity of NONE is
            // consistent whether the user sees it as a row value on the
            // mapping screen or as an option here.
            titleTv.setTypeface(null, Typeface.ITALIC)
            if (!isSelected) {
                titleTv.setTextColor(ctx.themeColor(R.attr.ptTextHint))
            }
        } else {
            titleTv.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        }
        // Checkmark in the language_list_row's repurposed trailing slot.
        val trailing = view.findViewById<android.widget.FrameLayout>(R.id.btnDelete)
        val trailingIcon = view.findViewById<ImageView>(R.id.ivDeleteIcon)
        if (isSelected) {
            trailing.visibility = View.VISIBLE
            trailingIcon.setImageResource(R.drawable.ic_check)
            trailingIcon.imageTintList =
                android.content.res.ColorStateList.valueOf(ctx.themeColor(R.attr.ptAccent))
            trailing.isClickable = false
            trailing.isFocusable = false
            trailing.foreground = null
            view.background = ctx.pickerSelectedRowBackground(topCornerRadius, bottomCornerRadius)
        }
        view.setOnClickListener {
            onPicked?.invoke(source)
            dismiss()
        }
        return view
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
