package com.playtranslate.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
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
 * Full-screen picker for the user's AnkiDroid note types ("card types"
 * in our UI). Shows a synthetic "Default (PlayTranslate)" row at the
 * top — picking it commits `ankiModelId = -1L` and dismisses. Any
 * other row dismisses the picker and opens [AnkiFieldMappingDialog]
 * for the chosen model; the mapping dialog is responsible for
 * committing the model id + name + per-field mapping if the user saves.
 *
 * Slides in from the right like [AnkiDeckPickerDialog].
 */
class AnkiCardTypePickerDialog : DialogFragment() {

    /**
     * Fires after the picker resolves the user's intent — either
     * after picking "Default" (no mapping dialog) or after the mapping
     * dialog Saves. NOT fired when the mapping dialog is cancelled.
     */
    var onCardTypePicked: ((modelId: Long, modelName: String) -> Unit)? = null

    /**
     * The CardMode in which the picker was opened — passed through to
     * the mapping dialog so Basic-shape templates get mode-appropriate
     * defaults (Front=EXPRESSION on word mode, Front=SENTENCE on
     * sentence mode). Defaults to SENTENCE since that's the dominant
     * mining mode; the Word sheet overrides via setMode().
     */
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
            renderRows(container, models)
        }
    }

    private fun renderRows(container: LinearLayout, models: List<AnkiManager.ModelInfo>) {
        val ctx = requireContext()
        val prefs = Prefs(ctx)
        val density = resources.displayMetrics.density

        // Synthetic "Default (PlayTranslate)" row.
        container.addView(buildRow(
            title = ctx.getString(R.string.anki_card_type_row_empty),
            subtitle = null,
            isSelected = prefs.ankiModelId == -1L,
            onClick = {
                prefs.ankiModelId = -1L
                prefs.ankiModelName = ""
                onCardTypePicked?.invoke(-1L, "")
                dismiss()
            },
        ))

        if (models.isEmpty()) {
            // Default row remains; show a muted empty state below.
            container.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.anki_card_type_no_models)
                setTextColor(ctx.themeColor(R.attr.ptTextMuted))
                textSize = 14f
                setPadding(
                    (16 * density).toInt(), (16 * density).toInt(),
                    (16 * density).toInt(), 0,
                )
            })
            return
        }

        models.forEach { model ->
            // Divider matches AnkiDeckPickerDialog spacing.
            container.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).also { it.marginStart = (16 * density).toInt() }
                setBackgroundColor(ctx.themeColor(R.attr.ptDivider))
            })
            container.addView(buildRow(
                title = model.name,
                subtitle = model.fieldNames.joinToString(" · "),
                isSelected = prefs.ankiModelId == model.id,
                onClick = {
                    // Hand off to the mapping dialog. Picker dismisses
                    // here; the mapping dialog will commit prefs if the
                    // user Saves.
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
            ))
        }
    }

    private fun buildRow(
        title: String,
        subtitle: String?,
        isSelected: Boolean,
        onClick: () -> Unit,
    ): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (16 * density).toInt(), (14 * density).toInt(),
                (16 * density).toInt(), (14 * density).toInt(),
            )
            minimumHeight = (56 * density).toInt()
            isClickable = true
            isFocusable = true
            background = android.util.TypedValue().let { tv ->
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                ctx.getDrawable(tv.resourceId)
            }
            addView(TextView(ctx).apply {
                text = title
                textSize = 15f
                setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(ctx.themeColor(
                    if (isSelected) R.attr.ptAccent else R.attr.ptText
                ))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (subtitle != null) {
                addView(TextView(ctx).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(ctx.themeColor(R.attr.ptTextMuted))
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, (2 * density).toInt(), 0, 0)
                })
            }
            setOnClickListener { onClick() }
        }
    }

    companion object {
        const val TAG = "AnkiCardTypePickerDialog"

        fun newInstance(mode: CardMode): AnkiCardTypePickerDialog =
            AnkiCardTypePickerDialog().also { it.setMode(mode) }
    }
}
