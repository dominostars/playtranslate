package com.playtranslate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.playtranslate.PlayTranslateApplication
import com.playtranslate.R
import com.playtranslate.applyTheme
import com.playtranslate.translation.KeyStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Generic settings sub-screen for the OpenAI- and Gemini-style LLM backends.
 *
 * Routes through [LlmBackendConfig] so the activity itself stays
 * provider-agnostic — adding a third backend (Anthropic, OpenRouter as a
 * first-class entry, etc.) is one [LlmBackendConfigs.forId] branch plus
 * the backend class.
 *
 * UX contract mirrors [DeepLSettingsActivity]:
 *  - Prepopulates from prefs on entry.
 *  - The toolbar X discards in-progress edits.
 *  - Save persists key / model / base URL and flips `enabled` to true iff
 *    the key is non-blank. The pref change drives the row's switch back in
 *    Settings via the SharedPreferences listener.
 *
 * Special handling:
 *  - Base URL field is GONE when [LlmBackendConfig.allowsBaseUrl] is false.
 *  - Save rejects a blank / non-`https://` base URL with an inline error
 *    (loopback addresses get an `http://` pass for LM Studio).
 *  - When the provider supports it and the base URL is the default, the
 *    activity fires a non-blocking key-validation ping post-save and
 *    surfaces [KeyStatus.Invalid] as a Toast.
 */
class LlmBackendSettingsActivity : AppCompatActivity() {

    private lateinit var config: LlmBackendConfig

    /** Pending model selection. The picker writes here instead of straight
     *  to prefs so the toolbar X (discard) leaves the previously-saved
     *  model untouched — [onSave] flushes this to prefs. */
    private var pendingModel: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_llm_backend_settings)

        val backendId = intent.getStringExtra(EXTRA_BACKEND_ID)
            ?: error("LlmBackendSettingsActivity launched without EXTRA_BACKEND_ID")
        config = LlmBackendConfigs.forId(this, backendId)
        pendingModel = config.getModel()

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(config.titleStringRes)
        toolbar.setNavigationOnClickListener { finish() }

        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        etApiKey.hint = config.keyHint
        etApiKey.setText(config.getKey())
        etApiKey.setSelection(etApiKey.text.length)

        wireBaseUrlSection(findViewById(R.id.sectionBaseUrl))
        wireModelRow(findViewById(R.id.rowModel))
        wireGetKeyLink(findViewById(R.id.rowGetKeyLink))
        wireUsageRow(findViewById(R.id.rowTodayUsage))

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            onSave(etApiKey)
        }
    }

    private fun wireBaseUrlSection(section: View) {
        if (!config.allowsBaseUrl) {
            section.visibility = View.GONE
            findViewById<View>(R.id.dividerBaseUrl)?.visibility = View.GONE
            return
        }
        section.visibility = View.VISIBLE
        val etBaseUrl = section.findViewById<EditText>(R.id.etBaseUrl)
        etBaseUrl.hint = config.defaultBaseUrl ?: ""
        etBaseUrl.setText(config.getBaseUrl())
    }

    private fun wireModelRow(row: View) {
        row.findViewById<TextView>(R.id.tvRowTitle).text =
            getString(R.string.llm_backend_model_label)
        val tvValue = row.findViewById<TextView>(R.id.tvRowValue)
        tvValue.text = pendingModel
        row.setOnClickListener { showModelPicker(tvValue) }
    }

    private fun showModelPicker(tvValue: TextView) {
        val curated = config.availableModels
        val customLabel = getString(R.string.llm_backend_model_custom_entry)
        val items = curated + customLabel
        val currentIdx = curated.indexOf(pendingModel).let { if (it >= 0) it else items.size - 1 }
        AlertDialog.Builder(this)
            .setTitle(R.string.llm_backend_model_label)
            .setSingleChoiceItems(items.toTypedArray(), currentIdx) { dialog, which ->
                dialog.dismiss()
                if (which == items.size - 1) {
                    showCustomModelDialog(tvValue)
                } else {
                    val picked = items[which]
                    pendingModel = picked
                    tvValue.text = picked
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCustomModelDialog(tvValue: TextView) {
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(pendingModel)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.llm_backend_model_custom_entry)
            .setView(input)
            .setPositiveButton(R.string.deepl_settings_save) { _, _ ->
                val typed = input.text.toString().trim()
                if (typed.isNotBlank()) {
                    pendingModel = typed
                    tvValue.text = typed
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun wireGetKeyLink(row: View) {
        row.findViewById<TextView>(R.id.tvRowTitle).text =
            getString(R.string.llm_backend_get_key_title_fmt, config.displayName)
        val tvSub = row.findViewById<TextView>(R.id.tvRowSubtitle)
        tvSub.text = config.getKeyUrl
        tvSub.visibility = View.VISIBLE
        row.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(config.getKeyUrl)))
        }
        row.setOnLongClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("URL", config.getKeyUrl))
            Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun wireUsageRow(row: View) {
        row.findViewById<TextView>(R.id.tvRowTitle).text =
            getString(R.string.llm_backend_today_usage_label)
        val tvValue = row.findViewById<TextView>(R.id.tvRowValue)
        tvValue.text = getString(R.string.llm_status_today_tokens_fmt, config.todayUsageString())
        row.isClickable = false
        row.isFocusable = false
        row.findViewById<View?>(R.id.tvRowValue)?.let { /* keep value visible */ }
    }

    private fun onSave(etApiKey: EditText) {
        val key = etApiKey.text.toString().trim()
        if (config.allowsBaseUrl) {
            val etBaseUrl = findViewById<EditText>(R.id.etBaseUrl)
            val rawUrl = etBaseUrl.text.toString().trim()
            val validation = validateBaseUrl(rawUrl)
            if (validation != null) {
                etBaseUrl.error = validation
                return
            }
            config.setBaseUrl(rawUrl)
        }
        config.setKey(key)
        // SharedPreferences only fires its listener on actual value changes,
        // so writing pendingModel == current value is a safe no-op.
        config.setModel(pendingModel)
        config.setEnabled(key.isNotBlank())

        // Fire the validation ping after the prefs write so the backend's
        // keyProvider closure sees the just-saved key. Toast surfaces only
        // KeyStatus.Invalid; Ok and Unreachable stay silent.
        //
        // Launched on the Application's scope (with Main dispatcher for the
        // Toast) — NOT lifecycleScope, because finish() below cancels that
        // scope and would silently kill the validation before it completes,
        // leaving an invalid key saved and enabled without the warning.
        val validate = config.validateKey
        if (key.isNotBlank() && validate != null) {
            val appCtx = applicationContext
            val warningText = getString(R.string.llm_backend_invalid_key_toast)
            (application as PlayTranslateApplication).appScope.launch(Dispatchers.Main) {
                val status = runCatching { validate.invoke() }.getOrNull()
                    ?: KeyStatus.Unreachable
                if (status is KeyStatus.Invalid) {
                    Toast.makeText(appCtx, warningText, Toast.LENGTH_LONG).show()
                }
            }
        }
        finish()
    }

    /**
     * Returns null when the URL is acceptable, or an error string when not.
     * Rejects blank + non-`https://` URLs, with an `http://` allowance for
     * loopback hosts so LM Studio's default (`http://localhost:1234/v1`)
     * works without losing the bearer-leak protection for real endpoints.
     * A non-blank host is required for both schemes — `https://` or
     * `https:///v1` would otherwise pass on the scheme alone and then
     * fail every request at OkHttp URL-build time.
     */
    private fun validateBaseUrl(raw: String): String? {
        if (raw.isBlank()) return getString(R.string.llm_backend_base_url_invalid)
        val parsed = runCatching { Uri.parse(raw) }.getOrNull()
            ?: return getString(R.string.llm_backend_base_url_invalid)
        val scheme = parsed.scheme?.lowercase() ?: return getString(R.string.llm_backend_base_url_invalid)
        val host = parsed.host?.lowercase()
        if (host.isNullOrBlank()) return getString(R.string.llm_backend_base_url_invalid)
        if (scheme == "https") return null
        if (scheme == "http" && isLoopback(host)) return null
        return getString(R.string.llm_backend_base_url_invalid)
    }

    private fun isLoopback(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1"

    companion object {
        const val EXTRA_BACKEND_ID = "backend_id"
    }
}
