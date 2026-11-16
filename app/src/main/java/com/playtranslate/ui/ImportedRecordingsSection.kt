package com.playtranslate.ui

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.playtranslate.PlayTranslateApplication
import com.playtranslate.R
import com.playtranslate.audio.localaudio.LocalAudioDb
import com.playtranslate.audio.localaudio.LocalAudioImportResult
import com.playtranslate.audio.localaudio.LocalAudioImportStep
import com.playtranslate.audio.localaudio.LocalAudioStore
import com.playtranslate.themeColor
import com.playtranslate.translation.llm.humanSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The Imported recordings group at the foot of the Text-to-speech page: an
 * `android.db` file of human recordings ([LocalAudioStore]), which then
 * speaks every word it has a recording of
 * ([com.playtranslate.audio.sources.LocalAudioSource]).
 *
 * The group is one of two things. With no file: the row that picks one and
 * copies it in, under a progress popup whose Cancel ends the copy and
 * leaves nothing behind. With a file: its name and size, with a trash
 * button at the row's end that removes it, after asking, and under the
 * size whatever is known to be wrong with reading it. There is no switch and nothing to save: the file being here
 * is the whole setting. The app ships no such file and says nowhere where
 * one is found; the user brings their own.
 *
 * The copy runs for as long as the page lives, as every import and download
 * in the app does. The manifest has the page handle a rotation itself, so
 * turning the device does not end it.
 *
 * [pickFile] opens the system's document picker; the page owns it, since a
 * picker is registered by an activity, and hands the pick to [startImport].
 */
internal class ImportedRecordingsSection(
    private val activity: AppCompatActivity,
    private val rows: LinearLayout,
    private val pickFile: () -> Unit,
) {

    private var importJob: Job? = null
    private var checkJob: Job? = null

    // ── The group ────────────────────────────────────────────────────────

    fun render() {
        checkJob?.cancel()
        rows.removeAllViews()
        val inflater = LayoutInflater.from(activity)
        if (!LocalAudioStore.isImported(activity)) {
            val load = inflater.inflate(R.layout.settings_row_nav, rows, false)
            load.findViewById<TextView>(R.id.tvRowTitle).text = activity.getString(R.string.local_audio_load_title)
            load.findViewById<TextView>(R.id.tvRowSubtitle).apply {
                text = activity.getString(R.string.local_audio_load_subtitle)
                isVisible = true
            }
            load.setOnClickListener { pickFile() }
            rows.addView(load)
            return
        }
        // The loaded file: what it is and how large, and at its end the
        // trash that removes it. The row itself is nothing to tap.
        val file = inflater.inflate(R.layout.settings_row_removable, rows, false)
        file.findViewById<TextView>(R.id.tvRowTitle).text = activity.getString(R.string.audio_source_local_name)
        file.findViewById<TextView>(R.id.tvRowSubtitle).text =
            humanSize(activity, LocalAudioStore.sizeBytes(activity))
        file.findViewById<View>(R.id.ivRowDelete).apply {
            contentDescription = activity.getString(R.string.local_audio_remove)
            setOnClickListener { confirmRemove() }
        }
        rows.addView(file)

        // What is known to be wrong with the file, under its size: a read
        // that failed since it was loaded, and, asked now, a file that no
        // longer opens. Without this a damaged file only ever looks like
        // one with fewer words in it.
        val warning = file.findViewById<TextView>(R.id.tvRowWarning)
        checkJob = activity.lifecycleScope.launch {
            LocalAudioDb.check(activity)
            showProblem(warning)
        }
    }

    private fun showProblem(warning: TextView) {
        val problem = LocalAudioDb.problem
        warning.isVisible = problem != null
        warning.text = problem?.let { activity.getString(R.string.local_audio_read_problem, it) }
    }

    // ── Loading a file ───────────────────────────────────────────────────

    /**
     * Copies the picked document in ([LocalAudioStore.import]) under a
     * progress popup. Cancel, Back and the page going away all end the
     * copy, and the store deletes what it had copied. A failure is said in
     * an alert with its cause; a cancel says nothing.
     */
    fun startImport(uri: Uri) {
        if (importJob?.isActive == true) return
        val progress = OverlayProgress.Builder(activity)
            .setTitle(activity.getString(R.string.local_audio_importing_title))
            // What a load does first is look at the file's head.
            .setMessage(activity.getString(R.string.local_audio_checking_message))
            .setCancelLabel(activity.getString(R.string.btn_cancel))
            .setOnDismiss { importJob?.cancel() }
            .show()
        progress.setIndeterminate(true)
        importJob = activity.lifecycleScope.launch {
            val result = try {
                LocalAudioStore.import(activity, uri) { step ->
                    activity.runOnUiThread { showStep(progress, step) }
                }
            } finally {
                progress.dismiss()
                // However the load ended, a cancel included: the group
                // shows what is on disk, never what it expected to be.
                render()
            }
            if (result == LocalAudioImportResult.Imported) {
                // What the file holds, for the log. On the app's scope: it
                // reads for seconds, and leaving the page must not cut it.
                val app = activity.applicationContext
                (activity.application as PlayTranslateApplication).appScope.launch {
                    LocalAudioStore.logSummary(app)
                }
            }
            showFailure(result)
        }
    }

    private fun showStep(progress: OverlayProgress, step: LocalAudioImportStep) {
        val percent = stepPercent(step)
        if (percent != null) progress.setProgress(percent) else progress.setIndeterminate(true)
        progress.setMessage(stepMessage(step))
    }

    /** Where the popup's bar stands for [step], or null for a bar that only
     *  moves: the check, and a copy of a file whose size neither the picker
     *  nor the file gave. */
    @VisibleForTesting
    internal fun stepPercent(step: LocalAudioImportStep): Int? = when (step) {
        is LocalAudioImportStep.Copying ->
            if (step.totalBytes > 0) {
                ((step.copiedBytes * 100) / step.totalBytes).toInt().coerceAtMost(100)
            } else {
                null
            }
        LocalAudioImportStep.Checking -> null
    }

    /** What the popup says for [step]. */
    @VisibleForTesting
    internal fun stepMessage(step: LocalAudioImportStep): String = when (step) {
        is LocalAudioImportStep.Copying ->
            if (step.totalBytes > 0) {
                activity.getString(
                    R.string.local_audio_copying_message,
                    humanSize(activity, step.copiedBytes),
                    humanSize(activity, step.totalBytes),
                )
            } else {
                // The count alone: there is no whole to set it against.
                humanSize(activity, step.copiedBytes)
            }
        LocalAudioImportStep.Checking -> activity.getString(R.string.local_audio_checking_message)
    }

    private fun showFailure(result: LocalAudioImportResult) {
        val message = when (result) {
            LocalAudioImportResult.Imported -> return
            LocalAudioImportResult.NotSqlite -> activity.getString(R.string.local_audio_error_not_database)
            is LocalAudioImportResult.InvalidFormat ->
                activity.getString(R.string.local_audio_error_invalid_format, result.reason)
            is LocalAudioImportResult.Incomplete -> activity.getString(
                R.string.local_audio_error_incomplete,
                humanSize(activity, result.haveBytes),
                humanSize(activity, result.expectedBytes),
            )
            is LocalAudioImportResult.InsufficientSpace -> activity.getString(
                R.string.model_refused_storage,
                humanSize(activity, result.neededBytes),
                humanSize(activity, result.freeBytes),
            )
            // The system's own words under ours: they are the only
            // evidence of why a copy stopped.
            is LocalAudioImportResult.IoError -> listOfNotNull(
                activity.getString(R.string.local_audio_error_copy),
                result.message?.ifBlank { null },
            ).joinToString("\n\n")
        }
        OverlayAlert.Builder(activity)
            .setTitle(activity.getString(R.string.local_audio_import_failed_title))
            .setMessage(message)
            .addCancelButton(activity.getString(R.string.btn_ok))
            .show()
    }

    // ── Removing it ──────────────────────────────────────────────────────

    private fun confirmRemove() {
        OverlayAlert.Builder(activity)
            .setTitle(activity.getString(R.string.local_audio_remove_confirm_title))
            .setMessage(activity.getString(R.string.local_audio_remove_confirm_message))
            .addButton(
                activity.getString(R.string.local_audio_remove),
                activity.themeColor(R.attr.ptDanger),
                activity.themeColor(R.attr.ptAccentOn),
            ) { removeFile() }
            .addCancelButton(activity.getString(R.string.btn_cancel))
            .show()
    }

    /** The confirmed removal: the file, and the clips read from it. */
    private fun removeFile() {
        activity.lifecycleScope.launch {
            LocalAudioStore.remove(activity)
            render()
        }
    }
}
