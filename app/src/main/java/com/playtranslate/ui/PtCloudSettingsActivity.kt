package com.playtranslate.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.cloud.HealthStatus
import com.playtranslate.cloud.PtCloudClient
import com.playtranslate.cloud.PtCloudService
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Settings → Configure → PlayTranslate Cloud (debug builds only, like the
 * cell that opens it): the service's two "use for" switches, its client key
 * and its status. The translation-error pill's gear opens it too, for a
 * PlayTranslate Cloud error, and so do the Capture & overlay page's cloud
 * row and the OCR picker's cloud entry when the switch is turned on with no
 * key saved. The service's address is fixed ([PtCloudClient]); the page has
 * no field for it.
 *
 *  - Two switches, one per route and independent, no master: Read captures
 *    ([PtCloudService.setOcrEnabled], which the Capture & overlay page's
 *    cloud row and the OCR picker write too) and Translate
 *    ([PtCloudService.setTranslateEnabled], which the service's row on the
 *    Translation services page writes too). Each goes through its one
 *    mutation, which stores the pref and resets the service (its cooldown
 *    and the pill's errors), since either direction means "try it again
 *    now". While no usable key is saved both rows show disabled and off
 *    (2026-10-25): the key below is the first step, and its save
 *    turns both on. Seeded before their listeners are attached, so building
 *    or restoring the page writes nothing and resets nothing. They repaint,
 *    with the cooldown line below the status, from the service's own change
 *    signal ([PtCloudService.changes]), on each gesture and each resume, as
 *    the services row does, so a switch flipped from another surface (the
 *    picker over the game, the key save below) shows here without the page
 *    repainting after a mutation itself.
 *  - Save persists the key through the service ([PtCloudService.saveClientKey]:
 *    the first key saved turns both switches on, a cleared key turns both
 *    off, a replacement leaves them), which resets the service, then checks
 *    health. Unlike the LLM page, the page stays open after Save: it is
 *    where the status shows.
 *  - The status row is the server's answer to `GET /health`
 *    ([PtCloudClient.health], on the service's one client,
 *    [PtCloudService.client]), asked on resume and after Save; capture
 *    never asks it. Below it, while the service's cooldown runs, the line
 *    a Translation services row shows for its service's cooldown
 *    ([cooldownLine]).
 *
 * The key is not logged here.
 */
class PtCloudSettingsActivity : SettingsSubPageActivity() {

    override val layoutResId = R.layout.activity_pt_cloud_settings

    private lateinit var etKey: EditText
    private lateinit var btnSave: MaterialButton
    private lateinit var progressSave: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvCooldown: TextView

    /** The two "use for" rows, bound once and repainted from the service. */
    private lateinit var useForRows: List<UseForRow>

    /** The health check in flight. A new check cancels it, so only the
     *  latest check writes the status row and ends Save's busy state. */
    private var healthJob: Job? = null

    override fun onContentCreated(savedInstanceState: Bundle?) {
        etKey = findViewById(R.id.etCloudKey)
        btnSave = findViewById(R.id.btnCloudSave)
        progressSave = findViewById(R.id.progressCloudSave)
        tvStatus = findViewById(R.id.tvCloudStatus)
        tvCooldown = findViewById(R.id.tvCloudCooldown)

        useForRows = listOf(
            bindUseForRow(
                R.id.rowCloudOcr, R.string.pt_cloud_ocr_toggle_title, R.string.pt_cloud_ocr_toggle_subtitle,
                isOn = { PtCloudService.config.ocrEnabled }, write = PtCloudService::setOcrEnabled,
            ),
            bindUseForRow(
                R.id.rowCloudTranslate, R.string.pt_cloud_translate_toggle_title,
                R.string.pt_cloud_translate_toggle_subtitle,
                isOn = { PtCloudService.config.translateEnabled }, write = PtCloudService::setTranslateEnabled,
            ),
        )
        // Seeded from prefs; a recreate then restores whatever was typed
        // but not saved (EditText saves its text), as on the LLM page.
        etKey.setText(Prefs(this).ptCloudClientKey)
        btnSave.setOnClickListener { onSave() }
        // The switches and the cooldown line follow the service: each
        // gesture (a switch here or on another surface, Save, a running
        // health check) counts on PtCloudService.changes, and the StateFlow
        // replays to this collector on each resume.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                PtCloudService.changes.collect {
                    useForRows.forEach { it.render() }
                    renderCooldown()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkHealth(fromSave = false)
    }

    /** One "use for" switch row: its switch, what it reads and what it
     *  writes. [render] repaints the row from the service with the listener
     *  detached, so a repaint never writes (the services row's rule): with
     *  no usable key saved the row is disabled and its switch off, whatever
     *  is stored (nothing is, since a cleared key turns both switches off),
     *  so the key field below is the first step. */
    private class UseForRow(
        private val row: View,
        private val toggle: MaterialSwitch,
        private val isOn: () -> Boolean,
        private val write: (Boolean) -> Unit,
    ) {
        fun render() {
            val hasKey = PtCloudService.config.hasKey
            toggle.setOnCheckedChangeListener(null)
            row.isEnabled = hasKey
            row.alpha = if (hasKey) 1f else DISABLED_ROW_ALPHA
            toggle.isEnabled = hasKey
            toggle.isChecked = hasKey && isOn()
            toggle.setOnCheckedChangeListener { _, checked -> write(checked) }
        }
    }

    private fun bindUseForRow(
        rowId: Int,
        titleRes: Int,
        subtitleRes: Int,
        isOn: () -> Boolean,
        write: (Boolean) -> Unit,
    ): UseForRow {
        val row = findViewById<View>(rowId)
        row.findViewById<TextView>(R.id.tvRowTitle).setText(titleRes)
        row.findViewById<TextView>(R.id.tvRowSubtitle).apply {
            setText(subtitleRes)
            isVisible = true
        }
        val toggle = row.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        // Seeded with the listener detached (render attaches it after): seeding
        // through it would reset the service's cooldown every time the page
        // is built or restored. SINGLE persistence path, as on the History
        // page: the row tap only toggles, and every change funnels through
        // the listener. A disabled row takes no touch; the guard keeps a
        // programmatic click (a test's) from writing through a disabled
        // switch too, so the no-key rule does not rest on touch dispatch.
        row.setOnClickListener { if (toggle.isEnabled) toggle.toggle() }
        return UseForRow(row, toggle, isOn, write).also { it.render() }
    }

    private fun onSave() {
        PtCloudService.saveClientKey(etKey.text.toString().trim())
        checkHealth(fromSave = true)
    }

    /** Asks the service's `GET /health` and shows the answer. [fromSave]
     *  puts the Save button in its busy state until the answer lands (a
     *  resume check leaves it alone, so a slow check never blocks a Save). */
    private fun checkHealth(fromSave: Boolean) {
        healthJob?.cancel()
        tvStatus.setText(R.string.pt_cloud_status_checking)
        setSaving(fromSave)
        healthJob = lifecycleScope.launch {
            val status = healthOverrideForTest?.invoke() ?: PtCloudService.client.health()
            tvStatus.text = healthStatusText(this@PtCloudSettingsActivity, status)
            // A running service ends a cooldown recorded while it was
            // stopped (a pod start is no gesture the app can see; this check
            // is where the app learns of it).
            if (status == HealthStatus.Running) PtCloudService.onHealthRunning()
            setSaving(false)
        }
    }

    /** While saving, the button is blanked and disabled and the spinner
     *  sits on it, as on the LLM page. */
    private fun setSaving(saving: Boolean) {
        btnSave.text = if (saving) "" else getString(R.string.btn_save)
        btnSave.isEnabled = !saving
        progressSave.isVisible = saving
    }

    /** The cooldown line: shown while the service's cooldown runs (an
     *  expired one reads as null). */
    private fun renderCooldown() {
        val until = PtCloudService.unavailableUntil()
        if (until == null) {
            tvCooldown.isGone = true
            return
        }
        tvCooldown.text = cooldownLine(this, PtCloudService.unavailableDescription(), until)
        tvCooldown.isVisible = true
    }

    companion object {
        /** Test seam: answers the status check in place of `GET /health`, so
         *  a JVM test of the page never reaches the service. Never set in
         *  production. */
        @Volatile internal var healthOverrideForTest: (suspend () -> HealthStatus)? = null

        /** A "use for" row's alpha while no key is saved: the Settings
         *  sheet's dimming for a control that is currently out of reach. */
        private const val DISABLED_ROW_ALPHA = 0.5f
    }
}

/** The status row's value for a health answer. */
internal fun healthStatusText(context: Context, status: HealthStatus): String = when (status) {
    HealthStatus.Running -> context.getString(R.string.pt_cloud_status_running)
    HealthStatus.Starting -> context.getString(R.string.pt_cloud_status_starting)
    HealthStatus.NotRunning -> context.getString(R.string.pt_cloud_status_not_running)
    HealthStatus.Unreachable -> context.getString(R.string.pt_cloud_status_unreachable)
    // The code as plain digits (not %d, which a locale could render in its
    // own numerals): it is a protocol value, not a quantity.
    is HealthStatus.Other -> context.getString(R.string.pt_cloud_status_other, status.code.toString())
}
