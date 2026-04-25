package com.playtranslate.ui

import android.content.DialogInterface
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.playtranslate.AnkiManager
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.language.SourceLangId

/**
 * Lightweight activity that hosts [WordAnkiReviewSheet] when launched from
 * the floating overlay popup. Separate from MainActivity so that pressing
 * back finishes only this activity — without affecting the floating icon.
 */
class WordAnkiReviewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)

        // Hide our own UI from accessibility screenshots (see MainActivity
        // for the full rationale — prevents OCR feedback loop in multi-window).
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        if (savedInstanceState != null) {
            // Sheet is already restored by the FragmentManager — attach dismiss listener
            val existing = supportFragmentManager.findFragmentByTag(WordAnkiReviewSheet.TAG)
            (existing as? WordAnkiReviewSheet)?.onDismissListener = DialogInterface.OnDismissListener { finish() }
            return
        }

        val word = intent.getStringExtra(EXTRA_WORD) ?: run { finish(); return }
        val reading = intent.getStringExtra(EXTRA_READING) ?: ""
        val pos = intent.getStringExtra(EXTRA_POS) ?: ""
        val definition = intent.getStringExtra(EXTRA_DEFINITION) ?: ""
        val freqScore = intent.getIntExtra(EXTRA_FREQ_SCORE, 0)
        val screenshotPath = intent.getStringExtra(EXTRA_SCREENSHOT_PATH)
        val sentenceOriginal = intent.getStringExtra(EXTRA_SENTENCE_ORIGINAL)
        val sentenceTranslation = intent.getStringExtra(EXTRA_SENTENCE_TRANSLATION)
        val sourceLangId = SourceLangId.fromCode(intent.getStringExtra(EXTRA_SOURCE_LANG))
            ?: Prefs(applicationContext).sourceLangId

        if (!AnkiManager(this).hasPermission()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.anki_permission_rationale_title)
                .setMessage(R.string.anki_permission_rationale_message)
                .setPositiveButton(R.string.btn_continue) { _, _ ->
                    androidx.core.app.ActivityCompat.requestPermissions(
                        this, arrayOf(AnkiManager.PERMISSION), 0
                    )
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
            return
        }

        // Read word results from cache if sentence context matches
        val cachedWordResults = if (sentenceOriginal != null
            && LastSentenceCache.original == sentenceOriginal
        ) LastSentenceCache.wordResults else null

        showReviewSheet(word, reading, pos, definition, freqScore, screenshotPath,
            sentenceOriginal, sentenceTranslation, cachedWordResults, sourceLangId)
    }

    private fun showReviewSheet(
        word: String, reading: String, pos: String,
        definition: String, freqScore: Int, screenshotPath: String?,
        sentenceOriginal: String?, sentenceTranslation: String?,
        sentenceWordResults: Map<String, Triple<String, String, Int>>? = null,
        sourceLangId: SourceLangId = SourceLangId.JA
    ) {
        val sheet = WordAnkiReviewSheet.newInstance(
            word, reading, pos, definition, screenshotPath,
            freqScore = freqScore,
            sentenceOriginal = sentenceOriginal,
            sentenceTranslation = sentenceTranslation,
            sentenceWordResults = sentenceWordResults,
            sourceLangId = sourceLangId
        )
        sheet.onDismissListener = DialogInterface.OnDismissListener { finish() }
        sheet.show(supportFragmentManager, WordAnkiReviewSheet.TAG)
    }

    private fun applyTheme() {
        com.playtranslate.applyTheme(this)
    }

    companion object {
        const val EXTRA_WORD = "extra_word"
        const val EXTRA_READING = "extra_reading"
        const val EXTRA_POS = "extra_pos"
        const val EXTRA_DEFINITION = "extra_definition"
        const val EXTRA_SCREENSHOT_PATH = "extra_screenshot_path"
        const val EXTRA_FREQ_SCORE = "extra_freq_score"
        const val EXTRA_SENTENCE_ORIGINAL = "extra_sentence_original"
        const val EXTRA_SENTENCE_TRANSLATION = "extra_sentence_translation"
        const val EXTRA_SOURCE_LANG = "extra_source_lang"
    }
}
