package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.audio.online.OnlineTtsChoice
import com.playtranslate.audio.online.OnlineTtsConfig
import com.playtranslate.audio.online.OnlineTtsException
import com.playtranslate.audio.online.OnlineTtsPreset
import com.playtranslate.audio.online.OnlineTtsRequest
import com.playtranslate.audio.online.OnlineTtsRequest.Issue
import com.playtranslate.audio.online.OnlineTtsRequest.Token
import com.playtranslate.audio.online.OnlineTtsService
import com.playtranslate.language.SourceLangId
import com.playtranslate.themeColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The voice service's settings: which kind of request the service takes,
 * where it is sent, the key, the model, the voice instructions, and under
 * Advanced the request text itself and the voice ids of a service that
 * cannot list its own.
 *
 * There is no voice on this page and no switch: a voice is chosen on the
 * voice page, beside the device's voices, and choosing one of this service's
 * is what puts the service to use.
 *
 * Save validates ([OnlineTtsRequest.validate]), asks the service for its
 * models, and only then stores the whole page at once through
 * [OnlineTtsService.save]; nothing is stored before it. That one request is
 * the check of the URL and the key, and it picks a model for a user who
 * chose none (see [onSave]). A voice speaking its sample on the voice page
 * is the check of the rest.
 *
 * A row is shown only while its token is in the request text, read from the
 * fields as they stand, so the page has no row for something the request
 * does not send.
 *
 * The Model row's list opens inside this page, over the form, because it is
 * fetched with the URL and the key as typed, which are not stored yet and do
 * not belong in an Intent.
 */
class OnlineTtsServiceActivity : SettingsSubPageActivity() {

    override val layoutResId: Int = R.layout.activity_online_tts_service

    private lateinit var prefs: Prefs
    private lateinit var etUrl: EditText
    private lateinit var etKey: EditText
    private lateinit var etInstructions: EditText
    private lateinit var etRequest: EditText
    private lateinit var etManualVoices: EditText
    private lateinit var etOtherModel: EditText
    private lateinit var rowService: View
    private lateinit var rowModel: View
    private lateinit var formScroll: View
    private lateinit var modelPanel: View
    private lateinit var modelRows: LinearLayout
    private lateinit var saveBar: View
    private lateinit var saveDivider: View

    private var preset: OnlineTtsPreset = OnlineTtsPreset.OPENAI
    private var model: OnlineTtsChoice? = null
    private var modelsJob: Job? = null

    /** Save's check of the service, while it runs. Leaving the page ends
     *  it, and then nothing is stored. */
    private var saveJob: Job? = null

    private val closePanelOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { closeModelPanel() }
    }

    override fun onContentCreated(savedInstanceState: Bundle?) {
        prefs = OnlineTtsService.prefs(this)
        val stored = OnlineTtsService.config(prefs)

        etUrl = findViewById(R.id.etVoiceServiceUrl)
        etKey = findViewById(R.id.etVoiceServiceKey)
        etInstructions = findViewById(R.id.etVoiceServiceInstructions)
        etRequest = findViewById(R.id.etVoiceServiceRequest)
        etManualVoices = findViewById(R.id.etVoiceServiceManualVoices)
        etOtherModel = findViewById(R.id.etVoiceServiceOtherModel)
        rowService = findViewById(R.id.rowService)
        rowModel = findViewById(R.id.rowModel)
        formScroll = findViewById(R.id.formScroll)
        modelPanel = findViewById(R.id.modelPanel)
        modelRows = findViewById(R.id.modelRows)
        saveBar = findViewById(R.id.voiceServiceSaveBar)
        saveDivider = findViewById(R.id.voiceServiceSaveDivider)

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = getString(R.string.audio_source_voice_service_name)
            // The X closes the model list first, as Back does.
            setNavigationOnClickListener {
                if (modelPanel.isVisible) {
                    closeModelPanel()
                } else {
                    saveJob?.cancel()
                    finish()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, closePanelOnBack)

        findViewById<View>(R.id.headerVoiceServiceAdvanced)
            .findViewById<TextView>(R.id.tvGroupTitle).text =
            getString(R.string.voice_service_advanced_header)
        rowService.findViewById<TextView>(R.id.tvRowTitle).text = getString(R.string.voice_service_row_service)
        rowModel.findViewById<TextView>(R.id.tvRowTitle).text = getString(R.string.voice_service_row_model)

        if (savedInstanceState == null) {
            // A first open: the stored service with its key, or the first
            // preset. On a recreate the fields restore their own text,
            // after this.
            val connected = OnlineTtsService.connection(prefs)
            val shown = connected?.config ?: OnlineTtsConfig()
            preset = shown.preset
            model = shown.model
            etUrl.setText(shown.effectiveUrl)
            etKey.setText(connected?.key.orEmpty())
            etInstructions.setText(shown.instructions)
            etRequest.setText(shown.effectiveRequest)
            etManualVoices.setText(shown.manualVoices.joinToString("\n"))
        } else {
            preset = savedInstanceState.getString(STATE_PRESET)
                ?.let { name -> OnlineTtsPreset.entries.firstOrNull { it.name == name } }
                ?: OnlineTtsPreset.OPENAI
            model = savedInstanceState.getString(STATE_MODEL_ID)?.let { id ->
                OnlineTtsChoice(id, savedInstanceState.getString(STATE_MODEL_NAME) ?: id)
            }
        }

        rowService.setOnClickListener { showServiceMenu(it) }
        rowModel.setOnClickListener { openModelPanel() }
        findViewById<MaterialButton>(R.id.btnVoiceServiceSave).setOnClickListener { onSave() }
        findViewById<MaterialButton>(R.id.btnVoiceServiceUseModel).setOnClickListener {
            val typed = etOtherModel.text.toString().trim()
            if (typed.isNotEmpty()) pickModel(OnlineTtsChoice(typed))
        }
        findViewById<View>(R.id.cardVoiceServiceRemove).isVisible = stored != null
        findViewById<View>(R.id.rowVoiceServiceRemove).setOnClickListener { confirmRemove() }

        // The rows follow the request as it is typed. A watcher, not a read
        // at creation: on a recreate the fields get their text back after
        // this method has run.
        val rowsFollowText = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refreshRows()
        }
        etUrl.addTextChangedListener(rowsFollowText)
        etRequest.addTextChangedListener(rowsFollowText)

        refreshRows()
    }

    /** A recreated page whose model list was open opens it again here, not
     *  in [onContentCreated]: the list is fetched with the URL and the key
     *  as typed, and the fields have their text back only now. */
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (savedInstanceState.getBoolean(STATE_MODEL_PANEL)) openModelPanel()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PRESET, preset.name)
        outState.putString(STATE_MODEL_ID, model?.id)
        outState.putString(STATE_MODEL_NAME, model?.name)
        outState.putBoolean(STATE_MODEL_PANEL, modelPanel.isVisible)
    }

    // ── The page as a config ─────────────────────────────────────────────

    /** The page as it stands, unsaved. */
    private fun pageConfig(): OnlineTtsConfig = OnlineTtsConfig(
        preset = preset,
        url = etUrl.text.toString(),
        request = etRequest.text.toString(),
        model = model,
        instructions = etInstructions.text.toString().trim(),
        manualVoices = etManualVoices.text.toString().lines(),
    )

    /** The key as typed, or none while the request does not send one. */
    private fun pageKey(): String =
        if (Token.KEY in OnlineTtsRequest.tokensIn(pageConfig())) etKey.text.toString().trim() else ""

    private fun refreshRows() {
        val tokens = OnlineTtsRequest.tokensIn(pageConfig())
        findViewById<View>(R.id.sectionVoiceServiceKey).isVisible = Token.KEY in tokens
        findViewById<View>(R.id.sectionVoiceServiceModel).isVisible = Token.MODEL in tokens
        findViewById<View>(R.id.sectionVoiceServiceInstructions).isVisible = Token.INSTRUCTIONS in tokens
        rowService.findViewById<TextView>(R.id.tvRowValue).text = getString(presetLabel(preset))
        rowModel.findViewById<TextView>(R.id.tvRowValue).text =
            model?.name ?: getString(R.string.voice_service_model_none)
    }

    @StringRes
    private fun presetLabel(p: OnlineTtsPreset): Int = when (p) {
        OnlineTtsPreset.OPENAI -> R.string.voice_service_preset_openai
        OnlineTtsPreset.ELEVENLABS -> R.string.voice_service_preset_elevenlabs
    }

    private fun showServiceMenu(anchor: View) {
        val popup = PopupMenu(this, anchor, Gravity.END)
        OnlineTtsPreset.entries.forEachIndexed { index, p ->
            popup.menu.add(0, index, index, getString(presetLabel(p)))
        }
        popup.setOnMenuItemClickListener { item ->
            applyPreset(OnlineTtsPreset.entries[item.itemId])
            true
        }
        popup.show()
    }

    /**
     * Another kind of service: its URL and its request text replace the
     * page's, and the model, the typed voice ids and the key go. A model id
     * and a voice id mean nothing to another service, and a key left in its
     * field would be sent to that service by the next Save, which asks it
     * for its models. The instructions are the user's and stay.
     *
     * Only the page changes. What is stored changes at Save, so leaving
     * without saving and opening the page again shows the stored service
     * with its key.
     */
    @VisibleForTesting
    internal fun applyPreset(p: OnlineTtsPreset) {
        if (p == preset) return
        preset = p
        model = null
        etUrl.setText(p.spec.url)
        etRequest.setText(p.spec.request)
        etManualVoices.setText("")
        etKey.setText("")
        refreshRows()
    }

    // ── Save and remove ──────────────────────────────────────────────────

    /**
     * Save asks the service for its models before anything is stored
     * ([OnlineTtsService.modelsAnswer]). That one request is the check of
     * the URL and the key, and it is where a model is chosen for a user who
     * chose none ([com.playtranslate.audio.online.OnlineTtsPresetSpec.pickModel]).
     *
     * - The service lists its models: the page is stored, with the user's
     *   model or the one picked for them. When none can be picked (the list
     *   holds several and none the preset prefers), the list is shown and
     *   the user picks. A model the user has, and that the list does not
     *   hold, is said before it is stored ([confirmModelNotListed]).
     * - The key is refused, or the service is not reached: nothing is
     *   stored, and the service's own words are shown.
     * - The service answers without a list: the key could not be checked,
     *   and the user is asked whether to save anyway. Never silently: a
     *   page that reads as saved has either been checked or been told so.
     *
     * No speech is asked for here. That needs a voice, and a voice is
     * chosen on the voice page, where one that fails can be fixed by
     * tapping another. The one thing known about a voice without asking,
     * that the model refuses it, is said before the page is stored
     * ([store]).
     */
    private fun onSave() {
        // One check at a time, whatever delivers the press.
        if (saveJob?.isActive == true) return
        // The keyboard goes with the press, whatever Save then does: it
        // would otherwise stand over an alert, or stay up for a moment over
        // the page this one closes onto.
        hideKeyboard()
        val config = pageConfig()
        val key = pageKey()
        // What is wrong on the page itself is said before any request; a
        // missing model is not among it, the request below may supply one.
        val issue = OnlineTtsRequest.validate(config, key).firstOrNull { it != Issue.NO_MODEL }
        if (issue != null) {
            showCannotSave(getString(issueMessage(issue)))
            return
        }
        setChecking(true)
        saveJob = lifecycleScope.launch {
            val asked = try {
                Result.success(OnlineTtsService.modelsAnswer(config, key))
            } catch (e: CancellationException) {
                throw e
            } catch (e: OnlineTtsException) {
                Result.failure(e)
            }
            // The page was left while the service was being asked (Back
            // does not end the check as the X does). Its answer is taken
            // up by no page, whichever it is: leaving is not saving, and a
            // failure's alert shows over whatever page is in front, which
            // by now is the one under this.
            if (isFinishing) return@launch
            setChecking(false)
            val answer = asked.getOrElse { failure ->
                showVoiceServiceFailure(this@OnlineTtsServiceActivity, failure as OnlineTtsException, ofList = true)
                return@launch
            }
            val needsModel = Token.MODEL in OnlineTtsRequest.tokensIn(config)
            when (answer) {
                is OnlineTtsService.ModelsAnswer.Listed -> {
                    if (model == null && needsModel) {
                        val picked = preset.spec.pickModel(answer.models)
                        if (picked == null) {
                            // Nothing to pick by rule: the user picks, from
                            // the list just loaded.
                            setModelPanelShown(true)
                            renderModels(answer.models)
                            return@launch
                        }
                        model = picked
                        refreshRows()
                    }
                    val chosen = model
                    if (needsModel && chosen != null && answer.models.none { it.id == chosen.id }) {
                        confirmModelNotListed(chosen)
                    } else {
                        store()
                    }
                }
                is OnlineTtsService.ModelsAnswer.NoList ->
                    if (model == null && needsModel) {
                        showCannotSave(
                            noListMessage(R.string.voice_service_no_model_list_type_model, config, key, answer.said),
                        )
                    } else {
                        confirmSaveUnchecked(
                            noListMessage(R.string.voice_service_unchecked_message, config, key, answer.said),
                        )
                    }
            }
        }
    }

    /**
     * What is said when the service, asked for its models, answered with
     * no list: [sentence], then the service's own words. When the request
     * sends a key and none is entered, that is said first
     * ([withKeyNote]).
     */
    private fun noListMessage(
        @StringRes sentence: Int,
        config: OnlineTtsConfig,
        key: String,
        said: OnlineTtsException?,
    ): String = withKeyNote(withServiceWords(getString(sentence), said), config, key)

    /**
     * [message] under a line saying that no API key is entered, when
     * [config]'s request sends one and [key] is empty. For an answer to
     * the model list call that was neither a list nor a refusal of the
     * key: a hosted service asked without its key need not say so
     * (ElevenLabs answers "not found"), and then nothing the page shows
     * would name the key. A line and not a refusal to ask: a self-hosted
     * server may need no key.
     */
    private fun withKeyNote(message: String, config: OnlineTtsConfig, key: String): String =
        if (key.isEmpty() && Token.KEY in OnlineTtsRequest.tokensIn(config)) {
            getString(R.string.voice_service_no_key_entered) + "\n\n" + message
        } else {
            message
        }

    /**
     * Stores the page as it stands and closes it. The last step of every
     * Save, checked or, by the user's own choice, not.
     *
     * One thing is asked first, unless [voicesConfirmed] says it was: a
     * game language's voice that the page's model is known to refuse
     * ([OnlineTtsService.voicesNotTaken]) goes back to the device's voice
     * when the page is stored, and the user is told which before it does.
     */
    private fun store(voicesConfirmed: Boolean = false) {
        val config = pageConfig()
        val key = pageKey()
        val issue = OnlineTtsRequest.validate(config, key).firstOrNull()
        if (issue != null) {
            showCannotSave(getString(issueMessage(issue)))
            return
        }
        if (!voicesConfirmed) {
            val notTaken = OnlineTtsService.voicesNotTaken(prefs, config)
            if (notTaken.isNotEmpty()) {
                confirmVoicesNotTaken(config, notTaken)
                return
            }
        }
        if (!OnlineTtsService.save(prefs, config, key)) {
            showCannotSave(getString(R.string.voice_service_key_not_stored))
            return
        }
        setResult(RESULT_OK)
        finish()
    }

    /**
     * The service listed its models and [chosen], the page's, is not among
     * them: a model left on the page from another service (the URL was
     * changed, the model was not), an id typed wrong, a model the service
     * has retired. Stored unasked, it would be found out only by a voice
     * that will not speak, under a refusal that need not name the model.
     * The service's own list is the one thing known for certain here, so
     * it is said, and the user decides: a service may take a model it does
     * not list, and "Save anyway" stores it as it stands.
     */
    private fun confirmModelNotListed(chosen: OnlineTtsChoice) {
        OverlayAlert.Builder(this)
            .setTitle(getString(R.string.voice_service_model_not_listed_title))
            .setMessage(getString(R.string.voice_service_model_not_listed_message, chosen.name))
            .addButton(
                getString(R.string.llm_prompt_save_anyway),
                themeColor(R.attr.ptAccent),
                themeColor(R.attr.ptAccentOn),
            ) { store() }
            .addCancelButton(getString(R.string.btn_cancel))
            .show()
    }

    /** The service gave no list of models, so the key is unchecked: the
     *  user decides whether that is good enough to save. [message] says
     *  so ([noListMessage]). */
    private fun confirmSaveUnchecked(message: String) {
        OverlayAlert.Builder(this)
            .setTitle(getString(R.string.voice_service_unchecked_title))
            .setMessage(message)
            .addButton(
                getString(R.string.llm_prompt_save_anyway),
                themeColor(R.attr.ptAccent),
                themeColor(R.attr.ptAccentOn),
            ) { saveUnchecked() }
            .addCancelButton(getString(R.string.btn_cancel))
            .show()
    }

    /** "Save anyway", pressed. */
    @VisibleForTesting
    internal fun saveUnchecked() = store()

    /**
     * The page's model refuses a voice some game language speaks with:
     * which voice, for which language, and that saving sends each of those
     * languages back to the device's voice. Cancel leaves the page open
     * with nothing stored, so the model can be put back.
     */
    private fun confirmVoicesNotTaken(config: OnlineTtsConfig, notTaken: Map<SourceLangId, OnlineTtsChoice>) {
        // A line per language, joined as the voice page joins a service's
        // host and its model.
        val lines = notTaken.entries.joinToString("\n") { (lang, voice) -> "${lang.displayName()} · ${voice.name}" }
        val message = getString(R.string.voice_service_voices_not_taken_message, config.model?.name.orEmpty())
        OverlayAlert.Builder(this)
            .setTitle(getString(R.string.voice_service_voices_not_taken_title))
            .setMessage("$message\n\n$lines")
            .addButton(
                getString(R.string.llm_prompt_save_anyway),
                themeColor(R.attr.ptAccent),
                themeColor(R.attr.ptAccentOn),
            ) { store(voicesConfirmed = true) }
            .addCancelButton(getString(R.string.btn_cancel))
            .show()
    }

    /** [sentence], then what the service itself answered, when it answered
     *  in words. */
    private fun withServiceWords(sentence: String, said: OnlineTtsException?): String =
        if (said?.detail.isNullOrBlank()) sentence else "$sentence\n\n${said?.detail}"

    /** While the service is being asked: Save shows a spinner and nothing
     *  on the page can be changed, so what is stored is what was checked. */
    private fun setChecking(checking: Boolean) {
        val save = findViewById<MaterialButton>(R.id.btnVoiceServiceSave)
        save.isEnabled = !checking
        save.text = if (checking) "" else getString(R.string.btn_save)
        findViewById<View>(R.id.progressVoiceServiceSave).isVisible = checking
        listOf<View>(
            etUrl, etKey, etInstructions, etRequest, etManualVoices, rowService, rowModel,
            findViewById(R.id.rowVoiceServiceRemove),
        ).forEach { it.isEnabled = !checking }
    }

    /** Puts the keyboard away if it is up; nothing happens if it is not. */
    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(findViewById<View>(android.R.id.content).windowToken, 0)
    }

    @StringRes
    private fun issueMessage(issue: Issue): Int = when (issue) {
        Issue.BAD_URL -> R.string.voice_service_issue_bad_url
        Issue.NO_TEXT -> R.string.voice_service_issue_no_text
        Issue.TEXT_IN_HEADER -> R.string.voice_service_issue_text_in_header
        Issue.HEADER_NOT_ASCII -> R.string.voice_service_issue_header_not_ascii
        Issue.NO_MODEL -> R.string.voice_service_issue_no_model
    }

    private fun showCannotSave(message: String) {
        OverlayAlert.Builder(this)
            .setTitle(getString(R.string.voice_service_cannot_save_title))
            .setMessage(message)
            .addCancelButton(getString(R.string.btn_ok))
            .show()
    }

    private fun confirmRemove() {
        OverlayAlert.Builder(this)
            .setTitle(getString(R.string.voice_service_remove_confirm_title))
            .setMessage(getString(R.string.voice_service_remove_confirm_message))
            .addButton(
                getString(R.string.voice_service_remove),
                themeColor(R.attr.ptDanger),
                themeColor(R.attr.ptAccentOn),
            ) { removeService() }
            .addCancelButton(getString(R.string.btn_cancel))
            .show()
    }

    /** The confirmed removal: the service, its key, every language's
     *  voice of it and the clips it spoke ([OnlineTtsService.remove]). */
    @VisibleForTesting
    internal fun removeService() {
        OnlineTtsService.remove(this)
        setResult(RESULT_OK)
        finish()
    }

    // ── The model list ───────────────────────────────────────────────────

    private fun openModelPanel() {
        setModelPanelShown(true)
        renderModelStatus(getString(R.string.audio_loading), null)
        modelsJob?.cancel()
        modelsJob = lifecycleScope.launch {
            val config = pageConfig()
            val key = pageKey()
            // The failure in full: this list loading is the page's check
            // that the URL and the key are right.
            fun reason(e: OnlineTtsException) =
                voiceServiceFailureMessage(this@OnlineTtsServiceActivity, e, ofList = true)
            try {
                // Read as Save reads it ([OnlineTtsService.modelsAnswer]),
                // so an answer that is no list is told from a failure the
                // same way in both places, and says the same about a key
                // that was not entered.
                when (val answer = OnlineTtsService.modelsAnswer(config, key)) {
                    is OnlineTtsService.ModelsAnswer.Listed -> renderModels(answer.models)
                    is OnlineTtsService.ModelsAnswer.NoList -> when (val said = answer.said) {
                        null -> renderModels(emptyList())
                        else -> renderModelStatus(
                            getString(R.string.audio_error_loading),
                            withKeyNote(reason(said), config, key),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OnlineTtsException) {
                renderModelStatus(getString(R.string.audio_error_loading), reason(e))
            }
        }
    }

    private fun closeModelPanel() {
        modelsJob?.cancel()
        setModelPanelShown(false)
    }

    private fun setModelPanelShown(shown: Boolean) {
        modelPanel.isVisible = shown
        formScroll.isVisible = !shown
        saveBar.isVisible = !shown
        saveDivider.isVisible = !shown
        closePanelOnBack.isEnabled = shown
    }

    private fun pickModel(choice: OnlineTtsChoice) {
        model = choice
        closeModelPanel()
        refreshRows()
    }

    private fun renderModels(models: List<OnlineTtsChoice>) {
        modelRows.removeAllViews()
        if (models.isEmpty()) {
            renderModelStatus(getString(R.string.audio_no_results), null)
            return
        }
        val inflater = LayoutInflater.from(this)
        models.forEachIndexed { index, choice ->
            if (index > 0) modelRows.addView(inflater.inflate(R.layout.settings_row_divider, modelRows, false))
            val row = inflater.inflate(R.layout.settings_row_choice, modelRows, false)
            row.findViewById<TextView>(R.id.tvRowTitle).text = choice.name
            row.findViewById<TextView>(R.id.tvRowSubtitle).apply {
                // The id under a name that is not the id: it is what the
                // request sends.
                text = choice.id
                visibility = if (choice.name == choice.id) View.GONE else View.VISIBLE
            }
            row.findViewById<ImageView>(R.id.ivCheck).visibility =
                if (model?.id == choice.id) View.VISIBLE else View.GONE
            row.setOnClickListener { pickModel(choice) }
            modelRows.addView(row)
        }
    }

    private fun renderModelStatus(title: String, subtitle: String?) {
        modelRows.removeAllViews()
        val row = LayoutInflater.from(this).inflate(R.layout.settings_row_choice, modelRows, false)
        row.findViewById<TextView>(R.id.tvRowTitle).text = title
        row.findViewById<TextView>(R.id.tvRowSubtitle).apply {
            text = subtitle.orEmpty()
            visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        row.findViewById<ImageView>(R.id.ivCheck).visibility = View.GONE
        row.isClickable = false
        modelRows.addView(row)
    }

    companion object {
        private const val STATE_PRESET = "voice_service_preset"
        private const val STATE_MODEL_ID = "voice_service_model_id"
        private const val STATE_MODEL_NAME = "voice_service_model_name"
        private const val STATE_MODEL_PANEL = "voice_service_model_panel"

        fun intent(context: Context): Intent = Intent(context, OnlineTtsServiceActivity::class.java)
    }
}
