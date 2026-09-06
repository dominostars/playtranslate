package com.playtranslate.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.playtranslate.CaptureService
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.translation.llm.PromptKind
import kotlinx.coroutines.launch

/**
 * Translation services sub-page: the Online card (the user's service
 * instances — store-driven, reorderable, any number of instances per
 * service) and the Offline card (Gemma-E2B / Hunyuan-MT / Qwen-3.5 /
 * Qwen-MNN / Bergamot / ML Kit rows).
 *
 * The online card is owned by [OnlineServicesController] (RecyclerView +
 * add row + toolbar EDIT mode); every online mutation routes through
 * [com.playtranslate.translation.OnlineServiceMutations], which handles
 * the registry/order/cache/reconcile choreography at write time — so
 * this Activity's own lifecycle work reduces to an onResume rebind.
 * Offline rows keep the legacy [TranslationServicesBinder] +
 * [OfflineModelInstallController] wiring, including the offline-pref
 * SharedPreferences listener (their install flows still communicate
 * through prefs).
 */
class TranslationServicesActivity : SettingsSubPageActivity() {

    override val layoutResId = R.layout.activity_translation_services_settings

    private lateinit var binder: TranslationServicesBinder
    private lateinit var installer: OfflineModelInstallController
    private lateinit var onlineController: OnlineServicesController
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onContentCreated(savedInstanceState: Bundle?) {
        setGroupHeader(R.id.headerOnlineTranslations, R.string.settings_header_online_translations)
        setGroupHeader(R.id.headerOfflineTranslations, R.string.settings_header_offline_translations)
        setGroupHeader(R.id.headerAdvancedLlm, R.string.settings_header_advanced_llm)

        // Recent-lines-as-context toggle. Plain pref switch — the recorder
        // and the {context} provider read the pref live, so no push needed.
        val rowLlmContext = findViewById<View>(R.id.rowLlmContext)
        val switchLlmContext = rowLlmContext.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchRowToggle)
        rowLlmContext.findViewById<TextView>(R.id.tvRowTitle).text =
            getString(R.string.settings_llm_context_title)
        rowLlmContext.findViewById<TextView>(R.id.tvRowSubtitle).apply {
            text = getString(R.string.settings_llm_context_subtitle)
            isVisible = true
        }
        switchLlmContext.isChecked = Prefs(this).llmContextEnabled
        switchLlmContext.setOnCheckedChangeListener { _, checked ->
            Prefs(this).llmContextEnabled = checked
        }
        rowLlmContext.setOnClickListener { switchLlmContext.toggle() }

        wirePromptRow(
            R.id.rowPromptSystem,
            R.string.llm_prompt_row_system_title,
            R.string.llm_prompt_row_system_subtitle,
            PromptKind.SYSTEM,
        )
        wirePromptRow(
            R.id.rowPromptTranslation,
            R.string.llm_prompt_row_translation_title,
            R.string.llm_prompt_row_translation_subtitle,
            PromptKind.TRANSLATION,
        )
        wirePromptRow(
            R.id.rowPromptBatch,
            R.string.llm_prompt_row_batch_title,
            R.string.llm_prompt_row_batch_subtitle,
            PromptKind.BATCH,
        )

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
                override fun startHyMt2Download() = installer.download(installer.hymt2)
                override fun enableInstalledHyMt2() = installer.enableInstalled(installer.hymt2)
                override fun showHyMt2DisableDialog() = installer.disable(installer.hymt2)
                override fun startBergamotDownload() = installer.downloadBergamot()
                override fun enableInstalledBergamot() = installer.enableInstalledBergamot()
                override fun showBergamotDisableDialog() = installer.disableBergamot()
            },
        )
        installer = OfflineModelInstallController(this, binder)
        binder.bind()

        onlineController = OnlineServicesController(
            activity = this,
            root = findViewById(android.R.id.content),
            lifecycleScope = lifecycleScope,
        )
        wireEditAction()
    }

    /** Toolbar EDIT ⇄ DONE action driving the online card's edit mode. */
    private fun wireEditAction() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar) ?: return
        toolbar.inflateMenu(R.menu.menu_translation_services)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId != R.id.action_edit) return@setOnMenuItemClickListener false
            val editing = onlineController.toggleEditMode()
            item.title = getString(if (editing) R.string.label_done else R.string.label_edit)
            true
        }
    }

    override fun onResume() {
        super.onResume()
        // Catch up on changes made via the config sub-screens / add picker
        // while this page was paused. The mutation helper already handled
        // registry membership, order, cache clears, and preference
        // reconciliation at write time — this is purely a re-render, plus
        // one belt-and-braces reconcile (covers e.g. a cooldown expiring
        // while paused).
        onlineController.rebind()
        binder.refreshQwenMnnSwitch()
        binder.refreshQwen35Mnn2bSwitch()
        binder.refreshGemmaE2bSwitch()
        binder.refreshHyMtSwitch()
        binder.refreshHyMt2Switch()
        binder.refreshBergamotSwitch()
        binder.refreshAllBackendStatuses()
        CaptureService.instance?.reconcileBackendPreference()

        // Offline-model toggles still flow through prefs (their install
        // flows flip *_enabled from dialogs + download completions), so
        // the listener remains for the offline card only.
        val sp = getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
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
                Prefs.KEY_HYMT2_ENABLED -> {
                    binder.refreshHyMt2Switch()
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
        prefsListener?.let {
            getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(it)
        }
        prefsListener = null
    }

    /** Drop the loaded MNN model when every on-device LLM toggle is off, so the
     *  OS can reclaim the working set. Unload is mutex-serialized in the
     *  translator singleton, so it can't race an in-flight translation. */
    private fun maybeUnloadIdleEngines() {
        val prefs = Prefs(this)
        if (!prefs.qwenMnnEnabled && !prefs.gemmaE2bEnabled && !prefs.hyMtEnabled &&
            !prefs.hyMt2Enabled && !prefs.qwen35Mnn2bEnabled) {
            lifecycleScope.launch {
                com.playtranslate.translation.mnn.MnnTranslator.getInstance(this@TranslationServicesActivity).unloadModel()
            }
        }
    }

    private fun setGroupHeader(id: Int, titleRes: Int) {
        findViewById<View>(id)?.findViewById<TextView>(R.id.tvGroupTitle)?.text = getString(titleRes)
    }

    /** Static navigation rows of the ADVANCED LLM CONFIGURATION card —
     *  each opens the prompt editor for one [PromptKind]. */
    private fun wirePromptRow(rowId: Int, titleRes: Int, subtitleRes: Int, kind: PromptKind) {
        val row = findViewById<View>(rowId) ?: return
        row.findViewById<TextView>(R.id.tvRowTitle)?.text = getString(titleRes)
        row.findViewById<TextView>(R.id.tvRowSubtitle)?.apply {
            text = getString(subtitleRes)
            visibility = View.VISIBLE
        }
        row.setOnClickListener { startActivity(LlmPromptEditorActivity.intent(this, kind)) }
    }
}
