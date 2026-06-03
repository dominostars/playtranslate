package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.playtranslate.CaptureService
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.translation.BackendId
import kotlinx.coroutines.launch

/**
 * Translation services sub-page: the Online (Gemini / OpenAI / DeepSeek /
 * DeepL / Lingva) and Offline (Gemma-E2B / Hunyuan-MT / Qwen-3.5 / Qwen-MNN /
 * Bergamot / ML Kit) backend rows.
 *
 * The row rendering lives in [TranslationServicesBinder] (extracted from the
 * old SettingsRenderer). The offline-model install flows are consolidated in
 * [OfflineModelInstallController] (one descriptor-driven flow for the four MNN
 * tiers + a Bergamot sibling). The LLM key / DeepL key editors are the existing
 * sub-screen Activities.
 *
 * This Activity owns the backend cache-reconcile choreography that used to live
 * in SettingsBottomSheet: on resume it re-renders every backend, force-clears
 * the translation cache if an LLM key/model changed while paused (via a config
 * snapshot diff), and reconciles the backend preference; a SharedPreferences
 * listener does the same while the page is foreground. (A future change can
 * make this reactive — see the migration notes — but it's a faithful relocation
 * here to keep the behavior identical.)
 */
class TranslationServicesActivity : SettingsSubPageActivity() {

    override val layoutResId = R.layout.activity_translation_services_settings

    private lateinit var binder: TranslationServicesBinder
    private lateinit var installer: OfflineModelInstallController
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var llmConfigSnapshotOnPause: String? = null

    override fun onContentCreated(savedInstanceState: Bundle?) {
        setGroupHeader(R.id.headerOnlineTranslations, R.string.settings_header_online_translations)
        setGroupHeader(R.id.headerOfflineTranslations, R.string.settings_header_offline_translations)

        binder = TranslationServicesBinder(
            root = findViewById(android.R.id.content),
            prefs = Prefs(this),
            ctx = this,
            lifecycleScope = lifecycleScope,
            callbacks = object : TranslationServicesBinder.Callbacks {
                override fun startQwenMnnDownload() = installer.download(installer.qwenMnn)
                override fun enableInstalledQwenMnn() = installer.enableInstalled(installer.qwenMnn)
                override fun showQwenMnnDisableDialog() = installer.disable(installer.qwenMnn)
                override fun startQwen35Mnn2bDownload() = installer.download(installer.qwen35)
                override fun enableInstalledQwen35Mnn2b() = installer.enableInstalled(installer.qwen35)
                override fun showQwen35Mnn2bDisableDialog() = installer.disable(installer.qwen35)
                override fun startGemmaE2bMnnDownload() = installer.download(installer.gemma)
                override fun enableInstalledGemmaE2bMnn() = installer.enableInstalled(installer.gemma)
                override fun showGemmaE2bMnnDisableDialog() = installer.disable(installer.gemma)
                override fun startHyMtDownload() = installer.download(installer.hymt)
                override fun enableInstalledHyMt() = installer.enableInstalled(installer.hymt)
                override fun showHyMtDisableDialog() = installer.disable(installer.hymt)
                override fun startBergamotDownload() = installer.downloadBergamot()
                override fun enableInstalledBergamot() = installer.enableInstalledBergamot()
                override fun showBergamotDisableDialog() = installer.disableBergamot()
                override fun openDeepLSettings() {
                    startActivity(Intent(this@TranslationServicesActivity, DeepLSettingsActivity::class.java))
                }
                override fun openLlmBackendSettings(id: BackendId) {
                    startActivity(
                        Intent(this@TranslationServicesActivity, LlmBackendSettingsActivity::class.java)
                            .putExtra(LlmBackendSettingsActivity.EXTRA_BACKEND_ID, id),
                    )
                }
                override fun openLlmModelPicker(id: BackendId) {
                    startActivity(LlmModelPickerActivity.newIntent(this@TranslationServicesActivity, id))
                }
            },
        )
        installer = OfflineModelInstallController(this, binder)
        binder.bind()
    }

    override fun onResume() {
        super.onResume()
        // Catch up on backend changes made via the DeepL / LLM sub-screens while
        // this page was paused (they flip *_enabled / keys while our listener is
        // unregistered). Faithful port of the old SettingsBottomSheet.onResume.
        binder.refreshDeeplBackendSwitch()
        binder.refreshGeminiBackendSwitch()
        binder.refreshOpenaiBackendSwitch()
        binder.refreshDeepseekBackendSwitch()
        binder.refreshGeminiModelValue()
        binder.refreshOpenaiModelValue()
        binder.refreshDeepseekModelValue()
        binder.refreshLingvaBackendSwitch()
        binder.refreshQwenMnnSwitch()
        binder.refreshGemmaE2bSwitch()
        binder.refreshHyMtSwitch()
        // LLM key/model changes don't flip *_enabled but DO change the output a
        // given input maps to; reconcileBackendPreference can't catch them
        // (preferred id unchanged), so diff the paused-snapshot and force-clear
        // the cache when anything moved.
        val before = llmConfigSnapshotOnPause
        llmConfigSnapshotOnPause = null
        if (before != null && before != snapshotLlmConfig(Prefs(this))) {
            CaptureService.instance?.clearTranslationCache()
        }
        binder.refreshAllBackendStatuses()
        CaptureService.instance?.reconcileBackendPreference()

        val sp = getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                Prefs.KEY_DEEPL_ENABLED -> {
                    binder.refreshDeeplBackendSwitch()
                    binder.refreshAllBackendStatuses()
                    CaptureService.instance?.reconcileBackendPreference()
                }
                Prefs.KEY_GEMINI_ENABLED -> {
                    binder.refreshGeminiBackendSwitch()
                    binder.refreshAllBackendStatuses()
                    CaptureService.instance?.reconcileBackendPreference()
                }
                Prefs.KEY_OPENAI_ENABLED -> {
                    binder.refreshOpenaiBackendSwitch()
                    binder.refreshAllBackendStatuses()
                    CaptureService.instance?.reconcileBackendPreference()
                }
                Prefs.KEY_DEEPSEEK_ENABLED -> {
                    binder.refreshDeepseekBackendSwitch()
                    binder.refreshAllBackendStatuses()
                    CaptureService.instance?.reconcileBackendPreference()
                }
                Prefs.KEY_GEMINI_KEY, Prefs.KEY_OPENAI_KEY, Prefs.KEY_DEEPSEEK_KEY,
                Prefs.KEY_GEMINI_MODEL, Prefs.KEY_OPENAI_MODEL, Prefs.KEY_DEEPSEEK_MODEL -> {
                    binder.refreshAllBackendStatuses()
                    CaptureService.instance?.clearTranslationCache()
                    if (key == Prefs.KEY_GEMINI_MODEL) binder.refreshGeminiModelValue()
                    if (key == Prefs.KEY_OPENAI_MODEL) binder.refreshOpenaiModelValue()
                    if (key == Prefs.KEY_DEEPSEEK_MODEL) binder.refreshDeepseekModelValue()
                }
                Prefs.KEY_LINGVA_ENABLED -> {
                    binder.refreshLingvaBackendSwitch()
                    binder.refreshAllBackendStatuses()
                    CaptureService.instance?.reconcileBackendPreference()
                }
                Prefs.KEY_QWEN_MNN_ENABLED -> {
                    binder.refreshQwenMnnSwitch()
                    binder.refreshAllBackendStatuses()
                    CaptureService.instance?.reconcileBackendPreference()
                    maybeUnloadIdleEngines()
                }
                Prefs.KEY_QWEN35_MNN_2B_ENABLED -> {
                    binder.refreshQwen35Mnn2bSwitch()
                    binder.refreshAllBackendStatuses()
                    CaptureService.instance?.reconcileBackendPreference()
                    maybeUnloadIdleEngines()
                }
                Prefs.KEY_GEMMA_E2B_ENABLED -> {
                    binder.refreshGemmaE2bSwitch()
                    binder.refreshAllBackendStatuses()
                    CaptureService.instance?.reconcileBackendPreference()
                    maybeUnloadIdleEngines()
                }
                Prefs.KEY_HYMT_ENABLED -> {
                    binder.refreshHyMtSwitch()
                    binder.refreshAllBackendStatuses()
                    CaptureService.instance?.reconcileBackendPreference()
                    maybeUnloadIdleEngines()
                }
                Prefs.KEY_BERGAMOT_ENABLED -> {
                    binder.refreshBergamotSwitch()
                    binder.refreshAllBackendStatuses()
                    CaptureService.instance?.reconcileBackendPreference()
                }
            }
        }
        sp.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onPause() {
        super.onPause()
        llmConfigSnapshotOnPause = snapshotLlmConfig(Prefs(this))
        prefsListener?.let {
            getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(it)
        }
        prefsListener = null
    }

    private fun snapshotLlmConfig(prefs: Prefs): String = listOf(
        prefs.geminiApiKey, prefs.geminiModel,
        prefs.openaiApiKey, prefs.openaiModel,
        prefs.deepseekApiKey, prefs.deepseekModel,
    ).joinToString("|")

    /** Drop the loaded MNN model when every on-device LLM toggle is off, so the
     *  OS can reclaim the working set. Unload is mutex-serialized in the
     *  translator singleton, so it can't race an in-flight translation. */
    private fun maybeUnloadIdleEngines() {
        val prefs = Prefs(this)
        if (!prefs.qwenMnnEnabled && !prefs.gemmaE2bEnabled && !prefs.hyMtEnabled &&
            !prefs.qwen35Mnn2bEnabled) {
            lifecycleScope.launch {
                com.playtranslate.translation.mnn.MnnTranslator.getInstance(this@TranslationServicesActivity).unloadModel()
            }
        }
    }

    private fun setGroupHeader(id: Int, titleRes: Int) {
        findViewById<View>(id)?.findViewById<TextView>(R.id.tvGroupTitle)?.text = getString(titleRes)
    }
}
