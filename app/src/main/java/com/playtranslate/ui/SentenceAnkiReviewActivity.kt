package com.playtranslate.ui

import android.content.DialogInterface
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.playtranslate.Prefs
import com.playtranslate.language.SourceLangId

/**
 * Opaque activity that hosts [AnkiReviewBottomSheet] (the SENTENCE review).
 * Mirrors [WordAnkiReviewActivity], but for the whole captured sentence rather
 * than a single word.
 *
 * Reached from the over-game capture panel ([CaptureResultOverlay]): the panel
 * is a window, not a fragment host, so its card-level "add to Anki" button can't
 * show the [AnkiReviewBottomSheet] DialogFragment directly the way the in-app
 * results page does — it launches this Activity instead, which builds the sheet
 * from the launch intent.
 */
class SentenceAnkiReviewActivity : AppCompatActivity() {

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
            val existing = supportFragmentManager.findFragmentByTag(AnkiReviewBottomSheet.TAG)
            (existing as? AnkiReviewBottomSheet)?.onDismissListener =
                DialogInterface.OnDismissListener { finish() }
            return
        }

        val sentence = intent.getStringExtra(EXTRA_SENTENCE) ?: run { finish(); return }
        val translation = intent.getStringExtra(EXTRA_TRANSLATION) ?: ""
        val screenshotPath = intent.getStringExtra(EXTRA_SCREENSHOT_PATH)
        val sourceLangId = SourceLangId.fromCode(intent.getStringExtra(EXTRA_SOURCE_LANG))
            ?: Prefs(applicationContext).sourceLangId

        // Reconstruct the per-word results by zipping the parallel arrays, exactly
        // like TranslationResultActivity unpacks the drag-sentence extras. Missing /
        // empty arrays → empty map (a sentence card with no word rows).
        val wordResults: Map<String, Triple<String, String, Int>> =
            intent.getStringArrayExtra(EXTRA_WORDS)?.let { words ->
                val readings = intent.getStringArrayExtra(EXTRA_READINGS) ?: emptyArray()
                val meanings = intent.getStringArrayExtra(EXTRA_MEANINGS) ?: emptyArray()
                val freqScores = intent.getIntArrayExtra(EXTRA_FREQ_SCORES) ?: IntArray(0)
                words.mapIndexed { i, w ->
                    w to Triple(
                        readings.getOrElse(i) { "" },
                        meanings.getOrElse(i) { "" },
                        freqScores.getOrElse(i) { 0 },
                    )
                }.toMap()
            } ?: emptyMap()

        // Surfaces ride parallel to EXTRA_WORDS; enrichment as one Serializable
        // map. Both are the launcher's atomic snapshot — see AnkiReviewBottomSheet
        // .newInstance for why they don't come from the global LastSentenceCache.
        val surfaceForms: Map<String, String> =
            intent.getStringArrayExtra(EXTRA_WORDS)?.let { words ->
                val surfaces = intent.getStringArrayExtra(EXTRA_SURFACES) ?: emptyArray()
                words.mapIndexedNotNull { i, w ->
                    surfaces.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { w to it }
                }.toMap()
            } ?: emptyMap()
        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        val wordEnrichment: Map<String, WordEnrichment> =
            (intent.getSerializableExtra(EXTRA_ENRICHMENT) as? HashMap<String, WordEnrichment>)
                ?: emptyMap()

        showReviewSheet(sentence, translation, wordResults, surfaceForms,
            wordEnrichment, screenshotPath, sourceLangId)
    }

    private fun showReviewSheet(
        sentence: String,
        translation: String,
        wordResults: Map<String, Triple<String, String, Int>>,
        surfaceForms: Map<String, String>,
        wordEnrichment: Map<String, WordEnrichment>,
        screenshotPath: String?,
        sourceLangId: SourceLangId,
    ) {
        val sheet = AnkiReviewBottomSheet.newInstance(
            original = sentence,
            translation = translation,
            wordResults = wordResults,
            surfaceForms = surfaceForms,
            wordEnrichment = wordEnrichment,
            screenshotPath = screenshotPath,
            sourceLangId = sourceLangId,
        )
        sheet.onDismissListener = DialogInterface.OnDismissListener { finish() }
        sheet.show(supportFragmentManager, AnkiReviewBottomSheet.TAG)
    }

    override fun onDestroy() {
        tracker.unbind(this)
        super.onDestroy()
    }

    private fun applyTheme() {
        com.playtranslate.applyTheme(this)
    }

    companion object {
        /** See [CurrentActivityTracker] — the overlay launch path calls
         *  [finishCurrentIfAny] to dismiss the previous sheet before
         *  starting a new one, so MULTIPLE_TASK doesn't leave the old
         *  sheet orphaned in a hidden task. */
        private val tracker = CurrentActivityTracker<SentenceAnkiReviewActivity>()
        fun finishCurrentIfAny() = tracker.finishCurrent()

        const val EXTRA_SENTENCE = "extra_sentence"
        const val EXTRA_TRANSLATION = "extra_translation"
        const val EXTRA_SCREENSHOT_PATH = "extra_screenshot_path"
        const val EXTRA_SOURCE_LANG = "extra_source_lang"
        const val EXTRA_WORDS = "extra_words"
        const val EXTRA_READINGS = "extra_readings"
        const val EXTRA_MEANINGS = "extra_meanings"
        const val EXTRA_FREQ_SCORES = "extra_freq_scores"
        const val EXTRA_SURFACES = "extra_surfaces"
        const val EXTRA_ENRICHMENT = "extra_enrichment"
    }
}
