package com.playtranslate.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.playtranslate.R
import com.playtranslate.applyTheme
import com.playtranslate.themeColor

/**
 * Full-screen model picker for the LLM backends. Mirrors
 * [AnkiDeckPickerDialog]'s grouped-card layout (the visual style the
 * user picked as the reference point for "selecting an item from a
 * list"), but lives as an Activity so the entry can be reached from
 * either the per-backend sub-screen or the main Settings card row
 * without nesting DialogFragments inside a DialogFragment.
 *
 * Selection semantics: tapping a row writes the choice straight to
 * prefs and finishes. The toolbar back button finishes without
 * writing. There is no Save button — by design — because the caller
 * (either [LlmBackendSettingsActivity] or the inline row in Settings)
 * expects the row to read its current value from prefs after the
 * activity returns. The SharedPreferences listener wired in
 * [SettingsBottomSheet] also picks the change up so the inline
 * "Model" cell updates on resume.
 */
class LlmModelPickerActivity : AppCompatActivity() {

    private lateinit var config: LlmBackendConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_llm_model_picker)

        val backendId = intent.getStringExtra(EXTRA_BACKEND_ID)
            ?: error("LlmModelPickerActivity launched without EXTRA_BACKEND_ID")
        config = LlmBackendConfigs.forId(this, backendId)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.llm_backend_model_label)
        toolbar.setNavigationOnClickListener { finish() }

        renderModelList(findViewById(R.id.modelListContainer))
    }

    private fun renderModelList(parent: LinearLayout) {
        parent.removeAllViews()

        val inflater = LayoutInflater.from(this)
        val card = inflater.inflate(R.layout.language_list_section, parent, false) as MaterialCardView
        val rowContainer = card.findViewById<LinearLayout>(R.id.sectionRows)
        val cardRadius = card.radius

        // Curated entries plus a final "Custom…" row. The custom entry
        // is special-cased on tap to open a text dialog; otherwise it's
        // styled identically to the other rows. The current model is
        // matched against the curated list to decide whether to mark a
        // curated row or the "Custom…" row as selected.
        val curated = config.availableModels
        val currentModel = config.getModel()
        val isCustomCurrent = currentModel !in curated
        val customLabel = getString(R.string.llm_backend_model_custom_entry)

        curated.forEachIndexed { idx, model ->
            if (idx > 0) rowContainer.addView(insetDivider(rowContainer))
            val topRadius = if (idx == 0) cardRadius else 0f
            // Custom is the last row, not this curated one, so bottomRadius=0
            // for all curated entries.
            val bottomRadius = 0f
            rowContainer.addView(
                buildModelRow(
                    container = rowContainer,
                    label = model,
                    detail = null,
                    isSelected = model == currentModel,
                    topRadius = topRadius,
                    bottomRadius = bottomRadius,
                    onTap = {
                        config.setModel(model)
                        finish()
                    },
                )
            )
        }

        // Custom… row — separator above + bottom corner radius.
        rowContainer.addView(insetDivider(rowContainer))
        rowContainer.addView(
            buildModelRow(
                container = rowContainer,
                label = customLabel,
                detail = if (isCustomCurrent) currentModel else null,
                isSelected = isCustomCurrent,
                topRadius = 0f,
                bottomRadius = cardRadius,
                onTap = { showCustomModelDialog(currentModel) },
            )
        )

        parent.addView(card)
    }

    private fun buildModelRow(
        container: ViewGroup,
        label: String,
        detail: String?,
        isSelected: Boolean,
        topRadius: Float,
        bottomRadius: Float,
        onTap: () -> Unit,
    ): View {
        val view = LayoutInflater.from(this)
            .inflate(R.layout.language_list_row, container, false)
        view.findViewById<TextView>(R.id.tvRowTitle).apply {
            text = label
            setTypeface(typeface, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        }
        // language_list_row exposes a secondary endonym TextView — repurpose
        // it as the "current custom model" hint under the Custom… label
        // when the user has previously typed a non-curated id.
        view.findViewById<TextView>(R.id.tvRowEndonym).apply {
            if (detail != null) {
                text = detail
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
        val trailing = view.findViewById<FrameLayout>(R.id.btnDelete)
        val trailingIcon = view.findViewById<ImageView>(R.id.ivDeleteIcon)
        if (isSelected) {
            trailing.visibility = View.VISIBLE
            trailingIcon.setImageResource(R.drawable.ic_check)
            trailingIcon.imageTintList = ColorStateList.valueOf(themeColor(R.attr.ptAccent))
            trailing.isClickable = false
            trailing.isFocusable = false
            trailing.foreground = null
            view.background = pickerSelectedRowBackground(topRadius, bottomRadius)
        }
        view.setOnClickListener { onTap() }
        return view
    }

    private fun showCustomModelDialog(currentModel: String) {
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(currentModel)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.llm_backend_model_custom_entry)
            .setView(input)
            .setPositiveButton(R.string.deepl_settings_save) { _, _ ->
                val typed = input.text.toString().trim()
                if (typed.isNotBlank()) {
                    config.setModel(typed)
                    finish()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun insetDivider(container: ViewGroup): View =
        LayoutInflater.from(this)
            .inflate(R.layout.settings_row_divider, container, false)

    companion object {
        const val EXTRA_BACKEND_ID = "backend_id"

        fun newIntent(context: android.content.Context, backendId: String): Intent =
            Intent(context, LlmModelPickerActivity::class.java)
                .putExtra(EXTRA_BACKEND_ID, backendId)
    }
}
