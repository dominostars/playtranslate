package com.playtranslate.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
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
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.playtranslate.R
import com.playtranslate.applyEdgeToEdge
import com.playtranslate.applyTheme
import com.playtranslate.themeColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
        applyEdgeToEdge(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_llm_model_picker)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(sys.left, sys.top, sys.right, maxOf(sys.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        val backendId = intent.getStringExtra(EXTRA_BACKEND_ID)
            ?: error("LlmModelPickerActivity launched without EXTRA_BACKEND_ID")
        config = LlmBackendConfigs.forId(this, backendId)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.llm_backend_model_label)
        toolbar.setNavigationOnClickListener { finish() }

        val container = findViewById<LinearLayout>(R.id.modelListContainer)
        renderLoadingState(container)
        lifecycleScope.launch {
            val models = try {
                config.listModels()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "listModels failed for ${config.displayName}", e)
                emptyList()
            }
            renderModelList(container, models)
        }
    }

    private fun renderLoadingState(parent: LinearLayout) {
        parent.removeAllViews()
        val tv = TextView(this).apply {
            text = getString(R.string.llm_model_picker_loading)
            setTextColor(themeColor(R.attr.ptTextMuted))
            textSize = 14f
            setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        parent.addView(tv)
    }

    /** Render the model list. [fetched] is what the provider's /models
     *  endpoint returned, or empty on failure. Custom… is always
     *  appended as the escape hatch. If [fetched] is empty AND the
     *  current model is non-blank, surface it as a preselected "current"
     *  entry above Custom… so the user can still see what's saved. */
    private fun renderModelList(parent: LinearLayout, fetched: List<String>) {
        parent.removeAllViews()

        val inflater = LayoutInflater.from(this)
        val card = inflater.inflate(R.layout.language_list_section, parent, false) as MaterialCardView
        val rowContainer = card.findViewById<LinearLayout>(R.id.sectionRows)
        val cardRadius = card.radius

        val currentModel = config.getModel()
        val isCurrentInFetched = currentModel in fetched
        // Show a tiny inline-error TextView above the card if the fetch
        // failed (empty list). Custom… is always the escape hatch.
        if (fetched.isEmpty()) {
            val warn = TextView(this).apply {
                text = getString(R.string.llm_model_picker_fetch_failed)
                setTextColor(themeColor(R.attr.ptTextMuted))
                textSize = 13f
                setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
            }
            parent.addView(warn)
        }

        val customLabel = getString(R.string.llm_backend_model_custom_entry)
        val totalRows = fetched.size + 1 // +1 for Custom…
        val customIdx = fetched.size

        fetched.forEachIndexed { idx, model ->
            if (idx > 0) rowContainer.addView(insetDivider(rowContainer))
            val topRadius = if (idx == 0) cardRadius else 0f
            val bottomRadius = if (idx == totalRows - 1) cardRadius else 0f
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

        // Custom… row — separator above (unless it's the only row) +
        // bottom corner radius.
        if (fetched.isNotEmpty()) rowContainer.addView(insetDivider(rowContainer))
        val customTopRadius = if (customIdx == 0) cardRadius else 0f
        rowContainer.addView(
            buildModelRow(
                container = rowContainer,
                label = customLabel,
                // If the saved model isn't in the fetched list, show it
                // as the secondary line on the Custom… row so the user
                // can see what's currently active without scrolling.
                detail = if (!isCurrentInFetched && currentModel.isNotBlank()) currentModel else null,
                isSelected = !isCurrentInFetched,
                topRadius = customTopRadius,
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
        private const val TAG = "LlmModelPicker"
        const val EXTRA_BACKEND_ID = "backend_id"

        fun newIntent(context: android.content.Context, backendId: String): Intent =
            Intent(context, LlmModelPickerActivity::class.java)
                .putExtra(EXTRA_BACKEND_ID, backendId)
    }
}
