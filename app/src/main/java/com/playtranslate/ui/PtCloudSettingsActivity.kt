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
 * cell that opens it): the service's switch, its client key and its status.
 * The translation-error pill's gear opens it too, for a PlayTranslate Cloud
 * error. The service's address is fixed ([PtCloudClient]); the page has no
 * field for it.
 *
 *  - The switch goes through the service's one mutation
 *    ([PtCloudService.setEnabled], which the service's row on the
 *    Translation services page calls too): it stores [Prefs.ptCloudEnabled]
 *    and resets the service (its cooldown and the pill's errors), since
 *    either direction means "try it again now". Seeded before its listener
 *    is attached, so building or restoring the page writes nothing and
 *    resets nothing. The cooldown line below the status repaints from the
 *    service's own change signal ([PtCloudService.changes]), on each
 *    gesture and each resume, as the row does; the page repaints nothing
 *    after a mutation itself.
 *  - Save persists the key, resets the service
 *    ([PtCloudService.onCredentialsSaved]) and checks health. Unlike the LLM
 *    page, the page stays open after Save: it is where the status shows.
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

    /** The health check in flight. A new check cancels it, so only the
     *  latest check writes the status row and ends Save's busy state. */
    private var healthJob: Job? = null

    override fun onContentCreated(savedInstanceState: Bundle?) {
        etKey = findViewById(R.id.etCloudKey)
        btnSave = findViewById(R.id.btnCloudSave)
        progressSave = findViewById(R.id.progressCloudSave)
        tvStatus = findViewById(R.id.tvCloudStatus)
        tvCooldown = findViewById(R.id.tvCloudCooldown)

        bindMasterToggle()
        // Seeded from prefs; a recreate then restores whatever was typed
        // but not saved (EditText saves its text), as on the LLM page.
        etKey.setText(Prefs(this).ptCloudClientKey)
        btnSave.setOnClickListener { onSave() }
        // The cooldown line follows the service: each gesture (the switch,
        // Save, a running health check) counts on PtCloudService.changes,
        // and the StateFlow replays to this collector on each resume.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                PtCloudService.changes.collect { renderCooldown() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkHealth(fromSave = false)
    }

    private fun bindMasterToggle() {
        val row = findViewById<View>(R.id.rowCloudToggle)
        row.findViewById<TextView>(R.id.tvRowTitle).setText(R.string.pt_cloud_toggle_title)
        row.findViewById<TextView>(R.id.tvRowSubtitle).apply {
            setText(R.string.pt_cloud_toggle_subtitle)
            isVisible = true
        }
        val toggle = row.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        // Seed BEFORE attaching the listener: seeding through it would reset
        // the service's cooldown every time the page is built or restored.
        toggle.isChecked = Prefs(this).ptCloudEnabled
        // SINGLE persistence path, as on the History page: the row tap only
        // toggles, and every change funnels through this listener.
        toggle.setOnCheckedChangeListener { _, checked -> PtCloudService.setEnabled(checked) }
        row.setOnClickListener { toggle.toggle() }
    }

    private fun onSave() {
        Prefs(this).ptCloudClientKey = etKey.text.toString().trim()
        PtCloudService.onCredentialsSaved()
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
