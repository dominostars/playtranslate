package com.playtranslate.ui

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.R
import com.playtranslate.themeColor

/**
 * Backend-agnostic binder for a [R.layout.settings_row_switch_download] row: title +
 * subtitle, a download-state icon (cloud-down / spinner / downloaded) left of a
 * MaterialSwitch, the whole row being the tap target. Decouples the download-toggle
 * cell from any specific backend registry — unlike [TranslationServicesBinder]'s
 * offline rows, which are hard-wired to the TranslationBackend registry + the MNN
 * translator. First consumer: the "Use MangaOCR" OCR cell.
 *
 * All state is re-derived from the caller's predicates on each [render], so the row
 * survives the host's onResume rebuild. Tap routing mirrors the offline rows:
 *   - on            -> [onDisable] (e.g. a keep/delete dialog); switch reflects state
 *   - installed,off -> [onEnable]
 *   - absent        -> [onDownload] (the switch stays off until the install completes)
 */
class DownloadableToggleRow(
    private val row: View,
    title: CharSequence,
    subtitle: CharSequence,
    private val isInstalled: () -> Boolean,
    private val isOn: () -> Boolean,
    private val isDownloading: () -> Boolean,
    private val onDownload: () -> Unit,
    private val onEnable: () -> Unit,
    private val onDisable: () -> Unit,
) {
    private val icon = row.findViewById<ImageView>(R.id.ivStatusIcon)
    private val progress = row.findViewById<ProgressBar>(R.id.pbStatusDownloading)
    private val switch = row.findViewById<MaterialSwitch>(R.id.switchRowToggle)

    init {
        row.findViewById<TextView>(R.id.tvRowTitle).text = title
        row.findViewById<TextView>(R.id.tvRowSubtitle).apply {
            text = subtitle
            isVisible = subtitle.isNotEmpty()
        }
        row.setOnClickListener {
            when {
                isOn() -> onDisable()
                isInstalled() -> onEnable()
                else -> onDownload()
            }
            render()
        }
        render()
    }

    /** Re-paint icon + switch from the current predicate state. Cheap; safe to call
     *  after any state change (download start/finish, enable/disable). */
    fun render() {
        if (isDownloading()) {
            icon.isGone = true
            progress.isVisible = true
        } else {
            progress.isGone = true
            icon.isVisible = true
            val installed = isInstalled()
            icon.setImageResource(
                if (installed) R.drawable.ic_status_downloaded else R.drawable.ic_status_cloud_down,
            )
            ImageViewCompat.setImageTintList(
                icon,
                if (installed) null else ColorStateList.valueOf(row.context.themeColor(R.attr.ptTextMuted)),
            )
        }
        // Reflects (model present) AND (user pref) — never just the pref — so a row
        // whose pack is missing reads as off even if the pref lingers.
        switch.isChecked = isOn()
    }
}
