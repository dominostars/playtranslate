package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import com.google.android.material.appbar.MaterialToolbar
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.themeColor
import com.playtranslate.translation.llm.LlmPromptTemplates
import com.playtranslate.translation.llm.PromptIssue
import com.playtranslate.translation.llm.PromptKind
import com.playtranslate.translation.llm.PromptValidation

/**
 * Editor for one user-editable LLM prompt template (Advanced LLM
 * Configuration). One Activity serves all three [PromptKind]s via
 * [EXTRA_KIND]: a multiline field prefilled with the *effective* raw
 * template (override or built-in default, `{tokens}` unsubstituted), a
 * keyword legend, a toolbar Reset action (refills the default without
 * persisting), and a Save button that runs [LlmPromptTemplates.validate] —
 * fatal issues block the save, advisory issues offer a warning-colored
 * "Save anyway". Leaving with unsaved edits asks before discarding;
 * nothing is ever persisted on back.
 */
class LlmPromptEditorActivity : SettingsSubPageActivity() {

    override val layoutResId = R.layout.activity_llm_prompt_editor

    private lateinit var kind: PromptKind
    private lateinit var etPrompt: EditText

    override fun onContentCreated(savedInstanceState: Bundle?) {
        kind = PromptKind.valueOf(
            intent.getStringExtra(EXTRA_KIND) ?: PromptKind.SYSTEM.name
        )
        etPrompt = findViewById(R.id.etPrompt)
        etPrompt.setText(LlmPromptTemplates.effectiveTemplate(kind))

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(titleRes(kind))
        // The base class wired toolbar-back straight to finish(); re-route
        // both it and the system back through the discard guard.
        toolbar.setNavigationOnClickListener { confirmDiscardOrFinish() }
        onBackPressedDispatcher.addCallback(this) { confirmDiscardOrFinish() }
        toolbar.inflateMenu(R.menu.menu_llm_prompt_editor)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId != R.id.action_reset) return@setOnMenuItemClickListener false
            etPrompt.setText(kind.default)
            true
        }

        findViewById<View>(R.id.headerKeywords)
            .findViewById<TextView>(R.id.tvGroupTitle).text =
            getString(R.string.llm_prompt_keywords_header)
        populateLegend(findViewById(R.id.legendContainer))

        findViewById<View>(R.id.btnSave).setOnClickListener { onSave() }
    }

    private fun titleRes(kind: PromptKind): Int = when (kind) {
        PromptKind.SYSTEM -> R.string.llm_prompt_row_system_title
        PromptKind.TRANSLATION -> R.string.llm_prompt_row_translation_title
        PromptKind.BATCH -> R.string.llm_prompt_row_batch_title
    }

    /** One row per keyword: the literal token (monospace) over its
     *  localized description — same title/subtitle shape as the settings
     *  rows, compacted. */
    private fun populateLegend(container: LinearLayout) {
        val hPad = resources.getDimensionPixelSize(R.dimen.pt_row_h_padding)
        val vPad = (6 * resources.displayMetrics.density).toInt()
        kind.keywords.forEach { kw ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(hPad, vPad, hPad, vPad)
            }
            row.addView(TextView(this).apply {
                text = kw.token
                typeface = Typeface.MONOSPACE
                textSize = 14f
                setTextColor(themeColor(R.attr.ptText))
            })
            row.addView(TextView(this).apply {
                text = getString(kw.descRes)
                textSize = 12f
                setTextColor(themeColor(R.attr.ptTextMuted))
            })
            container.addView(row)
        }
    }

    private fun hasUnsavedChanges(): Boolean =
        etPrompt.text.toString() != LlmPromptTemplates.effectiveTemplate(kind)

    private fun confirmDiscardOrFinish() {
        if (!hasUnsavedChanges()) {
            finish()
            return
        }
        OverlayAlert.Builder(this)
            .hideIcon()
            .setTitle(getString(R.string.llm_prompt_discard_title))
            .setMessage(getString(R.string.llm_prompt_discard_message))
            .addButton(
                getString(R.string.llm_prompt_discard_confirm),
                themeColor(R.attr.ptDanger),
                themeColor(R.attr.ptAccentOn),
            ) { finish() }
            .addCancelButton(getString(R.string.btn_cancel))
            .show()
    }

    private fun onSave() {
        val text = etPrompt.text.toString()
        val result = LlmPromptTemplates.validate(kind, text)
        when {
            result.fatal.isNotEmpty() -> showFatalAlert(result)
            result.advisory.isNotEmpty() -> showAdvisoryAlert(result, text)
            else -> persistAndFinish(text)
        }
    }

    private fun persistAndFinish(text: String) {
        kind.write(Prefs(this), LlmPromptTemplates.normalize(kind, text))
        finish()
    }

    /** Fatal problems — the prompt can't function. Lists everything found
     *  (advisory lines included, so one pass fixes it all); no save path. */
    private fun showFatalAlert(result: PromptValidation) {
        OverlayAlert.Builder(this)
            .hideIcon()
            .setTitle(getString(R.string.llm_prompt_invalid_title))
            .setMessage(issuesMessage(result.fatal + result.advisory))
            .addCancelButton(getString(R.string.btn_ok))
            .show()
    }

    /** Advisory-only problems — degraded but functional; warning-colored
     *  confirm bypasses. */
    private fun showAdvisoryAlert(result: PromptValidation, text: String) {
        OverlayAlert.Builder(this)
            .hideIcon()
            .setTitle(getString(R.string.llm_prompt_warning_title))
            .setMessage(issuesMessage(result.advisory))
            .addButton(
                getString(R.string.llm_prompt_save_anyway),
                themeColor(R.attr.ptWarning),
                themeColor(R.attr.ptAccentOn),
            ) { persistAndFinish(text) }
            .addCancelButton(getString(R.string.btn_cancel))
            .show()
    }

    private fun issuesMessage(issues: List<PromptIssue>): String =
        issues.joinToString("\n\n") { issue ->
            when (issue) {
                PromptIssue.Blank -> getString(R.string.llm_prompt_fatal_blank)
                PromptIssue.MissingText -> getString(R.string.llm_prompt_fatal_missing_text)
                PromptIssue.MissingStrings -> getString(R.string.llm_prompt_fatal_missing_strings)
                PromptIssue.MissingSourceRef -> getString(R.string.llm_prompt_advisory_missing_source)
                PromptIssue.MissingTargetRef -> getString(R.string.llm_prompt_advisory_missing_target)
                PromptIssue.MissingCount -> getString(R.string.llm_prompt_advisory_missing_count)
                is PromptIssue.ForeignToken ->
                    getString(R.string.llm_prompt_advisory_foreign_token, issue.token)
                PromptIssue.TooLong -> getString(R.string.llm_prompt_advisory_too_long)
            }
        }

    companion object {
        private const val EXTRA_KIND = "prompt_kind"

        fun intent(context: Context, kind: PromptKind): Intent =
            Intent(context, LlmPromptEditorActivity::class.java)
                .putExtra(EXTRA_KIND, kind.name)
    }
}
