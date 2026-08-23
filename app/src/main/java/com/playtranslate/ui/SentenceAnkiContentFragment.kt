package com.playtranslate.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.playtranslate.R
import com.playtranslate.audio.AudioSelection
import com.playtranslate.language.SourceLangId

/**
 * Fragment shell for the sentence-card Anki editor. The card itself —
 * Original/Translation/Words/Screenshot sections, the game-audio trim
 * panel, the styled word rows — lives in [SentenceAnkiContentView], shared
 * with the floating workspace's editor page. This shell keeps the Fragment
 * work: the ActivityResult launcher for the audio picker, the
 * saved-state/process-death plumbing for the game-audio cell, the
 * teardown-finality decision for the snapshot file, and the parent-sheet
 * screenshot mirror. Embedded by [AnkiReviewBottomSheet] (sentence-only)
 * and the Sentence side of [WordAnkiReviewSheet].
 */
class SentenceAnkiContentFragment : Fragment() {

    private var content: SentenceAnkiContentView? = null

    // ── Public API, forwarded (the hosting sheets' seam) ─────────────────

    val selectedWords: Set<String> get() = content?.selectedWords ?: emptySet()

    /** Whether the sentence-audio switch is on. Read by the host sheet
     *  at send time; false when the toggle wasn't built. */
    val sentenceAudioEnabled: Boolean get() = content?.sentenceAudioEnabled == true

    /** See [SentenceAnkiContentView.onOriginalCommitted]. May be set before
     *  or after view creation — the shell forwards through a stable hook. */
    var onOriginalCommitted: ((newText: String) -> Unit)? = null

    fun getCardData(): SentenceAnkiContentView.CardData = content!!.getCardData()

    fun resolveGameAudioForSend(): Boolean = content?.resolveGameAudioForSend() ?: true

    fun removeScreenshotFromUi() {
        content?.removeScreenshotFromUi()
    }

    fun applyTranslation(forOriginal: String, text: String?) {
        content?.applyTranslation(forOriginal, text)
    }

    fun applyWords(
        forOriginal: String,
        entries: List<SentenceAnkiHtmlBuilder.WordEntry>,
        targetWord: String?,
    ) {
        content?.applyWords(forOriginal, entries, targetWord)
    }

    // ── Fragment plumbing ────────────────────────────────────────────────

    private var pendingPickerCallback: ((AudioSelection) -> Unit)? = null

    private val audioPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = pendingPickerCallback.also { pendingPickerCallback = null }
            ?: return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        cb(SentenceAnkiContentView.parsePickerResult(result.data))
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_sentence_anki_content, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = arguments ?: return
        val c = SentenceAnkiContentView(
            requireContext(),
            viewLifecycleOwner.lifecycleScope,
            // The fragment's OWN arguments bundle: the view writes fetched
            // translation/words back into it, so a re-created fragment
            // rebuilds from current state — unchanged semantics.
            args,
            FragmentHost(),
        )
        content = c
        c.onOriginalCommitted = { newText -> onOriginalCommitted?.invoke(newText) }
        c.buildInto(view as LinearLayout, savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        // This card's buffer is the "active" snapshot again — the audio
        // picker and trim-editor fallback resolve Game audio against it.
        content?.onHostResumed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        content?.saveState(outState)
    }

    override fun onDestroyView() {
        // Snapshot-file finality: delete only when the activity is finishing
        // (finished activities are never restored) or the teardown ran with
        // no state saved (plain dismissal while resumed). Everything else is
        // a potential saved-state destroy and MUST keep the file — see
        // [SentenceAnkiContentView.release]'s contract and the long
        // rationale it preserves.
        content?.release(
            deleteSnapshotFile = activity?.isFinishing == true || !isStateSaved,
        )
        content = null
        super.onDestroyView()
    }

    private inner class FragmentHost : SentenceAnkiContentView.Host {
        override val isAlive: Boolean get() = isAdded

        override fun openAudioPicker(intent: Intent, onPicked: (AudioSelection) -> Unit) {
            pendingPickerCallback = onPicked
            audioPickerLauncher.launch(intent)
        }

        override fun onScreenshotRemoved() {
            // Mirror the removal back into the word tab when this fragment
            // lives under WordAnkiReviewSheet — the two tabs share the same
            // source media and would otherwise get out of sync.
            (parentFragment as? WordAnkiReviewSheet)?.notifyScreenshotRemoved()
        }
    }

    companion object {
        fun newInstance(
            japanese: String,
            translation: String,
            words: List<SentenceAnkiHtmlBuilder.WordEntry>,
            screenshotPath: String?,
            targetWord: String? = null,
            sourceLangId: SourceLangId = SourceLangId.JA,
            wordsLoading: Boolean = false,
            audioAnchorMs: Long? = null,
        ) = SentenceAnkiContentFragment().apply {
            arguments = SentenceAnkiContentView.buildArgs(
                japanese, translation, words, screenshotPath,
                targetWord, sourceLangId, wordsLoading, audioAnchorMs,
            )
        }
    }
}
