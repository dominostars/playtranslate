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
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.playtranslate.AnkiManager
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.applyAccentOverlay
import com.playtranslate.fullScreenDialogTheme
import com.playtranslate.themeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen card-type picker. Layout mirrors LanguageSetupActivity:
 * grouped-card sections with uppercase headers, inset dividers between
 * rows, accent-tinted background on the currently-selected row.
 *
 * Sections:
 *  - "Default" — synthetic "Default (PlayTranslate)" row (modelId = -1L)
 *  - "Card Types" — the user's AnkiDroid note types
 *
 * Tapping the Default row commits `ankiModelId = -1L` and dismisses.
 * Tapping any other row dismisses the picker and opens
 * [AnkiFieldMappingDialog]; that dialog is responsible for committing
 * the model id + name + per-field mapping if the user Saves.
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
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val toolbar = view.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { dismiss() }

        val container = view.findViewById<LinearLayout>(R.id.cardTypeListContainer)
        val ctx = requireContext()
        val density = resources.displayMetrics.density

        container.addView(TextView(ctx).apply {
            text = "Loading card types…"
            setTextColor(ctx.themeColor(R.attr.ptTextMuted))
            textSize = 14f
            setPadding(0, (16 * density).toInt(), 0, 0)
        })

        viewLifecycleOwner.lifecycleScope.launch {
            val models = withContext(Dispatchers.IO) { AnkiManager(ctx).getModels() }
            if (!isAdded) return@launch
            container.removeAllViews()
            render(container, models)
        }
    }

    private fun render(container: LinearLayout, models: List<AnkiManager.ModelInfo>) {
        val ctx = requireContext()
        val prefs = Prefs(ctx)

        // Section 1: Default (PlayTranslate) — always shown.
        renderSection(
            parent = container,
            title = ctx.getString(R.string.anki_card_type_section_default),
            rows = listOf(
                CardTypeRow(
                    title = ctx.getString(R.string.anki_card_type_row_empty),
                    subtitle = null,
                    isSelected = prefs.ankiModelId == -1L,
                    onClick = {
                        prefs.ankiModelId = -1L
                        prefs.ankiModelName = ""
                        onCardTypePicked?.invoke(-1L, "")
                        dismiss()
                    },
                )
            ),
        )

        // Section 2: Card Types from AnkiDroid — or empty-state caption
        // if AnkiDroid has none.
        if (models.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.anki_card_type_no_models)
                setTextColor(ctx.themeColor(R.attr.ptTextMuted))
                textSize = 14f
                val density = resources.displayMetrics.density
                setPadding(
                    (4 * density).toInt(), (16 * density).toInt(),
                    (4 * density).toInt(), 0,
                )
            })
            return
        }
        renderSection(
            parent = container,
            title = ctx.getString(R.string.anki_card_type_section_custom),
            rows = models.map { model ->
                CardTypeRow(
                    title = model.name,
                    subtitle = model.fieldNames.joinToString(" · "),
                    isSelected = prefs.ankiModelId == model.id,
                    onClick = {
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
                )
            },
        )
    }

    private data class CardTypeRow(
        val title: String,
        val subtitle: String?,
        val isSelected: Boolean,
        val onClick: () -> Unit,
    )

    private fun renderSection(
        parent: LinearLayout,
        title: String,
        rows: List<CardTypeRow>,
    ) {
        if (rows.isEmpty()) return
        val ctx = requireContext()
        val inflater = LayoutInflater.from(ctx)

        val header = inflater.inflate(R.layout.settings_group_header, parent, false)
        header.findViewById<TextView>(R.id.tvGroupTitle).text = title.uppercase()
        parent.addView(header)

        val card = inflater.inflate(R.layout.language_list_section, parent, false) as MaterialCardView
        val rowContainer = card.findViewById<LinearLayout>(R.id.sectionRows)
        val cardRadius = card.radius
        val lastIdx = rows.lastIndex
        rows.forEachIndexed { idx, row ->
            if (idx > 0) rowContainer.addView(insetDivider(rowContainer))
            val topRadius = if (idx == 0) cardRadius else 0f
            val bottomRadius = if (idx == lastIdx) cardRadius else 0f
            rowContainer.addView(buildRow(rowContainer, row, topRadius, bottomRadius))
        }
        parent.addView(card)
    }

    private fun buildRow(
        container: ViewGroup,
        row: CardTypeRow,
        topCornerRadius: Float,
        bottomCornerRadius: Float,
    ): View {
        val ctx = requireContext()
        val view = LayoutInflater.from(ctx)
            .inflate(R.layout.anki_card_type_picker_row, container, false)
        view.findViewById<TextView>(R.id.tvRowTitle).apply {
            text = row.title
            setTypeface(typeface, if (row.isSelected) Typeface.BOLD else Typeface.NORMAL)
        }
        val subtitleTv = view.findViewById<TextView>(R.id.tvRowSubtitle)
        if (row.subtitle.isNullOrEmpty()) {
            subtitleTv.visibility = View.GONE
        } else {
            subtitleTv.text = row.subtitle
            subtitleTv.visibility = View.VISIBLE
        }
        val check = view.findViewById<ImageView>(R.id.ivSelectedCheck)
        check.visibility = if (row.isSelected) View.VISIBLE else View.GONE
        if (row.isSelected) {
            view.background = ctx.pickerSelectedRowBackground(topCornerRadius, bottomCornerRadius)
        }
        view.setOnClickListener { row.onClick() }
        return view
    }

    private fun insetDivider(container: ViewGroup): View =
        LayoutInflater.from(requireContext())
            .inflate(R.layout.settings_row_divider, container, false)

    companion object {
        const val TAG = "AnkiCardTypePickerDialog"

        fun newInstance(mode: CardMode): AnkiCardTypePickerDialog =
            AnkiCardTypePickerDialog().also { it.setMode(mode) }
    }
}
