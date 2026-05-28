package com.playtranslate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.playtranslate.R
import com.playtranslate.applyTheme
import com.playtranslate.translation.KeyStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Generic settings sub-screen for the OpenAI-, Gemini-, and DeepSeek-
 * style LLM backends.
 *
 * Routes through [LlmBackendConfig] so the activity itself stays
 * provider-agnostic — adding a new backend (Anthropic, etc.) is one
 * [LlmBackendConfigs.forId] branch plus the backend class.
 *
 * UX contract mirrors [DeepLSettingsActivity]:
 *  - Prepopulates the key field from prefs on entry.
 *  - The toolbar X discards in-progress edits to the key.
 *  - Save validates the typed key against the provider's auth-only
 *    endpoint BEFORE persisting. While validation is in flight the
 *    button is hidden behind an in-place spinner and the key field
 *    is disabled. On success (Ok or Unreachable) we persist the key
 *    + flip `enabled` and finish; on Invalid we restore the button,
 *    re-enable the field, and show an OverlayAlert explaining the
 *    rejection. Toolbar X mid-validation cancels the in-flight ping
 *    via lifecycleScope and dismisses the activity unchanged.
 *
 * Model selection deliberately lives outside this screen — the inline
 * "Model" sub-cell in the main Settings card appears only once the
 * backend is enabled (i.e. the user has saved a key), at which point
 * the picker has a real key to call /v1/models with.
 *
 * Each registered provider has a hardcoded canonical base URL (set at
 * registration time in PlayTranslateApplication), so the sub-screen
 * has no URL field. To add an OpenAI-compatible provider (DeepSeek,
 * etc.), register a second [com.playtranslate.translation.OpenAiBackend]
 * instance with the right URL — no UI changes needed here.
 */
class LlmBackendSettingsActivity : AppCompatActivity() {

    private lateinit var config: LlmBackendConfig
    private lateinit var etApiKey: EditText
    private lateinit var btnSave: MaterialButton
    private lateinit var progressSave: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_llm_backend_settings)

        val backendId = intent.getStringExtra(EXTRA_BACKEND_ID)
            ?: error("LlmBackendSettingsActivity launched without EXTRA_BACKEND_ID")
        config = LlmBackendConfigs.forId(this, backendId)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(config.titleStringRes)
        toolbar.setNavigationOnClickListener { finish() }

        etApiKey = findViewById(R.id.etApiKey)
        etApiKey.hint = config.keyHint
        etApiKey.setText(config.getKey())
        etApiKey.setSelection(etApiKey.text.length)

        wireGetKeyLink(findViewById(R.id.rowGetKeyLink))

        btnSave = findViewById(R.id.btnSave)
        progressSave = findViewById(R.id.progressSave)
        btnSave.setOnClickListener { onSave() }
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
            Toast.makeText(this, getString(R.string.toast_link_copied), Toast.LENGTH_SHORT).show()
            true
        }
    }

    /** Toggle the Save button's loading state. While loading, the
     *  button text is blanked + click suppressed and the centered
     *  ProgressBar overlays it. The key field is disabled to prevent
     *  edits racing with the in-flight validation request. */
    private fun setLoading(loading: Boolean) {
        if (loading) {
            btnSave.text = ""
            btnSave.isEnabled = false
            progressSave.visibility = View.VISIBLE
            etApiKey.isEnabled = false
        } else {
            btnSave.text = getString(R.string.deepl_settings_save)
            btnSave.isEnabled = true
            progressSave.visibility = View.GONE
            etApiKey.isEnabled = true
        }
    }

    private fun onSave() {
        val key = etApiKey.text.toString().trim()

        // Blank key short-circuit: clear the saved key + disable the
        // backend. Nothing to validate.
        if (key.isBlank()) {
            config.setKey("")
            config.setEnabled(false)
            finish()
            return
        }

        setLoading(true)
        // lifecycleScope so toolbar X (which calls finish()) cancels
        // the in-flight validation cleanly — the activity's destroy
        // tears down the scope and the coroutine never reaches its
        // post-validation save+finish. No prefs are persisted on cancel.
        lifecycleScope.launch {
            val status = try {
                config.validateKey(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Defensive: validateKey shouldn't throw (Unreachable
                // captures network errors), but if it does, fall back
                // to Unreachable rather than blocking the user.
                KeyStatus.Unreachable
            }
            when (status) {
                is KeyStatus.Invalid -> {
                    setLoading(false)
                    showInvalidKeyAlert()
                }
                else -> {
                    // Ok / Unreachable — save and finish. Unreachable
                    // means we couldn't *prove* the key wrong (offline,
                    // 5xx, etc.) so we let the user proceed; the next
                    // translate call surfaces any real issue.
                    config.setKey(key)
                    config.setEnabled(true)
                    finish()
                }
            }
        }
    }

    private fun showInvalidKeyAlert() {
        OverlayAlert.Builder(this)
            .hideIcon()
            .setTitle(getString(R.string.llm_backend_invalid_key_alert_title))
            .setMessage(
                getString(
                    R.string.llm_backend_invalid_key_alert_message_fmt,
                    config.displayName,
                    config.getKeyUrl,
                )
            )
            .addCancelButton(getString(R.string.llm_backend_invalid_key_alert_button))
            .show()
    }

    companion object {
        const val EXTRA_BACKEND_ID = "backend_id"
    }
}
