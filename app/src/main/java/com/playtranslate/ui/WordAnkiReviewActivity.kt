package com.playtranslate.ui

import android.content.DialogInterface
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.playtranslate.Prefs
import com.playtranslate.language.SourceLangId

/**
 * Opaque activity that hosts [WordAnkiReviewSheet]. Separate from
 * MainActivity so that pressing back finishes only this activity — without
 * affecting the floating icon.
 *
 * Reached only through [AnkiPermissionActivity], which guarantees the
 * AnkiDroid permission is held before forwarding here — so this activity
 * just builds the review sheet from the launch intent.
 */
class WordAnkiReviewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)

        // Hide our own UI from accessibility screenshots (see MainActivity
        // for the full rationale — prevents OCR feedback loop in multi-window).
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // Register before any early-return so [finishCurrentIfAny] can
        // reach this instance even after a saved-state restore.
        tracker.bind(this)

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

    override fun onDestroy() {
        tracker.unbind(this)
        super.onDestroy()
    }

    private fun applyTheme() {
        com.playtranslate.applyTheme(this)
    }

    companion object {
        /** See [CurrentActivityTracker] — the drag-flow launch path calls
         *  [finishCurrentIfAny] to dismiss the previous sheet before
         *  starting a new one, so MULTIPLE_TASK doesn't leave the old
         *  sheet orphaned in a hidden task. */
        private val tracker = CurrentActivityTracker<WordAnkiReviewActivity>()
        fun finishCurrentIfAny() = tracker.finishCurrent()

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
