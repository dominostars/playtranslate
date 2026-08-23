package com.playtranslate.ui

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.playtranslate.AnkiManager
import com.playtranslate.R
import com.playtranslate.applyAccentOverlay
import com.playtranslate.applyDialogEdgeToEdge
import com.playtranslate.audio.AudioSelection
import com.playtranslate.fullScreenDialogTheme
import com.playtranslate.language.SourceLangId

/**
 * DialogFragment shell for the word/sentence Anki review editor. The editor
 * itself — headword header, curatable definitions, the mode toggle, both
 * send paths — lives in [WordAnkiReviewBinder], shared with the floating
 * workspace's editor page. This shell keeps the Fragment work: the dialog
 * window, insets, the back button, the ActivityResult launcher for the
 * audio picker, the picker-dialog presentations, the saved-state plumbing,
 * and the teardown-finality decision.
 */
class WordAnkiReviewSheet : DialogFragment() {

    private var binder: WordAnkiReviewBinder? = null

    /** Optional listener called when this sheet is dismissed (used by WordAnkiReviewActivity). */
    var onDismissListener: DialogInterface.OnDismissListener? = null

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissListener?.onDismiss(dialog)
    }

    override fun getTheme(): Int = fullScreenDialogTheme(requireContext())

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        applyAccentOverlay(dialog.context.theme, requireContext())
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_word_anki_review, container, false)

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(R.style.AnimSlideRight)
            applyDialogEdgeToEdge(this, requireContext())
        }
    }

    override fun onResume() {
        super.onResume()
        // The sentence content's game-audio buffer is the "active" snapshot
        // again (picker / trim-editor round-trips).
        binder?.onHostResumed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binder?.saveState(outState)
    }

    override fun onDestroyView() {
        // Finality: release the screenshot pin (and the content's game-audio
        // snapshot) only when the activity is finishing or the teardown ran
        // with no state saved — a saved-state destroy must keep both files
        // for the restored instance. Same contract as before the extraction.
        binder?.release(releasePin = isFinalMediaTeardown())
        binder = null
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(sys.left, sys.top, sys.right, maxOf(sys.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        view.findViewById<View>(R.id.btnBackWordAnki).setOnClickListener { dismiss() }

        val args = arguments ?: return
        val b = WordAnkiReviewBinder(
            requireContext(),
            viewLifecycleOwner.lifecycleScope,
            args,
            SheetHost(),
        )
        binder = b
        b.bind(view, savedInstanceState)
    }

    // ── Audio picker (one launcher serves the word tab AND the sentence
    //    content's cells, via the shared Host seam) ──────────────────────

    private var pendingPickerCallback: ((AudioSelection) -> Unit)? = null

    private val audioPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = pendingPickerCallback.also { pendingPickerCallback = null }
            ?: return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        cb(SentenceAnkiContentView.parsePickerResult(result.data))
    }

    private inner class SheetHost : WordAnkiReviewBinder.Host {
        override val isAlive: Boolean get() = isAdded

        override fun openAudioPicker(intent: Intent, onPicked: (AudioSelection) -> Unit) {
            pendingPickerCallback = onPicked
            audioPickerLauncher.launch(intent)
        }

        override fun openDeckPicker(onPicked: (Long, String) -> Unit) {
            showAnkiDeckPicker(onPicked)
        }

        override fun openCardTypePicker(onPicked: (Long, String) -> Unit) {
            showAnkiCardTypePicker(CardMode.WORD, onPicked)
        }

        override fun openFieldMapping(model: AnkiManager.ModelInfo, mode: CardMode) {
            showAnkiCardTypeMappingDialog(model, mode) { _, _ -> }
        }

        override fun presentAlert(builder: OverlayAlert.Builder) {
            builder.showInDialog(requireDialog())
        }

        override fun onSentenceCardAdded() {
            parentFragmentManager.setFragmentResult(
                AnkiReviewBottomSheet.RESULT_ANKI_ADDED, bundleOf())
        }

        override fun dismiss() = this@WordAnkiReviewSheet.dismiss()
    }

    companion object {
        const val TAG = "WordAnkiReviewSheet"

        fun newInstance(
            word: String,
            reading: String,
            pos: String,
            definition: String,
            screenshotPath: String?,
            freqScore: Int = 0,
            isCommon: Boolean = false,
            sentenceOriginal: String? = null,
            sentenceTranslation: String? = null,
            sentenceWordResults: Map<String, Triple<String, String, Int>>? = null,
            sourceLangId: SourceLangId = SourceLangId.JA,
            sentencePending: com.playtranslate.model.PendingTranslation? = null,
            audioAnchorMs: Long? = null,
        ) = WordAnkiReviewSheet().apply {
            arguments = WordAnkiReviewBinder.buildArgs(
                word, reading, pos, definition, screenshotPath,
                freqScore, isCommon, sentenceOriginal, sentenceTranslation,
                sentenceWordResults, sourceLangId, sentencePending, audioAnchorMs,
            )
        }
    }
}
