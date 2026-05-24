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
 *  - Save persists the key and flips `enabled` to true iff the key is
 *    non-blank. The pref change drives the row's switch back in
 *    Settings via the SharedPreferences listener.
 *
 * Model selection deliberately lives outside this screen — the inline
 * "Model" sub-cell in the main Settings card appears only once the
 * backend is enabled (i.e. the user has saved a key), at which point
 * the picker has a real key to call /v1/models with. Keeping the
 * picker out of this screen avoids the "I typed a key but haven't
 * saved it, the picker can't use it yet" problem.
 *
 * Each registered provider has a hardcoded canonical base URL (set at
 * registration time in PlayTranslateApplication), so the sub-screen
 * has no URL field. To add an OpenAI-compatible provider (DeepSeek,
 * etc.), register a second [com.playtranslate.translation.OpenAiBackend]
 * instance with the right URL — no UI changes needed here.
 *
 * Special handling:
 *  - When the provider supports key validation (OpenAI / DeepSeek),
 *    Save fires a non-blocking key-validation ping post-save and
 *    surfaces [KeyStatus.Invalid] as a Toast.
 */
class LlmBackendSettingsActivity : AppCompatActivity() {

    private lateinit var config: LlmBackendConfig

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

        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        etApiKey.hint = config.keyHint
        etApiKey.setText(config.getKey())
        etApiKey.setSelection(etApiKey.text.length)

        wireGetKeyLink(findViewById(R.id.rowGetKeyLink))

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            onSave(etApiKey)
        }
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

    private fun onSave(etApiKey: EditText) {
        val key = etApiKey.text.toString().trim()
        config.setKey(key)
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

    companion object {
        const val EXTRA_BACKEND_ID = "backend_id"
    }
}
